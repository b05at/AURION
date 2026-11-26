package cc.szakacs.aurion.ui

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.szakacs.aurion.BuildConfig
import cc.szakacs.aurion.bring.BringClient
import cc.szakacs.aurion.bring.BringList
import cc.szakacs.aurion.data.ChatMessage
import cc.szakacs.aurion.data.LangMode
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.llm.DownloadState
import cc.szakacs.aurion.llm.LlmEngine
import cc.szakacs.aurion.llm.ModelManager
import cc.szakacs.aurion.reminders.Reminders
import cc.szakacs.aurion.ui.theme.AurionColors
import cc.szakacs.aurion.update.UpdateInfo
import cc.szakacs.aurion.update.Updater
import cc.szakacs.aurion.voice.Speaker
import cc.szakacs.aurion.speech.Pack
import cc.szakacs.aurion.speech.PackState
import cc.szakacs.aurion.speech.Packs
import cc.szakacs.aurion.weather.WeatherService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// ======================================================================
// Gespräch
// ======================================================================

/** Zählt bei jedem Zurückkehren in die App hoch (für Status-Prüfungen) */
val LocalResumeTick = androidx.compose.runtime.compositionLocalOf { 0 }

@Composable
fun ChatScreen(vm: AssistantViewModel, dictVm: DictationViewModel) {
    val ctx = LocalContext.current
    val chat by Store.get(ctx).chat.collectAsState()
    val st by vm.state.collectAsState()
    val dict by dictVm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Liste ist unten verankert (reverseLayout): wachsende Antworten bleiben ohne Scroll-Sprünge sichtbar.
    // Nur bei einer neuen Nachricht ganz nach unten springen.
    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.scrollToItem(0)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        // Hinweis, wenn Android Aurion nach einem Update als Assistent abgemeldet hat
        val tick = LocalResumeTick.current
        val lost = remember(tick) { cc.szakacs.aurion.assist.AssistantRole.isLost(ctx).also { if (!it) cc.szakacs.aurion.assist.AssistantRole.sync(ctx) } }
        if (lost) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Brush.linearGradient(listOf(Color(0x332F7BFF), Color(0x334FD8FF))))
                    .clickable { cc.szakacs.aurion.assist.AssistantRole.openSettings(ctx) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Seitentaste neu verbinden", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Android hat Aurion nach dem Update als Assistent abgemeldet. Tippen und wieder Aurion wählen.",
                        color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text("Öffnen", color = AurionColors.Cyan, modifier = Modifier.padding(start = 8.dp))
            }
        }
        // Live-Timer
        TimerStrip(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        // Diktat direkt im Chat
        if (dict.active) {
            DictationCard(dictVm, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        }
        st.lens?.takeIf { !it.inChat }?.let { lens ->
            LensCard(lens, vm, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) { vm.closeLens() }
        }
        if (chat.isEmpty() && st.orb == OrbState.IDLE && (st.lens == null || st.lens?.inChat == true) && !dict.active) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Hallo! Ich bin Aurion.", style = MaterialTheme.typography.displaySmall, color = AurionColors.TextHigh)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Probier zum Beispiel:\n„Erinnere mich in 20 Minuten an die Wäsche“\n„Setz Milch und Eier auf die Einkaufsliste“\n„Merk dir, dass mein Auto beim Bahnhof steht“\n„Remind me tomorrow at 8 to call Anna“\n„Was sind 17 % von 2340?“ · „5 Meilen in km“\n„Schreib eine Absage für das Meeting morgen“\n\nTeile Texte oder Bilder an Aurion – oder kreise über die Seitentaste etwas auf dem Bildschirm ein.",
                    style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextMid
                )
            }
        } else {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom)
            ) {
                // Achtung: umgekehrte Reihenfolge – das Neueste zuerst (unten)
                if (st.streaming != null && (st.lens == null || st.lens?.inChat == true)) {
                    item(key = "streaming", contentType = "stream") { StreamingBubble(st.streaming!!) }
                } else if (st.orb != OrbState.IDLE && !(st.lens != null && st.lens?.inChat != true && st.orb == OrbState.THINKING)) {
                    item(key = "status", contentType = "status") {
                        Text(
                            when (st.orb) {
                                OrbState.LISTENING -> st.transcript.ifBlank { "Ich höre zu …" }
                                OrbState.THINKING -> "Aurion denkt nach …"
                                else -> "…"
                            },
                            color = AurionColors.TextMid,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
                st.reply?.takeIf { (it.weather != null || it.shopping != null || it.card == cc.szakacs.aurion.brain.CardKind.CALL || it.card == cc.szakacs.aurion.brain.CardKind.MESSAGE) && st.orb != OrbState.THINKING && chat.isNotEmpty() }?.let { r ->
                    item(key = "card", contentType = "card") { ReplyCard(r, Modifier.fillMaxWidth(), showText = false, onCheckItem = { vm.checkOffShopping(it) }, onConfirm = { yes -> vm.submit(if (yes) "Ja" else "Abbrechen") }) }
                }
                val reversed = chat.asReversed()
                items(reversed.size, key = { i -> reversed[i].let { m -> "${chat.size - 1 - i}-${m.time}-${m.fromUser}" } }, contentType = { "msg" }) { i ->
                    Bubble(reversed[i])
                }
            }
        }
        st.hint?.let {
            Text(it, color = AurionColors.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
        }

        // Anhang wie bei ChatGPT/Claude: Vorschau am Eingabefeld, Anweisung dazuschreiben oder -sagen
        st.lens?.takeIf { it.inChat }?.let { lens ->
            AttachmentComposer(lens, onRemove = { vm.closeLens() }, onSuggestion = { vm.submit(it) })
        }

        fun send() {
            val lens = st.lens
            when {
                input.isNotBlank() -> vm.submit(input)
                lens?.inChat == true && lens.askWhat -> vm.submit(
                    if ((lens.result?.text?.length ?: 0) > 300) "Fasse das zusammen und nenne die wichtigsten Punkte."
                    else "Was ist das? Beschreibe es kurz."
                )
                else -> return
            }
            input = ""
        }

        // Anhänge
        var attachMenu by remember { mutableStateOf(false) }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(it) } }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp -> bmp?.let { vm.attachPhoto(it) } }
        val pickDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.attach(it) } }
        val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { dictVm.transcribeFile(it) } }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(AurionColors.Glass),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { attachMenu = true }) {
                        Icon(Icons.Rounded.Add, "Anhängen", tint = AurionColors.Cyan)
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = attachMenu,
                        onDismissRequest = { attachMenu = false },
                        containerColor = AurionColors.SurfaceHigh,
                    ) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Bild aus Galerie") },
                            leadingIcon = { Icon(Icons.Rounded.Image, null, tint = AurionColors.Cyan) },
                            onClick = {
                                attachMenu = false
                                pickImage.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Foto aufnehmen") },
                            leadingIcon = { Icon(Icons.Rounded.PhotoCamera, null, tint = AurionColors.Cyan) },
                            onClick = { attachMenu = false; takePhoto.launch(null) }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Diktat starten") },
                            leadingIcon = { Icon(Icons.Rounded.Mic, null, tint = AurionColors.Cyan) },
                            onClick = { attachMenu = false; vm.stop(); dictVm.start() }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Sprachnachricht / Audio transkribieren") },
                            leadingIcon = { Icon(Icons.Rounded.GraphicEq, null, tint = AurionColors.Cyan) },
                            onClick = { attachMenu = false; pickAudio.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) }
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("Dokument (PDF, Word, Text …)") },
                            leadingIcon = { Icon(Icons.Rounded.Description, null, tint = AurionColors.Cyan) },
                            onClick = {
                                attachMenu = false
                                pickDoc.launch(
                                    arrayOf(
                                        "application/pdf", "text/*", "application/json", "application/xml",
                                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                        "application/vnd.oasis.opendocument.text", "image/*",
                                    )
                                )
                            }
                        )
                    }
                }
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = {
                        Text(
                            if (st.lens?.askWhat == true) "Was soll ich damit machen?" else "Nachricht an Aurion",
                            color = AurionColors.TextLow
                        )
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f)
                )
                if (input.isNotBlank() || st.lens?.askWhat == true) {
                    IconButton(onClick = { send() }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Senden", tint = AurionColors.Cyan)
                    }
                }
            }
            IconButton(onClick = {
                vm.stop()
                ctx.startActivity(android.content.Intent(ctx, VoiceModeActivity::class.java))
            }) {
                Icon(Icons.Rounded.GraphicEq, "Gesprächsmodus", tint = AurionColors.Cyan)
            }
            Box(
                Modifier
                    .size(76.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (dict.active) dictVm.toggle() else vm.toggleListening()
                    },
                contentAlignment = Alignment.Center
            ) {
                AurionOrb(
                    if (dict.active) (if (dict.recording) OrbState.LISTENING else if (dict.busy != null) OrbState.THINKING else OrbState.IDLE) else st.orb,
                    if (dict.active) dict.level else st.level, size = 76.dp
                )
            }
        }
    }
}

/** Live-Antwort: schlanke Darstellung, damit das Schreiben flüssig bleibt */
@Composable
private fun StreamingBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Text(
            text.ifBlank { "…" }.replace("**", "").replace(Regex("^#+\\s*", RegexOption.MULTILINE), ""),
            style = MaterialTheme.typography.bodyLarge,
            color = AurionColors.TextHigh,
            modifier = Modifier
                .widthIn(max = 360.dp)
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 6.dp, bottomEnd = 22.dp))
                .background(Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF))))
                .padding(horizontal = 16.dp, vertical = 11.dp)
        )
    }
}

/** Anhang-Vorschau über dem Eingabefeld (wie bei ChatGPT/Claude) */
@Composable
private fun AttachmentComposer(lens: LensState, onRemove: () -> Unit, onSuggestion: (String) -> Unit) {
    val isDoc = lens.image == null || (lens.result?.text?.length ?: 0) > 300 || (lens.title?.contains('.') == true && lens.title != "Foto")
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(AurionColors.Glass)
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)),
                contentAlignment = Alignment.Center
            ) {
                if (lens.image != null) androidx.compose.foundation.Image(
                    lens.image.asImageBitmap(), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                ) else Text("📄", fontSize = 24.sp)
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.widthIn(max = 220.dp)) {
                Text(lens.title ?: "Anhang", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                when {
                    lens.analyzing || lens.progress != null -> Shimmer(lens.progress ?: "Wird gelesen …")
                    lens.askWhat -> Text("Sag oder schreib, was ich damit tun soll", color = AurionColors.TextMid, fontSize = 12.sp)
                    else -> Text("Im Gespräch – frag gern weiter", color = AurionColors.Cyan, fontSize = 12.sp)
                }
            }
            IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Anhang entfernen", tint = AurionColors.TextMid) }
        }
        if (lens.askWhat && !lens.analyzing) {
            Row(
                Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val ideas = if (isDoc) listOf(
                    "Zusammenfassen" to "Fasse das zusammen: Worum geht es, was sind die wichtigsten Punkte, gibt es Fristen, Beträge oder To-dos?",
                    "Fristen & To-dos" to "Welche Fristen, Termine, Beträge und To-dos stehen darin?",
                    "Einfach erklären" to "Erkläre den Inhalt in einfachen Worten.",
                    "Antwort schreiben" to "Formuliere eine passende, höfliche Antwort darauf.",
                    "Übersetzen" to "Übersetze den Inhalt ins Deutsche (falls er Deutsch ist: ins Englische).",
                ) else listOf(
                    "Was ist das?" to "Was ist auf dem Bild zu sehen? Beschreibe es.",
                    "Text auslesen" to "Gib den erkannten Text sauber formatiert wieder.",
                    "Übersetzen" to "Übersetze den Text auf dem Bild ins Deutsche.",
                    "Auf Einkaufsliste" to "Setz das auf die Einkaufsliste",
                )
                ideas.forEach { (label, prompt) ->
                    Text(
                        label, color = AurionColors.TextHigh, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0x262F7BFF))
                            .clickable { onSuggestion(prompt) }.padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: ChatMessage) {
    val ctx = LocalContext.current
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        val shape = RoundedCornerShape(
            topStart = 22.dp, topEnd = 22.dp,
            bottomStart = if (m.fromUser) 22.dp else 6.dp,
            bottomEnd = if (m.fromUser) 6.dp else 22.dp
        )
        val mod = Modifier
            .widthIn(max = if (m.fromUser) 320.dp else 360.dp)
            .clip(shape)
            .background(
                if (m.fromUser) Brush.linearGradient(listOf(AurionColors.Blue, Color(0xFF3A66E8)))
                else Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF)))
            )
            .combinedClickable(onClick = {}, onLongClick = {
                copy(ctx, m.text)
                android.widget.Toast.makeText(ctx, "Kopiert", android.widget.Toast.LENGTH_SHORT).show()
            })
            .padding(horizontal = 16.dp, vertical = 11.dp)
        if (m.fromUser) Column(mod) {
            m.attachment?.let { a ->
                Text("📎 $a", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1,
                    modifier = Modifier.padding(bottom = 4.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x33000000)).padding(horizontal = 8.dp, vertical = 3.dp))
            }
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextHigh)
        }
        else MarkdownText(m.text, modifier = mod)
    }
}

// ======================================================================
// Erinnerungen
// ======================================================================

@Composable
fun RemindersScreen(vm: AssistantViewModel) {
    val ctx = LocalContext.current
    val all by Store.get(ctx).reminders.collectAsState()
    val open = all.filter { !it.acknowledged }
    val done = all.filter { it.acknowledged }.sortedByDescending { it.triggerAt }
    var input by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("z. B. morgen um 8 Müll rausbringen") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (input.isNotBlank()) { vm.submit("Erinnere mich $input"); input = "" }
                }),
                colors = fieldColors()
            )
        }
        item { SectionTitle("Offen (${open.size})") }
        if (open.isEmpty()) item { Text("Keine offenen Erinnerungen.", color = AurionColors.TextMid, modifier = Modifier.padding(6.dp)) }
        items(open, key = { it.id }) { r ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.text, style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                        Text(
                            Reminders.formatWhen(ctx, r.triggerAt) + if (r.fired) " · fällig" else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (r.fired) AurionColors.Amber else AurionColors.TextMid
                        )
                    }
                    if (r.fired) IconButton(onClick = { Reminders.snooze(ctx, r.id) }) {
                        Icon(Icons.Rounded.Snooze, "Später", tint = AurionColors.TextMid)
                    }
                    IconButton(onClick = { Reminders.acknowledge(ctx, r.id) }) {
                        Icon(Icons.Rounded.Check, "Quittieren", tint = AurionColors.Cyan)
                    }
                    IconButton(onClick = { Reminders.delete(ctx, r.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Löschen", tint = AurionColors.TextLow)
                    }
                }
            }
        }
        if (done.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Erledigt", Modifier.weight(1f))
                    Text(
                        "Leeren", color = AurionColors.Cyan,
                        modifier = Modifier.clickable { Store.get(ctx).clearAcknowledged() }.padding(top = 14.dp, end = 6.dp)
                    )
                }
            }
            items(done.take(30), key = { it.id }) { r ->
                Text(
                    "✓  ${r.text} · ${Reminders.formatWhen(ctx, r.triggerAt)}",
                    color = AurionColors.TextLow, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp)
                )
            }
        }
    }
}

// ======================================================================
// Gedächtnis
// ======================================================================

/** Gedächtnis – jetzt als Bereich in den Einstellungen */
@Composable
private fun MemorySection() {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val mem by store.memory.collectAsState()
    var input by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Was Aurion sich merkt, fließt in alle Antworten ein – bleibt aber nur auf diesem Gerät. Neu per Sprache: „Merk dir, dass …“",
            color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = input, onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Neuer Eintrag, z. B. „Ich trinke Kaffee schwarz“") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) { store.addMemory(input); input = "" } }),
            colors = fieldColors()
        )
        if (mem.isEmpty()) {
            Text("Noch nichts gemerkt.", color = AurionColors.TextLow, modifier = Modifier.padding(top = 10.dp))
        } else {
            Text("Gemerkt (${mem.size})", color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            val shown = if (expanded) mem.asReversed() else mem.asReversed().take(5)
            shown.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, color = AurionColors.TextHigh, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { store.deleteMemory(f.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Löschen", tint = AurionColors.TextLow)
                    }
                }
            }
            if (mem.size > 5) {
                Text(
                    if (expanded) "Weniger anzeigen" else "Alle ${mem.size} anzeigen",
                    color = AurionColors.Cyan, modifier = Modifier.clickable { expanded = !expanded }.padding(vertical = 6.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SecondaryButton("Gesprächsverlauf löschen", Modifier.fillMaxWidth()) { store.clearChat() }
    }
}

/** Sicherung aller Daten in eine Datei – schützt bei Neuinstallation oder Handywechsel */
@Composable
private fun BackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch { msg = runCatching { cc.szakacs.aurion.data.Backup.export(ctx, uri) }.fold({ "Gesichert ✓" }, { "Fehler: ${it.message}" }) }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { msg = runCatching { cc.szakacs.aurion.data.Backup.import(ctx, uri) }.fold({ it }, { "Fehler: ${it.message}" }) }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Erinnerungen, Gedächtnis, Transkripte und Einstellungen bleiben bei Updates automatisch erhalten. Für eine Neuinstallation oder ein neues Handy kannst du hier eine Sicherung anlegen.",
            color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Sichern", Modifier.weight(1f)) { exportLauncher.launch("aurion-sicherung.json") }
            SecondaryButton("Wiederherstellen", Modifier.weight(1f)) { importLauncher.launch(arrayOf("application/json", "*/*")) }
        }
        msg?.let { Text(it, color = AurionColors.Cyan, modifier = Modifier.padding(top = 8.dp)) }
    }
}

// ======================================================================
// Einstellungen
// ======================================================================

@Composable
fun SettingsScreen(tick: Int, dictVm: DictationViewModel, onOpenChat: () -> Unit) {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()
    ) {
        // ---------- Assistent ----------
        SectionTitle("Assistent")
        GlassCard(Modifier.fillMaxWidth()) {
            val isDefault = remember(tick) { cc.szakacs.aurion.assist.AssistantRole.sync(ctx) }
            StatusRow(
                "Standard-Assistent",
                if (isDefault) "Aurion startet über Seitentaste / Assistent-Geste" else "Unter „Digitaler Assistent“ Aurion wählen",
                isDefault, if (isDefault) null else "Öffnen"
            ) { cc.szakacs.aurion.assist.AssistantRole.openSettings(ctx) }
            val mic = remember(tick) { hasPerm(ctx, android.Manifest.permission.RECORD_AUDIO) }
            StatusRow("Mikrofon", if (mic) "Erlaubt" else "Wird für Spracheingabe benötigt", mic, if (mic) null else "Erlauben") { openAppSettings(ctx) }
            val notif = remember(tick) {
                Build.VERSION.SDK_INT < 33 || hasPerm(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
            }
            StatusRow("Benachrichtigungen", if (notif) "Erlaubt" else "Nötig für Erinnerungen", notif, if (notif) null else "Erlauben") { openAppSettings(ctx) }
            val exact = remember(tick) {
                Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
            }
            StatusRow("Exakte Wecker", if (exact) "Erinnerungen kommen pünktlich" else "Für pünktliche Erinnerungen", exact, if (exact) null else "Erlauben") {
                if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(
                    Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}"))
                )
            }
            Text(
                "Bildschirm-Lupe: In den Android-Einstellungen unter Standard-Apps › Digitaler Assistent „Screenshot verwenden“ und „Text auf dem Bildschirm verwenden“ einschalten. Dann über die Seitentaste etwas einkreisen.",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid, modifier = Modifier.padding(top = 8.dp)
            )
            Text(
                "Tipp (Samsung): Einstellungen › Erweiterte Funktionen › Seitentaste › Gedrückt halten › Digitaler Assistent.",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ---------- WhatsApp ----------
        SectionTitle("WhatsApp per Sprache")
        GlassCard(Modifier.fillMaxWidth()) {
            val auto = remember(tick) { cc.szakacs.aurion.assist.AutoSendService.isEnabled(ctx) }
            Text(
                "Sag z. B. „Schreib Mama auf WhatsApp, dass ich später komme“. Aurion zeigt die Nachricht, fragt „Senden?“ und schickt sie nach deinem „Ja“.",
                color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
            )
            StatusRow(
                "Automatisch senden",
                if (auto) "Aktiv – nach deinem „Ja“ wird direkt gesendet" else "Aus – WhatsApp öffnet sich mit fertigem Text, du tippst auf Senden",
                auto, if (auto) null else "Einrichten"
            ) {
                runCatching { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            if (!auto) Text(
                "In den Bedienungshilfen „Aurion – Nachrichten senden“ einschalten. Ist der Schalter gesperrt: App-Info von Aurion › ⋮ › „Eingeschränkte Einstellungen zulassen“ (gilt für Apps außerhalb des Play Stores). Aurion drückt nur den Senden-Knopf für Nachrichten, die du bestätigt hast, und liest keine Chats mit.",
                color = AurionColors.TextLow, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ---------- Gedächtnis ----------
        SectionTitle("Gedächtnis")
        MemorySection()

        // ---------- Sprache ----------
        SectionTitle("Sprache")
        GlassCard(Modifier.fillMaxWidth()) {
            var mode by remember { mutableStateOf(settings.langMode) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(LangMode.AUTO to "Automatisch", LangMode.DE to "Deutsch", LangMode.EN to "English").forEach { (m, label) ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m; settings.langMode = m },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0x332F7BFF), selectedLabelColor = AurionColors.Cyan
                        )
                    )
                }
            }
            Text(
                "Automatisch erkennt Deutsch und Englisch (Spracherkennung ab Android 14, Antworten immer in deiner Sprache).",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 6.dp)
            )
            ToggleRow("Antworten vorlesen", settings.speakReplies) { settings.speakReplies = it }
            ToggleRow("Beim Öffnen sofort zuhören", settings.listenOnOpen) { settings.listenOnOpen = it }
        }

        // ---------- Stimme ----------
        SectionTitle("Stimme")
        VoiceSection()

        // ---------- Spracherkennung (Whisper) ----------
        SectionTitle("Spracherkennung (Diktat)")
        WhisperSection()

        SectionTitle("Gespeicherte Transkripte")
        TranscriptHistory(dictVm, onOpenChat)

        // ---------- Wetter ----------
        SectionTitle("Wetter")
        WeatherSection(tick)

        // ---------- Sprachmodell ----------
        SectionTitle("Lokales Sprachmodell")
        ModelSection()

        // ---------- Bring! ----------
        SectionTitle("Bring! Einkaufsliste")
        BringSection()

        // ---------- Sicherung ----------
        SectionTitle("Daten sichern")
        BackupSection()

        // ---------- Update ----------
        SectionTitle("App")
        GlassCard(Modifier.fillMaxWidth()) {
            var info by remember { mutableStateOf<UpdateInfo?>(null) }
            var status by remember { mutableStateOf("Version ${BuildConfig.VERSION_NAME}") }
            var progress by remember { mutableStateOf<Float?>(null) }
            Text(status, color = AurionColors.TextMid)
            progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = AurionColors.Cyan) }
            Spacer(Modifier.height(10.dp))
            if (info == null) {
                SecondaryButton("Nach Updates suchen", Modifier.fillMaxWidth()) {
                    status = "Suche …"
                    scope.launch {
                        info = Updater.check()
                        status = info?.let { "Update ${it.versionName} verfügbar" } ?: "Aurion ist aktuell (${BuildConfig.VERSION_NAME})"
                    }
                }
            } else {
                PrimaryButton("Update ${info!!.versionName} installieren", Modifier.fillMaxWidth()) {
                    scope.launch {
                        runCatching { Updater.downloadAndInstall(ctx, info!!) { progress = it } }
                            .onFailure { status = "Download fehlgeschlagen: ${it.message}" }
                    }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun VoiceSection() {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val speaker = remember { Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { speaker.shutdown() } }
    val ready by speaker.ready.collectAsState()
    var lang by remember { mutableStateOf(if (settings.lastLang == "en") "en" else "de") }
    var selDe by remember { mutableStateOf(settings.voiceDe) }
    var selEn by remember { mutableStateOf(settings.voiceEn) }
    var rate by remember { mutableFloatStateOf(settings.speechRate) }
    var showOnline by remember { mutableStateOf(false) }

    val sample = if (lang == "en") "Hi, I'm Aurion. This is how I sound." else "Hallo, ich bin Aurion. So klinge ich."
    fun choose(name: String?) {
        if (lang == "en") { selEn = name; settings.voiceEn = name } else { selDe = name; settings.voiceDe = name }
        speaker.speak(sample, lang, voiceName = name)
    }

    GlassCard(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("de" to "Deutsch", "en" to "English").forEach { (code, label) ->
                FilterChip(
                    selected = lang == code,
                    onClick = { lang = code },
                    label = { Text(label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0x332F7BFF), selectedLabelColor = AurionColors.Cyan
                    )
                )
            }
        }
        // ---- Natürliche neuronale Stimmen ----
        val packStates by Packs.states.collectAsState()
        val selectedNow = if (lang == "en") selEn else selDe
        Text(
            "✨ Natürliche Stimmen (neuronal, offline)",
            style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh, modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            "Klingen deutlich menschlicher als die Systemstimmen. Einmal laden, danach komplett offline.",
            style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(bottom = 4.dp)
        )
        Packs.voices.filter { it.lang == lang }.forEach { p ->
            val id = Speaker.NEURAL + p.id
            PackRow(
                pack = p,
                state = packStates[p.id] ?: Packs.state(ctx, p.id),
                selected = selectedNow == id,
                onSelect = { choose(id) },
                onDelete = {
                    Packs.delete(ctx, p.id)
                    if (selDe == id) { selDe = null; settings.voiceDe = null }
                    if (selEn == id) { selEn = null; settings.voiceEn = null }
                },
            )
        }
        Text(
            "Android-Stimmen",
            style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh, modifier = Modifier.padding(top = 16.dp)
        )
        if (!ready) {
            Text("Stimmen werden geladen …", color = AurionColors.TextMid, modifier = Modifier.padding(top = 10.dp))
        } else {
            val all = remember(ready, lang) { speaker.voices(lang) }
            val voices = if (showOnline) all else all.filterNot { it.isNetworkConnectionRequired }
            val selected = if (lang == "en") selEn else selDe
            Text(
                "Tippe auf eine Stimme, um sie zu hören und auszuwählen.",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
            )
            VoiceRow("Systemstandard", "Stimme aus den Android-Einstellungen", selected == null) { choose(null) }
            voices.forEachIndexed { i, v ->
                val region = v.locale.getDisplayCountry(java.util.Locale.GERMAN).ifBlank { v.locale.displayName }
                val quality = when {
                    v.quality >= 500 -> "sehr hohe Qualität"
                    v.quality >= 400 -> "hohe Qualität"
                    else -> "normale Qualität"
                }
                VoiceRow(
                    "Stimme ${i + 1} · $region",
                    "$quality · ${if (v.isNetworkConnectionRequired) "online" else "offline"}",
                    selected == v.name
                ) { choose(v.name) }
            }
            if (voices.isEmpty()) {
                Text("Keine Offline-Stimme für diese Sprache installiert.", color = AurionColors.Amber, modifier = Modifier.padding(top = 6.dp))
            }
            if (all.any { it.isNetworkConnectionRequired }) {
                ToggleRow("Online-Stimmen anzeigen", showOnline) { showOnline = it }
            }
        }

        Text("Sprechtempo: ${"%.2f".format(rate)}×", color = AurionColors.TextHigh, modifier = Modifier.padding(top = 14.dp))
        Slider(
            value = rate,
            onValueChange = { rate = it },
            onValueChangeFinished = {
                settings.speechRate = rate
                speaker.speak(sample, lang)
            },
            valueRange = 0.7f..1.5f,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = AurionColors.Blue)
        )
        SecondaryButton("Weitere Stimmen installieren", Modifier.fillMaxWidth()) {
            val ok = runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            if (!ok) runCatching { ctx.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

@Composable
private fun VoiceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Color(0x262F7BFF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (selected) "●" else "○", color = if (selected) AurionColors.Cyan else AurionColors.TextLow)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium)
        }
        Icon(Icons.Rounded.PlayArrow, "Anhören", tint = AurionColors.TextMid)
    }
}

@Composable
private fun WeatherSection(tick: Int) {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val scope = rememberCoroutineScope()
    var useDevice by remember { mutableStateOf(settings.weatherUseDevice) }
    var placeName by remember { mutableStateOf(settings.weatherPlaceName) }
    var input by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val locPerm = remember(tick) { WeatherService.hasLocationPermission(ctx) }

    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Kostenlos über Open-Meteo – ohne Konto, ohne API-Schlüssel. Frag z. B. „Wie wird das Wetter morgen?“ oder „Brauche ich heute einen Schirm?“",
            style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid
        )
        ToggleRow("Aktuellen Standort verwenden", useDevice) { useDevice = it; settings.weatherUseDevice = it }
        if (useDevice) {
            StatusRow(
                "Standort", if (locPerm) "Ungefährer Standort erlaubt" else "Berechtigung fehlt – sonst wird der feste Ort genutzt",
                locPerm, if (locPerm) null else "Erlauben"
            ) { openAppSettings(ctx) }
        }
        Text(
            if (useDevice) "Ersatz-Ort, falls kein Standort verfügbar ist:" else "Fester Ort für das Wetter:",
            color = AurionColors.TextMid, modifier = Modifier.padding(top = 10.dp)
        )
        placeName?.let { Text("Aktuell: $it", color = AurionColors.Cyan, modifier = Modifier.padding(top = 4.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ort oder PLZ") }, singleLine = true,
                shape = RoundedCornerShape(14.dp), colors = fieldColors()
            )
            Spacer(Modifier.size(8.dp))
            SecondaryButton("Setzen", enabled = input.isNotBlank()) {
                scope.launch {
                    val p = WeatherService.geocode(input)
                    if (p == null) msg = "Ort nicht gefunden"
                    else {
                        settings.weatherPlaceName = p.name
                        settings.weatherLat = p.lat
                        settings.weatherLon = p.lon
                        placeName = p.name; input = ""; msg = null
                    }
                }
            }
        }
        msg?.let { Text(it, color = AurionColors.Amber, modifier = Modifier.padding(top = 6.dp)) }
    }
}

/** Zeile für ein herunterladbares Sprachpaket (Stimme oder Whisper) */
@Composable
fun PackRow(pack: Pack, state: PackState, selected: Boolean = false, onSelect: (() -> Unit)? = null, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0x262F7BFF) else Color.Transparent)
            .then(if (state is PackState.Ready && onSelect != null) Modifier.clickable { onSelect() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onSelect != null) {
                Text(
                    if (selected) "●" else "○",
                    color = if (selected) AurionColors.Cyan else if (state is PackState.Ready) AurionColors.TextMid else AurionColors.TextLow
                )
                Spacer(Modifier.size(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(pack.title, color = AurionColors.TextHigh, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (state is PackState.Ready) "${pack.description} · ${sizeLabel(Packs.sizeOnDisk(ctx, pack.id))}" else "${pack.description} · ${pack.sizeLabel}",
                    color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium
                )
            }
            when (state) {
                is PackState.Ready -> {
                    if (onSelect != null) IconButton(onClick = { onSelect() }) {
                        Icon(Icons.Rounded.PlayArrow, "Anhören", tint = AurionColors.Cyan)
                    }
                    IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Löschen", tint = AurionColors.TextLow) }
                }
                is PackState.Downloading, PackState.Extracting -> IconButton(onClick = { Packs.cancel(ctx, pack.id) }) {
                    Icon(Icons.Rounded.Close, "Abbrechen", tint = AurionColors.TextMid)
                }
                else -> SecondaryButton("Laden") { Packs.download(ctx, pack) }
            }
        }
        when (state) {
            is PackState.Downloading -> LinearProgressIndicator(
                progress = { state.progress }, color = AurionColors.Cyan,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            PackState.Extracting -> {
                LinearProgressIndicator(color = AurionColors.Cyan, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text("Wird installiert …", color = AurionColors.TextMid, fontSize = 12.sp)
            }
            is PackState.Failed -> Text(state.message, color = AurionColors.Amber, fontSize = 12.sp)
            else -> Unit
        }
    }
}

@Composable
private fun WhisperSection() {
    val ctx = LocalContext.current
    val packStates by Packs.states.collectAsState()
    GlassCard(Modifier.fillMaxWidth()) {
        Text(
            "Whisper transkribiert Diktate und Sprachnachrichten sehr genau – komplett offline, in Deutsch, Englisch und vielen weiteren Sprachen. Ohne Whisper nutzt das Diktat die Live-Erkennung von Android.",
            style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid
        )
        Packs.whisper.forEach { p ->
            PackRow(pack = p, state = packStates[p.id] ?: Packs.state(ctx, p.id), onDelete = { Packs.delete(ctx, p.id) })
        }
    }
}

@Composable
private fun ModelSection() {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(settings.modelPath?.takeIf { File(it).exists() }) }
    var dl by remember { mutableStateOf<DownloadState?>(ModelManager.poll(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(ModelManager.installed(ctx)) }
    var hfToken by remember { mutableStateOf(settings.hfToken ?: "") }
    var toDelete by remember { mutableStateOf<File?>(null) }

    fun delete(f: File) {
        if (current == f.absolutePath) {
            LlmEngine.get(ctx).release()
            settings.modelPath = null
            current = null
        }
        f.delete()
        installed = ModelManager.installed(ctx)
    }

    toDelete?.let { f ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Modell löschen?") },
            text = { Text("${f.name} (${sizeLabel(f.length())}) wird vom Gerät entfernt. Du kannst es später wieder herunterladen.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { delete(f); toDelete = null }) { Text("Löschen", color = AurionColors.Amber) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { toDelete = null }) { Text("Abbrechen") } },
            containerColor = AurionColors.SurfaceHigh,
        )
    }

    LaunchedEffect(dl?.running) {
        while (dl?.running == true) {
            delay(1000)
            dl = ModelManager.poll(ctx)
            if (dl?.done == true) {
                current = settings.modelPath
                installed = ModelManager.installed(ctx)
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                runCatching { ModelManager.importFrom(ctx, uri, displayName(ctx, uri) ?: "model.task") }
                current = settings.modelPath
                installed = ModelManager.installed(ctx)
                importing = false
            }
        }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        StatusRow(
            "Aktives Modell",
            current?.let { File(it).name } ?: "Keins – Befehle funktionieren, freie Fragen brauchen ein Modell",
            current != null
        )
        dl?.let { d ->
            if (d.running) {
                Text("Download läuft … ${(d.progress * 100).toInt()} %", color = AurionColors.TextMid, modifier = Modifier.padding(top = 6.dp))
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = AurionColors.Cyan)
                SecondaryButton("Abbrechen") { ModelManager.cancelDownload(ctx); dl = null }
            } else if (d.failed) {
                Text("Download fehlgeschlagen – bei Gemma: Token und Lizenz-Zustimmung prüfen, sonst erneut versuchen.", color = AurionColors.Amber)
            }
        }
        if (dl?.running != true) {
            ModelManager.options.forEach { opt ->
                val file = installed.firstOrNull { it.name == opt.fileName }
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(opt.title, style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                    Text("${opt.description} · ${opt.sizeLabel}", style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid)
                    Spacer(Modifier.height(6.dp))
                    if (file != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                (if (current == file.absolutePath) "✓ Aktiv · " else "Installiert · ") + sizeLabel(file.length()),
                                color = if (current == file.absolutePath) AurionColors.Cyan else AurionColors.TextMid,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { toDelete = file }) {
                                Icon(Icons.Rounded.DeleteOutline, "Modell löschen", tint = AurionColors.TextMid)
                            }
                        }
                    }
                    when {
                        file != null && current == file.absolutePath -> Unit
                        file != null -> SecondaryButton("Verwenden") {
                            settings.modelPath = file.absolutePath
                            LlmEngine.get(ctx).release()
                            current = file.absolutePath
                        }
                        else -> {
                            if (opt.needsToken) {
                                Text(
                                    "Einmalig: auf huggingface.co anmelden, auf der Modellseite der Gemma-Lizenz zustimmen und unter Settings › Access Tokens einen Lese-Token erstellen.",
                                    style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedTextField(
                                    hfToken, { hfToken = it; settings.hfToken = it.trim() }, Modifier.fillMaxWidth(),
                                    label = { Text("Hugging-Face-Token (hf_…)") }, singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    shape = RoundedCornerShape(14.dp), colors = fieldColors()
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SecondaryButton("Modellseite öffnen") {
                                        runCatching {
                                            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/google/gemma-3n-E2B-it-litert-preview")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        }
                                    }
                                    PrimaryButton("Herunterladen", enabled = hfToken.isNotBlank()) {
                                        ModelManager.startDownload(ctx, opt)
                                        dl = DownloadState(true, 0f, false, false)
                                    }
                                }
                            } else PrimaryButton("Herunterladen") {
                                ModelManager.startDownload(ctx, opt)
                                dl = DownloadState(true, 0f, false, false)
                            }
                        }
                    }
                }
            }
            // Eigene, importierte Modelle
            installed.filter { f -> ModelManager.options.none { it.fileName == f.name } }.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                        Text(
                            (if (current == f.absolutePath) "✓ Aktiv · " else "Eigenes Modell · ") + sizeLabel(f.length()),
                            color = if (current == f.absolutePath) AurionColors.Cyan else AurionColors.TextMid,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (current != f.absolutePath) SecondaryButton("Verwenden") {
                        settings.modelPath = f.absolutePath
                        LlmEngine.get(ctx).release()
                        current = f.absolutePath
                    }
                    IconButton(onClick = { toDelete = f }) {
                        Icon(Icons.Rounded.DeleteOutline, "Modell löschen", tint = AurionColors.TextMid)
                    }
                }
            }
            if (installed.isNotEmpty()) {
                val free = remember(installed) { ModelManager.modelDir(ctx).usableSpace }
                Text(
                    "Belegt: ${sizeLabel(installed.sumOf { it.length() })} · Frei: ${sizeLabel(free)}",
                    style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 10.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            SecondaryButton(if (importing) "Wird importiert …" else "Eigene .task-Datei wählen", Modifier.fillMaxWidth(), enabled = !importing) {
                picker.launch(arrayOf("*/*"))
            }
            Text(
                "Modelle laufen komplett offline auf dem Gerät (MediaPipe / LiteRT). Empfohlen: WLAN für den Download.",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun BringSection() {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val client = remember { BringClient(settings) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(settings.bringEmail ?: "") }
    var pw by remember { mutableStateOf("") }
    var connected by remember { mutableStateOf(client.isConfigured) }
    var lists by remember { mutableStateOf<List<BringList>>(emptyList()) }
    var selected by remember { mutableStateOf(settings.bringListUuid) }
    var msg by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(connected) {
        if (connected) runCatching { client.lists() }.onSuccess { lists = it }.onFailure { msg = it.message }
    }

    GlassCard(Modifier.fillMaxWidth()) {
        if (!connected) {
            Text("Mit deinem Bring!-Konto anmelden:", color = AurionColors.TextMid)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-Mail") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), shape = RoundedCornerShape(14.dp), colors = fieldColors()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Passwort") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), shape = RoundedCornerShape(14.dp), colors = fieldColors()
            )
            Spacer(Modifier.height(12.dp))
            PrimaryButton(if (busy) "Verbinde …" else "Verbinden", Modifier.fillMaxWidth(), enabled = !busy && email.isNotBlank() && pw.isNotBlank()) {
                busy = true; msg = null
                scope.launch {
                    runCatching { client.login(email.trim(), pw) }
                        .onSuccess { lists = it; connected = true; selected = settings.bringListUuid; pw = "" }
                        .onFailure { msg = it.message ?: "Anmeldung fehlgeschlagen" }
                    busy = false
                }
            }
        } else {
            StatusRow("Verbunden", settings.bringEmail ?: "", true)
            if (lists.isNotEmpty()) {
                Text("Liste für Aurion:", color = AurionColors.TextMid, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                lists.forEach { l ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                selected = l.uuid
                                settings.bringListUuid = l.uuid
                                settings.bringListName = l.name
                            }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (selected == l.uuid) "●" else "○", color = if (selected == l.uuid) AurionColors.Cyan else AurionColors.TextLow)
                        Spacer(Modifier.size(10.dp))
                        Text(l.name, color = AurionColors.TextHigh)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Abmelden", Modifier.fillMaxWidth()) {
                settings.clearBring(); connected = false; lists = emptyList()
            }
        }
        msg?.let { Text(it, color = AurionColors.Amber, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = AurionColors.TextHigh, modifier = Modifier.weight(1f))
        Switch(
            checked = v, onCheckedChange = { v = it; onChange(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = AurionColors.Blue, checkedThumbColor = Color.White)
        )
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AurionColors.Cyan,
    unfocusedBorderColor = AurionColors.GlassBorder,
    focusedTextColor = AurionColors.TextHigh,
    unfocusedTextColor = AurionColors.TextHigh,
    cursorColor = AurionColors.Cyan,
)

// ---------- Helfer ----------

private fun hasPerm(ctx: Context, p: String) =
    androidx.core.content.ContextCompat.checkSelfPermission(ctx, p) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun isDefaultAssistant(ctx: Context): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= 29) {
        val rm = ctx.getSystemService(RoleManager::class.java)
        rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && rm.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } else {
        AndroidSettings.Secure.getString(ctx.contentResolver, "assistant")?.contains(ctx.packageName) == true
    }
}.getOrDefault(false)

private fun openAssistantSettings(ctx: Context) {
    val intents = listOf(
        Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
        Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Intent(AndroidSettings.ACTION_SETTINGS),
    )
    for (i in intents) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
}

private fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1e6)
    else -> "%.0f KB".format(bytes / 1e3)
}

fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
