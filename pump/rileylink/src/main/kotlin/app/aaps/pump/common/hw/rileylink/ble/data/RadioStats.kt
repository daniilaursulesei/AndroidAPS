package app.aaps.pump.common.hw.rileylink.ble.data

/**
 * The CC1110's own counters, as `GetStatistics` reports them.
 *
 * Reading these is the only way to tell apart the two faults that both look like a timeout from
 * the app: a radio that transmitted and heard nothing back, and a radio that never transmitted.
 *
 * Two counters the chip sends are deliberately not kept here. `crc_failure_count` is declared and
 * transmitted by the firmware but never incremented anywhere in it, so it always reads zero, and
 * `spi_sync_failure_count` is only incremented in one build variant of the firmware, so a zero
 * means nothing. Reporting either would look like a measurement without being one.
 *
 * @param uptimeMs how long the radio chip has been running. A value lower than the one before it
 *   means the chip restarted, which resets every other counter here.
 * @param rxOverflow receive overflows.
 * @param rxFifoOverflow receive queue overflows.
 * @param packetsReceived packets received whole. The firmware counts these only on success, so
 *   this does not move for a timeout or an interrupted listen.
 * @param packetsSent packets transmitted. The firmware counts one per transmission, so a command
 *   that repeats a packet 200 times adds 201, not 1.
 */
data class RadioStats(
    val uptimeMs: Long,
    val rxOverflow: Int,
    val rxFifoOverflow: Int,
    val packetsReceived: Int,
    val packetsSent: Int
) {

    companion object {

        /**
         * Status byte, four bytes of uptime, then eight 16 bit counters, big endian:
         *
         * ```
         * 0     status (0xDD on success)
         * 1..4  uptime in milliseconds
         * 5..6  rx overflow            11..12  packets sent
         * 7..8  rx queue overflow      13..14  crc failures  (never incremented, ignored)
         * 9..10 packets received       15..16  spi sync failures (one build only, ignored)
         *                              17..20  two placeholders, always zero
         * ```
         */
        const val REPLY_SIZE = 1 + 4 + 8 * 2

        private const val STATUS_SUCCESS = 0xDD

        /**
         * Reads a `GetStatistics` reply.
         *
         * @return the counters, or null when the reply is not a whole successful one. A timeout
         *   or an error reply is a single byte, and reading counters out of it would invent
         *   numbers.
         */
        fun parse(raw: ByteArray?): RadioStats? {
            if (raw == null || raw.size < REPLY_SIZE) return null
            if ((raw[0].toInt() and 0xff) != STATUS_SUCCESS) return null
            fun word(at: Int): Int = ((raw[at].toInt() and 0xff) shl 8) or (raw[at + 1].toInt() and 0xff)
            val uptime = ((raw[1].toLong() and 0xff) shl 24) or ((raw[2].toLong() and 0xff) shl 16) or
                ((raw[3].toLong() and 0xff) shl 8) or (raw[4].toLong() and 0xff)
            return RadioStats(
                uptimeMs = uptime,
                rxOverflow = word(5),
                rxFifoOverflow = word(7),
                packetsReceived = word(9),
                packetsSent = word(11)
            )
        }
    }
}
