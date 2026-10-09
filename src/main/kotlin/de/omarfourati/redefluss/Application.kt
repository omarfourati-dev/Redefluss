package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.ConversationService
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.overview.OverviewService
import de.omarfourati.redefluss.speech.speechFor
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import de.omarfourati.redefluss.metrics.Metrics
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock

fun main() {
    val log = LoggerFactory.getLogger("redefluss")
    val config = Config.from(System.getenv())
    val clock = Clock.systemDefaultZone()
    val db = Db.connect(config.databaseUrl, config.dbUser, config.dbPassword)
    val metrics = Metrics()
    val users = UserRepo(db)
    val auth = AuthService(users, Tokens(config.jwtSecret, clock), LoginThrottle(clock), metrics, clock)
    if (config.ownerEmail != null && config.ownerPassword != null) {
        runBlocking { auth.bootstrapOwner(config.ownerEmail, config.ownerPassword, config.ownerResetPassword) }
    }
    val conversation = ConversationService(SessionRepo(db), MistakeRepo(db), UsageRepo(db), speechFor(config, HttpClient(CIO)), metrics, config, clock)
    val overview = OverviewService(UsageRepo(db), SessionRepo(db), MistakeRepo(db), config, clock)
    val deps = Deps(config, db::ping, metrics, log::info, clock, users, auth, conversation, overview)
    embeddedServer(Netty, port = config.port) { redefluss(deps) }.start(wait = true)
}
