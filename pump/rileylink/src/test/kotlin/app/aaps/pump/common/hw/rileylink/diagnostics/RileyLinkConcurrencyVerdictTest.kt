package app.aaps.pump.common.hw.rileylink.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The verdict on two radio commands fired at the same moment.
 *
 * This exists because the same judgement, written in a bench script, got it wrong. That version
 * counted only replies that came back to the WRONG caller, so a run in which five commands
 * vanished outright printed "nothing crossed" and read as clean. It was not clean: the RileyLink
 * had received all five replies - its own received-packet counter stepped up for each one - and
 * five callers were left waiting forever.
 *
 * A dropped command is a dropped insulin command. Both ways of going wrong are tested here.
 */
class RileyLinkConcurrencyVerdictTest {

    private fun outcome(versionOk: Boolean = true, statsOk: Boolean = true, waited: Int = 1, missed: Int = 0) =
        RileyLinkSelfTest.concurrencyOutcome(versionOk, statsOk, waited, missed)

    private fun summary(versionOk: Boolean = true, statsOk: Boolean = true, waited: Int = 1, missed: Int = 0) =
        RileyLinkSelfTest.concurrencySummary(versionOk, statsOk, waited, missed)

    @Test fun `both answered and one waited is the pass`() {
        assertThat(outcome()).isEqualTo(CheckOutcome.OK)
        assertThat(summary()).contains("lock is holding")
    }

    @Test fun `a dropped command fails, even with both replies correct`() {
        // The shape the bench script called clean.
        assertThat(outcome(missed = 1)).isEqualTo(CheckOutcome.FAILED)
        assertThat(summary(missed = 1)).contains("dropped")
    }

    @Test fun `a missing version reply fails`() {
        assertThat(outcome(versionOk = false)).isEqualTo(CheckOutcome.FAILED)
        assertThat(summary(versionOk = false)).contains("version request was not answered")
    }

    @Test fun `a missing statistics reply fails`() {
        assertThat(outcome(statsOk = false)).isEqualTo(CheckOutcome.FAILED)
        assertThat(summary(statsOk = false)).contains("statistics request was not answered")
    }

    @Test fun `neither answering is named as such`() {
        assertThat(outcome(versionOk = false, statsOk = false)).isEqualTo(CheckOutcome.FAILED)
        assertThat(summary(versionOk = false, statsOk = false)).contains("Neither command")
    }

    @Test fun `no overlap is inconclusive, not a pass`() {
        // Both replies correct but neither command ever waited, so the lock was never exercised.
        // Calling this OK would let a broken lock pass on a quiet radio.
        assertThat(outcome(waited = 0)).isEqualTo(CheckOutcome.WARNING)
        assertThat(summary(waited = 0)).contains("did not overlap")
    }

    @Test fun `a drop outranks a missing overlap`() {
        assertThat(outcome(waited = 0, missed = 2)).isEqualTo(CheckOutcome.FAILED)
    }

    @Test fun `several waits are still a pass`() {
        assertThat(outcome(waited = 5)).isEqualTo(CheckOutcome.OK)
    }
}
