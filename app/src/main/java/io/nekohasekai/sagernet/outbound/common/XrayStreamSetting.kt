package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.LinkQuery
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.ui.json.engine.JsonTree
import io.nekohasekai.sagernet.ui.json.engine.JsonType
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.readQueryInt
import io.nekohasekai.sagernet.validation.vtr
import java.util.Locale
import kotlin.reflect.KMutableProperty1

/** XrayNetworks (xrayStreamSetting.h). */
val xrayNetworks = listOf("raw", "xhttp", "ws", "httpupgrade", "grpc", "kcp", "xdrive")

/** XrayXDriveServices (xrayStreamSetting.h): Xray still takes "Google Drive" for google-drive; XrayXDrive.parseFromJson folds it. */
val xrayXDriveServices = listOf("google-drive", "s3", "template", "local")
val xrayXhttpModes = listOf("auto", "packet-up", "stream-up", "stream-one")
val xrayXhttpMetaPlacements = listOf("", "path", "cookie", "header", "query")
val xrayXhttpUplinkDataPlacements = listOf("", "auto", "body", "cookie", "header")
val xrayXhttpUplinkMethods = listOf("", "POST", "PUT", "PATCH", "GET")

/** NormalizeXrayNetwork (xrayStreamSetting.cpp): the aliases Xray itself accepts (tcp, splithttp, websocket, mkcp), case-insensitively. */
fun normalizeXrayNetwork(network: String): String = when (val lower = network.lowercase()) {
    "tcp" -> "raw"
    "splithttp" -> "xhttp"
    "websocket" -> "ws"
    "mkcp" -> "kcp"
    else -> lower
}

/** XrayLower (xrayStreamSetting.cpp): Go's strings.ToLower for comparing against ASCII names (U+0130 -> i, Kelvin sign -> k). */
fun xrayLower(text: String): String {
    val out = StringBuilder(text.length)
    for (c in text) {
        out.append(
            when {
                c in 'A'..'Z' -> c + 32
                c.code == 0x0130 -> 'i'
                c.code == 0x212A -> 'k'
                else -> c
            },
        )
    }
    return out.toString()
}

enum class XrayLegProtection { Verified, Unverified, Plain }

// An object inside a local copy, with its path for messages ("" or ending in '.').
private class XrayNode(@JvmField val obj: JsonObject, @JvmField val at: String)

private fun xrayRoot(obj: JsonObject, at: String = ""): List<XrayNode> = listOf(XrayNode(obj, at))

// Every key Xray's case-folding decoder reads as name, in QJsonObject order.
private fun xrayKeysNamed(o: JsonObject, name: String): List<String> = o.sortedKeys().filter { it.equals(name, ignoreCase = true) }

private fun xrayRemoveNamed(o: JsonObject, name: String): Boolean {
    val keys = xrayKeysNamed(o, name)
    for (key in keys) o.remove(key)
    return keys.isNotEmpty()
}

private fun xrayUnset(value: Any?): Boolean = value == null || value === JsonNull

// dialerProxy names an outbound of the source config, and domainStrategy would outrank the core's resolver; Xray folds case.
private fun xrayStripDialerKeys(sockopt: JsonObject): Boolean {
    val proxy = xrayRemoveNamed(sockopt, "dialerProxy")
    val strategy = xrayRemoveNamed(sockopt, "domainStrategy")
    return proxy || strategy
}

/**
 * xrayStreamEditLive: Go merges every spelling of a pointer field, holders in turn, into one struct and a null
 * discards the earlier ones; those are dropped, any other non-object is skipped, and [edit] gets the rest in place.
 */
private fun xrayEditLive(holders: List<XrayNode>, name: String, create: Boolean, edit: (List<XrayNode>) -> Unit) {
    val spellings = ArrayList<Pair<XrayNode, String>>()
    for (holder in holders) for (key in xrayKeysNamed(holder.obj, name)) spellings.add(holder to key)
    var discarded = 0
    for (i in spellings.indices) if (spellings[i].first.obj.isNull(spellings[i].second)) discarded = i + 1
    for (i in 0 until discarded) spellings[i].first.obj.remove(spellings[i].second)
    val live = spellings.drop(discarded).filterTo(ArrayList()) { it.first.obj[it.second] is JsonObject }
    if (live.isEmpty()) {
        if (!create || holders.isEmpty()) return
        holders.last().obj[name] = JsonObject()
        live.add(holders.last() to name)
    }
    edit(live.map { (holder, key) -> XrayNode(holder.obj.obj(key), holder.at + key + ".") })
}

/** xrayStreamLast: the value Go keeps across spellings (a null resets only a pointer field) and its path; null when unset. */
private fun xrayLast(nodes: List<XrayNode>, name: String, pointer: Boolean): Pair<Any?, String?> {
    var result: Any? = null
    var at: String? = null
    for (node in nodes) {
        for (key in xrayKeysNamed(node.obj, name)) {
            val value = node.obj[key]
            if (value === JsonNull && !pointer) continue
            result = value
            at = node.at + key
        }
    }
    return result to at
}

// A set method outranks network, and neither means tcp; only the XHTTP dialer reads downloadSettings.
private fun xrayDialsXhttp(streams: List<XrayNode>): Boolean {
    var transport = xrayLast(streams, "method", true).first
    if (xrayUnset(transport)) transport = xrayLast(streams, "network", true).first
    val name = xrayLower(transport as? String ?: "")
    return name == "xhttp" || name == "splithttp"
}

// xhttpSettings outranks splithttpSettings, and extra is a json.RawMessage: its last spelling, even a null one, replaces the rest.
private fun xrayEditDownloadLeg(streams: List<XrayNode>, edit: (List<XrayNode>) -> Unit) {
    fun fromHolders(holders: List<XrayNode>) {
        var extraHolder: XrayNode? = null
        var extraKey = ""
        for (holder in holders) {
            for (key in xrayKeysNamed(holder.obj, "extra")) {
                extraHolder = holder
                extraKey = key
            }
        }
        val holder = extraHolder
        if (holder == null) {
            xrayEditLive(holders, "downloadSettings", false, edit)
            return
        }
        val extra = holder.obj[extraKey] as? JsonObject ?: return
        xrayEditLive(xrayRoot(extra, holder.at + extraKey + "."), "downloadSettings", false, edit)
    }
    var xhttp = false
    xrayEditLive(streams, "xhttpSettings", false) {
        xhttp = true
        fromHolders(it)
    }
    if (!xhttp) xrayEditLive(streams, "splithttpSettings", false) { fromHolders(it) }
}

// Every spelling loses the key and it is written once, into the last live sockopt, so Go's merge keeps it.
private fun xraySetSockopt(streams: List<XrayNode>, key: String, value: String) {
    xrayEditLive(streams, "sockopt", true) { sockopts ->
        for (sockopt in sockopts) xrayRemoveNamed(sockopt.obj, key)
        sockopts.last().obj[key] = value
    }
}

// Xray's XHTTP dialer dereferences the download leg's destination and casts its transport settings unchecked: either crashes the core.
private fun xrayDownloadEndpoint(issues: MutableList<Issue>, leg: List<XrayNode>) {
    val at = leg.last().at
    val address = xrayLast(leg, "address", true)
    val port = xrayLast(leg, "port", false)
    val method = xrayLast(leg, "method", true)
    val transport = if (!xrayUnset(method.first)) method else xrayLast(leg, "network", true)
    val value = transport.first
    val transportName = when {
        value is String -> xrayLower(value)
        xrayUnset(value) -> "tcp"
        else -> ""
    }
    if (transportName != "xhttp" && transportName != "splithttp") {
        issues.addIssue(Severity.Error, transport.second ?: (at + "network"), vtr(R.string.validation_xray_download_network), value as? String ?: "")
    }
    val host = address.first
    if (host !is String || host.trim().isEmpty()) {
        issues.addIssue(Severity.Error, address.second ?: (at + "address"), vtr(R.string.validation_xray_download_address))
    }
    val portOk = when (val number = port.first) {
        is Long -> number in 1L..65535L
        is Double -> number == Math.floor(number) && number >= 1 && number <= 65535
        else -> false
    }
    if (!portOk) issues.addIssue(Severity.Error, port.second ?: (at + "port"), vtr(R.string.validation_xray_download_port))
}

/** XrayDownloadLeg: the spellings Go merges into the one XHTTP download leg Xray dials for an outbound (the object holding streamSettings), in decode order. */
fun xrayDownloadLeg(outbound: JsonObject): List<JsonObject> {
    val leg = ArrayList<JsonObject>()
    xrayEditLive(xrayRoot(outbound.copy()), "streamSettings", false) { streams ->
        if (!xrayDialsXhttp(streams)) return@xrayEditLive
        xrayEditDownloadLeg(streams) { spellings -> spellings.forEach { leg.add(it.obj) } }
    }
    return leg
}

/** XrayDownloadLegProtection: how Xray protects a download leg made of these spellings, and the server it dials. */
fun xrayDownloadLegProtection(leg: List<JsonObject>): Pair<XrayLegProtection, String> {
    val nodes = leg.map { XrayNode(it.copy(), "") }
    val server = xrayLast(nodes, "address", true).first as? String ?: ""
    val security = xrayLower(xrayLast(nodes, "security", false).first as? String ?: "")
    if (security == "reality") return XrayLegProtection.Verified to server
    if (security != "tls") return XrayLegProtection.Plain to server
    var insecure = false
    var pinned = false
    xrayEditLive(nodes, "tlsSettings", false) { tls ->
        insecure = xrayLast(tls, "allowInsecure", false).first as? Boolean ?: false
        // Xray verifies against a pin or the names it is given even with allowInsecure; it splits both on commas and skips blanks.
        for (name in listOf("pinnedPeerCertSha256", "verifyPeerCertByName")) {
            val entries = (xrayLast(tls, name, false).first as? String ?: "").split(',')
            pinned = pinned || entries.any { it.trim().isNotEmpty() }
        }
    }
    return (if (insecure && !pinned) XrayLegProtection.Unverified else XrayLegProtection.Verified) to server
}

/** WithXrayDownloadLeg (Outbound.cpp): a download leg weaker than the main stream, to a public server, weakens the profile. */
fun withXrayDownloadLeg(info: SecurityInfo, host: String, leg: List<JsonObject>): SecurityInfo {
    if (leg.isEmpty() || info.level == SecurityLevel.Unknown) return info
    val (protection, server) = xrayDownloadLegProtection(leg)
    if (protection == XrayLegProtection.Verified || Hosts.isPrivateHost(server)) return info
    val level = if (protection == XrayLegProtection.Plain) SecurityLevel.None else SecurityLevel.Weak
    // A label that already counts gives way only to a weaker leg.
    if (info.isDangerous && !Hosts.isPrivateHost(host) && info.level <= level) return info
    return info.copy(
        label = if (protection == XrayLegProtection.Plain) "Raw download" else "Insecure TLS download",
        level = level,
        caVerified = false,
        publicLeg = true,
    )
}

/** SetXrayDialerProxy: routes every leg of an Xray outbound through the outbound tagged [tag], the XHTTP download leg included. */
fun setXrayDialerProxy(outbound: JsonObject, tag: String) {
    xrayEditLive(xrayRoot(outbound), "streamSettings", true) { streams ->
        xraySetSockopt(streams, "dialerProxy", tag)
        // The download leg dials with its own sockopt unless the main one sets penetrate.
        xrayEditDownloadLeg(streams) { leg -> xraySetSockopt(leg, "dialerProxy", tag) }
    }
}

/** BindXrayDownloadLeg: binds the XHTTP download leg of an Xray outbound to [iface] unless it names an interface of its own. */
fun bindXrayDownloadLeg(outbound: JsonObject, iface: String) {
    if (iface.isEmpty()) return
    xrayEditLive(xrayRoot(outbound), "streamSettings", false) { streams ->
        xrayEditDownloadLeg(streams) { leg ->
            var pinned = false
            xrayEditLive(leg, "sockopt", false) { sockopts ->
                pinned = (xrayLast(sockopts, "interface", false).first as? String ?: "").isNotEmpty()
            }
            if (!pinned) xraySetSockopt(leg, "interface", iface)
        }
    }
}

/** StripXrayDialerState: removes dialerProxy and domainStrategy from every sockopt spelling of a stream object; true when any went. */
fun stripXrayDialerState(stream: JsonObject): Boolean {
    var changed = false
    for (key in xrayKeysNamed(stream, "sockopt")) {
        val sockopt = stream[key] as? JsonObject ?: continue
        if (xrayStripDialerKeys(sockopt)) changed = true
    }
    return changed
}

// Xray reads tlsSettings only while security is tls.
private fun xrayUseSystemCAIn(streams: List<XrayNode>) {
    if (xrayLower(xrayLast(streams, "security", false).first as? String ?: "") != "tls") return
    xrayEditLive(streams, "tlsSettings", true) { tls ->
        if (xrayLast(tls, "useSystemCA", false).first == null) tls.last().obj["useSystemCA"] = true
    }
}

/** UseXraySystemCA: turns on useSystemCA in every TLS block of an Xray outbound, the XHTTP download leg included, unless the block sets it itself. */
fun useXraySystemCA(outbound: JsonObject) {
    xrayEditLive(xrayRoot(outbound), "streamSettings", false) { streams ->
        xrayUseSystemCAIn(streams)
        xrayEditDownloadLeg(streams, ::xrayUseSystemCAIn)
    }
}

// Xray infra/conf ParseRangeString plus ensureOrder: (from, to), or null where Xray refuses the text.
private fun xrayStreamRange(text: String): Pair<Long, Long>? {
    val t = text.trim()
    if (t.isEmpty()) return 0L to 0L
    if (Validation.intOrRange(t, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), Empty.Allowed, true).isNotEmpty()) return null
    val dash = t.indexOf('-', if (t.startsWith('-')) 1 else 0)
    val first = Validation.toInt(if (dash < 0) t else t.substring(0, dash)) ?: 0L
    val second = Validation.toInt(if (dash < 0) t else t.substring(dash + 1)) ?: 0L
    return Pair(minOf(first, second), maxOf(first, second))
}

// Xray transport/internet/tls/tls.go: PresetFingerprints, ModernFingerprints and OtherFingerprints, matched lower-cased.
private val XRAY_STREAM_FINGERPRINTS = setOf(
    "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "unsafe",
    "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131", "hellochrome_133", "helloios_13",
    "helloios_14", "helloedge_106", "hellosafari_26_3", "hello360_11_0", "helloqq_11_1",
    "hellogolang", "hellorandomized", "hellorandomizedalpn", "hellorandomizednoalpn", "hellofirefox_auto",
    "hellofirefox_55", "hellofirefox_56", "hellofirefox_63", "hellofirefox_65", "hellofirefox_99", "hellofirefox_102",
    "hellofirefox_105", "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70", "hellochrome_72",
    "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102", "hellochrome_106_shuffle",
    "helloios_auto", "helloios_11_1", "helloios_12_1", "helloandroid_11_okhttp", "helloedge_85", "helloedge_auto",
    "hellosafari_16_0", "hellosafari_auto", "hello360_auto", "hello360_7_5", "helloqq_auto", "hellochrome_100_psk",
    "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf", "hellochrome_115_pq", "hellochrome_115_pq_psk",
    "hellochrome_120_pq",
)

private val XRAY_SESSION_ID_TABLES = mapOf(
    "ALPHABET" to "ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "Alphabet" to "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    "BASE36" to "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ",
    "Base62" to "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz",
    "HEX" to "0123456789ABCDEF",
    "alphabet" to "abcdefghijklmnopqrstuvwxyz",
    "base36" to "0123456789abcdefghijklmnopqrstuvwxyz",
    "hex" to "0123456789abcdef",
    "number" to "0123456789",
)

// Unknown names are dropped with a note so Xray's default applies instead of a start failure.
private fun xrayKnownFingerprint(raw: String, reality: Boolean, field: String): String {
    val fingerprint = raw.trim()
    return if (acceptOptional(field, XrayChecks.xrayFingerprint(fingerprint, reality), fingerprint)) fingerprint else ""
}

// Links carry headers as name|value|name|value.
private fun xrayLinkHeaders(q: LinkQuery): MutableList<String> {
    val raw = q.valueFully("headers")
    if (raw.isEmpty()) return ArrayList()
    val headers = QtStrings.split(raw, "|")
    if (headers.size % 2 == 0) return headers
    ParseDiag.warn("headers", vtr(R.string.validation_xray_headers_pairs))
    return ArrayList()
}

private fun xrayWarnUnparsableExtra(value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty() || JsonInput.parseObjectOrNull(trimmed)?.isEmpty() == true) return
    ParseDiag.warn("extra", vtr(R.string.validation_xray_extra_invalid))
}

// ParseDiag::Scope as a sink: values the caller has already read, and reported, elsewhere.
private inline fun <T> unreported(read: () -> T): T {
    val scope = ParseDiag.Scope()
    try {
        return read()
    } finally {
        scope.close()
    }
}

private fun xrayValue(obj: JsonObject, name: String, pointer: Boolean): Any? = xrayLast(xrayRoot(obj), name, pointer).first

private fun xrayArrayOf(items: List<Any>): JsonArray = JsonArray().also { array -> items.forEach { array.add(it) } }

private fun xrayMaskType(mask: Any?): String = xrayLower(xrayValue(mask as? JsonObject ?: JsonObject(), "type", false) as? String ?: "")

// Go reads a mask's keys case-insensitively, and LoadWithID lower-cases its type.
private fun xrayCanonicalMask(mask: JsonObject): Boolean {
    val original = mask.copy()
    val type = xrayValue(mask, "type", false)
    val settings = xrayValue(mask, "settings", true)
    xrayRemoveNamed(mask, "type")
    xrayRemoveNamed(mask, "settings")
    if (type != null) mask["type"] = if (type is String) xrayLower(type) else type
    if (!xrayUnset(settings)) mask["settings"] = settings
    return mask != original
}

private val XRAY_MKCP_HEADERS = listOf("dns", "dtls", "srtp", "utp", "wechat", "wireguard")

// Xray 26.5.29 folded these into mkcp-legacy and dropped the names; null leaves the mask as it is. An empty AES password has no mkcp-legacy form.
private fun xrayUpgradeMkcpMask(mask: JsonObject): JsonObject? {
    val type = mask.string("type")
    val settings = mask.obj("settings")
    val legacy = JsonObject()
    if (type == "mkcp-aes128gcm") {
        val password = xrayValue(settings, "password", false) as? String ?: ""
        if (password.isEmpty()) return null
        legacy["value"] = password
    } else if (type.startsWith("header-") && type.substring(7) in XRAY_MKCP_HEADERS) {
        legacy["header"] = type.substring(7)
        val domain = xrayValue(settings, "domain", false) as? String ?: ""
        if (type == "header-dns" && domain.isNotEmpty()) legacy["value"] = domain
    } else if (type != "mkcp-original") {
        return null
    }
    val upgraded = jsonObjectOf("type" to "mkcp-legacy")
    if (legacy.isNotEmpty()) upgraded["settings"] = legacy
    return upgraded
}

private val XRAY_LEGACY_XDNS_TYPES = mapOf("" to 16, "txt" to 16, "a" to 1, "aaaa" to 28)

// The "name[:txt|a|aaaa]" spec of the pre-26.9.30 xdns lists, which always queried with a 1232-byte EDNS0 payload.
private fun xrayLegacyXdnsDomain(spec: String): JsonObject? {
    var name = spec.trim()
    var method = ""
    val colon = name.lastIndexOf(':')
    if (colon >= 0) {
        method = name.substring(colon + 1).lowercase()
        name = name.substring(0, colon)
    }
    name = name.trimStart('.').trimEnd('.')
    val type = XRAY_LEGACY_XDNS_TYPES[method]
    if (name.isEmpty() || type == null) return null
    return jsonObjectOf("names" to JsonArray.of(name), "types" to JsonArray.of(type), "edns0" to 1232)
}

// 3x-ui's maskcompat.UpgradeLegacyXdns: Xray 26.10.5 refuses the string lists and silently ignores 26.9.30's objects.
private fun xrayUpgradeXdnsSettings(settings: JsonObject): Boolean {
    val rawDomains = settings.array("domains")
    val rawResolvers = settings.array("resolvers")
    fun legacyDomain(v: Any) = v is String || (v is JsonObject && v.contains("name"))
    fun legacyResolver(v: Any) = v is String || (v is JsonObject && !v.contains("addrs"))
    if (rawDomains.none { legacyDomain(it) } && rawResolvers.none { legacyResolver(it) }) return false

    val domains = JsonArray()
    val listed = HashSet<String>()
    fun addDomain(domain: JsonObject) {
        for (name in domain.array("names")) if (name is String) listed.add(xrayLower(name))
        domains.add(domain)
    }
    fun addLegacyDomain(domain: JsonObject) {
        if (xrayLower(domain.array("names").firstOrNull() as? String ?: "") !in listed) addDomain(domain)
    }
    for (entry in rawDomains) {
        if (entry is JsonObject) {
            val domain = entry.copy()
            if (domain.contains("name")) {
                val name = (domain.remove("name") as? String ?: "").trim()
                val names = domain.array("names")
                domain["names"] = if (name.isNotEmpty() && name !in names) xrayArrayOf(listOf<Any>(name) + names) else names
            }
            addDomain(domain)
        } else if (entry is String) {
            xrayLegacyXdnsDomain(entry)?.let { addLegacyDomain(it) }
        }
    }
    val resolvers = JsonArray()
    for (entry in rawResolvers) {
        if (entry is String) {
            val cut = entry.indexOf("+udp://")
            val addr = if (cut < 0) "" else entry.substring(cut + 7).trim()
            if (addr.isEmpty()) continue
            val domain = xrayLegacyXdnsDomain(entry.substring(0, cut)) ?: continue
            addLegacyDomain(domain)
            resolvers.add(jsonObjectOf("addrs" to JsonArray.of("udp://$addr")))
        } else if (entry is JsonObject && legacyResolver(entry)) {
            val kind = (entry["type"] as? String ?: "").trim().lowercase()
            val addr = (entry.obj("settings")["addr"] as? String ?: "").trim()
            if ((kind == "udp" || kind == "tcp") && addr.isNotEmpty()) resolvers.add(jsonObjectOf("addrs" to JsonArray.of("$kind://$addr")))
        } else {
            resolvers.add(entry)
        }
    }
    settings["domains"] = domains
    if (resolvers.isEmpty()) settings.remove("resolvers") else settings["resolvers"] = resolvers
    return true
}

private val XRAY_KCP_SAME_HEADERS = listOf("dns", "dtls", "srtp", "utp", "wireguard")

// The mKCP header types links and kcpSettings carried before finalmask, as the mkcp-legacy header ("" for none); null for one Xray never had.
private fun xrayKcpLegacyHeader(type: String): String? {
    val t = xrayLower(type.trim())
    if (t.isEmpty() || t == "none") return ""
    if (t == "wechat-video") return "wechat"
    return if (t in XRAY_KCP_SAME_HEADERS) t else null
}

// Old mKCP ran the seed's AES-128-GCM, else its original obfuscation, inside the header; xdns has to stay the last mask.
private fun xrayKcpLegacyMasks(finalmask: JsonObject, header: String, domain: String, seed: String) {
    if (header.isEmpty() && seed.isEmpty()) return
    val udp = finalmask.array("udp").toMutableList()
    var at = udp.size
    for ((i, mask) in udp.withIndex()) {
        val type = xrayMaskType(mask)
        if (type == "mkcp-legacy") return
        if (type == "xdns" && at == udp.size) at = i
    }
    val cipher = jsonObjectOf("type" to "mkcp-legacy")
    if (seed.isNotEmpty()) cipher["settings"] = jsonObjectOf("value" to seed)
    udp.add(at++, cipher)
    if (header.isNotEmpty()) {
        val settings = jsonObjectOf("header" to header)
        if (domain.isNotEmpty()) settings["value"] = domain
        udp.add(at, jsonObjectOf("type" to "mkcp-legacy", "settings" to settings))
    }
    finalmask["udp"] = xrayArrayOf(udp)
}

private fun xrayKcpImportLegacy(finalmask: JsonObject, field: String, type: String, domain: String, seed: String) {
    val header = xrayKcpLegacyHeader(type)
    if (header == null) ParseDiag.warn(field, vtr(R.string.validation_xray_kcp_header_unknown, type.trim()), type)
    xrayKcpLegacyMasks(finalmask, header.orEmpty(), if (header == "dns") domain else "", seed)
}

// filter only screens a listener's sources, and a client core older than Xray 26.10.21 refuses the type.
private fun xrayShareFinalmask(finalmask: JsonObject): JsonObject {
    val shared = finalmask.copy()
    for (layer in listOf("tcp", "udp")) {
        for (key in xrayKeysNamed(shared, layer)) {
            val masks = shared[key] as? JsonArray ?: continue
            val kept = masks.filter { xrayMaskType(it) != "filter" }
            if (kept.isEmpty()) shared.remove(key) else shared[key] = xrayArrayOf(kept)
        }
    }
    return shared
}

private fun xrayXdnsNames(settings: JsonObject): List<String> {
    val names = ArrayList<String>()
    for (domain in xrayValue(settings, "domains", true) as? JsonArray ?: JsonArray()) {
        for (name in xrayValue(domain as? JsonObject ?: JsonObject(), "names", true) as? JsonArray ?: JsonArray()) {
            val text = (name as? String ?: "").trim()
            if (text.isNotEmpty()) names.add(text)
        }
    }
    return names
}

private fun xrayXdnsIssues(issues: MutableList<Issue>, at: String, mask: JsonObject, resolversReplaced: Boolean) {
    val (settingsValue, settingsAt) = xrayLast(xrayRoot(mask, at), "settings", true)
    val settings = settingsValue as? JsonObject ?: JsonObject()
    val base = (settingsAt ?: (at + "settings")) + "."
    if (xrayXdnsNames(settings).isEmpty()) issues.addIssue(Severity.Error, base + "domains", vtr(R.string.validation_xray_xdns_domains))
    var resolved = false
    val resolvers = xrayValue(settings, "resolvers", true) as? JsonArray ?: JsonArray()
    for ((i, entry) in resolvers.withIndex()) {
        val resolver = entry as? JsonObject ?: JsonObject()
        val resolverAt = "${base}resolvers[$i]."
        var doh = false
        for (addr in xrayValue(resolver, "addrs", true) as? JsonArray ?: JsonArray()) {
            val text = (addr as? String ?: "").trim()
            if (text.isEmpty()) continue
            resolved = true
            val scheme = if (text.contains("://")) text.substring(0, text.indexOf("://")).lowercase() else "udp"
            doh = doh || scheme == "https"
            if (scheme != "udp" && scheme != "tcp" && scheme != "https") {
                issues.addIssue(Severity.Error, resolverAt + "addrs", vtr(R.string.validation_xray_xdns_resolver_scheme), text)
            }
        }
        if (!doh) continue
        val tls = xrayValue(resolver, "tlsConfig", true) as? JsonObject
        if (tls == null) {
            issues.addIssue(Severity.Error, resolverAt + "tlsConfig", vtr(R.string.validation_xray_xdns_doh_tls))
            continue
        }
        val alpn = xrayValue(tls, "alpn", true)
        val h3 = if (alpn is String) alpn == "h3" else alpn is JsonArray && alpn == JsonArray.of("h3")
        if (h3) {
            val quic = xrayValue(resolver, "quicConfig", true) as? JsonObject ?: JsonObject()
            if (xrayLower(xrayValue(quic, "congestion", false) as? String ?: "") == "brutal") {
                issues.addIssue(Severity.Error, resolverAt + "quicConfig.congestion", vtr(R.string.validation_xray_xdns_h3_brutal))
            }
            continue
        }
        fun text(name: String) = (xrayValue(tls, name, false) as? String ?: "").trim()
        if (text("serverName").isEmpty() && text("pinnedPeerCertSha256").isEmpty() && text("verifyPeerCertByName").isEmpty() &&
            xrayValue(tls, "allowInsecure", false) != true
        ) {
            issues.addIssue(Severity.Warning, resolverAt + "tlsConfig.serverName", vtr(R.string.validation_xray_xdns_server_name))
        }
    }
    if (!resolved && !resolversReplaced) issues.addIssue(Severity.Warning, base + "resolvers", vtr(R.string.validation_xray_xdns_resolvers))
}

private fun xrayStripResolverKeyLog(resolver: JsonObject, at: String, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (tlsKey in xrayKeysNamed(resolver, "tlsConfig")) {
        val tls = resolver[tlsKey] as? JsonObject ?: continue
        for (key in xrayKeysNamed(tls, "masterKeyLog")) {
            val path = (tls[key] as? String ?: "").trim()
            // Xray reads "none" as no file.
            if (path.isEmpty() || path == "none") continue
            issues.add(Issue(Severity.Warning, "$at$tlsKey.$key", vtr(R.string.validation_custom_writes_file), path))
            tls.remove(key)
            removed = true
        }
    }
    return removed
}

// A DoH address keeps the settings of the mask resolver that listed it, else gets the serverName Xray's DoH dialer needs.
private fun xrayXdnsSourcedResolvers(settings: JsonObject, addresses: JsonArray): JsonArray {
    val known = HashMap<String, JsonObject>()
    for (entry in xrayValue(settings, "resolvers", true) as? JsonArray ?: JsonArray()) {
        val resolver = (entry as? JsonObject)?.copy() ?: JsonObject()
        val addrs = xrayValue(resolver, "addrs", true) as? JsonArray ?: JsonArray()
        xrayRemoveNamed(resolver, "addrs")
        for (addr in addrs) {
            val key = ResolverSources.xdnsAddress(addr as? String ?: "")
            if (key.isNotEmpty() && key !in known) known[key] = resolver
        }
    }
    val plain = JsonArray()
    val resolvers = ArrayList<Any>()
    for (value in addresses) {
        val address = value as? String ?: ""
        if (!address.startsWith("https://")) {
            plain.add(address)
            continue
        }
        val resolver = known[address]?.copy() ?: jsonObjectOf("tlsConfig" to jsonObjectOf("serverName" to LinkParser.parse(address).host))
        resolver["addrs"] = JsonArray.of(address)
        resolvers.add(resolver)
    }
    if (plain.isNotEmpty()) resolvers.add(0, jsonObjectOf("addrs" to plain))
    return xrayArrayOf(resolvers)
}

private fun xrayXdnsReplaceResolvers(finalmask: JsonObject, addresses: JsonArray) {
    for (layerKey in xrayKeysNamed(finalmask, "udp")) {
        val masks = finalmask[layerKey] as? JsonArray ?: continue
        finalmask[layerKey] = xrayArrayOf(masks.map { entry ->
            if (xrayMaskType(entry) != "xdns") return@map entry
            val mask = (entry as JsonObject).copy()
            val settings = (xrayValue(mask, "settings", true) as? JsonObject)?.copy() ?: JsonObject()
            val resolvers = xrayXdnsSourcedResolvers(settings, addresses)
            xrayRemoveNamed(settings, "resolvers")
            settings["resolvers"] = resolvers
            xrayRemoveNamed(mask, "settings")
            mask["settings"] = settings
            mask
        })
    }
}

// QString::number(double): C's %.6g, which drops the trailing zeros Java's %g keeps.
private fun xrayQtNumber(value: Double): String {
    val text = String.format(Locale.ROOT, "%.6g", value)
    val e = text.indexOfFirst { it == 'e' || it == 'E' }
    val mantissa = if (e < 0) text else text.substring(0, e)
    val trimmed = if (mantissa.contains('.')) mantissa.trimEnd('0').trimEnd('.') else mantissa
    if (e < 0) return trimmed
    val exponent = text.substring(e + 1).toInt()
    return trimmed + "e" + (if (exponent < 0) "-" else "+") + Math.abs(exponent).toString().padStart(2, '0')
}

// Whole JSON numbers as Validation.int reads them; null and other non-numbers keep Xray's default.
private fun xrayKcpJsonText(value: Any?): String {
    if (value is String) return value.trim()
    val number = when (value) {
        is Long -> value.toDouble()
        is Double -> value
        else -> return ""
    }
    if (Math.abs(number) < 1e15 && number == Math.floor(number)) return number.toLong().toString()
    return xrayQtNumber(number)
}

private fun xrayKcpCheck(key: String, text: String, mtu: Long): String = when (key) {
    "mtu" -> XrayChecks.xrayKCPMtu(text)
    "tti" -> XrayChecks.xrayKCPTti(text)
    "cwndMultiplier" -> XrayChecks.xrayKCPCwndMultiplier(text)
    "maxSendingWindow" -> XrayChecks.xrayKCPMaxSendingWindow(text, mtu.toString())
    else -> XrayChecks.xrayKCPCapacity(text)
}

/** UpgradeXrayFinalmask (xrayStreamSetting.cpp): rewrites the spellings Xray now refuses or ignores (key case, old mask names, old xdns shapes); true when anything changed. */
fun upgradeXrayFinalmask(finalmask: JsonObject): Boolean {
    var changed = false
    for (layer in listOf("tcp", "udp")) {
        val keys = xrayKeysNamed(finalmask, layer)
        if (keys.isEmpty()) continue
        if (keys != listOf(layer)) {
            val value = xrayValue(finalmask, layer, true)
            xrayRemoveNamed(finalmask, layer)
            if (!xrayUnset(value)) finalmask[layer] = value
            changed = true
        }
        val masks = finalmask[layer] as? JsonArray ?: continue
        var edited = false
        val upgraded = masks.map { entry ->
            if (entry !is JsonObject) return@map entry
            var mask = entry.copy()
            var maskChanged = xrayCanonicalMask(mask)
            if (layer == "udp") {
                xrayUpgradeMkcpMask(mask)?.let {
                    mask = it
                    maskChanged = true
                }
                val settings = mask["settings"]
                if (mask["type"] == "xdns" && settings is JsonObject) {
                    val copy = settings.copy()
                    if (xrayUpgradeXdnsSettings(copy)) {
                        mask["settings"] = copy
                        maskChanged = true
                    }
                }
            }
            if (!maskChanged) return@map entry
            edited = true
            mask
        }
        if (!edited) continue
        finalmask[layer] = xrayArrayOf(upgraded)
        changed = true
    }
    return changed
}

/** StripXrayFinalmaskKeyLog (xrayStreamSetting.cpp): removes masterKeyLog from the TLS settings of the finalmask's xdns resolvers, in place; true when any went. */
fun stripXrayFinalmaskKeyLog(finalmask: JsonObject, at: String, issues: MutableList<Issue>): Boolean {
    var removed = false
    for (layerKey in xrayKeysNamed(finalmask, "udp")) {
        val masks = finalmask[layerKey] as? JsonArray ?: continue
        for ((i, entry) in masks.withIndex()) {
            if (xrayMaskType(entry) != "xdns") continue
            val mask = entry as JsonObject
            for (settingsKey in xrayKeysNamed(mask, "settings")) {
                val settings = mask[settingsKey] as? JsonObject ?: continue
                for (resolversKey in xrayKeysNamed(settings, "resolvers")) {
                    val resolvers = settings[resolversKey] as? JsonArray ?: continue
                    for ((j, resolver) in resolvers.withIndex()) {
                        if (resolver !is JsonObject) continue
                        if (xrayStripResolverKeyLog(resolver, "$at$layerKey[$i].$settingsKey.$resolversKey[$j].", issues)) removed = true
                    }
                }
            }
        }
    }
    return removed
}

/** XrayFinalmaskHasXdns (xrayStreamSetting.cpp). */
fun xrayFinalmaskHasXdns(finalmask: JsonObject): Boolean =
    (xrayValue(finalmask, "udp", true) as? JsonArray)?.any { xrayMaskType(it) == "xdns" } == true

/** The Checks of xrayStreamSetting.h, shared by the Xray stream editor and the import check; each returns "" when acceptable. */
object XrayChecks {
    @JvmStatic
    fun xrayNetwork(network: String): String {
        val t = network.trim()
        if (t.isEmpty() || normalizeXrayNetwork(t) in xrayNetworks) return ""
        return vtr(R.string.validation_xray_network, t)
    }

    @JvmStatic
    fun xraySecurity(security: String): String {
        val t = security.trim()
        if (t.lowercase() in listOf("", "none", "tls", "reality")) return ""
        return vtr(R.string.validation_xray_security, t)
    }

    @JvmStatic
    fun xrayRealityNetwork(security: String, network: String): String {
        val t = normalizeXrayNetwork(network.trim())
        if (security.trim().lowercase() != "reality" || t.isEmpty() || t == "raw" || t == "xhttp" || t == "grpc") return ""
        return vtr(R.string.validation_xray_reality_network)
    }

    @JvmStatic
    fun xrayFingerprint(fingerprint: String, reality: Boolean): String {
        val t = fingerprint.trim().lowercase()
        if (t.isEmpty()) return ""
        // XrayReality.build drops "unsafe"; Xray refuses hellogolang for REALITY.
        if (reality && t == "hellogolang") return vtr(R.string.validation_xray_reality_hellogolang)
        if (t in XRAY_STREAM_FINGERPRINTS) return ""
        return vtr(R.string.validation_xray_fingerprint, fingerprint.trim())
    }

    /** A warning: Xray skips unknown names. */
    @JvmStatic
    fun xrayCipherSuites(text: String): String {
        for (name in splitCipherSuites(text)) {
            if (name in TLS_SECURE_CIPHER_SUITES || name in TLS_INSECURE_CIPHER_SUITES) continue
            return vtr(R.string.validation_xray_cipher_unknown, name)
        }
        return ""
    }

    /** A warning: a uTLS hello ignores the list. */
    @JvmStatic
    fun xrayCipherSuitesNeedUnsafe(cipherSuites: String, fingerprint: String): String {
        if (splitCipherSuites(cipherSuites).isEmpty() || fingerprint.trim().lowercase() == TLS_UNSAFE_FINGERPRINT) return ""
        return vtr(R.string.validation_xray_cipher_need_unsafe)
    }

    @JvmStatic
    fun xrayPinnedSha256(text: String): String = Validation.eachItem(text, Validation.Split.Comma, { item ->
        if (Validation.hex(item.replace(":", ""), 64, 64, true, Empty.Required).isEmpty()) "" else vtr(R.string.validation_xray_pinned_sha256)
    })

    /** A warning: a bad list makes every connection fail. [serverName] is the effective SNI. */
    @JvmStatic
    fun xrayEchConfigList(text: String, serverName: String): String {
        val t = text.trim()
        if (t.isEmpty()) return ""
        if (!t.contains("://")) {
            if (Validation.base64(t).isEmpty()) return ""
            return vtr(R.string.validation_xray_ech_config)
        }
        // Xray transport/internet/tls/ech.go: "name+server", else the SNI when it is a domain.
        var name = ""
        val plus = t.indexOf('+')
        if (plus >= 0) {
            name = t.substring(0, plus).trim()
        } else {
            val sni = serverName.trim()
            if (sni.isNotEmpty() && Validation.ip(sni.replace("[", "").replace("]", "")).isNotEmpty()) name = sni
        }
        if (name.isNotEmpty()) return ""
        return vtr(R.string.validation_xray_ech_domain)
    }

    @JvmStatic
    fun xraySpiderX(spiderX: String): String {
        val t = spiderX.trim()
        if (t.isEmpty() || t.startsWith('/')) return ""
        return vtr(R.string.validation_xray_spiderx)
    }

    /** Xray infra/conf/transport_security.go: RawURLEncoding of a 1952-byte ML-DSA-65 public key. */
    @JvmStatic
    fun xrayMldsa65Verify(key: String): String = Validation.base64Key(key, 1952, true, false, Empty.Allowed)

    @JvmStatic
    fun xrayHeadersHost(pairs: List<String>): String {
        var i = 0
        while (i < pairs.size) {
            if (pairs[i].trim().equals("host", ignoreCase = true)) return vtr(R.string.validation_xray_headers_host)
            i += 2
        }
        return ""
    }

    /** Xray Int32Range: "n" or "a-b" (negatives allowed, reversed bounds swapped). */
    @JvmStatic
    fun xrayInt32Range(text: String): String =
        Validation.intOrRange(text, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(), Empty.Allowed, true)

    @JvmStatic
    fun xrayXPaddingBytes(text: String): String {
        val (from, to) = xrayStreamRange(text) ?: return xrayInt32Range(text)
        if ((from == 0L && to == 0L) || (from > 0 && to > 0)) return ""
        return vtr(R.string.validation_xray_padding_bounds)
    }

    @JvmStatic
    fun xrayXHTTPMode(mode: String): String = Validation.oneOf(mode, listOf("") + xrayXhttpModes)

    @JvmStatic
    fun xrayPaddingMethod(method: String): String = Validation.oneOf(method, listOf("", "repeat-x", "tokenish"))

    @JvmStatic
    fun xrayPaddingPlacement(placement: String): String =
        Validation.oneOf(placement, listOf("", "queryInHeader", "cookie", "header", "query"))

    @JvmStatic
    fun xrayMetaPlacement(placement: String): String = Validation.oneOf(placement, xrayXhttpMetaPlacements)

    @JvmStatic
    fun xrayUplinkDataPlacement(placement: String, mode: String): String {
        Validation.oneOf(placement, xrayXhttpUplinkDataPlacements).let { if (it.isNotEmpty()) return it }
        val t = placement.trim()
        // Literally packet-up: Xray checks before it resolves auto.
        if ((t == "cookie" || t == "header") && mode.trim() != "packet-up") return vtr(R.string.validation_xray_uplink_placement_mode)
        return ""
    }

    @JvmStatic
    fun xrayUplinkMethod(method: String, mode: String): String {
        if (method.trim().uppercase() != "GET" || mode.trim() == "packet-up") return ""
        return vtr(R.string.validation_xray_uplink_get_mode)
    }

    @JvmStatic
    fun xraySessionId(table: String, length: String): String {
        if (table.isEmpty()) return ""
        val bytes = (XRAY_SESSION_ID_TABLES[table] ?: table).toByteArray(Charsets.UTF_8)
        val (from, to) = xrayStreamRange(length) ?: return ""
        if (from <= 0) return vtr(R.string.validation_xray_session_length)
        // Xray roomSize: the sum of n^k over the length range must reach 2^31.
        val needed = 2147483648.0
        var room = 0.0
        if (bytes.size == 1) {
            room = (to - from + 1).toDouble()
        } else {
            var k = from
            while (k <= to && room < needed) {
                room += Math.pow(bytes.size.toDouble(), k.toDouble())
                k++
            }
        }
        if (room < needed) return vtr(R.string.validation_xray_session_room)
        if (bytes.any { (it.toInt() and 0xFF) >= 0x80 }) return vtr(R.string.validation_xray_session_ascii)
        return ""
    }

    @JvmStatic
    fun xrayXmuxExclusive(maxConnections: String, maxConcurrency: String): String {
        val connections = xrayStreamRange(maxConnections) ?: return ""
        val concurrency = xrayStreamRange(maxConcurrency) ?: return ""
        if (connections.second <= 0 || concurrency.second <= 0) return ""
        return vtr(R.string.validation_xray_xmux_exclusive)
    }

    /** Errors for an outbound's XHTTP download leg that would crash the core: no address, no port 1-65535, or another network. */
    @JvmStatic
    fun xrayXHTTPDownloadLeg(issues: MutableList<Issue>, at: String, outbound: JsonObject) {
        xrayEditLive(xrayRoot(outbound.copy(), at), "streamSettings", false) { streams ->
            if (!xrayDialsXhttp(streams)) return@xrayEditLive
            xrayEditDownloadLeg(streams) { leg -> xrayDownloadEndpoint(issues, leg) }
        }
    }

    @JvmStatic
    fun xrayXDriveService(service: String): String {
        if (service.trim() in xrayXDriveServices) return ""
        return vtr(R.string.validation_xray_xdrive_service)
    }

    @JvmStatic
    fun xrayXDriveFolder(service: String, folder: String): String {
        val s = service.trim()
        if (s == "s3") {
            val prefix = folder.trim()
            if (prefix.isEmpty()) return vtr(R.string.validation_xray_xdrive_s3_prefix_empty)
            if (xdriveS3Name(prefix.removeSuffix("/"))) return ""
            return vtr(R.string.validation_xray_xdrive_s3_prefix)
        }
        if (folder.trim().isNotEmpty() || (s != "google-drive" && s != "local")) return ""
        if (s == "local") return vtr(R.string.validation_xray_xdrive_folder_local)
        return vtr(R.string.validation_xray_xdrive_folder_drive)
    }

    /**
     * google-drive takes exactly ClientID, ClientSecret and RefreshToken, none empty (Xray drive.go);
     * s3 takes AccessKeyID and SecretAccessKey, then an optional SessionToken.
     */
    @JvmStatic
    fun xrayXDriveSecrets(service: String, secrets: List<String>): String {
        if (service.trim() == "s3") {
            if (secrets.size !in 2..3 || secrets[0].trim().isEmpty() || secrets[1].trim().isEmpty()) {
                return vtr(R.string.validation_xray_xdrive_s3_secrets)
            }
            if (!secrets.all { xdriveHeaderValue(it) }) return vtr(R.string.validation_xray_xdrive_s3_keys_control)
            if (secrets[0].any { it == '/' || it == ' ' || it == ',' || it == '\t' }) return vtr(R.string.validation_xray_xdrive_s3_access_key)
            return ""
        }
        if (service.trim() != "google-drive") return ""
        if (secrets.size == 3 && secrets.none { it.trim().isEmpty() }) return ""
        return vtr(R.string.validation_xray_xdrive_secrets)
    }

    @JvmStatic
    fun xrayXDriveTemplate(service: String, templateSpec: JsonObject): String {
        if (service.trim() != "template" || templateSpec.isNotEmpty()) return ""
        return vtr(R.string.validation_xray_xdrive_template)
    }

    /** A tuning value: Xray reads a uint32, and 0 keeps its default. */
    @JvmStatic
    fun xrayXDriveTuning(text: String): String = Validation.int(text, 0, XRAY_UINT32_MAX)

    @JvmStatic
    fun xrayXDriveFrontPort(port: String): String = Validation.port(port, Empty.Allowed)

    /** A warning: the front port is dialed only together with a front address. */
    @JvmStatic
    fun xrayXDriveFrontPortAlone(address: String, port: String): String {
        if (address.trim().isNotEmpty() || (Validation.toInt(port.trim()) ?: 0L) == 0L) return ""
        return vtr(R.string.validation_xray_xdrive_front_port_alone)
    }

    /** The endpoint QUrl's StrictMode parses as http or https, without credentials, query or fragment. */
    @JvmStatic
    fun xrayXDriveS3Endpoint(service: String, endpoint: String): String {
        if (service.trim() != "s3") return ""
        val text = endpoint.trim()
        if (text.isEmpty()) return vtr(R.string.validation_xray_xdrive_s3_endpoint_empty)
        val url = LinkParser.parse(text)
        val scheme = url.scheme.lowercase()
        // Brackets belong only to an IPv6 host; StrictMode refuses them in the path.
        val path = text.substringAfter("://", "").dropWhile { it != '/' }
        if (xrayStrictUrlText(text) && '[' !in path && ']' !in path && url.isValid && (scheme == "https" || scheme == "http") && url.host.isNotEmpty() &&
            url.userName.isEmpty() && url.password.isEmpty() && !text.contains('?') && !text.contains('#')
        ) {
            return ""
        }
        return vtr(R.string.validation_xray_xdrive_s3_endpoint)
    }

    @JvmStatic
    fun xrayXDriveS3Region(service: String, region: String): String {
        if (service.trim() != "s3") return ""
        if (xdriveHeaderValue(region) && region.none { it == '/' || it == ',' || it == ' ' || it == '\t' }) return ""
        return vtr(R.string.validation_xray_xdrive_s3_region)
    }

    /** Virtual-hosted addressing (path style off) puts the bucket in the endpoint's DNS name. */
    @JvmStatic
    fun xrayXDriveS3Bucket(service: String, bucket: String, endpoint: String, pathStyle: Boolean): String {
        if (service.trim() != "s3") return ""
        if (bucket.isEmpty()) return vtr(R.string.validation_xray_xdrive_s3_bucket_empty)
        if (bucket.contains('/') || !xdriveS3Name(bucket)) return vtr(R.string.validation_xray_xdrive_s3_bucket)
        if (pathStyle) return ""
        if (!XRAY_S3_DNS_BUCKET.matches(bucket) || bucket.contains("..") || bucket.startsWith('.') || bucket.endsWith('.')) {
            return vtr(R.string.validation_xray_xdrive_s3_bucket_dns)
        }
        if (Validation.ip(LinkParser.parse(endpoint.trim()).host).isEmpty()) return vtr(R.string.validation_xray_xdrive_s3_bucket_ip)
        return ""
    }

    /**
     * mKCP values, which Xray reads as uint32; an empty text keeps Xray's default. A full data segment is MTU bytes
     * in one of Xray's 8192-byte buffers; beyond that the core panics.
     */
    @JvmStatic
    fun xrayKCPMtu(text: String): String = Validation.int(text, 21, 8192)

    @JvmStatic
    fun xrayKCPTti(text: String): String = Validation.int(text, 10, 1000)

    @JvmStatic
    fun xrayKCPCapacity(text: String): String = Validation.int(text, 0, XRAY_UINT32_MAX)

    @JvmStatic
    fun xrayKCPCwndMultiplier(text: String): String = Validation.int(text, 1, XRAY_UINT32_MAX)

    /** Xray sends at most maxSendingWindow / MTU packets ahead and refuses a window that holds none; an empty MTU is Xray's 1350. */
    @JvmStatic
    fun xrayKCPMaxSendingWindow(text: String, mtu: String): String {
        val message = Validation.int(text, 0, XRAY_UINT32_MAX)
        if (message.isNotEmpty()) return message
        val window = Validation.toInt(text.trim())
        val packet = Validation.toInt(mtu.trim()) ?: XrayKcp.DEFAULT_MTU
        if (window == null || packet <= 0 || window >= packet) return ""
        return vtr(R.string.validation_xray_kcp_window_mtu, packet.toString())
    }

    /** XrayFinalmask: an xdns mask must be the last udp mask, and its domains and resolvers have to work; [resolversReplaced] when the profile's resolver lines replace the mask's. */
    @JvmStatic
    @JvmOverloads
    fun xrayFinalmask(issues: MutableList<Issue>, at: String, finalmask: JsonObject, resolversReplaced: Boolean = false) {
        val (udpValue, udpPath) = xrayLast(xrayRoot(finalmask.copy(), at), "udp", true)
        val udpAt = udpPath ?: (at + "udp")
        val udp = udpValue as? JsonArray ?: JsonArray()
        var xdns = -1
        var reported = false
        for ((i, mask) in udp.withIndex()) {
            val type = xrayMaskType(mask)
            if (xdns >= 0 && !reported && type != "filter") {
                // xdns dials its resolvers itself, so the masks Xray stacks under it never see a packet.
                issues.addIssue(Severity.Error, "$udpAt[$xdns].type", vtr(R.string.validation_xray_xdns_last))
                reported = true
            }
            if (type != "xdns") continue
            if (xdns < 0) xdns = i
            xrayXdnsIssues(issues, "$udpAt[$i].", mask as JsonObject, resolversReplaced)
        }
    }
}

private const val XRAY_UINT32_MAX = 4294967295L

private val XRAY_S3_DNS_BUCKET = Regex("^[a-z0-9.-]+$")

// Go httpguts.ValidHeaderFieldValue: no control characters but space and tab.
private fun xdriveHeaderValue(text: String): Boolean = text.none { (it.code < 0x20 && it != '\t') || it.code == 0x7f }

// Xray validS3Name: no control characters or backslashes, and no empty, "." or ".." component.
private fun xdriveS3Name(name: String): Boolean {
    if (name.any { it.code < 0x20 || it.code == 0x7f || it == '\\' }) return false
    return name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

// The characters QUrl's StrictMode refuses anywhere: controls, space, the unsafe delimiters and a '%' not starting an escape.
private fun xrayStrictUrlText(text: String): Boolean {
    for ((i, c) in text.withIndex()) {
        if (c.code < 0x21 || c.code == 0x7f || c in "\"<>\\^`{|}") return false
        if (c == '%' && (i + 2 >= text.length || !text[i + 1].isHexAscii() || !text[i + 2].isHexAscii())) return false
    }
    return true
}

private fun Char.isHexAscii(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

/** xrayTLS (xrayStreamSetting.h, xrayStreamSetting.cpp). No insecure flag is modelled. */
class XrayTls {
    @JvmField var serverName: String = ""
    @JvmField var pinnedPeerCertSha256: String = ""
    @JvmField var verifyPeerCertByName: String = ""
    @JvmField var alpn: MutableList<String> = ArrayList()
    @JvmField var fingerprint: String = ""
    /** Colon separated, as Xray takes it. */
    @JvmField var cipherSuites: String = ""
    /** Base64 ECHConfigList, or "<query domain>+<DoH url>" for Xray to fetch it itself. */
    @JvmField var echConfigList: String = ""
    /** Only stored; the global xray_use_system_ca also turns it on at build time ([useXraySystemCA]). */
    @JvmField var useSystemCA: Boolean = false

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayTLS::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("sni")) serverName = q.value("sni")
        if (q.has("peer")) serverName = q.value("peer")
        if (q.has("server_name")) serverName = q.value("server_name")
        if (q.has("pcs")) pinnedPeerCertSha256 = q.valueFully("pcs")
        if (q.has("vcn")) verifyPeerCertByName = q.valueFully("vcn")
        if (q.has("alpn")) alpn = QtStrings.splitList(q.valueFully("alpn"))
        if (q.has("fp")) fingerprint = xrayKnownFingerprint(q.value("fp"), false, "fp")
        if (q.has("cs")) cipherSuites = splitCipherSuites(q.valueFully("cs")).joinToString(":")
        if (q.has("ech")) echConfigList = q.valueFully("ech").trim()
        return true
    }

    /** xrayTLS::ParseFromJson: also the DB loader, so only an import normalises or drops values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("serverName")) serverName = obj.string("serverName")
        if (obj.contains("pinnedPeerCertSha256")) pinnedPeerCertSha256 = obj.string("pinnedPeerCertSha256")
        if (obj.contains("verifyPeerCertByName")) verifyPeerCertByName = obj.string("verifyPeerCertByName")
        val importing = ParseDiag.active()
        if (obj.contains("alpn")) {
            alpn = jsonStringList(obj["alpn"], "tlsSettings.alpn")
            // Xray's StringList also takes one comma-separated string.
            if (importing) alpn = QtStrings.splitList(alpn.joinToString(","))
        }
        if (obj.contains("fingerprint")) {
            val value = obj.string("fingerprint")
            fingerprint = if (importing) xrayKnownFingerprint(value, false, "tlsSettings.fingerprint") else value
        }
        if (obj.contains("cipherSuites")) cipherSuites = obj.string("cipherSuites")
        if (obj.contains("echConfigList")) echConfigList = obj.string("echConfigList")
        if (obj.contains("useSystemCA")) useSystemCA = obj.bool("useSystemCA")
        return true
    }

    /** xrayTLS::ParseFromClash. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        serverName = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        for (item in proxy.strings("alpn")) {
            val protocol = item.trim()
            if (protocol.isNotEmpty()) alpn.add(protocol)
        }
        // mihomo's fingerprint is the certificate pin in hex, which validate checks as for pcs; client-fingerprint is the uTLS hello.
        val pin = proxy.string("fingerprint")
        if (pin.isNotEmpty()) pinnedPeerCertSha256 = pin.trim()
        // Xray's "unsafe" is Go's own TLS, which mihomo's "none" asks for; an empty fingerprint would mean Chrome.
        val clientFingerprint = proxy.string("client-fingerprint")
        if (isClashNoFingerprint(clientFingerprint)) {
            fingerprint = TLS_UNSAFE_FINGERPRINT
        } else if (clientFingerprint.isNotEmpty()) {
            fingerprint = xrayKnownFingerprint(clientFingerprint, false, "client-fingerprint")
        }
        return true
    }

    /** xrayTLS::ExportToLink: sni is always written. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        q.add("sni" to serverName)
        if (pinnedPeerCertSha256.isNotEmpty()) q.add("pcs" to pinnedPeerCertSha256)
        if (verifyPeerCertByName.isNotEmpty()) q.add("vcn" to verifyPeerCertByName)
        if (alpn.isNotEmpty()) q.add("alpn" to alpn.joinToString(","))
        if (fingerprint.isNotEmpty()) q.add("fp" to fingerprint)
        if (cipherSuites.isNotEmpty()) q.add("cs" to cipherSuites)
        if (echConfigList.isNotEmpty()) q.add("ech" to echConfigList)
        return q
    }

    /** xrayTLS::ExportToJson: serverName is always written. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["serverName"] = Hosts.toAceHost(serverName)
        if (pinnedPeerCertSha256.isNotEmpty()) obj["pinnedPeerCertSha256"] = pinnedPeerCertSha256
        if (verifyPeerCertByName.isNotEmpty()) obj["verifyPeerCertByName"] = verifyPeerCertByName
        if (alpn.isNotEmpty()) obj["alpn"] = JsonValues.stringArray(alpn)
        if (fingerprint.isNotEmpty()) obj["fingerprint"] = fingerprint
        if (cipherSuites.isNotEmpty()) obj["cipherSuites"] = cipherSuites
        if (echConfigList.isNotEmpty()) obj["echConfigList"] = echConfigList
        if (useSystemCA) obj["useSystemCA"] = true
        return obj
    }

    /** xrayTLS::Build. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (fingerprint.isEmpty() && ctx.utlsFingerprint.isNotEmpty()) obj["fingerprint"] = ctx.utlsFingerprint
        return obj
    }
}

/** xrayReality (xrayStreamSetting.h, xrayStreamSetting.cpp). `password` is the public key. */
class XrayReality {
    @JvmField var serverName: String = ""
    @JvmField var fingerprint: String = ""
    @JvmField var password: String = ""
    @JvmField var shortId: String = ""
    @JvmField var spiderX: String = ""
    /** The server's ML-DSA-65 public key ("pqv" in links), which signs the REALITY certificate. */
    @JvmField var mldsa65Verify: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayReality::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("sni")) serverName = q.value("sni")
        if (q.has("peer")) serverName = q.value("peer")
        if (q.has("pbk")) password = importRealityPublicKey(q.value("pbk"), "pbk")
        if (q.has("fp")) fingerprint = xrayKnownFingerprint(q.value("fp"), true, "fp")
        if (q.has("sid")) shortId = q.value("sid").trim()
        if (q.has("spx")) {
            val value = q.valueFully("spx")
            if (acceptOptional("spx", XrayChecks.xraySpiderX(value), value)) spiderX = value
        }
        if (q.has("pqv")) mldsa65Verify = q.valueFully("pqv").trim()
        return true
    }

    /** xrayReality::ParseFromJson: also the DB loader, so only an import drops or reports values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("serverName")) serverName = obj.string("serverName")
        if (obj.contains("fingerprint")) {
            val value = obj.string("fingerprint")
            fingerprint = if (ParseDiag.active()) xrayKnownFingerprint(value, true, "realitySettings.fingerprint") else value
        }
        // Xray reads publicKey, the older name, when password is empty.
        if (obj.contains("password") || obj.contains("publicKey")) {
            val field = if (obj.string("password").trim().isEmpty()) "publicKey" else "password"
            val key = obj.string(field)
            password = if (ParseDiag.active()) importRealityPublicKey(key, "realitySettings.$field") else key.trim()
        }
        if (obj.contains("shortId")) shortId = obj.string("shortId").trim()
        if (obj.contains("spiderX")) {
            val value = obj.string("spiderX")
            if (!ParseDiag.active() || acceptOptional("realitySettings.spiderX", XrayChecks.xraySpiderX(value), value)) spiderX = value
        }
        if (obj.contains("mldsa65Verify")) mldsa65Verify = obj.string("mldsa65Verify").trim()
        return true
    }

    /** xrayReality::ParseFromClash. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        serverName = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        // REALITY needs uTLS, so mihomo's "none" leaves Xray's default.
        val clientFingerprint = proxy.string("client-fingerprint")
        if (clientFingerprint.isNotEmpty() && !isClashNoFingerprint(clientFingerprint)) {
            fingerprint = xrayKnownFingerprint(clientFingerprint, true, "client-fingerprint")
        }
        val opts = proxy.obj("reality-opts")
        val publicKey = opts.string("public-key")
        if (publicKey.isNotEmpty()) password = importRealityPublicKey(publicKey, "reality-opts.public-key")
        val id = opts.string("short-id")
        if (id.isNotEmpty()) shortId = id.trim()
        return true
    }

    /** xrayReality::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        q.add("sni" to serverName)
        if (fingerprint.isNotEmpty()) q.add("fp" to fingerprint)
        if (password.isNotEmpty()) q.add("pbk" to password)
        if (shortId.isNotEmpty()) q.add("sid" to shortId)
        if (spiderX.isNotEmpty()) q.add("spx" to spiderX)
        if (mldsa65Verify.isNotEmpty()) q.add("pqv" to mldsa65Verify)
        return q
    }

    /** xrayReality::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["serverName"] = Hosts.toAceHost(serverName)
        if (fingerprint.isNotEmpty()) obj["fingerprint"] = fingerprint
        if (password.isNotEmpty()) obj["password"] = password
        if (shortId.isNotEmpty()) obj["shortId"] = shortId
        if (spiderX.isNotEmpty()) obj["spiderX"] = spiderX
        if (mldsa65Verify.isNotEmpty()) obj["mldsa65Verify"] = mldsa65Verify
        return obj
    }

    /** xrayReality::Build: REALITY needs a uTLS hello and fails on "unsafe"; Xray's own default (chrome) takes over. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        if (fingerprint.isEmpty() && ctx.utlsFingerprint.isNotEmpty()) obj["fingerprint"] = ctx.utlsFingerprint
        if (obj.string("fingerprint").lowercase() == TLS_UNSAFE_FINGERPRINT) obj.remove("fingerprint")
        return obj
    }
}

/** xrayXHTTP (xrayStreamSetting.h, xrayStreamSetting.cpp). Unknown `extra`/`xmux` keys survive in rawExtra/rawXmux. */
class XrayXhttp {
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var mode: String = "auto"
    @JvmField var rawExtra: JsonObject = JsonObject()
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var xPaddingBytes: String = ""
    @JvmField var xPaddingObfsMode: Boolean = false
    @JvmField var xPaddingKey: String = ""
    @JvmField var xPaddingHeader: String = ""
    @JvmField var xPaddingPlacement: String = ""
    @JvmField var xPaddingMethod: String = ""
    @JvmField var uplinkHTTPMethod: String = ""
    @JvmField var sessionIDPlacement: String = ""
    @JvmField var sessionIDKey: String = ""
    @JvmField var sessionIDTable: String = ""
    @JvmField var sessionIDLength: String = ""
    @JvmField var seqPlacement: String = ""
    @JvmField var seqKey: String = ""
    @JvmField var uplinkDataPlacement: String = ""
    @JvmField var uplinkDataKey: String = ""
    @JvmField var uplinkChunkSize: String = ""
    @JvmField var noGRPCHeader: Boolean = false
    @JvmField var noSSEHeader: Boolean = false
    @JvmField var scMaxEachPostBytes: String = ""
    @JvmField var scMinPostsIntervalMs: String = ""
    @JvmField var scMaxBufferedPosts: Long = 0
    @JvmField var scStreamUpServerSecs: String = ""
    @JvmField var serverMaxHeaderBytes: Int = 0
    @JvmField var rawXmux: JsonObject = JsonObject()
    @JvmField var maxConcurrency: String = ""
    @JvmField var maxConnections: String = ""
    @JvmField var cMaxReuseTimes: String = ""
    @JvmField var hMaxRequestTimes: String = ""
    @JvmField var hMaxReusableSecs: String = ""
    @JvmField var hKeepAlivePeriod: Long = 0
    @JvmField var downloadSettings: String = ""

    companion object {
        /** knownXHTTPExtraKeys (xrayStreamSetting.cpp). */
        private val KNOWN_EXTRA_KEYS = setOf(
            "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement",
            "xPaddingMethod", "uplinkHTTPMethod", "sessionPlacement", "sessionKey", "sessionIDPlacement", "sessionIDKey",
            "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey", "uplinkDataPlacement", "uplinkDataKey",
            "uplinkChunkSize", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes", "scMinPostsIntervalMs",
            "scMaxBufferedPosts", "scStreamUpServerSecs", "serverMaxHeaderBytes", "xmux", "downloadSettings",
        )

        /** knownXHTTPXmuxKeys (xrayStreamSetting.cpp). */
        private val KNOWN_XMUX_KEYS = setOf(
            "maxConcurrency", "maxConnections", "cMaxReuseTimes", "hMaxRequestTimes", "hMaxReusableSecs", "hKeepAlivePeriod",
        )

        private val EXTRA_KEY_FOLDS = KNOWN_EXTRA_KEYS.associateBy(::foldKey)
        private val XMUX_KEY_FOLDS = KNOWN_XMUX_KEYS.associateBy(::foldKey)

        /** foldXHTTPKey (xrayStreamSetting.cpp). */
        private fun foldKey(key: String): String = buildString {
            for (c in key) if (c != '_' && c != '-') append(c.lowercaseChar())
        }

        /**
         * canonicalXHTTPKeys (xrayStreamSetting.cpp): Go folds case, and sing-box forks write snake_case: every spelling
         * becomes Xray's name, an exact one outranking a case-folded one, then an underscored one.
         */
        private fun canonicalKeys(obj: JsonObject, known: Map<String, String>): JsonObject {
            val result = JsonObject()
            val rank = HashMap<String, Int>()
            for (key in obj.sortedKeys()) {
                val match = known[foldKey(key)]
                if (match == null) {
                    result[key] = obj[key]
                    continue
                }
                val r = if (key == match) 2 else if (key.equals(match, ignoreCase = true)) 1 else 0
                if ((rank[match] ?: -1) > r) continue
                rank[match] = r
                result[match] = obj[key]
            }
            return result
        }

        /** parseRange (xrayStreamSetting.cpp): Xray reads a range as "a-b" or a number; sing-box forks also write {"from": a, "to": b}. */
        private fun JsonObject.range(key: String): String {
            if (!isObject(key)) return variantString(key)
            val value = obj(key)
            val from = value.variantString("from")
            val to = value.variantString("to")
            return if (from.isEmpty() || to.isEmpty() || from == to) from.ifEmpty { to } else "$from-$to"
        }

        /** parseBool (xrayStreamSetting.cpp): loose generators write 1 or "true", which Xray would refuse. */
        private fun JsonObject.looseBool(key: String): Boolean = when (val value = this[key]) {
            is Long -> value != 0L
            is Double -> value != 0.0
            is String -> value.trim().let { it.equals("true", ignoreCase = true) || it == "1" }
            else -> bool(key)
        }

        /** pythonReprToJson (xrayStreamSetting.cpp): a Python dict repr (str() instead of json.dumps); quotes and True/False/None are rewritten outside strings only. */
        private fun pythonReprToJson(text: String): String {
            val out = StringBuilder(text.length)
            var quote: Char? = null
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (quote != null) {
                    if (c == '\\' && i + 1 < text.length) {
                        val next = text[++i]
                        if (next != '\'') out.append(c)
                        out.append(next)
                    } else if (c == quote) {
                        out.append('"')
                        quote = null
                    } else {
                        if (c == '"') out.append('\\')
                        out.append(c)
                    }
                } else if (c == '\'' || c == '"') {
                    quote = c
                    out.append('"')
                } else if (c.isLetter()) {
                    var end = i
                    while (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) end++
                    when (val word = text.substring(i, end)) {
                        "True" -> out.append("true")
                        "False" -> out.append("false")
                        "None" -> out.append("null")
                        else -> out.append(word)
                    }
                    i = end
                    continue
                } else {
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }

        /** The Int32Range fields dropUnusableXHTTPValues (xrayStreamSetting.cpp) checks, by import field name. */
        private val INT32_RANGE_FIELDS: List<Pair<String, KMutableProperty1<XrayXhttp, String>>> = listOf(
            "sessionIDLength" to XrayXhttp::sessionIDLength,
            "uplinkChunkSize" to XrayXhttp::uplinkChunkSize,
            "scMaxEachPostBytes" to XrayXhttp::scMaxEachPostBytes,
            "scMinPostsIntervalMs" to XrayXhttp::scMinPostsIntervalMs,
            "scStreamUpServerSecs" to XrayXhttp::scStreamUpServerSecs,
            "xmux.maxConcurrency" to XrayXhttp::maxConcurrency,
            "xmux.maxConnections" to XrayXhttp::maxConnections,
            "xmux.cMaxReuseTimes" to XrayXhttp::cMaxReuseTimes,
            "xmux.hMaxRequestTimes" to XrayXhttp::hMaxRequestTimes,
            "xmux.hMaxReusableSecs" to XrayXhttp::hMaxReusableSecs,
        )

        private fun exportString(obj: JsonObject, key: String, value: String) {
            if (value.isEmpty()) obj.remove(key) else obj[key] = value
        }

        private fun exportBool(obj: JsonObject, key: String, value: Boolean) {
            if (value) obj[key] = true else obj.remove(key)
        }

        private fun exportLong(obj: JsonObject, key: String, value: Long) {
            if (value != 0L) obj[key] = value else obj.remove(key)
        }

        private fun exportInt(obj: JsonObject, key: String, value: Int) {
            if (value != 0) obj[key] = value else obj.remove(key)
        }
    }

    /** parseXHTTPXmuxObject (xrayStreamSetting.cpp). */
    private fun parseXmuxObject(source: JsonObject) {
        val obj = canonicalKeys(source, XMUX_KEY_FOLDS)
        for (key in obj.keys()) {
            if (key !in KNOWN_XMUX_KEYS) rawXmux[key] = JsonValues.deepCopy(obj[key]!!)
        }
        if (obj.contains("maxConcurrency")) maxConcurrency = obj.range("maxConcurrency")
        if (obj.contains("maxConnections")) maxConnections = obj.range("maxConnections")
        if (obj.contains("cMaxReuseTimes")) cMaxReuseTimes = obj.range("cMaxReuseTimes")
        if (obj.contains("hMaxRequestTimes")) hMaxRequestTimes = obj.range("hMaxRequestTimes")
        if (obj.contains("hMaxReusableSecs")) hMaxReusableSecs = obj.range("hMaxReusableSecs")
        if (obj.contains("hKeepAlivePeriod")) hKeepAlivePeriod = obj.variantLong("hKeepAlivePeriod")
    }

    /** parseXHTTPExtraObject (xrayStreamSetting.cpp). */
    private fun parseExtraObject(source: JsonObject) {
        val obj = canonicalKeys(source, EXTRA_KEY_FOLDS)
        for (key in obj.keys()) {
            if (key !in KNOWN_EXTRA_KEYS) rawExtra[key] = JsonValues.deepCopy(obj[key]!!)
        }
        if (obj.contains("headers")) {
            if (obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
            else if (obj.isArray("headers")) headers = obj.array("headers").strings()
        }
        if (obj.contains("xPaddingBytes")) xPaddingBytes = obj.range("xPaddingBytes")
        if (obj.contains("xPaddingObfsMode")) xPaddingObfsMode = obj.looseBool("xPaddingObfsMode")
        if (obj.contains("xPaddingKey")) xPaddingKey = obj.string("xPaddingKey")
        if (obj.contains("xPaddingHeader")) xPaddingHeader = obj.string("xPaddingHeader")
        if (obj.contains("xPaddingPlacement")) xPaddingPlacement = obj.string("xPaddingPlacement")
        if (obj.contains("xPaddingMethod")) xPaddingMethod = obj.string("xPaddingMethod")
        if (obj.contains("uplinkHTTPMethod")) uplinkHTTPMethod = obj.string("uplinkHTTPMethod")
        // sessionPlacement / sessionKey: legacy aliases (pre-v26.6.22 Xray), overridden by the current names
        if (obj.contains("sessionPlacement")) sessionIDPlacement = obj.string("sessionPlacement")
        if (obj.contains("sessionIDPlacement")) sessionIDPlacement = obj.string("sessionIDPlacement")
        if (obj.contains("sessionKey")) sessionIDKey = obj.string("sessionKey")
        if (obj.contains("sessionIDKey")) sessionIDKey = obj.string("sessionIDKey")
        if (obj.contains("sessionIDTable")) sessionIDTable = obj.string("sessionIDTable")
        if (obj.contains("sessionIDLength")) sessionIDLength = obj.range("sessionIDLength")
        if (obj.contains("seqPlacement")) seqPlacement = obj.string("seqPlacement")
        if (obj.contains("seqKey")) seqKey = obj.string("seqKey")
        if (obj.contains("uplinkDataPlacement")) uplinkDataPlacement = obj.string("uplinkDataPlacement")
        if (obj.contains("uplinkDataKey")) uplinkDataKey = obj.string("uplinkDataKey")
        if (obj.contains("uplinkChunkSize")) uplinkChunkSize = obj.range("uplinkChunkSize")
        if (obj.contains("noGRPCHeader")) noGRPCHeader = obj.looseBool("noGRPCHeader")
        if (obj.contains("noSSEHeader")) noSSEHeader = obj.looseBool("noSSEHeader")
        if (obj.contains("scMaxEachPostBytes")) scMaxEachPostBytes = obj.range("scMaxEachPostBytes")
        if (obj.contains("scMinPostsIntervalMs")) scMinPostsIntervalMs = obj.range("scMinPostsIntervalMs")
        if (obj.contains("scMaxBufferedPosts")) scMaxBufferedPosts = obj.variantLong("scMaxBufferedPosts")
        if (obj.contains("scStreamUpServerSecs")) scStreamUpServerSecs = obj.range("scStreamUpServerSecs")
        if (obj.contains("serverMaxHeaderBytes")) serverMaxHeaderBytes = obj.variantInt("serverMaxHeaderBytes")
        if (obj.contains("downloadSettings")) {
            if (obj.isObject("downloadSettings")) downloadSettings = obj.obj("downloadSettings").toCompact()
            else if (obj.isString("downloadSettings")) downloadSettings = obj.string("downloadSettings")
        }
        val xmuxObj = obj.obj("xmux")
        if (xmuxObj.isNotEmpty()) parseXmuxObject(xmuxObj)
    }

    /** dropUnusableXHTTPValues (xrayStreamSetting.cpp): Xray refuses these at start but picks its own default for an empty one. */
    private fun dropUnusableValues() {
        fun drop(field: String, property: KMutableProperty1<XrayXhttp, String>, check: (String) -> String) {
            val value = property.get(this)
            if (!acceptOptional(field, check(value), value)) property.set(this, "")
        }
        if (!acceptOptional("mode", XrayChecks.xrayXHTTPMode(mode), mode)) mode = "auto"
        // Xray refuses a Host header, which the host field outranks anyway.
        var i = 0
        while (i + 1 < headers.size) {
            if (!headers[i].trim().equals("host", ignoreCase = true)) {
                i += 2
                continue
            }
            if (host.isEmpty()) host = Hosts.toAceHost(headers[i + 1].trim())
            headers.subList(i, i + 2).clear()
        }
        drop("xPaddingBytes", XrayXhttp::xPaddingBytes, XrayChecks::xrayXPaddingBytes)
        drop("xPaddingMethod", XrayXhttp::xPaddingMethod, XrayChecks::xrayPaddingMethod)
        drop("xPaddingPlacement", XrayXhttp::xPaddingPlacement, XrayChecks::xrayPaddingPlacement)
        drop("uplinkDataPlacement", XrayXhttp::uplinkDataPlacement) { Validation.oneOf(it, xrayXhttpUplinkDataPlacements) }
        drop("sessionIDPlacement", XrayXhttp::sessionIDPlacement, XrayChecks::xrayMetaPlacement)
        drop("seqPlacement", XrayXhttp::seqPlacement, XrayChecks::xrayMetaPlacement)
        for ((name, property) in INT32_RANGE_FIELDS) drop(name, property, XrayChecks::xrayInt32Range)
    }

    /** xrayXHTTP::ParseExtraJson: a Python dict repr ('key': True) is rewritten only when the JSON parse fails. */
    fun parseExtraJson(text: String): Boolean {
        var obj = JsonInput.parseObject(text)
        if (obj.isEmpty()) obj = JsonInput.parseObject(pythonReprToJson(text))
        if (obj.isEmpty()) return false
        parseExtraObject(obj)
        return true
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayXHTTP::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        if (q.has("mode")) mode = q.value("mode")
        if (q.has("extra")) {
            // Header values such as a User-Agent make a space likelier than a literal '+'.
            val readings = q.valueDecodings("extra", true)
            if (readings.none { parseExtraJson(it) }) xrayWarnUnparsableExtra(readings.first())
        }
        if (q.has("headers")) headers = xrayLinkHeaders(q)
        if (q.has("x_padding_bytes")) xPaddingBytes = q.value("x_padding_bytes")
        if (q.has("no_grpc_header")) noGRPCHeader = q.value("no_grpc_header").replace("1", "true") == "true"
        if (q.has("sc_max_each_post_bytes")) scMaxEachPostBytes = q.value("sc_max_each_post_bytes")
        if (q.has("sc_min_posts_interval_ms")) scMinPostsIntervalMs = q.value("sc_min_posts_interval_ms")
        if (q.has("max_concurrency")) maxConcurrency = q.value("max_concurrency")
        if (q.has("max_connections")) maxConnections = q.value("max_connections")
        if (q.has("max_reuse_times")) cMaxReuseTimes = q.value("max_reuse_times")
        if (q.has("max_request_times")) hMaxRequestTimes = q.value("max_request_times")
        if (q.has("max_reusable_secs")) hMaxReusableSecs = q.value("max_reusable_secs")
        if (q.has("keep_alive_period")) {
            val value = q.value("keep_alive_period").trim()
            if (acceptOptional("keep_alive_period", Validation.int(value, Long.MIN_VALUE, Long.MAX_VALUE), value)) {
                hKeepAlivePeriod = Validation.toInt(value) ?: 0L
            }
        }
        dropUnusableValues()
        return true
    }

    /** xrayXHTTP::ParseFromJson: every top-level key other than host/path/mode/extra is an extra field. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("path")) path = obj.string("path")
        if (obj.contains("mode")) mode = obj.string("mode")
        val topLevelExtra = JsonObject()
        for (key in obj.keys()) {
            if (key == "host" || key == "path" || key == "mode" || key == "extra") continue
            topLevelExtra[key] = obj[key]
        }
        if (topLevelExtra.isNotEmpty()) parseExtraObject(topLevelExtra)
        val exObj = obj.obj("extra")
        if (exObj.isNotEmpty()) {
            parseExtraObject(exObj)
        } else if (obj.isString("extra") && !parseExtraJson(obj.string("extra"))) {
            xrayWarnUnparsableExtra(obj.string("extra"))
        }
        if (ParseDiag.active()) dropUnusableValues()
        return true
    }

    /** xrayXHTTP::ParseFromClash: `xhttp-opts` with its reuse and download settings; a null download-settings means none. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = proxy.obj("xhttp-opts")
        host = opts.string("host")
        path = opts.string("path")
        mode = opts.string("mode").ifEmpty { "auto" }
        headers = ArrayList()
        for ((key, value) in opts.stringMap("headers")) {
            headers.add(key)
            headers.add(value)
        }
        noGRPCHeader = opts.bool("no-grpc-header")
        xPaddingBytes = opts.string("x-padding-bytes")
        xPaddingObfsMode = opts.bool("x-padding-obfs-mode")
        xPaddingKey = opts.string("x-padding-key")
        xPaddingHeader = opts.string("x-padding-header")
        xPaddingPlacement = opts.string("x-padding-placement")
        xPaddingMethod = opts.string("x-padding-method")
        uplinkHTTPMethod = opts.string("uplink-http-method")
        // 3x-ui writes session-id-*, which mihomo ignores; mihomo's own names win whenever they hold a scalar.
        fun session(name: String): String {
            val own = "session-$name"
            return if (opts.node.isString(own) || opts.string(own).isNotEmpty()) opts.string(own) else opts.string("session-id-$name")
        }
        sessionIDPlacement = session("placement")
        sessionIDKey = session("key")
        sessionIDTable = session("table")
        sessionIDLength = session("length")
        seqPlacement = opts.string("seq-placement")
        seqKey = opts.string("seq-key")
        uplinkDataPlacement = opts.string("uplink-data-placement")
        uplinkDataKey = opts.string("uplink-data-key")
        uplinkChunkSize = opts.string("uplink-chunk-size")
        scMaxEachPostBytes = opts.string("sc-max-each-post-bytes")
        scMinPostsIntervalMs = opts.string("sc-min-posts-interval-ms")
        val reuse = opts.obj("reuse-settings")
        maxConcurrency = reuse.string("max-concurrency")
        maxConnections = reuse.string("max-connections")
        cMaxReuseTimes = reuse.string("c-max-reuse-times")
        hMaxRequestTimes = reuse.string("h-max-request-times")
        hMaxReusableSecs = reuse.string("h-max-reusable-secs")
        val keepAlivePeriod = reuse.string("h-keep-alive-period")
        if (keepAlivePeriod.isNotEmpty()) hKeepAlivePeriod = QtStrings.toLong(keepAlivePeriod)
        // Dropped first, so the download leg inherits only usable values.
        dropUnusableValues()
        if (opts.present("download-settings")) downloadSettings = clashDownloadSettings(proxy, exportToJson().obj("extra")).toCompact()
        return true
    }

    /** xrayXHTTP::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty()) q.add("path" to path)
        if (mode.isNotEmpty()) q.add("mode" to mode)
        val jsonExport = exportToJson()
        if (jsonExport.isObject("extra")) {
            val exObj = jsonExport.obj("extra")
            if (exObj.isNotEmpty()) q.add("extra" to exObj.toCompact())
        }
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        return q
    }

    /** xrayXHTTP::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (path.isNotEmpty()) obj["path"] = path
        if (mode.isNotEmpty()) obj["mode"] = mode

        val extraObj = rawExtra.copy()
        if (headers.isNotEmpty()) extraObj["headers"] = JsonValues.pairListToObject(headers) else extraObj.remove("headers")
        exportString(extraObj, "xPaddingBytes", xPaddingBytes)
        exportBool(extraObj, "xPaddingObfsMode", xPaddingObfsMode)
        exportString(extraObj, "xPaddingKey", xPaddingKey)
        exportString(extraObj, "xPaddingHeader", xPaddingHeader)
        exportString(extraObj, "xPaddingPlacement", xPaddingPlacement)
        exportString(extraObj, "xPaddingMethod", xPaddingMethod)
        exportString(extraObj, "uplinkHTTPMethod", uplinkHTTPMethod)
        exportString(extraObj, "sessionIDPlacement", sessionIDPlacement)
        exportString(extraObj, "sessionIDKey", sessionIDKey)
        exportString(extraObj, "sessionIDTable", sessionIDTable)
        exportString(extraObj, "sessionIDLength", sessionIDLength)
        exportString(extraObj, "seqPlacement", seqPlacement)
        exportString(extraObj, "seqKey", seqKey)
        exportString(extraObj, "uplinkDataPlacement", uplinkDataPlacement)
        exportString(extraObj, "uplinkDataKey", uplinkDataKey)
        exportString(extraObj, "uplinkChunkSize", uplinkChunkSize)
        exportBool(extraObj, "noGRPCHeader", noGRPCHeader)
        exportBool(extraObj, "noSSEHeader", noSSEHeader)
        exportString(extraObj, "scMaxEachPostBytes", scMaxEachPostBytes)
        exportString(extraObj, "scMinPostsIntervalMs", scMinPostsIntervalMs)
        exportLong(extraObj, "scMaxBufferedPosts", scMaxBufferedPosts)
        exportString(extraObj, "scStreamUpServerSecs", scStreamUpServerSecs)
        exportInt(extraObj, "serverMaxHeaderBytes", serverMaxHeaderBytes)
        if (mode == "stream-one") {
            // Xray refuses downloadSettings in stream-one mode under any spelling.
            xrayRemoveNamed(extraObj, "downloadSettings")
        } else if (downloadSettings.isNotEmpty()) {
            val dsObj = JsonInput.parseObject(downloadSettings)
            if (dsObj.isNotEmpty()) extraObj["downloadSettings"] = dsObj
        } else {
            extraObj.remove("downloadSettings")
        }
        val xmuxObj = rawXmux.copy()
        exportString(xmuxObj, "maxConcurrency", maxConcurrency)
        exportString(xmuxObj, "maxConnections", maxConnections)
        exportString(xmuxObj, "cMaxReuseTimes", cMaxReuseTimes)
        exportString(xmuxObj, "hMaxRequestTimes", hMaxRequestTimes)
        exportString(xmuxObj, "hMaxReusableSecs", hMaxReusableSecs)
        exportLong(xmuxObj, "hKeepAlivePeriod", hKeepAlivePeriod)
        if (xmuxObj.isNotEmpty()) extraObj["xmux"] = xmuxObj else extraObj.remove("xmux")
        if (extraObj.isNotEmpty()) obj["extra"] = extraObj
        return obj
    }

    /** xrayXHTTP::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayWS (xrayStreamSetting.h, xrayStreamSetting.cpp). */
class XrayWs {
    @JvmField var path: String = ""
    @JvmField var host: String = ""
    @JvmField var ed: Int = 0
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var heartbeatPeriod: Int = 0

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayWS::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        ed = readQueryInt(q, "ed", ed)
        if (q.has("headers")) headers = xrayLinkHeaders(q)
        heartbeatPeriod = readQueryInt(q, "heartbeat_period", heartbeatPeriod)
        return true
    }

    /** xrayWS::ParseFromJson. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("path")) {
            path = obj.string("path")
            if (path.contains("?ed=")) {
                val spl = path.split("?ed=")
                path = spl[0]
                ed = QtStrings.toInt(spl[1])
            }
        }
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        if (obj.contains("heartbeatPeriod")) heartbeatPeriod = obj.int("heartbeatPeriod")
        return true
    }

    /** xrayWS::ParseFromClash. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = ClashWsOptions(proxy)
        opts.path?.let { path = it }
        opts.ed?.let { ed = it }
        opts.host?.let { host = it }
        headers.addAll(opts.headers)
        return true
    }

    /** xrayWS::ExportToLink: a bare "/" path is not written. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty() && path != "/") q.add("path" to path)
        if (ed > 0) q.add("ed" to ed.toString())
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        if (heartbeatPeriod > 0) q.add("heartbeat_period" to heartbeatPeriod.toString())
        return q
    }

    /** xrayWS::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (path.isNotEmpty()) {
            var fullPath = path
            if (ed > 0) fullPath += "?ed=$ed"
            obj["path"] = fullPath
        }
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        if (heartbeatPeriod > 0) obj["heartbeatPeriod"] = heartbeatPeriod
        return obj
    }

    /** xrayWS::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayHttpUpgrade (xrayStreamSetting.h, xrayStreamSetting.cpp). */
class XrayHttpUpgrade {
    @JvmField var path: String = ""
    @JvmField var ed: Int = 0
    @JvmField var host: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayHttpUpgrade::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("host")) host = q.value("host")
        if (q.has("path")) path = q.valueFully("path")
        ed = readQueryInt(q, "ed", ed)
        if (q.has("headers")) headers = xrayLinkHeaders(q)
        return true
    }

    /** xrayHttpUpgrade::ParseFromJson. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("path")) {
            path = obj.string("path")
            if (path.contains("?ed=")) {
                val spl = path.split("?ed=")
                path = spl[0]
                ed = QtStrings.toInt(spl[1])
            }
        }
        if (obj.contains("host")) host = obj.string("host")
        if (obj.contains("headers") && obj.isObject("headers")) headers = JsonValues.objectToPairList(obj.obj("headers"))
        return true
    }

    /** xrayHttpUpgrade::ParseFromClash. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = ClashWsOptions(proxy)
        opts.path?.let { path = it }
        opts.ed?.let { ed = it }
        opts.host?.let { host = it }
        headers.addAll(opts.headers)
        return true
    }

    /** xrayHttpUpgrade::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty() && path != "/") q.add("path" to path)
        if (ed > 0) q.add("ed" to ed.toString())
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        return q
    }

    /** xrayHttpUpgrade::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (path.isNotEmpty()) {
            var fullPath = path
            if (ed > 0) fullPath += "?ed=$ed"
            obj["path"] = fullPath
        }
        if (host.isNotEmpty()) obj["host"] = Hosts.toAceHost(host)
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        return obj
    }

    /** xrayHttpUpgrade::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayGRPC (xrayStreamSetting.h, xrayStreamSetting.cpp). */
class XrayGrpc {
    @JvmField var authority: String = ""
    @JvmField var serviceName: String = ""
    @JvmField var multiMode: Boolean = false

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayGRPC::ParseFromLink. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("authority")) authority = q.valueFully("authority")
        if (q.has("serviceName")) serviceName = q.valueFully("serviceName")
        if (q.has("mode") && q.value("mode") == "multi") multiMode = true
        return true
    }

    /** xrayGRPC::ParseFromJson. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("authority")) authority = obj.string("authority")
        if (obj.contains("serviceName")) serviceName = obj.string("serviceName")
        if (obj.contains("multiMode")) multiMode = obj.bool("multiMode")
        return true
    }

    /** xrayGRPC::ParseFromClash. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val name = proxy.obj("grpc-opts").string("grpc-service-name")
        if (name.isNotEmpty()) serviceName = name
        return true
    }

    /** xrayGRPC::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (authority.isNotEmpty()) q.add("authority" to authority)
        if (serviceName.isNotEmpty()) q.add("serviceName" to serviceName)
        if (multiMode) q.add("mode" to "multi")
        return q
    }

    /** xrayGRPC::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (authority.isNotEmpty()) obj["authority"] = Hosts.toAceHost(authority)
        if (serviceName.isNotEmpty()) obj["serviceName"] = serviceName
        if (multiMode) obj["multiMode"] = multiMode
        return obj
    }

    /** xrayGRPC::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/** xrayKCP (xrayStreamSetting.h, xrayStreamSetting.cpp): mKCP; its old header and seed live in finalmask as mkcp-legacy masks. */
class XrayKcp {
    @JvmField var mtu: Long = DEFAULT_MTU
    @JvmField var tti: Long = DEFAULT_TTI
    @JvmField var uplinkCapacity: Long = DEFAULT_UPLINK_CAPACITY
    @JvmField var downlinkCapacity: Long = DEFAULT_DOWNLINK_CAPACITY
    @JvmField var cwndMultiplier: Long = DEFAULT_CWND_MULTIPLIER
    @JvmField var maxSendingWindow: Long = DEFAULT_MAX_SENDING_WINDOW

    companion object {
        const val DEFAULT_MTU = 1350L
        const val DEFAULT_TTI = 50L
        const val DEFAULT_UPLINK_CAPACITY = 5L
        const val DEFAULT_DOWNLINK_CAPACITY = 20L
        const val DEFAULT_CWND_MULTIPLIER = 1L
        const val DEFAULT_MAX_SENDING_WINDOW = 2097152L

        /** kXrayKCPValues (xrayStreamSetting.cpp): the Xray key of each field. */
        @JvmField
        val VALUES: List<Pair<String, KMutableProperty1<XrayKcp, Long>>> = listOf(
            "mtu" to XrayKcp::mtu,
            "tti" to XrayKcp::tti,
            "uplinkCapacity" to XrayKcp::uplinkCapacity,
            "downlinkCapacity" to XrayKcp::downlinkCapacity,
            "cwndMultiplier" to XrayKcp::cwndMultiplier,
            "maxSendingWindow" to XrayKcp::maxSendingWindow,
        )

        @JvmField
        val DEFAULTS: Map<String, Long> = mapOf(
            "mtu" to DEFAULT_MTU,
            "tti" to DEFAULT_TTI,
            "uplinkCapacity" to DEFAULT_UPLINK_CAPACITY,
            "downlinkCapacity" to DEFAULT_DOWNLINK_CAPACITY,
            "cwndMultiplier" to DEFAULT_CWND_MULTIPLIER,
            "maxSendingWindow" to DEFAULT_MAX_SENDING_WINDOW,
        )
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayKCP::ParseFromLink: links carry only mtu and tti. */
    fun parseFromLink(url: ParsedLink): Boolean {
        val q = url.query
        for ((key, property) in listOf("mtu" to XrayKcp::mtu, "tti" to XrayKcp::tti)) {
            if (!q.has(key)) continue
            val text = q.value(key).trim()
            if (!acceptOptional(key, xrayKcpCheck(key, text, mtu), text)) continue
            Validation.toInt(text)?.let { property.set(this, it) }
        }
        return true
    }

    /** xrayKCP::ParseFromJson: an import drops a value Xray would refuse, with a warning. */
    fun parseFromJson(obj: JsonObject): Boolean {
        val importing = ParseDiag.active()
        for ((key, property) in VALUES) {
            if (!obj.contains(key)) continue
            val text = xrayKcpJsonText(obj[key])
            if (importing && !acceptOptional("kcpSettings.$key", xrayKcpCheck(key, text, mtu), text)) continue
            Validation.toInt(text)?.let { property.set(this, it) }
        }
        return true
    }

    /** xrayKCP::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> = listOf("mtu" to mtu.toString(), "tti" to tti.toString())

    /** xrayKCP::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        for ((key, property) in VALUES) obj[key] = property.get(this)
        return obj
    }

    /** xrayKCP::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/**
 * xrayXDrive (xrayStreamSetting.h, xrayStreamSetting.cpp). XDRIVE has no share-link format: a link would carry the
 * storage secrets, so profiles travel as JSON.
 */
class XrayXDrive {
    @JvmField var service: String = "google-drive"
    @JvmField var remoteFolder: String = ""
    /** google-drive: ClientID, ClientSecret, RefreshToken; s3: AccessKeyID, SecretAccessKey[, SessionToken]; template: read as {secret0}, {secret1}, ... */
    @JvmField var secrets: MutableList<String> = ArrayList()
    @JvmField var templateSpec: JsonObject = JsonObject()
    /** s3 only; Xray addresses the bucket path-style unless s3PathStyle is false. */
    @JvmField var s3Endpoint: String = ""
    @JvmField var s3Region: String = ""
    @JvmField var s3Bucket: String = ""
    @JvmField var s3PathStyle: Boolean = true
    /** The stream's own address and port: a front the storage API is dialed through. */
    @JvmField var frontAddress: String = ""
    @JvmField var frontPort: Int = 0
    // 0 keeps Xray's default.
    @JvmField var segmentBytes: Long = 0
    @JvmField var flushIntervalMs: Long = 0
    @JvmField var pollIntervalMs: Long = 0
    @JvmField var maxPollIntervalMs: Long = 0
    @JvmField var sessionTtlSeconds: Long = 0
    @JvmField var concurrency: Long = 0
    @JvmField var eagerWindowMs: Long = 0
    @JvmField var holeTimeoutMs: Long = 0
    /** Kept as written; the editor has no fields for it. */
    @JvmField var xmux: JsonObject = JsonObject()

    companion object {
        /** xdriveTuning (xrayStreamSetting.cpp): the Xray key of each tuning field. */
        @JvmField
        val TUNING: List<Pair<String, KMutableProperty1<XrayXDrive, Long>>> = listOf(
            "segmentBytes" to XrayXDrive::segmentBytes,
            "flushIntervalMs" to XrayXDrive::flushIntervalMs,
            "pollIntervalMs" to XrayXDrive::pollIntervalMs,
            "maxPollIntervalMs" to XrayXDrive::maxPollIntervalMs,
            "sessionTtlSeconds" to XrayXDrive::sessionTtlSeconds,
            "concurrency" to XrayXDrive::concurrency,
            "eagerWindowMs" to XrayXDrive::eagerWindowMs,
            "holeTimeoutMs" to XrayXDrive::holeTimeoutMs,
        )
    }

    /** xrayXDrive::ParseFromJson. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("service")) {
            service = obj.string("service")
            if (service == "Google Drive") service = "google-drive"
        }
        if (obj.contains("remoteFolder")) remoteFolder = obj.string("remoteFolder")
        if (obj.contains("secrets")) secrets = jsonStringList(obj["secrets"], "xdriveSettings.secrets")
        if (obj.isObject("template")) templateSpec = obj.obj("template").copy()
        if (obj.isObject("s3")) {
            val s3 = obj.obj("s3")
            s3Endpoint = s3.string("endpoint")
            s3Region = s3.string("region")
            s3Bucket = s3.string("bucket")
            s3PathStyle = s3["pathStyle"] as? Boolean ?: true
        }
        for ((key, property) in TUNING) if (obj.contains(key)) property.set(this, obj.integer(key))
        if (obj.isObject("xmux")) xmux = obj.obj("xmux").copy()
        return true
    }

    /** xrayXDrive::ExportToJson. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["service"] = service
        if (remoteFolder.isNotEmpty()) obj["remoteFolder"] = remoteFolder
        if (secrets.isNotEmpty()) obj["secrets"] = JsonValues.stringArray(secrets)
        if (templateSpec.isNotEmpty()) obj["template"] = templateSpec.copy()
        // Xray reads the s3 object only for the s3 service, so another service drops it.
        if (service == "s3") {
            val s3 = JsonObject()
            s3["endpoint"] = s3Endpoint
            if (s3Region.isNotEmpty()) s3["region"] = s3Region
            s3["bucket"] = s3Bucket
            if (!s3PathStyle) s3["pathStyle"] = false
            obj["s3"] = s3
        }
        for ((key, property) in TUNING) {
            val value = property.get(this)
            if (value != 0L) obj[key] = value
        }
        if (xmux.isNotEmpty()) obj["xmux"] = xmux.copy()
        return obj
    }

    /** xrayXDrive::Build. */
    fun build(ctx: BuildContext): JsonObject = exportToJson()
}

/**
 * xrayStreamSetting (xrayStreamSetting.h, xrayStreamSetting.cpp). Field `TLS` is named `tls` here. The desktop's
 * `sockopt` is not modelled: binding and DNS are wired by the core.
 */
class XrayStreamSetting {
    @JvmField var network: String = "raw"
    @JvmField var security: String = "none"
    @JvmField var rawSettings: JsonObject = JsonObject()
    @JvmField var finalmask: JsonObject = JsonObject()
    @JvmField var tls: XrayTls = XrayTls()
    @JvmField var reality: XrayReality = XrayReality()
    @JvmField var xhttp: XrayXhttp = XrayXhttp()
    @JvmField var ws: XrayWs = XrayWs()
    @JvmField var httpupgrade: XrayHttpUpgrade = XrayHttpUpgrade()
    @JvmField var grpc: XrayGrpc = XrayGrpc()
    @JvmField var kcp: XrayKcp = XrayKcp()
    @JvmField var xdrive: XrayXDrive = XrayXDrive()
    /** XDNS only; stored under Throne-only keys that build() removes. */
    @JvmField var xdnsResolvers: ResolverSource = ResolverSource()

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** xrayStreamSetting::ParseFromLink; false (an import Error) when the network is not one Throne's Xray stream knows. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        if (q.has("fm") || q.has("finalmask")) {
            val key = if (q.has("fm")) "fm" else "finalmask"
            // Mask values are mostly base64 and hex, where a bare '+' is more likely literal than a space.
            val readings = q.valueDecodings(key, false)
            var text = readings.first()
            var parsed = JsonTree.parse(text)
            for (reading in readings.drop(1)) {
                if (parsed.ok) break
                text = reading
                parsed = JsonTree.parse(text)
            }
            when {
                !parsed.ok -> ParseDiag.warn(key, vtr(R.string.validation_xray_finalmask_json, parsed.error))
                parsed.root?.type != JsonType.Object -> ParseDiag.warn(key, vtr(R.string.validation_xray_finalmask_object))
                else -> JsonInput.parseObjectOrNull(text)?.let { finalmask = it }
            }
        }
        upgradeXrayFinalmask(finalmask)
        val type = q.value("type").trim()
        if (type.isNotEmpty()) network = normalizeXrayNetwork(type)
        if (network !in xrayNetworks) {
            // A share link has no lossless fallback, unlike an Xray JSON outbound, which is then kept verbatim.
            ParseDiag.error("type", XrayChecks.xrayNetwork(network), network)
            return false
        }
        if (network == "xdrive") {
            ParseDiag.error("type", vtr(R.string.validation_xray_xdrive_no_link), network)
            return false
        }
        if (network == "raw" && q.value("headerType") == "http") {
            val request = JsonObject()
            val paths = QtStrings.splitList(q.valueFully("path"))
            if (paths.isNotEmpty()) request["path"] = JsonValues.stringArray(paths)
            val hosts = QtStrings.splitList(q.valueFully("host"))
            if (hosts.isNotEmpty()) {
                request["headers"] = jsonObjectOf("Host" to JsonValues.stringArray(hosts.map { Hosts.toAceHost(it) }))
            }
            val header = jsonObjectOf("type" to "http")
            if (request.isNotEmpty()) header["request"] = request
            rawSettings = jsonObjectOf("header" to header)
        }
        // Xray matches security case-insensitively.
        if (q.has("security")) security = q.value("security").trim().lowercase()
        if (security == "tls") tls.parseFromLink(url)
        else if (security == "reality") reality.parseFromLink(url)
        when (network) {
            "xhttp" -> xhttp.parseFromLink(url)
            "ws" -> ws.parseFromLink(url)
            "httpupgrade" -> httpupgrade.parseFromLink(url)
            "grpc" -> grpc.parseFromLink(url)
            "kcp" -> {
                kcp.parseFromLink(url)
                xrayKcpImportLegacy(finalmask, "headerType", q.valueFully("headerType"), "", q.valueFully("seed"))
            }
        }
        return true
    }

    /** xrayStreamSetting::ParseFromJson; false lets the importer keep the outbound as a lossless custom Xray outbound. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("finalmask") && obj.isObject("finalmask")) finalmask = obj.obj("finalmask").copy()
        if (ParseDiag.active()) upgradeXrayFinalmask(finalmask)
        if (obj.contains("method")) network = obj.string("method")
        else if (obj.contains("network")) network = obj.string("network")
        network = normalizeXrayNetwork(network)
        if (network !in xrayNetworks) return false
        if (network == "raw") {
            if (obj.isObject("rawSettings")) rawSettings = obj.obj("rawSettings").copy()
            else if (obj.isObject("tcpSettings")) rawSettings = obj.obj("tcpSettings").copy()
        }
        if (obj.contains("security")) security = obj.string("security").trim().lowercase()
        if (security == "tls" && obj.isObject("tlsSettings")) tls.parseFromJson(obj.obj("tlsSettings"))
        else if (security == "reality" && obj.isObject("realitySettings")) reality.parseFromJson(obj.obj("realitySettings"))
        if (network == "xhttp") {
            // splithttpSettings is Xray's older key; xhttpSettings wins when both are set.
            val settings = if (obj.isObject("xhttpSettings")) obj.obj("xhttpSettings") else obj["splithttpSettings"] as? JsonObject
            if (settings != null) xhttp.parseFromJson(settings)
        }
        if (network == "ws" && obj.isObject("wsSettings")) ws.parseFromJson(obj.obj("wsSettings"))
        if (network == "httpupgrade" && obj.isObject("httpupgradeSettings")) httpupgrade.parseFromJson(obj.obj("httpupgradeSettings"))
        if (network == "grpc" && obj.isObject("grpcSettings")) grpc.parseFromJson(obj.obj("grpcSettings"))
        if (network == "kcp") {
            val settings = obj.obj("kcpSettings")
            kcp.parseFromJson(settings)
            if (ParseDiag.active()) {
                val header = settings.obj("header")
                xrayKcpImportLegacy(finalmask, "kcpSettings.header.type", header.string("type"), header.string("domain"), settings.string("seed"))
            }
        }
        if (network == "xdrive") {
            if (obj.isObject("xdriveSettings")) xdrive.parseFromJson(obj.obj("xdriveSettings"))
            if (obj.contains("address")) xdrive.frontAddress = obj.string("address")
            if (obj.contains("port")) xdrive.frontPort = obj.int("port")
        }
        if (obj.contains("xdnsResolvers")) {
            val value = obj["xdnsResolvers"]
            val lines = ArrayList<String>()
            for (item in value as? JsonArray ?: JsonArray.of(value)) {
                if (item is String) lines.add(item)
                else if (item !== JsonNull) ParseDiag.warn("xdnsResolvers", vtr(R.string.validation_json_list_items))
            }
            xdnsResolvers = xdnsResolvers.copy(lines = ResolverSources.importLines(lines, ResolverFlavor.Xdns, "xdnsResolvers"))
        }
        if (obj.contains("xdnsResolverList")) {
            if (ParseDiag.active()) {
                ParseDiag.warn("xdnsResolverList", vtr(R.string.validation_xray_xdns_resolver_list))
            } else {
                val id = obj["xdnsResolverList"]
                xdnsResolvers = xdnsResolvers.copy(ipListId = if (id is Long && id in Int.MIN_VALUE..Int.MAX_VALUE) id.toInt() else -1)
            }
        }
        return true
    }

    /**
     * xrayStreamSetting::ParseFromClash: only raw, ws, grpc and xhttp carry over (Clash's `tcp` is raw); `tls` picks
     * TLS or Reality by the public key.
     */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val clashNetwork = proxy.string("network")
        if (clashNetwork.isNotEmpty()) network = if (clashNetwork == "tcp") "raw" else clashNetwork
        if (network != "raw" && network != "ws" && network != "grpc" && network != "xhttp") return false
        if (proxy.bool("tls")) {
            if (proxy.obj("reality-opts").string("public-key").isEmpty()) {
                security = "tls"
                tls.parseFromClash(proxy)
            } else {
                security = "reality"
                reality.parseFromClash(proxy)
            }
        }
        if (network == "xhttp") xhttp.parseFromClash(proxy)
        if (network == "ws") {
            if (proxy.obj("ws-opts").bool("v2ray-http-upgrade")) {
                network = "httpupgrade"
                httpupgrade.parseFromClash(proxy)
            } else {
                ws.parseFromClash(proxy)
            }
        }
        if (network == "grpc") grpc.parseFromClash(proxy)
        return true
    }

    /** xrayStreamSetting::ExportToLink. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (network.isNotEmpty()) q.add("type" to if (network == "raw") "tcp" else network)
        val shared = xrayShareFinalmask(finalmask)
        if (shared.isNotEmpty()) q.add("fm" to shared.toCompact())
        val header = rawSettings.obj("header")
        if (network == "raw" && header.string("type") == "http") {
            q.add("headerType" to "http")
            val request = header.obj("request")
            val paths = request.array("path")
            if (paths.isNotEmpty()) q.add("path" to paths.strings().joinToString(","))
            val hosts = request.obj("headers").array("Host")
            if (hosts.isNotEmpty()) q.add("host" to hosts.strings().joinToString(","))
        }
        if (security.isNotEmpty()) q.add("security" to security)
        if (security == "tls") q.addAll(tls.exportToLink())
        if (security == "reality") q.addAll(reality.exportToLink())
        when (network) {
            "xhttp" -> q.addAll(xhttp.exportToLink())
            "ws" -> q.addAll(ws.exportToLink())
            "httpupgrade" -> q.addAll(httpupgrade.exportToLink())
            "grpc" -> q.addAll(grpc.exportToLink())
            "kcp" -> q.addAll(kcp.exportToLink())
        }
        return q
    }

    /** xrayStreamSetting::ExportToJson: network and security always, the active network's settings even when empty. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["network"] = network
        obj["security"] = security
        if (finalmask.isNotEmpty()) obj["finalmask"] = finalmask.copy()
        if (network == "raw" && rawSettings.isNotEmpty()) obj["rawSettings"] = rawSettings.copy()
        if (security == "tls") obj["tlsSettings"] = tls.exportToJson()
        else if (security == "reality") obj["realitySettings"] = reality.exportToJson()
        when (network) {
            "xhttp" -> obj["xhttpSettings"] = xhttp.exportToJson()
            "ws" -> obj["wsSettings"] = ws.exportToJson()
            "httpupgrade" -> obj["httpupgradeSettings"] = httpupgrade.exportToJson()
            "grpc" -> obj["grpcSettings"] = grpc.exportToJson()
            "kcp" -> obj["kcpSettings"] = kcp.exportToJson()
            "xdrive" -> {
                obj["xdriveSettings"] = xdrive.exportToJson()
                if (xdrive.frontAddress.isNotEmpty()) obj["address"] = Hosts.toAceHost(xdrive.frontAddress)
                if (xdrive.frontPort > 0) obj["port"] = xdrive.frontPort
            }
        }
        if (isXdns()) {
            if (xdnsResolvers.lines.isNotEmpty()) obj["xdnsResolvers"] = JsonValues.stringArray(xdnsResolvers.lines)
            if (xdnsResolvers.ipListId >= 0) obj["xdnsResolverList"] = xdnsResolvers.ipListId
        }
        return obj
    }

    /** xrayStreamSetting::ExportIdentity: no rawSettings, so subscription-rotated hosts and paths do not change the identity. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        obj["network"] = network
        obj["security"] = security
        if (security == "reality") {
            if (reality.serverName.isNotEmpty()) obj["sni"] = Hosts.toAceHost(reality.serverName)
            if (reality.fingerprint.isNotEmpty()) obj["fingerprint"] = reality.fingerprint
        } else if (security == "tls") {
            if (tls.serverName.isNotEmpty()) obj["sni"] = Hosts.toAceHost(tls.serverName)
            if (tls.fingerprint.isNotEmpty()) obj["fingerprint"] = tls.fingerprint
            if (tls.echConfigList.isNotEmpty()) obj["ech"] = if (tls.echConfigList.contains("://")) tls.echConfigList else "static"
        }
        // The VLESS address is nominal on XDRIVE; the storage is what tells two profiles apart.
        if (network == "xdrive") {
            obj["service"] = xdrive.service
            if (xdrive.remoteFolder.isNotEmpty()) obj["folder"] = xdrive.remoteFolder
            if (xdrive.service == "s3") {
                obj["endpoint"] = xdrive.s3Endpoint
                obj["bucket"] = xdrive.s3Bucket
            }
        }
        // XDNS reaches the server through resolvers, so its tunnel domain tells two profiles apart.
        if (isXdns()) {
            val names = ArrayList<String>()
            for (mask in xrayValue(finalmask, "udp", true) as? JsonArray ?: JsonArray()) {
                if (xrayMaskType(mask) != "xdns") continue
                val settings = xrayValue(mask as JsonObject, "settings", true) as? JsonObject ?: JsonObject()
                for (name in xrayXdnsNames(settings)) names.add(xrayLower(name))
            }
            obj["xdns"] = JsonValues.stringArray(names.sorted().distinct())
        }
        return obj
    }

    /** IsXdns: XDNS is network kcp with an xdns mask in finalmask.udp; imports and the editor store exactly these spellings. */
    fun isXdns(): Boolean = normalizeXrayNetwork(network) == "kcp" && xrayFinalmaskHasXdns(finalmask)

    /**
     * xrayStreamSetting::Build: binding and DNS are wired by the core, not the config. The XDNS resolver lines replace
     * the resolvers of the xdns masks; [owner] names the profile in their error. Android has no IP lists, so only the
     * lines build.
     */
    fun build(ctx: BuildContext, owner: String = "XDNS"): BuildResult {
        val obj = exportToJson()
        obj.remove("xdnsResolvers")
        obj.remove("xdnsResolverList")
        if (isXdns() && !xdnsResolvers.isEmpty()) {
            val built = ResolverSources.build(xdnsResolvers, ResolverFlavor.Xdns, owner, ctx)
            if (built.error.isNotEmpty()) return BuildResult(JsonObject(), built.error)
            if (built.resolvers.isNotEmpty()) {
                val mask = finalmask.copy()
                xrayXdnsReplaceResolvers(mask, built.resolvers)
                obj["finalmask"] = mask
            }
        }
        if (security == "tls") obj["tlsSettings"] = tls.build(ctx)
        else if (security == "reality") obj["realitySettings"] = reality.build(ctx)
        return BuildResult(obj)
    }

    /** xrayStreamSetting::Validate; [ctx] gives the global uTLS fingerprint an empty TLS fingerprint falls back to. */
    fun validate(issues: MutableList<Issue>, serverAddress: String, ctx: BuildContext) {
        val root = "streamSettings."
        issues.addIssue(Severity.Error, root + "network", XrayChecks.xrayNetwork(network), network)
        issues.addIssue(Severity.Error, root + "security", XrayChecks.xraySecurity(security), security)
        issues.addIssue(Severity.Error, root + "security", XrayChecks.xrayRealityNetwork(security, network))

        when (security.trim().lowercase()) {
            "tls" -> {
                val key = root + "tlsSettings."
                val fingerprint = tls.fingerprint.ifEmpty { ctx.utlsFingerprint }
                issues.addIssue(Severity.Warning, key + "serverName", CommonChecks.serverName(tls.serverName), tls.serverName)
                issues.addIssue(Severity.Error, key + "fingerprint", XrayChecks.xrayFingerprint(tls.fingerprint, false), tls.fingerprint)
                issues.addIssue(Severity.Error, key + "pinnedPeerCertSha256", XrayChecks.xrayPinnedSha256(tls.pinnedPeerCertSha256))
                issues.addIssue(Severity.Warning, key + "cipherSuites", XrayChecks.xrayCipherSuites(tls.cipherSuites))
                issues.addIssue(Severity.Warning, key + "cipherSuites", XrayChecks.xrayCipherSuitesNeedUnsafe(tls.cipherSuites, fingerprint))
                issues.addIssue(
                    Severity.Warning, key + "echConfigList",
                    XrayChecks.xrayEchConfigList(tls.echConfigList, tls.serverName.ifEmpty { serverAddress }),
                )
            }

            "reality" -> {
                val key = root + "realitySettings."
                issues.addIssue(Severity.Warning, key + "serverName", CommonChecks.serverName(reality.serverName), reality.serverName)
                issues.addIssue(Severity.Error, key + "fingerprint", XrayChecks.xrayFingerprint(reality.fingerprint, true), reality.fingerprint)
                issues.addIssue(Severity.Error, key + "password", TlsChecks.realityPublicKey(reality.password, true))
                issues.addIssue(Severity.Error, key + "shortId", TlsChecks.realityShortId(reality.shortId))
                issues.addIssue(Severity.Error, key + "spiderX", XrayChecks.xraySpiderX(reality.spiderX), reality.spiderX)
                issues.addIssue(Severity.Error, key + "mldsa65Verify", XrayChecks.xrayMldsa65Verify(reality.mldsa65Verify))
            }
        }

        val xdns = isXdns()
        XrayChecks.xrayFinalmask(issues, root + "finalmask.", finalmask, xdns && !xdnsResolvers.isEmpty())
        if (xdns) {
            for (line in xdnsResolvers.lines) {
                val message = ResolverChecks.xdnsLine(line)
                if (message.isEmpty()) continue
                issues.addIssue(Severity.Error, root + "xdnsResolvers", message, line.trim())
                break
            }
            if (xdnsResolvers.hasIpList()) {
                issues.addIssue(Severity.Warning, root + "xdnsResolverList", vtr(R.string.validation_xray_xdns_resolver_list_android))
            }
        }
        val net = normalizeXrayNetwork(network)
        if (net == "httpupgrade") {
            issues.addIssue(Severity.Error, root + "httpupgradeSettings.headers", XrayChecks.xrayHeadersHost(httpupgrade.headers))
        }
        if (net == "kcp") {
            for ((key, property) in XrayKcp.VALUES) {
                val text = property.get(kcp).toString()
                issues.addIssue(Severity.Error, root + "kcpSettings." + key, xrayKcpCheck(key, text, kcp.mtu), text)
            }
        }
        if (net == "xdrive") {
            val d = xdrive
            val key = root + "xdriveSettings."
            val frontPort = if (d.frontPort == 0) "" else d.frontPort.toString()
            issues.addIssue(Severity.Error, key + "service", XrayChecks.xrayXDriveService(d.service), d.service)
            issues.addIssue(Severity.Error, key + "remoteFolder", XrayChecks.xrayXDriveFolder(d.service, d.remoteFolder))
            issues.addIssue(Severity.Error, key + "secrets", XrayChecks.xrayXDriveSecrets(d.service, d.secrets))
            issues.addIssue(Severity.Error, key + "template", XrayChecks.xrayXDriveTemplate(d.service, d.templateSpec))
            issues.addIssue(Severity.Error, key + "s3.endpoint", XrayChecks.xrayXDriveS3Endpoint(d.service, d.s3Endpoint), d.s3Endpoint)
            issues.addIssue(Severity.Error, key + "s3.region", XrayChecks.xrayXDriveS3Region(d.service, d.s3Region), d.s3Region)
            issues.addIssue(
                Severity.Error, key + "s3.bucket",
                XrayChecks.xrayXDriveS3Bucket(d.service, d.s3Bucket, d.s3Endpoint, d.s3PathStyle), d.s3Bucket,
            )
            for ((name, property) in XrayXDrive.TUNING) {
                val value = property.get(d).toString()
                issues.addIssue(Severity.Error, key + name, XrayChecks.xrayXDriveTuning(value), value)
            }
            issues.addIssue(Severity.Error, root + "port", XrayChecks.xrayXDriveFrontPort(frontPort), frontPort)
            issues.addIssue(Severity.Warning, root + "port", XrayChecks.xrayXDriveFrontPortAlone(d.frontAddress, frontPort), frontPort)
            return
        }
        if (net != "xhttp") return
        val x = xhttp
        val extra = root + "xhttpSettings.extra."
        issues.addIssue(Severity.Error, root + "xhttpSettings.mode", XrayChecks.xrayXHTTPMode(x.mode), x.mode)
        issues.addIssue(Severity.Error, extra + "headers", XrayChecks.xrayHeadersHost(x.headers))
        issues.addIssue(Severity.Error, extra + "xPaddingBytes", XrayChecks.xrayXPaddingBytes(x.xPaddingBytes), x.xPaddingBytes)
        issues.addIssue(Severity.Error, extra + "xPaddingMethod", XrayChecks.xrayPaddingMethod(x.xPaddingMethod), x.xPaddingMethod)
        issues.addIssue(Severity.Error, extra + "xPaddingPlacement", XrayChecks.xrayPaddingPlacement(x.xPaddingPlacement), x.xPaddingPlacement)
        issues.addIssue(Severity.Error, extra + "uplinkHTTPMethod", XrayChecks.xrayUplinkMethod(x.uplinkHTTPMethod, x.mode), x.uplinkHTTPMethod)
        issues.addIssue(Severity.Warning, extra + "uplinkHTTPMethod", Validation.httpToken(x.uplinkHTTPMethod), x.uplinkHTTPMethod)
        issues.addIssue(
            Severity.Error, extra + "uplinkDataPlacement",
            XrayChecks.xrayUplinkDataPlacement(x.uplinkDataPlacement, x.mode), x.uplinkDataPlacement,
        )
        issues.addIssue(Severity.Error, extra + "sessionIDPlacement", XrayChecks.xrayMetaPlacement(x.sessionIDPlacement), x.sessionIDPlacement)
        issues.addIssue(Severity.Error, extra + "seqPlacement", XrayChecks.xrayMetaPlacement(x.seqPlacement), x.seqPlacement)
        issues.addIssue(Severity.Error, extra + "sessionIDTable", XrayChecks.xraySessionId(x.sessionIDTable, x.sessionIDLength))
        if (x.serverMaxHeaderBytes < 0) {
            issues.addIssue(
                Severity.Error, extra + "serverMaxHeaderBytes",
                Validation.int(x.serverMaxHeaderBytes.toString(), 0, Int.MAX_VALUE.toLong()),
            )
        }
        for ((name, value) in listOf(
            "sessionIDLength" to x.sessionIDLength,
            "uplinkChunkSize" to x.uplinkChunkSize,
            "scMaxEachPostBytes" to x.scMaxEachPostBytes,
            "scMinPostsIntervalMs" to x.scMinPostsIntervalMs,
            "scStreamUpServerSecs" to x.scStreamUpServerSecs,
            "xmux.maxConcurrency" to x.maxConcurrency,
            "xmux.maxConnections" to x.maxConnections,
            "xmux.cMaxReuseTimes" to x.cMaxReuseTimes,
            "xmux.hMaxRequestTimes" to x.hMaxRequestTimes,
            "xmux.hMaxReusableSecs" to x.hMaxReusableSecs,
        )) {
            issues.addIssue(Severity.Error, extra + name, XrayChecks.xrayInt32Range(value), value)
        }
        issues.addIssue(Severity.Error, extra + "xmux.maxConnections", XrayChecks.xrayXmuxExclusive(x.maxConnections, x.maxConcurrency))
        XrayChecks.xrayXHTTPDownloadLeg(
            issues, "",
            jsonObjectOf("streamSettings" to jsonObjectOf("network" to "xhttp", "xhttpSettings" to xhttp.exportToJson())),
        )
    }
}

// xrayWS / xrayHttpUpgrade ParseFromClash: the `ws-opts` block; a `?ed=` suffix on the path is the early-data size.
private class ClashWsOptions(proxy: ClashProxy) {
    var path: String? = null
    var ed: Int? = null
    var host: String? = null
    val headers = ArrayList<String>()

    init {
        val ws = proxy.obj("ws-opts")
        val wsPath = ws.string("path")
        if (wsPath.isNotEmpty()) {
            if (wsPath.contains("?ed=")) {
                val parts = wsPath.split("?ed=")
                path = parts[0]
                ed = QtStrings.toInt(parts[1])
            } else {
                path = wsPath
            }
        }
        // clashWsHeaders: Host goes to the host field (Xray httpupgrade refuses it inside headers, ws deprecates it
        // there); mihomo sends it as the HTTP Host and uses servername only for the SNI.
        var headerHost = ""
        for ((key, value) in ws.stringMap("headers")) {
            if (key.equals("host", ignoreCase = true)) {
                if (headerHost.isEmpty()) headerHost = value
                continue
            }
            headers.add(key)
            headers.add(value)
        }
        val servername = proxy.string("servername")
        host = if (headerHost.isNotEmpty()) headerHost else servername.ifEmpty { null }
    }
}

// buildDownloadSettingsObject (xrayStreamSetting.cpp): the Clash `download-settings` block as the Xray downloadSettings
// object the profile stores as compact JSON. mihomo takes every unset value from the proxy itself, and its download leg
// copies the whole upload XHTTP config, whose [extra] this starts from.
private fun clashDownloadSettings(proxy: ClashProxy, extra: JsonObject): JsonObject {
    val opts = proxy.obj("xhttp-opts")
    val settings = opts.obj("download-settings")
    val obj = JsonObject()
    // The upload leg has already read, and reported, the proxy's own tls and port.
    val proxyTls = unreported { proxy.bool("tls") }
    val server = Hosts.toAceHost((if (settings.present("server")) settings.string("server") else proxy.string("server")).trim())
    if (server.isNotEmpty()) obj["address"] = server
    obj["port"] = if (settings.present("port")) settings.int("port") else unreported { proxy.int("port") }
    obj["network"] = "xhttp"

    val proxyReality = proxy.obj("reality-opts")
    val uploadReality = proxyTls && proxyReality.string("public-key").isNotEmpty()
    val ownReality = settings.present("reality-opts")
    // The upload leg uses its REALITY options only with tls on.
    val downloadReality = when {
        ownReality -> settings.obj("reality-opts")
        proxyTls -> proxyReality
        else -> ClashProxy(JsonObject())
    }
    val serverName = (if (settings.present("servername")) settings.string("servername") else proxy.string("servername").ifEmpty { proxy.string("sni") }).trim()
    val ownFingerprint = settings.present("client-fingerprint")
    val clientFingerprint = if (ownFingerprint) settings.string("client-fingerprint") else proxy.string("client-fingerprint")
    // Unknown names are dropped as on the upload leg, which has already reported an inherited one it refuses too.
    fun knownFingerprint(reality: Boolean): String {
        val fingerprint = clientFingerprint.trim()
        val message = XrayChecks.xrayFingerprint(fingerprint, reality)
        if (message.isEmpty()) return fingerprint
        if (ownFingerprint || !proxyTls || XrayChecks.xrayFingerprint(fingerprint, uploadReality).isEmpty()) {
            acceptOptional(if (ownFingerprint) "xhttp-opts.download-settings.client-fingerprint" else "client-fingerprint", message, fingerprint)
        }
        return ""
    }

    val rawPublicKey = downloadReality.string("public-key")
    if (rawPublicKey.isNotEmpty()) {
        obj["security"] = "reality"
        val reality = JsonObject()
        reality["show"] = false
        if (serverName.isNotEmpty()) reality["serverName"] = Hosts.toAceHost(serverName)
        // REALITY needs uTLS, so mihomo's "none" and Xray's "unsafe" leave Xray's default, as XrayReality.build does.
        if (!isClashNoFingerprint(clientFingerprint)) {
            val fingerprint = knownFingerprint(true)
            if (fingerprint.isNotEmpty() && fingerprint.lowercase() != TLS_UNSAFE_FINGERPRINT) reality["fingerprint"] = fingerprint
        }
        val at = "xhttp-opts.download-settings.reality-opts."
        val shortId = downloadReality.string("short-id").trim()
        val publicKey: String
        if (ownReality) {
            publicKey = importRealityPublicKey(rawPublicKey, at + "public-key")
            TlsChecks.realityPublicKey(publicKey, true).let { if (it.isNotEmpty()) ParseDiag.error(at + "public-key", it) }
            TlsChecks.realityShortId(shortId).let { if (it.isNotEmpty()) ParseDiag.error(at + "short-id", it) }
        } else {
            // The upload leg has already reported and checked the key it lends.
            publicKey = unreported { importRealityPublicKey(rawPublicKey, at + "public-key") }
        }
        reality["publicKey"] = publicKey
        if (shortId.isNotEmpty()) reality["shortId"] = shortId
        obj["realitySettings"] = reality
    } else if (if (settings.present("tls")) settings.bool("tls") else proxyTls) {
        obj["security"] = "tls"
        val tls = JsonObject()
        if (serverName.isNotEmpty()) tls["serverName"] = Hosts.toAceHost(serverName)
        val ownAlpn = settings.node.isString("alpn") || settings.node.isArray("alpn")
        val alpn = JsonArray()
        for (item in if (ownAlpn) settings.strings("alpn") else proxy.strings("alpn")) {
            val protocol = item.trim()
            if (protocol.isNotEmpty()) alpn.add(protocol)
        }
        if (alpn.isNotEmpty()) tls["alpn"] = alpn
        if (isClashNoFingerprint(clientFingerprint)) {
            tls["fingerprint"] = TLS_UNSAFE_FINGERPRINT
        } else {
            val fingerprint = knownFingerprint(false)
            if (fingerprint.isNotEmpty()) tls["fingerprint"] = fingerprint
        }
        val ownPin = settings.present("fingerprint")
        val pin = (if (ownPin) settings.string("fingerprint") else proxy.string("fingerprint")).trim()
        if (pin.isNotEmpty()) {
            tls["pinnedPeerCertSha256"] = pin
            // An inherited pin on a TLS upload leg is already checked there by validate.
            val uploadPinned = proxyTls && !uploadReality
            val message = XrayChecks.xrayPinnedSha256(pin)
            if (message.isNotEmpty() && (ownPin || !uploadPinned)) {
                ParseDiag.error(if (ownPin) "xhttp-opts.download-settings.fingerprint" else "fingerprint", message)
            }
        }
        obj["tlsSettings"] = tls
    }

    val xhttp = JsonObject()
    val host = if (settings.present("host")) settings.string("host") else opts.string("host")
    if (host.isNotEmpty()) xhttp["host"] = Hosts.toAceHost(host)
    val path = if (settings.present("path")) settings.string("path") else opts.string("path")
    if (path.isNotEmpty()) xhttp["path"] = path
    xhttp["mode"] = settings.string("mode").ifEmpty { "auto" }
    extra.remove("downloadSettings")
    if (settings.present("headers")) {
        val headers = JsonObject()
        for ((key, value) in settings.stringMap("headers")) {
            // Xray refuses a Host header; it is the host field.
            if (!key.equals("host", ignoreCase = true)) headers[key] = value
            else if (!xhttp.contains("host") && value.isNotEmpty()) xhttp["host"] = Hosts.toAceHost(value)
        }
        if (headers.isEmpty()) extra.remove("headers") else extra["headers"] = headers
    }
    if (settings.bool("x-padding-obfs-mode")) extra["xPaddingObfsMode"] = true
    for ((key, jsonKey) in listOf(
        "x-padding-key" to "xPaddingKey", "x-padding-header" to "xPaddingHeader",
        "x-padding-placement" to "xPaddingPlacement", "x-padding-method" to "xPaddingMethod",
    )) {
        val value = settings.string(key)
        if (value.isNotEmpty()) extra[jsonKey] = value
    }
    val reuse = settings.obj("reuse-settings")
    val xmux = JsonObject()
    for ((key, jsonKey) in listOf(
        "max-concurrency" to "maxConcurrency", "max-connections" to "maxConnections", "c-max-reuse-times" to "cMaxReuseTimes",
        "h-max-request-times" to "hMaxRequestTimes", "h-max-reusable-secs" to "hMaxReusableSecs",
    )) {
        val value = reuse.string(key)
        if (value.isNotEmpty()) xmux[jsonKey] = value
    }
    val keepAlivePeriod = reuse.string("h-keep-alive-period")
    if (keepAlivePeriod.isNotEmpty()) xmux["hKeepAlivePeriod"] = QtStrings.toLong(keepAlivePeriod)
    if (xmux.isNotEmpty()) extra["xmux"] = xmux
    if (extra.isNotEmpty()) xhttp["extra"] = extra
    obj["xhttpSettings"] = xhttp
    return obj
}
