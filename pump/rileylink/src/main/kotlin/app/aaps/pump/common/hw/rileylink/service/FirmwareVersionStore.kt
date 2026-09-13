package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersionBase
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Remembers the firmware version last read from a given RileyLink.
 *
 * The CC1110 firmware lives in flash. It is the same on every connection to the same device, so
 * asking for it again is not gathering news, it is re-running a measurement that can fail. When it
 * does fail the app has to guess, and a wrong guess picks the wrong wire format and takes the
 * radio off the air.
 *
 * Keeping the last good answer removes most of those guesses: a version that was read correctly
 * once is used again, and only a RileyLink that has never answered falls back to a default.
 *
 * The value is stored together with the MAC it came from, so swapping to another RileyLink never
 * inherits the old one's version.
 */
@SingleIn(AppScope::class)
@Inject
class FirmwareVersionStore(
    private val preferences: Preferences
) {

    /**
     * The remembered version for [macAddress], or null if none is stored for that device.
     *
     * @param macAddress the RileyLink this connection is for. A blank address never matches.
     */
    fun get(macAddress: String?): RileyLinkFirmwareVersionBase? {
        if (macAddress.isNullOrBlank()) return null
        val stored = preferences.get(RileyLinkStringKey.FirmwareVersionCache)
        val parts = stored.split(SEPARATOR)
        if (parts.size != 2) return null
        if (!parts[0].equals(macAddress, ignoreCase = true)) return null
        val version = RileyLinkFirmwareVersionBase.byVersionString(parts[1]) ?: return null
        return if (version == RileyLinkFirmwareVersionBase.UnknownVersion) null else version
    }

    /**
     * Remembers [version] for [macAddress].
     *
     * Only a version actually read from the radio should be stored. Storing a guess would make the
     * guess look like knowledge on the next connection, which is the opposite of the point.
     * [RileyLinkFirmwareVersionBase.UnknownVersion] and a blank address are ignored.
     */
    fun put(macAddress: String?, version: RileyLinkFirmwareVersionBase) {
        if (macAddress.isNullOrBlank()) return
        if (version == RileyLinkFirmwareVersionBase.UnknownVersion) return
        preferences.put(RileyLinkStringKey.FirmwareVersionCache, "$macAddress$SEPARATOR${version.versionKey}")
    }

    companion object {

        private const val SEPARATOR = "|"
    }
}
