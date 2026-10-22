package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.conversation.ConversationService
import de.omarfourati.redefluss.db.*
import de.omarfourati.redefluss.interview.FakeRealtime
import de.omarfourati.redefluss.interview.FakeReporter
import de.omarfourati.redefluss.interview.InterviewReporter
import de.omarfourati.redefluss.invite.InvitationRepo
import de.omarfourati.redefluss.invite.InvitationService
import de.omarfourati.redefluss.interview.LiveService
import de.omarfourati.redefluss.interview.RealtimeSessions
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

/** The account that tests bootstrap first after TestDb.reset() (RESTART IDENTITY → id 1). */
const val OWNER = 1L
val OWNER_PRINCIPAL = de.omarfourati.redefluss.auth.UserPrincipal(OWNER, "omar@example.de", de.omarfourati.redefluss.db.Role.OWNER)

val TEST_ZONE: ZoneId = ZoneId.of("Europe/Berlin")
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-08T10:00:00Z"), TEST_ZONE)

fun testConfig(vararg overrides: Pair<String, String>): Config = Config.from(
    mapOf(
        "DATABASE_URL" to "jdbc:postgresql://unused/redefluss", "DB_USER" to "u", "DB_PASSWORD" to "p",
        "JWT_SECRET" to "k".repeat(32), "SPEECH" to "fake",
        // caps over all accounts stay out of the way unless a test sets them on purpose
        "TOTAL_TURNS_PER_DAY" to "100000", "TOTAL_LIVE_MINUTES_PER_DAY" to "480",
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
    realtime: RealtimeSessions = FakeRealtime(),
    reporter: InterviewReporter = FakeReporter(),
): Deps {
    val database = db ?: TestDb.db
    val users = UserRepo(database)
    val tokens = Tokens(config.jwtSecret, clock)
    val throttle = LoginThrottle(clock)
    val auth = AuthService(users, tokens, throttle, metrics, clock)
    val pronunciation = PronunciationService(scorer, MistakeRepo(database), VocabRepo(database), UsageRepo(database), speech.voice, metrics, config, clock)
    return Deps(config = config, ping = ping, metrics = metrics, log = log, clock = clock, users = users, auth = auth,
        conversation = ConversationService(SessionRepo(database), MistakeRepo(database), UsageRepo(database), VocabRepo(database), speech, metrics, config, clock),
        overview = OverviewService(UsageRepo(database), SessionRepo(database), MistakeRepo(database), VocabRepo(database), config, clock, pronunciation.enabled),
        vocab = VocabService(VocabRepo(database), MistakeRepo(database), UsageRepo(database), vocabAi, speech, metrics, config, clock),
        pronunciation = pronunciation,
        live = LiveService(realtime, reporter, SessionRepo(database), MistakeRepo(database), UsageRepo(database), metrics, config, clock),
        invitations = InvitationService(InvitationRepo(database), users, tokens, throttle, clock))
}
