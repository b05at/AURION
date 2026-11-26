package cc.szakacs.aurion.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.szakacs.aurion.assist.ScreenContext
import cc.szakacs.aurion.brain.Brain
import cc.szakacs.aurion.brain.ScreenInput
import cc.szakacs.aurion.vision.Vision
import cc.szakacs.aurion.brain.Reply
import cc.szakacs.aurion.data.LangMode
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.llm.LlmEngine
import cc.szakacs.aurion.voice.SpeechCallback
import cc.szakacs.aurion.voice.SpeechInput
import cc.szakacs.aurion.voice.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Zustand der Bildschirm-Lupe (Auswahl, geteiltes Bild oder geteilter Text) */
data class LensState(
    val image: android.graphics.Bitmap?,
    val analyzing: Boolean = true,
    val result: cc.szakacs.aurion.vision.VisionResult? = null,
    val answer: String? = null,
    val answering: Boolean = false,
    /** Dateiname bei angehängten Dokumenten */
    val title: String? = null,
    /** Fortschritt, z. B. "Lese Seite 2 von 5 …" */
    val progress: String? = null,
    /** Abschnittsweise Zusammenfassung langer Dokumente */
    val digest: String? = null,
    /** Aurion wartet darauf, dass der Nutzer sagt, was er mit dem Anhang tun möchte */
    val askWhat: Boolean = false,
    /** Anhang aus dem Chat: erscheint als Vorschau im Eingabefeld, Antworten landen im Chat */
    val inChat: Boolean = false,
)

data class AssistUiState(
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val transcript: String = "",
    val reply: Reply? = null,
    val hint: String? = null,
    /** Live-Text, während das LLM schreibt */
    val streaming: String? = null,
    val lens: LensState? = null,
)

/** Prozessweiter Bereich für Anfragen, die ein geschlossenes Fenster überleben sollen */
object AppScope : kotlinx.coroutines.CoroutineScope by kotlinx.coroutines.CoroutineScope(
    kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val settings = Settings.get(ctx)
    private val brain = Brain.get(ctx)
    private val speech = SpeechInput(ctx)
    private val speaker = Speaker(ctx)

    private val _state = MutableStateFlow(AssistUiState())
    val state: StateFlow<AssistUiState> = _state

    /** Mikrofon-Pegel separat – wird nur vom Orb pro Bild gelesen, ohne die Oberfläche neu aufzubauen */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    /** Wird gesetzt, wenn eine Antwort fertig vorgelesen wurde (für Auto-Schließen im Overlay). */
    var onConversationIdle: (() -> Unit)? = null

    init {
        viewModelScope.launch { LlmEngine.get(ctx).warmUp() }
    }

    fun hasMicPermission() =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun toggleListening() {
        when (_state.value.orb) {
            OrbState.LISTENING -> speech.stopListening()
            OrbState.SPEAKING -> { speaker.stop(); startListening() }
            OrbState.THINKING -> Unit
            OrbState.IDLE -> startListening()
        }
    }

    fun startListening() {
        if (!hasMicPermission()) {
            _state.value = _state.value.copy(hint = "Mikrofon-Berechtigung fehlt – bitte in der App erlauben")
            return
        }
        speaker.stop()
        _state.value = _state.value.copy(orb = OrbState.LISTENING, transcript = "", hint = null, level = 0f)
        val lang = when (settings.langMode) {
            LangMode.DE -> "de"
            LangMode.EN -> "en"
            LangMode.AUTO -> null
        }
        speech.start(lang, settings.lastLang, object : SpeechCallback {
            override fun onLevel(level: Float) {
                _level.value = level
            }

            override fun onPartial(text: String) {
                _state.value = _state.value.copy(transcript = text)
            }

            override fun onFinal(text: String) {
                submit(text, spoken = true)
            }

            override fun onError(message: String) {
                _state.value = _state.value.copy(orb = OrbState.IDLE, level = 0f, hint = message)
            }
        })
    }

    fun stop() {
        speech.stop()
        speaker.stop()
        _state.value = _state.value.copy(orb = OrbState.IDLE, level = 0f)
    }

    /** App im Hintergrund: Mikrofon und Sprachausgabe anhalten, eine laufende Aufgabe aber weiterrechnen lassen */
    fun pauseAudio() {
        speech.stop()
        speaker.stop()
        if (_state.value.orb != OrbState.THINKING) _state.value = _state.value.copy(orb = OrbState.IDLE, level = 0f)
        _level.value = 0f
    }

    /** Liefert den Bildschirm-Kontext für das Brain: Auswahl > ganzer Bildschirm */
    private fun screenProvider(): (suspend () -> ScreenInput?) = provider@{
        val lens = _state.value.lens
        if (lens?.result != null) {
            return@provider ScreenInput(
                lens.result.text, lens.result.labels, lens.image, isSelection = true,
                title = lens.title, digest = lens.digest
            )
        }
        val shot = ScreenContext.screenshot.value
        val structureText = ScreenContext.text.value
        if (shot == null && structureText == null) return@provider null
        // Ohne Bildschirmtext (z. B. Bilder, Spiele) per OCR lesen
        val vr = if (shot != null && (structureText == null || structureText.length < 40)) Vision.analyze(shot) else null
        ScreenInput(
            text = listOfNotNull(structureText, vr?.text).joinToString("\n").take(5000),
            labels = vr?.labels.orEmpty(),
            image = shot,
            isSelection = false,
            app = ScreenContext.appPackage,
        )
    }

    /**
     * @param spoken true = per Sprache gefragt -> kurze, vorgelesene Antwort;
     *               false = getippt/Lupe -> ausführlich, nicht vorgelesen
     */
    fun submit(text: String, spoken: Boolean = false) {
        if (text.isBlank()) return
        speech.stop()
        _state.value = _state.value.copy(orb = OrbState.THINKING, transcript = text, level = 0f, hint = null, streaming = null)
        // Läuft app-weit: eine Anfrage wird auch fertig, wenn das Fenster geschlossen wird
        AppScope.launch {
            val lensNow = _state.value.lens
            val hasContext = lensNow?.result != null
            val inChat = lensNow?.inChat == true
            val lensActive = hasContext && !inChat   // Antwort in der Lupen-Karte (Bildschirm-Lupe)
            val attachmentLabel = if (inChat && lensNow?.askWhat == true) (lensNow.title ?: "Anhang") else null
            if (hasContext) _state.value = _state.value.copy(lens = _state.value.lens?.copy(answering = lensActive, answer = if (lensActive) null else _state.value.lens?.answer, askWhat = false))
            if (hasContext && Regex("zusammenfass|fasse|überblick|worum geht|summar|overview|wichtigsten|to-?dos|fristen", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                ensureDigest()
            }
            var lastEmit = 0L
            val reply = brain.handle(text, screenProvider(), spoken, attachment = attachmentLabel) { partial ->
                // Live-Text gebündelt weitergeben – flüssiger und spart Neuaufbauten der Oberfläche
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastEmit < 50) return@handle
                lastEmit = now
                if (lensActive) _state.value = _state.value.copy(lens = _state.value.lens?.copy(answer = partial))
                else _state.value = _state.value.copy(streaming = partial)
            }
            // Im Hintergrund fertig geworden? Dann Bescheid geben
            if (!AppVisibility.isVisible && settings.notifyWhenDone) AnswerNotifier.answerReady(ctx, text, reply.text)
            if (cleared) return@launch
            _state.value = _state.value.copy(
                reply = if (lensActive && reply.card == cc.szakacs.aurion.brain.CardKind.NONE) null else reply,
                streaming = null,
                lens = _state.value.lens?.copy(answering = false, answer = if (lensActive) reply.text else _state.value.lens?.answer),
            )
            if (spoken && settings.speakReplies) {
                _state.value = _state.value.copy(orb = OrbState.SPEAKING)
                speaker.speak(plain(reply.text), reply.lang, done = {
                    viewModelScope.launch {
                        _state.value = _state.value.copy(orb = OrbState.IDLE)
                        // Bei Rückfragen direkt wieder zuhören
                        if (reply.text.trimEnd().endsWith("?")) startListening() else onConversationIdle?.invoke()
                    }
                })
            } else {
                _state.value = _state.value.copy(orb = OrbState.IDLE)
                if (spoken && reply.text.trimEnd().endsWith("?")) startListening()
            }
        }
    }

    /** Markdown für die Sprachausgabe entfernen */
    private fun plain(s: String) = s
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>]+"), "")
        .replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
        .trim()

    // ================= Bildschirm-Lupe =================

    /** Eingekreister Bereich oder geteiltes Bild analysieren */
    fun analyzeImage(bitmap: android.graphics.Bitmap, autoExplain: Boolean = true, title: String? = null) {
        speech.stop(); speaker.stop()
        _state.value = _state.value.copy(lens = LensState(bitmap, title = title, askWhat = !autoExplain, inChat = !autoExplain), reply = null, transcript = "", orb = OrbState.THINKING)
        viewModelScope.launch {
            val r = Vision.analyze(bitmap)
            _state.value = _state.value.copy(lens = _state.value.lens?.copy(analyzing = false, result = r), orb = OrbState.IDLE)
            if (autoExplain && (LlmEngine.get(ctx).isAvailable)) {
                val q = when {
                    r.text.length > 300 -> "Fasse den markierten Inhalt kurz zusammen und erkläre, worum es geht."
                    r.text.isNotBlank() -> "Was ist das? Erkläre kurz, worum es beim markierten Inhalt geht."
                    else -> "Was ist auf dem markierten Bild zu sehen? Beschreibe es kurz."
                }
                submit(q, spoken = false)
            }
        }
    }

    /** Geteilten Text als Kontext übernehmen */
    fun analyzeText(text: String) {
        val r = cc.szakacs.aurion.vision.VisionResult(text, emptyList(), Vision.extractEntities(text))
        _state.value = _state.value.copy(lens = LensState(null, analyzing = false, result = r, askWhat = true, inChat = true, title = "Geteilter Text"), reply = null)
    }

    // ================= Anhänge =================

    /** Bild, Foto oder Dokument aus dem Chat anhängen */
    fun attach(uri: android.net.Uri) {
        speech.stop(); speaker.stop()
        _state.value = _state.value.copy(
            lens = LensState(null, analyzing = true, title = "Anhang", progress = "Öffne Anhang …", askWhat = true, inChat = true), reply = null
        )
        viewModelScope.launch {
            try {
                when (val a = cc.szakacs.aurion.vision.DocumentReader.read(ctx, uri) { p ->
                    _state.value = _state.value.copy(lens = _state.value.lens?.copy(progress = p))
                }) {
                    is cc.szakacs.aurion.vision.Attachment.Image -> analyzeImage(a.bitmap, autoExplain = false, title = a.name)
                    is cc.szakacs.aurion.vision.Attachment.Document -> analyzeDocument(a.name, a.text, a.preview, a.pages)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(lens = null, hint = "Anhang: ${e.message ?: "konnte nicht gelesen werden"}")
            }
        }
    }

    fun attachPhoto(bitmap: android.graphics.Bitmap) = analyzeImage(bitmap, autoExplain = false, title = "Foto")

    private suspend fun analyzeDocument(name: String, text: String, preview: android.graphics.Bitmap?, pages: Int) {
        if (text.isBlank()) {
            _state.value = _state.value.copy(lens = null, hint = "In „$name“ habe ich keinen Text gefunden.")
            return
        }
        val r = cc.szakacs.aurion.vision.VisionResult(text, emptyList(), Vision.extractEntities(text.take(20_000)))
        _state.value = _state.value.copy(
            lens = LensState(preview, analyzing = false, result = r, title = name, progress = null, askWhat = true, inChat = true)
        )
        // Nicht automatisch loslegen – der Nutzer sagt, was er damit machen möchte
    }

    /** Lange Dokumente bei Bedarf Abschnitt für Abschnitt zusammenfassen (für Gesamtüberblicke) */
    private suspend fun ensureDigest() {
        val lens = _state.value.lens ?: return
        val text = lens.result?.text ?: return
        if (lens.digest != null || lens.title == null || text.length <= 3500) return
        val llm = LlmEngine.get(ctx)
        if (!llm.isAvailable) return
        val chunks = text.chunked(2800).take(8)
        val parts = ArrayList<String>()
        chunks.forEachIndexed { i, c ->
            _state.value = _state.value.copy(lens = _state.value.lens?.copy(progress = "Lese Abschnitt ${i + 1} von ${chunks.size} …", answering = true))
            val sum = runCatching {
                llm.chat(
                    "Fasse den folgenden Abschnitt eines Dokuments in 3–6 knappen Stichpunkten auf Deutsch zusammen. Nur die Stichpunkte.",
                    emptyList(), c, temperature = 0.2f
                )
            }.getOrDefault("")
            if (sum.isNotBlank()) parts += "Abschnitt ${i + 1}:\n$sum"
        }
        _state.value = _state.value.copy(lens = _state.value.lens?.copy(digest = parts.joinToString("\n\n"), progress = null))
    }

    /** Artikel in Bring! abhaken (aus der Einkaufskarte) */
    fun checkOffShopping(name: String) {
        viewModelScope.launch {
            runCatching { cc.szakacs.aurion.bring.BringClient(settings).removeItem(name) }
                .onFailure { _state.value = _state.value.copy(hint = "Bring!: ${it.message}") }
        }
    }

    fun closeLens() {
        _state.value = _state.value.copy(lens = null)
    }

    fun reset() {
        stop()
        _state.value = AssistUiState()
    }

    /** Neues Gespräch: das bisherige wandert in den Verlauf, Rückfragen und Anzeige leeren */
    fun newConversation() {
        if (_state.value.orb == OrbState.THINKING) stopGeneration()
        reset()
        brain.resetConversation()
        cc.szakacs.aurion.data.Store.get(ctx).startNewChat()
    }

    /** Früheres Gespräch aus dem Verlauf fortsetzen */
    fun openConversation(id: String) {
        if (_state.value.orb == OrbState.THINKING) stopGeneration()
        reset()
        brain.resetConversation()
        cc.szakacs.aurion.data.Store.get(ctx).openConversation(id)
    }

    /** Stopp-Knopf: laufende Antwort beenden */
    fun stopGeneration() {
        LlmEngine.get(ctx).stopGeneration()
    }

    val isBusy: Boolean get() = _state.value.orb == OrbState.THINKING

    /** Letzte Antwort neu erzeugen */
    fun regenerate() {
        if (isBusy) return
        val store = cc.szakacs.aurion.data.Store.get(ctx)
        val chat = store.chat.value
        val idx = chat.indexOfLast { it.fromUser }
        if (idx < 0) return
        val q = chat[idx].text
        store.dropLastChat(chat.size - idx)
        _state.value = _state.value.copy(reply = null)
        submit(q, spoken = false)
    }

    /** Letzte eigene Nachricht zum Bearbeiten zurückholen (entfernt sie samt Antwort) */
    fun editLast(): String? {
        if (isBusy) return null
        val store = cc.szakacs.aurion.data.Store.get(ctx)
        val chat = store.chat.value
        val idx = chat.indexOfLast { it.fromUser }
        if (idx < 0) return null
        val q = chat[idx].text
        store.dropLastChat(chat.size - idx)
        _state.value = _state.value.copy(reply = null)
        return q
    }

    /** Eine Nachricht vorlesen */
    fun readAloud(text: String) {
        speech.stop()
        _state.value = _state.value.copy(orb = OrbState.SPEAKING)
        val lang = cc.szakacs.aurion.brain.Lang.detect(text, settings.lastLang)
        speaker.speak(plain(text), lang, done = {
            viewModelScope.launch { if (_state.value.orb == OrbState.SPEAKING) _state.value = _state.value.copy(orb = OrbState.IDLE) }
        })
    }

    @Volatile private var cleared = false

    override fun onCleared() {
        cleared = true
        speech.stop()
        speaker.shutdown()
        super.onCleared()
    }
}
