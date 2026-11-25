package cc.szakacs.aurion.brain

/** Sehr leichte Spracherkennung Deutsch/Englisch anhand typischer Wörter. */
object Lang {
    private val de = setOf(
        "ich", "du", "und", "die", "der", "das", "mich", "mir", "auf", "um", "morgen", "heute", "bitte",
        "erinnere", "erinner", "ist", "nicht", "was", "wie", "ein", "eine", "einen", "mit", "zu", "für",
        "wann", "warum", "wer", "wo", "liste", "einkaufsliste", "uhr", "minuten", "stunden", "noch",
        "merk", "dir", "kannst", "bin", "habe", "hast", "sind", "auch", "aber", "oder", "dass", "schreib",
        "setz", "füge", "hallo", "servus", "danke", "guten", "abend", "wetter", "spät", "vergiss"
    )
    private val en = setOf(
        "the", "and", "to", "me", "my", "is", "what", "how", "remind", "please", "tomorrow", "today",
        "at", "on", "a", "an", "list", "shopping", "add", "put", "you", "your", "can", "i", "it", "of",
        "for", "with", "in", "minutes", "hours", "that", "remember", "this", "are", "do", "does", "hello",
        "hi", "thanks", "thank", "good", "evening", "morning", "weather", "time", "forget", "who", "why",
        "where", "when", "need"
    )

    fun detect(text: String, fallback: String = "de"): String {
        val words = text.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return fallback
        var d = 0
        var e = 0
        for (w in words) {
            if (w in de) d++
            if (w in en) e++
            if (w.any { it in "äöüß" }) d += 2
        }
        return when {
            d > e -> "de"
            e > d -> "en"
            else -> fallback
        }
    }
}
