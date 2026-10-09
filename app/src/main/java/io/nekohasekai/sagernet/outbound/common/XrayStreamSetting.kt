package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink

/** xrayStreamSetting.h:5-9 */
val xrayNetworks = listOf("raw", "xhttp", "ws", "httpupgrade", "grpc")
/** xrayTLS (xrayStreamSetting.h:17-33, xrayStreamSetting.cpp:248-318). No insecure flag is modelled. */
class XrayTls {
    @JvmField var serverName: String = ""
    @JvmField var pinnedPeerCertSha256: String = ""
    @JvmField var verifyPeerCertByName: String = ""
    @JvmField var alpn: MutableList<String> = ArrayList()
    @JvmField var fingerprint: String = ""
    /** Base64 ECHConfigList, or "<query domain>+<DoH url>" for Xray to fetch it itself. */
    @JvmField var echConfigList: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:248-262. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("sni")) serverName = q.value("sni")
        if (q.has("peer")) serverName = q.value("peer")
        if (q.has("server_name")) serverName = q.value("server_name")
        if (q.has("pcs")) pinnedPeerCertSha256 = q.valueFully("pcs")
        if (q.has("vcn")) verifyPeerCertByName = q.valueFully("vcn")
        if (q.has("alpn")) alpn = QtStrings.split(q.valueFully("alpn"), ",")
        if (q.has("fp")) fingerprint = q.value("fp")
        if (q.has("ech")) echConfigList = q.valueFully("ech")
        return true
    }

    /** xrayStreamSetting.cpp:264-273. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("serverName")) serverName = obj.string("serverName")
        if (obj.contains("pinnedPeerCertSha256")) pinnedPeerCertSha256 = obj.string("pinnedPeerCertSha256")
        if (obj.contains("verifyPeerCertByName")) verifyPeerCertByName = obj.string("verifyPeerCertByName")
        if (obj.contains("alpn")) alpn = obj.array("alpn").strings()
        if (obj.contains("fingerprint")) fingerprint = obj.string("fingerprint")
        if (obj.contains("echConfigList")) echConfigList = obj.string("echConfigList")
        return true
    }

    /** xrayStreamSetting.cpp:275-288. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        serverName = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        alpn.addAll(proxy.strings("alpn"))
        val clientFingerprint = proxy.string("client-fingerprint")
        if (clientFingerprint.isNotEmpty()) fingerprint = clientFingerprint
        return true
    }

    /** xrayStreamSetting.cpp:290-299: sni is always written. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        q.add("sni" to serverName)
        if (pinnedPeerCertSha256.isNotEmpty()) q.add("pcs" to pinnedPeerCertSha256)
        if (verifyPeerCertByName.isNotEmpty()) q.add("vcn" to verifyPeerCertByName)
        if (alpn.isNotEmpty()) q.add("alpn" to alpn.joinToString(","))
        if (fingerprint.isNotEmpty()) q.add("fp" to fingerprint)
        if (echConfigList.isNotEmpty()) q.add("ech" to echConfigList)
        return q
    }

    /** xrayStreamSetting.cpp:301-312: serverName is always written. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["serverName"] = Hosts.toAceHost(serverName)
        if (pinnedPeerCertSha256.isNotEmpty()) obj["pinnedPeerCertSha256"] = pinnedPeerCertSha256
        if (verifyPeerCertByName.isNotEmpty()) obj["verifyPeerCertByName"] = verifyPeerCertByName
        if (alpn.isNotEmpty()) obj["alpn"] = JsonValues.stringArray(alpn)
        if (fingerprint.isNotEmpty()) obj["fingerprint"] = fingerprint
        if (echConfigList.isNotEmpty()) obj["echConfigList"] = echConfigList
        return obj
    }

    /** xrayStreamSetting.cpp:314-318. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (fingerprint.isEmpty() && ctx.utlsFingerprint.isNotEmpty()) obj["fingerprint"] = ctx.utlsFingerprint
        return obj
    }
}

/** xrayReality (xrayStreamSetting.h:33-47, xrayStreamSetting.cpp:316-379). `password` is the public key. */
class XrayReality {
    @JvmField var serverName: String = ""
    @JvmField var fingerprint: String = ""
    @JvmField var password: String = ""
    @JvmField var shortId: String = ""
    @JvmField var spiderX: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:316-329. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("sni")) serverName = q.value("sni")
        if (q.has("peer")) serverName = q.value("peer")
        if (q.has("pbk")) password = q.value("pbk")
        if (q.has("fp")) fingerprint = q.value("fp")
        if (q.has("sid")) shortId = q.value("sid")
        if (q.has("spx")) spiderX = q.valueFully("spx")
        return true
    }

    /** xrayStreamSetting.cpp:331-339. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("serverName")) serverName = obj.string("serverName")
        if (obj.contains("fingerprint")) fingerprint = obj.string("fingerprint")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("shortId")) shortId = obj.string("shortId")
        if (obj.contains("spiderX")) spiderX = obj.string("spiderX")
        return true
    }

    /** xrayStreamSetting.cpp:341-353. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        serverName = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        val clientFingerprint = proxy.string("client-fingerprint")
        if (clientFingerprint.isNotEmpty()) fingerprint = clientFingerprint
        val opts = proxy.obj("reality-opts")
        val publicKey = opts.string("public-key")
        if (publicKey.isNotEmpty()) password = publicKey
        val id = opts.string("short-id")
        if (id.isNotEmpty()) shortId = id
        return true
    }

    /** xrayStreamSetting.cpp:355-363. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        q.add("sni" to serverName)
        if (fingerprint.isNotEmpty()) q.add("fp" to fingerprint)
        if (password.isNotEmpty()) q.add("pbk" to password)
        if (shortId.isNotEmpty()) q.add("sid" to shortId)
        if (spiderX.isNotEmpty()) q.add("spx" to spiderX)
        return q
    }

    /** xrayStreamSetting.cpp:365-373. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["serverName"] = Hosts.toAceHost(serverName)
        if (fingerprint.isNotEmpty()) obj["fingerprint"] = fingerprint
        if (password.isNotEmpty()) obj["password"] = password
        if (shortId.isNotEmpty()) obj["shortId"] = shortId
        if (spiderX.isNotEmpty()) obj["spiderX"] = spiderX
        return obj
    }

    /** xrayStreamSetting.cpp:375-379. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (fingerprint.isEmpty() && ctx.utlsFingerprint.isNotEmpty()) obj["fingerprint"] = ctx.utlsFingerprint
        return obj
    }
}

/** xrayXHTTP (xrayStreamSetting.h:49-98, xrayStreamSetting.cpp:381-546). Unknown `extra`/`xmux` keys survive in rawExtra/rawXmux. */
class XrayXhttp {
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var mode: String = "auto"
    @JvmField var rawExtra: JsonObject = JsonObject()
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var xPaddingBytes: String = ""
    @JvmField var xPaddingObfsMode: Boolean = false
    @JvmField var xPaddingKey: String = ""
    @JvmField var xPaddingHeader: String = ""
    @JvmField var xPaddingPlacement: String = ""
    @JvmField var xPaddingMethod: String = ""
    @JvmField var uplinkHTTPMethod: String = ""
    @JvmField var sessionIDPlacement: String = ""
    @JvmField var sessionIDKey: String = ""
    @JvmField var sessionIDTable: String = ""
    @JvmField var sessionIDLength: String = ""
    @JvmField var seqPlacement: String = ""
    @JvmField var seqKey: String = ""
    @JvmField var uplinkDataPlacement: String = ""
    @JvmField var uplinkDataKey: String = ""
    @JvmField var uplinkChunkSize: String = ""
    @JvmField var noGRPCHeader: Boolean = false
    @JvmField var noSSEHeader: Boolean = false
    @JvmField var scMaxEachPostBytes: String = ""
    @JvmField var scMinPostsIntervalMs: String = ""
    @JvmField var scMaxBufferedPosts: Long = 0
    @JvmField var scStreamUpServerSecs: String = ""
    @JvmField var serverMaxHeaderBytes: Int = 0
    @JvmField var rawXmux: JsonObject = JsonObject()
    @JvmField var maxConcurrency: String = ""
    @JvmField var maxConnections: String = ""
    @JvmField var cMaxReuseTimes: String = ""
    @JvmField var hMaxRequestTimes: String = ""
    @JvmField var hMaxReusableSecs: String = ""
    @JvmField var hKeepAlivePeriod: Long = 0
    @JvmField var downloadSettings: String = ""

    companion object {
        /** xrayStreamSetting.cpp:96-128. */
        private val KNOWN_EXTRA_KEYS = setOf(
            "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement",
            "xPaddingMethod", "uplinkHTTPMethod", "sessionPlacement", "sessionKey", "sessionIDPlacement", "sessionIDKey",
            "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey",
            "uplinkChunkSize", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs",
            "scMaxBufferedPosts", "scStreamUpServerSecs", "serverMaxHeaderBytes", "xmux", "downloadSettings",
        )

        /** xrayStreamSetting.cpp:130-140. */
        private val KNOWN_XMUX_KEYS = setOf(
            "maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs", "hKeepAlivePeriod",
        )

        private fun exportString(obj: JsonObject, key: String, value: String) {
            if (value.isEmpty()) obj.remove(key) else obj[key] = value
        }

        private fun exportBool(obj: JsonObject, key: String, value: Boolean) {
            if (value) obj[key] = true else obj.remove(key)
        }

        private fun exportLong(obj: JsonObject, key: String, value: Long) {
            if (value != 0L) obj[key] = value else obj.remove(key)
        }

        private fun exportInt(obj: JsonObject, key: String, value: Int) {
            if (value != 0) obj[key] = value else obj.remove(key)
        }
    }

    /** xrayStreamSetting.cpp:182-194. */
    private fun parseXmuxObject(obj: JsonObject) {
        for (key in obj.keys()) {
            if (key !in KNOWN_XMUX_KEYS) rawXmux[key] = JsonValues.deepCopy(obj[key]!!)
        }
        if (obj.contains("maxConcurrency")) maxConcurrency = obj.variantString("maxConcurrency")
        if (obj.contains("maxConnections")) maxConnections = obj.variantString("maxConnections")
        if (obj.contains("cMaxReuseTimes")) cMaxReuseTimes = obj.variantString("cMaxReuseTimes")
        if (obj.contains("hMaxRequestTimes")) hMaxRequestTimes = obj.variantString("hMaxRequestTimes")
        if (obj.contains("hMaxReusableSecs")) hMaxReusableSecs = obj.variantString("hMaxReusableSecs")
        if (obj.contains("hKeepAlivePeriod")) hKeepAlivePeriod = obj.variantLong("hKeepAlivePeriod")
    }

    /** xrayStreamSetting.cpp:196-245. */
    private fun parseExtraObject(obj: JsonObject) {
        for (key in obj.keys()) {
            if (key !in KNOWN_EXTRA_KEYS) rawExtra[key] = JsonValues.deepCopy(obj[key]!!)
        }
        if (obj.contains("headers")) {
            if (obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
            else if (obj.isArray("headers")) headers = obj.array("headers").strings()
        }
        if (obj.contains("xPaddingBytes")) xPaddingBytes = obj.variantString("xPaddingBytes")
        if (obj.contains("xPaddingObfsMode")) xPaddingObfsMode = obj.bool("xPaddingObfsMode")
        if (obj.contains("xPaddingKey")) xPaddingKey = obj.string("xPaddingKey")
        if (obj.contains("xPaddingHeader")) xPaddingHeader = obj.string("xPaddingHeader")
        if (obj.contains("xPaddingPlacement")) xPaddingPlacement = obj.string("xPaddingPlacement")
        if (obj.contains("xPaddingMethod")) xPaddingMethod = obj.string("xPaddingMethod")
        if (obj.contains("uplinkHTTPMethod")) uplinkHTTPMethod = obj.string("uplinkHTTPMethod")
        // sessionPlacement / sessionKey: legacy aliases (pre-v26.6.22 Xray), overridden by the current names
        if (obj.contains("sessionPlacement")) sessionIDPlacement = obj.string("sessionPlacement")
        if (obj.contains("sessionIDPlacement")) sessionIDPlacement = obj.string("sessionIDPlacement")
        if (obj.contains("sessionKey")) sessionIDKey = obj.string("sessionKey")
        if (obj.contains("sessionIDKey")) sessionIDKey = obj.string("sessionIDKey")
        if (obj.contains("sessionIDTable")) sessionIDTable = obj.string("sessionIDTable")
        if (obj.contains("sessionIDLength")) sessionIDLength = obj.variantString("sessionIDLength")
        if (obj.contains("seqPlacement")) seqPlacement = obj.string("seqPlacement")
        if (obj.contains("seqKey")) seqKey = obj.string("seqKey")
        if (obj.contains("uplinkDataPlacement")) uplinkDataPlacement = obj.string("uplinkDataPlacement")
        if (obj.contains("uplinkDataKey")) uplinkDataKey = obj.string("uplinkDataKey")
        if (obj.contains("uplinkChunkSize")) uplinkChunkSize = obj.variantString("uplinkChunkSize")
        if (obj.contains("noGRPCHeader")) noGRPCHeader = obj.bool("noGRPCHeader")
        if (obj.contains("noSSEHeader")) noSSEHeader = obj.bool("noSSEHeader")
        if (obj.contains("scMaxEachPostBytes")) scMaxEachPostBytes = obj.variantString("scMaxEachPostBytes")
        if (obj.contains("scMinPostsIntervalMs")) scMinPostsIntervalMs = obj.variantString("scMinPostsIntervalMs")
        if (obj.contains("scMaxBufferedPosts")) scMaxBufferedPosts = obj.variantLong("scMaxBufferedPosts")
        if (obj.contains("scStreamUpServerSecs")) scStreamUpServerSecs = obj.variantString("scStreamUpServerSecs")
        if (obj.contains("serverMaxHeaderBytes")) serverMaxHeaderBytes = obj.variantInt("serverMaxHeaderBytes")
        if (obj.contains("downloadSettings")) {
            if (obj.isObject("downloadSettings")) downloadSettings = obj.obj("downloadSettings").toCompact()
            else if (obj.isString("downloadSettings")) downloadSettings = obj.string("downloadSettings")
        }
        val xmuxObj = obj.obj("xmux")
        if (xmuxObj.isNotEmpty()) parseXmuxObject(xmuxObj)
    }

    /** xrayStreamSetting.cpp:381-388: Python dict repr ('key': True) is rewritten only when the JSON parse fails. */
    fun parseExtraJson(text: String): Boolean {
        var obj = JsonInput.parseObject(text)
        if (obj.isEmpty()) obj = JsonInput.parseObject(text.replace('\'', '"').replace("True", "true").replace("False", "false"))
        if (obj.isEmpty()) return false
        parseExtraObject(obj)
        return true
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:390-422. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        if (q.has("mode")) mode = q.value("mode")
        if (q.has("extra") && !parseExtraJson(q.valueFully("extra"))) parseExtraJson(q.valueFormDecoded("extra"))
        if (q.has("headers")) {
            headers = QtStrings.split(q.valueFully("headers"), "|")
            if (headers.size % 2 != 0) headers.clear()
        }
        if (q.has("x_padding_bytes")) xPaddingBytes = q.value("x_padding_bytes")
        if (q.has("no_grpc_header")) noGRPCHeader = q.value("no_grpc_header").replace("1", "true") == "true"
        if (q.has("sc_max_each_post_bytes")) scMaxEachPostBytes = q.value("sc_max_each_post_bytes")
        if (q.has("sc_min_posts_interval_ms")) scMinPostsIntervalMs = q.value("sc_min_posts_interval_ms")
        if (q.has("max_concurrency")) maxConcurrency = q.value("max_concurrency")
        if (q.has("max_connections")) maxConnections = q.value("max_connections")
        if (q.has("max_reuse_times")) cMaxReuseTimes = q.value("max_reuse_times")
        if (q.has("max_request_times")) hMaxRequestTimes = q.value("max_request_times")
        if (q.has("max_reusable_secs")) hMaxReusableSecs = q.value("max_reusable_secs")
        if (q.has("keep_alive_period")) hKeepAlivePeriod = QtStrings.toLong(q.value("keep_alive_period"))
        return true
    }

    /** xrayStreamSetting.cpp:424-443: every top-level key other than host/path/mode/extra is an extra field. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("path")) path = obj.string("path")
        if (obj.contains("mode")) mode = obj.string("mode")
        val topLevelExtra = JsonObject()
        for (key in obj.keys()) {
            if (key == "host" || key == "path" || key == "mode" || key == "extra") continue
            topLevelExtra[key] = obj[key]
        }
        if (topLevelExtra.isNotEmpty()) parseExtraObject(topLevelExtra)
        val exObj = obj.obj("extra")
        if (exObj.isNotEmpty()) {
            parseExtraObject(exObj)
        } else if (obj.isString("extra")) {
            parseExtraJson(obj.string("extra"))
        }
        return true
    }

    /** xrayStreamSetting.cpp:445-472: `xhttp-opts` with its reuse and download settings. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = proxy.obj("xhttp-opts")
        host = opts.string("host")
        path = opts.string("path")
        mode = opts.string("mode").ifEmpty { "auto" }
        xPaddingObfsMode = opts.bool("x-padding-obfs-mode")
        xPaddingKey = opts.string("x-padding-key")
        xPaddingHeader = opts.string("x-padding-header")
        xPaddingPlacement = opts.string("x-padding-placement")
        xPaddingMethod = opts.string("x-padding-method")
        scMinPostsIntervalMs = opts.string("sc-min-posts-interval-ms")
        val reuse = opts.obj("reuse-settings")
        maxConcurrency = reuse.string("max-concurrency")
        maxConnections = reuse.string("max-connections")
        cMaxReuseTimes = reuse.string("c-max-reuse-times")
        hMaxRequestTimes = reuse.string("h-max-request-times")
        hMaxReusableSecs = reuse.string("h-max-reusable-secs")
        val keepAlivePeriod = reuse.string("h-keep-alive-period")
        if (keepAlivePeriod.isNotEmpty()) hKeepAlivePeriod = QtStrings.toLong(keepAlivePeriod)
        if (opts.has("download-settings")) downloadSettings = clashDownloadSettings(opts.obj("download-settings")).toCompact()
        return true
    }

    /** xrayStreamSetting.cpp:474-488. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty()) q.add("path" to path)
        if (mode.isNotEmpty()) q.add("mode" to mode)
        val jsonExport = exportToJson()
        if (jsonExport.isObject("extra")) {
            val exObj = jsonExport.obj("extra")
            if (exObj.isNotEmpty()) q.add("extra" to exObj.toCompact())
        }
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        return q
    }

    /** xrayStreamSetting.cpp:490-542. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (path.isNotEmpty()) obj["path"] = path
        if (mode.isNotEmpty()) obj["mode"] = mode

        val extraObj = rawExtra.copy()
        if (headers.isNotEmpty()) extraObj["headers"] = JsonValues.pairListToObject(headers) else extraObj.remove("headers")
        exportString(extraObj, "xPaddingBytes", xPaddingBytes)
        exportBool(extraObj, "xPaddingObfsMode", xPaddingObfsMode)
        exportString(extraObj, "xPaddingKey", xPaddingKey)
        exportString(extraObj, "xPaddingHeader", xPaddingHeader)
        exportString(extraObj, "xPaddingPlacement", xPaddingPlacement)
        exportString(extraObj, "xPaddingMethod", xPaddingMethod)
        exportString(extraObj, "uplinkHTTPMethod", uplinkHTTPMethod)
        exportString(extraObj, "sessionIDPlacement", sessionIDPlacement)
        exportString(extraObj, "sessionIDKey", sessionIDKey)
        exportString(extraObj, "sessionIDTable", sessionIDTable)
        exportString(extraObj, "sessionIDLength", sessionIDLength)
        exportString(extraObj, "seqPlacement", seqPlacement)
        exportString(extraObj, "seqKey", seqKey)
        exportString(extraObj, "uplinkDataPlacement", uplinkDataPlacement)
        exportString(extraObj, "uplinkDataKey", uplinkDataKey)
        exportString(extraObj, "uplinkChunkSize", uplinkChunkSize)
        exportBool(extraObj, "noGRPCHeader", noGRPCHeader)
        exportBool(extraObj, "noSSEHeader", noSSEHeader)
        exportString(extraObj, "scMaxEachPostBytes", scMaxEachPostBytes)
        exportString(extraObj, "scMinPostsIntervalMs", scMinPostsIntervalMs)
        exportLong(extraObj, "scMaxBufferedPosts", scMaxBufferedPosts)
        exportString(extraObj, "scStreamUpServerSecs", scStreamUpServerSecs)
        exportInt(extraObj, "serverMaxHeaderBytes", serverMaxHeaderBytes)
        if (mode == "stream-one") {
            extraObj.remove("downloadSettings")
        } else if (downloadSettings.isNotEmpty()) {
            val dsObj = JsonInput.parseObject(downloadSettings)
            if (dsObj.isNotEmpty()) extraObj["downloadSettings"] = dsObj
        } else {
            extraObj.remove("downloadSettings")
        }
        val xmuxObj = rawXmux.copy()
        exportString(xmuxObj, "maxConcurrency", maxConcurrency)
        exportString(xmuxObj, "maxConnections", maxConnections)
        exportString(xmuxObj, "cMaxReuseTimes", cMaxReuseTimes)
        exportString(xmuxObj, "hMaxRequestTimes", hMaxRequestTimes)
        exportString(xmuxObj, "hMaxReusableSecs", hMaxReusableSecs)
        exportLong(xmuxObj, "hKeepAlivePeriod", hKeepAlivePeriod)
        if (xmuxObj.isNotEmpty()) extraObj["xmux"] = xmuxObj else extraObj.remove("xmux")
        if (extraObj.isNotEmpty()) obj["extra"] = extraObj
        return obj
    }

    /** xrayStreamSetting.cpp:544-546. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayWS (xrayStreamSetting.h:100-114, xrayStreamSetting.cpp:548-631). */
class XrayWs {
    @JvmField var path: String = ""
    @JvmField var host: String = ""
    @JvmField var ed: Int = 0
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var heartbeatPeriod: Int = 0

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:548-565. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        if (q.has("ed")) ed = QtStrings.toInt(q.value("ed"))
        if (q.has("headers")) {
            headers = QtStrings.split(q.valueFully("headers"), "|")
            if (headers.size % 2 != 0) headers.clear()
        }
        if (q.has("heartbeat_period")) heartbeatPeriod = QtStrings.toInt(q.value("heartbeat_period"))
        return true
    }

    /** xrayStreamSetting.cpp:567-582. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("path")) {
            path = obj.string("path")
            if (path.contains("?ed=")) {
                val spl = path.split("?ed=")
                path = spl[0]
                ed = QtStrings.toInt(spl[1])
            }
        }
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        if (obj.contains("heartbeatPeriod")) heartbeatPeriod = obj.int("heartbeatPeriod")
        return true
    }

    /** xrayStreamSetting.cpp:584-604. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = ClashWsOptions(proxy)
        opts.path?.let { path = it }
        opts.ed?.let { ed = it }
        opts.host?.let { host = it }
        headers.addAll(opts.headers)
        return true
    }

    /** xrayStreamSetting.cpp:606-614: a bare "/" path is not written. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty() && path != "/") q.add("path" to path)
        if (ed > 0) q.add("ed" to ed.toString())
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        if (heartbeatPeriod > 0) q.add("heartbeat_period" to heartbeatPeriod.toString())
        return q
    }

    /** xrayStreamSetting.cpp:616-627. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (path.isNotEmpty()) {
            var fullPath = path
            if (ed > 0) fullPath += "?ed=$ed"
            obj["path"] = fullPath
        }
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        if (heartbeatPeriod > 0) obj["heartbeatPeriod"] = heartbeatPeriod
        return obj
    }

    /** xrayStreamSetting.cpp:629-631. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayHttpUpgrade (xrayStreamSetting.h:116-129, xrayStreamSetting.cpp:633-709). */
class XrayHttpUpgrade {
    @JvmField var path: String = ""
    @JvmField var ed: Int = 0
    @JvmField var host: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:633-646. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        if (q.has("ed")) ed = QtStrings.toInt(q.value("ed"))
        if (q.has("headers")) {
            headers = QtStrings.split(q.valueFully("headers"), "|")
            if (headers.size % 2 != 0) headers.clear()
        }
        return true
    }

    /** xrayStreamSetting.cpp:648-662. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("path")) {
            path = obj.string("path")
            if (path.contains("?ed=")) {
                val spl = path.split("?ed=")
                path = spl[0]
                ed = QtStrings.toInt(spl[1])
            }
        }
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        return true
    }

    /** xrayStreamSetting.cpp:664-684. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = ClashWsOptions(proxy)
        opts.path?.let { path = it }
        opts.ed?.let { ed = it }
        opts.host?.let { host = it }
        headers.addAll(opts.headers)
        return true
    }

    /** xrayStreamSetting.cpp:686-693. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty() && path != "/") q.add("path" to path)
        if (ed > 0) q.add("ed" to ed.toString())
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        return q
    }

    /** xrayStreamSetting.cpp:695-705. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (path.isNotEmpty()) {
            var fullPath = path
            if (ed > 0) fullPath += "?ed=$ed"
            obj["path"] = fullPath
        }
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        return obj
    }

    /** xrayStreamSetting.cpp:707-709. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayGRPC (xrayStreamSetting.h:131-143, xrayStreamSetting.cpp:711-752). */
class XrayGrpc {
    @JvmField var authority: String = ""
    @JvmField var serviceName: String = ""
    @JvmField var multiMode: Boolean = false

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:711-719. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("authority")) authority = q.valueFully("authority")
        if (q.has("serviceName")) serviceName = q.valueFully("serviceName")
        if (q.has("mode") && q.value("mode") == "multi") multiMode = true
        return true
    }

    /** xrayStreamSetting.cpp:721-727. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("authority")) authority = obj.string("authority")
        if (obj.contains("serviceName")) serviceName = obj.string("serviceName")
        if (obj.contains("multiMode")) multiMode = obj.bool("multiMode")
        return true
    }

    /** xrayStreamSetting.cpp:729-732. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val name = proxy.obj("grpc-opts").string("grpc-service-name")
        if (name.isNotEmpty()) serviceName = name
        return true
    }

    /** xrayStreamSetting.cpp:734-740. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (authority.isNotEmpty()) q.add("authority" to authority)
        if (serviceName.isNotEmpty()) q.add("serviceName" to serviceName)
        if (multiMode) q.add("mode" to "multi")
        return q
    }

    /** xrayStreamSetting.cpp:742-748. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (authority.isNotEmpty()) obj["authority"] = Hosts.toAceHost(authority)
        if (serviceName.isNotEmpty()) obj["serviceName"] = serviceName
        if (multiMode) obj["multiMode"] = multiMode
        return obj
    }

    /** xrayStreamSetting.cpp:750-752. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayStreamSetting (xrayStreamSetting.h:145-165, xrayStreamSetting.cpp:754-925). Field `TLS` is named `tls` here. */
class XrayStreamSetting {
    @JvmField var network: String = "raw"
    @JvmField var security: String = "none"
    @JvmField var rawSettings: JsonObject = JsonObject()
    @JvmField var finalmask: JsonObject = JsonObject()
    @JvmField var tls: XrayTls = XrayTls()
    @JvmField var reality: XrayReality = XrayReality()
    @JvmField var xhttp: XrayXhttp = XrayXhttp()
    @JvmField var ws: XrayWs = XrayWs()
    @JvmField var httpupgrade: XrayHttpUpgrade = XrayHttpUpgrade()
    @JvmField var grpc: XrayGrpc = XrayGrpc()

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting.cpp:754-795; false when the network is not one Xray knows. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("fm") || q.has("finalmask")) {
            val key = if (q.has("fm")) "fm" else "finalmask"
            (JsonInput.parseObjectOrNull(q.valueFully(key)) ?: JsonInput.parseObjectOrNull(q.valueFormDecoded(key)))?.let { finalmask = it }
        }
        if (q.has("type")) network = q.value("type").replace("tcp", "raw")
        if (network !in xrayNetworks) return false
        if (network == "raw" && q.value("headerType") == "http") {
            val request = JsonObject()
            val paths = QtStrings.splitList(q.valueFully("path"))
            if (paths.isNotEmpty()) request["path"] = JsonValues.stringArray(paths)
            val hosts = QtStrings.splitList(q.valueFully("host"))
            if (hosts.isNotEmpty()) {
                request["headers"] = jsonObjectOf("Host" to JsonValues.stringArray(hosts.map { Hosts.toAceHost(it) }))
            }
            val header = jsonObjectOf("type" to "http")
            if (request.isNotEmpty()) header["request"] = request
            rawSettings = jsonObjectOf("header" to header)
        }
        if (q.has("security")) security = q.value("security")
        if (security == "tls") tls.parseFromLink(url)
        else if (security == "reality") reality.parseFromLink(url)
        when (network) {
            "xhttp" -> xhttp.parseFromLink(url)
            "ws" -> ws.parseFromLink(url)
            "httpupgrade" -> httpupgrade.parseFromLink(url)
            "grpc" -> grpc.parseFromLink(url)
        }
        return true
    }

    /** xrayStreamSetting.cpp:797-818. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("finalmask") && obj.isObject("finalmask")) finalmask = obj.obj("finalmask").copy()
        if (obj.contains("method")) network = obj.string("method")
        else if (obj.contains("network")) network = obj.string("network")
        if (network == "tcp") network = "raw"
        if (network !in xrayNetworks) return false
        if (network == "raw") {
            if (obj.isObject("rawSettings")) rawSettings = obj.obj("rawSettings").copy()
            else if (obj.isObject("tcpSettings")) rawSettings = obj.obj("tcpSettings").copy()
        }
        if (obj.contains("security")) security = obj.string("security")
        if (security == "tls" && obj.isObject("tlsSettings")) tls.parseFromJson(obj.obj("tlsSettings"))
        else if (security == "reality" && obj.isObject("realitySettings")) reality.parseFromJson(obj.obj("realitySettings"))
        if (network == "xhttp" && obj.isObject("xhttpSettings")) xhttp.parseFromJson(obj.obj("xhttpSettings"))
        if (network == "ws" && obj.isObject("wsSettings")) ws.parseFromJson(obj.obj("wsSettings"))
        if (network == "httpupgrade" && obj.isObject("httpupgradeSettings")) httpupgrade.parseFromJson(obj.obj("httpupgradeSettings"))
        if (network == "grpc" && obj.isObject("grpcSettings")) grpc.parseFromJson(obj.obj("grpcSettings"))
        return true
    }

    /** xrayStreamSetting.cpp:820-843: only raw, ws, grpc and xhttp carry over; `tls` picks TLS or Reality by the public key. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val clashNetwork = proxy.string("network")
        if (clashNetwork.isNotEmpty()) network = clashNetwork
        if (network != "raw" && network != "ws" && network != "grpc" && network != "xhttp") return false
        if (proxy.bool("tls")) {
            if (proxy.obj("reality-opts").string("public-key").isEmpty()) {
                security = "tls"
                tls.parseFromClash(proxy)
            } else {
                security = "reality"
                reality.parseFromClash(proxy)
            }
        }
        if (network == "xhttp") xhttp.parseFromClash(proxy)
        if (network == "ws") {
            if (proxy.obj("ws-opts").bool("v2ray-http-upgrade")) {
                network = "httpupgrade"
                httpupgrade.parseFromClash(proxy)
            } else {
                ws.parseFromClash(proxy)
            }
        }
        if (network == "grpc") grpc.parseFromClash(proxy)
        return true
    }

    /** xrayStreamSetting.cpp:845-869. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (network.isNotEmpty()) q.add("type" to if (network == "raw") "tcp" else network)
        if (finalmask.isNotEmpty()) q.add("fm" to finalmask.toCompact())
        val header = rawSettings.obj("header")
        if (network == "raw" && header.string("type") == "http") {
            q.add("headerType" to "http")
            val request = header.obj("request")
            val paths = request.array("path")
            if (paths.isNotEmpty()) q.add("path" to paths.strings().joinToString(","))
            val hosts = request.obj("headers").array("Host")
            if (hosts.isNotEmpty()) q.add("host" to hosts.strings().joinToString(","))
        }
        if (security.isNotEmpty()) q.add("security" to security)
        if (security == "tls") q.addAll(tls.exportToLink())
        if (security == "reality") q.addAll(reality.exportToLink())
        when (network) {
            "xhttp" -> q.addAll(xhttp.exportToLink())
            "ws" -> q.addAll(ws.exportToLink())
            "httpupgrade" -> q.addAll(httpupgrade.exportToLink())
            "grpc" -> q.addAll(grpc.exportToLink())
        }
        return q
    }

    /** xrayStreamSetting.cpp:871-884: network and security always, the active network's settings even when empty. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["network"] = network
        obj["security"] = security
        if (finalmask.isNotEmpty()) obj["finalmask"] = finalmask.copy()
        if (network == "raw" && rawSettings.isNotEmpty()) obj["rawSettings"] = rawSettings.copy()
        if (security == "tls") obj["tlsSettings"] = tls.exportToJson()
        else if (security == "reality") obj["realitySettings"] = reality.exportToJson()
        when (network) {
            "xhttp" -> obj["xhttpSettings"] = xhttp.exportToJson()
            "ws" -> obj["wsSettings"] = ws.exportToJson()
            "httpupgrade" -> obj["httpupgradeSettings"] = httpupgrade.exportToJson()
            "grpc" -> obj["grpcSettings"] = grpc.exportToJson()
        }
        return obj
    }

    /** xrayStreamSetting.cpp:886-899: no rawSettings, so subscription-rotated hosts and paths do not change the identity. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        obj["network"] = network
        obj["security"] = security
        if (security == "reality") {
            if (reality.serverName.isNotEmpty()) obj["sni"] = Hosts.toAceHost(reality.serverName)
            if (reality.fingerprint.isNotEmpty()) obj["fingerprint"] = reality.fingerprint
        } else if (security == "tls") {
            if (tls.serverName.isNotEmpty()) obj["sni"] = Hosts.toAceHost(tls.serverName)
            if (tls.fingerprint.isNotEmpty()) obj["fingerprint"] = tls.fingerprint
            if (tls.echConfigList.isNotEmpty()) obj["ech"] = if (tls.echConfigList.contains("://")) tls.echConfigList else "static"
        }
        return obj
    }

    /** xrayStreamSetting.cpp:921-924: binding and DNS are wired by the core, not the config. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

// xrayStreamSetting.cpp:584-604 / 664-684: the `ws-opts` block as the ws and httpupgrade streams read it; a
// `?ed=` suffix on the path is the early-data size.
private class ClashWsOptions(proxy: ClashProxy) {
    var path: String? = null
    var ed: Int? = null
    var host: String? = null
    val headers = ArrayList<String>()

    init {
        val ws = proxy.obj("ws-opts")
        val wsPath = ws.string("path")
        if (wsPath.isNotEmpty()) {
            if (wsPath.contains("?ed=")) {
                val parts = wsPath.split("?ed=")
                path = parts[0]
                ed = QtStrings.toInt(parts[1])
            } else {
                path = wsPath
            }
        }
        val wsHeaders = ws.stringMap("headers")
        val servername = proxy.string("servername")
        host = if (servername.isNotEmpty()) servername else wsHeaders["Host"]
        for ((key, value) in wsHeaders) headers.add("$key=$value")
    }
}

// buildDownloadSettingsObject (xrayStreamSetting.cpp:52-94): the Clash `download-settings` block as the Xray
// downloadSettings object the profile stores as compact JSON.
private fun clashDownloadSettings(settings: ClashProxy): JsonObject {
    val obj = JsonObject()
    val server = settings.string("server")
    if (server.isNotEmpty()) obj["address"] = server
    obj["port"] = settings.int("port")
    obj["network"] = "xhttp"
    val realityOpts = settings.obj("reality-opts")
    val publicKey = realityOpts.string("public-key")
    val servername = settings.string("servername")
    val clientFingerprint = settings.string("client-fingerprint")
    if (publicKey.isNotEmpty()) {
        obj["security"] = "reality"
        val reality = JsonObject()
        reality["show"] = false
        if (servername.isNotEmpty()) reality["serverName"] = Hosts.toAceHost(servername)
        if (clientFingerprint.isNotEmpty()) reality["fingerprint"] = clientFingerprint
        reality["publicKey"] = publicKey
        val shortId = realityOpts.string("short-id")
        if (shortId.isNotEmpty()) reality["shortId"] = shortId
        obj["realitySettings"] = reality
    } else if (settings.bool("tls")) {
        obj["security"] = "tls"
        val tls = JsonObject()
        if (servername.isNotEmpty()) tls["serverName"] = Hosts.toAceHost(servername)
        val alpn = settings.strings("alpn")
        if (alpn.isNotEmpty()) tls["alpn"] = JsonArray().also { array -> alpn.forEach { array.add(it) } }
        if (clientFingerprint.isNotEmpty()) tls["fingerprint"] = clientFingerprint
        obj["tlsSettings"] = tls
    }
    val xhttp = JsonObject()
    val host = settings.string("host")
    if (host.isNotEmpty()) xhttp["host"] = Hosts.toAceHost(host)
    val path = settings.string("path")
    if (path.isNotEmpty()) xhttp["path"] = path
    xhttp["mode"] = settings.string("mode").ifEmpty { "auto" }
    val extra = JsonObject()
    if (settings.bool("x-padding-obfs-mode")) extra["xPaddingObfsMode"] = true
    for ((key, jsonKey) in listOf(
        "x-padding-key" to "xPaddingKey", "x-padding-header" to "xPaddingHeader",
        "x-padding-placement" to "xPaddingPlacement", "x-padding-method" to "xPaddingMethod",
    )) {
        val value = settings.string(key)
        if (value.isNotEmpty()) extra[jsonKey] = value
    }
    val reuse = settings.obj("reuse-settings")
    val xmux = JsonObject()
    for ((key, jsonKey) in listOf(
        "max-concurrency" to "maxConcurrency", "max-connections" to "maxConnections", "c-max-reuse-times" to "cMaxReuseTimes",
        "h-max-request-times" to "hMaxRequestTimes", "h-max-reusable-secs" to "hMaxReusableSecs",
    )) {
        val value = reuse.string(key)
        if (value.isNotEmpty()) xmux[jsonKey] = value
    }
    val keepAlivePeriod = reuse.string("h-keep-alive-period")
    if (keepAlivePeriod.isNotEmpty()) xmux["hKeepAlivePeriod"] = QtStrings.toLong(keepAlivePeriod)
    if (xmux.isNotEmpty()) extra["xmux"] = xmux
    if (extra.isNotEmpty()) xhttp["extra"] = extra
    obj["xhttpSettings"] = xhttp
    return obj
}
