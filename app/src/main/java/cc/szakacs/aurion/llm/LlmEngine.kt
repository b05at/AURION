package cc.szakacs.aurion.llm

import android.content.Context
import cc.szakacs.aurion.data.Settings
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

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

    private fun ensureLoaded(): LlmInference {
        val path = modelPath ?: throw IllegalStateException("Kein Sprachmodell installiert")
        llm?.let { if (loadedPath == path) return it }
        llm?.close()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(MAX_TOKENS)
            .setMaxTopK(64)
            .build()
        return LlmInference.createFromOptions(ctx, options).also {
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
     */
    suspend fun chat(system: String, history: List<Turn>, user: String, temperature: Float = 0.6f): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val engine = ensureLoaded()
                val prompt = buildPrompt(system, history, user)
                val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                    .setTopK(40)
                    .setTemperature(temperature)
                    .build()
                val session = LlmInferenceSession.createFromOptions(engine, sessionOptions)
                try {
                    session.addQueryChunk(prompt)
                    clean(session.generateResponse())
                } finally {
                    session.close()
                }
            }
        }

    /**
     * Die .task-Bundles bringen ihr eigenes Chat-Template (Prefix/Suffix) mit,
     * daher wird hier nur reiner Text übergeben.
     */
    private fun buildPrompt(system: String, history: List<Turn>, user: String): String {
        var budget = 2400
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
