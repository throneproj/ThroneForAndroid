package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.vPacketEncoding
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.common.Transport
import io.nekohasekai.sagernet.outbound.common.tlsForTransport
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** vlessFlows (vless.h). */
val vlessFlows = listOf("xtls-rprx-vision")

/** Share links write none for no flow; the core knows only the empty value and matches names exactly. */
private fun vlessImportFlow(raw: String): String {
    val value = raw.trim().lowercase()
    return if (value == "none") "" else value
}

/** Throne's own links write none for the empty encoding. */
private fun vlessImportPacketEncoding(raw: String): String {
    val value = raw.trim()
    if (value == "none") return ""
    if (value in vPacketEncoding) return value
    ParseDiag.warn("packet_encoding", vtr(R.string.validation_vmess_packet_encoding_unknown), value)
    return ""
}

/** No flow is the core's default; an unknown one stops it and a misplaced Vision fails every connection. */
private fun dropUnusableVlessFlow(v: Vless) {
    if (v.flow == "xtls-rprx-vision-udp443") return
    if (!acceptOptional("flow", VlessChecks.vlessFlow(v.flow, v.tls.enabled, v.transport.type), v.flow)) v.flow = ""
}

/** The Checks of vless.h. */
object VlessChecks {
    /** sing-box takes only xtls-rprx-vision, and Vision works only on TLS (or REALITY) directly over TCP. */
    @JvmStatic
    fun vlessFlow(flow: String, tlsEnabled: Boolean, transportType: String): String {
        if (flow.isEmpty()) return ""
        if (flow == "xtls-rprx-vision-udp443") return vtr(R.string.validation_vless_flow_udp443)
        if (flow != "xtls-rprx-vision") return Validation.oneOf(flow, vlessFlows)
        if (!tlsEnabled || !(transportType.isEmpty() || transportType == "tcp")) return vtr(R.string.validation_vless_flow_vision)
        return ""
    }
}

/** vless, the sing-box one (include/configs/outbounds/vless.h, src/configs/outbounds/vless.cpp). */
class Vless : Outbound("vless") {
    @JvmField var uuid: String = ""
    @JvmField var flow: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var packet_encoding: String = "xudp"
    @JvmField var multiplex: Multiplex = Multiplex()
    @JvmField var transport: Transport = Transport()

    override fun hasTls(): Boolean = true
    override fun hasMux(): Boolean = true
    override fun hasTransport(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getMux(): Multiplex = multiplex
    override fun getTransport(): Transport = transport

    /** vless::ParseFromLink (vless.cpp). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        uuid = url.userName
        if (serverPort == 0) serverPort = 443
        flow = vlessImportFlow(q.value("flow"))
        transport.parseFromLink(url)
        tls.parseFromLink(url)
        val packetEncoding = q.value("packetEncoding")
        packet_encoding = if (packetEncoding.isEmpty()) "xudp" else vlessImportPacketEncoding(packetEncoding)
        multiplex.parseFromLink(url)
        dropUnusableVlessFlow(this)
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** vless::ParseFromClash (vless.cpp): `packet-encoding` is copied even when absent, like the desktop. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "vless") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        val clashFlow = proxy.string("flow")
        if (clashFlow.isNotEmpty()) flow = vlessImportFlow(clashFlow)
        packet_encoding = vlessImportPacketEncoding(proxy.string("packet-encoding"))
        tls.parseFromClash(node, clashTlsOn(proxy))
        transport.parseFromClash(proxy)
        multiplex.parseFromClash(proxy)
        dropUnusableVlessFlow(this)
        return true
    }

    /** vless::ParseFromJson (vless.cpp): also the DB loader, so only an import normalizes flow and packet encoding. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "vless") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("flow")) flow = obj.string("flow")
        if (obj.contains("packet_encoding")) packet_encoding = obj.string("packet_encoding")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("transport")) transport.parseFromJson(obj.obj("transport"))
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        if (ParseDiag.active()) {
            flow = vlessImportFlow(flow)
            packet_encoding = vlessImportPacketEncoding(packet_encoding)
            dropUnusableVlessFlow(this)
        }
        return true
    }

    /** vless::ExportToLink (vless.cpp): no dial-field query items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("vless")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        url.addQueryItem("encryption", "none")
        if (flow.isNotEmpty()) url.addQueryItem("flow", flow)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(transport.exportToLink(tls.enabled))
        url.addQueryItems(multiplex.exportToLink())
        url.addQueryItem("packetEncoding", packet_encoding.ifEmpty { "none" })
        return url.build()
    }

    /** vless::ExportToJson (vless.cpp): `packet_encoding` is always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "vless"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (flow.isNotEmpty()) obj["flow"] = flow
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        val transportObj = transport.exportToJson()
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** vless::Build (vless.cpp): the quic transport drops uTLS ([tlsForTransport]). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "vless"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (flow.isNotEmpty()) obj["flow"] = flow
        obj["packet_encoding"] = packet_encoding
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsForTransport(tlsObj, transport.type)
        val transportObj = transport.build(ctx)
        if (transportObj.isNotEmpty()) obj["transport"] = transportObj
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** vless::DisplayType (vless.cpp). */
    override fun displayType(): String = "VLESS"

    /** vless::Validate (vless.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "uuid", Validation.required(uuid))
        issues.addIssue(Severity.Error, "flow", VlessChecks.vlessFlow(flow, tls.enabled, transport.type), flow)
        issues.addIssue(Severity.Error, "packet_encoding", Validation.oneOf(packet_encoding, vPacketEncoding), packet_encoding)
    }
}
