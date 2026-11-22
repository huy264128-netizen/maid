package top.aaa000111.maid.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class TimedWaitTest {
    @Test
    fun waitsCanCompleteAndAwaitingDoesNotConsumeTheResult() = runBlocking {
        withTimeout(5_000) {
            TimedWait.await(Duration.ZERO)
            val timer = TimedWait.start(this, 1.milliseconds)
            timer.await()
            timer.await()
            assertTrue(timer.isCompleted)
            assertFalse(timer.isCancelled)
        }
    }

    @Test
    fun asyncWaitReturnsImmediatelyAndFollowsItsOwner() = runBlocking {
        val owner = Job(coroutineContext[Job])
        val timer = TimedWait.start(CoroutineScope(coroutineContext + owner), 1.hours)
        assertFalse(timer.isCompleted)
        withTimeout(5_000) { owner.cancelAndJoin() }
        assertTrue(timer.isCancelled)
    }

    @Test
    fun rejectsInvalidDurationsBeforeStartingAsyncWork() = runBlocking {
        for (duration in listOf((-1).milliseconds, Duration.INFINITE)) {
            assertTrue(runCatching { TimedWait.await(duration) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { TimedWait.start(this, duration) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
