package wikitrend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ItemInfo(
    val id: String,
    val label: String,
    val description: String,
    /** language code -> article title */
    val sitelinks: Map<String, String>,
)

data class PageHit(val title: String, val snippet: String, val words: Int)

data class ResolvedArticle(
    val lang: String,
    val requested: String,
    val title: String,
    val exists: Boolean,
    val redirects: List<String>,
    val redirectsTotal: Int,
)

/** Thin typed wrapper over Wikidata, MediaWiki Action API and the Wikimedia Pageviews API. */
class Wiki(private val http: Http) {
    private val metaTtl = Duration.ofDays(7)

    fun searchItems(query: String, lang: String, limit: Int): List<ItemInfo> {
        val url = "https://www.wikidata.org/w/api.php?action=wbsearchentities&type=item&format=json" +
            "&search=${enc(query)}&language=${enc(lang)}&uselang=${enc(lang)}&limit=$limit"
        val body = http.get(url, metaTtl) ?: return emptyList()
        return json(body)["search"]?.jsonArray.orEmpty().map { hit ->
            val o = hit.jsonObject
            ItemInfo(o.str("id"), o.str("label"), o.str("description"), emptyMap())
        }
    }

    /** Labels, descriptions and sitelinks for up to 50 items per request. */
    fun items(ids: List<String>, langs: List<String>, labelLang: String = "en"): Map<String, ItemInfo> {
        val result = linkedMapOf<String, ItemInfo>()
        for (chunk in ids.distinct().chunked(50)) {
            val sites = langs.joinToString("|") { siteId(it) }
            val labelLangs = (listOf(labelLang, "en") + langs).distinct().joinToString("|")
            val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&format=json" +
                "&ids=${enc(chunk.joinToString("|"))}&props=${enc("labels|descriptions|sitelinks")}" +
                "&languages=${enc(labelLangs)}&sitefilter=${enc(sites)}"
            val body = http.get(url, metaTtl) ?: continue
            val entities = json(body)["entities"]?.jsonObject ?: continue
            for ((id, entity) in entities) {
                val e = entity.jsonObject
                if (e.containsKey("missing")) continue
                val labels = e["labels"]?.jsonObject
                val descriptions = e["descriptions"]?.jsonObject
                fun pick(map: JsonObject?): String {
                    if (map == null) return ""
                    for (l in listOf(labelLang, "en") + langs) {
                        map[l]?.jsonObject?.str("value")?.takeIf { it.isNotEmpty() }?.let { return it }
                    }
                    return ""
                }
                val links = e["sitelinks"]?.jsonObject.orEmpty()
                val byLang = langs.mapNotNull { lang -> links[siteId(lang)]?.jsonObject?.str("title")?.let { lang to it } }.toMap()
                result[id] = ItemInfo(id, pick(labels).ifEmpty { id }, pick(descriptions), byLang)
            }
        }
        return result
    }

    /** Full-text search; multi-word queries try the exact phrase first, then all words. */
    fun searchWiki(lang: String, query: String, limit: Int): List<PageHit> {
        if (' ' in query.trim() && !query.contains('"')) {
            val phrase = searchWikiRaw(lang, "\"${query.trim()}\"", limit)
            if (phrase.isNotEmpty()) return phrase
        }
        return searchWikiRaw(lang, query, limit)
    }

    private fun searchWikiRaw(lang: String, query: String, limit: Int): List<PageHit> {
        val url = "${apiBase(lang)}?action=query&list=search&format=json&formatversion=2&srnamespace=0" +
            "&srprop=${enc("snippet|wordcount")}&srlimit=$limit&srsearch=${enc(query)}"
        val body = http.get(url, metaTtl) ?: return emptyList()
        return json(body)["query"]?.jsonObject?.get("search")?.jsonArray.orEmpty().map { hit ->
            val o = hit.jsonObject
            PageHit(
                title = o.str("title"),
                snippet = o.str("snippet").replace(Regex("<[^>]+>"), "").replace("&quot;", "\"").replace("&amp;", "&").trim(),
                words = o["wordcount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
            )
        }
    }

    /** Follows a redirect if [title] is one, and lists the redirects pointing at the target article. */
    fun resolveArticle(lang: String, title: String, maxRedirects: Int): ResolvedArticle {
        val url = "${apiBase(lang)}?action=query&format=json&formatversion=2&redirects=1" +
            "&prop=redirects&rdnamespace=0&rdlimit=max&titles=${enc(title)}"
        val body = http.get(url, metaTtl) ?: return ResolvedArticle(lang, title, title, false, emptyList(), 0)
        val page = json(body)["query"]?.jsonObject?.get("pages")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return ResolvedArticle(lang, title, title, false, emptyList(), 0)
        val resolved = page.str("title").ifEmpty { title }
        if (page.containsKey("missing") || page.containsKey("invalid")) {
            return ResolvedArticle(lang, title, resolved, false, emptyList(), 0)
        }
        val redirects = page["redirects"]?.jsonArray.orEmpty().map { it.jsonObject.str("title") }
        return ResolvedArticle(lang, title, resolved, true, redirects.take(maxRedirects), redirects.size)
    }

    fun articleDaily(lang: String, title: String, window: Window, agent: String, access: String): Map<LocalDate, Long> {
        val article = enc(title.replace(' ', '_'))
        val url = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/${project(lang)}/$access/$agent/" +
            "$article/daily/${window.startDate.format(DAY)}/${window.endDate.format(DAY)}"
        return parseSeries(http.get(url, pageviewTtl(window)))
    }

    fun projectDaily(lang: String, window: Window, agent: String, access: String): Map<LocalDate, Long> {
        val url = "https://wikimedia.org/api/rest_v1/metrics/pageviews/aggregate/${project(lang)}/$access/$agent/" +
            "daily/${window.startDate.format(DAY)}/${window.endDate.format(DAY)}"
        return parseSeries(http.get(url, pageviewTtl(window)))
    }

    private fun parseSeries(body: String?): Map<LocalDate, Long> {
        if (body == null) return emptyMap()
        return json(body)["items"]?.jsonArray.orEmpty().associate { item ->
            val o = item.jsonObject
            LocalDate.parse(o.str("timestamp").take(8), DAY) to (o["views"]?.jsonPrimitive?.longOrNull ?: 0L)
        }
    }

    // Closed months never change; a window that reaches into the current month is refreshed daily.
    private fun pageviewTtl(window: Window): Duration? =
        if (window.isClosed()) null else Duration.ofHours(12)

    companion object {
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val LANG = Regex("^[a-z]{2,3}(-[a-z0-9]+)*$")

        fun validateLang(lang: String): String {
            if (!LANG.matches(lang)) throw WikiException("Invalid language code '$lang'. Use Wikipedia subdomain codes like en, uk, pl, cs, pt, zh.")
            return lang
        }

        fun project(lang: String) = "${validateLang(lang)}.wikipedia"
        fun apiBase(lang: String) = "https://${validateLang(lang)}.wikipedia.org/w/api.php"
        fun siteId(lang: String) = validateLang(lang).replace('-', '_') + "wiki"
        fun articleUrl(lang: String, title: String) = "https://$lang.wikipedia.org/wiki/${enc(title.replace(' ', '_'))}"

        fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
        private fun json(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject
        private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
        private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
    }
}
