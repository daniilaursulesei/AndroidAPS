package app.aaps.pump.common.hw.rileylink.ble

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The reply queue, driven the way the radio drives it.
 *
 * `newDataIsAvailable` is what the Response Count notification calls. The reader thread then reads
 * the data characteristic and puts the bytes on the queue, so these tests exercise that path with
 * a mocked Bluetooth layer rather than a copy of it.
 *
 * One reader for the whole class, because its thread runs until the process ends and there is no
 * way to stop it from outside. [reset] puts it back in a known state before each test, and every
 * check on the reply count is a difference rather than an absolute, which is the only way that
 * count is ever used anyway.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RFSpyReaderTest {

    private val aapsLogger: AAPSLogger = mock()

    /** What the radio answers, in order. Empty means it answers with no bytes. */
    private val replies = ArrayDeque<ByteArray>()

    private val reader: RFSpyReader

    init {
        val ble: RileyLinkBLE = mock()
        whenever(ble.readCharacteristicBlocking(any(), any())).thenAnswer {
            BLECommOperationResult().apply {
                value = replies.removeFirstOrNull() ?: ByteArray(0)
                resultCode = BLECommOperationResult.RESULT_SUCCESS
            }
        }
        reader = RFSpyReader(aapsLogger, ble).also { it.start() }
    }

    @BeforeEach fun reset() {
        replies.clear()
        while (reader.poll(0) != null) Unit
    }

    /** Stages [bytes] and lets the reader pick them up, one notification each. */
    private fun deliver(vararg bytes: ByteArray) {
        val target = reader.repliesSeen + bytes.size
        replies.addAll(bytes)
        repeat(bytes.size) { reader.newDataIsAvailable() }
        val deadline = System.currentTimeMillis() + 2000
        while (reader.repliesSeen < target && System.currentTimeMillis() < deadline) Thread.sleep(2)
        assertEquals(target, reader.repliesSeen, "the reader thread did not pick the replies up")
    }

    @Test fun `a reply that is already waiting is handed over, not refused`() {
        // The regression this whole change is about. There used to be a guard that skipped the
        // read when the queue was NOT empty and a real timeout had been asked for, and returned
        // null on the spot. The caller reported "no response" while its reply sat right there,
        // and the next command then took that reply as its own. In one field log it fired 56
        // times in 32 seconds and left the radio one command behind for the whole of it.
        deliver(byteArrayOf(0xDD.toByte(), 0x11))

        val got = reader.poll(5000)

        assertArrayEquals(byteArrayOf(0xDD.toByte(), 0x11), got, "the reply was in the queue and must go to the caller")
    }

    @Test fun `a caller that waits gets a reply that arrives while it waits`() {
        Thread {
            Thread.sleep(50)
            deliver(byteArrayOf(0xDD.toByte()))
        }.start()

        assertArrayEquals(byteArrayOf(0xDD.toByte()), reader.poll(5000))
    }

    @Test fun `a caller that waits and hears nothing gets null`() {
        assertNull(reader.poll(50))
    }

    @Test fun `the drain takes what is there without waiting`() {
        deliver(byteArrayOf(1), byteArrayOf(2))

        assertArrayEquals(byteArrayOf(1), reader.poll(0))
        assertArrayEquals(byteArrayOf(2), reader.poll(0))
        assertNull(reader.poll(0), "the queue is empty now")
    }

    @Test fun `the drain on an empty queue gives back null`() {
        assertNull(reader.poll(0))
    }

    @Test fun `replies come out in the order they arrived`() {
        deliver(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3))

        assertArrayEquals(byteArrayOf(1), reader.poll(1000))
        assertArrayEquals(byteArrayOf(2), reader.poll(1000))
        assertArrayEquals(byteArrayOf(3), reader.poll(1000))
    }

    @Test fun `the reply count moves once per reply`() {
        val before = reader.repliesSeen

        deliver(byteArrayOf(1))
        assertEquals(before + 1, reader.repliesSeen)

        deliver(byteArrayOf(2))
        assertEquals(before + 2, reader.repliesSeen)
    }

    @Test fun `the reply count does not move when nothing arrives`() {
        val before = reader.repliesSeen

        Thread.sleep(50)

        assertEquals(before, reader.repliesSeen)
    }

    @Test fun `reading a reply out does not move the count back`() {
        // The count is what a command compares either side of its own write, so it only ever goes
        // up. A count that fell when a reply was read would make every command look as though
        // nothing had arrived.
        deliver(byteArrayOf(1))
        val after = reader.repliesSeen

        reader.poll(0)

        assertEquals(after, reader.repliesSeen)
    }

    @Test fun `a reply arriving between the drain and the end of a write shows in the count`() {
        // The crossed reply, as the driver sees it: nothing in the queue at the drain, and the
        // count has moved by the time the write finished, so what comes back next was asked for
        // by an earlier command.
        assertNull(reader.poll(0), "the drain finds nothing")
        val seenBeforeWrite = reader.repliesSeen

        deliver(byteArrayOf(0xDD.toByte()))                 // lands during the write

        assertTrue(reader.repliesSeen > seenBeforeWrite)
    }

    @Test fun `a quiet write leaves the count where it was`() {
        assertNull(reader.poll(0))
        val seenBeforeWrite = reader.repliesSeen

        Thread.sleep(20)                                    // the write, with nothing arriving

        assertEquals(seenBeforeWrite, reader.repliesSeen)
    }
}
