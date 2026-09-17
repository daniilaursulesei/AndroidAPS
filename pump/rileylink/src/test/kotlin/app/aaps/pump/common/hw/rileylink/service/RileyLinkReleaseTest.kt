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

    // The Bluetooth client is created with autoConnect, so Android re-opens the link on its own.
    // Closing the client is the only way to stop that, and then nothing re-opens it either, so the
    // window has to remember that a reconnect is owed.

    @Test
    fun `nothing is owed before anything was released`() {
        assertFalse(RileyLinkRelease().shouldReconnect(t0))
    }

    @Test
    fun `no reconnect while the hold is running`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertTrue(release.needsReconnect)
        assertFalse(release.shouldReconnect(t0 + 5 * minute))
    }

    @Test
    fun `a reconnect is owed once the hold ends`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertTrue(release.shouldReconnect(t0 + 10 * minute))
    }

    @Test
    fun `a phone that slept past the end still reconnects on its next tick`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertTrue(release.shouldReconnect(t0 + 3 * 60 * minute))
    }

    @Test
    fun `the reconnect is owed once, not on every tick`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        assertTrue(release.shouldReconnect(t0 + 10 * minute))
        release.reconnected()
        assertFalse(release.shouldReconnect(t0 + 11 * minute))
        assertFalse(release.shouldReconnect(t0 + 60 * minute))
    }

    @Test
    fun `taking it back by hand owes the reconnect straight away`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        release.release()
        assertTrue(release.shouldReconnect(t0))
    }

    @Test
    fun `releasing twice still owes exactly one reconnect, after the second window`() {
        val release = RileyLinkRelease()
        release.hold(t0, 10)
        release.hold(t0 + minute, 10)
        assertFalse(release.shouldReconnect(t0 + 5 * minute))
        assertTrue(release.shouldReconnect(t0 + 11 * minute))
        release.reconnected()
        assertFalse(release.shouldReconnect(t0 + 12 * minute))
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
