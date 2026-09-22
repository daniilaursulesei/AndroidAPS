package app.aaps.pump.common.hw.rileylink.service.tasks

import app.aaps.core.data.pump.defs.ManufacturerType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkTargetFrequency
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkError
import app.aaps.pump.common.hw.rileylink.defs.RileyLinkServiceState
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkDoubleKey
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import dev.zacsweers.metro.Inject
import kotlin.math.roundToLong

/**
 * This class is intended to be run by the Service, for the Service. Not intended for clients to run.
 */
@Inject
class InitializePumpManagerTask(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val rileyLinkUtil: RileyLinkUtil,
    activePlugin: ActivePlugin
) : ServiceTask(activePlugin) {

    override fun run() {
        if (!isRileyLinkDevice) return

        // The link this run is about. A task can outlive its link: a GATT write that dies with
        // the link takes 22 seconds to time out, and by then the RileyLink can be connected
        // again. What this task then finds out is about a link that is gone, so it must not be
        // published. Saying "no contact with the pump" and asking for a tune up on a link that
        // was already replaced is how a tune up ends up running beside the start up sequence of
        // the new link, and those two share one reply queue.
        val startedOnLink = rileyLinkServiceData.radioSession.generation

        var lastGoodFrequency: Double
        if (rileyLinkServiceData.lastGoodFrequency == null) {
            lastGoodFrequency = preferences.get(RileyLinkDoubleKey.LastGoodDeviceFrequency)
            lastGoodFrequency = (lastGoodFrequency * 1000.0).roundToLong() / 1000.0
            rileyLinkServiceData.lastGoodFrequency = lastGoodFrequency
        } else lastGoodFrequency = rileyLinkServiceData.lastGoodFrequency ?: 0.0

        val rileyLinkCommunicationManager = pumpDevice?.rileyLinkService?.deviceCommunicationManager
        if (activePlugin.activePumpInternal.manufacturer() === ManufacturerType.Medtronic) {
            if (lastGoodFrequency > 0.0 && rileyLinkCommunicationManager?.isValidFrequency(lastGoodFrequency) == true) {
                rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkReady)
                aapsLogger.info(LTag.PUMPBTCOMM, "Setting radio frequency to $lastGoodFrequency MHz")
                rileyLinkCommunicationManager.setRadioFrequencyForPump(lastGoodFrequency)
                if (rileyLinkCommunicationManager.tryToConnectToDevice()) rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorReady)
                else if (linkIsGone(startedOnLink)) return
                else {
                    rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorError, RileyLinkError.NoContactWithDevice)
                    rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.IPC.MSG_PUMP_tunePump)
                }
            } else if (linkIsGone(startedOnLink)) return
            else rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.IPC.MSG_PUMP_tunePump)
        } else {
            if (!Round.isSame(lastGoodFrequency, RileyLinkTargetFrequency.Omnipod.scanFrequencies[0])) {
                lastGoodFrequency = RileyLinkTargetFrequency.Omnipod.scanFrequencies[0]
                lastGoodFrequency = (lastGoodFrequency * 1000.0).roundToLong() / 1000.0
                rileyLinkServiceData.lastGoodFrequency = lastGoodFrequency
            }
            rileyLinkServiceData.setServiceState(RileyLinkServiceState.RileyLinkReady)
            rileyLinkServiceData.rileyLinkTargetFrequency = RileyLinkTargetFrequency.Omnipod // TODO shouldn't be needed
            aapsLogger.info(LTag.PUMPBTCOMM, "Setting radio frequency to $lastGoodFrequency MHz")
            rileyLinkCommunicationManager?.setRadioFrequencyForPump(lastGoodFrequency)
            rileyLinkServiceData.setServiceState(RileyLinkServiceState.PumpConnectorReady)
        }
    }

    /**
     * True when the link this run started on has already been replaced.
     *
     * Nothing is published in that case. A new link brings its own start up sequence and its own
     * run of this task, and that one knows the truth about the link that is up now.
     */
    private fun linkIsGone(startedOnLink: Int): Boolean {
        if (rileyLinkServiceData.radioSession.stillOnSameLink(startedOnLink)) return false
        aapsLogger.warn(
            LTag.PUMPBTCOMM,
            "The RileyLink link changed while the pump manager was starting up. Dropping the result and not asking for a tune up; the link that is up now starts its own."
        )
        return true
    }
}
