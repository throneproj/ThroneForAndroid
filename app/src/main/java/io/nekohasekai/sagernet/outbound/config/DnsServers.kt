package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts

/** directDomainResolver (generate.cpp:788-790). */
internal fun directDomainResolver(ctx: BuildContext): JsonObject =
    jsonObjectOf("server" to Tags.DNS_DIRECT, "strategy" to ctx.directDomainStrategy())

/** DnsAddress (generate.h): [port] 0 means the type's default; [iface] is empty for dhcp://auto. */
class DnsAddress(
    @JvmField val type: String,
    @JvmField val server: String = "",
    @JvmField val port: Int = 0,
    @JvmField val path: String = "",
    @JvmField val iface: String = "",
)

/** ParseDnsAddress's result: [address], or null with the reason in [why]. */
class DnsAddressParse(@JvmField val address: DnsAddress?, @JvmField val why: String)

/** The desktop's DNS address syntax (ParseDnsAddress and buildDnsObj, generate.cpp) and the DoH upgrade table. */
object DnsServers {

    private val IFACE = Regex("^[^\\s/]+$")
    private val IPV4_LIKE = Regex("^[0-9.]+$")
    private val HOSTNAME = Regex(
        "^(?=.{1,253}\\.?$)[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?(?:\\.[A-Za-z0-9_](?:[A-Za-z0-9_-]{0,61}[A-Za-z0-9_])?)*\\.?$"
    )
    private val PORT = Regex("^[0-9]{1,5}$")
    private val PLAIN_SCHEMES = setOf("udp", "tcp", "tls", "quic")
    private val PATH_SCHEMES = setOf("https", "h3")

    private fun isCanonicalDecimal(text: String, maxDigits: Int): Boolean {
        if (text.isEmpty() || text.length > maxDigits) return false
        if (text.length > 1 && text.startsWith('0')) return false
        return text.all { it in '0'..'9' }
    }

    /** isCanonicalIPv4 (generate.cpp): Go netip, where QHostAddress also takes "10.1" or "010.0.0.1". */
    private fun isCanonicalIPv4(text: String): Boolean {
        val octets = text.split('.')
        return octets.size == 4 && octets.all { isCanonicalDecimal(it, 3) && it.toInt() <= 255 }
    }

    /** isIPv6Literal (generate.cpp): QHostAddress's IPv6 parse, a zone allowed only with [allowZone]. */
    private fun isIPv6Literal(text: String, allowZone: Boolean): Boolean {
        if (!text.contains(':') || (!allowZone && text.contains('%'))) return false
        val pct = text.indexOf('%')
        if (pct == text.length - 1) return false
        return Hosts.parseIpv6(if (pct >= 0) text.substring(0, pct) else text) != null
    }

    /**
     * routeExcludePrefix (generate.cpp): the core decodes route_exclude_address as netip.Prefix, so a bare address
     * gets its full length; empty when unparsable.
     */
    @JvmStatic
    fun routeExcludePrefix(range: String): String {
        val text = range.trim()
        val slash = text.indexOf('/')
        val addr = if (slash < 0) text else text.substring(0, slash)
        val maxBits = if (isCanonicalIPv4(addr)) 32 else if (isIPv6Literal(addr, false)) 128 else -1
        if (maxBits < 0) return ""
        if (slash < 0) return "$addr/$maxBits"
        val bits = text.substring(slash + 1)
        if (!isCanonicalDecimal(bits, 3) || bits.toInt() > maxBits) return ""
        return text
    }

    /**
     * ParseDnsAddress (generate.cpp): "local...", "dhcp://auto|<iface>", "udp|tcp|tls|quic://host[:port]",
     * "https|h3://host[:port][/path]" or a bare "host[:port]" (udp).
     */
    @JvmStatic
    fun parseDnsAddress(text: String): DnsAddressParse {
        fun fail(reason: String) = DnsAddressParse(null, reason)
        val address = text.trim()
        if (address.isEmpty()) return fail(vtr(R.string.validation_dns_empty))
        if (address.startsWith("local", ignoreCase = true)) return DnsAddressParse(DnsAddress("local"), "")

        val schemeEnd = address.indexOf("://")
        val scheme = if (schemeEnd < 0) "udp" else address.substring(0, schemeEnd).lowercase()
        var rest = if (schemeEnd < 0) address else address.substring(schemeEnd + 3)

        if (scheme == "dhcp") {
            if (rest.isEmpty()) return fail(vtr(R.string.validation_dns_dhcp_interface))
            if (!IFACE.matches(rest)) return fail(vtr(R.string.validation_dns_bad_interface, rest))
            return DnsAddressParse(DnsAddress("dhcp", iface = if (rest.equals("auto", ignoreCase = true)) "" else rest), "")
        }

        if (scheme !in PLAIN_SCHEMES && scheme !in PATH_SCHEMES) return fail(vtr(R.string.validation_dns_scheme, scheme))

        var path = ""
        val slash = rest.indexOf('/')
        if (slash >= 0) {
            if (scheme !in PATH_SCHEMES) return fail(vtr(R.string.validation_dns_path))
            path = rest.substring(slash)
            rest = rest.substring(0, slash)
        }

        var host = rest
        var portText = ""
        var hasPort = false
        if (rest.startsWith('[')) {
            val close = rest.indexOf(']')
            if (close < 0) return fail(vtr(R.string.validation_dns_bracket))
            host = rest.substring(1, close)
            val tail = rest.substring(close + 1)
            if (tail.isNotEmpty()) {
                if (!tail.startsWith(':')) return fail(vtr(R.string.validation_dns_after_bracket))
                portText = tail.substring(1)
                hasPort = true
            }
            if (!isIPv6Literal(host, true)) return fail(vtr(R.string.validation_dns_ipv6, host))
        } else if (rest.count { it == ':' } > 1) {
            // A bare IPv6 cannot carry a port: its last group would read as one.
            if (!isIPv6Literal(rest, true)) return fail(vtr(R.string.validation_dns_ipv6_brackets, rest))
        } else {
            val colon = rest.indexOf(':')
            if (colon >= 0) {
                host = rest.substring(0, colon)
                portText = rest.substring(colon + 1)
                hasPort = true
            }
            if (host.isEmpty()) return fail(vtr(R.string.validation_dns_server_missing))
            if (IPV4_LIKE.matches(host)) {
                if (!isCanonicalIPv4(host)) return fail(vtr(R.string.validation_dns_ipv4, host))
            } else if (!HOSTNAME.matches(Hosts.toAceHost(host))) {
                return fail(vtr(R.string.validation_dns_host, host))
            }
        }

        var port = 0
        if (hasPort) {
            if (portText.isEmpty()) return fail(vtr(R.string.validation_dns_port_missing))
            port = if (PORT.matches(portText)) portText.toInt() else 0
            if (port < 1 || port > 65535) return fail(vtr(R.string.validation_dns_port, portText))
        }
        return DnsAddressParse(DnsAddress(scheme, Hosts.toAceHost(host), port, path), "")
    }

    /**
     * SplitDnsAddresses (generate.cpp): a DNS setting holds one or more comma-separated addresses, each parsed by
     * [parseDnsAddress]; several are raced and the first definitive answer wins. Trimmed, without empties or repeats.
     */
    @JvmStatic
    fun splitDnsAddresses(text: String): List<String> = Validation.items(text, Validation.Split.Comma).distinct()

    /** DnsAddressListProblem: "" when every address parses, else why, naming the bad address when there are several. */
    @JvmStatic
    fun dnsAddressListProblem(text: String): String {
        val addresses = splitDnsAddresses(text)
        if (addresses.isEmpty()) return vtr(R.string.validation_dns_empty)
        for ((i, address) in addresses.withIndex()) {
            val parsed = parseDnsAddress(address)
            if (parsed.address != null) continue
            if (addresses.size == 1) return parsed.why
            return vtr(R.string.validation_dns_server_item, (i + 1).toString(), address, parsed.why)
        }
        return ""
    }

    /** NormalizeDnsAddressList: the list as stored, "a, b". */
    @JvmStatic
    fun normalizeDnsAddressList(text: String): String = splitDnsAddresses(text).joinToString(", ")

    /**
     * buildDnsObj (generate.cpp): the parsed address, or for one [parseDnsAddress] refuses the pre-ParseDnsAddress
     * split (so a saved value builds as it always did), logged by real builds only ([forTest] repeats per batch).
     * The `underlying` variant only exists for tun + systemd-resolved on Linux, never on Android.
     */
    @JvmStatic
    @JvmOverloads
    fun buildDnsObj(address: String, forTest: Boolean = false): JsonObject {
        val parsed = parseDnsAddress(address)
        val dns = parsed.address
        if (dns == null) {
            if (!forTest) Logs.w("DNS address \"$address\" is invalid: ${parsed.why}")
            return legacyDnsObj(address)
        }
        if (dns.type == "local") return jsonObjectOf("type" to "local")
        if (dns.type == "dhcp") return jsonObjectOf("type" to "dhcp", "interface" to dns.iface)
        val res = jsonObjectOf("type" to dns.type, "server" to dns.server)
        if (dns.port > 0) res["server_port"] = dns.port
        if (dns.path.isNotEmpty()) res["path"] = dns.path
        return res
    }

    /** legacyDnsObj (generate.cpp): the old split, kept for refused addresses. */
    private fun legacyDnsObj(address: String): JsonObject {
        if (address.startsWith("local")) return JsonObject().also { it["type"] = "local" }
        if (address.startsWith("dhcp://")) {
            var ifcName = address.replace("dhcp://", "")
            if (ifcName == "auto") ifcName = ""
            return JsonObject().also {
                it["type"] = "dhcp"
                it["interface"] = ifcName
            }
        }
        var addr = address
        var port = -1
        var type = "udp"
        var path = ""
        if (address.startsWith("udp://")) addr = addr.substring(6)
        if (address.startsWith("tcp://")) {
            type = "tcp"
            addr = addr.replace("tcp://", "")
        }
        if (address.startsWith("tls://")) {
            type = "tls"
            addr = addr.replace("tls://", "")
        }
        if (address.startsWith("quic://")) {
            type = "quic"
            addr = addr.replace("quic://", "")
        }
        if (address.startsWith("https://")) {
            type = "https"
            addr = addr.replace("https://", "")
            val slashIndex = addr.indexOf("/")
            if (slashIndex != -1) {
                path = addr.substring(slashIndex)
                addr = addr.substring(0, slashIndex)
            }
        }
        if (address.startsWith("h3://")) {
            type = "h3"
            addr = addr.replace("h3://", "")
            val slashIndex = addr.indexOf("/")
            if (slashIndex != -1) {
                path = addr.substring(slashIndex)
                addr = addr.substring(0, slashIndex)
            }
        }
        if (addr.contains(":")) {
            val spl = QtStrings.split(addr, ":")
            addr = spl[0]
            port = QtStrings.toInt(spl[1])
        }
        val res = JsonObject()
        res["type"] = type
        res["server"] = addr
        if (port != -1) res["server_port"] = port
        if (path.isNotEmpty()) res["path"] = path
        return res
    }

    /** usableEchResolver (generate.cpp): a resolver [parseDnsAddress] refuses, or a dhcp one, falls back to dns-direct like an unset one. */
    @JvmStatic
    fun usableEchResolver(resolver: String): Boolean {
        val parsed = parseDnsAddress(resolver).address ?: return false
        return parsed.type != "dhcp"
    }

    private val KNOWN_DOH = mapOf(
        // Google
        "8.8.8.8" to "https://8.8.8.8/dns-query",
        "8.8.4.4" to "https://8.8.4.4/dns-query",
        // Cloudflare
        "1.1.1.1" to "https://1.1.1.1/dns-query",
        "1.0.0.1" to "https://1.0.0.1/dns-query",
        "1.1.1.2" to "https://1.1.1.2/dns-query",
        "1.0.0.2" to "https://1.0.0.2/dns-query",
        "1.1.1.3" to "https://1.1.1.3/dns-query",
        "1.0.0.3" to "https://1.0.0.3/dns-query",
        // Quad9
        "9.9.9.9" to "https://9.9.9.9/dns-query",
        "149.112.112.112" to "https://149.112.112.112/dns-query",
        // AdGuard
        "94.140.14.14" to "https://94.140.14.14/dns-query",
        "94.140.15.15" to "https://94.140.15.15/dns-query",
    )

    /** upgradeUdpDnsToDoH: a known resolver keeps its address, anything else becomes Google's DoH. */
    @JvmStatic
    fun upgradeUdpDnsToDoH(server: String): String = KNOWN_DOH[server] ?: "https://8.8.8.8/dns-query"
}

/** PredefinedDNSEntry (generate.h:72-76). */
internal class PredefinedDnsEntry(val domain: String) {
    val v4 = ArrayList<String>()
    val v6 = ArrayList<String>()
}

/** ParsePredefinedDNS (generate.cpp:2263-2295): hosts-file lines of an address followed by domains, `#` comments. */
internal object PredefinedDns {
    private val WHITESPACE = Regex("\\s+")

    /** Null when any non-empty line is malformed (the caller then emits no predefined rules at all, :934). */
    @JvmStatic
    fun parse(lines: List<String>): List<PredefinedDnsEntry>? {
        val out = ArrayList<PredefinedDnsEntry>()
        val indexOf = HashMap<String, Int>()
        for (rawLine in lines) {
            var line = rawLine
            val hash = line.indexOf('#')
            if (hash != -1) line = line.substring(0, hash)
            val fields = line.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            if (fields.isEmpty()) continue
            if (fields.size < 2) return null
            val address = normalizeAddress(fields[0]) ?: return null
            for (i in 1 until fields.size) {
                var domain = fields[i].lowercase()
                while (domain.endsWith('.')) domain = domain.dropLast(1)
                if (domain.isEmpty()) return null
                val index = indexOf.getOrPut(domain) {
                    out.add(PredefinedDnsEntry(domain))
                    out.size - 1
                }
                val bucket = if (address.second) out[index].v6 else out[index].v4
                if (!bucket.contains(address.first)) bucket.add(address.first)
            }
        }
        return out
    }

    /** QHostAddress::setAddress + setScopeId({}) + toString: the normalised text and whether it is IPv6. */
    private fun normalizeAddress(text: String): Pair<String, Boolean>? {
        val bare = text.removePrefix("[").removeSuffix("]")
        val percent = bare.indexOf('%')
        val withoutZone = if (percent >= 0) bare.substring(0, percent) else bare
        if (withoutZone.contains(':')) {
            val v6 = Hosts.parseIpv6(withoutZone) ?: return null
            return Hosts.formatIpv6(v6) to true
        }
        val v4 = Hosts.parseIpv4(withoutZone) ?: return null
        return Hosts.formatIpv4(v4) to false
    }
}
