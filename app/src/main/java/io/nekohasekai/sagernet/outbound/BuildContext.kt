package io.nekohasekai.sagernet.outbound

/**
 * Every build-time global the desktop's Build() paths read from SettingsRepo (include/database/SettingsRepo.h),
 * with the desktop defaults, except the TLS spoof globals: Android never emits spoof (D8). Immutable: create one
 * per config generation.
 */
data class BuildContext(
    /** SettingsRepo.h:161 skip_cert — TLS.cpp:407. */
    val skipCert: Boolean = false,
    /** SettingsRepo.h:162 utlsFingerprint — TLS.cpp:60-63, xrayStreamSetting.cpp:311-313 / 376-378. */
    val utlsFingerprint: String = "",
    /** SettingsRepo.h:92 mux_default_on — multiplex.cpp:146-149. */
    val muxDefaultOn: Boolean = false,
    /** SettingsRepo.h:82-91, the sing-box multiplex preset — MuxSettings::Preset (multiplex.cpp:191-205). */
    val muxProtocol: String = "smux",
    /** mux_mode: "max_streams" pools by [muxConcurrency], "max_connections" by [muxMaxConnections] and [muxMinStreams]. */
    val muxMode: String = "max_streams",
    /** mux_concurrency, the preset's max_streams. */
    val muxConcurrency: Int = 8,
    val muxMaxConnections: Int = 4,
    val muxMinStreams: Int = 4,
    val muxPadding: Boolean = false,
    val muxBrutal: Boolean = false,
    val muxBrutalUpMbps: Int = 0,
    val muxBrutalDownMbps: Int = 0,
    /** SettingsRepo.h:81 fragment_implementation ("built-in" or "custom") — TLS.cpp:433, Outbound.cpp:156. */
    val fragmentImplementation: String = "built-in",
    /** SettingsRepo.h:82 fragment_default_on — TLS.cpp:462. */
    val fragmentDefaultOn: Boolean = false,
    /** SettingsRepo.h:84 fragment_size — Outbound.cpp:161. */
    val fragmentSize: String = "10-100",
    /** SettingsRepo.h:85 fragment_sleep — Outbound.cpp:162. */
    val fragmentSleep: String = "2-5",
    /** SettingsRepo.h:87 tls_tricks_default_on — TLS.cpp:477. */
    val tlsTricksDefaultOn: Boolean = false,
    /** SettingsRepo.h:94-100 — QUICFields.cpp:70-86. */
    val h2IdleTimeout: String = "",
    val h2KeepAlivePeriod: String = "",
    val h2StreamReceiveWindow: String = "",
    val h2ConnectionReceiveWindow: String = "",
    val h2MaxConcurrentStreams: Int = 0,
    val quicInitialPacketSize: Int = 0,
    val quicDisablePathMtuDiscovery: Boolean = false,
    /** SettingsRepo.h:334-337, the Xray mux preset — XrayMuxSettings::Preset (xrayMultiplex.cpp:154-162). */
    val xrayMuxConcurrency: Int = 8,
    /** 0 leaves the pool unbounded. */
    val xrayMuxMaxConnections: Int = 0,
    val xrayMuxXudpConcurrency: Int = 16,
    val xrayMuxXudpProxyUDP443: String = "reject",
    /** SettingsRepo.h:338 xray_mux_default_on — xrayVless.cpp:192. */
    val xrayMuxDefaultOn: Boolean = false,
    /** SettingsRepo.h xray_use_system_ca — generate.cpp UseXraySystemCA on every Xray outbound. */
    val xrayUseSystemCA: Boolean = false,
    /** VpnCredentialOverride.hpp:20-22 BuildingTestConfig — consulted by openvpn/openconnect only. */
    val buildingTestConfig: Boolean = false,
    /** SettingsRepo.h:199 default_domain_strategy — xrayStreamSetting.cpp:282-299. */
    val defaultDomainStrategy: String = "",
    /** direct_dns_disable_ipv6 of RouteDns::Current: the routing profile's own DNS, else SettingsRepo.h:182. */
    val directDnsDisableIpv6: Boolean = false,
    /** SettingsRepo.h:189 use_dns_object. */
    val useDnsObject: Boolean = false,
    /** generate.cpp BuildContext::routeOwnsDns: the routing profile has its own DNS, which also beats [useDnsObject]. */
    val routeOwnsDns: Boolean = false,
) {
    /** getDirectDomainStrategy (xrayStreamSetting.cpp:1342-1349). */
    fun directDomainStrategy(): String =
        if (directDnsDisableIpv6 && (routeOwnsDns || !useDnsObject)) "ipv4_only" else defaultDomainStrategy

    /** getXrayOutboundDomainStrategy (xrayStreamSetting.cpp:289-299). */
    fun xrayOutboundDomainStrategy(): String = when (directDomainStrategy()) {
        "prefer_ipv4" -> "UseIPv4v6"
        "prefer_ipv6" -> "UseIPv6v4"
        "ipv6_only" -> "ForceIPv6"
        "ipv4_only" -> if (defaultDomainStrategy == "ipv4_only") "ForceIPv4" else "UseIPv4"
        else -> "UseIP"
    }

    companion object {
        @JvmField
        val DEFAULT = BuildContext()
    }
}
