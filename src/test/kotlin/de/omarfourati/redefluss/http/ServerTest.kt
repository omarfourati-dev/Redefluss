package de.omarfourati.redefluss.http

import de.omarfourati.redefluss.redefluss
import de.omarfourati.redefluss.testDeps
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ServerTest {
    @Test fun healthzOk() = testApplication {
        application { redefluss(testDeps()) }
        val res = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("""{"status":"ok"}""", res.bodyAsText())
    }

    @Test fun healthzDatabaseDown() = testApplication {
        application { redefluss(testDeps(ping = { error("down") })) }
        val res = client.get("/healthz")
        assertEquals(HttpStatusCode.ServiceUnavailable, res.status)
        assertEquals("application/problem+json", res.contentType()?.withoutParameters()?.toString())
    }

    @Test fun unknownApiPathIsProblem404() = testApplication {
        application { redefluss(testDeps()) }
        for (path in listOf("/api/nope", "/api")) {
            val res = client.get(path)
            assertEquals(HttpStatusCode.NotFound, res.status, path)
            assertEquals("application/problem+json", res.contentType()?.withoutParameters()?.toString(), path)
        }
    }

    @Test fun securityHeaders() = testApplication {
        application { redefluss(testDeps()) }
        val h = client.get("/healthz").headers
        assertEquals("nosniff", h["X-Content-Type-Options"])
        assertEquals("no-referrer", h["Referrer-Policy"])
        assertEquals("microphone=(self), camera=(), geolocation=()", h["Permissions-Policy"])
        val csp = h["Content-Security-Policy"]!!
        for (part in listOf("default-src 'self'", "media-src 'self' blob: data:", "frame-ancestors 'none'", "script-src 'self'")) {
            assertTrue(part in csp, "CSP misses $part: $csp")
        }
    }

    @Test fun requestLogHasNoQueryString() = testApplication {
        val lines = mutableListOf<String>()
        application { redefluss(testDeps(log = { lines += it })) }
        client.get("/api/nope?token=geheim")
        client.get("/healthz")
        val out = lines.joinToString("\n")
        assertTrue("/api/nope" in out, out)
        assertFalse("geheim" in out || "token=" in out, out)
        assertFalse("/healthz" in out, "healthz must not be logged: $out")
    }

    @Test fun metricsEndpoint() = testApplication {
        application { redefluss(testDeps()) }
        val res = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue("jvm_memory_used_bytes" in res.bodyAsText() || "ktor_http_server_requests" in res.bodyAsText())
    }
}
