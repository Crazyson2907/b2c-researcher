package wikitrend

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StatsTest {
    @Test
    fun medianOddAndEven() {
        assertEquals(2.0, Stats.median(listOf(3.0, 1.0, 2.0)))
        assertEquals(2.5, Stats.median(listOf(4.0, 1.0, 2.0, 3.0)))
    }

    @Test
    fun theilSenIgnoresOneOutlier() {
        val ys = (0 until 12).map { it * 2.0 }.toMutableList()
        ys[5] = 1000.0
        assertEquals(2.0, Stats.theilSenSlope(ys)!!, 1e-9)
    }

    @Test
    fun signTestMatchesBinomial() {
        // 10 of 12: 2 * (C(12,0)+C(12,1)+C(12,2)) / 4096 = 2 * 79 / 4096
        assertEquals(2 * 79 / 4096.0, Stats.signTestP(10, 12), 1e-12)
        assertEquals(1.0, Stats.signTestP(6, 12))
        assertEquals(1.0, Stats.signTestP(0, 0))
    }

    @Test
    fun detectsIsolatedSpikeOnly() {
        val days = (0 until 60).map { LocalDate.of(2025, 1, 1).plusDays(it.toLong()) }
        val values = LongArray(60) { 100 }
        values[30] = 900
        val spikes = Stats.detectSpikes(days, values)
        assertEquals(listOf(days[30]), spikes.map { it.date })
        assertEquals(800.0, spikes.single().excess)
    }
}

class WindowTest {
    @Test
    fun lastMonthsEndsAtLastCompleteMonth() {
        val w = Window.lastMonths(24, LocalDate.of(2026, 9, 25))
        assertEquals(YearMonth.of(2024, 9), w.start)
        assertEquals(YearMonth.of(2026, 8), w.end)
        assertEquals(24, w.months.size)
        assertTrue(w.isClosed(LocalDate.of(2026, 9, 25)))
    }
}

class TopicSpecTest {
    @Test
    fun parsesNamedMixedTopic() {
        val t = TopicSpec.parse("Astronomy=Q333|uk:Сонячна система|q544")
        assertEquals("Astronomy", t.name)
        assertEquals(listOf("Q333", "Q544"), t.items)
        assertEquals(listOf(ArticleRef("uk", "Сонячна система")), t.articles)
    }

    @Test
    fun titleWithEqualsSignIsNotAName() {
        val t = TopicSpec.parse("en:E=mc2")
        assertNull(t.name)
        assertEquals(listOf(ArticleRef("en", "E=mc2")), t.articles)
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<WikiException> { TopicSpec.parse("astronomy") }
    }
}

class ConfidenceTest {
    private val window = Window(YearMonth.of(2024, 1), YearMonth.of(2025, 12))
    private val days = window.days
    private val edition = LongArray(days.size) { 1_000_000 }

    private fun metrics(daily: LongArray, project: LongArray = edition) =
        Analyzer.computeMetrics("t", "xx", listOf("T"), 0, 0, window, daily, project)

    private fun smoothGrowth(annual: Double, base: Double = 500.0) =
        LongArray(days.size) { i -> (base * (1 + annual).pow(i / 365.0)).toLong() }

    @Test
    fun steadyGrowthIsHighConfidence() {
        val m = metrics(smoothGrowth(0.5))
        assertEquals("growing", m.direction)
        assertEquals("HIGH", m.confidence, m.checks.toString())
        assertTrue(abs(m.growth!! - 0.5) < 0.05)
        assertEquals(12, m.monthsUp)
    }

    @Test
    fun spikeDrivenGrowthFailsSpikeCheck() {
        val daily = LongArray(days.size) { 200 }
        // Three viral days in the recent year add ~40% to the yearly total.
        for (d in listOf(500, 600, 650)) daily[d] = 10_000
        val m = metrics(daily)
        assertEquals("growing", m.direction)
        assertFalse(m.checks.first { it.name == "spikes" }.ok)
        assertTrue(m.confidence != "HIGH")
    }

    @Test
    fun growthExplainedByPlatformFailsPlatformCheck() {
        // Topic +20%, but the whole edition +50%: relative interest fell.
        val m = metrics(smoothGrowth(0.2), LongArray(days.size) { i -> (1_000_000 * 1.5.pow(i / 365.0)).toLong() })
        assertEquals("growing", m.direction)
        assertFalse(m.checks.first { it.name == "platform" }.ok)
        assertEquals("MEDIUM", m.confidence)
    }

    @Test
    fun newArticleIsLowConfidence() {
        val daily = LongArray(days.size) { i -> if (i < 200) 0 else 300 }
        val m = metrics(daily)
        assertFalse(m.checks.first { it.name == "coverage" }.ok)
        assertEquals("LOW", m.confidence)
    }

    @Test
    fun tinyAudienceIsCappedAtMedium() {
        val m = metrics(smoothGrowth(1.0, base = 5.0))
        assertEquals("growing", m.direction)
        assertEquals("MEDIUM", m.confidence, m.checks.toString())
    }

    @Test
    fun flatSeriesIsFlat() {
        val m = metrics(LongArray(days.size) { 400 })
        assertEquals("flat", m.direction)
        assertEquals(0.0, m.growth!!, 1e-9)
    }
}
