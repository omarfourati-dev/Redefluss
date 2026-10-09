package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.conversation.readTurnForm
import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.vocabRoutes(service: VocabService) {
    authenticate("auth") {
        get("/api/vocab/today") { call.respond(service.today()) }
        get("/api/vocab/due") { call.respond(service.due()) }
        get("/api/vocab/cards/{id}/audio") {
            val audio = service.audio(cardId(call.parameters["id"]))
            // Card text rarely changes; the browser may keep the audio for a day.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(audio.bytes, ContentType.parse(audio.contentType))
        }
        post("/api/vocab/cards/{id}/review") {
            val id = cardId(call.parameters["id"])
            val form = readTurnForm(call)
            call.respond(service.review(id, ReviewRequest(form.fields["text"], form.audio, form.fields["skip"] == "true")))
        }
    }
}

private fun cardId(raw: String?): Long = raw?.toLongOrNull() ?: throw badRequest("Ungültige Karten-ID.")
