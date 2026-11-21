package top.aaa000111.maid.memory

import ai.koog.agents.memory.model.Concept
import ai.koog.agents.memory.model.FactType
import ai.koog.agents.memory.model.MemorySubject

/**
 * Maid 领域自定义的 Koog 记忆主题。
 *
 * 注意：Koog 的 `MemorySubject.Serializer` 通过 `MemorySubject.registeredSubjects`
 * 按 `name` 反序列化对象；因此在从磁盘读取记忆之前必须确保各 `data object` 已完成类初始化。
 */
object MaidMemorySubjects {

    data object User : MemorySubject() {
        override val name: String = "user"
        override val promptDescription: String =
            "User identity, preferences, habits, and personal instructions"
        override val priorityLevel: Int = 1
    }

    data object Device : MemorySubject() {
        override val name: String = "device"
        override val promptDescription: String =
            "Device environment, installed apps, privilege backend, and system capabilities"
        override val priorityLevel: Int = 2
    }

    data object Workflow : MemorySubject() {
        override val name: String = "workflow"
        override val promptDescription: String =
            "Reusable automation steps, UI navigation patterns, and task execution history"
        override val priorityLevel: Int = 3
    }

    val all: List<MemorySubject> = listOf(
        User,
        Device,
        Workflow,
        MemorySubject.Everything,
    )

    /**
     * 触发所有内置与自定义 `MemorySubject` 初始化，确保反序列化时已完成注册。
     */
    fun ensureRegistered() {
        all.forEach { it.name }
    }
}

/**
 * Maid 常用的标准记忆概念定义。
 */
object MaidMemoryConcepts {

    val userPreference = Concept(
        keyword = "user-preference",
        description = "User preferences, response style, and personal habits",
        factType = FactType.MULTIPLE,
    )

    val deviceEnvironment = Concept(
        keyword = "device-environment",
        description = "Device model, Android version, privilege mode, and environment notes",
        factType = FactType.SINGLE,
    )

    val appHabit = Concept(
        keyword = "app-habit",
        description = "Frequently used apps and app-specific usage conventions",
        factType = FactType.MULTIPLE,
    )

    val workflowNote = Concept(
        keyword = "workflow-note",
        description = "Learned automation procedures and device operation tips",
        factType = FactType.MULTIPLE,
    )

    val all: List<Concept> = listOf(
        userPreference,
        deviceEnvironment,
        appHabit,
        workflowNote,
    )
}
