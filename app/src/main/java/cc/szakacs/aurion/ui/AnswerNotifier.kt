package cc.szakacs.aurion.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.szakacs.aurion.R

/** Merkt sich, ob gerade eine Aurion-Oberfläche sichtbar ist */
object AppVisibility {
    @Volatile private var visible = 0
    val isVisible: Boolean get() = visible > 0
    fun started() { visible++ }
    fun stopped() { visible = (visible - 1).coerceAtLeast(0) }
}

/** Benachrichtigung, wenn eine Antwort fertig ist, während Aurion im Hintergrund war */
object AnswerNotifier {
    private const val CHANNEL = "aurion_answers"
    private const val ID = 4711

    fun answerReady(ctx: Context, question: String, answer: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Antworten", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Meldet, wenn Aurion eine Anfrage im Hintergrund fertig bearbeitet hat"
                }
            )
        }
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java)
                .putExtra("tab", "chat")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val plain = answer
            .replace(Regex("```[\\s\\S]*?```"), "[Code]")
            .replace(Regex("[*_`#>]+"), "")
            .trim()
        val title = "Aurion hat geantwortet"
        val q = question.trim().let { if (it.length > 60) it.take(57) + " …" else it }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setContentTitle(title)
            .setSubText(q)
            .setContentText(plain.take(140))
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain.take(1500)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(ID, n) }
    }

    fun clear(ctx: Context) {
        runCatching { NotificationManagerCompat.from(ctx).cancel(ID) }
    }
}
