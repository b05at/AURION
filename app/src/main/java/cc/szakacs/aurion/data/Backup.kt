package cc.szakacs.aurion.data

import android.content.Context
import android.net.Uri
import cc.szakacs.aurion.reminders.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sicherung aller Aurion-Daten in eine JSON-Datei (Erinnerungen, Gedächtnis, Transkripte, Einstellungen).
 * Passwörter und Tokens werden aus Sicherheitsgründen nicht mitgesichert.
 */
object Backup {
    private val secretKeys = setOf("bringPw", "bringToken", "bringExp", "hfToken", "downloadId", "downloadTarget")

    suspend fun export(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val store = Store.get(ctx)
        val o = JSONObject()
            .put("app", "Aurion").put("version", 1).put("created", System.currentTimeMillis())
            .put("reminders", JSONArray().apply { store.reminders.value.forEach { put(it.toJson()) } })
            .put("memory", JSONArray().apply { store.memory.value.forEach { put(it.toJson()) } })
            .put("transcripts", JSONArray().apply { store.transcripts.value.forEach { put(it.toJson()) } })
        val prefs = JSONObject()
        ctx.getSharedPreferences("aurion", Context.MODE_PRIVATE).all.forEach { (k, v) ->
            if (k in secretKeys || v == null) return@forEach
            prefs.put(k, JSONObject().put("t", v::class.simpleName).put("v", v.toString()))
        }
        o.put("settings", prefs)
        ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(o.toString(2).toByteArray()) }
    }

    /** Stellt die Sicherung wieder her (ergänzt, ohne Vorhandenes zu löschen) und stellt Wecker neu. */
    suspend fun import(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val o = JSONObject(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) })
        require(o.optString("app") == "Aurion") { "Keine Aurion-Sicherung" }
        val store = Store.get(ctx)
        var r = 0; var m = 0; var t = 0
        o.optJSONArray("reminders")?.let { a ->
            for (i in 0 until a.length()) runCatching {
                val rem = Reminder.fromJson(a.getJSONObject(i))
                if (store.reminder(rem.id) == null) { store.upsertReminder(rem); r++ }
            }
        }
        o.optJSONArray("memory")?.let { a ->
            val existing = store.memory.value.map { it.text.lowercase() }.toSet()
            for (i in 0 until a.length()) runCatching {
                val f = MemoryFact.fromJson(a.getJSONObject(i))
                if (f.text.lowercase() !in existing) { store.addMemory(f.text); m++ }
            }
        }
        o.optJSONArray("transcripts")?.let { a ->
            for (i in 0 until a.length()) runCatching { store.saveTranscript(Transcript.fromJson(a.getJSONObject(i))); t++ }
        }
        o.optJSONObject("settings")?.let { s ->
            val ed = ctx.getSharedPreferences("aurion", Context.MODE_PRIVATE).edit()
            s.keys().forEach { k ->
                val e = s.getJSONObject(k)
                val v = e.getString("v")
                when (e.getString("t")) {
                    "Boolean" -> ed.putBoolean(k, v.toBoolean())
                    "Int" -> ed.putInt(k, v.toInt())
                    "Long" -> ed.putLong(k, v.toLong())
                    "Float" -> ed.putFloat(k, v.toFloat())
                    else -> ed.putString(k, v)
                }
            }
            ed.apply()
        }
        withContext(Dispatchers.Main) { Reminders.restoreAll(ctx) }
        "Wiederhergestellt: $r Erinnerungen, $m Gedächtnis-Einträge, $t Transkripte ✓"
    }
}
