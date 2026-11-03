package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.displayTransportName
import io.nekohasekai.sagernet.outbound.withPrivateServer
import io.nekohasekai.sagernet.outbound.common.XrayChecks
import io.nekohasekai.sagernet.outbound.common.normalizeXrayNetwork
import io.nekohasekai.sagernet.outbound.common.stripXrayFinalmaskKeyLog
import io.nekohasekai.sagernet.outbound.common.withXrayDownloadLeg
import io.nekohasekai.sagernet.outbound.common.xrayDownloadLeg
import io.nekohasekai.sagernet.outbound.common.xrayFinalmaskHasXdns
import io.nekohasekai.sagernet.outbound.common.xrayLower
import io.nekohasekai.sagernet.outbound.common.xrayNetworks
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.JsonWriter
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

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

    /**
     * The desktop's strict QString2QJsonObject: the config only when [Validation.jsonObject] accepts it (comments count
     * as whitespace there), else an empty object; [configObject] would also read text the strict parser refuses.
     */
    private fun strictConfigObject(): JsonObject =
        if (Validation.jsonObject(config, Empty.Required).isEmpty()) configObject() else JsonObject()

    /** custom.h: the config carries its own server, which base validate cannot see. */
    override fun hasServerEndpoint(): Boolean = false

    /** custom.h ParseFromJson. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        if (obj.contains("subtype")) subtype = obj.string("subtype").trim()
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

    /** Custom::GetAddress (custom.cpp): effectiveSecurity's private-server check reads this, so it must be the destination Xray dials. */
    override fun getAddress(): String {
        if (subtype == CUSTOM_OUTBOUND) return configObject().string("server")
        if (subtype == CUSTOM_XRAY_OUTBOUND) return xrayOutboundAddress(configObject())
        return ""
    }

    /** Custom::DisplayAddress (custom.cpp). */
    override fun displayAddress(): String {
        if (subtype == CUSTOM_OUTBOUND) {
            val obj = configObject()
            return Hosts.displayAddress(obj.string("server"), obj.int("server_port"))
        }
        if (subtype == CUSTOM_XRAY_OUTBOUND) {
            val peer = xrayOutboundPeer(configObject())
            if (peer.isNotEmpty()) {
                return Hosts.displayAddress(JsonValues.toStringValue(xrayAgreed(peer, "address")), JsonValues.toInt(xrayAgreed(peer, "port")))
            }
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

    /** Custom::Validate (custom.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        if (subtype !in SUBTYPES) {
            // oneOf trims, but build() and the strip compare exactly.
            val message = Validation.oneOf(subtype, SUBTYPES, Empty.Required).ifEmpty { vtr(R.string.validation_no_spaces) }
            issues.add(Issue(Severity.Error, "subtype", message, subtype))
            return
        }
        issues.addIssue(Severity.Error, "config", CustomChecks.customConfig(subtype, config))
        val root = strictConfigObject()
        if (subtype == CUSTOM_XRAY_OUTBOUND) {
            XrayChecks.xrayXHTTPDownloadLeg(issues, "", root)
        } else if (subtype == CUSTOM_XRAY_FULL_CONFIG) {
            for (key in keysNamed(root, "outbounds")) {
                val items = root[key] as? JsonArray ?: continue
                for ((i, item) in items.withIndex()) {
                    XrayChecks.xrayXHTTPDownloadLeg(issues, "$key[$i].", item as? JsonObject ?: JsonObject())
                }
            }
        }
    }

    /**
     * Custom::StripForeignState (custom.cpp): what would expose this device, write files where the sender chose or start
     * a program. Tailscale's SSH server and Taildrop folder are not stripped: Tailscale is not on Android.
     */
    override fun stripForeignState(issues: MutableList<Issue>) {
        val root = strictConfigObject()
        val removed = when (subtype) {
            CUSTOM_XRAY_OUTBOUND -> stripXrayOutbound(root, "", issues)
            CUSTOM_XRAY_FULL_CONFIG -> stripXrayExposure(root, issues)
            CUSTOM_FULL_CONFIG -> {
                var changed = stripSingBoxExposure(root, issues)
                for (list in listOf("outbounds", "endpoints")) {
                    changed = changed or editListNamed(root, list, "") { item, at -> stripLaunchers(item, at, issues) }
                }
                changed
            }
            // build() hands every other subtype to the core as a sing-box outbound.
            else -> stripLaunchers(root, "", issues)
        }
        if (removed) config = JsonWriter.writeIndented(root)
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

        private val SUBTYPES = listOf(CUSTOM_OUTBOUND, CUSTOM_FULL_CONFIG, CUSTOM_XRAY_OUTBOUND, CUSTOM_XRAY_FULL_CONFIG)

        /**
         * SubscriptionParser.cpp:344-350: a JSON document that is one sing-box outbound object (it has `type`, and
         * is neither a config with `outbounds`/`endpoints` nor an Xray outbound with `protocol`) is stored as JSON
         * text. Null when the text is not such an object. Android stores the Indented re-serialization rather than the
         * source: the lenient reader takes text (raw control characters in strings) that the strict check refuses.
         */
        @JvmStatic
        fun fromSingBoxOutboundText(text: String): Custom? {
            val obj = JsonInput.parseObjectOrNull(text) ?: return null
            if (obj.contains("protocol") || obj.contains("outbounds") || obj.contains("endpoints") || !obj.contains("type")) return null
            val custom = Custom()
            custom.subtype = CUSTOM_OUTBOUND
            custom.config = JsonWriter.writeIndented(obj)
            return custom
        }

        /**
         * SubscriptionParser.cpp:164-179 makeProfileForXrayOutbound: infrastructure protocols are dropped, a VLESS
         * outbound becomes an xrayvless profile when it parses, everything else a custom `xrayoutbound` whose config
         * is the Indented JSON text, named after the tag. Android keeps a VLESS outbound over a network the xrayvless
         * profile does not model custom too, so its settings survive.
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
                    if (!vless.invalid && vless.parseFromJson(normalized) && vless.getXrayStream().network in xrayNetworks) {
                        return vless
                    }
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
         * SubscriptionParser.cpp:396-402: one element of an Xray config array, or (Android only) a single config whose
         * one egress dials through its siblings; its `inbounds` are dropped because Throne injects its own bridge inbound.
         * Null without `outbounds`.
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

        /** custom.cpp xrayAgreed: Xray keeps one spelling of a key, so a label trusts only a value every spelling agrees on; null when they differ. */
        private fun xrayAgreed(o: JsonObject, name: String): Any? {
            val keys = keysNamed(o, name)
            if (keys.isEmpty()) return null
            val first = o[keys[0]]
            return if (keys.all { o[it] == first }) first else null
        }

        /** custom.cpp xrayAgreedName, for names Xray lower-cases: empty when there are none, null when they differ. */
        private fun xrayAgreedName(lowerCased: List<String>): String? {
            if (lowerCased.isEmpty()) return ""
            return if (lowerCased.all { it == lowerCased[0] }) lowerCased[0] else null
        }

        private fun xrayAgreedProtocol(o: JsonObject): String? =
            xrayAgreedName(keysNamed(o, "protocol").map { JsonValues.toStringValue(o[it]).lowercase() })

        /** custom.cpp xrayInlinePeer: Xray lets a non-null top-level address replace vnext/servers, taking the inline user with it; null when the spellings disagree. */
        private fun xrayInlinePeer(settings: JsonObject): Boolean? {
            var set = false
            var unset = false
            for (key in keysNamed(settings, "address")) {
                if (settings[key] === JsonNull) unset = true else set = true
            }
            if (set && unset) return null
            return set
        }

        /** custom.cpp xrayPeer: only VMess and VLESS read vnext; the other protocols read servers and ignore a vnext list. */
        private fun xrayPeer(settings: JsonObject, protocol: String): JsonObject {
            val inlinePeer = xrayInlinePeer(settings) ?: return JsonObject()
            if (inlinePeer) return settings
            val list = if (protocol == "vmess" || protocol == "vless") "vnext" else "servers"
            return (xrayAgreed(settings, list) as? JsonArray)?.firstOrNull() as? JsonObject ?: JsonObject()
        }

        /** custom.cpp xrayOutboundPeer: empty when the protocol is missing or its spellings disagree, which leaves the list Xray reads unknown. */
        private fun xrayOutboundPeer(o: JsonObject): JsonObject {
            val protocol = xrayAgreedProtocol(o)
            if (protocol.isNullOrEmpty()) return JsonObject()
            return xrayPeer(xrayAgreed(o, "settings") as? JsonObject ?: JsonObject(), protocol)
        }

        /** custom.cpp xrayVlessEncryption. */
        private fun xrayVlessEncryption(settings: JsonObject): String {
            val inlinePeer = xrayInlinePeer(settings) ?: return ""
            val user = if (inlinePeer) settings
            else (xrayAgreed(xrayPeer(settings, "vless"), "users") as? JsonArray)?.firstOrNull() as? JsonObject ?: JsonObject()
            return JsonValues.toStringValue(xrayAgreed(user, "encryption"))
        }

        private fun xrayOutboundAddress(o: JsonObject): String = JsonValues.toStringValue(xrayAgreed(xrayOutboundPeer(o), "address"))

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

        /** custom.cpp xrayOutboundSecurity. */
        private fun xrayOutboundSecurity(o: JsonObject): SecurityInfo {
            val agreedProtocol = xrayAgreedProtocol(o)
            if (agreedProtocol != null && (agreedProtocol.isEmpty() || isXrayInfra(agreedProtocol))) return SecurityInfo()
            // Disagreeing spellings leave the protocol unknown, so only TLS or REALITY lift the label above Raw.
            val protocol = agreedProtocol ?: ""

            // Go's decoder fills one stream from every spelling of streamSettings; a null one discards those decoded before it.
            val streams = ArrayList<JsonObject>()
            var streamsKnown = true
            for (key in keysNamed(o, "streamSettings")) {
                val value = o[key]
                streamsKnown = streamsKnown && value is JsonObject
                streams.add(value as? JsonObject ?: JsonObject())
            }
            val stream0 = streams.firstOrNull() ?: JsonObject()
            val network = JsonValues.toStringValue(customValueNamed(stream0, "network")).lowercase()
            val finalmask = customValueNamed(stream0, "finalmask") as? JsonObject ?: JsonObject()
            val transport = if (normalizeXrayNetwork(network) == "kcp" && xrayFinalmaskHasXdns(finalmask)) "XDNS" else displayTransportName(network)
            val securities = streams.flatMap { stream -> keysNamed(stream, "security").map { JsonValues.toStringValue(stream[it]).lowercase() } }
            val security = if (streamsKnown) xrayAgreedName(securities) ?: "" else ""
            var info = when (security) {
                "reality" -> SecurityInfo("Reality", transport, SecurityLevel.Secure)
                "tls" -> {
                    var insecure = false
                    for (stream in streams) {
                        for (tlsKey in keysNamed(stream, "tlsSettings")) {
                            val tls = stream.obj(tlsKey)
                            for (key in keysNamed(tls, "allowInsecure")) insecure = insecure || tls.bool(key)
                        }
                    }
                    if (insecure) SecurityInfo("Insecure TLS", transport, SecurityLevel.Weak) else SecurityInfo("TLS", transport, SecurityLevel.Secure)
                }
                else -> SecurityInfo("Raw", transport, SecurityLevel.None)
            }
            // Shadowsocks, WireGuard and VLESS Encryption wrap the whole connection, both XHTTP legs included; Xray's none and plain ciphers do not encrypt.
            val ssMethod = xrayLower(JsonValues.toStringValue(xrayAgreed(xrayOutboundPeer(o), "method")))
            val encrypts = (protocol == "shadowsocks" && ssMethod.isNotEmpty() && ssMethod != "none" && ssMethod != "plain") ||
                protocol == "wireguard" ||
                (protocol == "vless" && XrayVless.isVlessEncrypted(xrayVlessEncryption(xrayAgreed(o, "settings") as? JsonObject ?: JsonObject())))
            if (!encrypts) info = withXrayDownloadLeg(info, xrayOutboundAddress(o), xrayDownloadLeg(o))
            if (info.level != SecurityLevel.None) return info
            // Without transport security those still encrypt; VMess counts as insecure.
            if (encrypts) return info.copy(label = "Encrypted", level = SecurityLevel.Secure)
            if (protocol == "vmess") return info.copy(label = "Insecure", level = SecurityLevel.Weak)
            return info
        }

        /** custom.cpp analyzeXrayOutbound. */
        private fun analyzeXrayOutbound(o: JsonObject): SecurityInfo = withPrivateServer(xrayOutboundSecurity(o), xrayOutboundAddress(o))

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

/** The Checks of custom.h. */
object CustomChecks {
    /** Checks::CustomConfig (custom.cpp): valid JSON (line and column on a syntax error) with what the subtype needs: a type, a protocol or an outbounds list. */
    @JvmStatic
    fun customConfig(subtype: String, text: String): String {
        Validation.jsonObject(text, Empty.Required).let {
            if (it.isNotEmpty()) return if (!hasJsonComment(text)) it else it + " " + vtr(R.string.validation_custom_json_comments)
        }
        val root = JsonInput.parseObject(text)
        if (subtype == Custom.CUSTOM_OUTBOUND && JsonValues.toStringValue(customValueNamed(root, "type")).trim().isEmpty()) {
            return vtr(R.string.validation_custom_type_missing)
        }
        if (subtype == Custom.CUSTOM_XRAY_OUTBOUND && JsonValues.toStringValue(customValueNamed(root, "protocol")).trim().isEmpty()) {
            return vtr(R.string.validation_custom_protocol_missing)
        }
        // A missing list fails too: the desktop's customValueNamed gives a null QJsonValue then.
        if ((subtype == Custom.CUSTOM_FULL_CONFIG || subtype == Custom.CUSTOM_XRAY_FULL_CONFIG) && customValueNamed(root, "outbounds") !is JsonArray) {
            return vtr(R.string.validation_custom_outbounds_list)
        }
        return ""
    }
}

/**
 * StripXrayStreamKeyLog (custom.cpp): removes every spelling of masterKeyLog from an Xray stream's TLS and REALITY
 * settings, its xdns resolvers and its XHTTP download legs, editing [stream] in place; true when any went.
 */
fun stripXrayStreamKeyLog(stream: JsonObject, at: String, issues: MutableList<Issue>): Boolean = stripXrayKeyLog(stream, at, issues)

/** custom.cpp keysNamed: the core matches object keys case-insensitively, so every spelling of a key sets the same option. */
private fun keysNamed(o: JsonObject, name: String): List<String> = o.sortedKeys().filter { it.equals(name, ignoreCase = true) }

/** custom.cpp customValueNamed: null when no spelling is present. */
private fun customValueNamed(o: JsonObject, name: String): Any? = keysNamed(o, name).firstOrNull()?.let { o[it] }

private fun isBlank(value: Any?): Boolean = value == null || value === JsonNull || (value is String && value.isEmpty()) ||
    (value is JsonArray && value.isEmpty()) || (value is JsonObject && value.isEmpty())

/** torrc keys reach Tor's SETCONF unescaped. */
private fun isTorOptionName(key: String): Boolean =
    key.isNotEmpty() && key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '_' }

/** A pluggable-transport line ("obfs4 exec /path/to/program") makes Tor spawn that program; Tor also takes abbreviated option names. */
private fun isTorPluginOption(key: String): Boolean = key.length >= 4 &&
    ("ClientTransportPlugin".startsWith(key, ignoreCase = true) || "ServerTransportPlugin".startsWith(key, ignoreCase = true))

/** custom.cpp stripLaunchers: removes what would make the core start a program; true when anything went. */
private fun stripLaunchers(o: JsonObject, at: String, issues: MutableList<Issue>): Boolean {
    var removed = false
    fun report(key: String, message: String, value: String = "") {
        issues.add(Issue(Severity.Warning, at + key, message, value))
        removed = true
    }

    fun strip(name: String, message: String, showValue: Boolean) {
        for (key in keysNamed(o, name)) {
            if (isBlank(o[key])) continue
            report(key, message, if (showValue) JsonValues.toStringValue(o[key]) else "")
            o.remove(key)
        }
    }
    when (o.string("type")) {
        "tor" -> {
            strip("executable_path", vtr(R.string.validation_custom_tor_executable), true)
            strip("extra_args", vtr(R.string.validation_custom_tor_args), false)
            strip("data_directory", vtr(R.string.validation_custom_tor_data_directory), true)
            for (torrcKey in keysNamed(o, "torrc")) {
                val torrc = o[torrcKey] as? JsonObject ?: continue
                for (key in torrc.sortedKeys()) {
                    if (!isTorOptionName(key)) {
                        report(torrcKey, vtr(R.string.validation_custom_tor_option_name), key)
                    } else if (isTorPluginOption(key)) {
                        report("$torrcKey.$key", vtr(R.string.validation_custom_tor_plugin, key), JsonValues.toStringValue(torrc[key]))
                    } else {
                        continue
                    }
                    torrc.remove(key)
                }
            }
        }

        "openconnect" -> for (section in listOf("csd", "hip", "tncc")) {
            for (sectionKey in keysNamed(o, section)) {
                val wrapper = o[sectionKey] as? JsonObject ?: continue
                for (key in keysNamed(wrapper, "wrapper_path")) {
                    if (isBlank(wrapper[key])) continue
                    report("$sectionKey.$key", vtr(R.string.validation_custom_wrapper_path, section.uppercase()), JsonValues.toStringValue(wrapper[key]))
                    wrapper.remove(key)
                }
            }
        }
    }
    return removed
}

private fun stripNamed(o: JsonObject, name: String, at: String, message: String, showValue: Boolean, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (key in keysNamed(o, name)) {
        val value = o[key]
        if (isBlank(value)) continue
        issues.add(Issue(Severity.Warning, at + key, message, if (showValue) JsonValues.toStringValue(value) else ""))
        o.remove(key)
        removed = true
    }
    return removed
}

/** Runs [edit] on every spelling of [name] that holds an object, in place, with the field prefix of that object. */
private fun editObjectNamed(o: JsonObject, name: String, at: String, edit: (JsonObject, String) -> Boolean): Boolean {
    var changed = false
    for (key in keysNamed(o, name)) {
        val inner = o[key] as? JsonObject ?: continue
        if (edit(inner, "$at$key.")) changed = true
    }
    return changed
}

private fun editListNamed(o: JsonObject, name: String, at: String, edit: (JsonObject, String) -> Boolean): Boolean {
    var changed = false
    for (key in keysNamed(o, name)) {
        val items = o[key] as? JsonArray ?: continue
        for ((i, item) in items.withIndex()) {
            if (item is JsonObject && edit(item, "$at$key[$i].")) changed = true
        }
    }
    return changed
}

private fun opensPortMessage() = vtr(R.string.validation_custom_opens_port)

private fun writesFileMessage() = vtr(R.string.validation_custom_writes_file)

private fun xrayReverseMessage() = vtr(R.string.validation_custom_xray_reverse)

private val LOCAL_INBOUND_TYPES = listOf("mixed", "socks", "http", "direct", "redirect", "tproxy")

/** QHostAddress(text).isLoopback(): 127.0.0.0/8, ::1 and the v4-mapped 127.0.0.0/8, a scope id allowed. */
private fun isLoopbackLiteral(text: String): Boolean {
    val a = text.trim()
    if (a.contains(':')) {
        val pct = a.lastIndexOf('%')
        val v6 = Hosts.parseIpv6(if (pct >= 0) a.substring(0, pct) else a)
        if (v6 != null) {
            if ((0 until 15).all { v6[it].toInt() == 0 } && v6[15].toInt() == 1) return true
            val mapped = (0 until 10).all { v6[it].toInt() == 0 } && v6[10] == 0xFF.toByte() && v6[11] == 0xFF.toByte()
            return mapped && v6[12].toInt() == 127
        }
    }
    val v4 = Hosts.parseIpv4(a) ?: return false
    return (v4 shr 24) == 127L
}

/** custom.cpp isLocalSingBoxInbound: every spelling must agree, the core may read any of them. */
private fun isLocalSingBoxInbound(inbound: JsonObject): Boolean {
    val typeKeys = keysNamed(inbound, "type")
    if (typeKeys.isEmpty()) return false
    var tun = true
    var local = true
    for (key in typeKeys) {
        val t = inbound.string(key)
        tun = tun && t == "tun"
        local = local && t in LOCAL_INBOUND_TYPES
    }
    if (tun) return true
    if (!local) return false
    // TLS can carry ACME, which answers challenges on public ports.
    if (keysNamed(inbound, "tls").any { !isBlank(inbound[it]) }) return false
    // The core listens on 127.0.0.1 when no address is given.
    for (key in keysNamed(inbound, "listen")) {
        val listen = inbound[key]
        if (!isBlank(listen) && !isLoopbackLiteral(JsonValues.toStringValue(listen))) return false
    }
    return true
}

/** custom.cpp stripSingBoxExposure: removes what would open a port on this device or write a file; Throne runs a full config as it is. */
private fun stripSingBoxExposure(root: JsonObject, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (key in keysNamed(root, "inbounds")) {
        val items = root[key] as? JsonArray ?: continue
        val kept = JsonArray()
        for ((i, item) in items.withIndex()) {
            val inbound = item as? JsonObject ?: JsonObject()
            if (isLocalSingBoxInbound(inbound)) {
                kept.add(item)
                continue
            }
            issues.add(
                Issue(
                    Severity.Warning, "$key[$i]", vtr(R.string.validation_custom_inbound_removed),
                    JsonValues.toStringValue(customValueNamed(inbound, "type")),
                ),
            )
        }
        if (kept.size == items.size) continue
        root[key] = kept
        removed = true
    }
    removed = stripNamed(root, "services", "", opensPortMessage(), false, issues) or removed
    removed = stripNamed(root, "certificate_providers", "", opensPortMessage(), false, issues) or removed
    removed = editObjectNamed(root, "log", "") { log, at -> stripNamed(log, "output", at, writesFileMessage(), true, issues) } or removed
    removed = editObjectNamed(root, "experimental", "") { experimental, at ->
        var changed = stripNamed(experimental, "clash_api", at, vtr(R.string.validation_custom_clash_api), false, issues)
        // The core builds the V2Ray API only when it listens.
        for (key in keysNamed(experimental, "v2ray_api")) {
            val api = experimental.obj(key)
            if (keysNamed(api, "listen").all { isBlank(api[it]) }) continue
            issues.add(Issue(Severity.Warning, at + key, vtr(R.string.validation_custom_v2ray_api)))
            experimental.remove(key)
            changed = true
        }
        changed = editObjectNamed(experimental, "debug", at) { debug, debugAt ->
            stripNamed(debug, "listen", debugAt, opensPortMessage(), true, issues)
        } or changed
        changed = editObjectNamed(experimental, "cache_file", at) { cache, cacheAt ->
            stripNamed(cache, "path", cacheAt, writesFileMessage(), true, issues)
        } or changed
        changed
    } or removed
    removed = editListNamed(root, "network_namespaces", "") { ns, at -> stripNamed(ns, "pid_file", at, writesFileMessage(), true, issues) } or removed
    return removed
}

/** custom.cpp stripXrayFilePath: Xray reads "none" as no file. */
private fun stripXrayFilePath(o: JsonObject, name: String, at: String, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (key in keysNamed(o, name)) {
        val value = o[key]
        if (isBlank(value) || JsonValues.toStringValue(value) == "none") continue
        issues.add(Issue(Severity.Warning, at + key, writesFileMessage(), JsonValues.toStringValue(value)))
        o.remove(key)
        removed = true
    }
    return removed
}

/** custom.cpp stripXrayKeyLog: XHTTP's download leg is a stream of its own, also reachable through "extra". */
private fun stripXrayKeyLog(stream: JsonObject, at: String, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (name in listOf("tlsSettings", "realitySettings")) {
        removed = editObjectNamed(stream, name, at) { security, securityAt ->
            stripXrayFilePath(security, "masterKeyLog", securityAt, issues)
        } or removed
    }
    val stripDownload: (JsonObject, String) -> Boolean = { o, oAt ->
        editObjectNamed(o, "downloadSettings", oAt) { download, downloadAt -> stripXrayKeyLog(download, downloadAt, issues) }
    }
    for (name in listOf("xhttpSettings", "splithttpSettings")) {
        removed = editObjectNamed(stream, name, at) { xhttp, xhttpAt ->
            val changed = stripDownload(xhttp, xhttpAt)
            editObjectNamed(xhttp, "extra", xhttpAt, stripDownload) or changed
        } or removed
    }
    removed = editObjectNamed(stream, "finalmask", at) { finalmask, finalmaskAt ->
        stripXrayFinalmaskKeyLog(finalmask, finalmaskAt, issues)
    } or removed
    return removed
}

private fun stripXrayOutbound(outbound: JsonObject, at: String, issues: MutableList<Issue>): Boolean {
    val reverse = editObjectNamed(outbound, "settings", at) { settings, settingsAt ->
        stripNamed(settings, "reverse", settingsAt, xrayReverseMessage(), false, issues)
    }
    val keyLog = editObjectNamed(outbound, "streamSettings", at) { stream, streamAt -> stripXrayKeyLog(stream, streamAt, issues) }
    return reverse || keyLog
}

private fun stripXrayExposure(root: JsonObject, issues: MutableList<Issue>): Boolean {
    var removed = stripNamed(root, "inbounds", "", vtr(R.string.validation_custom_xray_inbounds), false, issues)
    removed = stripNamed(root, "api", "", opensPortMessage(), false, issues) or removed
    removed = stripNamed(root, "metrics", "", opensPortMessage(), false, issues) or removed
    removed = stripNamed(root, "reverse", "", xrayReverseMessage(), false, issues) or removed
    // Xray runs inside the core process and applies "env" with os.Setenv.
    removed = stripNamed(root, "env", "", vtr(R.string.validation_custom_xray_env), false, issues) or removed
    // The geodata updater swaps files in the asset folder, which is Throne's own folder.
    removed = stripNamed(root, "geodata", "", vtr(R.string.validation_custom_xray_geodata), false, issues) or removed
    removed = editObjectNamed(root, "routing", "") { routing, at ->
        editListNamed(routing, "rules", at) { rule, ruleAt ->
            stripNamed(rule, "webhook", ruleAt, vtr(R.string.validation_custom_xray_webhook), false, issues)
        }
    } or removed
    removed = editObjectNamed(root, "log", "") { log, at ->
        val access = stripXrayFilePath(log, "access", at, issues)
        stripXrayFilePath(log, "error", at, issues) || access
    } or removed
    removed = editListNamed(root, "outbounds", "") { outbound, at -> stripXrayOutbound(outbound, at, issues) } or removed
    return removed
}

/** custom.cpp hasJsonComment: a line or block comment opener outside a string; one inside (an https:// URL) is no comment. */
private fun hasJsonComment(text: String): Boolean {
    var inString = false
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (inString) {
            if (c == '\\') i++ else if (c == '"') inString = false
        } else if (c == '"') {
            inString = true
        } else if (c == '/' && i + 1 < text.length && (text[i + 1] == '/' || text[i + 1] == '*')) {
            return true
        }
        i++
    }
    return false
}
