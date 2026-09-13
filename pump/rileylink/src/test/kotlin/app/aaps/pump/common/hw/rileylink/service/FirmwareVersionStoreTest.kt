package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersionBase
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class FirmwareVersionStoreTest {

    private lateinit var preferences: Preferences
    private lateinit var store: FirmwareVersionStore

    private val mac = "84:FD:27:63:E6:B8"

    @BeforeEach fun setup() {
        preferences = mock()
        store = FirmwareVersionStore(preferences)
        whenever(preferences.get(RileyLinkStringKey.FirmwareVersionCache)).thenReturn("")
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn("")
    }

    private fun stored(value: String) {
        whenever(preferences.get(RileyLinkStringKey.FirmwareVersionCache)).thenReturn(value)
    }

    @Test fun `remembers a version for the device it came from`() {
        stored("$mac|2.2")
        assertEquals(RileyLinkFirmwareVersionBase.Version_2_2, store.get(mac))
    }

    @Test fun `never reuses a version stored for a different device`() {
        stored("$mac|2.2")
        assertNull(store.get("AA:BB:CC:DD:EE:FF"))
    }

    @Test fun `matches the address whatever case it is written in`() {
        stored("${mac.lowercase()}|2.2")
        assertEquals(RileyLinkFirmwareVersionBase.Version_2_2, store.get(mac))
    }

    @Test fun `returns nothing when there is nothing stored`() {
        assertNull(store.get(mac))
        assertNull(store.get(null))
        assertNull(store.get(""))
    }

    /**
     * The live address is cleared on a deliberate disconnect, which is one of the moments the cache
     * exists for. Falling back to the configured address keeps it working then.
     */
    @Test fun `falls back to the configured address when the live one is missing`() {
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn(mac)
        stored("$mac|2.2")
        assertEquals(RileyLinkFirmwareVersionBase.Version_2_2, store.get(null))
        assertEquals(RileyLinkFirmwareVersionBase.Version_2_2, store.get(""))
    }

    @Test fun `stores against the configured address when the live one is missing`() {
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn(mac)
        store.put(null, RileyLinkFirmwareVersionBase.Version_2_2)
        verify(preferences).put(RileyLinkStringKey.FirmwareVersionCache, "$mac|2.2")
    }

    @Test fun `ignores a stored value it cannot read`() {
        stored("nonsense")
        assertNull(store.get(mac))
        stored("$mac|9.9")
        assertNull(store.get(mac))
    }

    @Test fun `stores a version read from the radio`() {
        store.put(mac, RileyLinkFirmwareVersionBase.Version_2_2)
        verify(preferences).put(RileyLinkStringKey.FirmwareVersionCache, "$mac|2.2")
    }

    /** Storing a guess would make it look like knowledge on the next connection. */
    @Test fun `never stores an unknown version`() {
        store.put(mac, RileyLinkFirmwareVersionBase.UnknownVersion)
        verify(preferences, never()).put(eq(RileyLinkStringKey.FirmwareVersionCache), any<String>())
    }

    @Test fun `never stores when no address is known at all`() {
        store.put(null, RileyLinkFirmwareVersionBase.Version_2_2)
        store.put("", RileyLinkFirmwareVersionBase.Version_2_2)
        verify(preferences, never()).put(eq(RileyLinkStringKey.FirmwareVersionCache), any<String>())
    }
}
