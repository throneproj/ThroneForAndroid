package io.nekohasekai.sagernet.outbound

import io.nekohasekai.sagernet.outbound.common.DialFields
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.common.QuicFields
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.common.Transport
import io.nekohasekai.sagernet.outbound.common.XrayMultiplex
import io.nekohasekai.sagernet.outbound.common.XrayStreamSetting
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink

/** BuildResult (baseConfig.h:9-12). */
class BuildResult(@JvmField val json: JsonObject, @JvmField val error: String = "") {
    val ok: Boolean get() = error.isEmpty()
}

/** Outbound.h:23-28, ordered worst to best. */
enum class SecurityLevel { Unknown, None, Weak, Secure }

/**
 * Outbound.h:30-45; labels are the desktop's untranslated source strings. [caVerified]: Secure only through the CA
 * check, which the global skip_cert setting turns off. [compromised]: weakened by that global setting rather than by
 * the profile itself.
 */
data class SecurityInfo(
    @JvmField val label: String = "",
    @JvmField val transport: String = "",
    @JvmField val level: SecurityLevel = SecurityLevel.Unknown,
    @JvmField val caVerified: Boolean = false,
    @JvmField val compromised: Boolean = false,
) {
    val isDangerous: Boolean get() = level == SecurityLevel.None || level == SecurityLevel.Weak

    /** What "Remove insecure" acts on: a global setting is no reason to delete a profile. */
    val isInsecure: Boolean get() = isDangerous && !compromised
}

/** WithPrivateServer (Outbound.cpp:49-57): traffic to a private address never crosses the internet. */
fun withPrivateServer(info: SecurityInfo, host: String): SecurityInfo {
    if (info.isDangerous && Hosts.isPrivateHost(host)) {
        return info.copy(label = "Private", level = SecurityLevel.Secure, compromised = false)
    }
    return info
}

/** DisplayTransportName (Outbound.cpp:5-16). */
fun displayTransportName(type: String): String = when (type) {
    "", "tcp", "raw" -> ""
    "ws" -> "WebSocket"
    "grpc" -> "gRPC"
    "http" -> "HTTP"
    "httpupgrade" -> "HTTPUpgrade"
    "xhttp" -> "XHTTP"
    "quic" -> "QUIC"
    else -> type.uppercase()
}

/** Outbound.h:15 vPacketEncoding, shared by vmess and vless. */
val vPacketEncoding = listOf("", "packetaddr", "xudp")

/**
 * The `outbound` base class (include/configs/common/Outbound.h, src/configs/common/Outbound.cpp).
 * Fields are public `@JvmField var`s so preference screens can bind them by name.
 */
abstract class Outbound(@JvmField var type: String) {
    @JvmField var name: String = ""
    @JvmField var server: String = ""
    @JvmField var serverPort: Int = 0
    @JvmField var invalid: Boolean = false
    @JvmField var dial: DialFields = DialFields()

    open fun setAddress(newAddr: String) {
        server = newAddr
    }

    open fun getAddress(): String = server

    open fun setPort(newPort: Int) {
        serverPort = newPort
    }

    open fun getPort(): String = serverPort.toString()

    open fun displayAddress(): String = Hosts.displayAddress(server, serverPort)

    open fun displayName(): String = if (name.isEmpty()) displayAddress() else name

    open fun displayType(): String = ""

    fun displayTypeAndName(): String = "[${displayType()}] ${displayName()}"

    /** GetSecurity (Outbound.cpp:72-92): what the profile's own settings give; consumers want [effectiveSecurity]. */
    open fun security(): SecurityInfo {
        if (isXray()) {
            val stream = getXrayStream()
            val transport = displayTransportName(stream.network)
            return when (stream.security) {
                "reality" -> SecurityInfo("Reality", transport, SecurityLevel.Secure)
                "tls" -> SecurityInfo("TLS", transport, SecurityLevel.Secure)
                else -> SecurityInfo("Raw", transport, SecurityLevel.None)
            }
        }
        return securityFromTls(if (hasTransport()) displayTransportName(getTransport().type) else "")
    }

    /**
     * EffectiveSecurity (Outbound.cpp:59-70): [security] plus what the profile can't see, a private server and the
     * global skip_cert setting, which the caller passes as [skipCert] (DataStore.skipCert, as for BuildContext).
     */
    fun effectiveSecurity(skipCert: Boolean): SecurityInfo {
        var info = security()
        // Only TLS::Build injects skip_cert: naive builds its own TLS object, and custom JSON passes through untouched.
        if (info.caVerified && hasTls() && !limitedTls() && skipCert) {
            info = info.copy(label = "Compromised", level = SecurityLevel.Weak, compromised = true)
        }
        if (!info.isDangerous) return info
        return withPrivateServer(info, getAddress())
    }

    /** DisplaySecurity (Outbound.cpp:94-102). */
    fun displaySecurity(skipCert: Boolean): String {
        val info = effectiveSecurity(skipCert)
        if (info.label.isEmpty()) return ""
        val text = if (info.transport.isEmpty()) info.label else "${info.transport}+${info.label}"
        return if (info.isDangerous) "⚠️ $text" else text
    }

    /** SecurityFromTLS (Outbound.cpp:18-47). */
    protected fun securityFromTls(transport: String): SecurityInfo {
        if (hasTls()) {
            val tls = getTls()
            // TLS::Build emits nothing while TLS is off, Reality included.
            if (tls.enabled || mustTls()) {
                return when {
                    tls.reality.enabled -> SecurityInfo("Reality", transport, SecurityLevel.Secure)
                    // The core checks a pinned certificate or key in place of the CA chain, insecure or not.
                    tls.certificate_sha256.isNotEmpty() || tls.certificate_public_key_sha256.isNotEmpty() ->
                        SecurityInfo("TLS", transport, SecurityLevel.Secure)
                    tls.insecure -> SecurityInfo("Insecure TLS", transport, SecurityLevel.Weak)
                    else -> SecurityInfo("TLS", transport, SecurityLevel.Secure, caVerified = true)
                }
            }
        }
        return SecurityInfo("Raw", transport, SecurityLevel.None)
    }

    open fun isXray(): Boolean = false
    open fun isExtraCore(): Boolean = false
    open fun isXrayFullConfig(): Boolean = false
    open fun hasMux(): Boolean = false
    open fun hasTransport(): Boolean = false
    open fun hasTls(): Boolean = false
    open fun mustTls(): Boolean = false
    open fun limitedTls(): Boolean = false
    open fun hasQuic(): Boolean = false
    open fun getTls(): Tls = Tls()
    open fun getQuic(): QuicFields = QuicFields()
    open fun getTransport(): Transport = Transport()
    open fun getMux(): Multiplex = Multiplex()
    open fun getXrayStream(): XrayStreamSetting = XrayStreamSetting()
    open fun getXrayMultiplex(): XrayMultiplex = XrayMultiplex()
    open fun isEndpoint(): Boolean = false
    open fun supportsCredentialStrip(): Boolean = false
    open fun stripCredentials() {}
    open fun buildXray(ctx: BuildContext): BuildResult = BuildResult(JsonObject())

    /** ExportJsonLink (Outbound.h:153-162): the dedup key of the desktop (Profile.cpp:87-90). */
    @JvmOverloads
    fun exportJsonLink(stripMetadata: Boolean = false): String {
        val json = exportToJson()
        if (stripMetadata) json.remove("tag")
        return "throne://add/" + Base64Strict.encode(json.toCompact(), urlSafe = true, padding = false)
    }

    open fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /**
     * outbound::ParseFromLink (Outbound.cpp:77-94) on an already parsed link: port (0 when absent or invalid),
     * name from the fragment, the ACE host, and the dial fields. Subclasses call this with the link they parsed.
     */
    protected open fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) {
            if (!url.invalidPortOnly) return false
            serverPort = 0
        } else {
            serverPort = url.port(0)
        }
        url.fragment?.let { name = it }
        server = url.host
        dial.parseFromLink(url)
        return true
    }

    /**
     * ParseFromClash of the type (src/configs/outbounds/<type>.cpp) on one Clash `proxies:` entry; false for the
     * types Clash cannot describe. Subclasses start with [baseParseFromClash].
     */
    open fun parseFromClash(node: JsonObject): Boolean = false

    /** outbound::ParseFromClash (Outbound.cpp:104-110): name, ACE host and port of the entry. */
    protected fun baseParseFromClash(proxy: ClashProxy) {
        name = proxy.string("name")
        server = Hosts.toAceHost(proxy.string("server"))
        serverPort = proxy.int("port")
    }

    /** outbound::ParseFromJson (Outbound.cpp:95-103). */
    open fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("tag")) name = obj.string("tag")
        if (obj.contains("server")) server = Hosts.toAceHost(obj.string("server"))
        if (obj.contains("server_port")) serverPort = obj.int("server_port")
        dial.parseFromJson(obj)
        return true
    }

    /** outbound::ExportToLink (Outbound.cpp:111-116): only the dial-field query items; types build a full URL. */
    open fun exportToLink(): String = LinkBuilder.formatQuery(baseLinkQuery())

    /** The dial-field query items every type appends last (mergeUrlQuery(query, outbound::ExportToLink())). */
    protected fun baseLinkQuery(): List<Pair<String, String>> = dial.exportToLink()

    open fun exportToJson(): JsonObject = baseExportToJson()

    /** outbound::ExportToJson (Outbound.cpp:117-126). */
    protected fun baseExportToJson(): JsonObject {
        val obj = JsonObject()
        if (name.isNotEmpty()) obj["tag"] = name
        if (server.isNotEmpty()) obj["server"] = server
        if (serverPort > 0) obj["server_port"] = serverPort
        obj.merge(dial.exportToJson())
        return obj
    }

    /** ExportIdentity (Outbound.cpp:127-145). */
    open fun exportIdentity(): JsonObject {
        if (server.isEmpty()) return exportToJson()
        val obj = jsonObjectOf("server" to server, "server_port" to serverPort)
        if (isXray()) {
            val s = getXrayStream().exportIdentity()
            if (s.isNotEmpty()) obj["stream"] = s
        } else {
            if (hasTls()) {
                val t = getTls().exportIdentity()
                if (t.isNotEmpty()) obj["tls"] = t
            }
            if (hasTransport()) {
                val t = getTransport().exportIdentity()
                if (t.isNotEmpty()) obj["transport"] = t
            }
        }
        return obj
    }

    open fun build(ctx: BuildContext): BuildResult = BuildResult(baseBuild(ctx))

    /**
     * outbound::Build (Outbound.cpp:146-167): server, port, dial fields, and with the "custom" fragment
     * implementation the dialer-level tls_fragment block (which the core rejects next to tcp_fast_open).
     */
    protected fun baseBuild(ctx: BuildContext): JsonObject {
        val obj = JsonObject()
        if (server.isNotEmpty()) obj["server"] = server
        if (serverPort > 0) obj["server_port"] = serverPort
        obj.merge(dial.build(ctx))
        if (hasTls()) {
            val t = getTls()
            if (t.enabled && t.fragmentEffectivelyOn(ctx) && ctx.fragmentImplementation == "custom") {
                obj.remove("tcp_fast_open")
                obj["tls_fragment"] = jsonObjectOf(
                    "enabled" to true,
                    "size" to ctx.fragmentSize.ifEmpty { "10-100" },
                    "sleep" to ctx.fragmentSleep.ifEmpty { "2-5" },
                )
            }
        }
        return obj
    }
}

/**
 * The base `outbound` with invalid=true that NewOutboundByType returns for unknown types (OutboundFactory.cpp:62-64),
 * also used as the placeholder for types not yet ported. Links never parse (the profile is dropped); JSON keeps the
 * base fields so a stored row of an unknown type survives a load/save cycle.
 */
class InvalidOutbound(type: String) : Outbound(type) {
    init {
        invalid = true
    }

    override fun parseFromLink(link: String): Boolean = false
}
