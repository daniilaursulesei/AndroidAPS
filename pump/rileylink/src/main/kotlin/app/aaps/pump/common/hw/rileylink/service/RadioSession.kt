package app.aaps.pump.common.hw.rileylink.service

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Keeps the RileyLink to one job at a time, and says which link a job belongs to.
 *
 * The RileyLink has one command channel and one reply queue. A caller writes a command and then
 * waits on that queue for the answer. If two jobs run at the same time, a caller that stops
 * waiting a moment too early leaves its answer behind, and the next caller takes it. From then on
 * every answer belongs to the command before it, and it stays that way until one of the two jobs
 * ends.
 *
 * That is what happens when a stale service task asks for a tune up at the same moment a new link
 * comes up: the tune up runs on the task thread while the start up sequence runs on the broadcast
 * thread. Every reply is then one command behind. `GetVersion` gets answered with the wake up's
 * reply, so the CC1110 version comes out empty and the screen shows "-".
 *
 * There are two halves to stopping that.
 *
 * [tryTake] and [release] are the gate. The start up sequence and every service task go through
 * them, so only one of the two can talk to the radio.
 *
 * [generation] is the other half. It goes up on every link change. A job that started on an older
 * link found out something about a link that is gone, so its result must not be published. A GATT
 * write that dies with the link takes 22 seconds to time out, which is long enough for the
 * RileyLink to be connected again by the time the job gives up.
 *
 * This class has no Android types in it on purpose, so the rules can be tested.
 */
@SingleIn(AppScope::class)
@Inject
class RadioSession() {

    /** Fair, so a job that has been waiting is served before one that just asked. */
    private val turn = ReentrantLock(true)

    private val linkGeneration = AtomicInteger(0)

    /** The link the radio is on now. A job saves this when it starts. */
    val generation: Int get() = linkGeneration.get()

    /** True while some job holds the radio. Reporting only. */
    val busy: Boolean get() = turn.isLocked

    /**
     * Counts one link change. Called on every connect and every disconnect, and only when the
     * state really changed, so a second disconnect of an already dead link does not cancel work
     * that is still valid.
     */
    fun linkChanged(): Int = linkGeneration.incrementAndGet()

    /** True when [startedOn] is still the link the radio is on. */
    fun stillOnSameLink(startedOn: Int): Boolean = startedOn == linkGeneration.get()

    /**
     * Waits up to [timeoutMs] for the radio, and says whether it got it.
     *
     * A caller that gets true must call [release] in a `finally` block. A caller that gets false
     * must not: the radio belongs to someone else, and releasing it would hand it to a third job.
     */
    fun tryTake(timeoutMs: Long): Boolean =
        try {
            turn.tryLock(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            // Waiting can be interrupted. Put the flag back for whoever set it and report a
            // missed turn, rather than letting a new exception type escape into the caller.
            Thread.currentThread().interrupt()
            false
        }

    /** Gives the radio back. Only the job that took it may call this. */
    fun release() {
        if (turn.isHeldByCurrentThread) turn.unlock()
    }

    companion object {

        /**
         * How long the start up sequence waits for a running service task.
         *
         * A tune up scans every frequency and can hold the radio for most of a minute, and it is
         * worth waiting for: running the start up sequence beside it is the fault this whole
         * class exists to stop.
         */
        const val START_UP_WAIT_MS = 90_000L

        /**
         * How long a service task waits for the start up sequence.
         *
         * The start up sequence is short - a few register writes and two version reads - so a
         * task that waits longer than this is waiting for something that is stuck, and running
         * without the radio to itself is no worse than what happened before this gate existed.
         */
        const val TASK_WAIT_MS = 30_000L
    }
}
