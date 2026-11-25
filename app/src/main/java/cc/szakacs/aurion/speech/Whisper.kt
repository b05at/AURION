package cc.szakacs.aurion.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Wachsender Float-Puffer */
class FloatList(initial: Int = 16000 * 30) {
    var data = FloatArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Whisper-Spracherkennung, komplett offline (sherpa-onnx). */
object WhisperEngine {
    const val SR = 16000
    private var rec: OfflineRecognizer? = null
    private var key: String? = null
    private val lock = Mutex()

    @Synchronized
    private fun recognizer(ctx: Context, packId: String, lang: String): OfflineRecognizer {
        val k = "$packId/$lang"
        rec?.let { if (key == k) return it }
        rec?.release()
        rec = null
        val dir = Packs.dir(ctx, packId)
        val files = dir.listFiles().orEmpty()
        val enc = files.firstOrNull { it.name.endsWith("encoder.int8.onnx") } ?: throw IllegalStateException("Whisper-Modell unvollständig")
        val dec = files.firstOrNull { it.name.endsWith("decoder.int8.onnx") } ?: throw IllegalStateException("Whisper-Modell unvollständig")
        val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") } ?: throw IllegalStateException("Whisper-Modell unvollständig")
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = enc.absolutePath,
                    decoder = dec.absolutePath,
                    language = lang,
                    task = "transcribe",
                ),
                tokens = tokens.absolutePath,
                numThreads = 4,
                debug = false,
                provider = "cpu",
                modelType = "whisper",
            ),
        )
        return OfflineRecognizer(null, config).also { rec = it; key = k }
    }

    fun releaseIf(packId: String) {
        if (key?.startsWith("$packId/") == true) synchronized(this) {
            rec?.release(); rec = null; key = null
        }
    }

    /**
     * Transkribiert 16-kHz-Mono-Audio. Lange Aufnahmen werden an leisen Stellen in ≤ 28 s-Stücke geteilt.
     * @param lang "de", "en" oder "" (automatisch)
     */
    suspend fun transcribe(
        ctx: Context,
        packId: String,
        samples: FloatArray,
        lang: String,
        progress: (Float, String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        lock.withLock {
            val r = recognizer(ctx.applicationContext, packId, lang)
            val chunks = split(samples)
            val sb = StringBuilder()
            chunks.forEachIndexed { i, (from, to) ->
                progress(i.toFloat() / chunks.size, sb.toString())
                val chunk = samples.copyOfRange(from, to)
                if (rms(chunk) < 0.004f) return@forEachIndexed   // Stille überspringen (Whisper würde sonst "halluzinieren")
                val s = r.createStream()
                try {
                    s.acceptWaveform(chunk, SR)
                    r.decode(s)
                    val text = r.getResult(s).text.trim()
                    if (text.isNotEmpty() && !isHallucination(text)) {
                        if (sb.isNotEmpty()) sb.append(' ')
                        sb.append(text)
                    }
                } finally {
                    s.release()
                }
            }
            progress(1f, sb.toString())
            sb.toString().trim()
        }
    }

    private fun isHallucination(t: String) = Regex(
        "^(\\[.*]|\\(.*\\)|untertitel.*|subtitles by.*|vielen dank\\.?|thank you\\.?|thanks for watching.*|copyright.*)$",
        RegexOption.IGNORE_CASE
    ).matches(t.trim())

    private fun rms(a: FloatArray): Float {
        if (a.isEmpty()) return 0f
        var s = 0.0
        for (v in a) s += v * v
        return sqrt(s / a.size).toFloat()
    }

    /** Schnittpunkte an der leisesten Stelle zwischen 20 und 28 Sekunden */
    private fun split(a: FloatArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val max = SR * 28
        val min = SR * 20
        val win = SR / 4
        var start = 0
        while (start < a.size) {
            if (a.size - start <= max) { out += start to a.size; break }
            var best = start + max
            var bestE = Double.MAX_VALUE
            var p = start + min
            while (p + win < start + max) {
                var e = 0.0
                for (i in p until p + win) e += a[i] * a[i]
                if (e < bestE) { bestE = e; best = p + win / 2 }
                p += win / 2
            }
            out += start to best
            start = best
        }
        return out
    }
}

/** Dekodiert beliebige Audiodateien (m4a, mp3, ogg/opus, wav, aac …) zu 16 kHz Mono. */
object AudioDecoder {
    suspend fun decode(ctx: Context, uri: Uri, maxSeconds: Int = 3600, progress: (Float) -> Unit = {}): FloatArray =
        withContext(Dispatchers.IO) {
            val ex = MediaExtractor()
            ex.setDataSource(ctx, uri, null)
            val trackIdx = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("Keine Tonspur gefunden")
            ex.selectTrack(trackIdx)
            val fmt = ex.getTrackFormat(trackIdx)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var isFloat = false

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val mono = FloatList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            try {
                while (!outputDone) {
                    if (!inputDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                if (durationUs > 0) progress((ex.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        val ob = codec.getOutputBuffer(o)!!
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        ob.order(ByteOrder.LITTLE_ENDIAN)
                        if (isFloat) {
                            val fb = ob.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += fb.get() }
                                mono.add(s / channels)
                            }
                        } else {
                            val sb = ob.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var s = 0f
                                repeat(channels) { s += sb.get() / 32768f }
                                mono.add(s / channels)
                            }
                        }
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        if (mono.size > sr.toLong() * maxSeconds) outputDone = true
                    } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = codec.outputFormat
                        sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        isFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                }
            } finally {
                runCatching { codec.stop(); codec.release() }
                ex.release()
            }
            resample(mono.toArray(), sr, WhisperEngine.SR)
        }

    /** Lineares Resampling */
    fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || input.isEmpty()) return input
        val ratio = from.toDouble() / to
        val outLen = (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * ratio
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = input[idx]
            val b = if (idx + 1 < input.size) input[idx + 1] else a
            out[i] = a + (b - a) * frac
        }
        return out
    }
}

/** Nimmt 16-kHz-Mono vom Mikrofon auf (für die genaue Whisper-Transkription). */
class PcmRecorder {
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private val buffer = FloatList(16000 * 60)

    val seconds: Float get() = buffer.size / 16000f

    @SuppressLint("MissingPermission")
    fun start(onLevel: (Float) -> Unit) {
        val sr = WhisperEngine.SR
        val min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, sr))
        record = r
        running = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(1600)
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var sum = 0.0
                synchronized(buffer) {
                    for (i in 0 until n) {
                        val v = buf[i] / 32768f
                        buffer.add(v)
                        sum += v * v
                    }
                }
                onLevel((sqrt(sum / n).toFloat() * 8f).coerceIn(0f, 1f))
            }
        }.apply { start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(500)
        runCatching { record?.stop(); record?.release() }
        record = null
        return synchronized(buffer) { buffer.toArray() }
    }
}
