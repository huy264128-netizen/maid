package top.aaa000111.maid.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface

/** The UserService owns the platform displays and releases them when the client binder dies. */
internal class TrustedVirtualDisplays(private val appContext: Context) {
    private class Entry(
        val display: VirtualDisplay,
        val surface: Surface,
        val deathRecipient: IBinder.DeathRecipient,
    )

    private val ownedDisplays = mutableMapOf<IBinder, Entry>()
    private val handler = Handler(Looper.getMainLooper())

    @Synchronized
    fun create(owner: IBinder, name: String, width: Int, height: Int, densityDpi: Int, surface: Surface): Int {
        var committed = false
        try {
            enforceAppCaller()
            require(name.isNotBlank() && width > 0 && height > 0 && densityDpi > 0 && surface.isValid)
            check(owner !in ownedDisplays) { "A display already exists for this owner" }
            if (appContext.checkPermission(CREATE_TRUSTED_DISPLAY, Process.myPid(), Process.myUid()) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                throw SecurityException("Shizuku UserService lacks ADD_TRUSTED_DISPLAY")
            }
            val context = if (Process.myUid() == SHELL_UID) {
                appContext.createPackageContext("com.android.shell", 0)
            } else {
                appContext
            }
            val displayManager = checkNotNull(context.getSystemService(DisplayManager::class.java)) {
                "DisplayManager unavailable in Shizuku UserService"
            }
            val deathRecipient = IBinder.DeathRecipient { releaseOwnedDisplay(owner) }
            owner.linkToDeath(deathRecipient, 0)
            try {
                val display = displayManager.createVirtualDisplay(
                    name,
                    width,
                    height,
                    densityDpi,
                    surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or TRUSTED or DESTROY_CONTENT_ON_REMOVAL,
                    object : VirtualDisplay.Callback() {
                        override fun onStopped() = releaseOwnedDisplay(owner)
                    },
                    handler,
                ) ?: error("Android did not create a trusted virtual display")
                val id = display.display?.displayId ?: Display.DEFAULT_DISPLAY
                if (id <= Display.DEFAULT_DISPLAY) {
                    display.release()
                    error("Android did not return a valid secondary display ID")
                }
                ownedDisplays[owner] = Entry(display, surface, deathRecipient)
                committed = true
                return id
            } finally {
                if (!committed) owner.unlinkToDeath(deathRecipient, 0)
            }
        } finally {
            if (!committed) surface.release()
        }
    }

    fun release(owner: IBinder) {
        enforceAppCaller()
        releaseOwnedDisplay(owner)
    }

    @Synchronized
    private fun releaseOwnedDisplay(owner: IBinder) {
        val entry = ownedDisplays.remove(owner) ?: return
        try {
            try {
                owner.unlinkToDeath(entry.deathRecipient, 0)
            } finally {
                entry.display.release()
            }
        } finally {
            entry.surface.release()
        }
    }

    private fun enforceAppCaller() {
        if (Binder.getCallingUid() != appContext.applicationInfo.uid) {
            throw SecurityException("Only Maid can manage its trusted virtual displays")
        }
    }

    private companion object {
        const val SHELL_UID = 2000
        const val CREATE_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
        // Framework System API flags. Destroying content on removal keeps tasks off the main display.
        const val TRUSTED = 1 shl 10
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    }
}
