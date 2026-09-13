package app.aaps.pump.common.hw.rileylink.ble.defs

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The unknown case is the reason this helper exists, so it is the case worth pinning down.
 *
 * Field logs showed an unread version making the app fall back to the version 1 command format
 * against a version 2 radio. Every field after the delay then shifts by one byte and a 25 second
 * listen is read as 6 400 000 ms, which takes the radio off the air for nearly two hours.
 */
class ProtocolVersionTest {

    @Test fun `unknown version uses the version 2 format`() {
        assertTrue(RileyLinkFirmwareVersionBase.UnknownVersion.usesV2Protocol())
    }

    @Test fun `no version at all uses the version 2 format`() {
        assertTrue((null as RileyLinkFirmwareVersionBase?).usesV2Protocol())
    }

    @Test fun `version 1 radios still get the version 1 format`() {
        assertFalse(RileyLinkFirmwareVersionBase.Version_0_0.usesV2Protocol())
        assertFalse(RileyLinkFirmwareVersionBase.Version_0_9.usesV2Protocol())
        assertFalse(RileyLinkFirmwareVersionBase.Version_1_0.usesV2Protocol())
        assertFalse(RileyLinkFirmwareVersionBase.Version_1_x.usesV2Protocol())
    }

    @Test fun `version 2 and newer use the version 2 format`() {
        assertTrue(RileyLinkFirmwareVersionBase.Version_2_0.usesV2Protocol())
        assertTrue(RileyLinkFirmwareVersionBase.Version_2_2.usesV2Protocol())
        assertTrue(RileyLinkFirmwareVersionBase.Version_2_x.usesV2Protocol())
        assertTrue(RileyLinkFirmwareVersionBase.Version_3_x.usesV2Protocol())
        assertTrue(RileyLinkFirmwareVersionBase.Version_4_x.usesV2Protocol())
    }
}
