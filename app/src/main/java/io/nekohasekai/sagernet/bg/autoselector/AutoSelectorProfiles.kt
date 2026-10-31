package io.nekohasekai.sagernet.bg.autoselector

import io.nekohasekai.sagernet.bg.test.TestEngine
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlanner
import io.nekohasekai.sagernet.outbound.config.SelectorMember
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Auto-selector profiles in storage: load / save, and the ranking of mainwindow_autoselector.cpp. */
object AutoSelectorProfiles {

    const val TYPE = "autoselector"

    /** Held by every :bg writer that loads a selector, changes it and saves it whole, so none undoes another's change. */
    val writes = Mutex()

    /** A fresh parse of the stored selector [id]; null when it is gone or not a selector. */
    fun load(id: Long): AutoSelector? = ProfileManager.getProfile(id)?.outbound as? AutoSelector

    /** Writes the selector's JSON only, so traffic and test results written meanwhile stay. */
    fun save(id: Long, selector: AutoSelector) {
        SagerDatabase.proxyDao.updateOutbound(id, selector.type, selector.name, selector.exportToJson().toCompact())
    }

    /** ProfilesRepo::GetProfileIdsByType("autoselector"). */
    fun ids(): List<Long> {
        val ids = ArrayList<Long>()
        SagerDatabase.instance.query("SELECT `id` FROM `${ProxyEntity.TABLE}` WHERE `type` = ?", arrayOf(TYPE)).use {
            while (it.moveToNext()) ids.add(it.getLong(0))
        }
        return ids
    }

    /** The ids among [ids] that still exist. */
    fun existing(ids: Collection<Long>): Set<Long> =
        if (ids.isEmpty()) emptySet() else ProfileManager.getProfiles(ids.distinct()).mapTo(HashSet()) { it.id }

    /**
     * AutoSelectorMonitor::PersistHealth (AutoSelectorMonitor.cpp:104-135): a probed member's average becomes its
     * profile's latency, a dead one's -1 with the core's last error as the test error; softer states are inconclusive
     * and keep their result. Only changed latencies are written, and only the latency result: traffic and the other
     * test columns stay; kept sorts that read test results move the changed ones. [profileIds] maps the members'
     * tags to profiles.
     */
    fun saveHealth(profileIds: Map<String, Long>, members: List<AutoSelectorStatus.Member>) {
        val at = System.currentTimeMillis() / 1000
        val dao = SagerDatabase.proxyDao
        val dirty = ArrayList<Long>()
        SagerDatabase.instance.runInTransaction {
            for (member in members) {
                val id = profileIds[member.tag] ?: continue
                if (member.probes == 0) continue
                val written = when {
                    member.isUsable && member.averageMs > 0 -> dao.updateLatency(id, member.averageMs, at, null)
                    member.isDead -> dao.updateLatency(id, -1, at, member.lastError.ifEmpty { null })
                    else -> 0
                }
                if (written > 0) dirty.add(id)
            }
        }
        GroupSort.repositionLater(dirty, SortInput.TEST_RESULT)
    }

    /** profile_start (mainwindow_profile_lifecycle.cpp:206-221): the plan succeeds and wants measurements first. */
    fun needsRanking(id: Long): Boolean {
        val selector = load(id) ?: return false
        val plan = AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(id, selector)
        return plan.ok && plan.needsRanking
    }

    /**
     * AutoSelectorController::rank (AutoSelectorController.cpp:121-190): URL-tests the candidates without a fresh
     * result plus [stale] (waiting for any running test session), then reranks and saves `pool` / `pool_ranked_at`.
     * [onMeasuring] runs before the tests with their count. With [startEarly], once enough candidates work
     * ([AutoSelectorPlanner.startQuorum]) the pool is reranked with the stale members not re-measured yet as untested
     * and [startEarly] runs while the tests go on; the full rerank is then the caller's ([rerankChanges]). Returns
     * whether it started early.
     */
    suspend fun rank(
        id: Long,
        stale: Collection<Long> = emptyList(),
        onMeasuring: suspend (Int) -> Unit = {},
        startEarly: (suspend () -> Unit)? = null,
    ): Boolean {
        val sweep = withContext(Dispatchers.IO) {
            val selector = load(id) ?: return@withContext null
            // Stale members do not count as working until this sweep re-measures them.
            val (needed, working) = AutoSelectorPlanner(SettingsMapper.selectorStore()).unmeasuredCandidates(id, selector, stale)
            Sweep(selector, needed, working, if (startEarly != null) AutoSelectorPlanner.startQuorum(selector) else 0)
        } ?: return false
        if (sweep.needed.isEmpty()) {
            val ranked = withContext(Dispatchers.IO) { writes.withLock { rerank(id) } } ?: return false
            Logs.i("[Auto selector] Reusing existing test results; ranked ${ranked.size} profiles.")
            return false
        }
        Logs.i("[Auto selector] Measuring ${sweep.needed.size} not-yet-tested profiles...")
        onMeasuring(sweep.needed.size)
        val startedEarly = coroutineScope {
            val early = launch {
                sweep.quorumMet.await()
                val (found, remaining, untrusted) = sweep.takeEarlyStart(stale) ?: return@launch
                // The early build takes the working members first; the core probes the unmeasured rest itself.
                withContext(Dispatchers.IO) { writes.withLock { rerank(id, untrusted) } }
                Logs.i(
                    "[Auto selector] $found working profiles found; starting now while the remaining $remaining are " +
                        "still being measured."
                )
                startEarly?.invoke()
            }
            sweep.checkQuorum()
            TestEngine.queueUrlTests(sweep.needed, sweep::onResult)
            // An early start the sweep's end overtook is moot: the full ranking is ready now.
            val started = sweep.end()
            if (started) early.join() else early.cancel()
            started
        }
        if (startedEarly) return true
        val ranked = withContext(Dispatchers.IO) { writes.withLock { rerank(id) } } ?: return false
        Logs.i("[Auto selector] Ranked ${ranked.size} profiles.")
        return false
    }

    /**
     * RerankAutoSelectorPool: ranks the selector's members, [untrusted] as untested, and saves `pool` /
     * `pool_ranked_at`. Null when the selector is gone. Callers hold [writes].
     */
    fun rerank(id: Long, untrusted: Collection<Long> = emptyList()): List<Long>? {
        // Re-read: the selector may have been saved while the tests ran, and the store must see the new results.
        val selector = load(id) ?: return null
        val ranked = AutoSelectorPlanner(SettingsMapper.selectorStore()).rerank(id, selector, untrusted)
        save(id, selector)
        return ranked
    }

    /**
     * finishEarlySweep's rerank (AutoSelectorController.cpp:192-216) once every result is in: saves the full ranking
     * and tells whether it changes the build of [built], the members running now (AutoSelectorBuildChanged); false
     * without them. Callers hold [writes].
     */
    fun rerankChanges(id: Long, built: Collection<Long>?): Boolean {
        val selector = load(id) ?: return false
        val planner = AutoSelectorPlanner(SettingsMapper.selectorStore())
        val ranked = planner.rerank(id, selector)
        save(id, selector)
        Logs.i("[Auto selector] Ranked ${ranked.size} profiles.")
        return built != null && planner.buildChanged(id, selector, built)
    }

    /**
     * One ranking sweep's results so far (AutoSelectorSweep). [onResult] runs on test threads, for a profile possibly
     * twice (a failure, then its Connect OK verdict), and may still run after [end].
     */
    private class Sweep(
        private val selector: AutoSelector,
        val needed: List<Long>,
        private val workingBefore: Int,
        /** 0 = never start early. */
        private val quorum: Int,
    ) {
        private val measured = HashSet<Long>()
        private val working = HashSet<Long>()
        private var ended = false
        private var startedEarly = false

        /** Completes once [quorum] candidates work. */
        val quorumMet = CompletableDeferred<Unit>()

        fun onResult(profile: ProxyEntity) {
            val member = SelectorMember(profile.id, profile.outbound, profile.latency, profile.latencyAt, profile.testCountry.orEmpty())
            val works = AutoSelectorPlanner.memberWorks(member, selector, System.currentTimeMillis() / 1000)
            synchronized(this) {
                measured.add(profile.id)
                if (works) working.add(profile.id) else working.remove(profile.id)
            }
            checkQuorum()
        }

        fun checkQuorum() {
            if (synchronized(this) { quorum > 0 && workingBefore + working.size >= quorum }) quorumMet.complete(Unit)
        }

        /**
         * Takes the early start unless the sweep ended first: the working count, how many are left to measure, and
         * the [stale] members not re-measured yet.
         */
        fun takeEarlyStart(stale: Collection<Long>): Triple<Int, Int, List<Long>>? {
            synchronized(this) {
                if (ended) return null
                startedEarly = true
                return Triple(workingBefore + working.size, maxOf(0, needed.size - measured.size), stale.filter { it !in measured })
            }
        }

        /** The tests are over; whether the selector started early. */
        fun end(): Boolean = synchronized(this) {
            ended = true
            startedEarly
        }
    }
}
