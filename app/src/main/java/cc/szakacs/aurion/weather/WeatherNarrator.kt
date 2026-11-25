package cc.szakacs.aurion.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Formuliert Wetterberichte in natürlicher, gut vorlesbarer Sprache (wie im Radio) –
 * mit Tagesverlauf, Regen-/Schneezeitpunkt, Wind und Ausblick.
 */
class WeatherNarrator(private val f: Forecast, private val en: Boolean) {

    private fun t(de: String, e: String) = if (en) e else de
    private fun deg(v: Double): String {
        val r = v.roundToInt()
        return if (r < 0) t("minus ${-r}", "minus ${-r}") else "$r"
    }

    private val where = if (f.place.name == "hier") t("hier", "here") else t("in ${f.place.name}", "in ${f.place.name}")
    private val Where = where.replaceFirstChar { it.uppercase() }

    private fun isWet(code: Int) = code in 51..67 || code in 80..82 || code >= 95
    private fun isSnow(code: Int) = code in 71..77 || code in 85..86
    private fun hoursOf(date: LocalDate) = f.hours.filter { it.time.toLocalDate() == date }
    private fun wetHour(h: HourForecast) = h.prob >= 50 && (isWet(h.code) || isSnow(h.code) || h.snow > 0)
    private fun snowy(h: HourForecast) = h.snow > 0 || isSnow(h.code)
    private fun hourWord(h: Int) = if (en) (if (h == 0) "midnight" else if (h < 12) "$h am" else if (h == 12) "noon" else "${h - 12} pm") else "$h Uhr"

    /** "ist es gerade bedeckt" / "regnet es gerade leicht" */
    private fun nowPhrase(code: Int, isDay: Boolean): String = if (en) when (code) {
        0 -> if (isDay) "it's sunny and clear" else "it's clear"
        1 -> "it's mostly sunny"; 2 -> "it's partly cloudy"; 3 -> "it's overcast"; 45, 48 -> "it's foggy"
        in 51..57 -> "it's drizzling"; 61, 80 -> "it's raining lightly"; 63, 81 -> "it's raining"; 65, 82 -> "it's raining heavily"
        66, 67 -> "there's freezing rain"; 71, 85 -> "it's snowing lightly"; 73, 75, 86 -> "it's snowing"; 77 -> "there are snow grains"
        in 95..99 -> "there's a thunderstorm"; else -> "the weather is changeable"
    } else when (code) {
        0 -> if (isDay) "ist es gerade sonnig und klar" else "ist es gerade klar"
        1 -> "ist es gerade überwiegend sonnig"; 2 -> "ist es gerade teils bewölkt"; 3 -> "ist es gerade bedeckt"; 45, 48 -> "ist es gerade neblig"
        in 51..57 -> "nieselt es gerade"; 61, 80 -> "regnet es gerade leicht"; 63, 81 -> "regnet es gerade"; 65, 82 -> "regnet es gerade kräftig"
        66, 67 -> "fällt gerade gefrierender Regen"; 71, 85 -> "schneit es gerade leicht"; 73, 75, 86 -> "schneit es gerade"; 77 -> "fällt gerade Schneegriesel"
        in 95..99 -> "gewittert es gerade"; else -> "ist das Wetter gerade wechselhaft"
    }

    /** Charakter eines Tages: "sonnig", "regnerisch", … */
    private fun dayPhrase(code: Int): String = if (en) when (code) {
        0 -> "sunny"; 1 -> "mostly sunny"; 2 -> "a mix of sun and clouds"; 3 -> "grey and cloudy"; 45, 48 -> "foggy"
        in 51..57 -> "dull with some drizzle"; 61, 80, 81 -> "showery"; 63 -> "rainy"; 65, 82 -> "very wet"; 66, 67 -> "icy with freezing rain"
        in 71..77, 85, 86 -> "wintry with snow"; in 95..99 -> "stormy with thunder"; else -> "changeable"
    } else when (code) {
        0 -> "sonnig"; 1 -> "überwiegend sonnig"; 2 -> "ein Mix aus Sonne und Wolken"; 3 -> "trüb und bewölkt"; 45, 48 -> "neblig"
        in 51..57 -> "trüb mit etwas Nieselregen"; 61, 80, 81 -> "wechselhaft mit Schauern"; 63 -> "regnerisch"; 65, 82 -> "sehr regnerisch"
        66, 67 -> "glatt mit gefrierendem Regen"; in 71..77, 85, 86 -> "winterlich mit Schneefall"; in 95..99 -> "gewittrig"; else -> "wechselhaft"
    }

    private fun dayName(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> t("heute", "today")
            today.plusDays(1) -> t("morgen", "tomorrow")
            today.plusDays(2) -> t("übermorgen", "the day after tomorrow")
            else -> t("am ", "on ") + date.dayOfWeek.getDisplayName(TextStyle.FULL, if (en) Locale.ENGLISH else Locale.GERMAN)
        }
    }

    /** Wann regnet/schneit es an einem Tag? -> "vor allem am Nachmittag und Abend" */
    private fun wetParts(hours: List<HourForecast>): Pair<List<String>, Boolean> {
        val parts = listOf(
            t("am Vormittag", "in the morning") to (6..11),
            t("am Nachmittag", "in the afternoon") to (12..17),
            t("am Abend", "in the evening") to (18..22),
        )
        val wet = parts.filter { (_, r) -> hours.any { it.time.hour in r && wetHour(it) } }.map { it.first }
        val snow = hours.any { wetHour(it) && snowy(it) }
        return wet to snow
    }

    private fun joinList(xs: List<String>): String = when (xs.size) {
        0 -> ""; 1 -> xs[0]
        else -> xs.dropLast(1).joinToString(", ") + t(" und ", " and ") + xs.last()
    }

    private fun windSentence(gust: Double): String? = when {
        gust >= 60 -> t("Achtung, es wird stürmisch mit Böen bis ${gust.roundToInt()} Kilometer pro Stunde.", "Watch out, it gets stormy with gusts up to ${gust.roundToInt()} km/h.")
        gust >= 40 -> t("Dazu weht ein frischer Wind mit Böen bis ${gust.roundToInt()} Kilometer pro Stunde.", "It's also breezy, with gusts up to ${gust.roundToInt()} km/h.")
        else -> null
    }

    private fun frostSentence(d: DayForecast): String? = if (d.tMin <= 0.5)
        t("In der Früh ist es mit ${deg(d.tMin)} Grad frostig – Vorsicht vor Glätte.", "Mornings are frosty at ${deg(d.tMin)} degrees – watch out for ice.")
    else null

    /** Kurzer Satz zu Niederschlag an einem ganzen Tag */
    private fun daySky(d: DayForecast): String {
        val (parts, snow) = wetParts(hoursOf(d.date))
        val what = if (snow) t("Schnee", "snow") else t("Regen", "rain")
        return when {
            parts.size == 3 -> t("Mit $what ist fast den ganzen Tag zu rechnen.", "Expect $what for most of the day.")
            parts.isNotEmpty() -> t("$what gibt es vor allem ${joinList(parts)}.", "${what.replaceFirstChar { it.uppercase() }} is most likely ${joinList(parts)}.")
            d.rainProb >= 25 -> t("Ein kurzer Schauer ist nicht ganz ausgeschlossen.", "A brief shower can't be ruled out.")
            else -> t("Es bleibt voraussichtlich trocken.", "It should stay dry.")
        } + (if (d.snowSum >= 1.0) t(" Dabei können rund ${d.snowSum.roundToInt()} Zentimeter Schnee fallen.", " Around ${d.snowSum.roundToInt()} cm of snow may fall.") else "")
    }

    /** Ausführlicher Bericht für heute */
    fun today(withOutlook: Boolean = true): String {
        val now = LocalDateTime.now()
        val d = f.days.first()
        val sb = ArrayList<String>()

        // Jetzt
        var s1 = t("$Where ${nowPhrase(f.currentCode, f.isDay)}, bei ${deg(f.currentTemp)} Grad",
            "$Where ${nowPhrase(f.currentCode, f.isDay)} at ${deg(f.currentTemp)} degrees")
        if (abs(f.currentFeels - f.currentTemp) >= 3) s1 += t(", gefühlt eher ${deg(f.currentFeels)}", ", feeling more like ${deg(f.currentFeels)}")
        sb += "$s1."

        // Tagesverlauf
        val rest = f.hours.filter { it.time.isAfter(now) && it.time.toLocalDate() == now.toLocalDate() }
        if (now.hour < 18 && rest.isNotEmpty()) {
            val max = rest.maxOf { it.temp }
            val evening = rest.firstOrNull { it.time.hour == 20 }?.temp
            sb += if (max > f.currentTemp + 1.0)
                t("Im Laufe des Tages wird es bis zu ${deg(max)} Grad", "During the day it climbs to ${deg(max)} degrees") +
                    (evening?.let { t(", am Abend sind es dann noch etwa ${deg(it)} Grad.", ", dropping to around ${deg(it)} in the evening.") } ?: ".")
            else
                t("Viel wärmer wird es heute nicht mehr", "It won't get much warmer today") +
                    (evening?.let { t(", am Abend hat es etwa ${deg(it)} Grad.", " – around ${deg(it)} degrees this evening.") } ?: ".")
        } else {
            val night = f.hours.filter { it.time.isAfter(now) && it.time.isBefore(now.plusHours(12)) }.minOfOrNull { it.temp }
            night?.let { sb += t("In der Nacht kühlt es auf etwa ${deg(it)} Grad ab.", "Overnight it cools to around ${deg(it)} degrees.") }
        }

        sb += rainToday()
        if (d.snowSum >= 1.0) sb += t("Insgesamt können heute rund ${d.snowSum.roundToInt()} Zentimeter Schnee fallen.", "Around ${d.snowSum.roundToInt()} cm of snow may fall today.")
        windSentence(maxOf(d.gustMax, f.currentGust))?.let { sb += it }

        // Ausblick auf morgen
        if (withOutlook) f.days.getOrNull(1)?.let { tm ->
            val (parts, snow) = wetParts(hoursOf(tm.date))
            val wet = if (parts.isNotEmpty()) t(", ${if (snow) "Schnee" else "Regen"} vor allem ${joinList(parts)}", ", with ${if (snow) "snow" else "rain"} mainly ${joinList(parts)}") else ""
            sb += t("Morgen wird es ${dayPhrase(tm.code)} bei ${deg(tm.tMin)} bis ${deg(tm.tMax)} Grad$wet.",
                "Tomorrow will be ${dayPhrase(tm.code)}, ${deg(tm.tMin)} to ${deg(tm.tMax)} degrees$wet.")
        }
        return sb.joinToString(" ")
    }

    /** Regen/Schnee heute mit Uhrzeit */
    private fun rainToday(): String {
        val now = LocalDateTime.now()
        val d = f.days.first()
        val next = f.hours.filter { it.time.isAfter(now.minusMinutes(30)) && it.time.isBefore(now.plusHours(14)) }
        val wetNow = isWet(f.currentCode) || isSnow(f.currentCode)
        if (wetNow) {
            val dry = next.firstOrNull { it.time.isAfter(now) && !wetHour(it) && it.prob < 40 }
            return if (dry != null) t("Das hört voraussichtlich gegen ${hourWord(dry.time.hour)} auf.", "It should ease off around ${hourWord(dry.time.hour)}.")
            else t("Und das bleibt wohl noch eine ganze Weile so – nimm einen Schirm mit.", "And it'll likely stay that way for a while – take an umbrella.")
        }
        val first = next.firstOrNull { wetHour(it) }
        if (first != null) {
            val snow = snowy(first)
            return t("Ab etwa ${hourWord(first.time.hour)} kann es ${if (snow) "schneien" else "regnen"}" + (if (!snow) " – nimm lieber einen Schirm mit." else "."),
                "From around ${hourWord(first.time.hour)} it may ${if (snow) "snow" else "rain"}" + (if (!snow) " – better take an umbrella." else "."))
        }
        val maxP = next.maxOfOrNull { it.prob } ?: d.rainProb
        return if (maxP >= 25) t("Ein kurzer Schauer ist möglich, das Risiko liegt aber nur bei etwa $maxP Prozent.", "A brief shower is possible, but the chance is only about $maxP percent.")
        else t("Regen ist nicht in Sicht.", "No rain in sight.")
    }

    /** Antwort auf "Regnet es …?" / "Brauche ich einen Schirm?" */
    fun rainAnswer(index: Int): String {
        if (index == 0) {
            val now = LocalDateTime.now()
            val wet = isWet(f.currentCode) || isSnow(f.currentCode) ||
                f.hours.any { it.time.isAfter(now) && it.time.isBefore(now.plusHours(14)) && wetHour(it) }
            return (if (wet) t("Ja. ", "Yes. ") else t("Nein, eher nicht. ", "No, probably not. ")) + rainToday()
        }
        val d = f.days.getOrElse(index) { f.days.last() }
        val (parts, _) = wetParts(hoursOf(d.date))
        val name = dayName(d.date)
        return (if (parts.isNotEmpty() || d.rainProb >= 50) t("Ja, $name sieht es nach Niederschlag aus. ", "Yes, $name looks wet. ")
        else t("Nein, $name bleibt es wohl trocken. ", "No, $name should stay dry. ")) + daySky(d)
    }

    /** Bericht für einen bestimmten Tag */
    fun day(index: Int): String {
        if (index == 0) return today()
        val d = f.days.getOrElse(index) { f.days.last() }
        val name = dayName(d.date).replaceFirstChar { it.uppercase() }
        val sb = ArrayList<String>()
        sb += t("$name wird es $where ${dayPhrase(d.code)}, mit Temperaturen zwischen ${deg(d.tMin)} und ${deg(d.tMax)} Grad.",
            "$name it'll be ${dayPhrase(d.code)} $where, with temperatures between ${deg(d.tMin)} and ${deg(d.tMax)} degrees.")
        sb += daySky(d)
        frostSentence(d)?.let { sb += it }
        windSentence(d.gustMax)?.let { sb += it }
        return sb.joinToString(" ")
    }

    /** Überblick über mehrere Tage (z. B. "die nächsten Tage", "Wochenende") */
    fun overview(indices: List<Int>): String {
        val sb = ArrayList<String>()
        sb += t("Hier der Überblick für $where:", "Here's the outlook for $where:")
        indices.mapNotNull { f.days.getOrNull(it) }.forEach { d ->
            val (parts, snow) = wetParts(hoursOf(d.date))
            val wet = when {
                parts.size == 3 -> t(", fast den ganzen Tag ${if (snow) "Schnee" else "Regen"}", ", ${if (snow) "snow" else "rain"} most of the day")
                parts.isNotEmpty() -> t(", ${if (snow) "Schnee" else "Regen"} ${joinList(parts)}", ", ${if (snow) "snow" else "rain"} ${joinList(parts)}")
                else -> ""
            }
            sb += t("${dayName(d.date).replaceFirstChar { it.uppercase() }} ${dayPhrase(d.code)}, ${deg(d.tMin)} bis ${deg(d.tMax)} Grad$wet.",
                "${dayName(d.date).replaceFirstChar { it.uppercase() }}: ${dayPhrase(d.code)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees$wet.")
        }
        // kurzes Fazit
        val ds = indices.mapNotNull { f.days.getOrNull(it) }
        if (ds.size >= 2) {
            val warm = ds.maxBy { it.tMax }
            val dry = ds.filter { d -> wetParts(hoursOf(d.date)).first.isEmpty() }
            sb += when {
                dry.size == ds.size -> t("Insgesamt bleibt es trocken.", "Overall it stays dry.")
                dry.isEmpty() -> t("Einen ganz trockenen Tag gibt es leider nicht.", "Unfortunately there's no completely dry day.")
                else -> t("Am freundlichsten wird es ${dayName(dry.maxBy { it.tMax }.date)}.", "The nicest day looks like ${dayName(dry.maxBy { it.tMax }.date)}.")
            }
            if (warm.tMax - ds.minOf { it.tMax } >= 5) sb += t("Am wärmsten ist es ${dayName(warm.date)} mit bis zu ${deg(warm.tMax)} Grad.", "Warmest is ${dayName(warm.date)} at up to ${deg(warm.tMax)} degrees.")
        }
        return sb.joinToString(" ")
    }
}
