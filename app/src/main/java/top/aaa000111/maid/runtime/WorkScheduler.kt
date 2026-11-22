package top.aaa000111.maid.runtime

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow

/** Schedules persistent work by a stable name supplied by the caller. */
interface WorkScheduler {
    fun enqueueOneTime(
        name: String,
        request: OneTimeWorkRequest,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
    ): Operation

    fun enqueuePeriodic(
        name: String,
        request: PeriodicWorkRequest,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
    ): Operation

    /** Includes retained execution history for this name. Each entry has its own request ID. */
    fun observe(name: String): Flow<List<WorkInfo>>

    fun cancel(name: String): Operation
}

class AndroidWorkScheduler(context: Context) : WorkScheduler {
    private val manager = WorkManager.getInstance(context.applicationContext)

    override fun enqueueOneTime(
        name: String,
        request: OneTimeWorkRequest,
        policy: ExistingWorkPolicy,
    ): Operation {
        require(name.isNotBlank()) { "Work name must not be blank" }
        return manager.enqueueUniqueWork(name, policy, request)
    }

    override fun enqueuePeriodic(
        name: String,
        request: PeriodicWorkRequest,
        policy: ExistingPeriodicWorkPolicy,
    ): Operation {
        require(name.isNotBlank()) { "Work name must not be blank" }
        return manager.enqueueUniquePeriodicWork(name, policy, request)
    }

    override fun observe(name: String): Flow<List<WorkInfo>> {
        require(name.isNotBlank()) { "Work name must not be blank" }
        return manager.getWorkInfosForUniqueWorkFlow(name)
    }

    override fun cancel(name: String): Operation {
        require(name.isNotBlank()) { "Work name must not be blank" }
        return manager.cancelUniqueWork(name)
    }
}
