package de.omarfourati.redefluss.overview

import de.omarfourati.redefluss.db.MistakeStatus
import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Route.overviewRoutes(service: OverviewService) {
    authenticate("auth") {
        get("/api/overview") { call.respond(service.overview()) }
        get("/api/mistakes") {
            val status = when (call.request.queryParameters["status"] ?: "open") {
                "open" -> MistakeStatus.OPEN; "resolved" -> MistakeStatus.RESOLVED; "all" -> MistakeStatus.ALL
                else -> throw badRequest("status muss open, resolved oder all sein.")
            }
            call.respond(service.mistakes(status))
        }
        patch("/api/mistakes/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw badRequest("Ungültige Fehler-ID.")
            service.resolve(id, call.receive<ResolveRequest>().resolved)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
