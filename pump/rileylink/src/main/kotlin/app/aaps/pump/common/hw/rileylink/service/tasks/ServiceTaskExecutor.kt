package app.aaps.pump.common.hw.rileylink.service.tasks

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.SingleIn

@SingleIn(AppScope::class)
@Inject
class ServiceTaskExecutor() : ThreadPoolExecutor(1, 1, 10000, TimeUnit.MILLISECONDS, taskQueue) {

    @Inject lateinit var aapsLogger: AAPSLogger

    companion object {

        private val taskQueue = LinkedBlockingQueue<Runnable>()
    }

    /** The task on the thread right now, so a caller can tell a running one from a queued one. */
    @Volatile private var runningTask: Class<out ServiceTask>? = null

    fun startTask(task: ServiceTask): ServiceTask {
        execute(task) // task will be run on async thread from pool.
        return task
    }

    /**
     * True when a task of this kind is on the thread or waiting in the queue.
     *
     * Only one task runs at a time here, so starting a second of the same kind does not make
     * anything happen sooner. It makes it happen twice, one after the other.
     */
    fun isQueuedOrRunning(taskClass: Class<out ServiceTask>): Boolean =
        runningTask == taskClass || queue.any { it.javaClass == taskClass }

    /**
     * Starts [task] unless one of its kind is already queued or running.
     *
     * For work that is triggered by a repeating failure. A tune up asked for on every timeout
     * queued one run per timeout, and because the queue is served one at a time they ran back to
     * back, each one holding the radio for a minute or more.
     *
     * @return the task, or null when one of its kind was already on its way.
     */
    fun startTaskOnce(task: ServiceTask): ServiceTask? {
        if (isQueuedOrRunning(task.javaClass)) {
            aapsLogger.debug(LTag.PUMPBTCOMM, "Not starting ${task.javaClass.simpleName}, one is already on its way")
            return null
        }
        return startTask(task)
    }

    override fun beforeExecute(t: Thread, r: Runnable) {
        // This is run on either caller UI thread or Service UI thread.
        val task = r as ServiceTask
        runningTask = task.javaClass
        aapsLogger.debug(LTag.PUMPBTCOMM, "About to run task ${task.javaClass.simpleName}")
        task.preOp()
    }

    override fun afterExecute(r: Runnable, t: Throwable?) {
        // This is run on either caller UI thread or Service UI thread.
        val task = r as ServiceTask
        runningTask = null
        task.postOp()
        aapsLogger.debug(LTag.PUMPBTCOMM, "Finishing task ${task.javaClass.simpleName}")
    }
}