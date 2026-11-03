package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.types.Dnstt
import io.nekohasekai.sagernet.outbound.types.MasterDns
import io.nekohasekai.sagernet.outbound.types.XrayVless
import io.nekohasekai.sagernet.ui.json.engine.JsonTree
import io.nekohasekai.sagernet.ui.json.engine.JsonType
import io.nekohasekai.sagernet.ui.json.engine.JsonValue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr

/** Configs::DnstLink (include/configs/common/DnsTunnel.h). */
class DnstLink {
    @JvmField var tag: String = ""
    @JvmField var domain: String = ""
    @JvmField var transport: String = ""
    @JvmField var backend: String = ""

    /** Readable-form parameter names; "pk" holds the whole PEM key. */
    @JvmField val params: MutableMap<String, String> = HashMap()

    /** qThroneMobile's top-level "resolvers" in the JSON form. */
    @JvmField var resolvers: List<String> = emptyList()
    @JvmField var hasResolvers: Boolean = false
}

/** [link] is null when the text is unusable, [error] then says why. */
class DnstDecode(@JvmField val link: DnstLink?, @JvmField val error: String)

/** src/configs/common/DnsTunnel.cpp, without the startup allowance only the desktop's test runner and scanner read. */
object DnsTunnels {

    const val MAX_LINK_SIZE = 64 * 1024

    private val PARAMS = listOf(
        "pubkey", "cert", "record-type", "dnstt-compat", "clientid-size", "idle-timeout", "keepalive", "mkey", "encryption",
        "user", "password", "pk", "method",
    )
    private val TRANSPORT_PARAMS = PARAMS.subList(0, 9)

    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun isXdns(outbound: Outbound): Boolean = outbound is XrayVless && outbound.streamSetting.isXdns()

    /** IsDnsTunnelProfile: includes Xray VLESS over mKCP with an xdns mask. */
    @JvmStatic
    fun isDnsTunnelProfile(outbound: Outbound): Boolean = outbound is Dnstt || outbound is MasterDns || isXdns(outbound)

    /** ProfileResolverSource. */
    @JvmStatic
    fun profileResolverSource(outbound: Outbound): ResolverSource? = when {
        outbound is Dnstt -> outbound.resolvers
        outbound is MasterDns -> outbound.resolvers
        outbound is XrayVless && outbound.streamSetting.isXdns() -> outbound.streamSetting.xdnsResolvers
        else -> null
    }

    private class DerElement(val tag: Int, val start: Int, val end: Int)

    private fun derElement(der: ByteArray, pos: Int): DerElement? {
        if (pos < 0 || pos + 2 > der.size) return null
        val tag = der[pos].toInt() and 0xFF
        var length = (der[pos + 1].toLong() and 0xFF)
        var start = pos + 2
        if (length and 0x80L != 0L) {
            val bytes = (length and 0x7F).toInt()
            if (bytes == 0 || bytes > 4 || start + bytes > der.size) return null
            length = 0
            for (i in 0 until bytes) length = (length shl 8) or (der[start + i].toLong() and 0xFF)
            start += bytes
        }
        if (length > der.size - start) return null
        return DerElement(tag, start, start + length.toInt())
    }

    // The PEM type of a private key: OpenSSH's own format, or PKCS#8 (plain or encrypted), SEC1, DSA or PKCS#1 told apart by their ASN.1 shape.
    private fun pemType(der: ByteArray): String {
        if (der.size >= OPENSSH_MAGIC.size && der.copyOfRange(0, OPENSSH_MAGIC.size).contentEquals(OPENSSH_MAGIC)) return "OPENSSH PRIVATE KEY"
        val outer = derElement(der, 0) ?: return ""
        if (outer.tag != 0x30 || outer.end != der.size) return ""
        val tags = ArrayList<Int>()
        var firstInteger: ByteArray? = null
        var pos = outer.start
        while (pos < outer.end && tags.size <= 16) {
            val child = derElement(der, pos) ?: return ""
            if (child.end > outer.end) return ""
            if (tags.isEmpty() && child.tag == 0x02) firstInteger = der.copyOfRange(child.start, child.end)
            tags.add(child.tag)
            pos = child.end
        }
        val leadingIntegers = tags.indexOfFirst { it != 0x02 }.let { if (it < 0) tags.size else it }
        if (tags.size >= 3 && tags[0] == 0x02 && tags[1] == 0x30 && tags[2] == 0x04) return "PRIVATE KEY"
        if (tags.size == 2 && tags[0] == 0x30 && tags[1] == 0x04) return "ENCRYPTED PRIVATE KEY"
        if (tags.size >= 2 && tags[0] == 0x02 && tags[1] == 0x04 && firstInteger?.contentEquals(byteArrayOf(1)) == true) return "EC PRIVATE KEY"
        if (tags.size == 6 && leadingIntegers == 6) return "DSA PRIVATE KEY"
        if (leadingIntegers >= 9) return "RSA PRIVATE KEY"
        return ""
    }

    private fun jsonText(value: Any?): String = when (value) {
        is String -> value
        is Boolean -> if (value) "true" else "false"
        is Long -> if (Math.abs(value) < 1_000_000_000_000_000L) value.toString() else QtStrings.numberG(value.toDouble())
        is Double -> {
            if (value.isFinite() && value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString()
            else QtStrings.numberG(value)
        }
        else -> ""
    }

    /**
     * QJsonDocument::fromJson(...).object(): null unless [text] is one strict JSON object. JsonTree checks the grammar;
     * a leading zero or '+' must be refused here because org.json reads "010" as octal.
     */
    @JvmStatic
    fun strictJsonObject(text: String): JsonObject? {
        val root = JsonTree.parse(text).root ?: return null
        if (root.type != JsonType.Object || hasLooseNumber(root, text)) return null
        return JsonInput.parseValue(text) as? JsonObject
    }

    private fun hasLooseNumber(value: JsonValue, text: String): Boolean = when (value.type) {
        JsonType.Number -> {
            val raw = text.substring(value.span.offset, value.span.end).removePrefix("-")
            raw.startsWith('+') || (raw.length > 1 && raw[0] == '0' && raw[1].isDigit())
        }
        JsonType.Array -> value.elements.any { hasLooseNumber(it, text) }
        JsonType.Object -> value.members.any { hasLooseNumber(it, text) }
        else -> false
    }

    private fun formDecode(text: String): String = LinkCodec.decodeFully(text.replace('+', ' '))

    private fun decode(link: String, full: Boolean): DnstDecode {
        fun fail(message: String) = DnstDecode(null, message)
        if (link.length > MAX_LINK_SIZE) return fail(vtr(R.string.validation_link_too_long))
        if (!link.startsWith("dnst://", ignoreCase = true)) return fail(vtr(R.string.validation_dnst_not_link))
        val out = DnstLink()
        var rest = link.substring(7).trim()
        var fragment = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            fragment = LinkCodec.decodeFully(rest.substring(hash + 1)).trim()
            rest = rest.substring(0, hash)
        }
        var query = ""
        val mark = rest.indexOf('?')
        if (mark >= 0) {
            query = rest.substring(mark + 1)
            rest = rest.substring(0, mark)
        }

        if (rest.contains('/')) {
            val parts = rest.split('/')
            out.tag = fragment
            out.domain = LinkCodec.decodeFully(parts.getOrElse(0) { "" }).trim()
            out.transport = LinkCodec.decodeFully(parts.getOrElse(1) { "" }).trim().lowercase()
            out.backend = LinkCodec.decodeFully(parts.getOrElse(2) { "" }).trim().lowercase()
            for (pair in query.split('&')) {
                if (pair.isEmpty()) continue
                val eq = pair.indexOf('=')
                val key = formDecode(if (eq < 0) pair else pair.substring(0, eq)).trim().lowercase()
                if (key in PARAMS) out.params[key] = if (eq < 0) "" else formDecode(pair.substring(eq + 1))
            }
        } else {
            val bytes = base64(rest)
            if (bytes.isEmpty()) return fail(vtr(R.string.validation_import_link_damaged_base64))
            val root = strictJsonObject(String(bytes, Charsets.UTF_8))
                ?: return fail(vtr(R.string.validation_import_link_damaged_object))
            val transport = root.obj("transport")
            val backend = root.obj("backend")
            out.tag = root.string("tag").trim().ifEmpty { fragment }
            out.domain = transport.string("domain").trim()
            out.transport = transport.string("type").trim().lowercase()
            out.backend = backend.string("type").trim().lowercase()
            for (name in PARAMS) {
                val key = name.replace('-', '_')
                val layer = if (name in TRANSPORT_PARAMS) transport else backend
                if (layer.contains(key)) out.params[name] = jsonText(layer[key])
            }
            if (full && root.contains("resolvers")) {
                out.resolvers = jsonStringList(root["resolvers"], "resolvers")
                out.hasResolvers = true
            }
        }
        if (out.transport.isEmpty()) return fail(vtr(R.string.validation_dnst_no_transport))

        if (full) {
            val key = out.params["pk"].orEmpty().trim()
            if (key.isNotEmpty()) {
                val pem = if (key.contains("-----BEGIN ")) key else privateKeyPem(key)
                if (pem.isEmpty()) {
                    ParseDiag.warn("ssh_private_key", vtr(R.string.validation_dnst_ssh_key_unreadable))
                    out.params.remove("pk")
                } else {
                    out.params["pk"] = pem
                }
            }
        }
        return DnstDecode(out, "")
    }

    /** DecodeDnstLink: reports an unreadable SSH key through ParseDiag and drops it. */
    @JvmStatic
    fun decodeLink(link: String): DnstDecode = decode(link, true)

    /** DnstLinkTransport: "" when the link is unusable. */
    @JvmStatic
    fun linkTransport(link: String): String = decode(link, false).link?.transport ?: ""

    /** EncodeDnstLink. */
    @JvmStatic
    fun encodeLink(link: DnstLink, asJson: Boolean): String {
        if (asJson) {
            val transport = JsonObject().also {
                it["type"] = link.transport
                it["domain"] = link.domain
            }
            val backend = JsonObject().also { it["type"] = link.backend }
            for (name in PARAMS) {
                val value = link.params[name].orEmpty()
                if (value.isEmpty()) continue
                val json: Any = when (name) {
                    "dnstt-compat" -> value == "true"
                    "clientid-size", "encryption" -> QtStrings.toInt(value)
                    else -> value
                }
                (if (name in TRANSPORT_PARAMS) transport else backend).set(name.replace('-', '_'), json)
            }
            val root = JsonObject()
            if (link.tag.isNotEmpty()) root["tag"] = link.tag
            root["transport"] = transport
            root["backend"] = backend
            return "dnst://" + Base64Strict.encode(root.toCompact(), urlSafe = true, padding = false)
        }
        val text = StringBuilder("dnst://${link.domain}/${link.transport}/${link.backend}")
        val query = ArrayList<String>()
        for (name in PARAMS) {
            var value = link.params[name].orEmpty()
            if (name == "pk" && value.isNotEmpty()) value = privateKeyParam(value)
            if (value.isEmpty()) continue
            query.add(name + "=" + LinkCodec.percentEncodeAll(value))
        }
        if (query.isNotEmpty()) text.append('?').append(query.joinToString("&"))
        if (link.tag.isNotEmpty()) text.append('#').append(LinkCodec.percentEncodeAll(link.tag))
        return text.toString()
    }

    /** DnsTunnelBase64: either alphabet, padded or not; empty for anything else. */
    @JvmStatic
    fun base64(text: String): ByteArray {
        val data = StringBuilder(text.length + 3)
        for (c in text) {
            if (c == '\r' || c == '\n' || c == '\t') continue
            if (c.code >= 128) return ByteArray(0)
            // A '+' that form decoding turned into a space.
            when (c) {
                ' ', '+' -> data.append('-')
                '/' -> data.append('_')
                else -> data.append(c)
            }
        }
        while (data.endsWith("=")) data.setLength(data.length - 1)
        if (data.isEmpty()) return ByteArray(0)
        while (data.length % 4 != 0) data.append('=')
        return Base64Strict.decode(data.toString(), urlSafe = true) ?: ByteArray(0)
    }

    /** DnstPrivateKeyPem: pk drops the PEM type, so it is told from the key's DER structure. */
    @JvmStatic
    fun privateKeyPem(param: String): String {
        val der = base64(param.trim())
        val type = if (der.isEmpty()) "" else pemType(der)
        if (type.isEmpty()) return ""
        val lines = arrayListOf("-----BEGIN $type-----")
        val encoded = Base64Strict.encode(der)
        for (i in encoded.indices step 64) lines.add(encoded.substring(i, minOf(i + 64, encoded.length)))
        lines.add("-----END $type-----")
        return lines.joinToString("\n")
    }

    /** DnstPrivateKeyParam: "" for a legacy encrypted key, whose Proc-Type headers pk cannot carry. */
    @JvmStatic
    fun privateKeyParam(pem: String): String {
        val text = pem.trim()
        val begin = text.indexOf("-----BEGIN ")
        val labelEnd = if (begin < 0) -1 else text.indexOf("-----", begin + 11)
        val end = if (labelEnd < 0) -1 else text.indexOf("-----END ", labelEnd + 5)
        if (end < 0) return ""
        val body = text.substring(labelEnd + 5, end)
        // Proc-Type and DEK-Info lines of a legacy encrypted key.
        if (body.contains(':')) return ""
        val compact = StringBuilder()
        for (c in body) if (!c.isWhitespace()) compact.append(if (c.code < 128) c else '!')
        val decoded = Base64Strict.decode(compact.toString())
        if (decoded == null || decoded.isEmpty()) return ""
        return Base64Strict.encode(decoded, urlSafe = true, padding = false)
    }
}

/** The DNS tunnel Checks of DnsTunnel.h. */
object DnsTunnelChecks {
    @JvmStatic
    fun dnsTunnelDomain(text: String): String {
        val domain = text.trim()
        if (domain.isEmpty()) return vtr(R.string.validation_dns_tunnel_domain_required)
        Validation.host(domain).let { if (it.isNotEmpty()) return it }
        val bare = domain.removeSuffix(".")
        if (domain.startsWith('[') || Validation.ip(bare).isEmpty() || !bare.contains('.')) {
            return vtr(R.string.validation_dns_tunnel_domain_delegated)
        }
        return ""
    }
}
