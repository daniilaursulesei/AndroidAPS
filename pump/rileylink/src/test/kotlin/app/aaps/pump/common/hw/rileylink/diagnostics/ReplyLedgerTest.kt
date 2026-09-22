package app.aaps.pump.common.hw.rileylink.diagnostics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReplyLedgerTest {

    @Test fun `a fresh ledger is in step`() {
        val ledger = ReplyLedger()
        assertTrue(ledger.inStep)
        assertEquals(0, ledger.owedNow)
    }

    @Test fun `a command sent puts the radio in debt`() {
        val ledger = ReplyLedger()
        assertEquals(1, ledger.sent())
        assertFalse(ledger.inStep)
    }

    @Test fun `a reply taken pays the debt back`() {
        val ledger = ReplyLedger()
        ledger.sent()
        ledger.taken()
        assertTrue(ledger.inStep)
    }

    @Test fun `the debt never goes below zero`() {
        val ledger = ReplyLedger()
        ledger.taken()
        ledger.taken()
        assertEquals(0, ledger.owedNow)
        // And a command sent after that still counts, rather than paying off a negative debt.
        ledger.sent()
        assertEquals(1, ledger.owedNow)
    }

    @Test fun `two commands owe two replies`() {
        val ledger = ReplyLedger()
        ledger.sent()
        ledger.sent()
        assertEquals(2, ledger.owedNow)
        ledger.taken()
        assertFalse(ledger.inStep)
        ledger.taken()
        assertTrue(ledger.inStep)
    }

    @Test fun `a reply written off does not stay on the books`() {
        val ledger = ReplyLedger()
        ledger.sent()
        ledger.writtenOff()
        assertTrue(ledger.inStep, "a reply that never came must not make every later command look crossed")
    }

    @Test fun `a link change clears everything owed`() {
        val ledger = ReplyLedger()
        ledger.sent()
        ledger.sent()
        ledger.linkReset()
        assertTrue(ledger.inStep, "the replies in flight died with the link")
    }

    @Test fun `the ordinary round is in step`() {
        assertEquals(ReplyStep.IN_STEP, replyStep(crossed = false, answered = true, settledLate = false))
    }

    @Test fun `a reply that was already there when the write finished is crossed`() {
        assertEquals(ReplyStep.CROSSED, replyStep(crossed = true, answered = true, settledLate = false))
    }

    @Test fun `crossed beats every other way of describing the round`() {
        assertEquals(ReplyStep.CROSSED, replyStep(crossed = true, answered = true, settledLate = true))
    }

    @Test fun `a crossing with nothing handed over is not crossed`() {
        // Nothing was given to the caller, so nothing was given to it wrongly. What matters then
        // is whether the reply turned up at all.
        assertEquals(ReplyStep.LATE_DRAINED, replyStep(crossed = true, answered = false, settledLate = true))
        assertEquals(ReplyStep.LOST, replyStep(crossed = true, answered = false, settledLate = false))
    }

    @Test fun `a reply caught during the settle is late, not lost`() {
        assertEquals(ReplyStep.LATE_DRAINED, replyStep(crossed = false, answered = false, settledLate = true))
    }

    @Test fun `nothing at all is lost`() {
        assertEquals(ReplyStep.LOST, replyStep(crossed = false, answered = false, settledLate = false))
    }
}
