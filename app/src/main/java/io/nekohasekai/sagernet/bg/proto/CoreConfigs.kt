package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.config.ChainStepBuild
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.nekohasekai.sagernet.outbound.config.ProfileGroup
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.outbound.config.TestCandidate
import io.nekohasekai.sagernet.route.RouteDns
import io.nekohasekai.sagernet.route.RouteProfile

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the route profile of the profile's group and the rule-set list from
 * RouteManager (main configs only; test configs read only the current profile's own DNS), the landing / front proxy
 * from the profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row read and parsed once per generation, with their groups. */
    private class DatabaseProfiles : ProfileProvider {
        private val rows = HashMap<Long, ProxyEntity?>()
        private val groups = HashMap<Long, ProfileGroup?>()

        private fun row(id: Long): ProxyEntity? = rows.getOrPut(id) { SagerDatabase.proxyDao.getById(id) }

        override fun get(id: Long): Outbound? = row(id)?.outbound

        override fun groupOf(id: Long): ProfileGroup? {
            val groupId = row(id)?.groupId ?: return null
            return groups.getOrPut(groupId) { CoreConfigs.group(groupId) }
        }
    }

    /** [dns]: the routing profile's own DNS, null for the global one; [settings] must be mapped with the same. */
    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
        dns: RouteDns? = null,
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(dns), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets. A config that will run
     * leases its bridge ports to [ports] (ConfigGenerator.build). [route] is the routing profile of the profile's group
     * (RouteIdFor, generate.cpp:2834), and the DNS is that very profile's.
     */
    fun buildMain(
        profile: ProxyEntity,
        deferRuleSets: Boolean = false,
        ports: PortLease? = null,
        route: RouteProfile = RouteManager.routeFor(profile.groupId),
    ): GeneratedConfig {
        val (landing, front, groupName) = groupProxies(profile.groupId)
        val routing = SettingsMapper.routingInput(route)
        val dns = routing.profile?.activeDns()
        val settings = SettingsMapper.generatorSettings(dns).copy(deferRuleSets = deferRuleSets)
        val generated = generator(routing, settings, dns).build(profile.id, landing, front, ports, groupName)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /**
     * One test config for every candidate at once, each under its own group's landing / front proxy, with the DNS of
     * the current routing profile (BuildTestConfig sets ctx.dns); tests mix groups, so it stays the global one.
     */
    fun buildTest(profileIds: List<Long>, ports: PortLease? = null): GeneratedConfig {
        val groups = HashMap<Long, Triple<Long, Long, String>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front, groupName) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front, groupName)
        }
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        return generator(settings = SettingsMapper.generatorSettings(dns), dns = dns).buildTest(candidates, ports)
    }

    /**
     * The chain editor's hop test (BuildChainStepTestConfig): the unsaved [hops], top first, behind group [groupId]'s
     * front / landing proxy, with the DNS of [buildTest]; in :bg the core checks every hop as IsValid does.
     */
    fun buildChainStepTest(hops: List<Long>, groupId: Long, ports: PortLease? = null): ChainStepBuild {
        val (landing, front, _) = groupProxies(groupId)
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        val routing = RoutingInput(
            RoutingInput.DEFAULT.profile, RoutingInput.DEFAULT.catalog, memberCheck = SettingsMapper.memberCheck(),
        )
        return generator(routing, SettingsMapper.generatorSettings(dns), dns).buildChainStepTest(hops, landing, front, ports)
    }

    /** (landing_proxy_id, front_proxy_id, name) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Triple<Long, Long, String> {
        val group = group(groupId) ?: return Triple(-1L, -1L, "")
        return Triple(group.landingProxyId, group.frontProxyId, group.name)
    }

    /** The landing / front proxy (-1 when unset) and name of a group, null when it does not exist. */
    private fun group(groupId: Long): ProfileGroup? {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return null
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return ProfileGroup(landing, front, group.displayName())
    }
}
