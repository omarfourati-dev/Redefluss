package de.omarfourati.redefluss.invite

import de.omarfourati.redefluss.auth.UserPrincipal
import de.omarfourati.redefluss.http.badRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class CreateInvitationRequest(val email: String = "")
@Serializable data class TokenRequest(val token: String = "")
@Serializable data class AcceptRequest(val token: String = "", val password: String = "")
@Serializable data class PreviewResponse(val email: String)

fun Route.invitationRoutes(service: InvitationService) {
    // Public: the invited person has no account yet. The token travels in the body (never in a URL or a log line).
    post("/api/invitations/preview") {
        val req = runCatching { call.receive<TokenRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
        call.respond(PreviewResponse(service.preview(req.token, call.request.origin.remoteHost)))
    }
    post("/api/invitations/accept") {
        val req = runCatching { call.receive<AcceptRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
        call.respond(HttpStatusCode.Created, service.accept(req.token, req.password, call.request.origin.remoteHost))
    }
    authenticate("auth") {
        get("/api/invitations") { call.respond(service.list(call.principal<UserPrincipal>()!!)) }
        post("/api/invitations") {
            val req = runCatching { call.receive<CreateInvitationRequest>() }.getOrElse { throw badRequest("Die Anfrage ist ungültig.") }
            call.respond(HttpStatusCode.Created, service.create(call.principal<UserPrincipal>()!!, req.email))
        }
        delete("/api/invitations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw badRequest("Ungültige Einladungs-ID.")
            service.revoke(call.principal<UserPrincipal>()!!, id)
            call.respond(HttpStatusCode.NoContent)
        }
        get("/api/users") { call.respond(service.accounts(call.principal<UserPrincipal>()!!)) }
        delete("/api/users/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw badRequest("Ungültige Konto-ID.")
            service.removeGuest(call.principal<UserPrincipal>()!!, id)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
