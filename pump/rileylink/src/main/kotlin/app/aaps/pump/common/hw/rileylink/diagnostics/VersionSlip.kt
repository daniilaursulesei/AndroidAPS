package app.aaps.pump.common.hw.rileylink.diagnostics

/**
 * A firmware version string that was found behind a framing slip.
 *
 * @property shiftBits How many bits the reply had to be moved to become readable text. A real
 *   reply needs 0. Any other value means the bytes arrived out of step.
 * @property recovered The readable text that came out of the shift.
 */
data class VersionSlipResult(
    val shiftBits: Int,
    val recovered: String
)

/**
 * Looks for a firmware version string that is present in the reply but shifted by a few bits.
 *
 * A RileyLink is two chips: the BLE113 talks to the phone, the CC1110 owns the radio, and they talk
 * to each other over a serial link. One extra or missing bit on that link moves every following bit
 * out of place. The bytes still arrive, and the reply still has the right length, but read on the
 * wrong boundaries it looks like noise.
 *
 * A real case: the CC1110 answered `subg_rfspy 2.2.21` and the phone read
 * `BA E7 EA C4 CE BE E4 CC E6 E0 F2 40 64 5C 64 5C 64 62`. Moved by one bit, 16 of the 17 bytes are
 * exact.
 *
 * This class only reports. It does not repair the reply and nothing acts on the result, because a
 * shifted link is a hardware fault and hiding it would be wrong. The point is that the log says
 * "the radio answered correctly and the link damaged it" instead of "the radio sent noise".
 */
object VersionSlip {

    /**
     * The part of `subg_rfspy` used to recognise a hit.
     *
     * Deliberately not the whole word. A slip often damages one bit near the start, so the first
     * one or two letters can be wrong while the rest is perfect. This tail is still long enough
     * that it will not turn up in random bytes.
     */
    private const val MARKER = "bg_rfspy"

    /** Letters kept as themselves when building the readable form. */
    private fun Byte.isPrintableAscii(): Boolean = toInt() and 0xFF in 0x20..0x7E

    /** Stands in for a byte the slip damaged, so the rest of the string still shows. */
    private const val DAMAGED = '?'

    /**
     * Turns the decoded bytes into something a person can read.
     *
     * A slip usually damages a bit or two near the start as well as moving the frame, so the
     * recovered text has isolated bad bytes in it. Those are replaced rather than treated as the
     * end of the string: stopping at the first one threw away the whole version and reported a
     * single letter, which said nothing about what had really arrived.
     *
     * Two bad bytes in a row does mean the end - by then we have left the string and are reading
     * whatever followed it.
     */
    private fun readableFrom(decoded: ByteArray, start: Int): String {
        val text = StringBuilder()
        var consecutiveBad = 0
        for (i in start until decoded.size) {
            val b = decoded[i]
            if (b.isPrintableAscii()) {
                consecutiveBad = 0
                text.append((b.toInt() and 0xFF).toChar())
            } else {
                consecutiveBad++
                if (consecutiveBad >= 2) break
                text.append(DAMAGED)
            }
        }
        return text.toString().trimEnd(DAMAGED)
    }

    /**
     * Searches [raw] for a version string hidden by a bit slip.
     *
     * @return the shift and the text if one is found, or null if the bytes hold no version string
     *   at any shift. Returns null for an unshifted reply too, since that is the normal case and
     *   is not a slip.
     */
    fun detect(raw: ByteArray?): VersionSlipResult? {
        if (raw == null || raw.size < MARKER.length + 2) return null

        val bits = StringBuilder(raw.size * 8)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            for (bit in 7 downTo 0) bits.append((v shr bit) and 1)
        }

        // Shift 0 is the normal reading. If the version were readable there we would never have
        // been called, so start at 1 and only report a real slip.
        for (shift in 1..7) {
            val shifted = bits.substring(shift)
            val byteCount = shifted.length / 8
            if (byteCount < MARKER.length) continue

            val decoded = ByteArray(byteCount) { i ->
                shifted.substring(i * 8, i * 8 + 8).toInt(2).toByte()
            }
            val text = String(CharArray(byteCount) { i -> (decoded[i].toInt() and 0xFF).toChar() })
            val at = text.indexOf(MARKER)
            if (at < 0) continue

            // The two letters before the marker are "su" in a healthy reply. Include them when they
            // are there, so the report shows how much of the string survived.
            val from = if (at >= 2) at - 2 else 0
            return VersionSlipResult(shiftBits = shift, recovered = readableFrom(decoded, from))
        }
        return null
    }
}
