package io.nekohasekai.sagernet.outbound.config

/**
 * Every setting the config skeleton reads (include/database/SettingsRepo.h), with the desktop default. Each field
 * names the desktop key; database.SettingsMapper fills it from the DataStore property of that key (lowerCamelCase).
 * `direct_dns_disable_ipv6` and `use_dns_object` are read from [io.nekohasekai.sagernet.outbound.BuildContext] (they
 * also drive the domain strategies there), not duplicated here.
 */
data class GeneratorSettings(
    // ---- log
    /** log_level (SettingsRepo.h:67). */
    val logLevel: String = "info",

    // ---- ntp (SettingsRepo.h:245-249): enable_ntp, ntp_server_address, ntp_server_port, ntp_interval, ntp_outbound
    val ntpEnabled: Boolean = false,
    val ntpServer: String = "",
    val ntpServerPort: Int = 0,
    val ntpInterval: String = "",
    /** "direct" or "proxy". */
    val ntpOutbound: String = "direct",

    // ---- certificate
    /** use_mozilla_certs (SettingsRepo.h:164). */
    val useMozillaCerts: Boolean = false,

    // ---- inbounds
    /** core_dns_in_port (SettingsRepo.h:297). */
    val dnsInPort: Int = 5533,
    /** !disable_mixed_inbound (SettingsRepo.h:203); the proxy-only service mode always keeps the mixed inbound. */
    val mixedInboundEnabled: Boolean = true,
    /** inbound_address (SettingsRepo.h:204): the mixed inbound's listen address, "::" once LAN access is allowed. */
    val inboundAddress: String = "127.0.0.1",
    /** inbound_socks_port (SettingsRepo.h:205). */
    val mixedPort: Int = 2080,
    /** inbound_auth / inbound_user / inbound_pass (SettingsRepo.h:208-210). */
    val mixedAuth: Boolean = false,
    val mixedUsername: String = "",
    val mixedPassword: String = "",
    /** custom_inbound (SettingsRepo.h:207): a JSON object whose `inbounds` array is appended verbatim. */
    val customInboundJson: String = "",

    // ---- tun (only in VPN mode)
    /** spmode_vpn (SettingsRepo.h:48) <- DataStore.serviceMode == Key.MODE_VPN. */
    val vpnMode: Boolean = false,
    /** vpn_mtu (SettingsRepo.h:236). */
    val tunMtu: Int = 9000,
    /** vpn_strict_route (SettingsRepo.h:222-231); always true on Android (no setting). */
    val tunStrictRoute: Boolean = true,
    /** vpn_tun_ipv4_cidr (SettingsRepo.h:240). */
    val tunIPv4Cidr: String = "172.19.0.1/24",
    /** vpn_tun_ipv6_cidr (SettingsRepo.h:241). */
    val tunIPv6Cidr: String = "fdfe:dcba:9876::1/96",
    /** vpn_ipv6 (SettingsRepo.h:239). */
    val ipv6Enabled: Boolean = false,
    /** !disable_private_range_bypass (SettingsRepo.h:238). */
    val bypassLan: Boolean = true,
    /** vpn_private_ranges (SettingsRepo.h:239, defaultTunPrivateRanges); a range a non-direct rule targets stays in the tun. */
    val privateRanges: List<String> = DEFAULT_PRIVATE_RANGES,
    /** DataStore.proxyApps: whether [perAppPackages] is applied at all. */
    val perAppEnabled: Boolean = false,
    /** DataStore.bypass: true excludes the listed packages from the tun (`exclude_package`), false routes only them (`include_package`). */
    val perAppBypass: Boolean = true,
    /** DataStore.individual split on newlines; the caller adds or removes its own package as it sees fit. */
    val perAppPackages: List<String> = emptyList(),
    /**
     * DataStore.appendHttpProxy (Android-only, off by default): `platform.http_proxy` points the VPN's system HTTP
     * proxy (VpnService.openTun) at the mixed inbound, only while that inbound is on and has no [mixedAuth].
     */
    val httpProxyEnabled: Boolean = false,
    /** platform.http_proxy.bypass_domain <- DataStore.httpProxyBypass split on newlines. */
    val httpProxyBypassDomains: List<String> = emptyList(),

    // ---- dns (SettingsRepo.h:179-197, :296)
    /** remote_dns (desktop address syntax, see DnsServers.buildDnsObj). */
    val remoteDns: String = "https://8.8.8.8/dns-query",
    /** remote_dns_disable_ipv6. */
    val remoteDnsDisableIpv6: Boolean = false,
    /** direct_dns. */
    val directDns: String = "localhost",
    /** core_box_underlying_dns: the dns-local server, "" means "local". */
    val underlyingDns: String = "",
    /** dns_cache_capacity. */
    val dnsCacheCapacity: Int = 65536,
    val dnsDisableCache: Boolean = false,
    val dnsDisableExpire: Boolean = false,
    /** dns_persist_cache: experimental.cache_file.store_fakeip / store_dns. */
    val dnsPersistCache: Boolean = false,
    val dnsReverseMapping: Boolean = false,
    /** dns_object: the raw dns section used when BuildContext.useDnsObject is set (never for tests). */
    val dnsObject: String = "",
    /** dns_final_out: "remote" or "direct". */
    val dnsFinalOut: String = "remote",
    val dnsOptimistic: Boolean = false,
    val dnsOptimisticTimeout: String = "",
    val dnsQueryTimeout: String = "",
    val dnsUseHosts: Boolean = false,
    /** dns_predefined_enable / dns_predefined_rules (hosts-file lines). */
    val dnsPredefinedEnable: Boolean = true,
    val dnsPredefinedRules: List<String> = listOf("127.0.0.1 localhost"),
    /** fakedns (SettingsRepo.h:219). */
    val fakeDns: Boolean = false,
    /** fakeip_disable_ipv6 (SettingsRepo.h:220). */
    val fakeIpDisableIpv6: Boolean = false,

    // ---- route
    /** domain_strategy (SettingsRepo.h:198): adds the `resolve` rule for mixed-in / tun-in when non-empty. */
    val resolveDomainStrategy: String = "",
    /** ruleset_mirror (SettingsRepo.h:201): the jsDelivr mirror of srslist rule-sets, 0 = GitHub itself. */
    val rulesetMirror: Int = 1,
    /** adblock_enable (SettingsRepo.h:217): the adblock rule-set and its reject rule. */
    val adblockEnable: Boolean = false,
    /** enable_dns_routing (SettingsRepo.h:189): DNS rules for the route profile's direct and proxy sites. */
    val enableDnsRouting: Boolean = true,
    /** enable_tun_routing (SettingsRepo.h:222): the route profile's direct ip_cidr values bypass the tun. */
    val enableTunRouting: Boolean = false,
    /** enable_stats (SettingsRepo.h:118): route.find_process and the api service. */
    val trafficStats: Boolean = true,
    /**
     * core_box_api_port (SettingsRepo.h:298), sign-encoded: > 0 is the loopback port the api service serves the
     * dashboard on, otherwise the service is only there for the stats. Android defaults to 9091 (on).
     */
    val apiPort: Int = -9091,
    /** core_box_api_secret (SettingsRepo.h:299): the api service's secret. */
    val apiSecret: String = "",

    // ---- experimental (SettingsRepo.h:290-292)
    /** core_box_clash_api > 0; [clashApiPort] is its magnitude, [clashApiListen] / [clashApiSecret] the other two keys. */
    val clashApiEnabled: Boolean = false,
    val clashApiListen: String = "127.0.0.1",
    val clashApiPort: Int = 9090,
    val clashApiSecret: String = "",
    /** external_ui; the desktop always sends "dashboard", "" omits the key. */
    val clashApiExternalUi: String = "dashboard",

    // ---- xray
    /** xray_log_level (SettingsRepo.h:300). */
    val xrayLogLevel: String = "warning",

    // ---- tests (SettingsRepo.h:69-71): the auto-selector's probe URLs when its own are empty
    /** test_url (the desktop member test_latency_url). */
    val testUrl: String = "http://cp.cloudflare.com/",
    /** direct_test_url: the selector's direct connectivity probe; "" omits it. */
    val directTestUrl: String = "",

    // ---- warp (SettingsRepo.h:252-267), the built-in WARP hop of WarpHop
    /** enable_warp. */
    val enableWarp: Boolean = false,
    /** warp_mode: "wireguard" or "masque". */
    val warpMode: String = "wireguard",
    /** warp_ep, warp_private_key, warp_public_key, warp_ifc_addrs, warp_reserved (decimal strings). */
    val warpEp: String = "",
    val warpPrivateKey: String = "",
    val warpPublicKey: String = "",
    val warpIfcAddrs: List<String> = emptyList(),
    val warpReserved: List<String> = emptyList(),
    /** warp_masque_ep, warp_masque_private_key, warp_masque_peer_public_key, warp_masque_ifc_addrs, warp_masque_sni. */
    val warpMasqueEp: String = "",
    val warpMasquePrivateKey: String = "",
    val warpMasquePeerPublicKey: String = "",
    val warpMasqueIfcAddrs: List<String> = emptyList(),
    val warpMasqueSni: String = "consumer-masque.cloudflareclient.com",
    /** warp_masque_http_mode: 0 HTTP/3 falling back to HTTP/2, 1 HTTP/3 only, 2 HTTP/2. */
    val warpMasqueHttpMode: Int = 0,

    // ---- start parameters (Android-only, not settings)
    /**
     * #65: the start does not wait for remote rule-sets. Each starts from its cached copy or else empty
     * ([ConfigGenerator.EMPTY_RULE_SET_PATH]) and downloads through `proxy`; DNS rules leave rule-sets out.
     */
    val deferRuleSets: Boolean = false,
) {
    /** The mixed inbound's `listen`: inbound_address as stored. */
    val mixedListen: String get() = inboundAddress

    companion object {
        /** defaultTunPrivateRanges (SettingsRepo.h:18-21). */
        @JvmField
        val DEFAULT_PRIVATE_RANGES: List<String> = listOf(
            "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4",
            "fc00::/7", "fe80::/10", "ff00::/8",
        )

        /** A newline separated DataStore list (individual, httpProxyBypass) with blanks and `#` comments dropped. */
        @JvmStatic
        fun splitLines(text: String): List<String> =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }
}
