package de.omarfourati.redefluss.http

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Problem(val type: String = "about:blank", val title: String, val status: Int, val detail: String? = null)

/** Thrown anywhere in a handler; StatusPages turns it into a Problem response. */
class ApiException(val status: HttpStatusCode, val title: String, val detail: String) : RuntimeException(detail)

val ProblemJson = ContentType.parse("application/problem+json")

suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, title: String, detail: String? = null) {
    respondText(Json.encodeToString(Problem(title = title, status = status.value, detail = detail)), ProblemJson, status)
}

fun badRequest(detail: String) = ApiException(HttpStatusCode.BadRequest, "Bad Request", detail)
fun notFound(detail: String) = ApiException(HttpStatusCode.NotFound, "Not Found", detail)
