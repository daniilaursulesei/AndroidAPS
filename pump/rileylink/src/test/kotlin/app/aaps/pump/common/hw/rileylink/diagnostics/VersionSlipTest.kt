package app.aaps.pump.common.hw.rileylink.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VersionSlipTest {

    /**
     * Bytes taken from a real log. The CC1110 answered correctly and the serial link to the BLE113
     * inserted one bit, so the phone read what looked like noise. Sixteen of the seventeen bytes
     * are exact once the stream is moved back.
     */
    private val slippedReply = byteArrayOf(
        0xBA.toByte(), 0xE7.toByte(), 0xEA.toByte(), 0xC4.toByte(), 0xCE.toByte(), 0xBE.toByte(),
        0xE4.toByte(), 0xCC.toByte(), 0xE6.toByte(), 0xE0.toByte(), 0xF2.toByte(), 0x40,
        0x64, 0x5C, 0x64, 0x5C, 0x64, 0x62
    )

    @Test fun `finds the version string behind a one bit slip`() {
        val result = VersionSlip.detect(slippedReply)
        requireNotNull(result)
        assertEquals(7, result.shiftBits)
        assertTrue(result.recovered.endsWith("bg_rfspy 2.2.21"), "was: ${result.recovered}")
    }

    @Test fun `a healthy reply is not reported as a slip`() {
        val healthy = byteArrayOf(0xDD.toByte()) + "subg_rfspy 2.2".toByteArray(Charsets.US_ASCII)
        assertNull(VersionSlip.detect(healthy))
    }

    @Test fun `real noise is not reported as a slip`() {
        assertNull(VersionSlip.detect(byteArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x11, 0x22)))
    }

    @Test fun `short or missing replies are ignored`() {
        assertNull(VersionSlip.detect(null))
        assertNull(VersionSlip.detect(byteArrayOf()))
        assertNull(VersionSlip.detect(byteArrayOf(0xDD.toByte())))
    }
}
