package wikitrend

import org.jfree.chart.ChartFactory
import org.jfree.chart.JFreeChart
import org.jfree.chart.annotations.XYTextAnnotation
import org.jfree.chart.axis.DateAxis
import org.jfree.chart.axis.LogAxis
import org.jfree.chart.axis.NumberAxis
import org.jfree.chart.block.BlockBorder
import org.jfree.chart.labels.StandardCategoryItemLabelGenerator
import org.jfree.chart.plot.CategoryPlot
import org.jfree.chart.plot.PlotOrientation
import org.jfree.chart.plot.XYPlot
import org.jfree.chart.renderer.category.BarRenderer
import org.jfree.chart.renderer.category.StandardBarPainter
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer
import org.jfree.chart.title.TextTitle
import org.jfree.chart.ui.RectangleEdge
import org.jfree.chart.ui.RectangleInsets
import org.jfree.chart.ui.TextAnchor
import org.jfree.data.category.DefaultCategoryDataset
import org.jfree.data.time.Month
import org.jfree.data.time.TimeSeries
import org.jfree.data.time.TimeSeriesCollection
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.nio.file.Path
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale
import javax.imageio.ImageIO

/**
 * PNG charts for a run. Colours are the fixed-order categorical palette (validated for colour-vision
 * deficiency on adjacent pairs), assigned by series order so an entity keeps its colour across runs.
 */
/** Minimal per-series data the charts need; built from a live analysis or from analysis.json. */
data class ChartSeries(
    val label: String,
    val lang: String,
    val months: List<java.time.YearMonth>,
    val monthly: List<Long>,
    val growth: Double?,
    val shareGrowth: Double?,
)

object Charts {
    val PALETTE = listOf(
        Color(0x2a78d6), Color(0xeb6834), Color(0x1baf7a), Color(0xeda100),
        Color(0xe87ba4), Color(0x008300), Color(0x4a3aa7), Color(0xe34948),
    )
    private val SURFACE = Color(0xfcfcfb)
    private val TEXT = Color(0x0b0b0b)
    private val TEXT_SECONDARY = Color(0x52514e)
    private val GRID = Color(0xe4e3de)

    fun writeAll(r: AnalysisResult, dir: Path) {
        if (r.series.isEmpty()) return
        val series = r.series.map { ChartSeries(it.label, it.lang, it.months, it.monthly, it.growth, it.shareGrowth) }
        val period = r.series.first().let { if (it.seasonallyAligned) "last 12 months vs previous 12" else "last ${it.compareMonths} months vs previous ${it.compareMonths}" }
        ImageIO.write(render(viewsChart(series, VIEWS_TITLE_EN, VIEWS_TITLE_LOG_EN), 1000, 460), "png", dir.resolve("views.png").toFile())
        ImageIO.write(render(growthChart(series, "Change, $period (%)"), 1000, 120 + 56 * series.size), "png", dir.resolve("growth.png").toFile())
    }

    const val VIEWS_TITLE_EN = "Monthly pageviews (user traffic)"
    const val VIEWS_TITLE_LOG_EN = "Monthly pageviews (user traffic, log scale)"

    /** Renders at 2x for crisp embedding in the PDF. */
    fun render(chart: JFreeChart, w: Int, h: Int): java.awt.image.BufferedImage =
        chart.createBufferedImage(w * 2, h * 2, w.toDouble(), h.toDouble(), null)

    fun viewsChart(series: List<ChartSeries>, title: String, logTitle: String): JFreeChart {
        val positive = series.flatMap { it.monthly }.filter { it > 0 }
        val useLog = positive.isNotEmpty() && positive.max().toDouble() / positive.min() > 20
        val dataset = TimeSeriesCollection()
        for (s in series) {
            val ts = TimeSeries(s.label)
            s.months.forEachIndexed { i, ym ->
                val v = s.monthly[i]
                if (!useLog || v > 0) ts.add(Month(ym.monthValue, ym.year), v.toDouble())
            }
            dataset.addSeries(ts)
        }
        val chart = ChartFactory.createTimeSeriesChart(if (useLog) logTitle else title, null, null, dataset, series.size > 1, false, false)
        style(chart)
        val plot = chart.xyPlot
        if (useLog) {
            plot.rangeAxis = NiceLogAxis().apply { smallestValue = 1.0 }
        } else {
            (plot.rangeAxis as NumberAxis).apply {
                autoRangeIncludesZero = true
                numberFormatOverride = DecimalFormat("#,##0")
            }
        }
        (plot.domainAxis as DateAxis).dateFormatOverride = SimpleDateFormat("MMM yyyy", Locale.US)
        styleAxes(plot)
        val renderer = XYLineAndShapeRenderer(true, false)
        series.indices.forEach { i ->
            renderer.setSeriesPaint(i, PALETTE[i % PALETTE.size])
            renderer.setSeriesStroke(i, BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND))
        }
        plot.renderer = renderer
        // Direct labels at the line ends for small series counts (the legend still carries identity).
        if (series.size in 2..4) {
            val topics = series.map { it.label.substringAfter(" · ") }
            val sameLang = series.map { it.lang }.distinct().size == 1
            val sameTopic = topics.distinct().size == 1
            for ((i, s) in series.withIndex()) {
                val lastIdx = s.monthly.indexOfLast { !useLog || it > 0 }
                if (lastIdx < 0) continue
                val text = when {
                    sameLang -> topics[i]
                    sameTopic -> s.lang
                    else -> s.label
                }.let { if (it.length > 24) it.take(23) + "…" else it }
                val x = Month(s.months[lastIdx].monthValue, s.months[lastIdx].year).middleMillisecond.toDouble()
                plot.addAnnotation(XYTextAnnotation(text, x, s.monthly[lastIdx].toDouble()).apply {
                    font = Font("SansSerif", Font.BOLD, 13)
                    paint = TEXT
                    backgroundPaint = Color(0xfc, 0xfc, 0xfb, 220)
                    isOutlineVisible = false
                    textAnchor = TextAnchor.BOTTOM_RIGHT
                })
            }
        }
        return chart
    }

    fun growthChart(series: List<ChartSeries>, title: String): JFreeChart {
        val dataset = DefaultCategoryDataset()
        for (s in series) {
            dataset.addValue(s.growth?.times(100), "Pageviews", s.label)
            dataset.addValue(s.shareGrowth?.times(100), "Share of all views in that language edition", s.label)
        }
        val chart = ChartFactory.createBarChart(title, null, null, dataset, PlotOrientation.HORIZONTAL, true, false, false)
        style(chart)
        val plot = chart.categoryPlot
        styleAxes(plot)
        plot.rangeAxis.apply { (this as NumberAxis).numberFormatOverride = DecimalFormat("+#,##0;-#,##0") }
        plot.isRangeZeroBaselineVisible = true
        plot.rangeZeroBaselinePaint = TEXT_SECONDARY
        val renderer = plot.renderer as BarRenderer
        renderer.barPainter = StandardBarPainter()
        renderer.setShadowVisible(false)
        renderer.itemMargin = 0.08
        renderer.maximumBarWidth = 0.18
        renderer.setSeriesPaint(0, PALETTE[0])
        renderer.setSeriesPaint(1, Color(0x9a9890))
        renderer.defaultItemLabelGenerator = StandardCategoryItemLabelGenerator("{2}%", DecimalFormat("+#,##0;-#,##0"))
        renderer.defaultItemLabelsVisible = true
        renderer.defaultItemLabelFont = Font("SansSerif", Font.PLAIN, 12)
        renderer.defaultItemLabelPaint = TEXT
        return chart
    }

    private fun style(chart: JFreeChart) {
        chart.backgroundPaint = SURFACE
        chart.antiAlias = true
        chart.title = TextTitle(chart.title.text, Font("SansSerif", Font.BOLD, 16)).apply {
            paint = TEXT
            horizontalAlignment = org.jfree.chart.ui.HorizontalAlignment.LEFT
            padding = RectangleInsets(4.0, 8.0, 8.0, 8.0)
        }
        chart.legend?.apply {
            position = RectangleEdge.BOTTOM
            frame = BlockBorder.NONE
            backgroundPaint = SURFACE
            itemFont = Font("SansSerif", Font.PLAIN, 13)
            itemPaint = TEXT
        }
        chart.plot.backgroundPaint = SURFACE
        chart.plot.isOutlineVisible = false
    }

    private fun styleAxes(plot: XYPlot) {
        plot.isDomainGridlinesVisible = false
        plot.rangeGridlinePaint = GRID
        plot.rangeGridlineStroke = BasicStroke(1f)
        listOf(plot.domainAxis, plot.rangeAxis).forEach { axisStyle(it) }
    }

    private fun styleAxes(plot: CategoryPlot) {
        plot.isDomainGridlinesVisible = false
        plot.rangeGridlinePaint = GRID
        plot.rangeGridlineStroke = BasicStroke(1f)
        axisStyle(plot.domainAxis)
        axisStyle(plot.rangeAxis)
    }

    private fun axisStyle(axis: org.jfree.chart.axis.Axis) {
        axis.tickLabelFont = Font("SansSerif", Font.PLAIN, 12)
        axis.tickLabelPaint = TEXT_SECONDARY
        axis.labelPaint = TEXT_SECONDARY
        axis.axisLinePaint = GRID
        axis.tickMarkPaint = GRID
    }
}

/** Log axis with ticks only at 1-2-5 × 10^k (JFreeChart's default puts them at 10^(k/10), e.g. 15,849). */
private class NiceLogAxis : LogAxis() {
    override fun refreshTicksVertical(g2: java.awt.Graphics2D, dataArea: java.awt.geom.Rectangle2D, edge: RectangleEdge): MutableList<Any?> {
        val ticks = mutableListOf<Any?>()
        val lo = maxOf(range.lowerBound, 1.0)
        val hi = range.upperBound
        val decades = kotlin.math.log10(hi / lo)
        val multipliers = if (decades > 3) intArrayOf(1) else intArrayOf(1, 2, 5)
        val format = DecimalFormat("#,##0")
        var exp = kotlin.math.floor(kotlin.math.log10(lo)).toInt()
        while (Math.pow(10.0, exp.toDouble()) <= hi) {
            for (m in multipliers) {
                val v = m * Math.pow(10.0, exp.toDouble())
                if (v >= lo * 0.999 && v <= hi * 1.001) {
                    ticks += org.jfree.chart.axis.NumberTick(
                        org.jfree.chart.axis.TickType.MAJOR, v, format.format(v), TextAnchor.CENTER_RIGHT, TextAnchor.CENTER, 0.0,
                    )
                }
            }
            exp++
        }
        return ticks
    }
}
