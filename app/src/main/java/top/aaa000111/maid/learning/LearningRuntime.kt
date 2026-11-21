package top.aaa000111.maid.learning

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.aaa000111.maid.skill.SkillRepository

/** 初始化只绑定路径；首次后台任务按需恢复证据并安装内置技能，避免启动时阻塞主线程。 */
object LearningRuntime {
    private val initialization = Mutex()
    @Volatile private var application: Context? = null
    @Volatile private var current: TaskRecorder? = null

    fun init(context: Context) {
        application = context.applicationContext
    }

    suspend fun recorderOrNull(): TaskRecorder? {
        current?.let { return it }
        val app = application ?: return null
        return initialization.withLock {
            current ?: withContext(Dispatchers.IO) {
                val root = app.noBackupFilesDir.toPath().resolve("learning")
                val evidence = TaskEvidenceRepository(root.resolve("tasks"))
                evidence.recoverInterrupted()
                val skills = SkillRepository(root.resolve("skills"))
                val snapshot = skills.snapshot()
                // 用户安装的同名技能不被内置种子覆盖。
                for (id in listOf("task-verification", "android-app-download")) {
                    val installed = snapshot.catalog[id]
                    if (installed == null || installed.source == "bundled") {
                        val content = app.assets.open("skills/$id/SKILL.md").bufferedReader().use { it.readText() }
                        skills.install(mapOf("SKILL.md" to content), source = "bundled")
                    }
                }
                TaskRecorder(evidence, skills, buildJsonObject {
                    put("androidApi", android.os.Build.VERSION.SDK_INT)
                    put("appVersion", app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty())
                }).also { current = it }
            }
        }
    }

    suspend fun <T> withTask(sessionId: String, block: suspend () -> T): T {
        if (currentCoroutineContext()[TaskExecutionContext] != null) return block()
        val recorder = recorderOrNull()
        return if (recorder != null) recorder.run(sessionId, block = block) else block()
    }

    internal fun resetForTest() {
        current = null
        application = null
    }
}
