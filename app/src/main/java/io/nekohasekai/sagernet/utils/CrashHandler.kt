package io.nekohasekai.sagernet.utils

import android.content.Intent
import android.os.Process
import android.util.Log
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ui.BlankActivity
import kotlin.system.exitProcess

/** Logs an uncaught exception and restarts into [BlankActivity], which shares a redacted log export. */
object CrashHandler : Thread.UncaughtExceptionHandler {

    /** Set by [BlankActivity] in the reborn process: a crash there must not start another report, or it loops. */
    @Volatile
    var reporting = false

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        // note: libc / go panic is in android log

        try {
            Log.e(thread.toString(), throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        try {
            Logs.e(thread.toString())
            Logs.e(throwable.stackTraceToString())
        } catch (e: Exception) {
        }

        if (reporting) {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
        ProcessPhoenix.triggerRebirth(app, Intent(app, BlankActivity::class.java).apply {
            putExtra(BlankActivity.EXTRA_SEND_LOG, true)
        })
    }
}
