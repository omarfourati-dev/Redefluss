package de.omarfourati.redefluss.metrics

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/** All application metrics in one place; names end up as redefluss_* in Prometheus. */
class Metrics(val registry: PrometheusMeterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)) {
    fun turn(outcome: String) = registry.counter("redefluss.turns", "outcome", outcome).increment()
    fun stage(stage: String, millis: Long) =
        registry.timer("redefluss.stage.duration", "stage", stage).record(Duration.ofMillis(millis))
    fun mistake(category: String) = registry.counter("redefluss.mistakes", "category", category).increment()
    fun login(outcome: String) = registry.counter("redefluss.logins", "outcome", outcome).increment()
    fun scrape(): String = registry.scrape()
}
