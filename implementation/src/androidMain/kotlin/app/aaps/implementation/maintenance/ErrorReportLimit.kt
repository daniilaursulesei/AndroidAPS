package app.aaps.implementation.maintenance

import app.aaps.implementation.logging.ErrorKind

/**
 * Decides how often a fault may be reported.
 *
 * A broken radio link does not fail once. It fails, retries, fails again, and keeps doing that for
 * hours, so a rule with no limit would upload a log every few seconds all night: mobile data spent,
 * the cloud folder full of near identical files, and the first report - the interesting one, the
 * only one that shows what the link was doing before it broke - buried under hundreds of later
 * ones.
 *
 * Two limits, because they stop different things:
 *  - a gap **per kind**, so one noisy fault cannot crowd out a different fault that starts later
 *  - a cap **per day** over all kinds, so a phone left in a bad state has a known worst case
 *
 * Every method is synchronized. The caller is whichever thread wrote the log line, which in
 * practice means the pump communication thread and the Bluetooth callback thread at the same time.
 */
class ErrorReportLimit(
    private val minGapMillis: Long = DEFAULT_MIN_GAP_MILLIS,
    private val maxPerDay: Int = DEFAULT_MAX_PER_DAY
) {

    private val lastByKind = mutableMapOf<ErrorKind, Long>()

    /** Times of the reports allowed in the last day, oldest first. */
    private val allowed = ArrayDeque<Long>()

    /**
     * Asks whether this fault may be reported now, and records it when the answer is yes.
     *
     * Deciding and recording are one step on purpose. Two threads hitting a fault in the same
     * millisecond must not both be told yes, which is what would happen if a caller could ask
     * first and record afterwards.
     *
     * @return true when the caller should go ahead and upload
     */
    @Synchronized
    fun allow(kind: ErrorKind, now: Long): Boolean {
        forgetOlderThanADay(now)
        if (allowed.size >= maxPerDay) return false
        val last = lastByKind[kind]
        if (last != null && now - last < minGapMillis) return false
        lastByKind[kind] = now
        allowed.addLast(now)
        return true
    }

    /** How many reports were allowed in the last day. Used for the report header. */
    @Synchronized
    fun countToday(now: Long): Int {
        forgetOlderThanADay(now)
        return allowed.size
    }

    private fun forgetOlderThanADay(now: Long) {
        while (allowed.isNotEmpty() && now - allowed.first() >= DAY_MILLIS) allowed.removeFirst()
    }

    companion object {

        const val DAY_MILLIS = 24L * 60 * 60 * 1000

        /**
         * Thirty minutes between two reports of the same kind.
         *
         * Long enough that a failure that keeps retrying produces one report, short enough that a
         * fault which comes back after the link recovered is captured again rather than treated as
         * the same event.
         */
        const val DEFAULT_MIN_GAP_MILLIS = 30L * 60 * 1000

        /**
         * Twelve reports a day over all kinds.
         *
         * At roughly fifty kilobytes a report that is well under a megabyte a day, which is safe to
         * leave switched on over mobile data.
         */
        const val DEFAULT_MAX_PER_DAY = 12
    }
}
