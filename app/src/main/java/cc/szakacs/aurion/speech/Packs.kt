package cc.szakacs.aurion.speech

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

enum class PackKind { VOICE, WHISPER, KWS }

/** Ein herunterladbares Sprachpaket (neuronale Stimme oder Whisper-Erkennung). */
data class Pack(
    val id: String,
    val kind: PackKind,
    val lang: String,
    val title: String,
    val description: String,
    val sizeLabel: String,
    val url: String,
)

sealed class PackState {
    object None : PackState()
    data class Downloading(val progress: Float) : PackState()
    object Extracting : PackState()
    object Ready : PackState()
    data class Failed(val message: String) : PackState()
}

/**
 * Natürliche Offline-Stimmen (Piper/VITS) und Whisper-Modelle über sherpa-onnx.
 * Download per DownloadManager, Entpacken (.tar.bz2) im Hintergrund.
 */
object Packs {
    private const val TTS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/"
    private const val ASR = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val voices = listOf(
        Pack("vits-piper-de_DE-thorsten-high", PackKind.VOICE, "de", "Thorsten", "Männlich · sehr natürlich", "≈ 110 MB", TTS + "vits-piper-de_DE-thorsten-high.tar.bz2"),
        Pack("vits-piper-de_DE-thorsten-medium", PackKind.VOICE, "de", "Thorsten (schnell)", "Männlich · gute Qualität, flott", "≈ 65 MB", TTS + "vits-piper-de_DE-thorsten-medium.tar.bz2"),
        Pack("vits-piper-de_DE-kerstin-low", PackKind.VOICE, "de", "Kerstin", "Weiblich · klar, kompakt", "≈ 30 MB", TTS + "vits-piper-de_DE-kerstin-low.tar.bz2"),
        Pack("vits-piper-de_DE-ramona-low", PackKind.VOICE, "de", "Ramona", "Weiblich · warm, kompakt", "≈ 30 MB", TTS + "vits-piper-de_DE-ramona-low.tar.bz2"),
        Pack("vits-piper-de_DE-eva_k-x_low", PackKind.VOICE, "de", "Eva", "Weiblich · sehr klein", "≈ 25 MB", TTS + "vits-piper-de_DE-eva_k-x_low.tar.bz2"),
        Pack("vits-piper-en_US-lessac-high", PackKind.VOICE, "en", "Lessac", "Female · US · very natural", "≈ 110 MB", TTS + "vits-piper-en_US-lessac-high.tar.bz2"),
        Pack("vits-piper-en_US-ryan-high", PackKind.VOICE, "en", "Ryan", "Male · US · very natural", "≈ 110 MB", TTS + "vits-piper-en_US-ryan-high.tar.bz2"),
        Pack("vits-piper-en_GB-cori-high", PackKind.VOICE, "en", "Cori", "Female · British", "≈ 110 MB", TTS + "vits-piper-en_GB-cori-high.tar.bz2"),
        Pack("vits-piper-en_GB-alan-medium", PackKind.VOICE, "en", "Alan", "Male · British", "≈ 65 MB", TTS + "vits-piper-en_GB-alan-medium.tar.bz2"),
    )

    val whisper = listOf(
        Pack("sherpa-onnx-whisper-base", PackKind.WHISPER, "multi", "Whisper Base (empfohlen)", "Gutes Deutsch & Englisch, flott", "≈ 200 MB Download, 160 MB installiert", ASR + "sherpa-onnx-whisper-base.tar.bz2"),
        Pack("sherpa-onnx-whisper-small", PackKind.WHISPER, "multi", "Whisper Small", "Sehr genau, deutlich langsamer", "≈ 600 MB Download, 375 MB installiert", ASR + "sherpa-onnx-whisper-small.tar.bz2"),
        Pack("sherpa-onnx-whisper-tiny", PackKind.WHISPER, "multi", "Whisper Tiny", "Sehr schnell, weniger genau", "≈ 110 MB Download, 100 MB installiert", ASR + "sherpa-onnx-whisper-tiny.tar.bz2"),
    )

    /** Lokale Wake-Word-Erkennung (Englisch, ~5 MB installiert) */
    val wakeWord = Pack(
        "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01", PackKind.KWS, "en", "Wake-Word-Erkennung",
        "Erkennt dein Wake Word offline", "≈ 20 MB Download, 5 MB installiert",
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    )

    fun byId(id: String): Pack? = (voices + whisper + wakeWord).firstOrNull { it.id == id }

    private val _states = MutableStateFlow<Map<String, PackState>>(emptyMap())
    val states: StateFlow<Map<String, PackState>> = _states

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var poller: Job? = null

    fun base(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "packs").apply { mkdirs() }
    fun dir(ctx: Context, id: String): File = File(base(ctx), id)
    fun isInstalled(ctx: Context, id: String): Boolean = File(dir(ctx, id), ".ok").exists()

    fun state(ctx: Context, id: String): PackState =
        _states.value[id] ?: if (isInstalled(ctx, id)) PackState.Ready else PackState.None

    fun firstInstalledWhisper(ctx: Context): Pack? = whisper.firstOrNull { isInstalled(ctx, it.id) }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("aurion_packs", Context.MODE_PRIVATE)

    private fun set(id: String, s: PackState) {
        _states.value = _states.value + (id to s)
    }

    fun download(ctx: Context, pack: Pack) {
        val app = ctx.applicationContext
        val archive = File(base(app), pack.id + ".tar.bz2")
        archive.delete()
        val req = DownloadManager.Request(Uri.parse(pack.url))
            .setTitle("Aurion – ${pack.title}")
            .setDescription(pack.description)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(archive))
            .setAllowedOverMetered(true)
        val dl = app.getSystemService(DownloadManager::class.java).enqueue(req)
        prefs(app).edit().putLong(pack.id, dl).apply()
        set(pack.id, PackState.Downloading(0f))
        ensurePolling(app)
    }

    /** Beim App-Start: laufende Downloads weiter beobachten */
    fun resume(ctx: Context) {
        if (prefs(ctx).all.isNotEmpty()) ensurePolling(ctx.applicationContext)
    }

    private fun ensurePolling(app: Context) {
        if (poller?.isActive == true) return
        poller = scope.launch {
            val dm = app.getSystemService(DownloadManager::class.java)
            while (true) {
                val pending = prefs(app).all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }
                if (pending.isEmpty()) break
                for ((id, dl) in pending) {
                    dm.query(DownloadManager.Query().setFilterById(dl)).use { c ->
                        if (!c.moveToFirst()) {
                            prefs(app).edit().remove(id).apply()
                            set(id, PackState.Failed("Download abgebrochen"))
                            return@use
                        }
                        val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        when (status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                prefs(app).edit().remove(id).apply()
                                set(id, PackState.Extracting)
                                scope.launch { install(app, id) }
                            }
                            DownloadManager.STATUS_FAILED -> {
                                prefs(app).edit().remove(id).apply()
                                set(id, PackState.Failed("Download fehlgeschlagen"))
                            }
                            else -> set(id, PackState.Downloading(if (total > 0) done.toFloat() / total else 0f))
                        }
                    }
                }
                delay(800)
            }
        }
    }

    private fun install(app: Context, id: String) {
        val archive = File(base(app), "$id.tar.bz2")
        val target = dir(app, id)
        try {
            target.deleteRecursively()
            target.mkdirs()
            val kind = byId(id)?.kind
            val isWhisper = kind == PackKind.WHISPER || kind == PackKind.KWS
            BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16)).use { bz ->
                TarArchiveInputStream(bz).use { tar ->
                    var e = tar.nextEntry
                    while (e != null) {
                        // obersten Ordner entfernen
                        val rel = e.name.substringAfter('/', "")
                        val keep = rel.isNotEmpty() && !rel.contains("..") && !rel.startsWith("test_wavs") && !rel.endsWith("README.md") &&
                            (!isWhisper || rel.endsWith("tokens.txt") || rel.contains(".int8.onnx") || rel.endsWith("bpe.model"))
                        if (keep) {
                            val out = File(target, rel)
                            if (e.isDirectory) out.mkdirs()
                            else {
                                out.parentFile?.mkdirs()
                                out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                            }
                        }
                        e = tar.nextEntry
                    }
                }
            }
            File(target, ".ok").writeText("ok")
            set(id, PackState.Ready)
        } catch (ex: Exception) {
            target.deleteRecursively()
            set(id, PackState.Failed("Entpacken fehlgeschlagen: ${ex.message}"))
        } finally {
            archive.delete()
        }
    }

    fun cancel(ctx: Context, id: String) {
        val dl = prefs(ctx).getLong(id, -1L)
        if (dl >= 0) ctx.getSystemService(DownloadManager::class.java).remove(dl)
        prefs(ctx).edit().remove(id).apply()
        set(id, PackState.None)
    }

    fun delete(ctx: Context, id: String) {
        NeuralTts.releaseIf(id)
        WhisperEngine.releaseIf(id)
        dir(ctx, id).deleteRecursively()
        set(id, PackState.None)
    }

    fun sizeOnDisk(ctx: Context, id: String): Long =
        dir(ctx, id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
