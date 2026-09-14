package app.aaps.pump.common.hw.rileylink.diagnostics

/** How a single check came out. */
enum class CheckOutcome { OK, WARNING, FAILED, SKIPPED }

/**
 * One step of the diagnosis.
 *
 * @property title what was checked, in the reader's terms.
 * @property outcome the verdict.
 * @property summary one line saying what was found.
 * @property detail the evidence - measured values, bytes, timings. This is the part that makes the
 *   report worth more than a red light, so a check that found something must fill it in.
 */
data class DiagnosisCheck(
    val title: String,
    val outcome: CheckOutcome,
    val summary: String,
    val detail: List<String> = emptyList()
)

/** A repair the app can attempt, in the order it should be tried. */
enum class RepairAction(val title: String, val what: String) {

    REREAD_VERSION(
        "Read the firmware version again",
        "Asks the radio what it is, and stores the answer. Safe: no radio traffic to the pump."
    ),
    RESET_RADIO_CONFIG(
        "Reload the radio settings",
        "Writes the frequency and encoding registers again. Safe: no radio traffic to the pump."
    ),
    RESET_CC1110(
        "Restart the radio chip",
        "Tells the CC1110 to reboot. The radio is unavailable for a few seconds and any command in flight is lost."
    ),
    RECONNECT(
        "Reconnect Bluetooth",
        "Drops the Bluetooth link and lets it come back. Pump communication pauses for a few seconds."
    )
}

/**
 * The result of one run of the self test.
 *
 * @property checks every step, in the order run.
 * @property suggestedRepairs what to try, most conservative first. Empty when nothing is wrong.
 * @property headline one sentence a person can act on.
 */
data class DiagnosisReport(
    val atMillis: Long,
    val checks: List<DiagnosisCheck>,
    val suggestedRepairs: List<RepairAction>,
    val headline: String
) {

    val worst: CheckOutcome
        get() = when {
            checks.any { it.outcome == CheckOutcome.FAILED }  -> CheckOutcome.FAILED
            checks.any { it.outcome == CheckOutcome.WARNING } -> CheckOutcome.WARNING
            checks.all { it.outcome == CheckOutcome.SKIPPED } -> CheckOutcome.SKIPPED
            else                                             -> CheckOutcome.OK
        }

    /** The whole report as plain text, for the copy button and for pasting into a report. */
    fun toPlainText(): String = buildString {
        appendLine("RileyLink diagnosis")
        appendLine(headline)
        appendLine()
        checks.forEach { check ->
            appendLine("[${check.outcome}] ${check.title} - ${check.summary}")
            check.detail.forEach { appendLine("    $it") }
        }
        if (suggestedRepairs.isNotEmpty()) {
            appendLine()
            appendLine("Suggested repairs, in order:")
            suggestedRepairs.forEach { appendLine("  - ${it.title}") }
        }
    }
}

/** What a repair attempt did. */
data class RepairResult(
    val action: RepairAction,
    val succeeded: Boolean,
    val detail: String
)
