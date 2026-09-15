package app.aaps.implementation.maintenance

import app.aaps.implementation.logging.ErrorKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * How often an automatic error report is allowed.
 *
 * The behaviour worth pinning down is what happens during a long failure, because that is the only
 * case that matters: a RileyLink that loses the pump does not fail once, it fails every few seconds
 * until something changes. These tests walk a whole night of that and check that the phone uploads
 * a handful of files rather than a few thousand.
 */
class ErrorReportLimitTest {

    private val minute = 60L * 1000
    private val start = 1_700_000_000_000L

    @Test fun `the first fault of a kind is allowed`() {
        val limit = ErrorReportLimit()
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
    }

    @Test fun `the same fault again straight away is refused`() {
        val limit = ErrorReportLimit()
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertFalse(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertFalse(limit.allow(ErrorKind.RADIO_SILENT, start + 29 * minute))
    }

    @Test fun `the same fault after the gap is allowed again`() {
        val limit = ErrorReportLimit()
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + 30 * minute))
    }

    // The point of a per-kind gap. A radio that keeps timing out must not stop a bit slip - the
    // fault actually being hunted - from being captured the moment it appears.
    @Test fun `a different fault is not blocked by the first`() {
        val limit = ErrorReportLimit()
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertTrue(limit.allow(ErrorKind.RADIO_BIT_SLIP, start))
        assertTrue(limit.allow(ErrorKind.LINK_TIMEOUT, start))
    }

    @Test fun `a refused fault does not move the gap`() {
        val limit = ErrorReportLimit()
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertFalse(limit.allow(ErrorKind.RADIO_SILENT, start + 20 * minute))
        // Still measured from the allowed one at `start`, not from the refusal at +20.
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + 30 * minute))
    }

    @Test fun `the daily cap stops reporting`() {
        val limit = ErrorReportLimit(minGapMillis = 0, maxPerDay = 3)
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + minute))
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + 2 * minute))
        assertFalse(limit.allow(ErrorKind.RADIO_SILENT, start + 3 * minute))
    }

    @Test fun `the daily cap frees up once the day has passed`() {
        val limit = ErrorReportLimit(minGapMillis = 0, maxPerDay = 2)
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start))
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + minute))
        assertFalse(limit.allow(ErrorKind.RADIO_SILENT, start + 2 * minute))
        assertTrue(limit.allow(ErrorKind.RADIO_SILENT, start + ErrorReportLimit.DAY_MILLIS))
    }

    @Test fun `countToday reports what was allowed`() {
        val limit = ErrorReportLimit()
        limit.allow(ErrorKind.RADIO_SILENT, start)
        limit.allow(ErrorKind.RADIO_SILENT, start + minute) // refused by the gap
        limit.allow(ErrorKind.RADIO_BIT_SLIP, start + minute)
        assertEquals(2, limit.countToday(start + minute))
        // The window rolls, it does not reset. A day after the first report the first one has
        // dropped out and the second, a minute younger, has not.
        assertEquals(1, limit.countToday(start + ErrorReportLimit.DAY_MILLIS))
        assertEquals(0, limit.countToday(start + ErrorReportLimit.DAY_MILLIS + 2 * minute))
    }

    /**
     * A broken link for eight hours, logging a fault every ten seconds.
     *
     * Without a limit that is 2880 uploads. With the defaults it has to be a small number, and the
     * daily cap has to be what stops it.
     */
    @Test fun `a night of continuous failure produces few reports`() {
        val limit = ErrorReportLimit()
        var allowed = 0
        var at = start
        val end = start + 8 * 60 * minute
        while (at < end) {
            if (limit.allow(ErrorKind.RADIO_SILENT, at)) allowed++
            at += 10 * 1000
        }
        // Eight hours at one every thirty minutes is sixteen, and the daily cap of twelve bites
        // first.
        assertEquals(ErrorReportLimit.DEFAULT_MAX_PER_DAY, allowed)
    }
}
