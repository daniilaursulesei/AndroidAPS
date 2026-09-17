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
        assertFalse(release.isHeld)
        assertEquals(0, release.minutesHeld(t0))
        assertEquals(0L, release.heldSince)
    }

    @Test
    fun `a hold does not end by itself, however long it lasts`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        assertTrue(release.isHeld)
        assertTrue(release.isHeld)
        assertEquals(0, release.minutesHeld(t0))
        assertEquals(59, release.minutesHeld(t0 + 59 * minute))
        // A whole day later it is still held. This is the point of the change.
        assertTrue(release.isHeld)
        assertEquals(24 * 60, release.minutesHeld(t0 + 24 * 60 * minute))
    }

    @Test
    fun `minutes held counts whole minutes since the hold started`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        assertEquals(0, release.minutesHeld(t0 + minute - 1))
        assertEquals(1, release.minutesHeld(t0 + minute))
        assertEquals(8, release.minutesHeld(t0 + 8 * minute + 30_000))
    }

    @Test
    fun `pressing release again does not restart the count`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.hold(t0 + 5 * minute)
        assertEquals(t0, release.heldSince)
        assertEquals(5, release.minutesHeld(t0 + 5 * minute))
    }

    @Test
    fun `taking it back ends the hold`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.release()
        assertFalse(release.isHeld)
        assertEquals(0, release.minutesHeld(t0))
    }

    // The Bluetooth client is created with autoConnect, so Android re-opens the link on its own.
    // Closing the client is the only way to stop that, and then nothing re-opens it either, so the
    // hold has to remember that a reconnect is owed.

    @Test
    fun `nothing is owed before anything was released`() {
        assertFalse(RileyLinkRelease().shouldReconnect())
    }

    @Test
    fun `no reconnect while the hold is running`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        assertTrue(release.needsReconnect)
        assertFalse(release.shouldReconnect())
    }

    @Test
    fun `a reconnect is owed as soon as it is taken back`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.release()
        assertTrue(release.shouldReconnect())
    }

    @Test
    fun `the reconnect is owed once, not on every tick`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.release()
        assertTrue(release.shouldReconnect())
        release.reconnected()
        assertFalse(release.shouldReconnect())
        assertFalse(release.shouldReconnect())
    }

    @Test
    fun `releasing twice still owes exactly one reconnect`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.hold(t0 + minute)
        release.release()
        assertTrue(release.shouldReconnect())
        release.reconnected()
        assertFalse(release.shouldReconnect())
    }

    @Test
    fun `a second release after taking it back owes another reconnect`() {
        val release = RileyLinkRelease()
        release.hold(t0)
        release.release()
        release.reconnected()
        assertFalse(release.shouldReconnect())

        release.hold(t0 + 10 * minute)
        assertEquals(t0 + 10 * minute, release.heldSince)
        release.release()
        assertTrue(release.shouldReconnect())
    }
}
