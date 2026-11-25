package cc.szakacs.aurion.assist

import android.app.assist.AssistStructure
import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Was der Assistent beim Start über die Seitentaste vom Bildschirm bekommt:
 * Screenshot und Text der Vordergrund-App (wenn in den Android-Assistent-Einstellungen erlaubt).
 */
object ScreenContext {
    private val _screenshot = MutableStateFlow<Bitmap?>(null)
    val screenshot: StateFlow<Bitmap?> = _screenshot

    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text

    @Volatile var appPackage: String? = null
        private set

    fun clear() {
        _screenshot.value = null
        _text.value = null
        appPackage = null
    }

    fun setScreenshot(b: Bitmap?) {
        _screenshot.value = b
    }

    fun setStructure(structure: AssistStructure?) {
        if (structure == null) return
        appPackage = structure.activityComponent?.packageName
        val lines = LinkedHashSet<String>()
        for (i in 0 until structure.windowNodeCount) {
            collect(structure.getWindowNodeAt(i).rootViewNode, lines, 0)
        }
        val joined = lines.joinToString("\n").take(6000)
        _text.value = joined.ifBlank { null }
    }

    private fun collect(node: AssistStructure.ViewNode?, out: MutableSet<String>, depth: Int) {
        if (node == null || depth > 60 || out.size > 400) return
        if (node.visibility == android.view.View.VISIBLE) {
            node.text?.toString()?.trim()?.takeIf { it.length > 1 }?.let { out += it }
            if (node.text.isNullOrBlank()) {
                node.contentDescription?.toString()?.trim()?.takeIf { it.length > 2 }?.let { out += it }
            }
            for (i in 0 until node.childCount) collect(node.getChildAt(i), out, depth + 1)
        }
    }
}
