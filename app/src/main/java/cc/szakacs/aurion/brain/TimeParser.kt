package cc.szakacs.aurion.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * Versteht deutsche und englische Zeitangaben, z. B.
 *  "in 10 Minuten", "morgen um 8", "heute Abend", "am Freitag um halb 7", "am 24.12. um 18 Uhr",
 *  "in an hour", "tomorrow at 5pm", "on monday morning", "at 7:30".
 */
object TimeParser {

    data class Result(val dateTime: LocalDateTime?, val rest: String)

    private val numberWords = mapOf(
        "ein" to 1, "eine" to 1, "einer" to 1, "einem" to 1, "einen" to 1, "a" to 1, "an" to 1, "one" to 1,
        "zwei" to 2, "two" to 2, "drei" to 3, "three" to 3, "vier" to 4, "four" to 4, "fünf" to 5, "five" to 5,
        "sechs" to 6, "six" to 6, "sieben" to 7, "seven" to 7, "acht" to 8, "eight" to 8, "neun" to 9, "nine" to 9,
        "zehn" to 10, "ten" to 10, "elf" to 11, "eleven" to 11, "zwölf" to 12, "twelve" to 12,
        "fünfzehn" to 15, "fifteen" to 15, "zwanzig" to 20, "twenty" to 20, "dreißig" to 30, "thirty" to 30,
        "vierzig" to 40, "forty" to 40, "fünfundvierzig" to 45, "sechzig" to 60, "sixty" to 60,
    )
    private const val NUM = "(\\d+(?:[.,]\\d+)?|ein|eine|einer|einem|einen|a|an|one|zwei|two|drei|three|vier|four|fünf|five|sechs|six|sieben|seven|acht|eight|neun|nine|zehn|ten|elf|eleven|zwölf|twelve|fünfzehn|fifteen|zwanzig|twenty|dreißig|thirty|vierzig|forty|fünfundvierzig|sechzig|sixty)"

    private fun num(s: String): Double? =
        s.replace(',', '.').toDoubleOrNull() ?: numberWords[s]?.toDouble()

    private val weekdays = mapOf(
        "montag" to DayOfWeek.MONDAY, "dienstag" to DayOfWeek.TUESDAY, "mittwoch" to DayOfWeek.WEDNESDAY,
        "donnerstag" to DayOfWeek.THURSDAY, "freitag" to DayOfWeek.FRIDAY, "samstag" to DayOfWeek.SATURDAY,
        "sonntag" to DayOfWeek.SUNDAY, "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
    )

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): Result {
        var s = " " + input.lowercase() + " "
        val orig = StringBuilder(" $input ")

        fun consume(r: IntRange) {
            for (i in r) {
                if (i < s.length) {
                    s = s.substring(0, i) + " " + s.substring(i + 1)
                    orig.setCharAt(i, ' ')
                }
            }
        }

        fun find(regex: String): MatchResult? = Regex(regex).find(s)

        // ---------- 1) Relative Angaben ----------
        var relMinutes: Double? = null
        find("\\b(in|nach|within)\\s+(einer\\s+|einem\\s+)?(halben|half\\s+an?)\\s+(stunde|hour)\\b")?.let {
            relMinutes = 30.0; consume(it.range)
        }
        if (relMinutes == null) find("\\b(in|nach)\\s+(einer\\s+)?(viertelstunde|quarter\\s+of\\s+an\\s+hour|a\\s+quarter\\s+hour)\\b")?.let {
            relMinutes = 15.0; consume(it.range)
        }
        if (relMinutes == null) find("\\b(in|nach|within)\\s+(about\\s+|etwa\\s+|ca\\.?\\s+)?$NUM\\s*(sekunden|sekunde|sek|seconds?|secs?|minuten|minute|min|mins?|minutes?|stunden|stunde|std|h|hours?|hrs?|tagen|tage|tag|days?|wochen|woche|weeks?)\\b")?.let { m ->
            val n = num(m.groupValues[3])
            val unit = m.groupValues[4]
            if (n != null) {
                relMinutes = when {
                    unit.startsWith("sek") || unit.startsWith("sec") -> n / 60.0
                    unit.startsWith("min") -> n
                    unit.startsWith("st") || unit == "h" || unit.startsWith("hour") || unit.startsWith("hr") -> n * 60
                    unit.startsWith("tag") || unit.startsWith("day") -> n * 1440
                    else -> n * 10080
                }
                consume(m.range)
            }
        }
        // "in 2 stunden und 30 minuten"
        if (relMinutes != null) {
            find("^\\s*(und|and)\\s+$NUM\\s*(minuten|minutes?|min)\\b")?.let { m ->
                num(m.groupValues[2])?.let { relMinutes = relMinutes!! + it; consume(m.range) }
            }
            val secs = (relMinutes!! * 60).toLong()
            return Result(now.plusSeconds(secs).withNano(0), clean(orig.toString()))
        }

        // ---------- 2) Datum ----------
        var date: LocalDate? = null
        var partHour: Int? = null
        var partMinute = 0
        var isPm = false
        var isAm = false

        find("\\b(heute\\s+(früh|morgen)|this\\s+morning)\\b")?.let { date = now.toLocalDate(); partHour = 8; consume(it.range) }
        find("\\b(heute\\s+nacht|tonight)\\b")?.let { date = now.toLocalDate(); partHour = 21; consume(it.range) }
        find("\\b(übermorgen|day\\s+after\\s+tomorrow)\\b")?.let { date = now.toLocalDate().plusDays(2); consume(it.range) }
        if (date == null) find("\\b(morgen\\s+früh|tomorrow\\s+morning)\\b")?.let { date = now.toLocalDate().plusDays(1); partHour = 8; consume(it.range) }
        if (date == null) find("(?<!am |heute |guten )\\b(morgen|tomorrow)\\b")?.let { date = now.toLocalDate().plusDays(1); consume(it.range) }
        if (date == null) find("\\b(heute|today)\\b")?.let { date = now.toLocalDate(); consume(it.range) }
        if (date == null) {
            find("\\b(am\\s+|on\\s+|next\\s+|nächsten\\s+|kommenden\\s+|diesen\\s+|this\\s+)?(montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")?.let { m ->
                val dow = weekdays[m.groupValues[2]]!!
                var d = now.toLocalDate().with(TemporalAdjusters.next(dow))
                if (m.groupValues[1].trim() in setOf("next", "nächsten", "kommenden") && d.isBefore(now.toLocalDate().plusDays(2))) d = d.plusWeeks(1)
                date = d
                consume(m.range)
            }
        }
        if (date == null) {
            find("\\b(am\\s+|on\\s+)?(\\d{1,2})\\.\\s?(\\d{1,2})\\.(\\d{2,4})?")?.let { m ->
                val day = m.groupValues[2].toInt()
                val month = m.groupValues[3].toInt()
                var year = m.groupValues[4].toIntOrNull() ?: now.year
                if (year < 100) year += 2000
                runCatching {
                    var d = LocalDate.of(year, month, day)
                    if (m.groupValues[4].isEmpty() && d.isBefore(now.toLocalDate())) d = d.plusYears(1)
                    date = d
                    consume(m.range)
                }
            }
        }

        // ---------- 3) Tageszeit ----------
        find("\\b(am\\s+|in\\s+der\\s+|in\\s+the\\s+)?(früh|morgens|morning|in\\s+der\\s+früh)\\b")?.let { if (partHour == null) partHour = 8; isAm = true; consume(it.range) }
        find("\\b(am\\s+)?(vormittag|vormittags)\\b")?.let { partHour = 10; isAm = true; consume(it.range) }
        find("\\b(zu\\s+|am\\s+|at\\s+)?(mittag|mittags|noon|midday)\\b")?.let { partHour = 12; consume(it.range) }
        find("\\b(am\\s+|in\\s+the\\s+)?(nachmittag|nachmittags|afternoon)\\b")?.let { partHour = 15; isPm = true; consume(it.range) }
        find("\\b(am\\s+|in\\s+the\\s+|this\\s+)?(abend|abends|evening)\\b")?.let { partHour = 19; isPm = true; consume(it.range) }
        find("\\b(in\\s+der\\s+|at\\s+)?(nacht|nachts|night)\\b")?.let { partHour = 22; isPm = true; consume(it.range) }
        find("\\b(um\\s+|at\\s+)?(mitternacht|midnight)\\b")?.let { partHour = 0; consume(it.range); if (date == null) date = now.toLocalDate().plusDays(1) }

        // ---------- 4) Uhrzeit ----------
        var hour: Int? = null
        var minute = 0
        find("\\b(um\\s+)?(halb)\\s+(\\d{1,2})\\b")?.let { m ->
            hour = (m.groupValues[3].toInt() - 1 + 24) % 24; minute = 30; consume(m.range)
        }
        if (hour == null) find("\\b(um\\s+)?viertel\\s+(nach|vor)\\s+(\\d{1,2})\\b")?.let { m ->
            val h = m.groupValues[3].toInt()
            if (m.groupValues[2] == "nach") { hour = h; minute = 15 } else { hour = (h - 1 + 24) % 24; minute = 45 }
            consume(m.range)
        }
        if (hour == null) find("\\b(at\\s+)?(half\\s+past|quarter\\s+past|quarter\\s+to)\\s+(\\d{1,2})\\b")?.let { m ->
            val h = m.groupValues[3].toInt()
            when (m.groupValues[2].replace(Regex("\\s+"), " ")) {
                "half past" -> { hour = h; minute = 30 }
                "quarter past" -> { hour = h; minute = 15 }
                else -> { hour = (h - 1 + 24) % 24; minute = 45 }
            }
            consume(m.range)
        }
        if (hour == null) Regex("\\b(um|at|gegen|around|by)?\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(uhr|h\\b|o'clock|am|pm|a\\.m\\.|p\\.m\\.)?(?:\\s+(\\d{1,2})\\b)?")
            .findAll(s).firstOrNull { m ->
                // Nur akzeptieren, wenn klar eine Uhrzeit gemeint ist
                m.groupValues[1].isNotEmpty() || m.groupValues[3].isNotEmpty() || m.groupValues[4].isNotEmpty()
            }?.let { m ->
                val h = m.groupValues[2].toInt()
                if (h in 0..24) {
                    hour = h % 24
                    minute = m.groupValues[3].toIntOrNull()
                        ?: (if (m.groupValues[4] == "uhr") m.groupValues[5].toIntOrNull() else null)
                        ?: 0
                    if (minute > 59) minute = 0
                    val suffix = m.groupValues[4]
                    if (suffix.startsWith("p")) isPm = true
                    if (suffix.startsWith("a")) isAm = true
                    // "8 uhr 30" -> die 30 wurde mit erfasst; sonst Gruppe 5 nicht verbrauchen
                    val end = if (m.groupValues[4] == "uhr" && m.groupValues[5].isNotEmpty()) m.range.last
                    else (m.groups[4]?.range?.last ?: m.groups[3]?.range?.last ?: m.groups[2]!!.range.last)
                    consume(m.range.first..end)
                }
            }

        if (date == null && hour == null && partHour == null) return Result(null, input.trim())

        var h: Int
        var min: Int
        if (hour != null) {
            h = hour!!
            min = minute
            if (isPm && h < 12) h += 12
            if (isAm && h == 12) h = 0
        } else if (partHour != null) {
            h = partHour!!
            min = partMinute
        } else {
            h = 9
            min = 0
        }

        var dt = LocalDateTime.of(date ?: now.toLocalDate(), LocalTime.of(h, min))
        if (date == null && !dt.isAfter(now)) {
            // Uhrzeit ohne Datum, bereits vorbei: bei ≤12 Uhr ohne am/pm erst die Nachmittags-Variante probieren
            if (hour != null && !isAm && !isPm && h in 1..11 && dt.plusHours(12).isAfter(now)) dt = dt.plusHours(12)
            else dt = dt.plusDays(1)
        }
        return Result(dt, clean(orig.toString()))
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.!?])"), "$1").trim()
}
