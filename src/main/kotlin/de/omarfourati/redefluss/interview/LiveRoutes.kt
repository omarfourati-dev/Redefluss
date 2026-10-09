package de.omarfourati.redefluss.interview

import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.UUID

/** Explicit nulls: the client checks clientSecret/expiresAt === null for fake mode. */
private val StartJson = Json { explicitNulls = true; encodeDefaults = true }

fun Route.liveRoutes(service: LiveService) {
    authenticate("auth") {
        post("/api/live/session") {
            val req = runCatching { call.receive<StartRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
            val response = service.start(req)
            call.respondText(StartJson.encodeToString(StartResponse.serializer(), response), ContentType.Application.Json)
        }
        post("/api/live/cancel") {
            val req = runCatching { call.receive<CancelRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
            val id = runCatching { UUID.fromString(req.sessionId) }.getOrNull() ?: throw badRequest("sessionId fehlt oder ist ungültig.")
            service.cancel(id)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/api/live/report") {
            val req = runCatching { call.receive<ReportRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
            val id = runCatching { UUID.fromString(req.sessionId) }.getOrNull() ?: throw badRequest("sessionId fehlt oder ist ungültig.")
            call.respond(service.report(id, req.seconds, req.transcript))
        }
    }
}
