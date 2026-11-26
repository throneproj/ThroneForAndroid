package io.nekohasekai.sagernet.ui.scan

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.aidl.IScanCallback
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.bg.scan.StartMode
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.scan.IpScan
import io.nekohasekai.sagernet.scan.ScanProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The main process's side of the :bg scan engine (ScanEngine, the desktop's ScanManager): starts, pauses and stops
 * saved resolver scans and WARP finder runs, and mirrors every run in [runs]. One IScanCallback registration feeds
 * [runs] and [results] while either is collected, so a screen that comes back re-attaches by collecting them.
 */
object ScanClient {

    /** ScanManager::StartResult: [targetsChanged] asks whether to start over, as the targets changed since the pause. */
    class StartResult(@JvmField val error: String, @JvmField val targetsChanged: Boolean = false) {
        val ok: Boolean get() = error.isEmpty() && !targetsChanged
    }

    /** A finder run's id (< 0), or why it did not start. */
    class FinderStart(@JvmField val id: Long, @JvmField val error: String) {
        val ok: Boolean get() = id < 0 && error.isEmpty()
    }

    /** One run as the engine last reported it (ScanLiveState plus who it belongs to). */
    data class RunState(
        /** The saved scan's id, or the finder run's id (< 0). */
        val id: Long,
        /** A WARP finder run, else a saved resolver scan. */
        val finder: Boolean,
        /** The saved scan's id; -1 for finder runs. */
        val scanId: Long,
        /** The DNS tunnel profile of a resolver scan, or the profile a finder searches for (-1 for a new draft). */
        val profileId: Long,
        /** The scan's name, or the finder profile's display name. */
        val label: String,
        val running: Boolean,
        /** A pause was asked for and the run is winding down. */
        val stopping: Boolean,
        /** Running while it runs; afterwards the row's status, for finders Completed or Failed. */
        val status: IpScan.Status,
        /** The phase while running, e.g. "Probing". */
        val stage: String,
        /** Resolver scans: targets done and in total; finders: seconds of the search budget used and the budget. */
        val tested: Long,
        val total: Long,
        /** Hits: saved results of this pass, or confirmed finder endpoints. */
        val found: Int,
        /** Results a rescan removed. */
        val removed: Int,
        /** The last [io.nekohasekai.sagernet.bg.scan.ScanLiveState.LOG_LINES] log lines, newest last; kept after the run ends. */
        val log: List<String>,
        /** Why the run stopped; empty when it completed or was paused. */
        val error: String,
        /** Finders: the endpoints answered only with AmneziaWG junk, apply with junk. */
        val warpJunk: Boolean,
        /** Finders: MASQUE answered only over HTTP/2, apply with the HTTP/2 fallback. */
        val warpHttp2: Boolean,
        /** Finders: confirmed endpoints, best first. */
        val hits: List<IpListEntry>,
        /** The core session's counters. */
        val probed: Long,
        val probePassed: Long,
        val urlTested: Long,
        val urlPassed: Long,
        /** Resolver scans: the result list, -1 until made. */
        val resultListId: Long,
        /** Bumped on every results change of the run while it is mirrored here. */
        val resultsVersion: Long = 0L,
    ) {
        val canPause: Boolean get() = running && !stopping
    }

    /** The registration outlives the last collector by this much (configuration changes). */
    private const val STOP_DELAY_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val mutableRuns = MutableStateFlow<Map<Long, RunState>>(emptyMap())

    /**
     * Every run the engine has, by id: running ones, finder runs until forgotten, and the last state of saved scans that
     * ended while mirrored. A saved scan missing here is not running; its row tells the rest.
     */
    val runs: StateFlow<Map<Long, RunState>> get() = mutableRuns

    private val mutableResults = MutableSharedFlow<Long>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Ids of runs whose results changed: a saved scan's result list, a finder's [RunState.hits]. */
    val results: SharedFlow<Long> get() = mutableResults

    private val listenerLock = Any()
    private var listener: Job? = null
    private var stopper: Job? = null

    // Finder runs forgotten here: reports :bg sent before the forget reached it must not bring them back. Checked inside
    // the map updates, so a report racing the forget loses either way.
    private val forgotten: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    init {
        scope.launch {
            combine(mutableRuns.subscriptionCount, mutableResults.subscriptionCount) { a, b -> a + b }
                .collect { subscribersChanged(it) }
        }
    }

    /** Run [id]'s state as it changes; null while the engine has none. */
    fun observe(id: Long): Flow<RunState?> = runs.map { it[id] }.distinctUntilChanged()

    fun isRunning(id: Long): Boolean = runs.value[id]?.running == true

    // ------------------------------------------------------------------------------------------------ calls

    /** ScanManager::Start for saved scan [scanId]; blocks for the engine's checks. Resume falls back as on the desktop. */
    suspend fun start(scanId: Long, mode: StartMode): StartResult = withContext(Dispatchers.IO) {
        try {
            val reply = JsonInput.parseObject(CoreServiceClient.call { it.startScan(scanId, mode.code) }.orEmpty())
            StartResult(reply.string("error"), reply.bool("targetsChanged"))
        } catch (e: Exception) {
            Logs.w(e)
            StartResult(app.getString(R.string.scan_error_unreachable))
        }
    }

    /** A WARP search for stored profile [profileId]; the profile itself is changed only by [applyWarpEndpoint]. */
    suspend fun startWarpFinder(profileId: Long): FinderStart = finder { it.startWarpFinder(profileId, "", "", 0L) }

    /** A WARP search for an editor's unsaved [draft]: its type, outbound, id (0 for a new profile) and group. */
    suspend fun startWarpFinder(draft: ProxyEntity): FinderStart {
        val type = draft.type
        val json = draft.outbound.exportToJson().toCompact()
        return finder { it.startWarpFinder(draft.id, type, json, draft.groupId) }
    }

    private suspend fun finder(block: (ICoreService) -> String?): FinderStart = withContext(Dispatchers.IO) {
        try {
            val reply = JsonInput.parseObject(CoreServiceClient.call(block).orEmpty())
            FinderStart(reply.integer("id"), reply.string("error"))
        } catch (e: Exception) {
            Logs.w(e)
            FinderStart(0L, app.getString(R.string.scan_error_unreachable))
        }
    }

    /** ScanManager::Pause: a saved scan keeps its position for a resume; its state says stopping until it has. */
    fun pause(id: Long) = launchCall { it.pauseScan(id) }

    /** Stops run [id] (a finder run, or pauses a saved scan). */
    fun stop(id: Long) = pause(id)

    /** ScanManager::ForgetWarpFinder: stops finder run [id] if it still runs and drops its state. */
    fun forgetWarpFinder(id: Long) {
        if (id >= 0) return
        forgotten.add(id)
        mutableRuns.update { it - id }
        launchCall { it.forgetWarpFinder(id) }
    }

    /** ScanManager::StopAll: [persist] false before a backup restore, so no run writes to the restored database. */
    suspend fun stopAll(persist: Boolean) {
        withContext(Dispatchers.IO) {
            runCatching { CoreServiceClient.call { it.stopAllScans(persist) } }.onFailure { Logs.w(it) }
        }
    }

    /** Run [id]'s state from the engine now; a saved scan that does not run comes from its row. */
    suspend fun state(id: Long): RunState? = withContext(Dispatchers.IO) {
        try {
            parse(JsonInput.parseObjectOrNull(CoreServiceClient.call { it.scanState(id) }.orEmpty()))
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
    }

    /** Every run the engine has now, for one-off checks; screens collect [runs] instead. */
    suspend fun activeRuns(): List<RunState> = withContext(Dispatchers.IO) {
        try {
            parseArray(CoreServiceClient.call { it.activeScans() }.orEmpty())
        } catch (e: Exception) {
            Logs.w(e)
            emptyList()
        }
    }

    /** ScanManager::DeleteScan: "" or why not; without [deleteResultList] the result list becomes a user list. */
    suspend fun deleteScan(scanId: Long, deleteResultList: Boolean): String = withContext(Dispatchers.IO) {
        try {
            CoreServiceClient.call { it.deleteScan(scanId, deleteResultList) }.orEmpty()
        } catch (e: Exception) {
            Logs.w(e)
            app.getString(R.string.scan_error_unreachable)
        }
    }

    /** ScanManager::EnsureResultList: the scan's result list, made when missing; -1 on failure. */
    suspend fun ensureResultList(scanId: Long): Long = withContext(Dispatchers.IO) {
        try {
            CoreServiceClient.call { it.ensureScanResultList(scanId) }
        } catch (e: Exception) {
            Logs.w(e)
            -1L
        }
    }

    /**
     * MainWindow::applyFoundWarpEndpoint in this process: points stored profile [profileId] at [entry] with the junk or
     * HTTP/2 fallback the run needed ([RunState.warpJunk], [RunState.warpHttp2]), clears its test results and saves it.
     * Returns "" or why not. An editor applies to its own draft with [ScanProfiles.applyWarpEndpoint] instead.
     */
    suspend fun applyWarpEndpoint(profileId: Long, entry: IpListEntry, junk: Boolean, http2: Boolean): String =
        withContext(Dispatchers.IO) {
            try {
                val profile = ProfileManager.getProfile(profileId) ?: return@withContext app.getString(R.string.scan_apply_gone)
                val name = profile.displayName()
                if (!ScanProfiles.applyWarpEndpoint(profile, entry, junk, http2)) {
                    return@withContext app.getString(R.string.scan_apply_not_warp, name)
                }
                profile.clearTestResults()
                val used = ProfileManager.runningUses(profileId)
                ProfileManager.updateProfile(profile)
                if (ProfileManager.getProfile(profileId) == null) return@withContext app.getString(R.string.scan_apply_gone)
                GroupSort.repositionLater(mapOf(profile.groupId to listOf(profileId)), SortInput.CONFIG)
                if (used) ProfileManager.noteRestartNeeded(profile.groupId)
                ""
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
        }

    private fun launchCall(block: (ICoreService) -> Unit) {
        scope.launch {
            runCatching { CoreServiceClient.call(block) }.onFailure { Logs.w(it) }
        }
    }

    // ------------------------------------------------------------------------------------------------ state

    private fun parse(obj: JsonObject?): RunState? {
        if (obj == null || !obj.isNumber("id")) return null
        val hits = ArrayList<IpListEntry>()
        for (item in obj.array("hits")) {
            if (item !is JsonObject) continue
            val loss = if (item.isNumber("lossPct")) item.int("lossPct") else -1
            hits.add(IpListEntry(item.string("cidr"), item.int("port"), item.int("latencyMs"), loss))
        }
        return RunState(
            id = obj.integer("id"),
            finder = obj.string("kind") == "warp",
            scanId = obj.integer("scanId"),
            profileId = obj.integer("profileId"),
            label = obj.string("label"),
            running = obj.bool("running"),
            stopping = obj.bool("stopping"),
            status = IpScan.Status.of(obj.int("status")),
            stage = obj.string("stage"),
            tested = obj.integer("tested"),
            total = obj.integer("total"),
            found = obj.int("found"),
            removed = obj.int("removed"),
            log = obj.array("log").strings(),
            error = obj.string("error"),
            warpJunk = obj.bool("warpJunk"),
            warpHttp2 = obj.bool("warpHttp2"),
            hits = hits,
            probed = obj.integer("probed"),
            probePassed = obj.integer("probePassed"),
            urlTested = obj.integer("urlTested"),
            urlPassed = obj.integer("urlPassed"),
            resultListId = obj.integer("resultListId"),
        )
    }

    private fun parseArray(json: String): List<RunState> {
        val array = JsonInput.parseValue(json) as? JsonArray ?: return emptyList()
        return array.mapNotNull { parse(it as? JsonObject) }
    }

    // A stopped scan has no live log; the last lines stay up until the next run (DialogScanInstance::refreshRun).
    private fun merge(old: RunState?, new: RunState): RunState {
        if (old == null) return new
        val log = if (!new.running && new.log.isEmpty()) old.log else new.log
        return new.copy(log = log, resultsVersion = old.resultsVersion)
    }

    private fun ended(state: RunState): RunState = state.copy(
        running = false,
        stopping = false,
        status = if (state.finder) IpScan.Status.Failed else IpScan.Status.Paused,
        error = state.error.ifEmpty { app.getString(R.string.scan_error_service_died) },
    )

    private fun sync(old: Map<Long, RunState>, states: List<RunState>): Map<Long, RunState> {
        val next = LinkedHashMap<Long, RunState>()
        for (state in states) next[state.id] = merge(old[state.id], state)
        for ((id, state) in old) {
            if (id in next) continue
            when {
                !state.running -> next[id] = state
                // Ended while nothing listened; the row has the rest.
                !state.finder -> Unit
                // Only a restarted :bg loses a finder run it did not forget.
                else -> next[id] = ended(state)
            }
        }
        return next
    }

    // ------------------------------------------------------------------------------------------------ callbacks

    private fun subscriberCount(): Int = mutableRuns.subscriptionCount.value + mutableResults.subscriptionCount.value

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
        // A registration that lands after the listener stopped unregisters itself and is ignored meanwhile. The engine
        // delivers in order: onSync first, then every change.
        val active = AtomicBoolean(true)
        val callback = object : IScanCallback.Stub() {
            override fun onSync(statesJson: String?) {
                if (!active.get()) return
                val states = parseArray(statesJson.orEmpty())
                mutableRuns.update { old -> sync(old, states.filter { it.id !in forgotten }) }
            }

            override fun onState(id: Long, stateJson: String?) {
                if (!active.get()) return
                val state = parse(JsonInput.parseObjectOrNull(stateJson.orEmpty())) ?: return
                mutableRuns.update { if (id in forgotten) it else it + (id to merge(it[id], state)) }
            }

            override fun onResults(id: Long) {
                if (!active.get() || id in forgotten) return
                mutableRuns.update { runs ->
                    val state = runs[id] ?: return@update runs
                    runs + (id to state.copy(resultsVersion = state.resultsVersion + 1))
                }
                mutableResults.tryEmit(id)
            }
        }
        val service = AtomicReference<ICoreService?>()
        val unbind = CoreServiceClient.bindPersistent(
            onConnected = { binder ->
                service.set(binder)
                scope.launch {
                    runCatching { binder.registerScanCallback(callback) }.onFailure { Logs.w(it) }
                    if (!active.get()) runCatching { binder.unregisterScanCallback(callback) }
                }
            },
            onDisconnected = {
                service.set(null)
                if (active.get()) mutableRuns.update { runs -> runs.mapValues { (_, state) -> if (state.running) ended(state) else state } }
            },
        )
        try {
            awaitCancellation()
        } finally {
            active.set(false)
            service.getAndSet(null)?.let { runCatching { it.unregisterScanCallback(callback) } }
            unbind()
        }
    }
}
