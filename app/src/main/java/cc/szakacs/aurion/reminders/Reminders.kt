package cc.szakacs.aurion.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.szakacs.aurion.R
import cc.szakacs.aurion.data.Reminder
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.ui.MainActivity
import java.text.DateFormat
import java.util.Date

object Reminders {

    const val CHANNEL_ID = "aurion_reminders"
    const val CHANNEL_SILENT_ID = "aurion_reminders_silent"

    const val ACTION_FIRE = "cc.szakacs.aurion.REMINDER_FIRE"
    const val ACTION_ACK = "cc.szakacs.aurion.REMINDER_ACK"
    const val ACTION_SNOOZE = "cc.szakacs.aurion.REMINDER_SNOOZE"
    const val ACTION_DISMISSED = "cc.szakacs.aurion.REMINDER_DISMISSED"
    const val EXTRA_ID = "id"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val loud = NotificationChannel(CHANNEL_ID, "Erinnerungen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Erinnerungen von Aurion – bleiben sichtbar bis zur Quittierung"
            enableVibration(true)
            setBypassDnd(false)
        }
        val silent = NotificationChannel(CHANNEL_SILENT_ID, "Erinnerungen (still)", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Wiederhergestellte, noch offene Erinnerungen"
        }
        nm.createNotificationChannel(loud)
        nm.createNotificationChannel(silent)
    }

    /** Neue Erinnerung anlegen und Alarm stellen */
    fun create(ctx: Context, text: String, triggerAt: Long): Reminder {
        val r = Reminder(text = text, triggerAt = triggerAt)
        Store.get(ctx).upsertReminder(r)
        schedule(ctx, r)
        return r
    }

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = firePendingIntent(ctx, r.id)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            val show = PendingIntent.getActivity(
                ctx, r.notificationId, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(r.triggerAt, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.triggerAt, pi)
        }
    }

    fun cancelAlarm(ctx: Context, id: String) {
        ctx.getSystemService(AlarmManager::class.java).cancel(firePendingIntent(ctx, id))
    }

    fun delete(ctx: Context, id: String) {
        val r = Store.get(ctx).reminder(id) ?: return
        cancelAlarm(ctx, id)
        NotificationManagerCompat.from(ctx).cancel(r.notificationId)
        Store.get(ctx).deleteReminder(id)
    }

    fun acknowledge(ctx: Context, id: String) {
        val store = Store.get(ctx)
        val r = store.reminder(id) ?: return
        cancelAlarm(ctx, id)
        // Quittiert = erledigt: direkt aus der Liste entfernen
        store.deleteReminder(id)
        NotificationManagerCompat.from(ctx).cancel(r.notificationId)
    }

    fun snooze(ctx: Context, id: String, minutes: Int = 10) {
        val store = Store.get(ctx)
        val r = store.reminder(id) ?: return
        NotificationManagerCompat.from(ctx).cancel(r.notificationId)
        val updated = r.copy(triggerAt = System.currentTimeMillis() + minutes * 60_000L, fired = false)
        store.upsertReminder(updated)
        schedule(ctx, updated)
    }

    /** Nach Neustart: offene Alarme neu stellen, ausgelöste aber unquittierte wieder anzeigen */
    fun restoreAll(ctx: Context) {
        val now = System.currentTimeMillis()
        Store.get(ctx).reminders.value.filter { !it.acknowledged }.forEach { r ->
            if (r.fired || r.triggerAt <= now) {
                Store.get(ctx).upsertReminder(r.copy(fired = true))
                showNotification(ctx, r, silent = r.fired)
            } else {
                schedule(ctx, r)
            }
        }
    }

    fun showNotification(ctx: Context, r: Reminder, silent: Boolean) {
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val en = Settings.get(ctx).lastLang == "en"

        val open = PendingIntent.getActivity(
            ctx, r.notificationId, Intent(ctx, MainActivity::class.java).putExtra("tab", "reminders"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(r.triggerAt))
        val n = NotificationCompat.Builder(ctx, if (silent) CHANNEL_SILENT_ID else CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setColor(0xFF2F7BFF.toInt())
            .setContentTitle(r.text)
            .setContentText(if (en) "Reminder · $time" else "Erinnerung · $time")
            .setStyle(NotificationCompat.BigTextStyle().bigText(r.text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(silent)
            .setWhen(r.triggerAt)
            .setShowWhen(true)
            .setContentIntent(open)
            .setDeleteIntent(actionPendingIntent(ctx, ACTION_DISMISSED, r))
            .addAction(0, if (en) "✓ Acknowledge" else "✓ Quittieren", actionPendingIntent(ctx, ACTION_ACK, r))
            .addAction(0, if (en) "Snooze 10 min" else "10 Min später", actionPendingIntent(ctx, ACTION_SNOOZE, r))
            .build()
        try {
            nm.notify(r.notificationId, n)
        } catch (_: SecurityException) {
        }
    }

    private fun firePendingIntent(ctx: Context, id: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, id.hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(ACTION_FIRE).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun actionPendingIntent(ctx: Context, action: String, r: Reminder): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, (action + r.id).hashCode(),
            Intent(ctx, ReminderReceiver::class.java).setAction(action).putExtra(EXTRA_ID, r.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun formatWhen(ctx: Context, millis: Long): String {
        val en = Settings.get(ctx).lastLang == "en"
        val cal = java.util.Calendar.getInstance()
        val today = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        cal.timeInMillis = millis
        val day = cal.get(java.util.Calendar.DAY_OF_YEAR) to cal.get(java.util.Calendar.YEAR)
        val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
        val tomorrow = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
            .let { it.get(java.util.Calendar.DAY_OF_YEAR) to it.get(java.util.Calendar.YEAR) }
        return when (day) {
            today -> if (en) "today at $time" else "heute um $time"
            tomorrow -> if (en) "tomorrow at $time" else "morgen um $time"
            else -> {
                val d = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))
                if (en) "on $d at $time" else "am $d um $time"
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val store = Store.get(ctx)
        when (intent.action) {
            Reminders.ACTION_FIRE -> {
                val r = store.reminder(id) ?: return
                if (r.acknowledged) return
                val fired = r.copy(fired = true)
                store.upsertReminder(fired)
                Reminders.showNotification(ctx, fired, silent = false)
            }
            Reminders.ACTION_ACK -> Reminders.acknowledge(ctx, id)
            Reminders.ACTION_SNOOZE -> Reminders.snooze(ctx, id)
            Reminders.ACTION_DISMISSED -> {
                // Weggewischt, aber nicht quittiert -> sofort (still) wieder anzeigen
                val r = store.reminder(id) ?: return
                if (!r.acknowledged && r.fired) Reminders.showNotification(ctx, r, silent = true)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.createChannels(ctx)
        Reminders.restoreAll(ctx)
        cc.szakacs.aurion.timers.Timers.restore(ctx)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // Android setzt den Assistenten nach Updates zurück – kurz warten, dann prüfen
            val pending = goAsync()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    if (cc.szakacs.aurion.assist.AssistantRole.isLost(ctx)) cc.szakacs.aurion.assist.AssistantRole.notifyLost(ctx)
                    else cc.szakacs.aurion.assist.AssistantRole.sync(ctx)
                } finally {
                    pending.finish()
                }
            }, 4000)
        }
    }
}
