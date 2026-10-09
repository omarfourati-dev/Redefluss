package de.omarfourati.redefluss

import de.omarfourati.redefluss.auth.AuthService
import de.omarfourati.redefluss.auth.LoginThrottle
import de.omarfourati.redefluss.auth.Tokens
import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.db.Db
import de.omarfourati.redefluss.db.UserRepo
import de.omarfourati.redefluss.metrics.Metrics
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
): Deps {
    val database = db ?: TestDb.db
    val users = UserRepo(database)
    val auth = AuthService(users, Tokens(config.jwtSecret, clock), LoginThrottle(clock), metrics, clock)
    return Deps(config = config, ping = ping, metrics = metrics, log = log, clock = clock, users = users, auth = auth)
}
