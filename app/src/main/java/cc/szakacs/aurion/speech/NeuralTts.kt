package cc.szakacs.aurion.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
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

    fun speak(ctx: Context, id: String, text: String, speed: Float, onDone: () -> Unit) {
        stop()
        val app = ctx.applicationContext
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

                val ch = Channel<FloatArray>(2)
                val producer = launch {
                    for (s in sentences(text)) {
                        if (!isActive) break
                        val audio = genLock.withLock { eng.generate(s, 0, speed.coerceIn(0.6f, 1.8f)) }
                        // kleine Pause zwischen Sätzen
                        ch.send(audio.samples + FloatArray(sr / 12))
                    }
                    ch.close()
                }
                var written = 0L
                for (samples in ch) {
                    if (!isActive || track !== t) break
                    t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                    written += samples.size
                }
                producer.join()
                // warten, bis alles abgespielt ist
                while (isActive && track === t && t.playbackHeadPosition < written - sr / 20) delay(40)
            } catch (_: Exception) {
            } finally {
                runCatching { t?.stop(); t?.release() }
                if (track === t) track = null
                if (isActive) withContext(Dispatchers.Main) { onDone() }
            }
        }
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
        speak(ctx, id, text, speed) {}
    }
}
