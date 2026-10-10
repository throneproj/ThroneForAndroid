package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.common.Transport
import io.nekohasekai.sagernet.outbound.common.XrayStreamSetting
import io.nekohasekai.sagernet.outbound.common.xrayNetworks
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.outbound.types.Shadowsocks

/**
 * Issue #63 (Android only): a node the core it would run in rejects, or that the parse silently turned into plain
 * TCP / plain Shadowsocks, is not imported. Each check returns the reason for the import log, or null when the core
 * can run the node. The sets follow the pinned cores: sing-box constant/v2ray.go and transport/sip003, Xray
 * infra/conf/xray.go and infra/conf/transport_internet.go.
 */
internal object CoreSupport {

    /** sing-box's V2Ray transports; empty and "tcp" mean none. */
    private val SING_BOX_TRANSPORTS = setOf("", "tcp", "http", "ws", "quic", "grpc", "httpupgrade")

    /** The SIP003 plugins sing-box registers. */
    private val SING_BOX_PLUGINS = setOf("", "obfs-local", "v2ray-plugin")

    /** The Clash plugins Shadowsocks.parseFromClash converts (shadowsocks.cpp:64-94). */
    private val CLASH_PLUGINS = setOf("", "obfs", "v2ray-plugin")

    /** The Clash networks Transport.parseFromClash maps, to the transport types each may become. */
    private val CLASH_NETWORKS = mapOf(
        "ws" to setOf("ws", "httpupgrade"),
        "h2" to setOf("http"),
        "http" to setOf("http"),
        "grpc" to setOf("grpc"),
    )

    /** outboundConfigLoader; Xray matches protocols lower-cased. */
    private val XRAY_PROTOCOLS = setOf(
        "block", "blackhole", "loopback", "direct", "freedom", "http", "shadowsocks", "socks", "vless", "vmess",
        "trojan", "hysteria", "dns", "wireguard",
    )

    /** TransportProtocol.Build: h2, h3, http and quic are removed, anything else unknown. */
    private val XRAY_NETWORKS = setOf(
        "raw", "tcp", "xhttp", "splithttp", "kcp", "mkcp", "grpc", "ws", "websocket", "httpupgrade", "hysteria",
    )

    /** StreamConfig.Build: REALITY only over RAW, XHTTP and gRPC. */
    private val XRAY_REALITY_NETWORKS = setOf("raw", "tcp", "xhttp", "splithttp", "grpc")

    /** Why the core [outbound] would run in cannot run it; null when it can. */
    fun problem(outbound: Outbound): String? = when {
        outbound is Custom -> customProblem(outbound)
        outbound.isXray() -> xrayStreamProblem(outbound.getXrayStream())
        outbound is Shadowsocks -> pluginProblem(outbound.plugin)
        outbound.hasTransport() -> transportProblem(outbound.getTransport(), outbound.getTls().enabled)
        else -> null
    }

    /** What the Clash entry asked for that its parse dropped without a trace; null when nothing was lost. */
    fun clashProblem(proxy: ClashProxy, outbound: Outbound): String? {
        if (outbound is Shadowsocks) {
            val plugin = proxy.string("plugin")
            return if (plugin in CLASH_PLUGINS) null else "the Clash plugin \"$plugin\" is not supported"
        }
        if (outbound.isXray() || !outbound.hasTransport()) return null
        val network = proxy.string("network").lowercase()
        if (network.isEmpty() || network == "tcp") return null
        if (CLASH_NETWORKS[network]?.contains(outbound.getTransport().type) == true) return null
        return transportTypeProblem(network) ?: "the Clash network \"$network\" could not be converted"
    }

    private fun transportProblem(transport: Transport, tls: Boolean): String? {
        transportTypeProblem(transport.type)?.let { return it }
        if (transport.type == "quic" && !tls) return "sing-box runs the QUIC transport only over TLS"
        // with TLS on, sing-box's http transport speaks HTTP/2 (v2rayhttp/client.go), which a raw-header server does not
        if (transport.type == "http" && transport.rawHttpHeader && tls) return "sing-box cannot send the raw TCP HTTP header over TLS"
        return null
    }

    private fun transportTypeProblem(type: String): String? = when {
        type in SING_BOX_TRANSPORTS -> null
        type == "xhttp" || type == "splithttp" -> "XHTTP runs only in the Xray core, for VLESS"
        else -> "sing-box has no \"$type\" transport"
    }

    private fun pluginProblem(plugin: String): String? {
        val name = plugin.substringBefore(';')
        return if (name in SING_BOX_PLUGINS) null else "sing-box has no \"$name\" plugin"
    }

    /** XrayStreamSetting models only [xrayNetworks]; a link's settings for any other network are lost. */
    private fun xrayStreamProblem(stream: XrayStreamSetting): String? {
        if (stream.network !in xrayNetworks) return "the Xray VLESS profile has no \"${stream.network}\" transport"
        return xraySecurityProblem(stream.security, stream.network)
    }

    private fun customProblem(custom: Custom): String? = when (custom.subtype) {
        Custom.CUSTOM_OUTBOUND -> transportTypeProblem(custom.configObject().obj("transport").string("type"))
        Custom.CUSTOM_XRAY_OUTBOUND -> xrayOutboundProblem(custom.configObject())
        // Xray refuses the whole config when one outbound does not build
        Custom.CUSTOM_XRAY_FULL_CONFIG -> custom.configObject().array("outbounds").withIndex().firstNotNullOfOrNull { (i, o) ->
            val out = o as? JsonObject ?: return@firstNotNullOfOrNull null
            xrayOutboundProblem(out)?.let { "outbound ${out.string("tag").ifEmpty { "#${i + 1}" }}: $it" }
        }
        else -> null
    }

    private fun xrayOutboundProblem(out: JsonObject): String? {
        val protocol = out.string("protocol")
        if (protocol.lowercase() !in XRAY_PROTOCOLS) return "Xray has no \"$protocol\" outbound"
        val stream = out.obj("streamSettings")
        // `method` overrides `network`
        val network = (if (stream.contains("method")) stream.string("method") else stream.string("network"))
            .lowercase().ifEmpty { "raw" }
        if (network !in XRAY_NETWORKS) return "Xray has no \"$network\" transport"
        return xraySecurityProblem(stream.string("security"), network)
    }

    private fun xraySecurityProblem(security: String, network: String): String? = when (security.lowercase()) {
        "", "none", "tls" -> null
        "reality" -> if (network.lowercase() in XRAY_REALITY_NETWORKS) null else "Xray runs REALITY only over RAW, XHTTP and gRPC"
        else -> "Xray has no \"$security\" security"
    }
}
