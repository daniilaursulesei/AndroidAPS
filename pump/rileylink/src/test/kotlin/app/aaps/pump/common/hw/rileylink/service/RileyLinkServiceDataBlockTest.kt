package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * Which device a block is judged against.
 *
 * This is the piece that decides whether a radio command is allowed, so it is worth its own test.
 * The failure it guards against is the quiet one: a deliberate disconnect clears the live address,
 * and if "no live address" had read as "not blocked", every block would have come undone the moment
 * the link dropped.
 */
class RileyLinkServiceDataBlockTest {

    private lateinit var preferences: Preferences
    private lateinit var data: RileyLinkServiceData

    private val bench = "FF:FF:FF:FF:FF:FF"
    private val other = "84:FD:27:63:E6:B8"

    @BeforeEach fun setup() {
        preferences = mock()
        whenever(preferences.get(RileyLinkStringKey.BlockedDevices)).thenReturn("")
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn("")
        data = RileyLinkServiceData(mock<AAPSLogger>(), mock<RileyLinkUtil>(), mock<RxBus>(), RileyLinkBlockList(preferences))
    }

    private fun blocked(value: String) {
        whenever(preferences.get(RileyLinkStringKey.BlockedDevices)).thenReturn(value)
    }

    private fun configured(value: String) {
        whenever(preferences.get(RileyLinkStringKey.MacAddress)).thenReturn(value)
    }

    @Test fun `nothing blocked means the current device is not blocked`() {
        configured(bench)
        data.rileyLinkAddress = bench
        assertFalse(data.isCurrentDeviceBlocked)
    }

    @Test fun `the live device is judged when there is one`() {
        configured(other)
        blocked(bench)
        data.rileyLinkAddress = bench
        assertTrue(data.isCurrentDeviceBlocked)
    }

    @Test fun `a live device that is not blocked is allowed even when another one is`() {
        configured(other)
        blocked(other)
        data.rileyLinkAddress = bench
        assertFalse(data.isCurrentDeviceBlocked)
    }

    @Test fun `with no live address the configured device is judged`() {
        configured(bench)
        blocked(bench)
        data.rileyLinkAddress = null
        assertTrue(data.isCurrentDeviceBlocked)
    }

    @Test fun `dropping the link does not undo a block`() {
        // The whole point. disconnectRileyLink() clears rileyLinkAddress, and the block has to
        // survive that or it would end itself the first time the link went down.
        configured(bench)
        blocked(bench)
        data.rileyLinkAddress = bench
        assertTrue(data.isCurrentDeviceBlocked)
        data.rileyLinkAddress = null
        assertTrue(data.isCurrentDeviceBlocked)
    }

    @Test fun `no configured device and no live device is never blocked`() {
        blocked(bench)
        data.rileyLinkAddress = null
        assertFalse(data.isCurrentDeviceBlocked)
    }

    @Test fun `case does not matter for the live address`() {
        blocked(bench)
        data.rileyLinkAddress = bench.lowercase()
        assertTrue(data.isCurrentDeviceBlocked)
    }
}
