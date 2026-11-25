package cc.szakacs.aurion.assist

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Merkt sich kurzzeitig, dass Aurion gerade eine Nachricht verschicken soll */
object AutoSend {
    @Volatile private var until = 0L
    fun arm() { until = System.currentTimeMillis() + 20_000 }
    fun armed() = System.currentTimeMillis() < until
    fun disarm() { until = 0L }
}

/**
 * Optionale Bedienungshilfe: drückt in WhatsApp den Senden-Knopf – aber nur direkt nachdem
 * Aurion eine von dir bestätigte Nachricht vorbereitet hat. Sonst passiert nichts.
 */
class AutoSendService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoSend.armed()) return
        val pkg = event?.packageName?.toString() ?: return
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return
        val root = rootInActiveWindow ?: return
        val send = root.findAccessibilityNodeInfosByViewId("$pkg:id/send").firstOrNull { it.isVisibleToUser }
            ?: findByDescription(root, setOf("senden", "send"))
        if (send != null && send.isEnabled && send.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AutoSend.disarm()
        }
    }

    private fun findByDescription(node: AccessibilityNodeInfo, names: Set<String>, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 40) return null
        val d = node.contentDescription?.toString()?.lowercase()
        if (d != null && d in names && node.isClickable) return node
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { c -> findByDescription(c, names, depth + 1)?.let { return it } }
        }
        return null
    }

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, AutoSendService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(me, ignoreCase = true) }
        }
    }
}
