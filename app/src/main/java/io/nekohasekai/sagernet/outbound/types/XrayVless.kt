package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.XrayMultiplex
import io.nekohasekai.sagernet.outbound.common.XrayStreamSetting
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser

/**
 * xrayVless (include/configs/outbounds/xrayVless.h, src/configs/outbounds/xrayVless.cpp): the Xray-core VLESS
 * outbound. Its JSON is the Xray outbound shape (`protocol`, `settings`, `streamSettings`, `mux`), never a
 * sing-box `type` object; [build] only yields the dummy the desktop uses for config validation, the real hop
 * is [buildXray] (the generator adds `tag` and the dialer proxy).
 */
class XrayVless : Outbound("xrayvless") {
    @JvmField var uuid: String = ""
    @JvmField var encryption: String = "none"
    @JvmField var flow: String = ""
    @JvmField var streamSetting: XrayStreamSetting = XrayStreamSetting()
    @JvmField var multiplex: XrayMultiplex = XrayMultiplex()

    override fun isXray(): Boolean = true
    override fun getXrayStream(): XrayStreamSetting = streamSetting
    override fun getXrayMultiplex(): XrayMultiplex = multiplex

    /** xrayVless.cpp:41-50. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "vless") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        val clashFlow = proxy.string("flow")
        if (clashFlow.isNotEmpty()) flow = clashFlow
        val clashEncryption = proxy.string("encryption")
        if (clashEncryption.isNotEmpty()) encryption = clashEncryption
        streamSetting.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        return true
    }

    /** xrayVless.cpp:8-20: no default port. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        uuid = url.userName
        encryption = q.valueOr("encryption", "none")
        flow = q.valueOr("flow", "")
        streamSetting.parseFromLink(url)
        multiplex.parseFromLink(url)
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /**
     * xrayVless.cpp:22-39 on the flat `settings` object (no base parse: no dial fields, no ACE conversion of the
     * address). A real Xray outbound with `settings.vnext` is flattened first the way the desktop subscription
     * parser does before calling this ([normalizeForParse]).
     */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("protocol") != "vless") return false
        val source = normalizeForParse(obj) ?: obj
        if (source.contains("tag")) name = source.string("tag")
        val settings = source.obj("settings")
        if (settings.isNotEmpty()) {
            if (settings.contains("address")) server = settings.string("address")
            if (settings.contains("port")) serverPort = settings.int("port")
            if (settings.contains("flow")) flow = settings.string("flow")
            if (settings.contains("id")) uuid = settings.string("id")
            if (settings.contains("encryption")) encryption = settings.string("encryption")
        }
        val stream = source.obj("streamSettings")
        if (stream.isNotEmpty()) streamSetting.parseFromJson(stream)
        val mux = source.obj("mux")
        if (mux.isNotEmpty()) multiplex.parseFromJson(mux)
        return true
    }

    /** xrayVless.cpp:67-84: no dial-field query items; '+' goes out as %2B so receivers don't decode it as a space. */
    override fun exportToLink(): String {
        val url = LinkBuilder("vless")
        url.plusAsEscape = true
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItem("encryption", encryption)
        if (flow.isNotEmpty()) url.addQueryItem("flow", flow)
        url.addQueryItems(streamSetting.exportToLink())
        url.addQueryItems(multiplex.exportToLink())
        return url.build()
    }

    /** xrayVless.cpp:71-85. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (name.isNotEmpty()) obj["tag"] = name
        obj["protocol"] = "vless"
        obj["settings"] = settingsJson()
        val streamObj = streamSetting.exportToJson()
        if (streamObj.isNotEmpty()) obj["streamSettings"] = streamObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["mux"] = muxObj
        return obj
    }

    /** xrayVless.cpp:87-92: the sing-box side placeholder (CheckConfig / IsValid only). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj["server"] = "127.0.0.1"
        return BuildResult(obj)
    }

    /** xrayVless.cpp:94-107. */
    override fun buildXray(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["protocol"] = "vless"
        obj["settings"] = settingsJson()
        val streamObj = streamSetting.build(ctx)
        if (streamObj.isNotEmpty()) obj["streamSettings"] = streamObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["mux"] = muxObj
        return BuildResult(obj)
    }

    /** xrayVless.cpp:74-79 / 96-101: address, port, id and encryption always; flow unless empty or "none". */
    private fun settingsJson(): JsonObject {
        val settings = JsonObject()
        settings["address"] = server
        settings["port"] = serverPort
        settings["id"] = uuid
        settings["encryption"] = encryption
        if (flow.isNotEmpty() && flow != "none") settings["flow"] = flow
        return settings
    }

    /** xrayVless.h:26-28. */
    override fun displayType(): String = "VLESS (Xray)"

    /** xrayVless.cpp:126-133. */
    override fun security(): SecurityInfo {
        val info = super.security()
        if (info.level == SecurityLevel.None && isVlessEncrypted(encryption)) {
            return info.copy(label = "Encrypted", level = SecurityLevel.Secure)
        }
        return info
    }

    companion object {
        /**
         * IsVlessEncrypted (xrayVless.h:9-10): Xray loads only "none" or an mlkem768x25519plus key string, which
         * encrypts the payload without transport security.
         */
        @JvmStatic
        fun isVlessEncrypted(encryption: String): Boolean = encryption.isNotEmpty() && encryption != "none"

        /**
         * normalizeXrayVlessForParse (SubscriptionParser.cpp:142-162): a real Xray outbound nests the server under
         * `settings.vnext[0]` and the user under its `users[0]`, while [parseFromJson] reads a flat `settings`.
         * Returns the object unchanged when it is already flat, the flattened copy when the nested form is complete,
         * and null when it is not a vless outbound or the nested form is incomplete (the desktop then keeps such an
         * outbound as a custom Xray outbound instead).
         */
        @JvmStatic
        fun normalizeForParse(obj: JsonObject): JsonObject? {
            if (obj.string("protocol") != "vless") return null
            val settings = obj.obj("settings")
            if (settings.contains("address") && !settings.contains("vnext")) return obj
            val vnext = settings.array("vnext")
            if (vnext.isEmpty()) return null
            val first = vnext[0] as? JsonObject ?: return null
            if (first.isEmpty()) return null
            val users = first.array("users")
            if (users.isEmpty()) return null
            val user = users[0] as? JsonObject ?: JsonObject()
            val simple = JsonObject()
            if (first.contains("address")) simple["address"] = first["address"]
            if (first.contains("port")) simple["port"] = first["port"]
            if (user.contains("id")) simple["id"] = user["id"]
            simple["encryption"] = if (user.contains("encryption")) user["encryption"] else "none"
            if (user.contains("flow")) simple["flow"] = user["flow"]
            val normalized = obj.copy()
            normalized["settings"] = simple
            return normalized
        }
    }
}
