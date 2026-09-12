package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.juicity.buildSingBoxOutboundJuicityBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.shadowsocksr.buildSingBoxOutboundShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.snell.buildSingBoxOutboundSnellBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"
const val TAG_DNS_HOSTS = "dns-hosts"

const val LOCALHOST = "127.0.0.1"

// Only types backed by the target sing-box endpoint registry belong here. Keeping this
// whitelist explicit prevents a user-supplied legacy/custom outbound from being silently
// reinterpreted as an endpoint.
private val ENDPOINT_TYPES = setOf("wireguard")

private fun SingBoxOption.isGeneratedEndpoint(): Boolean {
    return this is Endpoint && type in ENDPOINT_TYPES
}

internal fun SingBoxOption.detourTo(nextTag: String) {
    if (this is Endpoint_WireGuardOptions) {
        val effectiveOptions = asMap()
        val listenPort = when (val value = effectiveOptions["listen_port"]) {
            is Number -> value.toInt()
            else -> value?.toString()?.toIntOrNull() ?: 0
        }
        if (listenPort > 0) {
            val endpointTag = effectiveOptions["tag"]?.toString()?.takeIf { it.isNotBlank() }
                ?: "<untagged>"
            throw IllegalArgumentException(
                "WireGuard endpoint '$endpointTag' cannot detour to '$nextTag' while " +
                    "listen_port is enabled; set listen_port to 0 or use WireGuard only " +
                    "in a chain position that does not require another hop."
            )
        }

        // sing-box exposes endpoints through OutboundManager, so selectors, URL tests and
        // other dialers can reference this tag directly. WireGuard also embeds DialerOptions,
        // allowing its own outbound connection to follow the existing T4A chain direction.
        detour = nextTag
        return
    }

    _hack_config_map["detour"] = nextTag
}

// sing-box 的 fragment_fallback_delay 要求带单位的时长字符串（如 "10ms"）；
// fragmentInterval 以区间形式存储（如 "10-20"，单位毫秒），取区间首值并补全单位。
private const val DEFAULT_FRAGMENT_FALLBACK_DELAY = "10ms"

private fun tlsFragmentFallbackDelay(): String {
    val first = DataStore.fragmentInterval
        .split(',', '-', ' ')
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() } ?: return DEFAULT_FRAGMENT_FALLBACK_DELAY
    return if (first.toDoubleOrNull() != null) "${first}ms" else first
}

internal data class ChainHopTag(
    val tag: String,
    val reused: Boolean,
)

internal fun resolveChainHopTag(
    profileId: Long,
    proposedTag: String,
    needGlobal: Boolean,
    globalOutbounds: MutableMap<Long, String>,
): ChainHopTag {
    if (!needGlobal) return ChainHopTag(proposedTag, reused = false)

    val existingTag = globalOutbounds[profileId]
    if (existingTag != null) return ChainHopTag(existingTag, reused = true)

    globalOutbounds[profileId] = proposedTag
    return ChainHopTag(proposedTag, reused = false)
}

internal fun RouteOptions.ensureMainRouteFinal(mainProxyTag: String) {
    if (final_.isNullOrBlank()) final_ = mainProxyTag
}

internal fun buildSelectorOutbound(defaultTag: String?, memberTags: List<String>) =
    Outbound_SelectorOptions().apply {
        type = "selector"
        tag = TAG_PROXY
        default_ = defaultTag
        // Endpoint tags are valid outbound references in sing-box 1.13; keep them as direct
        // group members instead of wrapping WireGuard in a removed outbound.
        outbounds = memberTags
    }

private fun endpointTag(value: Any?): String? {
    return (value as? Map<*, *>)?.get("tag")?.toString()?.takeIf { it.isNotBlank() }
}

private fun mergeEndpointList(
    existing: List<*>, incoming: List<*>, prependNew: Boolean = false
): MutableList<Any?> {
    val result = existing.toMutableList()
    val additions = mutableListOf<Any?>()

    incoming.forEach { endpoint ->
        val tag = endpointTag(endpoint)
        val existingIndex = tag?.let { candidate ->
            result.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1
        val additionIndex = tag?.let { candidate ->
            additions.indexOfFirst { endpointTag(it) == candidate }
        } ?: -1

        when {
            existingIndex >= 0 -> result[existingIndex] = endpoint
            additionIndex >= 0 -> additions[additionIndex] = endpoint
            else -> additions.add(endpoint)
        }
    }

    if (prependNew) result.addAll(0, additions) else result.addAll(additions)
    return result
}

@Suppress("UNCHECKED_CAST")
private fun mergeRootConfig(dst: MutableMap<String, Any?>, json: String) {
    if (json.isBlank()) return
    val source = gson.fromJson(json, dst.javaClass) as? Map<String, Any?> ?: return
    val remaining = source.toMutableMap()

    // Root custom config precedence is automatic < global < selected profile. For endpoints,
    // a later non-empty tag replaces the earlier object in place; distinct/untagged objects
    // coexist. The existing +key/key+ list extension syntax remains prepend/append respectively.
    val replacement = remaining.remove("endpoints")
    val prepended = remaining.remove("+endpoints")
    val appended = remaining.remove("endpoints+")
    Util.mergeMap(dst, remaining)

    fun merge(value: Any?, prependNew: Boolean = false) {
        if (value !is List<*>) {
            if (value != null) dst["endpoints"] = value
            return
        }
        val current = dst["endpoints"] as? List<*> ?: emptyList<Any?>()
        dst["endpoints"] = mergeEndpointList(current, value, prependNew)
    }

    merge(replacement)
    merge(prepended, prependNew = true)
    merge(appended)
}

internal fun finalizeRootConfig(
    options: MyOptions,
    globalCustomConfig: String = "",
    profileCustomConfig: String = "",
): MutableMap<String, Any?> {
    val generatedEndpoints = options.outbounds.orEmpty().filter { it.isGeneratedEndpoint() }
    generatedEndpoints
        .filterIsInstance<Endpoint_WireGuardOptions>()
        .filter { endpoint -> endpoint.asMap()["detour"]?.toString().isNullOrBlank() }
        .forEach { endpoint -> endpoint.detourTo(TAG_DIRECT) }
    options.endpoints = options.endpoints.orEmpty() + generatedEndpoints.map { it as Endpoint }
    options.outbounds = options.outbounds.orEmpty().filterNot { it.isGeneratedEndpoint() }

    val configMap = options.asMap()
    mergeRootConfig(configMap, globalCustomConfig)
    mergeRootConfig(configMap, profileCustomConfig)
    return configMap
}

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

private fun sanitizeDnsEntry(value: String): String {
    return value.filterNot { it.isISOControl() }.trim()
}

private fun parseDnsHosts(value: String): Map<String, List<String>> {
    val hosts = linkedMapOf<String, MutableList<String>>()
    value.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
        val tokens = trimmed.split("\\s+".toRegex())
        if (tokens.size < 2) return@forEach
        val domain = tokens.first()
        val addresses = tokens.drop(1).filter { it.isIpAddress() }
        if (addresses.isEmpty()) return@forEach
        hosts.getOrPut(domain) { mutableListOf() }.addAll(addresses)
    }
    return hosts.mapValues { (_, addresses) -> addresses.distinct() }
}

private fun serverHostOf(bean: AbstractBean): String? {
    val fallback = bean.serverAddress?.takeIf { it.isNotBlank() }
    if (bean is ConfigBean) {
        return try {
            val map = gson.fromJson(bean.config, mutableMapOf<String, Any>().javaClass)
            map["server"]?.toString()?.takeIf { it.isNotBlank() } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
    return fallback
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            val tagProxy = proxy.displayName()
            return ConfigBuildResult(
                bean.config,
                listOf(),
                proxy.id, //
                mapOf(tagProxy to listOf(proxy)), //
                mapOf(proxy.id to tagProxy), //
                -1L
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val readableNames = mutableSetOf(TAG_DIRECT, TAG_BYPASS, TAG_BLOCK, TAG_MIXED, TAG_PROXY)
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean is ChainBean) {
            val beans = SagerDatabase.proxyDao.getEntities(bean.proxies)
            val beansMap = beans.associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = beansMap[proxyId] ?: continue
                beanList.addAll(item.resolveChainInternal())
            }
            return beanList.asReversed()
        }
        return mutableListOf(this)
    }

    fun readableTag(name_: String): String {
        var name = name_
        var count = 0
        while (!readableNames.add(name)) {
            count++
            name = "$name_-$count"
        }
        return name
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val list = resolveChainInternal()
        if (frontProxy != null) {
            list.add(frontProxy)
        }
        if (landingProxy != null) {
            list.add(0, landingProxy)
        }
        return list
    }

    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val perGroupResolver = HashMap<Long, String>()
    val perGroupServerHosts = HashMap<Long, MutableSet<String>>()
    val hostResolvers = HashMap<String, MutableSet<String>>()
    val nonCustomFinalHosts = hashSetOf<String>()
    val groupCache = HashMap<Long, ProxyGroup?>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val dnsHosts by lazy { parseDnsHosts(DataStore.dnsHosts) }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    // sing-box 1.13 已移除 sniff_override_destination（sniff 规则动作不再覆盖目标地址），
    // trafficSniffing 退化为开关语义（>0 即启用）。
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    // 测速配置必须与正式连接一致（对齐 husi）：沿用用户的 IPv6 模式。
    // 曾强制 ENABLE——测速拨号的协议族选择与真实路径不同，
    // v6 不通的网络里测速假 err（节点实际可用），反之假成功。
    val ipv6Mode = DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    // 旧 fork 的 "hosts" DNS 地址 = 系统解析器；官方内核无此 scheme，对应 "local"
    // （官方 legacy 升级会把裸 "hosts" 误判为 UDP 服务器域名，静默失败）。
    // 仅用于 dns-direct / 订阅 resolver 等"本就应本机直解"的场景，远程 DNS 禁止走此函数。
    fun normalizeDnsAddress(address: String): String = if (address == "hosts") "local" else address

    // 远程 DNS 必须由节点代访问（配合下方 detour=当前节点），绝不能归一化为本机直解的 local：
    // 官方内核下 local/hosts/fakeip 都是本机解析占位符（Android 上 local 走平台接口经物理网卡
    // 直连系统 DNS），用作远程即 DNS 泄露，与 fork 时代 hosts 语义不对齐。
    // 统一回退为公共 DoH 并 Toast 提示用户修改设置。
    fun normalizeRemoteDnsAddress(address: String): String {
        return when (address) {
            "hosts", "local", "localhost", "fakeip" -> {
                runOnMainDispatcher {
                    Toast.makeText(
                        SagerNet.application,
                        "Warning: \"$address\" is not supported as remote DNS and has been replaced with https://8.8.8.8/dns-query. Please update your remote DNS setting.",
                        Toast.LENGTH_LONG
                    ).show()
                }
                "https://8.8.8.8/dns-query"
            }

            else -> address
        }
    }

    return MyOptions().apply {
        // forTest 不配 experimental：Go 侧 NewTestSingBoxInstance 不注册
        // PlatformLogWriter，官方内核据此不再强制创建 CacheFile/ClashServer
        // （官方 box.go 的 needCacheFile/needClashAPI 分支），测速完全不产生
        // cache.db——曾因此引发主进程并发共享 bbolt 文件损坏闪退，现从根上移除。
        if (!forTest) {
            experimental = ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "../cache/cache.db"
                    // if (DataStore.enableClashAPI) {
                    store_fakeip = true
                    // }
                }

                if (DataStore.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            independent_cache = true
            // 禁用 IPv6 时全局 DNS 策略强制 ipv4_only
            if (ipv6Mode == IPv6Mode.DISABLE) strategy = "ipv4_only"
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                interface_name = "tun0"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                mtu = DataStore.mtu
                auto_route = true
                strict_route = DataStore.strictRoute
                // sing-box 1.13 移除了入站 sniff/domain_strategy 字段，
                // 改由路由规则动作实现（见下方 route.rules 构建处）；
                // inet4_address/inet6_address 与 endpoint_independent_nat 已于 1.12 移除（构造函数硬报错），
                // address 为合并后的新字段。
                // 禁用 IPv6 时仍保留 v6 虚拟地址：与 VpnService 常驻 v6 路由配合，
                // 让系统完整接管 IPv6 流量进入 tun（由内核 reject 策略兜底）
                address = when (ipv6Mode) {
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126"
                    )
                }
            })
            if (!DataStore.mixedInboundDisabled) inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
                if (DataStore.mixedInboundNeedsAuth) {
                    users = listOf(User().also { u ->
                        u.username = DataStore.mixedUsername
                        u.password = DataStore.mixedPassword
                    })
                }
            })
        }

        endpoints = mutableListOf()
        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            override_android_vpn = true
            rules = mutableListOf()
            rule_set = mutableListOf()

            // 双网络加速：hybrid = 在所有可用网络接口（WiFi/移动数据）上并发传输
            if (DataStore.dualNetworkAcceleration) default_network_strategy = "hybrid"
        }

        // returns outbound tag
        @Suppress("UNCHECKED_CAST")
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            val profileList = entity.resolveChain()
            // profileList 的顺序即应用流量经过各 outbound 的顺序：前一跳通过
            // detour 交给后一跳拨号，最后一项直接连接物理网络。
            Logs.d(
                "Outbound chain id=$chainId forTest=$forTest appToEgress=" +
                    profileList.joinToString(" -> ") { hop ->
                        val hopBean = hop.requireBean()
                        val host = serverHostOf(hopBean) ?: "<unknown>"
                        val endpoint = hopBean.displayAddress().takeIf { it.isNotBlank() }
                            ?: "$host:${hopBean.serverPort}"
                        "${hop.id}:${hop.displayType()}@$endpoint"
                    }
            )
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            // 禁用 IPv6 时链路域名解析策略强制 ipv4_only，
            // 避免节点服务器域名被解析出 IPv6 地址后经物理网卡直连泄露
            val defaultServerDomainStrategy = if (ipv6Mode == IPv6Mode.DISABLE) "ipv4_only"
            else SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    needGlobal = true
                    tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()

                    if (!forTest) {
                        val ownerGid = entity.groupId
                        val ownerGroup = groupCache.getOrPut(ownerGid) {
                            SagerDatabase.groupDao.getById(ownerGid)
                        }
                        val resolver = ownerGroup
                            ?.takeIf { it.type == GroupType.SUBSCRIPTION }
                            ?.subscription?.serverDnsResolver
                            ?.let { sanitizeDnsEntry(it) }
                            ?.takeIf { it.isNotBlank() }

                        if (resolver != null) {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    if (hop.groupId == ownerGid) {
                                        perGroupResolver[ownerGid] = resolver
                                        perGroupServerHosts.getOrPut(ownerGid) { mutableSetOf() }
                                            .add(host)
                                        hostResolvers.getOrPut(host) { mutableSetOf() }.add(resolver)
                                    } else {
                                        nonCustomFinalHosts.add(host)
                                    }
                                }
                            }
                        } else {
                            profileList.forEach { hop ->
                                val host = serverHostOf(hop.requireBean())
                                if (host != null && !host.isIpAddress()) {
                                    nonCustomFinalHosts.add(host)
                                }
                            }
                        }
                    }
                }

                if (index == 0) {
                    tagOut = readableTag(bean.displayName())
                }

                // Resolve a globally shared hop before writing the edge that references it.
                // A hop built by an earlier rule may use a readable or endpoint tag instead of
                // this chain's proposed g-<id> tag; the global map is the source of truth.
                val resolvedTag = resolveChainHopTag(
                    proxyEntity.id,
                    tagOut,
                    needGlobal,
                    globalOutbounds,
                )
                tagOut = resolvedTag.tag

                // chain rules
                if (index > 0) {
                    // chain route/proxy rules
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound.detourTo(tagOut)
                    }
                } else {
                    // index == 0 means last profile in chain / not chain
                    chainTagOut = tagOut
                }

                // The edge now points at the previously generated final tag, so the duplicate
                // object can be skipped without leaving a dangling detour.
                if (resolvedTag.reused) return@forEachIndexed

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> CustomSingBoxOption(bean.config) as SingBoxOption

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is JuicityBean ->
                            buildSingBoxOutboundJuicityBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is ShadowsocksRBean ->
                            buildSingBoxOutboundShadowsocksRBean(bean)

                        is WireGuardBean -> {
                            val endpointMtu = bean.mtu?.takeIf { it > 0 }
                            val peerAddressFamily = when {
                                bean.serverAddress?.contains(':') == true -> "ipv6"
                                bean.serverAddress?.isNotBlank() == true -> "ipv4-or-domain"
                                else -> "missing"
                            }
                            Logs.i(
                                "WireGuardEndpointTrace profileId=${proxyEntity.id} forTest=$forTest " +
                                    "endpointMtu=${endpointMtu ?: "default(1408)"} " +
                                    "tunMtu=${DataStore.mtu} peerAddressFamily=$peerAddressFamily " +
                                    "peerPort=${bean.serverPort}"
                            )
                            buildSingBoxEndpointWireGuardBean(bean)
                        }

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }

                    if (needGlobal && DataStore.enableTLSFragment) {
                        val outboundMap = currentOutbound.asMap()
                        val tlsOptions = outboundMap["tls"] as? Map<*, *>
                        if (tlsOptions?.get("enabled") == true) {
                            // sing-box 1.13 起代理 outbound 的 TLS 选项支持内联分片字段；
                            // 旧式 direct outbound 的 fragment 字段已被移除，继续发射会
                            // 因 DisallowUnknownFields 导致配置解析失败。
                            currentOutbound._hack_config_map["tls"] = mapOf(
                                "fragment" to true,
                                "record_fragment" to true,
                                "fragment_fallback_delay" to tlsFragmentFallbackDelay(),
                            )
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    // 测速配置必须与正式连接一致（对齐 husi）：沿用统一的服务器
                    // 域名解析策略。曾强制空——测速解析出的 IP/协议族与真实路径不同。
                    _hack_config_map["domain_strategy"] = defaultServerDomainStrategy

                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            else -> ""
                        }
                        if (Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                outbounds.add(currentOutbound)
                chainOutbounds.add(currentOutbound)
                pastOutbound = currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, buildSelectorOutbound(tagMap[proxy.id], tagMap.values.toList()))
        } else {
            val mainTag = buildChain(0, proxy)
            tagMap[proxy.id] = mainTag
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        val mainProxyTag = (if (buildSelector) TAG_PROXY else tagMap[proxy.id]) ?: TAG_PROXY

        // 在应用用户规则之前检查全局模式
        if (!forTest && DataStore.globalMode) {
            // 全局模式下的规则处理
            
            // 绕过内部网络（如果启用）
            if (DataStore.bypassLan) {
                route.rules.add(Rule_DefaultOptions().apply {
                    ip_cidr = listOf(
                        "224.0.0.0/3",
                        "172.16.0.0/12",
                        "127.0.0.0/8",
                        "10.0.0.0/8",
                        "192.168.0.0/16",
                        "169.254.0.0/16",
                        "::1/128",
                        "fc00::/7",
                        "fe80::/10"
                    )
                    outbound = TAG_DIRECT
                })
            }

            route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf("tun-in")
                outbound = mainProxyTag
            })

            // 禁用混合入站时不生成入站系的规则
            if (!DataStore.mixedInboundDisabled) route.rules.add(Rule_DefaultOptions().apply {
                inbound = listOf(TAG_MIXED)
                outbound = mainProxyTag
            })

            route.final_ = mainProxyTag
        } else {
            // 应用用户规则
            for (rule in extraRules) {
                if (rule.packages.isNotEmpty()) {
                    PackageCache.awaitLoadSync()
                }
                val uidList = rule.packages.map {
                    if (!isVPN) {
                        Toast.makeText(
                            SagerNet.application,
                            SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    PackageCache[it]?.takeIf { uid -> uid >= 1000 }
                }.toHashSet().filterNotNull()
                val ruleSets = mutableListOf<RuleSet>()

                val ruleObj = Rule_DefaultOptions().apply {
                    if (uidList.isNotEmpty()) {
                        PackageCache.awaitLoadSync()
                        user_id = uidList
                    }
                    var domainList: List<String>? = null
                    if (rule.domains.isNotBlank()) {
                        domainList = rule.domains.listByLineOrComma()
                        makeSingBoxRule(domainList, false)
                    }
                    if (rule.ip.isNotBlank()) {
                        makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                    }
                    
                    if (rule_set != null) generateRuleSet(rule_set, ruleSets)
                    
		    // 存储ruleset标签和类型信息
                    val rulesetTags = mutableListOf<Pair<String, Boolean>>()
                    
                    // 处理远程ruleset
                    if (rule.ruleset.isNotBlank()) {
                        val rulesetUrls = rule.ruleset.listByLineOrComma()
                        rulesetUrls.forEach { origUrl ->
                            val (url, isIPRuleset) = processRulesetUrl(origUrl)
                            
                            val tag = generateRemoteRuleSet(url, ruleSets, DataStore.rulesUpdateInterval)
                            
                            rulesetTags.add(Pair(tag, isIPRuleset))
                            
                            rule_set = (rule_set ?: mutableListOf()).apply {
                                add(tag)
                            }
                        }
                    }

                    if (rule.port.isNotBlank()) {
                        port = mutableListOf<Int>()
                        port_range = mutableListOf<String>()
                        rule.port.listByLineOrComma().map {
                            if (it.contains(":")) {
                                port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { port.add(this) }
                            }
                        }
                    }
                    if (rule.sourcePort.isNotBlank()) {
                        source_port = mutableListOf<Int>()
                        source_port_range = mutableListOf<String>()
                        rule.sourcePort.listByLineOrComma().map {
                            if (it.contains(":")) {
                                source_port_range.add(it)
                            } else {
                                it.toIntOrNull()?.apply { source_port.add(this) }
                            }
                        }
                    }
                    if (rule.network.isNotBlank()) {
                        network = listOf(rule.network)
                    }
                    if (rule.source.isNotBlank()) {
                        source_ip_cidr = rule.source.listByLineOrComma()
                    }
                    if (rule.protocol.isNotBlank()) {
                        protocol = rule.protocol.listByLineOrComma()
                    }

                    fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                        return DNSRule_DefaultOptions().apply {
                            if (uidList.isNotEmpty()) user_id = uidList
                            domainList?.let { makeSingBoxRule(it) }
                        }
                    }

                    val hasDomainCriteria = !domainList.isNullOrEmpty()
                    val hasIpCriteria =
                        rule.ip.isNotBlank() || rulesetTags.any { it.second }
                    val hasDomainRuleset = rulesetTags.any { !it.second }
                    val isAppOnlyDns =
                        uidList.isNotEmpty() &&
                            !hasDomainCriteria &&
                            !hasIpCriteria &&
                            !hasDomainRuleset &&
                            rule.port.isBlank() &&
                            rule.sourcePort.isBlank() &&
                            rule.network.isBlank() &&
                            rule.source.isBlank() &&
                            rule.protocol.isBlank()
                    val shouldAddDnsRule = hasDomainCriteria || isAppOnlyDns

                    when (rule.outbound) {
                        -1L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply { server = "dns-direct" }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    // 只处理ruleset标签，且必须是非IP类型
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-direct"
                                        }
                                    }
                                }
                            }
                        }

                        0L -> {
                            if (shouldAddDnsRule) {
                                if (useFakeDns) userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-fake"
                                    inbound = listOf("tun-in")
                                    query_type = listOf("A", "AAAA")
                                } else {
                                    userDNSRuleList += makeDnsRuleObj().apply {
                                        server = "dns-remote"
                                    }
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        if (useFakeDns) {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-fake"
                                                inbound = listOf("tun-in")
                                                query_type = listOf("A", "AAAA")
                                            }
                                        } else {
                                            userDNSRuleList += DNSRule_DefaultOptions().apply {
                                                rule_set = mutableListOf(tag)
                                                server = "dns-remote"
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        -2L -> {
                            if (shouldAddDnsRule) {
                                userDNSRuleList += makeDnsRuleObj().apply {
                                    server = "dns-block"
                                    disable_cache = true
                                }
                            }

                            if (rule_set != null && rulesetTags.isNotEmpty()) {
                                for (tag in rule_set) {
                                    val tagInfo = rulesetTags.find { it.first == tag }
                                    if (tag.startsWith("ruleset-") && tagInfo != null && !tagInfo.second) {
                                        userDNSRuleList += DNSRule_DefaultOptions().apply {
                                            rule_set = mutableListOf(tag)
                                            server = "dns-block"
                                            disable_cache = true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    outbound = when (val outId = rule.outbound) {
                        0L -> mainProxyTag
                        -1L -> TAG_BYPASS
                        -2L -> TAG_BLOCK
                        else -> if (outId == proxy.id) mainProxyTag else tagMap[outId] ?: ""
                    }

                    _hack_custom_config = rule.config
                }

                if (!ruleObj.checkEmpty()) {
                    if (ruleObj.outbound.isNullOrBlank()) {
                        Toast.makeText(
                            SagerNet.application,
                            "Warning: " + rule.displayName() + ": A non-existent outbound was specified.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        // block 改用新的写法
                        if (ruleObj.outbound == TAG_BLOCK) {
                            ruleObj.outbound = null
                            ruleObj.action = "reject"
                        }
                        route.rules.add(ruleObj)
                        route.rule_set.addAll(ruleSets)
                    }
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) {
            outbounds.add(Outbound().apply {
                tag = freedom
                type = "direct"
                if (freedom == TAG_DIRECT) {
                    // A WireGuard endpoint detour cannot target an empty direct outbound.
                    // Keep MTU unchanged and switch only the Android dialer path.
                    _hack_config_map["network_strategy"] = "default"
                }
            })
        }

        fun isExclusiveCustomHost(host: String): Boolean {
            return hostResolvers[host]?.size == 1 && !nonCustomFinalHosts.contains(host)
        }

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                if (!isExclusiveCustomHost(serverAddr)) {
                    domainListDNSDirectForce.add("full:${serverAddr}")
                }
            }
        }

        remoteDns.forEach {
            var address = it
            if (address.contains("://")) {
                address = address.substringAfter("://")
            }
            "https://$address".toHttpUrlOrNull()?.apply {
                if (!host.isIpAddress()) {
                    domainListDNSDirectForce.add("full:$host")
                }
            }
        }

        dns.servers.add(DNSServerOptions().apply {
            address = "rcode://success"
            tag = "dns-block"
        })

        dns.servers.add(DNSServerOptions().apply {
            address = "local"
            tag = "dns-local"
            detour = TAG_DIRECT
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DNSServerOptions().apply {
                address = normalizeDnsAddress(it ?: throw Exception("No direct DNS, check your settings!"))
                tag = "dns-direct"
                detour = TAG_DIRECT
                address_resolver = "dns-local"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DNSServerOptions().apply {
                address = normalizeRemoteDnsAddress(it ?: throw Exception("No remote DNS, check your settings!"))
                tag = "dns-remote"
                // 远程 DNS 交给当前节点代访问（对齐 Throne 桌面端 detour=proxy），本机直出即泄露。
                detour = mainProxyTag
                address_resolver = "dns-direct"
                strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(tag))
            })
        }
        if (dnsHosts.isNotEmpty()) {
            dns.servers.add(DNSServerOptions().apply {
                tag = TAG_DNS_HOSTS
                _hack_config_map["type"] = "hosts"
                _hack_config_map["predefined"] = dnsHosts
            })
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            userDNSRuleList.forEach {
                if (!it.checkEmpty()) dns.rules.add(it)
            }
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            // built-in DNS rules
            route.rules.add(0, Rule_DefaultOptions().apply {
                protocol = listOf("dns")
                action = "hijack-dns"
            })
            route.rules.add(0, Rule_DefaultOptions().apply {
                port = listOf(53)
                action = "hijack-dns"
            })
            // sing-box 1.13：sniff / 解析目标地址迁移为路由规则动作（须位于规则最前）。
            if (DataStore.resolveDestination) route.rules.add(0, Rule_DefaultOptions().apply {
                action = "resolve"
                strategy = genDomainStrategy(true)
            })
            if (needSniff) route.rules.add(0, Rule_DefaultOptions().apply {
                action = "sniff"
            })
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                source_ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // 禁用 IPv6：在路由规则最前拒绝所有 IPv6 流量（与 DNS AAAA reject 配合）
            if (ipv6Mode == IPv6Mode.DISABLE) {
                route.rules.add(0, Rule_DefaultOptions().apply {
                    ip_version = 6
                    action = "reject"
                })
            }
            // FakeDNS obj
            if (useFakeDns) {
                dns.fakeip = DNSFakeIPOptions().apply {
                    enabled = true
                    inet4_range = "198.18.0.0/15"
                    // 禁用 IPv6 时不生成 inet6_range，fakeip 仅处理 A 记录
                    if (ipv6Mode != IPv6Mode.DISABLE) inet6_range = "fc00::/18"
                }
                dns.servers.add(DNSServerOptions().apply {
                    address = "fakeip"
                    tag = "dns-fake"
                    strategy = "ipv4_only"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    inbound = listOf("tun-in")
                    server = "dns-fake"
                    disable_cache = true
                    query_type = if (ipv6Mode == IPv6Mode.DISABLE) listOf("A") else listOf("A", "AAAA")
                })
            }
            if (dnsHosts.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    server = TAG_DNS_HOSTS
                    _hack_config_map["ip_accept_any"] = true
                })
            }
            // avoid loopback
            dns.rules.add(0, DNSRule_DefaultOptions().apply {
                outbound = mutableListOf("any")
                server = "dns-direct"
            })
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    server = "dns-direct"
                })
            }
            perGroupResolver.forEach { (gid, resolver) ->
                val hosts = perGroupServerHosts[gid]
                    ?.filter { it.isNotBlank() && isExclusiveCustomHost(it) }
                    ?.map { "full:$it" }
                if (hosts.isNullOrEmpty()) return@forEach

                val serverTag = "dns-sub-$gid"
                dns.servers.add(DNSServerOptions().apply {
                    address = normalizeDnsAddress(resolver)
                    tag = serverTag
                    detour = TAG_DIRECT
                    if (!resolver.isIpAddress()) {
                        address_resolver = "dns-direct"
                    }
                    strategy = autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy("server"))
                })
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    makeSingBoxRule(hosts)
                    server = serverTag
                })
            }

            // 禁用 IPv6：在 DNS 规则最前拒绝所有 AAAA 查询，杜绝 v6 解析结果泄露
            if (ipv6Mode == IPv6Mode.DISABLE) {
                dns.rules.add(0, DNSRule_DefaultOptions().apply {
                    query_type = listOf("AAAA")
                    action = "reject"
                })
            }
        }

        // Legacy outbounds implicitly used their first item as the default route. Endpoints are
        // partitioned out of that list, so an unset final would silently fall back to direct.
        route.ensureMainRouteFinal(mainProxyTag)
        val routeFinalState = when (route.final_) {
            null, "" -> "unset"
            mainProxyTag -> "main"
            else -> "other"
        }
        Logs.i(
            "RouteFinalTrace profileId=${proxy.id} forTest=$forTest " +
                "globalMode=${!forTest && DataStore.globalMode} selector=$buildSelector " +
                "finalState=$routeFinalState ruleCount=${route.rules.size}"
        )

    }.let { options ->
        val configMap = finalizeRootConfig(
            options,
            globalCustomConfig = if (forTest) "" else DataStore.globalCustomConfig,
            profileCustomConfig = proxy.requireBean().customConfigJson,
        )
        val endpointTags = (configMap["endpoints"] as? List<*>)
            .orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("tag")?.toString() }
        val outboundNodes = (configMap["outbounds"] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? Map<*, *> }
        val outboundTags = outboundNodes.mapNotNull { it["tag"]?.toString() }
        val availableTags = (endpointTags + outboundTags).toSet()
        val references = buildList {
            val endpointNodes = (configMap["endpoints"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }
            (endpointNodes + outboundNodes).forEach { node ->
                val source = node["tag"]?.toString() ?: "<untagged>"
                node["detour"]?.toString()?.takeIf { it.isNotBlank() }?.let {
                    add("$source->$it")
                }
                if (node["type"] == "selector") {
                    (node["outbounds"] as? List<*>)?.forEach { target ->
                        target?.toString()?.takeIf { it.isNotBlank() }?.let {
                            add("$source=>$it")
                        }
                    }
                }
            }
            val route = configMap["route"] as? Map<*, *>
            route?.get("final")?.toString()?.takeIf { it.isNotBlank() }?.let {
                add("route.final->$it")
            }
        }
        val unresolvedTargets = references.mapNotNull { reference ->
            reference.substringAfterLast("->", reference.substringAfterLast("=>"))
                .takeUnless { it in availableTags }
        }.distinct()
        Logs.i(
            "ChainTopologyTrace profileId=${proxy.id} forTest=$forTest " +
                "endpoints=$endpointTags outbounds=$outboundTags " +
                "references=$references unresolved=$unresolvedTargets"
        )
        ConfigBuildResult(
            gson.toJson(configMap),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L
        )
    }

}
