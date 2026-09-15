package app.aaps.implementation.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import java.util.concurrent.atomic.AtomicReference

/**
 * Watches every log line and says when one of them means the pump link has broken.
 *
 * It is attached in `app/src/main/assets/logback.xml` next to the file and logcat appenders, so it
 * sees exactly what the log file sees: every module, every thread, nothing to register and nothing
 * to remember to call.
 *
 * ## Why a global instead of injection
 *
 * Logback builds its appenders itself, by reflection, from the XML. It knows nothing about Metro
 * and cannot be given a constructor argument, so the instance it creates cannot hold an injected
 * reporter. The listener therefore lives in the companion object and
 * [app.aaps.implementation.maintenance.ErrorLogReporter] installs itself into it at startup.
 *
 * This is the one shape that works, but it is worth being clear about the cost: there is one
 * listener for the whole process and the last caller of [setListener] wins. That is fine for a
 * single application-scoped reporter and would not be fine for anything else, which is why nothing
 * else should use it.
 *
 * ## What it must not do
 *
 * [append] runs on whichever thread wrote the log line - very often the pump communication thread,
 * in the middle of a timed radio exchange. It does string matching and nothing else. Reading files,
 * zipping and uploading all happen on the reporter's own coroutine, after this method has returned.
 */
class ErrorLogAppender : AppenderBase<ILoggingEvent>() {

    override fun append(eventObject: ILoggingEvent?) {
        val event = eventObject ?: return
        val handler = listener.get() ?: return
        val message = event.formattedMessage ?: return
        val kind = ErrorTrigger.match(event.level?.toString() ?: "", message, reportAllErrors.get() ?: neverReport) ?: return
        // Must not block: see the class note.
        handler(kind, message)
    }

    companion object {

        private val listener = AtomicReference<((ErrorKind, String) -> Unit)?>(null)
        private val reportAllErrors = AtomicReference<(() -> Boolean)?>(null)
        private val neverReport: () -> Boolean = { false }

        /**
         * Installs the one handler that is told about faults, or clears it with null.
         *
         * Lines logged before this is called are not reported. That is on purpose: the reporter
         * cannot upload anything before the app graph is built anyway, and startup is noisy.
         */
        fun setListener(handler: ((ErrorKind, String) -> Unit)?) {
            listener.set(handler)
        }

        /**
         * Installs the question "does any error level line count?". See [ErrorTrigger.match].
         *
         * A function rather than a flag so the answer comes from the live preference. Set it to
         * null to go back to the narrow rule.
         */
        fun setReportAllErrors(enabled: (() -> Boolean)?) {
            reportAllErrors.set(enabled)
        }
    }
}
