package cc.szakacs.aurion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.brain.ContactMatch
import cc.szakacs.aurion.brain.DeviceActions
import cc.szakacs.aurion.timers.AurionTimer
import cc.szakacs.aurion.timers.Timers
import cc.szakacs.aurion.ui.theme.AurionColors
import kotlinx.coroutines.delay

/** Live-Uhr: aktualisiert sich mehrmals pro Sekunde */
@Composable
private fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); delay(200) }
    }
    return now
}

/** Alle laufenden Timer als Live-Leiste (oben im Chat) */
@Composable
fun TimerStrip(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { Timers.load(ctx) }
    val timers by Timers.timers.collectAsState()
    // Fortschritt in der Benachrichtigung auffrischen, solange die App offen ist
    LaunchedEffect(timers.size) {
        while (timers.isNotEmpty()) { Timers.refreshLive(ctx); delay(15_000) }
    }
    if (timers.isEmpty()) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        timers.forEach { TimerCard(it.id, compact = true) }
    }
}

/** Animierte Timer-Karte mit Ring, Restzeit und Steuerung */
@Composable
fun TimerCard(id: String, modifier: Modifier = Modifier, compact: Boolean = false) {
    val ctx = LocalContext.current
    val timers by Timers.timers.collectAsState()
    val t = timers.firstOrNull { it.id == id }
    val now = rememberNow()
    if (t == null) {
        if (!compact) GlassCard(modifier.fillMaxWidth()) {
            Text("✓ Timer beendet", color = AurionColors.TextMid, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    val remaining = t.remaining(now)
    val progress = if (t.durationMs > 0) (remaining.toFloat() / t.durationMs).coerceIn(0f, 1f) else 0f
    val pulse = rememberInfiniteTransition(label = "ring").animateFloat(
        0.55f, 1f, infiniteRepeatable(tween(if (t.ringing) 400 else 1200), RepeatMode.Reverse), label = "pulse"
    ).value

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    if (t.ringing) listOf(Color(0xFF2F7BFF), Color(0xFF5B5BF0)) else listOf(Color(0xFF0E2350), Color(0xFF123C6E))
                )
            )
            .border(1.dp, if (t.ringing) Color.White.copy(alpha = pulse) else Color(0x33FFFFFF), RoundedCornerShape(24.dp))
            .padding(if (compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TimerRing(progress, t.paused, t.ringing, pulse, if (compact) 56.dp else 96.dp)
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.label ?: "Timer", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                if (t.ringing) "Zeit ist um!" else Timers.format(remaining),
                color = Color.White,
                fontSize = if (compact) 28.sp else 42.sp,
                fontWeight = FontWeight.Light
            )
            Text(
                when {
                    t.ringing -> "Tippe auf Stopp"
                    t.paused -> "Pausiert"
                    else -> "von ${Timers.format(t.durationMs)}"
                },
                color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!t.ringing) {
                RoundButton(if (t.paused) "▶" else "⏸") { if (t.paused) Timers.resume(ctx, t.id) else Timers.pause(ctx, t.id) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RoundButton("+1") { Timers.addTime(ctx, t.id) }
                RoundButton("✕") { Timers.stop(ctx, t.id) }
            }
        }
    }
}

@Composable
private fun TimerRing(progress: Float, paused: Boolean, ringing: Boolean, pulse: Float, size: Dp) {
    Canvas(Modifier.size(size)) {
        val stroke = this.size.minDimension * 0.1f
        val d = this.size.minDimension - stroke
        val topLeft = Offset(stroke / 2, stroke / 2)
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, topLeft, Size(d, d), style = Stroke(stroke))
        drawArc(
            Brush.sweepGradient(listOf(AurionColors.Cyan, AurionColors.Blue, AurionColors.Indigo, AurionColors.Cyan)),
            -90f, 360f * (if (ringing) 1f else progress), false, topLeft, Size(d, d),
            alpha = if (paused || ringing) pulse else 1f,
            style = Stroke(stroke, cap = StrokeCap.Round)
        )
        // leuchtender Punkt an der Spitze
        if (!ringing && progress > 0f) {
            val a = Math.toRadians((-90.0 + 360.0 * progress))
            val r = d / 2
            drawCircle(Color.White, stroke * 0.45f, Offset(center.x + (r * Math.cos(a)).toFloat(), center.y + (r * Math.sin(a)).toFloat()))
        }
    }
}

@Composable
private fun RoundButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Anruf-Karte: Kontakt(e) mit Anruf-Knopf, ggf. Berechtigung anfragen */
@Composable
fun CallCard(contacts: List<ContactMatch>?, needsPermission: Boolean, title: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val device = remember { DeviceActions(ctx) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    GlassCard(modifier) {
        if (needsPermission) {
            Text("Anrufe per Sprache", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
            Text(
                "Aurion braucht Zugriff auf Kontakte (zum Finden) und Telefon (zum direkten Anrufen). Danach einfach nochmal fragen" +
                    (title?.let { ": „Ruf $it an“" } ?: "."),
                color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(10.dp))
            PrimaryButton("Zugriff erlauben", Modifier.fillMaxWidth()) {
                perm.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE))
            }
            return@GlassCard
        }
        contacts.orEmpty().forEach { c ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { device.call(c.number) }
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(AurionColors.Blue, AurionColors.Cyan))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(c.name.firstOrNull()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
                    Text("${c.type} · ${c.number}", color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
                }
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color(0x332F7BFF)).border(1.dp, AurionColors.Cyan, CircleShape),
                    contentAlignment = Alignment.Center
                ) { Text("📞", fontSize = 18.sp) }
            }
        }
    }
}
