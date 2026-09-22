package app.aaps.pump.common.hw.rileylink.diagnostics

import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyScanResults
import app.aaps.pump.common.hw.rileylink.ble.data.FrequencyTrial
import app.aaps.pump.common.hw.rileylink.ble.data.RadioStats
import kotlin.math.roundToInt

/**
 * Says what the radio did, in words, instead of in bytes.
 *
 * WHY THIS FILE EXISTS. The diagnostics card used to print the reply as a hex dump. On a real
 * failure in the field that dump said `DD 00 53 EC F1 00 00 00 00 00 00 0C 0F ...`, which nobody
 * can read while standing in a car park. Every number in it had a meaning the screen already knew
 * and did not say: that the radio answered, that it had transmitted 3087 packets, and that it had
 * received none. The last of those three was the whole diagnosis, and it was invisible.
 *
 * Everything here is a pure function of values that are already in hand, so all of it is checked
 * offline in PlainWordsTest rather than argued about after a run.
 */

/** A status byte the radio can answer with, and what it means for the person reading it. */
private val STATUS_WORDS = mapOf(
    0x01 to "did it (older firmware)",
    0x11 to "refused the values it was given",
    0x22 to "does not know that command",
    0xAA to "listened and heard nothing",
    0xBB to "was interrupted before it could finish",
    0xCC to "answered with no data at all",
    0xDD to "did it"
)

/** The status, the signal reading and the packet counter, before any pump data. */
private const val REPLY_HEADER_SIZE = 3

/**
 * What one status byte means.
 *
 * An unknown byte is reported as a number rather than guessed at, because a wrong name here would
 * be worse than no name: it sends the reader looking for a fault that is not there.
 */
fun statusWords(status: Int): String =
    STATUS_WORDS[status and 0xFF] ?: "answered with 0x%02X, which this app does not know".format(status and 0xFF)

/**
 * The whole reply, in one sentence.
 *
 * @param commandName the command this answers, so a counters reply can be read as counters.
 * @param raw what came back, or null when nothing did.
 */
fun describeReply(commandName: String?, raw: ByteArray?): String {
    if (raw == null) return "nothing came back from the RileyLink"
    if (raw.isEmpty()) return "the RileyLink answered with an empty message"

    val status = raw[0].toInt() and 0xFF
    val words = "the radio " + statusWords(status)
    if (status != 0xDD && status != 0x01) return words

    // A counters reply is all zeros apart from the numbers, so it is the one reply where the
    // bytes really do have to be read out. It is also the only one that can say whether the
    // radio is transmitting into silence or not hearing at all.
    if (commandName?.contains("Statistics", ignoreCase = true) == true) {
        val stats = RadioStats.parse(raw)
        if (stats != null) {
            return "the radio has transmitted ${stats.packetsSent} packet(s) and received " +
                "${stats.packetsReceived} since it was switched on"
        }
    }

    if (raw.size <= REPLY_HEADER_SIZE) {
        return "$words, but there was no pump packet in the answer"
    }
    val payload = raw.size - REPLY_HEADER_SIZE
    return "$words, and brought back $payload byte(s) from the pump"
}

/**
 * Whether the pump serial the app is set to use is the one it is really calling.
 *
 * @property configured what the settings screen shows.
 * @property onWire the three bytes actually put in every packet, read back as digits.
 * @property agree true when the radio is calling the pump the user chose.
 */
data class SerialCheck(
    val configured: String?,
    val onWire: String?,
    val agree: Boolean
)

/**
 * Compare the serial in the settings with the serial in the packets.
 *
 * THIS IS HERE BECAUSE IT COST A WHOLE MORNING. The settings screen read one value and the radio
 * layer held another: the pump ID is loaded into RileyLinkServiceData once, when the service is
 * built, so changing the serial in the settings does not reach the radio until the app is
 * restarted. Every packet went to the old pump, which was in another town, and the screen
 * cheerfully showed the new one. Nothing anywhere said the two disagreed.
 *
 * @param configured the serial from the settings, six digits.
 * @param onWire the three address bytes from the packets. Each byte reads as two decimal digits,
 *   so 0x54 0x39 0x79 is pump 543979.
 */
fun checkSerial(configured: String?, onWire: ByteArray?): SerialCheck {
    val wireDigits = onWire
        ?.takeIf { it.size == 3 && it.any { b -> b.toInt() != 0 } }
        ?.joinToString("") { "%02X".format(it) }
    val wanted = configured?.takeIf { it.isNotBlank() }
    return SerialCheck(
        configured = wanted,
        onWire = wireDigits,
        // Unknown is not agreement. Reporting "they match" when one side is missing is the same
        // failure this function exists to catch.
        agree = wanted != null && wireDigits != null && wanted.equals(wireDigits, ignoreCase = true)
    )
}

/** One row of the frequency table: what was tried, and how it did. */
data class ScanRow(
    val frequencyMHz: Double,
    val answered: Int,
    val tries: Int,
    val averageRssi: Int,
    val best: Boolean
)

/**
 * The frequency scan as a table, strongest last.
 *
 * The scan already keeps every try. It was only ever reduced to one number - the frequency it
 * picked - so a scan where every frequency scored the same, which means the pump answered none of
 * them, looked exactly like a scan that found a clear winner.
 */
fun scanRows(results: FrequencyScanResults?): List<ScanRow> {
    val trials = results?.trials ?: return emptyList()
    return trials.map { trial ->
        ScanRow(
            frequencyMHz = trial.frequencyMHz,
            answered = trial.successes,
            tries = trial.tries,
            averageRssi = trial.averageRSSI.roundToInt(),
            best = results.bestFrequencyMHz != 0.0 && trial.frequencyMHz == results.bestFrequencyMHz
        )
    }.sortedBy { it.frequencyMHz }
}

/**
 * The one line verdict over the table.
 *
 * "Nothing answered anywhere" is a different fault from "it answered but weakly", and the table
 * alone makes the reader work that out from eight rows of numbers.
 */
fun describeScan(rows: List<ScanRow>): String {
    if (rows.isEmpty()) return "no frequency scan has run yet"
    val answered = rows.count { it.answered > 0 }
    if (answered == 0) {
        return "the pump answered on NONE of the ${rows.size} frequencies tried " +
            "(${rows.sumOf { it.tries }} attempts). Either it is out of range, switched off, " +
            "or the app is calling the wrong serial."
    }
    val best = rows.firstOrNull { it.best } ?: rows.maxByOrNull { it.averageRssi }
    return "the pump answered on $answered of ${rows.size} frequencies; " +
        "best %.2f MHz at %d dBm".format(best?.frequencyMHz ?: 0.0, best?.averageRssi ?: FrequencyTrial.NO_ANSWER_RSSI)
}

/**
 * Why the app has gone quiet, and when it will try again.
 *
 * A screen that simply stops for two and a half minutes reads as a crash. It was not a crash: each
 * retry was waking the pump, and a wake up is a three second burst followed by a twenty five
 * second listen. Saying so turns a frightening pause into a countdown.
 *
 * @param reason what the last attempt ran into.
 * @param nextTryInSeconds seconds until the next attempt, or null when nothing is scheduled.
 */
fun describeWait(reason: WaitReason, nextTryInSeconds: Int?): String {
    val what = when (reason) {
        WaitReason.NO_PUMP_ANSWER  -> "No answer from the pump"
        WaitReason.WAKING_PUMP     -> "Waking the pump (3 s burst, then listening for 25 s)"
        WaitReason.TUNING          -> "Searching every frequency for the pump"
        WaitReason.PUMP_BUSY       -> "The pump is busy and cannot answer yet"
        WaitReason.RADIO_BUSY      -> "Waiting for the radio, another command has it"
        WaitReason.LINK_DOWN       -> "No Bluetooth link to the RileyLink"
    }
    return when {
        nextTryInSeconds == null -> "$what."
        nextTryInSeconds <= 0    -> "$what. Trying again now."
        else                     -> "$what. Trying again in $nextTryInSeconds s."
    }
}

/** The things the driver waits for, named so the screen can say which one it is. */
enum class WaitReason { NO_PUMP_ANSWER, WAKING_PUMP, TUNING, PUMP_BUSY, RADIO_BUSY, LINK_DOWN }

/**
 * What became of one command's reply, as a sentence.
 *
 * The same fact goes to two readers who want opposite things. In the log it is
 * `step=CROSSED|owed=1`, which finds every one of them at once. On the screen, read while
 * standing next to a pump, it has to say what went wrong without being decoded first.
 *
 * @param commandName the command, as the driver names it, for example `GetVersion`.
 * @param step what happened to its reply.
 */
fun replyProblemWords(commandName: String, step: ReplyStep): String = when (step) {
    ReplyStep.CROSSED      ->
        "$commandName was given the answer to an earlier command. Anything the app read from it is wrong, " +
            "and every answer after it belongs to the command before it until this clears."

    ReplyStep.LATE_DRAINED ->
        "The answer to $commandName came back too late to be used. It was thrown away rather than " +
            "handed to the next command, so nothing is out of step. $commandName will be asked again."

    ReplyStep.LOST         ->
        "$commandName was never answered. The RileyLink holds one answer at a time, so two arriving " +
            "together destroy one. $commandName will be asked again."

    ReplyStep.IN_STEP      ->
        "$commandName was answered normally."
}

/**
 * The headline for the diagnostics screen when replies have gone to the wrong commands.
 *
 * Worth a line of its own because every other number on that screen can look healthy while this
 * is happening: each command does get an answer, it is simply the wrong one. In the log where the
 * CC1110 version read as "-", the link was up, the radio was transmitting, and nothing else said
 * anything was wrong.
 *
 * @param crossed how many commands were answered with an earlier command's reply.
 * @return the sentence, or null when nothing has gone wrong.
 */
fun crossedReplyHeadline(crossed: Int): String? = when {
    crossed <= 0 -> null
    crossed == 1 ->
        "One command was answered with an earlier command's reply. Readings taken around that " +
            "moment may belong to a different command."

    else         ->
        "$crossed commands were answered with an earlier command's reply. Readings taken around " +
            "those moments may belong to a different command."
}
