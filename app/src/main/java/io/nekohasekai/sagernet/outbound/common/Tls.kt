package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
private const val LOWER_HEX = "0123456789abcdef"

/** The core hex-decodes short_id into an 8-byte array: a longer one panics it, an odd length fails. */
private val REALITY_SHORT_ID = Regex("(?:[0-9a-fA-F]{2}){0,8}")

/** tlsUnsafeFingerprint (TLS.h): Xray's name for Go's own TLS stack; for sing-box it means uTLS off, overriding the global default. */
const val TLS_UNSAFE_FINGERPRINT = "unsafe"

/** splitCipherSuites (TLS.h): Xray's `cs` link value is colon separated; commas are accepted too. */
fun splitCipherSuites(value: String): MutableList<String> =
    value.replace(',', ':').split(':').map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }

/** tlsSpoofMethods (TLS.h). */
private val TLS_SPOOF_METHODS = listOf("", "wrong-sequence", "wrong-checksum", "wrong-ack", "wrong-md5", "wrong-timestamp")

/** tlsSecureCipherSuites (TLS.h): Go crypto/tls CipherSuites(), the only names sing-box accepts. */
val TLS_SECURE_CIPHER_SUITES = listOf(
    "TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384", "TLS_CHACHA20_POLY1305_SHA256",
    "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
    "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
    "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
)

/** tlsInsecureCipherSuites (TLS.h): Go crypto/tls InsecureCipherSuites(); Xray accepts them too, sing-box refuses them. */
val TLS_INSECURE_CIPHER_SUITES = listOf(
    "TLS_RSA_WITH_RC4_128_SHA", "TLS_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA",
    "TLS_RSA_WITH_AES_256_CBC_SHA", "TLS_RSA_WITH_AES_128_CBC_SHA256", "TLS_RSA_WITH_AES_128_GCM_SHA256",
    "TLS_RSA_WITH_AES_256_GCM_SHA384", "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA", "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
    "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
    "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
)

/** kTriStateOff (TLS.h): index of "Off" in the Keep Default / On / Off menus. */
const val TRI_STATE_OFF = 2

/**
 * certificateSha256FromPcs (TLS.cpp): Xray's pcs and sing-box's certificate_sha256 both hash the whole DER
 * certificate, hex there and base64 here; anything but a 64-digit hex value (':' separators allowed) is dropped,
 * as an import Error under [reportField] when one is given.
 */
private fun certificateSha256FromPcs(pcs: String, reportField: String = ""): MutableList<String> {
    val hashes = ArrayList<String>()
    for (item in QtStrings.splitSkipEmpty(pcs, ",")) {
        val value = item.trim()
        if (value.isEmpty()) continue
        val hex = value.replace(":", "")
        if (SHA256_HEX.matches(hex)) {
            hashes.add(Base64Strict.encode(ByteArray(32) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }))
        } else if (reportField.isNotEmpty()) {
            ParseDiag.error(reportField, vtr(R.string.validation_tls_pin_hex), value)
        }
    }
    return hashes
}

private class TlsPemBlock(val label: String, val bodyOk: Boolean)

/** [trailing]: non-empty text after the first complete block. */
private class TlsPemScan(val blocks: List<TlsPemBlock>, val trailing: Boolean)

/** tlsPemBlocks (TLS.cpp): Go pem.Decode's view of the text, BEGIN/END lines around a base64 body; "Key: value" header lines are skipped. */
private fun tlsPemBlocks(lines: List<String>): TlsPemScan {
    val blocks = ArrayList<TlsPemBlock>()
    var trailing = false
    var label = ""
    val body = StringBuilder()
    var inside = false
    for (raw in lines.joinToString("\n").split('\n')) {
        val line = raw.trim()
        if (!inside) {
            if (line.startsWith("-----BEGIN ") && line.endsWith("-----") && line.length > 16) {
                label = line.substring(11, line.length - 5)
                body.setLength(0)
                inside = true
            } else if (blocks.isNotEmpty() && line.isNotEmpty()) {
                trailing = true
            }
            continue
        }
        if (line == "-----END $label-----") {
            blocks.add(TlsPemBlock(label, Validation.base64(body.toString()).isEmpty()))
            inside = false
        } else if (!line.contains(':')) {
            body.append(line)
        }
    }
    return TlsPemScan(blocks, trailing)
}

private fun tlsHasText(lines: List<String>): Boolean = lines.any { it.isNotBlank() }

/** tlsLinkList (TLS.cpp): link values trimmed, empty items dropped (an empty ALPN entry makes Go abort every handshake). */
private fun tlsLinkList(value: String): MutableList<String> = value.split(',').map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }

private fun tlsCipherSuiteRule(name: String): String = when (name) {
    in TLS_SECURE_CIPHER_SUITES -> ""
    in TLS_INSECURE_CIPHER_SUITES -> vtr(R.string.validation_tls_cipher_insecure)
    else -> vtr(R.string.validation_tls_cipher_unknown)
}

/** tlsKnownCurves (TLS.cpp): unknown curves are dropped with a Warning. */
private fun tlsKnownCurves(names: List<String>, field: String): MutableList<String> =
    names.map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() && acceptOptional(field, TlsChecks.tlsCurve(it), it) }

/** tlsPemLines (TLS.cpp): PEM text kept one line per item, whether sing-box wrote it as one string or as a list. */
private fun tlsPemLines(value: Any?, field: String): MutableList<String> =
    jsonStringList(value, field).flatMapTo(ArrayList()) { item -> item.split('\n').filter { it.isNotEmpty() } }

/** pcsFromCertificateSha256 (TLS.cpp): only values that decode to 32 bytes are written, as lower-case hex. */
private fun pcsFromCertificateSha256(hashes: List<String>): String {
    val pcs = ArrayList<String>()
    for (hash in hashes) {
        val raw = Base64Strict.decodeLenient(hash.trim())
        if (raw.size != 32) continue
        val sb = StringBuilder(64)
        for (b in raw) {
            val v = b.toInt() and 0xFF
            sb.append(LOWER_HEX[v shr 4]).append(LOWER_HEX[v and 0xF])
        }
        pcs.add(sb.toString())
    }
    return pcs.joinToString(",")
}

/** CertificatePinsFromHex (TLS.cpp): pins pasted as hex SHA-256 (as openssl prints them, colons allowed) in the base64 form sing-box takes; others unchanged. */
fun certificatePinsFromHex(pins: List<String>): MutableList<String> {
    val out = ArrayList<String>()
    for (pin in pins) {
        if (pin.isBlank()) continue
        val converted = certificateSha256FromPcs(pin)
        out.add(if (converted.size == 1 && !pin.contains(',')) converted[0] else pin.trim())
    }
    return out
}

/** ImportTlsFingerprint (TLS.cpp): the fingerprint as given, lower-cased when only its case was wrong, else empty with a Warning under [field]. */
fun importTlsFingerprint(raw: String, field: String): String {
    val fingerprint = raw.trim()
    val message = TlsChecks.singBoxFingerprint(fingerprint)
    if (message.isEmpty()) return fingerprint
    val lower = fingerprint.lowercase()
    if (TlsChecks.singBoxFingerprint(lower).isEmpty()) return lower
    acceptOptional(field, message, fingerprint)
    return ""
}

/** ImportTlsCipherSuites (TLS.cpp): suites sing-box would refuse are dropped with a Warning under [field]. */
fun importTlsCipherSuites(names: List<String>, field: String): MutableList<String> =
    names.filterTo(ArrayList()) { acceptOptional(field, tlsCipherSuiteRule(it), it) }

/** ImportRealityPublicKey (TLS.cpp): the key without the trailing = padding that sing-box and Xray (RawURLEncoding) refuse. */
@Suppress("UNUSED_PARAMETER")
fun importRealityPublicKey(raw: String, field: String): String {
    val key = raw.trim()
    if (!key.endsWith('=')) return key
    val unpadded = key.trimEnd('=')
    if (unpadded.isEmpty() || TlsChecks.realityPublicKey(unpadded).isNotEmpty()) return key
    return unpadded
}

/** IsClashNoFingerprint (TLS.cpp): mihomo's client-fingerprint "none", its way of turning uTLS off. */
fun isClashNoFingerprint(fp: String): Boolean = fp.trim().equals("none", ignoreCase = true)

/** The Checks of TLS.h: an empty string when acceptable, else one sentence; shared by the editors and the import check. */
object TlsChecks {
    /** sing-box common/tls/utls_client.go uTLSClientHelloID, case-sensitive; "unsafe" is Throne's. */
    private val SING_BOX_FINGERPRINTS = listOf(
        "", "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized", "custom", TLS_UNSAFE_FINGERPRINT,
    )

    @JvmStatic
    fun singBoxFingerprint(fingerprint: String): String {
        val t = fingerprint.trim()
        if (t in SING_BOX_FINGERPRINTS) return ""
        return vtr(R.string.validation_tls_fingerprint_unknown, t)
    }

    /** Colon or comma separated, as the editor and the `cs` link value carry them. */
    @JvmStatic
    fun singBoxCipherSuites(text: String): String =
        Validation.eachItem(text.replace(':', ','), Validation.Split.Comma, { tlsCipherSuiteRule(it) })

    @JvmStatic
    fun tlsVersion(version: String): String = Validation.oneOf(version, listOf("1.0", "1.1", "1.2", "1.3"))

    @JvmStatic
    fun tlsVersionOrder(minVersion: String, maxVersion: String): String {
        val low = minVersion.trim()
        val high = maxVersion.trim()
        if (low.isEmpty() || high.isEmpty() || tlsVersion(low).isNotEmpty() || tlsVersion(high).isNotEmpty()) return ""
        // "1.0" to "1.3" order as text.
        if (low > high) return vtr(R.string.validation_tls_version_order)
        return ""
    }

    @JvmStatic
    fun tlsCurve(curve: String): String =
        Validation.oneOf(curve, listOf("P256", "P384", "P521", "X25519", "X25519MLKEM768"), Empty.Allowed, ignoreCase = true)

    @JvmStatic
    @JvmOverloads
    fun realityPublicKey(key: String, required: Boolean = false): String {
        val t = key.trim()
        if (t.isEmpty()) return if (required) vtr(R.string.validation_tls_reality_key_required) else ""
        val message = Validation.base64Key(t, 32, urlAlphabet = true, padded = false)
        if (message.isEmpty()) return ""
        if (t.endsWith('=') && Validation.base64Key(t.trimEnd('='), 32, urlAlphabet = true, padded = false).isEmpty()) {
            return vtr(R.string.validation_tls_reality_key_padding)
        }
        return message
    }

    @JvmStatic
    fun realityShortId(shortId: String): String = Validation.hex(shortId, 0, 16, true)

    @JvmStatic
    fun pemCertificates(lines: List<String>): String {
        if (!tlsHasText(lines)) return ""
        if (tlsPemBlocks(lines).blocks.any { it.label == "CERTIFICATE" && it.bodyOk }) return ""
        return vtr(R.string.validation_tls_pem_certificate)
    }

    @JvmStatic
    fun pemPrivateKey(lines: List<String>): String {
        if (!tlsHasText(lines)) return ""
        // Go tls.X509KeyPair takes the first "PRIVATE KEY" or "<kind> PRIVATE KEY" block.
        val key = tlsPemBlocks(lines).blocks.firstOrNull { it.label == "PRIVATE KEY" || it.label.endsWith(" PRIVATE KEY") }
        if (key != null && key.bodyOk) return ""
        return vtr(R.string.validation_tls_pem_private_key)
    }

    @JvmStatic
    fun certificatePins(pins: List<String>): String {
        val rule: (String) -> String = { pin ->
            if (certificateSha256FromPcs(pin).size == 1) vtr(R.string.validation_tls_pin_base64) else Validation.base64Key(pin, 32)
        }
        return Validation.eachItem(pins.joinToString("\n"), Validation.Split.Newline, rule)
    }

    @JvmStatic
    fun echConfig(lines: List<String>): String {
        if (!tlsHasText(lines)) return ""
        val scan = tlsPemBlocks(lines)
        // sing-box common/tls/ech.go: exactly one ECH CONFIGS block and nothing after it.
        val block = scan.blocks.singleOrNull()
        if (block != null && block.label == "ECH CONFIGS" && block.bodyOk && !scan.trailing) return ""
        return vtr(R.string.validation_tls_ech_config)
    }

    @JvmStatic
    fun spoofMethod(method: String): String = Validation.oneOf(method, TLS_SPOOF_METHODS)

    /** Only the profile's own spoof SNI: the global preset is not the profile's to check. */
    @JvmStatic
    fun ownSpoofOn(spoof: String, spoofState: Int): Boolean = spoof.isNotBlank() && spoofState != TRI_STATE_OFF

    @JvmStatic
    fun realityConflict(ech: Boolean, disableSni: Boolean, insecure: Boolean, spoof: Boolean): String = when {
        ech -> vtr(R.string.validation_tls_reality_ech)
        disableSni && !insecure -> vtr(R.string.validation_tls_reality_disable_sni)
        spoof -> vtr(R.string.validation_tls_reality_spoof)
        else -> ""
    }

    @JvmStatic
    fun pinsWithCertificate(pins: Boolean, certificate: Boolean): String =
        if (!pins || !certificate) "" else vtr(R.string.validation_tls_pins_with_certificate)

    @JvmStatic
    fun clientCertificatePair(certificate: Boolean, key: Boolean): String =
        if (certificate == key) "" else vtr(R.string.validation_tls_client_pair)

    /** [serverName] is the effective SNI: the TLS server name, else the server address. */
    @JvmStatic
    fun spoofServerName(spoof: String, serverName: String, disableSni: Boolean): String {
        val fake = spoof.trim()
        if (fake.isEmpty()) return ""
        if (disableSni) return vtr(R.string.validation_tls_spoof_needs_sni)
        var name = serverName.trim()
        if (name.startsWith('[') && name.endsWith(']')) name = name.substring(1, name.length - 1)
        // sing-box common/tls/client.go: no SNI for an empty or IP server name.
        if (name.isEmpty() || Validation.ip(name).isEmpty()) return vtr(R.string.validation_tls_spoof_needs_domain)
        if (fake.equals(name, ignoreCase = true)) return vtr(R.string.validation_tls_spoof_same_sni)
        return ""
    }

    /** A uTLS client applies TLS fragment / record fragment and returns before it applies the spoof. */
    @JvmStatic
    fun spoofIgnored(spoof: Boolean, utls: Boolean, fragment: Boolean, recordFragment: Boolean): String {
        if (!spoof || !utls || (!fragment && !recordFragment)) return ""
        return vtr(R.string.validation_tls_spoof_ignored)
    }
}

/** uTLS (TLS.h, TLS.cpp). */
class UTls {
    @JvmField var supported: Boolean = true
    @JvmField var enabled: Boolean = false
    @JvmField var fingerPrint: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** uTLS::ParseFromLink (TLS.cpp). */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("fp")) {
            val value = q.value("fp")
            fingerPrint = if (supported) importTlsFingerprint(value, "fp") else value
        }
        if (fingerPrint.isNotEmpty()) enabled = true
        return true
    }

    /** uTLS::ParseFromJson (TLS.cpp). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("fingerprint")) {
            val value = obj.string("fingerprint")
            fingerPrint = if (supported && ParseDiag.active()) importTlsFingerprint(value, "tls.utls.fingerprint") else value
        }
        return true
    }

    /** uTLS::ExportToLink (TLS.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        if (fingerPrint.isNotEmpty()) q.add("fp" to fingerPrint)
        return q
    }

    /** uTLS::ExportToJson (TLS.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (fingerPrint.isNotEmpty()) obj["fingerprint"] = fingerPrint
        return obj
    }

    /** uTLS::ParseFromClash (TLS.cpp): mihomo's "none" turns uTLS off, which Throne's unsafe does; empty would take the global default fingerprint. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val value = proxy.string("client-fingerprint")
        if (value.isEmpty()) return false
        fingerPrint = when {
            isClashNoFingerprint(value) -> if (supported) TLS_UNSAFE_FINGERPRINT else ""
            supported -> importTlsFingerprint(value, "client-fingerprint")
            else -> value
        }
        enabled = fingerPrint.isNotEmpty()
        return true
    }

    /** uTLS::ExportIdentity (TLS.cpp). */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (enabled && fingerPrint.isNotEmpty()) obj["fingerprint"] = fingerPrint
        return obj
    }

    /** uTLS::Build (TLS.cpp): the global fingerprint fills in whenever the profile has no usable one; "unsafe" turns uTLS off. */
    fun build(ctx: BuildContext): JsonObject {
        if (!supported) return JsonObject()
        val obj = exportToJson()
        if ((obj.isEmpty() || !obj.bool("enabled") || fingerPrint.isEmpty()) && ctx.utlsFingerprint.isNotEmpty()) {
            obj["enabled"] = true
            obj["fingerprint"] = ctx.utlsFingerprint
        }
        if (obj.string("fingerprint") == TLS_UNSAFE_FINGERPRINT) return JsonObject()
        return obj
    }
}

/** ECH (TLS.h, TLS.cpp). [resolver] is Throne-only: it is stored and shared but never reaches the core. */
class Ech {
    @JvmField var enabled: Boolean = false
    @JvmField var config: MutableList<String> = ArrayList()
    @JvmField var config_path: String = ""
    @JvmField var serverName: String = ""
    @JvmField var resolver: String = ""

    companion object {
        /** ECH::NormalizeConfig (TLS.cpp): a bare base64 list becomes one PEM block; a list with a BEGIN line is kept. */
        @JvmStatic
        fun normalizeConfig(items: List<String>): MutableList<String> {
            if (items.any { it.contains("-----BEGIN") }) return ArrayList(items)
            val joined = items.joinToString("").trim()
            if (joined.isEmpty()) return ArrayList()
            return arrayListOf("-----BEGIN ECH CONFIGS-----", joined, "-----END ECH CONFIGS-----")
        }
    }

    /** ECH::SetQueryTarget (TLS.cpp): `<domain>+<scheme://resolver>`, a bare resolver URL, or a bare domain. */
    fun setQueryTarget(target: String) {
        val trimmed = target.trim()
        val schemeIdx = trimmed.indexOf("://")
        if (schemeIdx != -1) {
            val plusIdx = trimmed.indexOf('+')
            if (plusIdx > 0 && plusIdx < schemeIdx) {
                serverName = trimmed.substring(0, plusIdx).trim()
                resolver = trimmed.substring(plusIdx + 1).trim()
            } else {
                serverName = ""
                resolver = trimmed
            }
        } else {
            serverName = trimmed
            resolver = ""
        }
    }

    /** ECH::QueryTarget (TLS.cpp). */
    fun queryTarget(): String {
        if (resolver.isEmpty()) return serverName
        return if (serverName.isEmpty()) resolver else "$serverName+$resolver"
    }

    /** ECH::ConfigBase64 (TLS.cpp): the config lines without the PEM armour. */
    fun configBase64(): String {
        val sb = StringBuilder()
        for (line in config) if (!line.contains("-----")) sb.append(line.trim())
        return sb.toString()
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** ECH::ParseFromLink (TLS.cpp): `ech` is off (0/false/empty), on (1/true), a query target (has "://" or '.') or a config. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("ech")) {
            val echVal = q.valueFully("ech").trim()
            enabled = echVal.isNotEmpty() && echVal != "0" && echVal != "false"
            if (enabled && echVal != "1" && echVal != "true") {
                if (echVal.contains("://") || echVal.contains('.')) setQueryTarget(echVal)
                else config = normalizeConfig(listOf(echVal))
            }
        }
        if (q.has("ech_enabled")) enabled = q.value("ech_enabled") == "true"
        if (q.has("ech_config")) config = normalizeConfig(QtStrings.splitSkipEmpty(q.value("ech_config"), ","))
        if (q.has("ech_config_path")) config_path = q.value("ech_config_path")
        if (q.has("ech_server_name")) serverName = q.value("ech_server_name")
        return true
    }

    /** ECH::ParseFromJson (TLS.cpp). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("config")) config = normalizeConfig(tlsPemLines(obj["config"], "tls.ech.config"))
        if (obj.contains("config_path")) config_path = obj.string("config_path")
        if (obj.contains("query_server_name")) serverName = obj.string("query_server_name")
        if (obj.contains("resolver")) resolver = obj.string("resolver")
        return true
    }

    /** ECH::ExportToLink (TLS.cpp): `ech_config` carries the bare base64; `ech` repeats the target or config in its short form. */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val b64 = configBase64()
        val q = ArrayList<Pair<String, String>>()
        q.add("ech_enabled" to "true")
        if (b64.isNotEmpty()) q.add("ech_config" to b64)
        if (config_path.isNotEmpty()) q.add("ech_config_path" to config_path)
        if (serverName.isNotEmpty()) q.add("ech_server_name" to serverName)
        if (serverName.isNotEmpty() && resolver.isNotEmpty()) q.add("ech" to "$serverName+$resolver")
        else if (serverName.isEmpty() && b64.isNotEmpty()) q.add("ech" to b64)
        else if (serverName.isEmpty() && resolver.isNotEmpty()) q.add("ech" to resolver)
        return q
    }

    /** ECH::ExportToJson (TLS.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (config.isNotEmpty()) obj["config"] = JsonValues.stringArray(config)
        if (config_path.isNotEmpty()) obj["config_path"] = config_path
        if (serverName.isNotEmpty()) obj["query_server_name"] = Hosts.toAceHost(serverName)
        if (resolver.isNotEmpty()) obj["resolver"] = resolver
        return obj
    }

    /** ECH::ExportIdentity (TLS.cpp). */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = true
        if (serverName.isNotEmpty()) obj["query_server_name"] = Hosts.toAceHost(serverName)
        if (resolver.isNotEmpty()) obj["resolver"] = resolver
        return obj
    }

    /** ECH::Build (TLS.cpp): the core rejects the Throne-only `resolver`. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj.remove("resolver")
        return obj
    }
}

/** Reality (TLS.h, TLS.cpp). */
class Reality {
    @JvmField var enabled: Boolean = false
    @JvmField var public_key: String = ""
    @JvmField var short_id: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** Reality::ParseFromLink (TLS.cpp): sing-box has no ML-DSA-65 check, so a `pqv` refuses the import. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("pbk")) {
            enabled = true
            public_key = importRealityPublicKey(q.value("pbk"), "pbk")
            short_id = q.value("sid").trim()
            if (q.value("pqv").isNotBlank()) ParseDiag.error("pqv", vtr(R.string.validation_tls_reality_pqv))
        }
        return true
    }

    /** Reality::ParseFromJson (TLS.cpp). */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("public_key")) {
            val key = obj.string("public_key")
            public_key = if (ParseDiag.active()) importRealityPublicKey(key, "tls.reality.public_key") else key.trim()
        }
        if (obj.contains("short_id")) short_id = obj.string("short_id").trim()
        return true
    }

    /** Reality::ExportToLink (TLS.cpp): both keys are written whenever enabled. */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        return listOf("pbk" to public_key, "sid" to short_id)
    }

    /** Reality::ParseFromClash (TLS.cpp). */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = proxy.obj("reality-opts")
        val publicKey = opts.string("public-key")
        if (publicKey.isEmpty()) return false
        enabled = true
        public_key = importRealityPublicKey(publicKey, "reality-opts.public-key")
        short_id = opts.string("short-id").trim()
        return true
    }

    /** Reality::ExportToJson (TLS.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (short_id.isNotEmpty()) obj["short_id"] = short_id
        return obj
    }

    /** Reality::ExportIdentity (TLS.cpp). */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (enabled) obj["enabled"] = true
        return obj
    }

    /** Reality::Build's condition: a key alone turns it on. */
    private fun builds(): Boolean = public_key.isNotEmpty() || enabled

    /** Reality::Build (TLS.cpp): public_key is always written, even empty; a short ID the core would refuse is left out, see [buildError]. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = JsonObject()
        if (builds()) {
            obj["enabled"] = true
            obj["public_key"] = public_key
            if (short_id.isNotEmpty() && REALITY_SHORT_ID.matches(short_id)) obj["short_id"] = short_id
        }
        return obj
    }

    /** Reality::Build's error (TLS.cpp): empty unless it builds and short_id is not even-length hex of at most 16 characters. */
    fun buildError(): String {
        if (!builds() || REALITY_SHORT_ID.matches(short_id)) return ""
        return vtr(R.string.validation_tls_reality_short_id_build)
    }
}

/** TLS (TLS.h, TLS.cpp). Tri-states: `*_unspecified` true means "Keep Default" (key absent). */
class Tls {
    @JvmField var enabled: Boolean = false
    @JvmField var disable_sni: Boolean = false
    @JvmField var server_name: String = ""
    @JvmField var insecure: Boolean = false
    @JvmField var alpn: MutableList<String> = ArrayList()
    @JvmField var min_version: String = ""
    @JvmField var max_version: String = ""
    @JvmField var cipher_suites: MutableList<String> = ArrayList()
    @JvmField var curve_preferences: MutableList<String> = ArrayList()
    @JvmField var certificate: MutableList<String> = ArrayList()
    @JvmField var certificate_path: String = ""
    @JvmField var certificate_sha256: MutableList<String> = ArrayList()
    @JvmField var certificate_public_key_sha256: MutableList<String> = ArrayList()
    @JvmField var client_certificate: MutableList<String> = ArrayList()
    @JvmField var client_certificate_path: String = ""
    @JvmField var client_key: MutableList<String> = ArrayList()
    @JvmField var client_key_path: String = ""
    @JvmField var fragment: Boolean = false
    @JvmField var fragment_unspecified: Boolean = true
    @JvmField var fragment_fallback_delay: String = ""
    @JvmField var record_fragment: Boolean = false
    @JvmField var spoof: String = ""
    @JvmField var spoof_method: String = ""
    @JvmField var spoof_enabled: Boolean = false
    @JvmField var spoof_unspecified: Boolean = true
    @JvmField var tls_tricks: Boolean = false
    @JvmField var tls_tricks_unspecified: Boolean = true
    @JvmField var ech: Ech = Ech()
    @JvmField var utls: UTls = UTls()
    @JvmField var reality: Reality = Reality()

    /** TLS.h saveFragmentState (0 = Keep Default, 1 = On, 2 = Off). */
    fun saveFragmentState(state: Int) {
        fragment = state == 1
        fragment_unspecified = state == 0
    }

    /** TLS::FragmentEffectivelyOn (TLS.cpp). */
    fun fragmentEffectivelyOn(ctx: BuildContext): Boolean {
        if (fragment) return true
        if (fragment_unspecified) return ctx.fragmentDefaultOn
        return false
    }

    /** TLS::TlsTricksEffectivelyOn (TLS.cpp). */
    fun tlsTricksEffectivelyOn(ctx: BuildContext): Boolean {
        if (tls_tricks) return true
        if (tls_tricks_unspecified) return ctx.tlsTricksDefaultOn
        return false
    }

    /**
     * TLS::Validate (TLS.cpp): the editor's TLS rules over the stored fields; [limited] mirrors outbound::LimitedTLS().
     * The spoof checks are left out (D8), so [serverAddress], which only feeds the spoof SNI check, goes unused.
     */
    @Suppress("UNUSED_PARAMETER")
    fun validate(issues: MutableList<Issue>, serverAddress: String, limited: Boolean, ctx: BuildContext) {
        issues.addIssue(Severity.Warning, "tls.server_name", CommonChecks.serverName(server_name), server_name)
        issues.addIssue(Severity.Error, "tls.certificate", TlsChecks.pemCertificates(certificate))
        if (ech.enabled) {
            issues.addIssue(Severity.Error, "tls.ech.config", TlsChecks.echConfig(ech.config))
            issues.addIssue(Severity.Warning, "tls.ech.query_server_name", Validation.host(ech.serverName, Empty.Allowed), ech.serverName)
        }
        if (limited) return

        issues.addIssue(Severity.Error, "tls.min_version", TlsChecks.tlsVersion(min_version), min_version)
        issues.addIssue(Severity.Error, "tls.max_version", TlsChecks.tlsVersion(max_version), max_version)
        issues.addIssue(Severity.Error, "tls.max_version", TlsChecks.tlsVersionOrder(min_version, max_version))
        issues.addIssue(Severity.Error, "tls.cipher_suites", TlsChecks.singBoxCipherSuites(cipher_suites.joinToString(",")))
        issues.addIssue(
            Severity.Error, "tls.curve_preferences",
            Validation.eachItem(curve_preferences.joinToString(","), Validation.Split.Comma, { TlsChecks.tlsCurve(it) }),
        )
        if (utls.supported && utls.enabled) {
            issues.addIssue(Severity.Error, "tls.utls.fingerprint", TlsChecks.singBoxFingerprint(utls.fingerPrint), utls.fingerPrint)
        }

        issues.addIssue(Severity.Error, "tls.certificate_sha256", TlsChecks.certificatePins(certificate_sha256))
        issues.addIssue(Severity.Error, "tls.certificate_public_key_sha256", TlsChecks.certificatePins(certificate_public_key_sha256))
        issues.addIssue(
            Severity.Error, "tls.certificate_sha256",
            TlsChecks.pinsWithCertificate(
                tlsHasText(certificate_sha256) || tlsHasText(certificate_public_key_sha256),
                tlsHasText(certificate) || certificate_path.isNotEmpty(),
            ),
        )
        issues.addIssue(Severity.Error, "tls.client_certificate", TlsChecks.pemCertificates(client_certificate))
        issues.addIssue(Severity.Error, "tls.client_key", TlsChecks.pemPrivateKey(client_key))
        issues.addIssue(
            Severity.Error, "tls.client_key",
            TlsChecks.clientCertificatePair(
                tlsHasText(client_certificate) || client_certificate_path.isNotEmpty(),
                tlsHasText(client_key) || client_key_path.isNotEmpty(),
            ),
        )

        if (fragmentEffectivelyOn(ctx) && ctx.fragmentImplementation != "custom") {
            issues.addIssue(Severity.Error, "tls.fragment_fallback_delay", Validation.duration(fragment_fallback_delay), fragment_fallback_delay)
        }

        if (reality.enabled || reality.public_key.isNotEmpty()) {
            issues.addIssue(Severity.Error, "tls.reality.public_key", TlsChecks.realityPublicKey(reality.public_key, true))
            issues.addIssue(Severity.Error, "tls.reality.short_id", TlsChecks.realityShortId(reality.short_id))
            issues.addIssue(Severity.Error, "tls.reality", TlsChecks.realityConflict(ech.enabled, disable_sni, insecure, false))
        }
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /**
     * TLS::ParseFromLink (TLS.cpp). Values sing-box would refuse are dropped with a Warning; the spoof method is kept
     * as given, since Android never emits spoof (D8).
     */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        fun checked(key: String, current: String, rule: (String) -> String): String {
            if (!q.has(key)) return current
            val value = q.value(key).trim()
            return if (acceptOptional(key, rule(value), value)) value else current
        }
        // An empty security= counts as absent, so an sni still turns TLS on.
        val security = q.value("security").trim().lowercase()
        val securityOff = security == "none" || security == "false" || security == "0"
        if (security.isNotEmpty()) enabled = security == "tls" || security == "true" || security == "1" || security == "reality"
        if (q.has("disable_sni")) disable_sni = q.value("disable_sni").replace("1", "true") == "true"
        if (q.has("sni")) server_name = q.value("sni")
        if (q.has("peer")) server_name = q.value("peer")
        if (q.has("allowInsecure")) insecure = q.value("allowInsecure").replace("1", "true") == "true"
        if (q.has("allow_insecure")) insecure = q.value("allow_insecure").replace("1", "true") == "true"
        if (q.has("insecure")) insecure = q.value("insecure").replace("1", "true") == "true"
        if (q.has("alpn")) alpn = tlsLinkList(q.valueFully("alpn"))
        min_version = checked("tls_min_version", min_version) { TlsChecks.tlsVersion(it) }
        max_version = checked("tls_max_version", max_version) { TlsChecks.tlsVersion(it) }
        if (q.has("tls_cipher_suites")) {
            cipher_suites = importTlsCipherSuites(splitCipherSuites(q.valueFully("tls_cipher_suites")), "tls_cipher_suites")
        }
        if (q.has("cs")) cipher_suites = importTlsCipherSuites(splitCipherSuites(q.valueFully("cs")), "cs")
        if (q.has("tls_curve_preferences")) {
            curve_preferences = tlsKnownCurves(QtStrings.split(q.value("tls_curve_preferences"), ","), "tls_curve_preferences")
        }
        if (q.has("tls_certificate")) certificate = QtStrings.split(q.value("tls_certificate"), ",")
        if (q.has("tls_certificate_path")) certificate_path = q.value("tls_certificate_path")
        if (q.has("pcs")) certificate_sha256 = certificateSha256FromPcs(q.valueFully("pcs"), "pcs")
        if (q.has("tls_certificate_public_key_sha256")) certificate_public_key_sha256 = tlsLinkList(q.value("tls_certificate_public_key_sha256"))
        if (q.has("tls_client_certificate")) client_certificate = QtStrings.split(q.value("tls_client_certificate"), ",")
        if (q.has("tls_client_certificate_path")) client_certificate_path = q.value("tls_client_certificate_path")
        if (q.has("tls_client_key")) client_key = QtStrings.split(q.value("tls_client_key"), ",")
        if (q.has("tls_client_key_path")) client_key_path = q.value("tls_client_key_path")
        if (q.has("tls_fragment")) {
            fragment = q.value("tls_fragment") == "true"
            fragment_unspecified = false
        } else {
            fragment_unspecified = true
        }
        fragment_fallback_delay = checked("tls_fragment_fallback_delay", fragment_fallback_delay) { Validation.duration(it) }
        if (q.has("tls_record_fragment")) record_fragment = q.value("tls_record_fragment") == "true"
        if (q.has("tls_spoof")) spoof = q.value("tls_spoof").trim()
        if (q.has("tls_spoof_method")) spoof_method = q.value("tls_spoof_method").trim()
        if (q.has("tls_spoof_enabled")) {
            spoof_enabled = q.value("tls_spoof_enabled") == "true"
            spoof_unspecified = false
        } else {
            spoof_unspecified = true
        }
        if (q.has("tls_tricks")) {
            tls_tricks = q.value("tls_tricks") == "true"
            tls_tricks_unspecified = false
        } else {
            tls_tricks_unspecified = true
        }
        if (!securityOff && server_name.isNotEmpty()) enabled = true
        ech.parseFromLink(url)
        utls.parseFromLink(url)
        reality.parseFromLink(url)
        // Without pbk Reality stays off and the profile would silently become plain TLS.
        if (security == "reality" && !q.has("pbk")) ParseDiag.error("pbk", vtr(R.string.validation_tls_reality_key_required))
        return true
    }

    /** TLS::ParseFromJson (TLS.cpp); also the DB loader, so only an import ([ParseDiag.active]) drops values. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("disable_sni")) disable_sni = obj.bool("disable_sni")
        if (obj.contains("server_name")) server_name = obj.string("server_name")
        if (obj.contains("insecure")) insecure = obj.bool("insecure")
        val importing = ParseDiag.active()
        fun checked(key: String, current: String, rule: (String) -> String): String {
            if (!obj.contains(key)) return current
            val value = obj.string(key).trim()
            return if (!importing || acceptOptional("tls.$key", rule(value), value)) value else current
        }
        if (obj.contains("alpn")) {
            alpn = jsonStringList(obj["alpn"], "tls.alpn")
            if (importing) alpn = tlsLinkList(alpn.joinToString(","))
        }
        min_version = checked("min_version", min_version) { TlsChecks.tlsVersion(it) }
        max_version = checked("max_version", max_version) { TlsChecks.tlsVersion(it) }
        if (obj.contains("cipher_suites")) {
            cipher_suites = jsonStringList(obj["cipher_suites"], "tls.cipher_suites")
            if (importing) cipher_suites = importTlsCipherSuites(cipher_suites, "tls.cipher_suites")
        }
        if (obj.contains("curve_preferences")) {
            curve_preferences = jsonStringList(obj["curve_preferences"], "tls.curve_preferences")
            if (importing) curve_preferences = tlsKnownCurves(curve_preferences, "tls.curve_preferences")
        }
        if (obj.contains("certificate")) certificate = tlsPemLines(obj["certificate"], "tls.certificate")
        if (obj.contains("certificate_path")) certificate_path = obj.string("certificate_path")
        if (obj.contains("certificate_sha256")) {
            certificate_sha256 = jsonStringList(obj["certificate_sha256"], "tls.certificate_sha256")
            if (importing) certificate_sha256 = certificatePinsFromHex(certificate_sha256)
        }
        if (obj.contains("certificate_public_key_sha256")) {
            certificate_public_key_sha256 = jsonStringList(obj["certificate_public_key_sha256"], "tls.certificate_public_key_sha256")
            if (importing) certificate_public_key_sha256 = certificatePinsFromHex(certificate_public_key_sha256)
        }
        if (obj.contains("client_certificate")) client_certificate = tlsPemLines(obj["client_certificate"], "tls.client_certificate")
        if (obj.contains("client_certificate_path")) client_certificate_path = obj.string("client_certificate_path")
        if (obj.contains("client_key")) client_key = tlsPemLines(obj["client_key"], "tls.client_key")
        if (obj.contains("client_key_path")) client_key_path = obj.string("client_key_path")
        if (obj.contains("fragment")) {
            fragment = obj.bool("fragment")
            fragment_unspecified = false
        } else {
            fragment_unspecified = true
        }
        fragment_fallback_delay = checked("fragment_fallback_delay", fragment_fallback_delay) { Validation.duration(it) }
        if (obj.contains("record_fragment")) record_fragment = obj.bool("record_fragment")
        if (obj.contains("spoof")) spoof = obj.string("spoof")
        if (obj.contains("spoof_method")) spoof_method = obj.string("spoof_method").trim()
        if (obj.contains("spoof_enabled")) {
            spoof_enabled = obj.bool("spoof_enabled")
            spoof_unspecified = false
        } else {
            spoof_unspecified = true
        }
        if (obj.contains("tls_tricks")) {
            tls_tricks = obj.obj("tls_tricks").bool("mixedcase_sni")
            tls_tricks_unspecified = false
        } else {
            tls_tricks_unspecified = true
        }
        if (obj.contains("ech")) ech.parseFromJson(obj.obj("ech"))
        if (obj.contains("utls")) utls.parseFromJson(obj.obj("utls"))
        if (obj.contains("reality")) reality.parseFromJson(obj.obj("reality"))
        return true
    }

    /**
     * TLS::ParseFromClash (TLS.cpp): `servername`, then `sni`, then the server itself name the certificate. mihomo's
     * `fingerprint` is the certificate pin, hex like Xray's pcs; [readPin] false skips it, which mihomo ignores where
     * the protocol's tls switch is off.
     */
    @JvmOverloads
    fun parseFromClash(proxy: ClashProxy, readPin: Boolean = true): Boolean {
        enabled = proxy.bool("tls")
        server_name = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        insecure = proxy.bool("skip-cert-verify")
        for (s in proxy.strings("alpn")) {
            val protocol = s.trim()
            if (protocol.isNotEmpty()) alpn.add(protocol)
        }
        if (readPin) {
            val pin = proxy.string("fingerprint")
            if (pin.isNotEmpty()) certificate_sha256 = certificateSha256FromPcs(pin, "fingerprint")
        }
        utls.parseFromClash(proxy)
        reality.parseFromClash(proxy)
        return true
    }

    /** TLS::ParseFromClash(object, readPin) on the raw Clash node. */
    fun parseFromClash(node: JsonObject, readPin: Boolean): Boolean = parseFromClash(ClashProxy(node), readPin)

    /** TLS::ExportToLink (TLS.cpp). */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        q.add("security" to if (reality.enabled) "reality" else "tls")
        if (disable_sni) q.add("disable_sni" to "true")
        if (server_name.isNotEmpty()) q.add("sni" to server_name)
        if (insecure) q.add("allowInsecure" to "true")
        if (alpn.isNotEmpty()) q.add("alpn" to alpn.joinToString(","))
        if (min_version.isNotEmpty()) q.add("tls_min_version" to min_version)
        if (max_version.isNotEmpty()) q.add("tls_max_version" to max_version)
        if (cipher_suites.isNotEmpty()) q.add("cs" to cipher_suites.joinToString(":"))
        if (curve_preferences.isNotEmpty()) q.add("tls_curve_preferences" to curve_preferences.joinToString(","))
        if (certificate.isNotEmpty()) q.add("tls_certificate" to certificate.joinToString(","))
        if (certificate_path.isNotEmpty()) q.add("tls_certificate_path" to certificate_path)
        pcsFromCertificateSha256(certificate_sha256).let { if (it.isNotEmpty()) q.add("pcs" to it) }
        if (certificate_public_key_sha256.isNotEmpty()) q.add("tls_certificate_public_key_sha256" to certificate_public_key_sha256.joinToString(","))
        if (client_certificate.isNotEmpty()) q.add("tls_client_certificate" to client_certificate.joinToString(","))
        if (client_certificate_path.isNotEmpty()) q.add("tls_client_certificate_path" to client_certificate_path)
        if (client_key.isNotEmpty()) q.add("tls_client_key" to client_key.joinToString(","))
        if (client_key_path.isNotEmpty()) q.add("tls_client_key_path" to client_key_path)
        if (!fragment_unspecified) q.add("tls_fragment" to if (fragment) "true" else "false")
        if (fragment_fallback_delay.isNotEmpty()) q.add("tls_fragment_fallback_delay" to fragment_fallback_delay)
        if (record_fragment) q.add("tls_record_fragment" to "true")
        if (!spoof_unspecified) q.add("tls_spoof_enabled" to if (spoof_enabled) "true" else "false")
        if (spoof.isNotEmpty()) {
            q.add("tls_spoof" to spoof)
            if (spoof_method.isNotEmpty()) q.add("tls_spoof_method" to spoof_method)
        }
        if (!tls_tricks_unspecified) q.add("tls_tricks" to if (tls_tricks) "true" else "false")
        q.addAll(ech.exportToLink())
        q.addAll(utls.exportToLink())
        q.addAll(reality.exportToLink())
        return q
    }

    /** TLS::ExportToJson (TLS.cpp). */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (disable_sni) obj["disable_sni"] = disable_sni
        if (server_name.isNotEmpty()) obj["server_name"] = Hosts.toAceHost(server_name)
        if (insecure) obj["insecure"] = insecure
        if (alpn.isNotEmpty()) obj["alpn"] = JsonValues.stringArray(alpn)
        if (min_version.isNotEmpty()) obj["min_version"] = min_version
        if (max_version.isNotEmpty()) obj["max_version"] = max_version
        if (cipher_suites.isNotEmpty()) obj["cipher_suites"] = JsonValues.stringArray(cipher_suites)
        if (curve_preferences.isNotEmpty()) obj["curve_preferences"] = JsonValues.stringArray(curve_preferences)
        if (certificate.isNotEmpty()) obj["certificate"] = JsonValues.stringArray(certificate)
        if (certificate_path.isNotEmpty()) obj["certificate_path"] = certificate_path
        if (certificate_sha256.isNotEmpty()) obj["certificate_sha256"] = JsonValues.stringArray(certificate_sha256)
        if (certificate_public_key_sha256.isNotEmpty()) obj["certificate_public_key_sha256"] = JsonValues.stringArray(certificate_public_key_sha256)
        if (client_certificate.isNotEmpty()) obj["client_certificate"] = JsonValues.stringArray(client_certificate)
        if (client_certificate_path.isNotEmpty()) obj["client_certificate_path"] = client_certificate_path
        if (client_key.isNotEmpty()) obj["client_key"] = JsonValues.stringArray(client_key)
        if (client_key_path.isNotEmpty()) obj["client_key_path"] = client_key_path
        if (!fragment_unspecified) obj["fragment"] = fragment
        if (fragment_fallback_delay.isNotEmpty()) obj["fragment_fallback_delay"] = fragment_fallback_delay
        if (record_fragment) obj["record_fragment"] = record_fragment
        if (!spoof_unspecified) obj["spoof_enabled"] = spoof_enabled
        if (spoof.isNotEmpty()) {
            obj["spoof"] = spoof
            if (spoof_method.isNotEmpty()) obj["spoof_method"] = spoof_method
        }
        if (!tls_tricks_unspecified) obj["tls_tricks"] = jsonObjectOf("mixedcase_sni" to tls_tricks)
        if (ech.enabled) obj["ech"] = ech.exportToJson()
        if (utls.enabled) obj["utls"] = utls.exportToJson()
        if (reality.enabled) obj["reality"] = reality.exportToJson()
        return obj
    }

    /** TLS::ExportIdentity (TLS.cpp). */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = true
        if (disable_sni) obj["disable_sni"] = true
        if (server_name.isNotEmpty()) obj["server_name"] = Hosts.toAceHost(server_name)
        utls.exportIdentity().let { if (it.isNotEmpty()) obj["utls"] = it }
        ech.exportIdentity().let { if (it.isNotEmpty()) obj["ech"] = it }
        reality.exportIdentity().let { if (it.isNotEmpty()) obj["reality"] = it }
        return obj
    }

    /**
     * TLS::Build (TLS.cpp) without `spoof` / `spoof_method`: spoofing needs raw sockets, which an unrooted app does
     * not have (D8). The spoof fields still round-trip through links and JSON. Reality's short-ID error is reported
     * by Outbound.sharedBuildError.
     */
    fun build(ctx: BuildContext): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (disable_sni) obj["disable_sni"] = disable_sni
        if (server_name.isNotEmpty()) obj["server_name"] = Hosts.toAceHost(server_name)
        if (insecure || ctx.skipCert) obj["insecure"] = true
        if (alpn.isNotEmpty()) obj["alpn"] = JsonValues.stringArray(alpn)
        if (min_version.isNotEmpty()) obj["min_version"] = min_version
        if (max_version.isNotEmpty()) obj["max_version"] = max_version
        if (cipher_suites.isNotEmpty()) obj["cipher_suites"] = JsonValues.stringArray(cipher_suites)
        if (curve_preferences.isNotEmpty()) obj["curve_preferences"] = JsonValues.stringArray(curve_preferences)
        if (certificate.isNotEmpty()) obj["certificate"] = JsonValues.stringArray(certificate)
        if (certificate_path.isNotEmpty()) obj["certificate_path"] = certificate_path
        if (certificate_sha256.isNotEmpty()) obj["certificate_sha256"] = JsonValues.stringArray(certificate_sha256)
        if (certificate_public_key_sha256.isNotEmpty()) obj["certificate_public_key_sha256"] = JsonValues.stringArray(certificate_public_key_sha256)
        if (client_certificate.isNotEmpty()) obj["client_certificate"] = JsonValues.stringArray(client_certificate)
        if (client_certificate_path.isNotEmpty()) obj["client_certificate_path"] = client_certificate_path
        if (client_key.isNotEmpty()) obj["client_key"] = JsonValues.stringArray(client_key)
        if (client_key_path.isNotEmpty()) obj["client_key_path"] = client_key_path
        // the "custom" implementation is emitted at the dialer level by Outbound.baseBuild instead
        if (fragmentEffectivelyOn(ctx) && ctx.fragmentImplementation != "custom") {
            obj["fragment"] = true
            if (fragment_fallback_delay.isNotEmpty()) obj["fragment_fallback_delay"] = fragment_fallback_delay
        }
        if (record_fragment) obj["record_fragment"] = record_fragment
        if (tlsTricksEffectivelyOn(ctx)) obj["tls_tricks"] = jsonObjectOf("mixedcase_sni" to true)
        ech.build(ctx).let { if (it.isNotEmpty()) obj["ech"] = it }
        val utlsObj = utls.build(ctx)
        if (utlsObj.isNotEmpty()) {
            obj["utls"] = utlsObj
        } else if (reality.enabled) {
            obj["utls"] = jsonObjectOf("enabled" to true, "fingerprint" to "random")
        }
        reality.build(ctx).let { if (it.isNotEmpty()) obj["reality"] = it }
        return obj
    }
}
