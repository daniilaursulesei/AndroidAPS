package app.aaps.pump.medtronic.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ProbeScheduleTest {

    private val minute = 60 * 1000L
    private var now = 1_000_000L

    private fun ProbeSchedule.runUnreachableFor(minutes: Int): Int {
        var probes = 0
        repeat(minutes) {
            now += minute
            if (shouldProbe(now)) {
                onProbeFailed(now)
                probes++
            }
        }
        return probes
    }

    @Test
    fun `first probe waits one interval, it does not fire immediately`() {
        val schedule = ProbeSchedule()
        assertThat(schedule.shouldProbe(now)).isFalse()
        assertThat(schedule.intervalMinutes).isEqualTo(1)
    }

    @Test
    fun `interval doubles after every unanswered probe and stops at the cap`() {
        val schedule = ProbeSchedule()
        schedule.shouldProbe(now)                       // arm at 1 min
        val seen = mutableListOf<Int>()
        repeat(8) {
            now += 60 * minute                          // always past due
            assertThat(schedule.shouldProbe(now)).isTrue()
            schedule.onProbeFailed(now)
            seen.add(schedule.intervalMinutes)
        }
        assertThat(seen).containsExactly(2, 4, 8, 16, 30, 30, 30, 30).inOrder()
    }

    @Test
    fun `the real outage costs 19 probes, not one every minute`() {
        // 7 h 44 min, the logged case.
        val probes = ProbeSchedule().runUnreachableFor(464)
        assertThat(probes).isEqualTo(19)
    }

    @Test
    fun `a day of silence stays near the cap`() {
        // A flat pump battery silences the radio for good. Must not busy-probe.
        val probes = ProbeSchedule().runUnreachableFor(24 * 60)
        assertThat(probes).isAtMost(24 * 60 / ProbeSchedule.DEFAULT_MAX_INTERVAL_MINUTES + 6)
    }

    @Test
    fun `a successful probe stops the schedule`() {
        val schedule = ProbeSchedule()
        schedule.shouldProbe(now)
        now += 2 * minute
        assertThat(schedule.shouldProbe(now)).isTrue()
        schedule.onProbeSucceeded(now)

        schedule.onHealthy(now)
        now += minute
        // Healthy, so nothing is due; the call only re-arms.
        assertThat(schedule.shouldProbe(now)).isFalse()
    }

    @Test
    fun `a flapping link keeps the long interval instead of restarting at one minute`() {
        val schedule = ProbeSchedule()
        // Back off to the cap.
        schedule.shouldProbe(now)
        repeat(6) {
            now += 60 * minute
            schedule.shouldProbe(now)
            schedule.onProbeFailed(now)
        }
        assertThat(schedule.intervalMinutes).isEqualTo(30)

        // Pump answers, then drops out again a minute later.
        schedule.onProbeSucceeded(now)
        schedule.onHealthy(now)
        now += minute

        assertThat(schedule.intervalMinutes).isEqualTo(30)
        assertThat(schedule.shouldProbe(now)).isFalse()     // re-arms at 30, not 1
        now += 29 * minute
        assertThat(schedule.shouldProbe(now)).isFalse()
        now += 2 * minute
        assertThat(schedule.shouldProbe(now)).isTrue()
    }

    @Test
    fun `staying reachable long enough clears the backoff`() {
        val schedule = ProbeSchedule()
        schedule.shouldProbe(now)
        repeat(6) {
            now += 60 * minute
            schedule.shouldProbe(now)
            schedule.onProbeFailed(now)
        }
        schedule.onProbeSucceeded(now)

        now += (ProbeSchedule.DEFAULT_SETTLE_MINUTES + 1) * minute
        schedule.onHealthy(now)

        assertThat(schedule.intervalMinutes).isEqualTo(0)
        assertThat(schedule.attempt).isEqualTo(0)
        // Next outage starts from the short interval again.
        assertThat(schedule.shouldProbe(now)).isFalse()
        assertThat(schedule.intervalMinutes).isEqualTo(1)
    }
}
