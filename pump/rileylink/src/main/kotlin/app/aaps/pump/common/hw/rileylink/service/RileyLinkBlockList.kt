package app.aaps.pump.common.hw.rileylink.service

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.common.hw.rileylink.keys.RileyLinkStringKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The RileyLinks this app must leave alone, kept by MAC address and stored on the device.
 *
 * A RileyLink takes one Bluetooth connection at a time. While the phone holds it, nothing else can
 * reach it, so handing it to a laptop or a second phone means this app has to stay away. A plain
 * disconnect does not do that: the client is created with autoConnect, so the Android stack brings
 * the link back on its own within seconds.
 *
 * Two things make a block real, and it needs both:
 *
 *  * it is stored in preferences, so it survives the app being restarted. An earlier version kept
 *    the block in a field, and every process restart quietly threw it away. The app then
 *    reconnected and drove the pump with nothing in the log to say a block had ever been set.
 *  * it is kept per device, so blocking a bench RileyLink can never reach a real one. A single
 *    global flag would block whatever happened to be configured at the time.
 *
 * A block does not expire. A bench session takes as long as it takes, and a block that ran out in
 * the middle of one would take the RileyLink back while it was being used.
 */
@SingleIn(AppScope::class)
@Inject
class RileyLinkBlockList(
    private val preferences: Preferences
) {

    /** Every blocked address, in the order they were blocked. Empty when none are. */
    fun blocked(): Set<String> = parse(preferences.get(RileyLinkStringKey.BlockedDevices))

    /** True when this app must not connect to [macAddress]. A blank address is never blocked. */
    fun isBlocked(macAddress: String?): Boolean {
        val mac = normalise(macAddress) ?: return false
        return blocked().contains(mac)
    }

    /**
     * The RileyLink this app is set up to use, or null when none is stored.
     *
     * The live address is cleared on a deliberate disconnect, so a caller that only has that one
     * cannot tell "no RileyLink" from "not connected right now". The configured address is the
     * answer to which device a block is about.
     */
    fun configuredAddress(): String? = normalise(preferences.get(RileyLinkStringKey.MacAddress))

    /** True when the RileyLink this app is set up to use is blocked. */
    fun isConfiguredBlocked(): Boolean = isBlocked(configuredAddress())

    /**
     * The stored name of the RileyLink this app is set up to use, or null when it has none.
     *
     * Only the configured device has a name kept for it, so a picker row for any other address can
     * only show the address. That is the honest answer: showing a name that belongs to a different
     * RileyLink would be worse than showing none.
     */
    fun configuredName(): String? = preferences.get(RileyLinkStringKey.Name).takeIf { it.isNotBlank() }

    /** Adds [macAddress]. Does nothing if it is already blocked or the address is blank. */
    fun block(macAddress: String?) {
        val mac = normalise(macAddress) ?: return
        val current = blocked()
        if (current.contains(mac)) return
        store(current + mac)
    }

    /** Removes [macAddress]. Does nothing if it was not blocked. */
    fun unblock(macAddress: String?) {
        val mac = normalise(macAddress) ?: return
        val current = blocked()
        if (!current.contains(mac)) return
        store(current - mac)
    }

    private fun store(addresses: Collection<String>) {
        preferences.put(RileyLinkStringKey.BlockedDevices, format(addresses))
    }

    companion object {

        private const val SEPARATOR = ","

        /**
         * One written form for an address, so a block set from the picker matches the address the
         * Bluetooth layer asks about. Android reports MACs in upper case, but a value that came
         * from a preference the user edited, or from an older build, may not be.
         */
        fun normalise(macAddress: String?): String? =
            macAddress?.trim()?.uppercase()?.takeIf { it.isNotBlank() }

        /** Reads the stored form. Blank and malformed entries are dropped rather than kept. */
        fun parse(stored: String): Set<String> =
            stored.split(SEPARATOR).mapNotNull { normalise(it) }.toCollection(LinkedHashSet())

        /** Writes the stored form. */
        fun format(addresses: Collection<String>): String =
            addresses.mapNotNull { normalise(it) }.joinToString(SEPARATOR)
    }
}
