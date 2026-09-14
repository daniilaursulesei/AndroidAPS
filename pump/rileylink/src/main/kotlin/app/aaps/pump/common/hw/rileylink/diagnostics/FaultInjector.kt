package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A fault the tester can arm on purpose, to make a rare failure happen on demand.
 *
 * Each one reproduces something seen in a real log. Without them the only way to reach these paths
 * is to power cycle the RileyLink over and over and hope to land inside a window of about a second.
 */
enum class InjectableFault(val title: String, val what: String) {

    /**
     * The next firmware version read returns nothing, as if the CC1110 were not awake yet.
     *
     * Seen in every field log. It is what the version cache exists for: with a stored value the
     * app carries on, without one it has to guess.
     */
    VERSION_READ_FAILS(
        "Version read returns nothing",
        "The next firmware read is answered as empty. Expect the app to fall back to the stored version for this RileyLink."
    ),

    /**
     * The next firmware version read returns the exact bytes from the log where a bit was lost on
     * the link between the CC1110 and the BLE113.
     */
    VERSION_READ_BIT_SLIP(
        "Version read arrives shifted by one bit",
        "The next firmware read is answered with the real bytes from the log where the chip to chip link lost a bit. Expect a VER_SLIP warning."
    ),

    /**
     * The next GATT operation is treated as if the link had just dropped.
     *
     * Reproduces the supervision timeout that used to leave the app writing into a dead connection
     * for 22 seconds at a time.
     */
    LINK_DROPS(
        "Bluetooth link drops",
        "The next Bluetooth operation is refused as if the link had gone. Expect an immediate refusal rather than a long wait."
    )
}

/**
 * Holds at most one armed fault, for testing.
 *
 * Three rules make this safe enough to exist in a build that drives a real pump:
 *
 *  - **One shot.** Arming sets a single fault. The first piece of code that asks for it consumes
 *    it and it is gone. Nothing stays broken.
 *  - **It expires.** An armed fault that nobody consumes disarms itself after [EXPIRY_MS]. A tester
 *    who arms one and puts the phone down does not leave a trap behind.
 *  - **It is loud.** [armed] drives a banner on the diagnostics screen, so the app never quietly
 *    behaves differently from how it looks.
 *
 * Everything it does is also written to the log, so a log from a session with injection in it can
 * never be mistaken for a normal one.
 */
@SingleIn(AppScope::class)
@Inject
class FaultInjector(
    private val aapsLogger: AAPSLogger
) {

    private val _armed = MutableStateFlow<InjectableFault?>(null)

    /** The fault waiting to fire, or null. Shown on screen while it is set. */
    val armed: StateFlow<InjectableFault?> = _armed.asStateFlow()

    private var armedAtMillis = 0L

    /** Arms [fault]. Replaces anything already armed - only one can be waiting at a time. */
    @Synchronized
    fun arm(fault: InjectableFault) {
        _armed.value = fault
        armedAtMillis = System.currentTimeMillis()
        aapsLogger.warn(LTag.RLDIAG, "RLDIAG|INJECT_ARMED|fault=${fault.name}|expiresInMs=$EXPIRY_MS")
    }

    /** Clears anything armed. Safe to call when nothing is. */
    @Synchronized
    fun disarm() {
        _armed.value?.let { aapsLogger.warn(LTag.RLDIAG, "RLDIAG|INJECT_DISARMED|fault=${it.name}") }
        _armed.value = null
    }

    /**
     * Takes [fault] if it is the one armed, and clears it.
     *
     * @return true if the caller should behave as if the fault happened. False in every other case,
     *   including an armed fault that has sat too long, which is dropped instead of fired.
     */
    @Synchronized
    fun consume(fault: InjectableFault): Boolean {
        val current = _armed.value ?: return false
        if (System.currentTimeMillis() - armedAtMillis > EXPIRY_MS) {
            aapsLogger.warn(LTag.RLDIAG, "RLDIAG|INJECT_EXPIRED|fault=${current.name}")
            _armed.value = null
            return false
        }
        if (current != fault) return false
        _armed.value = null
        aapsLogger.warn(LTag.RLDIAG, "RLDIAG|INJECT_FIRED|fault=${fault.name}")
        return true
    }

    companion object {

        /** How long an armed fault waits before it gives up. */
        const val EXPIRY_MS = 2 * 60 * 1000L

        /**
         * The reply from the log where one bit was lost between the CC1110 and the BLE113.
         *
         * Sixteen of these seventeen bytes are an exact `subg_rfspy 2.2.21` once the stream is
         * moved back by a bit. Kept as the real bytes rather than something made up, so what the
         * detector sees in a test is what it will see in the field.
         */
        val BIT_SLIPPED_VERSION_REPLY: ByteArray = byteArrayOf(
            0xBA.toByte(), 0xE7.toByte(), 0xEA.toByte(), 0xC4.toByte(), 0xCE.toByte(), 0xBE.toByte(),
            0xE4.toByte(), 0xCC.toByte(), 0xE6.toByte(), 0xE0.toByte(), 0xF2.toByte(), 0x40,
            0x64, 0x5C, 0x64, 0x5C, 0x64, 0x62
        )
    }
}
