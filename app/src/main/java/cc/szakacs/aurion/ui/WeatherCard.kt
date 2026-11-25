package cc.szakacs.aurion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.weather.Forecast
import cc.szakacs.aurion.weather.WeatherService
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val SunYellow = Color(0xFFFFD45C)
private val SunCore = Color(0xFFFFF1B8)
private val CloudLight = Color(0xFFE8EEF7)
private val CloudDark = Color(0xFF9AA8BF)
private val RainBlue = Color(0xFF7FC8FF)
private val SnowWhite = Color(0xFFF4F8FF)

private enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, RAIN, SNOW, STORM }

private fun sky(code: Int): Sky = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    3 -> Sky.CLOUDY
    45, 48 -> Sky.FOG
    in 51..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.STORM
    else -> Sky.CLOUDY
}

private fun background(code: Int, isDay: Boolean): List<Color> = when {
    !isDay -> listOf(Color(0xFF0B1033), Color(0xFF1B1F5E))
    else -> when (sky(code)) {
        Sky.CLEAR -> listOf(Color(0xFF1C6DFF), Color(0xFF3FC3FF))
        Sky.PARTLY -> listOf(Color(0xFF2A62D6), Color(0xFF6BA9E8))
        Sky.CLOUDY, Sky.FOG -> listOf(Color(0xFF3B4A66), Color(0xFF6E7E99))
        Sky.RAIN -> listOf(Color(0xFF1D2B4A), Color(0xFF3D5A80))
        Sky.SNOW -> listOf(Color(0xFF5C7BA6), Color(0xFFA9C4E4))
        Sky.STORM -> listOf(Color(0xFF151A3A), Color(0xFF2E2A5C))
    }
}

/**
 * Animierte Wetterkarte: aktuelle Werte, Detail-Chips und 5-Tage-Verlauf.
 */
@Composable
fun WeatherCard(f: Forecast, selectedDay: Int, modifier: Modifier = Modifier) {
    val en = false // Karten-Beschriftung auf Deutsch
    val today = f.days.first()
    val sel = f.days.getOrElse(selectedDay) { today }
    val code = if (selectedDay == 0) f.currentCode else sel.code
    val isDay = if (selectedDay == 0) f.isDay else true

    // Einblenden + hochzählende Temperatur
    val appear = remember { Animatable(0f) }
    val temp = remember { Animatable(0f) }
    val shownTemp = if (selectedDay == 0) f.currentTemp else sel.tMax
    LaunchedEffect(f) {
        appear.snapTo(0f); temp.snapTo(0f)
        appear.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(f, shownTemp) { temp.animateTo(shownTemp.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }

    Column(
        modifier
            .graphicsLayer {
                alpha = appear.value
                translationY = (1f - appear.value) * 40f
                scaleX = 0.96f + 0.04f * appear.value
                scaleY = 0.96f + 0.04f * appear.value
            }
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(background(code, isDay)))
            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(28.dp))
    ) {
        Box(Modifier.fillMaxWidth()) {
            // Hintergrund-Effekte (Regen/Schnee über die ganze Kopfzeile)
            SkyParticles(sky(code), Modifier.matchParentSize())
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (f.place.name == "hier") "Dein Standort" else f.place.name,
                        color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium
                    )
                    if (selectedDay != 0) {
                        Text(
                            sel.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN),
                            color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        "${temp.value.roundToInt()}°",
                        color = Color.White, fontSize = 64.sp, fontWeight = FontWeight.Thin
                    )
                    Text(WeatherService.describe(code, en).replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        (if (selectedDay == 0) "Gefühlt ${WeatherService.deg(f.currentFeels)}°  ·  " else "") +
                            "↑ ${WeatherService.deg(sel.tMax)}°  ↓ ${WeatherService.deg(sel.tMin)}°",
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium
                    )
                }
                WeatherIcon(code, isDay, 118.dp)
            }
        }

        // Detail-Chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Chip("💧", "Regen", "${sel.rainProb} %" + if (sel.rainSum >= 0.1) " · ${"%.1f".format(sel.rainSum)} mm" else "")
            if (sel.snowProb > 0 || sel.snowSum > 0 || sel.tMin <= 2.0) {
                Chip("❄", "Schnee", "${sel.snowProb} %" + if (sel.snowSum >= 0.1) " · ${"%.1f".format(sel.snowSum)} cm" else "")
            }
            Chip("༄", "Wind", if (selectedDay == 0) "${WeatherService.deg(f.currentWind)} km/h" else "${WeatherService.deg(sel.windMax)} km/h")
            if (sel.gustMax >= 30) Chip("≋", "Böen", "${WeatherService.deg(sel.gustMax)} km/h")
            if (selectedDay == 0) Chip("◌", "Feuchte", "${f.humidity} %")
            Chip("☀", "UV", "%.0f".format(sel.uvMax))
            Chip("↑", "Sonne", "${sel.sunrise} – ${sel.sunset}")
        }

        // 5-Tage-Verlauf
        val days = f.days.take(5)
        val lo = days.minOf { it.tMin }
        val hi = days.maxOf { it.tMax }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0x26000000))
                .padding(vertical = 12.dp, horizontal = 6.dp)
        ) {
            days.forEachIndexed { i, d ->
                val a = remember { Animatable(0f) }
                LaunchedEffect(f) {
                    a.snapTo(0f)
                    kotlinx.coroutines.delay(250L + i * 110L)
                    a.animateTo(1f, tween(500, easing = FastOutSlowInEasing))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 30f }
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (i == selectedDay) Color(0x33FFFFFF) else Color.Transparent)
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        if (i == 0) "Heute" else d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).removeSuffix("."),
                        color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge
                    )
                    WeatherIcon(d.code, true, 34.dp)
                    Text("${WeatherService.deg(d.tMax)}°", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    TempBar(d.tMin, d.tMax, lo, hi, a.value)
                    Text("${WeatherService.deg(d.tMin)}°", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                    val p = if (d.snowProb >= d.rainProb && d.snowProb > 0) "❄ ${d.snowProb}%" else "💧 ${d.rainProb}%"
                    Text(p, color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

@Composable
private fun Chip(icon: String, label: String, value: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x2EFFFFFF))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = Color.White, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Column {
            Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun TempBar(min: Double, max: Double, lo: Double, hi: Double, progress: Float) {
    val range = (hi - lo).coerceAtLeast(1.0)
    Canvas(Modifier.width(6.dp).height(46.dp).padding(vertical = 4.dp)) {
        val h = size.height
        drawRoundRect(Color(0x33FFFFFF), cornerRadius = CornerRadius(size.width / 2))
        val top = h * (1f - ((max - lo) / range).toFloat())
        val bottom = h * (1f - ((min - lo) / range).toFloat())
        val center = (top + bottom) / 2
        val half = (bottom - top) / 2 * progress
        drawRoundRect(
            Brush.verticalGradient(listOf(SunYellow, RainBlue), startY = top, endY = bottom),
            topLeft = Offset(0f, center - half),
            size = Size(size.width, (half * 2).coerceAtLeast(size.width)),
            cornerRadius = CornerRadius(size.width / 2)
        )
    }
}

// ======================================================================
// Animierte Wetter-Symbole
// ======================================================================

@Composable
fun WeatherIcon(code: Int, isDay: Boolean, size: Dp) {
    val t = rememberInfiniteTransition(label = "wx")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(24000, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(2200), RepeatMode.Reverse), label = "pulse")
    val drift by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(4200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val fall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "fall")
    val slowFall by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "slowFall")
    val flash by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "flash")

    val s = sky(code)
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val c = Offset(w / 2, w / 2)
        when {
            !isDay && (s == Sky.CLEAR || s == Sky.PARTLY) -> {
                stars(w, pulse)
                moon(Offset(w * 0.5f, w * 0.45f), w * 0.22f)
                if (s == Sky.PARTLY) cloud(Offset(w * 0.58f + drift * w * 0.04f, w * 0.66f), w * 0.5f, CloudDark)
            }
            s == Sky.CLEAR -> sun(c, w * 0.22f, spin, pulse)
            s == Sky.PARTLY -> {
                sun(Offset(w * 0.38f, w * 0.38f), w * 0.17f, spin, pulse)
                cloud(Offset(w * 0.56f + drift * w * 0.05f, w * 0.62f), w * 0.58f, CloudLight)
            }
            s == Sky.CLOUDY -> {
                cloud(Offset(w * 0.4f - drift * w * 0.04f, w * 0.42f), w * 0.46f, CloudDark)
                cloud(Offset(w * 0.56f + drift * w * 0.05f, w * 0.58f), w * 0.6f, CloudLight)
            }
            s == Sky.FOG -> {
                cloud(Offset(w * 0.5f, w * 0.42f), w * 0.56f, CloudDark)
                for (i in 0..2) {
                    val y = w * (0.66f + i * 0.1f)
                    val dx = drift * w * 0.06f * if (i % 2 == 0) 1 else -1
                    drawLine(CloudLight.copy(alpha = 0.75f), Offset(w * 0.18f + dx, y), Offset(w * 0.82f + dx, y), strokeWidth = w * 0.04f, cap = StrokeCap.Round)
                }
            }
            s == Sky.RAIN -> {
                drops(w, fall, RainBlue)
                cloud(Offset(w * 0.5f + drift * w * 0.03f, w * 0.42f), w * 0.62f, CloudLight)
            }
            s == Sky.SNOW -> {
                flakes(w, slowFall, drift)
                cloud(Offset(w * 0.5f + drift * w * 0.03f, w * 0.4f), w * 0.62f, CloudLight)
            }
            s == Sky.STORM -> {
                drops(w, fall, RainBlue)
                val on = flash in 0.0f..0.08f || flash in 0.14f..0.2f
                if (on) drawCircle(SunYellow.copy(alpha = 0.25f), radius = w * 0.5f, center = c)
                cloud(Offset(w * 0.5f, w * 0.4f), w * 0.62f, CloudDark)
                bolt(w, if (on) 1f else 0.35f)
            }
        }
    }
}

private fun DrawScope.sun(c: Offset, r: Float, spin: Float, pulse: Float) {
    drawCircle(Brush.radialGradient(listOf(SunYellow.copy(alpha = 0.45f), Color.Transparent), c, r * 2.3f * pulse), r * 2.3f * pulse, c)
    rotate(spin, c) {
        for (i in 0 until 12) {
            val a = (i * 30.0) * PI / 180
            val inner = r * 1.3f
            val outer = r * (if (i % 2 == 0) 1.85f else 1.6f)
            drawLine(
                SunYellow,
                Offset(c.x + (cos(a) * inner).toFloat(), c.y + (sin(a) * inner).toFloat()),
                Offset(c.x + (cos(a) * outer).toFloat(), c.y + (sin(a) * outer).toFloat()),
                strokeWidth = r * 0.16f, cap = StrokeCap.Round
            )
        }
    }
    drawCircle(Brush.radialGradient(listOf(SunCore, SunYellow), c, r), r, c)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0x55C9D3E0), Color.Transparent), c, r * 2.2f), r * 2.2f, c)
    val path = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c, r))
    }
    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.35f), r * 0.95f)) }
    val crescent = Path().apply { op(path, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(crescent, Brush.linearGradient(listOf(Color(0xFFF7F3DC), Color(0xFFC9D3E0))))
}

private fun DrawScope.stars(w: Float, pulse: Float) {
    val pts = listOf(0.15f to 0.2f, 0.82f to 0.18f, 0.75f to 0.72f, 0.2f to 0.7f, 0.9f to 0.45f, 0.35f to 0.12f)
    pts.forEachIndexed { i, (x, y) ->
        val a = if (i % 2 == 0) pulse - 0.4f else 1.5f - pulse
        drawCircle(Color.White.copy(alpha = a.coerceIn(0.2f, 1f)), radius = w * 0.018f, center = Offset(w * x, w * y))
    }
}

private fun DrawScope.cloud(c: Offset, width: Float, color: Color) {
    val h = width * 0.42f
    val base = Brush.verticalGradient(listOf(color, color.copy(alpha = 0.85f)), startY = c.y - h, endY = c.y + h / 2)
    drawCircle(base, width * 0.2f, Offset(c.x - width * 0.22f, c.y + h * 0.08f))
    drawCircle(base, width * 0.27f, Offset(c.x, c.y - h * 0.18f))
    drawCircle(base, width * 0.21f, Offset(c.x + width * 0.24f, c.y + h * 0.05f))
    drawRoundRect(
        base,
        topLeft = Offset(c.x - width * 0.42f, c.y),
        size = Size(width * 0.84f, h * 0.55f),
        cornerRadius = CornerRadius(h * 0.3f)
    )
}

private fun DrawScope.drops(w: Float, fall: Float, color: Color) {
    val xs = listOf(0.3f, 0.45f, 0.6f, 0.72f, 0.38f, 0.53f)
    xs.forEachIndexed { i, x ->
        val p = (fall + i * 0.17f) % 1f
        val y = w * (0.55f + p * 0.4f)
        drawLine(
            color.copy(alpha = 1f - p * 0.8f),
            Offset(w * x - p * w * 0.03f, y), Offset(w * x - p * w * 0.03f - w * 0.025f, y + w * 0.08f),
            strokeWidth = w * 0.028f, cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.flakes(w: Float, fall: Float, drift: Float) {
    val xs = listOf(0.28f, 0.42f, 0.58f, 0.72f, 0.35f, 0.65f, 0.5f)
    xs.forEachIndexed { i, x ->
        val p = (fall + i * 0.14f) % 1f
        val y = w * (0.52f + p * 0.44f)
        val sway = sin((p * 6f + i).toDouble()).toFloat() * w * 0.03f + drift * w * 0.01f
        drawCircle(SnowWhite.copy(alpha = 1f - p * 0.7f), radius = w * (0.022f + (i % 3) * 0.006f), center = Offset(w * x + sway, y))
    }
}

private fun DrawScope.bolt(w: Float, alpha: Float) {
    val p = Path().apply {
        moveTo(w * 0.54f, w * 0.5f)
        lineTo(w * 0.42f, w * 0.72f)
        lineTo(w * 0.52f, w * 0.72f)
        lineTo(w * 0.44f, w * 0.94f)
        lineTo(w * 0.64f, w * 0.64f)
        lineTo(w * 0.54f, w * 0.64f)
        lineTo(w * 0.62f, w * 0.5f)
        close()
    }
    drawPath(p, SunYellow.copy(alpha = alpha))
}

/** Leichte Partikel über der ganzen Karte (Regen/Schnee) */
@Composable
private fun SkyParticles(sky: Sky, modifier: Modifier) {
    if (sky != Sky.RAIN && sky != Sky.SNOW && sky != Sky.STORM) return
    val t = rememberInfiniteTransition(label = "particles")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (sky == Sky.SNOW) 6000 else 1600, easing = LinearEasing)), label = "p")
    Canvas(modifier.alpha(0.35f)) {
        val n = 26
        for (i in 0 until n) {
            val x = (i * 37 % 100) / 100f * size.width
            val phase = ((i * 0.13f) + p) % 1f
            val y = phase * size.height
            if (sky == Sky.SNOW) drawCircle(SnowWhite, radius = 2.5f + (i % 3), center = Offset(x + sin((phase * 6 + i).toDouble()).toFloat() * 8f, y))
            else drawLine(RainBlue, Offset(x, y), Offset(x - 4f, y + 18f), strokeWidth = 2f, cap = StrokeCap.Round)
        }
    }
}
