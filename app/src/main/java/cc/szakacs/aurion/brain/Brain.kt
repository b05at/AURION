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

enum class CardKind { REMINDER, SHOPPING, MEMORY, WEATHER, NONE }

data class Reply(
    val text: String,
    val lang: String,
    val card: CardKind = CardKind.NONE,
    val cardTitle: String? = null,
    val cardLines: List<String> = emptyList(),
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

    suspend fun handle(input: String): Reply {
        val text = input.trim()
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
        if (Regex("wetter|regnen|regnet|\\bregen\\b|schneien|schneit|\\bschnee\\b|gewitter|wie warm|wie kalt|grad hat es|temperatur|regenschirm|\\bschirm\\b|sonnig|vorhersage|weather|\\brain\\b|raining|\\bsnow|temperature|how warm|how cold|umbrella|forecast|sunny").containsMatchIn(l)
            && !Regex("erinner|remind").containsMatchIn(l)
        ) {
            return weather(text, l, lang)
        }

        // --- Erinnerungen auflisten ---
        if (Regex("(welche|meine|offene|zeig\\w*|liste?)\\s+(\\w+\\s+)?erinnerungen|what reminders|my reminders|list (my )?reminders|show (my )?reminders").containsMatchIn(l)) {
            return listReminders(lang)
        }

        // --- Erinnerung anlegen ---
        if (Regex("\\b(erinner\\w*|remind\\w*|reminder|wecke? mich)\\b").containsMatchIn(l)) {
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

        // --- Freies Gespräch mit dem lokalen LLM ---
        return chat(text, lang)
    }

    // ================= Erinnerungen =================

    private suspend fun reminderIntent(text: String, lang: String): Reply {
        val parsed = TimeParser.parse(text)
        val body = cleanReminderText(parsed.rest)
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

        val askRain = Regex("regnen|regnet|\\bregen\\b|schirm|\\brain\\b|raining|umbrella|nass|wet").containsMatchIn(l)
        val askSnow = Regex("schneien|schneit|\\bschnee\\b|\\bsnow").containsMatchIn(l)
        val askTemp = Regex("wie warm|wie kalt|grad|temperatur|how warm|how cold|temperature").containsMatchIn(l)

        val answer = when {
            askRain -> {
                val wet = WeatherService.isWet(d.code) || d.rainProb >= 50
                if (wet) t(lang, "Ja, $dayName $where liegt die Regenwahrscheinlichkeit bei ${d.rainProb} %, etwa ${"%.1f".format(d.rainSum)} mm. Nimm lieber einen Schirm mit.",
                    "Yes, $dayName $where there's a ${d.rainProb}% chance of rain, about ${"%.1f".format(Locale.ENGLISH, d.rainSum)} mm. Better take an umbrella.")
                else if (d.rainProb >= 25) t(lang, "Eher nicht, aber $dayName gibt es $where eine Regenwahrscheinlichkeit von ${d.rainProb} %.",
                    "Probably not, but $dayName there's a ${d.rainProb}% chance of rain $where.")
                else t(lang, "Nein, $dayName bleibt es $where trocken – ${WeatherService.describe(d.code, false)}.",
                    "No, $dayName it should stay dry $where – ${WeatherService.describe(d.code, true)}.")
            }
            askSnow -> {
                if (WeatherService.isSnow(d.code)) t(lang, "Ja, $dayName ist $where ${WeatherService.describe(d.code, false)} angesagt, bei ${deg(d.tMin)} bis ${deg(d.tMax)} Grad.",
                    "Yes, $dayName $where expect ${WeatherService.describe(d.code, true)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees.")
                else t(lang, "Nein, Schnee ist $dayName $where nicht angesagt. Es wird ${WeatherService.describe(d.code, false)} bei ${deg(d.tMin)} bis ${deg(d.tMax)} Grad.",
                    "No snow expected $dayName $where. It'll be ${WeatherService.describe(d.code, true)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees.")
            }
            dayOffset == 0 -> t(lang,
                "$where hat es gerade ${deg(f.currentTemp)} Grad, ${WeatherService.describe(f.currentCode, false)}. Heute ${deg(d.tMin)} bis ${deg(d.tMax)} Grad" +
                    (if (d.rainProb >= 30) ", Regenwahrscheinlichkeit ${d.rainProb} %." else "."),
                "It's currently ${deg(f.currentTemp)} degrees $where, ${WeatherService.describe(f.currentCode, true)}. Today ${deg(d.tMin)} to ${deg(d.tMax)} degrees" +
                    (if (d.rainProb >= 30) ", ${d.rainProb}% chance of rain." else ".")
            ).replaceFirstChar { it.uppercase() }
            else -> t(lang,
                "${dayName.replaceFirstChar { it.uppercase() }} $where: ${WeatherService.describe(d.code, false)}, ${deg(d.tMin)} bis ${deg(d.tMax)} Grad" +
                    (if (d.rainProb >= 30) ", Regenwahrscheinlichkeit ${d.rainProb} %." else "."),
                "${dayName.replaceFirstChar { it.uppercase() }} $where: ${WeatherService.describe(d.code, true)}, ${deg(d.tMin)} to ${deg(d.tMax)} degrees" +
                    (if (d.rainProb >= 30) ", ${d.rainProb}% chance of rain." else ".")
            )
        }

        val lines = f.days.take(5).map { day ->
            val label = day.date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, if (en) Locale.ENGLISH else Locale.GERMAN)
            "$label  ${deg(day.tMin)}° / ${deg(day.tMax)}°  ·  ${WeatherService.describe(day.code, en)}" +
                (if (day.rainProb >= 20) "  ·  ${day.rainProb} %" else "")
        }
        val title = (if (p.name == "hier") t(lang, "Wetter", "Weather") else p.name) + " · ${deg(f.currentTemp)}°"
        return Reply(answer, lang, CardKind.WEATHER, title, lines)
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
        "hey", "aurion", "hallo", "mir", "uns", "einkaufen",
        // en
        "add", "put", "please", "to", "on", "my", "our", "the", "shopping", "grocery", "list", "can", "you", "could", "i",
        "we", "need", "buy", "also", "remove", "delete", "take", "off", "from", "get", "some",
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

    private fun systemPrompt(lang: String): String {
        val now = LocalDateTime.now()
        val mem = store.memory.value
        val memText = if (mem.isEmpty()) "" else
            "\nDinge, die dir der Nutzer anvertraut hat (nutze sie, wenn passend):\n" + mem.joinToString("\n") { "- ${it.text}" }
        val languageRule = if (lang == "en") "Answer in English." else "Antworte auf Deutsch."
        return """
            Du bist Aurion, ein privater Sprachassistent, der vollständig lokal auf einem Android-Smartphone läuft.
            Du bist freundlich, präzise und kommst schnell auf den Punkt. Deine Antworten werden vorgelesen:
            antworte in höchstens drei kurzen Sätzen, ohne Markdown, ohne Listen, ohne Emojis.
            Erfinde keine Fakten; wenn du etwas nicht weißt (z. B. Live-Daten wie Nachrichten), sag es ehrlich.
            Aktuelles Datum und Uhrzeit: ${now.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy, HH:mm", Locale.GERMAN))}.
            $languageRule$memText
        """.trimIndent()
    }

    private suspend fun chat(text: String, lang: String): Reply {
        if (!llm.isAvailable) {
            return Reply(
                t(lang, "Für freie Fragen brauche ich noch mein Sprachmodell. Lade es in den Einstellungen herunter. Erinnerungen, Einkaufsliste und Merken funktionieren schon.",
                    "I need my language model for open questions. Download it in settings. Reminders, shopping list and memory already work."), lang
            )
        }
        val history = store.chat.value.dropLast(1).takeLast(8).map { Turn(it.fromUser, it.text) }
        val answer = llm.chat(systemPrompt(lang), history, text)
        return Reply(answer.ifBlank { t(lang, "Dazu fällt mir gerade nichts ein.", "I'm not sure what to say.") }, lang)
    }

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

    private fun isCancel(l: String) = Regex("^(abbrechen|abbruch|stopp?|nichts|vergiss es|cancel|never ?mind|nothing)\\b").containsMatchIn(l)

    private fun t(lang: String, de: String, en: String) = if (lang == "en") en else de

    companion object {
        @Volatile private var instance: Brain? = null
        fun get(ctx: Context): Brain = instance ?: synchronized(this) {
            instance ?: Brain(ctx.applicationContext).also { instance = it }
        }
    }
}
