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
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.chat.SessionAttachment
import top.aaa000111.maid.chat.nodeExecuteToolWithStatus
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.settings.AgentStepConfig
import top.aaa000111.maid.settings.AgentStepConfigRepository

/**
 * [ProotLinuxAgent] 单次 PRoot Linux 任务执行结果。
 */
data class ProotLinuxExecutionResult(
    val success: Boolean,
    val summary: String,
    val provider: LlmProvider? = null,
    val modelId: String = "",
    val sessionId: String? = null,
    val suggestedWorkingDir: String = ProotLinuxAgentLifecycleManager.DEFAULT_PROOT_HOME,
    val executedTools: List<String> = emptyList(),
    val terminalCommandCount: Int = 0,
    val injectedLlmEnvVars: List<String> = emptyList(),
)

/**
 * 操作 PRoot Linux 容器的专用智能体：
 * 1. 本身**不直接持有**底层 PRoot 进程生命周期，也不直接实现 `ToolSet`——其生命周期与专用 PRoot 终端
 *    由 [ProotLinuxAgentLifecycleManager] 统一创建、复用与销毁；
 * 2. 执行时挂载由同一 [ProotLinuxAgentLifecycleManager] 管理的专用 PRoot 终端工具集
 *    （[ProotLinuxTerminalToolSet]）与文件输入/输出工具集（[ProotLinuxFileIoToolSet]）；
 * 3. 在系统提示与任务上下文中**建议**以 `~/<session id>/`（即 `/root/<session id>/`）作为工作目录，
 *    但**不强制约束**；不同会话之间没有强制隔离，允许模型根据需要改造 PRoot 容器本身（安装依赖、
 *    修改系统配置、部署脚本与工具等）；
 * 4. 模型绑定优先使用 [ModelRole.COMPLEX_PLANNING]（回退 [ModelRole.FAST_CHEAP]），
 *    并通过 [ProotLinuxAgentManager] 作为高层次工具暴露给 [PlannerAgent]。
 */
class ProotLinuxAgent(
    private val modelRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
    private val modelBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val visionToolSet: VisionToolSet? = DeviceActionAgent.defaultVisionToolSet,
    private val directImageInputProvider: (suspend () -> Boolean)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val maxIterations: Int? = null,
    private val maxIterationsProvider: (suspend () -> Int)? = null,
    private val temperature: Double = DEFAULT_TEMPERATURE,
) {

    constructor(
        promptExecutor: PromptExecutor,
        model: LLModel,
        provider: LlmProvider? = null,
        extraToolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        visionToolSet: VisionToolSet? = DeviceActionAgent.defaultVisionToolSet,
        directImageInputProvider: (suspend () -> Boolean)? = null,
        imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
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
        extraToolRegistry = extraToolRegistry,
        visionToolSet = visionToolSet,
        directImageInputProvider = directImageInputProvider,
        imageAttachmentResolver = imageAttachmentResolver,
        systemPrompt = systemPrompt,
        maxIterations = maxIterations,
        maxIterationsProvider = maxIterationsProvider,
        temperature = temperature,
    )

    /**
     * 在由 [ProotLinuxAgentLifecycleManager] 注入的专用 PRoot 终端与文件 I/O 工具集上执行一次 Linux 任务。
     */
    suspend fun execute(
        task: String,
        context: String = "",
        session: ChatSession? = null,
        sessionId: String? = session?.id,
        suggestedWorkingDirDisplay: String,
        suggestedWorkingDirPosix: String,
        terminalToolSet: ProotLinuxTerminalToolSet,
        fileIoToolSet: ProotLinuxFileIoToolSet,
        fileAttachmentIds: List<String> = emptyList(),
        imageUris: List<String> = emptyList(),
        llmApiEnvPromptSection: String? = null,
        injectedLlmEnvVars: List<String> = emptyList(),
    ): ProotLinuxExecutionResult {
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty()) { "PRoot Linux task must not be blank" }

        val binding = modelBindingProvider?.invoke()
            ?: ChatAgent.resolveDefaultModelBinding(modelRoles)

        currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)

        val useDirectImageInput = resolveDirectImageInputMode()

        var effectiveToolRegistry = ToolRegistry {
            tools(terminalToolSet)
            tools(fileIoToolSet)
        }
        if (extraToolRegistry.tools.isNotEmpty()) {
            effectiveToolRegistry = mergeTools(
                base = effectiveToolRegistry,
                extra = extraToolRegistry,
            )
        }
        val directImageToolSet = if (useDirectImageInput) {
            val resolver: suspend (String) -> Attachment.Image? = imageAttachmentResolver ?: { ref ->
                VisionToolSet.resolveImageAttachment(
                    rawIdOrUri = ref,
                    prootDefaultBaseDirPosix = suggestedWorkingDirPosix,
                )
            }
            DirectImageReadToolSet(imageAttachmentResolver = resolver)
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

        val (formattedTaskPrompt, directImageAttachments) = prepareTaskPromptPayload(
            task = normalizedTask,
            context = context.trim(),
            sessionId = sessionId,
            suggestedWorkingDirDisplay = suggestedWorkingDirDisplay,
            suggestedWorkingDirPosix = suggestedWorkingDirPosix,
            sessionAttachments = session?.attachments.orEmpty(),
            requestedFileIds = fileAttachmentIds,
            imageUris = imageUris,
            useDirectImageInput = useDirectImageInput,
            session = session,
        )

        val resolvedMaxIterations = resolveMaxIterations()
        val effectiveSystemPrompt = buildEffectiveSystemPrompt(
            baseSystemPrompt = systemPrompt + taskSkillGuidance(),
            sessionId = sessionId,
            suggestedWorkingDirDisplay = suggestedWorkingDirDisplay,
            suggestedWorkingDirPosix = suggestedWorkingDirPosix,
            hasVisionTool = !useDirectImageInput && visionToolSet != null,
            hasDirectImageReadTool = directImageToolSet != null,
            llmApiEnvPromptSection = llmApiEnvPromptSection,
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
        val strategy = createStrategy(
            session = session,
            directImageAttachments = directImageAttachments,
            directImageToolSet = directImageToolSet,
            onToolCalled = { toolName -> executedTools += toolName },
        )

        val commandsBefore = terminalToolSet.executedCommandCount()
        val agent = AIAgent(
            promptExecutor = binding.executor,
            agentConfig = agentConfig,
            strategy = strategy,
            toolRegistry = withTaskSkills(effectiveToolRegistry),
            id = AGENT_NAME,
        )

        val reply = try {
            if (session != null) {
                withContext(ChatSessionContext(session)) {
                    agent.run(formattedTaskPrompt)
                }
            } else {
                agent.run(formattedTaskPrompt)
            }
        } finally {
            runCatching { agent.close() }
        }
        val commandsDelta = (terminalToolSet.executedCommandCount() - commandsBefore).coerceAtLeast(0)

        return ProotLinuxExecutionResult(
            success = true,
            summary = reply.trim(),
            provider = binding.provider,
            modelId = binding.model.id,
            sessionId = sessionId,
            suggestedWorkingDir = suggestedWorkingDirPosix,
            executedTools = executedTools.toList(),
            terminalCommandCount = commandsDelta,
            injectedLlmEnvVars = injectedLlmEnvVars,
        )
    }

    private suspend fun resolveDirectImageInputMode(): Boolean {
        if (directImageInputProvider != null) {
            return directImageInputProvider.invoke()
        }
        return DeviceActionAgent.isComplexPlanningSameAsComplexVision()
    }

    private suspend fun prepareTaskPromptPayload(
        task: String,
        context: String,
        sessionId: String?,
        suggestedWorkingDirDisplay: String,
        suggestedWorkingDirPosix: String,
        sessionAttachments: List<SessionAttachment>,
        requestedFileIds: List<String>,
        imageUris: List<String>,
        useDirectImageInput: Boolean,
        session: ChatSession?,
    ): Pair<String, List<Attachment.Image>> {
        val normalizedImages = imageUris
            .map { raw -> session?.findAttachment(raw)?.uri ?: raw.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val normalizedFileRefs = requestedFileIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        if (!useDirectImageInput || normalizedImages.isEmpty()) {
            val text = buildFormattedTaskPrompt(
                task = task,
                context = context,
                sessionId = sessionId,
                suggestedWorkingDirDisplay = suggestedWorkingDirDisplay,
                suggestedWorkingDirPosix = suggestedWorkingDirPosix,
                sessionAttachments = sessionAttachments,
                requestedFileIds = normalizedFileRefs,
                imageUrisForTool = normalizedImages,
                unresolvedImageUris = emptyList(),
                hasDirectImages = false,
            )
            return text to emptyList()
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

        val text = buildFormattedTaskPrompt(
            task = task,
            context = context,
            sessionId = sessionId,
            suggestedWorkingDirDisplay = suggestedWorkingDirDisplay,
            suggestedWorkingDirPosix = suggestedWorkingDirPosix,
            sessionAttachments = sessionAttachments,
            requestedFileIds = normalizedFileRefs,
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
        const val AGENT_NAME = "proot_linux_agent"
        const val PROMPT_ID = "proot_linux_agent_prompt"
        const val DEFAULT_MAX_ITERATIONS = AgentStepConfig.DEFAULT_DEVICE_ACTION_MAX_ITERATIONS
        const val DEFAULT_TEMPERATURE = 0.2

        val DEFAULT_MODEL_ROLES: List<ModelRole> = listOf(
            ModelRole.COMPLEX_PLANNING,
            ModelRole.FAST_CHEAP,
        )

        val DEFAULT_SYSTEM_PROMPT: String
            get() = PromptResources.read("linux/system.md")

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
            sessionId: String?,
            suggestedWorkingDirDisplay: String,
            suggestedWorkingDirPosix: String,
            hasVisionTool: Boolean,
            hasDirectImageReadTool: Boolean = false,
            llmApiEnvPromptSection: String? = null,
            maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        ): String = buildString {
            appendLine(baseSystemPrompt.trim())
            val safeMaxIterations = maxIterations.coerceAtLeast(1)
            val estimatedToolCalls = AgentStepConfig.estimateDeviceActionToolCalls(safeMaxIterations)
            appendLine()
            appendLine("[当前执行步数限制]")
            appendLine(
                "当前 PRoot Linux 容器任务的最大执行步数上限为 $safeMaxIterations 步（约支持 $estimatedToolCalls 次工具调用）。" +
                    PromptResources.read("linux/step_budget.md"),
            )
            appendLine()
            appendLine("[当前会话与 PRoot 工作空间建议]")
            if (!sessionId.isNullOrBlank()) {
                appendLine("- 当前会话 ID：$sessionId")
            }
            appendLine("- 建议工作目录（非强制）：$suggestedWorkingDirDisplay（容器内路径：$suggestedWorkingDirPosix）")
            appendLine(PromptResources.read("linux/isolation.md"))
            appendLine()
            appendLine(PromptResources.read("linux/offline_model.md"))
            if (!llmApiEnvPromptSection.isNullOrBlank()) {
                appendLine()
                appendLine(llmApiEnvPromptSection.trim())
            }
            if (hasDirectImageReadTool) {
                appendLine()
                append(PromptResources.read("linux/direct_images.md"))
            } else if (hasVisionTool) {
                appendLine()
                append(PromptResources.read("linux/vision_tool.md"))
            }
        }.trimEnd()

        internal fun buildFormattedTaskPrompt(
            task: String,
            context: String,
            sessionId: String?,
            suggestedWorkingDirDisplay: String,
            suggestedWorkingDirPosix: String,
            sessionAttachments: List<SessionAttachment>,
            requestedFileIds: List<String>,
            imageUrisForTool: List<String>,
            unresolvedImageUris: List<String>,
            hasDirectImages: Boolean,
        ): String = buildString {
            appendLine("[PRoot Linux 执行任务]")
            append(task)
            if (context.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[补充上下文]")
                append(context)
            }
            appendLine()
            appendLine()
            appendLine("[建议工作目录（非强制）]")
            if (!sessionId.isNullOrBlank()) {
                appendLine("- sessionId: $sessionId")
            }
            append("- $suggestedWorkingDirDisplay -> $suggestedWorkingDirPosix")

            if (requestedFileIds.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[指定参考的会话文件 ID / URI（可调用 importSessionFileToProot 导入容器）]")
                requestedFileIds.forEach { ref -> appendLine("- $ref") }
            }

            if (sessionAttachments.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[当前会话可用附件池]")
                sessionAttachments.forEach { att ->
                    val kind = if (att.isImage) "图片" else "文件"
                    appendLine("- [$kind] id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription}")
                }
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

        internal fun createStrategy(
            session: ChatSession?,
            directImageAttachments: List<Attachment.Image> = emptyList(),
            directImageToolSet: DirectImageReadToolSet? = null,
            onToolCalled: (String) -> Unit = {},
        ): AIAgentGraphStrategy<String, String> = strategy(AGENT_NAME) {
            val nodeCallLLM by if (directImageAttachments.isEmpty()) {
                nodeLLMRequest(
                    name = "proot_linux_llm_request",
                    allowToolCalls = true,
                )
            } else {
                node<String, Message.Response>(name = "proot_linux_llm_request") { input ->
                    llm.writeSession {
                        updatePrompt {
                            user(content = input, attachments = directImageAttachments)
                        }
                        requestLLM()
                    }
                }
            }

            val nodeExecuteTool by nodeExecuteToolWithStatus(
                name = "proot_linux_execute_tool",
                agentScopeLabel = "Linux终端",
                sessionProvider = { session },
            )
            val nodeRecordToolCall by node<Message.Tool.Call, Message.Tool.Call>(
                name = "proot_linux_record_tool",
            ) { call ->
                onToolCalled(call.tool)
                call
            }
            val nodeSendToolResult by nodeLLMSendToolResultWithDirectImages(
                name = "proot_linux_send_tool_result",
                directImageToolSet = directImageToolSet,
                allowToolCalls = true,
            )

            edge(nodeStart forwardTo nodeCallLLM)
            edge(nodeCallLLM forwardTo nodeRecordToolCall onToolCall { true })
            edge(nodeRecordToolCall forwardTo nodeExecuteTool)
            edge(nodeExecuteTool forwardTo nodeSendToolResult)
            edge(nodeSendToolResult forwardTo nodeRecordToolCall onToolCall { true })
            edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
            edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
        }
    }
}
