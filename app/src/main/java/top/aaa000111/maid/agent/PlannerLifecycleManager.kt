package top.aaa000111.maid.agent

import kotlinx.coroutines.currentCoroutineContext
import top.aaa000111.maid.learning.TaskExecutionContext

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.coroutines.CancellationException
import top.aaa000111.maid.chat.ChatSession
import top.aaa000111.maid.chat.SessionAttachment
import top.aaa000111.maid.chat.UserInput
import top.aaa000111.maid.settings.AgentStepConfig
import top.aaa000111.maid.settings.AgentStepConfigRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class PlannerLifecycleStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

enum class CheckpointStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
}

data class CheckpointEntry(
    val index: Int,
    val title: String,
    val status: CheckpointStatus = CheckpointStatus.PENDING,
    val summary: String = "",
)

data class CompletedCheckpointRecord(
    val checkpointIndex: Int,
    val checkpointTitle: String,
    val summary: String,
    val externalInputs: List<UserInput> = emptyList(),
    val completedAtMillis: Long = System.currentTimeMillis(),
)

sealed interface CheckpointOutcome {
    data class NextCheckpoint(
        val summary: String,
        val nextCheckpointGoal: String = "",
    ) : CheckpointOutcome

    data class FinalExit(
        val content: String,
    ) : CheckpointOutcome
}

data class PlannerExecutionResult(
    val planId: String,
    val success: Boolean,
    val finalContent: String,
    val checkpointsCompleted: Int,
    val trailingExternalInputs: List<UserInput> = emptyList(),
    val memory: PlannerLifecycleMemory.Snapshot,
)

/**
 * 规划器单次运行期内的生命周期记忆（与 [ChatAgent] 的长期 `AgentMemory` 隔离）。
 * 在同一轮规划的多个检查点之间持续保留目标、检查点进度、工作笔记及外部补充输入，规划退出后归档。
 */
class PlannerLifecycleMemory(
    val planId: String = UUID.randomUUID().toString(),
    val goal: String,
    val initialContext: String = "",
    initialImageUris: List<String> = emptyList(),
) {
    data class Snapshot(
        val planId: String,
        val goal: String,
        val initialContext: String,
        val initialImageUris: List<String>,
        val status: PlannerLifecycleStatus,
        val currentCheckpointIndex: Int,
        val checkpoints: List<CheckpointEntry>,
        val notes: Map<String, String>,
        val completedSteps: List<CompletedCheckpointRecord>,
        val externalInputsHistory: List<UserInput>,
        val finalResult: String,
    )

    private val lock = Any()
    private val normalizedInitialImages = initialImageUris
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

    private var status: PlannerLifecycleStatus = PlannerLifecycleStatus.RUNNING
    private var currentCheckpointIndex: Int = 1
    private var checkpoints: MutableList<CheckpointEntry> = mutableListOf()
    private val notes: LinkedHashMap<String, String> = linkedMapOf()
    private val completedSteps: MutableList<CompletedCheckpointRecord> = mutableListOf()
    private val externalInputsHistory: MutableList<UserInput> = mutableListOf()
    private var finalResult: String = ""

    fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            planId = planId,
            goal = goal,
            initialContext = initialContext,
            initialImageUris = normalizedInitialImages,
            status = status,
            currentCheckpointIndex = currentCheckpointIndex,
            checkpoints = checkpoints.toList(),
            notes = notes.toMap(),
            completedSteps = completedSteps.toList(),
            externalInputsHistory = externalInputsHistory.toList(),
            finalResult = finalResult,
        )
    }

    fun setCheckpoints(
        titles: List<String>,
        notesText: String = "",
    ): Snapshot = synchronized(lock) {
        val cleanedTitles = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleanedTitles.isNotEmpty()) {
            val completedPrefix = checkpoints.filter { it.status == CheckpointStatus.COMPLETED }
            val startIndex = completedPrefix.size + 1
            val updatedRemaining = cleanedTitles.mapIndexed { offset, title ->
                CheckpointEntry(
                    index = startIndex + offset,
                    title = title,
                    status = if (offset == 0) CheckpointStatus.IN_PROGRESS else CheckpointStatus.PENDING,
                )
            }
            checkpoints = (completedPrefix + updatedRemaining).toMutableList()
            currentCheckpointIndex = startIndex
        }
        val trimmedNote = notesText.trim()
        if (trimmedNote.isNotEmpty()) {
            notes["plan_notes"] = trimmedNote
        }
        snapshotLocked()
    }

    fun putNote(key: String, value: String) {
        val normalizedKey = key.trim()
        val normalizedValue = value.trim()
        if (normalizedKey.isEmpty()) return
        synchronized(lock) {
            if (normalizedValue.isEmpty()) {
                notes.remove(normalizedKey)
            } else {
                notes[normalizedKey] = normalizedValue
            }
        }
    }

    fun recordExternalInputs(inputs: List<UserInput>) {
        if (inputs.isEmpty()) return
        synchronized(lock) {
            externalInputsHistory.addAll(inputs.map { it.normalized() }.filterNot { it.isBlank })
        }
    }

    fun completeCheckpointAndAdvance(
        summary: String,
        nextCheckpointGoal: String = "",
        inputsForStep: List<UserInput> = emptyList(),
    ): Snapshot = synchronized(lock) {
        val trimmedSummary = summary.trim()
        val currentIdx = currentCheckpointIndex
        val currentTitle = currentCheckpointTitleLocked()

        val existingIndex = checkpoints.indexOfFirst { it.index == currentIdx }
        if (existingIndex >= 0) {
            checkpoints[existingIndex] = checkpoints[existingIndex].copy(
                status = CheckpointStatus.COMPLETED,
                summary = trimmedSummary,
            )
        } else {
            checkpoints.add(
                CheckpointEntry(
                    index = currentIdx,
                    title = currentTitle,
                    status = CheckpointStatus.COMPLETED,
                    summary = trimmedSummary,
                ),
            )
        }

        completedSteps.add(
            CompletedCheckpointRecord(
                checkpointIndex = currentIdx,
                checkpointTitle = currentTitle,
                summary = trimmedSummary,
                externalInputs = inputsForStep,
            ),
        )

        val nextIdx = currentIdx + 1
        currentCheckpointIndex = nextIdx

        val trimmedNextGoal = nextCheckpointGoal.trim()
        val existingNextIdx = checkpoints.indexOfFirst { it.index == nextIdx }
        if (existingNextIdx >= 0) {
            val targetTitle = trimmedNextGoal.ifEmpty { checkpoints[existingNextIdx].title }
            checkpoints[existingNextIdx] = checkpoints[existingNextIdx].copy(
                title = targetTitle,
                status = CheckpointStatus.IN_PROGRESS,
            )
        } else if (trimmedNextGoal.isNotEmpty()) {
            checkpoints.add(
                CheckpointEntry(
                    index = nextIdx,
                    title = trimmedNextGoal,
                    status = CheckpointStatus.IN_PROGRESS,
                ),
            )
        }

        snapshotLocked()
    }

    fun markFinalExit(
        content: String,
        inputsForStep: List<UserInput> = emptyList(),
    ): Snapshot = synchronized(lock) {
        val trimmedContent = content.trim()
        val currentIdx = currentCheckpointIndex
        val currentTitle = currentCheckpointTitleLocked()

        val existingIndex = checkpoints.indexOfFirst { it.index == currentIdx }
        if (existingIndex >= 0) {
            checkpoints[existingIndex] = checkpoints[existingIndex].copy(
                status = CheckpointStatus.COMPLETED,
                summary = trimmedContent,
            )
        } else if (checkpoints.isNotEmpty() || trimmedContent.isNotEmpty()) {
            checkpoints.add(
                CheckpointEntry(
                    index = currentIdx,
                    title = currentTitle,
                    status = CheckpointStatus.COMPLETED,
                    summary = trimmedContent,
                ),
            )
        }

        completedSteps.add(
            CompletedCheckpointRecord(
                checkpointIndex = currentIdx,
                checkpointTitle = currentTitle,
                summary = trimmedContent,
                externalInputs = inputsForStep,
            ),
        )
        finalResult = trimmedContent
        status = PlannerLifecycleStatus.COMPLETED
        snapshotLocked()
    }

    fun markStatus(newStatus: PlannerLifecycleStatus) {
        synchronized(lock) {
            status = newStatus
        }
    }

    fun currentCheckpointTitle(): String = synchronized(lock) {
        currentCheckpointTitleLocked()
    }

    fun totalCheckpointsCount(): Int = synchronized(lock) {
        checkpoints.size
    }

    fun renderMemorySection(): String = synchronized(lock) {
        buildString {
            appendLine("[规划总目标]")
            appendLine(goal)
            if (initialContext.isNotBlank()) {
                appendLine()
                appendLine("[初始背景上下文]")
                appendLine(initialContext)
            }
            if (checkpoints.isNotEmpty()) {
                appendLine()
                appendLine("[检查点列表（当前第 $currentCheckpointIndex 步）]")
                checkpoints.forEach { cp ->
                    val marker = when (cp.status) {
                        CheckpointStatus.COMPLETED -> "已完成"
                        CheckpointStatus.IN_PROGRESS -> "进行中"
                        CheckpointStatus.PENDING -> "待执行"
                    }
                    append("- #${cp.index} [$marker] ${cp.title}")
                    if (cp.summary.isNotBlank()) {
                        append(" -> ${cp.summary}")
                    }
                    appendLine()
                }
            }
            if (completedSteps.isNotEmpty()) {
                appendLine()
                appendLine("[已完成检查点记忆摘要]")
                completedSteps.forEach { step ->
                    appendLine("- 检查点 #${step.checkpointIndex} (${step.checkpointTitle}): ${step.summary}")
                    if (step.externalInputs.isNotEmpty()) {
                        appendLine("  └ 期间收到的真实用户中途直接输入: ${formatExternalInputsInline(step.externalInputs)}")
                    }
                }
            }
            if (notes.isNotEmpty()) {
                appendLine()
                appendLine("[规划生命周期工作记忆]")
                notes.forEach { (key, value) ->
                    appendLine("- $key: $value")
                }
            }
        }.trimEnd()
    }

    private fun currentCheckpointTitleLocked(): String {
        return checkpoints.firstOrNull { it.index == currentCheckpointIndex }?.title
            ?: if (currentCheckpointIndex == 1) "制定并执行初始检查点" else "检查点 $currentCheckpointIndex"
    }

    private fun snapshotLocked(): Snapshot = Snapshot(
        planId = planId,
        goal = goal,
        initialContext = initialContext,
        initialImageUris = normalizedInitialImages,
        status = status,
        currentCheckpointIndex = currentCheckpointIndex,
        checkpoints = checkpoints.toList(),
        notes = notes.toMap(),
        completedSteps = completedSteps.toList(),
        externalInputsHistory = externalInputsHistory.toList(),
        finalResult = finalResult,
    )
}

/**
 * 绑定单次检查点执行的控制工具集，向 [PlannerAgent] 提供：
 * - `setCheckpoints`：设置/更新后续检查点列表；
 * - `updateLifecycleNote`：写入跨检查点保留的规划生命周期记忆；
 * - `finish`：主动结束当前检查点（进入下一检查点或真正退出并返回最终内容）。
 */
class PlannerControlToolSet(
    private val memory: PlannerLifecycleMemory,
    internal val session: ChatSession? = null,
) : ToolSet {

    override val name: String = TOOLSET_NAME

    @Volatile
    private var outcome: CheckpointOutcome? = null

    fun hasOutcome(): Boolean = outcome != null

    fun currentOutcome(): CheckpointOutcome? = outcome

    fun currentOutcomeSummary(): String = when (val current = outcome) {
        is CheckpointOutcome.NextCheckpoint -> current.summary
        is CheckpointOutcome.FinalExit -> current.content
        null -> ""
    }

    @Tool
    @LLMDescription(
        "Set or update the ordered checkpoint plan for this planning lifecycle. " +
            "Already completed checkpoints are preserved while upcoming checkpoints are replaced by the provided list.",
    )
    fun setCheckpoints(
        @LLMDescription("Ordered list of remaining checkpoint titles or milestones.")
        checkpoints: List<String>,
        @LLMDescription("Optional planning notes to keep in lifecycle memory across checkpoints.")
        notes: String = "",
    ): String {
        val cleaned = checkpoints.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            return "No non-empty checkpoints were provided; existing checkpoints left unchanged."
        }
        val snapshot = memory.setCheckpoints(titles = cleaned, notesText = notes)
        session?.updatePlanningCheckpoint(
            checkpointIndex = snapshot.currentCheckpointIndex,
            totalCheckpoints = snapshot.checkpoints.size,
            currentCheckpointTitle = memory.currentCheckpointTitle(),
        )
        return "Checkpoints updated (${snapshot.checkpoints.size} total, current #${snapshot.currentCheckpointIndex}: ${memory.currentCheckpointTitle()})."
    }

    @Tool
    @LLMDescription(
        "Store or update a key-value fact in the planning lifecycle memory so it persists into subsequent checkpoints.",
    )
    fun updateLifecycleNote(
        @LLMDescription("Short descriptive key for this working fact.")
        key: String,
        @LLMDescription("Fact value or observation to retain until the planning lifecycle ends.")
        value: String,
    ): String {
        if (key.isBlank()) return "Skipped empty lifecycle note key."
        memory.putNote(key, value)
        return "Lifecycle note '$key' saved."
    }

    @Tool
    @LLMDescription(
        "Explicitly finish the current checkpoint. " +
            "Set action='next_checkpoint' to advance to the next checkpoint (and receive any new user messages sent while thinking), " +
            "or set action='final_exit' to terminate the planning lifecycle and return the final result to ChatAgent.",
    )
    fun finish(
        @LLMDescription("Either 'next_checkpoint' to proceed to the next checkpoint, or 'final_exit' to finish planning and return.")
        action: String,
        @LLMDescription("Summary of this checkpoint (for next_checkpoint) or complete final planning result (for final_exit).")
        summary: String,
        @LLMDescription("Optional title/goal for the next checkpoint when action='next_checkpoint'.")
        nextCheckpointGoal: String = "",
    ): String {
        val normalizedAction = action.trim().lowercase()
        return when (normalizedAction) {
            "next_checkpoint", "next", "checkpoint", "continue" -> {
                outcome = CheckpointOutcome.NextCheckpoint(
                    summary = summary.trim(),
                    nextCheckpointGoal = nextCheckpointGoal.trim(),
                )
                "Checkpoint finished; advancing to next checkpoint."
            }

            "final_exit", "exit", "done", "complete", "finish" -> {
                outcome = CheckpointOutcome.FinalExit(
                    content = summary.trim(),
                )
                "Planning lifecycle finished; exiting with final content."
            }

            else -> {
                "Invalid finish action '$action'. Expected 'next_checkpoint' or 'final_exit'."
            }
        }
    }

    companion object {
        const val TOOLSET_NAME = "planner_control"
    }
}

/**
 * 规划智能体生命周期管理器：
 * 1. 创建并管理 [PlannerLifecycleMemory]（跨检查点保留、与 `ChatAgent` 长期记忆隔离）；
 * 2. 驱动 [PlannerAgent] 按检查点循环执行，并在检查点切换时从 [ChatSession] 排空外部用户输入注入下一检查点；
 * 3. 在规划期间负责维护 [ChatSession] 的 `Planning` 状态迁移及异常/取消清理。
 */
class PlannerLifecycleManager(
    private val plannerAgent: PlannerAgent = PlannerAgent(),
    private val maxCheckpoints: Int? = null,
    private val maxCheckpointsProvider: (suspend () -> Int)? = null,
) {
    private val activeMemories = ConcurrentHashMap<String, PlannerLifecycleMemory>()

    @Volatile
    var lastCompletedMemory: PlannerLifecycleMemory.Snapshot? = null
        private set

    val manager: PlannerAgentManager by lazy { PlannerAgentManager(this) }

    fun activePlanCount(): Int = activeMemories.size

    fun activeMemorySnapshot(planId: String): PlannerLifecycleMemory.Snapshot? {
        return activeMemories[planId]?.snapshot()
    }

    suspend fun runPlan(
        goal: String,
        context: String = "",
        imageUris: List<String> = emptyList(),
        session: ChatSession? = null,
    ): PlannerExecutionResult {
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotEmpty()) { "Planning goal must not be blank" }

        // 支持上层模型通过图片 ID 或原始 URI 传递图片，统一解析并暂存
        val resolvedInitialImages = imageUris
            .map { raw -> session?.findAttachment(raw)?.uri ?: raw.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val memory = PlannerLifecycleMemory(
            goal = normalizedGoal,
            initialContext = context.trim(),
            initialImageUris = resolvedInitialImages,
        )
        currentCoroutineContext()[TaskExecutionContext]?.linkPlan(memory.planId)
        activeMemories[memory.planId] = memory

        if (resolvedInitialImages.isNotEmpty()) {
            session?.stashImageUris(resolvedInitialImages, touchTimestamp = false)
        }

        session?.enterPlanning(
            checkpointIndex = 1,
            totalCheckpoints = 0,
            currentCheckpointTitle = memory.currentCheckpointTitle(),
        )

        var lastNonEmptySummary = ""
        try {
            val safeMaxCheckpoints = resolveMaxCheckpoints()
            for (stepNumber in 1..safeMaxCheckpoints) {
                val externalInputs = session?.drainPendingPlannerInputs().orEmpty()
                memory.recordExternalInputs(externalInputs)

                currentCoroutineContext()[TaskExecutionContext]?.recordMilestone("CHECKPOINT_STARTED", stepNumber)
                val currentSnapshot = memory.snapshot()
                session?.updatePlanningCheckpoint(
                    checkpointIndex = currentSnapshot.currentCheckpointIndex,
                    totalCheckpoints = currentSnapshot.checkpoints.size,
                    currentCheckpointTitle = memory.currentCheckpointTitle(),
                )

                val stepPrompt = buildCheckpointPrompt(
                    memory = currentSnapshot,
                    stepNumber = stepNumber,
                    maxCheckpoints = safeMaxCheckpoints,
                    newExternalInputs = externalInputs,
                    sessionFileUriPool = session?.fileUriPool.orEmpty(),
                    sessionImageUriPool = session?.imageUriPool.orEmpty(),
                    sessionAttachments = session?.attachments.orEmpty(),
                )
                val stepImages = collectStepImageUris(
                    memory = currentSnapshot,
                    stepNumber = stepNumber,
                    newExternalInputs = externalInputs,
                )

                val controlToolSet = PlannerControlToolSet(
                    memory = memory,
                    session = session,
                )

                val outcome = plannerAgent.runCheckpoint(
                    promptText = stepPrompt,
                    imageUris = stepImages,
                    controlToolSet = controlToolSet,
                    maxCheckpoints = safeMaxCheckpoints,
                )

                when (outcome) {
                    is CheckpointOutcome.NextCheckpoint -> {
                        if (outcome.summary.isNotBlank()) {
                            lastNonEmptySummary = outcome.summary
                        }
                        currentCoroutineContext()[TaskExecutionContext]?.recordMilestone("CHECKPOINT_ADVANCED", stepNumber)
                        if (stepNumber < safeMaxCheckpoints) {
                            val updatedSnapshot = memory.completeCheckpointAndAdvance(
                                summary = outcome.summary,
                                nextCheckpointGoal = outcome.nextCheckpointGoal,
                                inputsForStep = externalInputs,
                            )
                            session?.updatePlanningCheckpoint(
                                checkpointIndex = updatedSnapshot.currentCheckpointIndex,
                                totalCheckpoints = updatedSnapshot.checkpoints.size,
                                currentCheckpointTitle = memory.currentCheckpointTitle(),
                            )
                        } else {
                            currentCoroutineContext()[TaskExecutionContext]?.recordMilestone("CHECKPOINT_LIMIT", stepNumber)
                            val finalSummary = outcome.summary.ifBlank { lastNonEmptySummary }
                            val trailingInputs = session?.drainPendingPlannerInputs().orEmpty()
                            memory.recordExternalInputs(trailingInputs)
                            val finalSnapshot = memory.markFinalExit(
                                content = finalSummary,
                                inputsForStep = externalInputs,
                            )
                            lastCompletedMemory = finalSnapshot
                            return PlannerExecutionResult(
                                planId = memory.planId,
                                success = true,
                                finalContent = finalSummary,
                                checkpointsCompleted = finalSnapshot.completedSteps.size,
                                trailingExternalInputs = trailingInputs,
                                memory = finalSnapshot,
                            )
                        }
                    }

                    is CheckpointOutcome.FinalExit -> {
                        currentCoroutineContext()[TaskExecutionContext]?.recordMilestone("PLANNER_FINAL_EXIT", stepNumber)
                        val finalSummary = outcome.content.ifBlank { lastNonEmptySummary }
                        val trailingInputs = session?.drainPendingPlannerInputs().orEmpty()
                        memory.recordExternalInputs(trailingInputs)
                        val finalSnapshot = memory.markFinalExit(
                            content = finalSummary,
                            inputsForStep = externalInputs,
                        )
                        lastCompletedMemory = finalSnapshot
                        return PlannerExecutionResult(
                            planId = memory.planId,
                            success = true,
                            finalContent = finalSummary,
                            checkpointsCompleted = finalSnapshot.completedSteps.size,
                            trailingExternalInputs = trailingInputs,
                            memory = finalSnapshot,
                        )
                    }
                }
            }

            val fallbackSnapshot = memory.markFinalExit(content = lastNonEmptySummary)
            lastCompletedMemory = fallbackSnapshot
            return PlannerExecutionResult(
                planId = memory.planId,
                success = true,
                finalContent = lastNonEmptySummary,
                checkpointsCompleted = fallbackSnapshot.completedSteps.size,
                memory = fallbackSnapshot,
            )
        } catch (e: CancellationException) {
            memory.markStatus(PlannerLifecycleStatus.CANCELLED)
            lastCompletedMemory = memory.snapshot()
            throw e
        } catch (e: Throwable) {
            memory.markStatus(PlannerLifecycleStatus.FAILED)
            lastCompletedMemory = memory.snapshot()
            throw e
        } finally {
            session?.exitPlanning()
            activeMemories.remove(memory.planId)
        }
    }

    private suspend fun resolveMaxCheckpoints(): Int {
        val raw = maxCheckpointsProvider?.invoke()
            ?: maxCheckpoints
            ?: if (AgentStepConfigRepository.isReady) {
                AgentStepConfigRepository.getStepConfig().plannerMaxCheckpoints
            } else {
                DEFAULT_MAX_CHECKPOINTS
            }
        return raw.coerceAtLeast(1)
    }

    companion object {
        const val DEFAULT_MAX_CHECKPOINTS = AgentStepConfig.DEFAULT_PLANNER_MAX_CHECKPOINTS

        val default: PlannerLifecycleManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            PlannerLifecycleManager()
        }

        internal fun collectStepImageUris(
            memory: PlannerLifecycleMemory.Snapshot,
            stepNumber: Int,
            newExternalInputs: List<UserInput>,
        ): List<String> {
            val combined = mutableListOf<String>()
            if (stepNumber == 1) {
                combined.addAll(memory.initialImageUris)
            }
            newExternalInputs.forEach { input ->
                combined.addAll(input.normalizedImageUris)
            }
            return combined.distinct()
        }

        internal fun buildCheckpointPrompt(
            memory: PlannerLifecycleMemory.Snapshot,
            stepNumber: Int,
            maxCheckpoints: Int,
            newExternalInputs: List<UserInput>,
            sessionFileUriPool: List<String> = emptyList(),
            sessionImageUriPool: List<String> = emptyList(),
            sessionAttachments: List<SessionAttachment> = emptyList(),
        ): String = buildString {
            appendLine("[当前检查点执行轮次]")
            appendLine("第 $stepNumber 步（最大检查点上限：$maxCheckpoints）")
            val activeCheckpoint = memory.checkpoints.firstOrNull { it.index == memory.currentCheckpointIndex }
            if (activeCheckpoint != null) {
                appendLine("当前检查点目标：#${activeCheckpoint.index} ${activeCheckpoint.title}")
            }
            appendLine()
            append(renderSnapshotForPrompt(memory))

            if (sessionFileUriPool.isNotEmpty() || sessionImageUriPool.isNotEmpty() || sessionAttachments.isNotEmpty()) {
                val allAttachments = if (sessionAttachments.isNotEmpty()) {
                    sessionAttachments
                } else {
                    ChatSession.mergeAttachmentsWithUris(
                        existing = emptyList(),
                        fileUris = sessionFileUriPool,
                        imageUris = sessionImageUriPool,
                    )
                }
                appendLine()
                appendLine()
                appendLine("[当前会话公用 URI 池（信息来源：当前会话历次输入累计暂存的文件与图片；模型内部通信请使用 id）]")
                allAttachments.filterNot { it.isImage }.forEach { att ->
                    appendLine("- [文件] id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                }
                sessionFileUriPool.forEach { uri -> appendLine("- [文件 URI] $uri") }
                allAttachments.filter { it.isImage }.forEach { att ->
                    appendLine("- [图片] id=${att.id} | 文件名: ${att.normalizedFileName} | 描述: ${att.normalizedDescription} | uri=${att.uri}")
                }
                sessionImageUriPool.forEach { uri -> appendLine("- [图片 URI] $uri") }
            }

            if (newExternalInputs.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("[真实用户中途直接发送的新输入]")
                appendLine("重要说明：以下内容是由真实人类用户本人在上一检查点执行/思考期间直接于聊天界面发送的新消息，并非系统提示、工具返回或上层对话模型代写。请将其视为用户本人的最新直接指示或补充要求，在本检查点中优先响应并按需调整后续计划：")
                newExternalInputs.forEachIndexed { idx, userInput ->
                    append("${idx + 1}. [用户本人直接输入] ")
                    if (userInput.normalizedText.isNotEmpty()) {
                        append(userInput.normalizedText)
                    } else {
                        append("（仅附带文件或图片）")
                    }
                    if (userInput.normalizedFileUris.isNotEmpty()) {
                        val fileItems = userInput.normalizedFileUris.map { uri ->
                            val att = sessionAttachments.firstOrNull { it.uri == uri || it.originalUri == uri }
                            if (att != null) "${att.id} (${att.normalizedFileName}, ${att.normalizedDescription}, $uri)" else uri
                        }
                        append(" | 文件: ${fileItems.joinToString(", ")}")
                    }
                    if (userInput.normalizedImageUris.isNotEmpty()) {
                        val imageItems = userInput.normalizedImageUris.map { uri ->
                            val att = sessionAttachments.firstOrNull { it.uri == uri || it.originalUri == uri }
                            if (att != null) "${att.id} (${att.normalizedFileName}, ${att.normalizedDescription}, $uri)" else uri
                        }
                        append(" | 图片: ${imageItems.joinToString(", ")}")
                    }
                    appendLine()
                }
            }
        }.trimEnd()

        private fun renderSnapshotForPrompt(memory: PlannerLifecycleMemory.Snapshot): String = buildString {
            appendLine("[规划总目标]")
            appendLine(memory.goal)
            if (memory.initialContext.isNotBlank()) {
                appendLine()
                appendLine("[初始背景上下文]")
                appendLine(memory.initialContext)
            }
            if (memory.checkpoints.isNotEmpty()) {
                appendLine()
                appendLine("[检查点计划状态]")
                memory.checkpoints.forEach { cp ->
                    val marker = when (cp.status) {
                        CheckpointStatus.COMPLETED -> "已完成"
                        CheckpointStatus.IN_PROGRESS -> "进行中"
                        CheckpointStatus.PENDING -> "待执行"
                    }
                    append("- #${cp.index} [$marker] ${cp.title}")
                    if (cp.summary.isNotBlank()) {
                        append(" -> ${cp.summary}")
                    }
                    appendLine()
                }
            }
            if (memory.completedSteps.isNotEmpty()) {
                appendLine()
                appendLine("[已完成检查点记忆摘要]")
                memory.completedSteps.forEach { step ->
                    appendLine("- 检查点 #${step.checkpointIndex} (${step.checkpointTitle}): ${step.summary}")
                    if (step.externalInputs.isNotEmpty()) {
                        appendLine("  └ 期间收到的真实用户中途直接输入: ${formatExternalInputsInline(step.externalInputs)}")
                    }
                }
            }
            if (memory.notes.isNotEmpty()) {
                appendLine()
                appendLine("[规划生命周期工作记忆]")
                memory.notes.forEach { (key, value) ->
                    appendLine("- $key: $value")
                }
            }
        }.trimEnd()
    }
}


private fun formatExternalInputsInline(inputs: List<UserInput>): String {
    return inputs.joinToString("；") { input ->
        buildString {
            append(input.normalizedText.ifEmpty { "（仅附带文件或图片）" })
            if (input.normalizedFileUris.isNotEmpty()) {
                append(" [文件: ${input.normalizedFileUris.joinToString(", ")}]")
            }
            if (input.normalizedImageUris.isNotEmpty()) {
                append(" [图片: ${input.normalizedImageUris.joinToString(", ")}]")
            }
        }
    }
}
