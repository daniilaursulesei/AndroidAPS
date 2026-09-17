package app.aaps.pump.common.hw.rileylink.ble.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for RadioStats, the reader of a `GetStatistics` reply.
 *
 * The replies here are built the way the firmware writes them, so the test fails if the layout
 * assumed by the parser and the layout the chip sends ever drift apart.
 */
class RadioStatsTest {

    /**
     * Builds a reply exactly as `cmd_get_statistics` does: a status byte, the uptime as four
     * bytes, then eight counters of two bytes each, all big endian.
     */
    private fun firmwareReply(
        uptimeMs: Long,
        rxOverflow: Int = 0,
        rxFifoOverflow: Int = 0,
        received: Int = 0,
        sent: Int = 0,
        status: Int = 0xDD
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.add(status.toByte())
        out.add(((uptimeMs shr 24) and 0xff).toByte())
        out.add(((uptimeMs shr 16) and 0xff).toByte())
        out.add(((uptimeMs shr 8) and 0xff).toByte())
        out.add((uptimeMs and 0xff).toByte())
        // The two counters after the ones we keep are the crc and spi failure counts, which this
        // firmware never increments, then two placeholders.
        for (v in listOf(rxOverflow, rxFifoOverflow, received, sent, 0, 0, 0, 0)) {
            out.add(((v shr 8) and 0xff).toByte())
            out.add((v and 0xff).toByte())
        }
        return out.toByteArray()
    }

    @Test
    fun `a firmware reply is 21 bytes`() {
        assertEquals(21, firmwareReply(0).size)
        assertEquals(21, RadioStats.REPLY_SIZE)
    }

    @Test
    fun `reads every field back`() {
        val stats = RadioStats.parse(
            firmwareReply(uptimeMs = 3_600_123, rxOverflow = 2, rxFifoOverflow = 3, received = 14, sent = 1207)
        )
        assertEquals(3_600_123L, stats?.uptimeMs)
        assertEquals(2, stats?.rxOverflow)
        assertEquals(3, stats?.rxFifoOverflow)
        assertEquals(14, stats?.packetsReceived)
        assertEquals(1207, stats?.packetsSent)
    }

    @Test
    fun `a wake up that repeats its packet 200 times adds 201 to packets sent`() {
        val before = RadioStats.parse(firmwareReply(uptimeMs = 1000, sent = 100))
        val after = RadioStats.parse(firmwareReply(uptimeMs = 34000, sent = 301))
        assertEquals(201, after!!.packetsSent - before!!.packetsSent)
    }

    @Test
    fun `counters are unsigned 16 bit`() {
        val stats = RadioStats.parse(firmwareReply(uptimeMs = 1, received = 0xFFFF, sent = 0xFFFF))
        assertEquals(65535, stats?.packetsReceived)
        assertEquals(65535, stats?.packetsSent)
    }

    @Test
    fun `uptime uses all four bytes`() {
        assertEquals(4_294_967_295L, RadioStats.parse(firmwareReply(uptimeMs = 4_294_967_295L))?.uptimeMs)
    }

    @Test
    fun `a chip restart shows as the uptime going backwards`() {
        val before = RadioStats.parse(firmwareReply(uptimeMs = 900_000, sent = 5000))
        val after = RadioStats.parse(firmwareReply(uptimeMs = 1_200, sent = 3))
        assertEquals(true, after!!.uptimeMs < before!!.uptimeMs)
    }

    @Test
    fun `nothing is read out of a reply that is not one`() {
        assertNull(RadioStats.parse(null))
        assertNull(RadioStats.parse(ByteArray(0)))
        assertNull(RadioStats.parse(byteArrayOf(0xDD.toByte())))
        assertNull(RadioStats.parse(byteArrayOf(0xAA.toByte())))
        assertNull(RadioStats.parse(byteArrayOf(0xBB.toByte())))
        assertNull(RadioStats.parse(byteArrayOf(0x22.toByte())))
    }

    @Test
    fun `a reply cut short is refused rather than half read`() {
        assertNull(RadioStats.parse(firmwareReply(uptimeMs = 1, sent = 7).copyOf(20)))
    }

    @Test
    fun `a reply of the right length but not a success is refused`() {
        assertNull(RadioStats.parse(firmwareReply(uptimeMs = 1, sent = 7, status = 0xAA)))
    }
}
