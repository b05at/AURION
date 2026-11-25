package cc.szakacs.aurion.brain

import android.content.Context
import cc.szakacs.aurion.bring.BringClient
import cc.szakacs.aurion.bring.BringItem
import cc.szakacs.aurion.data.ChatMessage
import cc.szakacs.aurion.data.LangMode
import cc.szakacs.aurion.data.Settings
import cc.szakacs.aurion.data.Store
import cc.szakacs.aurion.llm.LlmEngine
import cc.szakacs.aurion.llm.Turn
import cc.szakacs.aurion.reminders.Reminders
import cc.szakacs.aurion.weather.WeatherService
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Bildschirm-/Auswahl-Inhalt als Kontext für Fragen */
data class ScreenInput(
    val text: String,
    val labels: List<String>,
    val image: android.graphics.Bitmap?,
    val isSelection: Boolean,
    val app: String? = null,
)

enum class CardKind { REMINDER, SHOPPING, MEMORY, WEATHER, NONE }

data class Reply(
    val text: String,
    val lang: String,
    val card: CardKind = CardKind.NONE,
    val cardTitle: String? = null,
    val cardLines: List<String> = emptyList(),
    val weather: cc.szakacs.aurion.weather.Forecast? = null,
    val weatherDay: Int = 0,
)

/**
 * Versteht Befehle (Erinnerungen, Bring!, Gedächtnis) regelbasiert in DE/EN,
 * fällt für alles andere auf das lokale LLM zurück.
 */
class Brain(private val ctx: Context) {

    private val store = Store.get(ctx)
    private val settings = Settings.get(ctx)
    private val llm = LlmEngine.get(ctx)
    private val bring = BringClient(settings)
    private val device = DeviceActions(ctx)

    // Kontext der aktuellen Anfrage
    private var screenProvider: (suspend () -> ScreenInput?)? = null
    private var spoken = true
    private var partial: ((String) -> Unit)? = null
    private var screenCache: ScreenInput? = null
    private var screenLoaded = false

    private suspend fun screen(): ScreenInput? {
        if (!screenLoaded) {
            screenCache = runCatching { screenProvider?.invoke() }.getOrNull()
            screenLoaded = true
        }
        return screenCache
    }

    /** Offene Rückfrage (z. B. "Wann soll ich dich erinnern?") */
    private sealed class Pending {
        data class ReminderTime(val text: String) : Pending()
        data class ReminderText(val time: LocalDateTime) : Pending()
        object ShoppingItems : Pending()
    }

    private var pending: Pending? = null

    fun resetConversation() {
        pending = null
    }

    /**
     * @param screen liefert bei Bedarf den Bildschirm-/Auswahl-Kontext (lazy, wegen OCR)
     * @param spoken Antwort wird vorgelesen -> kurz; getippt -> ausführlich mit Markdown
     * @param onPartial Live-Text während das LLM schreibt
     */
    suspend fun handle(
        input: String,
        screen: (suspend () -> ScreenInput?)? = null,
        spoken: Boolean = true,
        onPartial: ((String) -> Unit)? = null,
    ): Reply {
        screenProvider = screen
        screenLoaded = false
        screenCache = null
        this.spoken = spoken
        partial = onPartial
        val text = normalize(input.trim())
        val lang = when (settings.langMode) {
            LangMode.DE -> "de"
            LangMode.EN -> "en"
            LangMode.AUTO -> Lang.detect(text, settings.lastLang)
        }
        settings.lastLang = lang
        store.addChat(ChatMessage(true, text))
        val reply = try {
            route(text, lang)
        } catch (e: Exception) {
            Reply(t(lang, "Da ist etwas schiefgelaufen: ${e.message}", "Something went wrong: ${e.message}"), lang)
        }
        store.addChat(ChatMessage(false, reply.text))
        return reply
    }

    private suspend fun route(text: String, lang: String): Reply {
        val l = text.lowercase(Locale.ROOT)

        // --- Offene Rückfragen ---
        pending?.let { p ->
            pending = null
            if (!isCancel(l)) when (p) {
                is Pending.ReminderTime -> {
                    val r = TimeParser.parse(text)
                    if (r.dateTime != null) return createReminder(p.text, r.dateTime, lang)
                }
                is Pending.ReminderText -> return createReminder(cleanReminderText(text), p.time, lang)
                Pending.ShoppingItems -> return addShopping(parseItems(text), lang)
            } else return Reply(t(lang, "Okay, abgebrochen.", "Okay, cancelled."), lang)
        }

        // --- Rechnen & Einheiten (exakt, lokal) ---
        Units.tryAnswer(text, lang)?.let { return Reply(it, lang) }
        Calc.tryAnswer(text, lang)?.let { return Reply(it, lang) }

        // --- Geräteaktionen ---
        deviceAction(text, l, lang)?.let { return it }

        // --- Zwischenablage ---
        if (Regex("zwischenablage|clipboard|kopierte(n)? text").containsMatchIn(l)) {
            val clip = readClipboard()
            if (clip.isNullOrBlank()) return Reply(t(lang, "Die Zwischenablage ist leer.", "The clipboard is empty."), lang)
            val q = text.replace(Regex("(in |aus |von |der |die |meiner |meine |my |the |in the |from the )*(zwischenablage|clipboard)", RegexOption.IGNORE_CASE), "das").trim()
            screenCache = ScreenInput(clip, emptyList(), null, isSelection = true)
            screenLoaded = true
            return chat(q.ifBlank { t(lang, "Fasse das zusammen", "Summarize this") }, lang, screenCache)
        }

        // --- Uhrzeit / Datum ---
        if (Regex("\\b(wie spät|wieviel uhr|wie viel uhr|what time is it|what's the time|current time)\\b").containsMatchIn(l)) {
            val now = LocalDateTime.now()
            return Reply(
                t(lang, "Es ist ${now.format(DateTimeFormatter.ofPattern("H:mm"))} Uhr.",
                    "It's ${now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}."), lang
            )
        }
        if (Regex("\\b(welcher tag ist|welches datum|der wievielte|what day is|what's the date|what is the date|today's date)\\b").containsMatchIn(l)) {
            val now = LocalDateTime.now()
            return Reply(
                t(lang, "Heute ist ${now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN))}.",
                    "Today is ${now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.ENGLISH))}."), lang
            )
        }

        // --- Wetter ---
        if (Regex("wetter|regnen|regnet|sonnenaufgang|sonnenuntergang|windig|\\bsturm|böen|\\buv\\b|sonnencreme|sunrise|sunset|windy|\\bregen\\b|schneien|schneit|\\bschnee\\b|gewitter|wie warm|wie kalt|grad hat es|temperatur|regenschirm|\\bschirm\\b|sonnig|vorhersage|weather|\\brain\\b|raining|\\bsnow|temperature|how warm|how cold|umbrella|forecast|sunny").containsMatchIn(l)
            && !Regex("erinner|remind").containsMatchIn(l)
        ) {
            return weather(text, l, lang)
        }

        // --- Erinnerungen auflisten ---
        if (Regex("(welche|meine|offene|zeig\\w*|liste?)\\s+(\\w+\\s+)?erinnerungen|what reminders|my reminders|list (my )?reminders|show (my )?reminders").containsMatchIn(l)) {
            return listReminders(lang)
        }

        // --- Erinnerung anlegen ---
        if (Regex("\\b(erinner\\w*|remind\\w*|reminder)\\b").containsMatchIn(l)) {
            return reminderIntent(text, lang)
        }

        // --- Einkaufsliste ---
        val mentionsList = Regex("einkaufsliste|einkaufszettel|bring!?|shopping ?list|grocery ?list|\\bliste\\b|\\blist\\b").containsMatchIn(l)
        if (mentionsList && Regex("^(was|what|zeig|show|lies|read)|was steht|what's on|what is on|was brauchen|what do (i|we) need").containsMatchIn(l)) {
            return readShopping(lang)
        }
        if (mentionsList && Regex("\\b(lösch\\w*|entfern\\w*|streich\\w*|nimm|remove|delete|take)\\b").containsMatchIn(l)) {
            val items = parseItems(text)
            if (items.isNotEmpty()) {
                items.forEach { bring.removeItem(it.name) }
                return Reply(t(lang, "${joinNames(items, lang)} von der Liste genommen.", "Removed ${joinNames(items, lang)} from the list."), lang,
                    CardKind.SHOPPING, "Bring!", items.map { "– ${it.name}" })
            }
        }
        if (mentionsList || Regex("^(kauf\\w*|wir brauchen|ich brauche|we need|i need|buy)\\b").containsMatchIn(l)) {
            var items = parseItems(text)
            if (items.isEmpty()) screen()?.let { sc ->
                // "Setz das auf die Einkaufsliste" mit Bildschirm/Auswahl
                items = if (llm.isAvailable) llmExtractItems(sc.text.take(1500) + "\n" + sc.labels.joinToString(", "))
                else sc.text.lines().map { it.trim() }.filter { it.length in 2..40 }.take(10).map { BringItem(it.replaceFirstChar { c -> c.uppercase() }, "") }
            }
            if (items.isEmpty() && llm.isAvailable) items = llmExtractItems(text)
            if (items.isEmpty()) {
                pending = Pending.ShoppingItems
                return Reply(t(lang, "Was soll ich auf die Einkaufsliste setzen?", "What should I add to the shopping list?"), lang)
            }
            return addShopping(items, lang)
        }

        // --- Gedächtnis ---
        Regex("^(bitte\\s+)?(merk\\s*dir|merke\\s*dir|speicher\\w*|notier\\w*\\s*dir|remember|note)\\s*(bitte\\s*)?(,|:)?\\s*(dass|das|that)?\\s*(.+)$", RegexOption.IGNORE_CASE)
            .find(text)?.let { m ->
                val fact = m.groupValues[6].trim().trimEnd('.', '!')
                if (fact.isNotEmpty()) {
                    store.addMemory(fact)
                    return Reply(t(lang, "Alles klar, ich merke mir: $fact.", "Got it, I'll remember: $fact."), lang,
                        CardKind.MEMORY, t(lang, "Gemerkt", "Remembered"), listOf(fact))
                }
            }
        Regex("^(bitte\\s+)?(vergiss|forget)\\s+(bitte\\s+)?(dass|das|that|about)?\\s*(.+)$", RegexOption.IGNORE_CASE).find(text)?.let { m ->
            val term = m.groupValues[5].trim().trimEnd('.', '!')
            val n = store.forgetMatching(term)
            return Reply(
                if (n > 0) t(lang, "Erledigt, ich habe es vergessen.", "Done, I've forgotten it.")
                else t(lang, "Dazu habe ich mir nichts gemerkt.", "I don't have anything about that."), lang
            )
        }
        if (Regex("was weißt du über mich|was hast du dir gemerkt|what do you know about me|what do you remember").containsMatchIn(l)) {
            val mem = store.memory.value
            if (mem.isEmpty()) return Reply(t(lang, "Ich habe mir noch nichts gemerkt.", "I haven't remembered anything yet."), lang)
            return Reply(
                t(lang, "Ich habe mir ${mem.size} Dinge gemerkt.", "I remember ${mem.size} things."), lang,
                CardKind.MEMORY, t(lang, "Gedächtnis", "Memory"), mem.map { it.text }
            )
        }

        // --- Freies Gespräch mit dem lokalen LLM (ggf. mit Bildschirm-Kontext) ---
        val sc = screenProvider?.let {
            val selection = screen()?.takeIf { it.isSelection }
            selection ?: if (wantsScreen(l)) screen() else null
        }
        return chat(text, lang, sc)
    }

    // ================= Erinnerungen =================

    private suspend fun reminderIntent(text: String, lang: String): Reply {
        val parsed = TimeParser.parse(text)
        var body = cleanReminderText(parsed.rest)
        if (body.isBlank() || Regex("^(daran|das|dies|this|that|it)$", RegexOption.IGNORE_CASE).matches(body)) {
            body = screen()?.let { sc ->
                sc.text.lines().map { it.trim() }.firstOrNull { it.length > 2 }?.take(80) ?: sc.labels.firstOrNull()
            } ?: body.takeUnless { Regex("^(daran|das|dies|this|that|it)$", RegexOption.IGNORE_CASE).matches(it) }.orEmpty()
        }
        if (parsed.dateTime != null) {
            if (body.isBlank()) {
                pending = Pending.ReminderText(parsed.dateTime)
                return Reply(t(lang, "Woran soll ich dich erinnern?", "What should I remind you about?"), lang)
            }
            return createReminder(body, parsed.dateTime, lang)
        }
        // Kein Zeitpunkt erkannt -> LLM fragen, sonst nachfragen
        if (llm.isAvailable) {
            llmExtractReminder(text)?.let { (time, what) ->
                if (time.isAfter(LocalDateTime.now())) return createReminder(what.ifBlank { body }, time, lang)
            }
        }
        if (body.isBlank()) return Reply(t(lang, "Woran und wann soll ich dich erinnern?", "What and when should I remind you?"), lang)
        pending = Pending.ReminderTime(body)
        return Reply(t(lang, "Wann soll ich dich an „$body“ erinnern?", "When should I remind you to $body?"), lang)
    }

    private fun createReminder(what: String, time: LocalDateTime, lang: String): Reply {
        val millis = time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val r = Reminders.create(ctx, what, millis)
        val whenText = Reminders.formatWhen(ctx, r.triggerAt)
        return Reply(
            t(lang, "Ich erinnere dich $whenText: $what.", "I'll remind you $whenText: $what."), lang,
            CardKind.REMINDER, what, listOf(whenText)
        )
    }

    private fun listReminders(lang: String): Reply {
        val open = store.reminders.value.filter { !it.acknowledged }
        if (open.isEmpty()) return Reply(t(lang, "Du hast keine offenen Erinnerungen.", "You have no open reminders."), lang)
        return Reply(
            t(lang, "Du hast ${open.size} offene Erinnerung${if (open.size == 1) "" else "en"}.",
                "You have ${open.size} open reminder${if (open.size == 1) "" else "s"}."), lang,
            CardKind.REMINDER, t(lang, "Erinnerungen", "Reminders"),
            open.map { "${it.text} · ${Reminders.formatWhen(ctx, it.triggerAt)}" }
        )
    }

    private fun cleanReminderText(s: String): String {
        var r = s.trim()
        val prefixes = listOf(
            "^(hey\\s+|hallo\\s+|hi\\s+)?(aurion[,!]?\\s+)?",
            "^(bitte\\s+|please\\s+|kannst du\\s+|can you\\s+|could you\\s+)?",
            "^(mich\\s+)?(erinnere|erinner|erinnern|remind)\\s+(mich|uns|me|us)\\s*",
            "^(set\\s+a\\s+reminder|create\\s+a\\s+reminder|neue\\s+erinnerung|erstelle?\\s+(eine\\s+)?erinnerung|erinnerung)\\s*(:|for|to|that|about|an|für|zu|dass)?\\s*",
            "^(bitte\\s+|please\\s+)?",
            "^(daran|dran)\\s*,?\\s*",
            "^(dass|das|that|to|about|of|an|zu|wegen)\\s+",
        )
        prefixes.forEach { r = r.replace(Regex(it, RegexOption.IGNORE_CASE), "") }
        r = r.replace(Regex("\\s+(zu\\s+erinnern|erinnern|bitte|please)\\s*[.!?]*$", RegexOption.IGNORE_CASE), "")
        r = r.trim().trim(',', '.', '!', '?', ':').trim()
        return r.replaceFirstChar { it.uppercase() }
    }

    // ================= Wetter =================

    private val dayWords = "heute|today|morgen|tomorrow|übermorgen|day after tomorrow|montag|dienstag|mittwoch|donnerstag|freitag|samstag|sonntag|monday|tuesday|wednesday|thursday|friday|saturday|sunday|am wochenende|this weekend|früh|abend|nachmittag|vormittag|morning|evening|afternoon|tonight|jetzt|now|gerade|draußen|outside"

    private suspend fun weather(text: String, l: String, lang: String): Reply {
        val en = lang == "en"
        // Tag bestimmen
        val today = java.time.LocalDate.now()
        var dayOffset = 0
        when {
            Regex("übermorgen|day after tomorrow").containsMatchIn(l) -> { dayOffset = 2 }
            Regex("(?<!heute |guten )\\bmorgen\\b|tomorrow").containsMatchIn(l) -> { dayOffset = 1 }
            else -> {
                val names = listOf("montag" to 1, "dienstag" to 2, "mittwoch" to 3, "donnerstag" to 4, "freitag" to 5, "samstag" to 6, "sonntag" to 7,
                    "monday" to 1, "tuesday" to 2, "wednesday" to 3, "thursday" to 4, "friday" to 5, "saturday" to 6, "sunday" to 7)
                names.firstOrNull { Regex("\\b${it.first}\\b").containsMatchIn(l) }?.let { (_, dow) ->
                    dayOffset = ((dow - today.dayOfWeek.value) + 7) % 7
                }
            }
        }
        if (dayOffset > 6) dayOffset = 6

        // Ort bestimmen ("in Wien", "für Graz", "in London")
        var place: cc.szakacs.aurion.weather.Place? = null
        Regex("\\b(?:in|für|for|at)\\s+([\\p{L}][\\p{L} .\\-]*)", RegexOption.IGNORE_CASE).findAll(text).forEach { m ->
            if (place != null) return@forEach
            var name = m.groupValues[1]
            name = name.replace(Regex("\\b($dayWords)\\b.*$", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\b(wetter|weather|wie|how|is|ist|es|it|wird|will|be|sein|regnen|rain|schneien|snow|werden)\\b.*$", RegexOption.IGNORE_CASE), "")
                .trim().trimEnd('?', '.', '!', ',').trim()
            val skip = setOf("der", "die", "das", "the", "der früh", "the morning", "")
            if (name.lowercase() !in skip && name.length >= 2) place = WeatherService.geocode(name, lang)
        }
        if (place == null) place = WeatherService.homePlace(ctx)
        val p = place ?: return Reply(
            t(lang, "Ich weiß nicht, wo du bist. Erlaube den Standort oder lege in den Einstellungen einen Wetter-Ort fest.",
                "I don't know where you are. Allow location access or set a weather location in settings."), lang
        )

        val f = WeatherService.forecast(p)
        val d = f.days.getOrNull(dayOffset) ?: f.days.first()
        val dayName = when (dayOffset) {
            0 -> t(lang, "heute", "today")
            1 -> t(lang, "morgen", "tomorrow")
            2 -> t(lang, "übermorgen", "the day after tomorrow")
            else -> t(lang, "am ", "on ") + d.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, if (en) Locale.ENGLISH else Locale.GERMAN)
        }
        val where = if (p.name == "hier") t(lang, "hier", "here") else t(lang, "in ${p.name}", "in ${p.name}")
        val deg = WeatherService::deg

        // Allgemeine Wetterfrage ("Wie ist das Wetter?") -> immer der volle Bericht, kein Ja/Nein
        val general = Regex("wetter|weather|vorhersage|forecast|wie wird es|what.?s it like").containsMatchIn(l) &&
            !Regex("^(wird|regnet|schneit|gibt es|brauche|brauch|will|is it|does|do i|should)\\b").containsMatchIn(l.trim())
        val askRain = !general && Regex("regnen|regnet|\\bregen\\b|schirm|\\brain\\b|raining|umbrella|\\bnass\\b|\\bwet\\b").containsMatchIn(l)
        val askSnow = !general && Regex("schneien|schneit|\\bschnee\\b|\\bsnow").containsMatchIn(l)
        val askWind = !general && Regex("wind|sturm|böen|boeen|gust|storm").containsMatchIn(l)
        val askSun = !general && Regex("sonnenaufgang|sonnenuntergang|sonne auf|sonne unter|wann wird es (hell|dunkel)|sunrise|sunset").containsMatchIn(l)
        val askUv = !general && Regex("\\buv\\b|sonnencreme|sonnenschutz|sunscreen").containsMatchIn(l)

        fun n1(v: Double) = if (en) "%.1f".format(Locale.ENGLISH, v) else "%.1f".format(Locale.GERMAN, v)
        fun cond(c: Int) = WeatherService.describe(c, en)

        // Alle relevanten Zusatzinfos zu einem Tag
        fun extras(day: cc.szakacs.aurion.weather.DayForecast): String {
            val parts = ArrayList<String>()
            parts += if (day.rainSum >= 0.1) t(lang, "Regen ${day.rainProb} % (${n1(day.rainSum)} mm)", "rain ${day.rainProb}% (${n1(day.rainSum)} mm)")
            else t(lang, "Regenwahrscheinlichkeit ${day.rainProb} %", "${day.rainProb}% chance of rain")
            if (day.snowProb > 0 || day.snowSum > 0 || day.tMin <= 2.0) {
                parts += if (day.snowSum >= 0.1) t(lang, "Schnee ${day.snowProb} % (${n1(day.snowSum)} cm)", "snow ${day.snowProb}% (${n1(day.snowSum)} cm)")
                else t(lang, "Schneewahrscheinlichkeit ${day.snowProb} %", "${day.snowProb}% chance of snow")
            }
            if (day.gustMax >= 40) parts += t(lang, "Böen bis ${deg(day.gustMax)} km/h", "gusts up to ${deg(day.gustMax)} km/h")
            return parts.joinToString(", ")
        }

        val answer = when {
            askSun -> t(lang, "$dayName $where geht die Sonne um ${d.sunrise} Uhr auf und um ${d.sunset} Uhr unter.",
                "$dayName $where the sun rises at ${d.sunrise} and sets at ${d.sunset}.").replaceFirstChar { it.uppercase() }
            askUv -> t(lang, "Der UV-Index erreicht $dayName $where bis zu ${n1(d.uvMax)}." + if (d.uvMax >= 3) " Sonnenschutz ist sinnvoll." else "",
                "The UV index $dayName $where reaches up to ${n1(d.uvMax)}." + if (d.uvMax >= 3) " Sunscreen is a good idea." else "")
            askWind -> t(lang, "$dayName $where: Wind bis ${deg(d.windMax)} km/h, Böen bis ${deg(d.gustMax)} km/h." +
                    (if (dayOffset == 0) " Gerade ${deg(f.currentWind)} km/h." else ""),
                "$dayName $where: wind up to ${deg(d.windMax)} km/h, gusts up to ${deg(d.gustMax)} km/h." +
                    (if (dayOffset == 0) " Currently ${deg(f.currentWind)} km/h." else "")).replaceFirstChar { it.uppercase() }
            askSnow -> {
                if (d.snowProb >= 40 || d.snowSum >= 0.1 || WeatherService.isSnow(d.code))
                    t(lang, "Ja, $dayName liegt die Schneewahrscheinlichkeit $where bei ${d.snowProb} %" +
                            (if (d.snowSum >= 0.1) ", erwartet werden etwa ${n1(d.snowSum)} cm" else "") +
                            ". Temperaturen ${deg(d.tMin)} bis ${deg(d.tMax)} Grad.",
                        "Yes, $dayName $where there's a ${d.snowProb}% chance of snow" +
                            (if (d.snowSum >= 0.1) ", about ${n1(d.snowSum)} cm expected" else "") +
                            ". Temperatures ${deg(d.tMin)} to ${deg(d.tMax)} degrees.")
                else if (d.snowProb > 0)
                    t(lang, "Eher nicht – die Schneewahrscheinlichkeit liegt $dayName $where bei ${d.snowProb} %, bei ${deg(d.tMin)} bis ${deg(d.tMax)} Grad.",
                        "Unlikely – $dayName $where there's a ${d.snowProb}% chance of snow, ${deg(d.tMin)} to ${deg(d.tMax)} degrees.")
                else t(lang, "Nein, Schnee ist $dayName $where nicht in Sicht. Es wird ${cond(d.code)} bei ${deg(d.tMin)} bis ${deg(d.tMax)} Grad.",
                    "No snow in sight $dayName $where. It'll be ${cond(d.code)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees.")
            }
            askRain -> {
                val wet = WeatherService.isWet(d.code) || d.rainProb >= 50
                val snowNote = if (d.snowProb >= 30) t(lang, " Schnee ist mit ${d.snowProb} % auch möglich.", " Snow is also possible (${d.snowProb}%).") else ""
                (if (wet) t(lang, "Ja, $dayName liegt die Regenwahrscheinlichkeit $where bei ${d.rainProb} %, etwa ${n1(d.rainSum)} mm. Nimm lieber einen Schirm mit.",
                    "Yes, $dayName $where there's a ${d.rainProb}% chance of rain, about ${n1(d.rainSum)} mm. Better take an umbrella.")
                else if (d.rainProb >= 25) t(lang, "Eher nicht, aber $dayName gibt es $where eine Regenwahrscheinlichkeit von ${d.rainProb} %.",
                    "Probably not, but $dayName there's a ${d.rainProb}% chance of rain $where.")
                else t(lang, "Nein, $dayName bleibt es $where voraussichtlich trocken (${d.rainProb} %) – ${cond(d.code)}.",
                    "No, $dayName it should stay dry $where (${d.rainProb}%) – ${cond(d.code)}.")) + snowNote
            }
            dayOffset == 0 -> t(lang,
                "$where hat es gerade ${deg(f.currentTemp)} Grad, gefühlt ${deg(f.currentFeels)}, ${cond(f.currentCode)}. " +
                    "Heute ${deg(d.tMin)} bis ${deg(d.tMax)} Grad, ${extras(d)}.",
                "It's currently ${deg(f.currentTemp)} degrees $where, feels like ${deg(f.currentFeels)}, ${cond(f.currentCode)}. " +
                    "Today ${deg(d.tMin)} to ${deg(d.tMax)} degrees, ${extras(d)}."
            ).replaceFirstChar { it.uppercase() }
            else -> t(lang,
                "${dayName.replaceFirstChar { it.uppercase() }} $where: ${cond(d.code)}, ${deg(d.tMin)} bis ${deg(d.tMax)} Grad, ${extras(d)}.",
                "${dayName.replaceFirstChar { it.uppercase() }} $where: ${cond(d.code)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees, ${extras(d)}."
            )
        }

        val lines = f.days.take(5).map { day ->
            val label = day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, if (en) Locale.ENGLISH else Locale.GERMAN)
            "$label  ${deg(day.tMin)}° / ${deg(day.tMax)}°  ·  ${cond(day.code)}"
        }
        val title = (if (p.name == "hier") t(lang, "Wetter", "Weather") else p.name) + " · ${deg(f.currentTemp)}°"
        return Reply(answer, lang, CardKind.WEATHER, title, lines, weather = f, weatherDay = dayOffset.coerceAtMost(f.days.size - 1))
    }

    // ================= Einkaufsliste =================

    private suspend fun addShopping(items: List<BringItem>, lang: String): Reply {
        if (!bring.isConfigured) {
            return Reply(
                t(lang, "Bring! ist noch nicht verbunden. Melde dich in den Einstellungen an.",
                    "Bring! isn't connected yet. Please sign in under settings."), lang
            )
        }
        bring.addItems(items)
        val list = settings.bringListName ?: "Bring!"
        return Reply(
            t(lang, "${joinNames(items, lang)} ${if (items.size == 1) "steht" else "stehen"} jetzt auf der Liste.",
                "Added ${joinNames(items, lang)} to your list."), lang,
            CardKind.SHOPPING, list, items.map { if (it.spec.isBlank()) it.name else "${it.name} · ${it.spec}" }
        )
    }

    private suspend fun readShopping(lang: String): Reply {
        if (!bring.isConfigured) return Reply(t(lang, "Bring! ist noch nicht verbunden.", "Bring! isn't connected yet."), lang)
        val items = bring.items()
        if (items.isEmpty()) return Reply(t(lang, "Die Einkaufsliste ist leer.", "Your shopping list is empty."), lang)
        return Reply(
            t(lang, "Auf der Liste: ${joinNames(items, lang)}.", "On your list: ${joinNames(items, lang)}."), lang,
            CardKind.SHOPPING, settings.bringListName ?: "Bring!",
            items.map { if (it.spec.isBlank()) it.name else "${it.name} · ${it.spec}" }
        )
    }

    private val fillerWords = setOf(
        // de
        "bitte", "noch", "mal", "auf", "die", "meine", "unsere", "zur", "zu", "der", "den", "einkaufsliste", "einkaufszettel",
        "liste", "bring", "bring!", "hinzu", "drauf", "dazu", "setz", "setze", "setzen", "schreib", "schreibe", "schreiben",
        "füg", "füge", "fügen", "pack", "packe", "tu", "gib", "kannst", "du", "könntest", "ich", "wir", "brauche", "brauchen",
        "kaufen", "kauf", "auch", "von", "nimm", "lösche", "lösch", "entferne", "entfern", "streiche", "streich", "runter",
        "hey", "aurion", "hallo", "mir", "uns", "einkaufen", "das", "dies", "diese", "dieses", "es", "alles", "hier",
        // en
        "add", "put", "please", "to", "on", "my", "our", "the", "shopping", "grocery", "list", "can", "you", "could", "i",
        "we", "need", "buy", "also", "remove", "delete", "take", "off", "from", "get", "some", "this", "that", "it", "these",
    )

    fun parseItems(text: String): List<BringItem> {
        val parts = text.replace(Regex("[.!?]"), " ")
            .split(Regex(",|;|\\s+und\\s+|\\s+and\\s+|\\s+sowie\\s+|\\s*&\\s*|\\s+plus\\s+", RegexOption.IGNORE_CASE))
        val unit = "(kg|g|gramm|l|liter|ml|stück|stk|packung|packungen|pkg|flaschen?|dosen?|becher|gläser|glas|bund|x|pieces?|packs?|bottles?|cans?)"
        return parts.mapNotNull { raw ->
            val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() && it.lowercase(Locale.ROOT) !in fillerWords }
            if (words.isEmpty()) return@mapNotNull null
            var phrase = words.joinToString(" ")
            var spec = ""
            Regex("^(\\d+(?:[.,]\\d+)?|ein|eine|einen|zwei|drei|vier|fünf|sechs|a|one|two|three|four|five|six)\\s*$unit?\\s+(.+)$", RegexOption.IGNORE_CASE)
                .find(phrase)?.let { m ->
                    spec = (m.groupValues[1] + " " + m.groupValues[2]).trim()
                    phrase = m.groupValues[3]
                    if (spec.lowercase() in setOf("ein", "eine", "einen", "a", "one")) spec = ""
                }
            val name = phrase.trim().replaceFirstChar { it.uppercase() }
            if (name.length < 2) null else BringItem(name, spec)
        }.distinctBy { it.name.lowercase() }
    }

    private fun joinNames(items: List<BringItem>, lang: String): String {
        val names = items.map { it.name }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + t(lang, " und ", " and ") + names.last()
        }
    }

    // ================= LLM =================

    private fun wantsScreen(l: String) = Regex(
        "bildschirm|screen|was ist das|was sind das|was steht (da|hier)|was siehst|was sehe ich|wer ist das|was bedeutet das|" +
            "erklär\\w* (mir )?(das|dies)|fass\\w* (das|dies|es)|zusammenfass|übersetz\\w* (das|dies|es)|lies (das|dies|vor)|" +
            "antworte (darauf|auf (die|diese) nachricht)|was meint (er|sie)|stimmt das|" +
            "what is this|what's this|what am i looking at|who is this|summari[sz]e (this|it)|translate (this|it)|explain (this|it)|read (this|it)|reply to this"
    ).containsMatchIn(l)

    private fun systemPrompt(lang: String): String {
        val now = LocalDateTime.now()
        val mem = store.memory.value
        val memText = if (mem.isEmpty()) "" else
            "\nDinge, die dir der Nutzer anvertraut hat (nutze sie, wenn passend):\n" + mem.joinToString("\n") { "- ${it.text}" }
        val languageRule = if (lang == "en") "Answer in English." else "Antworte auf Deutsch."
        val style = if (spoken) """
            Deine Antwort wird vorgelesen: antworte in höchstens drei kurzen Sätzen, ohne Markdown, ohne Listen, ohne Emojis.
        """.trimIndent() else """
            Die Antwort wird gelesen: antworte so ausführlich wie nötig und so knapp wie möglich.
            Strukturiere mit Markdown (Listen, **fett**, Codeblöcke mit ``` für Code), aber ohne unnötige Überschriften.
            Wenn du einen Text schreiben sollst (E-Mail, Nachricht, Brief, Zusammenfassung, Übersetzung), liefere direkt das fertige Ergebnis.
            Bei kniffligen Fragen denke Schritt für Schritt und nenne dann das Ergebnis.
        """.trimIndent()
        return """
            Du bist Aurion, ein privater KI-Assistent, der vollständig lokal auf einem Android-Smartphone läuft.
            Du bist hilfsbereit, ehrlich und präzise. Du kannst erklären, schreiben, übersetzen, zusammenfassen, beraten und programmieren.
            Erfinde keine Fakten. Wenn du etwas nicht sicher weißt oder Live-Daten bräuchtest (Nachrichten, Kurse), sag das ehrlich.
            $style
            Aktuelles Datum und Uhrzeit: ${now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN))}.
            $languageRule$memText
        """.trimIndent()
    }

    private suspend fun chat(text: String, lang: String, sc: ScreenInput? = null): Reply {
        if (!llm.isAvailable) {
            if (sc != null) {
                // Ohne Modell: zumindest zeigen, was erkannt wurde
                val parts = ArrayList<String>()
                if (sc.labels.isNotEmpty()) parts += t(lang, "Ich erkenne: ${sc.labels.joinToString(", ")}.", "I can see: ${sc.labels.joinToString(", ")}.")
                if (sc.text.isNotBlank()) parts += t(lang, "Text: ${sc.text.take(400)}", "Text: ${sc.text.take(400)}")
                parts += t(lang, "Für Erklärungen lade ein Sprachmodell in den Einstellungen.", "Download a language model in settings for explanations.")
                return Reply(parts.joinToString("\n"), lang)
            }
            return Reply(
                t(lang, "Für freie Fragen brauche ich noch mein Sprachmodell. Lade es in den Einstellungen herunter. Erinnerungen, Einkaufsliste, Wetter, Rechnen und Merken funktionieren schon.",
                    "I need my language model for open questions. Download it in settings. Reminders, shopping list, weather, maths and memory already work."), lang
            )
        }
        var system = systemPrompt(lang)
        var user = text
        var image: android.graphics.Bitmap? = null
        if (sc != null) {
            val what = if (sc.isSelection) t(lang, "einem Bereich, den der Nutzer auf dem Bildschirm markiert hat", "an area the user selected on screen")
            else t(lang, "dem aktuellen Bildschirm des Nutzers", "the user's current screen")
            val sb = StringBuilder()
            sb.append(t(lang, "Die Frage bezieht sich auf $what.", "The question refers to $what."))
            sc.app?.let { sb.append(t(lang, " App: $it.", " App: $it.")) }
            if (sc.labels.isNotEmpty()) sb.append(t(lang, "\nErkannte Objekte: ", "\nDetected objects: ")).append(sc.labels.joinToString(", "))
            if (sc.text.isNotBlank()) sb.append(t(lang, "\nErkannter Text:\n", "\nRecognized text:\n")).append(sc.text.take(2500))
            if (llm.supportsVision && sc.image != null) {
                image = sc.image
                sb.append(t(lang, "\nDas Bild ist beigefügt.", "\nThe image is attached."))
            }
            system += "\n\n" + sb.toString()
            if (user.length < 3) user = t(lang, "Was ist das?", "What is this?")
        }
        val history = if (sc != null) emptyList() else store.chat.value.dropLast(1).takeLast(8).map { Turn(it.fromUser, it.text) }
        val answer = llm.chat(system, history, user, image = image, onPartial = partial)
        return Reply(answer.ifBlank { t(lang, "Dazu fällt mir gerade nichts ein.", "I'm not sure what to say.") }, lang)
    }

    // ================= Geräteaktionen =================

    private suspend fun deviceAction(text: String, l: String, lang: String): Reply? {
        // Taschenlampe
        Regex("(taschenlampe|flashlight|torch)").find(l)?.let {
            val off = Regex("\\b(aus|ab|off|ausschalten|abdrehen)\\b").containsMatchIn(l)
            return if (device.torch(!off)) Reply(t(lang, if (off) "Taschenlampe aus." else "Taschenlampe an.", if (off) "Flashlight off." else "Flashlight on."), lang)
            else Reply(t(lang, "Die Taschenlampe ist nicht verfügbar.", "The flashlight isn't available."), lang)
        }
        // Wecker
        if (Regex("\\b(wecker|weck(e)? mich|alarm)\\b").containsMatchIn(l) && !Regex("erinner|remind").containsMatchIn(l)) {
            val dt = TimeParser.parse(text).dateTime
                ?: return Reply(t(lang, "Für wann soll ich den Wecker stellen?", "When should I set the alarm for?"), lang)
            device.alarm(dt.hour, dt.minute, "Aurion")
            return Reply(
                t(lang, "Wecker gestellt auf ${dt.format(DateTimeFormatter.ofPattern("H:mm"))} Uhr.", "Alarm set for ${dt.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))}."), lang
            )
        }
        // Anrufen (Nummer)
        Regex("^(ruf|wähl\\w*|call|dial)\\s+(die nummer\\s+|the number\\s+)?([+\\d][\\d /-]{4,})(\\s+an)?$").find(l)?.let { m ->
            device.dial(m.groupValues[3])
            return Reply(t(lang, "Ich öffne die Telefon-App.", "Opening the dialer."), lang)
        }
        // Navigation
        Regex("^(navigier\\w*|navigate|route|fahr\\w*|bring mich|take me|directions)\\s+(mich\\s+|me\\s+)?(nach|zu|zur|zum|to|in die|in den)\\s+(.+)$").find(l)?.let { m ->
            val dest = text.substring(text.length - m.groupValues[4].length).trim().trimEnd('.', '!', '?')
            device.navigate(dest)
            return Reply(t(lang, "Navigation nach $dest gestartet.", "Starting navigation to $dest."), lang)
        }
        // Kalender
        if (Regex("kalender|calendar|termin(?!e? ?erinner)").containsMatchIn(l) && Regex("trag|eintrag|leg|erstell|neuer|add|create|schedule|setz").containsMatchIn(l)) {
            val parsed = TimeParser.parse(text)
            val title = parsed.rest
                .replace(Regex("\\b(bitte|please|trag\\w*|ein|eintragen|leg\\w*|an|erstell\\w*|neuen?|termin|in (den|meinen) kalender|kalender|add|create|schedule|to my calendar|calendar|event|an event|a|in|setz\\w*)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim().trim(',', '.', ':').replaceFirstChar { it.uppercase() }
            val start = parsed.dateTime ?: LocalDateTime.now().plusHours(1).withMinute(0)
            device.calendarEvent(title.ifBlank { "Termin" }, start)
            return Reply(t(lang, "Ich habe den Termin im Kalender vorbereitet – bitte noch speichern.", "I've prepared the event in your calendar – just save it."), lang)
        }
        // Web-Suche (ausdrücklich gewünscht)
        Regex("^(such\\w*|google|search)\\s+(im (web|internet|netz)\\s+)?(nach\\s+|for\\s+|the web for\\s+)?(.+)$").find(l)?.let { m ->
            if (Regex("^(such|google|search)").containsMatchIn(l) && !Regex("einkaufsliste|erinnerung").containsMatchIn(l)) {
                val q = m.groupValues[5].trim()
                if (q == "das" || q == "this") {
                    screen()?.let { sc -> device.webSearch(sc.text.lines().firstOrNull { it.isNotBlank() } ?: sc.labels.joinToString(" ")) }
                } else device.webSearch(q)
                return Reply(t(lang, "Ich öffne die Suche.", "Opening search."), lang)
            }
        }
        // App öffnen
        Regex("^(öffne|starte|start|open|launch|geh zu|go to)\\s+(die\\s+|the\\s+)?(app\\s+)?(.+?)(\\s+app)?$").find(l)?.let { m ->
            val name = m.groupValues[4].trim().trimEnd('.', '!', '?')
            if (name.isNotBlank() && !Regex("einkaufsliste|erinnerung|wetter").containsMatchIn(name)) {
                val opened = device.openApp(name)
                return if (opened != null) Reply(t(lang, "Ich öffne $opened.", "Opening $opened."), lang)
                else Reply(t(lang, "Ich habe keine App namens „$name“ gefunden.", "I couldn't find an app called \"$name\"."), lang)
            }
        }
        return null
    }

    private fun readClipboard(): String? = runCatching {
        val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
        cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
    }.getOrNull()

    private suspend fun llmExtractReminder(text: String): Pair<LocalDateTime, String>? = runCatching {
        val now = LocalDateTime.now()
        val sys = """
            Extrahiere aus der Nachricht eine Erinnerung. Aktuell: ${now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).take(16)} (${now.dayOfWeek}).
            Antworte NUR mit JSON in der Form {"datetime":"YYYY-MM-DDTHH:MM","text":"woran erinnern"}.
            Wenn kein Zeitpunkt erkennbar ist: {"datetime":null,"text":"..."}
        """.trimIndent()
        val out = llm.chat(sys, emptyList(), text, temperature = 0.1f)
        val json = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
        val dt = json.optString("datetime", "")
        if (dt.isBlank() || dt == "null") return null
        LocalDateTime.parse(dt.take(16)) to cleanReminderText(json.optString("text", ""))
    }.getOrNull()

    private suspend fun llmExtractItems(text: String): List<BringItem> = runCatching {
        val sys = """
            Extrahiere die Einkaufsartikel aus der Nachricht. Antworte NUR mit einem JSON-Array,
            z. B. [{"name":"Milch","spec":"2 Liter"},{"name":"Eier","spec":""}]. Artikelnamen auf Deutsch, Großschreibung.
        """.trimIndent()
        val out = llm.chat(sys, emptyList(), text, temperature = 0.1f)
        val arr = JSONArray(out.substring(out.indexOf('['), out.lastIndexOf(']') + 1))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            BringItem(o.getString("name"), o.optString("spec", ""))
        }.filter { it.name.isNotBlank() }
    }.getOrDefault(emptyList())

    /** Häufige Tippfehler bei Befehlswörtern korrigieren, damit Befehle trotzdem erkannt werden */
    private fun normalize(t: String): String {
        var r = t
        val fixes = listOf(
            "\\be+r+i+n+e*r+(?=[a-zäöü]|\\b)" to "erinner",          // errinnere, erinere, erinnner …
            "\\bre+m+i+n+d" to "remind",                                // remmind, reminnd
            "\\beinkau?f?s?l[iue]?s?t?e\\b" to "einkaufsliste",         // einkausliste, einkaufslste
            "\\beinkaufsz[ae]t+el\\b" to "einkaufszettel",
            "\\bwet+er\\b" to "wetter",                                 // weter, wettter
            "\\bmerk+e?\\s+dir\\b" to "merk dir",
            "\\bvergis+\\b" to "vergiss",
        )
        for ((pat, rep) in fixes) {
            r = Regex(pat, RegexOption.IGNORE_CASE).replace(r) { m ->
                if (m.value.first().isUpperCase()) rep.replaceFirstChar { it.uppercase() } else rep
            }
        }
        return r
    }

    private fun isCancel(l: String) = Regex("^(abbrechen|abbruch|stopp?|nichts|vergiss es|cancel|never ?mind|nothing)\\b").containsMatchIn(l)

    private fun t(lang: String, de: String, en: String) = if (lang == "en") en else de

    companion object {
        @Volatile private var instance: Brain? = null
        fun get(ctx: Context): Brain = instance ?: synchronized(this) {
            instance ?: Brain(ctx.applicationContext).also { instance = it }
        }
    }
}
