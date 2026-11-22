package top.aaa000111.maid.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration

/** 在协程内等待指定时长。异步等待随传入的作用域取消。 */
object TimedWait {
    /** 挂起当前协程。零时长在检查取消状态后立即完成。 */
    suspend fun await(duration: Duration) {
        requireValidDuration(duration)
        currentCoroutineContext().ensureActive()
        delay(duration)
    }

    /** 提交独立的计时任务，句柄可等待、检查状态或取消。 */
    fun start(scope: CoroutineScope, duration: Duration): Deferred<Unit> {
        requireValidDuration(duration)
        return scope.async { delay(duration) }
    }

    private fun requireValidDuration(duration: Duration) {
        require(duration.isFinite() && duration >= Duration.ZERO) { "Wait duration must be finite and non-negative" }
    }
}
