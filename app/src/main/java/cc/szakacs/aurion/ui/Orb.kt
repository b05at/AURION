package cc.szakacs.aurion.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.szakacs.aurion.ui.theme.AurionColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/**
 * Leuchtender "Aurion"-Orb: überlagerte, rotierende Lichtwolken in Blau/Cyan/Indigo/Silber
 * mit Orbit-Ring (angelehnt ans Logo). Reagiert auf Lautstärke und Zustand.
 */
@Composable
fun AurionOrb(state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val t = rememberInfiniteTransition(label = "orb")
    val speed = when (state) {
        OrbState.THINKING -> 2600
        OrbState.LISTENING -> 5200
        OrbState.SPEAKING -> 4200
        OrbState.IDLE -> 9000
    }
    val phase by t.animateFloat(0f, 360f, infiniteRepeatable(tween(speed, easing = LinearEasing)), label = "phase")
    val breathe by t.animateFloat(0.94f, 1.04f, infiniteRepeatable(tween(2400), RepeatMode.Reverse), label = "breathe")
    val ringPhase by t.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "ring")

    val target = when (state) {
        OrbState.LISTENING -> 1f + level * 0.28f
        OrbState.SPEAKING -> 1.06f
        OrbState.THINKING -> 0.92f
        OrbState.IDLE -> 0.9f
    }
    val scale by animateFloatAsState(target, spring(dampingRatio = 0.55f, stiffness = 300f), label = "scale")
    val glow by animateFloatAsState(if (state == OrbState.IDLE) 0.45f else 0.9f, tween(500), label = "glow")

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = center
            val r = this.size.minDimension / 2f * 0.62f * scale * (if (state == OrbState.IDLE) breathe else 1f)

            // Äußerer Schein
            drawCircle(
                Brush.radialGradient(
                    listOf(AurionColors.Blue.copy(alpha = 0.35f * glow), Color.Transparent),
                    center = c, radius = r * 1.9f
                ), radius = r * 1.9f, center = c
            )

            // Kern
            drawCircle(
                Brush.radialGradient(listOf(Color(0xFF0B1A3A), Color(0xFF050A18)), center = c, radius = r),
                radius = r, center = c
            )

            // Rotierende Lichtwolken (auf die Kugel begrenzt)
            clipPath(Path().apply { addOval(Rect(c, r)) }) {
                blob(c, r, phase, 0f, AurionColors.Blue, 0.95f)
                blob(c, r, -phase * 1.3f, 120f, AurionColors.Cyan, 0.75f)
                blob(c, r, phase * 0.8f, 240f, AurionColors.Indigo, 0.8f)
                blob(c, r, -phase * 0.6f, 60f, AurionColors.Silver, 0.35f)
            }

            // Glanzlicht
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), radius = r * 0.55f
                ),
                radius = r * 0.55f, center = Offset(c.x - r * 0.3f, c.y - r * 0.35f), blendMode = BlendMode.Screen
            )
            // Zentraler Stern
            drawCircle(
                Brush.radialGradient(listOf(Color.White, AurionColors.Cyan.copy(alpha = 0.4f), Color.Transparent), center = c, radius = r * 0.28f),
                radius = r * 0.28f, center = c
            )

            // Orbit-Ring wie im Logo
            rotate(-18f + ringPhase * 0.05f, c) {
                val ringW = r * 2.9f
                val ringH = r * 0.95f
                drawOval(
                    brush = Brush.sweepGradient(
                        listOf(
                            AurionColors.Silver.copy(alpha = 0.1f), AurionColors.Silver.copy(alpha = 0.9f),
                            AurionColors.Cyan, AurionColors.Blue.copy(alpha = 0.2f), AurionColors.Silver.copy(alpha = 0.1f)
                        ), center = c
                    ),
                    topLeft = Offset(c.x - ringW / 2, c.y - ringH / 2),
                    size = androidx.compose.ui.geometry.Size(ringW, ringH),
                    style = Stroke(width = r * 0.06f)
                )
            }
            // Rand
            drawCircle(AurionColors.Silver.copy(alpha = 0.25f), radius = r, center = c, style = Stroke(width = 1.2f))
        }
    }
}

private fun DrawScope.blob(c: Offset, r: Float, phaseDeg: Float, offsetDeg: Float, color: Color, alpha: Float) {
    val a = ((phaseDeg + offsetDeg) * PI / 180.0)
    val p = Offset(c.x + (cos(a) * r * 0.38f).toFloat(), c.y + (sin(a) * r * 0.38f).toFloat())
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = p, radius = r * 0.85f),
        radius = r * 0.85f, center = p, blendMode = BlendMode.Plus
    )
}
