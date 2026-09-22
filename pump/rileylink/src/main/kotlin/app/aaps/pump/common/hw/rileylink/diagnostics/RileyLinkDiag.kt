package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyScanResults
import app.aaps.pump.common.hw.rileylink.ble.data.RadioStats
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
    /**
     * The same reply in words.
     *
     * Worked out here, where the bytes are already in hand, rather than on the screen: turning
     * the hex text back into bytes to read it a second time is one more thing that can be wrong.
     */
    val lastResponseWords: String? = null,
    val lastResponseAtMillis: Long? = null,
    val lastWaitedMs: Long? = null,
    val unexpectedDisconnects: Int = 0,
    val gattWriteTimeouts: Int = 0,
    val writesWhileLinkDown: Int = 0,
    val versionSlipsSeen: Int = 0,
    val concurrentInitPeak: Int = 0,
    /** Commands that had to wait for another command to release the radio. */
    val radioTurnsWaited: Int = 0,
    /** The longest any command waited for its turn on the radio. */
    val radioLongestWaitMs: Long = 0,
    /** Commands dropped because the radio never became free in time. Must stay at zero. */
    val radioTurnsMissed: Int = 0,
    /** Replies found in the queue before a command was sent, so they belong to nothing. */
    val radioJunkDrained: Int = 0,
    /**
     * Commands answered with the reply of the command before them. Must stay at zero.
     *
     * The one number that says the radio stream is out of step. Everything else on this screen
     * can look healthy while this climbs, because every command gets an answer - just the wrong
     * one. In the log that made the CC1110 version read as "-" this would have climbed to 23.
     */
    val repliesCrossed: Int = 0,
    /**
     * Replies that arrived after their caller gave up, and were caught before the next command.
     *
     * Not a fault on its own. The command that asked reports nothing and will be retried, but the
     * stream stays in step. A number that climbs means the timeouts are set too short for this
     * RileyLink.
     */
    val repliesLate: Int = 0,
    /**
     * Replies the RileyLink never sent, even after the settle period.
     *
     * The chip holds one reply at a time, so two landing together destroy one. Nothing the app
     * does can bring it back; the command is simply retried.
     */
    val repliesLost: Int = 0,
    /** The last command whose reply was crossed, late or lost, for the screen. */
    val lastReplyProblem: String? = null,
    /** When [lastReplyProblem] happened. */
    val lastReplyProblemAtMillis: Long? = null,
    /** The radio chip's counters, as last read. Null when they have never been read. */
    val radioStats: RadioStats? = null,
    /** Times the radio chip restarted on its own, seen as its uptime going backwards. */
    val radioResets: Int = 0,
    /** Radio commands refused because the RileyLink is blocked. */
    val writesWhileBlocked: Int = 0,
    /** Most recent markers, newest first. Capped at [RileyLinkDiag.EVENT_HISTORY]. */
    val events: List<RileyLinkDiagEvent> = emptyList(),
    /**
     * The pump serial in the settings against the one really in the packets.
     *
     * Null until a packet has been built. These two can disagree - the pump ID is loaded into
     * the radio layer once, when the service is built - and when they do, every command goes to
     * a pump that is not there while the screen shows the right number.
     */
    val serialCheck: SerialCheck? = null,
    /** The last frequency scan, one row per frequency tried. Empty until a scan has run. */
    val lastScan: List<ScanRow> = emptyList(),
    val lastScanAtMillis: Long? = null,
    /** What the driver is waiting for right now, or null when it is not waiting. */
    val waitReason: WaitReason? = null,
    /** Seconds until the next attempt, when one is scheduled. */
    val waitNextTrySeconds: Int? = null,
    /** What the driver decided and why, newest first. Capped at [RileyLinkDiag.DECISION_HISTORY]. */
    val decisions: List<DiagDecision> = emptyList()
)

/**
 * One choice the driver made, in words.
 *
 * The screen showed state but never intent. "Tuning up" tells you what it is doing and not why it
 * started, and the why is the part a person needs to judge whether the app is behaving sensibly.
 *
 * @property what the action taken.
 * @property why the condition that caused it, with the numbers that were compared.
 */
data class DiagDecision(
    val atMillis: Long,
    val what: String,
    val why: String
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

    /**
     * A warning that reads as a sentence on the screen and as key=value in the log.
     *
     * The two readers want different things. Someone standing next to a pump needs to know what
     * went wrong without decoding `step=CROSSED|owed=1`, and someone reading a log file later
     * wants to find every one of them at once. [words] goes on the screen, the pairs go in the
     * log, and neither is made worse to suit the other.
     */
    private fun markWarnInWords(event: String, words: String, vararg pairs: Pair<String, Any?>) =
        record(DiagSeverity.WARN, event, pairs, words)

    private fun record(
        severity: DiagSeverity,
        event: String,
        pairs: Array<out Pair<String, Any?>>,
        words: String? = null
    ) {
        val body = pairs.joinToString("|") { (k, v) -> "$k=$v" }
        val line = if (body.isEmpty()) "RLDIAG|$event" else "RLDIAG|$event|$body"
        when (severity) {
            DiagSeverity.INFO -> aapsLogger.debug(LTag.RLDIAG, line)
            DiagSeverity.WARN -> aapsLogger.warn(LTag.RLDIAG, line)
        }
        val entry = RileyLinkDiagEvent(System.currentTimeMillis(), event, words ?: body, severity)
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
            "thread" to Thread.currentThread().name,
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
                    lastResponseWords = describeReply(name, null),
                    lastResponseAtMillis = null,
                    lastWaitedMs = waitedMs,
                    chipState = ChipState.SILENT,
                    silentStreak = it.silentStreak + 1,
                    silentSinceMillis = it.silentSinceMillis ?: now
                )
            }
            markWarn("RX", "op" to name, "result" to "NONE", "waited" to waitedMs, "thread" to Thread.currentThread().name, "silentStreak" to streak)
        } else {
            _snapshot.update {
                it.copy(
                    lastResponseHex = ByteUtil.shortHexString(raw),
                    lastResponseWords = describeReply(name, raw),
                    lastResponseAtMillis = now,
                    lastWaitedMs = waitedMs,
                    chipState = ChipState.RESPONDING,
                    silentStreak = 0,
                    silentSinceMillis = null
                )
            }
            mark("RX", "op" to name, "result" to "OK", "len" to raw.size, "waited" to waitedMs, "thread" to Thread.currentThread().name, "hex" to ByteUtil.shortHexString(raw))
        }
    }

    // endregion

    // region radio turns

    /**
     * A command had to wait before it could use the radio.
     *
     * The radio answers one command at a time. When two threads send at once the second one gets
     * `0xBB` (interrupted) or `0x22` (unknown command), and the two can take each other's
     * replies. Waiting for a turn is the cure, and this marker is the proof it happened: every
     * line here is one collision that did not occur.
     *
     * @param op the command that waited
     * @param waitedMs how long it waited
     * @param behind the thread that was holding the radio
     * @param behindOp the command that thread was running
     */
    @Synchronized
    fun radioTurnTaken(op: String, waitedMs: Long, behind: String?, behindOp: String?) {
        _snapshot.update {
            it.copy(
                radioTurnsWaited = it.radioTurnsWaited + 1,
                radioLongestWaitMs = maxOf(it.radioLongestWaitMs, waitedMs)
            )
        }
        mark(
            "RADIO_TURN", "op" to op, "waitedMs" to waitedMs,
            "thread" to Thread.currentThread().name,
            "behind" to (behind ?: "-"), "behindOp" to (behindOp ?: "-")
        )
    }

    /**
     * A command was dropped because the radio never became free.
     *
     * This should never happen. If it does, a command is stuck holding the radio, and the wait
     * cap is the only thing keeping the rest of the app moving.
     */
    @Synchronized
    fun radioTurnMissed(op: String, waitedMs: Long, behind: String?, behindOp: String?) {
        _snapshot.update { it.copy(radioTurnsMissed = it.radioTurnsMissed + 1) }
        markWarn(
            "RADIO_TURN_MISSED", "op" to op, "waitedMs" to waitedMs,
            "thread" to Thread.currentThread().name,
            "behind" to (behind ?: "-"), "behindOp" to (behindOp ?: "-"),
            "count" to _snapshot.value.radioTurnsMissed
        )
    }

    /**
     * A reply was sitting in the queue before a command was even sent, so it belongs to nothing.
     *
     * While two threads shared the radio this was usually the other thread's reply. With one
     * command at a time it can only be a late answer from the radio itself, which makes it a
     * useful measurement rather than noise.
     */
    @Synchronized
    fun radioJunkDrained(op: String, junk: ByteArray) {
        _snapshot.update { it.copy(radioJunkDrained = it.radioJunkDrained + 1) }
        markWarn(
            "RADIO_JUNK", "op" to op, "len" to junk.size,
            "thread" to Thread.currentThread().name,
            "hex" to ByteUtil.shortHexString(junk),
            "count" to _snapshot.value.radioJunkDrained
        )
    }

    /**
     * Records what became of one command's reply.
     *
     * Called once per command, while the radio is still held, so the counts cannot race. Only the
     * three problem cases are written to the log; writing a line for every healthy command would
     * bury them.
     */
    @Synchronized
    fun replyStepTaken(op: String, step: ReplyStep, owedNow: Int) {
        if (step == ReplyStep.IN_STEP) return
        _snapshot.update {
            it.copy(
                repliesCrossed = it.repliesCrossed + if (step == ReplyStep.CROSSED) 1 else 0,
                repliesLate = it.repliesLate + if (step == ReplyStep.LATE_DRAINED) 1 else 0,
                repliesLost = it.repliesLost + if (step == ReplyStep.LOST) 1 else 0,
                // The sentence, not the enum name. This line is read on a screen, next to a
                // pump, by someone who needs to know what went wrong rather than decode it.
                lastReplyProblem = replyProblemWords(op, step),
                lastReplyProblemAtMillis = System.currentTimeMillis()
            )
        }
        val counts = _snapshot.value
        markWarnInWords(
            "REPLY", replyProblemWords(op, step),
            "op" to op, "step" to step.name, "owed" to owedNow,
            "crossed" to counts.repliesCrossed, "late" to counts.repliesLate,
            "lost" to counts.repliesLost
        )
    }

    /** A reply the radio owed turned up during the settle period, after its caller had gone. */
    @Synchronized
    fun replySettledLate(op: String, late: ByteArray) {
        markWarnInWords(
            "REPLY_LATE", "The answer to $op arrived after the app had stopped waiting. It was thrown away here, so the next command still gets its own.",
            "op" to op, "len" to late.size,
            "hex" to ByteUtil.shortHexString(late)
        )
    }

    /** The settle period ended and the reply never came. */
    @Synchronized
    fun replyNeverCame(op: String, owedNow: Int) {
        markWarnInWords(
            "REPLY_LOST", "The RileyLink never answered $op, even after waiting on for it.",
            "op" to op, "owed" to owedNow
        )
    }

    // endregion

    // region radio health

    /** The counters from the previous [radioStats] call, for the deltas. */
    private var lastStats: RadioStats? = null

    /**
     * The radio chip's own counters, with what changed since the last read.
     *
     * The deltas are the useful part. A stretch where the pump said nothing is either "sent
     * climbed, received did not", which puts the fault past the RileyLink's antenna, or "sent did
     * not climb", which puts it inside the RileyLink. From a timeout alone the two are identical.
     *
     * Packets sent counts one per transmission, so a wake up that repeats its packet 200 times is
     * expected to add 201.
     *
     * @param reason where the app was in its own sequence when it read them.
     * @param stats the counters, or null when the radio did not answer with a whole reply.
     * @param raw what did come back, so an unreadable reply is still in the log.
     */
    @Synchronized
    fun radioStats(reason: String, stats: RadioStats?, raw: ByteArray?) {
        if (stats == null) {
            markWarn("RADIO_STATS", "reason" to reason, "result" to "UNREADABLE", "hex" to ByteUtil.shortHexString(raw))
            return
        }
        val previous = lastStats
        // Every counter lives in the chip, so a restart zeroes them all and the deltas from
        // before it are not differences in anything.
        val restarted = previous != null && stats.uptimeMs < previous.uptimeMs
        if (restarted) _snapshot.update { it.copy(radioResets = it.radioResets + 1) }
        val sentDelta = if (previous == null || restarted) null else stats.packetsSent - previous.packetsSent
        val rxDelta = if (previous == null || restarted) null else stats.packetsReceived - previous.packetsReceived
        lastStats = stats
        _snapshot.update { it.copy(radioStats = stats) }
        val line = arrayOf<Pair<String, Any?>>(
            "reason" to reason,
            "uptimeMs" to stats.uptimeMs,
            "sent" to stats.packetsSent,
            "received" to stats.packetsReceived,
            "sentDelta" to (sentDelta ?: "-"),
            "receivedDelta" to (rxDelta ?: "-"),
            "rxOverflow" to stats.rxOverflow,
            "rxFifoOverflow" to stats.rxFifoOverflow,
            "chipRestarted" to restarted
        )
        if (restarted) record(DiagSeverity.WARN, "RADIO_STATS", line)
        else record(DiagSeverity.INFO, "RADIO_STATS", line)
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

    /**
     * A radio command was refused because the RileyLink is blocked.
     *
     * Counted and marked rather than dropped in silence. While a block is on, every command stops
     * before it reaches the radio, so the TX and RX markers stop with it and the diagnostics card
     * has nothing left to show. A card that has gone blank looks exactly like a broken one, so the
     * block has to say that it is the reason.
     */
    @Synchronized
    fun writeRefusedBlocked(operation: String, macAddress: String?) {
        _snapshot.update { it.copy(writesWhileBlocked = it.writesWhileBlocked + 1) }
        markWarn(
            "BLOCKED", "op" to operation, "device" to (macAddress ?: "-"),
            "count" to _snapshot.value.writesWhileBlocked
        )
    }

    /** A GATT operation waited its full timeout without any callback. */
    @Synchronized
    fun gattTimeout(operation: String, waitedMs: Long) {
        _snapshot.update { it.copy(gattWriteTimeouts = it.gattWriteTimeouts + 1) }
        markWarn("GATT_TIMEOUT", "op" to operation, "waited" to waitedMs, "count" to _snapshot.value.gattWriteTimeouts)
    }

    // endregion

    // region what the driver chose to do

    /**
     * Record which pump the radio is really calling.
     *
     * Called wherever a pump packet is built, so the screen compares the two values that matter
     * rather than the one the settings happen to hold.
     */
    @Synchronized
    fun pumpAddress(configuredSerial: String?, addressBytes: ByteArray?) {
        val check = checkSerial(configuredSerial, addressBytes)
        if (_snapshot.value.serialCheck == check) return          // only on a change, not per packet
        _snapshot.update { it.copy(serialCheck = check) }
        if (check.agree) {
            mark("PUMP_ADDRESS", "serial" to check.onWire)
        } else {
            markWarn(
                "PUMP_ADDRESS_MISMATCH",
                "configured" to (check.configured ?: "none"),
                "onWire" to (check.onWire ?: "none")
            )
        }
    }

    /** Record the whole frequency scan, not only the frequency it picked. */
    @Synchronized
    fun scanFinished(results: FrequencyScanResults?) {
        val rows = scanRows(results)
        _snapshot.update { it.copy(lastScan = rows, lastScanAtMillis = System.currentTimeMillis()) }
        mark("SCAN_RESULT", "rows" to rows.size, "verdict" to describeScan(rows))
    }

    /** Record a choice the driver made, with the reason that drove it. */
    @Synchronized
    fun decided(what: String, why: String) {
        val entry = DiagDecision(System.currentTimeMillis(), what, why)
        _snapshot.update { it.copy(decisions = (listOf(entry) + it.decisions).take(DECISION_HISTORY)) }
        mark("DECISION", "what" to what, "why" to why)
    }

    /**
     * Record what the driver is waiting for, so a long pause can be read as a countdown.
     *
     * Pass null to say it is not waiting any more.
     */
    @Synchronized
    fun waiting(reason: WaitReason?, nextTrySeconds: Int? = null) {
        _snapshot.update { it.copy(waitReason = reason, waitNextTrySeconds = nextTrySeconds) }
        if (reason != null) mark("WAITING", "reason" to reason.name, "nextIn" to (nextTrySeconds ?: "-"))
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

        /**
         * How many decisions to keep.
         *
         * Fewer than the markers: a decision is rare and each one matters, so a short list that
         * can be read at a glance beats a long one that has to be scrolled.
         */
        const val DECISION_HISTORY = 12

    }
}
