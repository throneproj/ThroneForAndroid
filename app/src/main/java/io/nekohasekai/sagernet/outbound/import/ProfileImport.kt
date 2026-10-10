package io.nekohasekai.sagernet.outbound.import

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.outbound.types.OpenConnect
import io.nekohasekai.sagernet.outbound.types.OpenVpn
import io.nekohasekai.sagernet.outbound.types.Shadowsocks
import io.nekohasekai.sagernet.outbound.types.WireGuard
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * The desktop's subscription / clipboard / file parser (src/configs/sub/SubscriptionParser.cpp with the scanning
 * helpers of include/configs/sub/SubscriptionScan.hpp). [parse] is ParseText: the whole body is base64-decoded
 * when it looks like one blob (standard, then a wrapped standard blob, then the url-safe alphabet), then the
 * document is classified in the desktop's order: JSON (Xray outbounds / configs, sing-box outbounds / endpoints,
 * SIP008), Clash YAML, a WireGuard INI file, an OpenVPN profile, an OpenConnect profile, and finally one item per
 * line (a line opening a bracket yields its balanced JSON block), each of which may itself be base64 or a share
 * link.
 */
object ProfileImport {

    /** The profiles produced and the desktop's log / warning lines of the run. */
    class Result(@JvmField val outbounds: List<Outbound>, @JvmField val messages: List<String>)

    private const val MAX_DEPTH = 16

    /** The size cap of a compressed vpn:// payload (SubscriptionParser.cpp:229). */
    private const val MAX_VPN_PAYLOAD = 16L * 1024 * 1024

    @JvmStatic
    @JvmOverloads
    fun parse(text: String, preference: OutboundFactory.XrayVlessPreference = OutboundFactory.DEFAULT_XRAY_VLESS_PREFERENCE): Result {
        var body = Scan.trim(text)
        if (body.isEmpty()) return Result(emptyList(), emptyList())
        if (Scan.looksLikeBase64(body)) {
            Scan.decodeBase64(body)?.let { body = it }
        } else if (Scan.looksLikeWrappedBase64(body)) {
            Scan.decodeBase64Lenient(body)?.let { body = it }
        } else if (Scan.looksLikeUrlSafeBase64(body)) {
            Scan.decodeBase64UrlSafe(body)?.let { body = it }
        }
        val parser = Parser(preference)
        parser.document(body, allowBase64 = false, needParse = true, depth = 0)
        return Result(parser.produced, parser.messages)
    }

    private enum class SingBoxSubType { OutboundInJson, OutboundJsonArray, OutboundObject, Invalid }
    private enum class XraySubType { OutboundInJson, OutboundJsonArray, OutboundObject, ConfigJsonArray, Invalid }

    private class Parser(private val preference: OutboundFactory.XrayVlessPreference) {
        val produced = ArrayList<Outbound>()
        val messages = ArrayList<String>()

        /** Issue #63: a node its core cannot run is logged and dropped ([CoreSupport]). */
        private fun produce(outbound: Outbound?) {
            if (outbound == null) return
            val problem = CoreSupport.problem(outbound)
            if (problem != null) {
                skip(outbound, problem)
                return
            }
            produced.add(outbound)
        }

        private fun skip(outbound: Outbound, problem: String) {
            log("Skipped ${outbound.displayTypeAndName()}: $problem")
        }

        private fun log(line: String) {
            messages.add(line)
        }

        private fun warn(title: String, text: String) {
            messages.add("$title: $text")
        }

        /** Parser::document (SubscriptionParser.cpp:361-408); [overrideName] names a WireGuard profile that has none. */
        fun document(raw: String, allowBase64: Boolean, needParse: Boolean, depth: Int, overrideName: String = "") {
            if (depth > MAX_DEPTH) return
            val text = Scan.trim(raw)
            if (text.isEmpty()) return

            if (allowBase64) {
                val decoded = when {
                    Scan.looksLikeBase64(text) -> Scan.decodeBase64(text)
                    Scan.looksLikeUrlSafeBase64(text) -> Scan.decodeBase64UrlSafe(text)
                    else -> null
                }
                if (decoded != null) {
                    document(decoded, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = overrideName)
                    return
                }
            }

            // QJsonDocument::fromJson rejects trailing content, which org.json would silently ignore
            if ((text[0] == '{' || text[0] == '[') && Scan.matchingClose(text, 0) == text.length - 1) {
                val value = JsonInput.parseValue(text)
                if (value is JsonObject || value is JsonArray) {
                    json(value, text)
                    return
                }
            }

            if (text.contains("proxies:")) {
                clash(text)
                return
            }

            if (hasWireGuardSections(text)) {
                wireguardFile(text, overrideName)
                return
            }

            if (looksLikeOvpnConfig(text)) {
                openVpnFile(text)
                return
            }

            if (looksLikeOpenConnectProfile(text)) {
                openConnectProfile(text)
                return
            }

            if (needParse && text.contains('\n')) {
                Scan.forEachItem(text) { item ->
                    document(item, allowBase64 = true, needParse = false, depth = depth + 1, overrideName = overrideName)
                }
                return
            }

            link(text, depth, overrideName)
        }

        /** Parser::json (SubscriptionParser.cpp:340-370): Xray first, its configs share the `outbounds` wrapper with sing-box. */
        private fun json(doc: Any, text: String) {
            val xrayType = xraySubType(doc)
            if (xrayType == XraySubType.OutboundObject) {
                produce(Custom.fromXrayOutbound(doc as JsonObject))
                return
            }
            if (xrayType != XraySubType.Invalid) {
                xray(doc, xrayType)
                return
            }

            val subType = singBoxSubType(doc)
            if (subType == SingBoxSubType.OutboundObject) {
                produce(Custom.fromSingBoxOutboundText(text))
                return
            }
            if (subType != SingBoxSubType.Invalid) {
                singBox(doc, subType)
                return
            }

            if (text.contains("version") && text.contains("servers")) sip008(doc)
        }

        /** getSingBoxSubType (SubscriptionParser.cpp:100-113). */
        private fun singBoxSubType(doc: Any): SingBoxSubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") || doc.contains("endpoints")) return SingBoxSubType.OutboundInJson
                if (doc.contains("type")) return SingBoxSubType.OutboundObject
                return SingBoxSubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject && first.contains("type")) return SingBoxSubType.OutboundJsonArray
            }
            return SingBoxSubType.Invalid
        }

        /** getXraySubType (SubscriptionParser.cpp:115-140): Xray tags outbounds with `protocol` where sing-box uses `type`. */
        private fun xraySubType(doc: Any): XraySubType {
            if (doc is JsonObject) {
                if (doc.contains("outbounds") && hasXrayOutbound(doc.array("outbounds"))) return XraySubType.OutboundInJson
                if (doc.contains("protocol")) return XraySubType.OutboundObject
                return XraySubType.Invalid
            }
            if (doc is JsonArray && doc.isNotEmpty()) {
                val first = doc[0]
                if (first is JsonObject) {
                    if (first.contains("protocol")) return XraySubType.OutboundJsonArray
                    if (first.contains("outbounds") && hasXrayOutbound(first.array("outbounds"))) return XraySubType.ConfigJsonArray
                }
            }
            return XraySubType.Invalid
        }

        private fun hasXrayOutbound(outbounds: JsonArray): Boolean =
            outbounds.any { it is JsonObject && it.contains("protocol") }

        /** Parser::singBox (SubscriptionParser.cpp:372-400). */
        private fun singBox(doc: Any, type: SingBoxSubType) {
            val outbounds: JsonArray
            val endpoints: JsonArray
            when (type) {
                SingBoxSubType.OutboundInJson -> {
                    val json = doc as JsonObject
                    outbounds = json.array("outbounds")
                    endpoints = json.array("endpoints")
                }

                SingBoxSubType.OutboundJsonArray -> {
                    outbounds = doc as JsonArray
                    endpoints = JsonArray()
                }

                else -> return
            }
            val handle = { value: Any ->
                val out = value as? JsonObject
                if (out != null) {
                    if (out.isEmpty()) {
                        log("invalid outbound: empty object")
                    } else {
                        val profileType = OutboundFactory.typeForSingBox(out.string("type"))
                        if (profileType != null) {
                            val outbound = OutboundFactory.newByType(profileType)
                            if (outbound.parseFromJson(out)) produce(outbound)
                        }
                    }
                }
            }
            for (outbound in outbounds) handle(outbound)
            for (endpoint in endpoints) handle(endpoint)
        }

        /** Parser::xray (SubscriptionParser.cpp:402-434). */
        private fun xray(doc: Any, type: XraySubType) {
            // Each element is a self-contained config (balancers, dialerProxy chains) that must run verbatim.
            if (type == XraySubType.ConfigJsonArray) {
                for (c in doc as JsonArray) {
                    val cfg = c as? JsonObject ?: continue
                    produce(Custom.fromXrayFullConfig(cfg))
                }
                return
            }
            val outbounds: JsonArray = when (type) {
                XraySubType.OutboundInJson -> (doc as JsonObject).array("outbounds")
                XraySubType.OutboundJsonArray -> doc as JsonArray
                else -> return
            }
            for (o in outbounds) {
                val out = o as? JsonObject ?: continue
                produce(Custom.fromXrayOutbound(out))
            }
        }

        /** Parser::sip008 (SubscriptionParser.cpp:436-447). */
        private fun sip008(doc: Any) {
            val servers = (doc as? JsonObject)?.array("servers") ?: return
            for (o in servers) {
                val out = o as? JsonObject ?: JsonObject()
                if (out.isEmpty()) {
                    log("invalid server object")
                    continue
                }
                val shadowsocks = Shadowsocks()
                if (!shadowsocks.parseFromSip008(out)) continue
                produce(shadowsocks)
            }
        }

        /** Parser::clash (SubscriptionParser.cpp:449-476): VLESS over xhttp or with encryption needs the Xray core. */
        private fun clash(text: String) {
            try {
                val proxies = ClashYaml.proxies(text) ?: return
                for (node in proxies) {
                    val proxy = ClashProxy(node)
                    val profileType = OutboundFactory.typeForClash(proxy.type) ?: continue
                    val encryption = proxy.string("encryption")
                    val outbound = if (proxy.type == "vless" && (proxy.string("network") == "xhttp" || (encryption.isNotEmpty() && encryption != "none"))) {
                        OutboundFactory.newByType("xrayvless")
                    } else {
                        OutboundFactory.newByType(profileType)
                    }
                    if (!outbound.parseFromClash(node)) continue
                    val problem = CoreSupport.clashProblem(proxy, outbound)
                    if (problem != null) {
                        skip(outbound, problem)
                        continue
                    }
                    produce(outbound)
                }
            } catch (e: Exception) {
                warn("YAML Exception", e.message ?: "Failed to parse the Clash configuration.")
            }
        }

        /** Parser::wireguardFile (SubscriptionParser.cpp:541-548). */
        private fun wireguardFile(text: String, overrideName: String) {
            val wireguard = WireGuard()
            if (!wireguard.parseFromLink(text)) return

            val names = extractWireGuardNames(text)
            setNameIfEmpty(wireguard, overrideName, names.explicitName, names.peerComment)
            produce(wireguard)
        }

        /** Parser::openVpnFile (SubscriptionParser.cpp:484-494). */
        private fun openVpnFile(text: String) {
            val problems = ArrayList<String>()
            val openVpn = OpenVpn()
            val ok = openVpn.parseOvpnConfig(text, problems)
            for (problem in problems) log("OpenVPN: $problem")
            if (!ok) {
                log("Failed to import the OpenVPN profile.")
                return
            }
            produce(openVpn)
        }

        /** Parser::openConnectProfile (SubscriptionParser.cpp:496-525). */
        private fun openConnectProfile(text: String) {
            val problems = ArrayList<String>()
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) {
                val hosts = ArrayList<OpenConnect>()
                val ok = OpenConnect.parseAnyConnectXml(text, hosts, problems)
                for (problem in problems) log("OpenConnect: $problem")
                if (!ok) {
                    log("Failed to import the OpenConnect profile.")
                    return
                }
                for (host in hosts) produce(host)
                return
            }
            val openConnect = OpenConnect()
            val ok = openConnect.parseOpenConnectProfile(text, problems)
            for (problem in problems) log("OpenConnect: $problem")
            if (!ok) {
                log("Failed to import the OpenConnect profile.")
                return
            }
            produce(openConnect)
        }

        /**
         * Parser::link (SubscriptionParser.cpp:591-621): [overrideName] only reaches a link of a wireguard scheme
         * (json:// and throne://add/ match no scheme).
         */
        private fun link(line: String, depth: Int, overrideName: String) {
            if (line.startsWith("vpn://", ignoreCase = true)) {
                vpnLink(line, depth)
                return
            }
            val outbound = OutboundFactory.parseLink(line, preference)
            if (outbound != null && OutboundFactory.typeForScheme(line) == "wireguard") setNameIfEmpty(outbound, overrideName)
            produce(outbound)
        }

        /**
         * Parser::vpnLink (SubscriptionParser.cpp:645-718), the AmneziaVPN share link: base64 (standard, then
         * url-safe), optionally Qt-compressed, holding either the `containers` JSON (each protocol's `last_config`
         * carries a config document) or a config document itself. The #fragment, else the export's `description` or
         * `name`, becomes the name of the WireGuard profiles inside.
         */
        private fun vpnLink(line: String, depth: Int) {
            var raw = line.substring(6)
            var fragmentName = ""
            val fragment = raw.indexOf('#')
            if (fragment != -1) {
                fragmentName = LinkCodec.decodeFully(raw.substring(fragment + 1)).trim()
                raw = raw.substring(0, fragment)
            }
            raw = LinkCodec.decodeFully(raw)
            val decoded = Base64Strict.decode(raw)?.takeIf { it.isNotEmpty() }
                ?: Base64Strict.decode(raw, urlSafe = true)?.takeIf { it.isNotEmpty() }
            if (decoded == null) {
                log("Failed to decode the vpn:// link.")
                return
            }
            val data = uncompressVpnPayload(decoded) ?: decoded

            val before = produced.size
            val text = String(data, Charsets.UTF_8)
            val trimmed = Scan.trim(text)
            val doc = if (trimmed.startsWith("{") && Scan.matchingClose(trimmed, 0) == trimmed.length - 1) {
                JsonInput.parseObjectOrNull(trimmed)
            } else {
                null
            }
            if (doc != null && doc.contains("containers")) {
                var jsonName = doc.string("description").trim()
                if (jsonName.isEmpty()) jsonName = doc.string("name").trim()
                val targetName = fragmentName.ifEmpty { jsonName }

                val entries = ArrayList<VpnConfigEntry>()
                var wgCount = 0

                for (container in doc.array("containers")) {
                    val containerObj = container as? JsonObject ?: continue
                    val containerType = containerObj.string("container").trim()

                    for (key in containerObj.keys()) {
                        val protoObj = containerObj[key] as? JsonObject ?: continue
                        val conf = when (val lastConfig = protoObj["last_config"]) {
                            is String -> {
                                val inner = JsonInput.parseObjectOrNull(lastConfig)
                                if (inner != null && inner.contains("config")) inner.string("config") else lastConfig
                            }

                            is JsonObject -> lastConfig.string("config")
                            else -> ""
                        }
                        if (conf.isEmpty()) continue

                        val isWireGuard = hasWireGuardSections(conf)
                        if (isWireGuard) wgCount++
                        entries.add(VpnConfigEntry(conf, containerType, isWireGuard))
                    }
                }

                for (e in entries) {
                    // Several WireGuard confs in one export would share a single name, so each is tagged with its container type.
                    var name = targetName
                    if (e.isWireGuard && wgCount > 1 && name.isNotEmpty() && e.containerType.isNotEmpty()) {
                        name += " (" + e.containerType + ")"
                    }
                    document(e.conf, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = name)
                }
            } else {
                document(text, allowBase64 = false, needParse = true, depth = depth + 1, overrideName = fragmentName)
            }
            if (produced.size == before) log("No importable profile found in the vpn:// link.")
        }

        /**
         * uncompressVpnPayload (SubscriptionParser.cpp:227-236): qUncompress data (4-byte big-endian size, then a
         * zlib stream) after validating the header; null when it is not such data.
         */
        private fun uncompressVpnPayload(data: ByteArray): ByteArray? {
            if (data.size < 6) return null
            val expected = ((data[0].toLong() and 0xFF) shl 24) or ((data[1].toLong() and 0xFF) shl 16) or
                ((data[2].toLong() and 0xFF) shl 8) or (data[3].toLong() and 0xFF)
            val cmf = data[4].toInt() and 0xFF
            val flg = data[5].toInt() and 0xFF
            if (expected == 0L || expected > MAX_VPN_PAYLOAD || (cmf and 0x0F) != 8 || ((cmf shl 8) or flg) % 31 != 0) {
                return null
            }
            val inflater = Inflater()
            return try {
                inflater.setInput(data, 4, data.size - 4)
                val out = ByteArrayOutputStream(minOf(expected, 1L shl 20).toInt())
                val buffer = ByteArray(64 * 1024)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && !inflater.finished()) return null
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_VPN_PAYLOAD) return null
                }
                out.toByteArray()
            } catch (e: Exception) {
                null
            } finally {
                inflater.end()
            }
        }

        /** looksLikeOvpnConfig (SubscriptionParser.cpp:181-198). */
        private fun looksLikeOvpnConfig(text: String): Boolean {
            var hasRemote = false
            var hasClientMarker = false
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#' || line[0] == ';') return@forEachLine true
                if (line.startsWith("remote ") || line == "<ca>" || line == "<tls-auth>" || line == "<tls-crypt>" ||
                    line == "<tls-crypt-v2>" || line == "<secret>"
                ) {
                    hasRemote = true
                }
                if (line == "client" || line == "tls-client" || line.startsWith("dev ") || line.startsWith("dev-type ") ||
                    line.startsWith("proto ")
                ) {
                    hasClientMarker = true
                }
                result = hasRemote && hasClientMarker
                !result
            }
            return result
        }

        /** looksLikeOpenConnectProfile (SubscriptionParser.cpp:200-224). */
        private fun looksLikeOpenConnectProfile(text: String): Boolean {
            if (text.contains("<AnyConnectProfile") || text.contains("<ServerList")) return true
            var matched = false
            if (text.contains("protocol")) {
                Scan.forEachLine(text) { raw ->
                    if (!raw.contains("protocol")) return@forEachLine true
                    matched = OPENCONNECT_CLI.containsMatchIn(raw) || OPENCONNECT_FILE.matches(raw)
                    !matched
                }
            }
            if (matched) return true
            var result = false
            Scan.forEachLine(text) { raw ->
                val line = Scan.trim(raw)
                if (line.isEmpty() || line[0] == '#') return@forEachLine true
                result = line.startsWith("openconnect ")
                false
            }
            return result
        }
    }

    private val OPENCONNECT_CLI = Regex("""(?:^|\s)--protocol[= ](?:anyconnect|nc|gp|pulse|f5|fortinet)\b""")
    private val OPENCONNECT_FILE = Regex("""[ \t]*protocol[ \t]*=[ \t]*(?:anyconnect|nc|gp|pulse|f5|fortinet)[ \t]*""")

    /** hasWireGuardSections (SubscriptionParser.cpp:103-106): shared by document and vpnLink so both agree on what a WireGuard conf is. */
    private fun hasWireGuardSections(text: String): Boolean = text.contains("[Interface]") && text.contains("[Peer]")

    // PCRE2's \s and \w spelled out: Unicode-wide where the desktop sets UseUnicodePropertiesOption, ASCII elsewhere.
    // java.util.regex (ASCII by default) and Android's ICU engine (Unicode) would each get one of them wrong.
    private val WG_NAME_SEPARATOR = Regex("""[\p{Z}\t\n\u000B\f\r\u0085_\-.:]+""")
    private val WG_COMMENT_MARKERS = Regex("""^[#;\p{Z}\t\n\u000B\f\r\u0085]+""")
    private val WG_DIRECTIVE = Regex("""^[A-Za-z0-9_]+[ \t\n\u000B\f\r]*=""")
    private val WG_EXPLICIT_NAME = Regex("""^(?:name|remarks?)[ \t\n\u000B\f\r]*[:=][ \t\n\u000B\f\r]*(.+)$""", RegexOption.IGNORE_CASE)
    private val WG_GENERIC_WORDS = setOf("peer", "server", "wireguard", "configuration", "config", "interface", "client", "wg")

    /**
     * looksLikeName (SubscriptionParser.cpp:108-121): filters dividers and boilerplate headers ("# WireGuard
     * configuration", "# Peer 1") so only a real node name can become a profile name.
     */
    private fun looksLikeName(comment: String): Boolean {
        // QChar::isLetterOrNumber covers every L* and N* category; Char.isLetterOrDigit stops at Nd
        if (comment.none { it.category.code[0] in "LN" }) return false
        for (word in comment.lowercase().split(WG_NAME_SEPARATOR)) {
            val w = word.trimEnd { it.isDigit() }
            if (w.isEmpty()) continue
            if (w !in WG_GENERIC_WORDS) return true
        }
        return false
    }

    /** WireGuardNames (SubscriptionParser.cpp:123-126). */
    private class WireGuardNames {
        var explicitName = ""
        var peerComment = ""
    }

    /**
     * extractWireGuardNames (SubscriptionParser.cpp:128-163): an explicit `name`/`remark(s)` comment, and the comment
     * directly above the last `[Peer]`. Any blank or config line in between drops that comment, so `[Interface]`
     * comments cannot leak in.
     */
    private fun extractWireGuardNames(text: String): WireGuardNames {
        val out = WireGuardNames()
        var lastComment = ""

        Scan.forEachLine(text) { raw ->
            val line = Scan.trim(raw)
            val isComment = line.isNotEmpty() && (line[0] == '#' || line[0] == ';')
            if (!isComment) {
                // Assigned on every [Peer]: the name comes from the last peer, the one WireGuard.parseFromLink keeps.
                if (line.startsWith("[peer]", ignoreCase = true)) out.peerComment = lastComment
                lastComment = ""
                return@forEachLine true
            }

            val comment = WG_COMMENT_MARKERS.replace(line, "").trim()

            if (out.explicitName.isEmpty()) {
                WG_EXPLICIT_NAME.find(comment)?.let { out.explicitName = it.groupValues[1].trim() }
            }

            // Commented-out options such as "# DNS = 1.1.1.1" are not names.
            lastComment = if (!WG_DIRECTIVE.containsMatchIn(comment) && looksLikeName(comment)) comment else ""
            true
        }

        return out
    }

    /**
     * setNameIfEmpty (SubscriptionParser.cpp:165-175): candidates in priority order; a name the link parse already
     * read from a #fragment is never overwritten.
     */
    private fun setNameIfEmpty(outbound: Outbound, vararg candidates: String) {
        if (outbound.name.trim().isNotEmpty()) return
        for (c in candidates) {
            val t = c.trim()
            if (t.isNotEmpty()) {
                outbound.name = t
                return
            }
        }
    }

    /** ConfigEntry of Parser::vpnLink (SubscriptionParser.cpp:669-673): one config document of a `containers` export. */
    private class VpnConfigEntry(val conf: String, val containerType: String, val isWireGuard: Boolean)

    /** Subscription::scan (include/configs/sub/SubscriptionScan.hpp) on strings. */
    internal object Scan {
        private fun isSpace(c: Char): Boolean =
            c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

        private fun isBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

        private fun isUrlSafeBase64Char(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '='

        /** scan::trim: ASCII whitespace and NBSP on both ends, a BOM at the front. */
        fun trim(s: String): String {
            var start = 0
            var end = s.length
            while (start < end) {
                val c = s[start]
                if (isSpace(c) || c == '\u00A0' || c == '\uFEFF') start++ else break
            }
            while (end > start) {
                val c = s[end - 1]
                if (isSpace(c) || c == '\u00A0') end-- else break
            }
            return s.substring(start, end)
        }

        /** scan::matchingClose: the index of the bracket closing the one at [open], honouring JSON string escapes; -1 when unbalanced. */
        fun matchingClose(s: String, open: Int): Int {
            var depth = 0
            var inString = false
            var escaped = false
            var i = open
            while (i < s.length) {
                val c = s[i]
                if (inString) {
                    when {
                        escaped -> escaped = false
                        c == '\\' -> escaped = true
                        c == '"' -> inString = false
                    }
                } else if (c == '"') {
                    inString = true
                } else if (c == '{' || c == '[') {
                    depth++
                } else if (c == '}' || c == ']') {
                    if (--depth == 0) return i
                }
                i++
            }
            return -1
        }

        /** scan::forEachLine: the segments of QString::split('\n'); [fn] returns false to stop. */
        fun forEachLine(s: String, fn: (String) -> Boolean) {
            var idx = 0
            while (true) {
                val nl = s.indexOf('\n', idx)
                if (nl < 0) {
                    fn(s.substring(idx))
                    return
                }
                if (!fn(s.substring(idx, nl))) return
                idx = nl + 1
            }
        }

        /** scan::forEachItem: one item per line, except that a line opening a bracket yields the whole balanced block. */
        fun forEachItem(s: String, fn: (String) -> Unit) {
            val n = s.length
            var idx = 0
            while (idx < n) {
                if (s[idx] == '\n') {
                    idx++
                    continue
                }
                if (s[idx] == '{' || s[idx] == '[') {
                    val end = matchingClose(s, idx)
                    if (end >= 0) {
                        fn(s.substring(idx, end + 1))
                        idx = end + 1
                        continue
                    }
                }
                var nl = s.indexOf('\n', idx)
                if (nl < 0) nl = n
                fn(s.substring(idx, nl))
                idx = nl + 1
            }
        }

        fun looksLikeBase64(s: String): Boolean = s.isNotEmpty() && s.all { isBase64Char(it) }

        /** The url-safe alphabet, which only counts when a `-` or `_` proves it is not standard base64. */
        fun looksLikeUrlSafeBase64(s: String): Boolean =
            s.isNotEmpty() && s.all { isUrlSafeBase64Char(it) } && s.any { it == '-' || it == '_' }

        /** scan::looksLikeWrappedBase64: one blob wrapped at a fixed column, as `base64` and MIME emit it. */
        fun looksLikeWrappedBase64(s: String): Boolean {
            var width = 0
            var lines = 0
            var sawShort = false
            var ok = true
            forEachLine(s) { raw ->
                var line = raw
                while (line.isNotEmpty() && isSpace(line[line.length - 1])) line = line.substring(0, line.length - 1)
                if (line.isEmpty()) return@forEachLine true
                if (!line.all { isBase64Char(it) }) {
                    ok = false
                    return@forEachLine false
                }
                lines++
                if (width == 0) {
                    width = line.length
                    return@forEachLine true
                }
                if (line.length > width || sawShort) {
                    ok = false
                    return@forEachLine false
                }
                if (line.length < width) sawShort = true
                true
            }
            return ok && lines >= 2 && width % 4 == 0
        }

        /** scan::decodeBase64 (AbortOnBase64DecodingErrors): null when invalid or empty. */
        fun decodeBase64(s: String): String? = Base64Strict.decode(s)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        fun decodeBase64UrlSafe(s: String): String? =
            Base64Strict.decode(s, urlSafe = true)?.takeIf { it.isNotEmpty() }?.let { String(it, Charsets.UTF_8) }

        /** QByteArray::fromBase64 without abort: characters outside the alphabet (line breaks, padding) are skipped. */
        fun decodeBase64Lenient(s: String): String? {
            val filtered = s.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' }
            return decodeBase64(filtered)
        }
    }
}
