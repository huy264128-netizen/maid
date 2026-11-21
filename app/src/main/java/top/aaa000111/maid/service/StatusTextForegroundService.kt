package top.aaa000111.maid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.aaa000111.maid.MainActivity
import top.aaa000111.maid.R

/**
 * 仅用于显示文本的轻量前台服务。
 *
 * 通过 [start] / [updateText] 启动或更新通知栏文本，通过 [stop] 停止。
 */
class StatusTextForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.fgs_status_default_title)
        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.fgs_status_default_text)

        ensureNotificationChannel()
        val notification = buildNotification(title = title, text = text)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        _isRunning.value = true
        _currentText.value = text
        return START_STICKY
    }

    override fun onDestroy() {
        _isRunning.value = false
        super.onDestroy()
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        _isRunning.value = false
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.fgs_status_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.fgs_status_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, text: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "maid_status_text_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_OR_UPDATE = "top.aaa000111.maid.action.STATUS_FGS_UPDATE"
        const val ACTION_STOP = "top.aaa000111.maid.action.STATUS_FGS_STOP"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        private val _currentText = MutableStateFlow("")
        val currentText: StateFlow<String> = _currentText.asStateFlow()

        fun start(
            context: Context,
            text: String,
            title: String? = null,
        ) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, StatusTextForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra(EXTRA_TEXT, text)
                if (!title.isNullOrBlank()) {
                    putExtra(EXTRA_TITLE, title)
                }
            }
            appContext.startForegroundService(intent)
        }

        fun updateText(
            context: Context,
            text: String,
            title: String? = null,
        ) {
            start(context = context, text = text, title = title)
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, StatusTextForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            appContext.stopService(intent)
        }
    }
}
