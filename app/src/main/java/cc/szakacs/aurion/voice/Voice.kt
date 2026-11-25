package cc.szakacs.aurion.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** Findet einen System-Spracherkenner, der nicht Aurion selbst ist. */
object Recognizers {
    fun systemRecognizer(ctx: Context): ComponentName? {
        val services = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != ctx.packageName }
        return services.firstOrNull { it.packageName == "com.google.android.as" }
            ?: services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.contains("google") }
            ?: services.firstOrNull()
    }

    fun create(ctx: Context): SpeechRecognizer {
        if (Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        }
        val comp = systemRecognizer(ctx)
        return if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
        else SpeechRecognizer.createSpeechRecognizer(ctx)
    }
}

interface SpeechCallback {
    fun onReady() {}
    fun onLevel(level: Float) {}
    fun onPartial(text: String) {}
    fun onFinal(text: String)
    fun onError(message: String)
}

/**
 * Spracheingabe (bevorzugt On-Device). Sprache: "de" / "en" / null = automatische Erkennung (ab Android 14).
 */
class SpeechInput(private val ctx: Context) {
    private var recognizer: SpeechRecognizer? = null

    fun start(lang: String?, fallbackLang: String, cb: SpeechCallback) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx) && Recognizers.systemRecognizer(ctx) == null) {
            cb.onError("Keine Spracherkennung auf dem Gerät verfügbar")
            return
        }
        val rec = Recognizers.create(ctx)
        recognizer = rec
        val tag = when (lang ?: fallbackLang) { "en" -> "en-US"; else -> "de-AT" }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            if (lang == null && Build.VERSION.SDK_INT >= 34) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, arrayListOf("de-DE", "de-AT", "en-US", "en-GB"))
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, arrayListOf("de-DE", "en-US"))
            }
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = cb.onReady()
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) = cb.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                val msg = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Ich habe nichts gehört"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon-Berechtigung fehlt"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Sprachpaket fehlt (offline)"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Spracherkennung ist beschäftigt"
                    else -> "Spracherkennung-Fehler $error"
                }
                cb.onError(msg)
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isBlank()) cb.onError("Ich habe nichts gehört") else cb.onFinal(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(cb::onPartial)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.let {
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        recognizer = null
    }
}

/** Sprachausgabe mit automatischer Wahl Deutsch/Englisch und wählbarer Stimme. */
class Speaker(ctx: Context) {
    private val appCtx = ctx.applicationContext
    private val settings = cc.szakacs.aurion.data.Settings.get(ctx)
    private var onDone: (() -> Unit)? = null
    private val _ready = kotlinx.coroutines.flow.MutableStateFlow(false)
    val ready: kotlinx.coroutines.flow.StateFlow<Boolean> = _ready
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        _ready.value = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { onDone?.invoke() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { onDone?.invoke() }
        })
    }

    /** Installierte Stimmen für "de" bzw. "en" – Offline-Stimmen und höchste Qualität zuerst. */
    fun voices(lang: String): List<Voice> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == lang }
            .filterNot { it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.toString() }, { it.name }))
    }.getOrDefault(emptyList())

    fun speak(text: String, lang: String, voiceName: String? = null, done: (() -> Unit)? = null) {
        onDone = done
        val wanted = voiceName ?: if (lang == "en") settings.voiceEn else settings.voiceDe
        // Natürliche neuronale Stimme (Piper, offline)
        if (wanted?.startsWith(NEURAL) == true) {
            val id = wanted.removePrefix(NEURAL)
            if (cc.szakacs.aurion.speech.Packs.isInstalled(appCtx, id)) {
                tts.stop()
                cc.szakacs.aurion.speech.NeuralTts.speak(appCtx, id, text, settings.speechRate) { onDone?.invoke() }
                return
            }
        }
        if (!_ready.value) { done?.invoke(); return }
        val voice = wanted?.let { n -> runCatching { tts.voices?.firstOrNull { it.name == n } }.getOrNull() }
        if (voice != null) {
            tts.voice = voice
        } else {
            val locale = if (lang == "en") Locale.US else Locale.GERMANY
            val res = tts.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.getDefault())
        }
        tts.setSpeechRate(settings.speechRate)
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aurion-" + System.nanoTime())
    }

    fun stop() {
        onDone = null
        tts.stop()
        cc.szakacs.aurion.speech.NeuralTts.stop()
    }

    fun shutdown() {
        tts.stop()
        cc.szakacs.aurion.speech.NeuralTts.stop()
        tts.shutdown()
    }

    companion object {
        /** Präfix für neuronale Stimmen in den Einstellungen */
        const val NEURAL = "piper:"
    }
}
