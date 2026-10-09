package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.speech.UpstreamException
import de.omarfourati.redefluss.speech.okBytes
import de.omarfourati.redefluss.speech.upstream
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.*
import java.util.Base64

class AzureScorer(private val http: HttpClient, private val key: String, private val region: String,
                  private val timeoutMs: Long = 15_000) : PronunciationScorer {

    /** Raw outcome of the HTTP call; typed failures are thrown outside upstream(), which would flatten them into UpstreamException. */
    private class Raw(val quota: Boolean, val body: ByteArray)

    override suspend fun assess(wav: ByteArray, reference: String): Assessment {
        val config = buildJsonObject {
            put("ReferenceText", reference)
            put("GradingSystem", "HundredMark")
            put("Granularity", "Phoneme")
            put("Dimension", "Comprehensive")
            put("EnableMiscue", true)
        }.toString()
        val raw = upstream("azure", timeoutMs) {
            val res = http.post("https://$region.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=de-DE&format=detailed") {
                header("Ocp-Apim-Subscription-Key", key)
                header(HttpHeaders.Accept, "application/json")
                header("Pronunciation-Assessment", Base64.getEncoder().encodeToString(config.toByteArray(Charsets.UTF_8)))
                setBody(RawTypeContent(wav, WAV_CONTENT_TYPE))
            }
            if (res.status == HttpStatusCode.TooManyRequests || res.status == HttpStatusCode.Forbidden) Raw(true, ByteArray(0))
            else Raw(false, res.okBytes("azure", 2_000_000))
        }
        if (raw.quota) throw QuotaExceededException()
        return try {
            parseAzure(String(raw.body, Charsets.UTF_8))
        } catch (e: NothingRecognizedException) {
            throw e
        } catch (e: Exception) {
            throw UpstreamException("azure", timeout = false)
        }
    }

}

internal const val WAV_CONTENT_TYPE = "audio/wav; codecs=audio/pcm; samplerate=16000"

/** Ktor would render the codecs parameter quoted (codecs="audio/pcm"), which Azure documents unquoted, so the header string is passed through verbatim. */
internal class RawTypeContent(private val bytes: ByteArray, type: String) : OutgoingContent.ByteArrayContent() {
    override val contentType: ContentType? = null
    override val contentLength: Long = bytes.size.toLong()
    override val headers: Headers = headersOf(HttpHeaders.ContentType, type)
    override fun bytes(): ByteArray = bytes
}
