package cc.szakacs.aurion.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.brain.DeviceActions
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.vision.Entity

/**
 * Ergebnis der Bildschirm-Lupe: Vorschau, erkannte Objekte/Text, Aurions Erklärung und Schnellaktionen.
 */
@Composable
fun LensCard(lens: LensState, vm: AssistantViewModel, modifier: Modifier = Modifier, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val device = DeviceActions(ctx)
    val r = lens.result

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            lens.image?.let { img ->
                Image(
                    img.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, AurionColors.GlassBorder, RoundedCornerShape(14.dp))
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    lens.title ?: if (lens.image != null) "Auswahl" else "Geteilter Text",
                    style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh,
                    maxLines = 2
                )
                when {
                    lens.analyzing -> Shimmer(lens.progress ?: "Analysiere …")
                    lens.title != null && r != null && r.labels.isEmpty() -> Text(
                        "${r.text.length} Zeichen" + (lens.progress?.let { " · $it" } ?: ""),
                        color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
                    )
                    r != null && r.labels.isNotEmpty() -> Text(r.labels.joinToString(" · "), color = AurionColors.Cyan, style = MaterialTheme.typography.bodyMedium)
                    r != null && r.text.isNotBlank() -> Text("${r.text.lines().size} Zeilen Text erkannt", color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
                    else -> Text("Nichts Eindeutiges erkannt", color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Schließen", tint = AurionColors.TextMid) }
        }

        // Erst fragen, was mit dem Anhang passieren soll
        if (lens.askWhat && !lens.analyzing && lens.answer == null && !lens.answering) {
            Spacer(Modifier.size(12.dp))
            Text("Was soll ich damit machen?", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
            Text(
                "Schreib oder sag es mir – oder wähle einen Vorschlag.",
                color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isDoc = lens.title != null && lens.image == null || (r?.text?.length ?: 0) > 300
                if (isDoc) {
                    ActionChip("Zusammenfassen") { vm.submit("Fasse das zusammen: Worum geht es, was sind die wichtigsten Punkte, gibt es Fristen, Beträge oder To-dos?") }
                    ActionChip("Fristen & To-dos") { vm.submit("Welche Fristen, Termine, Beträge und To-dos stehen darin? Liste sie auf.") }
                    ActionChip("Einfach erklären") { vm.submit("Erkläre den Inhalt in einfachen Worten.") }
                    ActionChip("Antwort formulieren") { vm.submit("Formuliere eine passende, höfliche Antwort darauf.") }
                } else {
                    ActionChip("Was ist das?") { vm.submit("Was ist auf dem Bild zu sehen? Beschreibe es.") }
                    ActionChip("Text auslesen") { vm.submit("Gib den erkannten Text sauber formatiert wieder.") }
                }
                ActionChip("Übersetzen") { vm.submit("Übersetze den Inhalt ins Deutsche (falls er Deutsch ist: ins Englische).") }
            }
        }

        // Aurions Erklärung (live)
        if (lens.answering || lens.answer != null) {
            Spacer(Modifier.size(10.dp))
            Box(Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                if (lens.answer.isNullOrBlank()) Shimmer(lens.progress ?: "Aurion denkt nach …") else MarkdownText(lens.answer)
            }
        }

        // Erkannter Text
        if (r != null && r.text.isNotBlank() && lens.image != null && lens.title == null) {
            Spacer(Modifier.size(10.dp))
            Text(
                r.text.take(600),
                color = AurionColors.TextMid, fontSize = 13.sp, maxLines = 5,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).padding(10.dp)
            )
        }

        // Aktionen
        if (r != null && !lens.askWhat) {
            Spacer(Modifier.size(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                r.entities.forEach { e ->
                    when (e.kind) {
                        Entity.Kind.PHONE -> ActionChip("📞 ${e.value}") { device.dial(e.value) }
                        Entity.Kind.EMAIL -> ActionChip("✉ ${e.value}") { device.email(e.value) }
                        Entity.Kind.URL -> ActionChip("🔗 Öffnen") { device.openUrl(e.value) }
                        Entity.Kind.ADDRESS -> ActionChip("📍 ${e.value.take(24)}") { device.showOnMap(e.value) }
                        Entity.Kind.DATE -> ActionChip("⏰ Erinnerung ${e.value}") { vm.submit("Erinnere mich ${e.value} daran") }
                    }
                }
                ActionChip("Erklären") { vm.submit("Erkläre das ausführlicher.") }
                if (r.text.isNotBlank()) {
                    ActionChip("Übersetzen") { vm.submit("Übersetze den markierten Text ins Deutsche (falls er Deutsch ist: ins Englische).") }
                    ActionChip("Zusammenfassen") { vm.submit("Fasse das in Stichpunkten zusammen.") }
                    ActionChip("Kopieren") { copy(ctx, r.text) }
                }
                ActionChip("🛒 Einkaufsliste") { vm.submit("Setz das auf die Einkaufsliste") }
                ActionChip("⏰ Erinnern") { vm.submit("Erinnere mich daran") }
                ActionChip("🔍 Im Web suchen") {
                    device.webSearch(r.text.lines().firstOrNull { it.isNotBlank() }?.take(120) ?: r.labels.joinToString(" "))
                }
            }
        }
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = AurionColors.TextHigh,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x332F7BFF))
            .border(1.dp, Color(0x334FD8FF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
fun Shimmer(text: String) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-300f, 900f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(
            brush = Brush.linearGradient(
                listOf(AurionColors.TextLow, Color.White, AurionColors.Cyan, AurionColors.TextLow),
                start = androidx.compose.ui.geometry.Offset(x, 0f),
                end = androidx.compose.ui.geometry.Offset(x + 300f, 0f)
            )
        )
    )
}

fun copy(ctx: Context, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Aurion", text))
}
