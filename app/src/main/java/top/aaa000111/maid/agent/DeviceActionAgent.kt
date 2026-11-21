package top.aaa000111.maid.agent

import top.aaa000111.maid.prompt.PromptResources
import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.skill.withTaskSkills
import top.aaa000111.maid.skill.taskSkillGuidance

import android.content.Context
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.environment.ReceivedToolResult
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import kotlinx.coroutines.CancellationException
import top.aaa000111.maid.androidtool.AndroidToolsetDispatcher
import top.aaa000111.maid.androidtool.DefaultAndroidToolSets
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import top.aaa000111.maid.chat.ToolCallDisplayFormatter
import top.aaa000111.maid.chat.currentChatSession
import top.aaa000111.maid.chat.nodeExecuteToolWithStatus
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.AgentStepConfig
import top.aaa000111.maid.settings.AgentStepConfigRepository
import top.aaa000111.maid.settings.ModelSafetyConfig
import top.aaa000111.maid.settings.ModelSafetyRepository

/**
 * [DeviceActionAgent] 单次设备自动化任务执行结果。
 */
data class DeviceActionResult(
    val success: Boolean,
    val summary: String,
    val provider: LlmProvider? = null,
    val modelId: String = "",
    val executedTools: List<String> = emptyList(),
    val blockedActions: List<String> = emptyList(),
)

/**
 * Android 设备操作执行 Agent：
 * 1. 默认持有由 [AndroidToolsetDispatcher] 按当前权限动态分发注册的 `androidtool` 工具集；
 * 2. 本身也实现 Koog [ToolSet]，可直接作为单个高层次设备任务工具供其他 Agent 调用；
 * 3. 模型绑定优先使用 [ModelRole.COMPLEX_PLANNING]（不可用时回退至 [ModelRole.FAST_CHEAP]）；
 * 4. 视觉处理与 [ChatAgent] 保持一致：当 `COMPLEX_PLANNING` 与 `COMPLEX_VISION` 为同一模型时
 *    跳过 `VisionToolSet` 并直接向模型传入图片附件，否则挂载绑定 `COMPLEX_VISION` 的 `VisionToolSet`；
 * 5. 根据 [ModelSafetyConfig] 过滤并实时拦截敏感操作，不持有对话记忆（由 [ChatAgent] 独占）。
 */
class DeviceActionAgent(
    private val modelRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
    private val modelBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val dispatcherProvider: (() -> AndroidToolsetDispatcher?)? = null,
    private val extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val visionToolSet: VisionToolSet? = defaultVisionToolSet,
    private val directImageInputProvider: (suspend () -> Boolean)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
    private val safetyConfigProvider: (suspend () -> ModelSafetyConfig)? = null,
    private val sensitiveActionAuthorizer: (suspend (toolName: String, argumentsJson: String, blockReason: String) -> Boolean)? = null,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxIterations: Int? = null,
    private val maxIterationsProvider: (suspend () -> Int)? = null,
    private val temperature: Double = DEFAULT_TEMPERATURE,
) : ToolSet {

    constructor(
        promptExecutor: PromptExecutor,
        model: LLModel,
        provider: LlmProvider? = null,
        dispatcher: AndroidToolsetDispatcher? = null,
        extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        visionToolSet: VisionToolSet? = defaultVisionToolSet,
        directImageInputProvider: (suspend () -> Boolean)? = null,
        imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
        safetyConfigProvider: (suspend () -> ModelSafetyConfig)? = null,
        sensitiveActionAuthorizer: (suspend (toolName: String, argumentsJson: String, blockReason: String) -> Boolean)? = null,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        maxIterations: Int? = null,
        maxIterationsProvider: (suspend () -> Int)? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
    ) : this(
        modelRoles = DEFAULT_MODEL_ROLES,
        modelBindingProvider = {
            ChatModelBinding(
                executor = promptExecutor,
                model = model,
                provider = provider,
            )
        },
        dispatcherProvider = { dispatcher },
        extraToolRegistry = extraToolRegistry,
        visionToolSet = visionToolSet,
        directImageInputProvider = directImageInputProvider,
        imageAttachmentResolver = imageAttachmentResolver,
        safetyConfigProvider = safetyConfigProvider,
        sensitiveActionAuthorizer = sensitiveActionAuthorizer,
        systemPrompt = systemPrompt,
        maxIterations = maxIterations,
        maxIterationsProvider = maxIterationsProvider,
        temperature = temperature,
    )

    val manager: DeviceActionAgentManager by lazy { DeviceActionAgentManager(this) }

    override val name: String = TOOLSET_NAME

    /**
     * 将当前 [DeviceActionAgent] 暴露为一个 Koog [Tool]，供上层 Agent 委派多步 Android 设备任务。
     */
    @Tool
    @LLMDescription(
        "Delegate an Android device automation or system control task to DeviceActionAgent. " +
            "It inspects the screen/UI tree, operates granted Android tools (Accessibility, Shizuku, Dhizuku, Root) " +
            "within configured model safety boundaries, and returns a concise execution summary.",
    )
    suspend fun executeDeviceTask(
        @LLMDescription("Clear description of the Android device operation or goal to accomplish.")
        task: String,
        @LLMDescription("Optional extra context, constraints, or hints about the target app or current screen.")
        context: String = "",
        @LLMDescription("Optional image URIs (screenshots or reference images) relevant to the task.")
        imageUris: List<String> = emptyList(),
    ): String {
        val result = runCatching {
            manager.execute(task = task, context = context, imageUris = imageUris)
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return "DeviceActionAgent execution failed: ${error.message ?: "unknown error"}"
        }

        return formatToolResponse(result)
    }

    /**
     * 直接执行一次 Android 设备任务并返回结构化结果。
     */
    suspend fun execute(
        task: String,
        context: String = "",
        imageUris: List<String> = emptyList(),
    ): DeviceActionResult {
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty()) { "Device action task must not be blank" }

        val safetyConfig = resolveSafetyConfig()
        val dispatcher = resolveDispatcher()
        val dispatchedRegistry = dispatcher?.dispatchAsync() ?: ToolRegistry.EMPTY
        val deviceBaseRegistry = mergeTools(
            base = dispatchedRegistry,
            extra = extraToolRegistry,
        )
        val filteredDeviceRegistry = filterRegistryBySafety(
            registry = deviceBaseRegistry,
            safetyConfig = safetyConfig,
        )

        if (deviceBaseRegistry.tools.isEmpty()) {
            return DeviceActionResult(
                success = false,
                summary = "当前未获取任何 Android 自动化或系统权限（无障碍 / Shizuku / Dhizuku / Root），" +
                    "请调用 requestToolsetPermission 工具传入所需工具集名称（input / screen / shell / app_control / window / root_system / device_policy）向用户请求权限，" +
                    "或引导用户前往「设置 -> 权限管理与提权」开启对应权限。",
            )
        }

        if (filteredDeviceRegistry.tools.isEmpty()) {
            return DeviceActionResult(
                success = false,
                summary = "当前可用工具均受「模型安全性」策略限制而被禁用，请在「设置 -> 模型安全性」调整授权后重试。",
            )
        }

        val binding = modelBindingProvider?.invoke()
            ?: ChatAgent.resolveDefaultModelBinding(modelRoles)

        currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)

        val useDirectImageInput = resolveDirectImageInputMode()
        val directImageToolSet = if (useDirectImageInput) {
            DirectImageReadToolSet(
                imageAttachmentResolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment,
            )
        } else {
            null
        }
        val effectiveToolRegistry = when {
            directImageToolSet != null -> mergeTools(
                base = filteredDeviceRegistry,
                extra = ToolRegistry { tools(directImageToolSet) },
            )
            visionToolSet != null -> mergeTools(
                base = filteredDeviceRegistry,
                extra = ToolRegistry { tools(visionToolSet) },
            )
            else -> filteredDeviceRegistry
        }

        val (formattedTaskPrompt, directImageAttachments) = prepareTaskPromptPayload(
            task = normalizedTask,
            context = context.trim(),
            imageUris = imageUris,
            useDirectImageInput = useDirectImageInput,
        )

        val resolvedMaxIterations = resolveMaxIterations()
        val effectiveSystemPrompt = buildEffectiveSystemPrompt(
            baseSystemPrompt = systemPrompt + taskSkillGuidance(),
            safetyConfig = safetyConfig,
            hasVisionTool = !useDirectImageInput && visionToolSet != null,
            hasDirectImageReadTool = directImageToolSet != null,
            maxIterations = resolvedMaxIterations,
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

        val executedTools = mutableListOf<String>()
        val blockedActions = mutableListOf<String>()

        val strategy = createStrategy(
            allowToolCalls = effectiveToolRegistry.tools.isNotEmpty(),
            directImageAttachments = directImageAttachments,
            directImageToolSet = directImageToolSet,
            safetyEvaluator = { call ->
                val blockReason = safetyConfig.evaluateToolCall(call.tool, call.content)
                if (blockReason == null) {
                    executedTools += call.tool
                    null
                } else {
                    val approved = sensitiveActionAuthorizer?.invoke(
                        call.tool,
                        call.content,
                        blockReason,
                    ) ?: false
                    if (approved) {
                        executedTools += call.tool
                        null
                    } else {
                        blockedActions += "${call.tool}: $blockReason"
                        blockReason
                    }
                }
            },
        )

        val agent = AIAgent(
            promptExecutor = binding.executor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = withTaskSkills(effectiveToolRegistry),
            id = AGENT_NAME,
        )

        val reply = try {
            agent.run(formattedTaskPrompt)
        } finally {
            runCatching { agent.close() }
        }

        return DeviceActionResult(
            success = true,
            summary = reply,
            provider = binding.provider,
            modelId = binding.model.id,
            executedTools = executedTools.toList(),
            blockedActions = blockedActions.toList(),
        )
    }

    /**
     * 将本 Agent 作为独立工具集打包为 Koog [ToolRegistry]。
     */
    fun asToolRegistry(): ToolRegistry = DeviceActionAgentManager(this).asToolRegistry()

    private fun resolveDispatcher(): AndroidToolsetDispatcher? {
        if (dispatcherProvider != null) {
            return dispatcherProvider.invoke()
        }
        return defaultDispatcher
    }

    private suspend fun resolveSafetyConfig(): ModelSafetyConfig {
        if (safetyConfigProvider != null) {
            return safetyConfigProvider.invoke()
        }
        return if (ModelSafetyRepository.isReady) {
            ModelSafetyRepository.getSafetyConfig()
        } else {
            ModelSafetyConfig.DEFAULT
        }
    }

    private suspend fun resolveDirectImageInputMode(): Boolean {
        if (directImageInputProvider != null) {
            return directImageInputProvider.invoke()
        }
        return isComplexPlanningSameAsComplexVision()
    }

    private suspend fun prepareTaskPromptPayload(
        task: String,
        context: String,
        imageUris: List<String>,
        useDirectImageInput: Boolean,
    ): Pair<String, List<Attachment.Image>> {
        val normalizedImages = imageUris.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (!useDirectImageInput || normalizedImages.isEmpty()) {
            val text = buildFormattedTaskPrompt(
                task = task,
                context = context,
                imageUrisForTool = normalizedImages,
                unresolvedImageUris = emptyList(),
                hasDirectImages = false,
            )
            return text to emptyList()
        }

        val resolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment
        val activeSession = currentChatSession()
        val resolvedAttachments = mutableListOf<Attachment.Image>()
        val unresolvedUris = mutableListOf<String>()
        for (uri in normalizedImages) {
            val accessedUri = activeSession?.accessAttachmentUri(uri) ?: uri
            val attachment = runCatching { resolver(accessedUri) }.getOrNull()
            if (attachment != null) {
                resolvedAttachments += attachment
            } else {
                unresolvedUris += uri
            }
        }

        val text = buildFormattedTaskPrompt(
            task = task,
            context = context,
            imageUrisForTool = emptyList(),
            unresolvedImageUris = unresolvedUris,
            hasDirectImages = resolvedAttachments.isNotEmpty(),
        )
        return text to resolvedAttachments
    }

    private suspend fun resolveMaxIterations(): Int {
        maxIterationsProvider?.let { return it.invoke().coerceAtLeast(1) }
        maxIterations?.let { return it.coerceAtLeast(1) }
        return if (AgentStepConfigRepository.isReady) {
            AgentStepConfigRepository.getStepConfig().deviceActionMaxIterations
        } else {
            DEFAULT_MAX_ITERATIONS
        }
    }

    companion object {
        const val AGENT_NAME = "device_action_agent"
        val default: DeviceActionAgent by lazy { DeviceActionAgent() }

        const val TOOLSET_NAME = "device_action"
        const val PROMPT_ID = "device_action_agent_prompt"
        const val DEFAULT_MAX_ITERATIONS = AgentStepConfig.DEFAULT_DEVICE_ACTION_MAX_ITERATIONS
        const val DEFAULT_TEMPERATURE = 0.2

        val DEFAULT_MODEL_ROLES: List<ModelRole> = listOf(
            ModelRole.COMPLEX_PLANNING,
            ModelRole.FAST_CHEAP,
        )

        val DEFAULT_VISION_ROLES: List<ModelRole> = listOf(
            ModelRole.COMPLEX_VISION,
            ModelRole.FAST_VISION,
        )

        val defaultVisionToolSet: VisionToolSet = VisionToolSet(
            visionBindingProvider = {
                ChatAgent.resolveDefaultModelBinding(DEFAULT_VISION_ROLES)
            },
        )

        val DEFAULT_SYSTEM_PROMPT: String
            get() = PromptResources.read("device/system.md")

        @Volatile
        private var defaultDispatcher: AndroidToolsetDispatcher? = null

        fun init(context: Context) {
            if (defaultDispatcher != null) return
            synchronized(this) {
                if (defaultDispatcher == null) {
                    defaultDispatcher = DefaultAndroidToolSets.createDispatcher(
                        context = context.applicationContext,
                        checkRoot = true,
                    )
                }
            }
        }

        internal fun resetForTest() {
            synchronized(this) {
                defaultDispatcher = null
            }
        }

        internal fun resolveEffectiveSelection(
            configs: Map<ModelRole, RoleModelSelection>,
            preferredRoles: List<ModelRole>,
        ): RoleModelSelection? {
            for (role in preferredRoles) {
                val candidate = configs[role]?.takeIf { it.isComplete }
                if (candidate != null) return candidate
            }
            return null
        }

        internal suspend fun isComplexPlanningSameAsComplexVision(): Boolean {
            if (!ModelConfigRepository.isReady) return false
            val configs = ModelConfigRepository.getAllConfigs()
            val complexPlanning = configs[ModelRole.COMPLEX_PLANNING]
            val complexVision = configs[ModelRole.COMPLEX_VISION]
            if (ChatAgent.isSameModelSelection(complexPlanning, complexVision)) {
                return true
            }
            val effectiveExecution = resolveEffectiveSelection(configs, DEFAULT_MODEL_ROLES)
            val effectiveVision = resolveEffectiveSelection(configs, DEFAULT_VISION_ROLES)
            return ChatAgent.isSameModelSelection(effectiveExecution, effectiveVision)
        }

        internal fun filterRegistryBySafety(
            registry: ToolRegistry,
            safetyConfig: ModelSafetyConfig,
        ): ToolRegistry {
            val allowedTools = registry.tools.filter { tool ->
                safetyConfig.isToolAllowed(tool.name)
            }
            return if (allowedTools.isEmpty()) {
                ToolRegistry.EMPTY
            } else if (allowedTools.size == registry.tools.size) {
                registry
            } else {
                ToolRegistry {
                    tools(allowedTools)
                }
            }
        }

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

        internal fun buildFormattedTaskPrompt(
            task: String,
            context: String,
            imageUrisForTool: List<String>,
            unresolvedImageUris: List<String>,
            hasDirectImages: Boolean,
        ): String = buildString {
            appendLine("[设备执行任务]")
            append(task)
            if (context.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[补充上下文]")
                append(context)
            }
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

        internal fun buildEffectiveSystemPrompt(
            baseSystemPrompt: String,
            safetyConfig: ModelSafetyConfig,
            hasVisionTool: Boolean,
            hasDirectImageReadTool: Boolean = false,
            maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        ): String {
            val safeMaxIterations = maxIterations.coerceAtLeast(1)
            val estimatedToolCalls = AgentStepConfig.estimateDeviceActionToolCalls(safeMaxIterations)
            val stepLimitGuidance = buildString {
                appendLine("[当前执行步数限制]")
                append("当前设备操作任务的最大执行步数上限为 $safeMaxIterations 步（约支持 $estimatedToolCalls 次工具调用）。")
                append(PromptResources.read("device/step_budget.md"))
            }
            val visionGuidance = when {
                hasDirectImageReadTool ->
                    PromptResources.read("device/direct_images.md")
                hasVisionTool ->
                    PromptResources.read("device/vision_tool.md")
                else ->
                    PromptResources.read("device/screenshot_fallback.md")
            }
            return buildString {
                appendLine(baseSystemPrompt.trim())
                appendLine()
                appendLine(stepLimitGuidance)
                appendLine()
                appendLine(visionGuidance)
                appendLine()
                appendLine(ToolsetPermissionCatalog.buildPromptGuidance())
                appendLine()
                append(safetyConfig.toPolicyPromptSection())
            }.trimEnd()
        }

        internal fun formatToolResponse(result: DeviceActionResult): String = buildString {
            appendLine("success=${result.success}")
            if (result.modelId.isNotBlank()) {
                appendLine("model=${result.provider?.name ?: "custom"}/${result.modelId}")
            }
            if (result.executedTools.isNotEmpty()) {
                appendLine("executedTools=${result.executedTools.joinToString(",")}")
            }
            if (result.blockedActions.isNotEmpty()) {
                appendLine("blockedActions=${result.blockedActions.joinToString(" | ")}")
            }
            appendLine("summary:")
            append(result.summary.trim())
        }.trimEnd()

        private sealed interface SafetyGateDecision {
            data class Allowed(val call: Message.Tool.Call) : SafetyGateDecision
            data class Blocked(val result: ReceivedToolResult) : SafetyGateDecision
        }

        internal fun createStrategy(
            allowToolCalls: Boolean,
            directImageAttachments: List<Attachment.Image> = emptyList(),
            directImageToolSet: DirectImageReadToolSet? = null,
            safetyEvaluator: suspend (Message.Tool.Call) -> String?,
        ): AIAgentGraphStrategy<String, String> = strategy(AGENT_NAME) {
            val nodeCallLLM by if (directImageAttachments.isEmpty()) {
                nodeLLMRequest(
                    name = "device_llm_request",
                    allowToolCalls = allowToolCalls,
                )
            } else {
                node<String, Message.Response>(name = "device_llm_request") { input ->
                    llm.writeSession {
                        updatePrompt {
                            user(content = input, attachments = directImageAttachments)
                        }
                        if (allowToolCalls) {
                            requestLLM()
                        } else {
                            requestLLMWithoutTools()
                        }
                    }
                }
            }

            val nodeSafetyGate by node<Message.Tool.Call, SafetyGateDecision>(
                name = "device_tool_safety_gate",
            ) { call ->
                val blockReason = safetyEvaluator(call)
                if (blockReason == null) {
                    SafetyGateDecision.Allowed(call)
                } else {
                    val session = currentChatSession()
                    if (session != null) {
                        val cardTitle = ToolCallDisplayFormatter.formatCardTitle(
                            toolName = call.tool,
                            agentScopeLabel = "设备执行",
                        )
                        val argsSummary = ToolCallDisplayFormatter.formatArgumentsSummary(call.content)
                        session.appendToolStatus(
                            title = cardTitle,
                            detail = ToolCallDisplayFormatter.formatCompletedDetail(
                                argsSummary = argsSummary,
                                rawResultText = blockReason,
                            ),
                            isCompleted = true,
                        )
                    }
                    SafetyGateDecision.Blocked(
                        ReceivedToolResult(
                            id = call.id,
                            tool = call.tool,
                            content = blockReason,
                            result = blockReason,
                        ),
                    )
                }
            }

            val nodeExecuteTool by nodeExecuteToolWithStatus(
                name = "device_execute_tool",
                agentScopeLabel = "设备执行",
            )
            val nodeSendToolResult by nodeLLMSendToolResultWithDirectImages(
                name = "device_send_tool_result",
                directImageToolSet = directImageToolSet,
                allowToolCalls = allowToolCalls,
            )

            if (allowToolCalls) {
                edge(nodeStart forwardTo nodeCallLLM)
                edge(nodeCallLLM forwardTo nodeSafetyGate onToolCall { true })
                edge(
                    nodeSafetyGate forwardTo nodeExecuteTool
                        onCondition { it is SafetyGateDecision.Allowed }
                        transformed { (it as SafetyGateDecision.Allowed).call },
                )
                edge(
                    nodeSafetyGate forwardTo nodeSendToolResult
                        onCondition { it is SafetyGateDecision.Blocked }
                        transformed { (it as SafetyGateDecision.Blocked).result },
                )
                edge(nodeExecuteTool forwardTo nodeSendToolResult)
                edge(nodeSendToolResult forwardTo nodeSafetyGate onToolCall { true })
                edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
                edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
            } else {
                edge(nodeStart forwardTo nodeCallLLM)
                edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
            }
        }
    }
}
