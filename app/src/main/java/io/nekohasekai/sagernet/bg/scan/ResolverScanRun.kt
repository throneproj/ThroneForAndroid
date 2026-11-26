package io.nekohasekai.sagernet.bg.scan

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.test.UrlTestRunner
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.types.Dnstt
import io.nekohasekai.sagernet.scan.EndpointSources
import io.nekohasekai.sagernet.scan.IpList
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.scan.IpListParse
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScan
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.scan.ScanProfiles
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Scanner::RescanRemovals: a rescan removes a failed row only once a row of the same address family passed in this
 * run, as a dead route, server or profile fails every row of a family at once.
 */
internal class RescanRemovals {

    private class Family {
        var passed = false
        var pending = ArrayList<IpListEntry>()
        var unresolvedFrom = Long.MAX_VALUE
    }

    private val v4 = Family()
    private val v6 = Family()

    private fun familyOf(entry: IpListEntry): Family = if (isV6(entry)) v6 else v4

    /** Whether adding these would make a row of that family removable. */
    fun wouldRemove(hits: List<IpListEntry>, failures: List<IpListEntry>, v6: Boolean): Boolean {
        val family = if (v6) this.v6 else v4
        val passes = family.passed || anyOf(hits, v6)
        return passes && (family.pending.isNotEmpty() || anyOf(failures, v6))
    }

    fun add(hits: List<IpListEntry>, failures: List<IpListEntry>, chunkStart: Long) {
        for (hit in hits) familyOf(hit).passed = true
        for (failure in failures) {
            val family = familyOf(failure)
            family.pending.add(failure)
            if (!family.passed) family.unresolvedFrom = minOf(family.unresolvedFrom, chunkStart)
        }
    }

    /** The failures of families that passed; the rest stay pending. */
    fun takeRemovable(): List<IpListEntry> {
        val removable = ArrayList<IpListEntry>()
        for (family in listOf(v4, v6)) {
            if (!family.passed) continue
            removable.addAll(family.pending)
            family.pending = ArrayList()
            family.unresolvedFrom = Long.MAX_VALUE
        }
        return removable
    }

    /** Where the rows still waiting for their family to pass begin; a pause resumes the rescan there. */
    fun unresolvedFrom(): Long = minOf(v4.unresolvedFrom, v6.unresolvedFrom)

    fun anyPassed(): Boolean = v4.passed || v6.passed

    fun pendingCount(): Int = v4.pending.size + v6.pending.size

    /** One line per family whose failures were kept; empty when no family passed. */
    fun keptLines(): List<String> {
        if (!anyPassed()) return emptyList()
        val lines = ArrayList<String>()
        for (family in listOf(v4, v6)) {
            if (family.passed || family.pending.isEmpty()) continue
            val name = if (family === v6) "IPv6" else "IPv4"
            lines.add(app.resources.getQuantityString(R.plurals.scan_log_kept, family.pending.size, name, family.pending.size))
        }
        return lines
    }

    companion object {
        fun isV6(entry: IpListEntry): Boolean = entry.cidr.contains(':')

        fun anyOf(entries: List<IpListEntry>, v6: Boolean): Boolean = entries.any { isV6(it) == v6 }
    }
}

/**
 * Scanner::ScanWorker: a DNS resolver scan, each target tried as the DNS tunnel profile's only resolver. It owns the
 * pass columns of its row while it runs. A chunk that overlapped our VPN starting or stopping is unverified, as one cut
 * short is: its failures are dropped and it runs again once the VPN settled.
 */
internal class ResolverScanRun(
    scan: IpScan,
    private val startMode: StartMode,
    private val base: ProxyEntity,
    private val hooks: ScanRunHooks,
) : ScanRun {

    private enum class Stage { Preparing, Probing, CheckingNetwork }

    private class ProbeOutcome(val address: String, val port: Int, val probePort: Int, val latencyMs: Int, val lossPct: Int)

    private class ChunkResult {
        val hits = ArrayList<IpListEntry>()
        var failures = ArrayList<IpListEntry>()
        val localFailures = ArrayList<IpListEntry>()
        val replaced = ArrayList<IpListEntry>()

        // Failure key -> the core's dns_reason; shortNamesOnly holds the keys that passed only a short name.
        val reasons = HashMap<String, String>()
        val shortNamesOnly = HashSet<String>()

        // A reply of any kind, even an error, proves the network up.
        var replied = false
        var aborted = false
        var error = ""

        // Android: the call overlapped our VPN starting or stopping.
        var disturbed = false
    }

    private class FailureTally {
        var noAnswer = 0
        var errorReply = 0
        var wrongAnswer = 0
        var nameTooLong = 0
        var shortNamesOnly = 0
        var other = 0
        var heavyLoss = 0
    }

    private val scanId = scan.id
    override val sessionId: String = "scan-${scan.id}-${System.currentTimeMillis()}-${sessionCounter.getAndIncrement()}"
    private val initialStatus = scan.status

    // Run thread only.
    private val scan: IpScan = scan
    private var spec: ScanTargetSpec? = null
    private var dns: ScanDnsOptions? = null
    private var systemResolvers: List<IpListEntry> = emptyList()
    private var profileResolvers: List<IpListEntry> = emptyList()
    private val tally = FailureTally()

    // "Scan from the beginning": the pass's first hit replaces the old results.
    private var replacePending = false

    // Rows this run added; the stop-after limit counts these, so Resume finds that many more.
    private var runFound = 0
    private var stoppedByLimit = false
    private val removals = RescanRemovals()

    private val stop = AtomicBoolean(false)
    private val persist = AtomicBoolean(true)

    @Volatile
    private var pollStop = false
    private val finalized = AtomicBoolean(false)

    private val lock = Any()
    private val live = ScanLiveState(running = true, stage = str(R.string.scan_stage_preparing), found = scan.found)
    private var chunkActive = false
    private var chunkStart = 0L
    private var chunkSize = 0L
    private var baseProbed = 0L
    private var lastProbed = 0L

    // Poller thread only.
    private var lastSeq = 0L

    init {
        this.scan.status = IpScan.Status.Running
        this.scan.lastError = ""
        live.resultListId = scan.resultListId
        if (startMode == StartMode.Resume) {
            live.tested = scan.activeCursor
            live.total = scan.activeTotal
            live.removed = scan.removed
        }
    }

    override val isStopping: Boolean get() = stop.get()

    override val isFinalized: Boolean get() = finalized.get()

    override fun start() {
        Thread(::run, "resolver-scan").start()
        Thread(::pollLoop, "resolver-scan-poll").start()
    }

    override fun requestStop() {
        stop.set(true)
        synchronized(lock) { live.stopping = true }
    }

    override fun disablePersist() = persist.set(false)

    override fun liveState(): ScanLiveState = synchronized(lock) {
        live.copy().also { it.stopping = stop.get() }
    }

    private fun rescanPass(): Boolean = scan.mode == IpScan.Mode.RescanResult

    private fun run() {
        var completed = false
        var error = try {
            execute().also { completed = it == null }.orEmpty()
        } catch (e: Exception) {
            e.readableMessage
        } catch (e: Throwable) {
            str(R.string.scan_error_unexpected)
        }
        // A pause cuts calls short, so their errors are artifacts.
        if (stop.get() && !completed) error = ""
        try {
            finishPass(completed, error)
        } catch (e: Throwable) {
            Logs.w(app.getString(R.string.scan_log_save_failed, scan.name), e)
        }
        pollStop = true
        synchronized(lock) { chunkActive = false }
        hooks.finished()
        ScanBinding.stop(sessionId)
    }

    /** Null when the pass completed; otherwise the error, "" for none. */
    private fun execute(): String? {
        setStage(Stage.Preparing)
        pushLog(str(R.string.scan_log_preparing_targets))
        val probe = EndpointSources.scope { ScanProfiles.prepareResolverProbe(base) }
        profileResolvers = probe.profileResolvers
        if (!probe.ok) return probe.error
        dns = ScanDnsOptions(probe.protocol, probe.configJson)
        if (stop.get()) return ""
        // Before the pass changes anything, so a dead network leaves the scan as it was.
        val check = networkUp()
        if (!check.up) {
            if (stop.get()) return ""
            if (check.error.isNotEmpty()) Logs.w(app.getString(R.string.scan_log_network_check_failed, scan.name, check.error))
            return str(R.string.scan_error_network_down)
        }
        systemResolvers = hostEntries(check.systemResolvers)
        if (stop.get()) return ""
        preparePass().let { if (it.isNotEmpty()) return it }
        if (stop.get()) return ""
        loadSpec().let { if (it.isNotEmpty()) return it }
        if (!rescanPass()) {
            val error = preflight()
            if (error == null) return null
            if (error.isNotEmpty() || stop.get()) return error
        }
        return runChunks()
    }

    private fun preparePass(): String {
        when (startMode) {
            StartMode.FromInitial -> return prepareInitial()
            StartMode.RescanResults -> return prepareRescan()
            StartMode.Resume -> {}
        }
        val rescanResumable = rescanPass() && scan.rescanSnapshotListId >= 0 && scan.rescanCursor < scan.rescanTotal
        if (!rescanResumable && !scan.canResumeInitial()) return prepareInitial()
        if (!rescanResumable) {
            scan.mode = IpScan.Mode.Initial
            replacePending = IpScansRepo.replacePending(scan)
        }
        if (!persist.get() || stop.get()) return ""
        val previous = scan.resultListId
        if (ensureResultList() < 0) return str(R.string.scan_error_result_list_create)
        if (scan.resultListId != previous) persistPassState()
        return ""
    }

    private fun ensureResultList(): Long {
        val id = IpScansRepo.ensureScanResultList(scan)
        synchronized(lock) { live.resultListId = scan.resultListId }
        return id
    }

    private fun prepareInitial(): String {
        val baseList = IpListsRepo.getIpList(scan.baseListId) ?: return str(R.string.scan_error_base_list_gone)
        if (baseList.entries.isEmpty()) return str(R.string.scan_error_base_list_empty)
        if (!persist.get() || stop.get()) return ""

        val resultId = ensureResultList()
        if (resultId < 0) return str(R.string.scan_error_result_list_create)
        // The old results stay until the pass's first hit replaces them.
        replacePending = true
        deleteList(scan.snapshotListId)
        deleteList(scan.rescanSnapshotListId)
        scan.snapshotListId = -1L
        scan.rescanSnapshotListId = -1L
        scan.rescanCursor = 0L
        scan.rescanTotal = 0L
        scan.mode = IpScan.Mode.Initial
        scan.cursor = 0L
        scan.total = 0L
        scan.found = IpListsRepo.entryCount(resultId)
        scan.removed = 0
        scan.startedAt = System.currentTimeMillis() / 1000
        scan.finishedAt = 0L
        scan.lastError = ""
        synchronized(lock) {
            live.found = scan.found
            live.removed = 0
        }

        val snapshot = IpList(
            name = app.getString(R.string.scan_snapshot_name, scan.name),
            role = IpList.Role.ScanSnapshot,
            relatedTestId = scan.id,
            lastUpdate = System.currentTimeMillis() / 1000,
            entries = baseList.entries,
            entryCount = baseList.entries.size,
            entriesLoaded = true,
        )
        if (!persist.get()) return ""
        if (!IpListsRepo.addIpList(snapshot)) return str(R.string.scan_error_base_list_copy)
        scan.snapshotListId = snapshot.id
        scan.seed = SecureRandom().nextLong()
        scan.specHash = scan.config.targetSpecHash(scan.baseListId)
        val spec = buildSpec(baseList.entries, scan.config.ports, scan.config.portMode.key, scan.config.shuffle, scan.seed)
        this.spec = spec

        scan.total = countTargets(spec) ?: return countError
        persistPassState()
        if (scan.total == 0L) return str(R.string.scan_error_base_list_no_target)
        setTested(0L, scan.total)
        return ""
    }

    private fun prepareRescan(): String {
        if (!persist.get() || stop.get()) return ""
        deleteList(scan.rescanSnapshotListId)
        scan.rescanSnapshotListId = -1L
        scan.rescanCursor = 0L
        scan.rescanTotal = 0L

        val resultId = ensureResultList()
        if (resultId < 0) return str(R.string.scan_error_result_list_create)
        val entries = IpListsRepo.getEntries(resultId)
        if (entries.isEmpty()) {
            persistPassState()
            return str(R.string.scan_error_result_list_empty)
        }
        val snapshot = IpList(
            name = app.getString(R.string.scan_rescan_snapshot_name, scan.name),
            role = IpList.Role.ScanSnapshot,
            relatedTestId = scan.id,
            entries = entries,
            entryCount = entries.size,
            entriesLoaded = true,
        )
        if (!persist.get()) return ""
        if (!IpListsRepo.addIpList(snapshot)) return str(R.string.scan_error_result_list_copy)
        scan.rescanSnapshotListId = snapshot.id
        scan.mode = IpScan.Mode.RescanResult
        scan.removed = 0
        scan.lastError = ""
        synchronized(lock) { live.removed = 0 }
        val spec = buildSpec(entries, emptyList(), "list", false, 0L)
        this.spec = spec

        scan.rescanTotal = countTargets(spec) ?: return countError
        persistPassState()
        if (scan.rescanTotal == 0L) return str(R.string.scan_error_result_list_no_target)
        setTested(0L, scan.rescanTotal)
        return ""
    }

    private fun loadSpec(): String {
        if (spec?.entries?.isNotEmpty() == true) return ""
        if (rescanPass()) {
            val entries = IpListsRepo.getEntries(scan.rescanSnapshotListId)
            if (entries.isEmpty()) {
                scan.rescanSnapshotListId = -1L
                return str(R.string.scan_error_result_copy_gone)
            }
            spec = buildSpec(entries, emptyList(), "list", false, 0L)
            return ""
        }
        val entries = IpListsRepo.getEntries(scan.snapshotListId)
        if (entries.isEmpty()) {
            scan.snapshotListId = -1L
            return str(R.string.scan_error_base_copy_gone)
        }
        spec = buildSpec(entries, scan.config.ports, scan.config.portMode.key, scan.config.shuffle, scan.seed)
        return ""
    }

    /** Null when the scan is complete (interception found); otherwise the error, "" for none. */
    private fun preflight(): String? {
        setStage(Stage.Probing)
        pushLog(str(R.string.scan_log_interception_check))
        val interception = runSettled(listOf(IpListEntry(INTERCEPTION_PROBE, 53)))
        if (stop.get()) return ""
        val intercepted = interception.error.isEmpty() && interception.hits.isNotEmpty()
        var targets = systemResolvers
        if (intercepted) {
            val line = str(R.string.scan_log_intercepted)
            pushLog(line)
            Logs.w(app.getString(R.string.scan_log_line, scan.name, line))
        } else {
            targets = targets + profileResolvers
        }
        targets = uniqueTargets(targets, PREFLIGHT_CAP)

        if (targets.isNotEmpty()) {
            pushLog(plural(R.plurals.scan_log_preflight, targets.size))
            val result = runSettled(targets)
            if (stop.get()) return ""
            if (result.error.isNotEmpty()) return result.error
            if (!result.replied && RescanRemovals.anyOf(result.localFailures, false)) {
                return str(R.string.scan_error_network_down_paused)
            }
            tally(result)
            if (storeHits(result)) {
                scan.found = IpListsRepo.entryCount(scan.resultListId)
                synchronized(lock) { live.found = scan.found }
                saveProgress()
                hooks.results()
            }
            if (result.hits.isEmpty() && !intercepted) pushLog(str(R.string.scan_log_preflight_none))
        }
        return if (intercepted) null else ""
    }

    /** Null when the pass completed; otherwise the error, "" for none. */
    private fun runChunks(): String? {
        val resultId = scan.resultListId
        val chunkSize = (scan.config.concurrency * 4).coerceIn(32, 2048)
        if (persist.get() && resultId >= 0) {
            scan.found = IpListsRepo.entryCount(resultId)
            synchronized(lock) { live.found = scan.found }
        }
        setTested(scan.activeCursor, scan.activeTotal)

        while (true) {
            val rescan = rescanPass()
            val total = if (rescan) scan.rescanTotal else scan.total
            val cursor = if (rescan) scan.rescanCursor else scan.cursor
            if (!rescan && stopAfterReached()) {
                pushLog(plural(R.plurals.scan_log_stop_after, runFound))
                stoppedByLimit = true
                return null
            }
            if (cursor >= total) return null
            if (stop.get()) return ""

            awaitVpn()
            if (stop.get()) return ""
            val chunkStart = cursor
            beginChunk(chunkStart, minOf(chunkSize.toLong(), total - chunkStart).toInt())
            val mark = VpnWindow.mark()
            var next = chunkStart
            val result = runChunk(spec!!, chunkStart, chunkSize) { next = it }
            if (rescan && result.error.isEmpty() && !result.aborted && !result.disturbed && result.failures.isNotEmpty()) {
                next = confirmFailures(result, chunkStart, next)
            }

            var unverified = false
            var networkError = ""
            if (!stop.get() && (result.disturbed || VpnWindow.disturbed(mark))) {
                // The core's verdicts, an error included, say nothing about the targets: the chunk runs again.
                result.disturbed = true
                result.error = ""
                result.failures.clear()
                unverified = true
            } else if (result.error.isEmpty()) {
                // Any reply proves the network up; a silent chunk asks the system resolvers.
                val silent = !result.replied && result.failures.isNotEmpty()
                // An outage can begin mid-chunk or during the retries, so a removal needs a fresh check.
                val removes = rescan && (removals.wouldRemove(result.hits, result.failures, false) ||
                    removals.wouldRemove(result.hits, result.failures, true))
                if (!result.replied && RescanRemovals.anyOf(result.localFailures, false)) {
                    // Without a default route every target fails locally, and the DNS check reports up when it knows no resolver.
                    unverified = true
                    networkError = str(R.string.scan_error_network_down_paused)
                } else if (rescan && result.aborted && result.failures.isNotEmpty()) {
                    // Cut short before its failures were retried, so they prove nothing.
                    unverified = true
                } else if (silent || removes) {
                    if (stop.get()) {
                        unverified = true
                    } else {
                        val check = networkUp()
                        if (!stop.get() && VpnWindow.disturbed(mark)) {
                            result.disturbed = true
                            result.failures.clear()
                            unverified = true
                        } else if (!check.up) {
                            unverified = true
                            networkError = str(R.string.scan_error_network_down_paused)
                            if (check.error.isNotEmpty()) {
                                Logs.w(app.getString(R.string.scan_log_network_check_failed, scan.name, check.error))
                            }
                        }
                    }
                }
            }
            val settled = result.error.isEmpty() && !unverified
            if (settled) tally(result)

            var resultsChanged = false
            if (persist.get() && resultId >= 0) {
                resultsChanged = storeHits(result)
                if (rescan && settled) {
                    removals.add(result.hits, result.failures, chunkStart)
                    val dead = removals.takeRemovable()
                    if (dead.isNotEmpty()) {
                        val removed = IpListsRepo.removeEntries(resultId, dead)
                        if (removed > 0) {
                            scan.removed += removed
                            resultsChanged = true
                        }
                    }
                }
                scan.found = IpListsRepo.entryCount(resultId)
            }

            val moved = minOf(if (result.error.isNotEmpty() || unverified) chunkStart else next, total)
            if (rescan) scan.rescanCursor = moved else scan.cursor = moved

            synchronized(lock) {
                chunkActive = false
                live.found = scan.found
                live.removed = scan.removed
            }
            saveProgress()
            setTested(moved, total)
            if (resultsChanged) hooks.results()

            if (result.error.isNotEmpty()) return result.error
            if (networkError.isNotEmpty()) return networkError
            if (result.disturbed) continue
            if (unverified) return if (stop.get()) "" else str(R.string.scan_error_session_ended)
            // The loop top completes a finished pass, even when a stop landed after its last chunk.
            if (moved >= total || (!rescan && stopAfterReached())) continue
            if (stop.get()) return ""
            if (result.aborted) return str(R.string.scan_error_session_ended)
            if (moved == chunkStart) return str(R.string.scan_error_no_progress)
        }
    }

    /** A rescan deletes what fails, so one lost exchange must not count: failures are tried again first. Returns next. */
    private fun confirmFailures(result: ChunkResult, chunkStart: Long, next: Long): Long {
        val hitsBefore = result.hits.size
        try {
            for (round in 0 until RESCAN_RETRIES) {
                if (result.failures.isEmpty()) break
                var waited = 0
                while (waited < RETRY_DELAY_MS && !stop.get()) {
                    sleep(100)
                    waited += 100
                }
                if (stop.get()) {
                    result.failures.clear()
                    result.aborted = true
                    return chunkStart
                }
                pushLog(plural(R.plurals.scan_log_retesting, result.failures.size))
                val retry = runTargets(result.failures)
                if (retry.error.isNotEmpty() || retry.aborted || retry.disturbed) {
                    // Unconfirmed, so nothing is removed; a pause re-runs the whole chunk instead.
                    result.failures.clear()
                    if (retry.disturbed) result.disturbed = true
                    if (stop.get()) {
                        result.aborted = true
                        return chunkStart
                    }
                    return next
                }
                result.hits.addAll(retry.hits)
                result.replaced.addAll(retry.replaced)
                result.localFailures.addAll(retry.localFailures)
                result.failures = retry.failures
                result.reasons.putAll(retry.reasons)
                result.shortNamesOnly.addAll(retry.shortNamesOnly)
                result.replied = result.replied || retry.replied
            }
            return next
        } finally {
            val rescued = result.hits.size - hitsBefore
            if (rescued > 0) pushLog(plural(R.plurals.scan_log_rescued, rescued))
        }
    }

    private fun runChunk(spec: ScanTargetSpec, chunkStart: Long, maxTargets: Int, nextCursor: (Long) -> Unit): ChunkResult {
        val result = ChunkResult()
        setStage(Stage.Probing)
        val request = ScanProbeRequest(
            sessionId = sessionId,
            spec = spec,
            cursor = chunkStart,
            maxTargets = maxTargets,
            concurrency = scan.config.concurrency,
            spawnIntervalMs = scan.config.spawnIntervalMs,
            dns = dns,
        )
        val mark = VpnWindow.mark()
        val response = ScanBinding.probe(request)

        for (item in response.results) {
            val outcome = ProbeOutcome(item.address, item.port, item.probePort, item.dnsMs, lossPct(item.sent, item.answered))
            if (item.dnsMs > 0 || item.passed) result.replied = true
            if (item.passed) {
                addHit(result, outcome)
            } else if (item.localFailure) {
                result.localFailures.add(entry(outcome.address, outcome.port, 0))
            } else {
                val entry = entry(outcome.address, outcome.port, 0)
                result.failures.add(entry)
                var reason = item.dnsReason
                if (reason.isEmpty() && item.dnsMs <= 0) reason = "timeout"
                result.reasons[key(entry)] = reason
                if (item.shortNamesOnly) result.shortNamesOnly.add(key(entry))
            }
        }

        if (response.error.isNotEmpty()) {
            // The positions of a call cut short stay unfinished, so its failures prove nothing.
            result.failures.clear()
            result.localFailures.clear()
            result.reasons.clear()
            result.shortNamesOnly.clear()
            if (isStopped(response.error)) stop.set(true)
            if (stop.get()) {
                result.aborted = true
                return result
            }
            if (VpnWindow.disturbed(mark)) {
                result.disturbed = true
                return result
            }
            result.error = response.error
            return result
        }
        nextCursor(maxOf(chunkStart, response.nextCursor))
        result.aborted = response.aborted
        if (!stop.get() && VpnWindow.disturbed(mark)) result.disturbed = true
        return result
    }

    private fun runTargets(targets: List<IpListEntry>): ChunkResult =
        runChunk(buildSpec(targets, emptyList(), "list", false, 0L), 0L, targets.size) {}

    /** [runTargets] again until it did not overlap a VPN window. */
    private fun runSettled(targets: List<IpListEntry>): ChunkResult {
        while (true) {
            awaitVpn()
            val result = runTargets(targets)
            if (!result.disturbed || stop.get()) return result
        }
    }

    private fun addHit(result: ChunkResult, candidate: ProbeOutcome) {
        val port = if (candidate.probePort > 0) candidate.probePort else candidate.port
        result.hits.add(entry(candidate.address, port, candidate.latencyMs).copy(lossPct = candidate.lossPct))
        // A hit under another port must replace the stored rescan row, not add a sibling.
        if (rescanPass() && port != candidate.port) result.replaced.add(entry(candidate.address, candidate.port, 0))
    }

    private fun storeHits(result: ChunkResult): Boolean {
        if (!persist.get() || scan.resultListId < 0 || result.hits.isEmpty()) return false
        val resultId = scan.resultListId
        if (replacePending) {
            replacePending = false
            IpListsRepo.replaceEntries(resultId, result.hits)
            IpScansRepo.clearReplacePending(scan)
            runFound = IpListsRepo.entryCount(resultId)
            return true
        }
        runFound += IpListsRepo.upsertEntries(resultId, result.hits)
        if (rescanPass() && result.replaced.isNotEmpty()) IpListsRepo.removeEntries(resultId, result.replaced)
        return true
    }

    private fun tally(result: ChunkResult) {
        for (entry in result.failures) {
            val key = key(entry)
            when (result.reasons[key].orEmpty()) {
                "timeout" -> tally.noAnswer++
                "rcode" -> tally.errorReply++
                "answer" -> tally.wrongAnswer++
                "qname" -> if (key in result.shortNamesOnly) tally.shortNamesOnly++ else tally.nameTooLong++
                else -> tally.other++
            }
        }
        for (hit in result.hits) if (hit.lossPct >= HEAVY_LOSS_PCT) tally.heavyLoss++
    }

    private fun logSummary() {
        val t = tally
        val parts = ArrayList<String>()
        if (t.noAnswer > 0) parts.add(app.getString(R.string.scan_tally_no_answer, t.noAnswer))
        if (t.errorReply > 0) parts.add(app.getString(R.string.scan_tally_error_reply, t.errorReply))
        if (t.wrongAnswer > 0) parts.add(app.getString(R.string.scan_tally_wrong_answer, t.wrongAnswer))
        if (t.nameTooLong > 0) parts.add(app.getString(R.string.scan_tally_name_too_long, t.nameTooLong))
        if (t.shortNamesOnly > 0) parts.add(app.getString(R.string.scan_tally_short_names, t.shortNamesOnly))
        if (t.other > 0) parts.add(app.getString(R.string.scan_tally_other, t.other))
        val lines = ArrayList<String>()
        if (parts.isNotEmpty()) lines.add(app.getString(R.string.scan_log_failures, parts.joinToString(", ")))
        if (t.heavyLoss > 0) lines.add(plural(R.plurals.scan_log_heavy_loss, t.heavyLoss))
        if (t.shortNamesOnly > 0 && (base.outbound as? Dnstt)?.mode == "vaydns") lines.add(str(R.string.scan_log_lower_qname))
        for (line in lines) {
            pushLog(line)
            Logs.w(app.getString(R.string.scan_log_line, scan.name, line))
        }
    }

    /** The DNS check through the physical network's resolvers, run again when it overlapped a VPN window. */
    private fun networkUp(): ScanNetworkCheck {
        setStage(Stage.CheckingNetwork)
        pushLog(str(R.string.scan_log_checking_network))
        while (true) {
            awaitVpn()
            val mark = VpnWindow.mark()
            val resolvers = UrlTestRunner.systemResolvers(SagerNet.underlyingNetwork)
            val check = ScanBinding.checkNetworkDns(resolvers, NETWORK_CHECK_TIMEOUT_MS)
            if (stop.get() || !VpnWindow.disturbed(mark)) return check
        }
    }

    private fun finishPass(completed: Boolean, error: String) {
        if (completed) {
            if (rescanPass()) {
                val lines = ArrayList(removals.keptLines())
                if (!removals.anyPassed() && removals.pendingCount() > 0) {
                    lines.add(plural(R.plurals.scan_log_none_removed, removals.pendingCount()))
                }
                for (line in lines) {
                    pushLog(line)
                    Logs.w(app.getString(R.string.scan_log_line, scan.name, line))
                }
                deleteList(scan.rescanSnapshotListId)
                scan.rescanSnapshotListId = -1L
                if (scan.canResumeInitial()) {
                    scan.mode = IpScan.Mode.Initial
                    // A pass that stopped at its limit stays Completed; Resume still finds more.
                    scan.status = if (initialStatus == IpScan.Status.Completed) IpScan.Status.Completed else IpScan.Status.Paused
                } else {
                    scan.status = IpScan.Status.Completed
                }
            } else {
                // A pass that stopped at its limit keeps its snapshot, so Resume finds more.
                if (!stoppedByLimit || !scan.canResumeInitial()) {
                    deleteList(scan.snapshotListId)
                    scan.snapshotListId = -1L
                }
                scan.status = IpScan.Status.Completed
            }
            if (scan.status == IpScan.Status.Completed) scan.finishedAt = System.currentTimeMillis() / 1000
            scan.lastError = ""
            logSummary()
        } else {
            if (rescanPass()) scan.rescanCursor = minOf(scan.rescanCursor, removals.unresolvedFrom())
            val rescanResumable = rescanPass() && scan.rescanSnapshotListId >= 0 && scan.rescanCursor < scan.rescanTotal
            if (rescanPass() && !rescanResumable && scan.canResumeInitial()) scan.mode = IpScan.Mode.Initial
            scan.lastError = error
            scan.status = when {
                rescanResumable || scan.canResumeInitial() -> IpScan.Status.Paused
                error.isNotEmpty() -> IpScan.Status.Failed
                initialStatus == IpScan.Status.Completed -> IpScan.Status.Completed
                else -> IpScan.Status.Idle
            }
        }
        persistPassState()
        finalized.set(true)
        if (error.isNotEmpty()) Logs.w(app.getString(R.string.scan_log_stopped, scan.name, error))

        synchronized(lock) {
            live.error = scan.lastError
            live.tested = scan.activeCursor
            live.total = scan.activeTotal
            live.found = scan.found
            live.removed = scan.removed
            live.resultListId = scan.resultListId
        }
    }

    /** Re-reads the row so a rename made while running survives: the run owns only the pass columns. */
    private fun persistPassState() {
        if (!persist.get()) return
        val row = IpScansRepo.getIpScan(scanId) ?: return
        row.resultListId = scan.resultListId
        row.status = scan.status
        row.mode = scan.mode
        row.snapshotListId = scan.snapshotListId
        row.seed = scan.seed
        row.cursor = scan.cursor
        row.total = scan.total
        row.specHash = scan.specHash
        row.rescanSnapshotListId = scan.rescanSnapshotListId
        row.rescanCursor = scan.rescanCursor
        row.rescanTotal = scan.rescanTotal
        row.found = scan.found
        row.removed = scan.removed
        row.lastError = scan.lastError
        row.startedAt = scan.startedAt
        row.finishedAt = scan.finishedAt
        IpScansRepo.saveScan(row)
    }

    private fun saveProgress() {
        if (!persist.get()) return
        val cursor = if (rescanPass()) minOf(scan.rescanCursor, removals.unresolvedFrom()) else scan.cursor
        IpScansRepo.saveProgress(scanId, scan.mode, cursor, scan.activeTotal, scan.found, scan.removed)
    }

    private fun deleteList(listId: Long) {
        if (listId < 0 || !persist.get()) return
        IpListsRepo.deleteIpList(listId)
    }

    // The error of the last countTargets that returned null; "" after a stop.
    private var countError = ""

    private fun countTargets(spec: ScanTargetSpec): Long? {
        val response = ScanBinding.probe(ScanProbeRequest(sessionId = sessionId, spec = spec, cursor = 0L, maxTargets = 0))
        if (response.error.isNotEmpty()) {
            if (isStopped(response.error)) stop.set(true)
            countError = if (stop.get()) "" else response.error
            return null
        }
        if (response.invalidEntries > 0) pushLog(plural(R.plurals.scan_log_invalid_entries, response.invalidEntries))
        return response.total
    }

    private fun stopAfterReached(): Boolean = scan.config.stopAfter > 0 && runFound >= scan.config.stopAfter

    private fun pollLoop() {
        while (!pollStop) {
            sleep(POLL_INTERVAL_MS)
            if (pollStop) break
            pollOnce()
        }
    }

    private fun pollOnce() {
        val response = ScanBinding.query(sessionId, lastSeq) ?: return
        if (pollStop) return
        // A lower sequence means the core restarted and the session began anew from 1.
        lastSeq = if (response.lastSeq < lastSeq) 0L else response.lastSeq

        val events = response.events
        val lines = events.drop(maxOf(0, events.size - ScanLiveState.LOG_LINES)).mapNotNull(::formatEvent)
        var changed = lines.isNotEmpty()
        synchronized(lock) {
            lastProbed = maxOf(lastProbed, response.probed)
            for (line in lines) live.pushLog(line)
            if (live.probed != response.probed || live.probePassed != response.probePassed ||
                live.urlTested != response.urlTested || live.urlPassed != response.urlPassed
            ) {
                live.probed = response.probed
                live.probePassed = response.probePassed
                live.urlTested = response.urlTested
                live.urlPassed = response.urlPassed
                changed = true
            }
            if (chunkActive) {
                val inChunk = (lastProbed - baseProbed).coerceIn(0L, chunkSize)
                if (chunkStart + inChunk > live.tested) {
                    live.tested = chunkStart + inChunk
                    changed = true
                }
            }
        }
        if (changed) notifyProgress()
    }

    private fun beginChunk(chunkStart: Long, chunkSize: Int) {
        // The core computes after_seq + 1, so Long.MAX_VALUE would wrap and return the newest events.
        val counters = ScanBinding.query(sessionId, Long.MAX_VALUE - 1)
        synchronized(lock) {
            // Counters are idle between chunks, and a restarted core counts from zero again.
            if (counters != null) lastProbed = counters.probed
            baseProbed = lastProbed
            this.chunkStart = chunkStart
            this.chunkSize = maxOf(chunkSize, 0).toLong()
            chunkActive = true
            live.tested = chunkStart
        }
    }

    private fun setStage(stage: Stage) {
        val text = str(
            when (stage) {
                Stage.Preparing -> R.string.scan_stage_preparing
                Stage.Probing -> R.string.scan_stage_probing
                Stage.CheckingNetwork -> R.string.scan_stage_checking_network
            }
        )
        setStageText(text)
    }

    private fun setStageText(text: String) {
        synchronized(lock) {
            if (live.stage == text) return
            live.stage = text
        }
        notifyProgress()
    }

    private fun setTested(tested: Long, total: Long) {
        synchronized(lock) {
            live.tested = tested
            live.total = total
        }
        notifyProgress()
    }

    private fun pushLog(line: String) {
        synchronized(lock) { live.pushLog(line) }
        notifyProgress()
    }

    private fun notifyProgress() = hooks.progress()

    private fun awaitVpn() {
        val previous = synchronized(lock) { live.stage }
        val waited = VpnWindow.awaitSettled(stop) {
            setStageText(str(R.string.scan_stage_vpn_wait))
            pushLog(str(R.string.scan_log_vpn_wait))
        }
        if (waited > 0L) setStageText(previous)
    }

    private fun formatEvent(event: ScanEvent): String? = when (event.kind) {
        "start" -> app.getString(R.string.scan_event_start, event.target, phaseName(event.phase))
        "fail" -> app.getString(R.string.scan_event_fail, event.target, phaseName(event.phase), event.error)
        "ok" -> if (event.phase == "dns") app.getString(R.string.scan_event_dns_ok, event.target, event.latencyMs) else null
        else -> null
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
        private const val NETWORK_CHECK_TIMEOUT_MS = 3000

        // Networks that reset a share of exchanges at random need several tries before a live entry is ruled dead.
        private const val RESCAN_RETRIES = 5
        private const val RETRY_DELAY_MS = 1000

        // System resolvers plus the profile's own, probed before the list.
        private const val PREFLIGHT_CAP = 20
        private const val HEAVY_LOSS_PCT = 50

        // TEST-NET-1 runs no resolver: an answer from it means the network answers DNS for any address.
        private const val INTERCEPTION_PROBE = "192.0.2.1"

        private val sessionCounter = AtomicInteger()

        private fun str(id: Int): String = app.getString(id)

        private fun plural(id: Int, count: Int): String = app.resources.getQuantityString(id, count, count)

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
            }
        }

        private fun isStopped(error: String): Boolean = error.contains("scan stopped")

        private fun phaseName(phase: String): String = when (phase) {
            "dns" -> "DNS"
            "warp" -> "WARP"
            "url" -> str(R.string.scan_phase_data_check)
            else -> phase
        }

        private fun buildSpec(entries: List<IpListEntry>, ports: List<Int>, portMode: String, shuffle: Boolean, seed: Long) =
            ScanTargetSpec(entries, ports, portMode, shuffle, seed)

        private fun entry(address: String, port: Int, latencyMs: Int): IpListEntry =
            IpListEntry(IpListParse.normalizeCidr(address).ifEmpty { address }, port, latencyMs)

        private fun key(entry: IpListEntry): String = entry.cidr + " " + entry.port

        private fun lossPct(sent: Int, answered: Int): Int {
            if (sent <= 0) return -1
            return (100.0 * maxOf(0, sent - answered) / sent).roundToInt()
        }

        // Single addresses only, in order; ranges and anything unparsable are dropped.
        private fun hostEntries(lines: List<String>): List<IpListEntry> {
            if (lines.isEmpty()) return emptyList()
            return IpListParse.parseIpListText(lines.joinToString("\n")).entries.filter { IpListParse.isHostCidr(it.cidr) }
        }

        // Port 0 is probed on 53, so it dedupes against an explicit 53.
        private fun uniqueTargets(entries: List<IpListEntry>, cap: Int): List<IpListEntry> {
            val out = ArrayList<IpListEntry>()
            val seen = HashSet<String>()
            for (entry in entries) {
                if (out.size >= cap) break
                if (!seen.add(entry.cidr + " " + (if (entry.port > 0) entry.port else 53))) continue
                out.add(entry)
            }
            return out
        }
    }
}
