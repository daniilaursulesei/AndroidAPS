package app.aaps.pump.common.hw.rileylink

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WakeUpBackoffTest {

    @Test fun `the first retry comes soon, because one missed burst means little`() {
        assertEquals(30_000L, wakeUpBackoffMs(1))
    }

    @Test fun `it backs off as the failures pile up`() {
        assertEquals(60_000L, wakeUpBackoffMs(2))
        assertEquals(120_000L, wakeUpBackoffMs(3))
    }

    @Test fun `and stops growing, so a pump that comes back is found within five minutes`() {
        assertEquals(WAKE_UP_BACKOFF_CAP_MS, wakeUpBackoffMs(4))
        assertEquals(WAKE_UP_BACKOFF_CAP_MS, wakeUpBackoffMs(40))
        assertEquals(WAKE_UP_BACKOFF_CAP_MS, wakeUpBackoffMs(4000))
    }

    @Test fun `the gap never shrinks as failures grow`() {
        var previous = 0L
        (1..10).forEach { n ->
            val gap = wakeUpBackoffMs(n)
            assertTrue(gap >= previous, "gap fell at $n: $gap after $previous")
            previous = gap
        }
    }

    @Test fun `no failures means no waiting`() {
        assertEquals(0L, wakeUpBackoffMs(0))
        assertEquals(0L, wakeUpBackoffMs(-1))
    }

    @Test fun `the first wake up of a run keeps the full listen`() {
        assertEquals(WAKE_UP_LISTEN_MS, wakeUpListenMs(0))
        assertEquals(25_000, wakeUpListenMs(0))
    }

    /**
     * Fourteen answered wake ups were measured on hardware and the slowest took 4.4 s. The retry
     * window has to stay comfortably above that or a pump that would have answered gets cut off.
     */
    @Test fun `a retry listens for long enough to catch any answer ever measured`() {
        val slowestAnswerMs = 4_400
        assertEquals(WAKE_UP_RETRY_LISTEN_MS, wakeUpListenMs(1))
        assertTrue(
            wakeUpListenMs(1) >= slowestAnswerMs * 2,
            "retry window ${wakeUpListenMs(1)} ms is not twice the slowest answer seen"
        )
        assertTrue(wakeUpListenMs(1) < WAKE_UP_LISTEN_MS, "a retry must be cheaper than the first try")
    }

    @Test fun `the announced duration matches the window it will actually use`() {
        assertEquals(28, wakeUpSecondsFor(0))
        assertEquals(13, wakeUpSecondsFor(3))
    }

    /**
     * The point of the whole change, in numbers.
     *
     * On 22 September the pump was out of reach from 20:32 to 20:46. The driver cleared the awake
     * window on every failure, so it ran a wake up every 30 s and each one cost about 29 s: the
     * radio was transmitting or listening essentially without pause.
     */
    @Test fun `an absent pump no longer keeps the radio on almost constantly`() {
        val windowSeconds = 14 * 60

        // What it did: a wake every 30 s, each costing the full 25 s listen plus the burst.
        val oldRadioOn = (windowSeconds / 30) * 29

        // What it does now: wait out the backoff, then one wake that costs the short listen.
        var elapsed = 0
        var failures = 0
        var newRadioOn = 0
        while (elapsed < windowSeconds) {
            val cost = wakeUpSecondsFor(failures)
            newRadioOn += cost
            failures++
            elapsed += cost + (wakeUpBackoffMs(failures) / 1000).toInt()
        }

        assertTrue(oldRadioOn > windowSeconds * 0.9, "the old behaviour really was near constant: $oldRadioOn s")
        assertTrue(
            newRadioOn * 4 < oldRadioOn,
            "radio time only fell from $oldRadioOn s to $newRadioOn s, which is not worth the change"
        )
    }

    @Test fun `a pump that answers again is not punished for its past failures`() {
        // The driver resets the counter on any answer, so the next failure starts at the short
        // gap again rather than at the cap. This pins the values that behaviour relies on.
        assertTrue(wakeUpBackoffMs(1) < wakeUpBackoffMs(4))
        assertEquals(WAKE_UP_LISTEN_MS, wakeUpListenMs(0))
    }
}
