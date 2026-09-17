package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RileyLinkBlockListTest {

    private lateinit var preferences: Preferences
    private lateinit var blockList: RileyLinkBlockList

    private val bench = "FF:FF:FF:FF:FF:FF"
    private val other = "84:FD:27:63:E6:B8"

    @BeforeEach fun setup() {
        preferences = mock()
        blockList = RileyLinkBlockList(preferences)
        stored("")
        configured("")
        name("")
    }

    private fun stored(value: String) {
        whenever(preferences.get(RileyLinkStringKey.BlockedDevices)).thenReturn(value)
    }

    private fun configured(value: String) {
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn(value)
    }

    private fun name(value: String) {
        whenever(preferences.get(RileyLinkStringKey.Name)).thenReturn(value)
    }

    // region reading

    @Test fun `nothing is blocked to start with`() {
        assertTrue(blockList.blocked().isEmpty())
        assertFalse(blockList.isBlocked(bench))
    }

    @Test fun `a stored address is blocked`() {
        stored(bench)
        assertTrue(blockList.isBlocked(bench))
    }

    @Test fun `a block never reaches another RileyLink`() {
        stored(bench)
        assertFalse(blockList.isBlocked(other))
    }

    @Test fun `the address is matched whatever case it is written in`() {
        stored(bench.lowercase())
        assertTrue(blockList.isBlocked(bench))
        assertTrue(blockList.isBlocked(bench.lowercase()))
    }

    @Test fun `a blank address is never blocked`() {
        stored(bench)
        assertFalse(blockList.isBlocked(null))
        assertFalse(blockList.isBlocked(""))
        assertFalse(blockList.isBlocked("   "))
    }

    @Test fun `several addresses are kept apart`() {
        stored("$bench,$other")
        assertTrue(blockList.isBlocked(bench))
        assertTrue(blockList.isBlocked(other))
        assertEquals(2, blockList.blocked().size)
    }

    @Test fun `blank and malformed entries are dropped rather than kept`() {
        stored(",,$bench, ,")
        assertEquals(setOf(bench), blockList.blocked())
    }

    // endregion
    // region writing

    @Test fun `blocking stores the address`() {
        blockList.block(bench)
        verify(preferences).put(RileyLinkStringKey.BlockedDevices, bench)
    }

    @Test fun `blocking keeps the addresses already stored`() {
        stored(other)
        blockList.block(bench)
        verify(preferences).put(RileyLinkStringKey.BlockedDevices, "$other,$bench")
    }

    @Test fun `blocking the same address twice writes nothing the second time`() {
        stored(bench)
        blockList.block(bench)
        verify(preferences, never()).put(eq(RileyLinkStringKey.BlockedDevices), any<String>())
    }

    @Test fun `a blank address is never stored`() {
        blockList.block(null)
        blockList.block("  ")
        verify(preferences, never()).put(eq(RileyLinkStringKey.BlockedDevices), any<String>())
    }

    @Test fun `unblocking removes only that address`() {
        stored("$bench,$other")
        blockList.unblock(bench)
        verify(preferences).put(RileyLinkStringKey.BlockedDevices, other)
    }

    @Test fun `unblocking an address that was not blocked writes nothing`() {
        stored(other)
        blockList.unblock(bench)
        verify(preferences, never()).put(eq(RileyLinkStringKey.BlockedDevices), any<String>())
    }

    @Test fun `unblocking the last address leaves the list empty`() {
        stored(bench)
        blockList.unblock(bench)
        verify(preferences).put(RileyLinkStringKey.BlockedDevices, "")
    }

    @Test fun `an address blocked in lower case can be unblocked in upper case`() {
        stored(bench.lowercase())
        blockList.unblock(bench)
        verify(preferences).put(RileyLinkStringKey.BlockedDevices, "")
    }

    // endregion
    // region the configured device

    @Test fun `the configured address is read from preferences`() {
        configured(bench)
        assertEquals(bench, blockList.configuredAddress())
    }

    @Test fun `no configured address reads as none`() {
        configured("")
        assertNull(blockList.configuredAddress())
        assertFalse(blockList.isConfiguredBlocked())
    }

    @Test fun `the configured device is blocked when its address is`() {
        configured(bench)
        stored(bench)
        assertTrue(blockList.isConfiguredBlocked())
    }

    @Test fun `blocking a different device leaves the configured one alone`() {
        configured(bench)
        stored(other)
        assertFalse(blockList.isConfiguredBlocked())
    }

    @Test fun `the configured name is used when there is one`() {
        name("EmaLink")
        assertEquals("EmaLink", blockList.configuredName())
    }

    @Test fun `a blank name reads as none`() {
        name("   ")
        assertNull(blockList.configuredName())
    }

    // endregion
    // region the stored form

    @Test fun `parsing and formatting round trip`() {
        val addresses = setOf(bench, other)
        assertEquals(addresses, RileyLinkBlockList.parse(RileyLinkBlockList.format(addresses)))
    }

    @Test fun `formatting puts every address in one written form`() {
        assertEquals(bench, RileyLinkBlockList.format(listOf(bench.lowercase())))
    }

    @Test fun `parsing an empty preference gives nothing`() {
        assertTrue(RileyLinkBlockList.parse("").isEmpty())
    }

    @Test fun `normalise trims and upper cases, and refuses a blank`() {
        assertEquals(bench, RileyLinkBlockList.normalise("  ${bench.lowercase()}  "))
        assertNull(RileyLinkBlockList.normalise(""))
        assertNull(RileyLinkBlockList.normalise("   "))
        assertNull(RileyLinkBlockList.normalise(null))
    }

    // endregion
}
