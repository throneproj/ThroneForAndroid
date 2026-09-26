package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts

/** directDomainResolver (generate.cpp:788-790). */
internal fun directDomainResolver(ctx: BuildContext): JsonObject =
    jsonObjectOf("server" to Tags.DNS_DIRECT, "strategy" to ctx.directDomainStrategy())

/** The desktop's DNS address syntax (buildDnsObj, generate.cpp:780-841) and the DoH upgrade table (:843-863). */
internal object DnsServers {

    /**
     * `local*` -> local; `dhcp://<ifc|auto>` -> dhcp; `tcp://`, `tls://`, `quic://`, `https://`, `h3://` set the
     * type (default udp), the HTTP forms split a `path` at the first `/`, and `host:port` splits `server_port`.
     * The `underlying` variant only exists for tun + systemd-resolved on Linux (:782-784), never on Android.
     */
    @JvmStatic
    fun buildDnsObj(address: String): JsonObject {
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
