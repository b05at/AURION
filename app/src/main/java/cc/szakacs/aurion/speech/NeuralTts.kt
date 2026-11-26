package cc.szakacs.aurion.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.GenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Natürliche, neuronale Offline-Sprachausgabe (Piper/VITS über sherpa-onnx).
 * Erzeugt Satz für Satz und spielt sofort ab, während der Rest noch berechnet wird.
 */
object NeuralTts {
    private var engine: OfflineTts? = null
    private var loadedId: String? = null
    private val genLock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var track: AudioTrack? = null

    @Synchronized
    private fun engineFor(ctx: Context, id: String): OfflineTts {
        engine?.let { if (loadedId == id) return it }
        engine?.release()
        engine = null
        val dir = Packs.dir(ctx, id)
        if (Packs.byId(id)?.engine == "supertonic") {
            fun f(n: String) = dir.walkTopDown().firstOrNull { it.isFile && it.name == n }?.absolutePath
                ?: throw IllegalStateException("Stimme nicht vollständig installiert ($n)")
            fun onnx(prefix: String) = dir.walkTopDown().firstOrNull { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".onnx") }?.absolutePath
                ?: throw IllegalStateException("Stimme nicht vollständig installiert ($prefix)")
            val cfg = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    supertonic = OfflineTtsSupertonicModelConfig(
                        durationPredictor = onnx("duration_predictor"),
                        textEncoder = onnx("text_encoder"),
                        vectorEstimator = onnx("vector_estimator"),
                        vocoder = onnx("vocoder"),
                        ttsJson = f("tts.json"),
                        unicodeIndexer = f("unicode_indexer.bin"),
                        voiceStyle = f("voice.bin"),
                    ),
                    numThreads = 2,
                    debug = false,
                    provider = "cpu",
                ),
                maxNumSentences = 1,
            )
            return OfflineTts(null, cfg).also { engine = it; loadedId = id }
        }
        val model = dir.listFiles { f -> f.name.endsWith(".onnx") }?.maxByOrNull { it.length() }
            ?: throw IllegalStateException("Stimme nicht vollständig installiert")
        val tokens = File(dir, "tokens.txt")
        val espeak = File(dir, "espeak-ng-data")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    tokens = tokens.absolutePath,
                    dataDir = if (espeak.isDirectory) espeak.absolutePath else "",
                ),
                numThreads = 2,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return OfflineTts(null, config).also {
            engine = it
            loadedId = id
        }
    }

    /** "paket#sprecher" -> (paket, sprecher) */
    fun split(voice: String): Pair<String, Int> =
        voice.substringBefore('#') to (voice.substringAfter('#', "0").toIntOrNull() ?: 0)

    fun releaseIf(id: String) {
        if (loadedId == id) synchronized(this) {
            stop()
            engine?.release()
            engine = null
            loadedId = null
        }
    }

    /** Text in Sätze teilen, damit die Wiedergabe sofort beginnen kann */
    private fun sentences(text: String): List<String> {
        val parts = text.replace(Regex("\\s+"), " ").split(Regex("(?<=[.!?…:;])\\s+"))
        val out = ArrayList<String>()
        for (p in parts) {
            if (p.length <= 220) { if (p.isNotBlank()) out += p; continue }
            // sehr lange Sätze an Kommas teilen
            var cur = StringBuilder()
            for (c in p.split(Regex("(?<=,)\\s+"))) {
                if (cur.length + c.length > 200 && cur.isNotEmpty()) { out += cur.toString(); cur = StringBuilder() }
                cur.append(c).append(' ')
            }
            if (cur.isNotBlank()) out += cur.toString().trim()
        }
        return out
    }

    /** Laufender Sprach-Stream: Sätze nach und nach hinzufügen, am Ende finish() */
    class Stream internal constructor(internal val ch: Channel<String>) {
        fun add(text: String) { if (text.isNotBlank()) ch.trySend(text) }
        fun finish() { ch.close() }
    }

    fun speak(ctx: Context, id: String, text: String, speed: Float, lang: String = "de", onDone: () -> Unit) {
        val s = stream(ctx, id, speed, lang, onDone)
        s.add(text)
        s.finish()
    }

    /** Startet die Wiedergabe sofort; Text kann laufend nachgereicht werden (für Live-Antworten). */
    fun stream(ctx: Context, voice: String, speed: Float, lang: String = "de", onDone: () -> Unit): Stream {
        stop()
        val (id, sid) = split(voice)
        val supertonic = Packs.byId(id)?.engine == "supertonic"
        val app = ctx.applicationContext
        val input = Channel<String>(Channel.UNLIMITED)
        job = scope.launch {
            var t: AudioTrack? = null
            try {
                val eng = genLock.withLock { engineFor(app, id) }
                val sr = eng.sampleRate()
                val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(maxOf(minBuf, sr * 4 / 2))
                    .build()
                track = t
                t.play()

                val audio = Channel<FloatArray>(2)
                val producer = launch {
                    for (chunk in input) {
                        for (sentence in sentences(chunk)) {
                            if (!isActive) break
                            val sp = speed.coerceIn(0.6f, 1.8f)
                            val a = genLock.withLock {
                                if (supertonic) eng.generateWithConfig(
                                    sentence,
                                    GenerationConfig(sid = sid, speed = sp, numSteps = 8, extra = mapOf("lang" to if (lang == "en") "en" else "de"))
                                ) else eng.generate(sentence, sid, sp)
                            }
                            audio.send(a.samples + FloatArray(sr / 12))
                        }
                    }
                    audio.close()
                }
                var written = 0L
                for (samples in audio) {
                    if (!isActive || track !== t) break
                    t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    written += samples.size
                }
                producer.join()
                while (isActive && track === t && t.playbackHeadPosition < written - sr / 20) delay(40)
            } catch (_: Exception) {
            } finally {
                runCatching { t?.stop(); t?.release() }
                if (track === t) track = null
                if (isActive) withContext(Dispatchers.Main) { onDone() }
            }
        }
        return Stream(input)
    }

    fun stop() {
        job?.cancel()
        job = null
        track?.let { tr ->
            track = null
            runCatching { tr.pause(); tr.flush() }
        }
    }

    /** Kurze Hörprobe */
    fun preview(ctx: Context, id: String, lang: String, speed: Float) {
        val text = if (lang == "en") "Hi, I'm Aurion. This is how I sound with this voice." else "Hallo, ich bin Aurion. So klinge ich mit dieser Stimme."
        speak(ctx, id, text, speed, lang) {}
    }
}
