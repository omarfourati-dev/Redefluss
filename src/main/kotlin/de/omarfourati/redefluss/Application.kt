package de.omarfourati.redefluss

import de.omarfourati.redefluss.config.Config
import de.omarfourati.redefluss.metrics.Metrics
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import org.slf4j.LoggerFactory
import java.time.Clock

fun main() {
    val log = LoggerFactory.getLogger("redefluss")
    val config = Config.from(System.getenv())
    val deps = Deps(config = config, ping = {}, metrics = Metrics(), log = log::info, clock = Clock.systemDefaultZone())
    embeddedServer(Netty, port = config.port) { redefluss(deps) }.start(wait = true)
}
