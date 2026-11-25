package cc.szakacs.aurion.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import cc.szakacs.aurion.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val versionCode: Int, val versionName: String, val apkUrl: String)

/** Selbst-Update über die GitHub-Releases von b05at/AURION. */
object Updater {
    private const val API = "https://api.github.com/repos/b05at/AURION/releases/latest"

    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(API).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = o.getString("tag_name")
            val code = tag.substringAfterLast('.').toIntOrNull() ?: return@runCatching null
            val assets = o.getJSONArray("assets")
            var url: String? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) url = a.getString("browser_download_url")
            }
            if (url == null || code <= BuildConfig.VERSION_CODE) null
            else UpdateInfo(code, tag.removePrefix("v"), url)
        }.getOrNull()
    }

    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo, progress: (Float) -> Unit) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "Aurion-${info.versionName}.apk")
            var conn = URL(info.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            // GitHub leitet auf einen anderen Host um
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects++ < 5) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                conn = URL(loc).openConnection() as HttpURLConnection
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var read: Int
                    var sum = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        out.write(buf, 0, read)
                        sum += read
                        if (total > 0) withContext(Dispatchers.Main) { progress(sum.toFloat() / total) }
                    }
                }
            }
            target
        }
        install(ctx, file)
    }

    private fun install(ctx: Context, file: File) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
