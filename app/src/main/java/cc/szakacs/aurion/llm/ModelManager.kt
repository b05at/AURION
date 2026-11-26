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
    val needsToken: Boolean = false,
)

/** Treffer bei der Hugging-Face-Suche */
data class HfRepo(val id: String, val downloads: Long, val likes: Long)

/** Modelldatei in einem Hugging-Face-Repository */
data class HfFile(val repo: String, val path: String, val size: Long) {
    val name: String get() = path.substringAfterLast('/')
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$path"
}

/** Zugriff auf Hugging Face: Modelle suchen und passende Dateien auflisten */
object HfHub {
    /** MediaPipe/LiteRT lädt .task-Bundles */
    fun supported(path: String) = path.endsWith(".task", true)

    private fun get(ctx: Context, url: String): String {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Accept", "application/json")
        Settings.get(ctx).hfToken?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer ${it.trim()}") }
        try {
            val code = c.responseCode
            if (code == 401 || code == 403) throw IllegalStateException("Kein Zugriff – das Repository ist geschützt. Hugging-Face-Token eintragen und der Lizenz zustimmen.")
            if (code == 404) throw IllegalStateException("Nicht gefunden")
            if (code !in 200..299) throw IllegalStateException("Hugging Face antwortet mit Fehler $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** Suche – bevorzugt Repos, die .task-Modelle für Smartphones enthalten (LiteRT) */
    suspend fun search(ctx: Context, query: String): List<HfRepo> = withContext(Dispatchers.IO) {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val arr = org.json.JSONArray(get(ctx, "https://huggingface.co/api/models?search=$q&limit=30&sort=downloads&direction=-1"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            HfRepo(o.optString("id", o.optString("modelId")), o.optLong("downloads"), o.optLong("likes"))
        }.filter { it.id.isNotBlank() }
            .sortedByDescending { (if (it.id.startsWith("litert-community/") || it.id.contains("litert", true) || it.id.contains("mediapipe", true)) 1_000_000_000L else 0L) + it.downloads }
    }

    /** Alle Dateien eines Repos; supported() markiert die verwendbaren */
    suspend fun files(ctx: Context, repo: String): List<HfFile> = withContext(Dispatchers.IO) {
        val arr = org.json.JSONArray(get(ctx, "https://huggingface.co/api/models/$repo/tree/main?recursive=true"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (o.optString("type") != "file") return@mapNotNull null
            val size = o.optJSONObject("lfs")?.optLong("size") ?: o.optLong("size")
            HfFile(repo, o.getString("path"), size)
        }
    }

    sealed class Parsed {
        data class Direct(val file: HfFile) : Parsed()
        data class Repo(val repo: String) : Parsed()
        data class Search(val query: String) : Parsed()
    }

    /** Link, "besitzer/repo" oder Suchbegriff erkennen */
    fun parse(input: String): Parsed {
        val t = input.trim().removeSuffix("/").substringBefore("?")
        val path = t.removePrefix("https://").removePrefix("http://").removePrefix("www.").removePrefix("huggingface.co/").removePrefix("hf.co/")
        val parts = path.split('/')
        if (t.contains("huggingface.co/") || t.contains("hf.co/")) {
            if (parts.size >= 5 && (parts[2] == "resolve" || parts[2] == "blob")) {
                return Parsed.Direct(HfFile("${parts[0]}/${parts[1]}", parts.drop(4).joinToString("/"), 0))
            }
            if (parts.size >= 2) return Parsed.Repo("${parts[0]}/${parts[1]}")
        }
        if (Regex("^[\\w.-]+/[\\w.-]+$").matches(t)) return Parsed.Repo(t)
        return Parsed.Search(t)
    }
}

data class DownloadState(val running: Boolean, val progress: Float, val done: Boolean, val failed: Boolean)

/** Verwaltet Download/Auswahl der lokalen Sprachmodelle. */
object ModelManager {

    val options = listOf(
        ModelOption(
            id = "gemma3n-e2b",
            title = "Gemma 3n · E2B (sieht Bilder)",
            description = "Versteht Bilder & Bildschirm direkt, beste Qualität – braucht HF-Token + Lizenz-Zustimmung",
            url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-preview/resolve/main/gemma-3n-E2B-it-int4.task",
            fileName = "gemma-3n-E2B-it-int4.task",
            sizeLabel = "≈ 3,1 GB",
            needsToken = true,
        ),
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
        s.hfToken?.takeIf { it.isNotBlank() }?.let { req.addRequestHeader("Authorization", "Bearer ${it.trim()}") }
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

    /** Modell aus einem beliebigen Hugging-Face-Repository laden */
    fun startHfDownload(ctx: Context, f: HfFile) {
        val safe = f.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        startDownload(ctx, ModelOption("hf:${f.repo}/${f.path}", f.name, f.repo, f.url, safe, sizeLabelOf(f.size)))
    }

    fun sizeLabelOf(b: Long): String = when {
        b <= 0 -> "Größe unbekannt"
        b >= 1L shl 30 -> String.format(java.util.Locale.GERMAN, "%.1f GB", b / 1073741824.0)
        else -> "${b / 1048576} MB"
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
