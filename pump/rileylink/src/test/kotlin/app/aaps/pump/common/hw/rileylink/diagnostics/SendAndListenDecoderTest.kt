package app.aaps.pump.common.hw.rileylink.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SendAndListenDecoderTest {

    /** A wake up command built correctly for a version 2 radio: 200 repeats, listen 25 s. */
    private val builtV2 = byteArrayOf(
        0x05, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x61, 0xA8.toByte(), 0x00, 0x00, 0x00,
        0xA7.toByte(), 0x82.toByte(), 0x81.toByte(), 0x75, 0x8D.toByte(), 0x00, 0xE7.toByte()
    )

    /** The same intent built with the old format, as seen in the field logs. */
    private val builtV1 = byteArrayOf(
        0x05, 0x00, 0xC8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x61, 0xA8.toByte(), 0x00,
        0xA9.toByte(), 0x66, 0xB2.toByte(), 0x6B, 0x15, 0xA5.toByte(), 0x68, 0xD5.toByte(), 0x55, 0x39, 0x65, 0x00
    )

    @Test fun `a correct version 2 command reads as a 25 second listen`() {
        val d = SendAndListenDecoder.decode(builtV2, v2 = true)
        requireNotNull(d)
        assertEquals(200, d.repeatCount)
        assertEquals(25_000L, d.timeoutMs)
        assertEquals(0, d.retryCount)
        assertEquals(25_000L, d.busyMs)
    }

    /**
     * The whole reason the decoder exists. The same bytes a version 1 build produces are read by a
     * version 2 radio as a listen of 6 400 000 ms repeated 170 times, which is why the radio goes
     * quiet for hours and only a power cycle brings it back.
     */
    @Test fun `the old format read by a version 2 radio becomes an enormous listen`() {
        val asBuilt = SendAndListenDecoder.decode(builtV1, v2 = false)
        requireNotNull(asBuilt)
        assertEquals(25_000L, asBuilt.timeoutMs)
        assertEquals(0, asBuilt.retryCount)

        val asRadioReads = SendAndListenDecoder.decode(builtV1, v2 = true)
        requireNotNull(asRadioReads)
        assertEquals(6_400_000L, asRadioReads.timeoutMs)
        assertEquals(169, asRadioReads.retryCount)
        assertEquals(6_400_000L * 170, asRadioReads.busyMs)
    }

    @Test fun `other commands and short buffers decode to nothing`() {
        assertNull(SendAndListenDecoder.decode(byteArrayOf(0x06, 0x0B, 0x77), v2 = true))
        assertNull(SendAndListenDecoder.decode(byteArrayOf(0x05, 0x00), v2 = true))
        assertNull(SendAndListenDecoder.decode(byteArrayOf(), v2 = true))
    }
}
