package de.omarfourati.redefluss.pronunciation

import kotlinx.serialization.Serializable

/** source: mistake | vocab | sound */
@Serializable data class Exercise(val id: String, val source: String, val text: String, val hint: String)

/** Fixed sentences for sounds that are hard for German learners; ids sound-1 … sound-12. */
object Exercises {
    val SOUNDS: List<Exercise> = listOf(
        "Über die Brücke fahren fünf grüne Busse." to "ü/u",
        "Können Sie mir bitte das Öl holen?" to "ö/o",
        "Ich möchte nicht noch acht Nächte machen." to "ich-Laut/ach-Laut",
        "Rote Rosen riechen richtig gut." to "r am Anfang",
        "Der Lehrer hört immer besser zu." to "r am Ende, -er",
        "Die Mädchen lächeln, die Männer lachen." to "ä/a, ch",
        "Zwei zahme Ziegen ziehen zum Zaun." to "z = ts",
        "Schöne Schuhe schützen schwache Füße." to "sch, ü",
        "Wir wohnen in einer wunderschönen Wohnung." to "w = v",
        "Hast du heute Zeit für einen Kaffee?" to "Frage-Melodie",
        "Ich spreche jeden Tag ein bisschen besser Deutsch." to "sp/st = schp/scht",
        "Die Bewerbung habe ich gestern abgeschickt." to "Endungen -ung, -en, -t",
    ).mapIndexed { i, (text, hint) -> Exercise("sound-${i + 1}", "sound", text, hint) }

    /** The example sentence with the first case-insensitive occurrence of [wrong] replaced by [right]; null if [wrong] is not in it. */
    fun corrected(example: String, wrong: String, right: String): String? {
        if (wrong.isBlank()) return null
        val i = example.indexOf(wrong, ignoreCase = true)
        if (i < 0) return null
        return example.substring(0, i) + right + example.substring(i + wrong.length)
    }
}
