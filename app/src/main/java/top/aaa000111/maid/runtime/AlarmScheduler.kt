package top.aaa000111.maid.runtime

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.time.Instant

/** Schedules a single delivery to a receiver declared by this app. */
interface AlarmScheduler {
    /**
     * Replaces any pending alarm with the same [id] and [receiver]. The system may deliver after
     * [triggerAt]. Setting [allowWhileIdle] requests delivery during idle under Android quotas.
     */
    fun schedule(
        id: String,
        receiver: Class<out BroadcastReceiver>,
        triggerAt: Instant,
        extras: Bundle = Bundle(),
        allowWhileIdle: Boolean = false,
    )

    /** Returns whether a matching pending intent was found and canceled. */
    fun cancel(id: String, receiver: Class<out BroadcastReceiver>): Boolean

    companion object {
        const val EXTRA_ALARM_ID = "top.aaa000111.maid.extra.ALARM_ID"
    }
}

class AndroidAlarmScheduler(context: Context) : AlarmScheduler {
    private val appContext = context.applicationContext
    private val manager = requireNotNull(appContext.getSystemService(AlarmManager::class.java))

    override fun schedule(
        id: String,
        receiver: Class<out BroadcastReceiver>,
        triggerAt: Instant,
        extras: Bundle,
        allowWhileIdle: Boolean,
    ) {
        val atMillis = triggerAt.toEpochMilli()
        val intent = alarmIntent(id, receiver)
            .putExtras(extras)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, id)
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or ALARM_INTENT_FLAGS,
        )
        if (allowWhileIdle) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            manager.set(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
    }

    override fun cancel(id: String, receiver: Class<out BroadcastReceiver>): Boolean {
        val pending = PendingIntent.getBroadcast(
            appContext,
            0,
            alarmIntent(id, receiver),
            PendingIntent.FLAG_NO_CREATE or ALARM_INTENT_FLAGS,
        ) ?: return false
        manager.cancel(pending)
        pending.cancel()
        return true
    }

    private companion object {
        const val ALARM_INTENT_FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
    }

    private fun alarmIntent(id: String, receiver: Class<out BroadcastReceiver>): Intent {
        require(id.isNotBlank()) { "Alarm ID must not be blank" }
        return Intent(appContext, receiver).apply {
            action = "${appContext.packageName}.action.SCHEDULED_ALARM"
            // Intent extras do not contribute to PendingIntent identity.
            data = Uri.Builder()
                .scheme("maid-alarm")
                .authority(appContext.packageName)
                .appendPath(id)
                .build()
        }
    }
}
