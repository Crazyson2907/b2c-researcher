package wikitrend

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class WikiException(message: String) : RuntimeException(message)

/**
 * GET-only HTTP client with an on-disk response cache.
 *
 * Pageview data for closed date ranges never changes, so it is cached forever (ttl = null);
 * search/metadata responses use a short TTL. A 404 is cached as "no data".
 */
class Http(private val cacheDir: Path) {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    // Wikimedia rate limits depend on the User-Agent: with contact details (email or URL) a client gets
    // 200 requests/min, without them 10/min. https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits
    private val contact = contactInfo()
    private val userAgent = "wiki-interest-trends-skill/0.1 (${contact ?: "no contact configured"}) java-http-client"
    private val minIntervalMs = if (contact != null) 350L else 6500L
    private var nextSlot = 0L

    val networkRequests = AtomicInteger()
    val cacheHits = AtomicInteger()

    init {
        Files.createDirectories(cacheDir)
    }

    val hasContact: Boolean get() = contact != null

    /** Spaces requests to stay under the rate limit for this client class. */
    private fun acquireSlot() {
        val wait = synchronized(this) {
            val now = System.currentTimeMillis()
            val slot = maxOf(now, nextSlot)
            nextSlot = slot + minIntervalMs
            slot - now
        }
        if (wait > 0) Thread.sleep(wait)
    }

    /** Returns the response body, or null when the server answers 404 (no data / no such page). */
    fun get(url: String, ttl: Duration?): String? {
        val file = cacheDir.resolve(sha256(url) + ".json")
        if (Files.exists(file)) {
            val fresh = ttl == null ||
                Files.getLastModifiedTime(file).toInstant().isAfter(Instant.now().minus(ttl))
            if (fresh) {
                cacheHits.incrementAndGet()
                val body = Files.readString(file)
                return if (body == NOT_FOUND) null else body
            }
        }

        var lastError = ""
        var retryAfterMs = 0L
        for (attempt in 0 until 5) {
            if (attempt > 0) Thread.sleep(maxOf(retryAfterMs, 5000L * (1 shl (attempt - 1))))
            acquireSlot()
            val request = HttpRequest.newBuilder(URI(url))
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build()
            val response = try {
                networkRequests.incrementAndGet()
                client.send(request, HttpResponse.BodyHandlers.ofString())
            } catch (e: java.io.IOException) {
                lastError = e.toString()
                continue
            }
            when (response.statusCode()) {
                200 -> {
                    writeAtomically(file, response.body())
                    return response.body()
                }
                404 -> {
                    writeAtomically(file, NOT_FOUND)
                    return null
                }
                429, in 500..599 -> {
                    lastError = "HTTP ${response.statusCode()}"
                    retryAfterMs = response.headers().firstValue("retry-after").map { it.toLongOrNull() }.orElse(null)
                        ?.let { minOf(it, 60L) * 1000 } ?: 0L
                }
                else -> throw WikiException("HTTP ${response.statusCode()} for $url: ${response.body().take(300)}")
            }
        }
        val hint = if (lastError == "HTTP 429" && contact == null) " Rate limited: set a contact (see SKILL.md, 'Setup') to raise the limit from 10 to 200 requests/min." else ""
        throw WikiException("Request failed after retries ($lastError): $url.$hint")
    }

    private fun writeAtomically(file: Path, content: String) {
        val tmp = Files.createTempFile(cacheDir, "tmp", ".part")
        Files.writeString(tmp, content)
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val NOT_FOUND = "__404__"

        /** Contact for the User-Agent: $WIKITREND_CONTACT, else the first line of ~/.config/wikitrend/contact. */
        fun contactInfo(): String? {
            System.getenv("WIKITREND_CONTACT")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
            val file = Path.of(System.getProperty("user.home"), ".config", "wikitrend", "contact")
            if (!Files.isRegularFile(file)) return null
            return Files.readAllLines(file).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun defaultCacheDir(): Path {
            System.getenv("WIKITREND_CACHE")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).resolve("http") }
            val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                ?: Path.of(System.getProperty("user.home"), ".cache")
            return base.resolve("wikitrend").resolve("http")
        }
    }
}
