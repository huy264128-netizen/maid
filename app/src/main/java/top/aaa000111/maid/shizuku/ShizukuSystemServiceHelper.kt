package top.aaa000111.maid.shizuku

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper

/**
 * 封装 Shizuku 的系统服务 Binder 代理能力（基于 [ShizukuBinderWrapper] 与 [Shizuku.transactRemote]）。
 */
object ShizukuSystemServiceHelper {

    /**
     * 获取原始系统服务 Binder（非特权代理）。
     */
    @SuppressLint("PrivateApi")
    fun getRawSystemService(name: String): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val method = serviceManager.getMethod("getService", String::class.java)
            method.invoke(null, name) as? IBinder
        }.getOrNull()
    }

    /**
     * 通过非阻塞方式检查系统服务是否已注册。
     */
    @SuppressLint("PrivateApi")
    fun checkRawSystemService(name: String): IBinder? {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val method = serviceManager.getMethod("checkService", String::class.java)
            method.invoke(null, name) as? IBinder
        }.getOrNull()
    }

    /**
     * 列出当前系统注册的所有 Binder 服务名。
     */
    @SuppressLint("PrivateApi")
    fun listSystemServices(): List<String> {
        return runCatching {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val method = serviceManager.getMethod("listServices")
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null) as? Array<String>)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * 使用 [ShizukuBinderWrapper] 包装系统服务 Binder，使其 IPC 调用经由 Shizuku 服务端（UID 0/2000）转发。
     */
    fun getWrappedSystemService(name: String): IBinder? {
        if (!Shizuku.pingBinder()) return null
        val original = getRawSystemService(name) ?: return null
        return ShizukuBinderWrapper(original)
    }

    /**
     * 将任意已获取的 [IBinder] 包装为 Shizuku 代理 Binder。
     */
    fun wrapBinder(original: IBinder): IBinder = ShizukuBinderWrapper(original)

    /**
     * 直接通过 [Shizuku.transactRemote] 发起远程 Binder 事务。
     */
    fun transactRemote(data: Parcel, reply: Parcel?, flags: Int = 0) {
        Shizuku.transactRemote(data, reply, flags)
    }
}
