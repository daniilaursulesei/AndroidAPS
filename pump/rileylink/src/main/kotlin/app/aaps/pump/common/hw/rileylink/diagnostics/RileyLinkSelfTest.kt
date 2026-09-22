package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.utils.pump.ByteUtil
import app.aaps.pump.common.hw.rileylink.RileyLinkConst
import app.aaps.pump.common.hw.rileylink.RileyLinkUtil
import app.aaps.pump.common.hw.rileylink.ble.RFSpy
import app.aaps.pump.common.hw.rileylink.ble.RileyLinkBLE
import app.aaps.pump.common.hw.rileylink.ble.defs.RileyLinkFirmwareVersionBase
import app.aaps.pump.common.hw.rileylink.ble.data.RadioStats
import app.aaps.pump.common.hw.rileylink.ble.defs.usesV2Protocol
import app.aaps.pump.common.hw.rileylink.service.FirmwareVersionStore
import app.aaps.pump.common.hw.rileylink.service.RileyLinkServiceData
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicReference

/**
 * Works out what is wrong with the RileyLink, and can try to put it right.
 *
 * It walks the signal path in order - Bluetooth adapter, configuration, BLE link, the BLE113, the
 * CC1110, the wire format - and stops describing once it finds the first thing that is broken,
 * because everything past a broken link is meaningless.
 *
 * The report says what was measured, not just whether it passed. A red light tells a user that
 * something is wrong, which they already knew. What they cannot get anywhere else is that the
 * BLE113 answered and the CC1110 did not, and that is what decides whether to reset the radio,
 * reconnect Bluetooth, or reach for the battery.
 *
 * Every method here blocks on Bluetooth, so call it off the main thread.
 *
 * It does **not** get exclusive use of the radio. The GATT lock serialises single Bluetooth
 * operations, not a whole radio command and its reply, so a probe sent while the app is talking to
 * the pump interrupts that exchange and both sides can end up reading the other's answer. The radio
 * says so itself, with 0xBB. So the radio check waits for the bus to go quiet, and if it will not,
 * says its result cannot be trusted instead of blaming the radio for interference it caused.
 */
@SingleIn(AppScope::class)
@Inject
class RileyLinkSelfTest(
    private val aapsLogger: AAPSLogger,
    private val rileyLinkBLE: RileyLinkBLE,
    private val rfSpy: RFSpy,
    private val rileyLinkServiceData: RileyLinkServiceData,
    private val firmwareVersionStore: FirmwareVersionStore,
    private val rileyLinkUtil: RileyLinkUtil,
    private val diag: RileyLinkDiag
) {

    /** Runs every check and returns what it found. Blocks; do not call on the main thread. */
    fun run(): DiagnosisReport {
        aapsLogger.warn(LTag.RLDIAG, "RLDIAG|SELFTEST|event=start")
        val checks = mutableListOf<DiagnosisCheck>()
        val repairs = mutableListOf<RepairAction>()

        val adapterOk = checkBluetoothAdapter().also { checks += it }.outcome == CheckOutcome.OK
        val linkOk = checkBluetoothLink(adapterOk).also { checks += it }.outcome == CheckOutcome.OK
        val ble113Ok = checkBle113(linkOk).also { checks += it }.outcome == CheckOutcome.OK
        val radioCheck = checkRadio(ble113Ok).also { checks += it }
        checks += checkConcurrency(radioCheck.outcome == CheckOutcome.OK)
        checks += checkWireFormat()
        checks += checkRecentHistory()

        // Most conservative first. The screen offers them in this order and stops as soon as the
        // radio answers again, so a working RileyLink is never reset just because it once was not.
        if (!linkOk && adapterOk) repairs += RepairAction.RECONNECT
        if (radioCheck.outcome == CheckOutcome.FAILED && ble113Ok) {
            repairs += RepairAction.RESET_RADIO_CONFIG
            repairs += RepairAction.RESET_CC1110
            repairs += RepairAction.RECONNECT
        }
        if (radioCheck.outcome == CheckOutcome.OK && rileyLinkServiceData.firmwareVersion == RileyLinkFirmwareVersionBase.UnknownVersion) {
            repairs += RepairAction.REREAD_VERSION
        }

        val report = DiagnosisReport(
            atMillis = System.currentTimeMillis(),
            checks = checks,
            suggestedRepairs = repairs.distinct(),
            headline = headlineFor(checks, adapterOk, linkOk, ble113Ok, radioCheck)
        )
        // Write the whole report, not just the verdict. The detail is the reason this feature is
        // worth more than a warning light, and a report that lives only on screen cannot be read
        // afterwards by anyone the user sends the log to.
        report.checks.forEach { check ->
            aapsLogger.warn(
                LTag.RLDIAG,
                "RLDIAG|SELFTEST_CHECK|name=${check.title}|outcome=${check.outcome}|summary=${check.summary}" +
                    check.detail.joinToString("") { "|detail=$it" }
            )
        }
        report.suggestedRepairs.forEach {
            aapsLogger.warn(LTag.RLDIAG, "RLDIAG|SELFTEST_REPAIR_OFFERED|action=${it.name}")
        }
        aapsLogger.warn(
            LTag.RLDIAG,
            "RLDIAG|SELFTEST|event=done|worst=${report.worst}|repairs=${report.suggestedRepairs.size}|headline=${report.headline}"
        )
        return report
    }

    // region checks

    private fun checkBluetoothAdapter(): DiagnosisCheck {
        val adapter = rileyLinkBLE.bluetoothAdapter
        return when {
            adapter == null      -> DiagnosisCheck(
                CHECK_ADAPTER, CheckOutcome.FAILED,
                "This phone reports no Bluetooth adapter.",
                listOf("Nothing the app can do about this. It is a phone or permission problem.")
            )

            !adapter.isEnabled   -> DiagnosisCheck(
                CHECK_ADAPTER, CheckOutcome.FAILED,
                "Bluetooth is switched off.",
                listOf("Switch Bluetooth on and run the check again.")
            )

            else                 -> DiagnosisCheck(CHECK_ADAPTER, CheckOutcome.OK, "Bluetooth is on.")
        }
    }

    private fun checkBluetoothLink(adapterOk: Boolean): DiagnosisCheck {
        if (!adapterOk) return DiagnosisCheck(CHECK_LINK, CheckOutcome.SKIPPED, "Not checked, Bluetooth is off.")
        val snapshot = diag.snapshot.value
        val name = rileyLinkServiceData.rileyLinkName ?: "-"
        val address = rileyLinkServiceData.rileyLinkAddress ?: "-"
        return if (rileyLinkBLE.isConnected) {
            DiagnosisCheck(
                CHECK_LINK, CheckOutcome.OK, "Connected to the RileyLink.",
                listOf("Device: $name", "Address: $address")
            )
        } else {
            DiagnosisCheck(
                CHECK_LINK, CheckOutcome.FAILED, "Not connected to the RileyLink.",
                listOf(
                    "Device: $name",
                    "Address: $address",
                    "Unexpected disconnects since the app started: ${snapshot.unexpectedDisconnects}",
                    "Is the RileyLink switched on and within range?"
                )
            )
        }
    }

    /** Reads a characteristic the BLE113 answers on its own, so it says nothing about the radio. */
    private fun checkBle113(linkOk: Boolean): DiagnosisCheck {
        if (!linkOk) return DiagnosisCheck(CHECK_BLE113, CheckOutcome.SKIPPED, "Not checked, no Bluetooth link.")
        val version = rfSpy.getVersion()
        return if (version.isNotBlank() && version != "(null)") {
            DiagnosisCheck(
                CHECK_BLE113, CheckOutcome.OK, "The Bluetooth chip is answering.",
                listOf("Firmware: $version")
            )
        } else {
            DiagnosisCheck(
                CHECK_BLE113, CheckOutcome.FAILED, "The Bluetooth chip did not answer.",
                listOf("The link is up but the RileyLink is not responding at all. Try reconnecting.")
            )
        }
    }

    /**
     * Sends one real command to the CC1110.
     *
     * This is the check that matters. The two chips fail independently, and in the field the
     * Bluetooth chip has answered perfectly for hours while the radio was off the air.
     */
    private fun checkRadio(ble113Ok: Boolean): DiagnosisCheck {
        if (!ble113Ok) return DiagnosisCheck(CHECK_RADIO, CheckOutcome.SKIPPED, "Not checked, the Bluetooth chip is not answering.")

        // The radio answers one command at a time. Asking it something while the app is mid
        // exchange with the pump interrupts that exchange, and the answer that comes back may
        // belong to either of us.
        val waitedForBus = waitForQuietRadio()
        if (rfSpy.radioBusy) {
            return DiagnosisCheck(
                CHECK_RADIO, CheckOutcome.SKIPPED,
                "Not checked, the app is talking to the pump right now.",
                listOf(
                    "Asking the radio anything now would interrupt that, and the answer could not be trusted.",
                    "Wait until the pump has finished and check again."
                )
            )
        }

        val raw = rfSpy.probeRadio()
        val text = raw?.let { String(it.map { b -> (b.toInt() and 0xFF).toChar() }.toCharArray()) } ?: ""
        return when {
            raw == null || raw.isEmpty()  -> DiagnosisCheck(
                CHECK_RADIO, CheckOutcome.FAILED, "The radio chip did not answer.",
                buildList {
                    add("The Bluetooth chip is fine, so this is the CC1110 itself.")
                    add("It is usually busy with a radio operation that has not finished.")
                    if (waitedForBus) add("The app had been talking to the pump just before this check, so try once more when everything is quiet.")
                    add("Restarting the radio chip is the next thing to try. If that does not help, remove power from the RileyLink for a few minutes.")
                }
            )

            text.contains(SUBG_RFSPY)     -> DiagnosisCheck(
                CHECK_RADIO, CheckOutcome.OK, "The radio chip is answering.",
                listOf("Firmware: ${text.substring(text.indexOf(SUBG_RFSPY)).trim()}")
            )

            else                          -> {
                val slip = VersionSlip.detect(raw)
                DiagnosisCheck(
                    CHECK_RADIO, CheckOutcome.WARNING, "The radio answered, but not with a version string.",
                    buildList {
                        add("Received: ${ByteUtil.shortHexString(raw)}")
                        if (slip != null) {
                            add("These bytes are a valid version shifted by ${slip.shiftBits} bit(s): ${slip.recovered}")
                            add("That points at the wired link between the two chips inside the RileyLink, not at the app.")
                        } else {
                            add("The app will keep using the stored version rather than guess.")
                        }
                    }
                )
            }
        }
    }

    /**
     * Fires two radio commands at the same moment, on purpose, to prove the radio lock holds.
     *
     * Measured on a bench pump with a laptop driving a RileyLink directly and no lock in the way:
     * two commands sent at the same moment lose one of them, five times out of five, at both the
     * worst and the best signal recorded. The radio RECEIVES the missing reply - its own
     * received-packet counter steps up for it - and the caller never sees it, because the
     * RileyLink has one command buffer and the second write displaces the first. From the outside
     * that is indistinguishable from bits sliding, and it is what the lock in
     * `RFSpy.writeToDataRaw` exists to stop.
     *
     * This check cannot show the failure, because the lock is in the way. A switch to bypass that
     * lock in an app that drives an insulin pump is not worth having, so there is none. What it can
     * show is that the lock did its job: one caller waited its turn, both callers got their own
     * answer, and nothing was dropped.
     *
     * Two commands the RileyLink answers by itself are used - GetVersion and GetStatistics - so
     * nothing is transmitted to the pump and the check works with no pump in range.
     */
    private fun checkConcurrency(radioOk: Boolean): DiagnosisCheck {
        if (!radioOk) return DiagnosisCheck(CHECK_CONCURRENCY, CheckOutcome.SKIPPED, "Not checked, the radio chip is not answering.")
        if (!rileyLinkServiceData.firmwareVersion.usesV2Protocol()) {
            return DiagnosisCheck(
                CHECK_CONCURRENCY, CheckOutcome.SKIPPED,
                "Not checked, this firmware has no statistics command to race against."
            )
        }

        // Quiet first. Contention from the app's own traffic would push the wait counter up for a
        // reason that has nothing to do with this check, and it would then pass without having
        // proved anything.
        waitForQuietRadio()
        if (rfSpy.radioBusy) {
            return DiagnosisCheck(
                CHECK_CONCURRENCY, CheckOutcome.SKIPPED,
                "Not checked, the app is talking to the pump right now.",
                listOf("This check needs the radio to itself, so that the only two commands racing are its own.")
            )
        }

        val before = diag.snapshot.value
        val stats = AtomicReference<RadioStats?>()
        val other = Thread({ stats.set(rfSpy.readRadioStats("concurrency check")) }, CONCURRENCY_THREAD)
        other.start()
        val versionRaw = rfSpy.probeRadio()
        other.join(CONCURRENCY_JOIN_MS)
        val after = diag.snapshot.value

        val versionText = versionRaw?.let { String(it.map { b -> (b.toInt() and 0xFF).toChar() }.toCharArray()) } ?: ""
        val versionOk = versionText.contains(SUBG_RFSPY)
        val statsOk = stats.get() != null
        val waited = after.radioTurnsWaited - before.radioTurnsWaited
        val missed = after.radioTurnsMissed - before.radioTurnsMissed

        val detail = listOf(
            "GetVersion answered: ${if (versionOk) "yes, with a version string" else "no"}",
            "GetStatistics answered: ${if (statsOk) "yes, counters read" else "no"}",
            "Commands that had to wait their turn: $waited",
            "Commands dropped because the radio never came free: $missed"
        )
        return DiagnosisCheck(CHECK_CONCURRENCY, concurrencyOutcome(versionOk, statsOk, waited, missed), concurrencySummary(versionOk, statsOk, waited, missed), detail)
    }

    /** What the app is currently deciding to send, and why. */
    private fun checkWireFormat(): DiagnosisCheck {
        val version = rileyLinkServiceData.firmwareVersion
        val cached = firmwareVersionStore.get(rileyLinkServiceData.rileyLinkAddress)
        val v2 = version.usesV2Protocol()
        val detail = listOf(
            "In use: ${version?.name ?: "none"}",
            "Stored for this RileyLink: ${cached?.name ?: "nothing yet"}",
            "Command format: ${if (v2) "version 2" else "version 1"}",
            "Encoding: ${rileyLinkUtil.encoding?.name ?: "not set"}"
        )
        return when {
            version == null                                          ->
                DiagnosisCheck(CHECK_FORMAT, CheckOutcome.SKIPPED, "The radio has not been asked yet.", detail)

            version == RileyLinkFirmwareVersionBase.UnknownVersion   ->
                DiagnosisCheck(
                    CHECK_FORMAT, CheckOutcome.WARNING,
                    "The firmware version is not known, so the safe version 2 format is in use.",
                    detail + "This is correct for every RileyLink made in the last several years."
                )

            !v2                                                      ->
                DiagnosisCheck(
                    CHECK_FORMAT, CheckOutcome.WARNING,
                    "The old version 1 command format is in use.",
                    detail + "Correct only for a genuinely old RileyLink. If this device is not one, report it."
                )

            else                                                     ->
                DiagnosisCheck(CHECK_FORMAT, CheckOutcome.OK, "Talking to the radio in its own format.", detail)
        }
    }

    /** Counters since the app started, so a fault that has already passed still shows up. */
    private fun checkRecentHistory(): DiagnosisCheck {
        val s = diag.snapshot.value
        val detail = listOf(
            "Unanswered commands in a row: ${s.silentStreak}",
            "Unexpected disconnects: ${s.unexpectedDisconnects}",
            "Operations refused because the link was down: ${s.writesWhileLinkDown}",
            "Bluetooth operations that timed out: ${s.gattWriteTimeouts}",
            "Version replies damaged by the chip to chip link: ${s.versionSlipsSeen}",
            "Most start-ups running at once: ${s.concurrentInitPeak}",
            "Commands that waited for the radio: ${s.radioTurnsWaited} (longest ${s.radioLongestWaitMs} ms)",
            "Commands dropped because the radio stayed busy: ${s.radioTurnsMissed}",
            "Replies found in the queue with no command waiting: ${s.radioJunkDrained}",
            "Commands answered with the reply of the one before: ${s.repliesCrossed}",
            "Replies that came back too late to be used: ${s.repliesLate}",
            "Replies the radio never sent: ${s.repliesLost}",
            s.lastReplyProblem?.let { "Last reply problem: $it" } ?: "No reply problem this session",
            "Radio chip restarts: ${s.radioResets}",
            s.radioStats?.let {
                "Radio counters: ${it.packetsSent} packets sent, ${it.packetsReceived} received"
            } ?: "Radio counters: not read yet"
        )
        // A crossed reply is in a class of its own. Every other counter here says something did
        // not happen; this one says something happened WRONG, and the app cannot tell from the
        // bytes alone. It is what made a version read come back as a wake up's reply and the
        // CC1110 show as "-", with every other number on this screen looking healthy.
        val crossed = s.repliesCrossed > 0
        val bad = s.versionSlipsSeen > 0 || s.concurrentInitPeak > 1 || s.gattWriteTimeouts > 0 ||
            s.radioTurnsMissed > 0 || s.radioResets > 0
        return DiagnosisCheck(
            CHECK_HISTORY,
            if (crossed) CheckOutcome.FAILED else if (bad) CheckOutcome.WARNING else CheckOutcome.OK,
            when {
                crossed -> "Commands were answered with the wrong reply ${s.repliesCrossed} times. " +
                    "Anything the app read during that time may be another command's answer."

                bad     -> "Something went wrong earlier in this session."
                else    -> "Nothing unusual since the app started."
            },
            detail
        )
    }

    /**
     * Waits a short while for the radio to stop being used by something else.
     *
     * @return true if it had to wait at all, so the caller can say so in its report.
     */
    private fun waitForQuietRadio(): Boolean {
        if (!rfSpy.radioBusy) return false
        val until = System.currentTimeMillis() + BUS_WAIT_MS
        while (rfSpy.radioBusy && System.currentTimeMillis() < until) Thread.sleep(BUS_POLL_MS)
        return true
    }

    /**
     * One sentence naming the first thing that is broken.
     *
     * Ordered by the signal path, because a fault upstream makes everything downstream unreadable -
     * telling someone their radio is silent when Bluetooth is switched off sends them the wrong way.
     */
    private fun headlineFor(
        checks: List<DiagnosisCheck>,
        adapterOk: Boolean,
        linkOk: Boolean,
        ble113Ok: Boolean,
        radio: DiagnosisCheck
    ): String = when {
        !adapterOk                                -> "Bluetooth is not available on this phone."
        !linkOk                                   -> "The phone is not connected to the RileyLink."
        !ble113Ok                                 -> "The RileyLink is connected but not answering at all."
        radio.outcome == CheckOutcome.FAILED      -> "The Bluetooth side is fine, but the radio chip is not answering. The pump cannot be reached until it does."
        radio.outcome == CheckOutcome.WARNING     -> "The radio answered, but not with something the app could read."
        // Any other failed check. Without this a failure below the radio fell through to
        // "Everything checked out", which is the one thing a diagnosis must never say while
        // something in it has failed.
        checks.any { it.outcome == CheckOutcome.FAILED }  -> "The radio is answering, but a check below it failed. The details say which."
        checks.any { it.outcome == CheckOutcome.WARNING } -> "Working, but something is worth a look."
        else                                      -> "Everything checked out. The RileyLink and the radio are both answering."
    }

    // endregion

    // region repair

    /** Carries out one repair. Blocks; do not call on the main thread. */
    fun repair(action: RepairAction): RepairResult {
        aapsLogger.warn(LTag.RLDIAG, "RLDIAG|REPAIR|action=${action.name}|event=start")
        val result = when (action) {
            RepairAction.REREAD_VERSION    -> {
                rfSpy.initializeRileyLink()
                val version = rileyLinkServiceData.firmwareVersion
                RepairResult(
                    action,
                    version != null && version != RileyLinkFirmwareVersionBase.UnknownVersion,
                    "Version now reads ${version?.name ?: "nothing"}."
                )
            }

            RepairAction.RESET_RADIO_CONFIG -> {
                waitForQuietRadio()
                rfSpy.resetRileyLinkConfiguration()
                val answering = rfSpy.probeRadio()?.isNotEmpty() == true
                RepairResult(action, answering, if (answering) "Radio settings reloaded and the radio is answering." else "Radio settings reloaded, but the radio still does not answer.")
            }

            RepairAction.RESET_CC1110       -> {
                waitForQuietRadio()
                rfSpy.resetRadioChip()
                // A rebooting chip usually never acknowledges, so its answer proves nothing. Give it
                // time to come back and ask it directly instead.
                Thread.sleep(RESET_SETTLE_MS)
                val answering = rfSpy.probeRadio()?.isNotEmpty() == true
                RepairResult(
                    action, answering,
                    if (answering) "The radio chip restarted and is answering again."
                    else "The radio chip did not come back. Remove power from the RileyLink for a few minutes."
                )
            }

            RepairAction.RECONNECT          -> {
                rileyLinkBLE.disconnect()
                Thread.sleep(RECONNECT_SETTLE_MS)
                rileyLinkUtil.sendBroadcastMessage(RileyLinkConst.Intents.RileyLinkNewAddressSet)
                RepairResult(action, true, "Reconnection started. Give it a few seconds, then run the check again.")
            }
        }
        aapsLogger.warn(LTag.RLDIAG, "RLDIAG|REPAIR|action=${action.name}|ok=${result.succeeded}|detail=${result.detail}")
        return result
    }

    // endregion

    companion object {

        private const val SUBG_RFSPY = "subg_rfspy"
        private const val RESET_SETTLE_MS = 3000L
        private const val BUS_WAIT_MS = 8000L
        private const val BUS_POLL_MS = 200L
        private const val RECONNECT_SETTLE_MS = 1000L
        private const val CONCURRENCY_JOIN_MS = 30_000L
        private const val CONCURRENCY_THREAD = "rl-concurrency-check"

        /**
         * The verdict on two commands that were fired at the same moment.
         *
         * Kept as a function of the four measured numbers, with no radio in it, because the
         * equivalent judgement written in a bench script got this wrong: it counted only replies
         * that came back to the WRONG caller, so a run where five commands vanished entirely
         * printed "nothing crossed" and read as clean. A dropped command is not clean. Both ways
         * of going wrong are named here, and both fail.
         */
        fun concurrencyOutcome(versionOk: Boolean, statsOk: Boolean, waited: Int, missed: Int): CheckOutcome = when {
            missed > 0            -> CheckOutcome.FAILED
            !versionOk || !statsOk -> CheckOutcome.FAILED
            waited < 1            -> CheckOutcome.WARNING
            else                  -> CheckOutcome.OK
        }

        /** One line for [concurrencyOutcome], saying what was actually measured. */
        fun concurrencySummary(versionOk: Boolean, statsOk: Boolean, waited: Int, missed: Int): String = when {
            missed > 0             -> "$missed command(s) were dropped because the radio never came free."
            !versionOk && !statsOk -> "Neither command was answered."
            !versionOk             -> "The version request was not answered while another command was in flight."
            !statsOk               -> "The statistics request was not answered while another command was in flight."
            waited < 1             -> "Both commands were answered, but they did not overlap, so nothing was proved."
            else                   -> "Both commands were answered and one waited its turn. The radio lock is holding."
        }

        private const val CHECK_ADAPTER = "Phone Bluetooth"
        private const val CHECK_LINK = "Link to the RileyLink"
        private const val CHECK_BLE113 = "Bluetooth chip (BLE113)"
        private const val CHECK_RADIO = "Radio chip (CC1110)"
        private const val CHECK_CONCURRENCY = "Two commands at once"
        private const val CHECK_FORMAT = "Command format"
        private const val CHECK_HISTORY = "Since the app started"
    }
}
