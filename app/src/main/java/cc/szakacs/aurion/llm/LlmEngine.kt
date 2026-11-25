package cc.szakacs.aurion.llm

import android.content.Context
import cc.szakacs.aurion.data.Settings
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

data class Turn(val user: Boolean, val text: String)

/**
 * On-Device-LLM über MediaPipe LLM Inference (.task-Modelle, z. B. Qwen2.5 / Gemma 3).
 * Läuft vollständig lokal – keine Daten verlassen das Gerät.
 */
class LlmEngine private constructor(private val ctx: Context) {

    private var llm: LlmInference? = null
    private var loadedPath: String? = null
    private val mutex = Mutex()

    val modelPath: String? get() = Settings.get(ctx).modelPath?.takeIf { File(it).exists() }
    val isAvailable: Boolean get() = modelPath != null

    /** Gemma 3n kann Bilder direkt verstehen */
    val supportsVision: Boolean
        get() = modelPath?.let { File(it).name.lowercase().let { n -> n.contains("gemma-3n") || n.contains("gemma3n") } } == true

    private fun maxTokensFor(path: String): Int =
        Regex("ekv(\\d+)").find(File(path).name.lowercase())?.groupValues?.get(1)?.toIntOrNull()?.coerceAtMost(4096) ?: 2048

    private fun ensureLoaded(): LlmInference {
        val path = modelPath ?: throw IllegalStateException("Kein Sprachmodell installiert")
        llm?.let { if (loadedPath == path) return it }
        llm?.close()
        val builder = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(maxTokensFor(path))
            .setMaxTopK(64)
        if (supportsVision) builder.setMaxNumImages(1)
        return LlmInference.createFromOptions(ctx, builder.build()).also {
            llm = it
            loadedPath = path
        }
    }

    /** Vorab laden, damit die erste Antwort schneller kommt */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        mutex.withLock { runCatching { ensureLoaded() } }
    }

    /**
     * Erzeugt eine Antwort. [history] enthält den bisherigen Verlauf (ohne aktuelle Nachricht).
     * [image] wird nur bei Vision-Modellen (Gemma 3n) verwendet. [onPartial] liefert den Text live mit.
     */
    suspend fun chat(
        system: String,
        history: List<Turn>,
        user: String,
        temperature: Float = 0.6f,
        image: Bitmap? = null,
        onPartial: ((String) -> Unit)? = null,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            val engine = ensureLoaded()
            val prompt = buildPrompt(system, history, user)
            val useImage = image != null && supportsVision
            val ob = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(temperature)
            if (useImage) ob.setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
            val session = LlmInferenceSession.createFromOptions(engine, ob.build())
            try {
                session.addQueryChunk(prompt)
                if (useImage) session.addImage(BitmapImageBuilder(scaled(image!!)).build())
                if (onPartial == null) {
                    clean(session.generateResponse())
                } else {
                    val sb = StringBuilder()
                    val full = suspendCancellableCoroutine<String> { cont ->
                        session.generateResponseAsync { partial, done ->
                            if (partial != null) sb.append(partial)
                            runCatching { onPartial(clean(sb.toString())) }
                            if (done && cont.isActive) cont.resume(sb.toString())
                        }
                    }
                    clean(full)
                }
            } finally {
                session.close()
            }
        }
    }

    private fun scaled(b: Bitmap): Bitmap {
        val max = 768
        val f = max.toFloat() / maxOf(b.width, b.height)
        return if (f >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * f).toInt(), (b.height * f).toInt(), true)
    }

    /**
     * Die .task-Bundles bringen ihr eigenes Chat-Template (Prefix/Suffix) mit,
     * daher wird hier nur reiner Text übergeben.
     */
    private fun buildPrompt(system: String, history: List<Turn>, user: String): String {
        var budget = 3000
        val trimmed = ArrayList<Turn>()
        for (t in history.asReversed()) {
            budget -= t.text.length
            if (budget < 0) break
            trimmed.add(0, t)
        }
        val sb = StringBuilder()
        sb.append(system.trim()).append("\n\n")
        if (trimmed.isNotEmpty()) {
            sb.append("Bisheriges Gespräch / Conversation so far:\n")
            trimmed.forEach { sb.append(if (it.user) "User: " else "Aurion: ").append(it.text).append('\n') }
            sb.append('\n')
        }
        sb.append("User: ").append(user).append("\nAurion:")
        return sb.toString()
    }

    private fun clean(s: String): String = s
        .replace(Regex("<think>[\\s\\S]*?</think>"), "")
        .replace("<|im_end|>", "").replace("<end_of_turn>", "").replace("<|endoftext|>", "")
        .trim()
        .removePrefix("Aurion:").trim()
        .substringBefore("\nUser:")
        .trim()

    fun release() {
        llm?.close()
        llm = null
        loadedPath = null
    }

    companion object {
        const val MAX_TOKENS = 2048
        @Volatile private var instance: LlmEngine? = null
        fun get(ctx: Context): LlmEngine = instance ?: synchronized(this) {
            instance ?: LlmEngine(ctx.applicationContext).also { instance = it }
        }
    }
}
