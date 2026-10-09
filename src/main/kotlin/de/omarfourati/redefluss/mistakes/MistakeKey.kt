package de.omarfourati.redefluss.mistakes

import java.util.Locale

/** Groups the same mistake across sessions: category + normalized wrong/right text. */
object MistakeKey {
    val CATEGORIES = setOf("artikel", "kasus", "verbstellung", "konjugation", "schreibung", "praeposition", "wortwahl", "aussprache", "sonstiges")
    private val EDGE = Regex("""^[\s.,!?;:"'„“‚‘»«()\-–]+|[\s.,!?;:"'„“‚‘»«()\-–]+$""")
    private val SPACE = Regex("""\s+""")

    fun category(raw: String?): String = raw?.trim()?.lowercase(Locale.GERMAN)?.takeIf { it in CATEGORIES } ?: "sonstiges"

    fun normalize(category: String, wrong: String, right: String): String =
        "${category(category)}|${text(wrong)}|${text(right)}"

    private fun text(s: String) = s.replace(EDGE, "").replace(SPACE, " ").lowercase(Locale.GERMAN)
}
