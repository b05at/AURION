package cc.szakacs.aurion.brain

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/** Exakter Rechner für gesprochene/getippte Rechenaufgaben (LLMs rechnen unzuverlässig). */
object Calc {

    fun tryAnswer(input: String, lang: String): String? {
        var s = " " + input.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!', '=') + " "
        if (!s.any { it.isDigit() }) return null
        s = s.replace(Regex("^\\s*(hey\\s+)?(aurion[,]?\\s+)?(was ist|was sind|was ergibt|was gibt|wie viel ist|wieviel ist|wie viel sind|wieviel sind|wie viel|wieviel|rechne( mal)?|berechne|what is|what's|whats|how much is|calculate|compute)\\s+"), " ")

        // "17 % von 2340" / "17 percent of 2340"
        Regex("^\\s*(-?[\\d.,]+)\\s*(%|prozent|percent)\\s*(von|of)\\s*(-?[\\d.,]+)\\s*$").find(s)?.let { m ->
            val p = num(m.groupValues[1]) ?: return null
            val v = num(m.groupValues[4]) ?: return null
            return answer(p / 100 * v, input, lang)
        }

        var e = s
        e = Regex("(\\d)\\.(\\d{3})(?!\\d)").replace(e) { "${it.groupValues[1]}${it.groupValues[2]}" } // 2.340 -> 2340
        e = e.replace(Regex("(\\d),(\\d)"), "$1.$2")
        val words = listOf(
            "multipliziert mit" to "*", "mal" to "*", "times" to "*", "multiplied by" to "*", "×" to "*", "x" to "*",
            "geteilt durch" to "/", "dividiert durch" to "/", "durch" to "/", "divided by" to "/", "over" to "/", "÷" to "/", ":" to "/",
            "plus" to "+", "und" to "+", "minus" to "-", "weniger" to "-",
            "hoch" to "^", "to the power of" to "^", "squared" to "^2", "zum quadrat" to "^2", "quadrat" to "^2", "cubed" to "^3",
            "quadratwurzel aus" to "sqrt", "quadratwurzel von" to "sqrt", "wurzel aus" to "sqrt", "wurzel von" to "sqrt",
            "square root of" to "sqrt", "root of" to "sqrt", "prozent" to "%", "percent" to "%",
            "klammer auf" to "(", "klammer zu" to ")",
        )
        for ((w, op) in words) e = e.replace(Regex("(?<![a-zäöü])${Regex.escape(w)}(?![a-zäöü])"), " $op ")
        e = e.replace(Regex("\\s+"), "")
        if (e.isEmpty() || !Regex("^[0-9.+\\-*/^()%sqrt]+$").matches(e)) return null
        if (!Regex("[+\\-*/^%]|sqrt").containsMatchIn(e)) return null
        val v = runCatching { Parser(e).parse() }.getOrNull() ?: return null
        if (v.isNaN() || v.isInfinite()) return if (lang == "en") "That's not defined." else "Das ist nicht definiert."
        return answer(v, input, lang)
    }

    private fun answer(v: Double, input: String, lang: String): String {
        val f = fmt(v, lang)
        return if (lang == "en") "That's $f." else "Das ergibt $f."
    }

    fun fmt(v: Double, lang: String): String {
        val bd = BigDecimal(v).setScale(if (abs(v) >= 1000) 2 else 6, RoundingMode.HALF_UP).stripTrailingZeros()
        var s = bd.toPlainString()
        if (lang != "en") s = s.replace('.', ',')
        return s
    }

    private fun num(s: String): Double? {
        var t = s.trim()
        t = Regex("(\\d)\\.(\\d{3})(?!\\d)").replace(t) { "${it.groupValues[1]}${it.groupValues[2]}" }
        return t.replace(',', '.').toDoubleOrNull()
    }

    /** Rekursiver Parser: + - * / ^ % ( ) sqrt */
    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double {
            val v = expr()
            if (i != s.length) throw IllegalArgumentException("Rest: ${s.substring(i)}")
            return v
        }
        fun expr(): Double {
            var v = term()
            while (i < s.length) {
                v = when (s[i]) {
                    '+' -> { i++; v + term() }
                    '-' -> { i++; v - term() }
                    else -> return v
                }
            }
            return v
        }
        fun term(): Double {
            var v = power()
            while (i < s.length) {
                v = when (s[i]) {
                    '*' -> { i++; v * power() }
                    '/' -> { i++; v / power() }
                    else -> return v
                }
            }
            return v
        }
        fun power(): Double {
            val b = unary()
            if (i < s.length && s[i] == '^') { i++; return b.pow(power()) }
            return b
        }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            var v = atom()
            while (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }
        fun atom(): Double {
            if (s.startsWith("sqrt", i)) { i += 4; return sqrt(unary()) }
            if (i < s.length && s[i] == '(') {
                i++
                val v = expr()
                if (i < s.length && s[i] == ')') i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("Zahl erwartet")
            return s.substring(start, i).toDouble()
        }
    }
}

/** Einheiten umrechnen: "5 Meilen in km", "180 cm in Fuß", "20 Grad Celsius in Fahrenheit". */
object Units {
    private data class U(val dim: String, val factor: Double)

    private val units = linkedMapOf(
        // Länge (Basis: Meter)
        "kilometer" to U("len", 1000.0), "km" to U("len", 1000.0), "meter" to U("len", 1.0), "m" to U("len", 1.0),
        "zentimeter" to U("len", 0.01), "centimeter" to U("len", 0.01), "cm" to U("len", 0.01), "millimeter" to U("len", 0.001), "mm" to U("len", 0.001),
        "meilen" to U("len", 1609.344), "meile" to U("len", 1609.344), "miles" to U("len", 1609.344), "mile" to U("len", 1609.344), "mi" to U("len", 1609.344),
        "fuß" to U("len", 0.3048), "fuss" to U("len", 0.3048), "feet" to U("len", 0.3048), "foot" to U("len", 0.3048), "ft" to U("len", 0.3048),
        "zoll" to U("len", 0.0254), "inches" to U("len", 0.0254), "inch" to U("len", 0.0254), "in" to U("len", 0.0254),
        "yards" to U("len", 0.9144), "yard" to U("len", 0.9144),
        // Masse (Basis: kg)
        "kilogramm" to U("mass", 1.0), "kilo" to U("mass", 1.0), "kg" to U("mass", 1.0), "gramm" to U("mass", 0.001), "g" to U("mass", 0.001),
        "pfund" to U("mass", 0.45359237), "pounds" to U("mass", 0.45359237), "pound" to U("mass", 0.45359237), "lbs" to U("mass", 0.45359237), "lb" to U("mass", 0.45359237),
        "unzen" to U("mass", 0.028349523), "ounces" to U("mass", 0.028349523), "ounce" to U("mass", 0.028349523), "oz" to U("mass", 0.028349523),
        "tonnen" to U("mass", 1000.0), "tonne" to U("mass", 1000.0), "t" to U("mass", 1000.0),
        // Volumen (Basis: Liter)
        "liter" to U("vol", 1.0), "l" to U("vol", 1.0), "milliliter" to U("vol", 0.001), "ml" to U("vol", 0.001),
        "gallonen" to U("vol", 3.785411784), "gallons" to U("vol", 3.785411784), "gallon" to U("vol", 3.785411784), "gal" to U("vol", 3.785411784),
        "tassen" to U("vol", 0.2365882365), "cups" to U("vol", 0.2365882365), "cup" to U("vol", 0.2365882365),
        // Geschwindigkeit (Basis: km/h)
        "km/h" to U("speed", 1.0), "kmh" to U("speed", 1.0), "stundenkilometer" to U("speed", 1.0),
        "mph" to U("speed", 1.609344), "m/s" to U("speed", 3.6), "knoten" to U("speed", 1.852), "knots" to U("speed", 1.852),
        // Daten (Basis: MB)
        "gb" to U("data", 1000.0), "mb" to U("data", 1.0), "tb" to U("data", 1_000_000.0), "kb" to U("data", 0.001),
    )
    private val temps = mapOf(
        "celsius" to "C", "grad celsius" to "C", "°c" to "C", "c" to "C", "grad" to "C",
        "fahrenheit" to "F", "°f" to "F", "f" to "F", "kelvin" to "K", "k" to "K",
    )

    fun tryAnswer(input: String, lang: String): String? {
        val s = input.lowercase(Locale.ROOT).trim().trimEnd('?', '.', '!')
        val m = Regex("(-?\\d+(?:[.,]\\d+)?)\\s*([a-zäöüß°/]+(?:\\s+celsius)?)\\s+(?:in|nach|zu|to|into|as)\\s+([a-zäöüß°/]+)").find(s) ?: return null
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val from = m.groupValues[2].trim()
        val to = m.groupValues[3].trim()
        val tf = temps[from]
        val tt = temps[to]
        if (tf != null && tt != null) {
            val c = when (tf) { "F" -> (v - 32) * 5 / 9; "K" -> v - 273.15; else -> v }
            val r = when (tt) { "F" -> c * 9 / 5 + 32; "K" -> c + 273.15; else -> c }
            val unit = mapOf("C" to "°C", "F" to "°F", "K" to "K")[tt]
            return say(v, from, Calc.fmt(round(r), lang) + " $unit", lang)
        }
        val uf = units[from] ?: return null
        val ut = units[to] ?: return null
        if (uf.dim != ut.dim) return null
        val r = v * uf.factor / ut.factor
        return say(v, from, Calc.fmt(round(r), lang) + " " + m.groupValues[3], lang)
    }

    private fun round(v: Double) = if (abs(v) >= 100) Math.round(v * 100) / 100.0 else Math.round(v * 10000) / 10000.0

    private fun say(v: Double, from: String, result: String, lang: String) =
        if (lang == "en") "${Calc.fmt(v, lang)} $from is $result." else "${Calc.fmt(v, lang)} $from sind $result."
}

/** Aktionen am Gerät – alles lokal, über System-Intents. */
class DeviceActions(private val ctx: Context) {

    private fun start(i: Intent): Boolean =
        runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    fun openApp(query: String): String? {
        val q = query.lowercase(Locale.ROOT).trim()
        // System-Einstellungen
        val settingsIntent = when {
            Regex("wlan|wifi|wi-fi").containsMatchIn(q) -> Intent(Settings.ACTION_WIFI_SETTINGS)
            Regex("bluetooth").containsMatchIn(q) -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            Regex("akku|batterie|battery").containsMatchIn(q) -> Intent(Intent.ACTION_POWER_USAGE_SUMMARY)
            Regex("^(die )?einstellungen$|^settings$").containsMatchIn(q) -> Intent(Settings.ACTION_SETTINGS)
            else -> null
        }
        if (settingsIntent != null) return if (start(settingsIntent)) query else null

        val pm = ctx.packageManager
        val launchables = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val clean = q.removePrefix("die ").removePrefix("app ").removeSuffix(" app").trim()
        val best = launchables.map { it to it.loadLabel(pm).toString() }
            .map { (ri, label) -> Triple(ri, label, score(label.lowercase(Locale.ROOT), clean)) }
            .filter { it.third > 0 }
            .maxByOrNull { it.third } ?: return null
        val intent = pm.getLaunchIntentForPackage(best.first.activityInfo.packageName) ?: return null
        return if (start(intent)) best.second else null
    }

    private fun score(label: String, q: String): Int = when {
        label == q -> 100
        label.startsWith(q) -> 80
        label.contains(q) -> 60
        q.contains(label) && label.length >= 3 -> 40
        else -> 0
    }

    fun torch(on: Boolean): Boolean = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.first { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.setTorchMode(id, on)
    }.isSuccess

    fun alarm(hour: Int, minute: Int, label: String): Boolean = start(
        Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
    )

    fun dial(number: String): Boolean = start(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })))

    fun email(address: String): Boolean = start(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$address")))

    fun openUrl(url: String): Boolean = start(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url")))

    fun navigate(dest: String): Boolean =
        start(Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))) ||
            start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest))))

    fun showOnMap(place: String): Boolean = start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place))))

    fun webSearch(query: String): Boolean =
        start(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            openUrl("https://www.google.com/search?q=" + Uri.encode(query))

    fun calendarEvent(title: String, start: java.time.LocalDateTime): Boolean {
        val begin = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return start(
            Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 3_600_000L)
        )
    }

    fun share(text: String): Boolean =
        start(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Teilen"))
}
