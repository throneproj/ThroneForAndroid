package io.nekohasekai.sagernet.bg.test

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreForeground
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PlatformNotifications
import kotlinx.coroutines.delay

/** The progress notification of a running session ("<kind> · <scope>", n / N, Stop), shown through [CoreForeground]. */
internal class TestNotification(private val session: TestSession) {

    @Volatile
    private var dirty = true

    @Volatile
    var scope: String = session.spec.scopeLabel

    @Volatile
    private var speedLine = ""

    /** The geo asset download in flight, empty when none. */
    @Volatile
    private var assetLine = ""

    private var receiver: BroadcastReceiver? = null

    fun start() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = TestEngine.stop()
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
            this.receiver = receiver
        } catch (e: Exception) {
            Logs.w(e)
        }
        CoreForeground.acquire(REASON, build())
        runCatching { NotificationManagerCompat.from(app).cancel(WARNING_TAG, WARNING_ID) }
    }

    fun changed() {
        dirty = true
    }

    fun speed(snapshot: SpeedTestSnapshot) {
        speedLine = app.getString(
            R.string.test_engine_speed_line, snapshot.profileName,
            snapshot.downloadSpeed.ifEmpty { "-" }, snapshot.uploadSpeed.ifEmpty { "-" },
        )
        dirty = true
    }

    fun asset(line: String) {
        assetLine = line
        dirty = true
    }

    /** Posts the latest state at most once per [REFRESH_MS] until cancelled. */
    suspend fun refreshLoop() {
        while (true) {
            delay(REFRESH_MS)
            if (!dirty) continue
            dirty = false
            CoreForeground.post(REASON, build())
        }
    }

    /** Ends the progress; [assetProblems] (profiles left untested for a geo asset) stay behind as a warning. */
    fun finish(assetProblems: Collection<String>) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
        receiver = null
        CoreForeground.release(REASON)
        if (assetProblems.isNotEmpty()) warnAssets(assetProblems)
    }

    @SuppressLint("MissingPermission")
    private fun warnAssets(problems: Collection<String>) {
        val text = problems.take(MAX_WARNED_PROBLEMS).joinToString("\n\n")
        val notification = NotificationCompat.Builder(app, PlatformNotifications.CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(app.getString(R.string.xray_geo_test_warning_title))
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(SagerNet.configureIntent(app))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        runCatching { NotificationManagerCompat.from(app).notify(WARNING_TAG, WARNING_ID, notification) }
            .onFailure { Logs.w(it) }
    }

    private fun build(): Notification {
        val kind = app.getString(
            when (session.kind) {
                TestSpec.KIND_IP -> R.string.test_engine_kind_ip
                TestSpec.KIND_SPEED -> R.string.test_engine_kind_speed
                else -> R.string.test_engine_kind_url
            }
        )
        val scope = if (session.spec.testCurrent) app.getString(R.string.test_engine_scope_current) else scope
        val total = session.total
        val done = session.doneCount.coerceAtMost(total)
        val progress = when {
            total <= 0 -> ""
            session.kind == TestSpec.KIND_SPEED -> app.getString(R.string.test_engine_progress, done, total)
            else -> app.getString(R.string.test_engine_progress_counts, done, total, session.okCount, session.failedCount)
        }
        val text = listOf(assetLine, speedLine, progress).filter { it.isNotEmpty() }.joinToString("\n")
        val stop = PendingIntent.getBroadcast(
            app, 0, Intent(ACTION_STOP).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(app, CoreForeground.CHANNEL)
            .setSmallIcon(R.drawable.ic_throne_tile)
            .setContentTitle(if (scope.isBlank()) kind else app.getString(R.string.test_engine_title, kind, scope))
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(total, done, total <= 1)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(SagerNet.configureIntent(app))
            .addAction(R.drawable.ic_notification_stop, app.getText(R.string.stop), stop)
            .build()
    }

    companion object {
        const val REASON = "tests"
        private const val ACTION_STOP = "io.nekohasekai.sagernet.TEST_STOP"
        private const val REFRESH_MS = 1000L
        private const val WARNING_TAG = "tests"
        private const val WARNING_ID = 1
        private const val MAX_WARNED_PROBLEMS = 3
    }
}
