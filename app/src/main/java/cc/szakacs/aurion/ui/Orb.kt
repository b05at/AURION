package cc.szakacs.aurion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cc.szakacs.aurion.ui.theme.AurionColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING }

/** Bequeme Variante mit festem Pegel */
@Composable
fun AurionOrb(state: OrbState, level: Float, modifier: Modifier = Modifier, size: Dp = 140.dp) =
    AurionOrb(state, { level }, modifier, size)

/** Laufende Werte des Orbs – werden nur in der Zeichenphase gelesen (kein Neuaufbau der Oberfläche pro Bild) */
private class OrbFrame {
    val phase = mutableFloatStateOf(0f)
    val ring = mutableFloatStateOf(0f)
    val scale = mutableFloatStateOf(0.9f)
    val glow = mutableFloatStateOf(0.45f)
    var speed = 40f      // Grad pro Sekunde
    var time = 0f
}

/**
 * Leuchtender "Aurion"-Orb: überlagerte, rotierende Lichtwolken in Blau/Cyan/Indigo/Silber
 * mit Orbit-Ring (angelehnt ans Logo).
 *
 * Flüssig: eine durchgehende Bild-Uhr (kein Neustart bei Zustandswechseln), weich ineinander
 * übergehende Geschwindigkeit/Größe, eigene Grafikebene und zwischengespeicherte Farbverläufe.
 */
@Composable
fun AurionOrb(state: OrbState, level: () -> Float, modifier: Modifier = Modifier, size: Dp = 140.dp) {
    val frame = remember { OrbFrame() }
    val currentState = rememberUpdatedState(state)
    val currentLevel = rememberUpdatedState(level)

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
                last = now
                frame.time += dt
                val s = currentState.value
                val targetSpeed = when (s) {
                    OrbState.THINKING -> 140f
                    OrbState.LISTENING -> 70f
                    OrbState.SPEAKING -> 90f
                    OrbState.IDLE -> 38f
                }
                // weich beschleunigen/abbremsen statt springen
                frame.speed += (targetSpeed - frame.speed) * min(1f, dt * 2.5f)
                frame.phase.floatValue = (frame.phase.floatValue + frame.speed * dt) % 360f
                frame.ring.floatValue = (frame.ring.floatValue + 26f * dt) % 360f

                val lv = currentLevel.value().coerceIn(0f, 1f)
                val breathe = 0.99f + 0.05f * sin(frame.time * 2.6f)
                val targetScale = when (s) {
                    OrbState.LISTENING -> 1f + lv * 0.28f
                    OrbState.SPEAKING -> 1.03f + 0.04f * sin(frame.time * 9f)
                    OrbState.THINKING -> 0.93f + 0.02f * sin(frame.time * 6f)
                    OrbState.IDLE -> 0.9f * breathe
                }
                // Feder-ähnliche Glättung
                frame.scale.floatValue += (targetScale - frame.scale.floatValue) * min(1f, dt * 9f)
                val targetGlow = if (s == OrbState.IDLE) 0.45f else 0.9f
                frame.glow.floatValue += (targetGlow - frame.glow.floatValue) * min(1f, dt * 4f)
            }
        }
    }

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()   // eigene Ebene: nur der Orb wird neu gezeichnet
                .drawWithCache {
                    val base = this.size.minDimension / 2f * 0.62f
                    // Verläufe um den Ursprung, Radius 1 – werden per Transformation positioniert/skaliert
                    fun unitRadial(c: Color, a: Float) = Brush.radialGradient(listOf(c.copy(alpha = a), Color.Transparent), Offset.Zero, 1f)
                    val outer = Brush.radialGradient(listOf(AurionColors.Blue.copy(alpha = 0.35f), Color.Transparent), Offset.Zero, 1f)
                    val core = Brush.radialGradient(listOf(Color(0xFF0B1A3A), Color(0xFF050A18)), Offset.Zero, 1f)
                    val blobBlue = unitRadial(AurionColors.Blue, 0.95f)
                    val blobCyan = unitRadial(AurionColors.Cyan, 0.75f)
                    val blobIndigo = unitRadial(AurionColors.Indigo, 0.8f)
                    val blobSilver = unitRadial(AurionColors.Silver, 0.35f)
                    val shine = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset.Zero, 1f)
                    val star = Brush.radialGradient(listOf(Color.White, AurionColors.Cyan.copy(alpha = 0.4f), Color.Transparent), Offset.Zero, 1f)
                    val ringBrush = Brush.sweepGradient(
                        listOf(
                            AurionColors.Silver.copy(alpha = 0.1f), AurionColors.Silver.copy(alpha = 0.9f),
                            AurionColors.Cyan, AurionColors.Blue.copy(alpha = 0.2f), AurionColors.Silver.copy(alpha = 0.1f)
                        ), Offset.Zero
                    )
                    val unitCircle = Path().apply { addOval(Rect(Offset.Zero, 1f)) }

                    onDrawBehind {
                        val c = center
                        val r = base * frame.scale.floatValue
                        val glow = frame.glow.floatValue
                        val phase = frame.phase.floatValue

                        translate(c.x, c.y) {
                            // Äußerer Schein
                            scale(r * 1.9f, r * 1.9f, Offset.Zero) { drawCircle(outer, 1f, Offset.Zero, alpha = (glow / 0.9f).coerceIn(0f, 1f)) }
                            // Kern + Lichtwolken (auf die Kugel begrenzt)
                            scale(r, r, Offset.Zero) {
                                drawCircle(core, 1f, Offset.Zero)
                                clipPath(unitCircle) {
                                    fun blob(brush: Brush, deg: Float) {
                                        val a = deg * PI / 180.0
                                        translate((cos(a) * 0.38).toFloat(), (sin(a) * 0.38).toFloat()) {
                                            scale(0.85f, 0.85f, Offset.Zero) { drawCircle(brush, 1f, Offset.Zero, blendMode = BlendMode.Plus) }
                                        }
                                    }
                                    blob(blobBlue, phase)
                                    blob(blobCyan, -phase * 1.3f + 120f)
                                    blob(blobIndigo, phase * 0.8f + 240f)
                                    blob(blobSilver, -phase * 0.6f + 60f)
                                }
                                // Glanzlicht
                                translate(-0.3f, -0.35f) {
                                    scale(0.55f, 0.55f, Offset.Zero) { drawCircle(shine, 1f, Offset.Zero, blendMode = BlendMode.Screen) }
                                }
                                // Zentraler Stern
                                scale(0.28f, 0.28f, Offset.Zero) { drawCircle(star, 1f, Offset.Zero) }
                            }
                            // Orbit-Ring wie im Logo
                            rotate(-18f + frame.ring.floatValue * 0.05f, Offset.Zero) {
                                val w = r * 2.9f
                                val h = r * 0.95f
                                drawOval(ringBrush, Offset(-w / 2, -h / 2), Size(w, h), style = Stroke(width = r * 0.06f))
                            }
                            // Rand
                            drawCircle(AurionColors.Silver.copy(alpha = 0.25f), r, Offset.Zero, style = Stroke(width = 1.2f))
                        }
                    }
                }
        )
    }
}
