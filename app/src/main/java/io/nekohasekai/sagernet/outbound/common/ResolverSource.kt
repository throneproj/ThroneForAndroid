package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.vtr

/**
 * Configs::ResolverSource (include/configs/common/ResolverSource.h): typed resolver lines plus the id of a desktop
 * resolver IP list. Android has no IP lists, so [ipListId] is only carried through the JSON (`resolver_list`) and
 * never built.
 */
data class ResolverSource(
    @JvmField var lines: List<String> = emptyList(),
    @JvmField var ipListId: Int = -1,
) {
    /**
     * Only blank and # lines. Unlike the desktop, [ipListId] does not count: Android never builds the list, so a
     * source holding only a list builds exactly like an empty one.
     */
    fun isEmpty(): Boolean = lines.all { raw -> raw.trim().let { it.isEmpty() || it.startsWith('#') } }

    fun hasIpList(): Boolean = ipListId >= 0
}

enum class ResolverFlavor {
    Dnstt,
    MasterDns,

    /** Lines become xdns mask addresses: no scheme means udp://, no udp or tcp port means 53. */
    Xdns,
}

class ResolverBuild(@JvmField val resolvers: JsonArray, @JvmField val error: String)

/** DnsttResolverObjectLine's results: [line] is "" when the object is unusable, [why] explains it (or a dropped part). */
class DnsttResolverLine(@JvmField val line: String, @JvmField val tcpFallback: Boolean, @JvmField val why: String)

/** The resolver grammar of src/configs/common/ResolverSource.cpp, without the IP list reader and its cache. */
object ResolverSources {

    private const val DNSTT_RANGE_HOSTS = 256L
    private const val MASTERDNS_RANGE_HOSTS = 65536L
    private const val MAX_EXPANDED = 4096

    // Xray's xdns client opens a socket and a reader goroutine per resolver when it starts.
    private const val MAX_XDNS = 256
    private const val MAX_IMPORT_LINES = 4096

    private val URL_SCHEMES = listOf("udp", "tcp", "tls", "https")

    private enum class Kind { Skip, System, Host, Range, Url }

    private class Line {
        var kind = Kind.Skip
        var scheme = ""
        var host = ""
        var port = 0
        var path = ""
        var bits = 0
        var v6 = false
        var error = ""
    }

    private fun failed(message: String) = Line().also { it.error = message }

    private fun shapeMessage(flavor: ResolverFlavor): String = vtr(
        when (flavor) {
            ResolverFlavor.Dnstt -> R.string.validation_resolver_shape_dnstt
            ResolverFlavor.MasterDns -> R.string.validation_resolver_shape_masterdns
            ResolverFlavor.Xdns -> R.string.validation_resolver_shape_xdns
        }
    )

    /** QHostAddress(ip).toString(), or [ip] itself when it is no address. */
    private fun canonicalIp(ip: String): String {
        if (ip.contains(':')) return Hosts.parseIpv6(ip)?.let { Hosts.formatIpv6(it) } ?: ip
        return Hosts.parseIpv4(ip)?.let { Hosts.formatIpv4(it) } ?: ip
    }

    private fun target(text: String, line: Line): Boolean {
        if (text.contains('/')) {
            if (Validation.cidr(text).isNotEmpty()) return false
            val slash = text.lastIndexOf('/')
            line.kind = Kind.Range
            line.host = canonicalIp(text.substring(0, slash))
            line.bits = text.substring(slash + 1).toInt()
        } else {
            if (Validation.ip(text).isNotEmpty()) return false
            line.kind = Kind.Host
            line.host = canonicalIp(text)
        }
        line.v6 = line.host.contains(':')
        return true
    }

    private fun parseUrl(text: String, flavor: ResolverFlavor): Line {
        val invalid = { failed(shapeMessage(flavor)) }
        val sep = text.indexOf("://")
        val scheme = text.substring(0, sep).lowercase()
        if (scheme !in URL_SCHEMES || (flavor == ResolverFlavor.Xdns && scheme == "tls")) return invalid()
        var authority = text.substring(sep + 3)
        var path = ""
        val slash = authority.indexOf('/')
        if (slash >= 0) {
            path = authority.substring(slash)
            authority = authority.substring(0, slash)
        }
        val bad = { c: Char -> c.isWhitespace() || c == '?' || c == '#' || c == '@' }
        if (authority.isEmpty() || authority.any(bad) || path.any(bad)) return invalid()
        if (path.isNotEmpty() && path != "/" && scheme != "https") return failed(vtr(R.string.validation_resolver_path))
        var host = authority
        var port: String? = null
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close < 0) return invalid()
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            if (after.isNotEmpty()) {
                if (!after.startsWith(':')) return invalid()
                port = after.substring(1)
            }
            if (Validation.ip(host, Family.V6).isNotEmpty()) return invalid()
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) return invalid()
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1)
            }
        }
        Validation.host(host).let { if (it.isNotEmpty()) return failed(it) }
        val line = Line()
        if (port != null) {
            if (port.isEmpty()) return invalid()
            Validation.port(port).let { if (it.isNotEmpty()) return failed(it) }
            line.port = Validation.toInt(port)?.toInt() ?: 0
        }
        line.kind = Kind.Url
        line.scheme = scheme
        line.host = if (Validation.ip(host).isEmpty()) canonicalIp(host) else Hosts.toAceHost(host)
        line.v6 = host.contains(':')
        line.path = if (path == "/") "" else path
        return line
    }

    // MasterDnsVPN config/client_resolvers.go parseResolverEntry: the bare form first, then host and port split at "]:" or the last ':'.
    private fun parse(raw: String, flavor: ResolverFlavor): Line {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith('#')) return Line()
        if (text.equals("system", ignoreCase = true)) return Line().also { it.kind = Kind.System }
        if (text.contains("://")) {
            return if (flavor == ResolverFlavor.Dnstt) parseUrl(text, flavor) else failed(shapeMessage(flavor))
        }
        Line().let { if (target(text, it)) return it }
        var hostPart = ""
        var portPart = ""
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close > 0 && text.getOrNull(close + 1) == ':') {
                hostPart = text.substring(1, close).trim()
                portPart = text.substring(close + 2).trim()
            }
        } else {
            val colon = text.lastIndexOf(':')
            if (colon > 0) {
                hostPart = text.substring(0, colon).trim()
                portPart = text.substring(colon + 1).trim()
            }
        }
        val line = Line()
        if (hostPart.isEmpty() || portPart.isEmpty() || !target(hostPart, line)) return failed(shapeMessage(flavor))
        Validation.port(portPart).let { if (it.isNotEmpty()) return failed(it) }
        line.port = Validation.toInt(portPart)?.toInt() ?: 0
        return line
    }

    // Xray's xdns mask reads an address without a scheme as udp://, and Go's URL parser splits a bare IPv6 at its last colon.
    private fun parseXdns(raw: String): Line {
        val text = raw.trim()
        if (text.isEmpty() || text.startsWith('#')) return Line()
        if (text.contains("://")) return parseUrl(text, ResolverFlavor.Xdns)
        if (text.equals("system", ignoreCase = true)) return failed(vtr(R.string.validation_resolver_xdns_system))
        val slash = text.indexOf('/')
        if (slash >= 0) {
            val head = text.substring(0, slash).replace("[", "")
            return failed(
                if (Validation.ip(head).isEmpty()) vtr(R.string.validation_resolver_xdns_range)
                else shapeMessage(ResolverFlavor.Xdns)
            )
        }
        if (Validation.ip(text, Family.V6).isEmpty()) return failed(vtr(R.string.validation_resolver_xdns_ipv6))
        val line = parseUrl("udp://$text", ResolverFlavor.Xdns)
        if (line.error.isEmpty() && Validation.ip(line.host).isNotEmpty()) return failed(shapeMessage(ResolverFlavor.Xdns))
        return line
    }

    // Usable addresses as MasterDnsVPN counts them: an IPv4 range below /31 loses its network and broadcast addresses, IPv6 below /127 the first.
    private fun rangeHosts(line: Line): Long {
        val hostBits = (if (line.v6) 128 else 32) - line.bits
        if (hostBits > 40) return Long.MAX_VALUE
        val total = 1L shl hostBits
        if (!line.v6) return if (line.bits < 31) total - 2 else total
        return if (line.bits < 127) total - 1 else total
    }

    private fun rangeLimit(line: Line, limit: Long): String {
        if (line.kind != Kind.Range || rangeHosts(line) <= limit) return ""
        val small = limit <= DNSTT_RANGE_HOSTS
        return vtr(R.string.validation_resolver_range_limit, limit.toString(), if (small) "24" else "16", if (small) "120" else "112")
    }

    // Only for ranges within DNSTT_RANGE_HOSTS, whose host bits all sit in the last byte.
    private fun rangeAddresses(line: Line): List<String> {
        val hostBits = (if (line.v6) 128 else 32) - line.bits
        if (hostBits !in 0..8) return emptyList()
        val count = 1 shl hostBits
        val dropFirst = if (line.v6) line.bits < 127 else line.bits < 31
        val dropLast = !line.v6 && line.bits < 31
        val out = ArrayList<String>()
        if (line.v6) {
            val bytes = Hosts.parseIpv6(line.host) ?: return emptyList()
            val network = bytes[15].toInt() and (0xFF shl hostBits) and 0xFF
            for (i in (if (dropFirst) 1 else 0) until count) {
                bytes[15] = (network or i).toByte()
                out.add(Hosts.formatIpv6(bytes))
            }
        } else {
            val base = Hosts.parseIpv4(line.host) ?: return emptyList()
            val network = base and (count - 1).toLong().inv() and 0xFFFFFFFFL
            for (i in (if (dropFirst) 1 else 0) until (if (dropLast) count - 1 else count)) out.add(Hosts.formatIpv4(network + i))
        }
        return out
    }

    private fun hostPort(host: String, v6: Boolean, port: Int): String {
        if (port <= 0) return host
        return (if (v6) "[$host]" else host) + ":" + port
    }

    private fun xdnsText(line: Line): String {
        val host = line.host.lowercase()
        if (line.scheme != "https") return line.scheme + "://" + hostPort(host, line.v6, if (line.port > 0) line.port else 53)
        val authority = if (line.v6) "[$host]" else host
        return "https://" + (if (line.port > 0) "$authority:${line.port}" else authority) + line.path
    }

    private fun udpObject(host: String, port: Int): JsonObject {
        val obj = jsonObjectOf("type" to "udp", "server" to host)
        if (port > 0) obj["server_port"] = port
        return obj
    }

    private fun urlObject(line: Line, utlsFingerprint: String): JsonObject {
        val obj = jsonObjectOf("type" to line.scheme, "server" to line.host)
        if (line.port > 0) obj["server_port"] = line.port
        if (line.scheme == "https" && line.path.isNotEmpty()) obj["path"] = line.path
        if (line.scheme == "tls" || line.scheme == "https") {
            val tls = jsonObjectOf("enabled" to true)
            if (Validation.ip(line.host).isNotEmpty()) tls["server_name"] = line.host
            // uTLS::Build's rule: the global fingerprint, never "unsafe".
            val fingerprint = utlsFingerprint.trim()
            if (fingerprint.isNotEmpty() && fingerprint != TLS_UNSAFE_FINGERPRINT) {
                tls["utls"] = jsonObjectOf("enabled" to true, "fingerprint" to fingerprint)
            }
            obj["tls"] = tls
        }
        return obj
    }

    private fun key(obj: JsonObject): String = "${obj.string("type")}|${obj.string("server")}|${obj.int("server_port")}|${obj.string("path")}"

    /** BuildResolvers: an empty source builds as system, except for Xdns, which builds as empty. */
    @JvmStatic
    @JvmOverloads
    fun build(source: ResolverSource, flavor: ResolverFlavor, owner: String, ctx: BuildContext = BuildContext.DEFAULT): ResolverBuild {
        val resolvers = JsonArray()
        val seen = HashSet<String>()
        val cap = when (flavor) {
            ResolverFlavor.MasterDns -> 0
            ResolverFlavor.Xdns -> MAX_XDNS
            ResolverFlavor.Dnstt -> MAX_EXPANDED
        }
        fun add(value: Any, key: String) {
            if (key in seen) return
            if (cap > 0 && resolvers.size >= cap) return
            seen.add(key)
            resolvers.add(value)
        }

        fun masterDnsText(line: Line): String {
            val target = if (line.kind == Kind.Range) "${line.host}/${line.bits}" else line.host
            return hostPort(target, line.v6, line.port)
        }

        fun invalid(raw: String, error: String) = ResolverBuild(resolvers, "$owner: the resolver \"${raw.trim()}\" is invalid: $error")

        for (raw in source.lines) {
            if (flavor == ResolverFlavor.Xdns) {
                val line = parseXdns(raw)
                if (line.error.isNotEmpty()) return invalid(raw, line.error)
                if (line.kind == Kind.Url) xdnsText(line).let { add(it, it) }
                continue
            }
            val dnstt = flavor == ResolverFlavor.Dnstt
            val line = parse(raw, flavor)
            val error = line.error.ifEmpty { rangeLimit(line, if (dnstt) DNSTT_RANGE_HOSTS else MASTERDNS_RANGE_HOSTS) }
            if (error.isNotEmpty()) return invalid(raw, error)
            when (line.kind) {
                Kind.Skip -> {}
                Kind.System -> if (dnstt) add(jsonObjectOf("type" to "system"), "system") else add("system", "system")
                Kind.Url -> urlObject(line, ctx.utlsFingerprint).let { add(it, key(it)) }
                Kind.Host, Kind.Range -> when {
                    !dnstt -> masterDnsText(line).let { add(it, it) }
                    line.kind == Kind.Host -> udpObject(line.host, line.port).let { add(it, key(it)) }
                    else -> for (address in rangeAddresses(line)) udpObject(address, line.port).let { add(it, key(it)) }
                }
            }
        }

        if (resolvers.isEmpty() && flavor != ResolverFlavor.Xdns) {
            if (flavor == ResolverFlavor.Dnstt) resolvers.add(jsonObjectOf("type" to "system")) else resolvers.add("system")
        }
        return ResolverBuild(resolvers, "")
    }

    /** DnsttResolverObjectLine: one sing-box dnstt resolver object as a resolver line. */
    @JvmStatic
    fun dnsttObjectLine(obj: JsonObject): DnsttResolverLine {
        val type = obj.string("type").trim().lowercase()
        if (type == "system") return DnsttResolverLine("system", false, "")
        val server = Hosts.toAceHost(obj.string("server").trim())
        if (type !in URL_SCHEMES || server.isEmpty()) return DnsttResolverLine("", false, vtr(R.string.validation_resolver_object))
        val tcpFallback = type == "udp" && obj.bool("tcp_fallback")
        val port = obj.int("server_port")
        val v6 = server.contains(':')
        var line: String
        if (type == "udp" && Validation.ip(server).isEmpty()) {
            line = hostPort(server, v6, port)
        } else {
            line = type + "://" + hostPort(if (v6) "[$server]" else server, false, port)
            val path = obj.string("path").trim()
            if (type == "https" && path.isNotEmpty()) line += if (path.startsWith('/')) path else "/$path"
        }
        val serverName = obj.obj("tls").string("server_name").trim()
        val why = if (serverName.isNotEmpty() && Hosts.toAceHost(serverName) != server) vtr(R.string.validation_resolver_server_name) else ""
        return DnsttResolverLine(line, tcpFallback, why)
    }

    /** ImportResolverLines: under an import scope the lines are trimmed, checked and capped; otherwise kept as they are. */
    @JvmStatic
    fun importLines(lines: List<String>, flavor: ResolverFlavor, field: String): List<String> {
        if (!ParseDiag.active()) return lines
        val kept = ArrayList<String>()
        var truncated = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (kept.size >= MAX_IMPORT_LINES) {
                truncated = true
                break
            }
            val message = when (flavor) {
                ResolverFlavor.Dnstt -> ResolverChecks.dnsttLine(line)
                ResolverFlavor.MasterDns -> ResolverChecks.masterDnsLine(line)
                ResolverFlavor.Xdns -> ResolverChecks.xdnsLine(line)
            }
            if (acceptOptional(field, message, line)) kept.add(line)
        }
        if (truncated) ParseDiag.warn(field, vtr(R.string.validation_resolver_import_limit, MAX_IMPORT_LINES.toString()))
        return kept
    }

    /** XdnsResolverAddress: the address [build] gives an Xdns line; "" when the line adds none. */
    @JvmStatic
    fun xdnsAddress(line: String): String {
        val parsed = parseXdns(line)
        if (parsed.error.isNotEmpty() || parsed.kind != Kind.Url) return ""
        return xdnsText(parsed)
    }

    internal fun dnsttLineError(line: String): String {
        val parsed = parse(line, ResolverFlavor.Dnstt)
        return parsed.error.ifEmpty { rangeLimit(parsed, DNSTT_RANGE_HOSTS) }
    }

    internal fun masterDnsLineError(line: String): String {
        val parsed = parse(line, ResolverFlavor.MasterDns)
        return parsed.error.ifEmpty { rangeLimit(parsed, MASTERDNS_RANGE_HOSTS) }
    }

    internal fun xdnsLineError(line: String): String = parseXdns(line).error
}

/** The resolver Checks of ResolverSource.h: "" for a usable line, otherwise one translated sentence. */
object ResolverChecks {
    @JvmStatic
    fun dnsttLine(line: String): String = ResolverSources.dnsttLineError(line)

    @JvmStatic
    fun masterDnsLine(line: String): String = ResolverSources.masterDnsLineError(line)

    @JvmStatic
    fun xdnsLine(line: String): String = ResolverSources.xdnsLineError(line)
}
