package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.aidl.ISubscriptionCallback
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The main process's entry to the :bg subscription queue (the desktop's GroupUpdater: RefreshGroup, RefreshAll,
 * SubscribeUrl, ImportUrl, ImportText) over ICoreService. [states] and [reports] are fed by one callback registration
 * that lives while either of them is collected.
 */
object SubscriptionClient {

    /** One ISubscriptionCallback event: a change report (popup = show the change window), an error or a reload hint. */
    data class Report(
        val gid: Long,
        val title: String,
        val text: String,
        val popup: Boolean,
        val error: Boolean,
        val reload: Boolean = false,
    )

    /** The registration outlives the last collector by this much (configuration changes). */
    private const val STOP_DELAY_MS = 5_000L

    /** A reload hint raised while no window shows the reports waits this long for one, e.g. a profile editor closing. */
    private const val PENDING_RELOAD_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableStates = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** gid → 1 queued / 2 running ([SubscriptionQueue.STATE_QUEUED], [SubscriptionQueue.STATE_RUNNING]); absent = idle. */
    val states: StateFlow<Map<Long, Int>> get() = mutableStates

    private val mutableReports = MutableSharedFlow<Report>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * Refresh reports (popup only for a manual refresh with sub_show_change_popup), after-URL-test reports and
     * importUrl results (popup false), and errors of the requests made here (gid -1 = no single group).
     */
    val reports: SharedFlow<Report> get() = mutableReports

    private val listenerLock = Any()
    private var listener: Job? = null
    private var stopper: Job? = null

    /** When [reloadNeeded] last raised a hint no collector took yet, 0 = none; and its group. */
    private val pendingReload = AtomicLong(0L)
    @Volatile
    private var pendingReloadGid = -1L

    init {
        scope.launch {
            combine(mutableStates.subscriptionCount, mutableReports.subscriptionCount) { a, b -> a + b }
                .collect { subscribersChanged(it) }
        }
        scope.launch {
            mutableReports.subscriptionCount.collect { if (it > 0) deliverReload() }
        }
    }

    /**
     * ProfileService's noteRestartNeeded for a change made in this process: the reload hint reaches the window showing
     * the reports now, or the next one within [PENDING_RELOAD_MS].
     */
    fun reloadNeeded(gid: Long) {
        pendingReloadGid = gid
        pendingReload.set(System.currentTimeMillis())
        deliverReload()
    }

    private fun deliverReload() {
        if (mutableReports.subscriptionCount.value == 0) return
        val raised = pendingReload.getAndSet(0L)
        if (raised <= 0L || System.currentTimeMillis() - raised > PENDING_RELOAD_MS) return
        mutableReports.tryEmit(Report(pendingReloadGid, "", "", popup = false, error = false, reload = true))
    }

    /**
     * ImportTargetRefusalText: why profiles cannot be imported into group [gid] (the current group when not positive),
     * null when they can (ImportService::CheckTarget).
     */
    fun importRefusal(gid: Long): String? {
        val target = if (gid > 0) gid else GroupRepo.currentId()
        return ProfileManager.checkTarget(target)?.let { refusalText(it, move = false) }
    }

    /** The text of a [ProfileManager.TargetRefusal]; a [move] says so where the desktop's move menu does. */
    fun refusalText(refusal: ProfileManager.TargetRefusal, move: Boolean): String = app.getString(
        when (refusal) {
            ProfileManager.TargetRefusal.NOT_FOUND -> R.string.import_target_gone
            ProfileManager.TargetRefusal.ARCHIVED -> R.string.import_target_archived
            ProfileManager.TargetRefusal.SUBSCRIPTION ->
                if (move) R.string.profiles_move_target_subscription else R.string.import_target_subscription
            ProfileManager.TargetRefusal.BUSY -> R.string.profiles_move_source_updating
        }
    )

    /** Desktop group actions that need the core: "remove_invalid", "resolve_domains"; returns the report text. */
    suspend fun groupAction(gid: Long, action: String): String = withContext(Dispatchers.IO) {
        try {
            val report = CoreServiceClient.call { it.groupAction(gid, action) }.orEmpty()
            GroupRepo.postReload(gid)
            report
        } catch (e: Exception) {
            Logs.w(e)
            e.readableMessage
        }
    }

    /** Resolve Domain for the profiles [ids] of group [gid]; returns the report text. */
    suspend fun resolveProfiles(gid: Long, ids: List<Long>): String = withContext(Dispatchers.IO) {
        try {
            val report = CoreServiceClient.call { it.resolveProfiles(ids.toLongArray()) }.orEmpty()
            GroupRepo.postReload(gid)
            report
        } catch (e: Exception) {
            Logs.w(e)
            e.readableMessage
        }
    }

    fun refreshGroup(gid: Long, showDiff: Boolean) = launchCall(gid) { it.refreshGroup(gid, showDiff) }

    fun refreshAll(onlyAllowed: Boolean) = launchCall(-1L) { it.refreshAll(onlyAllowed) }

    /**
     * SubscribeUrl: a subscription group named [name] (the URL host when blank) whose automatic update follows
     * [autoUpdate], created in :bg with its first refresh queued; returns its id, -1 on failure.
     */
    suspend fun subscribeUrl(url: String, name: String, autoUpdate: Boolean): Long = withContext(Dispatchers.IO) {
        try {
            CoreServiceClient.call { it.subscribeUrl(url, name, autoUpdate) }
        } catch (e: Exception) {
            failed(-1L, e)
            -1L
        }
    }

    /**
     * ImportUrl: one fetch with the global identity into group [gid] (the current group when not positive); refused
     * with an error report for a group [importRefusal] names.
     */
    fun importUrl(url: String, gid: Long) {
        scope.launch {
            val refusal = importRefusal(gid)
            if (refusal != null) {
                mutableReports.tryEmit(Report(gid, "", refusal, popup = false, error = true))
                return@launch
            }
            try {
                CoreServiceClient.call { it.importUrl(url, gid) }
            } catch (e: Exception) {
                failed(gid, e)
            }
        }
    }

    /** What [importText] stored, and whether the import box (a popup report) already said so; or why it [refused]. */
    class TextImport(val count: Int, val reported: Boolean, val refused: String? = null)

    /**
     * ImportText: the profiles of [text] appended to group [gid] (the current group when not positive), through a
     * file under cacheDir/import (binder transactions are capped); returns the outcome once imported. Nothing is
     * imported into a group [importRefusal] names.
     */
    suspend fun importText(text: String, gid: Long = -1L): TextImport = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext TextImport(0, false)
        val target = if (gid > 0) gid else GroupRepo.currentId()
        importRefusal(target)?.let { return@withContext TextImport(0, false, refused = it) }
        val dir = File(app.cacheDir, SubscriptionQueue.IMPORT_DIR)
        val file = File(dir, UUID.randomUUID().toString() + ".txt")
        val countFile = File(file.path + SubscriptionQueue.IMPORT_COUNT_SUFFIX)
        try {
            dir.mkdirs()
            file.writeText(text)
            CoreServiceClient.call { it.importFile(file.absolutePath, target) }
            val lines = countFile.takeIf { it.exists() }?.readLines().orEmpty()
            val count = lines.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            if (count > 0) GroupRepo.postReload(target)
            TextImport(count, lines.getOrNull(1)?.trim() == SubscriptionQueue.IMPORT_REPORTED)
        } catch (e: Exception) {
            failed(target, e)
            TextImport(0, false)
        } finally {
            file.delete()
            countFile.delete()
        }
    }

    private fun launchCall(gid: Long, block: (ICoreService) -> Unit) {
        scope.launch {
            try {
                CoreServiceClient.call(block)
            } catch (e: Exception) {
                failed(gid, e)
            }
        }
    }

    private fun failed(gid: Long, e: Exception) {
        Logs.w(e)
        mutableReports.tryEmit(Report(gid, "", e.readableMessage, popup = false, error = true))
    }

    // ------------------------------------------------------------------------------------------------ callbacks

    private fun subscriberCount(): Int = mutableStates.subscriptionCount.value + mutableReports.subscriptionCount.value

    private fun subscribersChanged(count: Int) {
        synchronized(listenerLock) {
            if (count > 0) {
                stopper?.cancel()
                stopper = null
                if (listener == null) listener = scope.launch { listen() }
                return
            }
            if (listener == null || stopper != null) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                delay(STOP_DELAY_MS)
                synchronized(listenerLock) {
                    if (stopper !== coroutineContext[Job]) return@synchronized
                    stopper = null
                    if (subscriberCount() == 0) {
                        listener?.cancel()
                        listener = null
                    }
                }
            }
            stopper = job
            job.start()
        }
    }

    private suspend fun listen() {
        // A registration that lands after the listener stopped unregisters itself and is ignored meanwhile.
        val active = AtomicBoolean(true)
        val callback = object : ISubscriptionCallback.Stub() {
            override fun onState(gid: Long, state: Int) {
                if (!active.get()) return
                mutableStates.update { if (state == SubscriptionQueue.STATE_IDLE) it - gid else it + (gid to state) }
                // In-process listeners (group rows, profile lists) learn about the :bg writes here.
                if (state == SubscriptionQueue.STATE_IDLE) scope.launch {
                    GroupRepo.postUpdate(gid)
                    GroupRepo.postReload(gid)
                }
            }

            override fun onReport(gid: Long, title: String?, text: String?, popup: Boolean) {
                if (!active.get()) return
                mutableReports.tryEmit(Report(gid, title.orEmpty(), text.orEmpty(), popup, error = false))
            }

            override fun onError(gid: Long, message: String?) {
                if (!active.get()) return
                mutableReports.tryEmit(Report(gid, "", message.orEmpty(), popup = false, error = true))
            }

            override fun onReloadNeeded(gid: Long) {
                if (!active.get()) return
                mutableReports.tryEmit(Report(gid, "", "", popup = false, error = false, reload = true))
            }
        }
        val service = AtomicReference<ICoreService?>()
        val unbind = CoreServiceClient.bindPersistent(
            onConnected = { binder ->
                service.set(binder)
                scope.launch {
                    runCatching { binder.registerSubscriptionCallback(callback) }.onFailure { Logs.w(it) }
                    if (!active.get()) runCatching { binder.unregisterSubscriptionCallback(callback) }
                }
            },
            onDisconnected = {
                service.set(null)
                mutableStates.value = emptyMap()
            },
        )
        try {
            awaitCancellation()
        } finally {
            active.set(false)
            service.getAndSet(null)?.let { runCatching { it.unregisterSubscriptionCallback(callback) } }
            unbind()
            mutableStates.value = emptyMap()
        }
    }
}
