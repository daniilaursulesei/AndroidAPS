package app.aaps.pump.common.hw.rileylink.service

/**
 * A timed hold on the Bluetooth link to the RileyLink.
 *
 * A RileyLink takes one Bluetooth connection at a time. While the phone holds it, nothing else
 * can reach it - not a laptop running a test, not a second phone. Releasing it needs more than a
 * disconnect, because the driver reconnects within seconds; it needs a window during which the
 * app does not try.
 *
 * The window always ends by itself. A release that lasted until someone remembered to undo it
 * would be a pump left unmanaged for as long as that took, so the only question this asks is
 * "for how long", never "until further notice".
 */
class RileyLinkRelease {

    /** When the hold ends, as epoch milliseconds. Zero when there is no hold. */
    var untilMillis: Long = 0
        private set

    /**
     * True once a hold has closed the link and it has not been re-opened yet.
     *
     * Releasing closes the Bluetooth client, which is the only way to cancel the automatic
     * reconnect Android performs on its own. Nothing re-opens it by itself, so without this the
     * hold would end and the pump would stay unreachable until someone noticed.
     */
    var needsReconnect: Boolean = false
        private set

    /** True while the app should leave the RileyLink alone. */
    fun isHeld(now: Long): Boolean = now < untilMillis

    /** Whole minutes left, rounded up, so a part minute still reads as one. Zero when free. */
    fun minutesLeft(now: Long): Int {
        val left = untilMillis - now
        return if (left <= 0) 0 else ((left + MINUTE_MS - 1) / MINUTE_MS).toInt()
    }

    /**
     * Holds the link for [minutes] from [now].
     *
     * Asking again while a hold is running replaces it rather than adding to it, so pressing the
     * button twice cannot quietly stretch the window to twice its length.
     *
     * @return the moment the hold will end.
     */
    fun hold(now: Long, minutes: Int): Long {
        val capped = minutes.coerceIn(1, MAX_MINUTES)
        untilMillis = now + capped * MINUTE_MS
        needsReconnect = true
        return untilMillis
    }

    /** Ends the hold now. The link still has to be re-opened; [reconnected] says when it was. */
    fun release() {
        untilMillis = 0
    }

    /** Call after the link has been opened again. */
    fun reconnected() {
        needsReconnect = false
    }

    /**
     * True when the hold is over but the link has not been brought back yet.
     *
     * This is the moment to re-open it, and it is asked on a timer rather than scheduled, so a
     * phone that slept through the end of the window still recovers on its next tick.
     */
    fun shouldReconnect(now: Long): Boolean = needsReconnect && !isHeld(now)

    companion object {

        private const val MINUTE_MS = 60_000L

        /**
         * The longest hold that can be asked for.
         *
         * Long enough to run a bench test, short enough that forgetting about it is not the same
         * as switching the pump off for the afternoon.
         */
        const val MAX_MINUTES = 30

        /** What the button asks for when it is pressed. */
        const val DEFAULT_MINUTES = 10
    }
}
