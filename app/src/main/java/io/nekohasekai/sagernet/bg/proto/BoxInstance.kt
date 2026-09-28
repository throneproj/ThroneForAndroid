package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.SingBoxDashboard
import io.nekohasekai.sagernet.bg.XrayGeoAssets
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isLoopbackPortFree
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.outbound.config.AutoSelectorBuild
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.StartOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

abstract class BoxInstance(
    val profile: ProxyEntity
) : AbstractInstance {

    lateinit var config: GeneratedConfig
    lateinit var core: CoreConfig
    lateinit var box: Instance

    val boxOrNull: Instance? get() = if (::box.isInitialized) box else null

    fun isInitialized(): Boolean {
        return ::config.isInitialized && ::box.isInitialized
    }

    protected open fun buildConfig() {
        // random_inbound_port: a fresh port per start, saved so the app's own requests reach it (mainwindow_setup.cpp:193-196).
        if (DataStore.randomInboundPort) DataStore.inboundSocksPort = mkPort()
        // The dashboard is on by default, so another app holding its port must not fail the start: it moves, and the
        // dashboard screen follows the saved port.
        if (DataStore.apiDashboardEnabled && !isLoopbackPortFree(DataStore.coreBoxApiPort)) {
            val port = mkPort()
            Logs.w("sing-box API port ${DataStore.coreBoxApiPort} is in use, the dashboard moves to $port")
            DataStore.coreBoxApiPort = port
        }
        config = CoreConfigs.buildMain(profile)
    }

    protected open suspend fun loadConfig() {
        box = Mobile.newInstance(CoreRuntime.platform, core.toStartOptions(config.autoSelector))
    }

    open suspend fun init() {
        buildConfig()
        core = CoreConfig.from(config, listOf(CoreConfig.TAG_PROXY))
        CoreRuntime.applyLogLevel(config.coreConfig)
        ensureXrayAssets()
        // Before the start: the core downloads the dashboard itself when it finds the dir empty.
        if (DataStore.apiDashboardEnabled) withContext(Dispatchers.IO) { SingBoxDashboard.install() }
        loadConfig()
    }

    /** The geo asset downloads the start waits for; null once they are over. */
    protected open suspend fun onAssetProgress(progress: XrayGeoAssets.Progress?) {}

    /**
     * Fetches the data files the Xray configs about to start read (geoip: / geosite: rules) and that are missing;
     * throws [XrayGeoAssets.DownloadException] naming what could not be had.
     */
    private suspend fun ensureXrayAssets() {
        val configs = listOfNotNull(config.xrayConfig) + config.xrayFullConfigs
        if (configs.isEmpty()) return
        val missing = withContext(Dispatchers.IO) { XrayGeoAssets.missing(XrayGeoAssets.needed(configs)) }
        if (missing.isEmpty()) return
        val failures = try {
            XrayGeoAssets.ensure(missing) { onAssetProgress(it) }
        } finally {
            withContext(NonCancellable) { runCatching { onAssetProgress(null) } }
        }
        if (failures.isNotEmpty()) {
            throw XrayGeoAssets.DownloadException(
                failures.values.joinToString("\n"),
                fetchable = failures.keys.any { it in XrayGeoAssets.FILES },
            )
        }
    }

    override fun launch() {
        try {
            box.start()
        } catch (error: Throwable) {
            Logs.w("box start failed for profile ${profile.id}: ${error.message}")
            box.localDNSFailure()?.let { throw LocalDnsFailedException(it.servers, error) }
            throw error
        }
        CoreRuntime.attachRunning(box, profile.id)
    }

    override fun close() {
        boxOrNull?.let(CoreRuntime::detachRunning)
        boxOrNull?.close()
    }

}

/** A start that failed on the local DNS server; [servers] are the network's DNS servers it asked, empty for Android's resolver. */
class LocalDnsFailedException(val servers: String, cause: Throwable) : Exception(cause.message, cause)

internal fun CoreConfig.toStartOptions(autoSelector: AutoSelectorBuild? = null): StartOptions = StartOptions().apply {
    coreConfig = this@toStartOptions.coreConfig
    needXray = this@toStartOptions.needXray
    xrayConfig = this@toStartOptions.xrayConfig ?: ""
    xrayOutboundDNSStrategy = xrayDnsStrategy
    this@toStartOptions.xrayFullConfigs.forEach(::addXrayFullConfig)
    // mainwindow_profile_lifecycle.cpp:231-239: the idle window must outlast the probe interval, or the sidecar
    // restarts every round; full configs stay resident (0).
    if (autoSelector != null && (needXray || this@toStartOptions.xrayFullConfigs.isNotEmpty())) {
        xrayLazyStart = true
        xrayIdleSeconds = maxOf(120, autoSelector.intervalSec * 2)
        xrayFullIdleSeconds = 0
    }
}
