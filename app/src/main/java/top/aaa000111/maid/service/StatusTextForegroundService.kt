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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.aaa000111.maid.MainActivity
import top.aaa000111.maid.R
import top.aaa000111.maid.chat.ChatBackgroundRuntime

/** Displays active chat sessions while the dispatcher owns and executes their tasks. */
@OptIn(ExperimentalCoroutinesApi::class)
class StatusTextForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val dispatcher = ChatBackgroundRuntime.current
        val titles = dispatcher.runningSessionIds.value.map { id ->
            dispatcher.findSession(id)?.title ?: getString(R.string.chat_default_session_title)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.fgs_status_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.fgs_status_channel_desc)
                setShowBadge(false)
            },
        )
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(titles), serviceType)
        } catch (failure: RuntimeException) {
            if (failure !is IllegalStateException && failure !is SecurityException) throw failure
            Log.w(TAG, "Could not promote chat task status service", failure)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        notificationJob?.cancel()
        if (titles.isEmpty()) {
            stopSelf(startId)
        } else {
            notificationJob = scope.launch {
                combine(dispatcher.runningSessionIds, dispatcher.sessions) { ids, sessions ->
                    val byId = sessions.associateBy { it.id }
                    ids.map { byId[it] }
                }.distinctUntilChanged().flatMapLatest { runningSessions ->
                    if (runningSessions.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        combine(runningSessions.map { session ->
                            session?.state?.map { it.title }?.distinctUntilChanged()
                                ?: flowOf(getString(R.string.chat_default_session_title))
                        }) { names -> names.toList() }
                    }
                }.distinctUntilChanged().collect { activeTitles ->
                    if (activeTitles.isEmpty()) {
                        if (!dispatcher.isAnyGenerating()) stopSelf(startId)
                    } else {
                        manager.notify(NOTIFICATION_ID, buildNotification(activeTitles))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(titles: List<String>): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(getString(R.string.fgs_status_running_sessions, titles.size))
            .setContentText(titles.joinToString(" · "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
            .setContentIntent(contentIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "ChatTaskStatus"
        private const val CHANNEL_ID = "maid_status_text_channel"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, StatusTextForegroundService::class.java))
            } catch (failure: RuntimeException) {
                if (failure !is IllegalStateException && failure !is SecurityException) throw failure
                Log.w(TAG, "Could not start chat task status service", failure)
            }
        }
    }
}
