package io.nekohasekai.sagernet.outbound

import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.types.Http
import io.nekohasekai.sagernet.outbound.types.Shadowsocks
import io.nekohasekai.sagernet.outbound.types.Socks
import java.util.concurrent.ConcurrentHashMap

/**
 * NewOutboundByType (src/configs/common/OutboundFactory.cpp:33-65), the link scheme table and link dispatch of
 * src/configs/sub/SubscriptionParser.cpp:48-67 / 510-559, and useXrayVless (src/configs/common/utils.cpp:50-72).
 *
 * Extension point for the per-type work packages: a new type lives in one file,
 * `io.nekohasekai.sagernet.outbound.types.<ClassName>` with a public no-argument constructor, where ClassName is
 * the entry of [CONVENTION_CLASSES] for its factory string. [newByType] finds it by name, so the factory itself
 * never changes; [register] can override any entry explicitly (an app initialiser may do that to avoid reflection
 * once every type exists). Release builds shrink with R8, so a keep rule for that package is required.
 */
object OutboundFactory {

    /** Const.hpp:70-74 Xray::XrayVlessPreference; the default is SettingsRepo.h:303. */
    enum class XrayVlessPreference { XhttpOnly, XhttpAndReality, AllVLESS }

    @JvmField
    val DEFAULT_XRAY_VLESS_PREFERENCE: XrayVlessPreference = XrayVlessPreference.XhttpAndReality

    /** Every factory string of OutboundFactory.cpp:35-61 (R2 §A). */
    @JvmField
    val ALL_TYPES: List<String> = listOf(
        "socks", "http", "shadowsocks", "chain", "autoselector", "vmess", "trojan", "vless", "xrayvless", "hysteria",
        "tuic", "juicity", "trusttunnel", "anytls", "mieru", "snell", "shadowtls", "wireguard", "masque", "openvpn",
        "openconnect", "tailscale", "ssh", "custom", "extracore", "naive", "direct",
    )

    /** OutboundFactory.cpp:44 and the JSON alias of openvpn.cpp:157-158. */
    private val TYPE_ALIASES = mapOf("hysteria2" to "hysteria", "openvpn-client" to "openvpn")

    /** Not ported (tailscale) or desktop-only (extracore); always an [InvalidOutbound]. */
    @JvmField
    val UNSUPPORTED_TYPES: Set<String> = setOf("tailscale", "extracore")

    /** Factory string to the class expected in io.nekohasekai.sagernet.outbound.types for the types other packages add. */
    @JvmField
    val CONVENTION_CLASSES: Map<String, String> = mapOf(
        "vmess" to "Vmess", "trojan" to "Trojan", "vless" to "Vless", "xrayvless" to "XrayVless",
        "hysteria" to "Hysteria", "tuic" to "Tuic", "juicity" to "Juicity", "trusttunnel" to "TrustTunnel",
        "anytls" to "AnyTls", "mieru" to "Mieru", "snell" to "Snell", "shadowtls" to "ShadowTls",
        "wireguard" to "WireGuard", "masque" to "Masque", "openvpn" to "OpenVpn", "openconnect" to "OpenConnect",
        "ssh" to "Ssh", "custom" to "Custom", "naive" to "Naive", "direct" to "Direct", "chain" to "Chain",
        "autoselector" to "AutoSelector",
    )

    private const val TYPES_PACKAGE = "io.nekohasekai.sagernet.outbound.types."

    private val builtin: Map<String, () -> Outbound> = mapOf<String, () -> Outbound>(
        "socks" to { Socks() },
        "http" to { Http() },
        "shadowsocks" to { Shadowsocks() },
    )

    private val registry = ConcurrentHashMap<String, () -> Outbound>()

    @JvmStatic
    fun register(type: String, constructor: () -> Outbound) {
        registry[canonicalType(type)] = constructor
    }

    @JvmStatic
    fun canonicalType(type: String): String = TYPE_ALIASES[type] ?: type

    /** NewOutboundByType: an [InvalidOutbound] (invalid=true) for unknown, unsupported or not yet ported types. */
    @JvmStatic
    fun newByType(type: String): Outbound {
        val canonical = canonicalType(type)
        registry[canonical]?.let { return it() }
        builtin[canonical]?.let { return it() }
        if (canonical !in UNSUPPORTED_TYPES) {
            CONVENTION_CLASSES[canonical]?.let { className -> instantiateByName(className)?.let { return it } }
        }
        return InvalidOutbound(canonical)
    }

    private fun instantiateByName(className: String): Outbound? = try {
        Class.forName(TYPES_PACKAGE + className).getDeclaredConstructor().newInstance() as? Outbound
    } catch (e: Throwable) {
        null
    }

    /** One row of kProtocols (SubscriptionParser.cpp:41-67). */
    class Protocol(
        @JvmField val type: String,
        @JvmField val schemes: List<String>,
        @JvmField val singbox: List<String>,
        @JvmField val clash: List<String>,
    )

    @JvmField
    val PROTOCOLS: List<Protocol> = listOf(
        Protocol("socks", listOf("socks5://", "socks4://", "socks4a://", "socks://"), listOf("socks"), listOf("socks5")),
        Protocol("http", listOf("http://", "https://"), listOf("http"), listOf("http")),
        Protocol("shadowsocks", listOf("ss://"), listOf("shadowsocks"), listOf("ss")),
        Protocol("vmess", listOf("vmess://"), listOf("vmess"), listOf("vmess")),
        Protocol("vless", listOf("vless://"), listOf("vless"), listOf("vless")),
        Protocol("trojan", listOf("trojan://"), listOf("trojan"), listOf("trojan")),
        Protocol("anytls", listOf("anytls://"), listOf("anytls"), listOf("anytls")),
        Protocol("mieru", listOf("mierus://", "mieru://"), listOf("mieru"), emptyList()),
        Protocol("snell", listOf("snell://"), listOf("snell"), listOf("snell")),
        Protocol("hysteria", listOf("hysteria://", "hysteria2://", "hy2://"), listOf("hysteria", "hysteria2"), listOf("hysteria", "hysteria2")),
        Protocol("tuic", listOf("tuic://"), listOf("tuic"), listOf("tuic")),
        Protocol("juicity", listOf("juicity://"), listOf("juicity"), emptyList()),
        Protocol("trusttunnel", listOf("tt://"), listOf("trusttunnel"), emptyList()),
        Protocol("shadowtls", listOf("shadowtls://"), listOf("shadowtls"), emptyList()),
        Protocol("wireguard", listOf("awg://", "wg://", "wireguard://"), listOf("wireguard"), emptyList()),
        Protocol("masque", emptyList(), listOf("masque"), listOf("masque")),
        Protocol("ssh", listOf("ssh://"), listOf("ssh"), listOf("ssh")),
        Protocol("naive", listOf("naive+https://", "naive+quic://"), listOf("naive"), emptyList()),
    )

    /** typeForScheme (SubscriptionParser.cpp:69-76): case-sensitive prefix match. */
    @JvmStatic
    fun typeForScheme(link: String): String? {
        for (p in PROTOCOLS) for (scheme in p.schemes) if (link.startsWith(scheme)) return p.type
        return null
    }

    /** typeForSingBox (SubscriptionParser.cpp:78-85): the factory string for a sing-box outbound `type`. */
    @JvmStatic
    fun typeForSingBox(type: String): String? {
        for (p in PROTOCOLS) for (name in p.singbox) if (type == name) return p.type
        return null
    }

    /** typeForClash (SubscriptionParser.cpp:87-94): the factory string for a Clash proxy `type`. */
    @JvmStatic
    fun typeForClash(type: String): String? {
        for (p in PROTOCOLS) for (name in p.clash) if (type == name) return p.type
        return null
    }

    /** useXrayVless (utils.cpp:50-72): whether a vless:// link needs the Xray core. */
    @JvmStatic
    @JvmOverloads
    fun useXrayVless(link: String, preference: XrayVlessPreference = DEFAULT_XRAY_VLESS_PREFERENCE): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val query = url.query
        val transport = query.value("type")
        val security = query.value("security")
        // sing-box's http transport speaks the raw HTTP header only in plaintext; TLS (which a bare sni also enables) turns it into h2
        val rawHttpOverTls = (transport.isEmpty() || transport == "tcp" || transport == "raw") &&
            query.value("headerType") == "http" &&
            ((security.isNotEmpty() && security != "none") ||
                query.value("sni").isNotEmpty() ||
                query.value("peer").isNotEmpty())
        val encryption = query.value("encryption")
        return preference == XrayVlessPreference.AllVLESS ||
            rawHttpOverTls ||
            transport == "xhttp" ||
            query.has("fm") ||
            query.has("finalmask") ||
            // sing-box has no counterpart to verifyPeerCertByName
            query.has("vcn") ||
            (security == "reality" && preference == XrayVlessPreference.XhttpAndReality) ||
            (encryption != "none" && encryption.isNotEmpty()) ||
            query.value("extra").isNotEmpty()
    }

    /**
     * The type resolution of Parser::jsonLink (SubscriptionParser.cpp:550-559): an object with `protocol` is an
     * Xray outbound ("xray" + protocol), otherwise `type` with the hysteria2 alias. Null for an invalid type. The
     * data is parsed into the result; like the desktop, the parse result itself does not reject the profile.
     */
    @JvmStatic
    fun fromJson(data: JsonObject): Outbound? {
        val outbound = if (data.contains("protocol")) newByType("xray" + data.string("protocol"))
        else newByType(data.string("type"))
        if (outbound.invalid) return null
        outbound.parseFromJson(data)
        return outbound
    }

    /** Parser::jsonLink (SubscriptionParser.cpp:539-560): `throne://add/<base64 of ExportToJson>` or `json://#<base64url>`. */
    @JvmStatic
    fun parseJsonLink(link: String, throneAdd: Boolean): Outbound? {
        val url = LinkParser.parse(link)
        if (!url.isValid) return null
        var dataBytes = if (throneAdd) Base64Strict.decode(url.path.drop(1)) else Base64Strict.decode(url.fragment ?: "", urlSafe = true)
        // ExportJsonLink emits the url-safe alphabet, which the standard decoder rejects whenever '-' or '_' occurs
        if ((dataBytes == null || dataBytes.isEmpty()) && throneAdd) dataBytes = Base64Strict.decode(url.path.drop(1), urlSafe = true)
        if (dataBytes == null || dataBytes.isEmpty()) return null
        val data = JsonInput.parseObject(String(dataBytes, Charsets.UTF_8))
        if (data.isEmpty()) return null
        return fromJson(data)
    }

    /**
     * Parser::link (SubscriptionParser.cpp:510-537) for one subscription line: comments and short lines are skipped,
     * json:// and throne://add/ carry JSON, vless:// goes to xrayvless when [useXrayVless] says so, everything else
     * follows the scheme table. `vpn://` (desktop credential links) is not supported. Null when nothing was produced.
     */
    @JvmStatic
    @JvmOverloads
    fun parseLink(line: String, preference: XrayVlessPreference = DEFAULT_XRAY_VLESS_PREFERENCE): Outbound? {
        if (line.startsWith("//") || line.startsWith("#") || line.length < 2) return null
        if (line.startsWith("json://")) return parseJsonLink(line, false)
        if (line.startsWith("throne://add/", ignoreCase = true)) return parseJsonLink(line, true)
        if (line.startsWith("vpn://", ignoreCase = true)) return null
        val profileType = typeForScheme(line) ?: return null
        val outbound = if (line.startsWith("vless://") && useXrayVless(line, preference)) newByType("xrayvless")
        else newByType(profileType)
        if (!outbound.parseFromLink(line)) return null
        return outbound
    }
}
