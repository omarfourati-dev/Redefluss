package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.ConversationService
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.overview.OverviewService
import de.omarfourati.redefluss.speech.speechFor
import de.omarfourati.redefluss.speech.openAiHttpClient
import de.omarfourati.redefluss.vocab.VocabRepo
import de.omarfourati.redefluss.vocab.VocabService
import de.omarfourati.redefluss.vocab.vocabAiFor
import de.omarfourati.redefluss.metrics.Metrics
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.time.Clock
import kotlin.system.exitProcess

/** Returns an error message when the app would start without any account, else null. */
fun ownerCheck(userCount: Long, email: String?, password: String?): String? =
    if (userCount == 0L && (email == null || password == null)) "Kein Konto vorhanden – OWNER_EMAIL und OWNER_PASSWORD setzen." else null

fun main() {
    val log = LoggerFactory.getLogger("redefluss")
    val config = Config.from(System.getenv())
    val clock = Clock.systemDefaultZone()
    val db = Db.connect(config.databaseUrl, config.dbUser, config.dbPassword)
    val metrics = Metrics()
    val users = UserRepo(db)
    val auth = AuthService(users, Tokens(config.jwtSecret, clock), LoginThrottle(clock), metrics, clock)
    ownerCheck(runBlocking { users.count() }, config.ownerEmail, config.ownerPassword)?.let {
        log.error(it)
        exitProcess(1)
    }
    if (config.ownerEmail != null && config.ownerPassword != null) {
        runBlocking { auth.bootstrapOwner(config.ownerEmail, config.ownerPassword, config.ownerResetPassword) }
    }
    val http = openAiHttpClient()
    val speech = speechFor(config, http)
    val vocabRepo = VocabRepo(db)
    val conversation = ConversationService(SessionRepo(db), MistakeRepo(db), UsageRepo(db), vocabRepo, speech, metrics, config, clock)
    val overview = OverviewService(UsageRepo(db), SessionRepo(db), MistakeRepo(db), vocabRepo, config, clock)
    val vocab = VocabService(vocabRepo, MistakeRepo(db), UsageRepo(db), vocabAiFor(config, http), speech, metrics, config, clock)
    val deps = Deps(config, db::ping, metrics, log::info, clock, users, auth, conversation, overview, vocab)
    embeddedServer(Netty, port = config.port) { redefluss(deps) }.start(wait = true)
}
