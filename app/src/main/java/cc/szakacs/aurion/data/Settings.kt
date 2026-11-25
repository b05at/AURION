package cc.szakacs.aurion.data

import android.content.Context
import android.content.SharedPreferences

enum class LangMode { AUTO, DE, EN }

/** App-Einstellungen (SharedPreferences). */
class Settings private constructor(private val p: SharedPreferences) {

    var langMode: LangMode
        get() = runCatching { LangMode.valueOf(p.getString("lang", "AUTO")!!) }.getOrDefault(LangMode.AUTO)
        set(v) = p.edit().putString("lang", v.name).apply()

    /** Sprache der letzten Eingabe – wird im Auto-Modus für Erkennung/Antwort genutzt */
    var lastLang: String
        get() = p.getString("lastLang", "de")!!
        set(v) = p.edit().putString("lastLang", v).apply()

    var speakReplies: Boolean
        get() = p.getBoolean("speak", true)
        set(v) = p.edit().putBoolean("speak", v).apply()

    var listenOnOpen: Boolean
        get() = p.getBoolean("listenOnOpen", true)
        set(v) = p.edit().putBoolean("listenOnOpen", v).apply()

    /** Name der gewählten TTS-Stimme (null = Systemstandard) */
    var voiceDe: String?
        get() = p.getString("voiceDe", null)
        set(v) = p.edit().putString("voiceDe", v).apply()

    var voiceEn: String?
        get() = p.getString("voiceEn", null)
        set(v) = p.edit().putString("voiceEn", v).apply()

    var speechRate: Float
        get() = p.getFloat("speechRate", 1.05f)
        set(v) = p.edit().putFloat("speechRate", v).apply()

    // ---- Wetter ----
    var weatherUseDevice: Boolean
        get() = p.getBoolean("wxDevice", true)
        set(v) = p.edit().putBoolean("wxDevice", v).apply()

    var weatherPlaceName: String?
        get() = p.getString("wxName", null)
        set(v) = p.edit().putString("wxName", v).apply()

    var weatherLat: Double
        get() = p.getString("wxLat", "0")!!.toDouble()
        set(v) = p.edit().putString("wxLat", v.toString()).apply()

    var weatherLon: Double
        get() = p.getString("wxLon", "0")!!.toDouble()
        set(v) = p.edit().putString("wxLon", v.toString()).apply()

    /** Hugging-Face-Token für lizenzpflichtige Modelle (Gemma) */
    var hfToken: String?
        get() = p.getString("hfToken", null)
        set(v) = p.edit().putString("hfToken", v).apply()

    var modelPath: String?
        get() = p.getString("modelPath", null)
        set(v) = p.edit().putString("modelPath", v).apply()

    var downloadId: Long
        get() = p.getLong("downloadId", -1L)
        set(v) = p.edit().putLong("downloadId", v).apply()

    var downloadTarget: String?
        get() = p.getString("downloadTarget", null)
        set(v) = p.edit().putString("downloadTarget", v).apply()

    // ---- Bring! ----
    var bringEmail: String?
        get() = p.getString("bringEmail", null)
        set(v) = p.edit().putString("bringEmail", v).apply()

    var bringPassword: String?
        get() = p.getString("bringPw", null)
        set(v) = p.edit().putString("bringPw", v).apply()

    var bringUserUuid: String?
        get() = p.getString("bringUuid", null)
        set(v) = p.edit().putString("bringUuid", v).apply()

    var bringToken: String?
        get() = p.getString("bringToken", null)
        set(v) = p.edit().putString("bringToken", v).apply()

    var bringTokenExpiry: Long
        get() = p.getLong("bringExp", 0L)
        set(v) = p.edit().putLong("bringExp", v).apply()

    var bringListUuid: String?
        get() = p.getString("bringList", null)
        set(v) = p.edit().putString("bringList", v).apply()

    var bringListName: String?
        get() = p.getString("bringListName", null)
        set(v) = p.edit().putString("bringListName", v).apply()

    fun clearBring() {
        p.edit().remove("bringEmail").remove("bringPw").remove("bringUuid").remove("bringToken")
            .remove("bringExp").remove("bringList").remove("bringListName").apply()
    }

    companion object {
        @Volatile private var instance: Settings? = null
        fun get(ctx: Context): Settings = instance ?: synchronized(this) {
            instance ?: Settings(ctx.applicationContext.getSharedPreferences("aurion", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
