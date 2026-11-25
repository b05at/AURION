package cc.szakacs.aurion.assist

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cc.szakacs.aurion.R

/**
 * Android setzt den Standard-Assistenten bei jedem App-Update zurück (Plattform-Verhalten).
 * Aurion merkt sich, dass er Assistent war, erkennt den Verlust und hilft beim Neusetzen.
 */
object AssistantRole {
    private const val CHANNEL = "aurion_hints"
    private const val NOTIF_ID = 4711

    fun isDefault(ctx: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        } else {
            Settings.Secure.getString(ctx.contentResolver, "assistant")?.contains(ctx.packageName) == true
        }
    }.getOrDefault(false)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aurion", Context.MODE_PRIVATE)

    /** War Aurion schon einmal als Assistent eingerichtet? */
    fun wasDefault(ctx: Context) = prefs(ctx).getBoolean("wasAssistant", false)

    /** Status abgleichen: merkt sich die Einrichtung und entfernt den Hinweis, sobald alles passt. */
    fun sync(ctx: Context): Boolean {
        val now = isDefault(ctx)
        if (now) {
            prefs(ctx).edit().putBoolean("wasAssistant", true).apply()
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        }
        return now
    }

    /** Wurde Aurion (z. B. durch ein Update) als Assistent abgemeldet? */
    fun isLost(ctx: Context) = wasDefault(ctx) && !isDefault(ctx)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun openSettings(ctx: Context) {
        val intents = listOf(
            settingsIntent(),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        for (i in intents) if (runCatching { ctx.startActivity(i) }.isSuccess) return
    }

    /** Benachrichtigung mit direktem Weg zur Einstellung "Digitaler Assistent" */
    fun notifyLost(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Hinweise", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Wichtige Hinweise zu Aurion, z. B. nach Updates"
            }
        )
        val pi = PendingIntent.getActivity(
            ctx, NOTIF_ID, settingsIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setColor(0xFF2F7BFF.toInt())
            .setContentTitle("Aurion wieder als Assistent setzen")
            .setContentText("Android hat die Seitentaste nach dem Update zurückgesetzt. Tippen und Aurion wählen.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android setzt den digitalen Assistenten bei jedem App-Update zurück. Tippe hier und wähle wieder Aurion – danach funktioniert die Seitentaste wie gewohnt."
                )
            )
            .setContentIntent(pi)
            .addAction(0, "Jetzt einrichten", pi)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }
}
