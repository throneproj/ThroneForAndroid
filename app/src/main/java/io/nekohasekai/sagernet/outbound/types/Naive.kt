package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.LinkQuery

/** naive (include/configs/outbounds/naive.h, src/configs/outbounds/naive.cpp). */
class Naive : Outbound("naive") {
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var congestion_control: String = ""
    @JvmField var extra_headers: MutableList<String> = ArrayList()
    @JvmField var insecure_concurrency: Int = 0
    @JvmField var quic: Boolean = false
    @JvmField var uot: Boolean = false
    @JvmField var tls: Tls = Tls()

    /** naive.h:19-22: TLS is always on and uTLS is never emitted. */
    init {
        tls.enabled = true
        tls.utls.supported = false
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun limitedTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** naive.cpp:70-102: `naive+quic://` selects QUIC; only sni/certificates/fragment/ECH of the TLS block are read. */
    override fun parseFromLink(link: String): Boolean {
        val escaped = escapeUserInfoAts(link)
        val url = LinkParser.parse(escaped)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password
        if (serverPort == 0) serverPort = 443
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        if (q.has("insecure-concurrency")) insecure_concurrency = maxOf(0, QtStrings.toInt(q.value("insecure-concurrency")))
        if (q.has("extra-headers")) extra_headers = parseExtraHeaders(q)
        if (url.scheme == "naive+quic") {
            quic = true
            if (q.has("congestion_control")) congestion_control = q.value("congestion_control")
        }
        tls.enabled = true
        if (q.has("sni")) tls.server_name = q.value("sni")
        if (q.has("tls_certificate")) tls.certificate = QtStrings.splitSkipEmpty(q.valueFully("tls_certificate"), ",")
        if (q.has("tls_certificate_path")) tls.certificate_path = q.valueFully("tls_certificate_path")
        if (q.has("tls_fragment")) {
            tls.fragment = q.value("tls_fragment") == "true"
            tls.fragment_unspecified = false
        }
        tls.ech.parseFromLink(url)
        return server.isNotEmpty()
    }

    /** naive.cpp:104-129. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "naive") return false
        super.parseFromJson(obj)
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("insecure_concurrency")) insecure_concurrency = maxOf(0, obj.int("insecure_concurrency"))
        if (obj.isObject("extra_headers")) {
            extra_headers = ArrayList()
            val headers = obj.obj("extra_headers")
            for (key in headers.sortedKeys()) {
                var value = headers[key]
                if (value is JsonArray) value = if (value.isEmpty()) JsonNull else value[0]
                extra_headers.add(key)
                extra_headers.add(JsonValues.toStringValue(value))
            }
        }
        if (obj.contains("udp_over_tcp")) {
            if (obj.isBool("udp_over_tcp")) uot = obj.bool("udp_over_tcp")
            if (obj.isObject("udp_over_tcp")) uot = obj.obj("udp_over_tcp").bool("enabled")
        }
        if (obj.contains("quic")) quic = obj.bool("quic")
        if (obj.contains("quic_congestion_control")) congestion_control = obj.string("quic_congestion_control")
        if (obj.contains("tls")) tls.parseFromJson(naiveTls(obj.obj("tls"), keepFragment = true))
        return true
    }

    /** naive.cpp:131-160. */
    override fun exportToLink(): String {
        val url = LinkBuilder(if (quic) "naive+quic" else "naive+https")
        // credentials missing from the link or JSON are null QStrings on the desktop, which add no user-info
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        if (serverPort > 0) url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (tls.server_name.isNotEmpty()) url.addQueryItem("sni", tls.server_name)
        if (extra_headers.isNotEmpty()) url.addQueryItemPercentEncoded("extra-headers", exportExtraHeaders(extra_headers))
        if (insecure_concurrency > 0) url.addQueryItem("insecure-concurrency", insecure_concurrency.toString())
        if (uot) url.addQueryItem("uot", "1")
        if (quic && congestion_control.isNotEmpty()) url.addQueryItem("congestion_control", congestion_control)
        if (tls.certificate.isNotEmpty()) url.addQueryItem("tls_certificate", tls.certificate.joinToString(","))
        if (tls.certificate_path.isNotEmpty()) url.addQueryItem("tls_certificate_path", tls.certificate_path)
        if (!tls.fragment_unspecified) url.addQueryItem("tls_fragment", if (tls.fragment) "true" else "false")
        url.addQueryItems(tls.ech.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** naive.cpp:162-178: `tls` is the filtered TLS block, fragment kept. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "naive"
        obj.merge(baseExportToJson())
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (insecure_concurrency > 0) obj["insecure_concurrency"] = insecure_concurrency
        if (extra_headers.isNotEmpty()) obj["extra_headers"] = JsonValues.pairListToObject(extra_headers)
        if (uot) obj["udp_over_tcp"] = uot
        if (quic) {
            obj["quic"] = quic
            if (congestion_control.isNotEmpty()) obj["quic_congestion_control"] = congestion_control
        }
        obj["tls"] = naiveTls(tls.exportToJson(), keepFragment = true)
        return obj
    }

    /** naive.cpp:180-200: not tls.build(), which would inject the global skip_cert / fragment defaults the core rejects for naive. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "naive"
        obj.merge(baseBuild(ctx))
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (insecure_concurrency > 0) obj["insecure_concurrency"] = insecure_concurrency
        if (extra_headers.isNotEmpty()) obj["extra_headers"] = JsonValues.pairListToObject(extra_headers)
        if (uot) obj["udp_over_tcp"] = uot
        if (quic) {
            obj["quic"] = quic
            if (congestion_control.isNotEmpty()) obj["quic_congestion_control"] = congestion_control
        }
        val tlsObj = tls.exportToJson()
        // exportToJson keeps the Throne-only ech.resolver, which the core rejects
        if (tls.ech.enabled) tlsObj["ech"] = tls.ech.build(ctx)
        obj["tls"] = naiveTls(tlsObj, keepFragment = false)
        return BuildResult(obj)
    }

    /** naive.cpp:199-202. */
    override fun displayType(): String = "Naive"

    /** naive.cpp:204-207. */
    override fun security(): SecurityInfo = securityFromTls(if (quic) "QUIC" else "")

    companion object {
        /**
         * naive.cpp:58-67: the core's naive outbound reads only these keys and refuses to start on most other TLS
         * options; `fragment` is kept in the persisted form only, for the dialer-level ("custom") fragment of baseBuild.
         */
        private fun naiveTls(tls: JsonObject, keepFragment: Boolean): JsonObject {
            val kept = jsonObjectOf("enabled" to true)
            for (key in listOf("server_name", "certificate", "certificate_path", "ech")) {
                if (tls.contains(key)) kept[key] = JsonValues.deepCopy(tls[key]!!)
            }
            if (keepFragment && tls.contains("fragment")) kept["fragment"] = tls["fragment"]
            return kept
        }

        // naive.cpp:12-30: Go's net/url (Husi, naive's own tooling) splits the user-info at the last '@', QUrl at the first
        private fun escapeUserInfoAts(link: String): String {
            val schemeEnd = link.indexOf("://")
            if (schemeEnd < 0) return link
            val from = schemeEnd + 3
            var authEnd = link.length
            for (i in from until link.length) {
                val c = link[i]
                if (c == '/' || c == '?' || c == '#') {
                    authEnd = i
                    break
                }
            }
            val lastAt = link.lastIndexOf('@', authEnd - 1)
            if (lastAt < from) return link
            val userInfo = link.substring(from, lastAt)
            if (!userInfo.contains('@')) return link
            return link.substring(0, from) + userInfo.replace("@", "%40") + link.substring(lastAt)
        }

        // naive.cpp:33-47 reads the FullyEncoded item, turns every '+' into a space (form encoding, as Husi writes it)
        // and percent-decodes; on the PrettyDecoded item the same '+' rule holds because a %2B escape is kept there
        // while a literal '+' is not, so decoding that form is equivalent
        private fun parseExtraHeaders(q: LinkQuery): MutableList<String> {
            val text = LinkCodec.decodeFully(q.value("extra-headers").replace('+', ' '))
            val pairs = ArrayList<String>()
            for (rawLine in QtStrings.splitSkipEmpty(text, "\n")) {
                val line = if (rawLine.endsWith('\r')) rawLine.dropLast(1) else rawLine
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val name = line.substring(0, colon).trim()
                if (name.isEmpty()) continue
                pairs.add(name)
                pairs.add(line.substring(colon + 1).trim())
            }
            return pairs
        }

        // naive.cpp:49-55: pre-encoded because QUrlQuery would keep a literal '+', which every reader decodes as a space
        private fun exportExtraHeaders(pairs: List<String>): String {
            val lines = ArrayList<String>()
            var i = 0
            while (i + 1 < pairs.size) {
                lines.add(pairs[i] + ":" + pairs[i + 1])
                i += 2
            }
            return LinkCodec.percentEncodeAll(lines.joinToString("\r\n"))
        }
    }
}
