package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkEncodingType
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/** Where the firmware version in use came from. */
enum class VersionSource {

    /** No version resolved yet. */
    NONE,

    /** Read from the CC1110 on this connection. */
    RADIO,

    /** Reused from the last good read for this RileyLink, because this read failed. */
    CACHE,

    /** Nothing known, so the safe version 2 default is in use. */
    FALLBACK
}

/** Whether the CC1110 is answering commands. */
enum class ChipState {

    /** Answering. */
    RESPONDING,

    /** Not answering, but has answered before on this connection. */
    SILENT,

    /** Nothing sent yet on this connection. */
    UNKNOWN
}

/** How loud an event is. Warnings are the ones worth looking at first. */
enum class DiagSeverity { INFO, WARN }

/**
 * One recorded marker, kept so the screen can show what just happened without exporting a log.
 *
 * While testing on real hardware you are holding the phone, not reading a log file, and the
 * interesting moments last seconds. Being able to watch the markers arrive is the difference
 * between seeing a disconnect and reconstructing it afterwards.
 */
data class RileyLinkDiagEvent(
    val atMillis: Long,
    val event: String,
    val detail: String,
    val severity: DiagSeverity
)

/**
 * Everything the diagnostics screen shows, in one immutable value.
 *
 * Times are epoch milliseconds so the screen can format them with the app's own date helper.
 */
data class RileyLinkDiagSnapshot(
    val linkUp: Boolean = false,
    val ble113Version: String? = null,
    val cc1110Version: String? = null,
    val firmwareVersion: String? = null,
    val versionSource: VersionSource = VersionSource.NONE,
    val protocolV2: Boolean = true,
    val encoding: RileyLinkEncodingType? = null,
    val chipState: ChipState = ChipState.UNKNOWN,
    val silentStreak: Int = 0,
    val silentSinceMillis: Long? = null,
    val lastCommandName: String? = null,
    val lastCommandHex: String? = null,
    val lastCommandDetail: String? = null,
    val lastCommandAtMillis: Long? = null,
    val lastResponseHex: String? = null,
    val lastResponseAtMillis: Long? = null,
    val lastWaitedMs: Long? = null,
    val unexpectedDisconnects: Int = 0,
    val gattWriteTimeouts: Int = 0,
    val writesWhileLinkDown: Int = 0,
    val versionSlipsSeen: Int = 0,
    val concurrentInitPeak: Int = 0,
    /** Most recent markers, newest first. Capped at [RileyLinkDiag.EVENT_HISTORY]. */
    val events: List<RileyLinkDiagEvent> = emptyList()
)

/**
 * Collects what the RileyLink stack is doing, for the log and for the diagnostics screen.
 *
 * Every line it writes starts with `RLDIAG|` and is a flat list of `key=value` pairs, so a whole
 * session can be pulled out of an exported log with one search and read without the surrounding
 * noise. The point of the fixed shape is that these lines are meant to be compared between
 * builds: same keys, same order, so a later log can be diffed against an earlier one.
 *
 * This class only records. It changes no behaviour and makes no decisions, so turning the markers
 * on cannot alter how the pump is driven.
 */
@SingleIn(AppScope::class)
@Inject
class RileyLinkDiag(
    private val aapsLogger: AAPSLogger
) {

    private val _snapshot = MutableStateFlow(RileyLinkDiagSnapshot())

    /** Current state, for the diagnostics screen. */
    val snapshot: StateFlow<RileyLinkDiagSnapshot> = _snapshot.asStateFlow()

    /** How many init runs are in flight. More than one at a time is a fault in itself. */
    private val initsInFlight = AtomicInteger(0)

    private fun mark(event: String, vararg pairs: Pair<String, Any?>) =
        record(DiagSeverity.INFO, event, pairs)

    private fun markWarn(event: String, vararg pairs: Pair<String, Any?>) =
        record(DiagSeverity.WARN, event, pairs)

    private fun record(severity: DiagSeverity, event: String, pairs: Array<out Pair<String, Any?>>) {
        val body = pairs.joinToString("|") { (k, v) -> "$k=$v" }
        val line = if (body.isEmpty()) "RLDIAG|$event" else "RLDIAG|$event|$body"
        when (severity) {
            DiagSeverity.INFO -> aapsLogger.debug(LTag.RLDIAG, line)
            DiagSeverity.WARN -> aapsLogger.warn(LTag.RLDIAG, line)
        }
        val entry = RileyLinkDiagEvent(System.currentTimeMillis(), event, body, severity)
        _snapshot.update { it.copy(events = (listOf(entry) + it.events).take(EVENT_HISTORY)) }
    }

    // region version

    /**
     * The raw bytes of a `GetVersion` reply, before anything tries to make sense of them.
     *
     * Also runs the bit slip check, so a reply damaged between the CC1110 and the BLE113 is
     * reported as such instead of being written off as noise. Detection only: nothing uses the
     * recovered text.
     */
    @Synchronized
    fun versionRead(raw: ByteArray?, parsed: String?) {
        mark(
            "VER_RAW",
            "len" to (raw?.size ?: 0),
            "hex" to ByteUtil.shortHexString(raw),
            "parsed" to (parsed ?: "-")
        )
        if (parsed == null || !parsed.contains("subg_rfspy")) {
            VersionSlip.detect(raw)?.let { slip ->
                _snapshot.update { it.copy(versionSlipsSeen = it.versionSlipsSeen + 1) }
                markWarn(
                    "VER_SLIP",
                    "detected" to true,
                    "shiftBits" to slip.shiftBits,
                    "recovered" to slip.recovered
                )
            }
        }
    }

    /** The version finally settled on, and where it came from. */
    @Synchronized
    fun versionVerdict(firmwareVersion: String, source: VersionSource, cc1110Version: String?, bleVersion: String?) {
        _snapshot.update {
            it.copy(
                firmwareVersion = firmwareVersion,
                versionSource = source,
                cc1110Version = cc1110Version,
                ble113Version = bleVersion
            )
        }
        mark("VER_VERDICT", "resolved" to firmwareVersion, "source" to source, "cc1110" to (cc1110Version ?: "-"), "ble113" to (bleVersion ?: "-"))
    }

    /** The wire settings derived from the version. All of them on one line, so drift is visible. */
    @Synchronized
    fun protocol(v2: Boolean, encoding: RileyLinkEncodingType, stopAtNull: Boolean) {
        _snapshot.update { it.copy(protocolV2 = v2, encoding = encoding) }
        mark("PROTO", "packetV2" to v2, "enc" to encoding.name, "stopAtNull" to stopAtNull, "rxOffset" to if (v2) 3 else 2)
    }

    // endregion

    // region init

    /** Call when an init run starts. Reports the number in flight so a race shows up at once. */
    fun initEnter(thread: String) {
        val n = initsInFlight.incrementAndGet()
        _snapshot.update { if (n > it.concurrentInitPeak) it.copy(concurrentInitPeak = n) else it }
        if (n > 1) markWarn("INIT_ENTER", "thread" to thread, "concurrent" to n)
        else mark("INIT_ENTER", "thread" to thread, "concurrent" to n)
    }

    /** Call when an init run ends, whatever the outcome. */
    fun initExit(thread: String) {
        val n = initsInFlight.decrementAndGet()
        mark("INIT_EXIT", "thread" to thread, "concurrent" to n)
    }

    // endregion

    // region traffic

    /**
     * A command about to be written to the radio.
     *
     * @param v2 the wire format the command was built in. Logged as its own field so the whole
     *   point of this work - that no version 1 command is ever emitted to a version 2 radio - can
     *   be checked with one search, without needing the raw Bluetooth trace switched on.
     * @param detail the decoded fields, as the radio will read them.
     */
    @Synchronized
    fun tx(name: String, payload: ByteArray, v2: Boolean, detail: String?) {
        _snapshot.update {
            it.copy(
                lastCommandName = name,
                lastCommandHex = ByteUtil.shortHexString(payload),
                lastCommandDetail = detail,
                lastCommandAtMillis = System.currentTimeMillis()
            )
        }
        mark(
            "TX", "op" to name, "fmt" to if (v2) "v2" else "v1", "len" to payload.size,
            "detail" to (detail ?: "-"), "hex" to ByteUtil.shortHexString(payload)
        )
    }

    /**
     * The outcome of the command reported by the last [tx].
     *
     * @param raw the reply, or null when nothing came back before [waitedMs] ran out.
     */
    @Synchronized
    fun rx(name: String, raw: ByteArray?, waitedMs: Long) {
        val now = System.currentTimeMillis()
        if (raw == null) {
            val streak = _snapshot.value.silentStreak + 1
            _snapshot.update {
                it.copy(
                    lastResponseHex = null,
                    lastResponseAtMillis = null,
                    lastWaitedMs = waitedMs,
                    chipState = ChipState.SILENT,
                    silentStreak = it.silentStreak + 1,
                    silentSinceMillis = it.silentSinceMillis ?: now
                )
            }
            markWarn("RX", "op" to name, "result" to "NONE", "waited" to waitedMs, "silentStreak" to streak)
        } else {
            _snapshot.update {
                it.copy(
                    lastResponseHex = ByteUtil.shortHexString(raw),
                    lastResponseAtMillis = now,
                    lastWaitedMs = waitedMs,
                    chipState = ChipState.RESPONDING,
                    silentStreak = 0,
                    silentSinceMillis = null
                )
            }
            mark("RX", "op" to name, "result" to "OK", "len" to raw.size, "waited" to waitedMs, "hex" to ByteUtil.shortHexString(raw))
        }
    }

    // endregion

    // region link

    /** A GATT connection came up and the RileyLink services were found. */
    @Synchronized
    fun linkUp() {
        _snapshot.update { it.copy(linkUp = true, chipState = ChipState.UNKNOWN, silentStreak = 0, silentSinceMillis = null) }
        mark("LINK", "event" to "connected")
    }

    /**
     * A GATT connection went away.
     *
     * @param expected true when the app asked for the disconnect, false when the link dropped by
     *   itself (a supervision timeout, for example).
     */
    @Synchronized
    fun linkDown(expected: Boolean, status: Int, gattClosed: Boolean) {
        _snapshot.update {
            it.copy(
                linkUp = false,
                unexpectedDisconnects = it.unexpectedDisconnects + if (expected) 0 else 1
            )
        }
        markWarn("LINK", "event" to "disconnected", "expected" to expected, "status" to status, "gattClosed" to gattClosed)
    }

    /**
     * A recovery probe: one cheap attempt to reach the pump while the driver is waiting for it
     * to come back.
     *
     * @param found true when the pump answered, which ends the waiting
     * @param attempt how many probes have been sent since the driver started waiting
     * @param nextInMinutes how long until the next probe, 0 when there will not be another
     */
    fun probe(found: Boolean, attempt: Int, nextInMinutes: Int) {
        mark("PROBE", "found" to found, "attempt" to attempt, "nextInMin" to nextInMinutes)
    }

    /** A GATT operation was refused because the link is down, instead of waiting for a timeout. */
    @Synchronized
    fun writeRefusedLinkDown(operation: String) {
        _snapshot.update { it.copy(writesWhileLinkDown = it.writesWhileLinkDown + 1) }
        markWarn("LINK_REFUSED", "op" to operation, "reason" to "linkDown", "count" to _snapshot.value.writesWhileLinkDown)
    }

    /** A GATT operation waited its full timeout without any callback. */
    @Synchronized
    fun gattTimeout(operation: String, waitedMs: Long) {
        _snapshot.update { it.copy(gattWriteTimeouts = it.gattWriteTimeouts + 1) }
        markWarn("GATT_TIMEOUT", "op" to operation, "waited" to waitedMs, "count" to _snapshot.value.gattWriteTimeouts)
    }

    // endregion

    companion object {

        /**
         * How many markers to keep for the screen.
         *
         * Enough to cover a disconnect and the reconnect that follows it, and small enough that
         * rebuilding the list on every marker costs nothing. The full history is in the log.
         */
        const val EVENT_HISTORY = 40
    }
}
