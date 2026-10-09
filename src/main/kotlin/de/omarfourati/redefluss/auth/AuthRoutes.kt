package de.omarfourati.redefluss.auth

import de.omarfourati.redefluss.http.respondProblem
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable data class LoginRequest(val email: String, val password: String)
@Serializable data class PasswordRequest(val current: String, val next: String)
@Serializable data class MeResponse(val email: String)

fun Application.installAuth(auth: AuthService) {
    install(Authentication) {
        jwt("auth") {
            verifier(auth.tokens.verifier)
            validate { cred ->
                val id = cred.payload.subject?.toLongOrNull() ?: return@validate null
                val tv = cred.payload.getClaim("tv").asInt() ?: return@validate null
                auth.principalFor(id, tv)
            }
            challenge { _, _ -> call.respondProblem(HttpStatusCode.Unauthorized, "Unauthorized", "Bitte melde dich an.") }
        }
    }
}

fun Route.authRoutes(auth: AuthService) {
    post("/api/auth/login") {
        val req = call.receive<LoginRequest>()
        call.respond(auth.login(req.email, req.password, call.request.origin.remoteHost))
    }
    authenticate("auth") {
        get("/api/auth/me") { call.respond(MeResponse(call.principal<UserPrincipal>()!!.email)) }
        post("/api/auth/password") {
            val req = call.receive<PasswordRequest>()
            call.respond(auth.changePassword(call.principal<UserPrincipal>()!!.id, req.current, req.next))
        }
    }
}
