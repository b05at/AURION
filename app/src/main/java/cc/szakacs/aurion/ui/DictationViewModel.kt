package cc.szakacs.aurion.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.data.Transcript
import cc.szakacs.aurion.llm.LlmEngine
import cc.szakacs.aurion.speech.AudioDecoder
import cc.szakacs.aurion.speech.Packs
import cc.szakacs.aurion.speech.PcmRecorder
import cc.szakacs.aurion.speech.WhisperEngine
import cc.szakacs.aurion.voice.SpeechCallback
import cc.szakacs.aurion.voice.SpeechInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class DictMode { LIVE, WHISPER }

data class DictState(
    val mode: DictMode = DictMode.LIVE,
    val lang: String = "de",          // "de", "en", "" = automatisch (nur Whisper)
    val recording: Boolean = false,
    val text: String = "",
    val partial: String = "",
    val level: Float = 0f,
    val busy: String? = null,          // z. B. "Transkribiere … 40 %"
    val error: String? = null,
    val savedId: String? = null,
    val seconds: Int = 0,
    /** Diktat-Karte im Chat sichtbar */
    val active: Boolean = false,
    /** Herkunft für das manuelle Speichern ("Diktat", "Audiodatei", …) */
    val source: String = "Diktat",
)

/**
 * Diktat & Transkription:
 *  - Live: System-Spracherkennung (on-device), läuft fortlaufend bis zum Stopp
 *  - Whisper: Aufnahme -> genaue Offline-Transkription
 *  - Audiodateien/Sprachnachrichten per Whisper
 */
class DictationViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val speech = SpeechInput(ctx)
    private var recorder: PcmRecorder? = null

    private val _state = MutableStateFlow(
        DictState(
            mode = if (Packs.firstInstalledWhisper(ctx) != null) DictMode.WHISPER else DictMode.LIVE,
            lang = Settings.get(ctx).lastLang,
        )
    )
    val state: StateFlow<DictState> = _state

    private fun update(f: (DictState) -> DictState) { _state.value = f(_state.value) }

    val whisperAvailable: Boolean get() = Packs.firstInstalledWhisper(ctx) != null

    fun setMode(m: DictMode) { if (!_state.value.recording) update { it.copy(mode = m, lang = if (m == DictMode.LIVE && it.lang.isEmpty()) "de" else it.lang) } }
    fun setLang(l: String) { if (!_state.value.recording) update { it.copy(lang = l) } }
    fun setText(t: String) = update { it.copy(text = t, savedId = null) }
    fun clear() = update { it.copy(text = "", partial = "", savedId = null, error = null) }

    fun toggle() = if (_state.value.recording) stop() else start()

    /** Diktat-Karte im Chat öffnen (ohne Aufnahme) */
    fun open() = update { it.copy(active = true) }

    /** Diktat-Karte schließen */
    fun close() {
        if (_state.value.recording) stop()
        update { it.copy(active = false, error = null) }
    }

    fun start() {
        if (_state.value.busy != null) return
        update { it.copy(active = true, recording = true, error = null, partial = "", seconds = 0, savedId = null) }
        if (_state.value.mode == DictMode.WHISPER && whisperAvailable) startWhisper() else startLive()
        viewModelScope.launch {
            while (_state.value.recording) { delay(1000); update { it.copy(seconds = it.seconds + 1) } }
        }
    }

    // ---------- Live (fortlaufend) ----------
    private fun startLive() {
        val lang = _state.value.lang.ifEmpty { "de" }
        speech.start(lang, lang, object : SpeechCallback {
            override fun onLevel(level: Float) = update { it.copy(level = level) }
            override fun onPartial(text: String) = update { it.copy(partial = text) }
            override fun onFinal(text: String) {
                update { it.copy(text = join(it.text, text), partial = "") }
                restartLive()
            }
            override fun onError(message: String) {
                update { it.copy(partial = "") }
                if (message.contains("Berechtigung")) { update { it.copy(recording = false, error = message) }; return }
                restartLive()
            }
        })
    }

    private fun restartLive() {
        if (!_state.value.recording) return
        viewModelScope.launch {
            delay(150)
            if (_state.value.recording) startLive()
        }
    }

    // ---------- Whisper ----------
    private fun startWhisper() {
        recorder = PcmRecorder().also { r -> r.start { lv -> update { it.copy(level = lv) } } }
    }

    fun stop() {
        val wasWhisper = recorder != null
        update { it.copy(recording = false, level = 0f) }
        if (wasWhisper) {
            val samples = recorder?.stop() ?: FloatArray(0)
            recorder = null
            runWhisper(samples, "Aufnahme")
        } else {
            speech.stopListening()
            viewModelScope.launch {
                delay(1200)
                // falls noch ein Teilergebnis offen ist, übernehmen
                val p = _state.value.partial
                speech.stop()
                if (p.isNotBlank()) update { it.copy(text = join(it.text, p), partial = "") }
            }
        }
    }

    /** Sprachnachricht / Audiodatei transkribieren */
    fun transcribeFile(uri: Uri) {
        val pack = Packs.firstInstalledWhisper(ctx)
        if (pack == null) {
            update { it.copy(active = true, error = "Für Audiodateien bitte zuerst ein Whisper-Modell laden (Einstellungen › Spracherkennung).") }
            return
        }
        update { it.copy(active = true, busy = "Lese Audiodatei …", error = null) }
        viewModelScope.launch {
            try {
                val samples = AudioDecoder.decode(ctx, uri) { p -> update { it.copy(busy = "Lese Audiodatei … ${(p * 100).toInt()} %") } }
                runWhisper(samples, "Audiodatei")
            } catch (e: Exception) {
                update { it.copy(busy = null, error = "Audiodatei: ${e.message}") }
            }
        }
    }

    private fun runWhisper(samples: FloatArray, source: String) {
        val pack = Packs.firstInstalledWhisper(ctx) ?: return
        if (samples.size < WhisperEngine.SR / 2) { update { it.copy(busy = null) }; return }
        val minutes = samples.size / WhisperEngine.SR / 60
        update { it.copy(busy = "Transkribiere${if (minutes > 0) " ($minutes min Audio)" else ""} …") }
        val before = _state.value.text
        viewModelScope.launch {
            try {
                val text = WhisperEngine.transcribe(ctx, pack.id, samples, _state.value.lang) { p, sofar ->
                    update { it.copy(busy = "Transkribiere … ${(p * 100).toInt()} %", text = join(before, sofar)) }
                }
                // Nicht automatisch speichern – nur über den "Speichern"-Knopf
                update { it.copy(text = join(before, text), busy = null, source = source) }
            } catch (e: Exception) {
                update { it.copy(busy = null, error = "Transkription fehlgeschlagen: ${e.message}") }
            }
        }
    }

    // ---------- KI-Nachbearbeitung ----------
    fun aiRewrite(kind: String) {
        val src = _state.value.text.trim()
        if (src.isEmpty()) return
        val llm = LlmEngine.get(ctx)
        if (!llm.isAvailable) { update { it.copy(error = "Dafür brauchst du ein Sprachmodell (Einstellungen)") }; return }
        val instruction = when (kind) {
            "clean" -> "Überarbeite das folgende Diktat: korrigiere Rechtschreibung, Zeichensetzung und Groß-/Kleinschreibung, entferne Füllwörter (äh, ähm) und Wiederholungen, gliedere in Absätze. Ändere den Inhalt nicht. Gib nur den überarbeiteten Text aus."
            "summary" -> "Fasse das folgende Diktat in wenigen Stichpunkten zusammen. Gib nur die Stichpunkte aus."
            "todo" -> "Mach aus dem folgenden Diktat eine kurze To-do-Liste mit einer Aufgabe pro Zeile, beginnend mit \"- \". Gib nur die Liste aus."
            "mail" -> "Formuliere aus dem folgenden Diktat eine höfliche, gut lesbare E-Mail (mit Betreffzeile). Gib nur die E-Mail aus."
            else -> "Überarbeite den folgenden Text. Gib nur das Ergebnis aus."
        }
        update { it.copy(busy = "Aurion überarbeitet …", error = null) }
        viewModelScope.launch {
            try {
                val out = llm.chat(instruction, emptyList(), src.take(6000), temperature = 0.2f) { partial ->
                    update { it.copy(text = partial) }
                }
                update { it.copy(text = out.ifBlank { src }, busy = null, savedId = null) }
            } catch (e: Exception) {
                update { it.copy(text = src, busy = null, error = e.message) }
            }
        }
    }

    fun save(source: String = _state.value.source) {
        val t = _state.value.text.trim()
        if (t.isEmpty()) return
        val tr = Transcript(id = _state.value.savedId ?: java.util.UUID.randomUUID().toString(), text = t, source = source)
        Store.get(ctx).saveTranscript(tr)
        update { it.copy(savedId = tr.id) }
    }

    fun load(t: Transcript) = update { it.copy(text = t.text, savedId = t.id, partial = "") }

    // ---------- Teilen ----------
    fun shareIntent(text: String, pkg: String? = null): Intent {
        val title = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("- ")?.take(60) ?: "Diktat"
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .putExtra(Intent.EXTRA_TITLE, title)
            .putExtra(Intent.EXTRA_TEXT, text)
        return if (pkg != null) send.setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        else Intent.createChooser(send, "Diktat teilen").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun join(a: String, b: String): String {
        val x = a.trimEnd()
        val y = b.trim()
        if (y.isEmpty()) return x
        if (x.isEmpty()) return y.replaceFirstChar { it.uppercase() }
        val sep = if (x.endsWith(".") || x.endsWith("?") || x.endsWith("!") || x.endsWith("\n")) " " else ". "
        return x + sep + y.replaceFirstChar { it.uppercase() }
    }

    override fun onCleared() {
        speech.stop()
        recorder?.stop()
        super.onCleared()
    }
}
