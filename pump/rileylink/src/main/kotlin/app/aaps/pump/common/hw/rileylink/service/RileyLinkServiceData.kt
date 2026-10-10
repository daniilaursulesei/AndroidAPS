package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.pump.common.events.EventRileyLinkDeviceStatusChange
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersionBase
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.data.RLHistoryItem
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkTargetDevice
import java.util.Locale
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

/**
 * Created by andy on 16/05/2018.
 */
@SingleIn(AppScope::class)
@Inject
class RileyLinkServiceData(
    private val aapsLogger: AAPSLogger,
    private val rileyLinkUtil: RileyLinkUtil,
    private val rxBus: RxBus,
    /**
     * The RileyLinks this app must leave alone.
     *
     * Lives here because both the Bluetooth layer, which must refuse to connect, and the pump
     * driver, which must stop asking, need to read the same list.
     */
    val blockList: RileyLinkBlockList,
    /**
     * Who holds the radio, and which link the radio is on.
     *
     * Lives here for the same reason as [blockList]: the Bluetooth layer, which counts the link
     * changes, the service tasks, which take the radio for the length of a task, and the pump
     * driver, which has to stop scanning when the link it was scanning on is gone, all need the
     * same one.
     */
    val radioSession: RadioSession
) {

    /**
     * True when the link was closed because of a block and has not been opened again.
     *
     * The block itself is stored on the device, but this is only about the Bluetooth client that
     * was closed a moment ago, and it is right for it to be forgotten on a restart: a fresh start
     * opens the link through the normal path anyway.
     */
    var needsReopen: Boolean = false

    /**
     * True when the RileyLink this app would use right now is blocked.
     *
     * The live address when there is one, and the configured address otherwise: a deliberate
     * disconnect clears the live one, and "not connected right now" must never read as
     * "not blocked".
     */
    val isCurrentDeviceBlocked: Boolean
        get() = rileyLinkAddress?.let { blockList.isBlocked(it) } ?: blockList.isConfiguredBlocked()

    var tuneUpDone = false
    var rileyLinkError: RileyLinkError? = null
    var rileyLinkServiceState: RileyLinkServiceState = RileyLinkServiceState.NotStarted
        private set
    var lastServiceStateChange = 0L
        private set

    // here we have "compatibility level" version
    var firmwareVersion: RileyLinkFirmwareVersionBase? = null
    var rileyLinkTargetFrequency: RileyLinkTargetFrequency = RileyLinkTargetFrequency.NotSet
    var rileyLinkAddress: String? = null
    var rileyLinkName: String? = null
    var batteryLevel: Int? = null
    var showBatteryLevel = false
    var lastTuneUpTime = 0L
    var lastGoodFrequency: Double? = null

    // bt version
    var versionBLE113: String? = null

    // radio version
    var versionCC110: String? = null

    // orangeLink
    var isOrange = false
    var versionOrangeFirmware: String? = null
    var versionOrangeHardware: String? = null
    lateinit var targetDevice: RileyLinkTargetDevice

    // Medtronic Pump
    var pumpID: String? = null
    var pumpIDBytes: ByteArray = byteArrayOf(0, 0, 0)

    fun setPumpID(pumpId: String?, pumpIdBytes: ByteArray) {
        pumpID = pumpId
        pumpIDBytes = pumpIdBytes
    }

    @Synchronized
    fun setServiceState(newState: RileyLinkServiceState, errorCode: RileyLinkError? = null) {
        rileyLinkServiceState = newState
        lastServiceStateChange = System.currentTimeMillis()
        rileyLinkError = errorCode
        aapsLogger.info(LTag.PUMP, String.format(Locale.ENGLISH, "RileyLink State Changed: $newState - Error State: ${errorCode?.name}"))
        rileyLinkUtil.rileyLinkHistory.add(RLHistoryItem(rileyLinkServiceState, errorCode, targetDevice))
        rxBus.send(EventRileyLinkDeviceStatusChange(targetDevice, newState, errorCode))
    }

    // The last Bluetooth signal strength (RSSI) read from the RileyLink, in dBm. Higher (closer to
    // zero) means nearer. Used to find a misplaced RileyLink by walking warmer/colder. It is set two
    // ways: from readRemoteRssi while connected, and from an advertising scan while locating (see
    // RileyLinkBLE.startLocating), so a lost RileyLink can be found even when it is not connected.
    var lastRssi: Int? = null

    fun updateRssi(rssi: Int) {
        lastRssi = rssi
        // Refresh the status screen live so the number updates while walking around.
        if (this::targetDevice.isInitialized)
            rxBus.send(EventRileyLinkDeviceStatusChange(targetDevice, rileyLinkServiceState, rileyLinkError))
    }

    // True while a "find my RileyLink" advertising scan is running (see RileyLinkBLE.startLocating).
    // Drives the Find/Stop button and the "searching" hint on the status screen.
    var locating: Boolean = false
        private set

    fun setLocating(value: Boolean) {
        if (locating == value) return
        locating = value
        if (this::targetDevice.isInitialized)
            rxBus.send(EventRileyLinkDeviceStatusChange(targetDevice, rileyLinkServiceState, rileyLinkError))
    }
}