package io.nekohasekai.sagernet.outbound.common

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

private val SHA256_HEX = Regex("[0-9a-fA-F]{64}")
private const val LOWER_HEX = "0123456789abcdef"

/**
 * certificateSha256FromPcs (TLS.cpp:14-23): Xray's pcs and sing-box's certificate_sha256 both hash the whole DER
 * certificate, hex there and base64 here; anything but a 64-digit hex value (':' separators allowed) is dropped.
 */
private fun certificateSha256FromPcs(pcs: String): MutableList<String> {
    val hashes = ArrayList<String>()
    for (item in QtStrings.splitSkipEmpty(pcs, ",")) {
        val value = item.trim().replace(":", "")
        if (!SHA256_HEX.matches(value)) continue
        hashes.add(Base64Strict.encode(ByteArray(32) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }))
    }
    return hashes
}

/** pcsFromCertificateSha256 (TLS.cpp:25-32): only values that decode to 32 bytes are written, as lower-case hex. */
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

/** uTLS (TLS.h:11-25, TLS.cpp:35-89). */
class UTls {
    @JvmField var supported: Boolean = true
    @JvmField var enabled: Boolean = false
    @JvmField var fingerPrint: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** TLS.cpp:35-44. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("fp")) fingerPrint = q.value("fp")
        if (fingerPrint.isNotEmpty()) enabled = true
        return true
    }

    /** TLS.cpp:45-51. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("fingerprint")) fingerPrint = obj.string("fingerprint")
        return true
    }

    /** TLS.cpp:59-65. */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        val q = ArrayList<Pair<String, String>>()
        if (fingerPrint.isNotEmpty()) q.add("fp" to fingerPrint)
        return q
    }

    /** TLS.cpp:66-73. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (fingerPrint.isNotEmpty()) obj["fingerprint"] = fingerPrint
        return obj
    }

    /** TLS.cpp:52-58. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val fingerprint = proxy.string("client-fingerprint")
        if (fingerprint.isEmpty()) return false
        enabled = true
        fingerPrint = fingerprint
        return true
    }

    /** TLS.cpp:74-79. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (enabled && fingerPrint.isNotEmpty()) obj["fingerprint"] = fingerPrint
        return obj
    }

    /** TLS.cpp:80-89: the global fingerprint fills in whenever the profile has no usable one. */
    fun build(ctx: BuildContext): JsonObject {
        if (!supported) return JsonObject()
        val obj = exportToJson()
        if ((obj.isEmpty() || !obj.bool("enabled") || fingerPrint.isEmpty()) && ctx.utlsFingerprint.isNotEmpty()) {
            obj["enabled"] = true
            obj["fingerprint"] = ctx.utlsFingerprint
        }
        return obj
    }
}

/** ECH (TLS.h:27-47, TLS.cpp:91-217). [resolver] is Throne-only: it is stored and shared but never reaches the core. */
class Ech {
    @JvmField var enabled: Boolean = false
    @JvmField var config: MutableList<String> = ArrayList()
    @JvmField var config_path: String = ""
    @JvmField var serverName: String = ""
    @JvmField var resolver: String = ""

    companion object {
        /** NormalizeConfig (TLS.cpp:91-102): a bare base64 list becomes one PEM block; a list with a BEGIN line is kept. */
        @JvmStatic
        fun normalizeConfig(items: List<String>): MutableList<String> {
            if (items.any { it.contains("-----BEGIN") }) return ArrayList(items)
            val joined = items.joinToString("").trim()
            if (joined.isEmpty()) return ArrayList()
            return arrayListOf("-----BEGIN ECH CONFIGS-----", joined, "-----END ECH CONFIGS-----")
        }
    }

    /** SetQueryTarget (TLS.cpp:104-120): `<domain>+<scheme://resolver>`, a bare resolver URL, or a bare domain. */
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

    /** QueryTarget (TLS.cpp:123-126). */
    fun queryTarget(): String {
        if (resolver.isEmpty()) return serverName
        return if (serverName.isEmpty()) resolver else "$serverName+$resolver"
    }

    /** ConfigBase64 (TLS.cpp:128-134): the config lines without the PEM armour. */
    fun configBase64(): String {
        val sb = StringBuilder()
        for (line in config) if (!line.contains("-----")) sb.append(line.trim())
        return sb.toString()
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** TLS.cpp:136-161: `ech` is off (0/false/empty), on (1/true), a query target (has "://" or '.') or a config. */
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

    /** TLS.cpp:162-173. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("config")) config = normalizeConfig(obj.array("config").strings())
        if (obj.contains("config_path")) config_path = obj.string("config_path")
        if (obj.contains("query_server_name")) serverName = obj.string("query_server_name")
        if (obj.contains("resolver")) resolver = obj.string("resolver")
        return true
    }

    /** TLS.cpp:174-189: `ech_config` carries the bare base64; `ech` repeats the target or config in its short form. */
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

    /** TLS.cpp:190-202. */
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

    /** TLS.cpp:203-211. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = true
        if (serverName.isNotEmpty()) obj["query_server_name"] = Hosts.toAceHost(serverName)
        if (resolver.isNotEmpty()) obj["resolver"] = resolver
        return obj
    }

    /** TLS.cpp:212-217: the core rejects the Throne-only `resolver`. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj.remove("resolver")
        return obj
    }
}

/** Reality (TLS.h:49-63, TLS.cpp:219-281). */
class Reality {
    @JvmField var enabled: Boolean = false
    @JvmField var public_key: String = ""
    @JvmField var short_id: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** TLS.cpp:219-232. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        if (q.has("pbk")) {
            enabled = true
            public_key = q.value("pbk")
            short_id = q.value("sid")
        }
        return true
    }

    /** TLS.cpp:233-240. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("public_key")) public_key = obj.string("public_key")
        if (obj.contains("short_id")) short_id = obj.string("short_id")
        return true
    }

    /** TLS.cpp:249-256: both keys are written whenever enabled. */
    fun exportToLink(): List<Pair<String, String>> {
        if (!enabled) return emptyList()
        return listOf("pbk" to public_key, "sid" to short_id)
    }

    /** TLS.cpp:241-248. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val opts = proxy.obj("reality-opts")
        val publicKey = opts.string("public-key")
        if (publicKey.isEmpty()) return false
        enabled = true
        public_key = publicKey
        short_id = opts.string("short-id")
        return true
    }

    /** TLS.cpp:257-265. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (!enabled) return obj
        obj["enabled"] = enabled
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (short_id.isNotEmpty()) obj["short_id"] = short_id
        return obj
    }

    /** TLS.cpp:266-271. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (enabled) obj["enabled"] = true
        return obj
    }

    /** TLS.cpp:272-281: public_key is always written, even empty. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = JsonObject()
        if (public_key.isNotEmpty() || enabled) {
            obj["enabled"] = true
            obj["public_key"] = public_key
            if (short_id.isNotEmpty()) obj["short_id"] = short_id
        }
        return obj
    }
}

/** TLS (TLS.h:65-140, TLS.cpp:283-586). Tri-states: `*_unspecified` true means "Keep Default" (key absent). */
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

    /** TLS.h:107-110 (0 = Keep Default, 1 = On, 2 = Off). */
    fun saveFragmentState(state: Int) {
        fragment = state == 1
        fragment_unspecified = state == 0
    }

    /** TLS.cpp:566-571. */
    fun fragmentEffectivelyOn(ctx: BuildContext): Boolean {
        if (fragment) return true
        if (fragment_unspecified) return ctx.fragmentDefaultOn
        return false
    }

    /** TLS.cpp:581-586. */
    fun tlsTricksEffectivelyOn(ctx: BuildContext): Boolean {
        if (tls_tricks) return true
        if (tls_tricks_unspecified) return ctx.tlsTricksDefaultOn
        return false
    }

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** TLS.cpp:283-330. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid && !url.invalidPortOnly) return false
        val q = url.query
        // TLS.cpp:289-295 is a literal chain of substring replacements on the value, quirks included ("false" -> "tls")
        if (q.has("security")) enabled = q.value("security")
            .replace("reality", "tls")
            .replace("none", "")
            .replace("0", "")
            .replace("false", "tls")
            .replace("1", "tls")
            .replace("true", "tls") == "tls"
        if (q.has("disable_sni")) disable_sni = q.value("disable_sni").replace("1", "true") == "true"
        if (q.has("sni")) server_name = q.value("sni")
        if (q.has("peer")) server_name = q.value("peer")
        if (q.has("allowInsecure")) insecure = q.value("allowInsecure").replace("1", "true") == "true"
        if (q.has("allow_insecure")) insecure = q.value("allow_insecure").replace("1", "true") == "true"
        if (q.has("insecure")) insecure = q.value("insecure").replace("1", "true") == "true"
        if (q.has("alpn")) alpn = QtStrings.split(q.valueFully("alpn"), ",")
        if (q.has("tls_min_version")) min_version = q.value("tls_min_version")
        if (q.has("tls_max_version")) max_version = q.value("tls_max_version")
        if (q.has("tls_cipher_suites")) cipher_suites = QtStrings.split(q.value("tls_cipher_suites"), ",")
        if (q.has("tls_curve_preferences")) curve_preferences = QtStrings.split(q.value("tls_curve_preferences"), ",")
        if (q.has("tls_certificate")) certificate = QtStrings.split(q.value("tls_certificate"), ",")
        if (q.has("tls_certificate_path")) certificate_path = q.value("tls_certificate_path")
        if (q.has("pcs")) certificate_sha256 = certificateSha256FromPcs(q.valueFully("pcs"))
        if (q.has("tls_certificate_public_key_sha256")) certificate_public_key_sha256 = QtStrings.split(q.value("tls_certificate_public_key_sha256"), ",")
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
        if (q.has("tls_fragment_fallback_delay")) fragment_fallback_delay = q.value("tls_fragment_fallback_delay")
        if (q.has("tls_record_fragment")) record_fragment = q.value("tls_record_fragment") == "true"
        if (q.has("tls_spoof")) spoof = q.value("tls_spoof")
        if (q.has("tls_spoof_method")) spoof_method = q.value("tls_spoof_method")
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
        if (server_name.isNotEmpty()) enabled = true
        ech.parseFromLink(url)
        utls.parseFromLink(url)
        reality.parseFromLink(url)
        return true
    }

    /** TLS.cpp:331-385. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("enabled")) enabled = obj.bool("enabled")
        if (obj.contains("disable_sni")) disable_sni = obj.bool("disable_sni")
        if (obj.contains("server_name")) server_name = obj.string("server_name")
        if (obj.contains("insecure")) insecure = obj.bool("insecure")
        if (obj.contains("alpn")) alpn = obj.array("alpn").strings()
        if (obj.contains("min_version")) min_version = obj.string("min_version")
        if (obj.contains("max_version")) max_version = obj.string("max_version")
        if (obj.contains("cipher_suites")) cipher_suites = obj.array("cipher_suites").strings()
        if (obj.contains("curve_preferences")) curve_preferences = obj.array("curve_preferences").strings()
        if (obj.contains("certificate")) {
            certificate = if (obj.isString("certificate")) QtStrings.splitSkipEmpty(obj.string("certificate"), "\n")
            else obj.array("certificate").strings()
        }
        if (obj.contains("certificate_path")) certificate_path = obj.string("certificate_path")
        if (obj.contains("certificate_sha256")) certificate_sha256 = obj.array("certificate_sha256").strings()
        if (obj.contains("certificate_public_key_sha256")) certificate_public_key_sha256 = obj.array("certificate_public_key_sha256").strings()
        if (obj.contains("client_certificate")) client_certificate = obj.array("client_certificate").strings()
        if (obj.contains("client_certificate_path")) client_certificate_path = obj.string("client_certificate_path")
        if (obj.contains("client_key")) client_key = obj.array("client_key").strings()
        if (obj.contains("client_key_path")) client_key_path = obj.string("client_key_path")
        if (obj.contains("fragment")) {
            fragment = obj.bool("fragment")
            fragment_unspecified = false
        } else {
            fragment_unspecified = true
        }
        if (obj.contains("fragment_fallback_delay")) fragment_fallback_delay = obj.string("fragment_fallback_delay")
        if (obj.contains("record_fragment")) record_fragment = obj.bool("record_fragment")
        if (obj.contains("spoof")) spoof = obj.string("spoof")
        if (obj.contains("spoof_method")) spoof_method = obj.string("spoof_method")
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

    /** TLS.cpp:386-403: `servername`, then `sni`, then the server itself name the certificate. */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        enabled = proxy.bool("tls")
        server_name = proxy.string("servername").ifEmpty { proxy.string("sni") }.ifEmpty { proxy.string("server") }
        insecure = proxy.bool("skip-cert-verify")
        alpn.addAll(proxy.strings("alpn"))
        utls.parseFromClash(proxy)
        reality.parseFromClash(proxy)
        return true
    }

    /** TLS.cpp:404-438. */
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
        if (cipher_suites.isNotEmpty()) q.add("tls_cipher_suites" to cipher_suites.joinToString(","))
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

    /** TLS.cpp:439-490. */
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

    /** TLS.cpp:491-503. */
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
     * TLS.cpp:504-564 without `spoof` / `spoof_method` (TLS.cpp:545-551): spoofing needs raw sockets, which an
     * unrooted app does not have (D8). The spoof fields still round-trip through links and JSON.
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
