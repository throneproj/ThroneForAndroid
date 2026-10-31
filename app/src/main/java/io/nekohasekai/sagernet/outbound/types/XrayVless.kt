package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.XrayMultiplex
import io.nekohasekai.sagernet.outbound.common.XrayStreamSetting
import io.nekohasekai.sagernet.outbound.common.normalizeXrayNetwork
import io.nekohasekai.sagernet.outbound.common.stripXrayDialerState
import io.nekohasekai.sagernet.outbound.common.withXrayDownloadLeg
import io.nekohasekai.sagernet.outbound.common.xrayDownloadLeg
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** xrayFlows (xrayVless.h). */
val xrayFlows = listOf("xtls-rprx-vision", "xtls-rprx-vision-udp443")

/** The Checks of xrayVless.h. */
object XrayVlessChecks {
    /** Xray infra/conf/vless.go: "none" or mlkem768x25519plus.<native|xorpub|random>.<1rtt|0rtt>.<parts>, parts of 20+ chars being keys. */
    @JvmStatic
    fun xrayVlessEncryption(encryption: String): String {
        if (encryption.isEmpty()) return vtr(R.string.validation_xray_vless_encryption_none)
        if (encryption == "none") return ""
        val parts = encryption.split('.')
        if (parts.size < 4 || parts[0] != "mlkem768x25519plus") return vtr(R.string.validation_xray_vless_encryption_format)
        if (parts[1] != "native" && parts[1] != "xorpub" && parts[1] != "random") return vtr(R.string.validation_xray_vless_encryption_mode)
        if (parts[2] != "1rtt" && parts[2] != "0rtt") return vtr(R.string.validation_xray_vless_encryption_rtt)
        // Shorter parts are padding specs.
        for (i in 3 until parts.size) {
            if (parts[i].length < 20) continue
            if (Validation.base64Key(parts[i], 32, true, false).isEmpty() || Validation.base64Key(parts[i], 1184, true, false).isEmpty()) continue
            return vtr(R.string.validation_xray_vless_encryption_key)
        }
        return ""
    }

    /** Without VLESS encryption, Vision needs TLS or REALITY directly on the raw network. */
    @JvmStatic
    fun xrayVlessVision(flow: String, encryption: String, network: String, security: String): String {
        if (!flow.startsWith("xtls-rprx-vision") || XrayVless.isVlessEncrypted(encryption)) return ""
        val normalized = normalizeXrayNetwork(network)
        if ((normalized.isEmpty() || normalized == "raw") && (security == "tls" || security == "reality")) return ""
        return vtr(R.string.validation_xray_vless_vision)
    }

    /** Xray runs XDRIVE only for VLESS with encryption, no flow, and Mux on (Keep Default counts: the build turns it on). */
    @JvmStatic
    fun xrayXDriveEncryption(encryption: String, network: String): String {
        if (normalizeXrayNetwork(network.trim()) != "xdrive" || XrayVless.isVlessEncrypted(encryption.trim())) return ""
        return vtr(R.string.validation_xray_xdrive_encryption)
    }

    @JvmStatic
    fun xrayXDriveFlow(flow: String, network: String): String {
        val f = flow.trim()
        if (normalizeXrayNetwork(network.trim()) != "xdrive" || f.isEmpty() || f.equals("none", ignoreCase = true)) return ""
        return vtr(R.string.validation_xray_xdrive_flow)
    }

    /** [muxState] as XrayMultiplex.getMuxState(): 0 Keep Default, 1 On, 2 Off, 3 Custom. */
    @JvmStatic
    fun xrayXDriveMux(muxState: Int, network: String): String {
        if (normalizeXrayNetwork(network.trim()) != "xdrive" || muxState != 2) return ""
        return vtr(R.string.validation_xray_xdrive_mux)
    }
}

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
    override fun hasXrayStream(): Boolean = true
    override fun getXrayStream(): XrayStreamSetting = streamSetting
    override fun getXrayMultiplex(): XrayMultiplex = multiplex

    /** xrayVless::ParseFromClash. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "vless") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        val clashFlow = proxy.string("flow")
        if (clashFlow.isNotEmpty()) flow = importFlow(clashFlow)
        val clashEncryption = proxy.string("encryption")
        if (clashEncryption.isNotEmpty()) encryption = importEncryption(clashEncryption)
        streamSetting.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        dropUnusableFlow()
        return true
    }

    /** xrayVless::ParseFromLink: no default port; a network the Xray stream refuses drops the link. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        uuid = url.userName
        encryption = importEncryption(q.valueOr("encryption", "none"))
        flow = importFlow(q.value("flow"))
        if (!streamSetting.parseFromLink(url)) return false
        multiplex.parseFromLink(url)
        dropUnusableFlow()
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /**
     * xrayVless::ParseFromJson on the flat `settings` object (no base parse: no dial fields, no ACE conversion of the
     * address). A real Xray outbound with `settings.vnext` is flattened first the way the desktop subscription
     * parser does before calling this ([normalizeForParse]). False when the stream does not parse, so the importer
     * keeps a lossless custom Xray outbound; the DB loader ignores the result.
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
        var streamParsed = true
        val stream = source.obj("streamSettings")
        if (stream.isNotEmpty()) streamParsed = streamSetting.parseFromJson(stream)
        val mux = source.obj("mux")
        if (mux.isNotEmpty()) multiplex.parseFromJson(mux)
        if (ParseDiag.active()) {
            flow = importFlow(flow)
            encryption = importEncryption(encryption)
            dropUnusableFlow()
        }
        return streamParsed
    }

    /** xrayVless::ExportToLink: no dial-field query items; '+' goes out as %2B so receivers don't decode it as a space. */
    override fun exportToLink(): String {
        // Callers fall back to exportJsonLink, which keeps the storage settings a vless:// link cannot carry.
        if (normalizeXrayNetwork(streamSetting.network) == "xdrive") return ""
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

    /** xrayVless::ExportToJson. */
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

    /** xrayVless::Build: the sing-box side placeholder (CheckConfig / IsValid only). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "socks"
        obj["server"] = "127.0.0.1"
        return BuildResult(obj)
    }

    /** xrayVless::BuildXray; no bind interface, so no BindXrayDownloadLeg: binding is wired by the core. */
    override fun buildXray(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["protocol"] = "vless"
        obj["settings"] = settingsJson()
        val streamObj = streamSetting.build(ctx)
        if (streamObj.isNotEmpty()) obj["streamSettings"] = streamObj
        // Xray refuses XDRIVE without Mux, so Keep Default turns it on there.
        val muxRequired = normalizeXrayNetwork(streamSetting.network) == "xdrive"
        val muxObj = multiplex.buildMux(ctx, muxRequired || ctx.xrayMuxDefaultOn)
        if (muxObj.isNotEmpty()) {
            // It also refuses any traffic leaving the mux there, which a preset may ask for.
            if (muxRequired) {
                if (muxObj.int("concurrency") < 0) muxObj.remove("concurrency")
                if (muxObj.int("xudpConcurrency") < 0) muxObj.remove("xudpConcurrency")
                if (muxObj.string("xudpProxyUDP443") == "skip") muxObj.remove("xudpProxyUDP443")
            }
            obj["mux"] = muxObj
        }
        return BuildResult(obj)
    }

    /** xrayVless ExportToJson / BuildXray: address, port, id and encryption always; flow unless empty or "none". */
    private fun settingsJson(): JsonObject {
        val settings = JsonObject()
        settings["address"] = server
        settings["port"] = serverPort
        settings["id"] = uuid
        settings["encryption"] = encryption
        if (flow.isNotEmpty() && flow != "none") settings["flow"] = flow
        return settings
    }

    /** xrayVless.h. */
    override fun displayType(): String = "VLESS (Xray)"

    /** xrayVless::GetSecurity: VLESS Encryption wraps the whole XHTTP connection, so the download leg cannot weaken it. */
    override fun security(): SecurityInfo {
        val info = super.security()
        if (isVlessEncrypted(encryption)) {
            if (info.level == SecurityLevel.None) return info.copy(label = "Encrypted", level = SecurityLevel.Secure)
            return info
        }
        if (normalizeXrayNetwork(streamSetting.network) != "xhttp") return info
        val stream = jsonObjectOf("network" to "xhttp", "xhttpSettings" to streamSetting.xhttp.exportToJson())
        return withXrayDownloadLeg(info, getAddress(), xrayDownloadLeg(jsonObjectOf("streamSettings" to stream)))
    }

    /** xrayVless::StripForeignState: the XHTTP download leg is a whole Xray stream kept as written, so it can carry a TLS key log and the source's dialer settings. */
    override fun stripForeignState(issues: MutableList<Issue>) {
        val x = streamSetting.xhttp
        val at = "streamSettings.xhttpSettings.extra."
        fun strip(download: JsonObject, downloadAt: String): Boolean {
            val keyLog = stripXrayStreamKeyLog(download, downloadAt, issues)
            return stripXrayDialerState(download) || keyLog
        }
        val download = JsonInput.parseObject(x.downloadSettings)
        if (download.isNotEmpty() && strip(download, at + "downloadSettings.")) x.downloadSettings = download.toCompact()
        for (key in x.rawExtra.keys()) {
            if (!key.equals("downloadSettings", ignoreCase = true)) continue
            val extraDownload = x.rawExtra[key] as? JsonObject ?: continue
            strip(extraDownload, "$at$key.")
        }
    }

    /** xrayVless::Validate. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "id", Validation.xrayId(uuid))
        issues.addIssue(Severity.Error, "encryption", XrayVlessChecks.xrayVlessEncryption(encryption))
        issues.addIssue(Severity.Error, "flow", flowProblem(), flow)
        issues.addIssue(Severity.Error, "encryption", XrayVlessChecks.xrayXDriveEncryption(encryption, streamSetting.network))
        issues.addIssue(Severity.Error, "mux", XrayVlessChecks.xrayXDriveMux(multiplex.getMuxState(), streamSetting.network))
        multiplex.validate(issues)
    }

    /** xrayVlessFlowProblem: shared by [validate] and the import's [dropUnusableFlow]. */
    private fun flowProblem(): String {
        Validation.oneOf(flow, listOf("", "none") + xrayFlows).let { if (it.isNotEmpty()) return it }
        XrayVlessChecks.xrayVlessVision(flow, encryption, streamSetting.network, streamSetting.security).let { if (it.isNotEmpty()) return it }
        return XrayVlessChecks.xrayXDriveFlow(flow, streamSetting.network)
    }

    /** dropUnusableXrayVlessFlow: no flow is Xray's default; an unknown one stops it and a misplaced Vision fails every connection. */
    private fun dropUnusableFlow() {
        if (!acceptOptional("flow", flowProblem(), flow)) flow = ""
    }

    companion object {
        /**
         * IsVlessEncrypted (xrayVless.h): Xray loads only "none" or an mlkem768x25519plus key string, which
         * encrypts the payload without transport security.
         */
        @JvmStatic
        fun isVlessEncrypted(encryption: String): Boolean = encryption.isNotEmpty() && encryption != "none"

        /** xrayVlessImportFlow: Xray compares flow names exactly. */
        private fun importFlow(raw: String): String = raw.trim().lowercase()

        /**
         * xrayVlessImportEncryption: empty (share links leave it out for none, which Xray refuses) or "none" in any
         * case becomes "none"; key strings are kept as written.
         */
        private fun importEncryption(raw: String): String {
            val value = raw.trim()
            return if (value.isEmpty() || value.equals("none", ignoreCase = true)) "none" else value
        }

        /**
         * normalizeXrayVlessForParse (SubscriptionParser.cpp): a real Xray outbound nests the server under
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
