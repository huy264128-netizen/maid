package top.aaa000111.maid

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import top.aaa000111.maid.runtime.AppRuntime
import top.aaa000111.maid.shizuku.ShizukuManager

class MaidApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppRuntime.init(this)
        ShizukuManager.refreshStatus()
        appScope.launch {
            AppRuntime.syncModelExecutors()
            AppRuntime.syncTinyModel()
            AppRuntime.syncSearchConfig()
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        AppRuntime.release()
        super.onTerminate()
    }
}
