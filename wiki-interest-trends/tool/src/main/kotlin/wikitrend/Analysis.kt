package wikitrend

import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class ArticleRef(val lang: String, val title: String)

/** A topic = a display name plus Wikidata items and/or explicit articles whose views are summed per language. */
data class TopicSpec(val name: String?, val items: List<String>, val articles: List<ArticleRef>) {
    companion object {
        private val QID = Regex("^Q[1-9][0-9]*$")

        /** Parses `Name=Q123|pl:Some title`, `Q123` or `pl:Some title`. Entries are separated by `|`. */
        fun parse(spec: String): TopicSpec {
            val eq = spec.indexOf('=')
            val (name, body) = if (eq > 0 && ':' !in spec.substring(0, eq) && !QID.matches(spec.substring(0, eq).trim())) {
                spec.substring(0, eq).trim() to spec.substring(eq + 1)
            } else {
                null to spec
            }
            val items = mutableListOf<String>()
            val articles = mutableListOf<ArticleRef>()
            for (raw in body.split('|').map { it.trim() }.filter { it.isNotEmpty() }) {
                when {
                    QID.matches(raw.uppercase()) -> items += raw.uppercase()
                    ':' in raw -> articles += article(raw)
                    else -> throw WikiException("Cannot parse topic entry '$raw'. Use a Wikidata id (Q333) or lang:Title (uk:Астрономія).")
                }
            }
            if (items.isEmpty() && articles.isEmpty()) throw WikiException("Topic '$spec' has no items or articles.")
            return TopicSpec(name?.ifEmpty { null }, items, articles)
        }

        fun article(raw: String): ArticleRef {
            val colon = raw.indexOf(':')
            if (colon <= 0) throw WikiException("Expected lang:Title, got '$raw'.")
            val lang = Wiki.validateLang(raw.substring(0, colon).trim().lowercase())
            val title = raw.substring(colon + 1).trim()
            if (title.isEmpty()) throw WikiException("Empty article title in '$raw'.")
            return ArticleRef(lang, title)
        }
    }
}

data class AnalyzeOptions(
    val topics: List<TopicSpec>,
    val langs: List<String>,
    val window: Window,
    val agent: String = "user",
    val access: String = "all-access",
    val maxRedirects: Int = 25,
)

data class Check(val name: String, val ok: Boolean, val detail: String)

data class SeriesResult(
    val topic: String,
    val lang: String,
    val articles: List<String>,
    val redirectsCounted: Int,
    val redirectsTotal: Int,
    val months: List<YearMonth>,
    val monthly: List<Long>,
    val projectMonthly: List<Long>,
    val days: List<LocalDate>,
    val daily: LongArray,
    val compareMonths: Int,
    val seasonallyAligned: Boolean,
    val avgDailyRecent: Double,
    val avgDailyPrior: Double,
    val growth: Double?,
    val shareGrowth: Double?,
    val projectGrowth: Double?,
    val medianGrowth: Double?,
    val recent3Growth: Double?,
    val monthsUp: Int,
    val monthsDown: Int,
    val signP: Double,
    val trendPerYear: Double?,
    val shareTrendPerYear: Double?,
    val spikeShareRecent: Double,
    val topSpikes: List<Spike>,
    val firstDataMonth: YearMonth?,
    val direction: String,
    val confidence: String,
    val checks: List<Check>,
) {
    val label: String get() = "$lang · $topic"
    val viewsPerMillionRecent: Double
        get() {
            val recentViews = monthly.takeLast(compareMonths).sum()
            val recentProject = projectMonthly.takeLast(compareMonths).sum()
            return if (recentProject > 0) recentViews * 1e6 / recentProject else 0.0
        }
}

data class Missing(val topic: String, val lang: String, val reason: String)

data class AnalysisResult(
    val options: AnalyzeOptions,
    val series: List<SeriesResult>,
    val missing: List<Missing>,
    val generatedAt: LocalDate,
)

class Analyzer(private val wiki: Wiki) {
    private val pool = Executors.newFixedThreadPool(4)

    fun shutdown() = pool.shutdown()

    private fun <T, R> parallelMap(items: List<T>, fn: (T) -> R): List<R> =
        pool.invokeAll(items.map { Callable { fn(it) } }).map { f ->
            try {
                f.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }

    fun run(opts: AnalyzeOptions): AnalysisResult {
        val allLangs = (opts.langs + opts.topics.flatMap { t -> t.articles.map { it.lang } }).distinct()
        val itemInfo = wiki.items(opts.topics.flatMap { it.items }, allLangs)

        data class Plan(val topic: String, val lang: String, val titles: List<String>)

        val plans = mutableListOf<Plan>()
        val missing = mutableListOf<Missing>()
        for (t in opts.topics) {
            val name = t.name ?: t.items.firstNotNullOfOrNull { itemInfo[it]?.label } ?: t.articles.first().title
            val topicLangs = if (opts.langs.isEmpty()) t.articles.map { it.lang }.distinct() else (opts.langs + t.articles.map { it.lang }).distinct()
            for (lang in topicLangs) {
                val titles = (t.items.mapNotNull { itemInfo[it]?.sitelinks?.get(lang) } +
                    t.articles.filter { it.lang == lang }.map { it.title }).distinct()
                if (titles.isEmpty()) {
                    val unknown = t.items.filter { it !in itemInfo }
                    val reason = if (unknown.isNotEmpty()) "Wikidata item(s) ${unknown.joinToString()} not found"
                    else "no $lang article is linked to ${t.items.joinToString()} on Wikidata"
                    missing += Missing(name, lang, reason)
                } else {
                    plans += Plan(name, lang, titles)
                }
            }
        }
        if (plans.size > MAX_SERIES) {
            throw WikiException("${plans.size} series requested (topics × languages); the limit is $MAX_SERIES so charts stay readable. Split into several runs.")
        }

        // Resolve titles (follow redirects, collect incoming redirects) in parallel.
        val resolved = parallelMap(plans.flatMap { p -> p.titles.map { p to it } }) { (p, title) ->
            p to wiki.resolveArticle(p.lang, title, opts.maxRedirects)
        }.groupBy({ it.first }, { it.second })

        val window = opts.window
        val days = window.days
        val langsWithSeries = plans.map { it.lang }.distinct()
        val projectDaily = parallelMap(langsWithSeries) { lang ->
            lang to wiki.projectDaily(lang, window, opts.agent, opts.access)
        }.toMap()

        val results = mutableListOf<SeriesResult>()
        for (p in plans) {
            val articles = resolved[p].orEmpty()
            val existing = articles.filter { it.exists }.distinctBy { it.title }
            if (existing.isEmpty()) {
                missing += Missing(p.topic, p.lang, "article(s) ${p.titles.joinToString { "\"$it\"" }} not found on ${p.lang}.wikipedia")
                continue
            }
            val pageTitles = existing.flatMap { listOf(it.title) + it.redirects }.distinct()
            val perPage = parallelMap(pageTitles) { wiki.articleDaily(p.lang, it, window, opts.agent, opts.access) }
            val daily = LongArray(days.size)
            for (series in perPage) for ((i, d) in days.withIndex()) daily[i] += series[d] ?: 0L
            val project = projectDaily[p.lang].orEmpty()
            results += computeMetrics(
                topic = p.topic,
                lang = p.lang,
                articles = existing.map { it.title },
                redirectsCounted = existing.sumOf { it.redirects.size },
                redirectsTotal = existing.sumOf { it.redirectsTotal },
                window = window,
                daily = daily,
                projectDaily = LongArray(days.size) { project[days[it]] ?: 0L },
            )
        }
        return AnalysisResult(opts, results, missing, LocalDate.now())
    }

    companion object {
        const val MAX_SERIES = 8
        const val GROWTH_THRESHOLD = 0.10
        const val MIN_VOLUME = 20.0
        const val MAX_SPIKE_SHARE = 0.15

        fun computeMetrics(
            topic: String,
            lang: String,
            articles: List<String>,
            redirectsCounted: Int,
            redirectsTotal: Int,
            window: Window,
            daily: LongArray,
            projectDaily: LongArray,
        ): SeriesResult {
            val days = window.days
            val months = window.months
            val monthIndex = days.map { months.indexOf(YearMonth.from(it)) }
            val monthly = LongArray(months.size).also { m -> daily.forEachIndexed { i, v -> m[monthIndex[i]] += v } }.toList()
            val projectMonthly = LongArray(months.size).also { m -> projectDaily.forEachIndexed { i, v -> m[monthIndex[i]] += v } }.toList()

            val n = months.size
            val cmp = if (n >= 24) 12 else n / 2
            val aligned = cmp == 12
            val recentIdx = (n - cmp) until n
            val priorIdx = (n - 2 * cmp) until (n - cmp)
            fun sum(list: List<Long>, idx: IntRange) = idx.sumOf { list[it] }
            fun ratio(a: Double, b: Double): Double? = if (b > 0) a / b - 1 else null

            val recentViews = sum(monthly, recentIdx).toDouble()
            val priorViews = sum(monthly, priorIdx).toDouble()
            val recentProject = sum(projectMonthly, recentIdx).toDouble()
            val priorProject = sum(projectMonthly, priorIdx).toDouble()
            val recentDays = days.indices.filter { monthIndex[it] in recentIdx }
            val priorDays = days.indices.filter { monthIndex[it] in priorIdx }
            fun perDay(i: Int) = monthly[i].toDouble() / months[i].lengthOfMonth()

            // Per-day averages, so a leap year or unequal month lengths do not look like a change.
            val growth = ratio(recentViews / recentDays.size, priorViews / priorDays.size)
            val projectGrowth = ratio(recentProject / recentDays.size, priorProject / priorDays.size)
            val shareGrowth = if (recentProject > 0 && priorProject > 0) ratio(recentViews / recentProject, priorViews / priorProject) else null
            val medianGrowth = ratio(
                Stats.median(recentDays.map { daily[it].toDouble() }),
                Stats.median(priorDays.map { daily[it].toDouble() }),
            )
            val recent3Growth = if (aligned) ratio(
                ((n - 3) until n).sumOf { perDay(it) },
                ((n - 15) until (n - 12)).sumOf { perDay(it) },
            ) else null

            var up = 0
            var down = 0
            for (i in 0 until cmp) {
                val a = perDay(priorIdx.first + i)
                val b = perDay(recentIdx.first + i)
                if (b > a * 1.0001) up++ else if (b < a * 0.9999) down++
            }
            val signP = Stats.signTestP(up, up + down)

            val trend = Stats.theilSenSlope(monthly.map { ln(it + 1.0) })?.let { exp(12 * it) - 1 }
            val shareTrend = if (projectMonthly.all { it > 0 }) {
                Stats.theilSenSlope(monthly.indices.map { ln((monthly[it] + 1.0) / projectMonthly[it]) })?.let { exp(12 * it) - 1 }
            } else null

            val spikes = Stats.detectSpikes(days, daily)
            val recentStart = days.getOrNull(recentDays.firstOrNull() ?: 0)
            val spikeExcessRecent = spikes.filter { recentStart != null && !it.date.isBefore(recentStart) }.sumOf { it.excess }
            val spikeShare = if (recentViews > 0) spikeExcessRecent / recentViews else 0.0

            val firstDataIdx = monthly.indexOfFirst { it > 0 }
            val firstDataMonth = months.getOrNull(firstDataIdx)
            val gapMonths = if (firstDataIdx >= 0) (firstDataIdx until n).count { monthly[it] == 0L } else 0

            val avgRecent = if (recentDays.isNotEmpty()) recentViews / recentDays.size else 0.0
            val avgPrior = if (priorDays.isNotEmpty()) priorViews / priorDays.size else 0.0
            val direction = when {
                growth == null -> "unknown"
                growth >= GROWTH_THRESHOLD -> "growing"
                growth <= -GROWTH_THRESHOLD -> "declining"
                else -> "flat"
            }

            val periodWord = if (aligned) "the same month a year earlier" else "the matching month of the previous ${cmp}-month period"
            val checks = mutableListOf<Check>()
            val sign = if (direction == "declining") -1 else 1
            if (direction == "growing" || direction == "declining") {
                val k = if (sign > 0) up else down
                checks += Check(
                    "consistency", k > (up + down - k) && signP < 0.05,
                    "$k of $cmp months ${if (sign > 0) "above" else "below"} $periodWord (sign test ${pValue(signP)})",
                )
                // Poisson noise: is the change larger than random fluctuation for this many views?
                val z = if (recentViews > 0 && priorViews > 0) abs(ln(recentViews / priorViews)) / sqrt(1 / recentViews + 1 / priorViews) else 0.0
                checks += Check(
                    "noise", z >= 3,
                    "change is ${"%.1f".format(z)} standard errors from zero given ${Output.int(recentViews.toLong())} vs ${Output.int(priorViews.toLong())} views (≥3 needed)",
                )
                val medianAgrees = medianGrowth != null && medianGrowth * sign > 0
                checks += Check(
                    "spikes", spikeShare < MAX_SPIKE_SHARE && medianAgrees,
                    "median day ${pct(medianGrowth)} vs total ${pct(growth)}; spike days = ${"%.0f".format(spikeShare * 100)}% of recent views",
                )
                checks += Check(
                    "platform", shareGrowth != null && shareGrowth * sign > 0,
                    "share of all $lang Wikipedia views ${pct(shareGrowth)} (edition total ${pct(projectGrowth)})",
                )
            } else {
                checks += Check("spikes", spikeShare < MAX_SPIKE_SHARE, "spike days = ${"%.0f".format(spikeShare * 100)}% of recent views")
            }
            checks += Check(
                "volume", avgRecent >= MIN_VOLUME,
                "${"%.0f".format(avgRecent)} views/day recently" + when {
                    avgRecent < MIN_VOLUME -> " - very small audience: a few readers, one school assignment or one external link can move it (caps confidence at MEDIUM)"
                    avgRecent < 100 -> " - small audience"
                    else -> ""
                },
            )
            val complete = firstDataIdx == 0 && gapMonths == 0
            checks += Check(
                "coverage", complete,
                when {
                    firstDataIdx < 0 -> "no views at all in the window"
                    firstDataIdx > 0 -> "no views before ${months[firstDataIdx]}: article is new or was renamed without a redirect - change is an artifact"
                    gapMonths > 0 -> "$gapMonths month(s) with zero views inside the window - possible rename or data gap"
                    else -> "data present for every month"
                },
            )
            if (!aligned) checks += Check("seasonality", false, "window shorter than 24 months: periods are not the same calendar months, seasonality may bias the change")

            // Core checks decide the level; volume and seasonality only cap it; missing coverage forces LOW.
            val core = checks.filter { it.name !in setOf("volume", "seasonality") }
            val failed = core.count { !it.ok }
            var confidence = when {
                failed == 0 -> "HIGH"
                failed == 1 && (direction == "flat" || checks.first { it.name == "consistency" }.ok) -> "MEDIUM"
                else -> "LOW"
            }
            if ((avgRecent < MIN_VOLUME || !aligned) && confidence == "HIGH") confidence = "MEDIUM"
            if (!complete) confidence = "LOW"

            return SeriesResult(
                topic = topic, lang = lang, articles = articles,
                redirectsCounted = redirectsCounted, redirectsTotal = redirectsTotal,
                months = months, monthly = monthly, projectMonthly = projectMonthly,
                days = days, daily = daily,
                compareMonths = cmp, seasonallyAligned = aligned,
                avgDailyRecent = avgRecent, avgDailyPrior = avgPrior,
                growth = growth, shareGrowth = shareGrowth, projectGrowth = projectGrowth,
                medianGrowth = medianGrowth, recent3Growth = recent3Growth,
                monthsUp = up, monthsDown = down, signP = signP,
                trendPerYear = trend, shareTrendPerYear = shareTrend,
                spikeShareRecent = spikeShare,
                topSpikes = spikes.sortedByDescending { it.excess }.take(5),
                firstDataMonth = firstDataMonth,
                direction = direction, confidence = confidence, checks = checks,
            )
        }

        fun pValue(p: Double) = if (p < 0.001) "p<0.001" else "p=${"%.3f".format(p)}"

        fun pct(x: Double?): String = when {
            x == null -> "n/a"
            abs(x) >= 10 -> "%+.0f×".format(x + 1).replace("+", "")
            else -> "%+.0f%%".format(x * 100)
        }
    }
}
