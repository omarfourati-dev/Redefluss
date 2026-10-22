package de.omarfourati.redefluss.vocab

import de.omarfourati.redefluss.auth.UserPrincipal
import de.omarfourati.redefluss.conversation.readTurnForm
import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.vocabRoutes(service: VocabService) {
    authenticate("auth") {
        get("/api/vocab/today") { call.respond(service.today(call.principal<UserPrincipal>()!!)) }
        get("/api/vocab/due") { call.respond(service.due(call.principal<UserPrincipal>()!!)) }
        get("/api/vocab/cards/{id}/audio") {
            val audio = service.audio(call.principal<UserPrincipal>()!!, cardId(call.parameters["id"]))
            // Card text rarely changes; the browser may keep the audio for a day.
            call.response.headers.append(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respondBytes(audio.bytes, ContentType.parse(audio.contentType))
        }
        post("/api/vocab/cards/{id}/review") {
            val id = cardId(call.parameters["id"])
            val form = readTurnForm(call)
            call.respond(service.review(call.principal<UserPrincipal>()!!, id, ReviewRequest(form.fields["text"], form.audio, form.fields["skip"] == "true")))
        }
    }
}

private fun cardId(raw: String?): Long = raw?.toLongOrNull() ?: throw badRequest("Ungültige Karten-ID.")
