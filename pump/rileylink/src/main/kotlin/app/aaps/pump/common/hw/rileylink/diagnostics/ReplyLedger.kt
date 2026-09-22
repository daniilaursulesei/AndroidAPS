package app.aaps.pump.common.hw.rileylink.diagnostics

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.atomic.AtomicInteger

/**
 * What became of one command's reply.
 *
 * These are the words the bench test uses, so a log from a phone and a run from the bench can be
 * read side by side.
 */
enum class ReplyStep {

    /** The reply came back to the command that asked for it. The ordinary case. */
    IN_STEP,

    /**
     * The reply was late, and was taken off the queue before the next command went out.
     *
     * The command that asked for it still reports nothing, so a read is lost, but the stream stays
     * in step and nothing is answered with the wrong bytes.
     */
    LATE_DRAINED,

    /**
     * A command was answered with the reply of the command before it.
     *
     * This is the fault. Once it happens every later reply belongs to the command before it, and
     * it does not correct itself: on the bench the only thing that ended it was one of the two
     * jobs using the radio stopping.
     */
    CROSSED,

    /**
     * Nothing came back at all, and nothing was still owed afterwards.
     *
     * The RileyLink has one reply buffer. Two replies landing close together destroy one of them
     * inside the chip, and no amount of waiting brings it back.
     */
    LOST
}

/**
 * Counts the replies the RileyLink still owes, so the app can tell when it is out of step.
 *
 * The rule is simple and exact: one command sent, one reply owed. A reply taken off the queue -
 * by the caller that asked for it, by the drain before the next command, or by the settle period
 * after a timeout - pays one back. While the count is zero the stream is in step.
 *
 * That count is what turns a whole family of confusing symptoms into one named thing. A command
 * that starts while a reply is still owed will be answered with that reply, which is what made
 * `GetVersion` come back as `BB` in the field log and the CC1110 version read as "-".
 *
 * There are no Android types here on purpose, so the rules can be tested.
 */
@SingleIn(AppScope::class)
@Inject
class ReplyLedger() {

    private val owed = AtomicInteger(0)

    /** How many replies the radio still owes. Zero means the stream is in step. */
    val owedNow: Int get() = owed.get()

    /** True while nothing is owed. */
    val inStep: Boolean get() = owed.get() == 0

    /** A command went out. Its reply is now owed. */
    fun sent(): Int = owed.incrementAndGet()

    /** A reply was taken off the queue, whoever took it. Never goes below zero. */
    fun taken(): Int = owed.updateAndGet { if (it > 0) it - 1 else 0 }

    /**
     * Gives up on a reply that never came.
     *
     * Without this the debt would stay on the books for good, and every command after it would
     * look as though the radio still owed something.
     */
    fun writtenOff(): Int = taken()

    /**
     * The link went down or came up. Nothing is owed any more.
     *
     * The replies that were in flight died with the link, and carrying the debt across would make
     * every command on the new link look crossed.
     */
    fun linkReset() = owed.set(0)
}

/**
 * Names what happened to one command's reply.
 *
 * @param crossed a reply reached the queue before this command's write had finished, so it was
 *   asked for by an earlier command
 * @param answered the caller was handed a reply
 * @param settledLate a reply turned up during the settle period after the caller gave up
 */
fun replyStep(crossed: Boolean, answered: Boolean, settledLate: Boolean): ReplyStep = when {
    // Being handed somebody else's reply beats every other description of the round. The bytes
    // are wrong, and whatever the caller does with them is wrong too.
    crossed && answered -> ReplyStep.CROSSED
    answered            -> ReplyStep.IN_STEP
    settledLate         -> ReplyStep.LATE_DRAINED
    else                -> ReplyStep.LOST
}
