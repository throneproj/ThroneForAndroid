package io.nekohasekai.sagernet.scan

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
import io.nekohasekai.sagernet.ktx.HttpGetOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.httpGet
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.route.UrlText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Scanner::IpListUpdater (IpListUpdater.h/.cpp): refreshes the user lists with a URL source, one at a time per process,
 * and their automatic update. The automatic update follows SubscriptionScheduler: WorkManager polls at the common period
 * of the lists' intervals and the worker queues the due lists in :bg. Anything a list's auto_update, update_interval,
 * source or role reads changing (an edit, a new list, a restore) calls [schedule].
 */
object IpListUpdater {

    const val MIN_INTERVAL_MINUTES = 30

    private const val MAX_BYTES = 64L * 1024 * 1024
    private const val WORK_NAME = "IpListUpdater"
    private const val KEY_PERIOD = "period"

    /** ImportOutcome: [error] is empty on success. */
    class ImportOutcome(
        @JvmField val error: String = "",
        @JvmField val entries: List<IpListEntry> = emptyList(),
        @JvmField val rejected: Int = 0,
        @JvmField val samples: List<String> = emptyList(),
    )

    /** listUpdated: called on the refreshing thread, [error] empty on success. */
    fun interface Listener {
        fun onListUpdated(listId: Long, error: String)
    }

    private val lock = Any()
    private val queue = ArrayDeque<Long>()
    private val finishers = HashMap<Long, MutableList<(String) -> Unit>>()
    private val active = HashSet<Long>()
    private var running = false
    private val idle = MutableStateFlow(true)
    private val listeners = CopyOnWriteArraySet<Listener>()

    /** autoAttempts: in this process's memory, so a new process retries a failing list once. */
    private val attempts = ConcurrentHashMap<Long, Long>()

    private fun str(id: Int, vararg args: Any): String = if (args.isEmpty()) app.getString(id) else app.getString(id, *args)

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private fun isAsciiSpace(b: Byte): Boolean = b.toInt() == 32 || b.toInt() in 9..13

    // QByteArray::trimmed on the first 4 KiB, after a UTF-8 BOM.
    private fun isWebPage(bytes: ByteArray): Boolean {
        var start = 0
        var end = minOf(bytes.size, 4096)
        if (end >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) start = 3
        while (start < end && isAsciiSpace(bytes[start])) start++
        while (end > start && isAsciiSpace(bytes[end - 1])) end--
        val head = String(bytes, start, end - start, Charsets.ISO_8859_1).lowercase(Locale.ROOT)
        return head.startsWith("<!doctype html") || head.startsWith("<html")
    }

    /** FetchEntries: blocking network, off the main thread. */
    @JvmStatic
    fun fetchEntries(kind: IpList.SourceKind, source: String): ImportOutcome {
        val url = source.trim()
        if (kind == IpList.SourceKind.Manual || url.isEmpty()) return ImportOutcome(error = str(R.string.iplist_error_no_source))
        val parsed = UrlText.parse(url)
        if (parsed == null || parsed.host.isEmpty() || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return ImportOutcome(error = str(R.string.iplist_error_not_http))
        }
        val response = httpGet(RuleSets.mirrorLink(url, DataStore.rulesetMirror), HttpGetOptions(maxBytes = MAX_BYTES))
        if (response.error.isNotEmpty()) return ImportOutcome(error = response.error)
        return parseImportBytes(response.data)
    }

    /** ParseImportBytes: a file's or a download's text; a web page or text without addresses is an error. */
    @JvmStatic
    fun parseImportBytes(bytes: ByteArray): ImportOutcome {
        if (isWebPage(bytes)) return ImportOutcome(error = str(R.string.iplist_error_web_page))
        val parsed = IpListParse.parseIpListText(bytes)
        val error = if (parsed.entries.isEmpty()) str(R.string.iplist_error_no_addresses) else ""
        return ImportOutcome(error, parsed.entries, parsed.rejected, parsed.samples)
    }

    private fun refreshable(list: IpList): Boolean = list.isRemote && list.role == IpList.Role.User

    private fun refreshOne(listId: Long): String {
        val header = IpListsRepo.getIpListHeader(listId) ?: return str(R.string.iplist_error_gone)
        if (!refreshable(header)) return str(R.string.iplist_error_not_remote)

        val name = header.name
        val kind = header.sourceKind
        val source = header.source
        Logs.i(">>>>>>>> " + str(R.string.iplist_log_updating, name))
        val outcome = fetchEntries(kind, source)

        // Re-read: the list may have been edited, or detached to be deleted, while it downloaded.
        val current = IpListsRepo.getIpListHeader(listId) ?: return str(R.string.iplist_error_gone)
        if (current.sourceKind != kind || current.source != source) {
            Logs.w("<<<<<<<< " + str(R.string.iplist_log_failed, name, str(R.string.iplist_log_source_changed)))
            return str(R.string.iplist_error_source_changed)
        }
        if (!refreshable(current)) return str(R.string.iplist_error_gone)
        var error = outcome.error
        if (error.isEmpty() && !IpListsRepo.replaceEntries(listId, outcome.entries)) error = str(R.string.iplist_error_not_saved)
        if (error.isNotEmpty()) {
            IpListsRepo.saveUpdateResult(listId, current.lastUpdate, error)
            Logs.w("<<<<<<<< " + str(R.string.iplist_log_failed, current.name, error))
            return error
        }
        IpListsRepo.saveUpdateResult(listId, nowSeconds(), "")
        Logs.i(
            "<<<<<<<< " + app.resources.getQuantityString(
                R.plurals.iplist_log_updated, outcome.entries.size, current.name, outcome.entries.size,
            )
        )
        return ""
    }

    private fun drain() {
        while (true) {
            val listId = synchronized(lock) {
                if (queue.isEmpty()) {
                    running = false
                    idle.value = true
                    return
                }
                queue.removeFirst()
            }
            val error = try {
                refreshOne(listId)
            } catch (e: Exception) {
                val message = e.readableMessage
                Logs.w(str(R.string.iplist_error_update_failed_reason, message), e)
                message
            }
            val done = synchronized(lock) {
                active.remove(listId)
                finishers.remove(listId)
            }
            for (listener in listeners) listener.onListUpdated(listId, error)
            done?.forEach { finish -> runCatching { finish(error) } }
        }
    }

    /**
     * Refresh: queues list [listId] unless it is queued or refreshing already; [finish] runs on the refreshing thread
     * once it is done, with "" on success. Refreshes run in this process.
     */
    @JvmStatic
    @JvmOverloads
    fun refresh(listId: Long, finish: ((String) -> Unit)? = null) {
        synchronized(lock) {
            if (finish != null) finishers.getOrPut(listId) { ArrayList() }.add(finish)
            if (!active.add(listId)) return
            queue.addLast(listId)
            if (running) return
            running = true
            idle.value = false
        }
        runOnIoDispatcher { drain() }
    }

    /** RefreshAll: every refreshable list, or only those with auto_update. Blocking (reads the lists). */
    @JvmStatic
    fun refreshAll(onlyAuto: Boolean) {
        for (list in IpListsRepo.getAllIpLists()) {
            if (!refreshable(list) || (onlyAuto && !list.autoUpdate)) continue
            refresh(list.id)
        }
    }

    /** IsRefreshing: queued or refreshing in this process. */
    @JvmStatic
    fun isRefreshing(listId: Long): Boolean = synchronized(lock) { listId in active }

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners.add(listener)
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    /** Suspends until this process has nothing queued or refreshing. */
    suspend fun awaitIdle() {
        idle.first { it }
    }

    private fun interval(list: IpList): Long = maxOf(MIN_INTERVAL_MINUTES, list.updateInterval) * 60L

    // A failed attempt waits the interval too.
    private fun due(list: IpList): Long {
        val last = maxOf(list.lastUpdate, attempts[list.id] ?: 0L)
        return if (last <= 0) 0 else last + interval(list)
    }

    private fun autoLists(): List<IpList> = IpListsRepo.getAllIpLists().filter { it.autoUpdate && refreshable(it) }

    private class Plan(val period: Long, val initialDelay: Long)

    /** The poll period is the intervals' greatest common divisor (at least [MIN_INTERVAL_MINUTES]); null = no poll. */
    private fun plan(now: Long): Plan? {
        var period = 0L
        var next = Long.MAX_VALUE
        for (list in autoLists()) {
            period = gcd(period, interval(list))
            next = minOf(next, due(list))
        }
        if (period <= 0) return null
        period = period.coerceAtLeast(MIN_INTERVAL_MINUTES * 60L)
        return Plan(period, (next - now).coerceIn(0L, period))
    }

    private tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

    /**
     * CheckAutoUpdate for a poll every [period] seconds: queues every list due before the middle of the next period;
     * returns how many.
     */
    private fun checkAutoUpdate(period: Long): Int {
        val now = nowSeconds()
        val horizon = now + period / 2
        var queued = 0
        for (list in autoLists()) {
            if (due(list) > horizon) continue
            attempts[list.id] = now
            refresh(list.id)
            queued++
        }
        return queued
    }

    /**
     * Plans the periodic poll from the lists, or cancels it while none updates automatically. UPDATE (app start
     * included) registers the job again whatever happened to it, and keeps the current period's start.
     */
    @JvmStatic
    fun schedule() {
        runOnDefaultDispatcher {
            try {
                val workManager = RemoteWorkManager.getInstance(app)
                val plan = plan(nowSeconds())
                if (plan == null) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, plan.period, TimeUnit.SECONDS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(plan.initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putLong(KEY_PERIOD, plan.period)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            } catch (e: Throwable) {
                Logs.w("IpListUpdater: scheduling failed", e)
            }
        }
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
            if (checkAutoUpdate(period.takeIf { it > 0 } ?: (MIN_INTERVAL_MINUTES * 60L)) > 0) awaitIdle()
            // The plan moved without a reschedule (a restored backup, an edit from another path): follow it.
            if (plan(nowSeconds())?.period != period) schedule()
            return Result.success()
        }
    }
}
