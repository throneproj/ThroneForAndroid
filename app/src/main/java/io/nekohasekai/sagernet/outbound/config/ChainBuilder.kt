package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.types.Socks

/** ChainBuildRequest (generate.cpp:1413-1428). */
internal class ChainRequest(
    /** Exit first: index 0 is the hop whose address the traffic leaves from (unwrapChain, :1296-1308). */
    val hopIds: List<Long>,
    val prefix: String,
    val includeProxy: Boolean = false,
    val link: Boolean = true,
    val startSuffix: Int = 0,
    /** Pre-reserved bridge ports; anything <= 0 lets the chain reserve its own. */
    val singToXrayPort: Int = -1,
    val xrayToSingPort: Int = -1,
    val xrayFullConfigPort: Int = -1,
    /** Drops the user's own inbounds from a custom Xray full config (siblings would repeat the ports). */
    val soleXrayInbound: Boolean = false,
    /** Hop 0 is the WARP hop: it becomes `proxy` dialing through hop 1, which becomes `warp-bypass`. */
    val warpWrap: Boolean = false,
)

/** hopChainOptions (generate.cpp:1310-1319) without the auxiliary-endpoint members. */
internal class HopChainOptions(
    val prefix: String,
    val includeProxy: Boolean = false,
    val link: Boolean = true,
    val startSuffix: Int = 0,
    val markIngress: Boolean = false,
    val warpWrap: Boolean = false,
)

/** memberBridges (generate.cpp:1563-1574): the loopback bridges one auto-selector member chain will create. */
internal class MemberBridges(val singToXray: Boolean, val xrayToSing: Boolean, val xrayFullConfig: Boolean) {
    val count: Int get() = (if (singToXray) 1 else 0) + (if (xrayToSing) 1 else 0) + (if (xrayFullConfig) 1 else 0)
}

/** chainScan (generate.cpp:1204-1213). */
private class ChainScan {
    var hopCount = 0
    var extraCoreCount = 0
    var extraCoreIdx = -1
    var xrayFullConfigCount = 0
    var xrayFullConfigIdx = -1
    var xrayHopCount = 0
    var hasCustomFullConfig = false
    var coreTransitions = 0
}

/** socksBridgeInbound (generate.cpp:340-351): the sing-box side of an Xray -> sing-box bridge. */
internal fun socksBridgeInbound(tag: String, bridge: BridgeConfig): JsonObject = jsonObjectOf(
    "type" to "socks",
    "tag" to tag,
    "listen" to bridge.host,
    "listen_port" to bridge.port,
    "users" to JsonArray.of(jsonObjectOf("username" to bridge.auth, "password" to bridge.auth)),
)

/** xraySocksInbound (generate.cpp:353-368): the Xray side of a sing-box -> Xray bridge. */
internal fun xraySocksInbound(tag: String, bridge: BridgeConfig): JsonObject = jsonObjectOf(
    "tag" to tag,
    "listen" to bridge.host,
    "port" to bridge.port,
    "protocol" to "socks",
    "settings" to jsonObjectOf(
        "auth" to "password",
        "udp" to true,
        "accounts" to JsonArray.of(jsonObjectOf("user" to bridge.auth, "pass" to bridge.auth)),
    ),
)

/**
 * The hop-list machinery of generate.cpp:1204-1600: the core-transition scan, the sing-box and Xray chain builders,
 * the loopback bridges between the two cores and the built-in WARP hop ([warp], resolved for [WarpHop.PROFILE_ID]).
 * Auxiliary VPN endpoints are not generated on Android.
 */
internal class ChainBuilder(
    private val profiles: ProfileProvider,
    private val ctx: BuildContext,
    private val state: BuildState,
    private val warp: Outbound? = null,
) {
    /** usesXrayCore (generate.cpp:428-431). */
    fun usesXrayCore(outbound: Outbound): Boolean = outbound.isXray() || outbound.isXrayFullConfig()

    /**
     * proxyPathUsesXray (:434-454): a chain when any hop does, an auto-selector when any member of its build does
     * ([selectorBuild], the plan's build list), otherwise the profile itself.
     */
    fun proxyPathUsesXray(outbound: Outbound, selectorBuild: List<Long> = emptyList()): Boolean {
        val ids = when (outbound.type) {
            "chain" -> TypeAccess.chainHops(outbound)
            "autoselector" -> selectorBuild
            else -> return usesXrayCore(outbound)
        }
        for (id in ids) {
            val hop = profiles.get(id) ?: continue
            if (usesXrayCore(hop)) return true
        }
        return false
    }

    /** bridgesFor (:1576-1592), counted like [resolveHops] counts, so the pre-reserved ports line up with the chain. */
    fun bridgesFor(hopIds: List<Long>): MemberBridges {
        var singToXray = false
        var xrayToSing = false
        var xrayFullConfig = false
        var inXray = false
        for (id in hopIds) {
            val hop = profiles.get(id) ?: continue
            if (hop.isXrayFullConfig()) xrayFullConfig = true
            val xray = hop.isXray()
            if (xray && !inXray) singToXray = true
            if (!xray && inXray) xrayToSing = true
            inXray = xray
        }
        return MemberBridges(singToXray, xrayToSing, xrayFullConfig)
    }

    /** unwrapChain (:1296-1308): the hop ids exit first, i.e. the stored in -> out list reversed. */
    fun unwrapChain(id: Long): List<Long> {
        val outbound = profiles.get(id) ?: return emptyList()
        if (outbound.type == "chain") return TypeAccess.chainHops(outbound).asReversed()
        return listOf(id)
    }

    // chainScanError (:1215-1235)
    private fun chainScanError(scan: ChainScan): String {
        if (scan.hasCustomFullConfig)
            return "Custom full config profiles cannot be used in a chain; only custom outbound profiles are chainable"
        if (scan.extraCoreCount > 1)
            return "Only one extra-core profile is allowed in a chain"
        if (scan.xrayFullConfigCount > 1)
            return "Only one custom Xray full config profile is allowed in a chain"
        if (scan.extraCoreCount > 0 && scan.xrayFullConfigCount > 0)
            return "Extra-core and custom Xray full config profiles cannot be combined in a chain"
        if (scan.xrayFullConfigCount > 0 && scan.xrayHopCount > 0)
            return "Custom Xray full config cannot be combined with other Xray hops in a chain (only one Xray instance is supported at a time)"
        // The last hop is the entry side: an extra core's socks server must be dialed directly, nothing runs after it.
        if (scan.extraCoreCount == 1 && scan.hopCount > 1 && scan.extraCoreIdx != scan.hopCount - 1)
            return "Extra-core profiles can only be the final hop in a chain (top of the chain editor)"
        // Same for a custom Xray full config: traffic exits through its socks bridge into the user's Xray.
        if (scan.xrayFullConfigCount == 1 && scan.hopCount > 1 && scan.xrayFullConfigIdx != scan.hopCount - 1)
            return "Custom Xray full config can only be the final hop in a chain (top of the chain editor)"
        if (scan.coreTransitions > 2)
            return "Too many core transitions, the valid sequence is: (optional sing-box chain)->(optional xray chain)->(optional sing-box chain)"
        return ""
    }

    /**
     * entIDListtoEntList (:1237-1294): resolves the hops exit-first and records where the walk enters and leaves
     * the Xray core, so the caller knows which loopback bridges it must create.
     */
    private fun resolveHops(hopIds: List<Long>, hops: MutableList<Hop>): String {
        val scan = ChainScan()
        var inXray = false
        for (id in hopIds) {
            if (id == WarpHop.PROFILE_ID) {
                val warpHop = warp ?: return "Null proxy in chain, you may want to check your configs"
                if (inXray) {
                    state.xrayToSingTransitioned = true
                    scan.coreTransitions++
                }
                inXray = false
                hops.add(Hop(id, warpHop))
                continue
            }
            val outbound = profiles.get(id) ?: return "Null proxy in chain, you may want to check your configs"
            if (outbound.invalid) return "Profile $id has a type this build cannot use: ${outbound.type}"
            if (!inXray && outbound.isXray()) {
                state.singToXrayTransitioned = true
                scan.coreTransitions++
            }
            if (inXray && !outbound.isXray()) {
                state.xrayToSingTransitioned = true
                scan.coreTransitions++
            }
            inXray = outbound.isXray()
            if (outbound.type == "chain") return "Chain in Chain is not allowed"
            // A selector resolves to a different member over time; a chain hop has to stay put.
            if (outbound.type == "autoselector") return "An auto selector cannot be used as a hop; it is not a fixed server"
            if (outbound.isExtraCore()) {
                scan.extraCoreCount++
                scan.extraCoreIdx = hops.size
            }
            if (outbound.isXrayFullConfig()) {
                scan.xrayFullConfigCount++
                scan.xrayFullConfigIdx = hops.size
            }
            if (outbound.isXray()) scan.xrayHopCount++
            if (TypeAccess.isCustomFullConfig(outbound)) scan.hasCustomFullConfig = true
            hops.add(Hop(id, outbound))
        }
        scan.hopCount = hops.size
        return chainScanError(scan)
    }

    /**
     * Stands in for the desktop's IsValid (generate.cpp:2408-2525) before a test candidate is built: the hop walk
     * and every hop's Build() / BuildXray() run once, and the first error is the reason to skip the candidate.
     */
    fun candidateError(hopIds: List<Long>): String {
        val hops = ArrayList<Hop>()
        val scanError = resolveHops(hopIds, hops)
        if (scanError.isNotEmpty()) return scanError
        if (hops.isEmpty()) return "The profile has no hops"
        for (hop in hops) {
            val outbound = hop.outbound
            if (outbound.isXrayFullConfig()) {
                val custom = TypeAccess.asCustom(outbound) ?: return "Failed to cast to Custom for Xray full config hop"
                if (custom.configObject().isEmpty()) return "Custom Xray full config is not valid JSON"
            } else {
                val result = if (outbound.isXray()) outbound.buildXray(ctx) else outbound.build(ctx)
                if (!result.ok) return result.error
            }
        }
        return ""
    }

    /**
     * buildOutboundChain (:1430-1560). Returns the ingress tag (the hop the rules name) and reports failures through
     * [BuildState.error].
     *
     * Bridge topology: the hop list is split at the first Xray hop into leading sing-box hops (exit side), the Xray
     * hops, and tailing sing-box hops (entry side). The last leading sing-box hop detours into a synthetic socks hop
     * that dials the Xray socks inbound (sing-box -> Xray); the last Xray hop dials a socks outbound that lands on the
     * sing-box `bridge-<tag>` inbound, whose route rule sends it into the first tailing hop (Xray -> sing-box).
     */
    fun buildOutboundChain(req: ChainRequest): String {
        val ingressTag = if (req.includeProxy) Tags.PROXY else hopTag(req.prefix, req.startSuffix)

        state.singToXrayTransitioned = false
        state.xrayToSingTransitioned = false
        val hops = ArrayList<Hop>()
        val scanError = resolveHops(req.hopIds, hops)
        if (scanError.isNotEmpty()) {
            state.error = scanError
            return ingressTag
        }

        val last = hops.lastOrNull()
        if (last != null && last.outbound.isXrayFullConfig()) {
            val custom = TypeAccess.asCustom(last.outbound)
            if (custom == null) {
                state.error = "Failed to cast to Custom for Xray full config hop"
                return ingressTag
            }
            val userXrayConfig = custom.configObject()
            if (userXrayConfig.isEmpty()) {
                state.error = "Custom Xray full config is not valid JSON"
                return ingressTag
            }
            // A pre-reserved 0 means the caller's reservation failed; reserve again rather than bake in a dead port.
            var port = req.xrayFullConfigPort
            if (port <= 0) port = LocalPorts.reserve(1, custom.bridgeHost)[0]
            if (port <= 0) {
                state.error = "Could not reserve a local port for the custom Xray full config bridge"
                return ingressTag
            }
            // Custom.build() emits the socks outbound to this bridge when the hop is built (custom.h:127-135).
            custom.bridgePort = port
            custom.bridgeAuth = LocalPorts.randomAuth(32)
            val bridgeInbound = xraySocksInbound(
                Tags.XRAY_FULL_CONFIG_IN, BridgeConfig(true, custom.bridgePort, custom.bridgeAuth, custom.bridgeHost),
            )
            bridgeInbound["sniffing"] = jsonObjectOf(
                "enabled" to true,
                "destOverride" to JsonArray.of("http", "tls", "quic"),
                "routeOnly" to false,
            )
            val inbounds = JsonArray()
            inbounds.add(bridgeInbound)
            if (!state.forTest && !req.soleXrayInbound) for (item in userXrayConfig.array("inbounds")) inbounds.add(item)
            userXrayConfig["inbounds"] = inbounds
            state.xrayConfig = userXrayConfig
            state.isXrayNeeded = true
        }

        val initialSingHops = ArrayList<Hop>()
        val xrayHops = ArrayList<Hop>()
        val tailingSingHops = ArrayList<Hop>()
        for (hop in hops) {
            if (hop.outbound.isXray()) xrayHops.add(hop)
            else if (xrayHops.isEmpty()) initialSingHops.add(hop)
            else tailingSingHops.add(hop)
        }
        val ports = ArrayList<Int>()
        fun bridgePort(given: Int): Int {
            if (given > 0) return given
            if (ports.isEmpty()) ports.addAll(LocalPorts.reserve(2))
            return ports.removeAt(0)
        }
        if (state.singToXrayTransitioned) {
            val port = bridgePort(req.singToXrayPort)
            if (port <= 0) {
                state.error = "Could not reserve a local port for the sing-box -> Xray bridge"
                return ingressTag
            }
            val bridge = BridgeConfig(true, port, LocalPorts.randomAuth(32))
            state.singToXrayBridges.add(bridge)
            val socks = Socks()
            socks.username = bridge.auth
            socks.password = bridge.auth
            socks.server = bridge.host
            socks.serverPort = bridge.port
            initialSingHops.add(Hop(-1, socks))
        }
        var xrayToSingBridge = BridgeConfig()
        if (state.xrayToSingTransitioned) {
            val port = bridgePort(req.xrayToSingPort)
            if (port <= 0) {
                state.error = "Could not reserve a local port for the Xray -> sing-box bridge"
                return ingressTag
            }
            xrayToSingBridge = BridgeConfig(true, port, LocalPorts.randomAuth(32))
            state.xrayToSingBridges.add(xrayToSingBridge)
        }

        val leadingOpts = HopChainOptions(req.prefix, req.includeProxy, req.link, req.startSuffix, markIngress = false, warpWrap = req.warpWrap)
        // The synthetic bridge socks hop counts, so the tailing numbering continues after it (D.8: proxy, config-1, config-2).
        val tailingStartSuffix = req.startSuffix + initialSingHops.size
        if (initialSingHops.isNotEmpty()) {
            buildSingboxChain(initialSingHops, leadingOpts)
            if (state.failed) return ingressTag
        }
        if (xrayHops.isNotEmpty()) {
            buildXrayChain(xrayHops, leadingOpts, xrayToSingBridge)
            if (state.failed) return ingressTag
        }
        if (tailingSingHops.isNotEmpty()) {
            buildSingboxChain(tailingSingHops, HopChainOptions(req.prefix, false, req.link, tailingStartSuffix, markIngress = true))
        }
        return ingressTag
    }

    /** resolvesHostnamesViaDnsRules (generate.cpp:1334-1338). */
    private fun resolvesHostnamesViaDnsRules(hop: Hop): Boolean =
        hop.outbound.isEndpoint() || TypeAccess.asSocks(hop.outbound)?.version == 4

    /**
     * buildSingboxChain (:1321-1369). Hop idx gets `<prefix>-<startSuffix+idx>` (idx 0 is `proxy` for the main
     * chain) and, when linked, `detour` to the next tag: the exit is dialed through the next hop, and so on until the
     * entry hop, which is dialed directly. Under [HopChainOptions.warpWrap] idx 0 is WARP as `proxy` and idx 1 takes
     * `warp-bypass`, the tag rules name to skip WARP.
     */
    private fun buildSingboxChain(hops: List<Hop>, opts: HopChainOptions) {
        for (idx in hops.indices) {
            var tag = hopTag(opts.prefix, opts.startSuffix + idx)
            val nextTag = if (idx < hops.size - 1) hopTag(opts.prefix, opts.startSuffix + idx + 1) else ""
            if (opts.includeProxy && idx == 0) tag = Tags.PROXY
            if (opts.warpWrap && idx == 1) tag = Tags.WARP_BYPASS
            if (opts.markIngress && idx == 0) state.singIngressTags.add(tag)
            val hop = hops[idx]
            val result = hop.outbound.build(ctx)
            if (!result.ok) {
                state.error += result.error
                return
            }
            val obj = result.json
            obj["tag"] = tag
            // Realm reads its STUN resolver off this key only; without it the hosts go through DNS rules (:1356-1358).
            if (TypeAccess.realmActive(hop.outbound)) obj["domain_resolver"] = jsonObjectOf("server" to Tags.DNS_DIRECT)
            if (nextTag.isNotEmpty() && opts.link) obj["detour"] = nextTag
            if (opts.warpWrap && idx == 0) obj["detour"] = Tags.WARP_BYPASS
            // A detour gets the server hostname unresolved; endpoints look it up via DNS rules, which end at dns-remote over the proxy.
            if (nextTag.isNotEmpty() && opts.link && !hop.outbound.isEndpoint() && !obj.contains("domain_resolver") &&
                resolvesHostnamesViaDnsRules(hops[idx + 1])
            ) obj["domain_resolver"] = directDomainResolver(ctx)
            if (hop.outbound.isEndpoint()) state.endpoints.add(obj) else state.outbounds.add(obj)
        }
    }

    /**
     * buildXrayChain (:1371-1411). Numbering restarts at startSuffix inside the Xray namespace (not offset by the
     * leading sing-box hops); hops link with `streamSettings.sockopt.dialerProxy`, and the last one dials the socks
     * outbound of the Xray -> sing-box bridge when there is one.
     */
    private fun buildXrayChain(hops: List<Hop>, opts: HopChainOptions, bridge: BridgeConfig) {
        for (idx in hops.indices) {
            var tag = hopTag(opts.prefix, opts.startSuffix + idx)
            val nextTag = if (idx < hops.size - 1 || bridge.needed) hopTag(opts.prefix, opts.startSuffix + idx + 1) else ""
            if (opts.includeProxy && idx == 0) tag = Tags.PROXY
            if (idx == 0) state.xrayIngressTags.add(tag)
            val result = hops[idx].outbound.buildXray(ctx)
            if (!result.ok) {
                state.error += result.error
                return
            }
            val obj = result.json
            obj["tag"] = tag
            // Xray removed outbound-level proxySettings; sockopt.dialerProxy is its 1:1 replacement.
            if (nextTag.isNotEmpty() && (opts.link || bridge.needed)) {
                val stream = obj.obj("streamSettings")
                val sockopt = stream.obj("sockopt")
                sockopt["dialerProxy"] = nextTag
                stream["sockopt"] = sockopt
                obj["streamSettings"] = stream
            }
            state.xrayOutbounds.add(obj)
        }
        if (bridge.needed) {
            state.xrayOutbounds.add(
                jsonObjectOf(
                    "tag" to hopTag(opts.prefix, opts.startSuffix + hops.size),
                    "protocol" to "socks",
                    "settings" to jsonObjectOf(
                        "address" to bridge.host,
                        "port" to bridge.port,
                        "user" to bridge.auth,
                        "pass" to bridge.auth,
                    ),
                ),
            )
        }
    }
}
