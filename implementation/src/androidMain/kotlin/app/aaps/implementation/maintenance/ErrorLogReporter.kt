package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.L
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.logging.LoggerUtils
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.implementation.R
import app.aaps.implementation.logging.ErrorKind
import app.aaps.implementation.logging.ErrorLogAppender
import app.aaps.implementation.logging.ErrorTrigger
import app.aaps.implementation.maintenance.cloud.CloudConstants
import app.aaps.implementation.maintenance.cloud.CloudStorageManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Captures the log around a pump link fault, uploads it, and says so on screen.
 *
 * ## Why this exists
 *
 * The logs were never the problem. Logback already keeps months of them on the phone. The problem
 * was finding out: a RileyLink fault that started at half past six in the morning was noticed hours
 * later, and by then the only way to look at it was to sit down at a computer, pull the whole log
 * off the phone and search it. This class turns that into a notification and a file already in the
 * cloud, with the minutes leading up to the fault in it.
 *
 * ## Why the cloud and not e-mail
 *
 * E-mail in AndroidAPS is an `ACTION_SEND` intent: it opens the mail app and waits for a person to
 * press send. That is no use for a fault at half past six in the morning. The cloud provider
 * already stored a refresh token, so
 * [app.aaps.core.interfaces.maintenance.CloudStorageProvider.getValidAccessToken] renews itself and
 * the upload needs nobody awake.
 *
 * ## What is uploaded
 *
 * A zip of about fifty kilobytes holding two files: a short header saying what was detected and
 * which build saw it, and the last [TAIL_BYTES] of the live log. The tail is the point - it is the
 * chain leading up to the fault, not just the line that failed.
 */
@SingleIn(AppScope::class)
@Inject
class ErrorLogReporter(
    private val aapsLogger: AAPSLogger,
    private val l: L,
    private val rh: ResourceHelper,
    private val config: Config,
    private val preferences: Preferences,
    private val loggerUtils: LoggerUtils,
    private val cloudStorageManager: CloudStorageManager,
    private val notificationManager: NotificationManager,
    private val dateUtil: DateUtil
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val limit = ErrorReportLimit()

    /**
     * True while a report is being built and uploaded.
     *
     * An upload can take half a minute on a bad connection, and a broken link keeps logging the
     * whole time. Without this every one of those lines would start its own upload.
     */
    private val busy = AtomicBoolean(false)

    /** Starts watching. Call once, from application startup. */
    fun start() {
        ErrorLogAppender.setReportAllErrors { preferences.get(BooleanKey.MaintenanceUploadAllErrors) }
        ErrorLogAppender.setListener { kind, line -> onFault(kind, line) }
        aapsLogger.info(LTag.CORE, ErrorTrigger.OWN_PREFIX + "watching for pump link faults")
        warnAboutSwitchedOffLogs()
    }

    /**
     * Says so when the feature is on but cannot possibly see anything.
     *
     * `AAPSLoggerProduction` drops `info` and `warn` for a log tag the user has switched off, so
     * the line never reaches logback and never reaches the appender. With `PUMP` or `RLDIAG` off,
     * this whole feature is switched on, uses no battery, and reports nothing - the worst way for
     * it to behave, because the user would only find out on the morning they needed it.
     */
    private fun warnAboutSwitchedOffLogs() {
        if (!preferences.get(BooleanKey.MaintenanceUploadLogsOnError)) return
        val off = REQUIRED_LOG_TAGS.filterNot { l.findByName(it.tag).enabled }
        if (off.isEmpty()) return
        val names = off.joinToString(", ") { it.tag }
        aapsLogger.warn(LTag.CORE, ErrorTrigger.OWN_PREFIX + "cannot detect faults, these log tags are off: $names")
        notify(rh.gs(R.string.error_log_tags_off, names))
    }

    /** Stops watching. Only needed by tests and by a shutdown path. */
    fun stop() {
        ErrorLogAppender.setListener(null)
        ErrorLogAppender.setReportAllErrors(null)
    }

    /**
     * Called from the logging thread for every line that matches a fault rule.
     *
     * Everything here has to be cheap and must not block: the caller may be halfway through a timed
     * radio exchange. The decision is made here and the work is handed to [scope].
     */
    private fun onFault(kind: ErrorKind, line: String) {
        if (!preferences.get(BooleanKey.MaintenanceUploadLogsOnError)) return
        if (!busy.compareAndSet(false, true)) return
        val now = dateUtil.now()
        if (!limit.allow(kind, now)) {
            busy.set(false)
            return
        }
        scope.launch {
            try {
                report(kind, line, now)
            } catch (e: Exception) {
                // Never let a reporting failure escape. This runs because something already went
                // wrong; it must not be able to make things worse.
                aapsLogger.error(LTag.CORE, ErrorTrigger.OWN_PREFIX + "could not report ${kind.id}", e)
                notify(rh.gs(R.string.error_log_upload_failed, nameOf(kind)))
            } finally {
                busy.set(false)
            }
        }
    }

    private suspend fun report(kind: ErrorKind, line: String, now: Long) {
        // Asked before anything is built. With no cloud signed in there is nowhere to put the zip,
        // and reading half a megabyte of log to throw it away would be work for nothing. Nothing is
        // lost by skipping it: those lines are still in the log file on the phone, and the
        // notification says which moment to look at.
        val provider = cloudStorageManager.getActiveProvider()
        if (provider == null || !provider.hasValidCredentials()) {
            aapsLogger.warn(LTag.CORE, ErrorTrigger.OWN_PREFIX + "no cloud storage signed in, not uploading")
            notify(rh.gs(R.string.error_log_no_cloud, nameOf(kind)))
            return
        }

        val name = "AndroidAPS_ERROR_${kind.id}_$now.zip"
        val zip = buildZip(kind, line, now)
        aapsLogger.info(LTag.CORE, ErrorTrigger.OWN_PREFIX + "captured ${kind.id}, ${zip.size} bytes")

        val id = provider.uploadFileToPath(name, zip, MIME_ZIP, CloudConstants.CLOUD_PATH_ERROR_LOGS)
        if (id == null) {
            aapsLogger.warn(LTag.CORE, ErrorTrigger.OWN_PREFIX + "upload of $name failed")
            notify(rh.gs(R.string.error_log_upload_failed, nameOf(kind)))
        } else {
            aapsLogger.info(LTag.CORE, ErrorTrigger.OWN_PREFIX + "uploaded $name")
            notify(rh.gs(R.string.error_log_uploaded, nameOf(kind), name))
        }
    }

    private fun notify(text: String) {
        notificationManager.post(id = NotificationId.ERROR_LOG_UPLOADED, text = text)
    }

    /** The translated name of a fault, for the notification. */
    private fun nameOf(kind: ErrorKind): String = rh.gs(
        when (kind) {
            ErrorKind.RADIO_BIT_SLIP    -> R.string.error_kind_bit_slip
            ErrorKind.RADIO_SILENT      -> R.string.error_kind_radio_silent
            ErrorKind.LINK_TIMEOUT      -> R.string.error_kind_link_timeout
            ErrorKind.PUMP_UNREACHABLE  -> R.string.error_kind_pump_unreachable
            ErrorKind.APP_ERROR         -> R.string.error_kind_app_error
        }
    )

    private fun buildZip(kind: ErrorKind, line: String, now: Long): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("report.txt"))
            zip.write(header(kind, line, now).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("AndroidAPS-tail.log"))
            zip.write(readTail().toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /**
     * The short summary that sits next to the log tail.
     *
     * Plain English and never translated: this file is read by whoever is debugging the radio, not
     * by the person carrying the phone.
     */
    private fun header(kind: ErrorKind, line: String, now: Long): String = buildString {
        appendLine("AndroidAPS automatic error report")
        appendLine("=================================")
        appendLine("detected : ${kind.id}")
        appendLine("at       : ${dateUtil.dateAndTimeAndSecondsString(now)} ($now)")
        appendLine("trigger  : $line")
        appendLine("reports today: ${limit.countToday(now)}")
        appendLine()
        appendLine("version  : ${config.VERSION}")
        appendLine("build    : ${config.BUILD_VERSION}")
        appendLine("remote   : ${config.REMOTE}")
        appendLine("flavor   : ${config.FLAVOR}${config.BUILD_TYPE}")
        appendLine()
        appendLine("The attached log is the last $TAIL_BYTES bytes of the live log file at the moment")
        appendLine("the fault was seen, so it holds what happened before the fault as well as after.")
    }

    /**
     * Reads the end of the live log file.
     *
     * Logback flushes every line as it is written, so the tail on disk is current - there is no
     * need to keep a copy in memory. Just after a log rollover the file is short and the tail is
     * short with it; that is accepted rather than stitching the previous archive back on.
     */
    private fun readTail(): String {
        val directory = runCatching { loggerUtils.logDirectory }.getOrNull()
            ?: return "log directory unknown"
        val file = File(directory, LOG_FILE_NAME)
        if (!file.isFile) return "no log file at ${file.absolutePath}"
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                val from = (length - TAIL_BYTES).coerceAtLeast(0)
                raf.seek(from)
                val bytes = ByteArray((length - from).toInt())
                raf.readFully(bytes)
                val text = String(bytes, Charsets.UTF_8)
                // Seeking lands in the middle of a line. Drop that fragment so the file starts on a
                // whole line and stays readable.
                if (from > 0) text.substringAfter('\n', text) else text
            }
        }.getOrElse { "could not read ${file.absolutePath}: ${it.message}" }
    }

    companion object {

        /**
         * How much of the log to take.
         *
         * Half a megabyte is roughly four thousand lines, which on a RileyLink covers several
         * minutes of radio traffic - long enough to hold the working exchanges before the fault.
         * It compresses to about fifty kilobytes.
         */
        const val TAIL_BYTES = 512L * 1024

        /** Matches the `file` appender in `app/src/main/assets/logback.xml`. */
        const val LOG_FILE_NAME = "AndroidAPS.log"

        private const val MIME_ZIP = "application/zip"

        /**
         * The log tags the fault rules read.
         *
         * `RLDIAG` carries the `RLDIAG|` markers and `PUMP` carries the RileyLink state changes. If
         * either is switched off, the matching rules can never fire.
         */
        private val REQUIRED_LOG_TAGS = listOf(LTag.RLDIAG, LTag.PUMP)
    }
}
