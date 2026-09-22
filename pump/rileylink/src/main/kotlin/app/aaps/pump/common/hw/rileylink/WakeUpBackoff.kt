package app.aaps.pump.common.hw.rileylink

/**
 * How hard to keep trying to wake a pump that is not answering.
 *
 * WHY THIS EXISTS, WITH THE NUMBERS. Waking a Medtronic is a burst of 200 repeats followed by a
 * listen. Measured over 51 wake ups on real hardware:
 *
 *   answered   14 of them, every one in 4.1 to 4.4 s, average 4.2
 *   timed out  37 of them, average 26.4 s, because the listen window is 25 s
 *
 * So a wake up that works is always quick, and a wake up that fails is six times more expensive
 * than any success has ever needed. Two things follow.
 *
 * FIRST, a failed wake must not be retried on every command. That is what the driver did after
 * the fix for "only treat the pump as awake when the wake up was answered": it cleared the awake
 * window on failure, so every following command paid another 26 s. On a pump that was genuinely
 * out of reach the radio ran a 29 s wake every 30 s for fourteen minutes - a duty cycle near
 * 100 % - which flattens the RileyLink battery and makes the app look frozen. The version before
 * that fix had the opposite fault: it marked a failed wake as a success and sent the next minute
 * of commands to a pump that could not hear them.
 *
 * The answer is neither. Back off between attempts, a little at first and more as the failures
 * pile up, so the first retry is quick and a pump that has really gone stops being hammered.
 *
 * SECOND, a retry does not need the full listen. Nothing in the measurements takes longer than
 * 4.4 s to answer, so [WAKE_UP_RETRY_LISTEN_MS] is more than twice the worst case seen and still
 * cuts a failed retry from 26 s to about 11.
 */

/** The listen window on the first wake up of a run. Left as it was; this is the careful one. */
const val WAKE_UP_LISTEN_MS = 25_000

/**
 * The listen window once a wake up has already failed.
 *
 * Over twice the slowest answer ever measured (4.4 s). A pump that is listening answers right
 * after the burst; one that does not answer in this window was not listening at all.
 */
const val WAKE_UP_RETRY_LISTEN_MS = 10_000

/** The longest gap between wake up attempts, however many have failed. */
const val WAKE_UP_BACKOFF_CAP_MS = 300_000L

/**
 * How long to leave the pump alone after a wake up that was not answered.
 *
 * The first gap is deliberately short - a pump can miss one burst for all sorts of reasons and
 * the next attempt should come soon. It grows quickly after that, because a pump that has missed
 * three bursts in a row is not there and the radio should stop shouting.
 *
 * @param consecutiveFailures how many wake ups in a row have gone unanswered, at least 1.
 */
fun wakeUpBackoffMs(consecutiveFailures: Int): Long = when {
    consecutiveFailures <= 0 -> 0L
    consecutiveFailures == 1 -> 30_000L
    consecutiveFailures == 2 -> 60_000L
    consecutiveFailures == 3 -> 120_000L
    else                     -> WAKE_UP_BACKOFF_CAP_MS
}

/**
 * How long to listen on the next wake up.
 *
 * @param consecutiveFailures how many wake ups in a row have gone unanswered. Zero means this is
 *   the first attempt of a run and gets the full window.
 */
fun wakeUpListenMs(consecutiveFailures: Int): Int =
    if (consecutiveFailures <= 0) WAKE_UP_LISTEN_MS else WAKE_UP_RETRY_LISTEN_MS

/**
 * Roughly how long a wake up will take, for the message on screen.
 *
 * The burst is about 3 s and then it listens, so this is what a person should expect the app to
 * be busy for.
 */
fun wakeUpSecondsFor(consecutiveFailures: Int): Int = 3 + wakeUpListenMs(consecutiveFailures) / 1000
