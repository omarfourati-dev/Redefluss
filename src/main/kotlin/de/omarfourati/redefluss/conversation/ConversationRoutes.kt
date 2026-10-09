package de.omarfourati.redefluss.conversation

import de.omarfourati.redefluss.http.AppJson
import de.omarfourati.redefluss.http.badRequest
import de.omarfourati.redefluss.speech.HistoryTurn
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

private val TurnJson = Json { explicitNulls = true; encodeDefaults = true }

@Serializable data class StartRequest(val topic: String? = null)
@Serializable data class StartResponse(val id: String)

fun Route.conversationRoutes(service: ConversationService) {
    authenticate("auth") {
        post("/api/sessions") {
            val req = runCatching { call.receive<StartRequest>() }.getOrDefault(StartRequest())
            call.respond(HttpStatusCode.Created, StartResponse(service.startSession(req.topic).toString()))
        }
        post("/api/turns") {
            val form = readTurnForm(call)
            val fields = form.fields
            val id = fields["sessionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw badRequest("sessionId fehlt oder ist ungültig.")
            val history = fields["history"]?.let {
                runCatching { AppJson.decodeFromString(ListSerializer(HistoryTurn.serializer()), it) }.getOrElse { throw badRequest("history ist kein gültiges JSON.") }
            } ?: emptyList()
            val response = service.turn(TurnRequest(id, fields["text"], form.audio, history, fields["speak"] != "false"))
            // AppJson omits nulls; the client expects replyAudio/replyAudioType to be present (null when voice failed or was off).
            call.respondText(TurnJson.encodeToString(TurnResponse.serializer(), response), ContentType.Application.Json)
        }
    }
}
