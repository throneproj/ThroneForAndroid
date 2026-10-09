package io.nekohasekai.sagernet.database

import java.util.concurrent.ConcurrentHashMap

/** GroupSort.hpp:3-16. */
enum class GroupSortMethod {
    RAW, BY_TYPE, BY_ADDRESS, BY_NAME, BY_TEST_RESULT, BY_ID, BY_TRAFFIC, BY_SECURITY,

    /** BY_TEST_RESULT pinned to latency, whatever the group's test_sort_by. */
    BY_LATENCY,
}

/** GroupSort.hpp:18-21. */
data class GroupSortAction(
    val method: GroupSortMethod = GroupSortMethod.RAW,
    val descending: Boolean = false,
)

/**
 * Group::SortProfiles (Group.cpp:73-155): a one-shot rewrite of the group's order (`user_order`); no sort mode is
 * kept, only the group's sub-criteria (test_sort_by, traffic_sort_by) that some methods read.
 */
object GroupSort {

    private val running = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Sorts group [groupId] and tells the listeners to reload it. False when a sort of the group is already running
     * (the desktop's "A sort action is already in progress"). Reads the database: call it off the main thread.
     */
    suspend fun sortProfiles(groupId: Long, action: GroupSortAction): Boolean {
        if (!running.add(groupId)) return false
        try {
            if (action.method == GroupSortMethod.RAW || action.method == GroupSortMethod.BY_ID) return true
            val group = GroupRepo.get(groupId) ?: return true
            val sorted = ProfileManager.members(groupId).sortedWith(comparator(action, group))
            ProfileManager.setOrder(groupId, sorted.map { it.id })
            return true
        } finally {
            running.remove(groupId)
        }
    }

    fun comparator(action: GroupSortAction, group: ProxyGroup): Comparator<ProxyEntity> {
        val ascending: Comparator<ProxyEntity> = when (action.method) {
            GroupSortMethod.BY_TYPE -> compareBy { it.outbound.displayType() }
            GroupSortMethod.BY_NAME -> compareBy { it.outbound.name }
            GroupSortMethod.BY_ADDRESS -> compareBy { it.outbound.displayAddress() }
            GroupSortMethod.BY_SECURITY -> {
                val skipCert = DataStore.skipCert
                Comparator<ProxyEntity> { a, b ->
                    val secA = a.outbound.effectiveSecurity(skipCert)
                    val secB = b.outbound.effectiveSecurity(skipCert)
                    if (secA.level != secB.level) secA.level.compareTo(secB.level)
                    else (secA.transport + secA.label).compareTo(secB.transport + secB.label)
                }
            }

            GroupSortMethod.BY_LATENCY -> compareBy { latencyKey(it.latency) }
            GroupSortMethod.BY_TEST_RESULT -> when (TestBy.of(group.testSortBy)) {
                TestBy.LATENCY -> compareBy { latencyKey(it.latency) }
                TestBy.DL_SPEED -> compareBy { bitrateToBps(it.dlSpeed) }
                TestBy.UL_SPEED -> compareBy { bitrateToBps(it.ulSpeed) }
                TestBy.IP_OUT -> compareBy { it.ipOut.orEmpty() }
            }

            GroupSortMethod.BY_TRAFFIC -> when (TrafficBy.of(group.trafficSortBy)) {
                TrafficBy.TOTAL -> compareBy { it.rx + it.tx }
                TrafficBy.DL -> compareBy { it.rx }
                TrafficBy.UL -> compareBy { it.tx }
            }

            GroupSortMethod.RAW, GroupSortMethod.BY_ID -> Comparator { _, _ -> 0 }
        }
        return if (action.descending) ascending.reversed() else ascending
    }

    /**
     * Group.cpp:92-97: measured profiles first, then failures, then untested. A Connect OK tunnel works without a
     * latency, so it leads the failures (the desktop mixes it into them).
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
