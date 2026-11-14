package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.XrayGeoAssets
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.config.AutoSelectorBuild
import kotlinx.coroutines.runBlocking

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    /**
     * Outbound tag -> the profiles whose traffic it carries: the `proxy` exit accounts for the started profile. An
     * auto-selector credits each member's `pool-<i>-0` to itself and the member (generate.cpp:1723-1724), a chain
     * around one to the chain, the selector and the member; under WARP or behind the chain's hops below the selector
     * every byte lands on `proxy`, which then accounts for the started profile and the selector.
     */
    val trafficMap: Map<String, List<ProxyEntity>>
        get() = selectorTraffic ?: mapOf(CoreConfig.TAG_PROXY to listOf(profile))

    @Volatile
    private var selectorTraffic: Map<String, List<ProxyEntity>>? = null

    override fun buildConfig() {
        super.buildConfig()
        selectorTraffic = config.autoSelector?.let(::selectorTrafficMap)
        Logs.d(config.coreConfig)
        if (config.needXray) Logs.d(config.xrayConfig ?: "")
        config.autoSelector?.rejected?.forEach { (id, reason) -> Logs.w("auto selector: member $id left out: $reason") }
    }

    private fun selectorTrafficMap(selector: AutoSelectorBuild): Map<String, List<ProxyEntity>>? {
        val hop = if (selector.selectorId == profile.id) null else SagerDatabase.proxyDao.getById(selector.selectorId)
        if (selector.landsOnProxy) return hop?.let { mapOf(CoreConfig.TAG_PROXY to listOf(profile, it)) }
        val members = SagerDatabase.proxyDao.getEntities(selector.memberIds).associateBy { it.id }
        val map = LinkedHashMap<String, List<ProxyEntity>>()
        // TrafficLooper keeps one item per profile id and reads a tag's speed off its last profile: the member goes last.
        for ((tag, id) in selector.members) map[tag] = listOfNotNull(profile, hop, members[id])
        return map
    }

    override suspend fun init() {
        AutoSelectorRuntime.beforeStart(this)
        super.init()
    }

    override suspend fun onAssetProgress(progress: XrayGeoAssets.Progress?) {
        service?.data?.notification?.postStartProgress(progress?.let(XrayGeoAssets::progressText), progress?.percent ?: -1)
    }

    override fun launch() {
        super.launch() // start box
        AutoSelectorRuntime.onStarted(this)
        runOnDefaultDispatcher {
            looper = service?.let { TrafficLooper(it.data, this) }
            looper?.start()
        }
    }

    override fun close() {
        var closeError: Throwable? = null
        try {
            AutoSelectorRuntime.onClosed(this)
        } catch (error: Throwable) {
            closeError = error
        }
        try {
            super.close()
        } catch (error: Throwable) {
            if (closeError == null) {
                closeError = error
            } else if (closeError !== error) {
                closeError?.addSuppressed(error)
            }
        }
        try {
            runBlocking {
                looper?.stop()
                looper = null
            }
        } catch (error: Throwable) {
            if (closeError == null) {
                closeError = error
            } else if (closeError !== error) {
                closeError?.addSuppressed(error)
            }
        }
        closeError?.let { throw it }
    }
}
