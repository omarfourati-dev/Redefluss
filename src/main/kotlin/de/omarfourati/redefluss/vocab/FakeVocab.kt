package de.omarfourati.redefluss.vocab

/** SPEECH=fake: fixed word list and a simple "does the sentence contain the word" check. */
class FakeVocabGenerator : VocabGenerator {
    private val pool = listOf(
        GeneratedWord("Kündigungsfrist", "die", "die Kündigungsfristen", "Zeit bis ein Vertrag endet", "Meine Kündigungsfrist beträgt drei Monate.", "it"),
        GeneratedWord("Ansprechpartner", "der", "die Ansprechpartner", "Person, an die man sich wendet", "Wer ist mein Ansprechpartner im Team?", "it"),
        GeneratedWord("Feierabend", "der", "die Feierabende", "Ende des Arbeitstags", "Nach dem Feierabend gehe ich joggen.", "alltag"),
        GeneratedWord("etwas in Angriff nehmen", "", "", "mit etwas beginnen", "Morgen nehme ich das Projekt in Angriff.", "redewendung"),
        GeneratedWord("Gehaltsvorstellung", "die", "die Gehaltsvorstellungen", "gewünschtes Gehalt", "Was ist Ihre Gehaltsvorstellung?", "it"),
        GeneratedWord("Termin", "der", "die Termine", "verabredete Zeit", "Ich habe morgen einen Termin beim Arzt.", "alltag"),
        GeneratedWord("auf dem Laufenden halten", "", "", "regelmäßig informieren", "Bitte halte mich auf dem Laufenden.", "redewendung"),
        GeneratedWord("Einarbeitung", "die", "die Einarbeitungen", "Zeit, in der man eine neue Arbeit lernt", "Die Einarbeitung dauert zwei Wochen.", "it"),
        GeneratedWord("Nebenkosten", "die", "die Nebenkosten", "Kosten zusätzlich zur Miete", "Die Nebenkosten sind im Winter höher.", "alltag"),
        GeneratedWord("unter Zeitdruck", "", "", "mit wenig Zeit", "Ich arbeite gut unter Zeitdruck.", "redewendung"),
    )
    override suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> {
        val avoided = avoid.map(VocabRepo::key).toSet()
        return pool.filter { VocabRepo.key(it.word) !in avoided }.take(count)
    }
}

class FakeVocabChecker : VocabChecker {
    override suspend fun check(word: String, meaning: String, sentence: String): CheckResult {
        val core = VocabRepo.key(word).split(" ").maxBy { it.length }
        return if (sentence.lowercase().contains(core))
            CheckResult(4, true, "Gut – „$word“ passt hier.", emptyList(), sentence.trim())
        else
            CheckResult(2, false, "Das Wort „$word“ fehlt in deinem Satz. Versuch es noch einmal damit.", emptyList(), sentence.trim())
    }
}
