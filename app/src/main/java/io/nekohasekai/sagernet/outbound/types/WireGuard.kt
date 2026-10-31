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
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr

private const val WIREGUARD_INT_MAX = 2147483647L
private const val WIREGUARD_UINT32_MAX = 4294967295L

/** Without a diagnostics scope (DB loads, clones) a parse keeps every value as it always did. */
private fun wireguardKeep(field: String, value: String, rule: Rule): Boolean =
    !ParseDiag.active() || acceptOptional(field, rule(value), value)

/** S1-S4 must match the server's, so a bad one refuses the profile; Jc, Jmin and Jmax only shape what this side sends. */
private fun wireguardAmneziaInt(field: String, value: String, max: Long, mustMatchServer: Boolean): Int {
    val message = Validation.int(value, 0, max)
    if (message.isEmpty()) return QtStrings.toInt(value.trim())
    if (mustMatchServer) ParseDiag.error(field, message, value) else acceptOptional(field, message, value)
    return 0
}

/** Call after fixAddress(): what is left without a valid prefix the core cannot decode. */
private fun wireguardDropBadAddresses(addresses: MutableList<String>): MutableList<String> {
    if (!ParseDiag.active()) return addresses
    return addresses.filterTo(ArrayList()) { acceptOptional("address", Validation.cidr(it), it) }
}

/** The Checks of wireguard.h, shared by the WireGuard editors (field text) and [WireGuard.validate] (profile fields). */
object WireGuardChecks {

    /** Three bytes separated by ',' or '-', as the editor and links write them; [bytes] receives them, or is cleared. */
    @JvmStatic
    @JvmOverloads
    fun wireguardReserved(text: String, bytes: MutableList<Int>? = null): String {
        bytes?.clear()
        val t = text.trim()
        if (t.isEmpty()) return ""
        val values = ArrayList<Int>()
        for (part in QtStrings.splitSkipEmpty(t.replace('-', ','), ",")) {
            val value = Validation.toInt(part)
            if (value == null || value < 0 || value > 255) {
                values.clear()
                break
            }
            values.add(value.toInt())
        }
        if (values.size != 3) return vtr(R.string.validation_wg_reserved)
        bytes?.addAll(values)
        return ""
    }

    /** Seconds, or an AmneziaWG range such as 22-30. */
    @JvmStatic
    fun wireguardKeepalive(text: String): String = Validation.intOrRange(text, 0, WIREGUARD_INT_MAX)

    /** wireguard-go UintRange: n or lo-hi, both uint32. */
    @JvmStatic
    fun amneziaRange(text: String): String = Validation.intOrRange(text, 0, WIREGUARD_UINT32_MAX)

    /** H1-H4 in order; unset ones are 1, 2, 3 and 4 in wireguard-go. Silent while one of them is not a range. */
    @JvmStatic
    fun amneziaHeaders(headers: List<String>): String {
        val ranges = ArrayList<Pair<Long, Long>>()
        for (i in 0 until 4) {
            val text = headers.getOrElse(i) { "" }.trim()
            if (text.isEmpty()) {
                ranges.add(Pair(i + 1L, i + 1L))
                continue
            }
            if (amneziaRange(text).isNotEmpty()) return ""
            val dash = text.indexOf('-')
            val lo = Validation.toInt(if (dash < 0) text else text.substring(0, dash)) ?: return ""
            val hi = if (dash < 0) lo else Validation.toInt(text.substring(dash + 1)) ?: return ""
            ranges.add(Pair(lo, hi))
        }
        for (i in ranges.indices) {
            for (j in i + 1 until ranges.size) {
                if (ranges[i].first <= ranges[j].second && ranges[j].first <= ranges[i].second) {
                    return vtr(R.string.validation_wg_headers_overlap, (i + 1).toString(), (j + 1).toString())
                }
            }
        }
        return ""
    }

    @JvmStatic
    fun amneziaHeaderProtection(key: String, paddings: List<Long>): String {
        if (key.isBlank()) return ""
        for ((i, padding) in paddings.withIndex()) {
            if (padding < 12) return vtr(R.string.validation_wg_header_protection_padding, (i + 1).toString(), padding.toString())
        }
        return ""
    }

    @JvmStatic
    fun amneziaJunkSizes(jmin: Long, jmax: Long): String = if (jmin <= jmax) "" else vtr(R.string.validation_wg_junk_sizes)

    /** wireguard-go newObfChain: <b 0xHEX>, <r N>, <rc N>, <rd N>, <dz N>, <t>, <d>, <ds>; text outside tags is ignored. */
    @JvmStatic
    fun amneziaSignature(spec: String): String {
        var start = spec.indexOf('<')
        while (start >= 0) {
            val end = spec.indexOf('>', start)
            if (end < 0) return vtr(R.string.validation_wg_signature_unclosed)
            val parts = words(spec.substring(start + 1, end))
            start = spec.indexOf('<', end + 1)
            if (parts.isEmpty()) return vtr(R.string.validation_wg_signature_empty)
            val tag = parts[0]
            val argument = parts.getOrElse(1) { "" }
            when (tag) {
                "b" -> {
                    val hex = if (argument.startsWith("0x")) argument.substring(2) else argument
                    if (hex.isEmpty() || Validation.hex(hex, 0, 0).isNotEmpty()) return vtr(R.string.validation_wg_signature_hex)
                }

                "r", "rc", "rd", "dz" -> {
                    val length = Validation.toInt(if (argument.startsWith('+')) argument.substring(1) else argument)
                    if (length == null || length < 0) return vtr(R.string.validation_wg_signature_length, tag)
                }

                "t", "d", "ds" -> {}
                else -> return vtr(R.string.validation_wg_signature_unknown, tag)
            }
        }
        return ""
    }

    /** QString::simplified().split(' ', Qt::SkipEmptyParts). */
    private fun words(text: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        for (c in text) {
            if (!c.isWhitespace()) {
                word.append(c)
                continue
            }
            if (word.isNotEmpty()) words.add(word.toString())
            word.setLength(0)
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }
}

/** Peer (wireguard.h, wireguard.cpp): the single peer of a wireguard endpoint. */
class WireGuardPeer {
    @JvmField var address: String = ""
    @JvmField var port: Int = 0
    @JvmField var public_key: String = ""
    @JvmField var pre_shared_key: String = ""
    @JvmField var reserved: MutableList<Int> = ArrayList()

    /** Seconds, or an AmneziaWG 3.0 range such as "22-30". */
    @JvmField var persistent_keepalive: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** Peer::ParseFromLink; values are FullyDecoded because PrettyDecoded keeps %2F/%2B/%2C and corrupts base64 keys. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        address = url.host
        port = url.port(51820)
        when {
            q.has("public_key") -> public_key = q.valueFully("public_key")
            q.has("publickey") -> public_key = q.valueFully("publickey")
            q.has("peer_public_key") -> public_key = q.valueFully("peer_public_key")
        }
        when {
            q.has("pre_shared_key") -> pre_shared_key = q.valueFully("pre_shared_key")
            q.has("preshared_key") -> pre_shared_key = q.valueFully("preshared_key")
            q.has("presharedkey") -> pre_shared_key = q.valueFully("presharedkey")
            q.has("psk") -> pre_shared_key = q.valueFully("psk")
        }
        if (q.has("reserved")) {
            val raw = q.valueFully("reserved")
            // The core needs all three bytes, so a partial list is dropped rather than kept.
            val message = WireGuardChecks.wireguardReserved(raw, reserved)
            if (message.isNotEmpty()) acceptOptional("reserved", message, raw)
        }
        for (key in KEEPALIVE_KEYS) {
            if (!q.has(key)) continue
            val value = q.valueFully(key).trim()
            if (wireguardKeep(key, value) { WireGuardChecks.wireguardKeepalive(it) }) persistent_keepalive = value
            break
        }
        return true
    }

    /** Peer::ParseFromJson: the keepalive accepts a number or a range string, reserved a base64 string or a byte list. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("address")) address = obj.string("address")
        if (obj.contains("port")) port = obj.int("port")
        if (obj.contains("public_key")) public_key = obj.string("public_key")
        if (obj.contains("pre_shared_key")) pre_shared_key = obj.string("pre_shared_key")
        if (obj.contains("reserved")) {
            val value = obj["reserved"]
            var text = ""
            if (value is String) {
                // Go decodes a JSON string into []uint8 as base64.
                text = value
                if (Validation.base64Key(text, 3).isEmpty()) {
                    reserved = Base64Strict.decodeLenient(text).mapTo(ArrayList()) { it.toInt() and 0xFF }
                }
            } else {
                reserved = obj.array("reserved").mapTo(ArrayList()) { JsonValues.toInt(it) }
            }
            if (ParseDiag.active() && (reserved.isNotEmpty() || text.isNotEmpty())) {
                val bytes = if (reserved.isNotEmpty()) reserved.joinToString(",") else text
                val message = WireGuardChecks.wireguardReserved(bytes, reserved)
                if (message.isNotEmpty()) acceptOptional("reserved", message, bytes)
            }
        }
        if (obj.contains("persistent_keepalive_interval")) {
            val text = if (obj.isString("persistent_keepalive_interval")) obj.string("persistent_keepalive_interval")
            else obj.int("persistent_keepalive_interval").toString()
            if (wireguardKeep("persistent_keepalive_interval", text) { WireGuardChecks.wireguardKeepalive(it) }) {
                persistent_keepalive = text
            }
        }
        return true
    }

    /** Peer::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (public_key.isNotEmpty()) q.add("public_key" to public_key)
        if (pre_shared_key.isNotEmpty()) q.add("pre_shared_key" to pre_shared_key)
        if (reserved.isNotEmpty()) q.add("reserved" to reserved.joinToString("-"))
        if (persistent_keepalive.isNotEmpty()) q.add("persistent_keepalive_interval" to persistent_keepalive)
        return q
    }

    /** Peer::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (address.isNotEmpty()) obj["address"] = address
        if (port > 0) obj["port"] = port
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (pre_shared_key.isNotEmpty()) obj["pre_shared_key"] = pre_shared_key
        if (reserved.isNotEmpty()) obj["reserved"] = reservedArray()
        writeKeepalive(obj)
        return obj
    }

    /** Peer::Build: ExportToJson plus the catch-all allowed_ips. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj["allowed_ips"] = JsonValues.stringArray(listOf("0.0.0.0/0", "::/0"))
        return obj
    }

    private fun reservedArray(): JsonArray {
        val arr = JsonArray()
        for (value in reserved) arr.add(value)
        return arr
    }

    // Peer::WriteKeepalive: a range must be a string and a plain interval a number; anything else makes the core
    // refuse to start, so a value that is neither is dropped.
    private fun writeKeepalive(obj: JsonObject) {
        if (persistent_keepalive.isEmpty()) return
        val seconds = QtStrings.toIntOrNull(persistent_keepalive)
        if (seconds != null) {
            if (seconds > 0) obj["persistent_keepalive_interval"] = seconds
            return
        }
        if (KEEPALIVE_RANGE.containsMatchIn(persistent_keepalive)) obj["persistent_keepalive_interval"] = persistent_keepalive
    }

    companion object {
        private val KEEPALIVE_RANGE = Regex("^\\d+-\\d+$")
        private val KEEPALIVE_KEYS = listOf("persistent_keepalive_interval", "persistent_keepalive", "keepalive")
    }
}

/**
 * wireguard / AmneziaWG (include/configs/outbounds/wireguard.h, src/configs/outbounds/wireguard.cpp): a sing-box
 * endpoint. The server address and port live in [peer]; the base `server`/`serverPort` are only what the last link
 * parse left there, exactly as on the desktop, so callers use [getAddress]/[getPort].
 */
class WireGuard : Outbound("wireguard") {
    @JvmField var private_key: String = ""
    @JvmField var peer: WireGuardPeer = WireGuardPeer()
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1420
    @JvmField var system: Boolean = false
    @JvmField var worker_count: Int = 0
    @JvmField var udp_timeout: String = ""

    /** AmneziaWG: jc/jmin/jmax and s1-s4 are integers; h1-h4 (magic headers) and i1-i5 (signature packets) are strings. */
    @JvmField var enable_amnezia: Boolean = false
    @JvmField var jc: Int = 0
    @JvmField var jmin: Int = 0
    @JvmField var jmax: Int = 0
    @JvmField var s1: Int = 0
    @JvmField var s2: Int = 0
    @JvmField var s3: Int = 0
    @JvmField var s4: Int = 0
    @JvmField var h1: String = ""
    @JvmField var h2: String = ""
    @JvmField var h3: String = ""
    @JvmField var h4: String = ""
    @JvmField var i1: String = ""
    @JvmField var i2: String = ""
    @JvmField var i3: String = ""
    @JvmField var i4: String = ""
    @JvmField var i5: String = ""

    /** AmneziaWG 3.0: header_protection_key is a base64 32-byte key; the rest are ranges ("30" or "22-30"). */
    @JvmField var header_protection_key: String = ""
    @JvmField var content_padding_addition: String = ""
    @JvmField var rekey_after_time: String = ""
    @JvmField var rekey_timeout: String = ""
    @JvmField var reject_after_time: String = ""
    @JvmField var keepalive_timeout: String = ""
    @JvmField var max_handshake_attempts: String = ""

    /** AmneziaWG 3.1 */
    @JvmField var random_trailers: Boolean = false
    @JvmField var disable_cookies: Boolean = false

    /**
     * wireguard::ParseFromLink: wg-quick / AmneziaWG INI text, or a wg:// / wireguard:// / awg:// URL. The scheme is
     * not checked, and awg:// alone does not enable AmneziaWG; only its parameters do, as on the desktop.
     */
    override fun parseFromLink(link: String): Boolean {
        if (link.contains("[Interface]") && link.contains("[Peer]")) return parseIni(link)

        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)

        when {
            q.has("private_key") -> private_key = q.valueFully("private_key")
            q.has("privatekey") -> private_key = q.valueFully("privatekey")
            url.userName.isNotEmpty() -> private_key = url.userName
        }

        peer.parseFromLink(url)
        server = peer.address
        serverPort = peer.port

        when {
            q.has("local_address") -> address = QtStrings.splitSkipEmpty(q.valueFully("local_address").replace(" ", "").replace('-', ','), ",")
            q.has("address") -> address = QtStrings.splitSkipEmpty(q.valueFully("address").replace(" ", ""), ",")
            q.has("ip") -> address = QtStrings.splitSkipEmpty(q.valueFully("ip").replace(" ", ""), ",")
        }

        if (q.has("mtu")) {
            val value = q.valueFully("mtu").trim()
            if (wireguardKeep("mtu", value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) mtu = QtStrings.toInt(value)
        }
        if (q.has("workers")) {
            val value = q.valueFully("workers").trim()
            if (wireguardKeep("workers", value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) worker_count = QtStrings.toInt(value)
        }
        if (q.has("use_system_interface")) system = q.valueFully("use_system_interface") == "true"
        if (q.has("udp_timeout")) {
            val value = q.valueFully("udp_timeout").trim()
            if (wireguardKeep("udp_timeout", value, Validation.Rules.duration())) udp_timeout = value
        }

        if (q.valueFully("enable_amnezia") == "true") enable_amnezia = true
        fun intKey(key: String, max: Long, mustMatchServer: Boolean, set: (Int) -> Unit) {
            if (!q.has(key)) return
            set(wireguardAmneziaInt(key, q.valueFully(key), max, mustMatchServer))
            enable_amnezia = true
        }
        fun strKey(key: String, set: (String) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(q.valueFully(key))
            enable_amnezia = true
            return true
        }
        intKey("jc", WIREGUARD_INT_MAX, false) { jc = it }
        intKey("jmin", WIREGUARD_INT_MAX, false) { jmin = it }
        intKey("jmax", WIREGUARD_INT_MAX, false) { jmax = it }
        intKey("s1", 65535, true) { s1 = it }
        intKey("s2", 65535, true) { s2 = it }
        intKey("s3", 65535, true) { s3 = it }
        intKey("s4", 65535, true) { s4 = it }
        strKey("h1") { h1 = it.trim() }
        strKey("h2") { h2 = it.trim() }
        strKey("h3") { h3 = it.trim() }
        strKey("h4") { h4 = it.trim() }
        strKey("i1") { i1 = it }
        strKey("i2") { i2 = it }
        strKey("i3") { i3 = it }
        strKey("i4") { i4 = it }
        strKey("i5") { i5 = it }
        if (!strKey("header_protection_key") { header_protection_key = it }) strKey("headerprotectionkey") { header_protection_key = it }
        if (!strKey("content_padding_addition") { content_padding_addition = it }) strKey("contentpaddingaddition") { content_padding_addition = it }
        strKey("rekey_after_time") { rekey_after_time = it }
        strKey("rekey_timeout") { rekey_timeout = it }
        strKey("reject_after_time") { reject_after_time = it }
        strKey("keepalive_timeout") { keepalive_timeout = it }
        strKey("max_handshake_attempts") { max_handshake_attempts = it }
        strKey("random_trailers") { random_trailers = parseAmneziaBool(it) }
        strKey("disable_cookies") { disable_cookies = parseAmneziaBool(it) }

        fixAddress()
        address = wireguardDropBadAddresses(address)
        return !(private_key.isEmpty() || peer.public_key.isEmpty() || server.isEmpty())
    }

    // The INI half of wireguard::ParseFromLink: keys are matched lower-cased and reported as written; no base parse,
    // so the name and dial fields stay untouched.
    private fun parseIni(text: String): Boolean {
        var peers = 0
        val allowedIps = ArrayList<String>()
        for (line in QtStrings.split(text, "\n")) {
            // wg and wg-quick cut every line at '#'; keys and signature tags never contain one.
            val comment = line.indexOf('#')
            val trimmed = (if (comment < 0) line else line.substring(0, comment)).trim()
            if (trimmed.isEmpty() || trimmed.startsWith(';')) continue
            if (trimmed == "[Peer]") {
                peers++
                continue
            }
            if (trimmed == "[Interface]") continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val rawKey = trimmed.substring(0, eqIdx).trim()
            val key = rawKey.lowercase()
            val value = trimmed.substring(eqIdx + 1).trim()
            when (key) {
                "privatekey" -> private_key = value
                // wg-quick allows the key to repeat, e.g. one line per address family.
                "address" -> address.addAll(QtStrings.splitSkipEmpty(value.replace(" ", ""), ","))
                "mtu" -> if (wireguardKeep(rawKey, value, Validation.Rules.int(0, WIREGUARD_INT_MAX))) mtu = QtStrings.toInt(value)
                "publickey" -> peer.public_key = value
                "presharedkey", "preshared_key", "psk" -> peer.pre_shared_key = value
                "persistentkeepalive", "persistent_keepalive" ->
                    if (wireguardKeep(rawKey, value) { WireGuardChecks.wireguardKeepalive(it) }) peer.persistent_keepalive = value
                "endpoint" -> {
                    val lastColon = value.lastIndexOf(':')
                    if (lastColon != -1) {
                        var host = value.substring(0, lastColon).trim()
                        if (host.startsWith('[') && host.endsWith(']')) {
                            host = if (host.length >= 2) host.substring(1, host.length - 1) else ""
                        }
                        val portText = value.substring(lastColon + 1).trim()
                        val message = Validation.port(portText)
                        if (message.isNotEmpty()) ParseDiag.error(rawKey, message, value)
                        val port = QtStrings.toInt(portText)
                        peer.address = host
                        peer.port = if (port > 0) port else 51820
                        server = peer.address
                        serverPort = peer.port
                    }
                }
                "jc" -> { jc = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "jmin" -> { jmin = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "jmax" -> { jmax = wireguardAmneziaInt(rawKey, value, WIREGUARD_INT_MAX, false); enable_amnezia = true }
                "s1" -> { s1 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s2" -> { s2 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s3" -> { s3 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "s4" -> { s4 = wireguardAmneziaInt(rawKey, value, 65535, true); enable_amnezia = true }
                "h1" -> { h1 = value; enable_amnezia = true }
                "h2" -> { h2 = value; enable_amnezia = true }
                "h3" -> { h3 = value; enable_amnezia = true }
                "h4" -> { h4 = value; enable_amnezia = true }
                "i1" -> { i1 = value; enable_amnezia = true }
                "i2" -> { i2 = value; enable_amnezia = true }
                "i3" -> { i3 = value; enable_amnezia = true }
                "i4" -> { i4 = value; enable_amnezia = true }
                "i5" -> { i5 = value; enable_amnezia = true }
                "headerprotectionkey", "header_protection_key" -> { header_protection_key = value; enable_amnezia = true }
                "contentpaddingaddition", "content_padding_addition" -> { content_padding_addition = value; enable_amnezia = true }
                "rekeyaftertime", "rekey_after_time" -> { rekey_after_time = value; enable_amnezia = true }
                "rekeytimeout", "rekey_timeout" -> { rekey_timeout = value; enable_amnezia = true }
                "rejectaftertime", "reject_after_time" -> { reject_after_time = value; enable_amnezia = true }
                "keepalivetimeout", "keepalive_timeout" -> { keepalive_timeout = value; enable_amnezia = true }
                "maxhandshakeattempts", "max_handshake_attempts" -> { max_handshake_attempts = value; enable_amnezia = true }
                "randomtrailers", "random_trailers" -> { random_trailers = parseAmneziaBool(value); enable_amnezia = true }
                "disablecookies", "disable_cookies" -> { disable_cookies = parseAmneziaBool(value); enable_amnezia = true }
                "dns" -> ParseDiag.warn(rawKey, vtr(R.string.validation_wg_dns_ignored), value)
                "allowedips" -> allowedIps.addAll(QtStrings.splitSkipEmpty(value.replace(" ", ""), ","))
                "listenport", "table", "fwmark", "saveconfig", "preup", "postup", "predown", "postdown" -> {}
                else -> ParseDiag.warn(rawKey, vtr(R.string.validation_wg_unknown_setting))
            }
        }
        if (peers > 1) ParseDiag.warn("[Peer]", vtr(R.string.validation_wg_peer_sections, peers.toString()))
        if (allowedIps.isNotEmpty() && "0.0.0.0/0" !in allowedIps) {
            ParseDiag.warn("AllowedIPs", vtr(R.string.validation_wg_allowed_ips_ignored), allowedIps.joinToString(", "))
        }
        fixAddress()
        address = wireguardDropBadAddresses(address)
        if (private_key.isEmpty() || peer.public_key.isEmpty()) return false
        if (server.isEmpty()) {
            ParseDiag.error("Endpoint", vtr(R.string.validation_wg_no_endpoint))
            return false
        }
        return true
    }

    /** wireguard::ParseFromJson: the DB loader too, so only an import drops values. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "wireguard") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peers") && obj.isArray("peers")) {
            val peers = obj.array("peers")
            if (peers.isNotEmpty()) {
                peer.parseFromJson(peers[0] as? JsonObject ?: JsonObject())
                if (peers.size > 1 && ParseDiag.active()) {
                    ParseDiag.warn("peers", vtr(R.string.validation_wg_peers, peers.size.toString()))
                }
            }
        }
        // Listable: the core accepts a bare string as well as an array.
        if (obj.contains("address")) address = jsonStringList(obj["address"], "address")
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("workers")) worker_count = obj.int("workers")
        else if (obj.contains("worker_count")) worker_count = obj.int("worker_count")
        if (obj.contains("udp_timeout")) {
            val value = obj.string("udp_timeout").trim()
            if (wireguardKeep("udp_timeout", value, Validation.Rules.duration())) udp_timeout = value
        }
        if (obj.contains("amnezia_wg")) amneziaFromJson(obj.obj("amnezia_wg"))
        fixAddress()
        address = wireguardDropBadAddresses(address)
        return true
    }

    /** wireguard::ExportToLink: `wg://<peer address>:<peer port>?...`, dial items before the peer items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("wg")
        url.host = peer.address
        url.port = peer.port
        if (name.isNotEmpty()) url.fragment = name

        if (private_key.isNotEmpty()) url.addQueryItem("private_key", private_key)
        if (address.isNotEmpty()) url.addQueryItem("local_address", address.joinToString("-"))
        if (mtu > 0 && mtu != 1420) url.addQueryItem("mtu", mtu.toString())
        if (system) url.addQueryItem("use_system_interface", "true")
        if (worker_count > 0) url.addQueryItem("workers", worker_count.toString())
        if (udp_timeout.isNotEmpty()) url.addQueryItem("udp_timeout", udp_timeout)

        if (enable_amnezia) {
            url.addQueryItem("enable_amnezia", "true")
            if (jc > 0) url.addQueryItem("jc", jc.toString())
            if (jmin > 0) url.addQueryItem("jmin", jmin.toString())
            if (jmax > 0) url.addQueryItem("jmax", jmax.toString())
            if (s1 > 0) url.addQueryItem("s1", s1.toString())
            if (s2 > 0) url.addQueryItem("s2", s2.toString())
            if (s3 > 0) url.addQueryItem("s3", s3.toString())
            if (s4 > 0) url.addQueryItem("s4", s4.toString())
            if (h1.isNotEmpty()) url.addQueryItem("h1", h1)
            if (h2.isNotEmpty()) url.addQueryItem("h2", h2)
            if (h3.isNotEmpty()) url.addQueryItem("h3", h3)
            if (h4.isNotEmpty()) url.addQueryItem("h4", h4)
            if (i1.isNotEmpty()) url.addQueryItem("i1", i1)
            if (i2.isNotEmpty()) url.addQueryItem("i2", i2)
            if (i3.isNotEmpty()) url.addQueryItem("i3", i3)
            if (i4.isNotEmpty()) url.addQueryItem("i4", i4)
            if (i5.isNotEmpty()) url.addQueryItem("i5", i5)
            if (header_protection_key.isNotEmpty()) url.addQueryItem("header_protection_key", header_protection_key)
            if (content_padding_addition.isNotEmpty()) url.addQueryItem("content_padding_addition", content_padding_addition)
            if (rekey_after_time.isNotEmpty()) url.addQueryItem("rekey_after_time", rekey_after_time)
            if (rekey_timeout.isNotEmpty()) url.addQueryItem("rekey_timeout", rekey_timeout)
            if (reject_after_time.isNotEmpty()) url.addQueryItem("reject_after_time", reject_after_time)
            if (keepalive_timeout.isNotEmpty()) url.addQueryItem("keepalive_timeout", keepalive_timeout)
            if (max_handshake_attempts.isNotEmpty()) url.addQueryItem("max_handshake_attempts", max_handshake_attempts)
            if (random_trailers) url.addQueryItem("random_trailers", "true")
            if (disable_cookies) url.addQueryItem("disable_cookies", "true")
        }

        url.addQueryItems(baseLinkQuery())
        url.addQueryItems(peer.exportToLink())
        return url.build()
    }

    /** wireguard::ExportToJson: no server/server_port; the peer carries them. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.exportToJson())
        writeFields(obj, "worker_count")
        val peerObj = peer.exportToJson()
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return obj
    }

    /** wireguard::Build: the core's key for the worker count is `workers`. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))
        writeFields(obj, "workers")
        val peerObj = peer.build(ctx)
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject, workersKey: String) {
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (system) obj["system"] = system
        if (worker_count > 0) obj[workersKey] = worker_count
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        val amnezia = amneziaToJson()
        if (amnezia.isNotEmpty()) obj["amnezia_wg"] = amnezia
    }

    /** wireguard::SetAddress and friends: the peer holds the address the profile list and the DNS resolver work with. */
    override fun setAddress(newAddr: String) {
        peer.address = newAddr
    }

    override fun getAddress(): String = peer.address

    override fun setPort(newPort: Int) {
        peer.port = newPort
    }

    override fun getPort(): String = peer.port.toString()

    /** wireguard::DisplayAddress. */
    override fun displayAddress(): String = Hosts.displayAddress(peer.address, peer.port)

    /** wireguard::DisplayType. */
    override fun displayType(): String = "WireGuard"

    /** wireguard::GetSecurity. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", if (enable_amnezia) "AmneziaWG" else "", SecurityLevel.Secure)

    /** wireguard::IsEndpoint. */
    override fun isEndpoint(): Boolean = true

    /** wireguard::Validate: key lengths and every AmneziaWG value are only checked when the endpoint first dials, so this is the early signal. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "private_key", Validation.base64Key(private_key, 32))
        issues.addIssue(Severity.Error, "public_key", Validation.base64Key(peer.public_key, 32))
        issues.addIssue(Severity.Error, "pre_shared_key", Validation.base64Key(peer.pre_shared_key, 32, empty = Empty.Allowed))
        if (peer.reserved.isNotEmpty()) {
            issues.addIssue(Severity.Error, "reserved", WireGuardChecks.wireguardReserved(peer.reserved.joinToString(",")))
        }
        issues.addIssue(
            Severity.Error, "persistent_keepalive_interval", WireGuardChecks.wireguardKeepalive(peer.persistent_keepalive),
            peer.persistent_keepalive,
        )
        if (address.isEmpty()) {
            issues.addIssue(Severity.Error, "address", vtr(R.string.validation_wg_address_required))
        } else {
            issues.addIssue(
                Severity.Error, "address",
                Validation.eachItem(address.joinToString(","), Validation.Split.Comma, Validation.Rules.cidr()),
            )
        }
        issues.addIssue(Severity.Error, "udp_timeout", Validation.duration(udp_timeout))
        if (enable_amnezia) issues.addAll(amneziaIssues())
    }

    /** wireguard::AmneziaIssues: the AmneziaWG part of [validate], keyed by amnezia_wg field; it applies only with enable_amnezia. */
    fun amneziaIssues(): MutableList<Issue> {
        val issues = ArrayList<Issue>()
        for ((field, value) in listOf("s1" to s1, "s2" to s2, "s3" to s3, "s4" to s4)) {
            // Only positive values are emitted.
            if (value > 65535) issues.addIssue(Severity.Error, field, Validation.int(value.toString(), 0, 65535))
        }
        val headers = listOf(h1, h2, h3, h4)
        for ((i, header) in headers.withIndex()) {
            issues.addIssue(Severity.Error, "h${i + 1}", WireGuardChecks.amneziaRange(header), header)
        }
        issues.addIssue(Severity.Error, "h1", WireGuardChecks.amneziaHeaders(headers))
        for ((i, signature) in listOf(i1, i2, i3, i4, i5).withIndex()) {
            issues.addIssue(Severity.Error, "i${i + 1}", WireGuardChecks.amneziaSignature(signature))
        }
        issues.addIssue(Severity.Error, "header_protection_key", Validation.base64Key(header_protection_key, 32, empty = Empty.Allowed))
        issues.addIssue(
            Severity.Error, "header_protection_key",
            WireGuardChecks.amneziaHeaderProtection(header_protection_key, listOf(s1.toLong(), s2.toLong(), s3.toLong(), s4.toLong())),
        )
        for ((field, value) in listOf(
            "content_padding_addition" to content_padding_addition, "rekey_after_time" to rekey_after_time,
            "rekey_timeout" to rekey_timeout, "reject_after_time" to reject_after_time,
            "keepalive_timeout" to keepalive_timeout, "max_handshake_attempts" to max_handshake_attempts,
        )) {
            issues.addIssue(Severity.Error, field, WireGuardChecks.amneziaRange(value), value)
        }
        issues.addIssue(Severity.Warning, "jmin", WireGuardChecks.amneziaJunkSizes(jmin.toLong(), jmax.toLong()))
        return issues
    }

    /** wireguard::AmneziaToJson. */
    private fun amneziaToJson(): JsonObject {
        val obj = JsonObject()
        if (!enable_amnezia) return obj
        if (jc > 0) obj["jc"] = jc
        if (jmin > 0) obj["jmin"] = jmin
        if (jmax > 0) obj["jmax"] = jmax
        if (s1 > 0) obj["s1"] = s1
        if (s2 > 0) obj["s2"] = s2
        if (s3 > 0) obj["s3"] = s3
        if (s4 > 0) obj["s4"] = s4
        if (h1.isNotEmpty()) obj["h1"] = h1
        if (h2.isNotEmpty()) obj["h2"] = h2
        if (h3.isNotEmpty()) obj["h3"] = h3
        if (h4.isNotEmpty()) obj["h4"] = h4
        if (i1.isNotEmpty()) obj["i1"] = i1
        if (i2.isNotEmpty()) obj["i2"] = i2
        if (i3.isNotEmpty()) obj["i3"] = i3
        if (i4.isNotEmpty()) obj["i4"] = i4
        if (i5.isNotEmpty()) obj["i5"] = i5
        if (header_protection_key.isNotEmpty()) obj["header_protection_key"] = header_protection_key
        if (content_padding_addition.isNotEmpty()) obj["content_padding_addition"] = content_padding_addition
        if (rekey_after_time.isNotEmpty()) obj["rekey_after_time"] = rekey_after_time
        if (rekey_timeout.isNotEmpty()) obj["rekey_timeout"] = rekey_timeout
        if (reject_after_time.isNotEmpty()) obj["reject_after_time"] = reject_after_time
        if (keepalive_timeout.isNotEmpty()) obj["keepalive_timeout"] = keepalive_timeout
        if (max_handshake_attempts.isNotEmpty()) obj["max_handshake_attempts"] = max_handshake_attempts
        if (random_trailers) obj["random_trailers"] = true
        if (disable_cookies) obj["disable_cookies"] = true
        return obj
    }

    /** wireguard::AmneziaFromJson: any non-empty block switches AmneziaWG on. */
    private fun amneziaFromJson(obj: JsonObject) {
        if (obj.isEmpty()) return
        enable_amnezia = true
        if (obj.contains("jc")) jc = obj.int("jc")
        if (obj.contains("jmin")) jmin = obj.int("jmin")
        if (obj.contains("jmax")) jmax = obj.int("jmax")
        if (obj.contains("s1")) s1 = obj.int("s1")
        if (obj.contains("s2")) s2 = obj.int("s2")
        if (obj.contains("s3")) s3 = obj.int("s3")
        if (obj.contains("s4")) s4 = obj.int("s4")
        if (obj.contains("h1")) h1 = obj.string("h1").trim()
        if (obj.contains("h2")) h2 = obj.string("h2").trim()
        if (obj.contains("h3")) h3 = obj.string("h3").trim()
        if (obj.contains("h4")) h4 = obj.string("h4").trim()
        if (obj.contains("i1")) i1 = obj.string("i1")
        if (obj.contains("i2")) i2 = obj.string("i2")
        if (obj.contains("i3")) i3 = obj.string("i3")
        if (obj.contains("i4")) i4 = obj.string("i4")
        if (obj.contains("i5")) i5 = obj.string("i5")
        if (obj.contains("header_protection_key")) header_protection_key = obj.string("header_protection_key")
        if (obj.contains("content_padding_addition")) content_padding_addition = amneziaRange(obj["content_padding_addition"])
        if (obj.contains("rekey_after_time")) rekey_after_time = amneziaRange(obj["rekey_after_time"])
        if (obj.contains("rekey_timeout")) rekey_timeout = amneziaRange(obj["rekey_timeout"])
        if (obj.contains("reject_after_time")) reject_after_time = amneziaRange(obj["reject_after_time"])
        if (obj.contains("keepalive_timeout")) keepalive_timeout = amneziaRange(obj["keepalive_timeout"])
        if (obj.contains("max_handshake_attempts")) max_handshake_attempts = amneziaRange(obj["max_handshake_attempts"])
        if (obj.contains("random_trailers")) random_trailers = obj.bool("random_trailers")
        if (obj.contains("disable_cookies")) disable_cookies = obj.bool("disable_cookies")
    }

    /** wireguard::AmneziaRangeFromJson: ranges are written as strings, but sing-box also accepts a bare number. */
    private fun amneziaRange(value: Any?): String = value as? String ?: JsonValues.toInt(value).toString()

    /** wireguard::FixAddress: bare IPs get /32 or /128 (the core needs a prefix); empty items are dropped. */
    fun fixAddress() {
        val fixed = ArrayList<String>(address.size)
        for (addr in address) {
            var trimmed = addr.trim()
            if (trimmed.isEmpty()) continue
            if (!trimmed.contains("/")) trimmed += if (trimmed.contains(":")) "/128" else "/32"
            fixed.add(trimmed)
        }
        address = fixed
    }

    companion object {
        /** ParseAmneziaBool (wireguard.cpp). */
        @JvmStatic
        fun parseAmneziaBool(value: String): Boolean {
            val trimmed = value.trim().lowercase()
            return trimmed == "true" || trimmed == "1" || trimmed == "on" || trimmed == "yes"
        }
    }
}
