package io.nekohasekai.sagernet.outbound

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.common.DialFields
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.common.QuicFields
import io.nekohasekai.sagernet.outbound.common.TLS_UNSAFE_FINGERPRINT
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
import io.nekohasekai.sagernet.outbound.types.Juicity
import io.nekohasekai.sagernet.outbound.types.Masque
import io.nekohasekai.sagernet.outbound.types.Naive
import io.nekohasekai.sagernet.outbound.types.TrustTunnel
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** BuildResult (baseConfig.h:9-12). */
class BuildResult(@JvmField val json: JsonObject, @JvmField val error: String = "") {
    val ok: Boolean get() = error.isEmpty()
}

/** Outbound.h:23-28, ordered worst to best. */
enum class SecurityLevel { Unknown, None, Weak, Secure }

/**
 * Outbound.h:30-45; labels are the desktop's untranslated source strings. [caVerified]: Secure only through the CA
 * check, which the global skip_cert setting turns off. [compromised]: weakened by that global setting rather than by
 * the profile itself. [publicLeg]: set by a weaker leg to a public server, which a private profile address does not
 * make moot.
 */
data class SecurityInfo(
    @JvmField val label: String = "",
    @JvmField val transport: String = "",
    @JvmField val level: SecurityLevel = SecurityLevel.Unknown,
    @JvmField val caVerified: Boolean = false,
    @JvmField val compromised: Boolean = false,
    @JvmField val publicLeg: Boolean = false,
) {
    val isDangerous: Boolean get() = level == SecurityLevel.None || level == SecurityLevel.Weak

    /** What "Remove insecure" acts on: a global setting is no reason to delete a profile. */
    val isInsecure: Boolean get() = isDangerous && !compromised
}

/** WithPrivateServer (Outbound.cpp): traffic to a private address never crosses the internet. */
fun withPrivateServer(info: SecurityInfo, host: String): SecurityInfo {
    if (info.isDangerous && !info.publicLeg && Hosts.isPrivateHost(host)) {
        return info.copy(label = "Private", level = SecurityLevel.Secure, compromised = false)
    }
    return info
}

/** The Checks of Outbound.h: the global custom TLS fragment settings. */
object OutboundChecks {
    /** sing-box common/dialer/fragment.go: chunks go through a 1024-byte buffer, five of them into a 2048-byte queue. */
    private const val FRAGMENT_MAX_CHUNK = 1019L
    private const val FRAGMENT_SAFE_CHUNK = 400L

    /** The larger bound of a spec that already passed intOrRange. */
    private fun fragmentMax(spec: String): Long {
        val t = spec.trim()
        return Validation.toInt(t.substring(t.indexOf('-') + 1)) ?: 0
    }

    /** "n" or "min-max" bytes: a max of 0 never advances, one above 1019 panics the core. */
    @JvmStatic
    fun tlsFragmentSize(spec: String): String {
        Validation.intOrRange(spec, 0, FRAGMENT_MAX_CHUNK).let { if (it.isNotEmpty()) return it }
        if (spec.isNotBlank() && fragmentMax(spec) == 0L) return vtr(R.string.validation_fragment_size_zero)
        return ""
    }

    /** A warning for a max above 400: five chunks can then overflow the core's 2048-byte queue on a very large ClientHello. */
    @JvmStatic
    fun tlsFragmentSizeLarge(spec: String): String {
        if (tlsFragmentSize(spec).isNotEmpty() || fragmentMax(spec) <= FRAGMENT_SAFE_CHUNK) return ""
        return vtr(R.string.validation_fragment_size_large)
    }

    @JvmStatic
    fun tlsFragmentSleep(spec: String): String = Validation.intOrRange(spec, 0, Long.MAX_VALUE)

    @JvmStatic
    fun fragmentSize(ctx: BuildContext): String = ctx.fragmentSize.trim().ifEmpty { "10-100" }

    @JvmStatic
    fun fragmentSleep(ctx: BuildContext): String = ctx.fragmentSleep.trim().ifEmpty { "2-5" }

    /** FragmentSettingsError (Outbound.cpp): why the global custom TLS-fragment settings fail every fragmenting TCP build. */
    @JvmStatic
    fun fragmentSettingsError(ctx: BuildContext): String {
        if (ctx.fragmentImplementation != "custom") return ""
        val size = fragmentSize(ctx)
        val sleep = fragmentSleep(ctx)
        tlsFragmentSize(size).let { if (it.isNotEmpty()) return vtr(R.string.validation_fragment_size_setting, size, it) }
        tlsFragmentSleep(sleep).let { if (it.isNotEmpty()) return vtr(R.string.validation_fragment_sleep_setting, sleep, it) }
        return ""
    }

    /** The dialer-level fragment wraps only TCP dials (sing-box common/dialer/extended_tcp.go). */
    private fun dialsTcp(out: Outbound): Boolean {
        // warpmasque falls back to HTTP/2 over TCP unless that is turned off.
        if (out is Masque) return out.http_version == 2 || !out.disable_version_fallback
        if (out.hasQuic() || out is Juicity) return false
        if (out is TrustTunnel) return !out.quic
        if (out is Naive) return !out.quic
        return !out.hasTransport() || out.getTransport().type != "quic"
    }

    /** CustomFragmentApplies (Outbound.cpp): whether the base build emits the dialer-level fragment, which also drops tcp_fast_open. */
    @JvmStatic
    fun customFragmentApplies(out: Outbound, ctx: BuildContext): Boolean {
        if (!out.hasTls() || ctx.fragmentImplementation != "custom") return false
        val tls = out.getTls()
        return tls.enabled && tls.fragmentEffectivelyOn(ctx) && dialsTcp(out)
    }
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
            val transport = if (stream.isXdns()) "XDNS" else displayTransportName(stream.network)
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

    /** Owns the stream [getXrayStream] returns; a custom Xray outbound runs on Xray but has none. */
    open fun hasXrayStream(): Boolean = false

    /** Base validate's server checks: types without a server of their own, and inputs (port ranges, realm, server lists) that supply it. */
    open fun hasServerEndpoint(): Boolean = true
    open fun serverPortOptional(): Boolean = false
    open fun serverAddressOptional(): Boolean = false

    /**
     * outbound::Validate (Outbound.cpp): runs after every import parse, where an Error issue refuses the profile, and
     * backs the editors' field errors. Overrides call super first and never mutate. [ctx] carries the globals the
     * desktop reads from SettingsRepo (fragment implementation, multiplex default).
     */
    open fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        val address = getAddress().trim()
        if (hasServerEndpoint()) {
            if (address.isEmpty()) {
                if (!serverAddressOptional()) issues.addIssue(Severity.Error, "server", vtr(R.string.validation_server_missing))
            } else {
                issues.addIssue(Severity.Error, "server", Validation.host(address), address)
            }
            val port = getPort().trim()
            if ((Validation.toInt(port) ?: 0L) == 0L) {
                if (!serverPortOptional()) issues.addIssue(Severity.Error, "server_port", vtr(R.string.validation_server_port_missing))
            } else {
                issues.addIssue(Severity.Error, "server_port", Validation.port(port), port)
            }
        }

        var tlsOn = false
        var ownUtls = false
        var realityOn = false
        if (hasTls()) {
            val tls = getTls()
            tlsOn = tls.enabled || mustTls()
            if (tlsOn) {
                tls.validate(issues, address, limitedTls(), ctx)
                val fingerprint = tls.utls.fingerPrint.trim()
                ownUtls = tls.utls.supported && tls.utls.enabled && fingerprint.isNotEmpty() && fingerprint != TLS_UNSAFE_FINGERPRINT
                realityOn = tls.reality.enabled || tls.reality.public_key.isNotEmpty()
            }
        }
        if (hasTransport()) getTransport().validate(issues, tlsOn, ownUtls, realityOn)
        if (hasQuic()) getQuic().validate(issues)
        if (hasMux()) getMux().validate(issues, ctx)
        dial.validate(issues)
        if (hasXrayStream()) getXrayStream().validate(issues, address, ctx)
    }

    /** StripForeignState (Outbound.h): called on every import before [validate]; drops state that only means something on another installation or would run a program. */
    open fun stripForeignState(issues: MutableList<Issue>) {}

    /**
     * The build errors the desktop's shared blocks return (outbound::Build's fragment settings, Reality::Build's short
     * ID), checked once here instead of in every type's build. Empty when the profile builds.
     */
    fun sharedBuildError(ctx: BuildContext): String {
        if (OutboundChecks.customFragmentApplies(this, ctx)) {
            OutboundChecks.fragmentSettingsError(ctx).let { if (it.isNotEmpty()) return it }
        }
        // Masque drops the Reality block and naive builds its own TLS object, so neither returns its error.
        if (hasTls() && this !is Masque && !limitedTls()) {
            val tls = getTls()
            if (tls.enabled || mustTls()) return tls.reality.buildError()
        }
        return ""
    }

    /** [build] with [sharedBuildError]: what the config generator and the validator call. */
    fun buildChecked(ctx: BuildContext): BuildResult {
        val result = build(ctx)
        if (!result.ok) return result
        val error = sharedBuildError(ctx)
        return if (error.isEmpty()) result else BuildResult(result.json, error)
    }

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
     * outbound::Build (Outbound.cpp): server, port, dial fields, and with the "custom" fragment implementation on a
     * TCP dial the dialer-level tls_fragment block (which the core rejects next to tcp_fast_open). Unusable fragment
     * settings are reported by [sharedBuildError].
     */
    protected fun baseBuild(ctx: BuildContext): JsonObject {
        val obj = JsonObject()
        if (server.isNotEmpty()) obj["server"] = server
        if (serverPort > 0) obj["server_port"] = serverPort
        obj.merge(dial.build(ctx))
        if (OutboundChecks.customFragmentApplies(this, ctx)) {
            obj.remove("tcp_fast_open")
            obj["tls_fragment"] = jsonObjectOf(
                "enabled" to true,
                "size" to OutboundChecks.fragmentSize(ctx),
                "sleep" to OutboundChecks.fragmentSleep(ctx),
            )
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
