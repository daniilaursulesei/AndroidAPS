package app.aaps.pump.common.hw.rileylink.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RileyLinkReleaseTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun `nothing is held to begin with`() {
        val release = RileyLinkRelease()
        assertFalse(release.isHeld(t0))
        assertEquals(0, release.minutesLeft(t0))
        assertEquals(0L, release.untilMillis)
    }

    @Test
    fun `a hold lasts the number of minutes asked for`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertTrue(release.isHeld(t0))
        assertTrue(release.isHeld(t0 + 9 * minute))
        assertFalse(release.isHeld(t0 + 10 * minute))
    }

    @Test
    fun `the hold ends by itself`() {
        val release = RileyLinkRelease()
        release.hold(t0, 1)
        assertFalse(release.isHeld(t0 + minute + 1))
        assertEquals(0, release.minutesLeft(t0 + minute + 1))
    }

    @Test
    fun `minutes left rounds up so a part minute still reads as one`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertEquals(10, release.minutesLeft(t0))
        assertEquals(1, release.minutesLeft(t0 + 9 * minute))
        assertEquals(1, release.minutesLeft(t0 + 10 * minute - 1))
        assertEquals(0, release.minutesLeft(t0 + 10 * minute))
    }

    @Test
    fun `asking again replaces the hold instead of adding to it`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        val second = release.hold(t0 + minute, 10)
        assertEquals(t0 + 11 * minute, second)
        assertEquals(10, release.minutesLeft(t0 + minute))
    }

    @Test
    fun `a hold cannot be longer than the cap`() {
        val release = RileyLinkRelease()
        release.hold(t0, 60 * 24)
        assertEquals(RileyLinkRelease.MAX_MINUTES, release.minutesLeft(t0))
    }

    @Test
    fun `a hold is always at least one minute`() {
        val release = RileyLinkRelease()
        release.hold(t0, 0)
        assertTrue(release.isHeld(t0))
        assertEquals(1, release.minutesLeft(t0))
        release.hold(t0, -5)
        assertEquals(1, release.minutesLeft(t0))
    }

    @Test
    fun `it can be ended early`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        release.release()
        assertFalse(release.isHeld(t0))
        assertEquals(0, release.minutesLeft(t0))
    }
}
