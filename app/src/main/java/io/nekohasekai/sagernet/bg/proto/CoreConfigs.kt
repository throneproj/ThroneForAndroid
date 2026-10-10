package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.outbound.config.TestCandidate

/**
 * The config generator wired to the app: profiles come from the profile table, the settings and the build-time
 * globals from DataStore through [SettingsMapper], the current route profile and the rule-set list from
 * RouteManager (main configs only; test configs never read them), the landing / front proxy from the profile's group.
 */
object CoreConfigs {

    /** Stored profiles by id, each row parsed once per generation. */
    private class DatabaseProfiles : ProfileProvider {
        private val cache = HashMap<Long, Outbound?>()
        override fun get(id: Long): Outbound? = cache.getOrPut(id) { SagerDatabase.proxyDao.getById(id)?.outbound }
    }

    fun generator(
        routing: RoutingInput = RoutingInput.DEFAULT,
        settings: GeneratorSettings = SettingsMapper.generatorSettings(),
    ): ConfigGenerator = ConfigGenerator(DatabaseProfiles(), settings, SettingsMapper.buildContext(), routing)

    /**
     * The main config of [profile]; throws with the generator's message when it cannot be built. [deferRuleSets]
     * is GeneratorSettings.deferRuleSets, for a start the user let go without its rule-sets.
     */
    fun buildMain(profile: ProxyEntity, deferRuleSets: Boolean = false): GeneratedConfig {
        val (landing, front) = groupProxies(profile.groupId)
        val settings = SettingsMapper.generatorSettings().copy(deferRuleSets = deferRuleSets)
        val generated = generator(SettingsMapper.routingInput(), settings).build(profile.id, landing, front)
        if (!generated.ok) error(generated.error ?: "config generation failed")
        return generated
    }

    /** One test config for every candidate at once, each under its own group's landing / front proxy. */
    fun buildTest(profileIds: List<Long>): GeneratedConfig {
        val groups = HashMap<Long, Pair<Long, Long>>()
        val candidates = profileIds.map { id ->
            val groupId = SagerDatabase.proxyDao.getById(id)?.groupId ?: -1L
            val (landing, front) = groups.getOrPut(groupId) { groupProxies(groupId) }
            TestCandidate(id, landing, front)
        }
        return generator().buildTest(candidates)
    }

    /** (landing_proxy_id, front_proxy_id) of a group, -1 when unset (any id <= 0 is "none"). */
    private fun groupProxies(groupId: Long): Pair<Long, Long> {
        val group: ProxyGroup = (if (groupId > 0) SagerDatabase.groupDao.getById(groupId) else null) ?: return -1L to -1L
        val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
        val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
        return landing to front
    }
}
