package app.aaps.pump.common.hw.rileylink.service

/**
 * A hold on the Bluetooth link to the RileyLink, for as long as it takes.
 *
 * A RileyLink accepts one Bluetooth connection at a time. While the phone holds it, nothing else
 * can reach it - not a laptop running a bench test, not a second phone. Letting go needs more
 * than a disconnect, because the Android stack reconnects on its own, so this records that the
 * app is to stay away until it is told otherwise.
 *
 * The hold does not end by itself. That is deliberate and it is the whole point: a bench session
 * takes as long as it takes, and a window that expired in the middle of one would take the
 * RileyLink back while it was being used. Nothing is managing the pump while this is held.
 */
class RileyLinkRelease {

    /** When the hold started, as epoch milliseconds. Zero when there is no hold. */
    var heldSince: Long = 0
        private set

    /**
     * True once a hold has closed the link and it has not been re-opened yet.
     *
     * Releasing closes the Bluetooth client, which is the only way to cancel the automatic
     * reconnect Android performs on its own. Nothing re-opens it by itself, so without this the
     * hold would be lifted and the pump would stay unreachable until someone noticed.
     */
    var needsReconnect: Boolean = false
        private set

    /** True while the app should leave the RileyLink alone. */
    val isHeld: Boolean get() = heldSince != 0L

    /** Whole minutes the hold has lasted so far, for the button to show. Zero when free. */
    fun minutesHeld(now: Long): Int {
        if (!isHeld) return 0
        val elapsed = now - heldSince
        return if (elapsed <= 0) 0 else (elapsed / MINUTE_MS).toInt()
    }

    /**
     * Starts the hold, or leaves an existing one alone.
     *
     * Asking again while a hold is running keeps the original start time, so the button does not
     * reset the elapsed count each time it is pressed.
     */
    fun hold(now: Long) {
        if (!isHeld) heldSince = now
        needsReconnect = true
    }

    /** Ends the hold. The link still has to be opened again; [reconnected] says when it was. */
    fun release() {
        heldSince = 0
    }

    /** Call after the link has been opened again. */
    fun reconnected() {
        needsReconnect = false
    }

    /**
     * True when the hold is over but the link has not been brought back yet.
     *
     * Asked on a timer rather than scheduled, so the link comes back on the next tick after the
     * hold is lifted however the app happened to be sleeping at the time.
     */
    fun shouldReconnect(): Boolean = needsReconnect && !isHeld

    companion object {

        private const val MINUTE_MS = 60_000L
    }
}
