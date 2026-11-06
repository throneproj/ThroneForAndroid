package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** The DialerOptions keys Throne has no field for that change how the server is reached; it sets the local knobs itself. */
private val UNMODELLED_DIAL_KEYS = listOf("detour", "tls_fragment")

/** dialFieldsRule (DialFields.cpp). */
private fun dialFieldsRule(key: String, value: String): String = when {
    key.endsWith("connect_timeout") -> Validation.duration(value)
    key.endsWith("inet4_bind_address") -> Validation.ip(value, Family.V4, Empty.Allowed)
    else -> DialChecks.bindAddress6(value)
}

/** dialFieldsReportUnmodelled (DialFields.cpp): the core matches keys case-insensitively. */
private fun dialFieldsReportUnmodelled(obj: JsonObject) {
    val dropped = obj.sortedKeys().filter { key -> UNMODELLED_DIAL_KEYS.any { it.equals(key, ignoreCase = true) } }.map { it.lowercase() }
    if (dropped.isEmpty()) return
    var message = vtr(R.string.validation_dial_unmodelled, dropped.joinToString(", "))
    if ("detour" in dropped) message += " " + vtr(R.string.validation_dial_detour)
    ParseDiag.warn("", message)
}

/** The Checks of DialFields.h. */
object DialChecks {
    /** An IPv6 literal; a zone (fe80::1%eth0) is allowed, as netip.ParseAddr takes it. */
    @JvmStatic
    fun bindAddress6(text: String): String {
        val t = text.trim()
        val zone = t.indexOf('%')
        if (zone > 0 && zone < t.length - 1 && !t.substring(zone + 1).contains(':')) return Validation.ip(t.substring(0, zone), Family.V6)
        return Validation.ip(t, Family.V6, Empty.Allowed)
    }
}

/** DialFields (include/configs/common/DialFields.h, src/configs/common/DialFields.cpp); merged flat into the outbound. */
class DialFields {
    @JvmField var reuse_addr: Boolean = false
    @JvmField var connect_timeout: String = ""
    @JvmField var tcp_fast_open: Boolean = false
    @JvmField var tcp_multi_path: Boolean = false
    @JvmField var udp_fragment: Boolean = false
    @JvmField var bind_interface: String = ""
    @JvmField var inet4_bind_address: String = ""
    @JvmField var inet6_bind_address: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** DialFields::ParseFromLink (DialFields.cpp). */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        fun checked(key: String, current: String): String {
            if (!q.has(key)) return current
            val value = q.value(key).trim()
            return if (acceptOptional(key, dialFieldsRule(key, value), value)) value else current
        }
        if (q.has("reuse_addr")) reuse_addr = q.value("reuse_addr") == "true"
        if (q.has("tcp_fast_open")) tcp_fast_open = q.value("tcp_fast_open") == "true"
        if (q.has("tcp_multi_path")) tcp_multi_path = q.value("tcp_multi_path") == "true"
        if (q.has("udp_fragment")) udp_fragment = q.value("udp_fragment") == "true"
        if (q.has("bind_interface")) bind_interface = q.value("bind_interface")
        connect_timeout = checked("connect_timeout", connect_timeout)
        inet4_bind_address = checked("inet4_bind_address", inet4_bind_address)
        inet6_bind_address = checked("inet6_bind_address", inet6_bind_address)
        return true
    }

    /**
     * DialFields::ParseFromJson (DialFields.cpp); also the DB loader, so only an import ([ParseDiag.active]) drops
     * values and reports the dial options Throne does not model.
     */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        val importing = ParseDiag.active()
        fun checked(key: String, current: String): String {
            if (!obj.contains(key)) return current
            val value = obj.string(key).trim()
            return if (!importing || acceptOptional(key, dialFieldsRule(key, value), value)) value else current
        }
        if (obj.contains("reuse_addr")) reuse_addr = obj.bool("reuse_addr")
        if (obj.contains("tcp_fast_open")) tcp_fast_open = obj.bool("tcp_fast_open")
        if (obj.contains("tcp_multi_path")) tcp_multi_path = obj.bool("tcp_multi_path")
        if (obj.contains("udp_fragment")) udp_fragment = obj.bool("udp_fragment")
        if (obj.contains("bind_interface")) bind_interface = obj.string("bind_interface")
        connect_timeout = checked("connect_timeout", connect_timeout)
        inet4_bind_address = checked("inet4_bind_address", inet4_bind_address)
        inet6_bind_address = checked("inet6_bind_address", inet6_bind_address)
        if (importing) dialFieldsReportUnmodelled(obj)
        return true
    }

    /** DialFields::ExportToLink (DialFields.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (reuse_addr) q.add("reuse_addr" to "true")
        if (connect_timeout.isNotEmpty()) q.add("connect_timeout" to connect_timeout)
        if (tcp_fast_open) q.add("tcp_fast_open" to "true")
        if (tcp_multi_path) q.add("tcp_multi_path" to "true")
        if (udp_fragment) q.add("udp_fragment" to "true")
        if (bind_interface.isNotEmpty()) q.add("bind_interface" to bind_interface)
        if (inet4_bind_address.isNotEmpty()) q.add("inet4_bind_address" to inet4_bind_address)
        if (inet6_bind_address.isNotEmpty()) q.add("inet6_bind_address" to inet6_bind_address)
        return q
    }

    /** DialFields::ExportToJson (DialFields.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (reuse_addr) obj["reuse_addr"] = true
        if (connect_timeout.isNotEmpty()) obj["connect_timeout"] = connect_timeout
        if (tcp_fast_open) obj["tcp_fast_open"] = true
        if (tcp_multi_path) obj["tcp_multi_path"] = true
        if (udp_fragment) obj["udp_fragment"] = true
        if (bind_interface.isNotEmpty()) obj["bind_interface"] = bind_interface
        if (inet4_bind_address.isNotEmpty()) obj["inet4_bind_address"] = inet4_bind_address
        if (inet6_bind_address.isNotEmpty()) obj["inet6_bind_address"] = inet6_bind_address
        return obj
    }

    /** DialFields::Build (DialFields.cpp). */
    fun build(ctx: BuildContext): JsonObject = exportToJson()

    /** DialFields::Validate (DialFields.cpp). */
    fun validate(issues: MutableList<Issue>) {
        issues.addIssue(Severity.Error, "connect_timeout", dialFieldsRule("connect_timeout", connect_timeout), connect_timeout)
        issues.addIssue(Severity.Error, "inet4_bind_address", dialFieldsRule("inet4_bind_address", inet4_bind_address), inet4_bind_address)
        issues.addIssue(Severity.Error, "inet6_bind_address", dialFieldsRule("inet6_bind_address", inet6_bind_address), inet6_bind_address)
    }
}
