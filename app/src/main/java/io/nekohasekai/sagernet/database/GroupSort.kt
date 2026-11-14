package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import java.util.EnumMap
import java.util.concurrent.Callable

/** GroupSort.hpp:3-16. Persisted as ordinals (`groups.sort_method`): append only. */
enum class GroupSortMethod {
    RAW, BY_TYPE, BY_ADDRESS, BY_NAME, BY_TEST_RESULT, BY_ID, BY_TRAFFIC, BY_SECURITY,

    /** BY_TEST_RESULT pinned to latency, whatever the group's test_sort_by. */
    BY_LATENCY;

    companion object {
        /** GroupsRepo.cpp groupFromJson: a value no method has reads as RAW. */
        @JvmStatic
        fun of(value: Int): GroupSortMethod = entries.getOrNull(value) ?: RAW
    }
}

/** GroupSort.hpp:19-24. */
data class GroupSortAction(
    val method: GroupSortMethod = GroupSortMethod.RAW,
    val descending: Boolean = false,
)

/** Group.h SortInput: what changed under a group, so only a kept sort that reads it runs again. */
enum class SortInput { MEMBERSHIP, CONFIG, TEST_RESULT, TRAFFIC }

/**
 * The kept group sort of Group.cpp / GroupsRepo.cpp (6906912f): a group keeps [ProxyGroup.sort], re-applied when
 * what it reads changes. Changes are queued with [resortLater] / [repositionLater] and sorted by one background
 * worker, one group at a time, in the process that queued them; the new order is written as `user_order`, which
 * the lists of both processes reload.
 */
object GroupSort {

    private const val SORT_ATTEMPTS = 3

    /** About how many rows of a bulk read cost as much as one single-row query. */
    private const val ROWS_PER_QUERY = 32

    private enum class Commit { MOVED, UNCHANGED, STALE }

    /** Group.cpp sortReads. */
    fun reads(method: GroupSortMethod, input: SortInput): Boolean = when (method) {
        GroupSortMethod.RAW, GroupSortMethod.BY_ID -> false
        GroupSortMethod.BY_TEST_RESULT, GroupSortMethod.BY_LATENCY ->
            input == SortInput.MEMBERSHIP || input == SortInput.TEST_RESULT

        GroupSortMethod.BY_TRAFFIC -> input == SortInput.MEMBERSHIP || input == SortInput.TRAFFIC
        else -> input == SortInput.MEMBERSHIP || input == SortInput.CONFIG
    }

    /** Group::SetSort + Save: the group keeps [action] from now on; RAW is the manual order. */
    suspend fun setSort(groupId: Long, action: GroupSortAction) {
        val descending = action.method != GroupSortMethod.RAW && action.descending
        if (SagerDatabase.groupDao.setSort(groupId, action.method.ordinal, descending) > 0) GroupRepo.postUpdate(groupId)
    }

    /**
     * Group::SortProfiles: sorts by [action] now and keeps the group sorted that way. False only when the group is
     * gone (the desktop no longer refuses a sort while another runs). Reads the database: call it off the main thread.
     */
    suspend fun sortProfiles(groupId: Long, action: GroupSortAction): Boolean {
        if (GroupRepo.get(groupId) == null) return false
        setSort(groupId, action)
        if (resort(groupId)) GroupRepo.postReload(groupId)
        return true
    }

    // ------------------------------------------------------------------------------------------------ queue

    private class PendingSort {
        var full = false
        val changed = EnumMap<SortInput, HashSet<Long>>(SortInput::class.java)
    }

    private val queueLock = Any()
    private val pendingSorts = LinkedHashMap<Long, PendingSort>()

    /** Changed profiles of groups the worker still has to look up. */
    private val pendingProfiles = EnumMap<SortInput, HashSet<Long>>(SortInput::class.java)
    private var workerRunning = false

    /** ResortLater: for changes that can move any row of group [groupId] (a refresh, an import). */
    fun resortLater(groupId: Long) {
        if (groupId <= 0L) return
        enqueue { pendingSorts.getOrPut(groupId) { PendingSort() }.full = true }
    }

    /** RepositionLater: a few changed rows per group; only groups whose sort reads [input] are sorted. */
    fun repositionLater(changedByGroup: Map<Long, Collection<Long>>, input: SortInput) {
        val changed = changedByGroup.filter { it.key > 0L && it.value.isNotEmpty() }
        if (changed.isEmpty()) return
        enqueue {
            for ((groupId, ids) in changed) {
                val pending = pendingSorts.getOrPut(groupId) { PendingSort() }
                if (!pending.full) pending.changed.getOrPut(input) { HashSet() }.addAll(ids)
            }
        }
    }

    /** RepositionLater(profiles) for profiles whose group the caller does not know. */
    fun repositionLater(profileIds: Collection<Long>, input: SortInput) {
        if (profileIds.isEmpty()) return
        enqueue { pendingProfiles.getOrPut(input) { HashSet() }.addAll(profileIds) }
    }

    /** Requests landing while a sort runs merge into one job per group. */
    private inline fun enqueue(add: () -> Unit) {
        synchronized(queueLock) {
            add()
            if (workerRunning) return
            workerRunning = true
        }
        runOnIoDispatcher { drain() }
    }

    private suspend fun drain() {
        while (true) {
            val (batch, loose) = synchronized(queueLock) {
                if (pendingSorts.isEmpty() && pendingProfiles.isEmpty()) {
                    workerRunning = false
                    return
                }
                val sorts = LinkedHashMap(pendingSorts)
                val profiles: Map<SortInput, Set<Long>> = EnumMap(pendingProfiles)
                pendingSorts.clear()
                pendingProfiles.clear()
                sorts to profiles
            }
            try {
                for (groupId in sortBatch(batch, loose)) GroupRepo.postReload(groupId)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    /** drainSorts: the groups whose order moved. */
    private fun sortBatch(batch: LinkedHashMap<Long, PendingSort>, loose: Map<SortInput, Set<Long>>): List<Long> {
        for ((input, ids) in loose) {
            for (profile in withoutData(ids)) {
                val pending = batch.getOrPut(profile.groupId) { PendingSort() }
                if (!pending.full) pending.changed.getOrPut(input) { HashSet() }.add(profile.id)
            }
        }
        val moved = ArrayList<Long>()
        for ((groupId, pending) in batch) {
            val group = GroupRepo.get(groupId) ?: continue
            val reordered = try {
                if (pending.full) {
                    resort(groupId)
                } else {
                    val changed = HashSet<Long>()
                    for ((input, ids) in pending.changed) if (group.sortReads(input)) changed.addAll(ids)
                    reposition(groupId, changed)
                }
            } catch (e: Exception) {
                Logs.w(e)
                false
            }
            if (reordered) moved.add(groupId)
        }
        return moved
    }

    // ------------------------------------------------------------------------------------------------ sorting

    /** Group::Resort: re-applies the kept sort to the whole group; true when the order moved. */
    fun resort(groupId: Long): Boolean {
        repeat(SORT_ATTEMPTS) {
            val group = GroupRepo.get(groupId) ?: return false
            if (!group.keepsSorted()) return false
            val action = group.sort
            val pins = group.pinnedJson
            val snapshot = ProfileManager.memberIds(groupId)
            val head = pinnedHead(snapshot, pins)
            val keys = SortKeys(SortRule(group, action))
            keys.loadGroup(groupId)
            keys.load(snapshot.subList(head, snapshot.size))
            // Stable, so a re-sort leaves rows with equal keys where they were.
            val order = snapshot.subList(0, head) + snapshot.subList(head, snapshot.size).sortedWith(keys)
            when (commit(groupId, snapshot, pins, action, order)) {
                Commit.MOVED -> return true
                Commit.UNCHANGED -> return false
                Commit.STALE -> Unit
            }
        }
        return false
    }

    /**
     * Group::Reposition: moves just the [changed] profiles into place; the rest must still be sorted. Falls back to
     * [resort] when many changed. True when the order moved.
     */
    fun reposition(groupId: Long, changed: Collection<Long>): Boolean {
        if (changed.isEmpty()) return false
        val changedSet = changed.toHashSet()
        repeat(SORT_ATTEMPTS) {
            val group = GroupRepo.get(groupId) ?: return false
            if (!group.keepsSorted()) return false
            val action = group.sort
            val pins = group.pinnedJson
            val snapshot = ProfileManager.memberIds(groupId)
            val count = snapshot.size
            // Pinned rows head the list and stay out of the sort, changed or not.
            val head = pinnedHead(snapshot, pins)
            val positions = (head until count).filter { snapshot[it] in changedSet }
            if (positions.isEmpty()) return false
            // A full sort loads each row once, a reinsertion about log2(n) rows one by one.
            val sorted = count - head
            val probes = Int.SIZE_BITS - sorted.countLeadingZeroBits() + 4
            if (positions.size * probes >= sorted) return resort(groupId)

            val rule = SortRule(group, action)
            val keys = SortKeys(rule)
            val around = ArrayList<Long>()
            for (i in positions) {
                around.add(snapshot[i])
                if (i > head) around.add(snapshot[i - 1])
                if (i + 1 < count) around.add(snapshot[i + 1])
            }
            keys.load(around)
            // The rest was sorted before, so the whole list still is when every changed row fits its neighbours.
            val inPlace = positions.all { i ->
                (i == head || !keys.less(snapshot[i], snapshot[i - 1])) &&
                    (i + 1 == count || !keys.less(snapshot[i + 1], snapshot[i]))
            }
            if (inPlace) return false
            if (positions.size * probes * ROWS_PER_QUERY >= sorted) keys.loadGroup(groupId)

            // Lift every changed row, not just the misplaced ones: a fitting row's neighbour may be leaving too.
            val order = ArrayList<Long>(count)
            order.addAll(snapshot.subList(0, head))
            for (i in head until count) if (snapshot[i] !in changedSet) order.add(snapshot[i])
            for (i in positions) {
                val id = snapshot[i]
                val key = keys.get(id)
                // upper_bound: after equal keys, where a stable sort leaves a row that came last.
                var low = head
                var high = order.size
                while (low < high) {
                    val mid = (low + high) ushr 1
                    if (rule.less(key, keys.get(order[mid]))) high = mid else low = mid + 1
                }
                order.add(low, id)
            }
            when (commit(groupId, snapshot, pins, action, order)) {
                Commit.MOVED -> return true
                Commit.UNCHANGED -> return false
                Commit.STALE -> Unit
            }
        }
        return false
    }

    /** How many rows head [snapshot] as pins: the leading run of pinned ids, which is every pin once normalized. */
    private fun pinnedHead(snapshot: List<Long>, pinnedJson: String): Int {
        val pins = ProxyGroup.parsePinned(pinnedJson).toHashSet()
        if (pins.isEmpty()) return 0
        var head = 0
        while (head < snapshot.size && snapshot[head] in pins) head++
        return head
    }

    /** The keys were read outside the transaction, so a change that landed meanwhile means sorting again. */
    private fun commit(
        groupId: Long, snapshot: List<Long>, pins: String, action: GroupSortAction, order: List<Long>,
    ): Commit =
        SagerDatabase.instance.runInTransaction(Callable {
            val group = SagerDatabase.groupDao.getById(groupId) ?: return@Callable Commit.UNCHANGED
            if (group.sort != action || group.pinnedJson != pins) return@Callable Commit.STALE
            val current = SagerDatabase.proxyDao.getPositionsByGroup(groupId)
            if (current.size != snapshot.size || current.indices.any { current[it].id != snapshot[it] }) {
                return@Callable Commit.STALE
            }
            if (order == snapshot) return@Callable Commit.UNCHANGED
            ProfileManager.writeOrder(current, order)
            Commit.MOVED
        })

    /** Group.cpp SortKey: fields a method leaves unset stay equal, so one comparison serves every method. */
    private class SortKey(
        val missing: Boolean = false,
        val level: Int = 0,
        val number: Double = 0.0,
        val text: String = "",
    )

    /** Group.cpp SortRule. */
    private class SortRule(group: ProxyGroup, action: GroupSortAction) {
        private val descending = action.descending
        private val testBy = TestBy.of(group.testSortBy)
        private val trafficBy = TrafficBy.of(group.trafficSortBy)

        // Android has no show_config_security switch: the group's type_sort_by alone picks Type or Security.
        private val method = when (action.method) {
            GroupSortMethod.BY_TYPE, GroupSortMethod.BY_SECURITY ->
                if (TypeBy.of(group.typeSortBy) == TypeBy.BY_SECURITY) GroupSortMethod.BY_SECURITY else GroupSortMethod.BY_TYPE

            else -> action.method
        }
        private val byLatency = method == GroupSortMethod.BY_LATENCY ||
            (method == GroupSortMethod.BY_TEST_RESULT && testBy == TestBy.LATENCY)
        private val skipCert = if (method == GroupSortMethod.BY_SECURITY) DataStore.skipCert else false

        /** The key reads plain columns only, never the outbound, so rows without their data are enough. */
        val readsColumns = byLatency || method == GroupSortMethod.BY_TEST_RESULT || method == GroupSortMethod.BY_TRAFFIC

        fun keyOf(profile: ProxyEntity?): SortKey {
            if (profile == null) return SortKey(missing = true)
            return when {
                method == GroupSortMethod.BY_TYPE -> SortKey(text = profile.outbound.displayType())
                method == GroupSortMethod.BY_NAME -> SortKey(text = profile.outbound.name)
                method == GroupSortMethod.BY_ADDRESS -> SortKey(text = profile.outbound.displayAddress())
                method == GroupSortMethod.BY_SECURITY -> profile.outbound.effectiveSecurity(skipCert).let {
                    SortKey(level = it.level.ordinal, text = it.transport + it.label)
                }

                byLatency -> SortKey(number = latencyKey(profile.latency).toDouble())
                method == GroupSortMethod.BY_TEST_RESULT -> when (testBy) {
                    TestBy.DL_SPEED -> SortKey(number = bitrateToBps(profile.dlSpeed))
                    TestBy.UL_SPEED -> SortKey(number = bitrateToBps(profile.ulSpeed))
                    else -> SortKey(text = profile.ipOut.orEmpty())
                }

                method == GroupSortMethod.BY_TRAFFIC -> when (trafficBy) {
                    TrafficBy.DL -> SortKey(number = profile.rx.toDouble())
                    TrafficBy.UL -> SortKey(number = profile.tx.toDouble())
                    TrafficBy.TOTAL -> SortKey(number = (profile.rx + profile.tx).toDouble())
                }

                else -> SortKey()
            }
        }

        /** A profile that cannot be loaded goes last in either direction. */
        fun less(a: SortKey, b: SortKey): Boolean {
            if (a.missing != b.missing) return b.missing
            return if (descending) ascending(b, a) else ascending(a, b)
        }

        private fun ascending(a: SortKey, b: SortKey): Boolean {
            if (a.level != b.level) return a.level < b.level
            if (a.number != b.number) return a.number < b.number
            return a.text < b.text
        }
    }

    /** Group.cpp SortKeys: the keys of the profiles loaded so far. */
    private class SortKeys(private val rule: SortRule) : Comparator<Long> {
        private val keys = HashMap<Long, SortKey>()

        /** Every member's key from one query, when the keys need no outbound; otherwise rows load as they are read. */
        fun loadGroup(groupId: Long) {
            if (!rule.readsColumns) return
            for (profile in SagerDatabase.proxyDao.getWithoutDataByGroup(groupId)) {
                keys.getOrPut(profile.id) { rule.keyOf(profile) }
            }
        }

        fun load(ids: List<Long>) {
            val absent = ids.filter { it !in keys }.distinct()
            if (absent.isEmpty()) return
            val rows = if (rule.readsColumns) withoutData(absent) else ProfileManager.getProfiles(absent)
            for (profile in rows) keys[profile.id] = rule.keyOf(profile)
            for (id in absent) if (id !in keys) keys[id] = rule.keyOf(null)
        }

        fun get(id: Long): SortKey {
            load(listOf(id))
            return keys.getValue(id)
        }

        fun less(a: Long, b: Long): Boolean = rule.less(keys.getValue(a), keys.getValue(b))

        override fun compare(a: Long, b: Long): Int = when {
            less(a, b) -> -1
            less(b, a) -> 1
            else -> 0
        }
    }

    /** The rows of [ids] without their data: their group, test results and traffic. */
    private fun withoutData(ids: Collection<Long>): List<ProxyEntity> =
        ids.toList().chunked(ProfileManager.CHUNK).flatMap { SagerDatabase.proxyDao.getWithoutData(it) }

    /**
     * Group.cpp latencyForSort: measured profiles first, then failures, then untested. A Connect OK tunnel works
     * without a latency, so it leads the failures (the desktop mixes it into them).
     */
    @JvmStatic
    fun latencyKey(latency: Int): Int = when {
        latency == 0 -> 100000
        latency == ProxyEntity.LATENCY_CONNECT_ONLY -> 99998
        latency < 0 -> 99999
        else -> latency
    }

    /** Group.cpp:55-71: "12.3Mbps" as bits per second, "N/A" = -1, anything else 0. */
    @JvmStatic
    fun bitrateToBps(text: String?): Double {
        val str = text.orEmpty()
        fun value(suffix: Int) = str.substring(0, str.length - suffix).trim().toDoubleOrNull() ?: 0.0
        return when {
            str.endsWith("Gbps", ignoreCase = true) -> value(4) * 1e9
            str.endsWith("Mbps", ignoreCase = true) -> value(4) * 1e6
            str.endsWith("Kbps", ignoreCase = true) -> value(4) * 1e3
            str == ProxyEntity.SPEED_NA -> -1.0
            else -> 0.0
        }
    }
}
