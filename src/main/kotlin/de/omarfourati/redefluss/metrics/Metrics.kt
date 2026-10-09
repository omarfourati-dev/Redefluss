package de.omarfourati.redefluss.metrics

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** All application metrics in one place; names end up as redefluss_* in Prometheus. */
class Metrics(val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {
    fun turn(outcome: String) = registry.counter("redefluss.turns", "outcome", outcome).increment()
    fun stage(stage: String, millis: Long) =
        registry.timer("redefluss.stage.duration", "stage", stage).record(Duration.ofMillis(millis))
    fun mistake(category: String) = registry.counter("redefluss.mistakes", "category", category).increment()
    fun login(outcome: String) = registry.counter("redefluss.logins", "outcome", outcome).increment()
    fun vocabReview(grade: Int) = registry.counter("redefluss.vocab.reviews", "grade", grade.toString()).increment()
    fun vocabGenerated(n: Int) = registry.counter("redefluss.vocab.generated").increment(n.toDouble())
    /** outcome: ok, quota, limit, upstream_error, timeout, bad_audio, no_speech. */
    fun pronunciation(outcome: String) = registry.counter("redefluss.pronunciations", "outcome", outcome).increment()

    private val azureSeconds = registry.gauge("redefluss.azure.seconds.month", AtomicInteger(0))!!
    /** Azure seconds used in the current month (alert at 80 % of the cap). */
    fun azureSecondsMonth(seconds: Int) = azureSeconds.set(seconds)

    /** Runs block and records its duration as redefluss.stage.duration{stage}. */
    suspend fun <T> timed(stage: String, block: suspend () -> T): T {
        val start = System.nanoTime()
        try { return block() } finally { stage(stage, (System.nanoTime() - start) / 1_000_000) }
    }
    fun scrape(): String = registry.scrape()
}
