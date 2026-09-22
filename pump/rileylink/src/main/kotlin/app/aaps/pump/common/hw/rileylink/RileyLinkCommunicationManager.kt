package app.aaps.pump.common.hw.rileylink

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.defs.PumpDeviceState
import app.aaps.core.interfaces.utils.Round.isSame
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.pump.ByteUtil.shortHexString
import app.aaps.pump.common.hw.rileylink.ble.RFSpy
import app.aaps.pump.common.hw.rileylink.ble.RileyLinkCommunicationException
import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyScanResults
import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyTrial
import app.aaps.pump.common.hw.rileylink.ble.data.RFSpyResponse
import app.aaps.pump.common.hw.rileylink.ble.data.RLMessage
import app.aaps.pump.common.hw.rileylink.ble.data.RadioPacket
import app.aaps.pump.common.hw.rileylink.ble.data.RadioResponse
import app.aaps.pump.common.hw.rileylink.diagnostics.WaitReason
import app.aaps.pump.common.hw.rileylink.ble.defs.RLMessageType
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkBLEError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkPumpDevice
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkLongKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import app.aaps.pump.common.hw.rileylink.service.tasks.ServiceTaskExecutor
import app.aaps.pump.common.hw.rileylink.service.tasks.WakeAndTuneTask
import java.util.Locale

/**
 * This is abstract class for RileyLink Communication, this one needs to be extended by specific "Pump" class.
 *
 *
 * Created by andy on 5/10/18.
 */
abstract class RileyLinkCommunicationManager<T : RLMessage>(
    val aapsLogger: AAPSLogger,
    val preferences: Preferences,
    val rileyLinkServiceData: RileyLinkServiceData,
    val serviceTaskExecutor: ServiceTaskExecutor,
    val rfspy: RFSpy,
    val activePlugin: ActivePlugin,
    val rileyLinkUtil: RileyLinkUtil,
    val wakeAndTuneTaskProvider: () -> WakeAndTuneTask,
    val radioResponseProvider: () -> RadioResponse
) {

    @Suppress("PrivatePropertyName")
    private val ALLOWED_PUMP_UNREACHABLE = 10 * 60 * 1000 // 10 minutes

    protected var receiverDeviceAwakeForMinutes: Int = 1 // override this in constructor of specific implementation
    protected var receiverDeviceID: String? = null // String representation of receiver device (ex. Pump (xxxxxx) or Pod (yyyyyy))
    protected var lastGoodReceiverCommunicationTime: Long = 0
        get() {
            // If we have a value of zero, we need to load from prefs.
            if (field == 0L) {
                field = preferences.get(RileyLinkLongKey.LastGoodDeviceCommunicationTime)
                // Might still be zero, but that's fine.
            }
            val minutesAgo: Double = (System.currentTimeMillis() - field) / (1000.0 * 60.0)
            aapsLogger.debug(LTag.PUMPBTCOMM, "Last good pump communication was $minutesAgo minutes ago.")
            return field
        }

    private var nextWakeUpRequired = 0L

    /** Status, RSSI and packet counter. A reply no longer than this carries no pump packet. */
    private val WAKE_UP_REPLY_HEADER_SIZE = 3

    /**
     * Wake ups in a row that went unanswered. Drives how long to wait before the next one and
     * how long to listen on it, so a pump that has gone is not hammered. Reset by any answer.
     */
    private var consecutiveWakeFailures = 0
    private var timeoutCount = 0

    @Throws(RileyLinkCommunicationException::class)
    protected open fun sendAndListen(msg: T, timeoutMs: Int, repeatCount: Int = 0, retryCount: Int = 0, extendPreambleMs: Int = 0): T {
        // internal flag

        val showPumpMessages = true
        if (showPumpMessages) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Sent:" + shortHexString(msg.getTxData()))
        }

        val rfSpyResponse = rfspy.transmitThenReceive(
            RadioPacket(rileyLinkUtil, msg.getTxData()),
            0.toByte(), repeatCount.toByte(), 0.toByte(), 0.toByte(), timeoutMs, retryCount.toByte(), extendPreambleMs
        )

        val radioResponse = rfSpyResponse?.getRadioResponse() ?: throw RileyLinkCommunicationException(RileyLinkBLEError.Interrupted, null)
        val response = createResponseMessage(radioResponse.getPayload())

        if (response.isValid()) {
            // Mark this as the last time we heard from the pump.
            rememberLastGoodDeviceCommunicationTime()
        } else {
            aapsLogger.warn(
                LTag.PUMPBTCOMM, String.format(
                    Locale.ENGLISH, "isDeviceReachable. Response is invalid ! [noResponseFromRileyLink=%b, interrupted=%b, timeout=%b, unknownCommand=%b, invalidParam=%b]",
                    rfSpyResponse.wasNoResponseFromRileyLink(), rfSpyResponse.wasInterrupted(), rfSpyResponse.wasTimeout(), rfSpyResponse.isUnknownCommand(), rfSpyResponse.isInvalidParam()
                )
            )

            if (rfSpyResponse.wasTimeout()) {
                if (rileyLinkServiceData.targetDevice.tuneUpEnabled) {
                    timeoutCount++

                    val diff = System.currentTimeMillis() - getPumpDevice().lastConnectionTimeMillis

                    if (diff > ALLOWED_PUMP_UNREACHABLE) {
                        aapsLogger.warn(LTag.PUMPBTCOMM, "We reached max time that Pump can be unreachable. Starting Tuning.")
                        rfspy.diag.decided(
                            "Started a tune up",
                            "the pump has not answered for ${diff / 60000} min, and the limit is ${ALLOWED_PUMP_UNREACHABLE / 60000} min"
                        )
                        rfspy.readRadioStats("pumpUnreachable")
                        // Once, not once per timeout. Every timeout while the pump is away used
                        // to queue its own run, and they were served one after the other.
                        serviceTaskExecutor.startTaskOnce(wakeAndTuneTaskProvider())
                        timeoutCount = 0
                    }
                }

                throw RileyLinkCommunicationException(RileyLinkBLEError.Timeout, null)
            } else if (rfSpyResponse.wasInterrupted()) {
                throw RileyLinkCommunicationException(RileyLinkBLEError.Interrupted, null)
            } else if (rfSpyResponse.wasNoResponseFromRileyLink()) {
                throw RileyLinkCommunicationException(RileyLinkBLEError.NoResponse, null)
            }
        }

        if (showPumpMessages) {
            aapsLogger.info(LTag.PUMPBTCOMM, "Received:" + shortHexString(rfSpyResponse.getRadioResponse().getPayload()))
        }

        return response
    }

    abstract fun createResponseMessage(payload: ByteArray): T

    abstract fun setPumpDeviceState(pumpDeviceState: PumpDeviceState)

    fun wakeUp(force: Boolean) {
        wakeUp(receiverDeviceAwakeForMinutes, force)
    }

    fun getNotConnectedCount(): Int {
        return rfspy.notConnectedCount
    }

    /**
     * Wake the pump, unless we have good reason to believe it is still awake.
     *
     * The pump only listens for a short window after it has been woken, so a command sent to a
     * sleeping pump is simply lost. Waking it costs a 3 s burst of 200 repeats followed by a
     * 25 s listen, so the result is remembered and the wake is skipped while the window lasts.
     *
     * The window is only remembered when the pump actually answered. Recording it after a
     * failed wake is what used to happen, and it locks the driver out of talking to the pump:
     * the wake fails, the driver notes the pump as awake anyway, and every command for the next
     * [receiverDeviceAwakeForMinutes] goes out as a single packet that a sleeping pump can
     * never hear. Nothing then retries the wake, so the failure holds until the window expires.
     *
     * Still open: [receiverDeviceAwakeForMinutes] is a fixed guess rather than anything the pump
     * tells us, and a shorter window would cost the pump less battery.
     */
    fun wakeUp(@Suppress("unused") durationMinutes: Int, force: Boolean) {
        // receiverDeviceAwakeForMinutes = duration_minutes;

        setPumpDeviceState(PumpDeviceState.WakingUp)

        if (force) {
            // A forced wake up is a deliberate one, so it gets a clean slate: no backoff to wait
            // out and the full listen window rather than the short retry one.
            nextWakeUpRequired = 0L
            consecutiveWakeFailures = 0
        }

        if (System.currentTimeMillis() > nextWakeUpRequired) {
            // Say what this is before it starts. A wake up is a 3 s burst followed by a 25 s
            // listen, and a screen that simply stops for half a minute reads as a crash.
            val listenMs = wakeUpListenMs(consecutiveWakeFailures)
            rfspy.diag.waiting(WaitReason.WAKING_PUMP, wakeUpSecondsFor(consecutiveWakeFailures))
            aapsLogger.info(LTag.PUMPBTCOMM, "Waking pump...")

            val pumpMsgContent = createPumpMessageContent(RLMessageType.ReadSimpleData) // simple
            val resp = rfspy.transmitThenReceive(
                RadioPacket(rileyLinkUtil, pumpMsgContent), 0.toByte(), 200.toByte(),
                0.toByte(), 0.toByte(), listenMs, 0.toByte()
            )
            aapsLogger.info(LTag.PUMPBTCOMM, "wakeup: raw response is " + shortHexString(resp?.raw))

            rfspy.diag.waiting(null)
            if (wakeUpSucceeded(resp)) {
                consecutiveWakeFailures = 0
                nextWakeUpRequired = System.currentTimeMillis() + (receiverDeviceAwakeForMinutes.toLong() * 60 * 1000)
                rfspy.diag.decided(
                    "Treating the pump as awake for $receiverDeviceAwakeForMinutes min",
                    "it answered the wake up"
                )
            } else {
                // The pump is not awake, so the awake window must NOT be set: commands sent
                // during it would go to a pump that cannot hear them. But clearing it outright
                // made every following command pay another 26 s wake, which on an absent pump
                // ran the radio at a near 100 % duty cycle and flattened the RileyLink battery.
                // Back off instead: soon at first, then further apart.
                consecutiveWakeFailures++
                val backoffMs = wakeUpBackoffMs(consecutiveWakeFailures)
                nextWakeUpRequired = System.currentTimeMillis() + backoffMs
                aapsLogger.warn(
                    LTag.PUMPBTCOMM,
                    "Wake up failed, pump is not awake. Next wake up in ${backoffMs / 1000} s " +
                        "(failure $consecutiveWakeFailures in a row)."
                )
                rfspy.diag.decided(
                    "Waiting ${backoffMs / 1000} s before waking the pump again",
                    "$consecutiveWakeFailures wake up(s) in a row went unanswered"
                )
            }
        } else {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Last pump communication was recent, not waking pump.")
        }

        // long lastGoodPlus = getLastGoodReceiverCommunicationTime() + (receiverDeviceAwakeForMinutes * 60 * 1000);
        //
        // if (System.currentTimeMillis() > lastGoodPlus || force) {
        // LOG.info("Waking pump...");
        //
        // byte[] pumpMsgContent = createPumpMessageContent(RLMessageType.PowerOn);
        // RFSpyResponse resp = rfspy.transmitThenReceive(new RadioPacket(pumpMsgContent), (byte) 0, (byte) 200, (byte)
        // 0, (byte) 0, 15000, (byte) 0);
        // LOG.info("wakeup: raw response is " + ByteUtil.INSTANCE.shortHexString(resp.getRaw()));
        // } else {
        // LOG.trace("Last pump communication was recent, not waking pump.");
        // }
    }

    fun setRadioFrequencyForPump(freqMHz: Double) {
        rfspy.setBaseFrequency(freqMHz)
    }

    fun tuneForDevice(): Double {
        return scanForDevice(rileyLinkServiceData.rileyLinkTargetFrequency.scanFrequencies)
    }

    /**
     * If user changes pump and one pump is running in US freq, and other in WW, then previously set frequency would be
     * invalid,
     * so we would need to retune. This checks that saved frequency is correct range.
     *
     * @param frequency
     * @return
     */
    fun isValidFrequency(frequency: Double): Boolean {
        val scanFrequencies = rileyLinkServiceData.rileyLinkTargetFrequency.scanFrequencies

        return if (scanFrequencies.size == 1) isSame(scanFrequencies[0], frequency)
        else (scanFrequencies[0] <= frequency && scanFrequencies[scanFrequencies.size - 1] >= frequency)
    }

    /**
     * Do device connection, with wakeup
     *
     * @return
     */
    abstract fun tryToConnectToDevice(): Boolean

    /**
     * Did the pump answer the wake up?
     *
     * A timeout means it did not hear us, and an interrupted or empty reply means the RileyLink
     * never got to listen. Beyond that the reply has to actually carry a pump packet, judged the
     * same way [tryToConnectToDevice] judges one, because a reply can come back well formed and
     * still contain nothing from the pump.
     *
     * `looksLikeRadioPacket` alone is not enough: it only asks for more than two bytes, and a
     * radio that hears noise answers with status, RSSI and a counter and no payload at all. On
     * the bench, a pump that had switched its radio off produced exactly `DD D8 0F` over and
     * over, with the counter climbing as the receiver triggered on noise.
     */
    private fun wakeUpSucceeded(response: RFSpyResponse?): Boolean {
        if (response == null) return false
        if (response.wasTimeout() || response.wasInterrupted() || response.wasNoResponseFromRileyLink()) return false
        // Status, RSSI and packet counter come first, so anything this short holds no pump packet.
        if (response.raw.size <= WAKE_UP_REPLY_HEADER_SIZE) return false
        return try {
            response.getRadioResponse().isValid()
        } catch (_: RileyLinkCommunicationException) {
            false
        }
    }

    private fun scanForDevice(frequencies: DoubleArray): Double {
        aapsLogger.info(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Scanning for receiver (%s)", receiverDeviceID))
        // A scan is the densest run of transmissions the app ever makes, so reading the radio's
        // counters either side of it gives the clearest answer to the one question a scan that
        // finds nothing cannot answer by itself: did this RileyLink transmit at all?
        rfspy.readRadioStats("beforeScan")
        rfspy.diag.waiting(WaitReason.TUNING)
        // The link this scan is about. A scan holds the radio for most of a minute, and it keeps
        // that hold while the link underneath it dies and comes back. Everything it measures
        // after that belongs to a link that is gone, and the start up sequence of the new link is
        // stuck behind it the whole time.
        val startedOnLink = rileyLinkServiceData.radioSession.generation
        wakeUp(receiverDeviceAwakeForMinutes, false)
        val results = FrequencyScanResults()

        for (i in frequencies.indices) {
            if (!rileyLinkServiceData.radioSession.stillOnSameLink(startedOnLink)) {
                aapsLogger.warn(LTag.PUMPBTCOMM, "The link changed during the scan. Stopping after ${results.trials.size} of ${frequencies.size} frequencies.")
                rfspy.diag.waiting(null)
                rfspy.diag.decided(
                    "Stopped the tune up",
                    "the Bluetooth link to the RileyLink changed while it was scanning, so the results are about a link that is gone"
                )
                return 0.0
            }
            val tries = 3
            val trial = FrequencyTrial()
            trial.frequencyMHz = frequencies[i]
            rfspy.setBaseFrequency(frequencies[i])

            (0 until tries).forEach { j ->
                val pumpMsgContent = createPumpMessageContent(RLMessageType.ReadSimpleData)
                val resp = rfspy.transmitThenReceive(
                    RadioPacket(rileyLinkUtil, pumpMsgContent), 0.toByte(), 0.toByte(),
                    0.toByte(), 0.toByte(), 1250, 0.toByte()
                )
                if (resp?.wasTimeout() == true) {
                    aapsLogger.error(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "scanForPump: Failed to find pump at frequency %.3f", frequencies[i]))
                    // A try that heard nothing is the worst result there is, so it has to score
                    // like one. Leaving it out made the average a measure of the tries that
                    // happened to work, which let a frequency that answered twice out of three
                    // beat one that answered every time.
                    trial.rssiList.add(FrequencyTrial.NO_ANSWER_RSSI)
                } else if (resp?.looksLikeRadioPacket() == true) {
                    val radioResponse = radioResponseProvider()

                    try {
                        radioResponse.init(resp.raw)

                        if (radioResponse.isValid()) {
                            trial.rssiList.add(calculateRssi(radioResponse.rssi))
                            trial.successes++
                        } else {
                            aapsLogger.warn(LTag.PUMPBTCOMM, "Failed to parse radio response: " + shortHexString(resp.raw))
                            trial.rssiList.add(FrequencyTrial.NO_ANSWER_RSSI)
                        }
                    } catch (_: RileyLinkCommunicationException) {
                        aapsLogger.warn(LTag.PUMPBTCOMM, "Failed to decode radio response: " + shortHexString(resp.raw))
                        trial.rssiList.add(FrequencyTrial.NO_ANSWER_RSSI)
                    }
                } else {
                    aapsLogger.error(LTag.PUMPBTCOMM, "scanForPump: raw response is " + shortHexString(resp?.raw))
                    trial.rssiList.add(FrequencyTrial.NO_ANSWER_RSSI)
                }
                trial.tries++
            }
            trial.calculateAverage()

            results.trials.add(trial)
        }

        results.dateTime = System.currentTimeMillis()

        val stringBuilder = StringBuilder("Scan results:\n")

        for (k in results.trials.indices) {
            val one = results.trials[k]

            stringBuilder.append(String.format("Scan Result[%s]: Freq=%s, avg RSSI = %s\n", k, one.frequencyMHz, one.averageRSSI.toString() + ", RSSIs =" + one.rssiList))
        }

        aapsLogger.info(LTag.PUMPBTCOMM, stringBuilder.toString())

        rfspy.readRadioStats("afterScan")

        results.sort() // sorts in ascending order

        val bestTrial = results.trials[results.trials.size - 1]
        results.bestFrequencyMHz = bestTrial.frequencyMHz
        // The whole table, not only the frequency it picked. A scan where every frequency scored
        // the same means the pump answered none of them, and reduced to one number that reads
        // exactly like a scan which found a clear winner.
        rfspy.diag.scanFinished(results)
        rfspy.diag.waiting(null)
        if (bestTrial.successes > 0) {
            rfspy.setBaseFrequency(results.bestFrequencyMHz)
            aapsLogger.debug(LTag.PUMPBTCOMM, "Best frequency found: " + results.bestFrequencyMHz)
            rfspy.diag.decided(
                "Using %.2f MHz".format(results.bestFrequencyMHz),
                "strongest of ${results.trials.size} frequencies, answered ${bestTrial.successes} of ${bestTrial.tries} tries"
            )
            return results.bestFrequencyMHz
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "No pump response during scan.")
            rfspy.diag.decided(
                "Gave up the tune up",
                "the pump answered on none of the ${results.trials.size} frequencies tried"
            )
            return 0.0
        }
    }

    private fun calculateRssi(rssiIn: Int): Int {
        val rssiOffset = 73
        val outRssi =
            if (rssiIn >= 128) ((rssiIn - 256) / 2) - rssiOffset
            else (rssiIn / 2) - rssiOffset
        return outRssi
    }

    abstract fun createPumpMessageContent(type: RLMessageType): ByteArray

    protected fun rememberLastGoodDeviceCommunicationTime() {
        lastGoodReceiverCommunicationTime = System.currentTimeMillis()

        preferences.put(RileyLinkLongKey.LastGoodDeviceCommunicationTime, lastGoodReceiverCommunicationTime)

        getPumpDevice().setLastCommunicationToNow()
    }

    fun clearNotConnectedCount() {
        rfspy.notConnectedCount = 0
    }

    private fun getPumpDevice(): RileyLinkPumpDevice {
        return activePlugin.activePumpInternal as RileyLinkPumpDevice
    }

    abstract fun isDeviceReachable(): Boolean
}
