package cc.szakacs.aurion.speech

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import cc.szakacs.aurion.R
import cc.szakacs.aurion.data.Settings
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Sentencepiece-BPE-Tokenizer (liest bpe.model direkt), damit beliebige Wake Words
 * in die Token des Keyword-Spotters übersetzt werden können – komplett offline.
 */
class BpeTokenizer(modelFile: File, tokensFile: File) {
    private val scores = HashMap<String, Float>()
    private val known: Set<String> = tokensFile.readLines().mapNotNull { it.trim().substringBeforeLast(' ').ifBlank { null } }.toSet()

    init {
        val bytes = modelFile.readBytes()
        var i = 0
        fun varint(): Long {
            var r = 0L; var shift = 0
            while (true) {
                val b = bytes[i++].toInt() and 0xff
                r = r or ((b and 0x7f).toLong() shl shift)
                if (b < 0x80) return r
                shift += 7
            }
        }
        while (i < bytes.size) {
            val key = varint().toInt()
            val field = key ushr 3
            when (key and 7) {
                0 -> varint()
                1 -> i += 8
                5 -> i += 4
                2 -> {
                    val len = varint().toInt()
                    val end = i + len
                    if (field == 1) {
                        // SentencePiece { 1: piece, 2: score, 3: type }
                        var piece: String? = null
                        var score = 0f
                        var type = 1
                        while (i < end) {
                            val k = varint().toInt()
                            when (k and 7) {
                                0 -> { val v = varint().toInt(); if (k ushr 3 == 3) type = v }
                                5 -> {
                                    val bits = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8) or
                                        ((bytes[i + 2].toInt() and 0xff) shl 16) or ((bytes[i + 3].toInt() and 0xff) shl 24)
                                    if (k ushr 3 == 2) score = java.lang.Float.intBitsToFloat(bits)
                                    i += 4
                                }
                                2 -> {
                                    val l = varint().toInt()
                                    if (k ushr 3 == 1) piece = String(bytes, i, l, Charsets.UTF_8)
                                    i += l
                                }
                                1 -> i += 8
                                else -> i = end
                            }
                        }
                        // 1 = NORMAL, 4 = USER_DEFINED
                        if (piece != null && (type == 1 || type == 4)) scores[piece] = score
                    }
                    i = end
                }
                else -> break
            }
        }
    }

    /** Text -> Token wie "▁HE Y ▁S I RI"; null, wenn ein Zeichen nicht darstellbar ist */
    fun encode(text: String): List<String>? {
        val out = ArrayList<String>()
        for (word in text.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }) {
            val clean = word.filter { it.isLetterOrDigit() || it == '\'' }
            if (clean.isEmpty()) continue
            // Wie sentencepiece: Wortanfang "▁" plus Einzelzeichen, dann nach Punktzahl zusammenfügen
            val sym = ArrayList<String>()
            sym += "▁"
            clean.forEach { sym += it.toString() }
            while (sym.size > 1) {
                var best = -1
                var bestScore = Float.NEGATIVE_INFINITY
                for (k in 0 until sym.size - 1) {
                    val sc = scores[sym[k] + sym[k + 1]] ?: continue
                    if (sc > bestScore) { bestScore = sc; best = k }
                }
                if (best < 0) break
                sym[best] = sym[best] + sym[best + 1]
                sym.removeAt(best + 1)
            }
            out += sym
        }
        if (out.isEmpty() || out.any { it !in known }) return null
        return out
    }
}

/** Wake-Word-Steuerung: hört nur, solange ein Headset verbunden ist */
object WakeWord {
    const val PACK_ID = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"

    /** Auswahl in den Einstellungen: Schlüssel -> (Anzeige, gesprochene Form für das englische Modell) */
    val choices = linkedMapOf(
        "aurion" to ("Aurion" to listOf("AURION", "ORION")),
        "hey_aurion" to ("Hey Aurion" to listOf("HEY AURION", "HEY ORION")),
        "computer" to ("Computer" to listOf("COMPUTER")),
        "ok_google" to ("OK Google" to listOf("OK GOOGLE", "OKAY GOOGLE", "HEY GOOGLE")),
        "custom" to ("Eigenes Wort" to emptyList()),
    )

    enum class Status { OFF, NO_MODEL, WAITING_HEADSET, LISTENING, PAUSED, ERROR }
    private val _status = MutableStateFlow(Status.OFF)
    val status: StateFlow<Status> = _status
    internal fun setStatus(s: Status) { _status.value = s }

    @Volatile var lastError: String? = null
    @Volatile private var triggeredAt = 0L

    /** Vom Overlay abgefragt: wurde es gerade per Wake Word geöffnet? */
    fun consumeTrigger(): Boolean {
        val recent = SystemClock.uptimeMillis() - triggeredAt < 8000
        triggeredAt = 0
        return recent
    }
    internal fun markTriggered() { triggeredAt = SystemClock.uptimeMillis() }

    fun modelReady(ctx: Context) = Packs.isInstalled(ctx, PACK_ID)

    /** Phrasen des gewählten Wake Words */
    fun phrases(s: Settings): List<String> {
        val key = s.wakeWord
        return if (key == "custom") listOf(s.wakeCustom.trim()).filter { it.isNotEmpty() }
        else choices[key]?.second ?: choices.getValue("aurion").second
    }

    fun displayName(s: Settings): String =
        if (s.wakeWord == "custom") s.wakeCustom.ifBlank { "Eigenes Wort" } else choices[s.wakeWord]?.first ?: "Aurion"

    /** Prüft, ob ein eigenes Wort mit dem Modell darstellbar ist */
    fun canEncode(ctx: Context, phrase: String): Boolean = runCatching {
        val d = Packs.dir(ctx, PACK_ID)
        BpeTokenizer(File(d, "bpe.model"), File(d, "tokens.txt")).encode(phrase) != null
    }.getOrDefault(false)

    /** Start (nur aus dem Vordergrund möglich – Android erlaubt Mikrofon-Dienste nur dann) */
    fun apply(ctx: Context) {
        val s = Settings.get(ctx)
        val i = Intent(ctx, WakeWordService::class.java)
        if (s.wakeEnabled && modelReady(ctx) &&
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { ContextCompat.startForegroundService(ctx, i) }
                .onFailure { lastError = it.message; setStatus(Status.ERROR) }
        } else {
            ctx.stopService(i)
            setStatus(if (s.wakeEnabled && !modelReady(ctx)) Status.NO_MODEL else Status.OFF)
        }
    }

    /** Einstellungen geändert (z. B. anderes Wort) -> Dienst neu starten */
    fun restart(ctx: Context) {
        ctx.stopService(Intent(ctx, WakeWordService::class.java))
        apply(ctx)
    }

    fun isHeadset(t: Int): Boolean = t == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        t == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        t == AudioDeviceInfo.TYPE_USB_HEADSET ||
        (Build.VERSION.SDK_INT >= 31 && (t == AudioDeviceInfo.TYPE_BLE_HEADSET || t == AudioDeviceInfo.TYPE_BLE_SPEAKER))

    fun headsetConnected(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isHeadset(it.type) }
    }
}

/**
 * Vordergrund-Dienst: öffnet das Mikrofon nur, wenn ein Headset verbunden ist und gerade
 * keine Aurion-Oberfläche selbst zuhört. Erkennt das Wake Word lokal (sherpa-onnx KWS).
 */
class WakeWordService : Service() {

    private var spotter: KeywordSpotter? = null
    @Volatile private var running = false
    private var thread: Thread? = null
    private lateinit var am: AudioManager

    private val deviceCb = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        am = getSystemService(AudioManager::class.java)
        startAsForeground()
        try {
            spotter = buildSpotter()
        } catch (e: Exception) {
            WakeWord.lastError = e.message
            WakeWord.setStatus(WakeWord.Status.ERROR)
            stopSelf()
            return
        }
        am.registerAudioDeviceCallback(deviceCb, null)
        running = true
        thread = Thread({ loop() }, "aurion-wakeword").apply { start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        runCatching { am.unregisterAudioDeviceCallback(deviceCb) }
        thread?.join(1500)
        runCatching { spotter?.release() }
        spotter = null
        WakeWord.setStatus(WakeWord.Status.OFF)
        super.onDestroy()
    }

    private fun update() { /* Schleife prüft den Zustand selbst */ }

    private fun buildSpotter(): KeywordSpotter {
        val d = Packs.dir(this, WakeWord.PACK_ID)
        fun f(prefix: String) = d.listFiles()?.firstOrNull { it.name.startsWith(prefix) && it.name.endsWith(".int8.onnx") }?.absolutePath
            ?: throw IllegalStateException("Wake-Word-Modell unvollständig ($prefix)")
        val tok = BpeTokenizer(File(d, "bpe.model"), File(d, "tokens.txt"))
        val s = Settings.get(this)
        val lines = WakeWord.phrases(s).mapNotNull { p ->
            tok.encode(p)?.joinToString(" ")?.let { "$it @${p.replace(' ', '_')}" }
        }
        if (lines.isEmpty()) throw IllegalStateException("Dieses Wake Word kann das Modell nicht darstellen – bitte ein anderes wählen")
        val kw = File(filesDir, "wake_keywords.txt").apply { writeText(lines.joinToString("\n") + "\n") }
        // Empfindlichkeit: höher = reagiert leichter (auch auf Fehlalarme)
        val sens = s.wakeSensitivity.coerceIn(0f, 1f)
        val cfg = KeywordSpotterConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = f("encoder"), decoder = f("decoder"), joiner = f("joiner")),
                tokens = File(d, "tokens.txt").absolutePath,
                numThreads = 1,
                provider = "cpu",
                modelType = "zipformer2",
            ),
            maxActivePaths = 4,
            keywordsFile = kw.absolutePath,
            keywordsScore = 1.0f + sens * 2.0f,
            keywordsThreshold = 0.35f - sens * 0.25f,
            numTrailingBlanks = 1,
        )
        return KeywordSpotter(config = cfg)
    }

    /** Darf gerade zugehört werden? */
    private fun mayListen(): Boolean =
        WakeWord.headsetConnected(this) && !cc.szakacs.aurion.ui.AppVisibility.isVisible

    @SuppressLint("MissingPermission")
    private fun loop() {
        val sp = spotter ?: return
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val chunk = ShortArray(rate / 10)
        var rec: AudioRecord? = null
        var stream = sp.createStream()
        var lastHit = 0L
        try {
            while (running) {
                if (!mayListen()) {
                    if (rec != null) { runCatching { rec?.stop(); rec?.release() }; rec = null }
                    WakeWord.setStatus(
                        if (WakeWord.headsetConnected(this)) WakeWord.Status.PAUSED else WakeWord.Status.WAITING_HEADSET
                    )
                    Thread.sleep(700)
                    continue
                }
                if (rec == null) {
                    rec = runCatching {
                        AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, rate)).also { it.startRecording() }
                    }.getOrNull()
                    if (rec == null || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        runCatching { rec?.release() }; rec = null
                        WakeWord.setStatus(WakeWord.Status.ERROR)
                        WakeWord.lastError = "Mikrofon ist gerade belegt"
                        Thread.sleep(2000)
                        continue
                    }
                    stream.release(); stream = sp.createStream()
                    WakeWord.setStatus(WakeWord.Status.LISTENING)
                }
                val n = rec.read(chunk, 0, chunk.size)
                if (n <= 0) continue
                val f = FloatArray(n) { chunk[it] / 32768f }
                stream.acceptWaveform(f, rate)
                while (sp.isReady(stream)) sp.decode(stream)
                val hit = sp.getResult(stream).keyword
                if (hit.isNotBlank()) {
                    sp.reset(stream)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastHit > 2500) {
                        lastHit = now
                        // Mikrofon für Aurion freigeben, dann Assistent öffnen
                        runCatching { rec.stop(); rec.release() }; rec = null
                        trigger()
                        Thread.sleep(1500)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            WakeWord.lastError = e.message
            WakeWord.setStatus(WakeWord.Status.ERROR)
        } finally {
            runCatching { rec?.stop(); rec?.release() }
            runCatching { stream.release() }
        }
    }

    private fun trigger() {
        WakeWord.markTriggered()
        val ok = cc.szakacs.aurion.assist.AurionInteractionService.launch()
        if (!ok) {
            // Aurion ist nicht als Assistent gesetzt: per Vollbild-Benachrichtigung öffnen
            val pi = PendingIntent.getActivity(
                this, 2,
                Intent(this, cc.szakacs.aurion.ui.AssistActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, CHANNEL_HIT)
                .setSmallIcon(R.drawable.ic_stat_aurion)
                .setContentTitle("Aurion hört zu")
                .setContentText("Tippen, um zu sprechen")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setTimeoutAfter(10_000)
                .build()
            ensureChannels()
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIF_HIT, n) }
        }
    }

    private fun ensureChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Wake Word", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Zeigt an, dass Aurion mit Headset auf das Wake Word wartet"
                setShowBadge(false)
            })
        }
        if (nm.getNotificationChannel(CHANNEL_HIT) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_HIT, "Wake Word erkannt", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun startAsForeground() {
        ensureChannels()
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, cc.szakacs.aurion.ui.MainActivity::class.java).putExtra("tab", "settings").putExtra("keepChat", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_aurion)
            .setContentTitle("Wake Word „${WakeWord.displayName(Settings.get(this))}“")
            .setContentText("Hört nur, wenn ein Headset verbunden ist")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(NOTIF, n)
    }

    companion object {
        private const val CHANNEL = "aurion_wakeword"
        private const val CHANNEL_HIT = "aurion_wakeword_hit"
        private const val NOTIF = 4801
        private const val NOTIF_HIT = 4802
    }
}
