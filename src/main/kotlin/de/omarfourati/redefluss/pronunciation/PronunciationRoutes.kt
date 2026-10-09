package de.omarfourati.redefluss.pronunciation

import de.omarfourati.redefluss.conversation.readTurnForm
import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class SpeakRequest(val text: String = "", val slow: Boolean = false)

fun Route.pronunciationRoutes(service: PronunciationService) {
    authenticate("auth") {
        get("/api/pronunciation/exercises") { call.respond(service.exercises(call.request.queryParameters["card"]?.toLongOrNull())) }
        get("/api/pronunciation/quota") { call.respond(service.quota()) }
        post("/api/pronunciation/speak") {
            service.requireEnabled()
            val req = runCatching { call.receive<SpeakRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
            val audio = service.speak(req.text, req.slow)
            call.respondBytes(audio.bytes, ContentType.parse(audio.contentType))
        }
        post("/api/pronunciation/assess") {
            service.requireEnabled() // before reading up to 1 MB of audio
            // Any part type: the WAV header decides (415 for everything else), so bad audio is counted in one place.
            val form = readTurnForm(call, maxAudio = MAX_WAV, types = null)
            call.respond(service.assess(form.fields["text"], form.audio))
        }
    }
}
