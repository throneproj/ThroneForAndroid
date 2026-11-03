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
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr

/** masterDnsModes (masterdns.h). */
val masterDnsModes = listOf("socks5", "tcp")

/** masterDnsEncryptionMethods (masterdns.h): indexed by MasterDnsVPN's DATA_ENCRYPTION_METHOD. */
val masterDnsEncryptionMethods = listOf("none", "xor", "chacha20", "aes-128-gcm", "aes-192-gcm", "aes-256-gcm")

/** masterDnsCompressions (masterdns.h): indexed by its UPLOAD_ and DOWNLOAD_COMPRESSION_TYPE. */
val masterDnsCompressions = listOf("off", "zstd", "lz4", "zlib")

/** masterDnsBalancingStrategies (masterdns.h). */
val masterDnsBalancingStrategies = listOf(
    "round_robin", "random", "least_loss", "lowest_latency", "hybrid", "loss_then_latency", "least_loss_top_random",
    "least_loss_top_round_robin",
)

/** The Checks of masterdns.h. */
object MasterDnsChecks {
    const val MAX_SETUP_DUPLICATION = 12

    @JvmStatic
    fun masterDnsEncryptionKey(method: String, key: String): String {
        if (method == "none" || key.isNotEmpty()) return ""
        return vtr(R.string.validation_masterdns_key)
    }

    @JvmStatic
    fun masterDnsDuplication(duplication: Int): String = Validation.int(duplication.toString(), 0, 10)

    @JvmStatic
    fun masterDnsMtuRange(min: Int, max: Int, defaultMin: Int, defaultMax: Int): String {
        if (min < 0 || max < 0) return vtr(R.string.validation_not_negative)
        val low = if (min > 0) min else defaultMin
        val high = if (max > 0) max else defaultMax
        if (low <= high) return ""
        return vtr(R.string.validation_masterdns_mtu_range, low.toString(), high.toString())
    }
}

// RESOLVER_BALANCING_STRATEGY ids: 0 and 2 are both round robin.
private val MASTERDNS_BALANCING_IDS = listOf(
    "round_robin", "random", "round_robin", "least_loss", "lowest_latency", "hybrid", "loss_then_latency",
    "least_loss_top_random", "least_loss_top_round_robin",
)

private val MASTERDNS_SEPARATORS = Regex("[,;\\s]+")

private val MASTERDNS_PROFILE_SCHEMAS = listOf("whitedns.profile", "masterdns.profile", "stormdns.profile")

private fun qtNumberG(d: Double, precision: Int): String = QtStrings.numberG(d, precision)

private fun masterDnsDomains(items: List<String>): MutableList<String> {
    val domains = ArrayList<String>()
    for (item in items) {
        val domain = Hosts.toAceHost(item.trim())
        if (domain.isNotEmpty() && domain !in domains) domains.add(domain)
    }
    return domains
}

private fun masterDnsSplit(text: String): List<String> = text.split(MASTERDNS_SEPARATORS).filter { it.isNotEmpty() }

/** The TOML subset MasterDnsVPN client configs use: KEY = string, number, boolean or a flat array of them, and # comments. */
private class MasterDnsToml(private val text: String) {
    private var pos = 0

    /** Values are String, Boolean, Double or a List of those; [order] keeps each key's first appearance. */
    fun read(values: MutableMap<String, Any>, order: MutableList<String>): Boolean {
        while (true) {
            skipSpace(true)
            if (atEnd()) return true
            if (text[pos] == '[') {
                while (!atEnd() && text[pos] != '\n') pos++
                continue
            }
            val start = pos
            while (!atEnd() && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '-')) pos++
            val key = text.substring(start, pos)
            skipSpace(false)
            if (key.isEmpty() || atEnd() || text[pos] != '=') return false
            pos++
            val value = readValue(false) ?: return false
            skipSpace(false)
            if (!atEnd() && text[pos] != '\n') return false
            if (!values.containsKey(key)) {
                if (order.size >= 512) return false
                order.add(key)
            }
            values[key] = value
        }
    }

    private fun atEnd() = pos >= text.length

    private fun isDelimiter(c: Char) = c == ',' || c == ']' || c == '#' || c == '\n' || c == '\r' || c == '\t' || c == ' '

    private fun skipSpace(newlines: Boolean) {
        while (!atEnd()) {
            val c = text[pos]
            if (c == '#') {
                while (!atEnd() && text[pos] != '\n') pos++
            } else if (c == ' ' || c == '\t' || c == '\r' || (newlines && c == '\n')) {
                pos++
            } else {
                return
            }
        }
    }

    private fun readString(): String? {
        val quote = text[pos++]
        val out = StringBuilder()
        while (!atEnd()) {
            val c = text[pos++]
            if (c == quote) return out.toString()
            if (c == '\n') return null
            if (c != '\\' || quote == '\'') {
                out.append(c)
                continue
            }
            if (atEnd()) return null
            when (val escape = text[pos++]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                'u', 'U' -> {
                    val digits = if (escape == 'u') 4 else 8
                    if (pos + digits > text.length) return null
                    val hex = text.substring(pos, pos + digits)
                    if (!hex.all { Character.digit(it, 16) >= 0 }) return null
                    val code = hex.toLong(16)
                    if (code > 0x10FFFF || code in 0xD800..0xDFFF) return null
                    pos += digits
                    out.appendCodePoint(code.toInt())
                }
                else -> return null
            }
        }
        return null
    }

    private fun readValue(inArray: Boolean): Any? {
        skipSpace(inArray)
        if (atEnd()) return null
        val c = text[pos]
        if (c == '"' || c == '\'') return readString()
        if (c == '[') {
            if (inArray) return null
            pos++
            val array = ArrayList<Any>()
            while (true) {
                skipSpace(true)
                if (atEnd()) return null
                if (text[pos] == ']') break
                if (array.size >= 4096) return null
                array.add(readValue(true) ?: return null)
                skipSpace(true)
                if (atEnd()) return null
                if (text[pos] == ',') pos++
                else if (text[pos] != ']') return null
            }
            pos++
            return array
        }
        val start = pos
        while (!atEnd() && !isDelimiter(text[pos])) pos++
        val word = text.substring(start, pos)
        if (word == "true" || word == "false") return word == "true"
        val number = DnsttChecks.parseNumber(word.replace("_", "")) ?: return null
        return if (number.isFinite()) number else null
    }
}

private fun masterDnsLocalKey(key: String): Boolean =
    key in setOf("LOG_LEVEL", "MTU_USING_SECTION_SEPARATOR_TEXT", "MTU_SERVERS_FILE_NAME", "FD_CONTROL_UNIX_SOCKET", "RESOLVERS_FILE_PATH") ||
        key.startsWith("LISTEN_") || key.startsWith("SOCKS5_") || key.startsWith("LOCAL_DNS_") || key.startsWith("SAVE_MTU_") ||
        (key.startsWith("MTU_") && key.endsWith("_FORMAT"))

/** masterdns (include/configs/outbounds/masterdns.h, src/configs/outbounds/masterdns.cpp): MasterDnsVPN and StormDNS. */
class MasterDns : Outbound("masterdns") {
    @JvmField var domains: MutableList<String> = ArrayList()
    @JvmField var resolvers: ResolverSource = ResolverSource()
    @JvmField var mode: String = "socks5"
    @JvmField var encryption_method: String = "xor"
    @JvmField var encryption_key: String = ""
    @JvmField var base64_response: Boolean = false

    /** Empty or 0 leaves the core's default. */
    @JvmField var upload_compression: String = ""
    @JvmField var download_compression: String = ""
    @JvmField var balancing_strategy: String = ""
    @JvmField var packet_duplication: Int = 0
    @JvmField var setup_packet_duplication: Int = 0
    @JvmField var mtu_min_upload: Int = 0
    @JvmField var mtu_max_upload: Int = 0
    @JvmField var mtu_min_download: Int = 0
    @JvmField var mtu_max_download: Int = 0
    @JvmField var mtu_test_retries: Int = 0
    @JvmField var mtu_test_timeout: String = ""
    @JvmField var mtu_test_parallelism: Int = 0
    @JvmField var mtu_auto_remove_low: Boolean = true

    override fun hasServerEndpoint(): Boolean = false

    private fun writeCommon(obj: JsonObject) {
        obj["domain"] = JsonValues.stringArray(domains)
        obj["mode"] = mode.ifEmpty { "socks5" }
        obj["encryption_method"] = encryption_method.ifEmpty { "xor" }
        if (encryption_key.isNotEmpty()) obj["encryption_key"] = encryption_key
        if (base64_response) obj["base64_response"] = true
        if (upload_compression.isNotEmpty()) obj["upload_compression"] = upload_compression
        if (download_compression.isNotEmpty()) obj["download_compression"] = download_compression
        if (balancing_strategy.isNotEmpty()) obj["balancing_strategy"] = balancing_strategy
        if (packet_duplication > 0) obj["packet_duplication"] = packet_duplication
        if (setup_packet_duplication > 0) obj["setup_packet_duplication"] = setup_packet_duplication
        val mtu = JsonObject()
        if (mtu_min_upload > 0) mtu["min_upload"] = mtu_min_upload
        if (mtu_max_upload > 0) mtu["max_upload"] = mtu_max_upload
        if (mtu_min_download > 0) mtu["min_download"] = mtu_min_download
        if (mtu_max_download > 0) mtu["max_download"] = mtu_max_download
        if (mtu_test_retries > 0) mtu["test_retries"] = mtu_test_retries
        mtu_test_timeout.trim().let { if (it.isNotEmpty()) mtu["test_timeout"] = it }
        if (mtu_test_parallelism > 0) mtu["test_parallelism"] = mtu_test_parallelism
        if (!mtu_auto_remove_low) mtu["auto_remove_low"] = false
        if (mtu.isNotEmpty()) obj["mtu"] = mtu
    }

    // DATA_ENCRYPTION_METHOD as links carry it; MasterDnsVPN's default is 1, XOR.
    private fun readMethod(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) {
            encryption_method = "xor"
            return true
        }
        val id = Validation.toInt(value)
        if (id == null || id < 0 || id >= masterDnsEncryptionMethods.size) {
            ParseDiag.error("encryption_method", vtr(R.string.validation_masterdns_method_id), value.take(16))
            return false
        }
        encryption_method = masterDnsEncryptionMethods[id.toInt()]
        return true
    }

    /** masterDnsReadId: null leaves the field as it is. */
    private fun readId(field: String, text: String, names: List<String>, zeroIsDefault: Boolean): String? {
        val value = text.trim()
        if (value.isEmpty() || !acceptOptional(field, Validation.int(value, 0, (names.size - 1).toLong()), value)) return null
        val id = QtStrings.toInt(value)
        return if (zeroIsDefault && id == 0) "" else names[id]
    }

    private fun readCount(field: String, text: String, min: Int, max: Int): Int? {
        val value = text.trim()
        if (value.isEmpty() || !acceptOptional(field, Validation.int(value, min.toLong(), max.toLong()), value)) return null
        return QtStrings.toInt(value)
    }

    private fun readBool(field: String, value: Any?): Boolean? {
        if (value is Boolean) return value
        acceptOptional(field, vtr(R.string.validation_use_true_false), tomlText(value).take(32))
        return null
    }

    private fun tomlText(value: Any?): String = when (value) {
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Double -> qtNumberG(value, 15)
        else -> ""
    }

    private fun parseDnst(link: String): Boolean {
        val decoded = DnsTunnels.decodeLink(link)
        val parsed = decoded.link
        if (parsed == null) {
            ParseDiag.error("link", decoded.error)
            return false
        }
        if (parsed.transport != "masterdns") {
            ParseDiag.error("mode", vtr(R.string.validation_dns_tunnel_unsupported), parsed.transport)
            return false
        }
        name = parsed.tag
        domains = masterDnsDomains(listOf(parsed.domain))
        encryption_key = parsed.params["mkey"].orEmpty()
        if (!readMethod(parsed.params["encryption"].orEmpty())) return false
        when (parsed.backend) {
            "socks" -> {
                mode = "socks5"
                if (parsed.params["user"].orEmpty().isNotEmpty() || parsed.params["password"].orEmpty().isNotEmpty()) {
                    ParseDiag.warn("mode", vtr(R.string.validation_masterdns_socks_credentials))
                }
            }
            "custom" -> mode = "tcp"
            "" -> {
                ParseDiag.error("mode", vtr(R.string.validation_dns_tunnel_no_backend))
                return false
            }
            else -> {
                ParseDiag.error("mode", vtr(R.string.validation_masterdns_backend), parsed.backend)
                return false
            }
        }
        if (parsed.hasResolvers) resolvers.lines = ResolverSources.importLines(parsed.resolvers, ResolverFlavor.MasterDns, "resolvers")
        return true
    }

    // yptun: v1?domains=&key=&enc=&resolvers=[&bal=][&dup=][&proxy=]#name, lists split on commas, semicolons or spaces.
    private fun parseYptun(text: String): Boolean {
        var rest = text
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            name = LinkCodec.decodeFully(rest.substring(hash + 1)).trim()
            rest = rest.substring(0, hash)
        }
        if (rest.startsWith('?')) rest = rest.substring(1)
        val query = HashMap<String, String>()
        for (pair in rest.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val key = LinkCodec.decodeFully(if (eq < 0) pair else pair.substring(0, eq)).trim().lowercase()
            if (!query.containsKey(key)) query[key] = if (eq < 0) "" else LinkCodec.decodeFully(pair.substring(eq + 1))
        }
        domains = masterDnsDomains(masterDnsSplit(query["domains"].orEmpty()))
        encryption_key = query["key"].orEmpty()
        if (!readMethod(query["enc"].orEmpty())) return false
        if (query.containsKey("resolvers")) {
            resolvers.lines = ResolverSources.importLines(masterDnsSplit(query["resolvers"].orEmpty()), ResolverFlavor.MasterDns, "resolvers")
        }
        // 0 leaves both to the client's default.
        readId("balancing_strategy", query["bal"].orEmpty(), MASTERDNS_BALANCING_IDS, true)?.let { balancing_strategy = it }
        readCount("packet_duplication", query["dup"].orEmpty(), 0, 10)?.let { packet_duplication = it }
        if (query["proxy"].orEmpty().isNotBlank()) ParseDiag.warn("link", vtr(R.string.validation_masterdns_proxy_link))
        return true
    }

    // WhiteDNS (whitedns.profile) and Hidden-Node (masterdns.profile, stormdns.profile) share this JSON, schema version 1.
    private fun parseProfile(text: String): Boolean {
        var payload = text
        for (mark in charArrayOf('#', '?')) {
            val at = payload.indexOf(mark)
            if (at >= 0) payload = payload.substring(0, at)
        }
        val bytes = DnsTunnels.base64(payload.trim())
        if (bytes.isEmpty()) {
            ParseDiag.error("link", vtr(R.string.validation_import_link_damaged_base64))
            return false
        }
        val root = DnsTunnels.strictJsonObject(String(bytes, Charsets.UTF_8))
        if (root == null) {
            ParseDiag.error("link", vtr(R.string.validation_import_link_damaged_object))
            return false
        }
        val schema = root.string("schema").trim().lowercase()
        if (schema !in MASTERDNS_PROFILE_SCHEMAS) {
            ParseDiag.error("link", vtr(R.string.validation_masterdns_format), schema.take(64))
            return false
        }
        val version = root.variantString("version").trim()
        if (version != "1") {
            ParseDiag.error("link", vtr(R.string.validation_masterdns_version), version.take(16))
            return false
        }
        val profile = root.obj("profile")
        val server = profile.obj("server")
        fun pick(snake: String, camel: String): Any? = if (server.contains(snake)) server[snake] else server[camel]
        name = profile.string("name").trim()
        val items = ArrayList<String>()
        if (server.contains("domain")) items.add(server.string("domain"))
        if (server.contains("domains")) items.addAll(jsonStringList(server["domains"], "domain"))
        domains = masterDnsDomains(items)
        encryption_key = pick("encryption_key", "encryptionKey") as? String ?: ""
        val method = pick("encryption_method", "encryptionMethod")
        val methodText = when (method) {
            is Long -> method.toString()
            is Double -> qtNumberG(method, 6)
            is String -> method
            else -> ""
        }
        return readMethod(methodText)
    }

    // lucx-ui: v1/<base64url of the client TOML>, which never carries resolvers.
    private fun parseToml(text: String): Boolean {
        var payload = text
        val hash = payload.indexOf('#')
        if (hash >= 0) {
            name = LinkCodec.decodeFully(payload.substring(hash + 1)).trim()
            payload = payload.substring(0, hash)
        }
        val bytes = DnsTunnels.base64(payload.trim())
        if (bytes.isEmpty()) {
            ParseDiag.error("link", vtr(R.string.validation_import_link_damaged_base64))
            return false
        }
        val values = HashMap<String, Any>()
        val order = ArrayList<String>()
        if (!MasterDnsToml(String(bytes, Charsets.UTF_8)).read(values, order)) {
            ParseDiag.error("link", vtr(R.string.validation_masterdns_config_unreadable))
            return false
        }
        val unsupported = ArrayList<String>()
        for (key in order) {
            val upper = key.uppercase()
            val value = values[key]
            val valueText = tomlText(value).trim()
            when (upper) {
                "DOMAINS" -> {
                    val items = if (value is List<*>) value.filterIsInstance<String>() else masterDnsSplit(valueText)
                    domains = masterDnsDomains(items)
                }
                "DATA_ENCRYPTION_METHOD" -> if (!readMethod(valueText)) return false
                "ENCRYPTION_KEY" -> encryption_key = tomlText(value)
                "PROTOCOL_TYPE" -> when {
                    valueText.equals("SOCKS5", ignoreCase = true) -> mode = "socks5"
                    valueText.equals("TCP", ignoreCase = true) -> mode = "tcp"
                    else -> {
                        ParseDiag.error("mode", vtr(R.string.validation_masterdns_protocol_type), valueText.take(32))
                        return false
                    }
                }
                "BASE_ENCODE_DATA" -> readBool("base64_response", value)?.let { base64_response = it }
                "UPLOAD_COMPRESSION_TYPE" -> readId("upload_compression", valueText, masterDnsCompressions, false)?.let { upload_compression = it }
                "DOWNLOAD_COMPRESSION_TYPE" -> readId("download_compression", valueText, masterDnsCompressions, false)?.let { download_compression = it }
                "RESOLVER_BALANCING_STRATEGY" -> readId("balancing_strategy", valueText, MASTERDNS_BALANCING_IDS, false)?.let { balancing_strategy = it }
                "PACKET_DUPLICATION_COUNT" -> readCount("packet_duplication", valueText, 1, 10)?.let { packet_duplication = it }
                "SETUP_PACKET_DUPLICATION_COUNT" -> readCount("setup_packet_duplication", valueText, 1, MasterDnsChecks.MAX_SETUP_DUPLICATION)
                    ?.let { setup_packet_duplication = it }
                "MIN_UPLOAD_MTU" -> readCount("mtu_min_upload", valueText, 0, 65535)?.let { mtu_min_upload = it }
                "MAX_UPLOAD_MTU" -> readCount("mtu_max_upload", valueText, 0, 65535)?.let { mtu_max_upload = it }
                "MIN_DOWNLOAD_MTU" -> readCount("mtu_min_download", valueText, 0, 65535)?.let { mtu_min_download = it }
                "MAX_DOWNLOAD_MTU" -> readCount("mtu_max_download", valueText, 0, 65535)?.let { mtu_max_download = it }
                "AUTO_REMOVE_LOW_MTU_SERVERS" -> readBool("mtu_auto_remove_low", value)?.let { mtu_auto_remove_low = it }
                "MTU_TEST_RETRIES" -> readCount("mtu_test_retries", valueText, 0, 1000)?.let { mtu_test_retries = it }
                "MTU_TEST_TIMEOUT" -> {
                    val seconds = DnsttChecks.parseNumber(valueText)
                    val valid = seconds != null && seconds > 0 && seconds < 86400
                    val message = if (valid) "" else vtr(R.string.validation_masterdns_timeout_seconds)
                    if (acceptOptional("mtu_test_timeout", message, valueText.take(32)) && seconds != null) {
                        mtu_test_timeout = qtNumberG(seconds, 15) + "s"
                    }
                }
                "MTU_TEST_PARALLELISM" -> readCount("mtu_test_parallelism", valueText, 1, 4096)?.let { mtu_test_parallelism = it }
                else -> if (!masterDnsLocalKey(upper)) unsupported.add(key.take(64))
            }
        }
        if (unsupported.isNotEmpty()) {
            ParseDiag.warn("link", vtr(R.string.validation_masterdns_unsupported_keys), unsupported.joinToString(", ").take(256))
        }
        return true
    }

    /** masterdns::ParseFromLink (masterdns.cpp): dnst://, yptun (v1?), lucx-ui TOML (v1/) and the shared profile JSON. */
    override fun parseFromLink(link: String): Boolean {
        if (link.length > DnsTunnels.MAX_LINK_SIZE) {
            ParseDiag.error("link", vtr(R.string.validation_link_too_long))
            return false
        }
        if (link.startsWith("dnst://", ignoreCase = true)) return parseDnst(link)
        val separator = link.indexOf("://")
        if (separator < 0) return false
        val rest = link.substring(separator + 3).trim()
        if (rest.startsWith("v1/", ignoreCase = true)) return parseToml(rest.substring(3))
        if (rest.equals("v1", ignoreCase = true) || rest.startsWith("v1?", ignoreCase = true) || rest.startsWith("v1#", ignoreCase = true)) {
            return parseYptun(rest.substring(2))
        }
        return parseProfile(rest)
    }

    /** masterdns::ParseFromJson (masterdns.cpp): the tunnel only dials resolvers, so a server here is dropped. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "masterdns") return false
        super.parseFromJson(obj)
        server = ""
        serverPort = 0
        if (obj.contains("domain")) domains = masterDnsDomains(jsonStringList(obj["domain"], "domain"))
        if (obj.contains("resolvers")) {
            resolvers.lines = ResolverSources.importLines(jsonStringList(obj["resolvers"], "resolvers"), ResolverFlavor.MasterDns, "resolvers")
        }
        if (obj.contains("resolver_list")) {
            if (ParseDiag.active()) {
                ParseDiag.warn("resolver_list", vtr(R.string.validation_resolver_list_foreign))
            } else {
                resolvers.ipListId = jsonIntOr(obj, "resolver_list", -1)
            }
        }
        if (obj.contains("mode")) mode = obj.string("mode").trim().lowercase()
        if (obj.contains("encryption_method")) encryption_method = obj.string("encryption_method").trim().lowercase()
        encryption_key = obj.string("encryption_key")
        base64_response = obj.bool("base64_response")
        upload_compression = obj.string("upload_compression").trim().lowercase()
        download_compression = obj.string("download_compression").trim().lowercase()
        balancing_strategy = obj.string("balancing_strategy").trim().lowercase()
        packet_duplication = obj.int("packet_duplication")
        setup_packet_duplication = obj.int("setup_packet_duplication")
        val mtu = obj.obj("mtu")
        mtu_min_upload = mtu.int("min_upload")
        mtu_max_upload = mtu.int("max_upload")
        mtu_min_download = mtu.int("min_download")
        mtu_max_download = mtu.int("max_download")
        mtu_test_retries = mtu.int("test_retries")
        mtu_test_timeout = mtu.string("test_timeout").trim()
        mtu_test_parallelism = mtu.int("test_parallelism")
        mtu_auto_remove_low = mtu["auto_remove_low"] as? Boolean ?: true
        return true
    }

    /** masterdns::ExportToLink (masterdns.cpp): the readable dnst:// form. */
    override fun exportToLink(): String {
        val link = DnstLink()
        link.tag = name
        link.domain = domains.firstOrNull() ?: ""
        link.transport = "masterdns"
        link.backend = if (mode == "tcp") "custom" else "socks"
        link.params["mkey"] = encryption_key
        val method = masterDnsEncryptionMethods.indexOf(encryption_method)
        link.params["encryption"] = (if (method >= 0) method else 1).toString()
        return DnsTunnels.encodeLink(link, false)
    }

    /** masterdns::ExportToJson (masterdns.cpp). */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "masterdns"
        obj.merge(baseExportToJson())
        writeCommon(obj)
        if (resolvers.lines.isNotEmpty()) obj["resolvers"] = JsonValues.stringArray(resolvers.lines)
        if (resolvers.ipListId >= 0) obj["resolver_list"] = resolvers.ipListId
        return obj
    }

    /** masterdns::ExportIdentity (masterdns.cpp). */
    override fun exportIdentity(): JsonObject = jsonObjectOf(
        "type" to "masterdns",
        "domain" to JsonValues.stringArray(domains.map { it.lowercase() }),
        "mode" to mode,
        "encryption_method" to encryption_method,
        "encryption_key" to encryption_key,
    )

    /** masterdns::Build (masterdns.cpp): resolvers built from the lines only, Android has no resolver IP lists. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "masterdns"
        obj.merge(baseBuild(ctx))
        writeCommon(obj)
        val built = ResolverSources.build(resolvers, ResolverFlavor.MasterDns, displayTypeAndName(), ctx)
        if (built.error.isNotEmpty()) return BuildResult(JsonObject(), built.error)
        obj["resolvers"] = built.resolvers
        return BuildResult(obj)
    }

    override fun displayAddress(): String {
        if (domains.size <= 1) return domains.firstOrNull() ?: ""
        return "${domains.first()} +${domains.size - 1}"
    }

    override fun displayType(): String = "MasterDNS"

    /** masterdns::GetSecurity (masterdns.cpp): MasterDnsVPN's AES-GCM modes leave what the server sends back unencrypted. */
    override fun security(): SecurityInfo = when {
        encryption_method == "none" -> SecurityInfo("Raw", "", SecurityLevel.None)
        encryption_method.startsWith("aes-") -> SecurityInfo("Plaintext Replies", "", SecurityLevel.Weak)
        else -> SecurityInfo("Weak Cipher", "", SecurityLevel.Weak)
    }

    /** masterdns::Validate (masterdns.cpp), plus the Android-only warning that a resolver IP list is never built. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        if (domains.isEmpty()) issues.addIssue(Severity.Error, "domain", DnsTunnelChecks.dnsTunnelDomain(""))
        for (item in domains) {
            val message = DnsTunnelChecks.dnsTunnelDomain(item)
            if (message.isNotEmpty()) {
                issues.addIssue(Severity.Error, "domain", message, item)
                break
            }
        }
        issues.addIssue(Severity.Error, "mode", Validation.oneOf(mode, masterDnsModes, Empty.Required), mode)
        issues.addIssue(
            Severity.Error, "encryption_method", Validation.oneOf(encryption_method, masterDnsEncryptionMethods, Empty.Required), encryption_method,
        )
        issues.addIssue(Severity.Error, "encryption_key", MasterDnsChecks.masterDnsEncryptionKey(encryption_method, encryption_key))
        issues.addIssue(Severity.Error, "upload_compression", Validation.oneOf(upload_compression, masterDnsCompressions), upload_compression)
        issues.addIssue(Severity.Error, "download_compression", Validation.oneOf(download_compression, masterDnsCompressions), download_compression)
        issues.addIssue(Severity.Error, "balancing_strategy", Validation.oneOf(balancing_strategy, masterDnsBalancingStrategies), balancing_strategy)
        issues.addIssue(Severity.Error, "packet_duplication", MasterDnsChecks.masterDnsDuplication(packet_duplication), packet_duplication.toString())
        // The core raises a setup duplication below the packet duplication to it, as MasterDnsVPN does.
        issues.addIssue(
            Severity.Error, "setup_packet_duplication",
            Validation.int(setup_packet_duplication.toString(), 0, MasterDnsChecks.MAX_SETUP_DUPLICATION.toLong()), setup_packet_duplication.toString(),
        )
        issues.addIssue(Severity.Error, "mtu_min_upload", MasterDnsChecks.masterDnsMtuRange(mtu_min_upload, mtu_max_upload, 38, 150))
        issues.addIssue(Severity.Error, "mtu_min_download", MasterDnsChecks.masterDnsMtuRange(mtu_min_download, mtu_max_download, 200, 4000))
        issues.addIssue(Severity.Error, "mtu_test_retries", Validation.int(mtu_test_retries.toString(), 0, Int.MAX_VALUE.toLong()), mtu_test_retries.toString())
        issues.addIssue(Severity.Error, "mtu_test_timeout", Validation.duration(mtu_test_timeout, DnsttChecks.positiveDuration), mtu_test_timeout)
        issues.addIssue(
            Severity.Error, "mtu_test_parallelism", Validation.int(mtu_test_parallelism.toString(), 0, Int.MAX_VALUE.toLong()), mtu_test_parallelism.toString(),
        )
        for (line in resolvers.lines) {
            val message = ResolverChecks.masterDnsLine(line)
            if (message.isNotEmpty()) {
                issues.addIssue(Severity.Error, "resolvers", message, line.trim())
                break
            }
        }
        if (resolvers.hasIpList()) issues.addIssue(Severity.Warning, "resolver_list", vtr(R.string.validation_resolver_list_android))
    }
}
