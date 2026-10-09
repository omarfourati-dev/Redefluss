package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.ConversationService
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.overview.OverviewService
import de.omarfourati.redefluss.speech.*
import de.omarfourati.redefluss.metrics.Metrics
import de.omarfourati.redefluss.pronunciation.PronunciationScorer
import de.omarfourati.redefluss.pronunciation.PronunciationService
import de.omarfourati.redefluss.pronunciation.scorerFor
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import de.omarfourati.redefluss.vocab.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

val TEST_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), TEST_ZONE)

fun testConfig(vararg overrides: Pair<String, String>): Config = Config.from(
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://unused/redefluss", "DB_USER" to "u", "DB_PASSWORD" to "p",
        "JWT_SECRET" to "k".repeat(32), "SPEECH" to "fake",
    ) + overrides,
)

/** Central place to build Deps for tests; later tasks add parameters with test defaults here. */
fun testDeps(
    config: Config = testConfig(),
    db: Db? = null,
    ping: () -> Unit = {},
    metrics: Metrics = Metrics(),
    log: (String) -> Unit = {},
    clock: Clock = TEST_CLOCK,
    speech: Speech = Speech(FakeTranscriber(), FakeCoach(), FakeVoice()),
    vocabAi: VocabAi = VocabAi(FakeVocabGenerator(), FakeVocabChecker()),
    // Same selection as production (fake / azure / disabled); the HTTP client never reaches the network.
    scorer: PronunciationScorer? = scorerFor(config, { HttpClient(MockEngine { error("no network in tests") }) }) {},
): Deps {
    val database = db ?: TestDb.db
    val users = UserRepo(database)
    val auth = AuthService(users, Tokens(config.jwtSecret, clock), LoginThrottle(clock), metrics, clock)
    val pronunciation = PronunciationService(scorer, MistakeRepo(database), VocabRepo(database), UsageRepo(database), speech.voice, metrics, config, clock)
    return Deps(config = config, ping = ping, metrics = metrics, log = log, clock = clock, users = users, auth = auth,
        conversation = ConversationService(SessionRepo(database), MistakeRepo(database), UsageRepo(database), VocabRepo(database), speech, metrics, config, clock),
        overview = OverviewService(UsageRepo(database), SessionRepo(database), MistakeRepo(database), VocabRepo(database), config, clock, pronunciation.enabled),
        vocab = VocabService(VocabRepo(database), MistakeRepo(database), UsageRepo(database), vocabAi, speech, metrics, config, clock),
        pronunciation = pronunciation)
}
