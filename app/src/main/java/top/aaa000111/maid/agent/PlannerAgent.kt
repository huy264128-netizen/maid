package top.aaa000111.maid.agent

import top.aaa000111.maid.prompt.PromptResources
import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.skill.withTaskSkills
import top.aaa000111.maid.skill.taskSkillGuidance

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.withContext
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import top.aaa000111.maid.androidtool.ToolsetPermissionToolSet
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.chat.nodeExecuteToolWithStatus
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.settings.AgentStepConfig
import top.aaa000111.maid.settings.AgentStepConfigRepository

/**
 * 复杂任务规划智能体：
 * 1. 本身**不实现** Koog `ToolSet`（由 [PlannerLifecycleManager] 管理生命周期并保存生命周期记忆）；
 * 2. 可将 [DeviceActionAgent] 作为工具调用（执行具体 Android 设备操作）；
 * 3. 在每个检查点内持有 [PlannerControlToolSet]（包含 `setCheckpoints`、`updateLifecycleNote` 与 `finish` 工具），
 *    可通过 `finish(action = "next_checkpoint", ...)` 主动进入下一个检查点，
 *    或通过 `finish(action = "final_exit", ...)` 真正退出规划并返回最终结果；
 * 4. 模型优先使用 [ModelRole.COMPLEX_PLANNING]（回退 [ModelRole.FAST_CHEAP]），
 *    视觉策略与 [DeviceActionAgent] 一致，且不持有 `ChatAgent` 的长期 `AgentMemory`。
 */
class PlannerAgent(
    private val modelRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
    private val modelBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val deviceActionAgent: DeviceActionAgent? = DeviceActionAgent.default,
    private val prootLinuxLifecycleManager: ProotLinuxAgentLifecycleManager? = ProotLinuxAgentLifecycleManager.default,
    private val researchAgent: ResearchAgent? = ResearchAgent.default,
    private val extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val visionToolSet: VisionToolSet? = DeviceActionAgent.defaultVisionToolSet,
    private val directImageInputProvider: (suspend () -> Boolean)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxIterationsPerCheckpoint: Int? = null,
    private val maxIterationsProvider: (suspend () -> Int)? = null,
    private val temperature: Double = DEFAULT_TEMPERATURE,
) {

    val manager: PlannerAgentManager by lazy { PlannerAgentManager(PlannerLifecycleManager(plannerAgent = this)) }

    constructor(
        promptExecutor: PromptExecutor,
        model: LLModel,
        deviceActionAgent: DeviceActionAgent? = null,
        prootLinuxLifecycleManager: ProotLinuxAgentLifecycleManager? = null,
        researchAgent: ResearchAgent? = null,
        extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        visionToolSet: VisionToolSet? = DeviceActionAgent.defaultVisionToolSet,
        directImageInputProvider: (suspend () -> Boolean)? = null,
        imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxIterationsPerCheckpoint: Int? = null,
        maxIterationsProvider: (suspend () -> Int)? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
    ) : this(
        modelRoles = DEFAULT_MODEL_ROLES,
        modelBindingProvider = {
            ChatModelBinding(
                executor = promptExecutor,
                model = model,
                provider = null,
            )
        },
        deviceActionAgent = deviceActionAgent,
        prootLinuxLifecycleManager = prootLinuxLifecycleManager,
        researchAgent = researchAgent,
        extraToolRegistry = extraToolRegistry,
        visionToolSet = visionToolSet,
        directImageInputProvider = directImageInputProvider,
        imageAttachmentResolver = imageAttachmentResolver,
        systemPrompt = systemPrompt,
        maxIterationsPerCheckpoint = maxIterationsPerCheckpoint,
        maxIterationsProvider = maxIterationsProvider,
        temperature = temperature,
    )

    /**
     * 执行单个检查点推理循环，直至规划者调用 `finish` 工具或直接给出最终文本回复。
     */
    suspend fun runCheckpoint(
        promptText: String,
        imageUris: List<String> = emptyList(),
        controlToolSet: PlannerControlToolSet,
        maxCheckpoints: Int? = null,
    ): CheckpointOutcome {
        val binding = modelBindingProvider?.invoke()
            ?: ChatAgent.resolveDefaultModelBinding(modelRoles)

        currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)

        val useDirectImageInput = resolveDirectImageInputMode()

        var effectiveToolRegistry = ToolRegistry {
            tools(controlToolSet)
        }
        val activeSession = controlToolSet.session
        if (activeSession != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ToolRegistry {
                    tools(ToolsetPermissionToolSet(session = activeSession))
                },
            )
        }
        if (deviceActionAgent != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = DeviceActionAgentManager(deviceActionAgent).asToolRegistry(),
            )
        }
        if (prootLinuxLifecycleManager != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ProotLinuxAgentManager(prootLinuxLifecycleManager, activeSession).asToolRegistry(),
            )
        }
        if (researchAgent != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ResearchAgentManager(researchAgent).asToolRegistry(),
            )
        }
        AppRuntime.createBrowserUseAgentManager()?.let { browserManager ->
            effectiveToolRegistry = mergeTools(effectiveToolRegistry, browserManager.asToolRegistry())
        }
        AppRuntime.createScheduledTaskToolSet()?.let { scheduledToolSet ->
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ToolRegistry { tools(scheduledToolSet) },
            )
        }
        AppRuntime.createAlarmClockToolSet()?.let { alarmClockToolSet ->
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ToolRegistry { tools(alarmClockToolSet) },
            )
        }
        if (extraToolRegistry.tools.isNotEmpty()) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = extraToolRegistry,
            )
        }
        val directImageToolSet = if (useDirectImageInput) {
            DirectImageReadToolSet(
                imageAttachmentResolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment,
            )
        } else {
            null
        }
        if (directImageToolSet != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ToolRegistry { tools(directImageToolSet) },
            )
        } else if (visionToolSet != null) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = ToolRegistry { tools(visionToolSet) },
            )
        }

        val (formattedPrompt, directImageAttachments) = prepareCheckpointPromptPayload(
            promptText = promptText,
            imageUris = imageUris,
            useDirectImageInput = useDirectImageInput,
            session = activeSession,
        )

        val currentPrivileges: Set<AndroidPrivilege> = runCatching {
            activeSession?.checkCurrentPrivileges()
                ?: ToolsetPermissionCatalog.checkCurrentPrivileges()
        }.getOrDefault(emptySet())

        val configuredModelMap: Map<ModelRole, RoleModelSelection> = runCatching {
            if (ModelConfigRepository.isReady) {
                ModelConfigRepository.getAllConfigs()
            } else {
                emptyMap()
            }
        }.getOrDefault(emptyMap())

        val allowProotLlmApiKeyAccess = prootLinuxLifecycleManager?.let { manager ->
            runCatching { manager.isLocalLlmApiKeyAccessAllowed() }.getOrDefault(false)
        } ?: false

        val resolvedMaxIterations = resolveMaxIterationsPerCheckpoint()
        val resolvedMaxCheckpoints = resolveMaxCheckpoints(maxCheckpoints)

        val effectiveSystemPrompt = buildEffectiveSystemPrompt(
            baseSystemPrompt = systemPrompt + taskSkillGuidance(includeCatalog = true),
            hasDeviceActionTool = deviceActionAgent != null,
            hasProotLinuxTool = prootLinuxLifecycleManager != null,
            allowProotLlmApiKeyAccess = allowProotLlmApiKeyAccess,
            hasResearchTool = researchAgent != null,
            hasVisionTool = !useDirectImageInput && visionToolSet != null,
            hasDirectImageReadTool = directImageToolSet != null,
            configuredModels = configuredModelMap,
            currentBinding = binding,
            availablePrivileges = currentPrivileges,
            maxIterationsPerCheckpoint = resolvedMaxIterations,
            maxCheckpoints = resolvedMaxCheckpoints,
        )

        val initialPrompt = prompt(
            id = PROMPT_ID,
            params = LLMParams(temperature = temperature),
        ) {
            system(effectiveSystemPrompt)
        }

        val agentConfig = AIAgentConfig(
            prompt = initialPrompt,
            model = binding.model,
            maxAgentIterations = resolvedMaxIterations,
        )

        val strategy = createStrategy(
            controlToolSet = controlToolSet,
            directImageAttachments = directImageAttachments,
            directImageToolSet = directImageToolSet,
        )

        val agent = AIAgent(
            promptExecutor = binding.executor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = withTaskSkills(effectiveToolRegistry),
            id = AGENT_NAME,
        )

        val reply = try {
            if (activeSession != null) {
                withContext(ChatSessionContext(activeSession)) {
                    agent.run(formattedPrompt)
                }
            } else {
                agent.run(formattedPrompt)
            }
        } finally {
            runCatching { agent.close() }
        }

        return controlToolSet.currentOutcome()
            ?: CheckpointOutcome.FinalExit(content = reply.trim())
    }

    private suspend fun resolveDirectImageInputMode(): Boolean {
        if (directImageInputProvider != null) {
            return directImageInputProvider.invoke()
        }
        return DeviceActionAgent.isComplexPlanningSameAsComplexVision()
    }

    private suspend fun prepareCheckpointPromptPayload(
        promptText: String,
        imageUris: List<String>,
        useDirectImageInput: Boolean,
        session: top.aaa000111.maid.chat.ChatSession? = null,
    ): Pair<String, List<Attachment.Image>> {
        val normalizedImages = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (!useDirectImageInput || normalizedImages.isEmpty()) {
            val formatted = buildFormattedCheckpointPrompt(
                promptText = promptText,
                imageUrisForTool = normalizedImages,
                unresolvedImageUris = emptyList(),
                hasDirectImages = false,
            )
            return formatted to emptyList()
        }

        val resolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment
        val resolvedAttachments = mutableListOf<Attachment.Image>()
        val unresolvedUris = mutableListOf<String>()
        for (uri in normalizedImages) {
            val accessedUri = session?.accessAttachmentUri(uri) ?: uri
            val attachment = runCatching { resolver(accessedUri) }.getOrNull()
            if (attachment != null) {
                resolvedAttachments += attachment
            } else {
                unresolvedUris += uri
            }
        }

        val formatted = buildFormattedCheckpointPrompt(
            promptText = promptText,
            imageUrisForTool = emptyList(),
            unresolvedImageUris = unresolvedUris,
            hasDirectImages = resolvedAttachments.isNotEmpty(),
        )
        return formatted to resolvedAttachments
    }

    private suspend fun resolveMaxIterationsPerCheckpoint(): Int {
        maxIterationsProvider?.let { return it.invoke().coerceAtLeast(1) }
        maxIterationsPerCheckpoint?.let { return it.coerceAtLeast(1) }
        return if (AgentStepConfigRepository.isReady) {
            AgentStepConfigRepository.getStepConfig().plannerMaxIterationsPerCheckpoint
        } else {
            DEFAULT_MAX_ITERATIONS_PER_CHECKPOINT
        }
    }

    private suspend fun resolveMaxCheckpoints(override: Int?): Int {
        override?.let { return it.coerceAtLeast(1) }
        return if (AgentStepConfigRepository.isReady) {
            AgentStepConfigRepository.getStepConfig().plannerMaxCheckpoints
        } else {
            AgentStepConfig.DEFAULT_PLANNER_MAX_CHECKPOINTS
        }
    }

    companion object {
        const val AGENT_NAME = "planner_agent"
        const val PROMPT_ID = "planner_agent_prompt"
        const val DEFAULT_MAX_ITERATIONS_PER_CHECKPOINT =
            AgentStepConfig.DEFAULT_PLANNER_MAX_ITERATIONS_PER_CHECKPOINT
        const val DEFAULT_TEMPERATURE = 0.3

        val DEFAULT_MODEL_ROLES: List<ModelRole> = listOf(
            ModelRole.COMPLEX_PLANNING,
            ModelRole.FAST_CHEAP,
        )

        val DEFAULT_SYSTEM_PROMPT: String
            get() = PromptResources.read("planner/system.md")

        private fun mergeTools(
            base: ToolRegistry,
            extra: ToolRegistry,
        ): ToolRegistry {
            val existingNames = base.tools.mapTo(HashSet()) { it.name }
            val additionalTools = extra.tools.filter { it.name !in existingNames }
            return if (additionalTools.isEmpty()) {
                base
            } else {
                base + ToolRegistry { tools(additionalTools) }
            }
        }

        internal fun buildEffectiveSystemPrompt(
            baseSystemPrompt: String,
            hasDeviceActionTool: Boolean,
            hasVisionTool: Boolean,
            hasDirectImageReadTool: Boolean = false,
            configuredModels: Map<ModelRole, RoleModelSelection> = emptyMap(),
            currentBinding: ChatModelBinding? = null,
            availablePrivileges: Set<AndroidPrivilege> = emptySet(),
            hasProotLinuxTool: Boolean = false,
            allowProotLlmApiKeyAccess: Boolean = false,
            hasResearchTool: Boolean = false,
            maxIterationsPerCheckpoint: Int = DEFAULT_MAX_ITERATIONS_PER_CHECKPOINT,
            maxCheckpoints: Int = AgentStepConfig.DEFAULT_PLANNER_MAX_CHECKPOINTS,
        ): String = buildString {
            appendLine(baseSystemPrompt.trim())

            val safeMaxIterations = maxIterationsPerCheckpoint.coerceAtLeast(1)
            val estimatedToolCalls = AgentStepConfig.estimatePlannerToolCallsPerCheckpoint(safeMaxIterations)
            val safeMaxCheckpoints = maxCheckpoints.coerceAtLeast(1)
            appendLine()
            appendLine("[当前执行步数限制]")
            appendLine("- 单个检查点最大执行步数上限：$safeMaxIterations 步（约支持 $estimatedToolCalls 次工具调用）；")
            appendLine("- 单次规划任务最大检查点上限：$safeMaxCheckpoints 个。")
            appendLine(PromptResources.read("planner/step_budget.md"))

            // 告知当前规划智能体持有的模型配置与各角色模型分布
            appendLine()
            appendLine("[当前已配置的模型与运行时角色能力说明]")
            if (currentBinding != null) {
                appendLine("- 当前规划者执行模型：${currentBinding.model.id}（服务商：${currentBinding.provider?.name ?: "系统默认"}）")
            }
            if (configuredModels.isNotEmpty()) {
                appendLine("应用已在设置中配置的能力角色模型列表：")
                ModelRole.entries.forEach { role ->
                    val selection = configuredModels[role]
                    val statusText = if (selection?.isComplete == true) {
                        "${selection.provider?.name} -> ${selection.normalizedModelId}"
                    } else {
                        "未配置"
                    }
                    val roleLabel = when (role) {
                        ModelRole.FAST_CHEAP -> "快速廉价应答模型 (FAST_CHEAP)"
                        ModelRole.COMPLEX_PLANNING -> "复杂规划模型 (COMPLEX_PLANNING)"
                        ModelRole.FAST_VISION -> "快速视觉模型 (FAST_VISION)"
                        ModelRole.COMPLEX_VISION -> "复杂视觉模型 (COMPLEX_VISION)"
                    }
                    appendLine("- $roleLabel：$statusText")
                }
            } else if (currentBinding != null) {
                appendLine("- 运行时规划模型已就绪：${currentBinding.model.id}")
            } else {
                appendLine("- 当前环境使用系统默认规划模型")
            }

            if (hasDeviceActionTool) {
                appendLine()
                append(PromptResources.read("planner/delegate_device.md"))
            }
            if (hasProotLinuxTool) {
                appendLine()
                append(PromptResources.read("planner/delegate_linux.md"))
                appendLine()
                if (allowProotLlmApiKeyAccess) {
                    append(PromptResources.read("planner/cloud_api_allowed.md"))
                } else {
                    append(PromptResources.read("planner/cloud_api_disabled.md"))
                }
            }
            if (hasResearchTool) {
                appendLine()
                append(PromptResources.read("planner/delegate_research.md"))
            }
            if (hasDeviceActionTool || hasProotLinuxTool || hasResearchTool) {
                appendLine()
                append(PromptResources.read("planner/async_tasks.md"))
            }
            if (hasDirectImageReadTool) {
                appendLine()
                append(PromptResources.read("planner/direct_images.md"))
            } else if (hasVisionTool) {
                appendLine()
                append(PromptResources.read("planner/vision_tool.md"))
            }
            appendLine()
            appendLine()
            append(ToolsetPermissionCatalog.buildPromptGuidance(availablePrivileges))
        }.trimEnd()

        internal fun buildFormattedCheckpointPrompt(
            promptText: String,
            imageUrisForTool: List<String>,
            unresolvedImageUris: List<String>,
            hasDirectImages: Boolean,
        ): String = buildString {
            append(promptText.trimEnd())
            if (hasDirectImages) {
                appendLine()
                appendLine()
                append("[已附带图片输入供直接参考]")
            }
            if (imageUrisForTool.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[附带图片 URI（可调用 analyzeImage 工具查看内容）]")
                imageUrisForTool.forEach { uri -> appendLine("- $uri") }
            }
            if (unresolvedImageUris.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[未能直接读取的图片 URI]")
                unresolvedImageUris.forEach { uri -> appendLine("- $uri") }
            }
        }.trimEnd()

        internal fun createStrategy(
            controlToolSet: PlannerControlToolSet,
            directImageAttachments: List<Attachment.Image> = emptyList(),
            directImageToolSet: DirectImageReadToolSet? = null,
        ): AIAgentGraphStrategy<String, String> = strategy(AGENT_NAME) {
            val nodeCallLLM by if (directImageAttachments.isEmpty()) {
                nodeLLMRequest(
                    name = "planner_llm_request",
                    allowToolCalls = true,
                )
            } else {
                node<String, Message.Response>(name = "planner_llm_request") { input ->
                    llm.writeSession {
                        updatePrompt {
                            user(content = input, attachments = directImageAttachments)
                        }
                        requestLLM()
                    }
                }
            }

            val nodeExecuteTool by nodeExecuteToolWithStatus(
                name = "planner_execute_tool",
                agentScopeLabel = "规划",
                sessionProvider = { controlToolSet.session },
            )
            val nodeSendToolResult by nodeLLMSendToolResultWithDirectImages(
                name = "planner_send_tool_result",
                directImageToolSet = directImageToolSet,
                allowToolCalls = true,
            )

            edge(nodeStart forwardTo nodeCallLLM)
            edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
            edge(
                nodeExecuteTool forwardTo nodeFinish
                    onCondition { controlToolSet.hasOutcome() }
                    transformed { controlToolSet.currentOutcomeSummary() },
            )
            edge(
                nodeExecuteTool forwardTo nodeSendToolResult
                    onCondition { !controlToolSet.hasOutcome() },
            )
            edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
        }
    }
}
