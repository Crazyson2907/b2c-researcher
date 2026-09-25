package wikitrend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import wikitrend.Analyzer.Companion.pct

data class ReportInput(
    val runDir: Path,
    val title: String,
    val question: String?,
    val findings: List<String>,
    val recommendation: String?,
    val uiLang: String,
    val out: Path,
)

/** Renders a one-page A4 PDF from an analyze run plus the agent's conclusions. Returns warnings. */
class Report(private val input: ReportInput) {
    private data class Row(
        val label: String, val avgDaily: Double, val perMillion: Double, val growth: Double?, val shareGrowth: Double?,
        val monthsUp: Int, val compareMonths: Int, val direction: String, val confidence: String,
        val failed: List<String>, val aligned: Boolean,
    )

    private val warnings = mutableListOf<String>()
    private val t = TEXTS[input.uiLang] ?: TEXTS.getValue("en")

    private lateinit var regular: PDFont
    private lateinit var bold: PDFont
    private lateinit var cs: PDPageContentStream

    private val pageW = PDRectangle.A4.width
    private val pageH = PDRectangle.A4.height
    private val margin = 40f
    private val contentW = pageW - 2 * margin
    private var y = pageH - margin

    fun render(): List<String> {
        val analysisFile = input.runDir.resolve("analysis.json")
        if (!Files.exists(analysisFile)) throw WikiException("No analysis.json in ${input.runDir}. Run `analyze` first and pass its Run dir.")
        val json = Json.parseToJsonElement(Files.readString(analysisFile)).jsonObject
        val params = json["params"]!!.jsonObject
        val rows = json["series"]!!.jsonArray.map { it.jsonObject }.map { s ->
            Row(
                label = s.str("label"),
                avgDaily = s.num("avg_daily_recent") ?: 0.0,
                perMillion = s.num("views_per_million_recent") ?: 0.0,
                growth = s.num("growth"),
                shareGrowth = s.num("share_growth"),
                monthsUp = s.int("months_up"),
                compareMonths = s.int("compare_months"),
                direction = s.str("direction"),
                confidence = s.str("confidence"),
                failed = s["checks"]!!.jsonArray.map { it.jsonObject }
                    .filter { (it["ok"] as? JsonPrimitive)?.booleanOrNull == false }
                    .mapNotNull { c -> t["check." + c.str("name")]?.let { "${s.str("label")}: $it" } },
                aligned = (s["seasonally_aligned"] as? JsonPrimitive)?.booleanOrNull ?: true,
            )
        }
        val chartSeries = json["series"]!!.jsonArray.map { it.jsonObject }.map { s ->
            ChartSeries(
                label = s.str("label"),
                lang = s.str("lang"),
                months = s["months"]!!.jsonArray.map { java.time.YearMonth.parse((it as JsonPrimitive).content) },
                monthly = s["monthly_views"]!!.jsonArray.map { (it as JsonPrimitive).content.toLong() },
                growth = s.num("growth"),
                shareGrowth = s.num("share_growth"),
            )
        }
        val missing = json["missing"]!!.jsonArray.map { it.jsonObject }.map { "${it.str("lang")} · ${it.str("topic")}: ${it.str("reason")}" }
        if (rows.isEmpty()) throw WikiException("The run has no series with data; nothing to report.")

        PDDocument().use { doc ->
            val page = PDPage(PDRectangle.A4)
            doc.addPage(page)
            loadFonts(doc)
            PDPageContentStream(doc, page).use { stream ->
                cs = stream
                layout(doc, params, rows, chartSeries, missing)
            }
            Files.createDirectories(input.out.toAbsolutePath().parent)
            Files.deleteIfExists(input.out)
            doc.save(input.out.toFile())
        }
        return warnings
    }

    private fun layout(doc: PDDocument, params: JsonObject, rows: List<Row>, chartSeries: List<ChartSeries>, missing: List<String>) {
        val bottom = margin + 14f // footer line lives below this

        // Header
        text(input.title, bold, 17f, TEXT, contentW)
        y -= 2f
        val window = "${params.str("window_start")} – ${params.str("window_end")}"
        text("${t.getValue("source")} · agent=${params.str("agent")}, ${params.str("access")} · $window · ${t.getValue("generated")} ${LocalDate.now()}", regular, 8f, MUTED, contentW)
        y -= 6f
        input.question?.takeIf { it.isNotBlank() }?.let {
            text("${t.getValue("question")}: $it", regular, 9f, SECONDARY, contentW)
            y -= 4f
        }

        // Pre-measure the flexible blocks so the chart can take whatever height is left.
        val findingsLines = input.findings.map { wrap(it, regular, 9.5f, contentW - 12f) }
        val recLines = input.recommendation?.let { wrap(it, regular, 9.5f, contentW) } ?: emptyList()
        val limitations = t.getValue("limitations").split("\n") + rows.flatMap { it.failed }.take(3) + missing.take(2)
        val limLines = limitations.map { wrap(it, regular, 7.5f, contentW - 10f) }
        val tableH = 24f + 13f * rows.size + 8f
        val headH = 20f
        val fixed = headH + findingsLines.sumOf { it.size } * 12.5f + 6f +
            tableH +
            (if (recLines.isNotEmpty()) headH + recLines.size * 12.5f + 6f else 0f) +
            headH + limLines.sumOf { it.size } * 9.5f
        val available = y - bottom - fixed - 8f
        val chartW = contentW
        var chartH = chartW * 460f / 1000f
        if (available < chartH) chartH = maxOf(available, 150f)
        if (available < 150f) warnings += "Content is too long for one page; the method/limitations section was shortened. Use fewer or shorter findings."

        // Findings
        heading(t.getValue("findings"))
        for (lines in findingsLines) bullet(lines, regular, 9.5f, 12.5f)
        y -= 6f

        // Chart, re-rendered so its title follows the report language
        val chart = Charts.viewsChart(chartSeries, t.getValue("chartTitle"), t.getValue("chartTitleLog"))
        val img = LosslessFactory.createFromImage(doc, Charts.render(chart, 1000, 460))
        val scale = minOf(chartW / img.width, chartH / img.height)
        val w = img.width * scale
        val h = img.height * scale
        cs.drawImage(img, margin + (contentW - w) / 2, y - h, w, h)
        y -= h + 8f

        // Metrics table
        table(rows)
        y -= 8f

        // Recommendation
        if (recLines.isNotEmpty()) {
            heading(t.getValue("recommendation"))
            for (line in recLines) line(line, regular, 9.5f, TEXT, 12.5f)
            y -= 6f
        }

        // Method & limitations (truncated first if space runs out)
        heading(t.getValue("method"))
        var dropped = 0
        for (lines in limLines) {
            if (y - lines.size * 9.5f < bottom) { dropped++; continue }
            bullet(lines, regular, 7.5f, 9.5f, SECONDARY)
        }
        if (dropped > 0) warnings += "$dropped limitation note(s) did not fit on the page."

        // Footer
        cs.setStrokingColor(RULE)
        cs.setLineWidth(0.5f)
        cs.moveTo(margin, margin + 10f)
        cs.lineTo(pageW - margin, margin + 10f)
        cs.stroke()
        drawText("${t.getValue("footer")} · run: ${input.runDir.fileName}", regular, 7f, MUTED, margin, margin)
    }

    private fun table(rows: List<Row>) {
        val cols = listOf(
            t.getValue("series") to 0.24f, t.getValue("vpd") to 0.11f, t.getValue("perMillion") to 0.11f, t.getValue("change") to 0.09f,
            t.getValue("vsEdition") to 0.11f, t.getValue("monthsUp") to 0.10f, t.getValue("verdict") to 0.24f,
        )
        val rowH = 13f
        // header: labels may wrap to two lines
        val headerH = 24f
        cs.setNonStrokingColor(HEADER_FILL)
        cs.addRect(margin, y - headerH, contentW, headerH)
        cs.fill()
        var x = margin + 4f
        for ((name, frac) in cols) {
            val lines = wrap(name, bold, 7.5f, contentW * frac - 6f).take(2)
            lines.forEachIndexed { i, l -> drawText(fit(l, bold, 7.5f, contentW * frac - 6f), bold, 7.5f, TEXT, x, y - 10f - i * 9f) }
            x += contentW * frac
        }
        y -= headerH
        for (r in rows) {
            val cells = listOf(
                r.label,
                Output.int(Math.round(r.avgDaily)),
                "%.1f".format(r.perMillion),
                pct(r.growth),
                pct(r.shareGrowth),
                "${r.monthsUp}/${r.compareMonths}",
                "${t[r.direction] ?: r.direction}, ${t[r.confidence] ?: r.confidence}",
            )
            x = margin + 4f
            for ((i, cell) in cells.withIndex()) {
                val font = if (i == 6) bold else regular
                val color = if (i == 6) confidenceColor(r.confidence) else TEXT
                drawText(fit(cell, font, 8.5f, contentW * cols[i].second - 6f), font, 8.5f, color, x, y - 9.5f)
                x += contentW * cols[i].second
            }
            y -= rowH
            cs.setStrokingColor(RULE)
            cs.setLineWidth(0.4f)
            cs.moveTo(margin, y + 1f)
            cs.lineTo(margin + contentW, y + 1f)
            cs.stroke()
        }
    }

    private fun confidenceColor(c: String) = when (c) {
        "HIGH" -> Color(0x0b6b2e)
        "MEDIUM" -> Color(0x8a5a00)
        else -> Color(0xb3261e)
    }

    private fun heading(s: String) {
        y -= 4f
        line(s, bold, 11f, TEXT, 16f)
    }

    private fun text(s: String, font: PDFont, size: Float, color: Color, width: Float) {
        for (l in wrap(s, font, size, width)) line(l, font, size, color, size * 1.3f)
    }

    private fun line(s: String, font: PDFont, size: Float, color: Color, leading: Float) {
        y -= leading
        drawText(s, font, size, color, margin, y)
    }

    private fun bullet(lines: List<String>, font: PDFont, size: Float, leading: Float, color: Color = TEXT) {
        for ((i, l) in lines.withIndex()) {
            y -= leading
            if (i == 0) drawText("•", font, size, color, margin + 2f, y)
            drawText(l, font, size, color, margin + 12f, y)
        }
    }

    private fun drawText(s: String, font: PDFont, size: Float, color: Color, x: Float, yPos: Float) {
        cs.beginText()
        cs.setFont(font, size)
        cs.setNonStrokingColor(color)
        cs.newLineAtOffset(x, yPos)
        cs.showText(safe(s, font))
        cs.endText()
    }

    private fun width(s: String, font: PDFont, size: Float) = font.getStringWidth(safe(s, font)) / 1000f * size

    private fun fit(s: String, font: PDFont, size: Float, max: Float): String {
        if (width(s, font, size) <= max) return s
        var cut = s
        while (cut.isNotEmpty() && width("$cut…", font, size) > max) cut = cut.dropLast(1)
        return "$cut…"
    }

    private fun wrap(s: String, font: PDFont, size: Float, max: Float): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in s.split("\n")) {
            var current = ""
            for (word in paragraph.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (width(candidate, font, size) <= max) {
                    current = candidate
                } else {
                    if (current.isNotEmpty()) lines += current
                    current = if (width(word, font, size) <= max) word else fit(word, font, size, max)
                }
            }
            if (current.isNotEmpty()) lines += current
        }
        return lines
    }

    private val safeCache = HashMap<Pair<PDFont, Int>, Boolean>()

    /** Replaces characters the font cannot encode (e.g. CJK with a Latin/Cyrillic font) with '?'. */
    private fun safe(s: String, font: PDFont): String {
        val sb = StringBuilder()
        s.codePoints().forEach { cp ->
            val ok = safeCache.getOrPut(font to cp) {
                try {
                    font.encode(String(Character.toChars(cp))); true
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) sb.appendCodePoint(cp) else sb.append('?')
        }
        return sb.toString()
    }

    private fun loadFonts(doc: PDDocument) {
        val candidates = buildList {
            System.getenv("WIKITREND_FONT")?.let { add(it to (System.getenv("WIKITREND_FONT_BOLD") ?: it)) }
            add("/System/Library/Fonts/Supplemental/Arial.ttf" to "/System/Library/Fonts/Supplemental/Arial Bold.ttf")
            add("/Library/Fonts/Arial.ttf" to "/Library/Fonts/Arial Bold.ttf")
            add("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf" to "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
            add("/usr/share/fonts/dejavu/DejaVuSans.ttf" to "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf")
            add("/usr/share/fonts/TTF/DejaVuSans.ttf" to "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf")
            add("/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf" to "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf")
            add("C:/Windows/Fonts/arial.ttf" to "C:/Windows/Fonts/arialbd.ttf")
        }
        for ((reg, b) in candidates) {
            if (!File(reg).isFile) continue
            regular = PDType0Font.load(doc, File(reg))
            bold = if (File(b).isFile) PDType0Font.load(doc, File(b)) else regular
            return
        }
        warnings += "No Unicode TTF font found (set WIKITREND_FONT=/path/font.ttf); non-Latin text is replaced by '?'."
        regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    }

    companion object {
        private val TEXT = Color(0x0b0b0b)
        private val SECONDARY = Color(0x52514e)
        private val MUTED = Color(0x7a7974)
        private val RULE = Color(0xd9d8d2)
        private val HEADER_FILL = Color(0xf0efea)

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
        private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
        private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull ?: 0

        val TEXTS: Map<String, Map<String, String>> = mapOf(
            "en" to mapOf(
                "source" to "Source: Wikimedia Pageviews API",
                "generated" to "generated",
                "question" to "Question",
                "findings" to "Key findings",
                "recommendation" to "Recommendation",
                "method" to "Method & limitations",
                "series" to "Series (language · topic)",
                "vpd" to "Views/day",
                "perMillion" to "Per 1M edition views",
                "change" to "Change",
                "vsEdition" to "Change vs edition",
                "monthsUp" to "Months up",
                "verdict" to "Direction, confidence",
                "growing" to "growing", "declining" to "declining", "flat" to "flat",
                "HIGH" to "high", "MEDIUM" to "medium", "LOW" to "low",
                "footer" to "wiki-interest-trends skill · data: wikimedia.org/api/rest_v1 (CC0)",
                "chartTitle" to Charts.VIEWS_TITLE_EN,
                "chartTitleLog" to Charts.VIEWS_TITLE_LOG_EN,
                "check.consistency" to "month-by-month changes are inconsistent, the direction is not established",
                "check.noise" to "the change is within statistical noise",
                "check.spikes" to "the change is driven by a few spike days (news, links or bots)",
                "check.platform" to "the change mirrors the whole language edition; relative interest moved differently",
                "check.volume" to "very small audience (<20 views/day)",
                "check.coverage" to "article is new or was renamed, so the change is an artifact",
                "check.seasonality" to "window under 24 months: seasonality not controlled",
                "limitations" to listOf(
                    "Change = recent 12 months vs the previous 12 (same calendar months). \"Change vs edition\" = change of the topic's share of all views in that language edition, which removes platform-wide traffic shifts.",
                    "Confidence combines month-by-month consistency (sign test), statistical noise, robustness to spike days, agreement with the share-adjusted change and data coverage; under 20 views/day caps it at medium. \"Per 1M edition views\" = topic views per million views of the edition (interest intensity).",
                    "Pageviews measure attention, not willingness to pay: use them to choose what to validate next, not as demand forecasts.",
                    "Only human traffic (agent=user) is counted; undetected bots can remain, which the spike check partly catches. Views include the article's redirects but not related articles.",
                    "A language edition is not a country: many speakers read English Wikipedia, so small editions under-represent interest.",
                ).joinToString("\n"),
            ),
            "uk" to mapOf(
                "source" to "Джерело: Wikimedia Pageviews API",
                "generated" to "створено",
                "question" to "Питання",
                "findings" to "Ключові висновки",
                "recommendation" to "Рекомендація",
                "method" to "Методологія та обмеження",
                "series" to "Серія (мова · тема)",
                "vpd" to "Переглядів за день",
                "perMillion" to "На 1 млн переглядів розділу",
                "change" to "Зміна",
                "vsEdition" to "Зміна частки в розділі",
                "monthsUp" to "Місяців росту",
                "verdict" to "Напрям, довіра",
                "growing" to "зростання", "declining" to "спад", "flat" to "стабільно",
                "HIGH" to "висока", "MEDIUM" to "середня", "LOW" to "низька",
                "footer" to "навичка wiki-interest-trends · дані: wikimedia.org/api/rest_v1 (CC0)",
                "chartTitle" to "Перегляди за місяць (людський трафік)",
                "chartTitleLog" to "Перегляди за місяць (людський трафік, логарифмічна шкала)",
                "check.consistency" to "помісячні зміни непослідовні, напрям не встановлено",
                "check.noise" to "зміна в межах статистичного шуму",
                "check.spikes" to "зміну спричинили кілька днів-сплесків (новини, посилання чи боти)",
                "check.platform" to "зміна повторює динаміку всього мовного розділу; відносний інтерес змінився інакше",
                "check.volume" to "дуже мала аудиторія (<20 переглядів/день)",
                "check.coverage" to "стаття нова або перейменована, тож зміна є артефактом",
                "check.seasonality" to "період коротший за 24 місяці: сезонність не врахована",
                "limitations" to listOf(
                    "Зміна = останні 12 місяців проти попередніх 12 (ті самі календарні місяці). «Зміна частки в розділі» = зміна частки теми серед усіх переглядів цього мовного розділу; вона прибирає загальні зміни трафіку Вікіпедії.",
                    "Довіра поєднує помісячну послідовність (тест знаків), статистичний шум, стійкість до днів-сплесків, узгодженість зі зміною частки та повноту даних; менше 20 переглядів/день обмежує її до середньої. «На 1 млн переглядів розділу» = перегляди теми на мільйон переглядів розділу (інтенсивність інтересу).",
                    "Перегляди показують увагу, а не готовність платити: це сигнал, що перевіряти далі, а не прогноз попиту.",
                    "Враховано лише людський трафік (agent=user); непомічені боти можуть лишатися, частково їх ловить перевірка сплесків. Перегляди включають перенаправлення на статтю, але не пов'язані статті.",
                    "Мовний розділ ≠ країна: багато людей читають англійську Вікіпедію, тож малі розділи недооцінюють інтерес.",
                ).joinToString("\n"),
            ),
        )
    }
}
