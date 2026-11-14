package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.CommonChecks
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr

/** trusttunnel.h: sing-trusttunnel quic.go runs cubic and reno as such, every other name (and an unknown one) as BBR. */
val trustTunnelCongestionControls = listOf("", "bbr", "cubic", "reno", "bbr_standard", "bbr2", "bbr_variant")

/** The Checks of trusttunnel.h. */
object TrustTunnelChecks {
    /** TrustTunnelClientRandom: sing-box-throne protocol/trusttunnel/client_random.go parseClientRandom, hex prefix[/mask] of 1 to 32 bytes. */
    @JvmStatic
    fun trustTunnelClientRandom(text: String): String {
        val value = text.trim()
        if (value.isEmpty()) return ""
        val slash = value.indexOf('/')
        val prefix = if (slash < 0) value else value.substring(0, slash)
        var ok = prefix.isNotEmpty() && Validation.hex(prefix, 2, 64).isEmpty()
        if (ok && slash >= 0) {
            val mask = value.substring(slash + 1)
            ok = mask.length == prefix.length && Validation.hex(mask, 0, 0).isEmpty()
        }
        return if (ok) "" else vtr(R.string.validation_trusttunnel_client_random)
    }

    @JvmStatic
    fun trustTunnelClientRandomAuthKey(text: String): String = Validation.hex(text, 0, 0)

    @JvmStatic
    fun trustTunnelClientRandomPair(clientRandom: Boolean, authKey: Boolean): String {
        if (!clientRandom || !authKey) return ""
        return vtr(R.string.validation_trusttunnel_client_random_pair)
    }

    /** The auth key is derived from the exact SNI on the wire, so that SNI must be a domain sent as is. */
    @JvmStatic
    fun trustTunnelClientRandomAuthKeySni(disableSni: Boolean, mixedCaseSni: Boolean, serverName: String): String {
        if (disableSni) return vtr(R.string.validation_trusttunnel_auth_key_disable_sni)
        if (mixedCaseSni) return vtr(R.string.validation_trusttunnel_auth_key_mixed_case_sni)
        val name = serverName.trim().trimEnd('.')
        if (name.isEmpty() || Hosts.isIpAddress(name)) return vtr(R.string.validation_trusttunnel_auth_key_sni)
        return ""
    }

    @JvmStatic
    fun trustTunnelCredentials(username: String, password: String): String {
        if (username.isNotBlank() && password.isNotEmpty()) return ""
        return vtr(R.string.validation_trusttunnel_credentials)
    }

    /**
     * TrustTunnelTlsConflict: client_random (set or derived from the auth key) and custom_sni against the TLS settings;
     * a QUIC build drops uTLS and REALITY.
     */
    @JvmStatic
    fun trustTunnelTlsConflict(
        clientRandom: Boolean,
        customSni: Boolean,
        quic: Boolean,
        ech: Boolean,
        reality: Boolean,
        disableSni: Boolean,
        utlsFingerprint: String,
    ): String {
        if (clientRandom && ech) return vtr(R.string.validation_trusttunnel_client_random_ech)
        if (customSni && ech) return vtr(R.string.validation_trusttunnel_custom_sni_ech)
        if (customSni && disableSni) return vtr(R.string.validation_trusttunnel_custom_sni_disable_sni)
        if (quic) return ""
        if ((clientRandom || customSni) && reality) return vtr(R.string.validation_trusttunnel_reality)
        if (clientRandom && utlsFingerprint == "custom") return vtr(R.string.validation_trusttunnel_client_random_utls)
        return ""
    }
}

/** trusttunnel (include/configs/outbounds/trusttunnel.h, src/configs/outbounds/trusttunnel.cpp). */
class TrustTunnel : Outbound("trusttunnel") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var congestion_control: String = ""
    @JvmField var custom_sni: String = ""
    @JvmField var client_random: String = ""
    @JvmField var client_random_auth_key: String = ""
    @JvmField var health_check: Boolean = false
    @JvmField var quic: Boolean = false
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /**
     * trusttunnel::ParseFromDeepLink: the official deep link `tt://?<base64url TLV payload>` (DEEP_LINK.md in
     * TrustTunnel/TrustTunnel). Tags: 0x00 version (at most 2), 0x01 SNI, 0x02 first `host[:port]`, 0x03 custom SNI,
     * 0x05/0x06 credentials, 0x07 insecure flag, 0x08 DER certificate chain, 0x09 QUIC when the byte is 2,
     * 0x0A anti-DPI (TLS fragment on), 0x0B client random, 0x0C name, 0x0E subscription URL (must be https; the
     * static fields stay required, so a subscription-only link fails), 0x0F client random auth key; has_ipv6,
     * dns_upstreams and unknown tags are skipped.
     */
    fun parseFromDeepLink(payload: String): Boolean {
        val data = Base64Strict.decodeLenient(payload, urlSafe = true)
        if (data.isEmpty()) return false
        var haveAddress = false
        var extraAddresses = 0
        val cursor = ByteCursor(data)
        while (cursor.pos < data.size) {
            val tag = cursor.readVarInt() ?: return false
            val length = cursor.readVarInt() ?: return false
            if (length > cursor.remaining.toLong()) return false
            val value = data.copyOfRange(cursor.pos, cursor.pos + length.toInt())
            cursor.pos += length.toInt()
            val text = String(value, Charsets.UTF_8)
            val flag = value.isNotEmpty() && value[0].toInt() != 0
            when (tag) {
                0x00L -> {
                    val version = ByteCursor(value).readVarInt()
                    if (version == null || version > 2) return false
                }
                0x01L -> tls.server_name = text
                0x02L -> {
                    // only the first address is kept: a profile has one server
                    if (haveAddress) {
                        extraAddresses++
                    } else {
                        val address = LinkParser.parse("tt://$text")
                        if (!address.isValid || address.host.isEmpty()) return false
                        server = address.host
                        serverPort = address.port(443)
                        haveAddress = true
                    }
                }
                0x03L -> custom_sni = text.trim()
                0x05L -> username = text
                0x06L -> password = text
                0x07L -> tls.insecure = flag
                0x08L -> {
                    tls.certificate = derChainToPem(value)
                    if (tls.certificate.isEmpty()) return false
                }
                0x09L -> quic = value.isNotEmpty() && value[0].toInt() == 2
                // anti_dpi slows the handshake writes down so the ClientHello spans several segments
                0x0AL -> if (flag) tls.saveFragmentState(1)
                0x0BL -> client_random = text.trim()
                0x0CL -> name = text
                0x0EL -> if (!text.startsWith("https://")) return false
                0x0FL -> client_random_auth_key = text.trim()
            }
        }
        // The auth key takes priority over client_random_prefix, as in the official client.
        if (client_random_auth_key.isNotEmpty()) client_random = ""
        if (extraAddresses > 0) {
            ParseDiag.warn("address", vplural(R.plurals.validation_trusttunnel_extra_addresses, extraAddresses))
        }
        tls.enabled = true
        return haveAddress && tls.server_name.isNotEmpty() && username.isNotEmpty() && password.isNotEmpty()
    }

    /** trusttunnel::ParseFromLink: a link without a host but with a query is the deep link. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        if (url.host.isEmpty()) {
            val payload = rawQuery(link)
            if (payload != null) return parseFromDeepLink(payload)
        }
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password

        if (q.has("health_check")) health_check = q.value("health_check") == "true"
        if (q.has("congestion_control")) {
            quic = true
            congestion_control = importCongestion(q.value("congestion_control"))
        }
        if (q.has("custom_sni")) custom_sni = q.value("custom_sni").trim()
        if (q.has("client_random")) client_random = q.value("client_random").trim()
        if (q.has("client_random_auth_key")) client_random_auth_key = q.value("client_random_auth_key").trim()

        tls.parseFromLink(url)
        tls.enabled = true

        if (serverPort == 0) serverPort = 443
        return !(username.isEmpty() || password.isEmpty() || server.isEmpty())
    }

    /** trusttunnel::ParseFromJson. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "trusttunnel") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("health_check")) health_check = obj.bool("health_check")
        if (obj.contains("quic")) quic = obj.bool("quic")
        if (obj.contains("quic_congestion_control")) congestion_control = importCongestion(obj.string("quic_congestion_control"))
        if (obj.contains("custom_sni")) custom_sni = obj.string("custom_sni")
        if (obj.contains("client_random")) client_random = obj.string("client_random")
        if (obj.contains("client_random_auth_key")) client_random_auth_key = obj.string("client_random_auth_key")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** trusttunnel.cpp:149-171 (see Mieru.exportToLink for the empty user-info rule). */
    override fun exportToLink(): String {
        val url = LinkBuilder("tt")
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name

        if (health_check) url.addQueryItem("health_check", "true")
        // the link carries QUIC only through congestion_control; sing-trusttunnel treats "" and "bbr" the same
        if (quic) url.addQueryItem("congestion_control", congestion_control.ifEmpty { "bbr" })
        if (custom_sni.isNotEmpty()) url.addQueryItem("custom_sni", custom_sni)
        if (client_random.isNotEmpty()) url.addQueryItem("client_random", client_random)
        if (client_random_auth_key.isNotEmpty()) url.addQueryItem("client_random_auth_key", client_random_auth_key)

        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** trusttunnel.cpp:173-189. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "trusttunnel"
        obj.merge(baseExportToJson())
        writeFields(obj, congestion_control)
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** trusttunnel::Build: the congestion control in its canonical form. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "trusttunnel"
        obj.merge(baseBuild(ctx))
        writeFields(obj, canonicalCongestion(congestion_control))
        if (tls.enabled) {
            val tlsObject = tls.build(ctx)
            // QUIC dials through qtls, which needs a std TLS config: uTLS and Reality fail there on every connection
            if (quic) {
                tlsObject.remove("utls")
                tlsObject.remove("reality")
            }
            // the official client mimics Chrome by default
            if (!quic && !tlsObject.contains("utls")) {
                tlsObject["utls"] = jsonObjectOf("enabled" to true, "fingerprint" to "chrome")
            }
            // Only the global mixed-case default can reach here with an auth key; the core rejects the pair.
            if (client_random_auth_key.isNotEmpty()) tlsObject.remove("tls_tricks")
            obj["tls"] = tlsObject
        }
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject, congestion: String) {
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (health_check) obj["health_check"] = health_check
        if (quic) {
            obj["quic"] = quic
            if (congestion.isNotEmpty()) obj["quic_congestion_control"] = congestion
        }
        if (custom_sni.isNotEmpty()) obj["custom_sni"] = Hosts.toAceHost(custom_sni)
        if (client_random.isNotEmpty()) obj["client_random"] = client_random
        if (client_random_auth_key.isNotEmpty()) obj["client_random_auth_key"] = client_random_auth_key
    }

    /** trusttunnel.cpp:221-224. */
    override fun displayType(): String = "TrustTunnel"

    /** trusttunnel.cpp:226-229. */
    override fun security(): SecurityInfo = securityFromTls(if (quic) "QUIC" else "")

    /** trusttunnel::Validate. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "username", TrustTunnelChecks.trustTunnelCredentials(username, password))
        issues.addIssue(Severity.Error, "custom_sni", CommonChecks.serverName(custom_sni), custom_sni)
        // The server grants access by this prefix, so it is not echoed.
        issues.addIssue(Severity.Error, "client_random", TrustTunnelChecks.trustTunnelClientRandom(client_random))
        issues.addIssue(Severity.Error, "client_random_auth_key", TrustTunnelChecks.trustTunnelClientRandomAuthKey(client_random_auth_key))
        issues.addIssue(
            Severity.Error, "client_random_auth_key",
            TrustTunnelChecks.trustTunnelClientRandomPair(client_random.isNotEmpty(), client_random_auth_key.isNotEmpty()),
        )
        if (client_random_auth_key.isNotEmpty()) {
            val sni = custom_sni.ifEmpty { tls.server_name.ifEmpty { server } }
            issues.addIssue(
                Severity.Error, "client_random_auth_key",
                TrustTunnelChecks.trustTunnelClientRandomAuthKeySni(tls.disable_sni, tls.tls_tricks, sni),
            )
        }
        issues.addIssue(
            Severity.Error, "tls",
            TrustTunnelChecks.trustTunnelTlsConflict(
                client_random.isNotEmpty() || client_random_auth_key.isNotEmpty(), custom_sni.isNotEmpty(), quic,
                tls.ech.enabled, tls.reality.enabled,
                tls.disable_sni, if (tls.utls.enabled) tls.utls.fingerPrint else "",
            ),
        )
    }

    /** A byte reader for the TLV payload; [readVarInt] is an RFC 9000 variable-length integer, null when truncated. */
    private class ByteCursor(private val data: ByteArray) {
        var pos = 0
        val remaining: Int get() = data.size - pos

        fun readVarInt(): Long? {
            if (pos >= data.size) return null
            val first = data[pos].toInt() and 0xFF
            val length = 1 shl (first shr 6)
            if (pos + length > data.size) return null
            var value = (first and 0x3F).toLong()
            for (i in 1 until length) value = (value shl 8) or (data[pos + i].toLong() and 0xFF)
            pos += length
            return value
        }
    }

    companion object {
        /** canonicalTrustTunnelCongestion: older builds saved translated combo text ("CUBIC", "Reno"), which sing-trusttunnel silently ran as bbr. */
        private fun canonicalCongestion(value: String): String = value.trim().lowercase().replace(' ', '_')

        /** trustTunnelImportCongestion: an import drops an unknown name with a warning, since the core would run it as BBR. */
        private fun importCongestion(value: String): String {
            val congestion = canonicalCongestion(value)
            val message = Validation.oneOf(congestion, trustTunnelCongestionControls)
            if (!ParseDiag.active() || acceptOptional("quic_congestion_control", message, congestion)) return congestion
            return ""
        }

        /** QUrl::query() of the link: the text after the first '?' of the hierarchical part, up to '#'; null without a '?'. */
        private fun rawQuery(link: String): String? {
            val hash = link.indexOf('#')
            val head = if (hash >= 0) link.substring(0, hash) else link
            val question = head.indexOf('?')
            return if (question >= 0) head.substring(question + 1) else null
        }

        /** trusttunnel.cpp:24-47: splits concatenated DER certificates and returns them as PEM lines; empty when malformed. */
        private fun derChainToPem(chain: ByteArray): MutableList<String> {
            val lines = ArrayList<String>()
            var pos = 0
            while (pos + 2 <= chain.size && (chain[pos].toInt() and 0xFF) == 0x30) {
                var headerLength = 2
                var length = chain[pos + 1].toLong() and 0xFF
                if ((length and 0x80L) != 0L) {
                    val lengthBytes = (length and 0x7FL).toInt()
                    if (lengthBytes == 0 || lengthBytes > 4 || pos + 2 + lengthBytes > chain.size) return ArrayList()
                    length = 0
                    for (i in 0 until lengthBytes) length = (length shl 8) or (chain[pos + 2 + i].toLong() and 0xFF)
                    headerLength += lengthBytes
                }
                val total = headerLength.toLong() + length
                if (pos + total > chain.size) return ArrayList()
                lines.add("-----BEGIN CERTIFICATE-----")
                val encoded = Base64Strict.encode(chain.copyOfRange(pos, pos + total.toInt()))
                var i = 0
                while (i < encoded.length) {
                    lines.add(encoded.substring(i, minOf(i + 64, encoded.length)))
                    i += 64
                }
                lines.add("-----END CERTIFICATE-----")
                pos += total.toInt()
            }
            return if (pos == chain.size) lines else ArrayList()
        }
    }
}
