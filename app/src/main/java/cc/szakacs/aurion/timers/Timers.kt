package cc.szakacs.aurion.timers

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.szakacs.aurion.R
import cc.szakacs.aurion.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class AurionTimer(
    val id: String = UUID.randomUUID().toString(),
    val label: String?,
    val durationMs: Long,
    /** Endzeitpunkt, solange der Timer läuft */
    val endAt: Long,
    /** Restzeit, wenn pausiert */
    val pausedRemaining: Long? = null,
    /** abgelaufen, klingelt bis gestoppt */
    val ringing: Boolean = false,
) {
    val paused get() = pausedRemaining != null
    fun remaining(now: Long = System.currentTimeMillis()) = (pausedRemaining ?: (endAt - now)).coerceAtLeast(0)
    val notifId get() = 9000 + (id.hashCode() and 0xfff)

    fun toJson() = JSONObject().put("id", id).put("label", label ?: "").put("dur", durationMs).put("end", endAt)
        .put("paused", pausedRemaining ?: -1L).put("ringing", ringing)

    companion object {
        fun fromJson(o: JSONObject) = AurionTimer(
            o.getString("id"), o.optString("label").ifBlank { null }, o.getLong("dur"), o.getLong("end"),
            o.optLong("paused", -1L).takeIf { it >= 0 }, o.optBoolean("ringing")
        )
    }
}

/**
 * Timer mit "Live Activity": eine laufende Benachrichtigung zählt selbst herunter (Systemuhr),
 * mit Pause / +1 Min / Stopp. Am Ende klingelt ein Alarm, bis er gestoppt wird.
 */
object Timers {
    private const val CH_LIVE = "aurion_timer_live2"
    private const val CH_ALARM = "aurion_timer_alarm"
    const val ACTION_FINISH = "cc.szakacs.aurion.TIMER_FINISH"
    const val ACTION_PAUSE = "cc.szakacs.aurion.TIMER_PAUSE"
    const val ACTION_RESUME = "cc.szakacs.aurion.TIMER_RESUME"
    const val ACTION_ADD = "cc.szakacs.aurion.TIMER_ADD"
    const val ACTION_STOP = "cc.szakacs.aurion.TIMER_STOP"

    private val _timers = MutableStateFlow<List<AurionTimer>>(emptyList())
    val timers: StateFlow<List<AurionTimer>> = _timers
    private var loaded = false

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aurion_timers", Context.MODE_PRIVATE)

    @Synchronized
    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val arr = runCatching { JSONArray(prefs(ctx).getString("list", "[]")) }.getOrDefault(JSONArray())
        _timers.value = (0 until arr.length()).mapNotNull { runCatching { AurionTimer.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun save(ctx: Context, list: List<AurionTimer>) {
        _timers.value = list
        prefs(ctx).edit().putString("list", JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
    }

    fun get(ctx: Context, id: String): AurionTimer? { load(ctx); return _timers.value.firstOrNull { it.id == id } }

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("aurion_timer_live")
        nm.createNotificationChannel(NotificationChannel(CH_LIVE, "Laufende Timer (Live)", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Live-Anzeige laufender Timer – auch auf dem Sperrbildschirm"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        nm.createNotificationChannel(NotificationChannel(CH_ALARM, "Timer-Alarm", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Klingelt, wenn ein Timer abgelaufen ist"
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
            setBypassDnd(true)
        })
    }

    // ---------------- Aktionen ----------------

    fun start(ctx: Context, durationMs: Long, label: String?): AurionTimer {
        load(ctx)
        val t = AurionTimer(label = label, durationMs = durationMs, endAt = System.currentTimeMillis() + durationMs)
        save(ctx, _timers.value + t)
        schedule(ctx, t)
        showLive(ctx, t)
        return t
    }

    fun pause(ctx: Context, id: String) {
        val t = get(ctx, id) ?: return
        if (t.paused || t.ringing) return
        val p = t.copy(pausedRemaining = t.remaining())
        replace(ctx, p)
        cancelAlarm(ctx, id)
        showLive(ctx, p)
    }

    fun resume(ctx: Context, id: String) {
        val t = get(ctx, id) ?: return
        val rem = t.pausedRemaining ?: return
        val r = t.copy(endAt = System.currentTimeMillis() + rem, pausedRemaining = null)
        replace(ctx, r)
        schedule(ctx, r)
        showLive(ctx, r)
    }

    fun addTime(ctx: Context, id: String, ms: Long = 60_000L) {
        val t = get(ctx, id) ?: return
        val n = when {
            t.ringing -> t.copy(endAt = System.currentTimeMillis() + ms, ringing = false, durationMs = ms)
            t.paused -> t.copy(pausedRemaining = t.pausedRemaining!! + ms, durationMs = t.durationMs + ms)
            else -> t.copy(endAt = t.endAt + ms, durationMs = t.durationMs + ms)
        }
        replace(ctx, n)
        NotificationManagerCompat.from(ctx).cancel(n.notifId + 1)
        if (!n.paused) schedule(ctx, n)
        showLive(ctx, n)
    }

    fun stop(ctx: Context, id: String) {
        val t = get(ctx, id) ?: return
        cancelAlarm(ctx, id)
        NotificationManagerCompat.from(ctx).cancel(t.notifId)
        NotificationManagerCompat.from(ctx).cancel(t.notifId + 1)
        save(ctx, _timers.value.filterNot { it.id == id })
    }

    fun stopAll(ctx: Context) { load(ctx); _timers.value.forEach { stop(ctx, it.id) } }

    /** Alarm ausgelöst */
    fun finish(ctx: Context, id: String) {
        val t = get(ctx, id) ?: return
        if (t.paused) return
        val r = t.copy(ringing = true)
        replace(ctx, r)
        NotificationManagerCompat.from(ctx).cancel(t.notifId)
        showAlarm(ctx, r)
    }

    /** Nach Neustart/Update: Alarme neu stellen und Live-Anzeigen wiederherstellen */
    fun restore(ctx: Context) {
        load(ctx)
        createChannels(ctx)
        val now = System.currentTimeMillis()
        _timers.value.forEach { t ->
            when {
                t.ringing -> showAlarm(ctx, t)
                t.paused -> showLive(ctx, t)
                t.endAt <= now -> finish(ctx, t.id)
                else -> { schedule(ctx, t); showLive(ctx, t) }
            }
        }
    }

    private fun replace(ctx: Context, t: AurionTimer) = save(ctx, _timers.value.map { if (it.id == t.id) t else it })

    // ---------------- AlarmManager ----------------

    private fun finishIntent(ctx: Context, id: String) = PendingIntent.getBroadcast(
        ctx, ("finish$id").hashCode(),
        Intent(ctx, TimerReceiver::class.java).setAction(ACTION_FINISH).putExtra("id", id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun schedule(ctx: Context, t: AurionTimer) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = finishIntent(ctx, t.id)
        val canExact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (canExact) am.setAlarmClock(AlarmManager.AlarmClockInfo(t.endAt, openApp(ctx, t.notifId)), pi)
        else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t.endAt, pi)
    }

    private fun cancelAlarm(ctx: Context, id: String) = ctx.getSystemService(AlarmManager::class.java).cancel(finishIntent(ctx, id))

    // ---------------- Benachrichtigungen ----------------

    private fun openApp(ctx: Context, code: Int) = PendingIntent.getActivity(
        ctx, code, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(ctx: Context, action: String, t: AurionTimer) = PendingIntent.getBroadcast(
        ctx, (action + t.id).hashCode(),
        Intent(ctx, TimerReceiver::class.java).setAction(action).putExtra("id", t.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    fun format(ms: Long): String {
        val s = (ms + 999) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    private fun showLive(ctx: Context, t: AurionTimer) {
        val title = t.label?.let { "⏱ $it" } ?: "⏱ Timer"
        val total = (t.durationMs / 1000).toInt().coerceAtLeast(1)
        val done = ((t.durationMs - t.remaining()) / 1000).toInt().coerceIn(0, total)
        val b = NotificationCompat.Builder(ctx, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setColor(0xFF4FD8FF.toInt())
            .setContentTitle(title)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setProgress(total, done, false)
            .setContentIntent(openApp(ctx, t.notifId))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(null)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Android 16+: als "Live Update" anfragen (Sperrbildschirm, Statusleisten-Chip, Samsung Now Bar)
            .addExtras(android.os.Bundle().apply {
                putBoolean("android.requestPromotedOngoing", true)
                if (!t.paused) putCharSequence("android.shortCriticalText", "${(t.remaining() + 59_999) / 60_000} min")
            })
        if (t.paused) {
            b.setContentText("Pausiert · noch ${format(t.remaining())}")
                .setUsesChronometer(false)
                .addAction(0, "▶ Weiter", action(ctx, ACTION_RESUME, t))
        } else {
            // Systemuhr zählt live herunter – auch auf dem Sperrbildschirm
            b.setContentText("Läuft · fertig um " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t.endAt)))
                .setWhen(t.endAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(0, "⏸ Pause", action(ctx, ACTION_PAUSE, t))
        }
        b.addAction(0, "+1 Min", action(ctx, ACTION_ADD, t))
            .addAction(0, "✕ Stopp", action(ctx, ACTION_STOP, t))
        runCatching { NotificationManagerCompat.from(ctx).notify(t.notifId, b.build()) }
    }

    private fun showAlarm(ctx: Context, t: AurionTimer) {
        val full = PendingIntent.getActivity(
            ctx, t.notifId + 2,
            Intent(ctx, MainActivity::class.java).putExtra("timerRinging", t.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_ALARM)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setColor(0xFF2F7BFF.toInt())
            .setContentTitle(t.label?.let { "⏰ $it – Zeit ist um!" } ?: "⏰ Timer abgelaufen")
            .setContentText("${format(t.durationMs)} sind vorbei")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setDeleteIntent(action(ctx, ACTION_STOP, t))
            .addAction(0, "✕ Stopp", action(ctx, ACTION_STOP, t))
            .addAction(0, "+1 Min", action(ctx, ACTION_ADD, t))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT // Ton wiederholt sich, bis gestoppt
        runCatching { NotificationManagerCompat.from(ctx).notify(t.notifId + 1, n) }
    }

    /** Laufende Anzeige aktualisieren (Fortschrittsbalken), solange die App aktiv ist */
    fun refreshLive(ctx: Context) {
        load(ctx)
        _timers.value.filter { !it.ringing && !it.paused }.forEach { showLive(ctx, it) }
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Timers.createChannels(ctx)
        when (intent.action) {
            Timers.ACTION_FINISH -> Timers.finish(ctx, id)
            Timers.ACTION_PAUSE -> Timers.pause(ctx, id)
            Timers.ACTION_RESUME -> Timers.resume(ctx, id)
            Timers.ACTION_ADD -> Timers.addTime(ctx, id)
            Timers.ACTION_STOP -> Timers.stop(ctx, id)
        }
    }
}
