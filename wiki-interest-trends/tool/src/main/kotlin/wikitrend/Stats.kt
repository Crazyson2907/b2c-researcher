package wikitrend

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.max
import kotlin.math.min

/** A closed range of whole calendar months. */
data class Window(val start: YearMonth, val end: YearMonth) {
    init {
        require(!end.isBefore(start)) { "window end $end is before start $start" }
    }

    val startDate: LocalDate get() = start.atDay(1)
    val endDate: LocalDate get() = end.atEndOfMonth()
    val months: List<YearMonth> get() = generateSequence(start) { it.plusMonths(1) }.takeWhile { !it.isAfter(end) }.toList()
    val days: List<LocalDate> get() = generateSequence(startDate) { it.plusDays(1) }.takeWhile { !it.isAfter(endDate) }.toList()

    fun isClosed(today: LocalDate = LocalDate.now()): Boolean = end.isBefore(YearMonth.from(today))

    override fun toString() = "$start..$end"

    companion object {
        /** Per-article pageview data starts in July 2015. */
        val EARLIEST: YearMonth = YearMonth.of(2015, 7)

        /** The last [months] complete calendar months before [today]. */
        fun lastMonths(months: Int, today: LocalDate = LocalDate.now()): Window {
            val end = YearMonth.from(today).minusMonths(1)
            return Window(end.minusMonths(months - 1L), end)
        }
    }
}

data class Spike(val date: LocalDate, val views: Long, val baseline: Double) {
    val excess: Double get() = views - baseline
    val ratio: Double get() = views / max(baseline, 1.0)
}

object Stats {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Theil–Sen estimator: median of pairwise slopes of y against its index. Robust to outlier months. */
    fun theilSenSlope(ys: List<Double>): Double? {
        if (ys.size < 3) return null
        val slopes = ArrayList<Double>(ys.size * (ys.size - 1) / 2)
        for (i in ys.indices) for (j in i + 1 until ys.size) slopes += (ys[j] - ys[i]) / (j - i)
        return median(slopes)
    }

    /** Two-sided exact sign test: probability of a split at least as uneven as [k] of [n] under p = 0.5. */
    fun signTestP(k: Int, n: Int): Double {
        if (n == 0) return 1.0
        val tail = min(k, n - k)
        var p = 0.0
        for (i in 0..tail) p += binomial(n, i)
        return min(1.0, 2 * p / Math.pow(2.0, n.toDouble()))
    }

    private fun binomial(n: Int, k: Int): Double {
        var r = 1.0
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }

    /**
     * Days whose views are at least 4x the median of the surrounding ±14 days and exceed it by 30+ views.
     * Such days are typically news events, Google Doodles, social-media links or undetected bots.
     */
    fun detectSpikes(days: List<LocalDate>, values: LongArray, ratio: Double = 4.0, minExcess: Double = 30.0): List<Spike> {
        val spikes = mutableListOf<Spike>()
        for (i in values.indices) {
            val neighbours = ArrayList<Double>(28)
            for (j in max(0, i - 14)..min(values.size - 1, i + 14)) if (j != i) neighbours += values[j].toDouble()
            val baseline = median(neighbours)
            val v = values[i].toDouble()
            if (v >= ratio * max(baseline, 1.0) && v - baseline >= minExcess) spikes += Spike(days[i], values[i], baseline)
        }
        return spikes
    }
}
