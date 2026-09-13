package app.aaps.pump.common.hw.rileylink.keys

import app.aaps.core.keys.interfaces.StringNonPreferenceKey

enum class RileyLinkStringKey(
    override val key: String,
    override val defaultValue: String,
) : StringNonPreferenceKey {

    Name("pref_rileylink_name", ""),
    MacAddress("pref_rileylink_mac_address", ""),

    /**
     * Last firmware version successfully read from a RileyLink, stored as `<mac>|<versionKey>`.
     *
     * The CC1110 firmware is in flash and cannot change between two connections to the same
     * device, so a version that was once read correctly stays true. Keeping it means a single
     * failed read no longer forces a guess. The MAC is stored with it so the value is never
     * reused for a different RileyLink.
     */
    FirmwareVersionCache("pref_rileylink_firmware_version_cache", ""),
}
