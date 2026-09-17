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

    /**
     * RileyLinks this app must never connect to, as MAC addresses separated by commas.
     *
     * Blocking hands a RileyLink to something else - a laptop running a bench test, a second
     * phone - and it has to survive a restart. A block kept only in memory is silently lost the
     * next time Android rebuilds the service, and the app reconnects with nothing in the log to
     * say a block was ever set. That is why this is a stored preference and not a field.
     */
    BlockedDevices("pref_rileylink_blocked_devices", ""),
}
