package io.nekohasekai.sagernet.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The automatic subscription update (GroupUpdater::CheckAutoUpdate on PeriodicRunner, mainwindow_setup.cpp): each
 * group follows its own [interval] and is due that long after its last successful update or automatic attempt.
 * WorkManager polls at the common period of those intervals and the worker queues the due groups in :bg. Anything
 * [interval] reads changing calls [schedule]; a poll that finds the plan changed reschedules itself.
 *
 * WorkManager honours the initial delay only on the first enqueue, so the polls keep their own grid while manual
 * refreshes move the due times off it: a poll takes every group due before the middle of the next period, which never
 * skips a cycle and starts a group at most half a period early.
 */
object SubscriptionScheduler {

    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "SubscriptionScheduler"

    /** The per-group updater of v11 and older, whose worker class is gone. */
    private const val LEGACY_WORK_NAME = "SubscriptionUpdater"

    private const val KEY_PERIOD = "period"

    /** autoAttempts (GroupUpdater.hpp): in :bg memory, so a new :bg process retries a failing group once. */
    private val attempts = ConcurrentHashMap<Long, Long>()

    enum class IntervalSource { SERVER, GROUP, GLOBAL }

    class AutoUpdate(val seconds: Long, val source: IntervalSource)

    /**
     * ResolveAutoUpdate (GroupUpdater.cpp:590-603), null = not updated automatically: the server's
     * profile-update-interval when followed and sent, else the group's interval, else sub_auto_update. A group's own
     * interval or respect-server choice stands while sub_auto_update is off; inherited ones follow it.
     */
    fun resolve(
        group: ProxyGroup,
        global: Int = DataStore.subAutoUpdate,
        respectServer: Boolean = DataStore.subRespectServerInterval,
    ): AutoUpdate? {
        if (group.url.isEmpty() || group.archive || group.skipAutoUpdate) return null
        val options = group.subOptions
        val globalOn = global >= MIN_INTERVAL_MINUTES
        val serverHours = group.subInfo.serverInterval
        if ((options.respectServerInterval ?: (globalOn && respectServer)) && serverHours > 0) {
            return AutoUpdate(serverHours * 3600L, IntervalSource.SERVER)
        }
        if (options.updateInterval > 0) {
            return AutoUpdate(maxOf(options.updateInterval, MIN_INTERVAL_MINUTES) * 60L, IntervalSource.GROUP)
        }
        return if (globalOn) AutoUpdate(global * 60L, IntervalSource.GLOBAL) else null
    }

    /** [resolve] in seconds, 0 = not updated automatically. */
    fun interval(
        group: ProxyGroup,
        global: Int = DataStore.subAutoUpdate,
        respectServer: Boolean = DataStore.subRespectServerInterval,
    ): Long = resolve(group, global, respectServer)?.seconds ?: 0L

    /** Whether [group]'s automatic update follows the server's interval when it sends one. */
    fun followsServerInterval(group: ProxyGroup): Boolean = group.subOptions.respectServerInterval
        ?: (DataStore.subAutoUpdate >= MIN_INTERVAL_MINUTES && DataStore.subRespectServerInterval)

    /** autoUpdateDue (GroupUpdater.cpp:519-523): a failed attempt waits the interval too. */
    private fun due(group: ProxyGroup, interval: Long): Long {
        val last = maxOf(group.subLastUpdate, attempts[group.id] ?: 0L)
        return if (last <= 0) 0 else last + interval
    }

    private class Plan(val period: Long, val initialDelay: Long)

    /** The poll period is the intervals' greatest common divisor (at least [MIN_INTERVAL_MINUTES]); null = no poll. */
    private fun plan(now: Long): Plan? {
        val global = DataStore.subAutoUpdate
        val respectServer = DataStore.subRespectServerInterval
        var period = 0L
        var next = Long.MAX_VALUE
        for (group in GroupRepo.all()) {
            val interval = interval(group, global, respectServer)
            if (interval <= 0) continue
            period = gcd(period, interval)
            next = minOf(next, due(group, interval))
        }
        if (period <= 0) return null
        period = period.coerceAtLeast(MIN_INTERVAL_MINUTES * 60L)
        return Plan(period, (next - now).coerceIn(0L, period))
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    /**
     * Plans the periodic poll from the groups and the settings, or cancels it while nothing is updated automatically.
     * UPDATE (app start included) registers the job again whatever happened to it, and keeps the current period's start.
     */
    fun schedule() {
        runOnDefaultDispatcher {
            try {
                val workManager = RemoteWorkManager.getInstance(app)
                workManager.cancelUniqueWork(LEGACY_WORK_NAME).await()
                val plan = plan(nowSeconds())
                if (plan == null) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, plan.period, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(plan.initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process, where the queue lives.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putLong(KEY_PERIOD, plan.period)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            } catch (e: Throwable) {
                Logs.w("SubscriptionScheduler: scheduling failed", e)
            }
        }
    }

    /**
     * CheckAutoUpdate (GroupUpdater.cpp:525-535) for a poll every [period] seconds: queues every group due before the
     * middle of the next period in tab order; returns how many.
     */
    private fun checkAutoUpdate(period: Long): Int {
        val now = nowSeconds()
        val horizon = now + period / 2
        val global = DataStore.subAutoUpdate
        val respectServer = DataStore.subRespectServerInterval
        var queued = 0
        for (group in GroupRepo.all()) {
            val interval = interval(group, global, respectServer)
            if (interval <= 0 || due(group, interval) > horizon) continue
            attempts[group.id] = now
            Logs.i(app.getString(R.string.subs_auto_update_running, group.displayName()))
            // Failures go to the log only (notifyErrors is off for a background refresh).
            SubscriptionQueue.refreshGroup(group.id, showDiff = false, foreground = false)
            queued++
        }
        return queued
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            val period = inputData.getLong(KEY_PERIOD, 0L)
            if (checkAutoUpdate(period.takeIf { it > 0 } ?: (MIN_INTERVAL_MINUTES * 60L)) > 0) SubscriptionQueue.awaitIdle()
            // The plan moved without a reschedule (a restored backup, a server interval, a removed group): follow it.
            if (plan(nowSeconds())?.period != period) schedule()
            return Result.success()
        }
    }
}
