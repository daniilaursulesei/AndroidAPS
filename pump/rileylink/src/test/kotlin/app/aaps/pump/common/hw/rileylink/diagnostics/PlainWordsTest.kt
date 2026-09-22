package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyScanResults
import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyTrial
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlainWordsTest {

    /**
     * Taken from a real log during a failure in the field. The radio answered its counters
     * command: 3087 packets transmitted, none received. That last number was the whole
     * diagnosis and the screen showed it as hex.
     */
    private val realCountersReply = byteArrayOf(
        0xDD.toByte(), 0x00, 0x53, 0xEC.toByte(), 0xF1.toByte(), 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x0C, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    @Test fun `a counters reply is read out as counters`() {
        val words = describeReply("GetStatistics", realCountersReply)
        assertTrue(words.contains("transmitted 3087"), words)
        assertTrue(words.contains("received 0"), words)
    }

    @Test fun `a timeout says the radio heard nothing`() {
        assertEquals("the radio listened and heard nothing", describeReply("SendAndListen", byteArrayOf(0xAA.toByte())))
    }

    @Test fun `nothing coming back is not the same as an empty answer`() {
        assertEquals("nothing came back from the RileyLink", describeReply("SendAndListen", null))
        assertEquals("the RileyLink answered with an empty message", describeReply("SendAndListen", byteArrayOf()))
    }

    @Test fun `a success with no pump packet is said plainly`() {
        val words = describeReply("SendAndListen", byteArrayOf(0xDD.toByte(), 0xD8.toByte(), 0x0F))
        assertTrue(words.contains("no pump packet"), words)
    }

    @Test fun `a success carrying a pump packet says how much came back`() {
        val reply = byteArrayOf(0xDD.toByte(), 0x41, 0x10) + ByteArray(7) { 0xA7.toByte() }
        assertTrue(describeReply("SendAndListen", reply).contains("7 byte(s) from the pump"))
    }

    @Test fun `an unknown status is reported as a number, not guessed at`() {
        assertTrue(statusWords(0x5E).contains("0x5E"))
    }

    // ------------------------------------------------------------------ the serial check

    @Test fun `the serial the app calls is compared with the one it is set to`() {
        val check = checkSerial("543979", byteArrayOf(0x54, 0x39, 0x79))
        assertEquals("543979", check.onWire)
        assertTrue(check.agree)
    }

    /**
     * The failure this check exists for. The settings said 543979 and every packet went to
     * 159474, because the pump ID is only loaded when the service is built.
     */
    @Test fun `a serial that does not reach the radio is caught`() {
        val check = checkSerial("543979", byteArrayOf(0x15, 0x94.toByte(), 0x74))
        assertEquals("159474", check.onWire)
        assertEquals("543979", check.configured)
        assertFalse(check.agree)
    }

    @Test fun `an unset address does not count as agreement`() {
        assertFalse(checkSerial("543979", byteArrayOf(0, 0, 0)).agree)
        assertFalse(checkSerial("543979", null).agree)
        assertFalse(checkSerial(null, byteArrayOf(0x54, 0x39, 0x79)).agree)
        assertNull(checkSerial("543979", byteArrayOf(0, 0, 0)).onWire)
    }

    // ------------------------------------------------------------------ the frequency table

    private fun scan(vararg pairs: Pair<Double, Int>, best: Double = 0.0): FrequencyScanResults {
        val results = FrequencyScanResults()
        pairs.forEach { (mhz, successes) ->
            val trial = FrequencyTrial()
            trial.frequencyMHz = mhz
            trial.tries = 3
            trial.successes = successes
            trial.rssiList = ArrayList(if (successes > 0) listOf(-55, -57, -56) else listOf(-99, -99, -99))
            trial.calculateAverage()
            results.trials.add(trial)
        }
        results.bestFrequencyMHz = best
        return results
    }

    @Test fun `every frequency tried gets a row, weakest first by frequency`() {
        val rows = scanRows(scan(916.70 to 0, 916.45 to 3, 916.55 to 1, best = 916.45))
        assertEquals(listOf(916.45, 916.55, 916.70), rows.map { it.frequencyMHz })
        assertEquals(3, rows[0].answered)
        assertEquals(3, rows[0].tries)
        assertTrue(rows[0].best)
        assertFalse(rows[2].best)
    }

    @Test fun `a scan nothing answered is named as such, not reduced to one number`() {
        val words = describeScan(scanRows(scan(916.45 to 0, 916.50 to 0, 916.55 to 0)))
        assertTrue(words.contains("NONE of the 3"), words)
        assertTrue(words.contains("9 attempts"), words)
        assertTrue(words.contains("wrong serial"), words)
    }

    @Test fun `a scan that found the pump reports the winner`() {
        val words = describeScan(scanRows(scan(916.45 to 3, 916.50 to 0, best = 916.45)))
        assertTrue(words.contains("answered on 1 of 2"), words)
        assertTrue(words.contains("916.45"), words)
    }

    @Test fun `no scan yet is not an empty table`() {
        assertEquals("no frequency scan has run yet", describeScan(emptyList()))
        assertTrue(scanRows(null).isEmpty())
    }

    // ------------------------------------------------------------------ the pause

    @Test fun `a pause says what it is waiting for and for how long`() {
        assertEquals(
            "No answer from the pump. Trying again in 120 s.",
            describeWait(WaitReason.NO_PUMP_ANSWER, 120)
        )
    }

    @Test fun `a wake up says why it takes half a minute`() {
        val words = describeWait(WaitReason.WAKING_PUMP, null)
        assertTrue(words.contains("25 s"), words)
        assertTrue(words.endsWith("."), words)
    }

    @Test fun `no countdown is not a countdown of zero`() {
        assertTrue(describeWait(WaitReason.TUNING, null).endsWith("frequency for the pump."))
        assertTrue(describeWait(WaitReason.TUNING, 0).endsWith("Trying again now."))
    }
}
