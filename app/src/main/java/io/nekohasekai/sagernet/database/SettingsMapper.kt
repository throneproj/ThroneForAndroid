package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.MemberCheck
import io.nekohasekai.sagernet.outbound.config.RoutingInput
import io.nekohasekai.sagernet.outbound.config.SelectorGroup
import io.nekohasekai.sagernet.outbound.config.SelectorMember
import io.nekohasekai.sagernet.outbound.config.SelectorStore
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.Custom
import io.throneproj.mobile.Mobile
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * DataStore -> the config generator's settings and build-time globals, and RouteManager / the profile tables -> its
 * routing input. The UI process (config export) and the :bg process (running instances, URL / speed tests) map them
 * identically, except that only :bg, where the core is loaded, lets the core check auto-selector members.
 * Every desktop key maps onto the field that names it; the Android-only inputs are the service mode, the per-app
 * lists and the HTTP proxy bypass list.
 */
object SettingsMapper {

    /**
     * The route profile of current_route_id (the first profile when it is gone), the rule-set list, the tracked
     * groups of auto-selectors and, in :bg, the core's member check.
     */
    fun routingInput(): RoutingInput = RoutingInput(
        RouteManager.current(),
        RouteManager.catalog(),
        selectorStore(),
        if (SagerNet.application.isBgProcess) CoreMemberCheck else null,
    )

    /** GroupsRepo / ProfilesRepo for the auto-selector plan; each group is read once per store. */
    fun selectorStore(): SelectorStore = DatabaseSelectorStore()

    /** The outbound layer's lookups into the database (autoselector.cpp:9-15 DisplayAddress); once per process. */
    fun installOutboundHooks() {
        AutoSelector.groupNames = AutoSelector.GroupNames { gid -> SagerDatabase.groupDao.getById(gid)?.displayName() }
    }

    fun generatorSettings(): GeneratorSettings {
        val mixedEnabled = !DataStore.mixedInboundDisabled
        val clashApi = DataStore.coreBoxClashApi
        return GeneratorSettings(
            logLevel = DataStore.logLevel,
            ntpEnabled = DataStore.enableNtp,
            ntpServer = DataStore.ntpServerAddress,
            ntpServerPort = DataStore.ntpServerPort,
            ntpInterval = DataStore.ntpInterval,
            ntpOutbound = DataStore.ntpOutbound,
            useMozillaCerts = DataStore.useMozillaCerts,
            dnsInPort = DataStore.coreDnsInPort,
            mixedInboundEnabled = mixedEnabled,
            inboundAddress = DataStore.inboundAddress.ifBlank { SettingsRegistry.LOOPBACK_ADDRESS },
            mixedPort = DataStore.inboundSocksPort,
            mixedAuth = DataStore.inboundAuth,
            mixedUsername = DataStore.inboundUser,
            mixedPassword = DataStore.inboundPass,
            customInboundJson = DataStore.customInbound,
            vpnMode = DataStore.serviceMode == Key.MODE_VPN,
            tunMtu = DataStore.vpnMtu,
            // strict_route is fixed on Android (decision D7).
            tunStrictRoute = true,
            tunIPv4Cidr = DataStore.vpnTunIpv4Cidr,
            tunIPv6Cidr = DataStore.vpnTunIpv6Cidr,
            ipv6Enabled = DataStore.vpnIpv6,
            bypassLan = !DataStore.disablePrivateRangeBypass,
            privateRanges = DataStore.vpnPrivateRanges,
            perAppEnabled = DataStore.proxyApps,
            perAppBypass = DataStore.bypass,
            perAppPackages = GeneratorSettings.splitLines(DataStore.individual),
            httpProxyEnabled = mixedEnabled,
            httpProxyBypassDomains = GeneratorSettings.splitLines(DataStore.httpProxyBypass),
            remoteDns = DataStore.remoteDns,
            remoteDnsDisableIpv6 = DataStore.remoteDnsDisableIpv6,
            directDns = DataStore.directDns,
            underlyingDns = DataStore.coreBoxUnderlyingDns,
            dnsCacheCapacity = DataStore.dnsCacheCapacity,
            dnsDisableCache = DataStore.dnsDisableCache,
            dnsDisableExpire = DataStore.dnsDisableExpire,
            dnsPersistCache = DataStore.dnsPersistCache,
            dnsReverseMapping = DataStore.dnsReverseMapping,
            dnsObject = DataStore.dnsObject,
            dnsFinalOut = DataStore.dnsFinalOut,
            dnsOptimistic = DataStore.dnsOptimistic,
            dnsOptimisticTimeout = DataStore.dnsOptimisticTimeout,
            dnsQueryTimeout = DataStore.dnsQueryTimeout,
            dnsUseHosts = DataStore.dnsUseHosts,
            dnsPredefinedEnable = DataStore.dnsPredefinedEnable,
            dnsPredefinedRules = DataStore.dnsPredefinedRules,
            fakeDns = DataStore.fakedns,
            fakeIpDisableIpv6 = DataStore.fakeipDisableIpv6,
            resolveDomainStrategy = DataStore.domainStrategy,
            rulesetMirror = DataStore.rulesetMirror,
            adblockEnable = DataStore.adblockEnable,
            enableDnsRouting = DataStore.enableDnsRouting,
            enableTunRouting = DataStore.enableTunRouting,
            trafficStats = DataStore.enableStats,
            apiPort = DataStore.coreBoxApiPort,
            apiSecret = DataStore.coreBoxApiSecret,
            clashApiEnabled = clashApi > 0,
            clashApiListen = DataStore.coreBoxClashListenAddr,
            clashApiPort = if (clashApi > 0) clashApi else -clashApi,
            clashApiSecret = DataStore.coreBoxClashApiSecret,
            xrayLogLevel = DataStore.xrayLogLevel,
            testUrl = DataStore.testUrl,
            directTestUrl = DataStore.directTestUrl,
            enableWarp = DataStore.enableWarp,
            warpMode = DataStore.warpMode,
            warpEp = DataStore.warpEp,
            warpPrivateKey = DataStore.warpPrivateKey,
            warpPublicKey = DataStore.warpPublicKey,
            warpIfcAddrs = DataStore.warpIfcAddrs,
            warpReserved = DataStore.warpReserved,
            warpMasqueEp = DataStore.warpMasqueEp,
            warpMasquePrivateKey = DataStore.warpMasquePrivateKey,
            warpMasquePeerPublicKey = DataStore.warpMasquePeerPublicKey,
            warpMasqueIfcAddrs = DataStore.warpMasqueIfcAddrs,
            warpMasqueSni = DataStore.warpMasqueSni,
            warpMasqueHttpMode = DataStore.warpMasqueHttpMode,
        )
    }

    /** DataStore -> BuildContext: the preset globals and the domain strategies. TLS spoof is never mapped (decision D8). */
    fun buildContext(): BuildContext = BuildContext(
        skipCert = DataStore.skipCert,
        utlsFingerprint = DataStore.utlsFingerprint,
        muxDefaultOn = DataStore.muxDefaultOn,
        muxProtocol = DataStore.muxProtocol,
        muxConcurrency = DataStore.muxConcurrency,
        muxPadding = DataStore.muxPadding,
        fragmentImplementation = DataStore.fragmentImplementation,
        fragmentDefaultOn = DataStore.fragmentDefaultOn,
        fragmentSize = DataStore.fragmentSize.ifBlank { SettingsRegistry.FRAGMENT_SIZE.default },
        fragmentSleep = DataStore.fragmentSleep.ifBlank { SettingsRegistry.FRAGMENT_SLEEP.default },
        tlsTricksDefaultOn = DataStore.tlsTricksDefaultOn,
        h2IdleTimeout = DataStore.h2IdleTimeout,
        h2KeepAlivePeriod = DataStore.h2KeepAlivePeriod,
        h2StreamReceiveWindow = DataStore.h2StreamReceiveWindow,
        h2ConnectionReceiveWindow = DataStore.h2ConnectionReceiveWindow,
        h2MaxConcurrentStreams = DataStore.h2MaxConcurrentStreams,
        quicInitialPacketSize = DataStore.quicInitialPacketSize,
        quicDisablePathMtuDiscovery = DataStore.quicDisablePathMtuDiscovery,
        xrayMuxConcurrency = DataStore.xrayMuxConcurrency,
        xrayMuxDefaultOn = DataStore.xrayMuxDefaultOn,
        defaultDomainStrategy = DataStore.outboundDomainStrategy,
        directDnsDisableIpv6 = DataStore.directDnsDisableIpv6,
        useDnsObject = DataStore.useDnsObject,
    )

    /** xray_vless_preference for link and subscription imports (utils.cpp:50-70). */
    fun xrayVlessPreference(): OutboundFactory.XrayVlessPreference =
        OutboundFactory.XrayVlessPreference.entries.getOrNull(DataStore.xrayVlessPreference)
            ?: OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE

    private class DatabaseSelectorStore : SelectorStore {
        private val groups = HashMap<Long, SelectorGroup?>()

        override fun group(gid: Long): SelectorGroup? {
            if (groups.containsKey(gid)) return groups[gid]
            return load(gid).also { groups[gid] = it }
        }

        private fun load(gid: Long): SelectorGroup? {
            val group = (if (gid > 0) SagerDatabase.groupDao.getById(gid) else null) ?: return null
            val members = SagerDatabase.proxyDao.getByGroup(gid).map {
                SelectorMember(it.id, it.outbound, it.latency, it.latencyAt, it.testCountry.orEmpty())
            }
            val landing = group.landingProxyId.takeIf { it > 0 } ?: -1L
            val front = group.frontProxyId.takeIf { it > 0 } ?: -1L
            fun outbound(id: Long): Outbound? = if (id > 0) SagerDatabase.proxyDao.getById(id)?.outbound else null
            return SelectorGroup(group.id, group.name, landing, front, outbound(landing), outbound(front), members)
        }
    }

    /**
     * IsValid (generate.cpp:2466-2525) on the loaded core, ten members at a time like invalidProfileIDs: a sing-box
     * member alone in `outbounds` / `endpoints` with the log level, an Xray one alone in an Xray config, a custom Xray
     * full config without its inbounds (a missing geo asset is left to fail at start, where it can be named).
     */
    private object CoreMemberCheck : MemberCheck {
        override fun rejected(members: List<Pair<Long, Outbound>>, ctx: BuildContext): Map<Long, String> {
            if (members.isEmpty()) return emptyMap()
            val logLevel = DataStore.logLevel
            val pool = Executors.newFixedThreadPool(minOf(10, members.size))
            try {
                val verdicts = members.map { (_, outbound) -> pool.submit(Callable { coreError(outbound, ctx, logLevel) }) }
                val rejected = LinkedHashMap<Long, String>()
                for ((index, verdict) in verdicts.withIndex()) verdict.get()?.let { rejected[members[index].first] = it }
                return rejected
            } finally {
                pool.shutdown()
            }
        }

        private fun coreError(outbound: Outbound, ctx: BuildContext, logLevel: String): String? = try {
            when {
                outbound.isXrayFullConfig() -> {
                    val config = (outbound as Custom).configObject()
                    config.remove("inbounds")
                    try {
                        Mobile.checkXrayConfig(config.toCompact())
                        null
                    } catch (e: Exception) {
                        val message = e.message.orEmpty()
                        if (message.contains("geoip.dat") || message.contains("geosite.dat")) null else message
                    }
                }

                outbound.isXray() -> {
                    val built = outbound.buildXray(ctx)
                    if (!built.ok) built.error else {
                        Mobile.checkXrayConfig(jsonObjectOf("outbounds" to JsonArray.of(built.json)).toCompact())
                        null
                    }
                }

                else -> {
                    val key = if (outbound.isEndpoint()) "endpoints" else "outbounds"
                    val config = jsonObjectOf(key to JsonArray.of(outbound.build(ctx).json), "log" to jsonObjectOf("level" to logLevel))
                    Mobile.checkConfig(config.toCompact())
                    null
                }
            }
        } catch (e: Exception) {
            e.message ?: e.toString()
        }
    }
}
