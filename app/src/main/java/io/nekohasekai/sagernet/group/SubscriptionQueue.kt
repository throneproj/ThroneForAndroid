package io.nekohasekai.sagernet.group

import android.net.Uri
import android.os.RemoteCallbackList
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISubscriptionCallback
import io.nekohasekai.sagernet.bg.CoreForeground
import io.nekohasekai.sagernet.bg.test.TestEngine
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The :bg subscription job queue behind ICoreService (GroupUpdater, GroupUpdater.cpp:321-446): jobs run one at a
 * time in FIFO order; a group is queued at most once; a batch refuses to start while the previous one runs.
 * Jobs requested over AIDL keep CoreService in the foreground and report their failures through [callbacks].
 */
object SubscriptionQueue {

    const val STATE_IDLE = 0
    const val STATE_QUEUED = 1
    const val STATE_RUNNING = 2

    const val ACTION_REMOVE_INVALID = "remove_invalid"
    const val ACTION_RESOLVE_DOMAINS = "resolve_domains"

    /**
     * Suffix of the file next to an [importFile] path that receives the imported count, followed by a line
     * [IMPORT_REPORTED] when the import box already showed it.
     */
    const val IMPORT_COUNT_SUFFIX = ".count"

    const val IMPORT_REPORTED = "reported"

    /** The directory of [importFile] payloads, under the app's cache directory. */
    const val IMPORT_DIR = "import"

    private const val FOREGROUND_REASON = "subscriptions"

    val callbacks = RemoteCallbackList<ISubscriptionCallback>()

    /** SubscriptionGroupChanged: [disturbed] profiles were deleted or changed in place (auto selectors re-plan). */
    fun interface GroupChangedListener {
        fun onGroupChanged(gid: Long, disturbed: List<Long>)
    }

    private val groupChangedListeners = CopyOnWriteArrayList<GroupChangedListener>()

    fun addGroupChangedListener(listener: GroupChangedListener) {
        groupChangedListeners.addIfAbsent(listener)
    }

    private class Job(val gid: Long, val batch: Boolean, val foreground: Boolean, val run: suspend () -> Unit)

    // Guards the queue and the state events: a callback registered meanwhile never sees them out of order.
    private val lock = Any()
    private val queue = ArrayDeque<Job>()
    private val pending = HashSet<Long>()
    private var pendingBatch = 0
    private var draining = false
    private var runningGid = -1L

    /** Queued or running jobs plus URL-test follow-ups not yet queued. */
    private val outstanding = MutableStateFlow(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------------------------------------ callbacks

    fun register(callback: ISubscriptionCallback) {
        synchronized(lock) {
            callbacks.register(callback)
            for (gid in pending) {
                runCatching { callback.onState(gid, if (gid == runningGid) STATE_RUNNING else STATE_QUEUED) }
            }
        }
    }

    fun unregister(callback: ISubscriptionCallback) {
        callbacks.unregister(callback)
    }

    private inline fun broadcast(action: (ISubscriptionCallback) -> Unit) {
        synchronized(callbacks) {
            val count = callbacks.beginBroadcast()
            try {
                for (i in 0 until count) {
                    try {
                        action(callbacks.getBroadcastItem(i))
                    } catch (_: Exception) {
                    }
                }
            } finally {
                callbacks.finishBroadcast()
            }
        }
    }

    private fun stateLocked(gid: Long, state: Int) = broadcast { it.onState(gid, state) }

    internal fun report(gid: Long, title: String, text: String, popup: Boolean) =
        broadcast { it.onReport(gid, title, text, popup) }

    internal fun error(gid: Long, message: String) = broadcast { it.onError(gid, message) }

    internal fun reloadNeeded(gid: Long) = broadcast { it.onReloadNeeded(gid) }

    internal fun groupChanged(gid: Long, disturbed: List<Long>) {
        for (listener in groupChangedListeners) {
            runCatching { listener.onGroupChanged(gid, disturbed) }.onFailure { Logs.w(it) }
        }
    }

    // ------------------------------------------------------------------------------------------------ entry points

    /** RefreshGroup (GroupUpdater.cpp:331-345); [showDiff] asks for the change window (manual refreshes). */
    fun refreshGroup(gid: Long, showDiff: Boolean) = refreshGroup(gid, showDiff, foreground = true)

    fun refreshGroup(gid: Long, showDiff: Boolean, foreground: Boolean) =
        refreshGroup(gid, if (showDiff) SubscriptionRefresh.Surface.Diff else SubscriptionRefresh.Surface.Log, foreground)

    /** RefreshGroup with the Surface its problems go to (GroupUpdater.cpp). */
    internal fun refreshGroup(gid: Long, surface: SubscriptionRefresh.Surface, foreground: Boolean) {
        val queued = synchronized(lock) {
            if (gid in pending) return@synchronized false
            pending.add(gid)
            enqueueLocked(Job(gid, batch = false, foreground) { SubscriptionRefresh.refresh(gid, surface, foreground) })
            true
        }
        if (!queued) Logs.i(app.getString(R.string.subs_already_queued, groupLabel(gid)))
    }

    /**
     * RefreshAll (GroupUpdater.cpp:347-365): every subscription group in tab order except archived ones and, with
     * [onlyAllowed] (the automatic update), those that skip automatic updates.
     */
    fun refreshAll(onlyAllowed: Boolean) = refreshAll(onlyAllowed, foreground = true)

    fun refreshAll(onlyAllowed: Boolean, foreground: Boolean) {
        val groups = GroupRepo.all()
        val refused = synchronized(lock) {
            if (pendingBatch > 0) return@synchronized true
            for (group in groups) {
                if (group.url.isEmpty() || group.archive || (onlyAllowed && group.skipAutoUpdate)) continue
                val gid = group.id
                if (gid in pending) continue
                pending.add(gid)
                pendingBatch++
                enqueueLocked(Job(gid, batch = true, foreground) { SubscriptionRefresh.refresh(gid, SubscriptionRefresh.Surface.Log, foreground) })
            }
            false
        }
        if (refused) {
            val message = app.getString(R.string.subs_batch_running)
            Logs.i(message)
            if (foreground) error(-1L, message)
        }
    }

    /**
     * SubscribeUrl (GroupUpdater.cpp) and the addsub deep link: a subscription group named [name] (the URL host when
     * blank) that skips automatic updates unless [autoUpdate]; its first refresh is queued without the change window,
     * its problems (a failed download included) go to a report popup. Returns the group id, -1 on failure.
     */
    fun subscribeUrl(url: String, name: String, autoUpdate: Boolean): Long {
        val content = url.trim()
        if (content.isEmpty()) return -1L
        val group = try {
            runBlocking {
                GroupRepo.add(
                    ProxyGroup(
                        name = name.trim().ifEmpty { runCatching { Uri.parse(content).host }.getOrNull().orEmpty() },
                        url = content,
                        skipAutoUpdate = !autoUpdate,
                    )
                )
            }
        } catch (e: Exception) {
            Logs.w(e)
            return -1L
        }
        refreshGroup(group.id, SubscriptionRefresh.Surface.Report, foreground = true)
        if (autoUpdate) SubscriptionScheduler.schedule()
        return group.id
    }

    /** ImportUrl (GroupUpdater.cpp:381-390): one fetch with the global identity into group [gid] (<= 0: current). */
    fun importUrl(url: String, gid: Long) {
        val content = url.trim()
        synchronized(lock) {
            enqueueLocked(Job(-1L, batch = false, foreground = true) {
                val fetched = SubscriptionFetch.fetch(content, content, RequestIdentity.resolve(null))
                if (fetched.error != null) {
                    error(gid, fetched.error)
                } else {
                    val emptyReason = if (SubscriptionRefresh.looksLikeWebPage(fetched.body)) vtr(R.string.validation_import_web_page) else ""
                    val result = SubscriptionRefresh.importDocuments(gid, listOf(fetched.body), emptyReason)
                    // The import box already says how many were imported.
                    if (!result.reported) report(result.gid, "", app.getString(R.string.subs_imported, result.count), false)
                }
            })
        }
    }

    /**
     * ImportText (GroupUpdater.cpp:392-399) for text the main process wrote to [path] (under cacheDir/[IMPORT_DIR]);
     * the file is deleted. Returns once the job ran, with the imported count written to [path] + [IMPORT_COUNT_SUFFIX].
     */
    fun importFile(path: String, gid: Long) {
        val file = File(path).canonicalFile
        if (file.parentFile != File(app.cacheDir, IMPORT_DIR).canonicalFile) {
            Logs.w("Not an import file: $path")
            return
        }
        val text = try {
            file.readText()
        } catch (e: Exception) {
            Logs.w(e)
            null
        } finally {
            file.delete()
        }
        var count = 0
        var reported = false
        if (text != null) {
            val done = CompletableDeferred<SubscriptionRefresh.ImportResult?>()
            synchronized(lock) {
                enqueueLocked(Job(-1L, batch = false, foreground = true) {
                    try {
                        done.complete(SubscriptionRefresh.importDocuments(gid, listOf(text)))
                    } finally {
                        done.complete(null)
                    }
                })
            }
            runBlocking { done.await() }?.let {
                count = it.count
                reported = it.reported
            }
        }
        val content = if (reported) "$count\n$IMPORT_REPORTED" else count.toString()
        runCatching { File(file.path + IMPORT_COUNT_SUFFIX).writeText(content) }.onFailure { Logs.w(it) }
    }

    /**
     * The desktop's manual group actions that need the core: [ACTION_REMOVE_INVALID] ("Remove Invalid") and
     * [ACTION_RESOLVE_DOMAINS] ("Resolve Domain for group"). Queued like a refresh so they never race one over the
     * group; returns the report text once done.
     */
    fun groupAction(gid: Long, action: String): String {
        if (action != ACTION_REMOVE_INVALID && action != ACTION_RESOLVE_DOMAINS) {
            return app.getString(R.string.subs_unknown_action, action)
        }
        return runQueued {
            if (action == ACTION_REMOVE_INVALID) SubscriptionFilters.removeInvalid(gid)
            else SubscriptionFilters.resolveDomains(gid)
        }
    }

    /** "Resolve Domain" for chosen profiles (on_menu_resolve_selected_triggered), queued like [groupAction]. */
    fun resolveProfiles(ids: List<Long>): String = runQueued { SubscriptionFilters.resolveProfiles(ids) }

    private fun runQueued(action: suspend () -> String): String {
        val result = CompletableDeferred<String>()
        synchronized(lock) {
            enqueueLocked(Job(-1L, batch = false, foreground = true) {
                try {
                    result.complete(action())
                } catch (e: Throwable) {
                    result.complete(e.readableMessage)
                    throw e
                }
            })
        }
        return runBlocking { result.await() }
    }

    /** Suspends until no job is queued or running and no URL-test follow-up is pending. */
    suspend fun awaitIdle() {
        outstanding.first { it == 0 }
    }

    /**
     * startUrlTest (GroupUpdater.cpp:1452-1462): the URL test waits for any running test session; the follow-up
     * goes back through the queue, so it never races another job over the group. Only what fails from testedSince on
     * (seconds, as latency_at) is this run's verdict.
     */
    internal fun requestUrlTest(gid: Long, profileIds: List<Long>) {
        outstanding.update { it + 1 }
        scope.launch {
            try {
                val testedSince = System.currentTimeMillis() / 1000
                val completed = try {
                    TestEngine.queueUrlTests(profileIds)
                } catch (e: Exception) {
                    if (e is CancellationException) ensureActive() else Logs.w(e)
                    false
                }
                synchronized(lock) {
                    enqueueLocked(Job(-1L, batch = false, foreground = false) {
                        SubscriptionRefresh.afterUrlTest(gid, testedSince, completed)
                    })
                }
            } finally {
                outstanding.update { it - 1 }
            }
        }
    }

    /**
     * The country tester (SetCountryTester, TestRunner::queueIpTests): an IP test of [profileIds] once no session
     * runs; afterCountryTest goes back through the queue.
     */
    internal fun requestCountryTest(gid: Long, profileIds: List<Long>) {
        outstanding.update { it + 1 }
        scope.launch {
            try {
                try {
                    TestEngine.queueIpTests(profileIds)
                } catch (e: Exception) {
                    if (e is CancellationException) ensureActive() else Logs.w(e)
                }
                synchronized(lock) {
                    enqueueLocked(Job(-1L, batch = false, foreground = false) { SubscriptionRefresh.afterCountryTest(gid) })
                }
            } finally {
                outstanding.update { it - 1 }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ worker

    private fun enqueueLocked(job: Job) {
        queue.addLast(job)
        outstanding.update { it + 1 }
        if (job.foreground) CoreForeground.acquire(FOREGROUND_REASON)
        if (job.gid >= 0) stateLocked(job.gid, STATE_QUEUED)
        if (draining) return
        draining = true
        scope.launch { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val job = synchronized(lock) {
                val next = queue.removeFirstOrNull()
                if (next == null) {
                    draining = false
                } else if (next.gid >= 0) {
                    runningGid = next.gid
                    stateLocked(next.gid, STATE_RUNNING)
                }
                next
            } ?: return
            try {
                job.run()
            } catch (e: Throwable) {
                // Anything thrown here would otherwise take the whole :bg process (and the VPN) down.
                val message = app.getString(R.string.subs_task_failed, e.readableMessage)
                Logs.w(message, e)
                if (job.foreground) error(job.gid, message)
            } finally {
                synchronized(lock) {
                    runningGid = -1L
                    if (job.gid >= 0) {
                        pending.remove(job.gid)
                        stateLocked(job.gid, STATE_IDLE)
                    }
                    if (job.batch) pendingBatch--
                }
                if (job.foreground) CoreForeground.release(FOREGROUND_REASON)
                outstanding.update { it - 1 }
            }
        }
    }

    private fun groupLabel(gid: Long): String = GroupRepo.get(gid)?.name ?: gid.toString()
}
