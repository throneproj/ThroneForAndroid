package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.vPacketEncoding
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.common.Transport
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser

/** vmess (include/configs/outbounds/vmess.h, src/configs/outbounds/vmess.cpp). */
class Vmess : Outbound("vmess") {
    @JvmField var uuid: String = ""
    @JvmField var security: String = "auto"
    @JvmField var alter_id: Int = 0
    @JvmField var global_padding: Boolean = false
    @JvmField var authenticated_length: Boolean = false
    @JvmField var tls: Tls = Tls()
    @JvmField var packet_encoding: String = "xudp"
    @JvmField var transport: Transport = Transport()
    @JvmField var multiplex: Multiplex = Multiplex()

    override fun hasTls(): Boolean = true
    override fun hasMux(): Boolean = true
    override fun hasTransport(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getMux(): Multiplex = multiplex
    override fun getTransport(): Transport = transport

    /** vmess.cpp:10-97: the V2RayN base64 JSON form first, then the URL form. */
    override fun parseFromLink(link: String): Boolean {
        val linkN = Base64Strict.decodeToString(QtStrings.substrAfter(link, "vmess://"))
        if (linkN.isNotEmpty()) {
            val objN = JsonInput.parseObject(linkN)
            if (objN.isNotEmpty()) return parseV2RayN(objN)
        }

        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        uuid = url.userName
        if (serverPort == 0) serverPort = 443
        security = q.valueOr("encryption", "auto")
        transport.parseFromLink(url)
        tls.parseFromLink(url)
        if (tls.server_name.isNotEmpty()) tls.enabled = true
        multiplex.parseFromLink(url)
        if (q.has("alterId")) alter_id = QtStrings.toInt(q.value("alterId"))
        if (q.has("globalPadding")) global_padding = q.value("globalPadding") == "true"
        if (q.has("authenticatedLength")) authenticated_length = q.value("authenticatedLength") == "true"
        if (q.has("packetEncoding")) packet_encoding = q.value("packetEncoding")
        if (packet_encoding !in vPacketEncoding) packet_encoding = ""
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** vmess.cpp:17-66: the V2RayN object; no base parse, so no dial fields and no ACE conversion of `add`. */
    private fun parseV2RayN(objN: JsonObject): Boolean {
        uuid = objN.string("id")
        server = objN.string("add")
        serverPort = objN.variantInt("port")
        name = objN.string("ps")
        alter_id = objN.variantInt("aid")

        var net = objN.string("net")
        if (net == "raw") net = "tcp"
        val rawHttp = (net.isEmpty() || net == "tcp") && objN.string("type") == "http"
        if (net == "h2" || rawHttp) net = "http"
        transport.type = net
        transport.host = objN.string("host")
        if (net == "grpc") transport.service_name = objN.string("path") else transport.path = objN.string("path")
        if (rawHttp) {
            transport.method = "GET"
            transport.path = QtStrings.sectionFirstSkipEmpty(transport.path, ',').trim()
        }
        transport.rawHttpHeader = rawHttp

        val scy = objN.string("scy")
        if (scy.isNotEmpty()) security = scy

        if (objN.string("tls") == "tls") {
            tls.enabled = true
            tls.server_name = objN.string("sni")
            tls.alpn = QtStrings.splitSkipEmpty(objN.string("alpn"), ",")
            val insecure = if (objN.contains("insecure")) objN.variantString("insecure") else objN.variantString("allowInsecure")
            tls.insecure = insecure == "1" || insecure == "true"
            val fingerprint = objN.string("fp")
            if (fingerprint.isNotEmpty()) {
                tls.utls.enabled = true
                tls.utls.fingerPrint = fingerprint
            }
        }

        // throneExtra holds what the V2RayN schema cannot; the dummy authority only satisfies the URL parser
        val extra = objN.string("throneExtra")
        if (extra.isNotEmpty()) {
            val extraLink = LinkParser.parse("vmess://x@127.0.0.1:1?$extra")
            transport.parseFromLink(extraLink)
            multiplex.parseFromLink(extraLink)
            val extraQuery = LinkParser.parseQuery(extra)
            if (extraQuery.has("globalPadding")) global_padding = extraQuery.value("globalPadding") == "true"
            if (extraQuery.has("authenticatedLength")) authenticated_length = extraQuery.value("authenticatedLength") == "true"
            if (extraQuery.has("packetEncoding")) packet_encoding = extraQuery.value("packetEncoding")
            if (packet_encoding !in vPacketEncoding) packet_encoding = ""
        }

        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** vmess.cpp:117-130: `packet-encoding` is copied even when absent, like the desktop. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "vmess") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        val cipher = proxy.string("cipher")
        if (cipher.isNotEmpty()) security = cipher
        alter_id = proxy.int("alterId")
        packet_encoding = proxy.string("packet-encoding")
        tls.parseFromClash(proxy)
        transport.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        return true
    }

    /** vmess.cpp:99-115. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "vmess") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("security")) security = obj.string("security")
        if (obj.contains("alter_id")) alter_id = obj.int("alter_id")
        if (obj.contains("alter-id")) alter_id = obj.int("alter-id")
        if (obj.contains("global_padding")) global_padding = obj.bool("global_padding")
        if (obj.contains("global-padding")) global_padding = obj.bool("global-padding")
        if (obj.contains("authenticated_length")) authenticated_length = obj.bool("authenticated_length")
        if (obj.contains("packet_encoding")) packet_encoding = obj.string("packet_encoding")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("transport")) transport.parseFromJson(obj.obj("transport"))
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** vmess.cpp:132-171: always the V2RayN form; what that schema has no room for travels in `throneExtra`. */
    override fun exportToLink(): String {
        val rawHttp = transport.type == "http" && !tls.enabled
        val network = when {
            transport.type.isEmpty() || transport.type == "tcp" || rawHttp -> "tcp"
            transport.type == "http" -> "h2"
            else -> transport.type
        }
        val path = if (network == "grpc") transport.service_name else transport.path

        val extra = ArrayList<Pair<String, String>>()
        extra.addAll(transport.exportToLink(tls.enabled))
        extra.addAll(multiplex.exportToLink())
        if (global_padding) extra.add("globalPadding" to "true")
        if (authenticated_length) extra.add("authenticatedLength" to "true")
        if (packet_encoding != "xudp") extra.add("packetEncoding" to packet_encoding.ifEmpty { "none" })

        val obj = JsonObject()
        obj["v"] = "2"
        obj["ps"] = name
        obj["add"] = server
        obj["port"] = serverPort.toString()
        obj["id"] = uuid
        obj["aid"] = alter_id.toString()
        obj["scy"] = security.ifEmpty { "auto" }
        obj["net"] = network
        obj["type"] = if (rawHttp) "http" else "none"
        obj["host"] = transport.host
        obj["path"] = path
        obj["tls"] = if (tls.enabled) "tls" else ""
        obj["sni"] = tls.server_name
        obj["alpn"] = tls.alpn.joinToString(",")
        obj["fp"] = tls.utls.fingerPrint
        if (tls.insecure) obj["allowInsecure"] = "1"
        if (extra.isNotEmpty()) obj["throneExtra"] = LinkBuilder.formatQuery(extra)
        return "vmess://" + Base64Strict.encode(obj.toCompact())
    }

    /** vmess.cpp:173-188: `packet_encoding` is always written, `security` only when it is not the default. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "vmess"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (security != "auto") obj["security"] = security
        if (alter_id > 0) obj["alter_id"] = alter_id
        if (global_padding) obj["global_padding"] = global_padding
        if (authenticated_length) obj["authenticated_length"] = authenticated_length
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.exportToJson()
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** vmess.cpp:190-205. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "vmess"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (security != "auto") obj["security"] = security
        if (alter_id > 0) obj["alter_id"] = alter_id
        if (global_padding) obj["global_padding"] = global_padding
        if (authenticated_length) obj["authenticated_length"] = authenticated_length
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.build(ctx)
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** vmess.cpp:207-210. */
    override fun displayType(): String = "VMess"

    /** vmess.cpp:212-221: encrypted, but VMess without TLS still counts as insecure; a no-op cipher stays Raw. */
    override fun security(): SecurityInfo {
        val info = super.security()
        if (info.level == SecurityLevel.None && security != "none" && security != "zero") {
            return info.copy(label = "Insecure", level = SecurityLevel.Weak)
        }
        return info
    }
}
