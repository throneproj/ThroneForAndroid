package io.nekohasekai.sagernet.ui.test

import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.test.ServiceTestRunner
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProxyEntity
import kotlin.math.min

/** Mutable state of one session; every call happens under [TestSessionClient]'s lock. */
internal class TestSessionModel(
    val kind: Int,
    var scopeLabel: String,
    var groupId: Long,
    val testCurrent: Boolean,
) {
    var speedMode = TestUiState.SPEED_MODE_FULL

    /** Effective `speed_test_timeout_ms` and `test_concurrent`, for the speed test's time-based progress. */
    var speedTimeoutMs = 0
    var concurrency = 0
    var session = 0
    var running = true
    var preparing = true
    var stopping = false
    var stopRequested = false
    var cancelled = false
    var failure = TestFailure.NONE
    var total = 0
    val startedAt = System.currentTimeMillis()
    var finishedAt = 0L
    var service: ICoreService? = null
    var unbind: (() -> Unit)? = null

    private var order = LongArray(0)
    private val position = HashMap<Long, Int>()
    private val rows = LinkedHashMap<Long, RowState>()
    private val reported = HashSet<Long>()
    private var rowsSnapshot: Map<Long, RowState> = emptyMap()
    private var rowsDirty = false

    private var done = 0
    private var ok = 0
    private var failed = 0
    private var testing = 0

    private val bins = IntArray(LatencyHistogram.BIN_COUNT)
    private var binsFailed = 0
    private var binsConnectOnly = 0
    private var histogram = LatencyHistogram()
    private var histogramDirty = false

    private val countryCounts = HashMap<String, Int>()
    private val ips = HashSet<String>()
    private var countries: List<CountryCount> = emptyList()
    private var countriesDirty = false

    private val working = HashMap<Long, RankedResult>()
    private var ranking: List<RankedResult> = emptyList()
    private var rankable = 0
    private var rankingDirty = false

    private val names = HashMap<Long, String>()
    private val namesRequested = HashSet<Long>()
    private val removed = LinkedHashSet<Long>()

    private var windowStart = 0
    private var windowLeft = 0
    private var windowSize = 0
    private var windowAt = 0L

    private var live: Live? = null

    private class Live(val profileId: Long, val t0: Long) {
        var name = ""
        var stage = ""

        /** The budget phase the profile reached ([phaseOf]) and when it began. */
        var phase = 0
        var phaseAt = t0
        var dlBps = 0.0
        var ulBps = 0.0
        var dlBytes = 0L
        var ulBytes = 0L
        var ping = 0L
        var server = ""
        var serverCountry = ""
        var finished = false
        val samples = ArrayList<SpeedSample>()
        var samplesSnapshot: List<SpeedSample> = emptyList()
        var samplesDirty = false

        fun add(sample: SpeedSample) {
            if (samples.size >= MAX_SAMPLES) {
                // Halve the resolution instead of dropping the start of the curve.
                val kept = samples.filterIndexed { i, _ -> i % 2 == 0 }
                samples.clear()
                samples.addAll(kept)
            }
            samples.add(sample)
            samplesDirty = true
        }
    }

    // URL, IP, service and country lookups run in engine batches of [batch] ids in list order and report no "started"
    // event, so the rows of the batch in flight are shown as testing until all of them reported.
    private val batched: Boolean
        get() = !testCurrent && (kind != TestSpec.KIND_SPEED || speedMode == TestUiState.SPEED_MODE_COUNTRY)

    private val batch = if (kind == TestSpec.KIND_SERVICE) ServiceTestRunner.SERVICE_BATCH_SIZE else BATCH

    fun setIds(ids: LongArray) {
        order = ids.distinct().toLongArray()
        order.forEachIndexed { i, id ->
            position[id] = i
            rows[id] = RowState(RowPhase.QUEUED)
        }
        total = if (testCurrent) 1 else order.size
        if (testCurrent) {
            order.firstOrNull()?.let { markTesting(it) }
            windowSize = 1
            windowAt = SystemClock.elapsedRealtime()
        } else if (batched) {
            openWindow()
        }
        rowsDirty = true
    }

    fun onStarted(session: Int, total: Int) {
        if (this.session == 0) this.session = session
        this.total = total.coerceAtLeast(0)
        preparing = false
    }

    fun onUrlResult(profileId: Long, latency: Int, error: String) {
        val ok = ProxyEntity.isWorking(latency)
        val aborted = !ok && (latency == 0 || ProxyEntity.isTestAborted(error))
        put(profileId, RowState(RowPhase.DONE, latency = latency, error = error, ok = ok, failed = !ok && !aborted))
    }

    fun onIpResult(profileId: Long, ip: String, country: String, error: String) {
        val ok = error.isEmpty()
        val aborted = !ok && ProxyEntity.isTestAborted(error)
        put(
            profileId,
            RowState(RowPhase.DONE, error = error, country = country, ip = ip, ok = ok, failed = !ok && !aborted)
        )
    }

    fun onServiceResult(profileId: Long, error: String) {
        val ok = error.isEmpty()
        val aborted = !ok && ProxyEntity.isTestAborted(error)
        put(profileId, RowState(RowPhase.DONE, error = error, ok = ok, failed = !ok && !aborted))
    }

    fun onSpeedResult(profileId: Long, dl: String, ul: String, latency: Int, country: String, error: String) {
        val ok = error.isEmpty()
        val aborted = !ok && ProxyEntity.isTestAborted(error)
        val id = resolve(profileId)
        put(
            id, RowState(
                RowPhase.DONE, latency = latency, error = error, dlSpeed = dl, ulSpeed = ul, country = country,
                ok = ok, failed = !ok && !aborted,
            )
        )
        live?.takeIf { it.profileId == id }?.let {
            it.finished = true
            it.stage = when {
                ok -> SpeedTestSnapshot.STAGE_COMPLETE
                aborted -> SpeedTestSnapshot.STAGE_CANCELLED
                else -> SpeedTestSnapshot.STAGE_ERROR
            }
            if (ok) {
                bps(dl, 0L).takeIf { v -> v > 0 }?.let { v -> it.dlBps = v }
                bps(ul, 0L).takeIf { v -> v > 0 }?.let { v -> it.ulBps = v }
            }
        }
    }

    fun onSpeedProgress(snapshot: SpeedTestSnapshot) {
        val id = resolve(snapshot.profileId)
        val now = SystemClock.elapsedRealtime()
        var l = live
        if (l == null || l.profileId != id) {
            l = Live(id, now)
            live = l
        }
        if (snapshot.profileName.isNotEmpty() && names[id].isNullOrEmpty()) {
            names[id] = snapshot.profileName
            namesRequested.add(id)
        }
        l.name = snapshot.profileName.ifEmpty { names[id].orEmpty() }
        l.stage = snapshot.stage
        val phase = phaseOf(snapshot.stage)
        if (phase > l.phase) {
            l.phase = phase
            l.phaseAt = now
        }
        l.dlBps = bps(snapshot.downloadSpeed, snapshot.downloadBitsPerSecond)
        l.ulBps = bps(snapshot.uploadSpeed, snapshot.uploadBitsPerSecond)
        l.dlBytes = snapshot.downloadBytes
        l.ulBytes = snapshot.uploadBytes
        l.ping = snapshot.latencyMs
        l.server = snapshot.serverName
        l.serverCountry = snapshot.serverCountry
        if (snapshot.done || snapshot.cancelled) {
            l.finished = true
            return
        }
        l.finished = false
        val t = (now - l.t0) / 1000f
        val uploading = snapshot.stage == SpeedTestSnapshot.STAGE_UPLOAD || snapshot.uploadBytes > 0
        if (uploading) {
            l.add(SpeedSample(t, l.ulBps.toFloat(), true))
        } else if (snapshot.stage == SpeedTestSnapshot.STAGE_DOWNLOAD || snapshot.downloadBytes > 0) {
            l.add(SpeedSample(t, l.dlBps.toFloat(), false))
        }
        markTesting(id)
    }

    fun finish(cancelled: Boolean, failure: TestFailure) {
        running = false
        preparing = false
        stopping = false
        this.cancelled = cancelled
        this.failure = failure
        finishedAt = System.currentTimeMillis()
        for (entry in rows.entries) {
            if (entry.value.phase != RowPhase.DONE) entry.setValue(RowState(RowPhase.DONE))
        }
        testing = 0
        rowsDirty = true
        live?.finished = true
    }

    fun markRemoved(ids: Collection<Long>) {
        removed.addAll(ids)
    }

    fun failedIds(): List<Long> = rows.entries.filter { it.value.failed && it.key !in removed }.map { it.key }

    /** Ids of ranked profiles whose names were never requested; they are marked requested. */
    fun takeMissingNames(): List<Long> {
        val missing = ArrayList<Long>()
        for (r in ranking) if (namesRequested.add(r.profileId)) missing.add(r.profileId)
        live?.let { if (it.name.isEmpty() && namesRequested.add(it.profileId)) missing.add(it.profileId) }
        return missing
    }

    fun putNames(resolved: Map<Long, String>) {
        names.putAll(resolved)
        rankingDirty = true
        live?.let { if (it.name.isEmpty()) it.name = names[it.profileId].orEmpty() }
    }

    fun snapshot(): TestUiState {
        if (rowsDirty) {
            rowsSnapshot = LinkedHashMap(rows)
            rowsDirty = false
        }
        if (histogramDirty) {
            histogram = LatencyHistogram(bins.toList(), binsFailed, binsConnectOnly)
            histogramDirty = false
        }
        if (countriesDirty) {
            countries = countryCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .map { CountryCount(it.key, it.value) }
            countriesDirty = false
        }
        if (rankingDirty) {
            val ranked = rank()
            rankable = ranked.size
            ranking = ranked.take(TestSessionClient.RANK_LIMIT).map { it.copy(name = names[it.profileId].orEmpty()) }
            rankingDirty = false
        }
        val l = live
        val speed = l?.let {
            if (it.samplesDirty) {
                it.samplesSnapshot = it.samples.toList()
                it.samplesDirty = false
            }
            SpeedLive(
                it.profileId, it.name, it.stage, it.dlBps, it.ulBps, it.dlBytes, it.ulBytes, it.ping,
                it.server, it.serverCountry, it.finished, it.samplesSnapshot,
            )
        }
        return TestUiState(
            session = session,
            kind = kind,
            scopeLabel = scopeLabel,
            running = running,
            cancelled = cancelled,
            total = total,
            done = done,
            ok = ok,
            failed = failed,
            rows = rowsSnapshot,
            startedAt = startedAt,
            finishedAt = finishedAt,
            groupId = groupId,
            testCurrent = testCurrent,
            speedMode = speedMode,
            preparing = preparing,
            stopping = stopping,
            failure = failure,
            testing = if (testCurrent && running && !preparing && done == 0) 1 else testing,
            histogram = histogram,
            ranking = ranking,
            rankable = rankable,
            countries = countries,
            distinctIps = ips.size,
            speed = speed,
            testingProgress = testingProgress(),
            removed = if (removed.isEmpty()) emptySet() else removed.toSet(),
        )
    }

    /**
     * Speed tests advance the testing slot with time over the known maxima of their phases ([phaseBudgets]), jumping
     * to the next phase when the snapshots show it began early, and stay below [PROGRESS_CAP] until the result.
     * Country lookups have no snapshots: their whole batch moves over its lookup rounds.
     */
    private fun testingProgress(): TestingProgress? {
        if (kind != TestSpec.KIND_SPEED || !running) return null
        if (speedMode == TestUiState.SPEED_MODE_COUNTRY) {
            val parallel = if (concurrency > 0) concurrency else DEFAULT_COUNTRY_CONCURRENCY
            val rounds = ((windowSize + parallel - 1) / parallel).coerceAtLeast(1)
            return TestingProgress(0f, PROGRESS_CAP, windowAt, rounds * DISCOVERY_MS)
        }
        val l = live?.takeIf { rows[it.profileId]?.phase == RowPhase.TESTING } ?: return TestingProgress.NONE
        val budgets = phaseBudgets()
        val phase = min(l.phase, budgets.size - 1)
        val before = budgets.take(phase).sum()
        val all = budgets.sum().toFloat()
        return TestingProgress(
            PROGRESS_CAP * before / all, PROGRESS_CAP * (before + budgets[phase]) / all, l.phaseAt, budgets[phase],
        )
    }

    /** Longest run of each phase in ms: server discovery, then download and/or upload bounded by the timeout. */
    private fun phaseBudgets(): List<Long> {
        val transfer = speedTimeoutMs.toLong().coerceAtLeast(1L)
        return when (speedMode) {
            TestUiState.SPEED_MODE_FULL -> listOf(DISCOVERY_MS, transfer, transfer)
            TestUiState.SPEED_MODE_SIMPLE_DOWNLOAD -> listOf(transfer)
            else -> listOf(DISCOVERY_MS, transfer)
        }
    }

    /** The [phaseBudgets] index a snapshot stage belongs to; "latency" is a transfer that has not moved bytes yet. */
    private fun phaseOf(stage: String): Int = when {
        speedMode == TestUiState.SPEED_MODE_SIMPLE_DOWNLOAD -> 0
        stage == SpeedTestSnapshot.STAGE_UPLOAD && speedMode == TestUiState.SPEED_MODE_FULL -> 2
        stage == SpeedTestSnapshot.STAGE_LATENCY || stage == SpeedTestSnapshot.STAGE_DOWNLOAD ||
            stage == SpeedTestSnapshot.STAGE_UPLOAD -> 1

        else -> 0
    }

    // A test of the running connection may report under another id than the one we guessed.
    private fun resolve(profileId: Long): Long =
        if (testCurrent && profileId !in rows && rows.size == 1) rows.keys.first() else profileId

    private fun markTesting(id: Long) {
        val row = rows[id] ?: return
        if (row.phase != RowPhase.QUEUED) return
        rows[id] = row.copy(phase = RowPhase.TESTING)
        testing++
        rowsDirty = true
    }

    private fun openWindow() {
        windowLeft = 0
        val end = min(windowStart + batch, order.size)
        for (i in windowStart until end) {
            val id = order[i]
            val row = rows[id] ?: continue
            if (row.phase == RowPhase.DONE) continue
            windowLeft++
            markTesting(id)
        }
        windowSize = windowLeft
        windowAt = SystemClock.elapsedRealtime()
    }

    private fun advanceWindow(id: Long) {
        val pos = position[id] ?: return
        if (pos >= windowStart + batch) {
            windowStart = pos / batch * batch
            openWindow()
        } else if (pos >= windowStart) {
            windowLeft--
        }
        while (windowLeft <= 0 && windowStart + batch < order.size) {
            windowStart += batch
            openWindow()
        }
    }

    private fun put(profileId: Long, row: RowState) {
        val id = resolve(profileId)
        if (rows[id]?.phase == RowPhase.TESTING) testing--
        rows[id] = row
        rowsDirty = true
        if (!reported.add(id)) {
            recount()
            return
        }
        count(id, row)
        if (batched) advanceWindow(id)
    }

    private fun count(id: Long, row: RowState) {
        done++
        if (row.ok) ok++ else if (row.failed) failed++
        when (kind) {
            TestSpec.KIND_URL -> {
                when {
                    row.ok && row.latency > 0 -> bins[LatencyHistogram.binOf(row.latency)]++
                    row.ok -> binsConnectOnly++
                    row.failed -> binsFailed++
                }
                histogramDirty = true
            }

            TestSpec.KIND_IP -> if (row.ok) {
                if (row.country.isNotEmpty()) countryCounts[row.country] = (countryCounts[row.country] ?: 0) + 1
                if (row.ip.isNotEmpty()) ips.add(row.ip)
                countriesDirty = true
            }

            TestSpec.KIND_SPEED -> if (row.ok && speedMode == TestUiState.SPEED_MODE_COUNTRY &&
                row.country.isNotEmpty()
            ) {
                countryCounts[row.country] = (countryCounts[row.country] ?: 0) + 1
                countriesDirty = true
            }
        }
        if (row.ok && kind != TestSpec.KIND_IP && kind != TestSpec.KIND_SERVICE) {
            working[id] = RankedResult(
                profileId = id,
                latency = row.latency,
                dlSpeed = row.dlSpeed,
                ulSpeed = row.ulSpeed,
                dlBps = GroupSort.bitrateToBps(row.dlSpeed),
                ulBps = GroupSort.bitrateToBps(row.ulSpeed),
                country = row.country,
            )
            rankingDirty = true
        }
    }

    // A second result for the same profile (a Connect OK verdict after the failure): rebuild every aggregate.
    private fun recount() {
        done = 0
        ok = 0
        failed = 0
        bins.fill(0)
        binsFailed = 0
        binsConnectOnly = 0
        countryCounts.clear()
        ips.clear()
        working.clear()
        for (id in reported) rows[id]?.let { count(id, it) }
        histogramDirty = true
        countriesDirty = true
        rankingDirty = true
    }

    /** Every working result the session's metric can order, best first. */
    private fun rank(): List<RankedResult> {
        val byLatency = kind == TestSpec.KIND_URL || speedMode == TestUiState.SPEED_MODE_COUNTRY
        val candidates = working.values
        return if (byLatency) {
            candidates.filter { it.latency > 0 }.sortedBy { it.latency }
        } else if (speedMode == TestUiState.SPEED_MODE_UPLOAD) {
            candidates.sortedByDescending { it.ulBps }
        } else {
            candidates.sortedByDescending { it.dlBps }
        }
    }

    companion object {
        const val BATCH = 100
        const val MAX_SAMPLES = 600

        /** getSpeedtestServer (core probe.go): the server list fetch (FetchServersTimeout, 8 s) plus its 4 s ping. */
        const val DISCOVERY_MS = 12_000L

        /** BatchSpeedTest's country lookup concurrency when test_concurrent is not positive. */
        const val DEFAULT_COUNTRY_CONCURRENCY = 5

        const val PROGRESS_CAP = 0.95f

        fun bps(text: String?, parsed: Long): Double =
            if (parsed > 0) parsed.toDouble() else GroupSort.bitrateToBps(text).coerceAtLeast(0.0)
    }
}
