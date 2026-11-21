package top.aaa000111.maid.agent

import top.aaa000111.maid.prompt.PromptResources
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import top.aaa000111.maid.learning.TaskExecutionContext
import top.aaa000111.maid.skill.withTaskSkills
import top.aaa000111.maid.skill.taskSkillGuidance

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.agent.entity.AIAgentGraphStrategy
import ai.koog.agents.core.annotation.InternalAgentsApi
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.builder.strategy
import ai.koog.agents.core.dsl.extension.nodeLLMRequest
import ai.koog.agents.core.dsl.extension.onAssistantMessage
import ai.koog.agents.core.dsl.extension.onToolCall
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.reflect.tools
import ai.koog.agents.memory.config.MemoryScopeType
import ai.koog.agents.memory.feature.AgentMemory
import ai.koog.agents.memory.feature.nodes.parseFactsFromResponse
import ai.koog.agents.memory.feature.withMemory
import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.Fact
import ai.koog.agents.memory.model.MemoryScope
import ai.koog.agents.memory.model.MemorySubject
import ai.koog.agents.memory.prompts.MemoryPrompts
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Attachment
import ai.koog.prompt.message.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import top.aaa000111.maid.androidtool.AndroidPrivilege
import top.aaa000111.maid.androidtool.ToolsetPermissionCatalog
import top.aaa000111.maid.androidtool.ToolsetPermissionToolSet
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.ChatSessionContext
import top.aaa000111.maid.chat.SessionAttachment
import top.aaa000111.maid.chat.UserInput
import top.aaa000111.maid.chat.nodeExecuteToolWithStatus
import top.aaa000111.maid.memory.MaidMemoryManager
import top.aaa000111.maid.memory.MaidMemorySubjects
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.provider.LlmProvider
import top.aaa000111.maid.provider.ModelConfigRepository
import top.aaa000111.maid.provider.ModelRole
import top.aaa000111.maid.provider.RoleModelSelection
import top.aaa000111.maid.settings.AgentStepConfig
import top.aaa000111.maid.settings.AgentStepConfigRepository
import top.aaa000111.maid.settings.PersonaRepository

/**
 * [ChatAgent] 单次执行所使用的底层模型绑定信息。
 */
data class ChatModelBinding(
    val executor: PromptExecutor,
    val model: LLModel,
    val provider: LlmProvider? = null,
)

/**
 * [ChatAgent] 一次对话轮次的返回结果与元数据。
 */
data class ChatAgentReply(
    val content: String,
    val provider: LlmProvider?,
    val modelId: String,
    val historySize: Int,
    val sessionId: String = "",
)

/**
 * 面向用户的对话 Agent，也是项目中唯一持有 Koog `AgentMemory` 对话记忆的 Agent。
 *
 * 职责范围：
 * 1. 基于 [ChatSession] 维护单个或多个独立会话的短期对话上下文与并发隔离；
 * 2. 通过 Koog [AgentMemory] 与 [MaidMemoryManager] 自动加载并持久化长期对话记忆；
 * 3. 调用解析得到的 [PromptExecutor] 与 [LLModel] 完成多轮对话（可选接入工具注册表）。
 */
class ChatAgent(
    private val modelRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
    private val modelBindingProvider: (suspend () -> ChatModelBinding)? = null,
    private val toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
    private val plannerLifecycleManager: PlannerLifecycleManager? = PlannerLifecycleManager.default,
    private val researchAgent: ResearchAgent? = ResearchAgent.default,
    private val visionToolSet: VisionToolSet? = VisionToolSet.default,
    private val directImageInputProvider: (suspend () -> Boolean)? = null,
    private val imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
    private val systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
    private val personaProvider: (suspend () -> String)? = null,
    private val memorySubjects: List<MemorySubject> = MaidMemorySubjects.all,
    private val loadMemoryScopes: List<MemoryScopeType> = DEFAULT_LOAD_MEMORY_SCOPES,
    private val saveMemoryScopes: List<MemoryScopeType> = DEFAULT_SAVE_MEMORY_SCOPES,
    private val enableMemory: Boolean = true,
    private val crossChatMemoryEnabledProvider: (suspend () -> Boolean)? = null,
    private val autoSaveMemory: Boolean = true,
    private val maxIterations: Int? = null,
    private val maxIterationsProvider: (suspend () -> Int)? = null,
    private val temperature: Double = DEFAULT_TEMPERATURE,
    val defaultSession: ChatSession = ChatSession(title = ChatSession.DEFAULT_TITLE),
) {

    val manager: ChatAgentManager by lazy { ChatAgentManager(this) }

    constructor(
        promptExecutor: PromptExecutor,
        model: LLModel,
        provider: LlmProvider? = null,
        toolRegistry: ToolRegistry = ToolRegistry.EMPTY,
        plannerLifecycleManager: PlannerLifecycleManager? = PlannerLifecycleManager.default,
        researchAgent: ResearchAgent? = null,
        visionToolSet: VisionToolSet? = VisionToolSet.default,
        directImageInputProvider: (suspend () -> Boolean)? = null,
        imageAttachmentResolver: (suspend (String) -> Attachment.Image?)? = null,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        personaProvider: (suspend () -> String)? = null,
        memorySubjects: List<MemorySubject> = MaidMemorySubjects.all,
        loadMemoryScopes: List<MemoryScopeType> = DEFAULT_LOAD_MEMORY_SCOPES,
        saveMemoryScopes: List<MemoryScopeType> = DEFAULT_SAVE_MEMORY_SCOPES,
        enableMemory: Boolean = true,
        crossChatMemoryEnabledProvider: (suspend () -> Boolean)? = null,
        autoSaveMemory: Boolean = true,
        maxIterations: Int? = null,
        maxIterationsProvider: (suspend () -> Int)? = null,
        temperature: Double = DEFAULT_TEMPERATURE,
        defaultSession: ChatSession = ChatSession(title = ChatSession.DEFAULT_TITLE),
    ) : this(
        modelRoles = DEFAULT_MODEL_ROLES,
        modelBindingProvider = {
            ChatModelBinding(
                executor = promptExecutor,
                model = model,
                provider = provider,
            )
        },
        toolRegistry = toolRegistry,
        plannerLifecycleManager = plannerLifecycleManager,
        researchAgent = researchAgent,
        visionToolSet = visionToolSet,
        directImageInputProvider = directImageInputProvider,
        imageAttachmentResolver = imageAttachmentResolver,
        systemPrompt = systemPrompt,
        personaProvider = personaProvider,
        memorySubjects = memorySubjects,
        loadMemoryScopes = loadMemoryScopes,
        saveMemoryScopes = saveMemoryScopes,
        enableMemory = enableMemory,
        crossChatMemoryEnabledProvider = crossChatMemoryEnabledProvider,
        autoSaveMemory = autoSaveMemory,
        maxIterations = maxIterations,
        maxIterationsProvider = maxIterationsProvider,
        temperature = temperature,
        defaultSession = defaultSession,
    )

    /**
     * 在指定 [session] 中发送一条用户消息并返回助手回复文本。
     */
    suspend fun chat(
        userMessage: String,
        session: ChatSession = defaultSession,
    ): String = chatWithDetails(userMessage = userMessage, session = session).content

    /**
     * 在指定 [session] 中发送一条包含文本与附件 URI 的用户输入并返回助手回复文本。
     */
    suspend fun chat(
        userInput: UserInput,
        session: ChatSession = defaultSession,
    ): String = chatWithDetails(userInput = userInput, session = session).content

    /**
     * 在指定 [session] 中发送一条用户消息，完成记忆加载/回复生成/记忆抽取后返回详细结果。
     */
    suspend fun chatWithDetails(
        userMessage: String,
        session: ChatSession = defaultSession,
    ): ChatAgentReply = chatWithDetails(
        userInput = UserInput(text = userMessage),
        session = session,
    )

    /**
     * 在指定 [session] 中发送一条包含文本、文件 URI 与图片 URI 的用户输入，
     * 根据快速模型与快速视觉模型是否为同一模型，自动决定启用 `VisionToolSet` 还是直接传入图片附件。
     */
    suspend fun chatWithDetails(
        userInput: UserInput,
        session: ChatSession = defaultSession,
    ): ChatAgentReply {
        val normalizedInput = userInput.normalized()
        require(!normalizedInput.isBlank) { "User message must not be blank" }

        return top.aaa000111.maid.learning.LearningRuntime.withTask(session.id) {
            session.withTurnLock {
                // 输入的文件 URI 与图片 URI 全部暂存并注册为 SessionAttachment（包含 id / fileName / description / uri）
                val (turnFileAttachments, turnImageAttachments) = session.registerTurnAttachments(
                    fileUris = normalizedInput.fileUris,
                    imageUris = normalizedInput.imageUris,
                    fileDescriptions = normalizedInput.fileDescriptions,
                    imageDescriptions = normalizedInput.imageDescriptions,
                    fileNames = normalizedInput.fileNames,
                    imageNames = normalizedInput.imageNames,
                    touchTimestamp = false,
                )

                val effectiveEnableMemory = resolveEnableMemory()
                if (effectiveEnableMemory) {
                    check(MaidMemoryManager.isInitialized) {
                        "MaidMemoryManager must be initialized before running ChatAgent with memory enabled"
                    }
                }

                val binding = modelBindingProvider?.invoke()
                    ?: resolveDefaultModelBinding(modelRoles)

                currentCoroutineContext()[TaskExecutionContext]?.recordModel(binding.model.id, binding.provider?.id)

                val useDirectImageInput = resolveDirectImageInputMode()

                var effectiveToolRegistry = mergeTools(
                    base = toolRegistry,
                    extra = session.asToolRegistry(),
                )
                effectiveToolRegistry = mergeTools(
                    base = effectiveToolRegistry,
                    extra = ToolRegistry {
                        tools(ToolsetPermissionToolSet(session = session))
                    },
                )
                if (plannerLifecycleManager != null) {
                    effectiveToolRegistry = mergeTools(
                        base = effectiveToolRegistry,
                        extra = PlannerAgentManager(plannerLifecycleManager, session).asToolRegistry(),
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

                val (llmUserText, directImageAttachments) = prepareUserPromptPayload(
                    input = normalizedInput,
                    useDirectImageInput = useDirectImageInput,
                    session = session,
                    turnFileAttachments = turnFileAttachments,
                    turnImageAttachments = turnImageAttachments,
                )

                val currentPrivileges: Set<AndroidPrivilege> = session.checkCurrentPrivileges()
                currentCoroutineContext()[TaskExecutionContext]?.recordEnvironment(JsonObject(mapOf(
                    "privileges" to JsonArray(currentPrivileges.map { JsonPrimitive(it.name) }),
                )))
                val resolvedPersona = resolvePersonaText()
                val resolvedMaxIterations = resolveMaxIterations()
                val effectiveSystemPrompt = buildEffectiveSystemPrompt(
                    baseSystemPrompt = systemPrompt + taskSkillGuidance(),
                    personaText = resolvedPersona,
                    crossChatMemoryEnabled = effectiveEnableMemory,
                    availablePrivileges = currentPrivileges,
                    hasDirectImageReadTool = directImageToolSet != null,
                    maxIterations = resolvedMaxIterations,
                )

                val initialPrompt = session.buildPrompt(
                    systemPrompt = effectiveSystemPrompt,
                    temperature = temperature,
                    promptId = PROMPT_ID,
                )
                val agentConfig = AIAgentConfig(
                    prompt = initialPrompt,
                    model = binding.model,
                    maxAgentIterations = resolvedMaxIterations,
                )
                val strategy = createStrategy(
                    allowToolCalls = effectiveToolRegistry.tools.isNotEmpty(),
                    enableMemory = effectiveEnableMemory,
                    autoSaveMemory = autoSaveMemory,
                    memorySubjects = memorySubjects,
                    loadMemoryScopes = loadMemoryScopes,
                    saveMemoryScopes = saveMemoryScopes,
                    directImageAttachments = directImageAttachments,
                    directImageToolSet = directImageToolSet,
                )

                val agent = AIAgent(
                    promptExecutor = binding.executor,
                    agentConfig = agentConfig,
                    strategy = strategy,
                    toolRegistry = withTaskSkills(effectiveToolRegistry),
                    id = AGENT_NAME,
                ) {
                    if (effectiveEnableMemory) {
                        install(AgentMemory) {
                            MaidMemoryManager.configureAgentMemory(
                                config = this,
                                agentName = AGENT_NAME,
                                featureName = FEATURE_NAME,
                            )
                        }
                    }
                }

                val reply = session.withActiveModel(
                    provider = binding.provider,
                    modelId = binding.model.id,
                ) {
                    try {
                        withContext(ChatSessionContext(session)) {
                            agent.run(llmUserText)
                        }
                    } finally {
                        runCatching { agent.close() }
                    }
                }

                session.recordExchange(
                    userMessage = llmUserText,
                    assistantReply = reply,
                    userAttachments = directImageAttachments,
                )

                ChatAgentReply(
                    content = reply,
                    provider = binding.provider,
                    modelId = binding.model.id,
                    historySize = session.historySize,
                    sessionId = session.id,
                )
            }
        }
    }

    /**
     * 返回默认会话短期对话历史的只读快照。
     */
    fun historySnapshot(): List<Message> = defaultSession.historySnapshot()

    /**
     * 使用已有消息记录恢复默认会话的短期对话上下文。
     */
    fun seedHistory(messages: List<Message>) {
        defaultSession.seedHistory(messages)
    }

    /**
     * 清空默认会话的短期对话历史（不会删除 [MaidMemoryManager] 中的长期记忆）。
     */
    fun clearHistory() {
        defaultSession.clearHistory()
    }

    /**
     * 显式向对话记忆写入一条长期事实（默认作用域为 `ChatAgent` 专属 Agent 作用域）。
     */
    suspend fun rememberFact(
        concept: Concept,
        value: String,
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = MemoryScope.Agent(AGENT_NAME),
        timestamp: Long = System.currentTimeMillis(),
    ) {
        check(enableMemory) { "Memory is disabled on this ChatAgent instance" }
        MaidMemoryManager.saveSingleFact(
            concept = concept,
            value = value,
            subject = subject,
            scope = scope,
            timestamp = timestamp,
        )
    }

    /**
     * 从长期对话记忆中读取事实列表（默认读取 `ChatAgent` 专属 Agent 作用域）。
     */
    suspend fun loadRememberedFacts(
        subject: MemorySubject = MaidMemorySubjects.User,
        scope: MemoryScope = MemoryScope.Agent(AGENT_NAME),
    ): List<Fact> {
        check(enableMemory) { "Memory is disabled on this ChatAgent instance" }
        return MaidMemoryManager.loadAllFacts(
            subject = subject,
            scope = scope,
        )
    }

    private suspend fun resolveDirectImageInputMode(): Boolean {
        if (directImageInputProvider != null) {
            return directImageInputProvider.invoke()
        }
        return isFastChatSameAsFastVision()
    }

    private suspend fun prepareUserPromptPayload(
        input: UserInput,
        useDirectImageInput: Boolean,
        session: ChatSession,
        turnFileAttachments: List<SessionAttachment> = emptyList(),
        turnImageAttachments: List<SessionAttachment> = emptyList(),
    ): Pair<String, List<Attachment.Image>> {
        val imageUris = input.normalizedImageUris
        val fileUris = input.normalizedFileUris

        if (!useDirectImageInput || imageUris.isEmpty()) {
            val formattedText = buildFormattedUserMessage(
                text = input.normalizedText,
                fileUris = fileUris,
                imageUris = imageUris,
                imageUrisForTool = imageUris,
                unresolvedImageUris = emptyList(),
                hasDirectImages = false,
                sessionFileUriPool = session.fileUriPool,
                sessionImageUriPool = session.imageUriPool,
                turnFileAttachments = turnFileAttachments,
                turnImageAttachments = turnImageAttachments,
                sessionAttachments = session.attachments,
            )
            return formattedText to emptyList()
        }

        val resolver = imageAttachmentResolver ?: VisionToolSet::resolveImageAttachment
        val resolvedAttachments = mutableListOf<Attachment.Image>()
        val unresolvedUris = mutableListOf<String>()
        for (uri in imageUris) {
            // 第一次访问图片附件时触发内部目录复制（若不在内部目录则复制到 <filesDir>/<session_id>/<file_id>）
            val accessedUri = session.accessAttachmentUri(uri)
            val attachment = runCatching { resolver(accessedUri) }.getOrNull()
            if (attachment != null) {
                resolvedAttachments += attachment
            } else {
                unresolvedUris += uri
            }
        }

        val refreshedTurnImages = imageUris.mapNotNull { session.findAttachment(it) }
        val formattedText = buildFormattedUserMessage(
            text = input.normalizedText,
            fileUris = fileUris,
            imageUris = imageUris,
            imageUrisForTool = emptyList(),
            unresolvedImageUris = unresolvedUris,
            hasDirectImages = resolvedAttachments.isNotEmpty(),
            sessionFileUriPool = session.fileUriPool,
            sessionImageUriPool = session.imageUriPool,
            turnFileAttachments = turnFileAttachments,
            turnImageAttachments = refreshedTurnImages,
            sessionAttachments = session.attachments,
        )
        return formattedText to resolvedAttachments
    }

    private suspend fun resolveEnableMemory(): Boolean {
        if (!enableMemory) return false
        crossChatMemoryEnabledProvider?.let { return it.invoke() }
        return if (AgentStepConfigRepository.isReady) {
            AgentStepConfigRepository.isCrossChatMemoryEnabled()
        } else {
            true
        }
    }

    private suspend fun resolvePersonaText(): String {
        if (personaProvider != null) {
            return personaProvider.invoke().trim()
        }
        return if (PersonaRepository.isReady) {
            PersonaRepository.getEffectivePersonaText()
        } else {
            ""
        }
    }

    private suspend fun resolveMaxIterations(): Int {
        maxIterationsProvider?.let { return it.invoke().coerceAtLeast(1) }
        maxIterations?.let { return it.coerceAtLeast(1) }
        return if (AgentStepConfigRepository.isReady) {
            AgentStepConfigRepository.getStepConfig().chatMaxIterations
        } else {
            DEFAULT_MAX_ITERATIONS
        }
    }

    companion object {
        const val AGENT_NAME = "chat_agent"
        const val FEATURE_NAME = "conversation_memory"
        const val PROMPT_ID = "chat_agent_prompt"
        const val DEFAULT_MAX_ITERATIONS = AgentStepConfig.DEFAULT_CHAT_MAX_ITERATIONS
        const val DEFAULT_TEMPERATURE = 0.7

        val DEFAULT_MODEL_ROLES: List<ModelRole> = listOf(
            ModelRole.FAST_CHEAP,
        )

        val DEFAULT_LOAD_MEMORY_SCOPES: List<MemoryScopeType> = listOf(
            MemoryScopeType.AGENT,
            MemoryScopeType.FEATURE,
            MemoryScopeType.PRODUCT,
        )

        val DEFAULT_SAVE_MEMORY_SCOPES: List<MemoryScopeType> = listOf(
            MemoryScopeType.AGENT,
        )

        val DEFAULT_SYSTEM_PROMPT: String
            get() = PromptResources.read("chat/system.md")

        internal fun isSameModelSelection(
            first: RoleModelSelection?,
            second: RoleModelSelection?,
        ): Boolean {
            val normalizedFirst = first?.takeIf { it.isComplete } ?: return false
            val normalizedSecond = second?.takeIf { it.isComplete } ?: return false
            return normalizedFirst.provider == normalizedSecond.provider &&
                normalizedFirst.normalizedCustomProviderId == normalizedSecond.normalizedCustomProviderId &&
                normalizedFirst.normalizedModelId == normalizedSecond.normalizedModelId
        }

        internal suspend fun isFastChatSameAsFastVision(): Boolean {
            if (!ModelConfigRepository.isReady) return false
            val configs = ModelConfigRepository.getAllConfigs()
            return isSameModelSelection(
                configs[ModelRole.FAST_CHEAP],
                configs[ModelRole.FAST_VISION],
            )
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

        const val CROSS_CHAT_MEMORY_SOURCE_HEADER =
            "[信息来源：跨聊天长期记忆（以下事实提炼自过往其他聊天会话，非当前会话当场发言，仅作跨会话背景参考；若与当前会话用户输入冲突，请以当前会话用户输入为准）]"

        internal fun buildFormattedUserMessage(
            text: String,
            fileUris: List<String>,
            imageUris: List<String> = emptyList(),
            imageUrisForTool: List<String> = imageUris,
            unresolvedImageUris: List<String> = emptyList(),
            hasDirectImages: Boolean = false,
            sessionFileUriPool: List<String> = emptyList(),
            sessionImageUriPool: List<String> = emptyList(),
            turnFileAttachments: List<SessionAttachment> = emptyList(),
            turnImageAttachments: List<SessionAttachment> = emptyList(),
            sessionAttachments: List<SessionAttachment> = emptyList(),
        ): String {
            val effectiveTurnImageUris = (imageUris + imageUrisForTool + unresolvedImageUris).distinct()
            val baseText = text.ifEmpty {
                when {
                    hasDirectImages || effectiveTurnImageUris.isNotEmpty() ->
                        "请查看我发送的图片。"
                    fileUris.isNotEmpty() -> "请查看我发送的文件。"
                    else -> ""
                }
            }

            val allKnownAttachments = if (sessionAttachments.isNotEmpty()) {
                sessionAttachments
            } else {
                ChatSession.mergeAttachmentsWithUris(
                    existing = turnFileAttachments + turnImageAttachments,
                    fileUris = sessionFileUriPool + fileUris,
                    imageUris = sessionImageUriPool + effectiveTurnImageUris,
                )
            }

            fun resolveAttachment(uri: String, isImage: Boolean): SessionAttachment {
                return allKnownAttachments.firstOrNull {
                    it.uri == uri || it.originalUri == uri || it.id == uri
                } ?: SessionAttachment(
                    id = if (isImage) "img_1" else "file_1",
                    description = SessionAttachment.defaultDescriptionFor(uri = uri, isImage = isImage),
                    uri = uri,
                    originalUri = uri,
                    isImage = isImage,
                )
            }

            return buildString {
                append(baseText)
                if (effectiveTurnImageUris.isNotEmpty() || hasDirectImages) {
                    appendLine()
                    appendLine()
                    appendLine(PromptResources.read("chat/user_images_header.md"))
                    if (hasDirectImages) {
                        appendLine(PromptResources.read("chat/user_images_direct.md"))
                    } else if (imageUrisForTool.isNotEmpty()) {
                        appendLine(PromptResources.read("chat/user_images_tool.md"))
                    }
                    effectiveTurnImageUris.forEach { uri ->
                        val att = resolveAttachment(uri, isImage = true)
                        appendLine("- id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                        appendLine("- ${att.originalUri}")
                    }
                }
                if (unresolvedImageUris.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("[未能直接读取为多模态附件的用户图片（信息来源：来自用户的图片）]")
                    unresolvedImageUris.forEach { uri ->
                        val att = resolveAttachment(uri, isImage = true)
                        appendLine("- id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                    }
                }
                if (fileUris.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine(PromptResources.read("chat/user_files_header.md"))
                    fileUris.forEach { uri ->
                        val att = resolveAttachment(uri, isImage = false)
                        appendLine("- id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                        appendLine("- ${att.originalUri}")
                    }
                }
                if (sessionFileUriPool.isNotEmpty() || sessionImageUriPool.isNotEmpty() || sessionAttachments.isNotEmpty()) {
                    appendLine()
                    appendLine()
                    appendLine("[当前会话公用 URI 池（信息来源：当前会话历次输入累计暂存的文件与图片 URI）]")
                    appendLine(PromptResources.read("chat/attachment_pool.md"))
                    val fileAtts = allKnownAttachments.filterNot { it.isImage }
                    val imageAtts = allKnownAttachments.filter { it.isImage }
                    fileAtts.forEach { att ->
                        appendLine("- [文件] id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                    }
                    sessionFileUriPool.forEach { uri ->
                        appendLine("- [文件 URI] $uri")
                    }
                    imageAtts.forEach { att ->
                        appendLine("- [图片] id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                    }
                    sessionImageUriPool.forEach { uri ->
                        appendLine("- [图片 URI] $uri")
                    }
                }
            }.trimEnd()
        }

        internal fun buildEffectiveSystemPrompt(
            baseSystemPrompt: String,
            personaText: String,
            crossChatMemoryEnabled: Boolean = true,
            availablePrivileges: Set<AndroidPrivilege> = emptySet(),
            hasDirectImageReadTool: Boolean = false,
            maxIterations: Int = DEFAULT_MAX_ITERATIONS,
        ): String {
            val trimmedBase = baseSystemPrompt.trim()
            val trimmedPersona = personaText.trim()
            val safeMaxIterations = maxIterations.coerceAtLeast(1)
            val estimatedToolCalls = AgentStepConfig.estimateChatToolCalls(safeMaxIterations)
            val stepLimitSection = buildString {
                appendLine("[当前执行步数限制]")
                append("当前对话模型单轮最大执行步数上限为 $safeMaxIterations 步（约支持 $estimatedToolCalls 次工具调用）。")
                append(PromptResources.read("chat/step_budget.md"))
            }
            val sourceSection = buildString {
                appendLine(PromptResources.read("chat/context_sources.md"))
                if (crossChatMemoryEnabled) {
                    append(PromptResources.read("chat/memory_enabled.md"))
                } else {
                    append(PromptResources.read("chat/memory_disabled.md"))
                }
            }

            val sections = mutableListOf<String>()
            if (trimmedBase.isNotEmpty()) {
                sections += trimmedBase
            }
            sections += stepLimitSection
            sections += sourceSection
            if (hasDirectImageReadTool) {
                sections += PromptResources.read("chat/direct_images.md")
            }
            sections += ToolsetPermissionCatalog.buildPromptGuidance(availablePrivileges)
            if (trimmedPersona.isNotEmpty()) {
                sections += buildString {
                    appendLine(PromptResources.read("chat/persona_intro.md"))
                append(trimmedPersona)
                }
            }
            return sections.joinToString("\n\n")
        }

        internal fun annotateCrossChatMemoryMessages(messages: List<Message>): List<Message> {
            return messages.map { message ->
                if (message.content.contains(CROSS_CHAT_MEMORY_SOURCE_HEADER)) {
                    message
                } else {
                    val annotatedContent = "$CROSS_CHAT_MEMORY_SOURCE_HEADER\n${message.content}"
                    when (message) {
                        is Message.User -> message.copy(content = annotatedContent)
                        is Message.System -> message.copy(content = annotatedContent)
                        is Message.Assistant -> message.copy(content = annotatedContent)
                        else -> message
                    }
                }
            }
        }

        @OptIn(InternalAgentsApi::class)
        internal fun createStrategy(
            allowToolCalls: Boolean,
            enableMemory: Boolean,
            autoSaveMemory: Boolean,
            memorySubjects: List<MemorySubject>,
            loadMemoryScopes: List<MemoryScopeType>,
            saveMemoryScopes: List<MemoryScopeType>,
            directImageAttachments: List<Attachment.Image> = emptyList(),
            directImageToolSet: DirectImageReadToolSet? = null,
        ): AIAgentGraphStrategy<String, String> = strategy(AGENT_NAME) {
            val nodeLoadMemory by node<String, String>(name = "chat_load_memory") { input ->
                val nodeContext = this
                if (loadMemoryScopes.isNotEmpty() && memorySubjects.isNotEmpty()) {
                    withMemory {
                        val memoryLlm = this.llm
                        val beforeMessages = memoryLlm.readSession { prompt.messages }
                        loadAllFactsToAgent(
                            scopes = loadMemoryScopes,
                            subjects = memorySubjects,
                        )
                        val addedMessages = memoryLlm.readSession {
                            prompt.messages.drop(beforeMessages.size)
                        }
                        if (addedMessages.isNotEmpty()) {
                            val annotatedMessages = annotateCrossChatMemoryMessages(addedMessages)
                            if (memoryLlm === nodeContext.llm) {
                                val updatedPrompt = nodeContext.llm.readSession {
                                    prompt.copy(messages = beforeMessages + annotatedMessages)
                                }
                                nodeContext.llm.writeSession {
                                    rewritePrompt { updatedPrompt }
                                }
                            } else {
                                // Koog 的 AgentMemory.Feature 在子图复制 LLMContext 之前捕获了外层 llm 引用；
                                // 这里先由 AgentMemory 加载并格式化事实，再将附带来源声明的记忆消息同步到当前节点上下文。
                                nodeContext.llm.writeSession {
                                    updatePrompt {
                                        messages(annotatedMessages)
                                    }
                                }
                            }
                        }
                    }
                }
                input
            }
            val nodeCallLLM by if (directImageAttachments.isEmpty()) {
                nodeLLMRequest(
                    name = "chat_llm_request",
                    allowToolCalls = allowToolCalls,
                )
            } else {
                node<String, Message.Response>(name = "chat_llm_request") { input ->
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
            val nodeExecuteTool by nodeExecuteToolWithStatus(name = "chat_execute_tool")
            val nodeSendToolResult by nodeLLMSendToolResultWithDirectImages(
                name = "chat_send_tool_result",
                directImageToolSet = directImageToolSet,
                allowToolCalls = allowToolCalls,
            )
            val nodeSaveMemory by node<String, String>(name = "chat_save_memory") { reply ->
                val nodeContext = this
                if (saveMemoryScopes.isNotEmpty()) {
                    withMemory {
                        val targetScopes = saveMemoryScopes
                            .mapNotNull { scopeType -> scopesProfile.getScope(scopeType) }
                            .distinct()
                        if (targetScopes.isEmpty()) return@withMemory

                        val extracted = nodeContext.llm.writeSession {
                            val initialPrompt = prompt.copy()
                            val initialModel = model
                            try {
                                updatePrompt {
                                    user(MemoryPrompts.autoDetectFacts(memorySubjects))
                                }
                                val response = requestLLMWithoutTools()
                                parseFactsSafely(response.content)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                emptyList()
                            } finally {
                                rewritePrompt { initialPrompt }
                                model = initialModel
                            }
                        }

                        for (scope in targetScopes) {
                            for ((subject, fact) in extracted) {
                                agentMemory.save(
                                    fact = fact,
                                    subject = subject,
                                    scope = scope,
                                )
                            }
                        }
                    }
                }
                reply
            }

            if (enableMemory) {
                edge(nodeStart forwardTo nodeLoadMemory)
                edge(nodeLoadMemory forwardTo nodeCallLLM)
            } else {
                edge(nodeStart forwardTo nodeCallLLM)
            }

            if (allowToolCalls) {
                edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
                edge(nodeExecuteTool forwardTo nodeSendToolResult)
                edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
                if (enableMemory && autoSaveMemory) {
                    edge(nodeSendToolResult forwardTo nodeSaveMemory onAssistantMessage { true })
                } else {
                    edge(nodeSendToolResult forwardTo nodeFinish onAssistantMessage { true })
                }
            }

            if (enableMemory && autoSaveMemory) {
                edge(nodeCallLLM forwardTo nodeSaveMemory onAssistantMessage { true })
                edge(nodeSaveMemory forwardTo nodeFinish)
            } else {
                edge(nodeCallLLM forwardTo nodeFinish onAssistantMessage { true })
            }
        }

        @OptIn(InternalAgentsApi::class)
        internal fun parseFactsSafely(rawContent: String): List<Pair<MemorySubject, Fact>> {
            val cleaned = stripMarkdownCodeFences(rawContent)
            if (cleaned.isEmpty()) return emptyList()
            return runCatching { parseFactsFromResponse(cleaned) }.getOrDefault(emptyList())
        }

        internal fun stripMarkdownCodeFences(raw: String): String {
            val trimmed = raw.trim()
            if (!trimmed.startsWith("```")) return trimmed
            val lines = trimmed.lines()
            if (lines.size < 2) return trimmed
            val withoutOpening = lines.drop(1)
            val withoutClosing = if (withoutOpening.lastOrNull()?.trim()?.startsWith("```") == true) {
                withoutOpening.dropLast(1)
            } else {
                withoutOpening
            }
            return withoutClosing.joinToString("\n").trim()
        }

        internal suspend fun resolveDefaultModelBinding(
            preferredRoles: List<ModelRole> = DEFAULT_MODEL_ROLES,
        ): ChatModelBinding {
            return AppRuntime.resolveModelBinding(
                preferredRoles = preferredRoles.ifEmpty { DEFAULT_MODEL_ROLES },
            )
        }
    }
}
