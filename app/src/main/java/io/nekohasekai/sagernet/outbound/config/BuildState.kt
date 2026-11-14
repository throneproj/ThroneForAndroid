package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.types.Dnstt
import io.nekohasekai.sagernet.outbound.types.MasterDns
import java.util.TreeMap

/** The tag vocabulary of generate.cpp:43-74. */
internal object Tags {
    const val PROXY = "proxy"
    const val DIRECT = "direct"
    const val WARP_BYPASS = "warp-bypass"

    const val DNS_REMOTE = "dns-remote"
    const val DNS_DIRECT = "dns-direct"
    const val DNS_LOCAL = "dns-local"
    const val DNS_FAKE = "dns-fake"
    const val DNS_HOSTS = "dns-hosts"
    const val DNS_ECH_PREFIX = "dns-ech"

    const val DNS_IN = "dns-in"
    const val MIXED_IN = "mixed-in"
    const val TUN_IN = "tun-in"
    const val XRAY_FULL_CONFIG_IN = "throne-bridge"

    const val MAIN_CHAIN_PREFIX = "config"
    const val ROUTE_CHAIN_PREFIX = "route"
    const val POOL_CHAIN_PREFIX = "pool"
    const val SELECTOR_UPSTREAM_PREFIX = "upstream"
    const val CHAIN_SELECTOR = "auto-selector"
    const val TEST_CHAIN_PREFIX = "proxy"
    const val TEST_XRAY_FULL_PREFIX = "xrayfull"
    const val BRIDGE_PREFIX = "bridge"
}

/** hopTag (generate.cpp:76). */
internal fun hopTag(prefix: String, index: Int): String = "$prefix-$index"

/** dnsRoleTags (generate.cpp): the server tags of a DNS role, the lead keeping the plain tag that resolvers pin. */
internal fun dnsRoleTags(lead: String, count: Int): List<String> = List(count) { if (it == 0) lead else hopTag(lead, it) }

/** DnsRace (generate.cpp): a role tag (dns-remote, dns-direct) -> its servers, lead first, and the race groups emitted. */
internal class DnsRace {
    val members = HashMap<String, List<String>>()
    var groups = 0
}

/**
 * bridgeTagFor (generate.cpp): the sing-box inbound the Xray -> sing-box bridge [idx] lands on. Pool members bridging
 * into one shared upstream repeat its ingress tag, so a repeat takes the count of the ones before it as a suffix.
 */
internal fun bridgeTagFor(singIngressTags: List<String>, idx: Int): String {
    val ingress = singIngressTags[idx]
    val repeats = (0 until idx).count { singIngressTags[it] == ingress }
    val tag = "${Tags.BRIDGE_PREFIX}-$ingress"
    return if (repeats == 0) tag else "$tag-$repeats"
}

/** coreBridgeConfig (generate.cpp:173-178). */
internal class BridgeConfig(
    val needed: Boolean = false,
    val port: Int = -1,
    val auth: String = "",
    val host: String = "127.0.0.1",
)

/**
 * One resolved hop of a chain: the stored profile's outbound, or a synthetic one the generator created (the socks
 * hop into the Xray inbound, generate.cpp:1502-1508, with id -1).
 */
internal class Hop(val id: Long, val outbound: Outbound)

/** DomainSelectors (generate.cpp:85-95): the prefixed selector lists of one DNS routing target. */
internal class DomainSelectors {
    val ruleSets = JsonArray()
    val domains = JsonArray()
    val suffixes = JsonArray()
    val keywords = JsonArray()
    val regexes = JsonArray()

    fun hasInlineConditions(): Boolean =
        domains.isNotEmpty() || suffixes.isNotEmpty() || keywords.isNotEmpty() || regexes.isNotEmpty()
}

/**
 * RouteOutboundGroup (generate.cpp): a route outbound's hops wrapped in the landing / front proxy of [group], the
 * route outbound's own group, which a deleted hop then names.
 */
internal class RouteOutboundGroup(val hopIds: List<Long>, val group: ChainGroup?)

/**
 * BuildPrerequisites (generate.cpp:137-171) as far as Android generates them: no DNS-server hijack selectors, no
 * auxiliary endpoints and no address sets. Test builds leave it empty.
 */
internal class Prerequisites {
    var needDirectDnsRules = false
    val directDns = DomainSelectors()
    var needProxyDnsRules = false
    val proxyDns = DomainSelectors()

    /** The direct / proxy rules that depend on the network, in rule order: (DNS server tag, network conditions, sites). */
    val conditionalDns = ArrayList<Triple<String, JsonObject, DomainSelectors>>()

    /** The `ip:` values of the route -> direct rules; they bypass the tun only with enable_tun_routing. */
    val directIpCidrs = ArrayList<String>()

    /** vpn_private_ranges minus every range a non-direct rule takes away from direct (#1741). */
    val bypassedPrivateRanges = ArrayList<String>()

    /** -1 proxy, -2 direct, -5 warp-bypass (proxy without WARP) and every route outbound profile id -> its `route-<n>` tag. */
    val outboundMap = HashMap<Long, String>()

    /** One hop list per route outbound, exit first. */
    val routeOutboundGroups = ArrayList<RouteOutboundGroup>()

    /** route.rule_set: tag -> download URL, first-seen order. */
    val ruleSets = LinkedHashMap<String, String>()
}

/** The per-build state of generate.cpp:180-207 that the sections share; [ports] takes every bridge port reserved. */
internal class BuildState(val forTest: Boolean, val ports: PortLease) {
    var singToXrayTransitioned = false
    var xrayToSingTransitioned = false
    var proxyUsesXray = false
    val prerequisites = Prerequisites()

    var error = ""
    val outbounds = JsonArray()
    val endpoints = JsonArray()
    val xrayOutbounds = JsonArray()
    val xrayIngressTags = ArrayList<String>()
    val singIngressTags = ArrayList<String>()
    val singToXrayBridges = ArrayList<BridgeConfig>()
    val xrayToSingBridges = ArrayList<BridgeConfig>()

    /** Names whose HTTPS record a hop's ECH needs to fetch; they must not resolve over the proxy they unlock. */
    val echQueryNames = ArrayList<String>()

    /** DNS tunnel domains; a loopback stub resolver forwarding them back through the Tun must not reach dns-remote. */
    val dnsTunnelDomains = ArrayList<String>()

    /** ECH query name -> the hop's own resolver, key-sorted like the desktop's QMap (generate.cpp:209). */
    val echResolvers = TreeMap<String, String>()

    // BuildConfigResult (generate.h:35-51)
    val coreConfig = JsonObject()
    var xrayConfig = JsonObject()
    var isXrayNeeded = false
    var tunIPv4Cidr: String? = null
    val xrayFullConfigs = ArrayList<String>()
    var autoSelector: AutoSelectorBuild? = null
    val routeRuleOrigins = ArrayList<RouteRuleOrigin>()

    val failed: Boolean get() = error.isNotEmpty()

    /**
     * collectEchQueryName (generate.cpp:1403-1420): without a static config the core fetches the ECH list over DNS
     * first, and via dns-remote that dials the same hop. The core queries query_server_name when set, else the TLS
     * server name, else the dial host.
     */
    fun collectEchQueryName(hop: Outbound) {
        if (!hop.hasTls()) return
        val tls = hop.getTls()
        if (!tls.enabled || !tls.ech.enabled) return
        if (tls.ech.config.isNotEmpty() || tls.ech.config_path.isNotEmpty()) return
        val name = Hosts.toAceHost(tls.ech.serverName.ifEmpty { tls.server_name }.ifEmpty { hop.server }.trim())
        if (name.isEmpty() || Hosts.isIpAddress(name)) return
        if (DnsServers.usableEchResolver(tls.ech.resolver)) {
            if (!echResolvers.containsKey(name)) echResolvers[name] = tls.ech.resolver
        } else if (!echQueryNames.contains(name)) {
            echQueryNames.add(name)
        }
    }

    /** collectDnsTunnelDomains (generate.cpp). */
    fun collectDnsTunnelDomains(hop: Outbound) {
        val domains = when (hop) {
            is Dnstt -> listOf(hop.domain)
            is MasterDns -> hop.domains
            else -> return
        }
        for (domain in domains) {
            // sing-box lower-cases the query name but matches domain_suffix entries verbatim.
            val name = Hosts.toAceHost(domain.trim()).lowercase().removeSuffix(".")
            if (name.isNotEmpty() && !dnsTunnelDomains.contains(name)) dnsTunnelDomains.add(name)
        }
    }

    /** bridgeIngressMismatch (generate.cpp:209-213). */
    fun bridgeIngressMismatch(): String =
        if (xrayToSingBridges.size != singIngressTags.size) "xray to sing-box bridges count does not match ingress tags count" else ""
}
