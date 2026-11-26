package io.nekohasekai.sagernet.bg.scan

import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.applyTo
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.types.Masque
import io.nekohasekai.sagernet.outbound.types.WireGuard
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.scan.IpListParse
import io.nekohasekai.sagernet.scan.ScanProfiles
import io.nekohasekai.sagernet.scan.WarpPresets
import io.throneproj.mobile.TestRequest
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Scanner::WarpWorker: a WARP finder run for one profile, with no database access. Port discovery over the seeds,
 * sampled rounds, then a data check that confirms handshake winners. It counts seconds of its time budget as
 * tested/total; hits stay in [liveState]. A call that overlapped our VPN starting or stopping runs again once it has
 * settled, and the wait costs none of the budget.
 */
internal class WarpFinderRun(
    finderId: Long,
    base: ProxyEntity,
    private val seeds: List<IpListEntry>,
    private val hooks: ScanRunHooks,
) : ScanRun {

    private class Target(val address: String, val port: Int)

    /** [port] is the one probed; [entryPort] the one asked for. */
    private class Candidate(
        val address: String,
        val port: Int,
        val entryPort: Int,
        val sent: Int,
        val answered: Int,
        val handshakeMs: Int,
    )

    /** One /24, a smaller IPv4 range, or an IPv6 prefix sampled at random; [port] 0 = the ports discovery found. */
    private class Pool(
        val v6: Boolean,
        val v4First: Long = 0L,
        val v4Count: Long = 0L,
        val v6Net: ByteArray = ByteArray(16),
        val v6Prefix: Int = 128,
        val port: Int = 0,
    ) {
        val used = HashSet<String>()
        var exhausted = false
    }

    private class ProbeBatch {
        val winners = ArrayList<Candidate>()
        val failures = ArrayList<Target>()
        var answered = 0
        var v4LocalFailure = false
        var v6LocalFailure = false
        var aborted = false
        var error = ""
        var disturbed = false
    }

    override val sessionId: String =
        "warp-$finderId-${System.currentTimeMillis()}-${sessionCounter.getAndIncrement()}"

    // Run thread only. A deep copy; junk and HTTP/2 mode swap in a copy with junk or the HTTP/2 fallback on.
    private var base: ProxyEntity = base
    private var stage = ""
    private var carrier = ""
    private var probeJson = ""
    private var testUrl = ""
    private var pools = ArrayList<Pool>()
    private var ports: List<Int> = emptyList()
    private var portCursor = 0
    private val pending = ArrayList<Candidate>()

    // The best confirmation of each address, best first.
    private val hits = ArrayList<Candidate>()
    private val confirmedAddresses = HashSet<String>()
    private var lastConfirmMs = -1L
    private var networkUpAtMs = -1L

    // Budget spent when the search ended.
    private var searchMs = 0L
    private var answeredAny = false
    private var junkMode = false
    private var ipv6Allowed = true

    // Also read by the ticker and liveState().
    @Volatile
    private var clockStartMs = 0L

    @Volatile
    private var searching = false

    private val stop = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)

    @Volatile
    private var tickStop = false

    private val lock = Any()
    private val live = ScanLiveState(running = true, stage = str(R.string.scan_stage_preparing), total = BUDGET_SECONDS)

    override val isStopping: Boolean get() = stop.get()

    override val isFinalized: Boolean get() = finalized.get()

    override fun disablePersist() {}

    override fun start() {
        Thread(::run, "warp-finder").start()
        Thread(::tickLoop, "warp-finder-tick").start()
    }

    override fun requestStop() {
        stop.set(true)
        synchronized(lock) { live.stopping = true }
    }

    override fun liveState(): ScanLiveState = synchronized(lock) {
        val state = live.copy()
        state.stopping = stop.get()
        if (searching) state.tested = budgetCursor()
        state
    }

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
            finishRun(completed, error)
        } catch (_: Throwable) {
        }
        tickStop = true
        hooks.finished()
        ScanBinding.stop(sessionId)
    }

    // The budget runs down inside long probe calls too, so a ticker keeps the progress moving. It also reads the core
    // session's counters, which the desktop does not show.
    private fun tickLoop() {
        var shown = Long.MAX_VALUE
        while (!tickStop) {
            sleep(TICK_MS)
            if (tickStop) break
            var changed = false
            ScanBinding.query(sessionId, Long.MAX_VALUE - 1)?.let { counters ->
                synchronized(lock) {
                    if (live.probed != counters.probed || live.probePassed != counters.probePassed ||
                        live.urlTested != counters.urlTested || live.urlPassed != counters.urlPassed
                    ) {
                        live.probed = counters.probed
                        live.probePassed = counters.probePassed
                        live.urlTested = counters.urlTested
                        live.urlPassed = counters.urlPassed
                        changed = true
                    }
                }
            }
            if (searching) {
                val cursor = budgetCursor()
                if (cursor != shown) {
                    shown = cursor
                    changed = true
                }
            }
            if (changed) notifyProgress()
        }
    }

    /** Null when the search completed; otherwise the error, "" for none. */
    private fun execute(): String? {
        clockStartMs = now()
        prepare().let { if (it.isNotEmpty() || stop.get()) return it }
        loadPools().let { if (it.isNotEmpty() || stop.get()) return it }

        // The budget starts here, so the IPv6 check costs none of it.
        clockStartMs = now()
        searching = true
        setTested(budgetCursor(), BUDGET_SECONDS)
        try {
            search().let { if (it.isNotEmpty() || stop.get()) return it }
        } finally {
            searchMs = elapsedMs()
            searching = false
        }
        logOutcome()
        return null
    }

    private fun prepare(): String {
        setStage(str(R.string.scan_stage_preparing))
        pushLog(str(R.string.scan_warp_log_checking_profile))
        carrier = ScanProfiles.warpCarrier(base.outbound)
        val endpoint = ScanProfiles.warpProbeEndpoint(base.outbound)
        if (!endpoint.ok || endpoint.json.isEmpty()) return endpoint.error.ifEmpty { str(R.string.scan_base_warp_build) }
        probeJson = endpoint.json.toCompact()
        ScanTestConfigs.validateBase(base).let { if (it.isNotEmpty()) return it }
        testUrl = DataStore.testUrl
        return ""
    }

    private fun buildPools(entries: List<IpListEntry>): ArrayList<Pool> {
        val masque = carrier == "masque"
        val pools = ArrayList<Pool>()
        for (entry in entries) {
            var cidr = entry.cidr.trim()
            if (!cidr.contains('/')) cidr += if (isIpv6(cidr)) "/128" else "/32"
            val (bytes, prefix) = parseSubnet(cidr) ?: continue
            val port = if (entry.port in 1..65535) entry.port else 0
            if (bytes.size == 16) {
                pools.add(Pool(v6 = true, v6Net = maskV6(bytes, prefix), v6Prefix = prefix, port = port))
                continue
            }
            val mask = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
            val network = v4ToLong(bytes) and mask
            if (prefix > 24) {
                val size = 1L shl (32 - prefix)
                pools.add(
                    Pool(
                        v6 = false, v4First = if (size > 2) network + 1 else network,
                        v4Count = if (size > 2) size - 2 else size, port = port,
                    )
                )
                continue
            }
            // Wider prefixes split into /24s; a very wide one gives a random subset.
            val blocks = 1L shl (24 - prefix)
            val picked = if (blocks <= MAX_POOLS) {
                (0 until blocks).toList()
            } else {
                val chosen = HashSet<Long>()
                while (chosen.size < MAX_POOLS) chosen.add(Random.nextLong(blocks))
                chosen.sorted()
            }
            for (block in picked) {
                // .1 of a MASQUE /24 is a plain CDN edge.
                pools.add(
                    Pool(
                        v6 = false, v4First = network + (block shl 8) + (if (masque) 2 else 1),
                        v4Count = if (masque) 253L else 254L, port = port,
                    )
                )
            }
        }
        if (pools.size > MAX_POOLS) {
            val kept = pools.indices.shuffled().take(MAX_POOLS).sorted()
            return kept.mapTo(ArrayList()) { pools[it] }
        }
        return pools
    }

    private fun loadPools(): String {
        if (seeds.isEmpty()) return str(R.string.scan_error_base_list_empty)
        pools = buildPools(seeds)
        if (pools.isEmpty()) return str(R.string.scan_warp_error_no_range)

        val hasV4 = pools.any { !it.v6 }
        val hasV6 = pools.any { it.v6 }
        if (hasV6 && !networkUp(true)) {
            if (stop.get()) return ""
            if (!hasV4) return str(R.string.scan_warp_error_ipv6_only)
            dropIpv6(str(R.string.scan_warp_log_no_ipv6))
        }
        return ""
    }

    // The profile's own endpoint joins round 1, checked with the settings discovery settled.
    private fun search(): String {
        val seeds = ArrayList<Target>()
        discoverPorts(seeds).let { if (it.isNotEmpty() || stop.get()) return it }
        val own = host(base.outbound.getAddress())
        var port = base.outbound.getPort().toIntOrNull() ?: 0
        if (port <= 0 && isMasque()) port = MASQUE_DEFAULT_PORT
        val seeded = seeds.any { it.address == own }
        if (own.isNotEmpty() && port > 0 && !seeded) seeds.add(0, Target(own, port))
        return sampleRounds(seeds)
    }

    private fun discoverPorts(seeds: MutableList<Target>): String {
        val masque = isMasque()
        val primary = if (masque) WarpPresets.MASQUE_PORTS else WarpPresets.WIREGUARD_PORTS
        // .1 of each /24 answers WireGuard; for MASQUE it is a plain CDN edge.
        val addresses = discoveryAddresses(DISCOVERY_ADDRESSES, !masque)
        if (addresses.isEmpty()) return ""
        fun cross(hosts: List<String>, ports: List<Int>): List<Target> = hosts.flatMap { host -> ports.map { Target(host, it) } }

        setStage(str(R.string.scan_stage_finding_ports))
        pushLog(plural(R.plurals.scan_warp_log_finding_ports, addresses.size))
        val usual = cross(addresses, primary)
        val found = ArrayList<Candidate>()
        probeTargets(usual, DISCOVERY_ATTEMPTS, false, found).let { if (it.isNotEmpty() || stop.get()) return it }

        if (masque) {
            tryHttp2(usual, found)
            if (stop.get()) return ""
        } else {
            // Junk on the usual ports comes before the rarer ports: where plain WireGuard is blocked, it answers within seconds.
            tryJunk(usual, found).let { if (it.isNotEmpty() || stop.get()) return it }
            if (found.isEmpty() && !stopRuleMet()) {
                val extra = WarpPresets.WIREGUARD_ALL_PORTS.filter { it !in primary }
                val rest = cross(addresses.take(FALLBACK_ADDRESSES), extra)
                pushLog(plural(R.plurals.scan_warp_log_more_ports, extra.size))
                probeTargets(rest, DISCOVERY_ATTEMPTS, false, found).let { if (it.isNotEmpty() || stop.get()) return it }
                tryJunk(rest, found).let { if (it.isNotEmpty() || stop.get()) return it }
            }
        }
        if (found.isEmpty()) return ""

        val answers = HashMap<Int, Int>()
        for (candidate in found) answers[candidate.port] = (answers[candidate.port] ?: 0) + 1
        fun rank(port: Int): Int {
            val index = primary.indexOf(port)
            return if (index >= 0) index else primary.size + WarpPresets.WIREGUARD_ALL_PORTS.indexOf(port)
        }
        ports = answers.keys.sortedWith { a, b ->
            val countA = answers[a] ?: 0
            val countB = answers[b] ?: 0
            if (countA != countB) countB.compareTo(countA) else rank(a).compareTo(rank(b))
        }
        pushLog(app.getString(R.string.scan_warp_log_ports, ports.joinToString(", ")))

        // Each address that answered opens round 1, on its best port.
        val seeded = HashSet<String>()
        for (port in ports) {
            for (candidate in found) {
                if (candidate.port != port || candidate.address in seeded) continue
                seeded.add(candidate.address)
                seeds.add(Target(candidate.address, port))
            }
        }
        return ""
    }

    // Only for a profile without AmneziaWG of its own: an answer to Throne's junk switches the run to it.
    private fun tryJunk(targets: List<Target>, found: MutableList<Candidate>): String {
        val wireguard = base.outbound as? WireGuard
        if (found.isNotEmpty() || wireguard == null || wireguard.enable_amnezia || stopRuleMet()) return ""
        val junk = copyOf(base) ?: return ""
        (junk.outbound as? WireGuard)?.let { ScanProfiles.applyWarpJunk(it) } ?: return ""
        junk.putOutbound(junk.outbound)
        val json = probeJsonOf(junk)
        if (json.isEmpty()) return ""
        pushLog(str(R.string.scan_warp_log_trying_junk))
        val plain = probeJson
        probeJson = json
        probeTargets(targets, DISCOVERY_ATTEMPTS, false, found).let { if (it.isNotEmpty() || stop.get()) return it }
        if (found.isEmpty()) {
            probeJson = plain
            return ""
        }
        base = junk
        junkMode = true
        synchronized(lock) { live.warpJunk = true }
        pushLog(str(R.string.scan_warp_log_junk_mode))
        return ""
    }

    // An HTTP/3-only profile nothing answered: with the fallback on, HTTP/2 over TCP may get through where QUIC is blocked.
    private fun tryHttp2(targets: List<Target>, found: MutableList<Candidate>) {
        val masque = base.outbound as? Masque
        if (found.isNotEmpty() || masque == null || masque.http_version == 2 || !masque.disable_version_fallback || stopRuleMet()) {
            return
        }
        // Only 3 turns the HTTP/2 fallback off.
        val copy = copyOf(base) ?: return
        (copy.outbound as? Masque)?.let {
            it.http_version = 0
            it.disable_version_fallback = false
        } ?: return
        copy.putOutbound(copy.outbound)
        val json = probeJsonOf(copy)
        if (json.isEmpty()) return
        pushLog(str(R.string.scan_warp_log_trying_http2))
        val http3 = probeJson
        probeJson = json
        val error = probeTargets(targets, DISCOVERY_ATTEMPTS, false, found)
        if (stop.get()) return
        // A copy that cannot probe here (its ECH fetch needs a running instance) leaves the profile's own outcome.
        if (error.isNotEmpty() || found.isEmpty()) {
            probeJson = http3
            found.clear()
            return
        }
        // The copy turns the HTTP/2 fallback on rather than forcing HTTP/2, so HTTP/3 stays in use wherever it works.
        base = copy
        probeJson = json
        synchronized(lock) { live.warpHttp2 = true }
        pushLog(str(R.string.scan_warp_log_http2_mode))
    }

    private fun sampleRounds(seeds: List<Target>): String {
        var round = 1
        while (true) {
            if (stop.get() || stopRuleMet()) return ""
            val targets = ArrayList<Target>()
            if (round == 1) targets.addAll(seeds)
            targets.addAll(roundTargets())
            if (targets.isEmpty()) return ""
            setStage(str(R.string.scan_stage_searching))
            pushLog(app.resources.getQuantityString(R.plurals.scan_warp_log_round, targets.size, round, targets.size))
            probeTargets(targets, ATTEMPTS, true, null).let { if (it.isNotEmpty()) return it }
            round++
        }
    }

    private fun probeTargets(targets: List<Target>, attempts: Int, confirm: Boolean, winners: MutableList<Candidate>?): String {
        val chunkSize = chunkFor(attempts)
        var offset = 0
        while (offset < targets.size) {
            if (stop.get() || stopRuleMet()) return ""
            var chunk = targets.subList(offset, minOf(offset + chunkSize, targets.size)).toList()
            if (!ipv6Allowed) chunk = chunk.filterNot { isIpv6(it.address) }
            if (chunk.isEmpty()) {
                offset += chunkSize
                continue
            }
            awaitVpn()
            if (stop.get()) return ""
            val batch = probe(chunk, attempts)
            // The same chunk again once the VPN settled.
            if (batch.disturbed) continue
            if (batch.error.isNotEmpty()) return batch.error
            if (batch.aborted) return ""
            guard(batch).let { if (it.isNotEmpty()) return it }
            if (batch.answered > 0) answeredAny = true
            winners?.addAll(batch.winners)
            if (confirm) {
                addPending(batch.winners)
                drainPending().let { if (it.isNotEmpty()) return it }
            }
            offset += chunkSize
        }
        return ""
    }

    private fun probe(targets: List<Target>, attempts: Int): ProbeBatch {
        val batch = ProbeBatch()
        val request = ScanProbeRequest(
            sessionId = sessionId,
            spec = ScanTargetSpec(targets.map { IpListEntry(it.address, it.port) }, portMode = "list", shuffle = false),
            cursor = 0L,
            maxTargets = targets.size,
            concurrency = concurrencyFor(attempts),
            spawnIntervalMs = SPAWN_INTERVAL_MS,
            warp = ScanWarpOptions(probeJson, attempts, HANDSHAKE_TIMEOUT_MS),
        )
        val mark = VpnWindow.mark()
        val response = ScanBinding.probe(request)
        if (!stop.get() && VpnWindow.disturbed(mark)) return batch.apply { disturbed = true }
        if (response.error.isNotEmpty()) {
            if (isStopped(response.error)) stop.set(true)
            if (stop.get()) batch.aborted = true else batch.error = response.error
            return batch
        }
        for (item in response.results) {
            val address = host(item.address).ifEmpty { item.address }
            val entryPort = item.port
            val port = if (item.probePort > 0) item.probePort else entryPort
            if (item.answered > 0 || item.passed) batch.answered++
            when {
                item.passed -> batch.winners.add(Candidate(address, port, entryPort, item.sent, item.answered, item.handshakeMs))
                item.localFailure -> if (isIpv6(address)) batch.v6LocalFailure = true else batch.v4LocalFailure = true
                else -> batch.failures.add(Target(address, entryPort))
            }
        }
        if (response.aborted) {
            if (stop.get()) batch.aborted = true else batch.error = str(R.string.scan_error_session_ended)
        }
        return batch
    }

    private fun guard(batch: ProbeBatch): String {
        // Without a default route every target fails locally, with no lost egress to report.
        if (batch.answered == 0 && batch.v4LocalFailure) return if (stop.get()) "" else str(R.string.scan_error_network_down)
        if (batch.v6LocalFailure && ipv6Allowed) dropIpv6(str(R.string.scan_warp_log_no_ipv6_route))
        if (batch.answered > 0 || batch.failures.isEmpty()) return ""
        val v6Only = batch.failures.all { isIpv6(it.address) }
        if (v6Only) {
            if (ipv6Allowed && !networkUp(true) && !stop.get()) dropIpv6(str(R.string.scan_warp_log_ipv6_unreachable))
        } else if (networkUpAtMs < 0 || elapsedMs() - networkUpAtMs >= NETWORK_REUSE_MS) {
            // Nothing answered: either nothing is here, or the network is gone.
            if (!networkUp(false)) return if (stop.get()) "" else str(R.string.scan_error_network_down)
            networkUpAtMs = elapsedMs()
        }
        return ""
    }

    private fun addPending(winners: List<Candidate>) {
        for (winner in winners) {
            if (winner.address in confirmedAddresses) continue
            val index = pending.indexOfFirst { it.address == winner.address }
            if (index < 0) pending.add(winner) else if (better(winner, pending[index])) pending[index] = winner
        }
    }

    private fun drainPending(): String {
        while (pending.isNotEmpty()) {
            if (stop.get() || stopRuleMet()) return ""
            pending.sortWith(BETTER)
            val batch = ArrayList(pending.subList(0, minOf(DATA_BATCH, pending.size)))
            pending.subList(0, batch.size).clear()
            val confirmed = ArrayList<Candidate>()
            // A check cut short by a pause or an error still keeps what passed before it ended.
            val error = dataCheck(batch, confirmed)
            storeConfirmed(confirmed)
            if (error.isNotEmpty()) return error
            if (stop.get()) return ""
        }
        return ""
    }

    private fun dataCheck(batch: List<Candidate>, confirmed: MutableList<Candidate>): String {
        if (batch.isEmpty()) return ""
        val previous = stage
        try {
            setStage(str(R.string.scan_stage_checking_data))
            pushLog(plural(R.plurals.scan_warp_log_checking_data, batch.size))
            val build = ScanTestConfigs.build(base, batch.map { ScanTestTarget(it.address, it.port) })
            if (build.error.isNotEmpty()) return build.error
            val generated = build.build
            if (generated == null || generated.outboundTags.isEmpty()) return ""
            val labels = generated.outboundTags.map { tag ->
                val index = build.tagToTarget[tag] ?: -1
                if (index in batch.indices) IpListParse.formatTarget(batch[index].address, batch[index].port) else tag
            }
            val request = TestRequest()
            CoreConfig.from(generated).applyTo(request)
            request.useDefaultOutbound = false
            request.url = testUrl
            request.maxConcurrency = batch.size
            request.timeoutMs = DATA_TIMEOUT_MS

            awaitVpn()
            if (stop.get()) return ""
            val mark = VpnWindow.mark()
            // One cold request: the latency stored is the handshake's, so a warm-up only costs time.
            val results = try {
                ScanBinding.urlTest(sessionId, request, labels, warmLatency = false)
            } catch (e: Exception) {
                val coreError = e.readableMessage
                if (isStopped(coreError)) stop.set(true)
                if (stop.get()) return ""
                if (VpnWindow.disturbed(mark)) {
                    pending.addAll(batch)
                    return ""
                }
                return coreError.ifEmpty { str(R.string.scan_error_data_check_no_answer) }
            }

            val reported = HashSet<Int>()
            var aborted = false
            for (item in results) {
                val index = build.tagToTarget[item.outboundTag] ?: -1
                if (index !in batch.indices || index in reported) continue
                reported.add(index)
                if (item.error.isEmpty()) confirmed.add(batch[index]) else if (isAborted(item.error)) aborted = true
            }
            for (index in build.tagToTarget.values) if (index !in reported) aborted = true
            if (!stop.get() && VpnWindow.disturbed(mark)) {
                // The failures prove nothing; they are checked again once the VPN settled.
                batch.filterTo(pending) { candidate -> confirmed.none { it === candidate } }
                return ""
            }
            if (aborted && !stop.get()) return str(R.string.scan_error_data_check_ended)
            return ""
        } finally {
            setStage(previous)
        }
    }

    private fun storeConfirmed(confirmed: List<Candidate>) {
        if (confirmed.isEmpty()) return
        for (candidate in confirmed) {
            if (confirmedAddresses.add(candidate.address)) lastConfirmMs = elapsedMs()
        }
        pushLog(plural(R.plurals.scan_warp_log_confirmed, confirmed.size))
        addHits(confirmed)
    }

    private fun addHits(confirmed: List<Candidate>) {
        for (candidate in confirmed) {
            val index = hits.indexOfFirst { it.address == candidate.address }
            if (index < 0) hits.add(candidate) else if (better(candidate, hits[index])) hits[index] = candidate
        }
        hits.sortWith(BETTER)
        val entries = hits.map(::entryOf)
        synchronized(lock) {
            live.found = entries.size
            live.hits = entries
        }
        hooks.results()
    }

    private fun stopRuleMet(): Boolean {
        if (confirmedAddresses.size >= STOP_AFTER) return true
        val elapsed = elapsedMs()
        if (elapsed >= BUDGET_MS) return true
        // Quiet only once every handshake winner has had its data check.
        return lastConfirmMs >= 0 && pending.isEmpty() && elapsed - lastConfirmMs >= QUIET_MS
    }

    private fun elapsedMs(): Long = now() - clockStartMs

    // Seconds of the budget spent, kept below the total while the search runs.
    private fun budgetCursor(): Long = (elapsedMs() / 1000).coerceIn(0L, BUDGET_SECONDS - 1)

    private fun networkUp(ipv6: Boolean): Boolean {
        val previous = stage
        try {
            setStage(str(R.string.scan_stage_checking_network))
            pushLog(str(R.string.scan_log_checking_network))
            while (true) {
                awaitVpn()
                if (stop.get()) return false
                val mark = VpnWindow.mark()
                val check = ScanBinding.checkNetwork(if (ipv6) NETWORK_CHECK_TARGETS_V6 else NETWORK_CHECK_TARGETS, NETWORK_CHECK_TIMEOUT_MS)
                if (stop.get() || !VpnWindow.disturbed(mark)) return check.up
            }
        } finally {
            setStage(previous)
        }
    }

    private fun dropIpv6(why: String) {
        if (!ipv6Allowed) return
        ipv6Allowed = false
        pools.removeAll { it.v6 }
        pushLog(why)
    }

    private fun discoveryAddresses(count: Int, anchors: Boolean): List<String> {
        val eligible = pools.filterTo(ArrayList()) { it.port <= 0 && !it.exhausted }
        val addresses = ArrayList<String>()
        if (anchors) {
            for (pool in eligible) {
                if (addresses.size >= count) break
                if (pool.v6) continue
                val anchor = formatV4(pool.v4First)
                if (anchor in pool.used) continue
                pool.used.add(anchor)
                addresses.add(anchor)
            }
        }
        // IPv6 prefixes first, so a mixed list tries both families.
        val ordered = eligible.filter { it.v6 } + eligible.filter { !it.v6 }
        var idle = 0
        var i = 0
        while (addresses.size < count && idle < ordered.size) {
            val address = nextAddress(ordered[i % ordered.size])
            i++
            if (address.isEmpty()) {
                idle++
                continue
            }
            idle = 0
            addresses.add(address)
        }
        return addresses
    }

    private fun roundTargets(): List<Target> {
        val perPool = ArrayList<List<Target>>()
        for (pool in pools) {
            if (pool.exhausted || (pool.port <= 0 && ports.isEmpty())) continue
            val picks = ArrayList<Target>()
            for (i in 0 until PER_PREFIX) {
                val address = nextAddress(pool)
                if (address.isEmpty()) break
                picks.add(Target(address, pool.port))
            }
            if (picks.isNotEmpty()) perPool.add(picks)
        }
        // Interleaved across ranges; portless ones rotate through the ports found.
        val targets = ArrayList<Target>()
        for (i in 0 until PER_PREFIX) {
            for (picks in perPool) {
                if (i >= picks.size) continue
                val target = picks[i]
                targets.add(if (target.port > 0) target else Target(target.address, ports[portCursor++ % ports.size]))
            }
        }
        return targets
    }

    private fun logOutcome() {
        if (confirmedAddresses.isNotEmpty()) {
            pushLog(plural(R.plurals.scan_warp_log_found, confirmedAddresses.size))
            return
        }
        pushLog(
            str(
                when {
                    !answeredAny -> R.string.scan_warp_log_no_answer
                    junkMode -> R.string.scan_warp_log_junk_no_data
                    else -> R.string.scan_warp_log_no_data
                }
            )
        )
    }

    private fun finishRun(completed: Boolean, error: String) {
        // A stop ends on what was found too; an error speaks for itself.
        if (!completed && error.isEmpty()) pushLog(plural(R.plurals.scan_warp_log_found, hits.size))
        finalized.set(true)
        synchronized(lock) {
            live.running = false
            live.error = error
            // The seconds the search took, not the whole budget.
            live.tested = minOf(BUDGET_SECONDS, (searchMs + 500) / 1000)
            live.total = BUDGET_SECONDS
        }
    }

    private fun setStage(stage: String) {
        this.stage = stage
        synchronized(lock) {
            if (live.stage == stage) return
            live.stage = stage
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

    /** Waits out a VPN window; the wait moves the clock, so it costs none of the budget. */
    private fun awaitVpn() {
        val previous = stage
        val waited = VpnWindow.awaitSettled(stop) {
            setStage(str(R.string.scan_stage_vpn_wait))
            pushLog(str(R.string.scan_log_vpn_wait))
        }
        if (waited <= 0L) return
        clockStartMs += waited
        setStage(previous)
    }

    private fun isMasque(): Boolean = carrier == "masque"

    private fun concurrencyFor(attempts: Int): Int = when {
        isMasque() -> MASQUE_CONCURRENCY
        attempts == DISCOVERY_ATTEMPTS -> DISCOVERY_CONCURRENCY
        else -> CONCURRENCY
    }

    // Discovery calls hold two waves, so its usual targets go out at once; other calls stay small, so data checks start early.
    private fun chunkFor(attempts: Int): Int = if (attempts == DISCOVERY_ATTEMPTS) 2 * concurrencyFor(attempts) else CHUNK

    companion object {
        // WireGuard probes in flight; discovery mostly waits on silent ports, so it sends more at once.
        private const val CONCURRENCY = 32
        private const val DISCOVERY_CONCURRENCY = 48

        // More MASQUE TLS handshakes at once lose answers.
        private const val MASQUE_CONCURRENCY = 16

        // Small calls, so the stop rule sees results as they arrive.
        private const val CHUNK = 32
        private const val SPAWN_INTERVAL_MS = 2
        private const val HANDSHAKE_TIMEOUT_MS = 1000
        private const val DISCOVERY_ATTEMPTS = 1
        private const val ATTEMPTS = 3
        private const val DISCOVERY_ADDRESSES = 12
        private const val FALLBACK_ADDRESSES = 4

        // The core dials MASQUE on 443 when the profile has no port.
        private const val MASQUE_DEFAULT_PORT = 443
        private const val PER_PREFIX = 16

        // A batch lasts as long as its slowest tunnel, and a failing one takes the whole data check bound.
        private const val DATA_BATCH = 4
        private const val DATA_TIMEOUT_MS = 3000
        private const val STOP_AFTER = 5
        private const val QUIET_MS = 10_000L

        // A search's total; its cursor counts the seconds spent.
        const val BUDGET_SECONDS = 90L
        private const val BUDGET_MS = BUDGET_SECONDS * 1000
        private const val TICK_MS = 500L
        private const val MAX_POOLS = 256
        private const val NETWORK_CHECK_TIMEOUT_MS = 3000
        private const val NETWORK_REUSE_MS = 10_000L

        private val NETWORK_CHECK_TARGETS = listOf(
            "1.1.1.1:443",
            "8.8.8.8:443",
            "9.9.9.9:443",
            "223.5.5.5:443",
            "77.88.8.8:443",
        )

        private val NETWORK_CHECK_TARGETS_V6 = listOf(
            "[2606:4700:4700::1111]:443",
            "[2001:4860:4860::8888]:443",
            "[2620:fe::fe]:443",
        )

        private val sessionCounter = AtomicInteger()

        private val BETTER = Comparator<Candidate> { a, b ->
            when {
                better(a, b) -> -1
                better(b, a) -> 1
                else -> 0
            }
        }

        private fun better(a: Candidate, b: Candidate): Boolean {
            val lossA = maxOf(lossPct(a), 0)
            val lossB = maxOf(lossPct(b), 0)
            if (lossA != lossB) return lossA < lossB
            val rttA = if (a.handshakeMs > 0) a.handshakeMs else Int.MAX_VALUE
            val rttB = if (b.handshakeMs > 0) b.handshakeMs else Int.MAX_VALUE
            return rttA < rttB
        }

        private fun lossPct(candidate: Candidate): Int {
            if (candidate.sent <= 0) return -1
            return (100.0 * maxOf(0, candidate.sent - candidate.answered) / candidate.sent).roundToInt()
        }

        private fun entryOf(candidate: Candidate): IpListEntry =
            IpListEntry(candidate.address, candidate.port, maxOf(candidate.handshakeMs, 0), lossPct(candidate))

        private fun str(id: Int): String = app.getString(id)

        private fun plural(id: Int, count: Int): String = app.resources.getQuantityString(id, count, count)

        private fun now(): Long = SystemClock.elapsedRealtime()

        private fun sleep(ms: Long) {
            try {
                Thread.sleep(ms)
            } catch (_: InterruptedException) {
            }
        }

        private fun isStopped(error: String): Boolean = error.contains("scan stopped")

        private fun isAborted(error: String): Boolean = error.contains("test aborted") || error.contains("context canceled")

        private fun isIpv6(address: String): Boolean = address.contains(':')

        // The canonical host; "" for a range or anything else.
        private fun host(text: String): String {
            val canonical = IpListParse.normalizeCidr(text.trim())
            if (canonical.isEmpty() || !IpListParse.isHostCidr(canonical)) return ""
            return canonical.substringBefore('/')
        }

        // QHostAddress::parseSubnet on a list entry: the address bytes (4 or 16) and the prefix.
        private fun parseSubnet(cidr: String): Pair<ByteArray, Int>? {
            val canonical = IpListParse.normalizeCidr(cidr)
            if (canonical.isEmpty()) return null
            val slash = canonical.indexOf('/')
            // A literal, so no lookup happens.
            val bytes = try {
                InetAddress.getByName(if (slash < 0) canonical else canonical.substring(0, slash)).address
            } catch (_: Exception) {
                return null
            }
            val bits = bytes.size * 8
            val prefix = if (slash < 0) bits else canonical.substring(slash + 1).toIntOrNull() ?: return null
            return if (prefix in 0..bits) bytes to prefix else null
        }

        private fun maskV6(source: ByteArray, prefix: Int): ByteArray {
            val bytes = source.copyOf()
            for (i in 0 until 16) {
                val keep = (prefix - i * 8).coerceIn(0, 8)
                bytes[i] = (bytes[i].toInt() and (0xFF shl (8 - keep)) and 0xFF).toByte()
            }
            return bytes
        }

        private fun v4ToLong(bytes: ByteArray): Long =
            bytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }

        private fun formatV4(value: Long): String =
            "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"

        private fun formatV6(bytes: ByteArray): String {
            val text = (0 until 8).joinToString(":") { i ->
                Integer.toHexString(((bytes[2 * i].toInt() and 0xFF) shl 8) or (bytes[2 * i + 1].toInt() and 0xFF))
            }
            return IpListParse.normalizeCidr(text).ifEmpty { text }
        }

        private fun nextAddress(pool: Pool): String {
            if (pool.exhausted) return ""
            fun take(address: String): String {
                pool.used.add(address)
                return address
            }
            if (pool.v6 && pool.v6Prefix < 120) {
                val bytes = pool.v6Net.copyOf()
                for (i in 0 until 16) {
                    val keep = (pool.v6Prefix - i * 8).coerceIn(0, 8)
                    if (keep == 8) continue
                    val mask = (0xFF shl (8 - keep)) and 0xFF
                    bytes[i] = ((bytes[i].toInt() and mask) or (Random.nextInt(256) and mask.inv() and 0xFF)).toByte()
                }
                return take(formatV6(bytes))
            }
            // At most 256 addresses: drawn without repeats.
            val count = if (pool.v6) 1L shl (128 - pool.v6Prefix) else pool.v4Count
            fun addressAt(offset: Long): String {
                if (!pool.v6) return formatV4(pool.v4First + offset)
                val bytes = pool.v6Net.copyOf()
                bytes[15] = (bytes[15].toInt() or offset.toInt()).toByte()
                return formatV6(bytes)
            }
            if (pool.used.size >= count) {
                pool.exhausted = true
                return ""
            }
            repeat(64) {
                val address = addressAt(Random.nextLong(count))
                if (address !in pool.used) return take(address)
            }
            for (offset in 0 until count) {
                val address = addressAt(offset)
                if (address !in pool.used) return take(address)
            }
            pool.exhausted = true
            return ""
        }

        // warpWorkerCopy: a deep copy keeping the id and group; null when it cannot be made.
        private fun copyOf(base: ProxyEntity): ProxyEntity? {
            val outbound = OutboundFactory.newByType(base.type)
            if (outbound.invalid || !outbound.parseFromJson(base.outbound.exportToJson().copy())) return null
            return ProxyEntity(id = base.id, groupId = base.groupId).putOutbound(outbound)
        }

        // "" when the profile cannot be built.
        private fun probeJsonOf(profile: ProxyEntity): String {
            val endpoint = ScanProfiles.warpProbeEndpoint(profile.outbound)
            return if (endpoint.ok && endpoint.json.isNotEmpty()) endpoint.json.toCompact() else ""
        }
    }
}
