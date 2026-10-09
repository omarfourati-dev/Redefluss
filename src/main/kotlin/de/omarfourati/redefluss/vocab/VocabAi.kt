package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.speech.CoachReply
import de.omarfourati.redefluss.speech.Correction
import de.omarfourati.redefluss.speech.cleanReply
import io.ktor.client.*
import kotlinx.serialization.Serializable

val THEMES = listOf("it", "alltag", "redewendung")

@Serializable data class GeneratedWord(val word: String, val article: String, val plural: String, val meaning: String,
                                       val example: String, val theme: String)
@Serializable data class WordBatch(val words: List<GeneratedWord>)
@Serializable data class CheckResult(val grade: Int, val usedCorrectly: Boolean, val feedback: String,
                                     val corrections: List<Correction>, val better: String)

interface VocabGenerator { suspend fun generate(count: Int, avoid: List<String>, themes: List<String>): List<GeneratedWord> }
interface VocabChecker { suspend fun check(word: String, meaning: String, sentence: String): CheckResult }
data class VocabAi(val generator: VocabGenerator, val checker: VocabChecker)

fun vocabAiFor(config: Config, http: HttpClient): VocabAi = when (config.speech) {
    "fake" -> VocabAi(FakeVocabGenerator(), FakeVocabChecker())
    else -> VocabAi(OpenAiVocabGenerator(http, config.openAiKey!!, config.aiModel), OpenAiVocabChecker(http, config.openAiKey, config.aiModel))
}

private val ARTICLES = setOf("der", "die", "das", "")
private val LEADING_ARTICLE = Regex("""^(der|die|das)\s+""", RegexOption.IGNORE_CASE)

fun cleanWords(raw: List<GeneratedWord>): List<GeneratedWord> {
    val seen = HashSet<String>()
    return raw.mapNotNull { w ->
        val word = w.word.trim().replace(LEADING_ARTICLE, "").trim()
        val key = VocabRepo.key(word)
        if (key.isEmpty() || w.meaning.isBlank() || w.example.isBlank() || !seen.add(key)) return@mapNotNull null
        GeneratedWord(word, w.article.trim().lowercase().takeIf { it in ARTICLES } ?: "", w.plural.trim(),
            w.meaning.trim(), w.example.trim(), w.theme.trim().lowercase().takeIf { it in THEMES } ?: "alltag")
    }
}

fun cleanCheck(raw: CheckResult): CheckResult = CheckResult(
    grade = raw.grade.coerceIn(0, 5),
    usedCorrectly = raw.usedCorrectly,
    feedback = raw.feedback.trim(),
    corrections = cleanReply(CoachReply(raw.corrections, "x", "x")).corrections.take(4),
    better = raw.better.trim(),
)
