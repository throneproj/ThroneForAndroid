package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.common.QuicFields
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.legacyComboValue
import io.nekohasekai.sagernet.validation.vtr

/** tuic.h */
val ccAlgorithms = listOf("cubic", "new_reno", "bbr")

/** Older builds saved translated combo text ("BBR", "New Reno", "нативный"); the core matches exact identifiers. */
private fun canonicalTuicCongestion(value: String): String = legacyComboValue(value, ccAlgorithms)

private fun canonicalTuicRelayMode(value: String): String {
    val mode = legacyComboValue(value, listOf("native", "quic"))
    return if (mode == "native" || mode == "quic") mode else ""
}

/** tuicKeep: without a diagnostics scope (DB loads, clones) a parse keeps every value as it always did. */
private fun tuicKeep(field: String, value: String, rule: Rule): Boolean =
    !ParseDiag.active() || acceptOptional(field, rule(value), value)

private fun tuicImportCongestion(raw: String): String {
    val value = canonicalTuicCongestion(raw)
    return if (value.isEmpty() || tuicKeep("congestion_control", value, Validation.Rules.oneOf(ccAlgorithms))) value else ""
}

/** The core treats an unknown mode as native, so it is dropped with a note. */
private fun tuicImportRelayMode(raw: String): String {
    val mode = canonicalTuicRelayMode(raw)
    if (mode.isEmpty() && raw.isNotBlank() && ParseDiag.active()) {
        acceptOptional("udp_relay_mode", Validation.oneOf(raw.trim(), listOf("native", "quic")), raw)
    }
    return mode
}

private fun tuicImportHeartbeat(raw: String): String {
    val value = raw.trim()
    return if (tuicKeep("heartbeat", value, Validation.Rules.duration())) value else ""
}

/** tuic (include/configs/outbounds/tuic.h, src/configs/outbounds/tuic.cpp). */
class Tuic : Outbound("tuic") {
    @JvmField var uuid: String = ""
    @JvmField var password: String = ""
    @JvmField var congestion_control: String = ""
    @JvmField var udp_relay_mode: String = ""
    @JvmField var udp_over_stream: Boolean = false
    @JvmField var zero_rtt_handshake: Boolean = false
    @JvmField var heartbeat: String = ""
    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** tuic.h:24-27: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    /** tuic::Validate (tuic.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "uuid", Validation.uuid(uuid))
        issues.addIssue(Severity.Error, "heartbeat", Validation.duration(heartbeat))
        issues.addIssue(
            Severity.Error, "congestion_control", Validation.oneOf(canonicalTuicCongestion(congestion_control), ccAlgorithms),
            congestion_control,
        )
    }

    /** tuic::ParseFromLink (tuic.cpp). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        val (linkUuid, linkPassword) = url.userInfoPair()
        uuid = linkUuid
        password = linkPassword
        if (q.has("congestion_control")) congestion_control = tuicImportCongestion(q.value("congestion_control"))
        if (q.has("udp_relay_mode")) udp_relay_mode = tuicImportRelayMode(q.value("udp_relay_mode"))
        if (q.has("udp_over_stream")) udp_over_stream = q.value("udp_over_stream") == "true"
        if (q.has("zero_rtt_handshake")) zero_rtt_handshake = q.value("zero_rtt_handshake") == "true"
        if (q.has("heartbeat")) heartbeat = tuicImportHeartbeat(q.value("heartbeat"))
        tls.parseFromLink(url)
        tls.enabled = true
        quic.parseFromLink(url)
        if (serverPort == 0) serverPort = 443
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** tuic::ParseFromClash (tuic.cpp): `ip` overrides the server, `heartbeat-interval` is milliseconds; TUIC v4 is refused. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "tuic") return false
        baseParseFromClash(proxy)
        uuid = proxy.string("uuid")
        if (uuid.isBlank()) {
            ParseDiag.error("uuid", vtr(R.string.validation_tuic_v4))
            return false
        }
        password = proxy.string("password")
        val congestion = proxy.string("congestion-controller")
        if (congestion.isNotEmpty()) congestion_control = tuicImportCongestion(congestion)
        val relayMode = proxy.string("udp-relay-mode")
        if (relayMode.isNotEmpty()) udp_relay_mode = tuicImportRelayMode(relayMode)
        zero_rtt_handshake = proxy.bool("reduce-rtt")
        val heartbeatInterval = proxy.int("heartbeat-interval")
        if (heartbeatInterval > 0) heartbeat = "${heartbeatInterval}ms"
        val ip = proxy.string("ip")
        if (ip.isNotEmpty()) server = ip
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /** tuic::ParseFromJson (tuic.cpp): the QUIC keys are flat. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "tuic") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("congestion_control")) congestion_control = tuicImportCongestion(obj.string("congestion_control"))
        if (obj.contains("udp_relay_mode")) udp_relay_mode = tuicImportRelayMode(obj.string("udp_relay_mode"))
        if (obj.contains("udp_over_stream")) udp_over_stream = obj.bool("udp_over_stream")
        if (obj.contains("zero_rtt_handshake")) zero_rtt_handshake = obj.bool("zero_rtt_handshake")
        if (obj.contains("heartbeat")) heartbeat = tuicImportHeartbeat(obj.string("heartbeat"))
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        quic.parseFromJson(obj)
        return true
    }

    /** tuic.cpp:67-90. */
    override fun exportToLink(): String {
        val url = LinkBuilder("tuic")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (congestion_control.isNotEmpty()) url.addQueryItem("congestion_control", congestion_control)
        if (udp_relay_mode.isNotEmpty() && !udp_over_stream) url.addQueryItem("udp_relay_mode", udp_relay_mode)
        if (udp_over_stream) url.addQueryItem("udp_over_stream", "true")
        if (zero_rtt_handshake) url.addQueryItem("zero_rtt_handshake", "true")
        if (heartbeat.isNotEmpty()) url.addQueryItem("heartbeat", heartbeat)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(quic.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** tuic.cpp:92-107: `udp_relay_mode` only without `udp_over_stream`, `tls` only when enabled. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "tuic"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (congestion_control.isNotEmpty()) obj["congestion_control"] = congestion_control
        if (udp_relay_mode.isNotEmpty() && !udp_over_stream) obj["udp_relay_mode"] = udp_relay_mode
        if (udp_over_stream) obj["udp_over_stream"] = udp_over_stream
        if (zero_rtt_handshake) obj["zero_rtt_handshake"] = zero_rtt_handshake
        if (heartbeat.isNotEmpty()) obj["heartbeat"] = heartbeat
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** tuic::Build (tuic.cpp): congestion control and relay mode in the core's identifiers. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "tuic"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        val congestion = canonicalTuicCongestion(congestion_control)
        if (congestion.isNotEmpty()) obj["congestion_control"] = congestion
        val relayMode = canonicalTuicRelayMode(udp_relay_mode)
        if (!udp_over_stream && relayMode.isNotEmpty()) obj["udp_relay_mode"] = relayMode
        if (udp_over_stream) obj["udp_over_stream"] = udp_over_stream
        if (zero_rtt_handshake) obj["zero_rtt_handshake"] = zero_rtt_handshake
        if (heartbeat.isNotEmpty()) obj["heartbeat"] = heartbeat
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** tuic.cpp:126-129. */
    override fun displayType(): String = "TUIC"

    /** tuic.cpp:131-134. */
    override fun security(): SecurityInfo = securityFromTls("QUIC")
}
