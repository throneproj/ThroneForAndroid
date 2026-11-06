package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** kTunnelDns* (Outbound.h): ignore the pushed resolvers, use them for the names they claim, or also for every remote query. */
const val TUNNEL_DNS_NONE = "none"
const val TUNNEL_DNS_PREFER = "prefer"
const val TUNNEL_DNS_STRICT = "strict"

/** openvpnNetworks (openvpn.h): the editor's network choices; "" lets the core pick. */
val OPENVPN_NETWORKS: List<String> = listOf("", "udp", "udp4", "udp6", "tcp", "tcp4", "tcp6")

/**
 * The shared toolkit of src/configs/sub/vpnFileImport.cpp (its anonymous namespace, `vpnfi*`): the .ovpn tokenizer
 * and directive reader, duration/prefix/fingerprint normalisers, and the QHostAddress::parseSubnet port. Used by
 * [OpenVpn.parseOvpnConfig] and [OpenConnect.parseOpenConnectProfile].
 */
internal object VpnFileImport {
    const val OVPN_DEFAULT_PORT = 1194

    class Directive(
        @JvmField val args: List<String>,
        @JvmField val inlineTag: String = "",
        @JvmField val inlineBody: List<String> = emptyList(),
    )

    class Remote {
        @JvmField var host: String = ""
        @JvmField var port: Int = 0
        @JvmField var network: String = ""
    }

    private val INTEGER = Regex("[+-]?[0-9]+")
    private val UNSIGNED = Regex("\\+?[0-9]+")

    /** QChar::isSpace(): ASCII whitespace, NEL, NBSP and the Unicode separator categories. */
    fun isSpace(c: Char): Boolean {
        if (c == ' ' || c in '\t'..'\r' || c == '\u0085' || c == '\u00a0') return true
        val type = Character.getType(c)
        return type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /** QString::trimmed(). */
    fun trimmed(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    /** QString::simplified(): trimmed, inner whitespace runs collapsed to one space. */
    private fun simplified(s: String): String {
        val sb = StringBuilder()
        var pendingSpace = false
        for (c in trimmed(s)) {
            if (isSpace(c)) {
                pendingSpace = true
                continue
            }
            if (pendingSpace) sb.append(' ')
            pendingSpace = false
            sb.append(c)
        }
        return sb.toString()
    }

    /** QString::toInt() with its ok flag: null when the text is not an integer. */
    fun toIntOk(s: String): Int? {
        val t = s.trim()
        if (!INTEGER.matches(t)) return null
        return t.toIntOrNull()
    }

    private fun toUInt(s: String): Long? {
        val t = s.trim()
        if (!UNSIGNED.matches(t)) return null
        val v = t.toLongOrNull() ?: return null
        return if (v > 0xFFFFFFFFL) null else v
    }

    /** sing-box listables accept a bare string as well as an array of lines (ovpnListable in openvpn.cpp, ocListable in openconnect.cpp). */
    fun listable(obj: JsonObject, key: String): MutableList<String> =
        if (obj.isString(key)) QtStrings.splitSkipEmpty(obj.string(key), "\n") else obj.array(key).strings()

    /** vpnfiTokenize (vpnFileImport.cpp): parse_line treats `#`/`;` as a comment only where a parameter would start. */
    fun tokenize(line: String): MutableList<String> {
        val tokens = ArrayList<String>()
        val token = StringBuilder()
        var quote: Char? = null
        var inToken = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quote != null) {
                if (ch == quote) {
                    quote = null
                    i++
                    continue
                }
                if (ch == '\\' && quote == '"' && i + 1 < line.length && (line[i + 1] == '"' || line[i + 1] == '\\')) {
                    token.append(line[++i])
                    i++
                    continue
                }
                token.append(ch)
                i++
                continue
            }
            if (isSpace(ch)) {
                if (inToken) {
                    tokens.add(token.toString())
                    token.setLength(0)
                    inToken = false
                }
                i++
                continue
            }
            if (!inToken && (ch == '#' || ch == ';')) break
            inToken = true
            if (ch == '"' || ch == '\'') {
                quote = ch
                i++
                continue
            }
            if (ch == '\\' && i + 1 < line.length) {
                token.append(line[++i])
                i++
                continue
            }
            token.append(ch)
            i++
        }
        if (inToken) tokens.add(token.toString())
        return tokens
    }

    /** vpnfiReadOvpnDirectives (vpnFileImport.cpp): inline `<tag>` blocks and backslash continuations. */
    fun readOvpnDirectives(body: String): List<Directive> {
        val result = ArrayList<Directive>()
        val lines = body.split('\n')
        var pending = ""
        var i = 0
        while (i < lines.size) {
            val trimmedLine = trimmed(lines[i].replace("\r", ""))
            if (pending.isEmpty() && trimmedLine.length > 2 && trimmedLine.startsWith('<') && trimmedLine.endsWith('>') &&
                !trimmedLine.startsWith("</")
            ) {
                val tag = trimmed(trimmedLine.substring(1, trimmedLine.length - 1))
                val closing = "</$tag>"
                val inlineBody = ArrayList<String>()
                i++
                while (i < lines.size) {
                    val inner = trimmed(lines[i].replace("\r", ""))
                    if (inner == closing) break
                    inlineBody.add(inner)
                    i++
                }
                while (inlineBody.isNotEmpty() && inlineBody[inlineBody.size - 1].isEmpty()) inlineBody.removeAt(inlineBody.size - 1)
                while (inlineBody.isNotEmpty() && inlineBody[0].isEmpty()) inlineBody.removeAt(0)
                result.add(Directive(emptyList(), tag, inlineBody))
                i++
                continue
            }
            if (trimmedLine.endsWith('\\') && !trimmedLine.endsWith("\\\\")) {
                pending += trimmedLine.dropLast(1)
                pending += ' '
                i++
                continue
            }
            val full = pending + trimmedLine
            pending = ""
            val args = tokenize(full)
            if (args.isNotEmpty()) result.add(Directive(args))
            i++
        }
        if (pending.isNotEmpty()) {
            val args = tokenize(pending)
            if (args.isNotEmpty()) result.add(Directive(args))
        }
        return result
    }

    /** IsValidDuration (generate.cpp): the sing-box duration grammar, not negative. */
    fun isValidDuration(text: String): Boolean = Validation.duration(text, Validation.DurationRule(Empty.Required)).isEmpty()

    /** vpnfiAccept (vpnFileImport.cpp): keeps a directive's value only when the endpoint accepts it; otherwise the note says why it was dropped. */
    fun accept(key: String, value: String, message: String, notes: MutableList<String>): Boolean {
        if (message.isEmpty()) return true
        notes.add(vtr(R.string.validation_ovpn_note_ignored, key, value, message))
        return false
    }

    /** vpnfiDuration (vpnFileImport.cpp): sing-box wants a duration string where `.ovpn` writes bare seconds. */
    fun duration(key: String, value: String, notes: MutableList<String>): String {
        val t = value.trim()
        val seconds = if (INTEGER.matches(t)) t.toLongOrNull() else null
        if (seconds != null && seconds >= 0) return if (seconds > 0) "${seconds}s" else ""
        if (seconds == null && isValidDuration(value)) return value
        notes.add(vtr(R.string.validation_ovpn_note_unreadable_value, key, value))
        return ""
    }

    /** vpnfiDataCiphers (vpnFileImport.cpp): the canonical upper-case names; unsupported offers are dropped from the negotiation list. */
    fun dataCiphers(key: String, value: String, notes: MutableList<String>): MutableList<String> {
        val ciphers = ArrayList<String>()
        for (name in QtStrings.splitSkipEmpty(value, ":")) {
            // OpenVPN 2.6 marks an optional cipher with '?'; the core takes only the bare name.
            val upper = name.trim().uppercase().removePrefix("?")
            if (accept(key, name, OpenVpnChecks.openVpnCipherName(upper), notes)) ciphers.add(upper)
        }
        return ciphers
    }

    /** QHostAddress(text) for a string containing ':' (qhostaddress.cpp parse): the 16 bytes and the scope id. */
    private fun parseHostAddressV6(text: String): Pair<ByteArray, String>? {
        val a = simplified(text)
        if (a.isEmpty()) return null
        val pct = a.lastIndexOf('%')
        val scope = if (pct >= 0) a.substring(pct + 1) else ""
        val bytes = Hosts.parseIpv6(if (pct >= 0) a.substring(0, pct) else a) ?: return null
        return Pair(bytes, scope)
    }

    /** QNetmask::setAddress: the prefix length of an IP-style netmask, null unless it is 1s followed by 0s. */
    private fun netmaskPrefixLength(text: String): Int? {
        val a = simplified(text)
        val bytes: ByteArray = if (a.contains(':')) {
            parseHostAddressV6(a)?.first ?: return null
        } else {
            val v4 = Hosts.parseIpv4(a) ?: return null
            byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
        }
        var netmask = 0
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 255) {
                netmask += 8
                i++
                continue
            }
            netmask += when (b) {
                254 -> 7
                252 -> 6
                248 -> 5
                240 -> 4
                224 -> 3
                192 -> 2
                128 -> 1
                0 -> 0
                else -> return null
            }
            break
        }
        i++
        while (i < bytes.size) {
            if (bytes[i].toInt() != 0) return null
            i++
        }
        return netmask
    }

    /** clearBits (qhostaddress.cpp) for a 128-bit address. */
    private fun clearBits(where: ByteArray, start: Int) {
        val end = 128
        if (start == end) return
        val bytemask = 256 - (1 shl (8 - (start and 7)))
        where[start / 8] = (where[start / 8].toInt() and bytemask).toByte()
        for (i in (start + 7) / 8 until end / 8) where[i] = 0
    }

    /**
     * QHostAddress::parseSubnet (qhostaddress.cpp): "a.b.c.d/nn", "a.b.c.d/m.m.m.m", the short "a.b/nn" forms and
     * "<ipv6>/nn"; the host bits are cleared. Returns the network address text and the prefix length, or null.
     */
    fun parseSubnet(subnet: String): Pair<String, Int>? {
        if (subnet.isEmpty()) return null
        val slash = subnet.indexOf('/')
        val netStr = if (slash != -1) subnet.substring(0, slash) else subnet
        var netmask = -1L
        val isIpv6 = netStr.contains(':')
        if (slash != -1) {
            netmask = if (!isIpv6 && subnet.indexOf('.', slash + 1) != -1) {
                (netmaskPrefixLength(subnet.substring(slash + 1)) ?: return null).toLong()
            } else {
                toUInt(subnet.substring(slash + 1)) ?: return null
            }
        }
        if (isIpv6) {
            if (netmask > 128) return null
            if (netmask < 0) netmask = 128
            val (bytes, scope) = parseHostAddressV6(netStr) ?: return null
            clearBits(bytes, netmask.toInt())
            val text = Hosts.formatIpv6(bytes) + (if (scope.isNotEmpty()) "%$scope" else "")
            return Pair(text, netmask.toInt())
        }
        if (netmask > 32) return null
        val parts = netStr.split('.').toMutableList()
        if (parts.size > 4) return null
        if (parts[parts.size - 1].isEmpty()) parts.removeAt(parts.size - 1)
        var addr = 0L
        for (part in parts) {
            val byteValue = toUInt(part) ?: return null
            if (byteValue > 255) return null
            addr = (addr shl 8) + byteValue
        }
        addr = (addr shl (8 * (4 - parts.size))) and 0xFFFFFFFFL
        if (netmask == -1L) {
            netmask = (8 * parts.size).toLong()
        } else if (netmask == 0L) {
            addr = 0
        } else if (netmask != 32L) {
            val shift = (32 - netmask).toInt()
            addr = addr and (((0xFFFFFFFFL ushr shift) shl shift) and 0xFFFFFFFFL)
        }
        return Pair(Hosts.formatIpv4(addr), netmask.toInt())
    }

    /** vpnfiPrefix (vpnFileImport.cpp): parseSubnet takes both the /24 and the /255.255.255.0 spelling. */
    fun prefix(address: String, mask: String): String {
        val parsed = parseSubnet("$address/$mask") ?: return ""
        return parsed.first + "/" + parsed.second
    }

    /** vpnfiNormalizePrefix (vpnFileImport.cpp). */
    fun normalizePrefix(prefix: String): String {
        val parsed = parseSubnet(prefix) ?: return ""
        return parsed.first + "/" + parsed.second
    }

    /** vpnfiFingerprint (vpnFileImport.cpp): 64 bare lowercase hex characters, not colon-separated. */
    fun fingerprint(raw: String): String {
        val hex = OpenVpnChecks.openVpnNormalizeFingerprint(raw)
        return if (hex.isNotEmpty() && OpenVpnChecks.openVpnFingerprint(hex).isEmpty()) hex else ""
    }

    /** vpnfiNetwork (vpnFileImport.cpp). */
    fun network(proto: String): String {
        val value = proto.lowercase()
        if (value == "udp" || value == "udp4" || value == "udp6" || value == "tcp" || value == "tcp4" || value == "tcp6") return value
        if (value == "tcp-client") return "tcp"
        if (value == "tcp4-client") return "tcp4"
        if (value == "tcp6-client") return "tcp6"
        return ""
    }

    /** vpnfiRouteKeyword (vpnFileImport.cpp). */
    fun routeKeyword(value: String): Boolean =
        value == "vpn_gateway" || value == "net_gateway" || value == "remote_host" || value == "default" || value == "dhcp"

    /** vpnfiParseRemote (vpnFileImport.cpp): `remote host [port] [proto]` in either order. */
    fun parseRemote(args: List<String>): Remote {
        val remote = Remote()
        if (args.size < 2) return remote
        remote.host = args[1]
        var i = 2
        while (i < args.size && i < 4) {
            val port = toIntOk(args[i])
            if (port != null && port > 0) {
                remote.port = port
            } else {
                val net = network(args[i])
                if (net.isNotEmpty()) remote.network = net
            }
            i++
        }
        return remote
    }

    /** The `key-direction` / `secret` / `tls-auth` direction argument (ParseOvpnConfig). */
    fun keyDirection(value: String): String = if (value == "1") "client" else if (value == "0") "server" else ""

    /** vpnfiOvpnIgnored (vpnFileImport.cpp): no endpoint equivalent, or the endpoint already behaves this way. */
    val OVPN_IGNORED: Set<String> = setOf(
        "allow-recursive-routing", "auth-nocache", "auth-token", "auth-token-user", "bind", "block-outside-dns",
        "client", "connect-retry", "connect-retry-max", "connect-timeout", "daemon", "dco", "dev-node", "dh",
        "dhcp-option", "dhcp-release", "dhcp-renew", "disable-dco", "down", "down-pre", "echo", "errors-to-stderr",
        "fast-io", "float", "group", "hash-size", "ifconfig-noexec", "ifconfig-nowarn", "ipchange", "key-method",
        "log", "log-append", "lport", "machine-readable-output", "max-routes", "mode", "mssfix-default", "mtu-disc",
        "mtu-test", "multihome", "mute", "mute-replay-warnings", "ncp-disable", "nice", "nobind", "opt-verify",
        "passtos", "persist-key", "persist-local-ip", "persist-remote-ip", "persist-tun", "ping-timer-rem", "pull",
        "push-peer-info", "rcvbuf", "register-dns", "remap-usr1", "remote-random-hostname", "resolv-retry",
        "route-delay", "route-method", "route-pre-down", "route-up", "script-security", "server-poll-timeout",
        "service", "setenv-safe", "single-session", "sndbuf", "socket-flags", "status", "suppress-timestamps",
        "syslog", "tls-client", "tls-exit", "tls-verify", "topology-subnet", "tran-window", "tun-mtu-extra",
        "txqueuelen", "up", "up-delay", "up-restart", "user", "verb", "windows-driver", "writepid",
    )

    /** vpnfiOvpnUnsupported (vpnFileImport.cpp): recognised, but nothing in the endpoint carries them. */
    val OVPN_UNSUPPORTED: Set<String> = setOf(
        "askpass", "capath", "cryptoapicert", "engine", "extra-certs", "http-proxy", "http-proxy-option",
        "inactive", "link-mtu", "management", "management-hold", "management-query-passwords", "no-replay",
        "ping-exit", "pkcs11-id", "pkcs11-providers", "providers", "socks-proxy", "tls-ciphersuites",
        "tls-crypt-v2-verify", "tls-export-cert", "x509-track", "x509-username-field",
    )

    /** vpnfiOvpnServerOnly (vpnFileImport.cpp). */
    val OVPN_SERVER_ONLY: Set<String> = setOf(
        "client-config-dir", "ifconfig-pool", "push", "server", "server-bridge", "tls-server",
    )
}

/**
 * openvpn (include/configs/outbounds/openvpn.h, src/configs/outbounds/openvpn.cpp): a sing-box endpoint. The stored
 * JSON says `type:"openvpn"` while Build emits `type:"openvpn-client"`; JSON parse accepts both. The share "link"
 * is the text of an `.ovpn` file ([parseOvpnConfig]).
 */
class OpenVpn : Outbound("openvpn") {

    /** OpenVPNRemote (openvpn.h). */
    class Remote {
        @JvmField var server: String = ""
        @JvmField var server_port: Int = 0
        @JvmField var network: String = ""

        /** OpenVPNRemote::ParseFromJson (openvpn.cpp). */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("server")) server = obj.string("server")
            if (obj.contains("server_port")) server_port = obj.int("server_port")
            if (obj.contains("network")) network = obj.string("network").trim()
            return true
        }

        /** OpenVPNRemote::ExportToJson (openvpn.cpp). */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (server.isNotEmpty()) obj["server"] = server
            if (server_port > 0) obj["server_port"] = server_port
            if (network.isNotEmpty()) obj["network"] = network
            return obj
        }

        /** OpenVPNRemote::Build (openvpn.cpp). */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNPullFilter (openvpn.h). */
    class PullFilter {
        @JvmField var action: String = ""
        @JvmField var text: String = ""

        /** OpenVPNPullFilter::ParseFromJson (openvpn.cpp). */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("action")) action = obj.string("action").trim()
            if (obj.contains("text")) text = obj.string("text")
            return true
        }

        /** OpenVPNPullFilter::ExportToJson (openvpn.cpp). */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (action.isEmpty() && text.isEmpty()) return obj
            obj["action"] = action
            obj["text"] = text
            return obj
        }

        /** OpenVPNPullFilter::Build (openvpn.cpp). */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNControlWrap (openvpn.h): tls-auth / tls-crypt / tls-crypt-v2 control channel wrapping. */
    class ControlWrap {
        @JvmField var type: String = ""
        @JvmField var key: MutableList<String> = ArrayList()
        @JvmField var direction: String = ""
        /** Conflicts with `key`. */
        @JvmField var key_path: String = ""

        /** OpenVPNControlWrap::ParseFromJson (openvpn.cpp). */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("type")) type = obj.string("type").trim()
            if (obj.contains("key")) key = VpnFileImport.listable(obj, "key")
            if (obj.contains("key_path")) key_path = obj.string("key_path")
            if (obj.contains("direction")) direction = obj.string("direction").trim()
            return true
        }

        /** OpenVPNControlWrap::ExportToJson (openvpn.cpp). */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (type.isEmpty()) return obj
            obj["type"] = type
            if (key.isNotEmpty()) obj["key"] = JsonValues.stringArray(key)
            if (key_path.isNotEmpty()) obj["key_path"] = key_path
            if (direction.isNotEmpty()) obj["direction"] = direction
            return obj
        }

        /** OpenVPNControlWrap::Build (openvpn.cpp). */
        fun build(ctx: BuildContext): JsonObject = exportToJson()
    }

    /** OpenVPNTLS (openvpn.h); not the sing-box TLS block, so [hasTls] stays false. */
    class Tls {
        @JvmField var certificate: MutableList<String> = ArrayList()
        @JvmField var client_certificate: MutableList<String> = ArrayList()
        @JvmField var client_key: MutableList<String> = ArrayList()
        @JvmField var control_wrap: ControlWrap = ControlWrap()

        /** Each *_path conflicts with its inline value. */
        @JvmField var server_name: String = ""
        @JvmField var server_name_type: String = ""
        @JvmField var certificate_path: String = ""
        @JvmField var client_certificate_path: String = ""
        @JvmField var client_key_path: String = ""
        @JvmField var peer_fingerprint: MutableList<String> = ArrayList()
        @JvmField var crl_path: String = ""
        @JvmField var remote_certificate_ku: MutableList<String> = ArrayList()
        @JvmField var remote_certificate_eku: String = ""
        @JvmField var remote_certificate_tls: String = ""
        @JvmField var certificate_profile: String = ""
        @JvmField var ns_certificate_type: String = ""
        @JvmField var version_min: String = ""
        @JvmField var version_max: String = ""
        @JvmField var cipher: String = ""
        @JvmField var groups: String = ""

        /** OpenVPNTLS::ParseFromJson (openvpn.cpp): the TLS cipher suites and groups pass [OpenVpnChecks] on import. */
        fun parseFromJson(obj: JsonObject): Boolean {
            if (obj.isEmpty()) return false
            if (obj.contains("server_name")) server_name = obj.string("server_name")
            if (obj.contains("server_name_type")) server_name_type = obj.string("server_name_type").trim()
            if (obj.contains("certificate")) certificate = VpnFileImport.listable(obj, "certificate")
            if (obj.contains("certificate_path")) certificate_path = obj.string("certificate_path")
            if (obj.contains("client_certificate")) client_certificate = VpnFileImport.listable(obj, "client_certificate")
            if (obj.contains("client_certificate_path")) client_certificate_path = obj.string("client_certificate_path")
            if (obj.contains("client_key")) client_key = VpnFileImport.listable(obj, "client_key")
            if (obj.contains("client_key_path")) client_key_path = obj.string("client_key_path")
            if (obj.contains("peer_fingerprint")) peer_fingerprint = VpnFileImport.listable(obj, "peer_fingerprint")
            if (obj.contains("crl_path")) crl_path = obj.string("crl_path")
            if (obj.contains("remote_certificate_ku")) remote_certificate_ku = VpnFileImport.listable(obj, "remote_certificate_ku")
            if (obj.contains("remote_certificate_eku")) remote_certificate_eku = obj.string("remote_certificate_eku")
            if (obj.contains("remote_certificate_tls")) remote_certificate_tls = obj.string("remote_certificate_tls").trim()
            if (obj.contains("certificate_profile")) certificate_profile = obj.string("certificate_profile").trim()
            if (obj.contains("ns_certificate_type")) ns_certificate_type = obj.string("ns_certificate_type").trim()
            if (obj.contains("version_min")) version_min = obj.string("version_min").trim()
            if (obj.contains("version_max")) version_max = obj.string("version_max").trim()
            // Without them the core's default suites and groups apply.
            cipher = ovpnReadChecked(obj, "cipher", "tls.cipher", cipher) { OpenVpnChecks.openVpnTlsCipher(it) }
            groups = ovpnReadChecked(obj, "groups", "tls.groups", groups) { OpenVpnChecks.openVpnTlsGroups(it) }
            if (obj.contains("control_wrap")) control_wrap.parseFromJson(obj.obj("control_wrap"))
            return true
        }

        /** OpenVPNTLS::ExportToJson (openvpn.cpp). */
        fun exportToJson(): JsonObject {
            val obj = JsonObject()
            if (server_name.isNotEmpty()) obj["server_name"] = server_name
            if (server_name_type.isNotEmpty()) obj["server_name_type"] = server_name_type
            if (certificate.isNotEmpty()) obj["certificate"] = JsonValues.stringArray(certificate)
            if (certificate_path.isNotEmpty()) obj["certificate_path"] = certificate_path
            if (client_certificate.isNotEmpty()) obj["client_certificate"] = JsonValues.stringArray(client_certificate)
            if (client_certificate_path.isNotEmpty()) obj["client_certificate_path"] = client_certificate_path
            if (client_key.isNotEmpty()) obj["client_key"] = JsonValues.stringArray(client_key)
            if (client_key_path.isNotEmpty()) obj["client_key_path"] = client_key_path
            if (peer_fingerprint.isNotEmpty()) obj["peer_fingerprint"] = JsonValues.stringArray(peer_fingerprint)
            if (crl_path.isNotEmpty()) obj["crl_path"] = crl_path
            if (remote_certificate_ku.isNotEmpty()) obj["remote_certificate_ku"] = JsonValues.stringArray(remote_certificate_ku)
            if (remote_certificate_eku.isNotEmpty()) obj["remote_certificate_eku"] = remote_certificate_eku
            if (remote_certificate_tls.isNotEmpty()) obj["remote_certificate_tls"] = remote_certificate_tls
            if (certificate_profile.isNotEmpty()) obj["certificate_profile"] = certificate_profile
            if (ns_certificate_type.isNotEmpty()) obj["ns_certificate_type"] = ns_certificate_type
            if (version_min.isNotEmpty()) obj["version_min"] = version_min
            if (version_max.isNotEmpty()) obj["version_max"] = version_max
            if (cipher.isNotEmpty()) obj["cipher"] = cipher
            if (groups.isNotEmpty()) obj["groups"] = groups
            val wrap = control_wrap.exportToJson()
            if (wrap.isNotEmpty()) obj["control_wrap"] = wrap
            return obj
        }

        /** OpenVPNTLS::Build (openvpn.cpp): bare lower-case fingerprints, the core's cipher-suite spelling, upper-case groups. */
        fun build(ctx: BuildContext): JsonObject {
            val obj = exportToJson()
            if (peer_fingerprint.isNotEmpty()) {
                val fingerprints = peer_fingerprint.map { OpenVpnChecks.openVpnNormalizeFingerprint(it) }.filter { it.isNotEmpty() }
                if (fingerprints.isEmpty()) obj.remove("peer_fingerprint") else obj["peer_fingerprint"] = JsonValues.stringArray(fingerprints)
            }
            if (cipher.isNotEmpty()) obj["cipher"] = OpenVpnChecks.openVpnNormalizeTlsCipher(cipher)
            if (groups.isNotEmpty()) obj["groups"] = groups.trim().uppercase()
            return obj
        }
    }

    @JvmField var network: String = ""
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var static_challenge: String = ""
    @JvmField var static_challenge_echo: Boolean = false
    @JvmField var mtu: Int = 0
    @JvmField var tls: Tls = Tls()
    /** Throne-only, never sent to the core (openvpn.h); Android has no OTP store, so it only round-trips. */
    @JvmField var otp_profile_id: Int = -1
    @JvmField var only_advertised_routes: Boolean = true
    @JvmField var tunnel_dns: String = TUNNEL_DNS_PREFER

    @JvmField var mode: String = ""
    @JvmField var servers: MutableList<Remote> = ArrayList()
    @JvmField var remote_random: Boolean = false
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var peer_address: String = ""
    @JvmField var peer_address_ipv6: String = ""
    @JvmField var topology: String = ""
    @JvmField var auth_retry: String = ""
    @JvmField var static_key: MutableList<String> = ArrayList()
    @JvmField var static_key_path: String = ""
    @JvmField var key_direction: String = ""
    @JvmField var cipher: String = ""
    @JvmField var data_ciphers: MutableList<String> = ArrayList()
    @JvmField var data_ciphers_fallback: String = ""
    @JvmField var auth: String = ""
    @JvmField var mss_fix: Int = 0
    @JvmField var mss_fix_disabled: Boolean = false
    @JvmField var mss_fix_mode: String = ""
    @JvmField var fragment: Int = 0
    @JvmField var replay_window: Int = 0
    @JvmField var replay_window_time: String = ""
    @JvmField var compression: String = ""
    @JvmField var compression_lzo: String = ""
    @JvmField var allow_compression: String = ""
    @JvmField var route_no_pull: Boolean = false
    @JvmField var pull_filters: MutableList<PullFilter> = ArrayList()
    @JvmField var routes: MutableList<String> = ArrayList()
    @JvmField var route_gateway: String = ""
    @JvmField var route_metric: Int = 0
    @JvmField var redirect_gateway: Boolean = false
    @JvmField var redirect_gateway_flags: MutableList<String> = ArrayList()
    @JvmField var redirect_private: Boolean = false
    @JvmField var block_ipv6: Boolean = false
    @JvmField var ping_interval: String = ""
    @JvmField var ping_restart: String = ""
    @JvmField var ping_restart_disabled: Boolean = false
    @JvmField var renegotiate_interval: String = ""
    @JvmField var renegotiate_disabled: Boolean = false
    @JvmField var renegotiate_bytes: Long = 0
    @JvmField var renegotiate_packets: Long = 0
    @JvmField var tls_timeout: String = ""
    @JvmField var handshake_window: String = ""
    @JvmField var explicit_exit_notify: Int = 0
    @JvmField var system: Boolean = false
    /** sing-box "name" (system interface name); [name] is the tag (openvpn.h). */
    @JvmField var interface_name: String = ""
    @JvmField var udp_timeout: String = ""
    @JvmField var udp_mapping: String = ""
    @JvmField var udp_filtering: String = ""
    @JvmField var udp_nat_max: Int = 0

    /** openvpn::ParseFromLink (openvpn.cpp): the "link" is the text of an .ovpn file. */
    override fun parseFromLink(link: String): Boolean = parseOvpnConfig(link, null)

    /**
     * openvpn::ParseFromJson (openvpn.cpp): enum-like values are trimmed; on import (and only then) an optional value
     * the core would refuse is dropped with a warning, while DB loads keep what was stored.
     */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        val type = obj.string("type")
        if (type != "openvpn" && type != "openvpn-client") return false
        super.parseFromJson(obj)

        if (obj.contains("mode")) mode = obj.string("mode").trim()
        if (obj.contains("network")) network = obj.string("network").trim()
        if (obj.contains("servers")) {
            servers.clear()
            for (item in obj.array("servers")) {
                val remote = Remote()
                if (remote.parseFromJson(item as? JsonObject ?: JsonObject())) servers.add(remote)
            }
        }
        if (obj.contains("remote_random")) remote_random = obj.bool("remote_random")
        if (obj.contains("address")) address = VpnFileImport.listable(obj, "address")
        if (obj.contains("peer_address")) peer_address = obj.string("peer_address")
        if (obj.contains("peer_address_ipv6")) peer_address_ipv6 = obj.string("peer_address_ipv6")
        if (obj.contains("topology")) topology = obj.string("topology").trim()
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("auth_retry")) auth_retry = obj.string("auth_retry").trim()
        if (obj.contains("static_challenge")) static_challenge = obj.string("static_challenge")
        if (obj.contains("static_challenge_echo")) static_challenge_echo = obj.bool("static_challenge_echo")
        if (obj.contains("static_key")) static_key = VpnFileImport.listable(obj, "static_key")
        if (obj.contains("static_key_path")) static_key_path = obj.string("static_key_path")
        if (obj.contains("key_direction")) key_direction = obj.string("key_direction").trim()
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (obj.contains("cipher")) cipher = obj.string("cipher")
        // Unsupported offers drop out of the negotiation; the static key cipher and auth have no such fallback.
        if (obj.contains("data_ciphers")) {
            data_ciphers = ovpnKeepItems("data_ciphers", VpnFileImport.listable(obj, "data_ciphers")) { OpenVpnChecks.openVpnCipherName(it) }
        }
        data_ciphers_fallback = ovpnReadChecked(obj, "data_ciphers_fallback", "data_ciphers_fallback", data_ciphers_fallback) {
            OpenVpnChecks.openVpnCipherName(it)
        }
        if (obj.contains("auth")) auth = obj.string("auth")
        if (obj.contains("mss_fix")) mss_fix = obj.int("mss_fix")
        if (obj.contains("mss_fix_disabled")) mss_fix_disabled = obj.bool("mss_fix_disabled")
        if (obj.contains("mss_fix_mode")) mss_fix_mode = obj.string("mss_fix_mode").trim()
        if (obj.contains("fragment")) {
            val value = obj.int("fragment")
            if (ovpnKeep("fragment", OpenVpnChecks.openVpnFragment(value.toString()), value.toString())) fragment = value
        }
        if (obj.contains("replay_window")) {
            val value = obj.int("replay_window")
            if (ovpnKeep("replay_window", OpenVpnChecks.openVpnReplayWindow(value.toString()), value.toString())) replay_window = value
        }
        replay_window_time = ovpnReadChecked(obj, "replay_window_time", "replay_window_time", replay_window_time) {
            OpenVpnChecks.openVpnReplayWindowTime(it)
        }
        if (obj.contains("compression")) compression = obj.string("compression")
        if (obj.contains("compression_lzo")) compression_lzo = obj.string("compression_lzo")
        if (obj.contains("allow_compression")) allow_compression = obj.string("allow_compression")
        if (obj.contains("route_no_pull")) route_no_pull = obj.bool("route_no_pull")
        if (obj.contains("pull_filters")) {
            pull_filters.clear()
            for (item in obj.array("pull_filters")) {
                val filter = PullFilter()
                if (filter.parseFromJson(item as? JsonObject ?: JsonObject())) pull_filters.add(filter)
            }
        }
        if (obj.contains("routes")) routes = ovpnKeepItems("routes", VpnFileImport.listable(obj, "routes")) { Validation.cidr(it) }
        route_gateway = ovpnReadChecked(obj, "route_gateway", "route_gateway", route_gateway) {
            Validation.ip(it, Family.Any, Empty.Allowed)
        }
        if (obj.contains("route_metric")) route_metric = obj.int("route_metric")
        if (obj.contains("redirect_gateway")) redirect_gateway = obj.bool("redirect_gateway")
        if (obj.contains("redirect_gateway_flags")) redirect_gateway_flags = VpnFileImport.listable(obj, "redirect_gateway_flags")
        if (obj.contains("redirect_private")) redirect_private = obj.bool("redirect_private")
        if (obj.contains("block_ipv6")) block_ipv6 = obj.bool("block_ipv6")
        ping_interval = ovpnReadChecked(obj, "ping_interval", "ping_interval", ping_interval) { OpenVpnChecks.openVpnPingInterval(it) }
        ping_restart = ovpnReadChecked(obj, "ping_restart", "ping_restart", ping_restart) { OpenVpnChecks.openVpnPingInterval(it) }
        if (obj.contains("ping_restart_disabled")) ping_restart_disabled = obj.bool("ping_restart_disabled")
        renegotiate_interval = ovpnReadChecked(obj, "renegotiate_interval", "renegotiate_interval", renegotiate_interval) {
            OpenVpnChecks.openVpnInterval(it)
        }
        if (obj.contains("renegotiate_disabled")) renegotiate_disabled = obj.bool("renegotiate_disabled")
        if (obj.contains("renegotiate_bytes")) renegotiate_bytes = obj.integer("renegotiate_bytes")
        if (obj.contains("renegotiate_packets")) renegotiate_packets = obj.integer("renegotiate_packets")
        tls_timeout = ovpnReadChecked(obj, "tls_timeout", "tls_timeout", tls_timeout) { OpenVpnChecks.openVpnInterval(it) }
        handshake_window = ovpnReadChecked(obj, "handshake_window", "handshake_window", handshake_window) { OpenVpnChecks.openVpnInterval(it) }
        if (obj.contains("explicit_exit_notify")) explicit_exit_notify = obj.int("explicit_exit_notify")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("name")) interface_name = obj.string("name")
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        // The core also takes a bare number of seconds here; Throne stores and emits the duration string.
        if (obj.contains("udp_timeout")) {
            if (obj.isNumber("udp_timeout") && obj.integer("udp_timeout") > 0) {
                udp_timeout = obj.integer("udp_timeout").toString() + "s"
            } else {
                udp_timeout = ovpnReadChecked(obj, "udp_timeout", "udp_timeout", udp_timeout) { Validation.duration(it) }
            }
        }
        if (obj.contains("udp_mapping")) udp_mapping = obj.string("udp_mapping")
        if (obj.contains("udp_filtering")) udp_filtering = obj.string("udp_filtering")
        if (obj.contains("udp_nat_max")) udp_nat_max = obj.int("udp_nat_max")

        if (obj.contains("otp_profile_id")) otp_profile_id = obj.int("otp_profile_id")
        if (obj.contains("only_advertised_routes")) only_advertised_routes = obj.bool("only_advertised_routes")
        // Profiles saved before the mode existed carry the two checkboxes it replaced.
        if (obj.contains("tunnel_dns")) tunnel_dns = obj.string("tunnel_dns")
        else if (obj.bool("block_outside_dns")) tunnel_dns = TUNNEL_DNS_STRICT
        else if (obj.contains("use_tunnel_dns") && !obj.bool("use_tunnel_dns")) tunnel_dns = TUNNEL_DNS_NONE
        return true
    }

    /** openvpn::ExportToJson (openvpn.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "openvpn"
        obj.merge(baseExportToJson())

        if (mode.isNotEmpty()) obj["mode"] = mode
        if (network.isNotEmpty()) obj["network"] = network
        if (servers.isNotEmpty()) {
            val remotes = JsonArray()
            for (remote in servers) {
                val item = remote.exportToJson()
                if (item.isNotEmpty()) remotes.add(item)
            }
            if (remotes.isNotEmpty()) obj["servers"] = remotes
        }
        if (remote_random) obj["remote_random"] = true
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (peer_address.isNotEmpty()) obj["peer_address"] = peer_address
        if (peer_address_ipv6.isNotEmpty()) obj["peer_address_ipv6"] = peer_address_ipv6
        if (topology.isNotEmpty()) obj["topology"] = topology
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (auth_retry.isNotEmpty()) obj["auth_retry"] = auth_retry
        if (static_challenge.isNotEmpty()) obj["static_challenge"] = static_challenge
        if (static_challenge_echo) obj["static_challenge_echo"] = true
        if (static_key.isNotEmpty()) obj["static_key"] = JsonValues.stringArray(static_key)
        if (static_key_path.isNotEmpty()) obj["static_key_path"] = static_key_path
        if (key_direction.isNotEmpty()) obj["key_direction"] = key_direction
        val tlsObj = tls.exportToJson()
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        if (cipher.isNotEmpty()) obj["cipher"] = cipher
        if (data_ciphers.isNotEmpty()) obj["data_ciphers"] = JsonValues.stringArray(data_ciphers)
        if (data_ciphers_fallback.isNotEmpty()) obj["data_ciphers_fallback"] = data_ciphers_fallback
        if (auth.isNotEmpty()) obj["auth"] = auth
        if (mss_fix > 0) obj["mss_fix"] = mss_fix
        if (mss_fix_disabled) obj["mss_fix_disabled"] = true
        if (mss_fix_mode.isNotEmpty()) obj["mss_fix_mode"] = mss_fix_mode
        if (fragment > 0) obj["fragment"] = fragment
        if (replay_window > 0) obj["replay_window"] = replay_window
        if (replay_window_time.isNotEmpty()) obj["replay_window_time"] = replay_window_time
        if (compression.isNotEmpty()) obj["compression"] = compression
        if (compression_lzo.isNotEmpty()) obj["compression_lzo"] = compression_lzo
        if (allow_compression.isNotEmpty()) obj["allow_compression"] = allow_compression
        if (route_no_pull) obj["route_no_pull"] = true
        if (pull_filters.isNotEmpty()) {
            val filters = JsonArray()
            for (filter in pull_filters) {
                val item = filter.exportToJson()
                if (item.isNotEmpty()) filters.add(item)
            }
            if (filters.isNotEmpty()) obj["pull_filters"] = filters
        }
        if (routes.isNotEmpty()) obj["routes"] = JsonValues.stringArray(routes)
        if (route_gateway.isNotEmpty()) obj["route_gateway"] = route_gateway
        if (route_metric > 0) obj["route_metric"] = route_metric
        if (redirect_gateway) obj["redirect_gateway"] = true
        if (redirect_gateway_flags.isNotEmpty()) obj["redirect_gateway_flags"] = JsonValues.stringArray(redirect_gateway_flags)
        if (redirect_private) obj["redirect_private"] = true
        if (block_ipv6) obj["block_ipv6"] = true
        if (ping_interval.isNotEmpty()) obj["ping_interval"] = ping_interval
        if (ping_restart.isNotEmpty()) obj["ping_restart"] = ping_restart
        if (ping_restart_disabled) obj["ping_restart_disabled"] = true
        if (renegotiate_interval.isNotEmpty()) obj["renegotiate_interval"] = renegotiate_interval
        if (renegotiate_disabled) obj["renegotiate_disabled"] = true
        if (renegotiate_bytes > 0) obj["renegotiate_bytes"] = renegotiate_bytes
        if (renegotiate_packets > 0) obj["renegotiate_packets"] = renegotiate_packets
        if (tls_timeout.isNotEmpty()) obj["tls_timeout"] = tls_timeout
        if (handshake_window.isNotEmpty()) obj["handshake_window"] = handshake_window
        if (explicit_exit_notify > 0) obj["explicit_exit_notify"] = explicit_exit_notify
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (mtu > 0) obj["mtu"] = mtu
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max

        if (otp_profile_id >= 0) obj["otp_profile_id"] = otp_profile_id
        obj["only_advertised_routes"] = only_advertised_routes
        obj["tunnel_dns"] = tunnel_dns
        return obj
    }

    /** openvpn::Build (openvpn.cpp). */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "openvpn-client"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))

        // `server`/`server_port` and `servers` are mutually exclusive.
        if (servers.isEmpty()) {
            if (server.isNotEmpty()) obj["server"] = server
            if (serverPort > 0) obj["server_port"] = serverPort
        } else {
            val remotes = JsonArray()
            for (remote in servers) {
                val item = remote.build(ctx)
                if (item.isNotEmpty()) remotes.add(item)
            }
            obj["servers"] = remotes
            if (remote_random) obj["remote_random"] = true
        }

        // Android has no OTP store: otp_profile_id only round-trips and the stored credentials reach the core as they are.
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (static_challenge.isNotEmpty()) obj["static_challenge"] = static_challenge
        if (static_challenge_echo) obj["static_challenge_echo"] = true

        if (mode.isNotEmpty()) obj["mode"] = mode
        if (network.isNotEmpty()) obj["network"] = network
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (peer_address.isNotEmpty()) obj["peer_address"] = peer_address
        if (peer_address_ipv6.isNotEmpty()) obj["peer_address_ipv6"] = peer_address_ipv6
        if (topology.isNotEmpty()) obj["topology"] = topology
        if (auth_retry.isNotEmpty()) obj["auth_retry"] = auth_retry
        if (static_key.isNotEmpty()) obj["static_key"] = JsonValues.stringArray(static_key)
        if (static_key_path.isNotEmpty()) obj["static_key_path"] = static_key_path
        if (key_direction.isNotEmpty()) obj["key_direction"] = key_direction
        val tlsObj = tls.build(ctx)
        if (tlsObj.isNotEmpty()) obj["tls"] = tlsObj
        // The core matches the canonical upper-case names only; .ovpn files often spell them in lower case.
        if (cipher.isNotEmpty()) obj["cipher"] = cipher.trim().uppercase()
        if (data_ciphers.isNotEmpty()) obj["data_ciphers"] = JsonValues.stringArray(data_ciphers.map { it.trim().uppercase() })
        if (data_ciphers_fallback.isNotEmpty()) obj["data_ciphers_fallback"] = data_ciphers_fallback.trim().uppercase()
        if (auth.isNotEmpty()) obj["auth"] = auth.trim().uppercase()
        if (mss_fix > 0) obj["mss_fix"] = mss_fix
        if (mss_fix_disabled) obj["mss_fix_disabled"] = true
        if (mss_fix_mode.isNotEmpty()) obj["mss_fix_mode"] = mss_fix_mode
        if (fragment > 0) obj["fragment"] = fragment
        if (replay_window > 0) obj["replay_window"] = replay_window
        if (replay_window_time.isNotEmpty()) obj["replay_window_time"] = replay_window_time
        if (compression.isNotEmpty()) obj["compression"] = compression
        if (compression_lzo.isNotEmpty()) obj["compression_lzo"] = compression_lzo
        if (allow_compression.isNotEmpty()) obj["allow_compression"] = allow_compression
        if (route_no_pull) obj["route_no_pull"] = true
        if (pull_filters.isNotEmpty()) {
            val filters = JsonArray()
            for (filter in pull_filters) {
                val item = filter.build(ctx)
                if (item.isNotEmpty()) filters.add(item)
            }
            if (filters.isNotEmpty()) obj["pull_filters"] = filters
        }
        if (routes.isNotEmpty()) obj["routes"] = JsonValues.stringArray(routes)
        if (route_gateway.isNotEmpty()) obj["route_gateway"] = route_gateway
        if (route_metric > 0) obj["route_metric"] = route_metric
        if (redirect_gateway) obj["redirect_gateway"] = true
        if (redirect_gateway_flags.isNotEmpty()) obj["redirect_gateway_flags"] = JsonValues.stringArray(redirect_gateway_flags)
        if (redirect_private) obj["redirect_private"] = true
        if (block_ipv6) obj["block_ipv6"] = true
        if (ping_interval.isNotEmpty()) obj["ping_interval"] = ping_interval
        if (ping_restart.isNotEmpty()) obj["ping_restart"] = ping_restart
        if (ping_restart_disabled) obj["ping_restart_disabled"] = true
        if (renegotiate_interval.isNotEmpty()) obj["renegotiate_interval"] = renegotiate_interval
        if (renegotiate_disabled) obj["renegotiate_disabled"] = true
        if (renegotiate_bytes > 0) obj["renegotiate_bytes"] = renegotiate_bytes
        if (renegotiate_packets > 0) obj["renegotiate_packets"] = renegotiate_packets
        if (tls_timeout.isNotEmpty()) obj["tls_timeout"] = tls_timeout
        if (handshake_window.isNotEmpty()) obj["handshake_window"] = handshake_window
        if (explicit_exit_notify > 0) obj["explicit_exit_notify"] = explicit_exit_notify
        if (system) obj["system"] = true
        if (interface_name.isNotEmpty()) obj["name"] = interface_name
        if (mtu > 0) obj["mtu"] = mtu
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        if (udp_mapping.isNotEmpty()) obj["udp_mapping"] = udp_mapping
        if (udp_filtering.isNotEmpty()) obj["udp_filtering"] = udp_filtering
        if (udp_nat_max > 0) obj["udp_nat_max"] = udp_nat_max
        return BuildResult(obj)
    }

    /** openvpn::DisplayType (openvpn.cpp). */
    override fun displayType(): String = "OpenVPN"

    /** openvpn::GetSecurity (openvpn.cpp). */
    override fun security(): SecurityInfo {
        if (mode == "static_key") return SecurityInfo("Static Key", "", SecurityLevel.Weak)
        val pinned = tls.certificate.isNotEmpty() || tls.certificate_path.isNotEmpty() || tls.peer_fingerprint.isNotEmpty()
        if (!pinned) return SecurityInfo("Unverified TLS", "", SecurityLevel.Weak)
        return SecurityInfo("TLS", "", SecurityLevel.Secure)
    }

    /** openvpn::IsEndpoint (openvpn.cpp). */
    override fun isEndpoint(): Boolean = true

    /** openvpn::SupportsCredentialStrip (openvpn.cpp). */
    override fun supportsCredentialStrip(): Boolean = true

    /** openvpn::StripCredentials (openvpn.cpp). */
    override fun stripCredentials() {
        username = ""
        password = ""
        static_key.clear()
        static_key_path = ""
        otp_profile_id = -1
        tls.client_key.clear()
        tls.client_key_path = ""
        tls.control_wrap.key.clear()
        tls.control_wrap.key_path = ""
        // Absolute local paths: meaningless on another machine and they carry the OS user name.
        tls.certificate_path = ""
        tls.client_certificate_path = ""
        tls.crl_path = ""
    }

    /** A non-empty Advanced server list replaces the profile's own server and port (openvpn.h). */
    override fun serverPortOptional(): Boolean = servers.isNotEmpty()

    override fun serverAddressOptional(): Boolean = servers.isNotEmpty()

    /** openvpn::StripForeignState (openvpn.cpp): the one-time password entry belongs to another installation. */
    override fun stripForeignState(issues: MutableList<Issue>) {
        if (otp_profile_id < 0) return
        otp_profile_id = -1
        issues.addIssue(Severity.Warning, "otp_profile_id", vtr(R.string.validation_ovpn_otp_removed))
    }

    /** openvpn::Validate (openvpn.cpp), without the `{otp}` placeholder reminders: Android never substitutes it. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        fun report(field: String, message: String, value: String = "") = issues.addIssue(Severity.Error, field, message, value)
        fun reportItems(field: String, items: List<String>, rule: Rule) {
            for (item in items) {
                val message = rule(item)
                if (message.isNotEmpty()) return report(field, message, item)
            }
        }
        val cidr: Rule = { Validation.cidr(it) }
        val versions = listOf("1.0", "1.1", "1.2", "1.3")

        report("mode", Validation.oneOf(mode, listOf("tls", "static_key")), mode)
        report("network", Validation.oneOf(network, OPENVPN_NETWORKS), network)
        if (servers.isEmpty() && (server.startsWith('[') || server.endsWith(']'))) {
            report("server", OpenVpnChecks.openVpnRemoteHost(server), server)
        }
        for ((i, remote) in servers.withIndex()) {
            val field = "servers[$i]"
            val port = remote.server_port.toString()
            report("$field.server", OpenVpnChecks.openVpnRemoteHost(remote.server), remote.server)
            report("$field.server_port", OpenVpnChecks.openVpnRemotePort(port), port)
            report("$field.network", Validation.oneOf(remote.network, OPENVPN_NETWORKS), remote.network)
        }
        reportItems("address", address, cidr)
        report("peer_address", Validation.ip(peer_address, Family.V4, Empty.Allowed), peer_address)
        report("peer_address_ipv6", Validation.ip(peer_address_ipv6, Family.V6, Empty.Allowed), peer_address_ipv6)
        report("topology", Validation.oneOf(topology, listOf("net30", "p2p", "subnet")), topology)
        report("auth_retry", Validation.oneOf(auth_retry, listOf("none", "nointeract", "interact")), auth_retry)
        report("key_direction", Validation.oneOf(key_direction, listOf("server", "client")), key_direction)
        report("cipher", OpenVpnChecks.openVpnCipherName(cipher), cipher)
        reportItems("data_ciphers", data_ciphers) { OpenVpnChecks.openVpnCipherName(it) }
        report("data_ciphers_fallback", OpenVpnChecks.openVpnCipherName(data_ciphers_fallback), data_ciphers_fallback)
        report("auth", OpenVpnChecks.openVpnDigest(auth), auth)
        report("mss_fix_mode", Validation.oneOf(mss_fix_mode, listOf("mtu", "fixed")), mss_fix_mode)
        report("fragment", OpenVpnChecks.openVpnFragment(fragment.toString()), fragment.toString())
        report("replay_window", OpenVpnChecks.openVpnReplayWindow(replay_window.toString()), replay_window.toString())
        report("replay_window_time", OpenVpnChecks.openVpnReplayWindowTime(replay_window_time), replay_window_time)
        reportItems("routes", routes, cidr)
        report("route_gateway", Validation.ip(route_gateway, Family.Any, Empty.Allowed), route_gateway)
        report("ping_interval", OpenVpnChecks.openVpnPingInterval(ping_interval), ping_interval)
        report("ping_restart", OpenVpnChecks.openVpnPingInterval(ping_restart), ping_restart)
        report("renegotiate_interval", OpenVpnChecks.openVpnInterval(renegotiate_interval), renegotiate_interval)
        report("tls_timeout", OpenVpnChecks.openVpnInterval(tls_timeout), tls_timeout)
        report("handshake_window", OpenVpnChecks.openVpnInterval(handshake_window), handshake_window)
        report("udp_timeout", Validation.duration(udp_timeout), udp_timeout)
        for ((i, filter) in pull_filters.withIndex()) {
            if (filter.action.isEmpty() && filter.text.isEmpty()) continue
            val field = "pull_filters[$i]"
            report("$field.action", Validation.oneOf(filter.action, listOf("accept", "ignore", "reject"), Empty.Required), filter.action)
            report("$field.text", Validation.required(filter.text))
        }

        report("tls.server_name_type", Validation.oneOf(tls.server_name_type, listOf("subject", "name", "name-prefix")), tls.server_name_type)
        reportItems("tls.peer_fingerprint", tls.peer_fingerprint) { OpenVpnChecks.openVpnFingerprint(it) }
        reportItems("tls.remote_certificate_ku", tls.remote_certificate_ku) { OpenVpnChecks.openVpnKeyUsage(it) }
        report("tls.remote_certificate_eku", OpenVpnChecks.openVpnExtendedKeyUsage(tls.remote_certificate_eku), tls.remote_certificate_eku)
        report("tls.remote_certificate_tls", Validation.oneOf(tls.remote_certificate_tls, listOf("server", "client", "none")), tls.remote_certificate_tls)
        report(
            "tls.certificate_profile",
            Validation.oneOf(tls.certificate_profile, listOf("legacy", "preferred", "insecure", "suiteb")),
            tls.certificate_profile,
        )
        report("tls.ns_certificate_type", Validation.oneOf(tls.ns_certificate_type, listOf("server", "client")), tls.ns_certificate_type)
        report("tls.version_min", Validation.oneOf(tls.version_min, versions), tls.version_min)
        report("tls.version_max", Validation.oneOf(tls.version_max, versions), tls.version_max)
        report("tls.cipher", OpenVpnChecks.openVpnTlsCipher(tls.cipher), tls.cipher)
        report("tls.groups", OpenVpnChecks.openVpnTlsGroups(tls.groups), tls.groups)

        for (key in OpenVpnChecks.openVpnModeConflicts(this)) report(key, OpenVpnChecks.openVpnModeConflict(this, key))
        report("static_key", OpenVpnChecks.openVpnStaticKeyMissing(this))
        report("static_key", OpenVpnChecks.vpnMaterialConflict(static_key, static_key_path))
        report("cipher", OpenVpnChecks.openVpnStaticCipher(this), cipher)
        report("address", OpenVpnChecks.openVpnStaticAddress(this))
        report("tls.certificate", OpenVpnChecks.openVpnTlsVerification(this))
        report("tls.client_certificate", OpenVpnChecks.openVpnClientPair(this))
        report("tls.certificate", OpenVpnChecks.vpnMaterialConflict(tls.certificate, tls.certificate_path))
        report("tls.client_certificate", OpenVpnChecks.vpnMaterialConflict(tls.client_certificate, tls.client_certificate_path))
        report("tls.client_key", OpenVpnChecks.vpnMaterialConflict(tls.client_key, tls.client_key_path))
        report("tls.control_wrap.key", OpenVpnChecks.vpnMaterialConflict(tls.control_wrap.key, tls.control_wrap.key_path))
        report("tls.control_wrap.type", OpenVpnChecks.openVpnControlWrapKey(this), tls.control_wrap.type)
        report("tls.control_wrap.direction", OpenVpnChecks.openVpnControlWrapDirection(this), tls.control_wrap.direction)
        report("tls.remote_certificate_eku", OpenVpnChecks.openVpnRemoteCertificate(this))
        report("tls.version_max", OpenVpnChecks.openVpnTlsVersions(this), tls.version_max)
        report("mss_fix", OpenVpnChecks.openVpnMssFix(this))
        report("ping_restart", OpenVpnChecks.openVpnPingRestart(this))
        report("renegotiate_interval", OpenVpnChecks.openVpnRenegotiate(this))
    }

    /**
     * ParseOvpnConfig (vpnFileImport.cpp) applied to this profile. Every note the desktop would show is
     * appended to [problems]; a fatal problem appends its reason too, then returns false.
     */
    @JvmOverloads
    fun parseOvpnConfig(body: String, problems: MutableList<String>? = null): Boolean {
        val notes = ArrayList<String>()
        val directives = VpnFileImport.readOvpnDirectives(body)
        fun flush() {
            problems?.addAll(notes)
        }
        if (directives.isEmpty()) {
            notes.add(vtr(R.string.validation_ovpn_note_empty))
            flush()
            return false
        }

        servers.clear()
        address.clear()
        routes.clear()
        data_ciphers.clear()
        pull_filters.clear()
        redirect_gateway_flags.clear()
        tls.peer_fingerprint.clear()
        tls.remote_certificate_ku.clear()

        val remotes = ArrayList<VpnFileImport.Remote>()
        var defaultPort = 0
        var keyDirection = ""
        var legacyCipher = ""
        var ifconfigLocal = ""
        var ifconfigSecond = ""
        var fatal = ""
        var clientCertSwitch = ""
        var friendlyName = ""

        // OpenVPN Connect keeps its display name in a comment, which the tokenizer drops.
        for (line in body.split('\n')) {
            var comment = VpnFileImport.trimmed(line)
            if (!comment.startsWith('#')) continue
            comment = VpnFileImport.trimmed(comment.substring(1))
            val prefix = "OVPN_FRIENDLY_PROFILE_NAME="
            if (comment.startsWith(prefix)) friendlyName = VpnFileImport.trimmed(comment.substring(prefix.length))
        }

        for (item in directives) {
            if (fatal.isNotEmpty()) break

            if (item.inlineTag.isNotEmpty()) {
                val tag = item.inlineTag
                if (tag == "connection") {
                    var remote = VpnFileImport.Remote()
                    var blockNetwork = ""
                    var blockPort = 0
                    for (line in item.inlineBody) {
                        val args = VpnFileImport.tokenize(line)
                        if (args.isEmpty()) continue
                        if (args[0] == "remote") remote = VpnFileImport.parseRemote(args)
                        else if (args[0] == "proto" && args.size > 1) blockNetwork = VpnFileImport.network(args[1])
                        else if ((args[0] == "port" || args[0] == "rport") && args.size > 1) blockPort = QtStrings.toInt(args[1])
                    }
                    if (remote.host.isEmpty()) {
                        notes.add(vtr(R.string.validation_ovpn_note_connection_no_remote))
                        continue
                    }
                    if (remote.network.isEmpty()) remote.network = blockNetwork
                    if (remote.port == 0) remote.port = blockPort
                    remotes.add(remote)
                    continue
                }
                when (tag) {
                    "ca" -> tls.certificate = ArrayList(item.inlineBody)
                    "cert" -> tls.client_certificate = ArrayList(item.inlineBody)
                    "key" -> tls.client_key = ArrayList(item.inlineBody)
                    "tls-auth", "tls-crypt", "tls-crypt-v2" -> {
                        tls.control_wrap.type = tag.replace('-', '_')
                        tls.control_wrap.key = ArrayList(item.inlineBody)
                    }
                    "secret" -> {
                        mode = "static_key"
                        static_key = ArrayList(item.inlineBody)
                    }
                    "auth-user-pass" -> {
                        if (item.inlineBody.isNotEmpty()) username = item.inlineBody[0]
                        if (item.inlineBody.size > 1) password = item.inlineBody[1]
                    }
                    "peer-fingerprint" -> for (line in item.inlineBody) {
                        val hex = VpnFileImport.fingerprint(line)
                        if (hex.isNotEmpty()) tls.peer_fingerprint.add(hex)
                        else notes.add(vtr(R.string.validation_ovpn_note_fingerprint, line))
                    }
                    "pkcs12" -> fatal = vtr(R.string.validation_ovpn_note_pkcs12)
                    else -> notes.add(vtr(R.string.validation_ovpn_note_inline_block, tag))
                }
                continue
            }

            val args = item.args
            val key = args[0]
            val value = if (args.size > 1) args[1] else ""

            if (key in VpnFileImport.OVPN_SERVER_ONLY) {
                fatal = vtr(R.string.validation_ovpn_note_server_config, key)
                continue
            }
            if (key == "pkcs12") {
                fatal = vtr(R.string.validation_ovpn_note_pkcs12)
                continue
            }
            if (key == "dev" || key == "dev-type") {
                if (value.startsWith("tap")) {
                    fatal = vtr(R.string.validation_ovpn_note_tap)
                }
                continue
            }
            if (key == "mode") {
                if (value == "server") {
                    fatal = vtr(R.string.validation_ovpn_note_mode_server)
                }
                continue
            }

            if (key == "remote") {
                val remote = VpnFileImport.parseRemote(args)
                if (remote.host.isNotEmpty()) remotes.add(remote)
                continue
            }
            if (key == "proto") {
                val net = VpnFileImport.network(value)
                if (net.isNotEmpty()) network = net
                else if (value.endsWith("-server")) fatal = vtr(R.string.validation_ovpn_note_server_transport, value)
                else notes.add(vtr(R.string.validation_ovpn_note_unknown_transport, value))
                continue
            }
            if (key == "port" || key == "rport") {
                if (VpnFileImport.accept(key, value, Validation.port(value), notes)) defaultPort = QtStrings.toInt(value)
                continue
            }
            if (key == "remote-random") {
                remote_random = true
                continue
            }

            if (key == "ifconfig" && args.size > 2) {
                ifconfigLocal = args[1]
                ifconfigSecond = args[2]
                continue
            }
            if (key == "ifconfig-ipv6" && args.size > 1) {
                if (VpnFileImport.normalizePrefix(args[1]).isNotEmpty()) address.add(args[1])
                else notes.add(vtr(R.string.validation_ovpn_note_ipv6_address, args[1]))
                if (args.size > 2) peer_address_ipv6 = args[2]
                continue
            }
            if (key == "topology") {
                topology = value
                continue
            }
            if (key == "auth-retry") {
                auth_retry = value
                continue
            }
            if (key == "auth-user-pass") {
                if (value.isNotEmpty() && value != "[inline]") {
                    notes.add(vtr(R.string.validation_ovpn_note_credentials_file, value))
                }
                continue
            }
            // OpenVPN 3 reads both as a client-side "log in without a client certificate"; 2.x has no such switch.
            if (key == "client-cert-not-required") {
                clientCertSwitch = key
                continue
            }
            if (key == "setenv") {
                val setting = if (args.size > 2) args[2] else ""
                if (value == "CLIENT_CERT" && setting == "0") clientCertSwitch = "setenv CLIENT_CERT 0"
                else if (value == "FRIENDLY_NAME" && setting.isNotEmpty()) friendlyName = setting
                continue
            }
            if (key == "static-challenge") {
                static_challenge = value
                if (args.size > 2) static_challenge_echo = args[2] == "1"
                continue
            }
            if (key == "key-direction") {
                keyDirection = VpnFileImport.keyDirection(value)
                continue
            }
            if (key == "secret") {
                mode = "static_key"
                if (value.isNotEmpty() && value != "[inline]") static_key_path = value
                if (args.size > 2) keyDirection = VpnFileImport.keyDirection(args[2])
                continue
            }

            if (key == "ca" || key == "cert" || key == "key" || key == "crl-verify") {
                if (value.isEmpty() || value == "[inline]") continue
                when (key) {
                    "ca" -> tls.certificate_path = value
                    "cert" -> tls.client_certificate_path = value
                    "key" -> tls.client_key_path = value
                    else -> {
                        tls.crl_path = value
                        if (args.size > 2 && args[2] == "dir") {
                            notes.add(vtr(R.string.validation_ovpn_note_crl_dir))
                        }
                    }
                }
                continue
            }
            if (key == "tls-auth" || key == "tls-crypt" || key == "tls-crypt-v2") {
                tls.control_wrap.type = key.replace('-', '_')
                if (value.isNotEmpty() && value != "[inline]") tls.control_wrap.key_path = value
                if (key == "tls-auth" && args.size > 2) keyDirection = VpnFileImport.keyDirection(args[2])
                continue
            }
            if (key == "peer-fingerprint" || key == "verify-hash") {
                val hex = VpnFileImport.fingerprint(value)
                if (hex.isNotEmpty()) tls.peer_fingerprint.add(hex)
                else notes.add(vtr(R.string.validation_ovpn_note_fingerprint, value))
                continue
            }
            if (key == "remote-cert-tls") {
                tls.remote_certificate_tls = value
                continue
            }
            if (key == "remote-cert-ku") {
                for (i in 1 until args.size) tls.remote_certificate_ku.add(args[i])
                continue
            }
            if (key == "remote-cert-eku") {
                tls.remote_certificate_eku = value
                continue
            }
            if (key == "ns-cert-type") {
                tls.ns_certificate_type = value
                continue
            }
            if (key == "verify-x509-name") {
                tls.server_name = value
                tls.server_name_type = if (args.size > 2) args[2] else "subject"
                continue
            }
            if (key == "tls-version-min" || key == "tls-version-max") {
                if (key == "tls-version-min") tls.version_min = value else tls.version_max = value
                continue
            }
            // Without them the endpoint's default suites and groups apply.
            if (key == "tls-cipher") {
                val suites = OpenVpnChecks.openVpnNormalizeTlsCipher(value)
                if (VpnFileImport.accept(key, value, OpenVpnChecks.openVpnTlsCipher(suites), notes)) tls.cipher = suites
                continue
            }
            if (key == "tls-groups") {
                val groups = value.trim().uppercase()
                if (VpnFileImport.accept(key, value, OpenVpnChecks.openVpnTlsGroups(groups), notes)) tls.groups = groups
                continue
            }
            if (key == "tls-cert-profile") {
                tls.certificate_profile = value
                continue
            }

            if (key == "cipher") {
                legacyCipher = value.trim().uppercase()
                continue
            }
            if (key == "data-ciphers" || key == "ncp-ciphers") {
                data_ciphers = VpnFileImport.dataCiphers(key, value, notes)
                continue
            }
            if (key == "data-ciphers-fallback") {
                val fallback = value.trim().uppercase()
                if (VpnFileImport.accept(key, value, OpenVpnChecks.openVpnCipherName(fallback), notes)) data_ciphers_fallback = fallback
                continue
            }
            if (key == "auth") {
                auth = value.trim().uppercase()
                continue
            }
            if (key == "mssfix") {
                if (value.isEmpty()) continue
                if (value == "0") {
                    mss_fix_disabled = true
                } else if (VpnFileImport.accept(key, value, Validation.int(value, 0, 65535), notes)) {
                    mss_fix = QtStrings.toInt(value)
                    if (args.size > 2 && (args[2] == "mtu" || args[2] == "fixed")) mss_fix_mode = args[2]
                }
                continue
            }
            if (key == "fragment") {
                if (VpnFileImport.accept(key, value, OpenVpnChecks.openVpnFragment(value), notes)) fragment = QtStrings.toInt(value)
                continue
            }
            if (key == "replay-window") {
                if (VpnFileImport.accept(key, value, OpenVpnChecks.openVpnReplayWindow(value), notes)) replay_window = QtStrings.toInt(value)
                if (args.size > 2) {
                    val window = VpnFileImport.duration(key, args[2], notes)
                    if (VpnFileImport.accept(key, args[2], OpenVpnChecks.openVpnReplayWindowTime(window), notes)) replay_window_time = window
                }
                continue
            }
            if (key == "compress") {
                val compressMode = value.lowercase()
                // The endpoint spells `compress lzo` as the identical comp-lzo mode.
                if (compressMode == "lzo") compression_lzo = "yes"
                else if (compressMode.isEmpty()) compression = "stub"
                else if (compressMode == "migrate") compression = "none"
                else compression = compressMode
                continue
            }
            if (key == "comp-lzo") {
                compression_lzo = if (value.isEmpty()) "adaptive" else value.lowercase()
                continue
            }
            if (key == "allow-compression") {
                allow_compression = value.lowercase()
                continue
            }

            if (key == "route-nopull") {
                route_no_pull = true
                continue
            }
            if (key == "pull-filter" && args.size > 2) {
                val filter = PullFilter()
                filter.action = args[1]
                filter.text = args[2]
                pull_filters.add(filter)
                continue
            }
            if (key == "route" && args.size > 1) {
                if (VpnFileImport.routeKeyword(args[1])) {
                    notes.add(vtr(R.string.validation_ovpn_note_route_symbolic, args[1]))
                    continue
                }
                val prefix: String
                var next = 2
                if (args[1].contains('/')) {
                    prefix = VpnFileImport.normalizePrefix(args[1])
                } else if (args.size > 2 && !VpnFileImport.routeKeyword(args[2]) && VpnFileImport.prefix(args[1], args[2]).isNotEmpty()) {
                    prefix = VpnFileImport.prefix(args[1], args[2])
                    next = 3
                } else {
                    prefix = VpnFileImport.prefix(args[1], "255.255.255.255")
                }
                if (prefix.isEmpty()) {
                    notes.add(vtr(R.string.validation_ovpn_note_route_unreadable, args.subList(1, args.size).joinToString(" ")))
                    continue
                }
                if (prefix !in routes) routes.add(prefix)
                // OpenVPN also takes a host name here; the endpoint needs an address.
                if (args.size > next && !VpnFileImport.routeKeyword(args[next]) && route_gateway.isEmpty() &&
                    VpnFileImport.accept(key, args[next], Validation.ip(args[next]), notes)
                ) {
                    route_gateway = args[next]
                }
                if (args.size > next + 1 && route_metric == 0 &&
                    VpnFileImport.accept(key, args[next + 1], Validation.int(args[next + 1], 0, Int.MAX_VALUE.toLong()), notes)
                ) {
                    route_metric = QtStrings.toInt(args[next + 1])
                }
                continue
            }
            if (key == "route-ipv6" && args.size > 1) {
                val prefix = VpnFileImport.normalizePrefix(args[1])
                if (prefix.isNotEmpty()) {
                    if (prefix !in routes) routes.add(prefix)
                } else {
                    notes.add(vtr(R.string.validation_ovpn_note_route_unreadable, args.subList(1, args.size).joinToString(" ")))
                }
                continue
            }
            if (key == "route-gateway") {
                if (!VpnFileImport.routeKeyword(value) &&
                    VpnFileImport.accept(key, value, Validation.ip(value, Family.Any, Empty.Allowed), notes)
                ) {
                    route_gateway = value
                }
                continue
            }
            if (key == "route-metric") {
                if (VpnFileImport.accept(key, value, Validation.int(value, 0, Int.MAX_VALUE.toLong()), notes)) route_metric = QtStrings.toInt(value)
                continue
            }
            if (key == "redirect-gateway" || key == "redirect-private") {
                if (key == "redirect-gateway") redirect_gateway = true
                else redirect_private = true
                for (i in 1 until args.size) {
                    val flag = args[i].lowercase()
                    // Host-route exceptions for the DHCP and DNS servers; Throne routes per connection, so they never apply.
                    if (flag == "bypass-dhcp" || flag == "bypass-dns") continue
                    if (flag == "block-local") {
                        notes.add(vtr(R.string.validation_ovpn_note_redirect_flag, flag))
                        continue
                    }
                    if (flag !in redirect_gateway_flags) redirect_gateway_flags.add(flag)
                }
                continue
            }
            if (key == "block-ipv6") {
                block_ipv6 = true
                continue
            }

            if (key == "keepalive" && args.size > 2) {
                ping_interval = VpnFileImport.duration(key, args[1], notes)
                ping_restart = VpnFileImport.duration(key, args[2], notes)
                continue
            }
            if (key == "ping") {
                ping_interval = VpnFileImport.duration(key, value, notes)
                continue
            }
            if (key == "ping-restart") {
                if (value == "0") ping_restart_disabled = true
                else ping_restart = VpnFileImport.duration(key, value, notes)
                continue
            }
            if (key == "reneg-sec") {
                if (value == "0") renegotiate_disabled = true
                else renegotiate_interval = VpnFileImport.duration(key, value, notes)
                continue
            }
            if (key == "reneg-bytes") {
                if (VpnFileImport.accept(key, value, Validation.int(value, 0, Long.MAX_VALUE), notes)) renegotiate_bytes = QtStrings.toLong(value)
                continue
            }
            if (key == "reneg-pkts") {
                if (VpnFileImport.accept(key, value, Validation.int(value, 0, Long.MAX_VALUE), notes)) renegotiate_packets = QtStrings.toLong(value)
                continue
            }
            if (key == "tls-timeout") {
                tls_timeout = VpnFileImport.duration(key, value, notes)
                continue
            }
            if (key == "hand-window") {
                handshake_window = VpnFileImport.duration(key, value, notes)
                continue
            }
            if (key == "explicit-exit-notify") {
                if (value.isEmpty()) {
                    explicit_exit_notify = 1
                } else if (VpnFileImport.accept(key, value, Validation.int(value, 0, Int.MAX_VALUE.toLong()), notes)) {
                    explicit_exit_notify = QtStrings.toInt(value)
                }
                continue
            }
            if (key == "tun-mtu") {
                if (VpnFileImport.accept(key, value, Validation.int(value, 0, 65535), notes)) mtu = QtStrings.toInt(value)
                continue
            }

            if (key in VpnFileImport.OVPN_IGNORED) continue
            if (key in VpnFileImport.OVPN_UNSUPPORTED) {
                notes.add(vtr(R.string.validation_ovpn_note_unsupported, key))
                continue
            }
            notes.add(vtr(R.string.validation_ovpn_note_unknown, key))
        }

        if (fatal.isNotEmpty()) {
            notes.add(fatal)
            flush()
            return false
        }

        if (clientCertSwitch.isNotEmpty()) {
            val dropped = tls.client_certificate.isNotEmpty() || tls.client_certificate_path.isNotEmpty() ||
                tls.client_key.isNotEmpty() || tls.client_key_path.isNotEmpty()
            tls.client_certificate.clear()
            tls.client_certificate_path = ""
            tls.client_key.clear()
            tls.client_key_path = ""
            if (dropped) {
                notes.add(vtr(R.string.validation_ovpn_note_client_cert_off, clientCertSwitch))
            }
        }
        // The core, like OpenVPN 2.x, refuses a lone certificate or key; OpenVPN 3 only tolerates it behind the switch.
        val hasClientCert = tls.client_certificate.isNotEmpty() || tls.client_certificate_path.isNotEmpty()
        val hasClientKey = tls.client_key.isNotEmpty() || tls.client_key_path.isNotEmpty()
        if (hasClientCert != hasClientKey) {
            notes.add(
                if (hasClientCert) vtr(R.string.validation_ovpn_note_cert_without_key)
                else vtr(R.string.validation_ovpn_note_key_without_cert)
            )
            flush()
            return false
        }

        for (remote in remotes) {
            if (remote.port == 0) remote.port = if (defaultPort > 0) defaultPort else VpnFileImport.OVPN_DEFAULT_PORT
        }
        if (remotes.isEmpty()) {
            notes.add(vtr(R.string.validation_ovpn_note_no_remote))
            flush()
            return false
        }
        // `server`/`server_port` and `servers` conflict in the core.
        if (remotes.size == 1) {
            server = remotes[0].host
            serverPort = remotes[0].port
            if (remotes[0].network.isNotEmpty()) network = remotes[0].network
        } else {
            server = ""
            serverPort = 0
            for (remote in remotes) {
                val entry = Remote()
                entry.server = remote.host
                entry.server_port = remote.port
                entry.network = remote.network
                servers.add(entry)
            }
        }
        if (friendlyName.isNotEmpty()) name = friendlyName
        if (name.isEmpty()) name = remotes[0].host

        if (ifconfigLocal.isNotEmpty() && ifconfigSecond.isNotEmpty()) {
            // Only `--topology subnet` makes the second argument a netmask.
            val masked = topology == "subnet" || ifconfigSecond.startsWith("255.")
            val parsed = VpnFileImport.parseSubnet(ifconfigLocal + "/" + (if (masked) ifconfigSecond else "32"))
            if (parsed != null) address.add(ifconfigLocal + "/" + parsed.second)
            else notes.add(vtr(R.string.validation_ovpn_note_interface_address, ifconfigLocal))
            if (!masked) peer_address = ifconfigSecond
        }

        // `key-direction` binds to the control-channel wrap in TLS mode, the secret otherwise.
        if (keyDirection.isNotEmpty()) {
            if (tls.control_wrap.type == "tls_auth") tls.control_wrap.direction = keyDirection
            else if (mode == "static_key") key_direction = keyDirection
        }
        // Outside static-key mode `cipher` is the pre-negotiation data cipher.
        if (legacyCipher.isNotEmpty()) {
            if (mode == "static_key") cipher = legacyCipher
            else if (data_ciphers_fallback.isEmpty() &&
                VpnFileImport.accept("cipher", legacyCipher, OpenVpnChecks.openVpnCipherName(legacyCipher), notes)
            ) {
                data_ciphers_fallback = legacyCipher
            }
        }
        if (tls.remote_certificate_eku.isNotEmpty() && tls.remote_certificate_tls.isNotEmpty()) {
            notes.add(vtr(R.string.validation_ovpn_note_eku_replaces))
            tls.remote_certificate_tls = ""
        }
        // A redirect-gateway config is a full tunnel, not a management network.
        if (redirect_gateway && !route_no_pull) only_advertised_routes = false

        // Every inline value conflicts with its own *_path in the core.
        if (tls.certificate.isNotEmpty()) tls.certificate_path = ""
        if (tls.client_certificate.isNotEmpty()) tls.client_certificate_path = ""
        if (tls.client_key.isNotEmpty()) tls.client_key_path = ""
        if (tls.control_wrap.key.isNotEmpty()) tls.control_wrap.key_path = ""
        if (static_key.isNotEmpty()) static_key_path = ""

        flush()
        return true
    }
}

/** ovpnKeep (openvpn.cpp): only an import drops an optional value the core would refuse, with a warning; DB loads and clones keep it. */
private fun ovpnKeep(field: String, message: String, value: String): Boolean = !ParseDiag.active() || acceptOptional(field, message, value)

/** ovpnReadChecked (openvpn.cpp): the trimmed string at [key], or [current] when it is absent or the import drops it. */
private fun ovpnReadChecked(obj: JsonObject, key: String, field: String, current: String, rule: Rule): String {
    if (!obj.contains(key)) return current
    val value = obj.string(key).trim()
    return if (ovpnKeep(field, rule(value), value)) value else current
}

/** ovpnKeepItems (openvpn.cpp): the trimmed, non-empty items the import keeps. */
private fun ovpnKeepItems(field: String, items: List<String>, rule: Rule): MutableList<String> {
    val kept = ArrayList<String>()
    for (raw in items) {
        val item = raw.trim()
        if (item.isNotEmpty() && ovpnKeep(field, rule(item), item)) kept.add(item)
    }
    return kept
}

/** The Checks of openvpn.h: per-field rules for the OpenVPN editors and the import check; profile checks read the saved fields. */
object OpenVpnChecks {
    private val CBC_CIPHERS = listOf(
        "BF-CBC", "DES-CBC", "DES-EDE-CBC", "DES-EDE3-CBC", "CAST5-CBC", "AES-128-CBC", "AES-192-CBC", "AES-256-CBC",
        "ARIA-128-CBC", "ARIA-192-CBC", "ARIA-256-CBC", "CAMELLIA-128-CBC", "CAMELLIA-192-CBC", "CAMELLIA-256-CBC",
        "SEED-CBC", "SM4-CBC",
    )
    private val AEAD_CIPHERS = listOf("AES-128-GCM", "AES-192-GCM", "AES-256-GCM", "CHACHA20-POLY1305")
    private val DIGESTS = listOf("SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "RIPEMD160", "MD5", "NONE")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val EKU_NAMES = setOf(
        "server", "client", "TLS Web Server Authentication", "TLS Web Client Authentication", "Code Signing",
        "E-mail Protection", "IPSec End System", "IPSec Tunnel", "IPSec User", "Time Stamping", "OCSP Signing",
        "Any Extended Key Usage", "Microsoft Server Gated Crypto", "Netscape Server Gated Crypto",
        "Microsoft Commercial Code Signing", "Microsoft Individual Code Signing",
    )
    private val TLS_CIPHERS = setOf(
        "RC4-SHA", "DES-CBC3-SHA", "AES128-SHA", "AES256-SHA", "AES128-SHA256", "AES128-GCM-SHA256",
        "AES256-GCM-SHA384", "ECDHE-ECDSA-AES128-SHA", "ECDHE-ECDSA-AES256-SHA", "ECDHE-ECDSA-RC4-SHA",
        "ECDHE-RSA-RC4-SHA", "ECDHE-RSA-DES-CBC3-SHA", "ECDHE-RSA-AES128-SHA", "ECDHE-RSA-AES256-SHA",
        "ECDHE-ECDSA-AES128-SHA256", "ECDHE-RSA-AES128-SHA256", "ECDHE-ECDSA-AES128-GCM-SHA256",
        "ECDHE-RSA-AES128-GCM-SHA256", "ECDHE-ECDSA-AES256-GCM-SHA384", "ECDHE-RSA-AES256-GCM-SHA384",
        "ECDHE-ECDSA-CHACHA20-POLY1305", "ECDHE-RSA-CHACHA20-POLY1305",
        "TLS_RSA_WITH_RC4_128_SHA", "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", "TLS_ECDHE_RSA_WITH_RC4_128_SHA", "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
    )
    private val TLS_GROUPS = setOf(
        "X25519", "CURVE25519", "SECP256R1", "PRIME256V1", "P-256", "NISTP256",
        "SECP384R1", "P-384", "NISTP384", "SECP521R1", "P-521", "NISTP521",
    )
    private val TLS_VERSIONS = listOf("1.0", "1.1", "1.2", "1.3")
    private val WRAP_TYPES = listOf("tls_auth", "tls_crypt", "tls_crypt_v2")

    /** sing-openvpn staticCipherKeySize. */
    private fun staticCipher(name: String) = name == "NONE" || name in CBC_CIPHERS

    /** sing-openvpn validateDataCipherName: the static ciphers, the AEADs and the -CFB/-OFB form of a CBC cipher. */
    private fun dataCipher(name: String): Boolean {
        if (staticCipher(name) || name in AEAD_CIPHERS) return true
        if (name.endsWith("-CFB") || name.endsWith("-OFB")) return (name.dropLast(4) + "-CBC") in CBC_CIPHERS
        return false
    }

    private fun durationNs(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0L
        return Validation.parseDurationNs(trimmed)
    }

    /** An unparsable value counts as set: the field's own rule reports it. */
    private fun durationSet(text: String): Boolean = (durationNs(text) ?: 1L) != 0L

    /** sing-openvpn validateDurationOption, plus validatePingDurationOption's whole seconds. */
    private fun durationRule(text: String, wholeSeconds: Boolean): String {
        Validation.duration(text, Validation.DurationRule(wholeSeconds = wholeSeconds)).let { if (it.isNotEmpty()) return it }
        val ns = durationNs(text) ?: 0L
        if (ns > 0L && ns < 1_000_000_000L) return vtr(R.string.validation_ovpn_interval_min)
        return ""
    }

    private fun staticMode(profile: OpenVpn) = profile.mode == "static_key"

    private fun pullFiltersSet(profile: OpenVpn) = profile.pull_filters.any { it.action.isNotEmpty() || it.text.isNotEmpty() }

    /** Names are checked upper-cased, as the editors save them and the build emits them. */
    @JvmStatic
    fun openVpnCipherName(name: String): String {
        val upper = name.trim().uppercase()
        if (upper.isEmpty() || dataCipher(upper)) return ""
        if (upper.contains(':')) return vtr(R.string.validation_ovpn_cipher_colons)
        return vtr(R.string.validation_ovpn_cipher_name)
    }

    @JvmStatic
    fun openVpnDigest(name: String): String = Validation.oneOf(name.trim().uppercase(), DIGESTS)

    /** Colons and spaces removed, lower-cased. */
    @JvmStatic
    fun openVpnNormalizeFingerprint(line: String): String {
        val hex = StringBuilder()
        for (c in line) if (c != ':' && !VpnFileImport.isSpace(c)) hex.append(c.lowercaseChar())
        return hex.toString()
    }

    @JvmStatic
    fun openVpnFingerprint(line: String): String {
        val hex = openVpnNormalizeFingerprint(line)
        if (hex.isEmpty() || SHA256.matches(hex)) return ""
        return vtr(R.string.validation_ovpn_fingerprint)
    }

    /** strconv.ParseUint(value, 16, 64), as --remote-cert-ku reads it. */
    @JvmStatic
    fun openVpnKeyUsage(item: String): String {
        if (Validation.hex(item, 1, 16, false).isEmpty()) return ""
        return vtr(R.string.validation_ovpn_key_usage)
    }

    /** sing-openvpn parseRemoteCertExtKeyUsage: an OpenSSL purpose name or a dotted OID. */
    @JvmStatic
    fun openVpnExtendedKeyUsage(text: String): String {
        val value = text.trim()
        if (value.isEmpty() || value in EKU_NAMES) return ""
        val parts = value.split('.')
        val oid = parts.size >= 2 && parts.all { part -> part.isNotEmpty() && part.length <= 9 && part.all { it in '0'..'9' } }
        return if (oid) "" else vtr(R.string.validation_ovpn_eku)
    }

    /** OpenVPN's TLS-ECDHE-RSA-WITH-... spelling becomes the TLS_ECDHE_RSA_WITH_... name the core knows. */
    @JvmStatic
    fun openVpnNormalizeTlsCipher(text: String): String = text.trim().split(':').joinToString(":") { token ->
        if (token.startsWith("TLS-", ignoreCase = true) && token.contains("-WITH-", ignoreCase = true)) {
            token.uppercase().replace('-', '_')
        } else {
            token
        }
    }

    /** sing-openvpn parseTLSCipherSuites: its OpenSSL table or Go's name for the same suites. */
    @JvmStatic
    fun openVpnTlsCipher(text: String): String {
        val value = openVpnNormalizeTlsCipher(text)
        if (value.isEmpty()) return ""
        for (token in value.split(':')) {
            if (token.isEmpty()) return vtr(R.string.validation_ovpn_empty_entry)
            if (token !in TLS_CIPHERS) return vtr(R.string.validation_ovpn_tls_cipher, token)
        }
        return ""
    }

    /** sing-openvpn parseTLSGroups, after the upper-casing the build applies. */
    @JvmStatic
    fun openVpnTlsGroups(text: String): String {
        val value = text.trim().uppercase()
        if (value.isEmpty()) return ""
        for (token in value.split(':')) {
            if (token.isEmpty()) return vtr(R.string.validation_ovpn_empty_entry)
            if (token !in TLS_GROUPS) return vtr(R.string.validation_ovpn_tls_group, token)
        }
        return ""
    }

    /** Renegotiation, TLS timeout and handshake window: 0 or at least 1s. */
    @JvmStatic
    fun openVpnInterval(text: String): String = durationRule(text, false)

    /** Ping interval and restart: the same, in whole seconds. */
    @JvmStatic
    fun openVpnPingInterval(text: String): String = durationRule(text, true)

    @JvmStatic
    fun openVpnReplayWindowTime(text: String): String =
        Validation.duration(text, Validation.DurationRule(wholeSeconds = true, maxNs = 600L * 1_000_000_000L))

    @JvmStatic
    fun openVpnFragment(text: String): String {
        Validation.int(text, 0, 65535).let { if (it.isNotEmpty()) return it }
        val value = Validation.toInt(text) ?: 0L
        if (value > 0L && value < 68L) return vtr(R.string.validation_ovpn_fragment)
        return ""
    }

    @JvmStatic
    fun openVpnReplayWindow(text: String): String = Validation.int(text, 0, 65536)

    /** sing-openvpn resolveClientRemotes refuses whitespace and brackets. */
    @JvmStatic
    fun openVpnRemoteHost(text: String): String {
        val host = text.trim()
        if (host.startsWith('[') || host.endsWith(']')) return vtr(R.string.validation_ovpn_remote_brackets)
        return Validation.host(host)
    }

    @JvmStatic
    fun openVpnRemotePort(text: String): String = Validation.port(text)

    /** JSON keys of the options the profile's mode refuses (static_key: TLS and authentication; tls: the static key). */
    @JvmStatic
    fun openVpnModeConflicts(profile: OpenVpn): List<String> {
        val keys = ArrayList<String>()
        fun add(set: Boolean, key: String) {
            if (set) keys.add(key)
        }
        if (!staticMode(profile)) {
            add(profile.static_key.isNotEmpty(), "static_key")
            add(profile.static_key_path.isNotEmpty(), "static_key_path")
            add(profile.key_direction.isNotEmpty(), "key_direction")
            add(profile.cipher.isNotEmpty(), "cipher")
            return keys
        }
        // sing-box buildStaticKeyClientOptions and sing-openvpn validateImplementedClientOptions.
        val tls = profile.tls
        add(profile.username.isNotEmpty(), "username")
        add(profile.password.isNotEmpty(), "password")
        add(profile.auth_retry.isNotEmpty() && profile.auth_retry != "none", "auth_retry")
        add(profile.static_challenge.isNotEmpty(), "static_challenge")
        add(profile.static_challenge_echo, "static_challenge_echo")
        add(profile.route_no_pull, "route_no_pull")
        add(pullFiltersSet(profile), "pull_filters")
        add(durationSet(profile.renegotiate_interval), "renegotiate_interval")
        add(profile.renegotiate_disabled, "renegotiate_disabled")
        add(profile.renegotiate_bytes > 0, "renegotiate_bytes")
        add(profile.renegotiate_packets > 0, "renegotiate_packets")
        add(durationSet(profile.tls_timeout), "tls_timeout")
        add(durationSet(profile.handshake_window), "handshake_window")
        add(profile.data_ciphers.isNotEmpty(), "data_ciphers")
        add(profile.data_ciphers_fallback.isNotEmpty(), "data_ciphers_fallback")
        add(tls.server_name.isNotEmpty(), "tls.server_name")
        add(tls.server_name_type.isNotEmpty(), "tls.server_name_type")
        add(tls.certificate.isNotEmpty(), "tls.certificate")
        add(tls.certificate_path.isNotEmpty(), "tls.certificate_path")
        add(tls.client_certificate.isNotEmpty(), "tls.client_certificate")
        add(tls.client_certificate_path.isNotEmpty(), "tls.client_certificate_path")
        add(tls.client_key.isNotEmpty(), "tls.client_key")
        add(tls.client_key_path.isNotEmpty(), "tls.client_key_path")
        add(tls.peer_fingerprint.isNotEmpty(), "tls.peer_fingerprint")
        add(tls.crl_path.isNotEmpty(), "tls.crl_path")
        add(tls.remote_certificate_ku.isNotEmpty(), "tls.remote_certificate_ku")
        add(tls.remote_certificate_eku.isNotEmpty(), "tls.remote_certificate_eku")
        add(tls.remote_certificate_tls.isNotEmpty(), "tls.remote_certificate_tls")
        add(tls.certificate_profile.isNotEmpty(), "tls.certificate_profile")
        add(tls.ns_certificate_type.isNotEmpty(), "tls.ns_certificate_type")
        add(tls.version_min.isNotEmpty(), "tls.version_min")
        add(tls.version_max.isNotEmpty(), "tls.version_max")
        add(tls.cipher.isNotEmpty(), "tls.cipher")
        add(tls.groups.isNotEmpty(), "tls.groups")
        add(tls.control_wrap.type.isNotEmpty(), "tls.control_wrap")
        return keys
    }

    /** The message for one key of [openVpnModeConflicts], or "" when that key is fine. */
    @JvmStatic
    fun openVpnModeConflict(profile: OpenVpn, key: String): String {
        if (key !in openVpnModeConflicts(profile)) return ""
        if (staticMode(profile)) return vtr(R.string.validation_ovpn_static_unused)
        if (key == "cipher") return vtr(R.string.validation_ovpn_cipher_static_only)
        if (key == "key_direction") return vtr(R.string.validation_ovpn_key_direction_static_only)
        return vtr(R.string.validation_ovpn_static_key_static_only)
    }

    @JvmStatic
    fun openVpnStaticKeyMissing(profile: OpenVpn): String {
        if (!staticMode(profile) || profile.static_key.isNotEmpty() || profile.static_key_path.isNotEmpty()) return ""
        return vtr(R.string.validation_ovpn_static_key_missing)
    }

    @JvmStatic
    fun openVpnStaticCipher(profile: OpenVpn): String {
        if (!staticMode(profile)) return ""
        val cipher = profile.cipher.trim().uppercase()
        if (cipher.isEmpty()) return vtr(R.string.validation_ovpn_static_cipher_missing)
        if (dataCipher(cipher) && !staticCipher(cipher)) return vtr(R.string.validation_ovpn_static_cipher_cbc)
        return ""
    }

    @JvmStatic
    fun openVpnStaticAddress(profile: OpenVpn): String {
        if (!staticMode(profile)) return ""
        if (profile.address.isEmpty()) return vtr(R.string.validation_ovpn_static_address_missing)
        var v4 = false
        var v6 = false
        for (item in profile.address) {
            // A malformed item is reported by its own rule.
            if (Validation.cidr(item).isNotEmpty()) return ""
            if (item.contains(':')) v6 = true else v4 = true
        }
        val peer4 = profile.peer_address.trim().isNotEmpty()
        val peer6 = profile.peer_address_ipv6.trim().isNotEmpty()
        if (v4 && !peer4) return vtr(R.string.validation_ovpn_static_peer4_missing)
        if (v6 && !peer6) return vtr(R.string.validation_ovpn_static_peer6_missing)
        if (peer4 && !v4) return vtr(R.string.validation_ovpn_static_address4_missing)
        if (peer6 && !v6) return vtr(R.string.validation_ovpn_static_address6_missing)
        return ""
    }

    /** sing-openvpn requireCAOrPeerFingerprint runs at session start, so a bare client pair still fails. */
    @JvmStatic
    fun openVpnTlsVerification(profile: OpenVpn): String {
        if (staticMode(profile)) return ""
        val tls = profile.tls
        if (tls.certificate.isNotEmpty() || tls.certificate_path.trim().isNotEmpty() || tls.peer_fingerprint.isNotEmpty()) return ""
        return vtr(R.string.validation_ovpn_tls_verification)
    }

    @JvmStatic
    fun openVpnClientPair(profile: OpenVpn): String {
        if (staticMode(profile)) return ""
        val tls = profile.tls
        val certificate = tls.client_certificate.isNotEmpty() || tls.client_certificate_path.isNotEmpty()
        val key = tls.client_key.isNotEmpty() || tls.client_key_path.isNotEmpty()
        if (certificate == key) return ""
        return vtr(if (certificate) R.string.validation_ovpn_client_key_missing else R.string.validation_ovpn_client_certificate_missing)
    }

    /** The same material pasted and as a file path, which the core refuses; OpenConnect uses it too. */
    @JvmStatic
    fun vpnMaterialConflict(inlineValue: List<String>, path: String): String {
        if (inlineValue.isEmpty() || path.trim().isEmpty()) return ""
        return vtr(R.string.validation_ovpn_material_conflict)
    }

    @JvmStatic
    fun openVpnControlWrapKey(profile: OpenVpn): String {
        val wrap = profile.tls.control_wrap
        val key = wrap.key.isNotEmpty() || wrap.key_path.trim().isNotEmpty()
        if (wrap.type.isEmpty()) {
            if (!key) return ""
            if (staticMode(profile)) return vtr(R.string.validation_ovpn_wrap_static)
            return vtr(R.string.validation_ovpn_wrap_type_missing)
        }
        Validation.oneOf(wrap.type, WRAP_TYPES).let { if (it.isNotEmpty()) return it }
        if (!key) return vtr(R.string.validation_ovpn_wrap_key_missing)
        return ""
    }

    @JvmStatic
    fun openVpnControlWrapDirection(profile: OpenVpn): String {
        val wrap = profile.tls.control_wrap
        if (wrap.direction.isEmpty()) return ""
        Validation.oneOf(wrap.direction, listOf("server", "client")).let { if (it.isNotEmpty()) return it }
        if (wrap.type != "tls_auth") return vtr(R.string.validation_ovpn_wrap_direction)
        return ""
    }

    @JvmStatic
    fun openVpnRemoteCertificate(profile: OpenVpn): String {
        val tls = profile.tls
        if (tls.remote_certificate_eku.trim().isEmpty() || tls.remote_certificate_tls.isEmpty()) return ""
        return vtr(R.string.validation_ovpn_remote_certificate)
    }

    /** sing-openvpn resolveTLSVersionBounds: an unset minimum is 1.2. */
    @JvmStatic
    fun openVpnTlsVersions(profile: OpenVpn): String {
        val tls = profile.tls
        val min = TLS_VERSIONS.indexOf(tls.version_min.ifEmpty { "1.2" })
        val max = TLS_VERSIONS.indexOf(tls.version_max)
        if (min < 0 || max < 0 || max >= min) return ""
        return vtr(R.string.validation_ovpn_tls_versions)
    }

    @JvmStatic
    fun openVpnMssFix(profile: OpenVpn): String {
        if (profile.mss_fix_disabled && profile.mss_fix > 0) return vtr(R.string.validation_ovpn_mss_value_disabled)
        if (profile.mss_fix_disabled && profile.mss_fix_mode.isNotEmpty()) return vtr(R.string.validation_ovpn_mss_mode_disabled)
        if (profile.mss_fix <= 0 && profile.mss_fix_mode.isNotEmpty()) return vtr(R.string.validation_ovpn_mss_mode_value)
        return ""
    }

    @JvmStatic
    fun openVpnPingRestart(profile: OpenVpn): String {
        if (!profile.ping_restart_disabled || (durationNs(profile.ping_restart) ?: 0L) <= 0L) return ""
        return vtr(R.string.validation_ovpn_ping_restart_disabled)
    }

    @JvmStatic
    fun openVpnRenegotiate(profile: OpenVpn): String {
        if (!profile.renegotiate_disabled || (durationNs(profile.renegotiate_interval) ?: 0L) <= 0L) return ""
        return vtr(R.string.validation_ovpn_renegotiate_disabled)
    }
}
