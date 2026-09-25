package wikitrend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.text.NumberFormat
import java.util.Locale
import wikitrend.Analyzer.Companion.pct

object Output {
    private val prettyJson = Json { prettyPrint = true }
    private val intFormat: NumberFormat = NumberFormat.getIntegerInstance(Locale.US)

    fun int(x: Number): String = intFormat.format(x)

    fun writeRun(dir: Path, r: AnalysisResult) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("analysis.json"), prettyJson.encodeToString(JsonElement.serializer(), toJson(r)))

        val monthly = StringBuilder("month,series,lang,topic,views,edition_views,views_per_million\n")
        for (s in r.series) for (i in s.months.indices) {
            val perMillion = if (s.projectMonthly[i] > 0) s.monthly[i] * 1e6 / s.projectMonthly[i] else 0.0
            monthly.append("${s.months[i]},${csv(s.label)},${s.lang},${csv(s.topic)},${s.monthly[i]},${s.projectMonthly[i]},${"%.2f".format(Locale.US, perMillion)}\n")
        }
        Files.writeString(dir.resolve("monthly.csv"), monthly)

        val daily = StringBuilder("date,series,views\n")
        for (s in r.series) for (i in s.days.indices) daily.append("${s.days[i]},${csv(s.label)},${s.daily[i]}\n")
        Files.writeString(dir.resolve("daily.csv"), daily)
    }

    private fun csv(s: String) = if (s.any { it == ',' || it == '"' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun toJson(r: AnalysisResult): JsonObject = buildJsonObject {
        put("tool", "wikitrend 0.1.0")
        put("generated", r.generatedAt.toString())
        putJsonObject("params") {
            put("window_start", r.options.window.start.toString())
            put("window_end", r.options.window.end.toString())
            put("months", r.options.window.months.size)
            put("agent", r.options.agent)
            put("access", r.options.access)
            putJsonArray("langs") { r.options.langs.forEach { add(it) } }
            putJsonArray("topics") {
                r.options.topics.forEach { t ->
                    addJsonObject {
                        t.name?.let { put("name", it) }
                        putJsonArray("items") { t.items.forEach { add(it) } }
                        putJsonArray("articles") { t.articles.forEach { add("${it.lang}:${it.title}") } }
                    }
                }
            }
        }
        putJsonArray("series") {
            r.series.forEach { s ->
                addJsonObject {
                    put("label", s.label)
                    put("topic", s.topic)
                    put("lang", s.lang)
                    putJsonArray("articles") { s.articles.forEach { add(it) } }
                    put("redirects_counted", s.redirectsCounted)
                    put("redirects_total", s.redirectsTotal)
                    put("compare_months", s.compareMonths)
                    put("seasonally_aligned", s.seasonallyAligned)
                    put("avg_daily_recent", round1(s.avgDailyRecent))
                    put("avg_daily_prior", round1(s.avgDailyPrior))
                    put("views_per_million_recent", round1(s.viewsPerMillionRecent))
                    put("growth", s.growth?.let(::round3))
                    put("share_growth", s.shareGrowth?.let(::round3))
                    put("edition_growth", s.projectGrowth?.let(::round3))
                    put("median_day_growth", s.medianGrowth?.let(::round3))
                    put("last3_months_growth_yoy", s.recent3Growth?.let(::round3))
                    put("months_up", s.monthsUp)
                    put("months_down", s.monthsDown)
                    put("sign_test_p", round3(s.signP))
                    put("trend_per_year", s.trendPerYear?.let(::round3))
                    put("share_trend_per_year", s.shareTrendPerYear?.let(::round3))
                    put("spike_share_recent", round3(s.spikeShareRecent))
                    put("direction", s.direction)
                    put("confidence", s.confidence)
                    putJsonArray("checks") {
                        s.checks.forEach { c ->
                            addJsonObject {
                                put("name", c.name)
                                put("ok", c.ok)
                                put("detail", c.detail)
                            }
                        }
                    }
                    putJsonArray("top_spikes") {
                        s.topSpikes.forEach { sp ->
                            addJsonObject {
                                put("date", sp.date.toString())
                                put("views", sp.views)
                                put("baseline", round1(sp.baseline))
                            }
                        }
                    }
                    putJsonArray("months") { s.months.forEach { add(it.toString()) } }
                    putJsonArray("monthly_views") { s.monthly.forEach { add(it) } }
                    putJsonArray("edition_monthly_views") { s.projectMonthly.forEach { add(it) } }
                }
            }
        }
        putJsonArray("missing") {
            r.missing.forEach { m ->
                addJsonObject {
                    put("topic", m.topic)
                    put("lang", m.lang)
                    put("reason", m.reason)
                }
            }
        }
    }

    private fun round1(x: Double) = Math.round(x * 10) / 10.0
    private fun round3(x: Double) = Math.round(x * 1000) / 1000.0

    /** Compact Markdown the agent reads: one table, trust checks, and what to do next. */
    fun summary(r: AnalysisResult, dir: Path, httpInfo: String): String = buildString {
        val w = r.options.window
        val first = r.series.firstOrNull()
        appendLine("# wikitrend analysis: ${w.start}..${w.end} (${w.months.size} months), agent=${r.options.agent}, access=${r.options.access}")
        appendLine("Run dir: ${dir.toAbsolutePath().normalize()}")
        appendLine("Files: analysis.json, monthly.csv, daily.csv, views.png, growth.png")
        if (first != null) {
            appendLine(
                if (first.seasonallyAligned) "Change = last 12 months vs the previous 12 months (same calendar months, so seasonality cancels out)."
                else "Change = last ${first.compareMonths} months vs the previous ${first.compareMonths} months (NOT seasonally aligned; use --months 24 or more when possible).",
            )
        }
        appendLine()
        if (r.series.isNotEmpty()) {
            appendLine("| Series | Articles (+redirects) | Views/day (recent) | Per 1M edition views | Change | Change vs edition | Months up | Trend/yr | Verdict |")
            appendLine("|---|---|---|---|---|---|---|---|---|")
            for (s in r.series) {
                val arts = s.articles.joinToString("; ") + when {
                    s.redirectsTotal > s.redirectsCounted -> " (+${s.redirectsCounted} of ${s.redirectsTotal} redirects)"
                    s.redirectsCounted > 0 -> " (+${s.redirectsCounted})"
                    else -> ""
                }
                appendLine(
                    "| ${s.label} | $arts | ${int(Math.round(s.avgDailyRecent))} | ${"%.1f".format(s.viewsPerMillionRecent)} | ${pct(s.growth)} | ${pct(s.shareGrowth)} | " +
                        "${s.monthsUp}/${s.compareMonths} | ${pct(s.trendPerYear)} | ${s.direction}, ${s.confidence} |",
                )
            }
            appendLine()
            appendLine("## Trust checks")
            for (s in r.series) {
                appendLine("${s.label}: ${s.direction}, ${s.confidence} confidence")
                for (c in s.checks) appendLine("- [${if (c.ok) "ok" else "!!"}] ${c.name}: ${c.detail}")
                if (s.recent3Growth != null) appendLine("- momentum: last 3 months ${pct(s.recent3Growth)} vs same months a year earlier")
                if (s.topSpikes.isNotEmpty()) {
                    appendLine("- top spike days: " + s.topSpikes.take(3).joinToString { "${it.date} (${int(it.views)} views, ${"%.0f".format(it.ratio)}× baseline)" })
                }
            }
            appendLine()
            appendLine("## Language edition totals (all articles, same filters)")
            for (s in r.series.distinctBy { it.lang }) {
                val recent = s.projectMonthly.takeLast(s.compareMonths).average()
                appendLine("- ${s.lang}: ${int(Math.round(recent))} views/month recently, ${pct(s.projectGrowth)} vs previous period")
            }
        }
        if (r.missing.isNotEmpty()) {
            appendLine()
            appendLine("## Missing series")
            for (m in r.missing) {
                appendLine("- ${m.lang} · ${m.topic}: ${m.reason}.")
            }
            appendLine("  Fix: `scripts/wikitrend search <lang> \"<term in that language>\"`, then re-run with --topic \"<Name>=<QID>|<lang>:<Title>\". " +
                "If no article exists, say so: missing coverage is itself a weak-interest signal.")
        }
        appendLine()
        appendLine("Next: answer the user from this table (cite numbers + confidence). For a shareable one-pager run")
        appendLine("`scripts/wikitrend report --run \"${dir.toAbsolutePath().normalize()}\" --title \"...\" --finding \"...\" --recommendation \"...\"`.")
        append("($httpInfo)")
    }
}
