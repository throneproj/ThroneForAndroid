package io.nekohasekai.sagernet.bg

import android.app.Application
import go.Seq
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.test.TestEngine
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.throneproj.mobile.Instance
import io.throneproj.mobile.LogSink
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SetupOptions
import moe.matsuri.nb4a.NativeInterface
import moe.matsuri.nb4a.utils.CoreLog
import java.io.File

// Loaded only in the :bg process: everything here pulls the ThroneCore AAR (libthrone.so) in.
object CoreRuntime {

    private const val LOG_QUEUE_LINES = 1024

    val platform: NativeInterface by lazy { NativeInterface() }

    /** The started main instance and the profile it runs, from box start to close (test-current). */
    class RunningCore(val instance: Instance, val profileId: Long)

    @Volatile
    var running: RunningCore? = null
        private set

    @Synchronized
    fun attachRunning(instance: Instance, profileId: Long) {
        running = RunningCore(instance, profileId)
    }

    fun detachRunning(instance: Instance) {
        val detached = synchronized(this) {
            (running?.instance === instance).also { if (it) running = null }
        }
        if (detached) TestEngine.onRunningClosed()
    }

    /**
     * The sing-box level lines must reach to be written: the box level-filters only its own console and hands every
     * line to the sink, so the sink applies the running config's `log.level` (unset = trace, like sing-box).
     */
    @Volatile
    private var logLevel = Mobile.LogLevelTrace

    fun setup(app: Application) {
        Seq.setContext(app)
        CoreLog.newSession()
        logLevel = runCatching { levelOf(DataStore.logLevel) }.getOrDefault(Mobile.LogLevelWarn)
        Mobile.setup(SetupOptions().apply {
            basePath = app.filesDir.absolutePath
            workingPath = File(app.filesDir, "core").absolutePath
            tempPath = app.cacheDir.absolutePath
            logMaxLines = LOG_QUEUE_LINES
            debug = BuildConfig.DEBUG
        })
        CoreLog.write("[Info] core: sing-box ${Mobile.version()}, xray ${Mobile.xrayVersion()}, ${Mobile.goVersion()}")
        Mobile.setLogSink(CoreLogSink)
    }

    /** Follows the `log.level` of [coreConfig], the config the main instance is about to run. */
    fun applyLogLevel(coreConfig: String) {
        logLevel = levelOf(JsonInput.parseObject(coreConfig).obj("log").string("level"))
    }

    private fun levelOf(level: String): Int = when (level.trim().lowercase()) {
        "panic" -> Mobile.LogLevelPanic
        "fatal" -> Mobile.LogLevelFatal
        "error" -> Mobile.LogLevelError
        "warn", "warning" -> Mobile.LogLevelWarn
        "info" -> Mobile.LogLevelInfo
        "debug" -> Mobile.LogLevelDebug
        else -> Mobile.LogLevelTrace
    }

    private object CoreLogSink : LogSink {
        override fun write(level: Int, message: String) {
            if (level > logLevel) return
            CoreLog.write("[${levelName(level)}] $message")
        }

        private fun levelName(level: Int): String = when (level) {
            Mobile.LogLevelPanic -> "Panic"
            Mobile.LogLevelFatal -> "Fatal"
            Mobile.LogLevelError -> "Error"
            Mobile.LogLevelWarn -> "Warning"
            Mobile.LogLevelInfo -> "Info"
            Mobile.LogLevelDebug -> "Debug"
            Mobile.LogLevelTrace -> "Trace"
            else -> "Info"
        }
    }
}
