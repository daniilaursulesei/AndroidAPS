package app.aaps.pump.common.hw.rileylink.ble.command

import app.aaps.pump.common.hw.rileylink.ble.defs.CC111XRegister
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkCommandType

/**
 * Reads one radio register back.
 *
 * A register write is acknowledged by the chip that received it, not by the register itself, so an
 * acknowledged write is not proof the value took. Reading it back is.
 */
class ReadRegister(val register: CC111XRegister) : RileyLinkCommand() {

    override fun getCommandType(): RileyLinkCommandType = RileyLinkCommandType.ReadRegister
    override fun getRaw(): ByteArray = getByteArray(getCommandType().code, register.value)
}
