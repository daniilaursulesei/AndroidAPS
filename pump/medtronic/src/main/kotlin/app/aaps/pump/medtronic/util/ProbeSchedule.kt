package app.aaps.pump.medtronic.util

/**
 * When to send the next recovery probe while the driver is waiting for the pump to come back.
 *
 * Pure timing logic, kept apart from the plugin so it can be tested on its own.
 *
 * The delay doubles after every probe that goes unanswered, from [firstIntervalMinutes] up to
 * [maxIntervalMinutes]. The point is to keep looking without wasting the RileyLink battery: a
 * pump can be unreachable for a long time - a flat pump battery silences the radio for good,
 * while the pump keeps running its stored basal rates - so a fixed short interval would drain
 * the stick for no possible gain.
 *
 * The interval is not cleared the moment the pump answers. It is cleared only after the pump
 * has stayed reachable for [settleMinutes]. Without that, a link that keeps dropping straight
 * back would restart at the shortest interval every time and probe far more often than this
 * schedule intends.
 */
class ProbeSchedule(
    private val firstIntervalMinutes: Int = DEFAULT_FIRST_INTERVAL_MINUTES,
    private val maxIntervalMinutes: Int = DEFAULT_MAX_INTERVAL_MINUTES,
    private val settleMinutes: Int = DEFAULT_SETTLE_MINUTES
) {

    /** How many probes have gone out since the driver started waiting. */
    var attempt: Int = 0
        private set

    /** Current delay between probes, in minutes. 0 means the schedule is idle. */
    var intervalMinutes: Int = 0
        private set

    private var nextAtMillis: Long = 0L
    private var lastRecoveryMillis: Long = 0L

    /** The pump is reachable. Clears the backoff once it has stayed that way long enough. */
    fun onHealthy(nowMillis: Long) {
        if (lastRecoveryMillis == 0L || nowMillis - lastRecoveryMillis > minutesToMillis(settleMinutes)) {
            reset()
        } else {
            // Recovered, but not settled. Hold the interval and pause the schedule.
            nextAtMillis = 0L
            attempt = 0
        }
    }

    /**
     * The pump is unreachable. Returns true when a probe is due now.
     *
     * The first call after the driver starts waiting only arms the schedule, so one interval
     * always passes before the first probe.
     */
    fun shouldProbe(nowMillis: Long): Boolean {
        if (nextAtMillis == 0L) {
            if (intervalMinutes == 0) intervalMinutes = firstIntervalMinutes
            nextAtMillis = nowMillis + minutesToMillis(intervalMinutes)
            return false
        }
        return nowMillis >= nextAtMillis
    }

    /** A probe went unanswered. Doubles the delay, up to the cap. */
    fun onProbeFailed(nowMillis: Long) {
        attempt++
        intervalMinutes = (intervalMinutes * 2).coerceAtMost(maxIntervalMinutes)
        nextAtMillis = nowMillis + minutesToMillis(intervalMinutes)
    }

    /** The pump answered. Stops probing, but keeps the interval until things settle. */
    fun onProbeSucceeded(nowMillis: Long) {
        attempt++
        lastRecoveryMillis = nowMillis
        nextAtMillis = 0L
    }

    private fun reset() {
        attempt = 0
        intervalMinutes = 0
        nextAtMillis = 0L
        lastRecoveryMillis = 0L
    }

    private fun minutesToMillis(minutes: Int): Long = minutes * 60 * 1000L

    companion object {

        const val DEFAULT_FIRST_INTERVAL_MINUTES = 1
        const val DEFAULT_MAX_INTERVAL_MINUTES = 30
        const val DEFAULT_SETTLE_MINUTES = 30
    }
}
