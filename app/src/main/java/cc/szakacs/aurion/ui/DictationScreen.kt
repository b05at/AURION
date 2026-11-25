package cc.szakacs.aurion.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.ui.theme.AurionColors
import java.text.DateFormat
import java.util.Date

private const val TASKS_PKG = "com.google.android.apps.tasks"
private const val KEEP_PKG = "com.google.android.keep"

private fun installed(ctx: Context, pkg: String) =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/**
 * Diktat & Transkription: sprechen -> Text -> bearbeiten/aufräumen -> teilen (Google Tasks, Keep, …).
 */
@Composable
fun DictationScreen(vm: DictationViewModel) {
    val ctx = LocalContext.current
    val st by vm.state.collectAsState()
    val history by Store.get(ctx).transcripts.collectAsState()
    val hasTasks = remember { installed(ctx, TASKS_PKG) }
    val hasKeep = remember { installed(ctx, KEEP_PKG) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.transcribeFile(it) } }

    fun share(pkg: String? = null) {
        val t = st.text.trim()
        if (t.isEmpty()) return
        vm.save()
        runCatching { ctx.startActivity(vm.shareIntent(t, pkg)) }
            .onFailure { runCatching { ctx.startActivity(vm.shareIntent(t)) } }
    }

    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Modus & Sprache
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Live", st.mode == DictMode.LIVE) { vm.setMode(DictMode.LIVE) }
                Chip("Whisper (genau)", st.mode == DictMode.WHISPER) { vm.setMode(DictMode.WHISPER) }
                Spacer(Modifier.size(8.dp))
                Chip("Deutsch", st.lang == "de") { vm.setLang("de") }
                Chip("English", st.lang == "en") { vm.setLang("en") }
                if (st.mode == DictMode.WHISPER) Chip("Auto", st.lang.isEmpty()) { vm.setLang("") }
            }
            if (st.mode == DictMode.WHISPER && !vm.whisperAvailable) {
                Text(
                    "Whisper ist noch nicht installiert – lade es unter Einstellungen › Spracherkennung. Bis dahin wird live erkannt.",
                    color = AurionColors.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // Aufnahme-Knopf
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(150.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = st.busy == null) { vm.toggle() },
                    contentAlignment = Alignment.Center
                ) {
                    AurionOrb(
                        when {
                            st.recording -> OrbState.LISTENING
                            st.busy != null -> OrbState.THINKING
                            else -> OrbState.IDLE
                        },
                        st.level, size = 150.dp
                    )
                }
                Text(
                    when {
                        st.recording -> "● Aufnahme  ${st.seconds / 60}:${"%02d".format(st.seconds % 60)}  – tippen zum Beenden"
                        st.busy != null -> st.busy!!
                        else -> "Tippen zum Diktieren"
                    },
                    color = if (st.recording) AurionColors.Cyan else AurionColors.TextMid,
                    style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center
                )
                if (st.busy == null && !st.recording) {
                    Text(
                        "🎧 Sprachnachricht / Audiodatei transkribieren",
                        color = AurionColors.Cyan, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp))
                            .clickable { pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) }
                            .padding(8.dp)
                    )
                }
                st.error?.let { Text(it, color = AurionColors.Amber, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp)) }
            }
        }

        // Text
        item {
            OutlinedTextField(
                value = st.text + if (st.partial.isNotBlank()) (if (st.text.isEmpty()) "" else " ") + st.partial else "",
                onValueChange = { if (!st.recording) vm.setText(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                placeholder = { Text("Hier erscheint dein Text …") },
                shape = RoundedCornerShape(20.dp),
                readOnly = st.recording || st.busy != null,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AurionColors.Cyan, unfocusedBorderColor = AurionColors.GlassBorder,
                    focusedTextColor = AurionColors.TextHigh, unfocusedTextColor = AurionColors.TextHigh, cursorColor = AurionColors.Cyan,
                )
            )
        }

        // Aktionen
        if (st.text.isNotBlank() && !st.recording) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (hasTasks) Action("✓ Google Tasks") { share(TASKS_PKG) }
                        if (hasKeep) Action("📝 Google Notizen") { share(KEEP_PKG) }
                        Action("↗ Teilen …") { share() }
                        Action("Kopieren") {
                            copy(ctx, st.text); Toast.makeText(ctx, "Kopiert", Toast.LENGTH_SHORT).show()
                        }
                        Action(if (st.savedId != null) "✓ Gespeichert" else "Speichern") { vm.save() }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Action("✨ Aufräumen") { vm.aiRewrite("clean") }
                        Action("Zusammenfassen") { vm.aiRewrite("summary") }
                        Action("To-do-Liste") { vm.aiRewrite("todo") }
                        Action("Als E-Mail") { vm.aiRewrite("mail") }
                        Action("Neu") { vm.clear() }
                    }
                }
            }
        }

        // Verlauf
        if (history.isNotEmpty()) {
            item { SectionTitle("Gespeicherte Transkripte") }
            items(history, key = { it.id }) { t ->
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).clickable { vm.load(t) }) {
                            Text(
                                "${t.source} · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.time)),
                                color = AurionColors.TextLow, fontSize = 12.sp
                            )
                            Text(t.text, color = AurionColors.TextHigh, maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { runCatching { ctx.startActivity(vm.shareIntent(t.text)) } }) {
                            Icon(Icons.Rounded.Share, "Teilen", tint = AurionColors.TextMid)
                        }
                        IconButton(onClick = { Store.get(ctx).deleteTranscript(t.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, "Löschen", tint = AurionColors.TextLow)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0x332F7BFF), selectedLabelColor = AurionColors.Cyan)
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Text(
        label, color = AurionColors.TextHigh, fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x332F7BFF))
            .border(1.dp, Color(0x334FD8FF), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}
