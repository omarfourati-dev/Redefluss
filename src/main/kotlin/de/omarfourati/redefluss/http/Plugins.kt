package de.omarfourati.redefluss.http

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.application.hooks.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.forwardedheaders.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.util.*
import kotlinx.serialization.json.Json

val AppJson = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

/** scriptHashes: sha256 hashes of the SPA's inline bootstrap script (see StaticFiles.kt). */
fun Application.installHttpBasics(log: (String) -> Unit, scriptHashes: List<String> = emptyList()) {
    install(ContentNegotiation) { json(AppJson) }
    // Behind Caddy: Caddy appends the real client IP as the last X-Forwarded-For entry; earlier entries are client-controlled.
    install(XForwardedHeaders) { useLastProxy() }

    val scriptSrc = (listOf("'self'") + scriptHashes.map { "'sha256-$it'" }).joinToString(" ")
    val csp = "default-src 'self'; script-src $scriptSrc; img-src 'self' data: blob:; media-src 'self' blob: data:; " +
        "style-src 'self' 'unsafe-inline'; connect-src 'self' https://api.openai.com; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            call.response.headers.append("Content-Security-Policy", csp)
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
            call.response.headers.append("Permissions-Policy", "microphone=(self), camera=(), geolocation=()")
        }
    })

    val started = AttributeKey<Long>("started")
    install(createApplicationPlugin("RequestLog") {
        onCall { call -> call.attributes.put(started, System.nanoTime()) }
        on(ResponseSent) { call ->
            val path = call.request.path() // never the query string
            if (path == "/healthz" || path == "/metrics") return@on
            val ms = (System.nanoTime() - call.attributes[started]) / 1_000_000
            log("${call.request.httpMethod.value} $path ${call.response.status()?.value} ${ms}ms")
        }
    })

    install(StatusPages) {
        exception<ApiException> { call, e -> call.respondProblem(e.status, e.title, e.detail) }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(HttpStatusCode.BadRequest, "Bad Request", "Die Anfrage ist ungültig.")
        }
        exception<Throwable> { call, e ->
            log("error ${call.request.httpMethod.value} ${call.request.path()} ${e::class.simpleName}")
            call.respondProblem(HttpStatusCode.InternalServerError, "Internal Server Error", "Etwas ist schiefgelaufen.")
        }
    }
}
