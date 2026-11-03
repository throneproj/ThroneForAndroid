package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.DnsTunnelChecks
import io.nekohasekai.sagernet.outbound.common.DnsTunnels
import io.nekohasekai.sagernet.outbound.common.DnstLink
import io.nekohasekai.sagernet.outbound.common.ResolverChecks
import io.nekohasekai.sagernet.outbound.common.ResolverFlavor
import io.nekohasekai.sagernet.outbound.common.ResolverSource
import io.nekohasekai.sagernet.outbound.common.ResolverSources
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** dnsttModes (dnstt.h). */
val dnsttModes = listOf("dnstt", "vaydns")

/** dnsttRecordTypes (dnstt.h). */
val dnsttRecordTypes = listOf("", "txt", "null", "cname", "a", "aaaa", "mx", "ns", "srv", "caa")

/** dnsttResolverStrategies (dnstt.h). */
val dnsttResolverStrategies = listOf("", "round_robin", "failover", "fanout")

/** dnsttBackends (dnstt.h): "" leaves the server's own stream to the next profile of a chain. */
val dnsttBackends = listOf("", "socks", "shadowsocks", "ssh")

/** DnsttDisplayType (dnstt.h). */
fun dnsttDisplayType(mode: String): String = if (mode == "vaydns") "VayDNS" else "DNSTT"

/** The Checks of dnstt.h. */
object DnsttChecks {
    const val MIN_MTU = 25

    // The core's defaults: dnstt and VayDNS's compat mode, then native VayDNS.
    const val DNSTT_IDLE_NS = 120_000_000_000L
    const val DNSTT_KEEPALIVE_NS = 10_000_000_000L
    const val VAYDNS_IDLE_NS = 10_000_000_000L
    const val VAYDNS_KEEPALIVE_NS = 2_000_000_000L

    private val DECIMAL = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")

    @JvmField
    val positiveDuration = Validation.DurationRule(minNs = 1_000_000)

    @JvmStatic
    fun vayNative(mode: String, compat: Boolean): Boolean = mode == "vaydns" && !compat

    /** QString::toDouble(&ok) on decimal text: null when Qt would report ok == false. */
    @JvmStatic
    fun parseNumber(text: String): Double? {
        val t = text.trim()
        return if (DECIMAL.matches(t)) t.toDoubleOrNull() else null
    }

    @JvmStatic
    fun dnsttPublicKey(text: String): String {
        if (Validation.hex(text.trim(), 64, 64, true, Empty.Required).isEmpty()) return ""
        return vtr(R.string.validation_dnstt_public_key)
    }

    @JvmStatic
    fun dnsttRecordType(mode: String, compat: Boolean, recordType: String): String {
        Validation.oneOf(recordType, dnsttRecordTypes).let { if (it.isNotEmpty()) return it }
        if (!vayNative(mode, compat) && recordType.isNotEmpty() && recordType != "txt") return vtr(R.string.validation_dnstt_record_type_classic)
        return ""
    }

    @JvmStatic
    fun dnsttClientIdSize(mode: String, compat: Boolean, size: Int): String {
        if (vayNative(mode, compat)) return Validation.int(size.toString(), 0, 253)
        if (size == 0 || size == 8) return ""
        return vtr(R.string.validation_dnstt_client_id_classic)
    }

    /**
     * Payload bytes per query as the core computes them, dnstt-client nameCapacity and VayDNS's QNAME budget: labels
     * cost a length byte each, base32 carries 5 bits per character. The core refuses fewer than [MIN_MTU].
     */
    @JvmStatic
    fun dnsttMtu(mode: String, compat: Boolean, domain: String, maxQnameLen: Int, clientIdSize: Int): Int {
        val name = Hosts.toAceHost(domain.trim()).trimEnd('.')
        var used = 0
        for (label in name.split('.')) if (label.isNotEmpty()) used += label.length + 1
        var capacity = 254 - used
        var overhead = 13
        if (mode == "vaydns") {
            var budget = if (compat) 253 else 101
            if (maxQnameLen > 0) budget = minOf(maxQnameLen, 253)
            capacity = maxOf(budget - used, 0)
            if (!compat) overhead = (if (clientIdSize > 0) clientIdSize else 2) + 1
        }
        return capacity * 63 / 64 * 5 / 8 - overhead
    }

    @JvmStatic
    fun dnsttMtuError(mode: String, compat: Boolean, domain: String, maxQnameLen: Int, clientIdSize: Int): String {
        val mtu = dnsttMtu(mode, compat, domain, maxQnameLen, clientIdSize)
        if (mtu >= MIN_MTU) return ""
        return vtr(R.string.validation_dnstt_mtu, maxOf(mtu, 0).toString(), MIN_MTU.toString())
    }

    @JvmStatic
    fun dnsttRps(rps: Double): String {
        if (!rps.isFinite()) return vtr(R.string.validation_must_be_number)
        if (rps < 0) return vtr(R.string.validation_not_negative)
        return ""
    }

    /** An empty value counts as the mode's default. */
    @JvmStatic
    fun dnsttKeepalive(mode: String, compat: Boolean, idleTimeout: String, keepalive: String): String {
        val vay = vayNative(mode, compat)
        val idle = if (idleTimeout.isBlank()) (if (vay) VAYDNS_IDLE_NS else DNSTT_IDLE_NS) else Validation.parseDurationNs(idleTimeout.trim())
        val alive = if (keepalive.isBlank()) (if (vay) VAYDNS_KEEPALIVE_NS else DNSTT_KEEPALIVE_NS) else Validation.parseDurationNs(keepalive.trim())
        if (idle == null || alive == null || idle <= 0 || alive <= 0 || alive < idle) return ""
        return vtr(R.string.validation_dnstt_keepalive)
    }

    /** A warning, not an error. */
    @JvmStatic
    fun dnsttChainOnly(backend: String): String = if (backend.isNotEmpty()) "" else vtr(R.string.validation_dnstt_chain_only)
}

/** QJsonValue::toInt(defaultValue): whole numbers that fit an int, else [default]. */
internal fun jsonIntOr(obj: JsonObject, key: String, default: Int): Int {
    val v = obj[key]
    return when (v) {
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else default
        is Double -> if (v == Math.rint(v) && v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) v.toInt() else default
        else -> default
    }
}

/** QString::number(double) for the issue values. */
internal fun dnsTunnelNumberText(d: Double): String = QtStrings.numberG(d)

/** dnstt (include/configs/outbounds/dnstt.h, src/configs/outbounds/dnstt.cpp): DNSTT and VayDNS. */
class Dnstt : Outbound("dnstt") {
    @JvmField var mode: String = "dnstt"

    /** VayDNS speaking the classic dnstt wire, for a stock dnstt-server. */
    @JvmField var dnstt_compat: Boolean = false
    @JvmField var domain: String = ""
    @JvmField var public_key: String = ""
    @JvmField var record_type: String = ""

    /** 0 leaves the core's default for the mode. */
    @JvmField var client_id_size: Int = 0
    @JvmField var max_qname_len: Int = 0
    @JvmField var rps: Double = 0.0
    @JvmField var idle_timeout: String = ""
    @JvmField var keepalive_interval: String = ""
    @JvmField var resolvers: ResolverSource = ResolverSource()
    @JvmField var resolver_strategy: String = ""

    /** Sets tcp_fallback on every udp resolver. */
    @JvmField var tcp_fallback: Boolean = false

    @JvmField var backend: String = ""
    @JvmField var socks_username: String = ""
    @JvmField var socks_password: String = ""
    @JvmField var socks_udp_over_tcp: Boolean = false
    @JvmField var ss_method: String = ""
    @JvmField var ss_password: String = ""
    @JvmField var ss_udp_over_tcp: Boolean = false
    @JvmField var ssh_user: String = ""
    @JvmField var ssh_password: String = ""
    @JvmField var ssh_private_key: String = ""
    @JvmField var ssh_private_key_path: String = ""
    @JvmField var ssh_private_key_passphrase: String = ""
    @JvmField var ssh_host_key: MutableList<String> = ArrayList()
    @JvmField var ssh_host_key_algorithms: MutableList<String> = ArrayList()
    @JvmField var ssh_client_version: String = ""

    override fun hasServerEndpoint(): Boolean = false

    private fun backendObject(): JsonObject {
        val obj = JsonObject()
        when (backend) {
            "socks" -> {
                if (socks_username.isNotEmpty()) obj["username"] = socks_username
                if (socks_password.isNotEmpty()) obj["password"] = socks_password
                if (socks_udp_over_tcp) obj["udp_over_tcp"] = true
            }
            "shadowsocks" -> {
                if (ss_method.isNotEmpty()) obj["method"] = ss_method
                if (ss_password.isNotEmpty()) obj["password"] = ss_password
                if (ss_udp_over_tcp) obj["udp_over_tcp"] = true
            }
            "ssh" -> {
                if (ssh_user.isNotEmpty()) obj["user"] = ssh_user
                if (ssh_password.isNotEmpty()) obj["password"] = ssh_password
                if (ssh_private_key.isNotEmpty()) obj["private_key"] = ssh_private_key
                if (ssh_private_key_path.isNotEmpty()) obj["private_key_path"] = ssh_private_key_path
                if (ssh_private_key_passphrase.isNotEmpty()) obj["private_key_passphrase"] = ssh_private_key_passphrase
                if (ssh_host_key.isNotEmpty()) obj["host_key"] = JsonValues.stringArray(ssh_host_key)
                if (ssh_host_key_algorithms.isNotEmpty()) obj["host_key_algorithms"] = JsonValues.stringArray(ssh_host_key_algorithms)
                if (ssh_client_version.isNotEmpty()) obj["client_version"] = ssh_client_version
            }
        }
        return obj
    }

    private fun writeCommon(obj: JsonObject) {
        obj["mode"] = mode.ifEmpty { "dnstt" }
        if (mode == "vaydns" && dnstt_compat) obj["dnstt_compat"] = true
        if (domain.isNotEmpty()) obj["domain"] = domain
        if (public_key.isNotEmpty()) obj["public_key"] = public_key.trim().lowercase()
        if (record_type.isNotEmpty()) obj["record_type"] = record_type
        if (client_id_size > 0) obj["client_id_size"] = client_id_size
        if (max_qname_len > 0) obj["max_qname_len"] = max_qname_len
        if (rps > 0) obj["rps"] = rps
        idle_timeout.trim().let { if (it.isNotEmpty()) obj["idle_timeout"] = it }
        keepalive_interval.trim().let { if (it.isNotEmpty()) obj["keepalive_interval"] = it }
        if (resolver_strategy.isNotEmpty()) obj["resolver_strategy"] = resolver_strategy
        if (backend.isNotEmpty() && backend in dnsttBackends) obj[backend] = backendObject()
    }

    /** dnsttReadBool: null when the text is no boolean (reported). */
    private fun readBool(field: String, text: String): Boolean? {
        val value = text.trim().lowercase()
        return when (value) {
            "true", "1", "yes" -> true
            "", "false", "0", "no" -> false
            else -> {
                acceptOptional(field, vtr(R.string.validation_use_true_false), value)
                null
            }
        }
    }

    private fun readVayKnobs(params: Map<String, String>) {
        if (params.containsKey("dnstt-compat")) readBool("dnstt_compat", params["dnstt-compat"].orEmpty())?.let { dnstt_compat = it }
        params["record-type"].orEmpty().trim().lowercase().let { text ->
            if (text.isNotEmpty() && acceptOptional("record_type", DnsttChecks.dnsttRecordType(mode, dnstt_compat, text), text)) record_type = text
        }
        params["clientid-size"].orEmpty().trim().let { text ->
            if (text.isEmpty()) return@let
            val message = Validation.int(text, 0, 253).ifEmpty {
                DnsttChecks.dnsttClientIdSize(mode, dnstt_compat, QtStrings.toInt(text))
            }
            if (acceptOptional("client_id_size", message, text)) client_id_size = QtStrings.toInt(text)
        }
        params["idle-timeout"].orEmpty().trim().let { text ->
            if (text.isNotEmpty() && acceptOptional("idle_timeout", Validation.duration(text, DnsttChecks.positiveDuration), text)) idle_timeout = text
        }
        params["keepalive"].orEmpty().trim().let { text ->
            if (text.isNotEmpty() && acceptOptional("keepalive_interval", Validation.duration(text, DnsttChecks.positiveDuration), text)) {
                keepalive_interval = text
            }
        }
    }

    private fun parseDnst(link: String): Boolean {
        val decoded = DnsTunnels.decodeLink(link)
        val parsed = decoded.link
        if (parsed == null) {
            ParseDiag.error("link", decoded.error)
            return false
        }
        if (parsed.transport !in dnsttModes) {
            ParseDiag.error("mode", vtr(R.string.validation_dns_tunnel_unsupported), parsed.transport)
            return false
        }
        val params = parsed.params
        mode = parsed.transport
        name = parsed.tag
        domain = Hosts.toAceHost(parsed.domain)
        public_key = params["pubkey"].orEmpty().trim()
        // The spec gives these knobs to VayDNS only.
        if (mode == "vaydns") readVayKnobs(params)

        val user = params["user"].orEmpty()
        val password = params["password"].orEmpty()
        when (parsed.backend) {
            "socks" -> {
                backend = "socks"
                socks_username = user
                socks_password = password
            }
            "ssh" -> {
                backend = "ssh"
                ssh_user = user
                ssh_password = password
                ssh_private_key = params["pk"].orEmpty()
            }
            "shadowsocks" -> {
                backend = "shadowsocks"
                ss_method = params["method"].orEmpty().trim().lowercase()
                ss_password = password
            }
            "custom" -> backend = ""
            "" -> {
                ParseDiag.error("backend", vtr(R.string.validation_dns_tunnel_no_backend))
                return false
            }
            else -> {
                ParseDiag.error("backend", vtr(R.string.validation_dns_tunnel_backend_unsupported), parsed.backend)
                return false
            }
        }
        if (parsed.hasResolvers) resolvers.lines = ResolverSources.importLines(parsed.resolvers, ResolverFlavor.Dnstt, "resolvers")
        return true
    }

    // "ip:port:flag" in every published sample; the transport field names how the resolver is reached.
    private fun slipnetResolver(text: String, transport: String): String {
        val parts = text.split(':').toMutableList()
        if (parts.size == 3) parts.removeAt(parts.size - 1)
        val address = parts.joinToString(":")
        if (address.isEmpty()) return ""
        return when (val kind = transport.trim().lowercase()) {
            "", "udp" -> address
            "tcp" -> "tcp://$address"
            "dot", "tls" -> "tls://$address"
            else -> {
                ParseDiag.warn("resolvers", vtr(R.string.validation_dnstt_slipnet_resolver_transport), kind)
                address
            }
        }
    }

    // SlipNet publishes no spec: positional '|' fields inferred from public links of versions 16 to 28.
    private fun parseSlipnet(link: String): Boolean {
        var payload = link.substring(10)
        val hash = payload.indexOf('#')
        if (hash >= 0) payload = payload.substring(0, hash)
        val bytes = DnsTunnels.base64(payload.trim())
        if (bytes.isEmpty()) {
            ParseDiag.error("link", vtr(R.string.validation_import_link_damaged_base64))
            return false
        }
        val fields = String(bytes, Charsets.UTF_8).split('|')
        fun raw(index: Int) = fields.getOrElse(index) { "" }
        fun field(index: Int) = raw(index).trim()
        val version = QtStrings.toIntOrNull(field(0))
        if (version == null || version < 16) {
            ParseDiag.error("link", vtr(R.string.validation_slipnet_version), field(0).take(16))
            return false
        }
        val needed = when {
            version >= 28 -> 79
            version >= 22 -> 60
            version >= 20 -> 50
            else -> 36
        }
        if (fields.size < needed) {
            ParseDiag.error("link", vtr(R.string.validation_slipnet_short))
            return false
        }
        if (version > 28) ParseDiag.warn("link", vtr(R.string.validation_slipnet_newer), field(0))

        var type = field(1).lowercase()
        val sshType = type.endsWith("_ssh")
        if (sshType) type = type.dropLast(4)
        when (type) {
            "dnstt", "vaydns" -> mode = type
            "sayedns" -> {
                mode = "dnstt"
                ParseDiag.warn("mode", vtr(R.string.validation_slipnet_noizdns))
            }
            else -> {
                ParseDiag.error("mode", vtr(R.string.validation_slipnet_type), field(1).take(32))
                return false
            }
        }

        name = field(2)
        domain = Hosts.toAceHost(field(3))
        public_key = field(11)
        val resolver = slipnetResolver(field(4), field(22))
        if (resolver.isNotEmpty()) resolvers.lines = ResolverSources.importLines(listOf(resolver), ResolverFlavor.Dnstt, "resolvers")
        if (sshType || field(14) == "1") {
            backend = "ssh"
            ssh_user = field(15)
            ssh_password = raw(16)
            val key = field(24)
            if (key.isNotEmpty()) {
                val pem = if (key.contains("-----BEGIN ")) key else DnsTunnels.privateKeyPem(key)
                if (pem.isEmpty()) ParseDiag.warn("ssh_private_key", vtr(R.string.validation_dnst_ssh_key_unreadable))
                ssh_private_key = pem
            }
        } else {
            backend = "socks"
            socks_username = field(12)
            socks_password = raw(13)
        }

        if (mode == "vaydns") {
            field(42).lowercase().let { text ->
                if (text.isNotEmpty() && acceptOptional("record_type", DnsttChecks.dnsttRecordType(mode, false, text), text)) record_type = text
            }
            field(43).let { text ->
                if (text.isNotEmpty() && text != "0" && acceptOptional("max_qname_len", Validation.int(text, 1, 253), text)) {
                    max_qname_len = QtStrings.toInt(text)
                }
            }
            field(44).let { text ->
                if (text.isNotEmpty() && text != "0") {
                    val value = DnsttChecks.parseNumber(text)
                    if (acceptOptional("rps", DnsttChecks.dnsttRps(value ?: Double.NaN), text)) rps = value ?: 0.0
                }
            }
            field(49).let { text ->
                if (text.isNotEmpty() && text != "0" && acceptOptional("client_id_size", Validation.int(text, 1, 253), text)) {
                    client_id_size = QtStrings.toInt(text)
                }
            }
        }
        return true
    }

    /** dnstt::ParseFromLink (dnstt.cpp): dnst://, and unencrypted slipnet:// links. */
    override fun parseFromLink(link: String): Boolean {
        if (link.length > DnsTunnels.MAX_LINK_SIZE) {
            ParseDiag.error("link", vtr(R.string.validation_link_too_long))
            return false
        }
        if (link.startsWith("slipnet://")) return parseSlipnet(link)
        if (link.startsWith("slipnet-")) {
            ParseDiag.error("link", vtr(R.string.validation_slipnet_encrypted))
            return false
        }
        return parseDnst(link)
    }

    /** dnstt::ParseFromJson (dnstt.cpp): the tunnel only dials resolvers, so a server here is dropped. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "dnstt") return false
        super.parseFromJson(obj)
        server = ""
        serverPort = 0
        if (obj.contains("mode")) mode = obj.string("mode").trim().lowercase()
        if (mode.isEmpty()) mode = "dnstt"
        dnstt_compat = obj.bool("dnstt_compat")
        if (obj.contains("domain")) domain = Hosts.toAceHost(obj.string("domain").trim())
        if (obj.contains("public_key")) public_key = obj.string("public_key").trim()
        if (obj.contains("record_type")) record_type = obj.string("record_type").trim().lowercase()
        client_id_size = obj.int("client_id_size")
        max_qname_len = obj.int("max_qname_len")
        rps = obj.double("rps")
        if (obj.contains("idle_timeout")) idle_timeout = obj.string("idle_timeout").trim()
        if (obj.contains("keepalive_interval")) keepalive_interval = obj.string("keepalive_interval").trim()
        if (obj.contains("resolver_strategy")) resolver_strategy = obj.string("resolver_strategy").trim().lowercase()
        tcp_fallback = obj.bool("tcp_fallback")

        if (obj.contains("resolvers")) {
            val value = obj["resolvers"]
            val items = if (value is JsonArray) value.toList() else listOf(value)
            val lines = ArrayList<String>()
            for (item in items) {
                when (item) {
                    is String -> lines.add(item)
                    is JsonObject -> {
                        val converted = ResolverSources.dnsttObjectLine(item)
                        if (converted.tcpFallback) tcp_fallback = true
                        if (converted.why.isNotEmpty()) ParseDiag.warn("resolvers", converted.why, converted.line)
                        if (converted.line.isNotEmpty()) lines.add(converted.line)
                    }
                    null, JsonNull -> {}
                    else -> ParseDiag.warn("resolvers", vtr(R.string.validation_json_list_items))
                }
            }
            resolvers.lines = ResolverSources.importLines(lines, ResolverFlavor.Dnstt, "resolvers")
        }
        if (obj.contains("resolver_list")) {
            if (ParseDiag.active()) {
                ParseDiag.warn("resolver_list", vtr(R.string.validation_resolver_list_foreign))
            } else {
                resolvers.ipListId = jsonIntOr(obj, "resolver_list", -1)
            }
        }

        val backends = listOf("socks", "shadowsocks", "ssh").filter { obj.isObject(it) }
        if (backends.size > 1) ParseDiag.error("backend", vtr(R.string.validation_dnstt_one_backend), backends.joinToString(", "))
        backend = backends.firstOrNull() ?: ""
        val options = if (backend.isEmpty()) JsonObject() else obj.obj(backend)
        fun udpOverTcp(): Boolean = if (options.isObject("udp_over_tcp")) options.obj("udp_over_tcp").bool("enabled") else options.bool("udp_over_tcp")
        fun nonBlankItems(key: String): MutableList<String> {
            val items = if (options.isString(key)) listOf(options.string(key)) else options.array(key).strings()
            return items.map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }
        }
        when (backend) {
            "socks" -> {
                socks_username = options.string("username")
                socks_password = options.string("password")
                socks_udp_over_tcp = udpOverTcp()
            }
            "shadowsocks" -> {
                ss_method = options.string("method").trim().lowercase()
                ss_password = options.string("password")
                ss_udp_over_tcp = udpOverTcp()
            }
            "ssh" -> {
                ssh_user = options.string("user")
                ssh_password = options.string("password")
                // The core joins private_key lines with '\n'.
                if (options.contains("private_key")) {
                    ssh_private_key = if (options.isString("private_key")) options.string("private_key")
                    else options.array("private_key").strings().joinToString("\n")
                }
                ssh_private_key_path = options.string("private_key_path")
                ssh_private_key_passphrase = options.string("private_key_passphrase")
                ssh_host_key = nonBlankItems("host_key")
                ssh_host_key_algorithms = nonBlankItems("host_key_algorithms")
                ssh_client_version = options.string("client_version")
            }
        }
        return true
    }

    /** dnstt::ExportToLink (dnstt.cpp): the readable dnst:// form unless the SSH key needs the JSON form. */
    override fun exportToLink(): String {
        val link = DnstLink()
        link.tag = name
        link.domain = domain
        link.transport = if (mode == "vaydns") "vaydns" else "dnstt"
        link.backend = backend.ifEmpty { "custom" }
        val params = link.params
        params["pubkey"] = public_key.trim().lowercase()
        if (mode == "vaydns") {
            params["record-type"] = record_type
            if (dnstt_compat) params["dnstt-compat"] = "true"
            if (client_id_size > 0) params["clientid-size"] = client_id_size.toString()
            params["idle-timeout"] = idle_timeout.trim()
            params["keepalive"] = keepalive_interval.trim()
        }
        var needsJson = false
        when (backend) {
            "socks" -> {
                params["user"] = socks_username
                params["password"] = socks_password
            }
            "ssh" -> {
                params["user"] = ssh_user
                params["password"] = ssh_password
                val key = ssh_private_key.trim()
                if (key.isNotEmpty()) {
                    params["pk"] = key
                    needsJson = DnsTunnels.privateKeyParam(key).isEmpty()
                }
            }
            "shadowsocks" -> {
                params["method"] = ss_method
                params["password"] = ss_password
            }
        }
        return DnsTunnels.encodeLink(link, needsJson)
    }

    /** dnstt::ExportToJson (dnstt.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "dnstt"
        obj.merge(baseExportToJson())
        writeCommon(obj)
        if (resolvers.lines.isNotEmpty()) obj["resolvers"] = JsonValues.stringArray(resolvers.lines)
        if (resolvers.ipListId >= 0) obj["resolver_list"] = resolvers.ipListId
        if (tcp_fallback) obj["tcp_fallback"] = true
        return obj
    }

    /** dnstt::ExportIdentity (dnstt.cpp). */
    override fun exportIdentity(): JsonObject {
        val obj = jsonObjectOf("type" to "dnstt", "mode" to mode, "domain" to domain.lowercase(), "public_key" to public_key.trim().lowercase())
        if (mode == "vaydns" && dnstt_compat) obj["dnstt_compat"] = true
        if (backend.isNotEmpty()) {
            val identity = jsonObjectOf("type" to backend)
            when (backend) {
                "socks" -> identity["username"] = socks_username
                "ssh" -> identity["user"] = ssh_user
                "shadowsocks" -> identity["method"] = ss_method
            }
            obj["backend"] = identity
        }
        return obj
    }

    /** dnstt::Build (dnstt.cpp): resolvers built from the lines only, Android has no resolver IP lists. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "dnstt"
        obj.merge(baseBuild(ctx))
        writeCommon(obj)
        val built = ResolverSources.build(resolvers, ResolverFlavor.Dnstt, displayTypeAndName(), ctx)
        if (built.error.isNotEmpty()) return BuildResult(JsonObject(), built.error)
        if (tcp_fallback) {
            for (resolver in built.resolvers) {
                if (resolver is JsonObject && resolver.string("type") == "udp") resolver["tcp_fallback"] = true
            }
        }
        obj["resolvers"] = built.resolvers
        return BuildResult(obj)
    }

    override fun displayAddress(): String = domain

    override fun displayType(): String = dnsttDisplayType(mode)

    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)

    /** dnstt::Validate (dnstt.cpp), plus the Android-only warning that a resolver IP list is never built. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "mode", Validation.oneOf(mode, dnsttModes, Empty.Required), mode)
        val domainMessage = DnsTunnelChecks.dnsTunnelDomain(domain)
        issues.addIssue(Severity.Error, "domain", domainMessage, domain)
        issues.addIssue(Severity.Error, "public_key", DnsttChecks.dnsttPublicKey(public_key))
        issues.addIssue(Severity.Error, "record_type", DnsttChecks.dnsttRecordType(mode, dnstt_compat, record_type), record_type)
        issues.addIssue(Severity.Error, "client_id_size", DnsttChecks.dnsttClientIdSize(mode, dnstt_compat, client_id_size), client_id_size.toString())
        issues.addIssue(Severity.Error, "max_qname_len", Validation.int(max_qname_len.toString(), 0, 253), max_qname_len.toString())
        if (domainMessage.isEmpty()) {
            issues.addIssue(Severity.Error, "domain", DnsttChecks.dnsttMtuError(mode, dnstt_compat, domain, max_qname_len, client_id_size), domain)
        }
        issues.addIssue(Severity.Error, "rps", DnsttChecks.dnsttRps(rps), dnsTunnelNumberText(rps))
        issues.addIssue(Severity.Error, "idle_timeout", Validation.duration(idle_timeout, DnsttChecks.positiveDuration), idle_timeout)
        issues.addIssue(Severity.Error, "keepalive_interval", Validation.duration(keepalive_interval, DnsttChecks.positiveDuration), keepalive_interval)
        issues.addIssue(Severity.Error, "keepalive_interval", DnsttChecks.dnsttKeepalive(mode, dnstt_compat, idle_timeout, keepalive_interval), keepalive_interval)
        for (line in resolvers.lines) {
            val message = ResolverChecks.dnsttLine(line)
            if (message.isNotEmpty()) {
                issues.addIssue(Severity.Error, "resolvers", message, line.trim())
                break
            }
        }
        if (resolvers.hasIpList()) issues.addIssue(Severity.Warning, "resolver_list", vtr(R.string.validation_resolver_list_android))
        issues.addIssue(Severity.Error, "resolver_strategy", Validation.oneOf(resolver_strategy, dnsttResolverStrategies), resolver_strategy)
        issues.addIssue(Severity.Error, "backend", Validation.oneOf(backend, dnsttBackends), backend)
        issues.addIssue(Severity.Warning, "backend", DnsttChecks.dnsttChainOnly(backend))
        if (backend == "shadowsocks") {
            issues.addIssue(Severity.Error, "ss_method", Validation.oneOf(ss_method, shadowsocksMethods, Empty.Required), ss_method)
            issues.addIssue(Severity.Error, "ss_password", ShadowsocksChecks.shadowsocksPassword(ss_method, ss_password))
        } else if (backend == "ssh") {
            issues.addIssue(Severity.Warning, "ssh_auth", SshChecks.sshAuthentication(ssh_password, ssh_private_key, ssh_private_key_path))
            issues.addIssue(Severity.Error, "ssh_private_key", SshChecks.sshPrivateKey(ssh_private_key))
            issues.addIssue(Severity.Warning, "ssh_private_key", SshChecks.sshKeySources(ssh_private_key, ssh_private_key_path))
            for (key in ssh_host_key) {
                val message = SshChecks.sshHostKey(key)
                if (message.isNotEmpty()) {
                    issues.addIssue(Severity.Error, "ssh_host_key", message, key)
                    break
                }
            }
            issues.addIssue(Severity.Warning, "ssh_client_version", SshChecks.sshClientVersion(ssh_client_version), ssh_client_version)
        }
    }
}
