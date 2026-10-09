package de.omarfourati.redefluss.conversation

import de.omarfourati.redefluss.http.ApiException
import de.omarfourati.redefluss.speech.Audio
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray

/** A spoken or typed sentence as multipart form: text fields plus an optional "audio" file. */
class TurnForm(val fields: Map<String, String>, val audio: Audio?)

/** Shared by /api/turns and the vocab review: same audio types, 415 for others, 413 above 10 MB. */
suspend fun readTurnForm(call: ApplicationCall): TurnForm {
    val fields = HashMap<String, String>()
    var audio: Audio? = null
    call.receiveMultipart(formFieldLimit = MAX_AUDIO.toLong() + 1).forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> fields[part.name ?: ""] = part.value
                is PartData.FileItem -> if (part.name == "audio") {
                    val type = part.contentType?.withoutParameters()?.toString()?.lowercase() ?: ""
                    if (type !in AUDIO_TYPES) throw ApiException(HttpStatusCode.UnsupportedMediaType, "Unsupported Media Type", "Dieses Audioformat wird nicht unterstützt.")
                    val bytes = part.provider().readBuffer(MAX_AUDIO.toLong() + 1).readByteArray()
                    if (bytes.size > MAX_AUDIO) throw ApiException(HttpStatusCode.PayloadTooLarge, "Content Too Large", "Die Aufnahme ist zu groß (höchstens 10 MB).")
                    audio = Audio(bytes, part.contentType.toString())
                }
                else -> {}
            }
        } finally { part.release() }
    }
    return TurnForm(fields, audio)
}
