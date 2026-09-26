package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.route.RuleType

/**
 * The desktop's config generator (src/configs/generate.cpp) for the Android app: [build] is BuildSingBoxConfig
 * (:2319-2395) for one selected profile under the route profile of [routing], [buildTest] is BuildTestConfig
 * (:2544-2693) for a batch of URL-test candidates. Sections are emitted in the desktop's order and serialised
 * compact with sorted keys, so a config matches the desktop's byte for byte wherever the inputs match.
 *
 * Not generated on Android (desktop-only or out of scope): raw route profiles, the `hijack` / `hijack-dns`
 * inbounds, route_exclude_address_set, TLS spoof, extra cores, auxiliary VPN endpoints and the OpenVPN /
 * OpenConnect tunnel DNS servers, the L3 bridge, the api dashboard and Tailscale profiles.
 */
class ConfigGenerator @JvmOverloads constructor(
    private val profiles: ProfileProvider,
    private val settings: GeneratorSettings,
    private val buildContext: BuildContext = BuildContext.DEFAULT,
    private val routing: RoutingInput = RoutingInput.DEFAULT,
    /** Unix seconds: result freshness and warm-health ages of auto-selector members. */
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /**
     * The config for the started profile [profileId]; [landingProxyId] becomes the exit and [frontProxyId] the
     * entry of the main chain when > 0 (the group's landing / front proxy, generate.cpp:1815-1832). An auto-selector
     * builds its members with its tracked group's landing / front proxy instead, the group its plan checked.
     */
    @JvmOverloads
    fun build(profileId: Long, landingProxyId: Long = -1, frontProxyId: Long = -1): GeneratedConfig {
        val reads = RecordingProfiles(profiles)
        val profile = reads.get(profileId) ?: return GeneratedConfig.failure("Profile $profileId does not exist")
        if (profile.invalid) return GeneratedConfig.failure("Profile $profileId has a type this build cannot use: ${profile.type}")
        // A custom full config is the whole core config (:2320-2350), never wrapped in WARP.
        val custom = TypeAccess.asCustom(profile)
        if (custom != null && custom.isFullConfig()) {
            val core = custom.build(buildContext).json
            if (core.isEmpty()) return GeneratedConfig.failure("Custom full config is not a valid JSON object")
            return GeneratedConfig(
                coreConfig = core.toCompact(),
                xrayConfig = null,
                needXray = false,
                xrayDnsStrategy = "",
                xrayFullConfigs = emptyList(),
                outboundTags = emptyList(),
                tagToProfileId = emptyMap(),
                fullConfigs = emptyMap(),
                skipped = emptyMap(),
                tunIPv4Cidr = tunIPv4CidrOf(core),
                error = null,
                involvedProfileIds = reads.ids,
            )
        }
        val route = routing.profile?.let(::routeProfileForBuild)
            ?: return GeneratedConfig.failure("Routing profile does not exist, try resetting the route profile in Routing Settings")

        // One plan serves the Xray decision and the build (the desktop plans twice, :446-452 and :1631).
        val plan = (profile as? AutoSelector)?.let { AutoSelectorPlanner(routing.selectors, clock).plan(profileId, it) }
        val landing = if (plan != null) plan.group?.landingProxyId ?: -1L else landingProxyId
        val front = if (plan != null) plan.group?.frontProxyId ?: -1L else frontProxyId

        val state = BuildState(forTest = false)
        val chains = ChainBuilder(reads, buildContext, state, if (settings.enableWarp) WarpHop.outbound(settings) else null)
        calculatePrerequisites(state, chains, reads, profile, route, landing, front, plan)
        if (state.failed) return GeneratedConfig.failure(state.error)

        buildLogSection(state)
        buildNtpSection(state)
        buildCertificateSection(state)
        buildInboundSection(state)
        buildOutboundsSection(state, chains, reads, profile, profileId, landing, front, plan)
        if (state.failed) return GeneratedConfig.failure(state.error)
        buildDnsSection(state, useDnsObj = true)
        buildRouteSection(state, route)
        if (state.failed) return GeneratedConfig.failure(state.error)
        buildExperimentalSection(state)
        buildServicesSection(state)
        buildXrayConfig(state)
        if (state.failed) return GeneratedConfig.failure(state.error)

        val anyXray = state.isXrayNeeded || state.xrayFullConfigs.isNotEmpty()
        return GeneratedConfig(
            coreConfig = state.coreConfig.toCompact(),
            xrayConfig = if (state.isXrayNeeded) state.xrayConfig.toCompact() else null,
            needXray = state.isXrayNeeded,
            xrayDnsStrategy = if (anyXray) buildContext.xrayOutboundDomainStrategy() else "",
            xrayFullConfigs = ArrayList(state.xrayFullConfigs),
            outboundTags = emptyList(),
            tagToProfileId = emptyMap(),
            fullConfigs = emptyMap(),
            skipped = emptyMap(),
            tunIPv4Cidr = state.tunIPv4Cidr,
            error = null,
            involvedProfileIds = reads.ids,
            autoSelector = state.autoSelector,
        )
    }

    /**
     * The profile the build works on: a copy (the desktop copies it too, :1964), with the Linux-only `bypass` action
     * turned into `route` to the same outbound (D12), so it also feeds the DNS and tun lists like any route rule.
     */
    private fun routeProfileForBuild(profile: RouteProfile): RouteProfile = profile.copy().also { copy ->
        for (rule in copy.rules) if (rule.action == "bypass") rule.action = "route"
    }

    /**
     * calculatePrerequisites (:542-745) minus auxiliary endpoints, the DNS-server hijack and extra cores: the WARP
     * identity check, the Xray decision, the route outbounds, the rule-sets, the DNS site lists and the tun exclusions.
     */
    private fun calculatePrerequisites(
        state: BuildState, chains: ChainBuilder, reads: ProfileProvider, profile: Outbound, route: RouteProfile,
        landingProxyId: Long, frontProxyId: Long, plan: AutoSelectorPlan?,
    ) {
        val pre = state.prerequisites
        if (settings.enableWarp && WarpHop.missing(settings)) {
            state.error = WarpHop.MISSING_ERROR
            return
        }
        pre.outboundMap[OutboundIds.PROXY] = Tags.PROXY
        pre.outboundMap[OutboundIds.DIRECT] = Tags.DIRECT
        pre.outboundMap[OutboundIds.WARP_BYPASS] = if (settings.enableWarp) Tags.WARP_BYPASS else Tags.PROXY
        state.proxyUsesXray = chains.proxyPathUsesXray(profile, plan?.build ?: emptyList())

        // Route outbounds (:580-618), each profile once (D12): a chain takes as many suffixes as it has hops.
        var suffix = 0
        for (id in route.usedOutboundIds()) {
            if (id < 0) continue
            val needed = reads.get(id)
            if (needed == null) {
                state.error = "The routing profile is referencing outbounds that no longer exist, consider revising your settings"
                return
            }
            if (needed.isExtraCore() || TypeAccess.isCustomFullConfig(needed) || needed.isXrayFullConfig()) {
                state.error = "Outbounds used in routing profile cannot use an extra core or be a custom full config"
                return
            }
            if (needed.type == "chain") {
                val hops = TypeAccess.chainHops(needed)
                if (hops.isEmpty()) {
                    state.error = "Chain outbound in routing profile is empty or corrupted"
                    return
                }
                for (hopId in hops) {
                    val hop = reads.get(hopId)
                    if (hop == null) {
                        state.error = "Chain outbound in routing profile contains a missing profile"
                        return
                    }
                    if (hop.isExtraCore() || TypeAccess.isCustomFullConfig(hop) || hop.isXrayFullConfig() || hop.type == "chain") {
                        state.error = "Chain hops in routing profile cannot use an extra core, a custom full config, or be of type chain"
                        return
                    }
                    if (chains.usesXrayCore(hop)) state.proxyUsesXray = true
                }
                pre.outboundMap[id] = hopTag(Tags.ROUTE_CHAIN_PREFIX, suffix)
                pre.routeOutboundGroups.add(hops.asReversed().toList())
                suffix += hops.size
            } else {
                if (chains.usesXrayCore(needed)) state.proxyUsesXray = true
                pre.outboundMap[id] = hopTag(Tags.ROUTE_CHAIN_PREFIX, suffix++)
                pre.routeOutboundGroups.add(listOf(id))
            }
        }

        collectRuleSets(state, route)
        if (state.failed) return

        if (settings.enableDnsRouting) {
            val directSites = route.directSites()
            parseDomainSelectors(directSites, pre.directDns)
            pre.needDirectDnsRules = directSites.isNotEmpty()
            // With a direct final DNS these need an explicit remote-DNS carve-out.
            val proxySites = route.proxySites()
            parseDomainSelectors(proxySites, pre.proxyDns)
            pre.needProxyDnsRules = proxySites.isNotEmpty()
        }

        for (id in listOf(frontProxyId, landingProxyId)) {
            if (id <= 0) continue
            val groupProxy = reads.get(id) ?: continue
            if (chains.usesXrayCore(groupProxy)) state.proxyUsesXray = true
        }

        parseSelectorList(route.directIps()) { prefix, value -> if (prefix == "ip:") pre.directIpCidrs.add(value) }

        if (settings.bypassLan) {
            // sing-tun keeps an excluded range out of the tun entirely, so a rule aimed at one would never fire (#1741).
            val hijacked = route.hijackedIps(settings.privateRanges)
            for (range in settings.privateRanges) {
                if (hijacked.none { Cidrs.overlap(range, it) }) pre.bypassedPrivateRanges.add(range)
            }
        }
    }

    /**
     * get_used_rule_sets resolved the way buildRuleSetArray does (:1916-1936): an `.srs` URL downloads verbatim under
     * its hashed tag, a srslist name through the ruleset_mirror. Entries are trimmed and deduplicated by tag (the core
     * refuses a repeated tag), and an unknown name fails here instead of at core start (D12).
     */
    private fun collectRuleSets(state: BuildState, route: RouteProfile) {
        val sets = state.prerequisites.ruleSets
        for ((index, rule) in route.rules.withIndex()) {
            if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) continue
            for (raw in rule.rule_set) {
                val entry = raw.trim()
                if (entry.isEmpty()) continue
                val tag = RuleSets.tagFor(entry)
                if (sets.containsKey(tag)) continue
                if (RuleSets.isUrl(entry)) {
                    sets[tag] = entry
                    continue
                }
                val listed = if (entry == RuleSets.ADBLOCK_TAG) RuleSets.ADBLOCK_URL else routing.catalog.urlOf(entry)
                if (listed == null) {
                    val ruleName = rule.name.ifBlank { "#${index + 1}" }
                    state.error = "Unknown rule-set \"$entry\" in rule \"$ruleName\" of routing profile \"${route.name}\""
                    return
                }
                sets[tag] = RuleSets.mirrorLink(listed, settings.rulesetMirror)
            }
        }
    }

    private fun parseDomainSelectors(items: List<String>, sink: DomainSelectors) = parseSelectorList(items) { prefix, value ->
        when (prefix) {
            "ruleset:" -> sink.ruleSets.add(value)
            "domain:" -> sink.domains.add(value)
            "suffix:" -> sink.suffixes.add(value)
            "keyword:" -> sink.keywords.add(value)
            "regex:" -> sink.regexes.add(value)
        }
    }

    /** parseSelectorList (:116-134): the first prefix a trimmed item starts with takes the trimmed rest, if any. */
    private fun parseSelectorList(items: List<String>, sink: (prefix: String, value: String) -> Unit) {
        for (raw in items) {
            val item = raw.trim()
            val prefix = SELECTOR_PREFIXES.firstOrNull { item.startsWith(it) } ?: continue
            val value = item.substring(prefix.length).trim()
            if (value.isNotEmpty()) sink(prefix, value)
        }
    }

    /**
     * One test box for every candidate at once (BuildTestConfig, :2544-2693): candidate n is a chain under the
     * prefix `proxy-<n>` whose ingress tag `proxy-<n>-0` is reported in [GeneratedConfig.outboundTags]; there is no
     * `proxy` tag, no inbounds except the Xray -> sing-box bridges, no experimental or services section, and the
     * DNS falls through to dns-direct. Xray candidates share one Xray config; custom Xray full configs each get
     * their own instance ([GeneratedConfig.xrayFullConfigs]).
     */
    fun buildTest(candidates: List<TestCandidate>): GeneratedConfig {
        val ctx = buildContext.copy(buildingTestConfig = true)
        val state = BuildState(forTest = true)
        val chains = ChainBuilder(profiles, ctx, state)
        buildDnsSection(state, useDnsObj = false)
        buildLogSection(state)
        buildCertificateSection(state)
        buildNtpSection(state)

        val outboundTags = ArrayList<String>()
        val tagToProfileId = LinkedHashMap<String, Long>()
        val fullConfigs = LinkedHashMap<Long, String>()
        val xrayFullConfigs = ArrayList<String>()
        val skipped = LinkedHashMap<Long, String>()

        val resolved = candidates.map { it to profiles.get(it.id) }
        var xrayCount = 0
        var chainCount = 0
        for ((_, outbound) in resolved) {
            if (outbound == null) continue
            if (outbound.isXray()) xrayCount++
            if (outbound.type == "chain") chainCount++
        }
        // Reserved in one batch so no two candidates collide; every chain is assumed to transition twice (:2553-2554).
        val xrayPorts = LocalPorts.reserve(xrayCount + 2 * chainCount)
        var xrayPortIdx = 0
        var suffix = 1

        for ((candidate, outbound) in resolved) {
            val id = candidate.id
            if (outbound == null) {
                skipped[id] = "Profile does not exist"
                continue
            }
            val skipReason = classifyTestCandidate(outbound)
            if (skipReason != null) {
                skipped[id] = skipReason
                continue
            }
            if (outbound.invalid) {
                skipped[id] = "Unsupported profile type: ${outbound.type}"
                continue
            }
            if (outbound.isXrayFullConfig()) {
                val invalid = chains.candidateError(listOf(id))
                if (invalid.isNotEmpty()) {
                    skipped[id] = invalid
                    continue
                }
                // The single xrayConfig slot is drained per full config so they all share one sing-box (:2571-2587).
                val tag = chains.buildOutboundChain(ChainRequest(listOf(id), prefix = "${Tags.TEST_XRAY_FULL_PREFIX}-$id"))
                if (state.failed) return GeneratedConfig.failure(state.error)
                if (!state.isXrayNeeded || state.xrayConfig.isEmpty()) {
                    skipped[id] = "Custom Xray full config produced no Xray config"
                    continue
                }
                xrayFullConfigs.add(state.xrayConfig.toCompact())
                state.xrayConfig = JsonObject()
                state.isXrayNeeded = false
                outboundTags.add(tag)
                tagToProfileId[tag] = id
                continue
            }
            val customCandidate = TypeAccess.asCustom(outbound)
            if (customCandidate != null && customCandidate.isFullConfig()) {
                // Passed through as its own config with the inbounds emptied (:2606-2612).
                val obj = customCandidate.configObject()
                obj["inbounds"] = JsonArray()
                fullConfigs[id] = obj.toCompact()
                continue
            }
            val hopIds = ArrayList<Long>()
            if (candidate.landingProxyId > 0) hopIds.add(candidate.landingProxyId)
            hopIds.addAll(chains.unwrapChain(id))
            if (candidate.frontProxyId > 0) hopIds.add(candidate.frontProxyId)
            val invalid = chains.candidateError(hopIds)
            if (invalid.isNotEmpty()) {
                skipped[id] = invalid
                continue
            }
            var singToXrayPort = -1
            var xrayToSingPort = -1
            if (outbound.isXray()) singToXrayPort = xrayPorts[xrayPortIdx++]
            if (outbound.type == "chain") {
                singToXrayPort = xrayPorts[xrayPortIdx++]
                xrayToSingPort = xrayPorts[xrayPortIdx++]
            }
            val tag = chains.buildOutboundChain(
                ChainRequest(
                    hopIds, hopTag(Tags.TEST_CHAIN_PREFIX, suffix),
                    singToXrayPort = singToXrayPort, xrayToSingPort = xrayToSingPort,
                ),
            )
            if (state.failed) return GeneratedConfig.failure(state.error)
            outboundTags.add(tag)
            tagToProfileId[tag] = id
            suffix++
        }

        buildXrayConfig(state)
        if (state.failed) return GeneratedConfig.failure(state.error)
        state.outbounds.add(jsonObjectOf("type" to "direct", "tag" to Tags.DIRECT))
        state.coreConfig["outbounds"] = state.outbounds
        state.coreConfig["endpoints"] = state.endpoints
        val inbounds = JsonArray()
        val routeRules = JsonArray()
        for (i in state.xrayToSingBridges.indices) {
            val bridge = state.xrayToSingBridges[i]
            inbounds.add(socksBridgeInbound("${Tags.BRIDGE_PREFIX}-${bridge.port}", bridge))
            // The desktop never fills its rule list here (:2656-2668), which leaves a bridge on the box's first
            // outbound; routing it into its tailing hop like the main config does is what the bridge is for.
            if (i < state.singIngressTags.size) {
                routeRules.add(jsonObjectOf("inbound" to "${Tags.BRIDGE_PREFIX}-${bridge.port}", "action" to "route", "outbound" to state.singIngressTags[i]))
            }
        }
        val xrayDnsStrategy = if (state.isXrayNeeded || xrayFullConfigs.isNotEmpty()) ctx.xrayOutboundDomainStrategy() else ""
        // A running tun owns the OS resolver, so the sidecar resolves against the probe box's dns-direct instead.
        val route = jsonObjectOf(
            "auto_detect_interface" to true,
            "default_domain_resolver" to directDomainResolver(ctx),
        )
        if (routeRules.isNotEmpty()) route["rules"] = routeRules
        state.coreConfig["route"] = route
        state.coreConfig["inbounds"] = inbounds

        return GeneratedConfig(
            coreConfig = state.coreConfig.toCompact(),
            xrayConfig = if (state.isXrayNeeded) state.xrayConfig.toCompact() else null,
            needXray = state.isXrayNeeded,
            xrayDnsStrategy = xrayDnsStrategy,
            xrayFullConfigs = xrayFullConfigs,
            outboundTags = outboundTags,
            tagToProfileId = tagToProfileId,
            fullConfigs = fullConfigs,
            skipped = skipped,
            tunIPv4Cidr = null,
            error = null,
        )
    }

    /** classifyTestCandidate (:2236-2259): the skip reason, or null when the candidate is built. */
    private fun classifyTestCandidate(outbound: Outbound): String? {
        if (outbound.isExtraCore()) return "Skipping extra-core conf"
        if (outbound.isXrayFullConfig()) return null
        if (outbound.type == "chain") {
            for (hopId in TypeAccess.chainHops(outbound)) {
                val hop = profiles.get(hopId) ?: continue
                if (hop.isExtraCore() || hop.isXrayFullConfig()) {
                    return "Skipping chain with terminal (extra-core or Xray full config) hop (cannot test)"
                }
            }
            return null
        }
        if (outbound.type == "tailscale") return "Skipping Tailscale conf"
        if (outbound.type == "autoselector") return "Skipping auto selector conf (test its members instead)"
        return null
    }

    // ------------------------------------------------------------------------------------------------ sections

    /** buildLogSection (:757-759). */
    private fun buildLogSection(state: BuildState) {
        state.coreConfig["log"] = jsonObjectOf("level" to settings.logLevel)
    }

    /** buildNTPSection (:761-771). */
    private fun buildNtpSection(state: BuildState) {
        if (!settings.ntpEnabled) return
        state.coreConfig["ntp"] = jsonObjectOf(
            "enabled" to true,
            "server" to settings.ntpServer,
            "server_port" to settings.ntpServerPort,
            "interval" to settings.ntpInterval,
            "detour" to if (settings.ntpOutbound == Tags.PROXY && !state.forTest) Tags.PROXY else Tags.DIRECT,
        )
    }

    /** buildCertificateSection (:773-776). */
    private fun buildCertificateSection(state: BuildState) {
        state.coreConfig["certificate"] = jsonObjectOf("store" to if (settings.useMozillaCerts) "mozilla" else "system")
    }

    /** buildInboundSection (:1118-1210): dns-in, mixed-in, tun-in, then the custom inbounds; bridges come later. */
    private fun buildInboundSection(state: BuildState) {
        if (state.forTest) return
        val inbounds = JsonArray()
        inbounds.add(jsonObjectOf("tag" to Tags.DNS_IN, "type" to "direct", "listen" to "127.0.0.1", "listen_port" to settings.dnsInPort))
        if (settings.mixedInboundEnabled) {
            val mixed = jsonObjectOf(
                "tag" to Tags.MIXED_IN,
                "type" to "mixed",
                "listen" to settings.mixedListen,
                "listen_port" to settings.mixedPort,
            )
            if (settings.mixedAuth) {
                mixed["users"] = JsonArray.of(jsonObjectOf("username" to settings.mixedUsername, "password" to settings.mixedPassword))
            }
            inbounds.add(mixed)
        }
        if (settings.vpnMode) inbounds.add(buildTunInbound(state))
        for (item in JsonInput.parseObject(settings.customInboundJson).array("inbounds")) inbounds.add(item)
        state.coreConfig["inbounds"] = inbounds
    }

    /**
     * The tun inbound of :1141-1180 with the Android field set of design §2.4: no interface_name / auto_redirect
     * (the platform opens the device), per-app package lists instead of uid rules, the system HTTP proxy handed to
     * the VpnService builder through `platform.http_proxy`, and an explicit 1.14 `dns_mode`.
     */
    private fun buildTunInbound(state: BuildState): JsonObject {
        val tun = JsonObject()
        tun["tag"] = Tags.TUN_IN
        tun["type"] = "tun"
        tun["auto_route"] = true
        tun["mtu"] = settings.tunMtu
        tun["stack"] = settings.tunStack
        tun["strict_route"] = settings.tunStrictRoute
        state.tunIPv4Cidr = settings.tunIPv4Cidr
        val address = JsonArray.of(settings.tunIPv4Cidr)
        if (settings.ipv6Enabled) address.add(settings.tunIPv6Cidr)
        tun["address"] = address
        tun["route_exclude_address"] = JsonArray().also { array -> routeExcludeAddresses(state).forEach { array.add(it) } }
        // hijack (the 1.14 default, spelled out): OpenTun receives the tun's derived DNS address for the VPN builder
        // and connections to it are hijacked into the DNS module; "disabled" would leave apps on the LAN resolver,
        // which the bypassed private ranges keep outside the tun (fork docs/configuration/inbound/tun.md, dns_mode).
        tun["dns_mode"] = "hijack"
        if (settings.perAppEnabled) {
            val packages = JsonValues.stringArray(settings.perAppPackages.map { it.trim() })
            if (packages.isNotEmpty()) tun[if (settings.perAppBypass) "exclude_package" else "include_package"] = packages
        }
        if (settings.mixedInboundEnabled && settings.httpProxyEnabled) {
            val httpProxy = jsonObjectOf("enabled" to true, "server" to "127.0.0.1", "server_port" to settings.mixedPort)
            val bypass = JsonValues.stringArray(settings.httpProxyBypassDomains.map { it.trim() })
            if (bypass.isNotEmpty()) httpProxy["bypass_domain"] = bypass
            tun["platform"] = jsonObjectOf("http_proxy" to httpProxy)
        }
        return tun
    }

    /**
     * route_exclude_address (:1158-1178): loopback, broadcast and the bypassed private ranges while the bypass is on,
     * plus the direct ip_cidr values with enable_tun_routing. Never route_exclude_address_set: the Android tun ignores
     * address sets (D13). The desktop cuts the tun subnet out only on macOS; Android needs it too, since the tun DNS
     * address (tun address + 1) lies in 172.16.0.0/12 and an excluded one takes every query out of the tunnel (D13).
     */
    private fun routeExcludeAddresses(state: BuildState): List<String> {
        val pre = state.prerequisites
        var excluded: List<String> = ArrayList<String>().apply {
            if (settings.bypassLan) {
                add("127.0.0.0/8")
                add("255.255.255.255/32")
                addAll(pre.bypassedPrivateRanges)
            }
            if (settings.enableTunRouting) addAll(pre.directIpCidrs)
        }
        excluded = Cidrs.subtract(excluded, settings.tunIPv4Cidr)
        if (settings.ipv6Enabled) excluded = Cidrs.subtract(excluded, settings.tunIPv6Cidr)
        return excluded
    }

    /**
     * buildOutboundsSection (:1795-1911): the main chain (WARP in front of it with enable_warp) or the auto-selector
     * group, one chain per route outbound, then bridges and `direct`.
     */
    private fun buildOutboundsSection(
        state: BuildState, chains: ChainBuilder, reads: ProfileProvider, profile: Outbound, profileId: Long,
        landingProxyId: Long, frontProxyId: Long, plan: AutoSelectorPlan?,
    ) {
        val warpWrap = settings.enableWarp
        if (profile is AutoSelector && plan != null) {
            buildAutoSelectorGroup(state, chains, reads, profile, profileId, plan, warpWrap)
            if (state.failed) return
            if (warpWrap) {
                buildWarpInFrontOfSelector(state)
                if (state.failed) return
            }
        } else {
            // Exit first: the landing proxy becomes "proxy", the stored chain list is reversed, the front proxy is dialed directly.
            val hopIds = ArrayList<Long>()
            if (landingProxyId > 0) hopIds.add(landingProxyId)
            if (profile.type == "chain") hopIds.addAll(TypeAccess.chainHops(profile).asReversed()) else hopIds.add(profileId)
            if (frontProxyId > 0) hopIds.add(frontProxyId)
            if (hopIds.isEmpty()) {
                state.error = "The chain has no hops"
                return
            }
            if (warpWrap) hopIds.add(0, WarpHop.PROFILE_ID)
            chains.buildOutboundChain(ChainRequest(hopIds, Tags.MAIN_CHAIN_PREFIX, includeProxy = true, warpWrap = warpWrap))
            if (state.failed) return
        }

        var routeSuffix = 0
        for (group in state.prerequisites.routeOutboundGroups) {
            chains.buildOutboundChain(ChainRequest(group, Tags.ROUTE_CHAIN_PREFIX, link = group.size > 1, startSuffix = routeSuffix))
            if (state.failed) return
            routeSuffix += group.size
        }

        // The Xray -> sing-box bridges of the main and the route chains alike, hence after every chain.
        val mismatch = state.bridgeIngressMismatch()
        if (mismatch.isNotEmpty()) {
            state.error = mismatch
            return
        }
        val inbounds = state.coreConfig.array("inbounds")
        for (i in state.xrayToSingBridges.indices) {
            inbounds.add(socksBridgeInbound(bridgeTagFor(state.singIngressTags[i]), state.xrayToSingBridges[i]))
        }
        state.coreConfig["inbounds"] = inbounds

        state.outbounds.add(jsonObjectOf("type" to "direct", "tag" to Tags.DIRECT))
        state.coreConfig["endpoints"] = state.endpoints
        state.coreConfig["outbounds"] = state.outbounds
    }

    /**
     * buildAutoSelectorGroup (:1622-1776): per member of the plan's build a chain `[landing?, member, front?]` under
     * `pool-<i>` (ingress `pool-<i>-0`) with the bridge ports reserved in one batch, custom Xray full configs drained
     * into their own instances, then the core `auto-selector` outbound over the members with the warm health of
     * fresh results and the pin. It is `proxy`, or `warp-bypass` when WARP takes `proxy`.
     */
    private fun buildAutoSelectorGroup(
        state: BuildState, chains: ChainBuilder, reads: ProfileProvider, selector: AutoSelector, selectorId: Long,
        plan: AutoSelectorPlan, warpWrap: Boolean,
    ) {
        val tracked = plan.group
        if (!plan.ok || tracked == null) {
            state.error = plan.error
            return
        }
        val groupTag = if (warpWrap) Tags.WARP_BYPASS else Tags.PROXY

        class PlannedMember(val member: SelectorMember, val hopIds: List<Long>, val bridges: MemberBridges)

        // One member the core rejects fails the whole group's start, so drop it instead.
        val rejected = rejectedMembers(chains, reads, plan.build)
        val plannedMembers = ArrayList<PlannedMember>()
        var bridgeCount = 0
        for (id in plan.build) {
            if (id in rejected) continue
            val member = plan.member(id) ?: continue
            if (reads.get(id) == null) continue
            val hopIds = ArrayList<Long>()
            if (tracked.landingProxyId > 0) hopIds.add(tracked.landingProxyId)
            hopIds.add(id)
            if (tracked.frontProxyId > 0) hopIds.add(tracked.frontProxyId)
            val bridges = chains.bridgesFor(hopIds)
            bridgeCount += bridges.count
            plannedMembers.add(PlannedMember(member, hopIds, bridges))
        }
        // One batch for every member: reserving per member can deal the same port twice.
        val bridgePorts = LocalPorts.reserve(bridgeCount)
        var portIdx = 0

        val builtAt = clock()
        val warm = JsonArray()
        var pinnedTag = ""
        val memberTags = JsonArray()
        val members = LinkedHashMap<String, Long>()
        for ((idx, planned) in plannedMembers.withIndex()) {
            val bridges = planned.bridges
            val tag = chains.buildOutboundChain(
                ChainRequest(
                    planned.hopIds,
                    hopTag(Tags.POOL_CHAIN_PREFIX, idx),
                    singToXrayPort = if (bridges.singToXray) bridgePorts[portIdx++] else -1,
                    xrayToSingPort = if (bridges.xrayToSing) bridgePorts[portIdx++] else -1,
                    xrayFullConfigPort = if (bridges.xrayFullConfig) bridgePorts[portIdx++] else -1,
                    soleXrayInbound = bridges.xrayFullConfig,
                ),
            )
            if (state.failed) return
            // buildOutboundChain has one Xray config slot; drain it per member.
            if (bridges.xrayFullConfig) {
                if (state.xrayConfig.isEmpty()) {
                    state.error = "Custom Xray full config member produced no Xray config"
                    return
                }
                state.xrayFullConfigs.add(state.xrayConfig.toCompact())
                state.xrayConfig = JsonObject()
                state.isXrayNeeded = false
            }
            val member = planned.member
            memberTags.add(tag)
            members[tag] = member.id
            if (member.id == selector.pinnedID) pinnedTag = tag
            if (member.latency != 0 && member.latencyAt > 0 && selector.resultValidityMins > 0) {
                val age = builtAt - member.latencyAt
                if (age >= 0 && age <= selector.resultValidityMins.toLong() * 60) {
                    // rtt 0 is how the core reads "known bad" rather than "never measured".
                    warm.add(jsonObjectOf("tag" to tag, "rtt" to (if (member.latency > 0) member.latency else 0), "age" to age))
                }
            }
        }
        if (memberTags.isEmpty()) {
            state.error = "Auto selector produced no usable members"
            return
        }

        val group = jsonObjectOf(
            "type" to "auto-selector",
            "tag" to groupTag,
            "outbounds" to memberTags,
            "url" to selector.testURL.ifEmpty { settings.testUrl },
            "interval" to "${selector.intervalSec}s",
            "bench_interval" to "${selector.benchIntervalSec}s",
            "watch_interval" to "${selector.watchIntervalSec}s",
            "active_size" to selector.activeSize,
            "sampling" to selector.sampling,
            "tolerance" to selector.toleranceMs,
            "expected" to selector.expected,
            "dial_retries" to selector.dialRetries,
            "interrupt_exist_connections" to selector.interruptOnSwitch,
        )
        if (warm.isNotEmpty()) group["warm"] = warm
        if (pinnedTag.isNotEmpty()) group["pinned"] = pinnedTag
        if (selector.maxRTTms > 0) group["max_rtt"] = "${selector.maxRTTms}ms"
        // Never the latency test URL: that one is fetched through the proxy and is routinely blocked directly.
        val connectivityUrl = selector.connectivityURL.ifEmpty { settings.directTestUrl }
        if (connectivityUrl.isNotEmpty()) group["connectivity_url"] = connectivityUrl
        if (selector.balance) {
            group["balance"] = true
            group["balance_mode"] = selector.balanceMode
            group["balance_interval"] = "${selector.balanceIntervalSec}s"
        }
        state.outbounds.add(group)
        state.autoSelector = AutoSelectorBuild(
            groupTag = groupTag,
            selectorId = selectorId,
            members = members,
            rejected = rejected,
            intervalSec = selector.intervalSec,
            warp = warpWrap,
        )
    }

    /**
     * invalidProfileIDs (:1602-1620) over the plan's build: every member must pass the structural check of a test
     * candidate (hop scan, Build / BuildXray), and then the core check of [RoutingInput.memberCheck] when one is set.
     */
    private fun rejectedMembers(chains: ChainBuilder, reads: ProfileProvider, ids: List<Long>): Map<Long, String> {
        val rejected = LinkedHashMap<Long, String>()
        val valid = ArrayList<Pair<Long, Outbound>>()
        for (id in ids) {
            val outbound = reads.get(id) ?: continue
            val error = chains.candidateError(listOf(id))
            if (error.isNotEmpty()) rejected[id] = error else valid.add(id to outbound)
        }
        val check = routing.memberCheck ?: return rejected
        if (valid.isNotEmpty()) rejected.putAll(check.rejected(valid, buildContext))
        return rejected
    }

    /** buildWarpInFrontOfSelector (:1779-1792): the group holds warp-bypass, so WARP is `proxy` dialing through it. */
    private fun buildWarpInFrontOfSelector(state: BuildState) {
        val warp = WarpHop.outbound(settings)
        val result = warp.build(buildContext)
        if (!result.ok) {
            state.error += result.error
            return
        }
        val obj = result.json
        obj["tag"] = Tags.PROXY
        obj["detour"] = Tags.WARP_BYPASS
        if (warp.isEndpoint()) state.endpoints.add(obj) else state.outbounds.add(obj)
    }

    /** buildDNSSection (:873-1114) without the Tailscale, tunnel DNS, extra-core and DNS-server hijack rules. */
    private fun buildDnsSection(state: BuildState, useDnsObj: Boolean) {
        if (buildContext.useDnsObject && useDnsObj) {
            state.coreConfig["dns"] = JsonInput.parseObject(settings.dnsObject)
            return
        }
        var independentCache = false
        val servers = JsonArray()
        val rules = JsonArray()
        // Merged in front of `rules` at the end.
        val headRules = JsonArray()

        if (!state.forTest) {
            var remoteDnsObj = DnsServers.buildDnsObj(settings.remoteDns)
            // Xray resolves through this server's transport and cannot carry udp or quic (:882-885).
            if (state.proxyUsesXray && (remoteDnsObj.string("type") == "udp" || remoteDnsObj.string("type") == "quic")) {
                remoteDnsObj = DnsServers.buildDnsObj(DnsServers.upgradeUdpDnsToDoH(remoteDnsObj.string("server")))
            }
            remoteDnsObj["tag"] = Tags.DNS_REMOTE
            remoteDnsObj["domain_resolver"] = Tags.DNS_LOCAL
            remoteDnsObj["detour"] = Tags.PROXY
            servers.add(remoteDnsObj)
        }

        val directDnsObj = DnsServers.buildDnsObj(settings.directDns)
        directDnsObj["tag"] = Tags.DNS_DIRECT
        directDnsObj["domain_resolver"] = Tags.DNS_LOCAL
        servers.add(directDnsObj)

        if (!state.forTest && settings.dnsPredefinedEnable) {
            val predefined = PredefinedDns.parse(settings.dnsPredefinedRules) ?: emptyList()
            for (entry in predefined) {
                emitPredefinedFamily(headRules, entry.domain, entry.v4, "A")
                emitPredefinedFamily(headRules, entry.domain, entry.v6, "AAAA")
            }
        }

        if (!state.forTest && settings.dnsUseHosts) {
            servers.add(jsonObjectOf("tag" to Tags.DNS_HOSTS, "type" to "hosts"))
            // The transport NXDOMAINs whatever it cannot answer, hence the preferred_by gate and query_type limit.
            headRules.add(
                jsonObjectOf(
                    "preferred_by" to JsonArray.of(Tags.DNS_HOSTS),
                    "query_type" to JsonArray.of("A", "AAAA"),
                    "action" to "route",
                    "server" to Tags.DNS_HOSTS,
                    "disable_cache" to true,
                ),
            )
        }

        if (settings.fakeDns) {
            val fakeServer = jsonObjectOf("tag" to Tags.DNS_FAKE, "type" to "fakeip", "inet4_range" to "198.18.0.0/15")
            // No inet6_range makes the transport answer AAAA empty itself; the rule stays on both types.
            if (!settings.fakeIpDisableIpv6) fakeServer["inet6_range"] = "fc00::/18"
            servers.add(fakeServer)
            rules.add(jsonObjectOf("query_type" to JsonArray.of("A", "AAAA"), "action" to "route", "server" to Tags.DNS_FAKE))
            independentCache = true
        }

        // Below the fakeip rule, which keeps answering A / AAAA for these sites as on the desktop (R6 §3.5).
        val pre = state.prerequisites
        if (pre.needDirectDnsRules) appendDnsRoutingRules(rules, pre.directDns, Tags.DNS_DIRECT, buildContext.directDnsDisableIpv6)

        // A test box builds no dns-remote server at all, so its fall-through goes out direct.
        val useDirectFinalDns = state.forTest || settings.dnsFinalOut == Tags.DIRECT
        if (!state.forTest && pre.needProxyDnsRules && useDirectFinalDns) {
            appendDnsRoutingRules(rules, pre.proxyDns, Tags.DNS_REMOTE, settings.remoteDnsDisableIpv6)
        }
        appendDnsRoute(
            rules, JsonObject(),
            if (useDirectFinalDns) Tags.DNS_DIRECT else Tags.DNS_REMOTE,
            if (useDirectFinalDns) buildContext.directDnsDisableIpv6 else settings.remoteDnsDisableIpv6,
        )

        val dnsLocalObj = DnsServers.buildDnsObj(settings.underlyingDns.ifEmpty { "local" })
        dnsLocalObj["tag"] = Tags.DNS_LOCAL
        servers.add(dnsLocalObj)

        val allRules = if (headRules.isEmpty()) rules else headRules.also { head -> for (rule in rules) head.add(rule) }
        val dns = jsonObjectOf("servers" to servers, "rules" to allRules, "cache_capacity" to settings.dnsCacheCapacity)
        if (settings.dnsDisableCache) dns["disable_cache"] = true
        if (settings.dnsDisableExpire) dns["disable_expire"] = true
        if (settings.dnsReverseMapping) dns["reverse_mapping"] = true
        if (independentCache) dns["independent_cache"] = true
        val queryTimeout = validDuration(settings.dnsQueryTimeout)
        if (queryTimeout.isNotEmpty()) dns["timeout"] = queryTimeout
        // The core refuses the config outright when optimistic meets either cache switch.
        if (settings.dnsOptimistic && !settings.dnsDisableCache && !settings.dnsDisableExpire) {
            val optimisticTimeout = validDuration(settings.dnsOptimisticTimeout)
            dns["optimistic"] = if (optimisticTimeout.isEmpty()) true
            else jsonObjectOf("enabled" to true, "timeout" to optimisticTimeout)
        }
        state.coreConfig["dns"] = dns
    }

    // The desktop never stores a malformed duration (dialog_manage_routes.cpp:230-237) and the core rejects one.
    private fun validDuration(text: String): String = text.trim().takeIf { isValidDuration(it) } ?: ""

    /** appendDnsRoutingRules (:386-399): one rule-set rule and one inline rule that always carries all four keys. */
    private fun appendDnsRoutingRules(rules: JsonArray, selectors: DomainSelectors, server: String, disableIPv6: Boolean) {
        if (selectors.ruleSets.isNotEmpty()) {
            appendDnsRoute(rules, jsonObjectOf("rule_set" to selectors.ruleSets), server, disableIPv6)
        }
        if (selectors.hasInlineConditions()) {
            appendDnsRoute(
                rules,
                jsonObjectOf(
                    "domain" to selectors.domains,
                    "domain_suffix" to selectors.suffixes,
                    "domain_keyword" to selectors.keywords,
                    "domain_regex" to selectors.regexes,
                ),
                server, disableIPv6,
            )
        }
    }

    // "*." is rewritten to the queried name by the core; a family without an address is refused rather than passed
    // through, else the other family defeats the override (:936-957).
    private fun emitPredefinedFamily(rules: JsonArray, domain: String, addresses: List<String>, type: String) {
        if (addresses.isEmpty()) {
            rules.add(jsonObjectOf("domain" to domain, "action" to "predefined", "query_type" to type, "rcode" to "NXDOMAIN"))
            return
        }
        val answers = JsonArray()
        for (address in addresses) answers.add("*. IN $type $address")
        rules.add(jsonObjectOf("domain" to domain, "action" to "predefined", "query_type" to type, "rcode" to "NOERROR", "answer" to answers))
    }

    // appendDnsRoute (:370-383): the AAAA guard answers empty instead of falling through to another server.
    private fun appendDnsRoute(rules: JsonArray, conditions: JsonObject, server: String, disableIPv6: Boolean) {
        if (disableIPv6) {
            val guard = conditions.copy()
            guard["query_type"] = JsonArray.of("AAAA")
            guard["action"] = "predefined"
            rules.add(guard)
        }
        val route = conditions.copy()
        route["action"] = "route"
        route["server"] = server
        rules.add(route)
    }

    /** buildRouteSection (:1957-2146) for a structured route profile. */
    private fun buildRouteSection(state: BuildState, route: RouteProfile) {
        val profileRules = getRouteRules(state, route)
        if (state.failed) return
        val mismatch = state.bridgeIngressMismatch()
        if (mismatch.isNotEmpty()) {
            state.error = mismatch
            return
        }
        val rules = JsonArray()
        // Bridge rules come first: what Xray hands back must reach its tailing hop before any other rule sees it.
        for (i in state.xrayToSingBridges.indices) {
            rules.add(jsonObjectOf("inbound" to bridgeTagFor(state.singIngressTags[i]), "action" to "route", "outbound" to state.singIngressTags[i]))
        }
        rules.add(jsonObjectOf("action" to "sniff"))
        if (settings.resolveDomainStrategy.isNotEmpty()) {
            rules.add(jsonObjectOf("inbound" to JsonArray.of(Tags.MIXED_IN, Tags.TUN_IN), "action" to "resolve", "strategy" to settings.resolveDomainStrategy))
        }
        rules.add(jsonObjectOf("protocol" to "dns", "action" to "hijack-dns"))
        if (!state.forTest) rules.add(jsonObjectOf("inbound" to Tags.DNS_IN, "action" to "reject"))
        for (rule in profileRules) rules.add(rule)
        val defaultOutbound = route.default_outbound_id
        // A block default is a direct final that nothing reaches (:2064-2068, :2126-2128).
        if (defaultOutbound == OutboundIds.BLOCK) rules.add(jsonObjectOf("action" to "reject"))

        val routeObj = JsonObject()
        routeObj["rules"] = rules
        routeObj["rule_set"] = buildRuleSetArray(state)
        routeObj["final"] = when (defaultOutbound) {
            OutboundIds.BLOCK -> Tags.DIRECT
            OutboundIds.WARP_BYPASS -> if (settings.enableWarp) Tags.WARP_BYPASS else Tags.PROXY
            else -> OutboundIds.toName(defaultOutbound)
        }
        if (settings.trafficStats) routeObj["find_process"] = true
        routeObj["default_domain_resolver"] = directDomainResolver(buildContext)
        if (settings.vpnMode) routeObj["auto_detect_interface"] = true
        state.coreConfig["route"] = routeObj
    }

    /**
     * get_route_rules(false, outboundMap) (RouteProfile.cpp:593-628) with get_rule_json (RouteRule.cpp:82-190): simple
     * rules without a condition are skipped and the adblock reject goes in front of the first `route` rule, else last.
     * Endpoint rules are skipped (no auxiliary endpoints on Android) and rule-level TLS spoof is dropped (D8).
     */
    private fun getRouteRules(state: BuildState, route: RouteProfile): JsonArray {
        val out = JsonArray()
        var addedAdblock = false
        for (rule in route.rules) {
            val type = RuleType.ofId(rule.type)
            if (type == RuleType.ENDPOINT_PREFERRED_BY) continue
            if (type != RuleType.CUSTOM && rule.isEmpty()) continue
            val json = rule.toRuleJson(false, state.prerequisites.outboundMap[rule.outbound_id])
            if (json.isEmpty()) {
                state.error = "Aborted generating routing section, an error has occurred"
                return out
            }
            json.remove("tls_spoof")
            json.remove("tls_spoof_method")
            if (!addedAdblock && settings.adblockEnable && json.string("action") == "route") {
                out.add(adblockRule())
                addedAdblock = true
            }
            out.add(json)
        }
        if (!addedAdblock && settings.adblockEnable) out.add(adblockRule())
        return out
    }

    private fun adblockRule(): JsonObject = jsonObjectOf("action" to "reject", "rule_set" to JsonArray.of(RuleSets.ADBLOCK_TAG))

    /**
     * buildRuleSetArray (:1916-1955): the profile's sets, then adblock; always present. No update_interval or
     * http_client, so the core refreshes each set every 24 h through route.final, as on the desktop.
     */
    private fun buildRuleSetArray(state: BuildState): JsonArray {
        val out = JsonArray()
        val sets = state.prerequisites.ruleSets
        for ((tag, url) in sets) out.add(remoteRuleSet(tag, url))
        if (settings.adblockEnable && !sets.containsKey(RuleSets.ADBLOCK_TAG)) {
            out.add(remoteRuleSet(RuleSets.ADBLOCK_TAG, RuleSets.mirrorLink(RuleSets.ADBLOCK_URL, settings.rulesetMirror)))
        }
        return out
    }

    private fun remoteRuleSet(tag: String, url: String): JsonObject =
        jsonObjectOf("type" to "remote", "tag" to tag, "format" to "binary", "url" to url)

    /** buildExperimentalSection (:2133-2155). */
    private fun buildExperimentalSection(state: BuildState) {
        if (state.forTest) return
        val experimental = JsonObject()
        if (settings.clashApiEnabled) {
            val clashApi = jsonObjectOf(
                "external_controller" to "${settings.clashApiListen}:${settings.clashApiPort}",
                "secret" to settings.clashApiSecret,
            )
            if (settings.clashApiExternalUi.isNotEmpty()) clashApi["external_ui"] = settings.clashApiExternalUi
            experimental["clash_api"] = clashApi
        }
        // enabled is unconditional: the same file backs the remote rule-set cache and the auto-selector's last pick.
        experimental["cache_file"] = jsonObjectOf(
            "enabled" to true,
            "store_fakeip" to settings.dnsPersistCache,
            "store_dns" to settings.dnsPersistCache,
        )
        state.coreConfig["experimental"] = experimental
    }

    /** buildServicesSection (:2160-2185): the core builds the traffic tracker from the mere presence of an api service. */
    private fun buildServicesSection(state: BuildState) {
        if (state.forTest || !settings.trafficStats) return
        state.coreConfig["services"] = JsonArray.of(
            jsonObjectOf("type" to "api", "listen" to "127.0.0.1", "listen_port" to 0, "secret" to settings.apiSecret),
        )
    }

    /** buildXrayConfig (:2189-2221): one socks inbound and routing rule per Xray ingress, no dns object. */
    private fun buildXrayConfig(state: BuildState) {
        if (state.xrayOutbounds.isEmpty()) return
        state.isXrayNeeded = true
        if (state.xrayIngressTags.size != state.singToXrayBridges.size) {
            state.error = "xray ingress tags size does not match bridge count!"
            return
        }
        val inbounds = JsonArray()
        val routeRules = JsonArray()
        for (i in state.xrayIngressTags.indices) {
            val outboundTag = state.xrayIngressTags[i]
            val inboundTag = "$outboundTag-inbound"
            inbounds.add(xraySocksInbound(inboundTag, state.singToXrayBridges[i]))
            routeRules.add(jsonObjectOf("type" to "field", "inboundTag" to JsonArray.of(inboundTag), "outboundTag" to outboundTag))
        }
        state.xrayConfig["log"] = jsonObjectOf(
            "loglevel" to settings.xrayLogLevel,
            "access" to if (settings.xrayLogLevel == "info") "" else "none",
        )
        state.xrayConfig["inbounds"] = inbounds
        state.xrayConfig["outbounds"] = state.xrayOutbounds
        state.xrayConfig["routing"] = jsonObjectOf("domainStrategy" to "AsIs", "rules" to routeRules)
    }

    /** The first IPv4 tun address of a custom full config (:2316-2331). */
    private fun tunIPv4CidrOf(core: JsonObject): String? {
        for (item in core.array("inbounds")) {
            val inbound = item as? JsonObject ?: continue
            if (inbound.string("type") != "tun") continue
            val addresses = if (inbound.isString("address")) listOf(inbound.string("address")) else inbound.array("address").strings()
            val cidr = addresses.firstOrNull { it.isNotEmpty() && !it.contains(':') }
            if (cidr != null) return cidr
        }
        return null
    }

    companion object {
        private val SELECTOR_PREFIXES = listOf("ruleset:", "domain:", "suffix:", "keyword:", "regex:", "ip:")

        private val DURATION = Regex("^(?:\\d+(?:\\.\\d+)?(?:ns|us|ms|s|m|h|d))+$")

        /** IsValidDuration (generate.cpp:2314-2317): a Go duration without a sign, e.g. `1m30s`. */
        @JvmStatic
        fun isValidDuration(text: String): Boolean = DURATION.matches(text)
    }
}

/** Records every profile id a build reads (buildProfileSink, generate.cpp:411-418). */
private class RecordingProfiles(private val inner: ProfileProvider) : ProfileProvider {
    val ids = LinkedHashSet<Long>()

    override fun get(id: Long): Outbound? = inner.get(id)?.also { ids.add(id) }
}
