package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.displayTransportName
import io.nekohasekai.sagernet.outbound.withPrivateServer
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.JsonWriter
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts

/**
 * Custom (include/configs/outbounds/custom.h, src/configs/outbounds/custom.cpp): raw JSON in one of four shapes.
 * The desktop's `type` member is [subtype] here because the base already owns `type` ("custom").
 */
class Custom : Outbound("custom") {
    @JvmField var config: String = ""
    @JvmField var subtype: String = ""

    /** custom.h:18-21: transient bridge fields (not persisted); [build] emits a socks outbound to them for an Xray full config. */
    @JvmField var bridgePort: Int = 0
    @JvmField var bridgeAuth: String = ""
    @JvmField var bridgeHost: String = "127.0.0.1"

    /** A sing-box full config (`fullconfig`), which replaces the whole core config; the Xray one is [isXrayFullConfig]. */
    fun isFullConfig(): Boolean = subtype == CUSTOM_FULL_CONFIG

    /** QString2QJsonObject(config): an empty object when the text is not a JSON object. */
    fun configObject(): JsonObject = JsonInput.parseObject(config)

    /** custom.h:23-29. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        if (obj.contains("subtype")) subtype = obj.string("subtype")
        if (obj.contains("config")) config = obj.string("config")
        return true
    }

    /** custom.h:31-38: all four keys always. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["name"] = name
        obj["type"] = "custom"
        obj["subtype"] = subtype
        obj["config"] = config
        return obj
    }

    /** custom.h:40-52. */
    override fun getAddress(): String {
        if (subtype == CUSTOM_OUTBOUND) return configObject().string("server")
        if (subtype == CUSTOM_XRAY_OUTBOUND) {
            val settings = configObject().obj("settings")
            if (settings.contains("vnext")) return firstObject(settings.array("vnext")).string("address")
            if (settings.contains("servers")) return firstObject(settings.array("servers")).string("address")
        }
        return ""
    }

    /** custom.h:54-68. */
    override fun displayAddress(): String {
        if (subtype == CUSTOM_OUTBOUND) {
            val obj = configObject()
            return Hosts.displayAddress(obj.string("server"), obj.int("server_port"))
        }
        if (subtype == CUSTOM_XRAY_OUTBOUND) {
            val settings = configObject().obj("settings")
            var server = JsonObject()
            if (settings.contains("vnext")) server = firstObject(settings.array("vnext"))
            else if (settings.contains("servers")) server = firstObject(settings.array("servers"))
            if (server.isNotEmpty()) return Hosts.displayAddress(server.string("address"), server.int("port"))
        }
        return ""
    }

    /** custom.h:70-86. */
    override fun displayType(): String = when (subtype) {
        CUSTOM_OUTBOUND -> {
            val outboundType = capitalized(configObject().string("type"))
            if (outboundType.isEmpty()) "Custom Outbound" else "Custom $outboundType Outbound"
        }
        CUSTOM_FULL_CONFIG -> "Custom Config"
        CUSTOM_XRAY_OUTBOUND -> {
            val protocol = capitalized(configObject().string("protocol"))
            if (protocol.isEmpty()) "Custom Xray Outbound" else "Custom Xray $protocol Outbound"
        }
        CUSTOM_XRAY_FULL_CONFIG -> "Custom Xray Config"
        else -> subtype
    }

    /** custom.cpp:176-196: the verdict of the outbound the config actually exits through. */
    override fun security(): SecurityInfo = when (subtype) {
        CUSTOM_OUTBOUND -> analyzeSingBoxOutbound(configObject())
        CUSTOM_FULL_CONFIG -> {
            val egress = singBoxFullConfigEgress(configObject())
            if (egress.isEmpty()) SecurityInfo() else analyzeSingBoxOutbound(egress)
        }
        CUSTOM_XRAY_OUTBOUND -> analyzeXrayOutbound(configObject())
        CUSTOM_XRAY_FULL_CONFIG -> {
            val egress = firstXrayEgress(configObject().array("outbounds"))
            if (egress.isEmpty()) SecurityInfo() else analyzeXrayOutbound(egress)
        }
        else -> SecurityInfo()
    }

    /** custom.cpp:198-217. */
    override fun exportIdentity(): JsonObject {
        val ob = when (subtype) {
            CUSTOM_OUTBOUND -> singBoxOutboundIdentity(configObject())
            CUSTOM_FULL_CONFIG -> {
                val egress = singBoxFullConfigEgress(configObject())
                if (egress.isEmpty()) JsonObject() else singBoxOutboundIdentity(egress)
            }
            CUSTOM_XRAY_OUTBOUND -> xrayOutboundIdentity(configObject())
            CUSTOM_XRAY_FULL_CONFIG -> {
                val egress = firstXrayEgress(configObject().array("outbounds"))
                if (egress.isEmpty()) JsonObject() else xrayOutboundIdentity(egress)
            }
            else -> JsonObject()
        }
        if (ob.isEmpty()) return exportToJson()
        return jsonObjectOf("subtype" to subtype, "ob" to ob)
    }

    /** custom.h:92-98: only raw sing-box outbound JSON can describe an endpoint. */
    override fun isEndpoint(): Boolean {
        if (subtype != CUSTOM_OUTBOUND) return false
        val t = configObject().string("type")
        return t == "wireguard" || t == "tailscale" || t == "masque"
    }

    /** custom.h:100. */
    override fun isXray(): Boolean = subtype == CUSTOM_XRAY_OUTBOUND

    /** custom.h:102. */
    override fun isXrayFullConfig(): Boolean = subtype == CUSTOM_XRAY_FULL_CONFIG

    /** custom.h:127-146. */
    override fun build(ctx: BuildContext): BuildResult {
        if (subtype == CUSTOM_XRAY_FULL_CONFIG) {
            return BuildResult(
                jsonObjectOf(
                    "type" to "socks",
                    "server" to bridgeHost,
                    "server_port" to bridgePort,
                    "username" to bridgeAuth,
                    "password" to bridgeAuth,
                )
            )
        }
        // dummy outbound so sing-box CheckConfig passes; the real one is in buildXray()
        if (subtype == CUSTOM_XRAY_OUTBOUND) return BuildResult(jsonObjectOf("type" to "socks", "server" to "127.0.0.1"))
        return BuildResult(configObject())
    }

    /** custom.h:148-155: domain resolution is wired on at instance creation, not as sockopt.domainStrategy. */
    override fun buildXray(ctx: BuildContext): BuildResult =
        if (subtype == CUSTOM_XRAY_OUTBOUND) BuildResult(configObject()) else BuildResult(JsonObject())

    companion object {
        /** custom.h:10-13 */
        const val CUSTOM_OUTBOUND = "outbound"
        const val CUSTOM_FULL_CONFIG = "fullconfig"
        const val CUSTOM_XRAY_OUTBOUND = "xrayoutbound"
        const val CUSTOM_XRAY_FULL_CONFIG = "xrayfullconfig"

        /**
         * SubscriptionParser.cpp:344-350: a JSON document that is one sing-box outbound object (it has `type`, and
         * is neither a config with `outbounds`/`endpoints` nor an Xray outbound with `protocol`) is stored as its
         * trimmed source text. Null when the text is not such an object.
         */
        @JvmStatic
        fun fromSingBoxOutboundText(text: String): Custom? {
            val obj = JsonInput.parseObjectOrNull(text) ?: return null
            if (obj.contains("protocol") || obj.contains("outbounds") || obj.contains("endpoints") || !obj.contains("type")) return null
            val custom = Custom()
            custom.subtype = CUSTOM_OUTBOUND
            custom.config = text.trim()
            return custom
        }

        /**
         * SubscriptionParser.cpp:164-179 makeProfileForXrayOutbound: infrastructure protocols are dropped, a VLESS
         * outbound becomes an xrayvless profile when it parses, everything else a custom `xrayoutbound` whose config
         * is the Indented JSON text, named after the tag.
         */
        @JvmStatic
        fun fromXrayOutbound(out: JsonObject): Outbound? {
            if (out.isEmpty()) return null
            val protocol = out.string("protocol")
            if (isXrayInfra(protocol)) return null
            if (protocol == "vless") {
                val normalized = XrayVless.normalizeForParse(out)
                if (normalized != null) {
                    val vless = OutboundFactory.newByType("xrayvless")
                    if (!vless.invalid && vless.parseFromJson(normalized)) return vless
                }
            }
            val custom = Custom()
            custom.subtype = CUSTOM_XRAY_OUTBOUND
            custom.config = JsonWriter.writeIndented(out)
            val tag = out.string("tag")
            if (tag.isNotEmpty()) custom.name = tag
            return custom
        }

        /**
         * SubscriptionParser.cpp:396-402: one element of an Xray config array; its `inbounds` are dropped because
         * Throne injects its own bridge inbound. Null without `outbounds`.
         */
        @JvmStatic
        fun fromXrayFullConfig(cfg: JsonObject): Custom? {
            if (!cfg.contains("outbounds")) return null
            val stripped = cfg.copy()
            stripped.remove("inbounds")
            val custom = Custom()
            custom.subtype = CUSTOM_XRAY_FULL_CONFIG
            custom.config = JsonWriter.writeIndented(stripped)
            val remarks = stripped.string("remarks")
            if (remarks.isNotEmpty()) custom.name = remarks
            return custom
        }

        private fun firstObject(arr: JsonArray): JsonObject =
            if (arr.isEmpty()) JsonObject() else arr[0] as? JsonObject ?: JsonObject()

        /** QChar::toUpper on the first character. */
        private fun capitalized(s: String): String =
            if (s.isEmpty()) s else s[0].uppercaseChar() + s.substring(1)

        /** custom.cpp:15-18. */
        private fun isSingBoxInfra(t: String): Boolean = t == "direct" || t == "block" || t == "dns"

        /** custom.cpp:71-75: direct and block are Xray's aliases of freedom and blackhole. */
        private fun isXrayInfra(p: String): Boolean =
            p == "freedom" || p == "direct" || p == "blackhole" || p == "block" || p == "dns" || p == "loopback"

        /** custom.cpp:77-82: Xray lets a top-level address replace vnext/servers, taking the inline user with it. */
        private fun xrayPeer(settings: JsonObject): JsonObject {
            if (settings.contains("address")) return settings
            return firstObject(if (settings.contains("vnext")) settings.array("vnext") else settings.array("servers"))
        }

        /** custom.cpp:84-89. */
        private fun xrayVlessEncryption(settings: JsonObject): String {
            val peer = xrayPeer(settings)
            val user = if (peer.contains("users")) firstObject(peer.array("users")) else peer
            return user.string("encryption")
        }

        /** custom.cpp:20-30 (the outbound of an unknown or not yet ported type has no verdict). */
        private fun analyzeSingBoxOutbound(o: JsonObject): SecurityInfo {
            val type = o.string("type")
            if (type.isEmpty() || type == "custom" || type == "selector" || type == "urltest" || isSingBoxInfra(type)) return SecurityInfo()
            val ob = OutboundFactory.newByType(type)
            if (ob.invalid) return SecurityInfo()
            ob.parseFromJson(o)
            return withPrivateServer(ob.security(), ob.getAddress())
        }

        /** custom.cpp:32-42. */
        private fun singBoxOutboundIdentity(o: JsonObject): JsonObject {
            val type = o.string("type")
            if (type.isEmpty() || type == "custom" || type == "selector" || type == "urltest" || isSingBoxInfra(type)) return JsonObject()
            val ob = OutboundFactory.newByType(type)
            if (ob.invalid) return JsonObject()
            ob.parseFromJson(o)
            return ob.exportIdentity()
        }

        private fun singBoxFullConfigEgress(cfg: JsonObject): JsonObject =
            resolveSingBoxEgress(cfg.array("outbounds"), cfg.obj("route").string("final"), 5)

        // custom.cpp:44-69: the named tag, or the first outbound (sing-box's default egress), through selector/urltest groups.
        private fun resolveSingBoxEgress(outbounds: JsonArray, tag: String, depth: Int): JsonObject {
            if (depth <= 0 || outbounds.isEmpty()) return JsonObject()
            var target = JsonObject()
            if (tag.isEmpty()) {
                target = firstObject(outbounds)
            } else {
                for (v in outbounds) {
                    val obj = v as? JsonObject ?: JsonObject()
                    if (obj.string("tag") == tag) {
                        target = obj
                        break
                    }
                }
            }
            if (target.isEmpty()) return JsonObject()
            val type = target.string("type")
            if (type == "selector" || type == "urltest") {
                val subs = target.array("outbounds")
                var next = target.string("default")
                if (next.isEmpty() && subs.isNotEmpty()) next = JsonValues.toStringValue(subs[0])
                return resolveSingBoxEgress(outbounds, next, depth - 1)
            }
            if (type.isEmpty() || isSingBoxInfra(type)) return JsonObject()
            return target
        }

        /** custom.cpp:91-127. */
        private fun xrayOutboundSecurity(o: JsonObject): SecurityInfo {
            val protocol = o.string("protocol")
            if (protocol.isEmpty() || isXrayInfra(protocol)) return SecurityInfo()
            val stream = o.obj("streamSettings")
            val transport = displayTransportName(stream.string("network"))
            val security = stream.string("security")
            if (security == "reality") return SecurityInfo("Reality", transport, SecurityLevel.Secure)
            if (security == "tls") {
                val insecure = stream.obj("tlsSettings").bool("allowInsecure")
                return if (insecure) SecurityInfo("Insecure TLS", transport, SecurityLevel.Weak)
                else SecurityInfo("TLS", transport, SecurityLevel.Secure)
            }
            // Without transport security, shadowsocks, WireGuard and VLESS Encryption still encrypt; VMess counts as insecure.
            if (protocol == "shadowsocks" || protocol == "wireguard" ||
                (protocol == "vless" && XrayVless.isVlessEncrypted(xrayVlessEncryption(o.obj("settings"))))
            ) {
                return SecurityInfo("Encrypted", transport, SecurityLevel.Secure)
            }
            if (protocol == "vmess") return SecurityInfo("Insecure", transport, SecurityLevel.Weak)
            return SecurityInfo("Raw", transport, SecurityLevel.None)
        }

        /** custom.cpp:129-132. */
        private fun analyzeXrayOutbound(o: JsonObject): SecurityInfo =
            withPrivateServer(xrayOutboundSecurity(o), xrayPeer(o.obj("settings")).string("address"))

        /** custom.cpp:134-164. */
        private fun xrayOutboundIdentity(o: JsonObject): JsonObject {
            val protocol = o.string("protocol")
            if (protocol.isEmpty() || isXrayInfra(protocol)) return JsonObject()
            val id = JsonObject()
            id["protocol"] = protocol
            val settings = o.obj("settings")
            var peer = JsonObject()
            if (settings.contains("vnext")) peer = firstObject(settings.array("vnext"))
            else if (settings.contains("servers")) peer = firstObject(settings.array("servers"))
            if (peer.isNotEmpty()) {
                peer["address"]?.let { id["server"] = it }
                peer["port"]?.let { id["server_port"] = it }
            }
            val stream = o.obj("streamSettings")
            val sid = JsonObject()
            val network = stream.string("network")
            if (network.isNotEmpty()) sid["network"] = network
            val security = stream.string("security")
            if (security.isNotEmpty()) sid["security"] = security
            if (security == "reality") {
                val r = stream.obj("realitySettings")
                r["serverName"]?.let { sid["sni"] = it }
                r["fingerprint"]?.let { sid["fingerprint"] = it }
            } else if (security == "tls") {
                val t = stream.obj("tlsSettings")
                t["serverName"]?.let { sid["sni"] = it }
                t["fingerprint"]?.let { sid["fingerprint"] = it }
            }
            if (sid.isNotEmpty()) id["stream"] = sid
            return id
        }

        /** custom.cpp:166-173: Xray routes to the first outbound by default; pick the first real proxy. */
        private fun firstXrayEgress(outbounds: JsonArray): JsonObject {
            for (v in outbounds) {
                val obj = v as? JsonObject ?: JsonObject()
                if (!isXrayInfra(obj.string("protocol"))) return obj
            }
            return JsonObject()
        }
    }
}
