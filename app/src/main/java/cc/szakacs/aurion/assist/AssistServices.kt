package cc.szakacs.aurion.assist

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.graphics.Bitmap
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteException
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import cc.szakacs.aurion.ui.AssistActivity
import cc.szakacs.aurion.voice.Recognizers

/** Macht Aurion als "Digitaler Assistent" auswählbar (Seitentaste / Assistent-Geste). */
class AurionInteractionService : VoiceInteractionService()

class AurionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AurionSession(this)
}

class AurionSession(ctx: Context) : VoiceInteractionSession(ctx) {

    override fun onCreate() {
        super.onCreate()
        current = this
        runCatching { AssistantRole.sync(context) }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        ScreenContext.clear()
        super.onPrepareShow(args, showFlags)
        // Kein eigenes Session-Fenster – die Oberfläche ist die AssistActivity
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(AssistActivity.EXTRA_FROM_ASSIST, true)
        try {
            startAssistantActivity(intent)
        } catch (e: Exception) {
            runCatching { context.startActivity(intent) }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        // Text der Vordergrund-App ("Text auf dem Bildschirm verwenden")
        runCatching { ScreenContext.setStructure(structure) }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Screenshot ("Screenshot verwenden") – Basis für die Bildschirm-Lupe
        ScreenContext.setScreenshot(screenshot?.copy(Bitmap.Config.ARGB_8888, false))
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile var current: AurionSession? = null
        fun finishCurrent() {
            current?.let { s -> Handler(Looper.getMainLooper()).post { runCatching { s.hide() } } }
        }
    }
}

/**
 * Pflichtbestandteil eines Assistenten. Leitet Anfragen an den System-Spracherkenner
 * (z. B. Google) weiter, damit die Spracheingabe anderer Apps weiterhin funktioniert.
 */
class AurionRecognitionService : RecognitionService() {

    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        val comp = Recognizers.systemRecognizer(this)
        if (comp == null) {
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(this, comp)
        delegate = rec
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
            override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
            override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) = safe { listener.bufferReceived(buffer ?: ByteArray(0)) }
            override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
            override fun onError(error: Int) = safe { listener.error(error) }
            override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
            override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec.startListening(recognizerIntent)
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        delegate?.cancel()
    }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: RemoteException) {}
    }
}
