package cc.szakacs.aurion.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.szakacs.aurion.brain.Brain
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

data class AssistUiState(
    val orb: OrbState = OrbState.IDLE,
    val level: Float = 0f,
    val transcript: String = "",
    val reply: Reply? = null,
    val hint: String? = null,
)

class AssistantViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val settings = Settings.get(ctx)
    private val brain = Brain.get(ctx)
    private val speech = SpeechInput(ctx)
    private val speaker = Speaker(ctx)

    private val _state = MutableStateFlow(AssistUiState())
    val state: StateFlow<AssistUiState> = _state

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
                _state.value = _state.value.copy(level = level)
            }

            override fun onPartial(text: String) {
                _state.value = _state.value.copy(transcript = text)
            }

            override fun onFinal(text: String) {
                submit(text)
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

    fun submit(text: String) {
        if (text.isBlank()) return
        speech.stop()
        _state.value = _state.value.copy(orb = OrbState.THINKING, transcript = text, level = 0f, hint = null)
        viewModelScope.launch {
            val reply = brain.handle(text)
            _state.value = _state.value.copy(reply = reply)
            if (settings.speakReplies) {
                _state.value = _state.value.copy(orb = OrbState.SPEAKING)
                speaker.speak(reply.text, reply.lang) {
                    viewModelScope.launch {
                        _state.value = _state.value.copy(orb = OrbState.IDLE)
                        // Bei Rückfragen direkt wieder zuhören
                        if (reply.text.trimEnd().endsWith("?")) startListening() else onConversationIdle?.invoke()
                    }
                }
            } else {
                _state.value = _state.value.copy(orb = OrbState.IDLE)
                if (reply.text.trimEnd().endsWith("?")) startListening()
            }
        }
    }

    fun reset() {
        stop()
        _state.value = AssistUiState()
    }

    override fun onCleared() {
        speech.stop()
        speaker.shutdown()
        super.onCleared()
    }
}
