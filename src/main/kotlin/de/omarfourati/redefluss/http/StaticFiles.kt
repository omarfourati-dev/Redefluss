package de.omarfourati.redefluss.http

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

data class StaticFile(val bytes: ByteArray, val contentType: ContentType, val cacheControl: String?)

object CspHashes {
    private val INLINE = Regex("""<script(?![^>]*\ssrc=)[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
    fun inlineScripts(html: String): List<String> = INLINE.findAll(html).map { it.groupValues[1] }.filter { it.isNotBlank() }
        .map { Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toList()
}

/** The built Svelte app from the classpath (the Docker build copies web/build into resources/static). */
class StaticFiles(private val root: String = "static", private val loader: ClassLoader = StaticFiles::class.java.classLoader) {
    private val cache = ConcurrentHashMap<String, StaticFile>()
    private val noCache = setOf("index.html", "sw.js", "manifest.webmanifest", "_app/version.json")

    val index: StaticFile? get() = resolve("/index.html")

    /** Traversal attempts get a hard 404, never the SPA fallback. */
    fun isUnsafe(path: String): Boolean = ".." in path || '\\' in path || path.removePrefix("/").startsWith("/") || path.any { it.code < 0x20 }

    /** Bytes of a regular file; null for directories (plain dirs and jar directory entries). */
    private fun readFile(url: java.net.URL): ByteArray? {
        if (url.protocol == "file") {
            val f = java.io.File(url.toURI())
            return if (f.isFile) f.readBytes() else null
        }
        val conn = url.openConnection()
        conn.useCaches = false
        if (conn is java.net.JarURLConnection) {
            val jarFile = conn.jarFile
            try {
                val entry = conn.jarEntry
                if (entry == null || entry.isDirectory) return null
                return jarFile.getInputStream(entry).use { it.readBytes() }
            } finally {
                jarFile.close()
            }
        }
        return conn.getInputStream().use { it.readBytes() }
    }

    fun resolve(path: String): StaticFile? {
        val rel = path.removePrefix("/").ifEmpty { "index.html" }
        if (isUnsafe(rel)) return null
        cache[rel]?.let { return it }
        if (rel.endsWith("/")) return null
        val bytes = loader.getResource("$root/$rel")?.let { readFile(it) } ?: return null
        val type = if (rel.endsWith(".webmanifest")) ContentType.parse("application/manifest+json")
                   else ContentType.defaultForFilePath(rel)
        val control = when {
            rel.startsWith("_app/immutable/") -> "public, max-age=31536000, immutable"
            rel in noCache -> "no-cache"
            else -> null
        }
        return StaticFile(bytes, type, control).also { cache[rel] = it }
    }
}

/** Decodes the request path once; null for malformed escapes. */
fun decodePath(raw: String): String? =
    try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (e: IllegalArgumentException) { null }

fun Route.spa(files: StaticFiles) {
    get("{...}") {
        val path = decodePath(call.request.path())
            ?: return@get call.respondProblem(HttpStatusCode.NotFound, "Not Found")
        if (path == "/api" || path.startsWith("/api/")) return@get call.respondProblem(HttpStatusCode.NotFound, "Not Found")
        if (files.isUnsafe(path)) return@get call.respondProblem(HttpStatusCode.NotFound, "Not Found")
        val file = files.resolve(path)
            ?: if (path.substringAfterLast('/').contains('.')) null else files.index
        if (file == null) return@get call.respondProblem(HttpStatusCode.NotFound, "Not Found")
        file.cacheControl?.let { call.response.headers.append(HttpHeaders.CacheControl, it) }
        call.respondBytes(file.bytes, file.contentType)
    }
}
