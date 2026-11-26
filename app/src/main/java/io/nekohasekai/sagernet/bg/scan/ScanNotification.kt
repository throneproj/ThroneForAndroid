package io.nekohasekai.sagernet.bg.scan

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreForeground
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One active run as the notification shows it. */
internal class ScanNotificationItem(val finder: Boolean, val label: String, val state: ScanLiveState)

/**
 * The progress notification of the running scans, shown through [CoreForeground] so :bg stays alive while any runs:
 * one run's stage and progress, or a line per run; Stop pauses them all.
 */
internal object ScanNotification {

    const val REASON = "scans"
    private const val ACTION_STOP = "io.nekohasekai.sagernet.SCAN_STOP"
    private const val REFRESH_MS = 1000L

    private val lock = Any()
    private var receiver: BroadcastReceiver? = null
    private var refresher: Job? = null

    @Volatile
    private var dirty = false

    /** Holds the service with [items]; [content] gives the current ones, posted at most once per [REFRESH_MS]. */
    fun start(scope: CoroutineScope, items: List<ScanNotificationItem>, content: () -> List<ScanNotificationItem>) {
        synchronized(lock) {
            if (refresher != null) return
            val stopReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch(Dispatchers.IO) { ScanEngine.pauseAll() }
                }
            }
            try {
                ContextCompat.registerReceiver(app, stopReceiver, IntentFilter(ACTION_STOP), ContextCompat.RECEIVER_NOT_EXPORTED)
                receiver = stopReceiver
            } catch (e: Exception) {
                Logs.w(e)
            }
            CoreForeground.acquire(REASON, build(items))
            dirty = false
            refresher = scope.launch {
                while (true) {
                    delay(REFRESH_MS)
                    if (!dirty) continue
                    dirty = false
                    val current = content()
                    if (current.isNotEmpty()) CoreForeground.post(REASON, build(current))
                }
            }
        }
    }

    fun changed() {
        dirty = true
    }

    fun finish() {
        synchronized(lock) {
            val job = refresher ?: return
            job.cancel()
            refresher = null
            receiver?.let { runCatching { app.unregisterReceiver(it) } }
            receiver = null
            CoreForeground.release(REASON)
        }
    }

    private fun kind(item: ScanNotificationItem): String =
        app.getString(if (item.finder) R.string.scan_notify_finder else R.string.scan_notify_resolver)

    private fun progress(item: ScanNotificationItem): String {
        val state = item.state
        val parts = ArrayList<String>()
        if (item.finder) {
            parts.add(app.getString(R.string.scan_progress_seconds, state.tested, state.total))
        } else if (state.total > 0) {
            val percent = (state.tested.coerceIn(0L, state.total) * 100 / state.total).toInt()
            parts.add(app.getString(R.string.scan_progress_count, state.tested.toString(), state.total.toString(), percent))
        }
        parts.add(app.resources.getQuantityString(R.plurals.scan_found, state.found, state.found))
        if (state.removed > 0) parts.add(app.resources.getQuantityString(R.plurals.scan_removed, state.removed, state.removed))
        return parts.joinToString(" · ")
    }

    private fun build(items: List<ScanNotificationItem>): Notification {
        val single = items.singleOrNull()
        val title: String
        val text: String
        if (single != null) {
            val kind = kind(single)
            title = if (single.label.isBlank()) kind else app.getString(R.string.scan_notify_title, kind, single.label)
            text = listOf(single.state.stage, progress(single)).filter { it.isNotEmpty() }.joinToString("\n")
        } else {
            title = app.resources.getQuantityString(R.plurals.scan_notify_many, items.size, items.size)
            text = items.joinToString("\n") { item ->
                val name = item.label.ifBlank { kind(item) }
                app.getString(R.string.scan_notify_line, name, progress(item))
            }
        }
        val stop = PendingIntent.getBroadcast(
            app, 0, Intent(ACTION_STOP).setPackage(app.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(app, CoreForeground.CHANNEL)
            .setSmallIcon(R.drawable.ic_throne_tile)
            .setContentTitle(title)
            .setContentText(text.substringBefore('\n'))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(SagerNet.configureIntent(app))
            .addAction(R.drawable.ic_notification_stop, app.getText(R.string.stop), stop)
        if (single != null) {
            val total = single.state.total.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val done = single.state.tested.coerceIn(0L, total.toLong()).toInt()
            builder.setProgress(total, done, total <= 0)
        }
        return builder.build()
    }
}
