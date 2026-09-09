package com.example

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Last-resort crash catcher.
 *
 * The app must never die silently on a real device: any uncaught throwable —
 * including `Error`s (ServiceLoader / NoClassDefFound / VerifyError family),
 * which sail straight past `catch (e: Exception)` — is recorded here into an
 * in-memory ring (shown by the Diagnostics dialog on the sign-in screen) and
 * to an on-disk file, so even a crash from a previous launch stays visible.
 * The previous handler still runs, so the OS keeps its normal behaviour.
 */
object CrashLog {

    private const val MAX_ENTRIES = 30
    private const val MAX_FILE_BYTES = 512L * 1024L
    private const val MAX_DUMP_CHARS = 60_000

    private val lock = ReentrantLock()
    private val ring = ArrayDeque<String>(MAX_ENTRIES)

    @Volatile
    private var logFile: File? = null

    /** Install as early as possible from Application.onCreate(). */
    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, "diag").apply { mkdirs() }
            logFile = File(dir, "crash.log")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    record("UNCAUGHT on thread '${thread.name}'", throwable)
                } catch (_: Throwable) { /* last line of defense — never throw here */ }
                // Chain to the system handler (shows the standard crash behaviour).
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // If even this fails on some exotic device, do nothing — the app
            // must start regardless.
        }
    }

    /** Record from anywhere (usually guarded catches of Throwable). */
    fun record(tag: String, throwable: Throwable) {
        val rendered = try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            "[$stamp] $tag\n$sw"
        } catch (t: Throwable) {
            "[crash-recorder failure] $tag: $t"
        }
        try {
            lock.withLock {
                ring.addLast(rendered)
                while (ring.size > MAX_ENTRIES) ring.removeFirst()
            }
            val f = logFile
            if (f != null && f.length() < MAX_FILE_BYTES) {
                f.appendText(rendered + "\n")
            }
        } catch (_: Throwable) { /* diagnostics never crash the app */ }
    }

    /** Recent entries from this process — short enough for a dialog. */
    fun recentText(): String {
        val snapshot = try {
            lock.withLock { ring.toList() }
        } catch (_: Throwable) {
            emptyList()
        }
        return if (snapshot.isEmpty()) {
            "No problems recorded in this session."
        } else {
            snapshot.joinToString(separator = "\n\n----------------\n\n")
        }
    }

    /** Full dump: device info + on-disk history + this process. */
    fun fullText(): String = buildString {
        appendLine("DepotDownloader debug build")
        appendLine(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL} — " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        )
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine()
        try {
            val f = logFile
            if (f != null && f.exists() && f.length() > 0) {
                appendLine("====== persisted history (this + earlier launches) ======")
                val text = f.readText()
                if (text.length > MAX_DUMP_CHARS) {
                    appendLine("…(older entries trimmed)…")
                }
                append(text.takeLast(MAX_DUMP_CHARS))
                if (!text.endsWith("\n")) appendLine()
            } else {
                appendLine("(no persisted crash history)")
            }
        } catch (t: Throwable) {
            appendLine("(could not read persisted log: ${t.message})")
        }
        appendLine()
        appendLine("====== this process ======")
        append(recentText())
    }
}
