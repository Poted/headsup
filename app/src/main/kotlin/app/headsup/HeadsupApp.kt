package app.headsup

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.Application
import android.os.Build
import app.headsup.export.ExportManager
import java.io.File
import java.util.Date

class HeadsupApp : Application() {
    val exportManager: ExportManager by lazy { ExportManager(this) }

    private val crashFile get() = File(filesDir, "last_crash.txt")

    override fun onCreate() {
        super.onCreate()
        // The raw reader's own process (see RawService) reports nothing: when it runs out of
        // memory the app carries on and says so.
        if (Build.VERSION.SDK_INT >= 28 && Application.getProcessName() != packageName) return
        // Keep the stack trace of a crash so it can be shown (and sent) on the next start.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                crashFile.writeText("${Date()} · thread ${thread.name} · ${Build.MODEL} Android ${Build.VERSION.RELEASE}\n${error.stackTraceToString()}")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Describes how the app ended last time if it did not exit normally: the saved stack trace for
     * a Kotlin/Java crash, otherwise the system's reason (native crash, out of memory, ...).
     * Each report is returned once.
     */
    fun takeCrashReport(): String? {
        val trace = crashFile.takeIf { it.exists() }?.let { f -> f.readText().also { f.delete() } }
        var exit: String? = null
        if (Build.VERSION.SDK_INT >= 30) {
            val prefs = getSharedPreferences("diagnostics", MODE_PRIVATE)
            val info = getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 5)
                .firstOrNull { it.processName == packageName }
            if (info != null && info.timestamp > prefs.getLong("last_exit_seen", 0L)) {
                prefs.edit().putLong("last_exit_seen", info.timestamp).apply()
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "crash"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                    ApplicationExitInfo.REASON_ANR -> "not responding (ANR)"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "killed for low memory"
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "killed for excessive resource use"
                    ApplicationExitInfo.REASON_SIGNALED -> "killed by signal ${info.status}"
                    else -> null
                }
                if (reason != null) exit = "Last exit: $reason at ${Date(info.timestamp)}. ${info.description.orEmpty()}"
            }
        }
        if (trace == null && exit == null) return null
        return listOfNotNull("The app stopped unexpectedly last time. Please send this text:", exit, trace).joinToString("\n")
    }
}
