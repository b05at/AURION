package cc.szakacs.aurion.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.ZipInputStream

sealed class Attachment {
    data class Image(val name: String, val bitmap: Bitmap) : Attachment()
    data class Document(val name: String, val text: String, val preview: Bitmap?, val pages: Int) : Attachment()
}

/**
 * Liest Anhänge komplett lokal: Bilder, PDF (per Texterkennung), Word (.docx), OpenDocument (.odt),
 * Text, Markdown, CSV, JSON, Code …
 */
object DocumentReader {

    private const val MAX_PDF_PAGES = 12
    private const val MAX_TEXT = 200_000

    suspend fun read(ctx: Context, uri: Uri, progress: (String) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val name = runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Anhang"
        val mime = cr.getType(uri) ?: ""
        val lower = name.lowercase()

        when {
            mime.startsWith("image/") || Regex("\\.(jpe?g|png|webp|heic|gif|bmp)$").containsMatchIn(lower) -> {
                val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
                    d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > 2400) d.setTargetSampleSize(longest / 1600)
                }
                Attachment.Image(name, bmp)
            }
            mime == "application/pdf" || lower.endsWith(".pdf") -> readPdf(ctx, uri, name, progress)
            lower.endsWith(".docx") || mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                Attachment.Document(name, readZipXml(ctx, uri, "word/document.xml", "</w:p>"), null, 0)
            lower.endsWith(".odt") || mime == "application/vnd.oasis.opendocument.text" ->
                Attachment.Document(name, readZipXml(ctx, uri, "content.xml", "</text:p>"), null, 0)
            lower.endsWith(".pptx") ->
                Attachment.Document(name, readPptx(ctx, uri), null, 0)
            else -> {
                val text = cr.openInputStream(uri)!!.use { it.readBytes().take(MAX_TEXT).toByteArray() }.toString(Charsets.UTF_8)
                if (text.count { it == '�' } > text.length / 20) throw IllegalArgumentException("Dieses Dateiformat kann ich nicht lesen")
                Attachment.Document(name, text, null, 0)
            }
        }
    }

    private suspend fun readPdf(ctx: Context, uri: Uri, name: String, progress: (String) -> Unit): Attachment.Document {
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalArgumentException("PDF nicht lesbar")
        pfd.use {
            PdfRenderer(pfd).use { pdf ->
                val pages = pdf.pageCount
                val sb = StringBuilder()
                var preview: Bitmap? = null
                for (i in 0 until minOf(pages, MAX_PDF_PAGES)) {
                    progress("Lese Seite ${i + 1} von ${minOf(pages, MAX_PDF_PAGES)} …")
                    val page = pdf.openPage(i)
                    val scale = 1600f / maxOf(page.width, page.height)
                    val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    if (i == 0) preview = Bitmap.createScaledBitmap(bmp, bmp.width / 4, bmp.height / 4, true)
                    val text = Vision.analyze(bmp).text
                    if (text.isNotBlank()) sb.append("--- Seite ${i + 1} ---\n").append(text).append("\n\n")
                }
                if (pages > MAX_PDF_PAGES) sb.append("(… weitere ${pages - MAX_PDF_PAGES} Seiten nicht gelesen)")
                return Attachment.Document(name, sb.toString().trim(), preview, pages)
            }
        }
    }

    private fun readZipXml(ctx: Context, uri: Uri, entryName: String, paragraphEnd: String): String {
        ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                if (e.name == entryName) {
                    val xml = zip.readBytes().toString(Charsets.UTF_8)
                    return xmlToText(xml, paragraphEnd)
                }
                e = zip.nextEntry
            }
        }
        throw IllegalArgumentException("Dokument ist leer oder beschädigt")
    }

    private fun readPptx(ctx: Context, uri: Uri): String {
        val slides = sortedMapOf<Int, String>()
        ZipInputStream(ctx.contentResolver.openInputStream(uri)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                Regex("ppt/slides/slide(\\d+)\\.xml").matchEntire(e.name)?.let { m ->
                    slides[m.groupValues[1].toInt()] = xmlToText(zip.readBytes().toString(Charsets.UTF_8), "</a:p>")
                }
                e = zip.nextEntry
            }
        }
        return slides.entries.joinToString("\n\n") { "--- Folie ${it.key} ---\n${it.value}" }
    }

    private fun xmlToText(xml: String, paragraphEnd: String): String = xml
        .replace(paragraphEnd, "\n")
        .replace(Regex("<w:tab/>|<text:tab/>"), "\t")
        .replace(Regex("<w:br/>|<text:line-break/>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
        .take(MAX_TEXT)
}
