package de.omarfourati.redefluss.pronunciation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import kotlin.math.floor

@Serializable data class PhonemeScore(val phoneme: String, val score: Double)
@Serializable data class WordScore(val word: String, val score: Double, val errorType: String, val phonemes: List<PhonemeScore>)
@Serializable data class Assessment(val recognized: String, val accuracy: Double, val fluency: Double, val completeness: Double,
                                    val pronunciation: Double, val words: List<WordScore>)

class NothingRecognizedException : RuntimeException("nothing recognized")
class QuotaExceededException : RuntimeException("azure quota")

interface PronunciationScorer { suspend fun assess(wav: ByteArray, reference: String): Assessment }

internal fun round1(v: Double): Double = floor(v * 10 + 0.5) / 10

/** Azure puts the scores either under a nested PronunciationAssessment object or directly on the object, depending on the endpoint version. */
private fun JsonObject.score(name: String): Double? =
    ((this["PronunciationAssessment"] as? JsonObject)?.get(name) ?: this[name])?.jsonPrimitive?.doubleOrNull

private fun JsonObject.errorType(): String =
    ((this["PronunciationAssessment"] as? JsonObject)?.get("ErrorType") ?: this["ErrorType"])?.jsonPrimitive?.contentOrNull ?: "None"

/** Throws NothingRecognizedException for NoMatch / InitialSilenceTimeout / empty NBest; any other malformed input throws a parsing exception. */
fun parseAzure(json: String): Assessment {
    val root = Json.parseToJsonElement(json).jsonObject
    val status = root["RecognitionStatus"]?.jsonPrimitive?.contentOrNull
    if (status != null && status != "Success") throw NothingRecognizedException()
    val best = (root["NBest"] as? JsonArray)?.firstOrNull()?.jsonObject ?: throw NothingRecognizedException()
    val recognized = (best["Display"] ?: best["Lexical"])?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val words = (best["Words"] as? JsonArray).orEmpty().map { w ->
        val o = w.jsonObject
        WordScore(
            word = o["Word"]!!.jsonPrimitive.content,
            score = round1(o.score("AccuracyScore") ?: 0.0),
            errorType = o.errorType(),
            phonemes = (o["Phonemes"] as? JsonArray).orEmpty().map { p ->
                val po = p.jsonObject
                PhonemeScore(po["Phoneme"]!!.jsonPrimitive.content, round1(po.score("AccuracyScore") ?: 0.0))
            },
        )
    }
    return Assessment(
        recognized = recognized,
        accuracy = round1(best.score("AccuracyScore") ?: 0.0),
        fluency = round1(best.score("FluencyScore") ?: 0.0),
        completeness = round1(best.score("CompletenessScore") ?: 0.0),
        pronunciation = round1(best.score("PronScore") ?: 0.0),
        words = words,
    )
}
