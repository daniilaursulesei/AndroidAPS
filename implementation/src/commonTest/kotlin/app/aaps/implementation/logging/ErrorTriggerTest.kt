package app.aaps.implementation.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule that decides whether a log line starts a report.
 *
 * Worth testing on its own because both ways of being wrong are expensive. Too loose and the phone
 * uploads a log every few minutes on a normal day, which costs data and hides the real fault in a
 * pile of noise. Too tight and the one night the radio breaks is the night nothing is captured.
 *
 * The lines below are copied from real `RLDIAG` output, not invented, so a change to the marker
 * format breaks these tests instead of silently switching the reporting off.
 */
class ErrorTriggerTest {

    @Test fun `bit slip is reported`() {
        assertEquals(
            ErrorKind.RADIO_BIT_SLIP,
            ErrorTrigger.match("WARN", "RLDIAG|VER_SLIP|detected=true|shiftBits=1|recovered=subg_rfspy 2.2")
        )
    }

    @Test fun `gatt timeout is reported`() {
        assertEquals(ErrorKind.LINK_TIMEOUT, ErrorTrigger.match("WARN", "RLDIAG|GATT_TIMEOUT|op=write|waited=2000|count=1"))
    }

    @Test fun `write refused while link down is reported`() {
        assertEquals(ErrorKind.LINK_TIMEOUT, ErrorTrigger.match("WARN", "RLDIAG|LINK_REFUSED|op=write|reason=linkDown|count=4"))
    }

    @Test fun `pump connector error is reported`() {
        assertEquals(ErrorKind.PUMP_UNREACHABLE, ErrorTrigger.match("INFO", "RileyLink state changed to PumpConnectorError"))
    }

    // A single miss is how a sleeping pump normally behaves. Reporting it would mean a report on
    // almost every idle hour.
    @Test fun `one silent command is not reported`() {
        assertNull(ErrorTrigger.match("WARN", "RLDIAG|RX|op=GetVersion|result=NONE|waited=2000|silentStreak=1"))
    }

    @Test fun `two silent commands are not reported`() {
        assertNull(ErrorTrigger.match("WARN", "RLDIAG|RX|op=GetVersion|result=NONE|waited=2000|silentStreak=2"))
    }

    @Test fun `three silent commands are reported`() {
        assertEquals(
            ErrorKind.RADIO_SILENT,
            ErrorTrigger.match("WARN", "RLDIAG|RX|op=GetVersion|result=NONE|waited=2000|silentStreak=3")
        )
    }

    @Test fun `a long silent streak is reported`() {
        assertEquals(
            ErrorKind.RADIO_SILENT,
            ErrorTrigger.match("WARN", "RLDIAG|RX|op=GetVersion|result=NONE|waited=2000|silentStreak=17")
        )
    }

    // The streak field is the last one on the line, so the digits run to the end of the string.
    // An off by one here would read an empty number and report nothing.
    @Test fun `streak at the end of the line is read`() {
        assertEquals(ErrorKind.RADIO_SILENT, ErrorTrigger.match("WARN", "RLDIAG|RX|silentStreak=5"))
    }

    @Test fun `a good reply is not reported`() {
        assertNull(ErrorTrigger.match("DEBUG", "RLDIAG|RX|op=GetVersion|result=OK|len=21|waited=140|hex=DD 73 75 62"))
    }

    @Test fun `an ordinary error is not reported by default`() {
        assertNull(ErrorTrigger.match("ERROR", "Nightscout upload failed"))
    }

    @Test fun `an ordinary error is reported when the wide rule is on`() {
        assertEquals(ErrorKind.APP_ERROR, ErrorTrigger.match("ERROR", "Nightscout upload failed", reportAllErrors = { true }))
    }

    @Test fun `a warning is not reported even when the wide rule is on`() {
        assertNull(ErrorTrigger.match("WARN", "something odd happened", reportAllErrors = { true }))
    }

    // Without this the reporter reports on itself and never stops.
    @Test fun `the reporter own lines never trigger`() {
        assertNull(ErrorTrigger.match("ERROR", "ERRLOG|upload failed for RLDIAG|VER_SLIP", reportAllErrors = { true }))
    }

    /**
     * The lines that actually reach the appender.
     *
     * `AAPSLoggerProduction` puts `[Class.method():12]: ` in front of every message before logback
     * sees it. Matching on the start of the line therefore never works - the self-report guard was
     * written that way first and would have let the reporter trigger on its own output, which is a
     * loop that only stops when the daily cap runs out.
     */
    @Test fun `the marker that the logger prepends does not hide a fault`() {
        assertEquals(
            ErrorKind.RADIO_BIT_SLIP,
            ErrorTrigger.match("WARN", "[RileyLinkDiag.record():131]: RLDIAG|VER_SLIP|detected=true|shiftBits=1")
        )
    }

    /**
     * The wide rule reads a preference, and this runs on the thread that wrote the log line - very
     * often the pump thread in the middle of a timed radio exchange. Asking on every line would put
     * a preference read in that path thousands of times a minute, so the question is asked last and
     * only about lines that could answer yes.
     */
    @Test fun `the wide rule is only asked about error level lines`() {
        var asked = 0
        val counting = { asked++; true }

        ErrorTrigger.match("DEBUG", "an ordinary debug line", counting)
        ErrorTrigger.match("INFO", "an ordinary info line", counting)
        ErrorTrigger.match("WARN", "an ordinary warning", counting)
        assertEquals(0, asked)

        ErrorTrigger.match("ERROR", "an ordinary error", counting)
        assertEquals(1, asked)
    }

    // A fault that the narrow rules already caught must not need the preference either.
    @Test fun `the wide rule is not asked when a narrow rule already matched`() {
        var asked = 0
        val counting = { asked++; true }

        assertEquals(
            ErrorKind.RADIO_BIT_SLIP,
            ErrorTrigger.match("ERROR", "RLDIAG|VER_SLIP|detected=true|shiftBits=1", counting)
        )
        assertEquals(0, asked)
    }

    @Test fun `the marker that the logger prepends does not hide the self report guard`() {
        assertNull(
            ErrorTrigger.match(
                "ERROR",
                "[ErrorLogReporter.report():118]: ERRLOG|could not report bit-slip",
                reportAllErrors = { true }
            )
        )
    }
}
