package wikitrend

import java.nio.file.Path
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.system.exitProcess

const val USAGE = """wikitrend - Wikipedia pageview trend research (run via scripts/wikitrend)

Commands:
  resolve <topic words> --langs pl,cs [--search-lang en] [--limit 5]
      Find Wikidata items for a topic and the article title in each language.
  search <lang> <query words> [--limit 5]
      Full-text search inside one language edition (when an item has no article there).
  analyze --item Q123 [--item Q456] --langs pl,cs [--months 24 | --from 2024-01 --to 2025-12] [--out DIR]
      Download daily pageviews, compute growth + trust checks, write charts/CSV/JSON, print a summary.
      Topic forms (repeatable, max 8 series = topics x langs):
        --item Q333                          one Wikidata item, all --langs
        --article "uk:Астрономія"            one article in one language
        --topic "Name=Q333|uk:Title|pl:Tytuł" several items/articles summed as one topic
      Other: --agent user|all-agents|automated|spider (default user), --access all-access|desktop|mobile-web|mobile-app,
             --max-redirects 25, --no-redirects
  report --run DIR --title "..." --finding "..." [--finding "..."] [--recommendation "..."] [--question "..."]
         [--ui-lang en|uk] [--out FILE.pdf]
      Render a one-page PDF from an analyze run plus your conclusions (default: DIR/report.pdf).
"""

private val FLAGS = setOf("no-redirects", "help")

class Args(raw: List<String>, private val allowed: Set<String>) {
    val positionals = mutableListOf<String>()
    private val options = linkedMapOf<String, MutableList<String>>()
    private val flags = mutableSetOf<String>()

    init {
        var i = 0
        while (i < raw.size) {
            val a = raw[i]
            if (a.startsWith("--")) {
                val eq = a.indexOf('=')
                val name = if (eq > 0) a.substring(2, eq) else a.substring(2)
                if (name !in allowed && name !in FLAGS) {
                    throw WikiException("Unknown option --$name. Valid here: ${(allowed + FLAGS).sorted().joinToString { "--$it" }}")
                }
                when {
                    name in FLAGS -> flags += name
                    eq > 0 -> options.getOrPut(name) { mutableListOf() } += a.substring(eq + 1)
                    i + 1 < raw.size -> options.getOrPut(name) { mutableListOf() } += raw[++i]
                    else -> throw WikiException("Option --$name needs a value.")
                }
            } else {
                positionals += a
            }
            i++
        }
    }

    fun all(name: String): List<String> = options[name].orEmpty()
    fun one(name: String): String? = options[name]?.last()
    fun flag(name: String) = name in flags
    fun int(name: String, default: Int): Int =
        one(name)?.let { it.toIntOrNull() ?: throw WikiException("--$name must be a number, got '$it'.") } ?: default
    fun langs(): List<String> = all("langs").flatMap { it.split(',', ' ') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { Wiki.validateLang(it) }.distinct()
}

fun main(argv: Array<String>) {
    Locale.setDefault(Locale.ROOT) // decimal points, not the system locale's commas
    val code = try {
        run(argv.toList())
    } catch (e: WikiException) {
        System.err.println("ERROR: ${e.message}")
        2
    } catch (e: IllegalArgumentException) {
        System.err.println("ERROR: ${e.message}")
        2
    }
    exitProcess(code)
}

fun run(argv: List<String>): Int {
    if (argv.isEmpty() || argv[0] in setOf("help", "--help", "-h")) {
        println(USAGE)
        return 0
    }
    val http = Http(Http.defaultCacheDir())
    if (!http.hasContact) {
        System.err.println("NOTE: no contact configured, so Wikimedia allows only ~10 requests/min (slow). " +
            "Fix once: mkdir -p ~/.config/wikitrend && echo 'you@example.com' > ~/.config/wikitrend/contact")
    }
    val wiki = Wiki(http)
    val rest = argv.drop(1)
    return when (argv[0]) {
        "resolve" -> resolve(wiki, Args(rest, setOf("langs", "search-lang", "limit")))
        "search" -> search(wiki, Args(rest, setOf("limit")))
        "analyze" -> analyze(wiki, http, Args(rest, setOf("item", "article", "topic", "langs", "months", "from", "to", "agent", "access", "out", "max-redirects")))
        "report" -> report(Args(rest, setOf("run", "title", "finding", "recommendation", "question", "ui-lang", "out")))
        else -> throw WikiException("Unknown command '${argv[0]}'.\n$USAGE")
    }
}

private fun resolve(wiki: Wiki, args: Args): Int {
    val query = args.positionals.joinToString(" ").trim()
    if (query.isEmpty()) throw WikiException("Usage: resolve <topic words> --langs pl,cs [--search-lang en]")
    val langs = args.langs()
    if (langs.isEmpty()) throw WikiException("Pass --langs, e.g. --langs uk,pl")
    val searchLang = Wiki.validateLang(args.one("search-lang") ?: "en")
    val hits = wiki.searchItems(query, searchLang, args.int("limit", 5))
    if (hits.isEmpty()) {
        println("No Wikidata items match \"$query\" in '$searchLang'. Try an English term, a synonym, or --search-lang <lang of the query>.")
        return 1
    }
    val info = wiki.items(hits.map { it.id }, langs, searchLang)
    // Scholarly papers, clinical trials etc. share the label but have no articles: hide them unless nothing else matches.
    val withArticles = hits.filter { info[it.id]?.sitelinks?.isNotEmpty() == true }
    val shown = withArticles.ifEmpty { hits.take(3) }
    println("# Wikidata candidates for \"$query\" (searched in $searchLang)")
    if (shown.size < hits.size) println("(${hits.size - shown.size} candidate(s) without articles in ${langs.joinToString()} hidden)")
    shown.forEachIndexed { i, h ->
        val item = info[h.id]
        println("${i + 1}. ${h.id} - ${item?.label ?: h.label}: ${item?.description ?: h.description}")
        println("   " + langs.joinToString("   ") { l -> "$l: ${item?.sitelinks?.get(l) ?: "(no article)"}" })
    }
    val best = info[shown.first().id]
    val missingLangs = langs.filter { best?.sitelinks?.containsKey(it) != true }
    println()
    println("Pick the QID whose description matches the user's intent (usually #1), then:")
    println("  scripts/wikitrend analyze --item ${shown.first().id} --langs ${langs.joinToString(",")}")
    if (missingLangs.isNotEmpty()) {
        println()
        println("${shown.first().id} has no article in: ${missingLangs.joinToString()}. Full-text search there (verify before using):")
        for (l in missingLangs) {
            val local = wiki.searchWiki(l, query, 3)
            println("  $l: " + if (local.isEmpty()) "nothing found for \"$query\" - search with a term in that language: scripts/wikitrend search $l \"<term>\""
            else local.joinToString("; ") { "\"${it.title}\" (${it.words} words)" })
        }
        println("If a result is an article ABOUT the topic, add it: --topic \"${best?.label ?: "Topic"}=${shown.first().id}|<lang>:<Title>\"")
        println("Otherwise tell the user there is no dedicated article in that language (low coverage is itself a signal).")
    }
    return 0
}

private fun search(wiki: Wiki, args: Args): Int {
    if (args.positionals.size < 2) throw WikiException("Usage: search <lang> <query words>")
    val lang = Wiki.validateLang(args.positionals[0].lowercase())
    val query = args.positionals.drop(1).joinToString(" ")
    val hits = wiki.searchWiki(lang, query, args.int("limit", 5))
    if (hits.isEmpty()) {
        println("No $lang.wikipedia articles match \"$query\". Try a different term in that language.")
        return 1
    }
    val words = query.lowercase().split(Regex("\\s+")).filter { it.length >= 4 }.map { it.take(5) }
    println("# $lang.wikipedia search: \"$query\"")
    hits.forEachIndexed { i, h ->
        val titleMatch = words.any { h.title.lowercase().contains(it) }
        println("${i + 1}. \"${h.title}\" (${h.words} words)${if (titleMatch) " [title matches query]" else ""} - ${h.snippet.take(160)}")
    }
    println()
    println("Use a title ONLY if it is an article about the topic itself (not one that merely mentions it). If none is,")
    println("report that $lang has no dedicated article. Use as: --article \"$lang:<Title>\" or --topic \"Name=Q...|$lang:<Title>\"")
    return 0
}

private fun parseMonth(s: String, opt: String): YearMonth = try {
    YearMonth.parse(s.trim())
} catch (e: DateTimeParseException) {
    throw WikiException("--$opt must be YYYY-MM, got '$s'.")
}

fun windowFrom(args: Args, today: LocalDate = LocalDate.now()): Window {
    val lastComplete = YearMonth.from(today).minusMonths(1)
    val months = args.int("months", 24)
    val to = args.one("to")?.let { parseMonth(it, "to") } ?: lastComplete
    val from = args.one("from")?.let { parseMonth(it, "from") } ?: to.minusMonths(months - 1L)
    if (to.isAfter(lastComplete)) throw WikiException("--to $to is not a complete month yet; the latest complete month is $lastComplete.")
    if (from.isBefore(Window.EARLIEST)) throw WikiException("Pageview data starts in ${Window.EARLIEST}; --from $from is too early.")
    val window = Window(from, to)
    if (window.months.size < 6) throw WikiException("Window $window has ${window.months.size} months; use at least 6 (24+ recommended for seasonally aligned comparisons).")
    return window
}

private fun analyze(wiki: Wiki, http: Http, args: Args): Int {
    val topics = args.all("item").map { TopicSpec.parse(it) } +
        args.all("article").map { TopicSpec(null, emptyList(), listOf(TopicSpec.article(it))) } +
        args.all("topic").map { TopicSpec.parse(it) }
    if (topics.isEmpty()) throw WikiException("Nothing to analyze: pass --item Q..., --article lang:Title or --topic \"Name=Q...|lang:Title\".")
    val langs = args.langs()
    if (langs.isEmpty() && topics.any { it.items.isNotEmpty() }) throw WikiException("--item/--topic with Wikidata ids needs --langs, e.g. --langs uk,pl")
    val window = windowFrom(args)
    val agent = args.one("agent") ?: "user"
    val access = args.one("access") ?: "all-access"
    if (agent !in setOf("user", "all-agents", "automated", "spider")) throw WikiException("--agent must be user, all-agents, automated or spider.")
    if (access !in setOf("all-access", "desktop", "mobile-web", "mobile-app")) throw WikiException("--access must be all-access, desktop, mobile-web or mobile-app.")
    val opts = AnalyzeOptions(
        topics = topics, langs = langs, window = window, agent = agent, access = access,
        maxRedirects = if (args.flag("no-redirects")) 0 else args.int("max-redirects", 25),
    )

    val analyzer = Analyzer(wiki)
    val result = try {
        analyzer.run(opts)
    } finally {
        analyzer.shutdown()
    }
    val dir = args.one("out")?.let { Path.of(it) } ?: Path.of("wikitrend-runs", runSlug(topics, langs, window))
    Output.writeRun(dir, result)
    Charts.writeAll(result, dir)
    val summary = Output.summary(result, dir, "HTTP: ${http.networkRequests.get()} requests, ${http.cacheHits.get()} served from cache")
    java.nio.file.Files.writeString(dir.resolve("summary.md"), summary)
    println(summary)
    return if (result.series.isEmpty()) 1 else 0
}

private fun runSlug(topics: List<TopicSpec>, langs: List<String>, window: Window): String {
    val topicPart = topics.joinToString("+") { t -> t.name ?: t.items.firstOrNull() ?: t.articles.first().title }
    val raw = "$topicPart-${langs.joinToString("-")}-${window.start}_${window.end}"
    val slug = raw.lowercase().replace(Regex("[^\\p{L}\\p{N}_]+"), "-").trim('-')
    return slug.take(90).ifEmpty { "run" }
}

private fun report(args: Args): Int {
    val run = args.one("run") ?: throw WikiException("--run DIR is required (the 'Run dir' printed by analyze).")
    val title = args.one("title") ?: throw WikiException("--title is required.")
    val findings = args.all("finding")
    if (findings.isEmpty()) throw WikiException("Pass at least one --finding \"...\" (2-4 short, number-backed statements work best).")
    val runDir = Path.of(run)
    val uiLang = (args.one("ui-lang") ?: "en").lowercase()
    if (uiLang !in Report.TEXTS) throw WikiException("--ui-lang must be one of ${Report.TEXTS.keys.joinToString()}.")
    val out = args.one("out")?.let { Path.of(it) } ?: runDir.resolve("report.pdf")
    val warnings = Report(ReportInput(runDir, title, args.one("question"), findings, args.one("recommendation"), uiLang, out)).render()
    println("PDF written: ${out.toAbsolutePath().normalize()}")
    warnings.forEach { println("WARNING: $it") }
    return 0
}
