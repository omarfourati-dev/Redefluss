package de.omarfourati.redefluss.http

import de.omarfourati.redefluss.redefluss
import de.omarfourati.redefluss.testDeps
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

class StaticFilesTest {
    @Test fun inlineScriptHashes() {
        val expected = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest("window.__boot = 1;".toByteArray()))
        assertEquals(listOf(expected), CspHashes.inlineScripts("""<script>window.__boot = 1;</script><script src="/x.js"></script>"""))
        assertEquals(emptyList(), CspHashes.inlineScripts("<p>no scripts</p>"))
    }

    @Test fun servingRules() = testApplication {
        application { redefluss(testDeps()) }
        suspend fun check(path: String, status: HttpStatusCode, contains: String? = null, cache: String? = null, type: String? = null) {
            val res = client.get(path)
            assertEquals(status, res.status, path)
            contains?.let { assertTrue(it in res.bodyAsText(), "$path body") }
            cache?.let { assertEquals(it, res.headers[HttpHeaders.CacheControl], "$path cache") }
            type?.let { assertEquals(it, res.contentType()?.withoutParameters()?.toString(), "$path type") }
        }
        check("/", HttpStatusCode.OK, "<div id=\"app\">", "no-cache")
        check("/gespraech", HttpStatusCode.OK, "<div id=\"app\">", "no-cache")
        check("/fehler/offen", HttpStatusCode.OK, "<div id=\"app\">")
        check("/_app/immutable/entry/start.Ab1-_x9Z.js", HttpStatusCode.OK, "console", "public, max-age=31536000, immutable")
        check("/_app/version.json", HttpStatusCode.OK, cache = "no-cache")
        check("/sw.js", HttpStatusCode.OK, cache = "no-cache")
        check("/manifest.webmanifest", HttpStatusCode.OK, cache = "no-cache", type = "application/manifest+json")
        check("/robots.txt", HttpStatusCode.OK, "Disallow")
        check("/missing.js", HttpStatusCode.NotFound)
        check("/api/unknown", HttpStatusCode.NotFound, type = "application/problem+json")
        check("/..%2f..%2fetc/passwd", HttpStatusCode.NotFound)
    }

    @Test fun cspContainsTheBootScriptHash() = testApplication {
        application { redefluss(testDeps()) }
        val hash = CspHashes.inlineScripts(javaClass.getResource("/static/index.html")!!.readText()).single()
        assertTrue("'sha256-$hash'" in client.get("/").headers["Content-Security-Policy"]!!)
    }
}
