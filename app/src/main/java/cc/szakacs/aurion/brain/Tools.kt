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

/** Dauer aus Sprache: "10 Minuten", "1 Stunde 30", "anderthalb Stunden", "90 Sekunden", "5 min", "2:30" */
object DurationParser {
    data class Result(val ms: Long, val rest: String)

    private val words = mapOf(
        "ein" to 1.0, "eine" to 1.0, "einer" to 1.0, "einen" to 1.0, "a" to 1.0, "an" to 1.0, "one" to 1.0,
        "zwei" to 2.0, "two" to 2.0, "drei" to 3.0, "three" to 3.0, "vier" to 4.0, "four" to 4.0, "fünf" to 5.0, "five" to 5.0,
        "sechs" to 6.0, "six" to 6.0, "sieben" to 7.0, "seven" to 7.0, "acht" to 8.0, "eight" to 8.0, "neun" to 9.0, "nine" to 9.0,
        "zehn" to 10.0, "ten" to 10.0, "elf" to 11.0, "zwölf" to 12.0, "fünfzehn" to 15.0, "fifteen" to 15.0,
        "zwanzig" to 20.0, "twenty" to 20.0, "dreißig" to 30.0, "thirty" to 30.0, "vierzig" to 40.0, "fünfundvierzig" to 45.0,
        "sechzig" to 60.0, "halbe" to 0.5, "halben" to 0.5, "half" to 0.5, "anderthalb" to 1.5, "eineinhalb" to 1.5,
    )
    private const val NUM = "(\\d+(?:[.,]\\d+)?|ein|eine|einer|einen|a|an|one|zwei|two|drei|three|vier|four|fünf|five|sechs|six|sieben|seven|acht|eight|neun|nine|zehn|ten|elf|zwölf|fünfzehn|fifteen|zwanzig|twenty|dreißig|thirty|vierzig|fünfundvierzig|sechzig|halbe|halben|half|anderthalb|eineinhalb)"

    fun parse(input: String): Result? {
        var s = " " + input.lowercase() + " "
        var total = 0.0
        var found = false
        // "2:30" = 2 Min 30 s
        Regex("\\b(\\d{1,2}):(\\d{2})\\b").find(s)?.let { m ->
            total += m.groupValues[1].toDouble() * 60 + m.groupValues[2].toDouble(); found = true
            s = s.replaceRange(m.range, " ")
        }
        Regex("$NUM\\s*(stunden|stunde|std|h|hours?|hrs?|minuten|minute|min|mins?|minutes?|sekunden|sekunde|sek|s|seconds?|secs?)\\b").findAll(s).toList().forEach { m ->
            val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: words[m.groupValues[1]] ?: return@forEach
            val u = m.groupValues[2]
            total += when {
                u.startsWith("st") || u == "h" || u.startsWith("hour") || u.startsWith("hr") -> n * 3600
                u.startsWith("min") -> n * 60
                else -> n
            }
            found = true
            s = s.replace(m.value, " ")
        }
        // "eine halbe Stunde" -> "eine" bleibt übrig; "1 Stunde 30" -> 30 Minuten
        Regex("(stunde\\w*|hour\\w*)\\s+(\\d{1,2})\\b").find(input.lowercase())?.let { m ->
            if (!Regex("${m.groupValues[2]}\\s*(min|sek|sec)").containsMatchIn(input.lowercase())) total += m.groupValues[2].toDouble() * 60
        }
        if (!found || total <= 0) return null
        return Result((total * 1000).toLong(), s.replace(Regex("\\s+"), " ").trim())
    }

    fun speak(ms: Long, en: Boolean): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        val parts = ArrayList<String>()
        if (h > 0) parts += if (en) "$h hour${if (h == 1L) "" else "s"}" else "$h Stunde${if (h == 1L) "" else "n"}"
        if (m > 0) parts += if (en) "$m minute${if (m == 1L) "" else "s"}" else "$m Minute${if (m == 1L) "" else "n"}"
        if (sec > 0) parts += if (en) "$sec second${if (sec == 1L) "" else "s"}" else "$sec Sekunde${if (sec == 1L) "" else "n"}"
        return parts.joinToString(if (en) " and " else " und ")
    }
}

data class ContactMatch(val name: String, val number: String, val type: String)

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

    fun canCall() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CALL_PHONE) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    fun canReadContacts() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.READ_CONTACTS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Direkt anrufen (mit Berechtigung), sonst Wähltastatur mit Nummer öffnen */
    fun call(number: String): Boolean {
        val tel = Uri.parse("tel:" + number.filter { it.isDigit() || it == '+' })
        return if (canCall()) start(Intent(Intent.ACTION_CALL, tel)) else start(Intent(Intent.ACTION_DIAL, tel))
    }

    /** Kontakte nach Namen durchsuchen (beste Treffer zuerst) */
    fun findContacts(query: String): List<ContactMatch> {
        if (!canReadContacts()) return emptyList()
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Pair<ContactMatch, Int>>()
        val cr = ctx.contentResolver
        runCatching {
            cr.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    val n = name.lowercase()
                    val score = when {
                        n == q -> 100
                        n.split(Regex("\\s+")).any { it == q } -> 85
                        n.startsWith(q) -> 75
                        n.contains(q) -> 55
                        q.split(Regex("\\s+")).all { part -> n.contains(part) } -> 50
                        else -> 0
                    }
                    if (score > 0) {
                        val type = when (c.getInt(2)) {
                            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "Mobil"
                            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "Privat"
                            android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "Arbeit"
                            else -> "Telefon"
                        }
                        val bonus = (if (c.getInt(3) > 0) 5 else 0) + (if (type == "Mobil") 2 else 0)
                        out += ContactMatch(name, number, type) to score + bonus
                    }
                }
            }
        }
        return out.sortedByDescending { it.second }
            .distinctBy { it.first.number.filter { ch -> ch.isDigit() }.takeLast(9) }
            .map { it.first }
            .take(6)
    }

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
