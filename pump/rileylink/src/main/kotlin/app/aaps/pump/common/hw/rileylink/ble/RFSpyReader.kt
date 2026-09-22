package app.aaps.pump.common.hw.rileylink.ble

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.core.utils.pump.ThreadUtil
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Created by geoff on 5/26/16.
 */
class RFSpyReader internal constructor(private val aapsLogger: AAPSLogger, private val rileyLinkBle: RileyLinkBLE) {

    private var executor = Executors.newSingleThreadExecutor()
    private val waitForRadioData = Semaphore(0, true)
    private val mDataQueue = LinkedBlockingQueue<ByteArray>()
    private var acquireCount = 0
    private var releaseCount = 0
    private var stopAtNull = true

    /**
     * Set while a reply is expected that may contain zero bytes and must arrive whole.
     *
     * Volatile because it is written by the thread sending the command and read by the reader
     * thread. Only one command is on the radio at a time, so the window cannot overlap another
     * command's reply.
     */
    @Volatile private var keepWholeReply = false

    /** Replies waiting to be read. Anything above zero when idle means a reply lost its owner. */
    val queuedResponses: Int get() = mDataQueue.size

    private val repliesEnqueued = AtomicLong(0)

    /**
     * How many replies this reader has ever put on the queue.
     *
     * A command reads this after the drain and again when its write finishes. If the number has
     * moved, a reply landed while the command was still being written, which means it was asked
     * for by an earlier command - this one had not reached the radio yet. That is the exact test
     * for a crossed reply, and it needs no timing.
     */
    val repliesSeen: Long get() = repliesEnqueued.get()

    /**
     * Notifications that arrived but have not been read out yet.
     *
     * Each one makes the reader thread do one more read of the radio data characteristic. A count
     * that keeps growing means the reader and the radio are out of step.
     */
    val pendingPermits: Int get() = waitForRadioData.availablePermits()
    fun setRileyLinkEncodingType(encodingType: RileyLinkEncodingType) {
        aapsLogger.debug("setRileyLinkEncodingType: $encodingType")
        stopAtNull = !(encodingType == RileyLinkEncodingType.Manchester || encodingType == RileyLinkEncodingType.FourByteSixByteRileyLink)
    }

    /**
     * Runs [block] with the reply cut at the first zero byte turned off.
     *
     * The cut exists because the radio data characteristic is padded with zeros, so for text and
     * for encoded packets the first zero is the end. A binary reply of fixed length is different:
     * its zeros are data. Reading the radio's counters without this gives a single 0xDD.
     */
    fun <T> keepingWholeReply(block: () -> T): T {
        keepWholeReply = true
        try {
            return block()
        } finally {
            keepWholeReply = false
        }
    }

    /**
     * Waits up to [timeoutMs] for one reply, or takes one that is already there.
     *
     * A timeout of zero is the drain used before a command goes out: it takes whatever is waiting
     * and never blocks.
     *
     * There used to be a guard here that skipped the read when the queue was NOT empty and a real
     * timeout had been asked for, and returned null on the spot. It reported "no response" while
     * the reply sat in the queue, and the next command then took that reply as its own. In one
     * field log it fired 56 times in 32 seconds and left the radio one command behind for the
     * whole of it. Measured on the bench, a RileyLink answers a local command in 109 to 188 ms
     * while the app allows 5000, so a command that reports nothing has almost always been told
     * nothing by this function rather than by the radio.
     *
     * The timeout still has to match the length of the radio operation, or the caller gives up
     * while the radio is still listening.
     */
    fun poll(timeoutMs: Int): ByteArray? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}Entering poll at t==${SystemClock.uptimeMillis()}, timeout is $timeoutMs mDataQueue size is ${mDataQueue.size}")
        try {
            // Blocks until the timeout or until there is data, and gives back null on a timeout.
            // With a zero timeout it returns at once, which is what the drain wants.
            val dataFromQueue = mDataQueue.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            if (dataFromQueue != null)
                aapsLogger.debug(LTag.PUMPBTCOMM, "Got data [${ByteUtil.shortHexString(dataFromQueue)}] at t==${SystemClock.uptimeMillis()}")
            else
                aapsLogger.debug(LTag.PUMPBTCOMM, "Got data [null] at t==" + SystemClock.uptimeMillis())
            return dataFromQueue
        } catch (_: InterruptedException) {
            aapsLogger.error(LTag.PUMPBTCOMM, "poll: Interrupted waiting for data")
            // Put the flag back for whoever set it, rather than swallowing the interrupt.
            Thread.currentThread().interrupt()
        }
        return null
    }

    // Call this from the "response count" notification handler.
    fun newDataIsAvailable() {
        releaseCount++
        aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}waitForRadioData released(count=$releaseCount) at t=${SystemClock.uptimeMillis()}")
        waitForRadioData.release()
    }

    fun start() {
        executor.execute {
            val serviceUUID = UUID.fromString(GattAttributes.SERVICE_RADIO)
            val radioDataUUID = UUID.fromString(GattAttributes.CHARA_RADIO_DATA)
            while (true) {
                try {
                    acquireCount++
                    waitForRadioData.acquire()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "${ThreadUtil.sig()}waitForRadioData acquired (count=$acquireCount) at t=${SystemClock.uptimeMillis()}")
                    SystemClock.sleep(1)
                    var result = rileyLinkBle.readCharacteristicBlocking(serviceUUID, radioDataUUID)
                    SystemClock.sleep(1)
                    if (result.resultCode == BLECommOperationResult.RESULT_SUCCESS) {
                        if (stopAtNull && !keepWholeReply) {
                            // only data up to the first null is valid
                            result.value?.let { resultValue ->
                                for (i in resultValue.indices) {
                                    if (resultValue[i].toInt() == 0) {
                                        result.value = ByteUtil.substring(resultValue, 0, i)
                                        break
                                    }
                                }
                            }
                        }
                        // Counted before it is offered, so a caller that reads the count right
                        // after its own write can tell that this reply was already here.
                        repliesEnqueued.incrementAndGet()
                        mDataQueue.add(result.value)
                    } else if (result.resultCode == BLECommOperationResult.RESULT_INTERRUPTED)
                        aapsLogger.error(LTag.PUMPBTCOMM, "Read operation was interrupted")
                    else if (result.resultCode == BLECommOperationResult.RESULT_TIMEOUT)
                        aapsLogger.error(LTag.PUMPBTCOMM, "Read operation on Radio Data timed out")
                    else if (result.resultCode == BLECommOperationResult.RESULT_BUSY)
                        aapsLogger.error(LTag.PUMPBTCOMM, "FAIL: RileyLinkBLE reports operation already in progress")
                    else if (result.resultCode == BLECommOperationResult.RESULT_NONE)
                        aapsLogger.error(LTag.PUMPBTCOMM, "FAIL: got invalid result code: ${result.resultCode}")
                } catch (_: InterruptedException) {
                    aapsLogger.error(LTag.PUMPBTCOMM, "Interrupted while waiting for data")
                }
            }
        }
    }
}