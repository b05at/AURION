package cc.szakacs.aurion.llm

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import cc.szakacs.aurion.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ModelOption(
    val id: String,
    val title: String,
    val description: String,
    val url: String,
    val fileName: String,
    val sizeLabel: String,
)

data class DownloadState(val running: Boolean, val progress: Float, val done: Boolean, val failed: Boolean)

/** Verwaltet Download/Auswahl der lokalen Sprachmodelle. */
object ModelManager {

    val options = listOf(
        ModelOption(
            id = "qwen25-15b",
            title = "Qwen 2.5 · 1.5B (empfohlen)",
            description = "Gutes Deutsch & Englisch, schnell genug für Assistent-Aufgaben",
            url = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            fileName = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            sizeLabel = "≈ 1,6 GB",
        ),
        ModelOption(
            id = "qwen25-05b",
            title = "Qwen 2.5 · 0.5B (kompakt)",
            description = "Sehr schnell und klein, einfachere Antworten",
            url = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            sizeLabel = "≈ 0,5 GB",
        ),
    )

    fun modelDir(ctx: Context): File =
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "models").apply { mkdirs() }

    fun installed(ctx: Context): List<File> =
        modelDir(ctx).listFiles { f -> f.isFile && (f.name.endsWith(".task") || f.name.endsWith(".bin")) }
            ?.sortedBy { it.name }.orEmpty()

    fun startDownload(ctx: Context, opt: ModelOption) {
        val dm = ctx.getSystemService(DownloadManager::class.java)
        val target = File(modelDir(ctx), opt.fileName)
        if (target.exists()) target.delete()
        val req = DownloadManager.Request(Uri.parse(opt.url))
            .setTitle("Aurion – Sprachmodell")
            .setDescription(opt.title)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(target))
            .setAllowedOverMetered(true)
        val s = Settings.get(ctx)
        s.downloadId = dm.enqueue(req)
        s.downloadTarget = target.absolutePath
    }

    fun cancelDownload(ctx: Context) {
        val s = Settings.get(ctx)
        if (s.downloadId >= 0) ctx.getSystemService(DownloadManager::class.java).remove(s.downloadId)
        s.downloadId = -1
        s.downloadTarget = null
    }

    /** Fortschritt abfragen; aktiviert das Modell automatisch nach erfolgreichem Download. */
    fun poll(ctx: Context): DownloadState? {
        val s = Settings.get(ctx)
        val id = s.downloadId
        if (id < 0) return null
        val dm = ctx.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) {
                s.downloadId = -1
                return null
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val p = if (total > 0) done.toFloat() / total else 0f
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    s.modelPath = s.downloadTarget
                    s.downloadId = -1
                    LlmEngine.get(ctx).release()
                    DownloadState(false, 1f, true, false)
                }
                DownloadManager.STATUS_FAILED -> {
                    s.downloadId = -1
                    DownloadState(false, p, false, true)
                }
                else -> DownloadState(true, p, false, false)
            }
        }
    }

    /** Eigene .task-Datei (per Dateiauswahl) übernehmen */
    suspend fun importFrom(ctx: Context, uri: Uri, name: String): File = withContext(Dispatchers.IO) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".task")) it else "$it.task" }
        val target = File(modelDir(ctx), safe)
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            target.outputStream().use { input.copyTo(it, 1 shl 20) }
        }
        Settings.get(ctx).modelPath = target.absolutePath
        LlmEngine.get(ctx).release()
        target
    }
}
