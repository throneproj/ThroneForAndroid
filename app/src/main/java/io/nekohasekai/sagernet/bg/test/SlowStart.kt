package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.outbound.types.Chain

/**
 * Slow starters (Psiphon; the desktop's Tor too) bootstrap a network of their own before their first connection, so a
 * test starts few of them at once (TestRunner.cpp testRunnerForBatches); the core extends their own timeouts.
 */
internal object SlowStart {

    /** kTestRunnerSlowStartConcurrency. */
    const val BATCH_SIZE = 2

    /** kDnsTunnelMaxDepth (DnsTunnel.cpp). */
    private const val MAX_DEPTH = 8

    /** The profiles of [profiles] with a slow-start hop, and the others; order is kept within each. */
    fun split(profiles: List<ProxyEntity>): Pair<List<ProxyEntity>, List<ProxyEntity>> {
        val groups = HashMap<Long, ProxyGroup?>()
        return profiles.partition { profile ->
            val group = groups.getOrPut(profile.groupId) { SagerDatabase.groupDao.getById(profile.groupId) }
            hasSlowStartHop(profile, group)
        }
    }

    /** HasSlowStartHop (DnsTunnel.cpp): a Psiphon profile among its chain hops and its group's front and landing proxies. */
    fun hasSlowStartHop(profile: ProxyEntity, group: ProxyGroup?): Boolean {
        val visited = HashSet<Long>()
        if (reaches(profile, visited, 0)) return true
        if (group == null) return false
        for (id in listOf(group.frontProxyId, group.landingProxyId)) {
            if (id > 0 && reaches(ProfileManager.getProfile(id), visited, 1)) return true
        }
        return false
    }

    // By type first: only a chain's outbound is parsed.
    private fun reaches(profile: ProxyEntity?, visited: MutableSet<Long>, depth: Int): Boolean {
        if (profile == null || depth > MAX_DEPTH || !visited.add(profile.id)) return false
        if (profile.type == "psiphon") return true
        if (profile.type != "chain") return false
        val hops = (profile.outbound as? Chain)?.list ?: return false
        return hops.any { reaches(ProfileManager.getProfile(it), visited, depth + 1) }
    }
}
