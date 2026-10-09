package de.omarfourati.redefluss.conversation

import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.speech.Audio
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import java.io.IOException

/** A spoken or typed sentence as multipart form: text fields plus an optional "audio" file. */
class TurnForm(val fields: Map<String, String>, val audio: Audio?)

/**
 * Shared by /api/turns, the vocab review and the pronunciation assessment: 415 for audio types outside [types]
 * (null = any type, the caller judges the bytes), 413 above [maxAudio] bytes (default 10 MB).
 */
suspend fun readTurnForm(call: ApplicationCall, maxAudio: Int = MAX_AUDIO, types: Set<String>? = AUDIO_TYPES): TurnForm {
    val tooLarge = ApiException(HttpStatusCode.PayloadTooLarge, "Content Too Large", "Die Aufnahme ist zu groß (höchstens ${maxAudio / (1024 * 1024)} MB).")
    // Cheap early exit; multipart overhead and text fields get 64 KB on top of the audio.
    if ((call.request.contentLength() ?: 0L) > maxAudio.toLong() + FORM_SLACK) throw tooLarge
    val fields = HashMap<String, String>()
    var audio: Audio? = null
    try {
        // Ktor checks the whole body against formFieldLimit; the audio part itself is capped at maxAudio below.
        call.receiveMultipart(formFieldLimit = maxAudio.toLong() + FORM_SLACK).forEachPart { part ->
            try {
                when (part) {
                    is PartData.FormItem -> fields[part.name ?: ""] = part.value
                    is PartData.FileItem -> if (part.name == "audio") {
                        val type = part.contentType?.withoutParameters()?.toString()?.lowercase() ?: ""
                        if (types != null && type !in types) throw ApiException(HttpStatusCode.UnsupportedMediaType, "Unsupported Media Type", "Dieses Audioformat wird nicht unterstützt.")
                        val bytes = part.provider().readBuffer(maxAudio.toLong() + 1).readByteArray()
                        if (bytes.size > maxAudio) throw tooLarge
                        audio = Audio(bytes, part.contentType.toString())
                    }
                    else -> {}
                }
            } finally { part.release() }
        }
    } catch (e: IOException) {
        // Ktor's formFieldLimit check (a body without Content-Length can still get there) → 413 instead of 500.
        if (e.message?.contains("exceeds limit") == true) throw tooLarge
        throw e
    }
    return TurnForm(fields, audio)
}

private const val FORM_SLACK = 64 * 1024
