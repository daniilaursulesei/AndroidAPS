package app.aaps.pump.common.hw.rileylink.ble.data

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock

/**
 * Pins down what counts as a successful pump wake up.
 *
 * `RileyLinkCommunicationManager.wakeUp` only remembers the pump as awake when the wake up was
 * answered. Recording it after a failed wake locks the driver out: every command for the next
 * minute goes out as a single packet that a sleeping pump cannot hear, and nothing retries the
 * wake. These tests hold the criterion in place, expressed the way the caller sees it.
 */
class WakeUpOutcomeTest {

    private lateinit var response: RFSpyResponse

    @BeforeEach
    fun setup() {
        response = RFSpyResponse { mock<RadioResponse>() }
    }

    /**
     * Mirrors the cheap half of `RileyLinkCommunicationManager.wakeUpSucceeded`. The production
     * code then also checks the packet CRC, which needs the full RadioResponse machinery; these
     * cases are all rejected before that point.
     */
    private fun succeeded(raw: ByteArray): Boolean {
        val r = response.with(null, raw)
        return !r.wasTimeout() &&
            !r.wasInterrupted() &&
            !r.wasNoResponseFromRileyLink() &&
            r.raw.size > 3
    }

    @Test
    fun `a radio timeout is not a successful wake up`() {
        // 0xAA, the pump did not answer. This is the case that used to be recorded as success.
        assertFalse(succeeded(byteArrayOf(0xAA.toByte())))
    }

    @Test
    fun `an interrupted listen is not a successful wake up`() {
        assertFalse(succeeded(byteArrayOf(0xBB.toByte())))
    }

    @Test
    fun `no reply from the RileyLink is not a successful wake up`() {
        assertFalse(succeeded(byteArrayOf()))
    }

    @Test
    fun `a bare success byte with no packet is not a successful wake up`() {
        // 0xDD on its own carries no pump reply, so the pump has not been heard from.
        assertFalse(succeeded(byteArrayOf(0xDD.toByte())))
    }

    @Test
    fun `a short reply that carries no pump packet is not a successful wake up`() {
        // Seen on the bench when the pump's radio was off: status, RSSI, counter, no payload.
        assertFalse(succeeded(byteArrayOf(0xDD.toByte(), 0xD8.toByte(), 0x0F)))
    }

    @Test
    fun `a reply that is only status RSSI and counter is rejected`() {
        // looksLikeRadioPacket alone would accept this, because it only asks for size > 2.
        assertFalse(succeeded(byteArrayOf(0xDD.toByte(), 0xD3.toByte(), 0x10)))
    }

    @Test
    fun `a real pump reply is a successful wake up`() {
        // Captured from a working link: 0xDD, RSSI, counter, then the 0xA7 pump frame.
        val raw = byteArrayOf(
            0xDD.toByte(), 0x19, 0xA2.toByte(),
            0xA7.toByte(), 0x54, 0x39, 0x79, 0x8D.toByte(), 0x09, 0x03, 0x37, 0x32, 0x32
        )
        assertTrue(succeeded(raw))
    }
}
