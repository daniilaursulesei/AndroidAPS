package app.aaps.implementation.logging

/**
 * The kinds of fault that are worth capturing a log for.
 *
 * The id goes into the name of the uploaded file and into the rate limit, so two different faults
 * in the same minute both get reported while the same fault repeating does not.
 */
enum class ErrorKind(val id: String) {

    /** A `GetVersion` reply came back shifted. The suspected SPI fault between BLE113 and CC1110. */
    RADIO_BIT_SLIP("bit-slip"),

    /** The radio stopped answering for several commands in a row. */
    RADIO_SILENT("radio-silent"),

    /** A Bluetooth operation timed out, or was refused because the link was down. */
    LINK_TIMEOUT("link-timeout"),

    /** The driver gave up on the pump and went into the error state. */
    PUMP_UNREACHABLE("pump-unreachable"),

    /** Any other line logged at error level. Only used when the wide rule is switched on. */
    APP_ERROR("app-error")
}

/**
 * Decides whether one log line means something went wrong.
 *
 * This is the whole rule, kept as pure text matching with no Android and no Bluetooth types, so it
 * can be tested with plain strings. The appender that calls it runs on the logging thread, so this
 * has to stay cheap: a few `contains` calls and nothing else.
 *
 * ## Why marker text and not call sites
 *
 * Every line in AndroidAPS goes through slf4j, so one rule placed here sees the output of every
 * module. The alternative - calling a reporter from each place that can fail - would mean touching
 * the pump drivers, the queue and the Bluetooth layer, and would still miss whatever was not
 * thought of. Matching the `RLDIAG|` markers that [app.aaps.core.interfaces.logging.LTag.RLDIAG]
 * already writes costs nothing and needs no new dependency between modules.
 */
object ErrorTrigger {

    /**
     * How many silent commands in a row count as a fault.
     *
     * One or two are normal: the pump sleeps between commands and a single miss is expected. Three
     * in a row is the point where the driver itself starts to treat the radio as gone.
     */
    const val SILENT_STREAK_LIMIT = 3

    /**
     * Marker the reporter writes its own lines with.
     *
     * Lines holding it are never a trigger. Without this the reporter would report on itself: it
     * logs while it uploads, the appender would see that line, and the capture would start again.
     * That loop is easy to create and hard to stop once the phone is in a pocket.
     *
     * Searched for anywhere in the line, not only at the start, because
     * `AAPSLoggerProduction` puts its own `[Class.method():12]: ` marker in front of every message
     * before logback sees it. A check on the start of the line would never match.
     */
    const val OWN_PREFIX = "ERRLOG|"

    /**
     * @param level the slf4j level name, upper case, for example `ERROR`
     * @param message the formatted message, without the timestamp and thread. It still carries the
     *   `[Class.method():12]: ` marker that `AAPSLoggerProduction` puts in front of every
     *   message, so all matching here is on the whole line rather than on its start.
     * @param reportAllErrors asked whether any line at error level counts. Off by default because
     *   AndroidAPS logs errors that are not faults - a Nightscout upload that failed once, for
     *   instance - and reporting those would bury the ones that matter.
     *
     *   It is a function and not a plain flag so the caller can read the live preference instead of
     *   a copy taken at startup, which would mean the setting only took effect after a restart. It
     *   is asked last and only about lines at error level, so the cost lands on the rare line
     *   rather than on every line in the log.
     * @return the kind of fault, or null when the line is not a fault
     */
    fun match(level: String, message: String, reportAllErrors: () -> Boolean = { false }): ErrorKind? {
        if (message.contains(OWN_PREFIX)) return null

        if (message.contains("RLDIAG|VER_SLIP")) return ErrorKind.RADIO_BIT_SLIP
        if (message.contains("RLDIAG|GATT_TIMEOUT") || message.contains("RLDIAG|LINK_REFUSED")) return ErrorKind.LINK_TIMEOUT
        if (message.contains("RLDIAG|RX") && silentStreakOf(message) >= SILENT_STREAK_LIMIT) return ErrorKind.RADIO_SILENT
        if (message.contains("PumpConnectorError")) return ErrorKind.PUMP_UNREACHABLE
        if (level == "ERROR" && reportAllErrors()) return ErrorKind.APP_ERROR

        return null
    }

    /**
     * Reads `silentStreak=<number>` out of an `RLDIAG|RX` marker.
     *
     * @return the number, or 0 when the field is missing or not a number
     */
    private fun silentStreakOf(message: String): Int {
        val at = message.indexOf(SILENT_STREAK_FIELD)
        if (at < 0) return 0
        val from = at + SILENT_STREAK_FIELD.length
        var to = from
        while (to < message.length && message[to].isDigit()) to++
        return message.substring(from, to).toIntOrNull() ?: 0
    }

    private const val SILENT_STREAK_FIELD = "silentStreak="
}
