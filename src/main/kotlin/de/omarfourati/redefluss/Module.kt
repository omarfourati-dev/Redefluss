package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.authRoutes
import de.omarfourati.redefluss.auth.installAuth
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.ConversationService
import de.omarfourati.redefluss.conversation.conversationRoutes
import de.omarfourati.redefluss.db.UserRepo
import de.omarfourati.redefluss.http.CspHashes
import de.omarfourati.redefluss.http.StaticFiles
import de.omarfourati.redefluss.http.installHttpBasics
import de.omarfourati.redefluss.http.respondProblem
import de.omarfourati.redefluss.http.spa
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.overview.OverviewService
import de.omarfourati.redefluss.overview.overviewRoutes
import de.omarfourati.redefluss.pronunciation.PronunciationService
import de.omarfourati.redefluss.pronunciation.pronunciationRoutes
import de.omarfourati.redefluss.vocab.VocabService
import de.omarfourati.redefluss.vocab.vocabRoutes
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import java.time.Clock

/** Everything the HTTP layer needs. Later tasks add services here (and in TestSupport.testDeps). */
class Deps(
    val config: Config,
    val ping: () -> Unit,
    val metrics: Metrics,
    val log: (String) -> Unit,
    val clock: Clock,
    val users: UserRepo,
    val auth: AuthService,
    val conversation: ConversationService,
    val overview: OverviewService,
    val vocab: VocabService,
    val pronunciation: PronunciationService,
)

fun Application.redefluss(deps: Deps) {
    val files = StaticFiles()
    val hashes = files.index?.let { CspHashes.inlineScripts(String(it.bytes, Charsets.UTF_8)) } ?: emptyList()
    installHttpBasics(deps.log, hashes)
    installAuth(deps.auth)
    install(MicrometerMetrics) {
        registry = deps.metrics.registry
        meterBinders = listOf(JvmMemoryMetrics())
    }
    routing {
        get("/healthz") {
            try {
                deps.ping()
                call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
            } catch (e: Exception) {
                call.respondProblem(HttpStatusCode.ServiceUnavailable, "Service Unavailable", "Datenbank nicht erreichbar.")
            }
        }
        // Caddy answers /metrics with 404 publicly; Prometheus reaches it inside the Docker network.
        get("/metrics") { call.respondText(deps.metrics.scrape(), ContentType.parse("text/plain; version=0.0.4")) }
        authRoutes(deps.auth)
        conversationRoutes(deps.conversation)
        overviewRoutes(deps.overview)
        vocabRoutes(deps.vocab)
        pronunciationRoutes(deps.pronunciation)
        route("/api") {
            handle { call.respondProblem(HttpStatusCode.NotFound, "Not Found") }
            route("{...}") { handle { call.respondProblem(HttpStatusCode.NotFound, "Not Found") } }
        }
        spa(files)
    }
}
