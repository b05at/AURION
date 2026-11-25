package cc.szakacs.aurion.ui

import android.app.AlarmManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

// ======================================================================
// Gespräch
// ======================================================================

@Composable
fun ChatScreen(vm: AssistantViewModel) {
    val ctx = LocalContext.current
    val chat by Store.get(ctx).chat.collectAsState()
    val st by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size, st.orb) {
        val count = chat.size + if (st.orb != OrbState.IDLE) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (chat.isEmpty() && st.orb == OrbState.IDLE) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Hallo! Ich bin Aurion.", style = MaterialTheme.typography.displaySmall, color = AurionColors.TextHigh)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Probier zum Beispiel:\n„Erinnere mich in 20 Minuten an die Wäsche“\n„Setz Milch und Eier auf die Einkaufsliste“\n„Merk dir, dass mein Auto beim Bahnhof steht“\n„Remind me tomorrow at 8 to call Anna“",
                    style = MaterialTheme.typography.bodyLarge, color = AurionColors.TextMid
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(chat) { Bubble(it) }
                if (st.orb != OrbState.IDLE) {
                    item {
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
            }
        }
        st.hint?.let {
            Text(it, color = AurionColors.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp))
        }

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
                TextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Nachricht an Aurion", color = AurionColors.TextLow) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.submit(input); input = "" }),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f)
                )
                if (input.isNotBlank()) {
                    IconButton(onClick = { vm.submit(input); input = "" }) {
                        Icon(Icons.AutoMirrored.Rounded.Send, "Senden", tint = AurionColors.Cyan)
                    }
                }
            }
            Box(
                Modifier
                    .size(76.dp)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.toggleListening() },
                contentAlignment = Alignment.Center
            ) {
                AurionOrb(st.orb, st.level, size = 76.dp)
            }
        }
    }
}

@Composable
private fun Bubble(m: ChatMessage) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            m.text,
            style = MaterialTheme.typography.bodyLarge,
            color = AurionColors.TextHigh,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 22.dp, topEnd = 22.dp,
                        bottomStart = if (m.fromUser) 22.dp else 6.dp,
                        bottomEnd = if (m.fromUser) 6.dp else 22.dp
                    )
                )
                .background(
                    if (m.fromUser) Brush.linearGradient(listOf(AurionColors.Blue, Color(0xFF3A66E8)))
                    else Brush.linearGradient(listOf(Color(0x26FFFFFF), Color(0x14FFFFFF)))
                )
                .padding(horizontal = 16.dp, vertical = 11.dp)
        )
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

@Composable
fun MemoryScreen() {
    val ctx = LocalContext.current
    val store = Store.get(ctx)
    val mem by store.memory.collectAsState()
    var input by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
        item {
            Text(
                "Was Aurion sich merkt, fließt in alle Antworten ein – bleibt aber nur auf diesem Gerät.",
                color = AurionColors.TextMid, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(6.dp)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Neuer Eintrag, z. B. „Ich trinke Kaffee schwarz“") },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (input.isNotBlank()) { store.addMemory(input); input = "" } }),
                colors = fieldColors()
            )
        }
        item { SectionTitle("Gemerkt (${mem.size})") }
        items(mem.asReversed(), key = { it.id }) { f ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.text, color = AurionColors.TextHigh, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { store.deleteMemory(f.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, "Löschen", tint = AurionColors.TextLow)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            SecondaryButton("Gesprächsverlauf löschen", Modifier.fillMaxWidth()) { store.clearChat() }
        }
    }
}

// ======================================================================
// Einstellungen
// ======================================================================

@Composable
fun SettingsScreen(tick: Int) {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).imePadding()
    ) {
        // ---------- Assistent ----------
        SectionTitle("Assistent")
        GlassCard(Modifier.fillMaxWidth()) {
            val isDefault = remember(tick) { isDefaultAssistant(ctx) }
            StatusRow(
                "Standard-Assistent",
                if (isDefault) "Aurion startet über Seitentaste / Assistent-Geste" else "Unter „Digitaler Assistent“ Aurion wählen",
                isDefault, if (isDefault) null else "Öffnen"
            ) { openAssistantSettings(ctx) }
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
                "Tipp (Samsung): Einstellungen › Erweiterte Funktionen › Seitentaste › Gedrückt halten › Digitaler Assistent.",
                style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextLow, modifier = Modifier.padding(top = 8.dp)
            )
        }

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

        // ---------- Sprachmodell ----------
        SectionTitle("Lokales Sprachmodell")
        ModelSection()

        // ---------- Bring! ----------
        SectionTitle("Bring! Einkaufsliste")
        BringSection()

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
private fun ModelSection() {
    val ctx = LocalContext.current
    val settings = Settings.get(ctx)
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(settings.modelPath?.takeIf { File(it).exists() }) }
    var dl by remember { mutableStateOf<DownloadState?>(ModelManager.poll(ctx)) }
    var importing by remember { mutableStateOf(false) }
    var installed by remember { mutableStateOf(ModelManager.installed(ctx)) }

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
                Text("Download fehlgeschlagen – bitte erneut versuchen.", color = AurionColors.Amber)
            }
        }
        if (dl?.running != true) {
            ModelManager.options.forEach { opt ->
                val file = installed.firstOrNull { it.name == opt.fileName }
                Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(opt.title, style = MaterialTheme.typography.titleMedium, color = AurionColors.TextHigh)
                    Text("${opt.description} · ${opt.sizeLabel}", style = MaterialTheme.typography.bodyMedium, color = AurionColors.TextMid)
                    Spacer(Modifier.height(6.dp))
                    when {
                        file != null && current == file.absolutePath -> Text("✓ Aktiv", color = AurionColors.Cyan)
                        file != null -> SecondaryButton("Verwenden") {
                            settings.modelPath = file.absolutePath
                            LlmEngine.get(ctx).release()
                            current = file.absolutePath
                        }
                        else -> PrimaryButton("Herunterladen") {
                            ModelManager.startDownload(ctx, opt)
                            dl = DownloadState(true, 0f, false, false)
                        }
                    }
                }
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

private fun displayName(ctx: Context, uri: Uri): String? = runCatching {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }
}.getOrNull()
