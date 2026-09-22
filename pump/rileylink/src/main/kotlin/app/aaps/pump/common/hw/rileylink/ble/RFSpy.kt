package app.aaps.pump.common.hw.rileylink.ble

import android.os.SystemClock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.utils.StringUtil.fromBytes
import app.aaps.core.utils.pump.ByteUtil.concat
import app.aaps.core.utils.pump.ByteUtil.shortHexString
import app.aaps.core.utils.pump.ThreadUtil.sig
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.command.ResetRadio
import app.aaps.pump.common.hw.rileylink.ble.command.RileyLinkCommand
import app.aaps.pump.common.hw.rileylink.ble.command.GetStatistics
import app.aaps.pump.common.hw.rileylink.ble.command.SendAndListen
import app.aaps.pump.common.hw.rileylink.ble.command.SetHardwareEncoding
import app.aaps.pump.common.hw.rileylink.ble.command.SetPreamble
import app.aaps.pump.common.hw.rileylink.ble.command.UpdateRegister
import app.aaps.pump.common.hw.rileylink.ble.data.GattAttributes
import app.aaps.pump.common.hw.rileylink.ble.data.RFSpyResponse
import app.aaps.pump.common.hw.rileylink.ble.data.RadioPacket
import app.aaps.pump.common.hw.rileylink.ble.data.RadioStats
import app.aaps.pump.common.hw.rileylink.ble.defs.CC111XRegister
import app.aaps.pump.common.hw.rileylink.ble.defs.RXFilterMode
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersion
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersionBase
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.ble.defs.usesV2Protocol
import app.aaps.pump.common.hw.rileylink.diagnostics.FaultInjector
import app.aaps.pump.common.hw.rileylink.diagnostics.InjectableFault
import app.aaps.pump.common.hw.rileylink.diagnostics.RileyLinkDiag
import app.aaps.pump.common.hw.rileylink.diagnostics.SendAndListenDecoder
import app.aaps.pump.common.hw.rileylink.diagnostics.VersionSource
import app.aaps.pump.common.hw.rileylink.service.FirmwareVersionStore
import app.aaps.pump.common.hw.rileylink.ble.operations.BLECommOperationResult
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringPreferenceKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import org.apache.commons.lang3.ArrayUtils
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn
import kotlin.math.pow

/**
 * Created by geoff on 5/26/16.
 */
@SingleIn(AppScope::class)
@Inject
class RFSpy(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rxBus: RxBus,
    private val rileyLinkBle: RileyLinkBLE,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val rileyLinkUtil: RileyLinkUtil,
    private val rfSpyResponseProvider: () -> RFSpyResponse,
    // Not private: RileyLinkCommunicationManager records the scan and the decisions it takes,
    // and it reaches the recorder through the RFSpy it already holds rather than through a new
    // constructor parameter on an abstract class that two drivers extend.
    val diag: RileyLinkDiag,
    private val firmwareVersionStore: FirmwareVersionStore,
    private val faultInjector: FaultInjector
) {

    private val radioServiceUUID: UUID = UUID.fromString(GattAttributes.SERVICE_RADIO)
    private val radioDataUUID: UUID = UUID.fromString(GattAttributes.CHARA_RADIO_DATA)
    private val radioVersionUUID: UUID = UUID.fromString(GattAttributes.CHARA_RADIO_VERSION)
    private val batteryServiceUUID: UUID = UUID.fromString(GattAttributes.SERVICE_BATTERY)
    private val batteryLevelUUID: UUID = UUID.fromString(GattAttributes.CHARA_BATTERY_LEVEL)
    var notConnectedCount: Int = 0

    private val transactionsInFlight = AtomicInteger(0)

    /**
     * Held for a whole command and its reply, so only one command is on the radio at a time.
     *
     * The radio answers one command at a time. Two callers share this class - the service task
     * executor runs the tune up, and the command queue runs the status reads - and until this
     * lock existed both could write to the radio at once. The second command then got `0xBB`
     * (interrupted) or `0x22` (unknown command, because the chip read a length byte out of the
     * middle of the other command), and the two threads could take each other's replies. A single
     * Bluetooth operation was already guarded, but a whole round trip was not, which is where the
     * two interleaved.
     *
     * Fair, so a thread that has been waiting cannot be passed over again and again by a caller
     * that sends in a tight loop.
     */
    private val radioLock = ReentrantLock(true)

    /** The thread currently holding [radioLock], for the markers. Reporting only. */
    @Volatile private var radioHolder: String? = null

    /** The command currently holding [radioLock], for the markers. Reporting only. */
    @Volatile private var radioHolderOp: String? = null

    /**
     * True while a command is on its way to the radio and its reply has not arrived.
     *
     * Only for reporting. [radioLock] is what keeps it to one at a time; the self test reads this
     * to wait for the radio to go quiet before it runs its own checks.
     */
    val radioBusy: Boolean get() = transactionsInFlight.get() > 0

    private var reader: RFSpyReader = RFSpyReader(aapsLogger, rileyLinkBle)
    private var bleVersion: String? = null // We don't use it so no need of sophisticated logic
    private var currentFrequencyMHz: Double? = null
    private var nextBatteryCheck: Long = 0

    fun getBLEVersionCached(): String = bleVersion ?: "UNKNOWN"

    /** Replies sitting in the reader queue. For the diagnostics screen. */
    val queuedResponses: Int get() = reader.queuedResponses

    /** Notifications received but not yet read out. For the diagnostics screen. */
    val pendingPermits: Int get() = reader.pendingPermits

    // Call this after the RL services are discovered.
    // Starts an async task to read when data is available
    fun startReader() {
        rileyLinkBle.registerRadioResponseCountNotification { this.newDataIsAvailable() }
        reader.start()
    }

    // Here should go generic RL initialisation + protocol adjustments depending on
    // firmware version
    fun initializeRileyLink() {
        val threadName = Thread.currentThread().name
        diag.initEnter(threadName)
        try {
            bleVersion = getVersion()
            val cc1110Version = getCC1110Version()
            rileyLinkServiceData.versionCC110 = cc1110Version

            val fromRadio = getFirmwareVersion(aapsLogger, getBLEVersionCached(), cc1110Version)
            // FirmwareVersionStore falls back to the configured address when the live one is
            // missing, so both the read and the write below agree on which device they mean.
            val macAddress = rileyLinkServiceData.rileyLinkAddress

            // The firmware is in flash and cannot have changed since the last good read, so a
            // failed read is a failed measurement, not news. Prefer what this RileyLink already
            // told us over a guess; only a device that has never answered falls back.
            val resolved: RileyLinkFirmwareVersionBase
            val source: VersionSource
            if (fromRadio != RileyLinkFirmwareVersionBase.UnknownVersion) {
                resolved = fromRadio
                source = VersionSource.RADIO
                firmwareVersionStore.put(macAddress, fromRadio)
            } else {
                val cached = firmwareVersionStore.get(macAddress)
                if (cached != null) {
                    resolved = cached
                    source = VersionSource.CACHE
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "Firmware Version could not be read. Using last known good version for $macAddress: $cached"
                    )
                } else {
                    resolved = RileyLinkFirmwareVersionBase.UnknownVersion
                    source = VersionSource.FALLBACK
                    aapsLogger.warn(
                        LTag.PUMPBTCOMM,
                        "Firmware Version is unknown and nothing is stored for $macAddress. Using the version 2 command format."
                    )
                }
            }
            rileyLinkServiceData.firmwareVersion = resolved
            diag.versionVerdict(resolved.name, source, cc1110Version, bleVersion)

            aapsLogger.debug(
                LTag.PUMPBTCOMM,
                String.format(
                    "RileyLink - BLE Version: %s, CC1110 Version: %s, Firmware Version: %s (%s)",
                    bleVersion, cc1110Version, resolved, source
                )
            )

            // A baseline, so the first read taken later has something to be a difference from.
            // Also catches a radio chip that restarted while the app kept its Bluetooth link.
            readRadioStats("afterInit")
        } finally {
            diag.initExit(threadName)
        }
    }

    // Call this from the "response count" notification handler.
    private fun newDataIsAvailable() {
        // pass the message to the reader (which should be internal to RFSpy)
        reader.newDataIsAvailable()
    }

    fun retrieveBatteryLevel(): Int? {
        val result = rileyLinkBle.readCharacteristicBlocking(batteryServiceUUID, batteryLevelUUID)
        if (result.resultCode == BLECommOperationResult.RESULT_SUCCESS) {
            result.value?.let {
                if (ArrayUtils.isNotEmpty(it)) {
                    val value = it[0].toInt()
                    aapsLogger.debug(LTag.PUMPBTCOMM, "getBatteryLevel response received: $value")
                    return value
                } else {
                    aapsLogger.error(LTag.PUMPBTCOMM, "getBatteryLevel received an empty result. Value: $it")
                }
            }
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "getBatteryLevel failed with code: " + result.resultCode)
        }
        return null
    }

    // This gets the version from the BLE113, not from the CC1110.
    // I.e., this gets the version from the BLE interface, not from the radio.
    fun getVersion(): String {
        val result = rileyLinkBle.readCharacteristicBlocking(radioServiceUUID, radioVersionUUID)
        if (result.resultCode == BLECommOperationResult.RESULT_SUCCESS) {
            val version = fromBytes(result.value)
            aapsLogger.debug(LTag.PUMPBTCOMM, "BLE Version: $version")
            return version
        } else {
            aapsLogger.error(LTag.PUMPBTCOMM, "getVersion failed with code: " + result.resultCode)
            return "(null)"
        }
    }

    private fun getCC1110Version(): String? {
        aapsLogger.debug(LTag.PUMPBTCOMM, "Firmware Version. Get Version - Start")

        // Testing only. Taken once for the whole read, not once per attempt: the loop below tries
        // five times, so a fault that failed a single attempt would always be recovered by the next
        // one and could never reach the path it exists to test.
        val injectFailure = faultInjector.consume(InjectableFault.VERSION_READ_FAILS)
        val injectSlip = faultInjector.consume(InjectableFault.VERSION_READ_BIT_SLIP)

        (0..4).forEach { i ->
            // We have to call raw version of communication to get firmware version
            // So that we can adjust other commands accordingly afterwords

            val getVersionRaw = getByteArray(RileyLinkCommandType.GetVersion.code)
            // Testing only, and only when a fault was armed by hand on the diagnostics screen.
            // One shot: consume() clears it, so the retry below sees the real radio again.
            val response = when {
                injectFailure -> null
                injectSlip    -> FaultInjector.BIT_SLIPPED_VERSION_REPLY
                else          -> writeToDataRaw(getVersionRaw, PROBE_TIMEOUT_MS, "GetVersion")
            }

            aapsLogger.debug(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Firmware Version. GetVersion [response=%s]", shortHexString(response)))
            diag.versionRead(response, response?.let { fromBytes(it) })

            if (response != null) { // && response[0] == (byte) 0xDD) {

                var versionString = fromBytes(response)
                if (versionString.length > 3) {
                    if (versionString.indexOf('s') >= 0) {
                        versionString = versionString.substring(versionString.indexOf('s'))
                    }
                    return versionString
                }
                SystemClock.sleep(1000)
            }
        }

        return null
    }

    /**
     * Sends one command and waits for its reply, with the radio to itself.
     *
     * @param opName the command name, for the markers only.
     * @return the reply, or null when the radio did not answer or never became free.
     */
    private fun writeToDataRaw(bytes: ByteArray, responseTimeoutMs: Int, opName: String): ByteArray? {
        // The last gate, and the only one that cannot be walked around. Refusing to OPEN a link
        // covers the paths that open one, and nothing else: a link that is already up, or that the
        // Android stack brought back by itself, still carries commands, and a service task that
        // only calls discoverServices() never touches a connect path at all. Every radio command
        // in the driver comes through this function, so a block checked here holds no matter how
        // the link came to exist.
        //
        // Null is what a caller already gets when the radio is busy, so nothing new has to be
        // handled upstream.
        if (rileyLinkServiceData.isCurrentDeviceBlocked) {
            aapsLogger.info(LTag.PUMPBTCOMM, "$opName refused: the RileyLink is blocked")
            diag.writeRefusedBlocked(opName, rileyLinkServiceData.rileyLinkAddress ?: rileyLinkServiceData.blockList.configuredAddress())
            return null
        }
        val askedAt = System.currentTimeMillis()
        val behind = radioHolder
        val behindOp = radioHolderOp
        // Waiting can be interrupted. This function could never throw before, and a new exception
        // type escaping into the command queue would be a worse fault than the one being fixed, so
        // an interrupt is reported as a missed turn and the flag is put back for whoever set it.
        val gotRadio = try {
            radioLock.tryLock(RADIO_LOCK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!gotRadio) {
            diag.radioTurnMissed(opName, System.currentTimeMillis() - askedAt, behind, behindOp)
            aapsLogger.error(LTag.PUMPBTCOMM, "writeToData: the radio was still busy with $behindOp, dropping $opName")
            return null
        }
        val waitedMs = System.currentTimeMillis() - askedAt
        // Only worth a marker when there really was a queue. Every one of these lines is a
        // collision that used to happen instead.
        if (waitedMs >= RADIO_TURN_REPORT_MS) diag.radioTurnTaken(opName, waitedMs, behind, behindOp)
        radioHolder = Thread.currentThread().name
        radioHolderOp = opName
        transactionsInFlight.incrementAndGet()
        try {
            // The markers go here, inside the lock, so their order in the log is the order on
            // the wire. Recording the command before waiting for a turn logged what a caller
            // meant to do, which reads as two commands overlapping when in fact one of them was
            // still queued. The wait itself is already reported by RADIO_TURN, so the time
            // measured here is the radio's, not the queue's.
            diag.tx(opName, bytes, rileyLinkServiceData.firmwareVersion.usesV2Protocol(), describeForRadio(bytes))
            val startedAt = System.currentTimeMillis()
            val raw = writeToDataRawInner(bytes, responseTimeoutMs, opName)
            diag.rx(opName, raw, System.currentTimeMillis() - startedAt)
            return raw
        } finally {
            transactionsInFlight.decrementAndGet()
            radioHolder = null
            radioHolderOp = null
            radioLock.unlock()
        }
    }

    private fun writeToDataRawInner(bytes: ByteArray, responseTimeoutMs: Int, opName: String): ByteArray? {
        SystemClock.sleep(1)
        // Anything already in the queue belongs to no one. With one command at a time this can
        // only be a reply the radio sent after its caller had given up, so it is worth counting
        // rather than dropping quietly.
        var junkInBuffer = reader.poll(0)

        while (junkInBuffer != null) {
            aapsLogger.warn(
                LTag.PUMPBTCOMM, (sig() + "writeToData: draining read queue, found this: "
                    + shortHexString(junkInBuffer))
            )
            diag.radioJunkDrained(opName, junkInBuffer)
            junkInBuffer = reader.poll(0)
        }

        // prepend length, and send it.
        val prepended = concat(byteArrayOf((bytes.size).toByte()), bytes)

        aapsLogger.debug(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "writeToData (raw=%s)", shortHexString(prepended)))

        val writeCheck = rileyLinkBle.writeCharacteristicBlocking(
            radioServiceUUID, radioDataUUID,
            prepended
        )
        if (writeCheck.resultCode != BLECommOperationResult.RESULT_SUCCESS) {
            aapsLogger.error(LTag.PUMPBTCOMM, "BLE Write operation failed, code=" + writeCheck.resultCode)
            return null // will be a null (invalid) response
        }

        return reader.poll(responseTimeoutMs)
    }

    // The caller has to know how long the RFSpy will be busy with what was sent to it.
    private fun writeToData(command: RileyLinkCommand, responseTimeoutMs: Int): RFSpyResponse? {
        val bytes = command.getRaw()
        val commandName = command.getCommandType().name
        val rawResponse = writeToDataRaw(bytes, responseTimeoutMs, commandName)

        if (rawResponse == null) {
            aapsLogger.error(LTag.PUMPBTCOMM, "writeToData: No response from RileyLink")
            notConnectedCount++
            return null
        }
        val resp = rfSpyResponseProvider().with(command, rawResponse)
        if (resp.wasInterrupted()) {
            aapsLogger.error(LTag.PUMPBTCOMM, "writeToData: RileyLink was interrupted")
        } else if (resp.wasTimeout()) {
            aapsLogger.error(LTag.PUMPBTCOMM, "writeToData: RileyLink reports timeout")
            notConnectedCount++
        } else if (resp.isOK()) {
            aapsLogger.warn(LTag.PUMPBTCOMM, "writeToData: RileyLink reports OK")
            resetNotConnectedCount()
        } else {
            if (resp.looksLikeRadioPacket()) {
                aapsLogger.debug(LTag.PUMPBTCOMM, "writeToData: received radio response. Will decode at upper level")
                resetNotConnectedCount()
            }
        }
        return resp
    }

    private fun resetNotConnectedCount() {
        this.notConnectedCount = 0
    }

    private fun getByteArray(vararg input: Byte): ByteArray {
        return input
    }

    @JvmOverloads fun transmitThenReceive(
        pkt: RadioPacket, sendChannel: Byte, repeatCount: Byte, delayMs: Byte,
        listenChannel: Byte, timeoutMs: Int, retryCount: Byte, extendPreambleMs: Int = 0
    ): RFSpyResponse? {
        val sendDelay = repeatCount * delayMs
        val receiveDelay = timeoutMs * (retryCount + 1)

        val command = SendAndListen(
            rileyLinkServiceData, sendChannel, repeatCount, delayMs.toInt(), listenChannel, timeoutMs,
            retryCount, extendPreambleMs, pkt
        )

        val rfSpyResponse = writeToData(command, sendDelay + receiveDelay + EXPECTED_MAX_BLUETOOTH_LATENCY_MS)

        if (System.currentTimeMillis() >= nextBatteryCheck) {
            updateBatteryLevel()
        }

        return rfSpyResponse
    }

    private fun updateBatteryLevel() {
        rileyLinkServiceData.batteryLevel = retrieveBatteryLevel()
        nextBatteryCheck = System.currentTimeMillis() +
            (if (Optional.ofNullable<Int>(rileyLinkServiceData.batteryLevel).orElse(0) <= LOW_BATTERY_PERCENTAGE_THRESHOLD) LOW_BATTERY_BATTERY_CHECK_INTERVAL_MILLIS else DEFAULT_BATTERY_CHECK_INTERVAL_MILLIS)

        // The Omnipod plugin reports the RL battery as the pump battery (as the Omnipod battery level is unknown)
        // So update overview when the battery level has been updated
        rxBus.send(EventRefreshOverview("RL battery level updated", false))
    }

    private fun updateRegister(reg: CC111XRegister, `val`: Int): RFSpyResponse? {
        return writeToData(UpdateRegister(reg, `val`.toByte()), EXPECTED_MAX_BLUETOOTH_LATENCY_MS)
    }

    fun setBaseFrequency(freqMHz: Double) {
        val value = (freqMHz * 1000000 / ((RILEYLINK_FREQ_XTAL).toDouble() / 2.0.pow(16.0))).toInt()
        updateRegister(CC111XRegister.freq0, (value and 0xff).toByte().toInt())
        updateRegister(CC111XRegister.freq1, ((value shr 8) and 0xff).toByte().toInt())
        updateRegister(CC111XRegister.freq2, ((value shr 16) and 0xff).toByte().toInt())
        aapsLogger.info(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Set frequency to %.3f MHz", freqMHz))

        this.currentFrequencyMHz = freqMHz

        // Reading the frequency back would be worth doing, but it cannot be done. See the note on
        // RileyLinkCommandType.ReadRegister: the command hangs the radio chip.

        configureRadioForRegion(rileyLinkServiceData.rileyLinkTargetFrequency)
    }

    private fun configureRadioForRegion(frequency: RileyLinkTargetFrequency) {
        // we update registers only on first run, or if region changed
        aapsLogger.error(LTag.PUMPBTCOMM, "RileyLinkTargetFrequency: $frequency")

        when (frequency) {
            RileyLinkTargetFrequency.MedtronicWorldWide -> {
                setRXFilterMode(RXFilterMode.Wide)
                updateRegister(CC111XRegister.mdmcfg1, 0x62)
                updateRegister(CC111XRegister.mdmcfg0, 0x1A)
                updateRegister(CC111XRegister.deviatn, 0x13)
                setMedtronicEncoding()
            }

            RileyLinkTargetFrequency.MedtronicUS        -> {
                setRXFilterMode(RXFilterMode.Narrow)
                updateRegister(CC111XRegister.mdmcfg1, 0x61)
                updateRegister(CC111XRegister.mdmcfg0, 0x7E)
                updateRegister(CC111XRegister.deviatn, 0x15)
                setMedtronicEncoding()
            }

            RileyLinkTargetFrequency.Omnipod            -> {
                // RL initialization for Omnipod is a copy/paste from OmniKit implementation.
                // Last commit from original repository: 5c3beb4144
                // so if something is terribly wrong, please check git diff PodCommsSession.swift since that commit
                updateRegister(CC111XRegister.pktctrl1, 0x20)
                updateRegister(CC111XRegister.agcctrl0, 0x00)
                updateRegister(CC111XRegister.fsctrl1, 0x06)
                updateRegister(CC111XRegister.mdmcfg4, 0xCA)
                updateRegister(CC111XRegister.mdmcfg3, 0xBC)
                updateRegister(CC111XRegister.mdmcfg2, 0x06)
                updateRegister(CC111XRegister.mdmcfg1, 0x70)
                updateRegister(CC111XRegister.mdmcfg0, 0x11)
                updateRegister(CC111XRegister.deviatn, 0x44)
                updateRegister(CC111XRegister.mcsm0, 0x18)
                updateRegister(CC111XRegister.foccfg, 0x17)
                updateRegister(CC111XRegister.fscal3, 0xE9)
                updateRegister(CC111XRegister.fscal2, 0x2A)
                updateRegister(CC111XRegister.fscal1, 0x00)
                updateRegister(CC111XRegister.fscal0, 0x1F)

                updateRegister(CC111XRegister.test1, 0x31)
                updateRegister(CC111XRegister.test0, 0x09)
                updateRegister(CC111XRegister.paTable0, 0x84)
                updateRegister(CC111XRegister.sync1, 0xA5)
                updateRegister(CC111XRegister.sync0, 0x5A)

                setRileyLinkEncoding(RileyLinkEncodingType.Manchester)
                setPreamble(0x6665)
            }

            else                                        -> aapsLogger.warn(LTag.PUMPBTCOMM, "No region configuration for RfSpy and " + frequency.name)
        }
    }

    /**
     * Describes a command the way the radio will read it, for the log and the diagnostics screen.
     *
     * A hex dump hides a format mismatch completely: the same bytes mean "listen 25 s" in one
     * format and "listen 6 400 000 ms, 169 tries" in the other. When the format we built with and
     * the format the radio uses disagree, both readings are printed, because that difference is
     * the whole fault.
     */
    private fun describeForRadio(payload: ByteArray): String? {
        val builtV2 = rileyLinkServiceData.firmwareVersion.usesV2Protocol()
        val asBuilt = SendAndListenDecoder.decode(payload, builtV2) ?: return null
        val text = "listen=${asBuilt.timeoutMs}ms retries=${asBuilt.retryCount} repeats=${asBuilt.repeatCount} busyUpTo=${asBuilt.busyMs}ms"
        if (builtV2) return text
        // Built with the old format. Show what a version 2 radio would make of the same bytes.
        val asV2 = SendAndListenDecoder.decode(payload, true) ?: return text
        return "$text | ifRadioIsV2: listen=${asV2.timeoutMs}ms retries=${asV2.retryCount} busyUpTo=${asV2.busyMs}ms"
    }

    private fun setMedtronicEncoding() {
        var encoding = RileyLinkEncodingType.FourByteSixByteLocal

        // Same version test as the packet format uses, so the encoder and the framing can never
        // disagree. They used to be able to, which produced commands that were half one format and
        // half the other.
        if (rileyLinkServiceData.firmwareVersion.usesV2Protocol()) {
            if (preferences.get(RileyLinkStringPreferenceKey.Encoding) == RileyLinkEncodingType.FourByteSixByteRileyLink.key)
                encoding = RileyLinkEncodingType.FourByteSixByteRileyLink
        }

        setRileyLinkEncoding(encoding)

        aapsLogger.debug(LTag.PUMPBTCOMM, "Set Encoding for Medtronic: " + encoding.name)
    }

    private fun setPreamble(@Suppress("SameParameterValue") preamble: Int): RFSpyResponse? {
        try {
            return writeToData(SetPreamble(rileyLinkServiceData, preamble), EXPECTED_MAX_BLUETOOTH_LATENCY_MS)
        } catch (e: Exception) {
            aapsLogger.error("Failed to set preamble", e)
        }
        return null
    }

    fun setRileyLinkEncoding(encoding: RileyLinkEncodingType): RFSpyResponse? {
        val resp = writeToData(SetHardwareEncoding(encoding), EXPECTED_MAX_BLUETOOTH_LATENCY_MS)

        if (resp?.isOK() == true) {
            reader.setRileyLinkEncodingType(encoding)
            rileyLinkUtil.encoding = encoding
        }
        diag.protocol(
            v2 = rileyLinkServiceData.firmwareVersion.usesV2Protocol(),
            encoding = encoding,
            stopAtNull = !(encoding == RileyLinkEncodingType.Manchester || encoding == RileyLinkEncodingType.FourByteSixByteRileyLink)
        )

        return resp
    }

    @Suppress("SpellCheckingInspection", "LocalVariableName")
    private fun setRXFilterMode(mode: RXFilterMode) {
        val drate_e = 0x9.toByte() // exponent of symbol rate (16kbps)
        val chanbw = mode.value

        updateRegister(CC111XRegister.mdmcfg4, (chanbw.toInt() or drate_e.toInt()).toByte().toInt())
    }

    /**
     * Reads the radio chip's own counters and records them.
     *
     * Worth doing whenever the pump has gone quiet. A timeout tells the app nothing about which
     * half of the radio failed; the packets sent counter does. If it climbed while the pump was
     * silent, the RileyLink transmitted and nothing answered, which points at the pump or the
     * path. If it did not climb, the RileyLink never transmitted, which points at the RileyLink.
     *
     * @param reason where the app is in its own sequence, so two reads can be compared.
     * @return the counters, or null if the radio did not answer with a whole reply.
     */
    fun readRadioStats(reason: String): RadioStats? {
        // Version 1 radios have no statistics command and would answer "unknown command", so
        // asking them would fill the log with a failure that is not one.
        if (!rileyLinkServiceData.firmwareVersion.usesV2Protocol()) return null
        // The reply is binary and mostly zero bytes, and the reader normally cuts a reply at its
        // first zero. Without this it arrives as a single 0xDD.
        val response = reader.keepingWholeReply { writeToData(GetStatistics(), EXPECTED_MAX_BLUETOOTH_LATENCY_MS) }
        val stats = RadioStats.parse(response?.raw)
        diag.radioStats(reason, stats, response?.raw)
        return stats
    }

    /**
     * Asks the CC1110 what firmware it runs and returns the raw reply, for the self test.
     *
     * Deliberately raw and deliberately one attempt. The self test wants to report exactly what
     * came back - nothing, a short reply, or bytes that are not a version string - and a helper
     * that retried or tidied the answer would hide the very thing being looked for.
     *
     * @return the bytes the radio sent, or null if it did not answer in time.
     */
    fun probeRadio(): ByteArray? = writeToDataRaw(getByteArray(RileyLinkCommandType.GetVersion.code), PROBE_TIMEOUT_MS, "GetVersion(probe)")

    /**
     * Tells the CC1110 to reboot.
     *
     * @return true if the radio acknowledged. A reboot often means no acknowledgement arrives at
     *   all, which is not proof it failed - the caller should re-probe rather than trust this.
     */
    fun resetRadioChip(): Boolean {
        val response = writeToData(ResetRadio(), EXPECTED_MAX_BLUETOOTH_LATENCY_MS)
        return response?.isOK() == true
    }

    /**
     * Reset RileyLink Configuration (set all updateRegisters)
     */
    fun resetRileyLinkConfiguration() {
        currentFrequencyMHz?.let { setBaseFrequency(it) }
    }

    companion object {

        private const val DEFAULT_BATTERY_CHECK_INTERVAL_MILLIS = 30 * 60 * 1000L // 30 minutes;
        private const val LOW_BATTERY_BATTERY_CHECK_INTERVAL_MILLIS = 10 * 60 * 1000L // 10 minutes;
        private const val LOW_BATTERY_PERCENTAGE_THRESHOLD = 20
        private const val RILEYLINK_FREQ_XTAL: Long = 24000000
        private const val EXPECTED_MAX_BLUETOOTH_LATENCY_MS = 7500 // 1500
        private const val PROBE_TIMEOUT_MS = 5000

        /**
         * How long a command waits for its turn on the radio before it gives up.
         *
         * The longest single command is a wake up: 25 s of listening plus the Bluetooth latency
         * allowance, so about 33 s. Two of those queued is the worst honest case. Ninety seconds
         * leaves room for that and still frees the app if a command ever gets stuck holding the
         * radio.
         */
        private const val RADIO_LOCK_WAIT_MS = 90_000L

        /**
         * Waits shorter than this are not reported.
         *
         * A few milliseconds is ordinary hand off between threads. Anything longer means one
         * command really did sit behind another, which is what the marker is for.
         */
        private const val RADIO_TURN_REPORT_MS = 5L

        fun getFirmwareVersion(aapsLogger: AAPSLogger, bleVersion: String, cc1110Version: String?): RileyLinkFirmwareVersionBase {
            if (cc1110Version != null) {
                val version = RileyLinkFirmwareVersion.getByVersionString(cc1110Version)
                aapsLogger.debug(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Firmware Version string: %s, resolved to %s.", cc1110Version, version))

                if (version != null && version != RileyLinkFirmwareVersionBase.UnknownVersion) {
                    return version
                }
            }

            aapsLogger.error(LTag.PUMPBTCOMM, String.format(Locale.ENGLISH, "Firmware Version can't be determined. Checking with BLE Version [%s].", bleVersion))

            if (bleVersion.contains(" 2.")) {
                return RileyLinkFirmwareVersionBase.Version_2_0
            }

            return RileyLinkFirmwareVersionBase.UnknownVersion
        }
    }
}
