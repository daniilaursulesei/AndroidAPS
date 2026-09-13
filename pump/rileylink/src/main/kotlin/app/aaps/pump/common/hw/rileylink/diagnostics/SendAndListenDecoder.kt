package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType

/**
 * The fields of a `SendAndListen` command as a radio would read them off the wire.
 *
 * @property timeoutMs How long the radio is told to listen, per try.
 * @property retryCount How many extra tries the radio is told to make.
 * @property busyMs Worst case time the radio is taken off the air: `timeoutMs * (retryCount + 1)`.
 */
data class DecodedSendAndListen(
    val sendChannel: Int,
    val repeatCount: Int,
    val delayMs: Int,
    val listenChannel: Int,
    val timeoutMs: Long,
    val retryCount: Int,
    val preambleMs: Int,
    val packetBytes: Int
) {

    val busyMs: Long get() = timeoutMs * (retryCount + 1L)
}

/**
 * Reads a raw `SendAndListen` back into fields, using a chosen wire format.
 *
 * This exists so a log line and the diagnostics screen can show the numbers the *radio* will act
 * on, not the numbers the app meant. When the two formats disagree those numbers are wildly
 * different, and a hex dump hides that completely:
 *
 * ```
 * 05 00 C8 00 00 00 00 61 A8 00 …   built as version 1, meaning "listen 25 s"
 *                                   read as version 2, meaning "listen 6 400 000 ms, 169 tries"
 * ```
 *
 * Both readings come from the same bytes. Printing both is the only way to see the mismatch.
 */
object SendAndListenDecoder {

    /**
     * Decodes [payload] (the command without its leading length byte).
     *
     * @param v2 true to read the version 2 layout (two byte delay, two byte preamble), false for
     *   the older version 1 layout (one byte delay, no preamble).
     * @return the fields, or null if [payload] is not a `SendAndListen` or is too short.
     */
    fun decode(payload: ByteArray, v2: Boolean): DecodedSendAndListen? {
        if (payload.isEmpty()) return null
        if (payload[0] != RileyLinkCommandType.SendAndListen.code) return null

        val headerSize = if (v2) 13 else 10
        if (payload.size < headerSize) return null

        fun u8(i: Int): Int = payload[i].toInt() and 0xFF
        fun u16(i: Int): Int = (u8(i) shl 8) or u8(i + 1)
        fun u32(i: Int): Long =
            (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()

        var p = 1
        val sendChannel = u8(p); p += 1
        val repeatCount = u8(p); p += 1
        val delayMs = if (v2) u16(p).also { p += 2 } else u8(p).also { p += 1 }
        val listenChannel = u8(p); p += 1
        val timeoutMs = u32(p); p += 4
        val retryCount = u8(p); p += 1
        val preambleMs = if (v2) u16(p).also { p += 2 } else 0

        return DecodedSendAndListen(
            sendChannel = sendChannel,
            repeatCount = repeatCount,
            delayMs = delayMs,
            listenChannel = listenChannel,
            timeoutMs = timeoutMs,
            retryCount = retryCount,
            preambleMs = preambleMs,
            packetBytes = payload.size - headerSize
        )
    }
}
