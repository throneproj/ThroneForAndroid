package io.nekohasekai.sagernet.bg.scan

import android.os.RemoteCallbackList
import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.IScanCallback
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.scan.DefaultIpLists
import io.nekohasekai.sagernet.scan.IpList
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScan
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.scan.ScanProfiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Scanner::ScanManager in :bg: owns every run, saved resolver scans under their id and WARP finder runs under ids
 * from -1 down, so scans outlive their screens. While any run is active the process is held in the foreground with
 * [ScanNotification]. Clients follow runs through [IScanCallback]: a state JSON on every change (progress at most every
 * 250 ms) and a results signal; see [stateJson] for the format.
 */
object ScanEngine {

    private const val PROGRESS_INTERVAL_MS = 250L

    private class RunInfo(val finder: Boolean, val profileId: Long, val label: String)

    private val lock = Any()
    private val runs = LinkedHashMap<Long, ScanRun>()
    private val infos = HashMap<Long, RunInfo>()

    // Ended finder runs keep their final state until forgotten.
    private val finders = HashMap<Long, ScanLiveState>()
    private val deleting = HashSet<Long>()

    // Unique across :bg processes of one boot: clients keep ended finder states, which a restarted engine must not reuse.
    private var nextFinderId = -SystemClock.elapsedRealtime() - 1L
    private var persistDisabled = false

    // Status writes go out of [lock] (no run's hooks wait on them) but under this, against [restoreGeneration]: what a
    // start or stopAll decided before a stopAll(false) never reaches the restored database.
    private val writeLock = Any()

    @Volatile
    private var restoreGeneration = 0L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingProgress = HashSet<Long>()
    private var flushJob: Job? = null

    private val callbacks = RemoteCallbackList<IScanCallback>()

    // Computing a state and delivering it happen together, so no client sees an older state after a newer one.
    private val publishLock = Any()

    private val startLock = Any()

    @Volatile
    private var started = false

    /** ScanManager's startup, once per process before any scan: interrupted rows become Paused, orphans go. */
    private fun ensureStarted() {
        if (started) return
        synchronized(startLock) {
            if (started) return
            IpScansRepo.normalizeInterrupted()
            IpScansRepo.sweepOrphans()
            started = true
        }
    }

    // ------------------------------------------------------------------------------------------------ saved scans

    /** ScanManager::Start. Blocking. */
    fun start(scanId: Long, mode: StartMode): StartResult {
        ensureStarted()
        val generation: Long
        synchronized(lock) {
            if (scanId in runs) return StartResult(str(R.string.scan_error_already_running))
            if (scanId in deleting) return StartResult(str(R.string.scan_error_gone))
            generation = restoreGeneration
        }
        val scan = IpScansRepo.getIpScan(scanId)
        if (scan == null || scan.kind != IpScan.Kind.Resolver) return StartResult(str(R.string.scan_error_gone))
        val base = ScanProfiles.resolveScanBase(scan)
        val profile = base.profile ?: return StartResult(base.error.ifEmpty { str(R.string.scan_error_profile_unusable) })

        var effective = mode
        if (mode == StartMode.Resume && !rescanResumable(scan)) {
            if (!scan.canResumeInitial()) {
                effective = StartMode.FromInitial
            } else if (scan.specHash != scan.config.targetSpecHash(scan.baseListId)) {
                return StartResult(targetsChanged = true)
            }
        }
        if (effective == StartMode.FromInitial) {
            val baseList = listHeader(scan.baseListId) ?: return StartResult(str(R.string.scan_error_choose_list))
            if (baseList.entryCount <= 0) return StartResult(str(R.string.scan_error_list_empty))
        } else if (effective == StartMode.RescanResults) {
            val resultList = listHeader(scan.resultListId)
            if (resultList == null || resultList.relatedTestId != scanId || resultList.entryCount <= 0) {
                return StartResult(str(R.string.scan_error_result_list_empty))
            }
        }

        val run: ResolverScanRun
        synchronized(lock) {
            if (scanId in runs) return StartResult(str(R.string.scan_error_already_running))
            // The checks above ran unlocked: a delete or a backup restore may have finished meanwhile.
            if (scanId in deleting || restoreGeneration != generation || IpScansRepo.getIpScan(scanId) == null) {
                return StartResult(str(R.string.scan_error_gone))
            }
            persistDisabled = false
            run = ResolverScanRun(scan, effective, profile, hooksFor(scanId))
            runs[scanId] = run
            infos[scanId] = RunInfo(false, scan.config.resolverProfileId, scan.name)
            onRunAddedLocked()
        }
        synchronized(writeLock) {
            if (restoreGeneration == generation) IpScansRepo.saveStatus(scanId, IpScan.Status.Running)
        }
        publish(scanId)
        run.start()
        return StartResult()
    }

    private fun rescanResumable(scan: IpScan): Boolean =
        scan.mode == IpScan.Mode.RescanResult && scan.rescanSnapshotListId >= 0 && scan.rescanCursor < scan.rescanTotal

    private fun listHeader(listId: Long): IpList? {
        if (listId < 0) return null
        val header = IpListsRepo.getIpListHeader(listId) ?: return null
        header.entryCount = IpListsRepo.entryCount(listId)
        return header
    }

    /** ScanManager::Pause: asynchronous; the run persists its position and reports when it has stopped. Finders too. */
    fun pause(id: Long) {
        val run = synchronized(lock) { runs[id] } ?: return
        if (run.isStopping) return
        run.requestStop()
        sendStop(run.sessionId)
        publish(id)
    }

    /** The notification's Stop: [pause] for every run. */
    fun pauseAll() {
        val ids = synchronized(lock) { runs.keys.toList() }
        ids.forEach(::pause)
    }

    /** ScanManager::StopAll: [persist] false (a backup restore) stops every database write of every run from here on. */
    fun stopAll(persist: Boolean) {
        if (!persist) synchronized(writeLock) { restoreGeneration++ }
        val generation = restoreGeneration
        val paused = ArrayList<Pair<Long, ScanRun>>()
        val sessions = synchronized(lock) {
            if (!persist) persistDisabled = true
            // After a restore, a stopped run still in the map must not touch the restored rows.
            val write = persist && !persistDisabled
            for ((id, run) in runs) {
                if (!persist) run.disablePersist()
                run.requestStop()
                if (write && id >= 0) paused.add(id to run)
            }
            runs.values.map { it.sessionId }
        }
        sessions.forEach(::sendStop)
        if (paused.isNotEmpty()) synchronized(writeLock) {
            if (restoreGeneration == generation) {
                for ((id, run) in paused) if (!run.isFinalized) IpScansRepo.saveStatus(id, IpScan.Status.Paused)
            }
        }
        synchronized(lock) { runs.keys.toList() }.forEach(::publish)
    }

    fun isRunning(id: Long): Boolean = synchronized(lock) { id in runs }

    /** Saved scans only, never finder runs. */
    fun runningScans(): List<Long> = synchronized(lock) { runs.keys.filter { it >= 0 } }

    /** ScanManager::DeleteScan: "" or why not; [deleteResultList] false turns the result list into a user list. */
    fun deleteScan(scanId: Long, deleteResultList: Boolean): String {
        if (scanId < 0) return ""
        ensureStarted()
        synchronized(lock) {
            if (scanId in runs) return str(R.string.scan_error_pause_first)
            if (!deleting.add(scanId)) return ""
        }
        try {
            IpScansRepo.deleteScan(scanId, deleteResultList)
        } finally {
            synchronized(lock) { deleting.remove(scanId) }
        }
        return ""
    }

    /** ScanManager::EnsureResultList: a running scan's run owns its row and makes the list itself. */
    fun ensureResultList(scanId: Long): Long {
        ensureStarted()
        synchronized(lock) { runs[scanId]?.let { return it.liveState().resultListId } }
        return IpScansRepo.ensureResultList(scanId)
    }

    // ------------------------------------------------------------------------------------------------ finder runs

    /**
     * ScanManager::StartWarpFinder for the stored profile [profileId], or for an editor's unsaved draft when [draftType]
     * is set ([draftJson] its outbound, in group [groupId]). Returns the run id (< 0) and "", or 0 and the error.
     */
    fun startWarpFinder(profileId: Long, draftType: String, draftJson: String, groupId: Long): Pair<Long, String> {
        val profile = try {
            if (draftType.isNotEmpty()) draft(profileId, draftType, draftJson, groupId) else ProfileManager.getProfile(profileId)
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
        return startWarpFinder(profile)
    }

    private fun draft(profileId: Long, type: String, json: String, groupId: Long): ProxyEntity? {
        val obj = JsonInput.parseObjectOrNull(json) ?: return null
        val outbound = OutboundFactory.newByType(type)
        if (outbound.invalid || !outbound.parseFromJson(obj)) return null
        return ProxyEntity(id = if (profileId > 0) profileId else -1L, groupId = groupId).putOutbound(outbound)
    }

    /** A one-off WARP search on a deep copy of [profile]; it writes nothing (a deleted default WARP list is put back). */
    fun startWarpFinder(profile: ProxyEntity?): Pair<Long, String> {
        ensureStarted()
        val base = ScanProfiles.cloneWarpScanBase(profile)
        val copy = base.profile ?: return 0L to base.error
        val seeds = DefaultIpLists.warpSeeds(ScanProfiles.warpCarrier(copy.outbound) == "masque")
        val id: Long
        val run: WarpFinderRun
        synchronized(lock) {
            id = nextFinderId--
            run = WarpFinderRun(id, copy, seeds, hooksFor(id))
            runs[id] = run
            infos[id] = RunInfo(true, base.sourceId, copy.displayName())
            onRunAddedLocked()
        }
        publish(id)
        run.start()
        return id to ""
    }

    /** ScanManager::ForgetWarpFinder: stops the run if it still runs and drops its state. */
    fun forgetWarpFinder(id: Long) {
        if (id >= 0) return
        val run = synchronized(lock) {
            finders.remove(id)
            infos.remove(id)
            pendingProgress.remove(id)
            runs.remove(id)?.also { onRunRemovedLocked() }
        } ?: return
        // The run winds down on its own threads; nothing it reports reaches clients any more.
        if (!run.isStopping) {
            run.requestStop()
            sendStop(run.sessionId)
        }
    }

    // ------------------------------------------------------------------------------------------------ state

    /**
     * The state of run [id] as JSON, or of saved scan [id] from its row when it does not run; null for an unknown
     * finder. Keys: id, kind ("resolver" | "warp"), scanId (-1 for finders), profileId, label, running, stopping,
     * status (IpScan.Status code; finders: Running, then Completed or Failed), stage, tested, total, found, removed,
     * log (newest last), error, warpJunk, warpHttp2, hits ([{cidr, port, latencyMs, lossPct}], best first), probed,
     * probePassed, urlTested, urlPassed, resultListId.
     */
    fun stateJson(id: Long): String? {
        ensureStarted()
        return stateObject(id)?.toCompact()
    }

    /** Every run, ended finder runs included until forgotten. */
    fun activeJson(): String {
        ensureStarted()
        val ids = synchronized(lock) { runs.keys.toList() + finders.keys.filter { it !in runs } }
        val array = JsonArray()
        for (id in ids) stateObject(id)?.let { array.add(it) }
        return array.toCompact()
    }

    private fun stateObject(id: Long): JsonObject? {
        synchronized(lock) {
            val run = runs[id]
            if (run != null) return toJson(id, run.liveState(), infos[id], IpScan.Status.Running)
            if (id < 0) {
                val state = finders[id] ?: return null
                return toJson(id, state, infos[id], finderStatus(state))
            }
        }
        val scan = IpScansRepo.getIpScan(id) ?: return null
        val state = ScanLiveState(
            tested = scan.activeCursor, total = scan.activeTotal, found = scan.found, removed = scan.removed,
            error = scan.lastError, resultListId = scan.resultListId,
        )
        return toJson(id, state, RunInfo(false, scan.config.resolverProfileId, scan.name), scan.status)
    }

    private fun finderStatus(state: ScanLiveState): IpScan.Status = when {
        state.running -> IpScan.Status.Running
        state.error.isNotEmpty() -> IpScan.Status.Failed
        else -> IpScan.Status.Completed
    }

    private fun toJson(id: Long, state: ScanLiveState, info: RunInfo?, status: IpScan.Status): JsonObject {
        val log = JsonArray()
        for (line in state.log) log.add(line)
        val hits = JsonArray()
        for (hit in state.hits) {
            hits.add(jsonObjectOf("cidr" to hit.cidr, "port" to hit.port, "latencyMs" to hit.latencyMs, "lossPct" to hit.lossPct))
        }
        return jsonObjectOf(
            "id" to id,
            "kind" to if (id < 0) "warp" else "resolver",
            "scanId" to if (id < 0) -1L else id,
            "profileId" to (info?.profileId ?: -1L),
            "label" to info?.label.orEmpty(),
            "running" to state.running,
            "stopping" to state.stopping,
            "status" to status.code,
            "stage" to state.stage,
            "tested" to state.tested,
            "total" to state.total,
            "found" to state.found,
            "removed" to state.removed,
            "log" to log,
            "error" to state.error,
            "warpJunk" to state.warpJunk,
            "warpHttp2" to state.warpHttp2,
            "hits" to hits,
            "probed" to state.probed,
            "probePassed" to state.probePassed,
            "urlTested" to state.urlTested,
            "urlPassed" to state.urlPassed,
            "resultListId" to state.resultListId,
        )
    }

    // ------------------------------------------------------------------------------------------------ callbacks

    /** Registers [callback] and sends it [activeJson] through onSync first, so a client that (re)connects is current. */
    fun register(callback: IScanCallback) {
        ensureStarted()
        synchronized(publishLock) {
            callbacks.register(callback)
            try {
                callback.onSync(activeJson())
            } catch (_: Exception) {
            }
        }
    }

    fun unregister(callback: IScanCallback) {
        callbacks.unregister(callback)
    }

    private inline fun broadcast(action: (IScanCallback) -> Unit) {
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

    private fun publish(id: Long) {
        synchronized(publishLock) {
            if (!tracked(id)) return
            val json = stateJson(id) ?: return
            broadcast { it.onState(id, json) }
        }
        ScanNotification.changed()
    }

    // False for a finder run already forgotten, whose late reports nobody wants.
    private fun tracked(id: Long): Boolean = synchronized(lock) { id >= 0 || id in runs || id in finders }

    private fun hooksFor(id: Long) = ScanRunHooks(
        progress = { queueProgress(id) },
        results = { publishResults(id) },
        finished = { onRunFinished(id) },
    )

    private fun publishResults(id: Long) {
        synchronized(publishLock) {
            if (!tracked(id)) return
            // A finder's hits travel in its state, which must be there when the signal arrives.
            if (id < 0) stateJson(id)?.let { json -> broadcast { it.onState(id, json) } }
            broadcast { it.onResults(id) }
        }
    }

    private fun queueProgress(id: Long) {
        synchronized(lock) {
            if (id < 0 && id !in runs && id !in finders) return
            pendingProgress.add(id)
            if (flushJob != null) return
            flushJob = scope.launch {
                delay(PROGRESS_INTERVAL_MS)
                val ids = synchronized(lock) {
                    flushJob = null
                    pendingProgress.toList().also { pendingProgress.clear() }
                }
                ids.forEach(::publish)
            }
        }
    }

    /** ScanManager::onWorkerFinished: the final state goes out, then the run is gone; a finder's stays until forgotten. */
    private fun onRunFinished(id: Long) {
        synchronized(publishLock) {
            var info: RunInfo?
            val state = synchronized(lock) {
                pendingProgress.remove(id)
                // A finder forgotten meanwhile.
                val run = runs.remove(id) ?: return
                info = infos[id]
                if (id >= 0) infos.remove(id)
                val state = run.liveState()
                state.running = false
                state.stopping = false
                if (id < 0) finders[id] = state
                onRunRemovedLocked()
                state
            }
            val status = if (id < 0) finderStatus(state) else IpScansRepo.getIpScan(id)?.status ?: IpScan.Status.Idle
            val json = toJson(id, state, info, status).toCompact()
            broadcast { it.onState(id, json) }
        }
    }

    private fun onRunAddedLocked() {
        VpnWindow.acquire()
        if (runs.size == 1) ScanNotification.start(scope, notificationItemsLocked(), ::notificationItems)
        else ScanNotification.changed()
    }

    private fun onRunRemovedLocked() {
        VpnWindow.release()
        if (runs.isEmpty()) ScanNotification.finish() else ScanNotification.changed()
    }

    private fun notificationItems(): List<ScanNotificationItem> = synchronized(lock) { notificationItemsLocked() }

    private fun notificationItemsLocked(): List<ScanNotificationItem> = runs.map { (id, run) ->
        ScanNotificationItem(id < 0, infos[id]?.label.orEmpty(), run.liveState())
    }

    private fun sendStop(sessionId: String) {
        scope.launch(Dispatchers.IO) { ScanBinding.stop(sessionId) }
    }

    private fun str(id: Int): String = app.getString(id)
}
