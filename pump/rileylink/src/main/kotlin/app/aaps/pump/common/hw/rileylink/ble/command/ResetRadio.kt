package app.aaps.pump.common.hw.rileylink.ble.command

import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType

/**
 * Tells the CC1110 to reboot.
 *
 * The software equivalent of pulling the battery, and the only way from the app to end a radio
 * operation that is already running. A wrongly framed `SendAndListen` can put the chip into a
 * listen lasting over an hour, during which it answers nothing; if it is still reading its command
 * input this ends that, and if it is not, nothing will and the user has to remove power.
 *
 * The radio is gone for a few seconds afterwards and anything in flight is lost, so this belongs
 * behind a repair action the user chose, never in an automatic retry.
 */
class ResetRadio : RileyLinkCommand() {

    override fun getCommandType(): RileyLinkCommandType = RileyLinkCommandType.Reset

    override fun getRaw(): ByteArray = getByteArray(getCommandType().code)
}
