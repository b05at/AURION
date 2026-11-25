package cc.szakacs.aurion.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Ein erkanntes, direkt nutzbares Element im Text (Telefonnummer, Link, …). */
data class Entity(val kind: Kind, val value: String) {
    enum class Kind { PHONE, EMAIL, URL, ADDRESS, DATE }
}

data class VisionResult(
    val text: String,
    val labels: List<String>,
    val entities: List<Entity>,
)

/**
 * Lokale Bildanalyse mit ML Kit (gebündelte Modelle – funktioniert offline).
 */
object Vision {

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.62f).build())
    }

    suspend fun analyze(bitmap: Bitmap): VisionResult {
        val img = InputImage.fromBitmap(bitmap, 0)
        val text = runCatching { recognizer.process(img).await().text }.getOrDefault("").trim()
        val labels = runCatching {
            labeler.process(img).await().sortedByDescending { it.confidence }.take(6).map { it.text }
        }.getOrDefault(emptyList())
        return VisionResult(text, labels.map { translateLabel(it) }, extractEntities(text))
    }

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val w = (r.width()).coerceIn(1, src.width - left)
        val h = (r.height()).coerceIn(1, src.height - top)
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    fun extractEntities(text: String): List<Entity> {
        val out = LinkedHashSet<Entity>()
        Regex("(https?://|www\\.)[^\\s]+", RegexOption.IGNORE_CASE).findAll(text).forEach {
            out += Entity(Entity.Kind.URL, it.value.trimEnd('.', ',', ')'))
        }
        Regex("[\\w.+-]+@[\\w-]+\\.[\\w.]+").findAll(text).forEach { out += Entity(Entity.Kind.EMAIL, it.value.trimEnd('.')) }
        Regex("(\\+|00)?\\d[\\d /()-]{6,}\\d").findAll(text).forEach { m ->
            val digits = m.value.count { it.isDigit() }
            if (digits in 7..15 && !Regex("^\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}$").matches(m.value.trim())) {
                out += Entity(Entity.Kind.PHONE, m.value.trim())
            }
        }
        // Österreichische/deutsche Adressen: "Hauptstraße 12, 7400 Oberwart"
        Regex("[A-ZÄÖÜ][\\wäöüß.-]+(straße|strasse|gasse|weg|platz|allee|ring|str\\.)\\s*\\d+[a-z]?(,?\\s*\\d{4,5}\\s+[A-ZÄÖÜ][\\wäöüß-]+)?", RegexOption.IGNORE_CASE)
            .findAll(text).forEach { out += Entity(Entity.Kind.ADDRESS, it.value.trim()) }
        Regex("\\b\\d{1,2}\\.\\s?\\d{1,2}\\.(\\d{2,4})?(\\s*(um|,)?\\s*\\d{1,2}[:.]\\d{2})?").findAll(text).forEach {
            out += Entity(Entity.Kind.DATE, it.value.trim())
        }
        return out.take(8)
    }

    /** ML-Kit-Labels sind englisch – die häufigsten übersetzen. */
    private fun translateLabel(l: String): String = mapOf(
        "Food" to "Essen", "Dog" to "Hund", "Cat" to "Katze", "Car" to "Auto", "Plant" to "Pflanze", "Flower" to "Blume",
        "Tree" to "Baum", "Sky" to "Himmel", "Building" to "Gebäude", "Person" to "Person", "Smile" to "Lächeln",
        "Shoe" to "Schuh", "Clothing" to "Kleidung", "Glasses" to "Brille", "Bicycle" to "Fahrrad", "Computer" to "Computer",
        "Mobile phone" to "Handy", "Furniture" to "Möbel", "Bird" to "Vogel", "Fruit" to "Obst", "Vegetable" to "Gemüse",
        "Drink" to "Getränk", "Cup" to "Tasse", "Bottle" to "Flasche", "Toy" to "Spielzeug", "Book" to "Buch",
        "Text" to "Text", "Poster" to "Poster", "Screenshot" to "Bildschirmfoto", "Mountain" to "Berg", "Lake" to "See",
        "Beach" to "Strand", "Snow" to "Schnee", "Bag" to "Tasche", "Watch" to "Uhr", "Jacket" to "Jacke",
    )[l] ?: l

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
