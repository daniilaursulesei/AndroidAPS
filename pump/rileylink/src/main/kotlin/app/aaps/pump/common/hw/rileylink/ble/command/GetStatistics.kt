package app.aaps.pump.common.hw.rileylink.ble.command

import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType

/**
 * Asks the CC1110 for its own counters.
 *
 * The answer is the only way to tell a radio that is transmitting into a silent room from a radio
 * that is not transmitting at all. Both look the same from the app: a timeout.
 */
class GetStatistics : RileyLinkCommand() {

    override fun getCommandType(): RileyLinkCommandType = RileyLinkCommandType.GetStatistics
    override fun getRaw(): ByteArray = getByteArray(getCommandType().code)
}
