package app.aaps.pump.common.hw.rileylink.ble.defs

/**
 * Created by andy on 22/05/2018.
 */
@Suppress("unused")
enum class RileyLinkCommandType(val code: Byte) {

    GetState(1),  //
    GetVersion(2),  //
    GetPacket(3),  // aka Listen, receive
    Send(4),  //
    SendAndListen(5),  //
    UpdateRegister(6),  //
    Reset(7),  //
    Led(8),
    /**
     * Do not send this. It hangs the radio chip.
     *
     * `cmd_read_register` in subg_rfspy reads the register address, then calls `get_register`,
     * which throws that argument away and reads a second byte from the serial input that the
     * caller never sends. `serial_rx_byte` spins until one arrives, so the chip never answers and
     * the first byte of the next command is eaten as the missing argument. Every command after it
     * is shifted by one byte, which shows up as a run of 0xBB and 0xCC replies until the link is
     * dropped. Seen on subg_rfspy 2.2.21 and present in the 2.2.23 source.
     *
     * Kept in this list because the numbering has to match the firmware.
     */
    ReadRegister(9),
    SetModeRegisters(10),
    SetHardwareEncoding(11),
    SetPreamble(12),
    ResetRadioConfig(13),
    GetStatistics(14),
    ;
}
