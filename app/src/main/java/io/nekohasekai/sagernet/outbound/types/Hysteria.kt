package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.common.QuicFields
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.jsonStringList
import io.nekohasekai.sagernet.validation.vtr
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.floor
import kotlin.math.pow

/** hysteria.h */
val hysteriaBBRProfiles = listOf("standard", "conservative", "aggressive")

/** hysteria.h */
val hysteriaObfsTypes = listOf("salamander", "gecko")

/** hysteria.h: the core refuses Hysteria v1 without both rates; [Hysteria.build] falls back to these. */
const val HYSTERIA_V1_DEFAULT_UP_MBPS = 10
const val HYSTERIA_V1_DEFAULT_DOWN_MBPS = 50

/**
 * The Checks of hysteria.h, shared by the editor (field text) and [Hysteria.validate]. Android only: the hop
 * intervals read a bare number as seconds ([Hysteria.hopDuration]).
 */
object HysteriaChecks {
    private const val MIN_HOP_NS = 5_000_000_000L

    /** The core needs ':' (and reports start > end as no ports, not an error); portsToPorts rewrites '-'. */
    private fun rangeStyle(item: String): Validation.PortRangeStyle? = when {
        item.contains(':') -> Validation.PortRangeStyle(Validation.RangeSep.Colon, allowSingle = false, allowOpenEnds = true, checkOrder = false)
        item.contains('-') -> Validation.PortRangeStyle(Validation.RangeSep.Dash, allowSingle = false, allowOpenEnds = true, checkOrder = false)
        else -> null
    }

    /** One server_ports item as Throne stores it: n, a-b or a:b, open ends allowed. */
    @JvmStatic
    fun hysteriaPortItem(item: String): String {
        val t = item.trim()
        if (t.isEmpty()) return ""
        val style = rangeStyle(t) ?: return Validation.port(t)
        return Validation.portRange(t, style)
    }

    @JvmStatic
    fun hysteriaPortItemOrder(item: String): String {
        val t = item.trim()
        val style = rangeStyle(t) ?: return ""
        return Validation.portRangeOrder(t, style)
    }

    /** Android only: the hop interval fields, where the desktop editor uses Validation::Duration. */
    @JvmStatic
    fun hysteriaHopInterval(text: String): String = Validation.duration(Hysteria.hopDuration(text))

    /** sing-quic hysteria/hop.go: checked on every dial, never at start. */
    @JvmStatic
    fun hysteriaHopMinimum(hopping: Boolean, interval: String): String {
        if (!hopping) return ""
        val ns = Validation.parseDurationNs(Hysteria.hopDuration(interval)) ?: return ""
        if (ns <= 0 || ns >= MIN_HOP_NS) return ""
        return vtr(R.string.validation_hysteria_hop_minimum)
    }

    @JvmStatic
    fun hysteriaHopMaximum(hopping: Boolean, interval: String, intervalMax: String): String {
        if (!hopping || interval.isBlank()) return ""
        val maxNs = Validation.parseDurationNs(Hysteria.hopDuration(intervalMax)) ?: return ""
        if (maxNs <= 0) return ""
        val minNs = Validation.parseDurationNs(Hysteria.hopDuration(interval)) ?: return ""
        if (minNs == 0L) return vtr(R.string.validation_hysteria_hop_maximum_zero)
        if (minNs > maxNs) return vtr(R.string.validation_hysteria_hop_maximum_shorter)
        return ""
    }

    /** Build leaves the maximum out without an interval, so the core's 30s default applies. */
    @JvmStatic
    fun hysteriaHopMaximumIgnored(hopping: Boolean, interval: String, intervalMax: String): String {
        if (!hopping || interval.isNotBlank()) return ""
        val maxNs = Validation.parseDurationNs(Hysteria.hopDuration(intervalMax)) ?: return ""
        if (maxNs <= 0) return ""
        return vtr(R.string.validation_hysteria_hop_maximum_ignored)
    }

    @JvmStatic
    fun hysteriaV1Speed(upMbps: Long, downMbps: Long): String {
        if (upMbps > 0 && downMbps > 0) return ""
        return vtr(R.string.validation_hysteria_v1_speed, HYSTERIA_V1_DEFAULT_UP_MBPS, HYSTERIA_V1_DEFAULT_DOWN_MBPS)
    }

    /** 0 means the core default (512 / 1200). */
    @JvmStatic
    fun hysteriaGeckoSizes(minSize: Long, maxSize: Long): String {
        if (minSize < 0 || maxSize < 0 || minSize > 2048 || maxSize > 2048) return vtr(R.string.validation_hysteria_gecko_range)
        if ((if (minSize == 0L) 512 else minSize) <= (if (maxSize == 0L) 1200 else maxSize)) return ""
        return vtr(R.string.validation_hysteria_gecko_order)
    }

    @JvmStatic
    fun hysteriaAuth(type: String, auth: String): String {
        if (type != "BASE64" || auth.isEmpty()) return ""
        // The core decodes it as a JSON []byte: strict standard base64, padding included.
        if (auth == auth.trim() && auth.length % 4 == 0 && Validation.base64(auth).isEmpty()) return ""
        return vtr(R.string.validation_hysteria_auth_base64)
    }

    @JvmStatic
    fun hysteriaAuthType(type: String, auth: String): String {
        if (type.isNotEmpty() || auth.isEmpty()) return ""
        return vtr(R.string.validation_hysteria_auth_type)
    }

    @JvmStatic
    fun hysteriaRealm(
        url: String, id: String, stunServers: List<String>, portMapping: Boolean, ipVersion: Int, timeout: String, lifetime: String,
    ): String {
        if (url.isBlank() || id.isBlank() || stunServers.isEmpty()) return vtr(R.string.validation_hysteria_realm_required)
        Validation.url(url, listOf("http", "https"), Empty.Required).let {
            if (it.isNotEmpty()) return vtr(R.string.validation_hysteria_realm_url, it)
        }
        Validation.eachItem(stunServers.joinToString(","), Validation.Split.Comma, Validation.Rules.hostPort(false)).let {
            if (it.isNotEmpty()) return vtr(R.string.validation_hysteria_realm_stun, it)
        }
        if (portMapping && ipVersion != 6) {
            Validation.duration(timeout).let { if (it.isNotEmpty()) return vtr(R.string.validation_hysteria_realm_timeout, it) }
            Validation.duration(lifetime).let { if (it.isNotEmpty()) return vtr(R.string.validation_hysteria_realm_lifetime, it) }
        }
        return ""
    }
}

/** hysteria, v1 and v2 in one class (include/configs/outbounds/hysteria.h, src/configs/outbounds/hysteria.cpp). */
class Hysteria : Outbound("hysteria") {
    @JvmField var protocol_version: String = "1"
    @JvmField var server_ports: MutableList<String> = ArrayList()
    @JvmField var hop_interval: String = ""
    @JvmField var up_mbps: Int = 0
    @JvmField var down_mbps: Int = 0
    @JvmField var obfs: String = ""

    // Hysteria1
    @JvmField var auth_type: String = ""
    @JvmField var auth: String = ""
    @JvmField var recv_window_conn: Int = 0
    @JvmField var recv_window: Int = 0
    @JvmField var disable_mtu_discovery: Boolean = false

    // Hysteria2
    @JvmField var password: String = ""
    @JvmField var min_packet_size: Int = 0
    @JvmField var max_packet_size: Int = 0
    @JvmField var obfs_type: String = "salamander"
    @JvmField var hop_interval_max: String = ""
    @JvmField var bbr_profile: String = ""
    @JvmField var disable_chrome_parrot: Boolean = false

    // Hysteria2 realm (NAT traversal rendezvous); replaces server/server_port/server_ports.
    @JvmField var realm_enabled: Boolean = false
    @JvmField var realm_server_url: String = ""
    @JvmField var realm_token: String = ""
    @JvmField var realm_id: String = ""
    @JvmField var realm_stun_servers: MutableList<String> = ArrayList()
    @JvmField var realm_ip_version: Int = 0
    @JvmField var realm_port_mapping: Boolean = false
    @JvmField var realm_port_mapping_timeout: String = ""
    @JvmField var realm_port_mapping_lifetime: String = ""
    @JvmField var realm_http_client: JsonObject = JsonObject()

    @JvmField var tls: Tls = Tls()
    @JvmField var quic: QuicFields = QuicFields()

    /** hysteria.h:52-55: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    /** hysteria.h:57-59. */
    fun realmActive(): Boolean = realm_enabled && protocol_version == "2"

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun hasQuic(): Boolean = true
    override fun getTls(): Tls = tls
    override fun getQuic(): QuicFields = quic

    override fun serverPortOptional(): Boolean = server_ports.isNotEmpty() || realmActive()

    override fun serverAddressOptional(): Boolean = realmActive()

    /** hysteria::Validate (hysteria.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        val realm = realmActive()
        if (!realm) {
            for ((i, raw) in server_ports.withIndex()) {
                val item = raw.trim()
                fun numbered(message: String) = vtr(R.string.validation_item_echo, (i + 1).toString(), item, message)
                val message = HysteriaChecks.hysteriaPortItem(item)
                if (message.isNotEmpty()) {
                    issues.addIssue(Severity.Error, "server_ports", numbered(message))
                } else {
                    val order = HysteriaChecks.hysteriaPortItemOrder(item)
                    if (order.isNotEmpty()) issues.addIssue(Severity.Warning, "server_ports", numbered(order))
                }
            }
        }
        val hopping = !realm && server_ports.isNotEmpty()
        if (!realm) issues.addIssue(Severity.Error, "hop_interval", HysteriaChecks.hysteriaHopInterval(hop_interval))
        issues.addIssue(Severity.Error, "hop_interval", HysteriaChecks.hysteriaHopMinimum(hopping, hop_interval))

        if (protocol_version == "1") {
            issues.addIssue(Severity.Warning, "up_mbps", HysteriaChecks.hysteriaV1Speed(up_mbps.toLong(), down_mbps.toLong()))
            issues.addIssue(Severity.Error, "auth", HysteriaChecks.hysteriaAuth(auth_type, auth))
            issues.addIssue(Severity.Warning, "auth", HysteriaChecks.hysteriaAuthType(auth_type, auth))
            return
        }

        if (!realm) issues.addIssue(Severity.Error, "hop_interval_max", HysteriaChecks.hysteriaHopInterval(hop_interval_max))
        issues.addIssue(Severity.Error, "hop_interval_max", HysteriaChecks.hysteriaHopMaximum(hopping, hop_interval, hop_interval_max))
        issues.addIssue(Severity.Warning, "hop_interval_max", HysteriaChecks.hysteriaHopMaximumIgnored(hopping, hop_interval, hop_interval_max))
        if (obfs.isNotEmpty()) {
            val type = obfs_type.trim().lowercase()
            val message = Validation.oneOf(type, hysteriaObfsTypes)
            if (message.isNotEmpty()) {
                issues.addIssue(Severity.Error, "obfs.type", message, obfs_type)
            } else if (type == "gecko") {
                issues.addIssue(Severity.Error, "obfs.min_packet_size", HysteriaChecks.hysteriaGeckoSizes(min_packet_size.toLong(), max_packet_size.toLong()))
            }
        }
        if (realm) {
            issues.addIssue(
                Severity.Error, "realm",
                HysteriaChecks.hysteriaRealm(
                    realm_server_url, realm_id, realm_stun_servers, realm_port_mapping, realm_ip_version,
                    realm_port_mapping_timeout, realm_port_mapping_lifetime,
                ),
            )
        }
    }

    /** hysteriaDropBadGeckoSizes (hysteria.cpp): an import resets an unusable pair to the core defaults. */
    private fun dropBadGeckoSizes() {
        if (!ParseDiag.active()) return
        val message = HysteriaChecks.hysteriaGeckoSizes(min_packet_size.toLong(), max_packet_size.toLong())
        if (acceptOptional("obfs.min_packet_size", message, "$min_packet_size/$max_packet_size")) return
        min_packet_size = 0
        max_packet_size = 0
    }

    /**
     * Android only: the desktop drops a bare-number hop interval as an invalid duration. Read as seconds it is kept
     * only while the hop checks pass; otherwise it is dropped with a warning, so the profile imports with the core
     * default instead of being refused. The bare maximum goes first when the pair is out of order.
     */
    private fun dropBadBareHops(intervalField: String, bareInterval: String?, bareMax: String?) {
        if (!ParseDiag.active() || (bareInterval == null && bareMax == null)) return
        val hopping = !realmActive() && server_ports.isNotEmpty()
        if (bareInterval != null &&
            !acceptOptional(intervalField, HysteriaChecks.hysteriaHopMinimum(hopping, hop_interval), bareInterval)
        ) hop_interval = ""
        if (protocol_version != "2") return
        val message = HysteriaChecks.hysteriaHopMaximum(hopping, hop_interval, hop_interval_max)
        if (message.isEmpty()) return
        if (bareMax != null) {
            acceptOptional("hop_interval_max", message, bareMax)
            hop_interval_max = ""
        } else if (bareInterval != null) {
            acceptOptional(intervalField, message, bareInterval)
            hop_interval = ""
        }
    }

    /**
     * hysteria.cpp: the official hysteria2 URI pins a certificate's SHA-256 in hex (its normalizeCertHash strips ':'
     * and '-'), sing-box's certificate_sha256 holds it in base64.
     */
    private fun parsePinSha256(value: String) {
        var pinned = false
        for (raw in QtStrings.splitSkipEmpty(value, ",")) {
            val pin = raw.trim()
            val hex = pin.replace(":", "").replace("-", "").trim()
            val hash = when {
                Validation.hex(hex, 64, 64, true, Empty.Required).isEmpty() ->
                    Base64Strict.encode(ByteArray(32) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
                Validation.base64Key(pin, 32).isEmpty() || Validation.base64Key(pin, 32, urlAlphabet = false, padded = false).isEmpty() ->
                    Base64Strict.encode(Base64Strict.decodeLenient(pin))
                else -> {
                    ParseDiag.error("pinSHA256", vtr(R.string.validation_hysteria_pin_format), pin)
                    continue
                }
            }
            if (!tls.certificate_sha256.contains(hash)) tls.certificate_sha256.add(hash)
            pinned = true
        }
        // The official client accepts a pin of any certificate in the chain; sing-box checks only the server's own.
        if (pinned) ParseDiag.warn("pinSHA256", vtr(R.string.validation_hysteria_pin_leaf))
    }

    /** hysteria::ParseFromLink (hysteria.cpp). Android only: a bare-number hop interval is seconds ([hopDuration]). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) {
            if (!url.invalidPortOnly) return false
            // The port list is whatever follows QUrl::authority() in the link text; the first occurrence is searched,
            // so a recoded user-info, a normalised host or a host that also occurs in the scheme make the desktop
            // silently drop the list (reproduced as is)
            val authority = prettyAuthority(link, url)
            val portStartIndex = link.indexOf(authority) + authority.length
            val match = if (portStartIndex <= link.length) PORT_LIST.find(link.substring(portStartIndex)) else null
            if (match != null) server_ports = portsToPorts(QtStrings.splitSkipEmpty(match.groupValues[1], ","))
        }
        val q = url.query
        super.parseFromLink(url)
        var bareMax: String? = null
        if (url.scheme == "hysteria") {
            protocol_version = "1"
            if (q.has("protocol")) rejectTransport("protocol", q.value("protocol"))
            if (q.has("obfsParam")) obfs = q.valueFully("obfsParam")
            if (q.has("auth")) {
                auth = q.value("auth")
                auth_type = "STRING"
            }
            if (q.has("recv_window_conn")) recv_window_conn = QtStrings.toInt(q.value("recv_window_conn"))
            if (q.has("recv_window")) recv_window = QtStrings.toInt(q.value("recv_window"))
            if (q.has("disable_mtu_discovery")) disable_mtu_discovery = q.value("disable_mtu_discovery") == "true"
        } else {
            protocol_version = "2"
            password = if (url.password.isEmpty()) url.userName else url.userName + ":" + url.password
            if (q.has("obfs-password")) obfs = q.valueFully("obfs-password")
            obfs_type = when {
                q.has("obfs") -> q.value("obfs").trim().lowercase()
                q.has("min_packet_size") || q.has("max_packet_size") -> "gecko"
                else -> "salamander"
            }
            if (q.has("min_packet_size")) {
                val value = q.value("min_packet_size").trim()
                if (keep("min_packet_size", value, Validation.Rules.int(0, 2048))) min_packet_size = QtStrings.toInt(value)
            }
            if (q.has("max_packet_size")) {
                val value = q.value("max_packet_size").trim()
                if (keep("max_packet_size", value, Validation.Rules.int(0, 2048))) max_packet_size = QtStrings.toInt(value)
            }
            if (obfs_type == "gecko") dropBadGeckoSizes()
            if (q.has("hop_interval_max")) {
                val raw = q.value("hop_interval_max").trim()
                val value = hopDuration(raw)
                if (keep("hop_interval_max", value, Validation.Rules.duration())) {
                    hop_interval_max = value
                    if (value != raw) bareMax = raw
                }
            }
            if (q.has("bbr_profile")) {
                val value = q.value("bbr_profile").trim()
                if (keep("bbr_profile", value, Validation.Rules.oneOf(hysteriaBBRProfiles))) bbr_profile = value
            }
            if (q.has("disable_chrome_parrot")) disable_chrome_parrot = q.value("disable_chrome_parrot") == "true"
        }
        // The URI scheme says a bare number, but panels commonly write "55Mbps".
        if (q.has("upmbps")) up_mbps = bandwidth("upmbps", q.valueFully("upmbps"))
        if (q.has("downmbps")) down_mbps = bandwidth("downmbps", q.valueFully("downmbps"))
        var bareInterval: String? = null
        if (q.has("hop_interval")) {
            val raw = q.value("hop_interval").trim()
            val value = hopDuration(raw)
            if (keep("hop_interval", value, Validation.Rules.duration())) {
                hop_interval = value
                if (value != raw) bareInterval = raw
            }
        }
        if (q.has("mport")) server_ports = portsToPorts(QtStrings.splitSkipEmpty(q.valueFully("mport"), ","))
        dropBadBareHops("hop_interval", bareInterval, bareMax)
        tls.parseFromLink(url)
        tls.enabled = true
        if (q.has("pinSHA256")) parsePinSha256(q.valueFully("pinSHA256"))
        quic.parseFromLink(url)
        if (serverPort == 0 && server_ports.isEmpty()) serverPort = 443
        return true
    }

    /**
     * hysteria::ParseFromClash (hysteria.cpp): `hysteria` is v1 and `hysteria2` v2; bandwidth strings accept the
     * mihomo units. Android also reads mihomo's `hop-interval` (seconds).
     */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        protocol_version = when (proxy.type) {
            "hysteria" -> "1"
            "hysteria2" -> "2"
            else -> return false
        }
        baseParseFromClash(proxy)
        val ports = proxy.string("ports")
        if (ports.isNotEmpty()) server_ports = portsToPorts(ports.split(CLASH_PORT_SEPARATOR).filter { it.isNotEmpty() })
        val rawHop = proxy.string("hop-interval").trim()
        val hopInterval = hopDuration(rawHop)
        if (hopInterval.isNotEmpty() && keep("hop-interval", hopInterval, Validation.Rules.duration())) {
            hop_interval = hopInterval
            dropBadBareHops("hop-interval", rawHop.takeIf { it != hopInterval }, null)
        }
        val up = proxy.string("up")
        if (up.isNotEmpty()) up_mbps = bandwidth("up", up)
        val down = proxy.string("down")
        if (down.isNotEmpty()) down_mbps = bandwidth("down", down)
        if (protocol_version == "1") {
            rejectTransport("protocol", proxy.string("protocol"))
            val authString = proxy.string("auth-str").ifEmpty { proxy.string("auth_str") }
            if (authString.isNotEmpty()) {
                auth = authString
                auth_type = "STRING"
            }
            val clashObfs = proxy.string("obfs")
            if (clashObfs.isNotEmpty()) obfs = clashObfs
            val window = proxy.int("recv-window").takeIf { it > 0 } ?: proxy.int("recv_window")
            if (window > 0) recv_window = window
            val windowConn = proxy.int("recv-window-conn").takeIf { it > 0 } ?: proxy.int("recv_window_conn")
            if (windowConn > 0) recv_window_conn = windowConn
            disable_mtu_discovery = proxy.bool("disable_mtu_discovery")
        } else {
            val clashPassword = proxy.string("password")
            if (clashPassword.isNotEmpty()) password = clashPassword
            val clashObfs = proxy.string("obfs")
            if (clashObfs.isNotEmpty()) obfs_type = clashObfs.trim().lowercase()
            val obfsPassword = proxy.string("obfs-password")
            if (obfsPassword.isNotEmpty()) obfs = obfsPassword
        }
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /**
     * hysteria::ParseFromJson (hysteria.cpp): `type` picks the version (`hysteria` / `hysteria2`); the QUIC keys are
     * flat. Android only: a hop interval given as a number or a bare-number string is seconds ([hopDuration]).
     */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        protocol_version = when (obj.string("type")) {
            "hysteria" -> "1"
            "hysteria2" -> "2"
            else -> return false
        }
        super.parseFromJson(obj)
        // LegacyListable: a bare string is a single range.
        if (obj.contains("server_ports")) server_ports = jsonStringList(obj["server_ports"], "server_ports")
        var bareInterval: String? = null
        var bareMax: String? = null
        if (obj.contains("hop_interval")) {
            val raw = obj.variantString("hop_interval").trim()
            val value = hopDuration(raw)
            if (keep("hop_interval", value, Validation.Rules.duration())) {
                hop_interval = value
                if (value != raw) bareInterval = raw
            }
        }
        if (obj.contains("up_mbps")) up_mbps = obj.int("up_mbps")
        if (obj.contains("down_mbps")) down_mbps = obj.int("down_mbps")
        if (protocol_version == "1") {
            // The core prefers up/down over up_mbps/down_mbps when they are set.
            for (key in arrayOf("up", "down")) {
                if (!obj.contains(key)) continue
                val value = obj[key]
                val mbps = jsonSpeedMbps(value)
                if (mbps == null) {
                    val shown = when (value) {
                        is String -> value
                        is Long, is Double -> JsonValues.variantToString(value)
                        else -> "0"
                    }
                    ParseDiag.warn(key, vtr(R.string.validation_hysteria_json_speed), shown)
                } else if (mbps > 0) {
                    if (key == "up") up_mbps = mbps else down_mbps = mbps
                }
            }
            if (obj.contains("obfs")) obfs = obj.string("obfs")
            if (obj.contains("auth")) {
                auth = obj.string("auth")
                auth_type = "BASE64"
            }
            if (obj.contains("auth_str")) {
                auth = obj.string("auth_str")
                auth_type = "STRING"
            }
            if (obj.contains("recv_window_conn")) recv_window_conn = obj.int("recv_window_conn")
            if (obj.contains("recv_window")) recv_window = obj.int("recv_window")
            if (obj.contains("disable_mtu_discovery")) disable_mtu_discovery = obj.bool("disable_mtu_discovery")
        } else {
            if (obj.contains("obfs")) {
                val obfsObj = obj.obj("obfs")
                if (obfsObj.contains("password")) obfs = obfsObj.string("password")
                obfs_type = if (obfsObj.contains("type")) obfsObj.string("type").trim().lowercase() else "salamander"
                if (obfsObj.contains("min_packet_size")) {
                    val value = obfsObj.int("min_packet_size")
                    if (keep("obfs.min_packet_size", value.toString(), Validation.Rules.int(0, 2048))) min_packet_size = value
                }
                if (obfsObj.contains("max_packet_size")) {
                    val value = obfsObj.int("max_packet_size")
                    if (keep("obfs.max_packet_size", value.toString(), Validation.Rules.int(0, 2048))) max_packet_size = value
                }
                if (obfs_type == "gecko") dropBadGeckoSizes()
            }
            if (obj.contains("obfsPassword")) obfs = obj.string("obfsPassword")
            if (obj.contains("password")) password = obj.string("password")
            if (obj.contains("hop_interval_max")) {
                val raw = obj.variantString("hop_interval_max").trim()
                val value = hopDuration(raw)
                if (keep("hop_interval_max", value, Validation.Rules.duration())) {
                    hop_interval_max = value
                    if (value != raw) bareMax = raw
                }
            }
            if (obj.contains("bbr_profile")) {
                val value = obj.string("bbr_profile").trim()
                if (keep("bbr_profile", value, Validation.Rules.oneOf(hysteriaBBRProfiles))) bbr_profile = value
            }
            if (obj.contains("disable_chrome_parrot")) disable_chrome_parrot = obj.bool("disable_chrome_parrot")
            if (obj.isObject("realm")) {
                val realmObj = obj.obj("realm")
                realm_enabled = true
                realm_server_url = realmObj.string("server_url").trim()
                realm_token = realmObj.string("token")
                realm_id = realmObj.string("realm_id")
                // Listable: the core accepts a bare string as well as an array.
                if (realmObj.isArray("stun_servers")) realm_stun_servers = realmObj.array("stun_servers").strings()
                else if (realmObj.contains("stun_servers")) realm_stun_servers = mutableListOf(realmObj.string("stun_servers"))
                realm_stun_servers = realm_stun_servers.mapTo(ArrayList()) { it.trim() }
                val ipVersion = realmObj.int("ip_version")
                if (keep("realm.ip_version", ipVersion.toString(), Validation.Rules.oneOf(listOf("0", "4", "6")))) realm_ip_version = ipVersion
                val portMapping = realmObj.obj("port_mapping")
                realm_port_mapping = portMapping.bool("enabled")
                val timeout = portMapping.string("timeout").trim()
                if (keep("realm.port_mapping.timeout", timeout, Validation.Rules.duration())) realm_port_mapping_timeout = timeout
                val lifetime = portMapping.string("lifetime").trim()
                if (keep("realm.port_mapping.lifetime", lifetime, Validation.Rules.duration())) realm_port_mapping_lifetime = lifetime
                realm_http_client = realmObj.obj("http_client").copy()
            }
        }
        dropBadBareHops("hop_interval", bareInterval, bareMax)
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        quic.parseFromJson(obj)
        return true
    }

    /** hysteria.cpp:264-331: no link form carries realm; a port list replaces the port right after the authority. */
    override fun exportToLink(): String {
        if (realmActive()) return ""
        val v1 = protocol_version == "1"
        val url = LinkBuilder(if (v1) "hysteria" else "hysteria2")
        url.host = server
        if (name.isNotEmpty()) url.fragment = name
        var linkUser: String? = null
        var linkPassword: String? = null
        if (v1) {
            if (obfs.isNotEmpty()) url.addQueryItemPercentEncoded("obfsParam", LinkCodec.percentEncodeAll(obfs))
            if (auth_type == "STRING" && auth.isNotEmpty()) url.addQueryItem("auth", auth)
            if (recv_window_conn > 0) url.addQueryItem("recv_window_conn", recv_window_conn.toString())
            if (recv_window > 0) url.addQueryItem("recv_window", recv_window.toString())
            if (disable_mtu_discovery) url.addQueryItem("disable_mtu_discovery", "true")
        } else {
            if (password.contains(":")) {
                linkUser = QtStrings.substrBefore(password, ":")
                linkPassword = QtStrings.substrAfter(password, ":")
                url.setUserName(linkUser)
                url.setPassword(linkPassword)
            } else if (password.isNotEmpty()) {
                // a profile from a link or JSON without a password holds a null QString, which adds no user-info
                linkUser = password
                url.setUserName(password)
            }
            if (obfs.isNotEmpty()) {
                url.addQueryItemPercentEncoded("obfs", LinkCodec.percentEncodeAll(obfs_type))
                url.addQueryItemPercentEncoded("obfs-password", LinkCodec.percentEncodeAll(obfs))
                if (min_packet_size > 0) url.addQueryItem("min_packet_size", min_packet_size.toString())
                if (max_packet_size > 0) url.addQueryItem("max_packet_size", max_packet_size.toString())
            }
            if (hop_interval_max.isNotEmpty()) url.addQueryItem("hop_interval_max", hopDuration(hop_interval_max))
            if (bbr_profile.isNotEmpty()) url.addQueryItem("bbr_profile", bbr_profile)
            if (disable_chrome_parrot) url.addQueryItem("disable_chrome_parrot", "true")
        }
        if (up_mbps > 0) url.addQueryItem("upmbps", up_mbps.toString())
        if (down_mbps > 0) url.addQueryItem("downmbps", down_mbps.toString())
        if (hop_interval.isNotEmpty()) url.addQueryItem("hop_interval", hopDuration(hop_interval))
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(quic.exportToLink())
        url.addQueryItems(baseLinkQuery())
        if (server_ports.isEmpty()) {
            url.port = serverPort
            return url.build()
        }
        // hysteria.cpp:314-325: serialised without a port, then ":" + list inserted after the first occurrence of the
        // FullyEncoded authority (QString::insert past the end pads with spaces)
        var result = url.build()
        val authority = fullyEncodedAuthority(linkUser, linkPassword)
        val portStartIndex = result.indexOf(authority) + authority.length
        if (portStartIndex > result.length) result = result.padEnd(portStartIndex, ' ')
        val portList = server_ports.joinToString(",") { it.replace(":", "-") }
        return result.substring(0, portStartIndex) + ":" + portList + result.substring(portStartIndex)
    }

    /** hysteria.cpp:333-369: `tls` is always written, v2 obfs always carries both packet sizes, realm replaces the server keys. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = if (protocol_version == "1") "hysteria" else "hysteria2"
        obj.merge(baseExportToJson())
        if (server_ports.isNotEmpty()) obj["server_ports"] = JsonValues.stringArray(portsToPorts(server_ports))
        if (hop_interval.isNotEmpty()) obj["hop_interval"] = hopDuration(hop_interval)
        if (up_mbps > 0) obj["up_mbps"] = up_mbps
        if (down_mbps > 0) obj["down_mbps"] = down_mbps
        if (protocol_version == "1") {
            if (obfs.isNotEmpty()) obj["obfs"] = obfs
            if (auth.isNotEmpty()) {
                if (auth_type == "BASE64") obj["auth"] = auth
                if (auth_type == "STRING") obj["auth_str"] = auth
            }
            if (recv_window_conn > 0) obj["recv_window_conn"] = recv_window_conn
            if (recv_window > 0) obj["recv_window"] = recv_window
            if (disable_mtu_discovery) obj["disable_mtu_discovery"] = disable_mtu_discovery
        } else {
            if (obfs.isNotEmpty()) {
                obj["obfs"] = jsonObjectOf(
                    "type" to obfs_type,
                    "password" to obfs,
                    "min_packet_size" to min_packet_size,
                    "max_packet_size" to max_packet_size,
                )
            }
            if (password.isNotEmpty()) obj["password"] = password
            if (hop_interval_max.isNotEmpty()) obj["hop_interval_max"] = hopDuration(hop_interval_max)
            if (bbr_profile.isNotEmpty()) obj["bbr_profile"] = bbr_profile
            if (disable_chrome_parrot) obj["disable_chrome_parrot"] = true
            if (realmActive()) applyRealmObject(obj)
        }
        obj["tls"] = tls.exportToJson()
        obj.merge(quic.exportToJson())
        return obj
    }

    /** hysteria.cpp:371-389. */
    override fun exportIdentity(): JsonObject {
        if (realmActive()) {
            // outbound::ExportIdentity() falls back to the whole config when there is no server.
            val obj = jsonObjectOf(
                "protocol_version" to protocol_version,
                "realm_server_url" to realm_server_url,
                "realm_id" to realm_id,
            )
            val t = tls.exportIdentity()
            if (t.isNotEmpty()) obj["tls"] = t
            return obj
        }
        val obj = super.exportIdentity()
        obj["protocol_version"] = protocol_version
        if (server_ports.isNotEmpty()) obj["server_ports"] = server_ports.joinToString(",")
        if (obfs.isNotEmpty()) obj["obfs"] = true
        return obj
    }

    /** hysteria::Build (hysteria.cpp): under realm the hop intervals are unused, yet the core would still parse them. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = if (protocol_version == "1") "hysteria" else "hysteria2"
        obj.merge(baseBuild(ctx))
        val realm = realmActive()
        if (server_ports.isNotEmpty()) obj["server_ports"] = JsonValues.stringArray(portsToPorts(server_ports))
        if (hop_interval.isNotEmpty() && !realm) obj["hop_interval"] = hopDuration(hop_interval)
        val v1 = protocol_version == "1"
        if (up_mbps > 0 || v1) obj["up_mbps"] = if (up_mbps > 0) up_mbps else HYSTERIA_V1_DEFAULT_UP_MBPS
        if (down_mbps > 0 || v1) obj["down_mbps"] = if (down_mbps > 0) down_mbps else HYSTERIA_V1_DEFAULT_DOWN_MBPS
        if (v1) {
            if (obfs.isNotEmpty()) obj["obfs"] = obfs
            if (auth.isNotEmpty()) {
                if (auth_type == "BASE64") obj["auth"] = auth
                if (auth_type == "STRING") obj["auth_str"] = auth
            }
            if (recv_window_conn > 0) obj["recv_window_conn"] = recv_window_conn
            if (recv_window > 0) obj["recv_window"] = recv_window
            if (disable_mtu_discovery) obj["disable_mtu_discovery"] = disable_mtu_discovery
        } else {
            if (obfs.isNotEmpty()) {
                val obfsType = obfs_type.trim().lowercase()
                obj["obfs"] = when (obfsType) {
                    "salamander" -> jsonObjectOf("type" to obfsType, "password" to obfs)
                    "gecko" -> jsonObjectOf(
                        "type" to obfsType,
                        "password" to obfs,
                        "min_packet_size" to min_packet_size,
                        "max_packet_size" to max_packet_size,
                    )
                    else -> jsonObjectOf("type" to "salamander", "password" to obfs)
                }
            }
            if (password.isNotEmpty()) obj["password"] = password
            // sing-box randomizes the hop interval within [hop_interval, hop_interval_max]; it rejects a max without a min.
            if (hop_interval_max.isNotEmpty() && hop_interval.isNotEmpty() && !realm) obj["hop_interval_max"] = hopDuration(hop_interval_max)
            // An unknown profile makes sing-box refuse to start, so drop anything a subscription made up.
            if (bbr_profile in hysteriaBBRProfiles) obj["bbr_profile"] = bbr_profile
            if (disable_chrome_parrot) obj["disable_chrome_parrot"] = true
            if (realm) applyRealmObject(obj)
        }
        obj["tls"] = tls.build(ctx)
        obj.merge(quic.build(ctx))
        return BuildResult(obj)
    }

    /** hysteria.cpp:445-451: QUrl(realm_server_url).host(), the whole string when that is empty. */
    override fun displayAddress(): String {
        if (!realmActive()) return super.displayAddress()
        var host = prettyHost(LinkParser.parse(realm_server_url).host, bracketed = false)
        if (host.isEmpty()) host = realm_server_url
        return if (realm_id.isEmpty()) host else "$realm_id@$host"
    }

    /** hysteria.cpp:453-456. */
    override fun displayType(): String = "Hysteria"

    /** hysteria.cpp:458-461. */
    override fun security(): SecurityInfo = securityFromTls("QUIC")

    /** hysteria.cpp:26-44: the core only checks realm's required fields when it first dials, so they are emitted unconditionally. */
    private fun buildRealmObject(): JsonObject {
        val realm = JsonObject()
        realm["server_url"] = realm_server_url
        if (realm_token.isNotEmpty()) realm["token"] = realm_token
        realm["realm_id"] = realm_id
        if (realm_stun_servers.isNotEmpty()) realm["stun_servers"] = JsonValues.stringArray(realm_stun_servers)
        if (realm_ip_version == 4 || realm_ip_version == 6) realm["ip_version"] = realm_ip_version
        // Hole punching over IPv6 has no gateway mapping to make; the core rejects the pair.
        if (realm_port_mapping && realm_ip_version != 6) {
            val portMapping = jsonObjectOf("enabled" to true)
            if (realm_port_mapping_timeout.isNotEmpty()) portMapping["timeout"] = realm_port_mapping_timeout
            if (realm_port_mapping_lifetime.isNotEmpty()) portMapping["lifetime"] = realm_port_mapping_lifetime
            realm["port_mapping"] = portMapping
        }
        if (realm_http_client.isNotEmpty()) realm["http_client"] = realm_http_client.copy()
        return realm
    }

    /** hysteria.cpp:46-53: realm replaces the server address; the core refuses a config carrying both. */
    private fun applyRealmObject(obj: JsonObject) {
        obj.remove("server")
        obj.remove("server_port")
        obj.remove("server_ports")
        obj["realm"] = buildRealmObject()
    }

    /** QUrl::authority(QUrl::FullyEncoded) of the export URL, which has no port. */
    private fun fullyEncodedAuthority(linkUser: String?, linkPassword: String?): String {
        val sb = StringBuilder()
        if (linkUser != null || linkPassword != null) {
            sb.append(LinkCodec.encodeUserName(linkUser ?: ""))
            if (linkPassword != null) sb.append(':').append(LinkCodec.encodePassword(linkPassword))
            sb.append('@')
        }
        sb.append(Hosts.formatForUrl(Hosts.normalizeHost(server, percentDecode = false) ?: ""))
        return sb.toString()
    }

    companion object {
        private val PORT_LIST = Regex("^:([\\d,\\-]+)")
        private val PORT_RANGE_SEPARATOR = Regex("[:-]")
        private const val HEX = "0123456789ABCDEF"

        // Escapes QUrl::authority() prints decoded (PrettyDecoded user-info): unreserved, space, the unsafe set except
        // the backtick, and the gen-delims that cannot occur unescaped in an authority. Everything else, in particular
        // ':' '@' '[' ']' '%' '`', the sub-delims and control characters, stays percent-encoded (upper-case hex).
        private const val DECODED_IN_AUTHORITY = " -._~\"<>\\^{|}/?#"

        private val CLASH_PORT_SEPARATOR = Regex("[,/]")
        private val CLASH_BANDWIDTH = Regex("^(\\d+(?:\\.\\d+)?)\\s*([KMGTkmgt]?)(?:([bB])(?:ps|/s)?|ps)?$")
        private const val SPEED_PREFIXES = "KMGTPE"

        /** hysteriaKeep: without a diagnostics scope (DB loads, clones) a parse keeps every value as it always did. */
        private fun keep(field: String, value: String, rule: Rule): Boolean =
            !ParseDiag.active() || acceptOptional(field, rule(value), value)

        /** hysteriaRejectTransport (hysteria.cpp). */
        private fun rejectTransport(field: String, transport: String) {
            val t = transport.trim()
            if (t.isEmpty() || t.equals("udp", ignoreCase = true)) return
            ParseDiag.error(field, vtr(R.string.validation_hysteria_transport), transport)
        }

        /** hysteriaBandwidth (hysteria.cpp). */
        private fun bandwidth(field: String, text: String): Int {
            val mbps = clashBandwidthToMbps(text)
            if (mbps == 0 && Validation.toInt(text) != 0L) ParseDiag.warn(field, vtr(R.string.validation_hysteria_clash_bandwidth), text)
            return mbps
        }

        /** hysteriaSpeedUnit: sing byteformats NetworkBytesCompat units, where only "<prefix>bps" counts bits. */
        private fun speedUnit(unit: String): Double {
            if (unit == "Bps") return 1.0
            if (unit.length == 4 && unit.endsWith("ps") && SPEED_PREFIXES.indexOf(unit[0]) >= 0) {
                val scale = 1000.0.pow(SPEED_PREFIXES.indexOf(unit[0]) + 1)
                if (unit[1] == 'B') return scale
                if (unit[1] == 'b') return scale / 8
            }
            val lower = unit.lowercase()
            if (lower == "b") return 1.0
            val power = if (lower.isEmpty()) 0 else SPEED_PREFIXES.indexOf(lower[0].uppercaseChar()) + 1
            if (power == 0) return 0.0
            val rest = lower.substring(1)
            if (rest.isEmpty() || rest == "b") return 1000.0.pow(power)
            if (rest == "i" || rest == "ib") return 1024.0.pow(power)
            return 0.0
        }

        /** hysteriaJsonSpeedMbps: a JSON number is bytes per second, a string digits followed by a unit; null when unusable. */
        private fun jsonSpeedMbps(value: Any?): Int? {
            val bytesPerSecond: Double = when (value) {
                is Long, is Double -> {
                    val d = (value as Number).toDouble()
                    if (d < 0 || d != floor(d)) return null
                    d
                }
                is String -> {
                    var digits = 0
                    while (digits < value.length && value[digits] in '0'..'9') digits++
                    if (digits == 0 || digits == value.length) return null
                    val number = value.substring(0, digits).toULongOrNull() ?: return null
                    val unit = speedUnit(value.substring(digits).trim())
                    if (unit == 0.0) return null
                    number.toDouble() * unit
                }
                else -> return null
            }
            if (bytesPerSecond <= 0) return 0
            val mbps = minOf(bytesPerSecond / 125000.0, Int.MAX_VALUE.toDouble())
            return maxOf(1L, Math.round(mbps)).toInt()
        }

        /**
         * Android only (#96): a bare number is seconds, as mihomo's `hop-interval` and the editor label have it;
         * sing-box parses the hop intervals as Go durations and refuses to start on a number without a unit.
         */
        @JvmStatic
        fun hopDuration(value: String): String {
            val trimmed = value.trim()
            return if (trimmed.isNotEmpty() && trimmed.all { it in '0'..'9' }) trimmed + "s" else value
        }

        /**
         * clashBandwidthToMbps (hysteria.cpp): a bare number is Mbps; only the final b/B tells bits from bytes, so
         * "100 Mbps" is 100 and "50 MB/s" is 400. 0 when unusable.
         */
        @JvmStatic
        fun clashBandwidthToMbps(text: String): Int {
            val match = CLASH_BANDWIDTH.find(text.trim()) ?: return 0
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0
            if (!value.isFinite()) return 0
            val prefix = match.groupValues[2].uppercase()
            val unit = match.groupValues[3]
            var mbps = value
            if (prefix.isNotEmpty() || unit.isNotEmpty()) {
                var scale = when (prefix) {
                    "K" -> 1e-3
                    "M" -> 1.0
                    "G" -> 1e3
                    "T" -> 1e6
                    else -> 1e-6
                }
                if (unit == "B") scale *= 8.0
                mbps = value * scale
            }
            if (mbps <= 0) return 0
            return maxOf(1L, Math.round(minOf(mbps, Int.MAX_VALUE.toDouble()))).toInt()
        }

        /** portsToPorts (hysteria.cpp): "a-b" and "a:b" become "a:b", anything else "v:v"; blank items (every port) are dropped. */
        @JvmStatic
        fun portsToPorts(ports: List<String>): MutableList<String> {
            val result = ArrayList<String>(ports.size)
            for (raw in ports) {
                val v = raw.trim()
                if (v.isEmpty()) continue
                val range = v.split(PORT_RANGE_SEPARATOR)
                result.add(if (range.size == 2) "${range[0]}:${range[1]}" else "$v:$v")
            }
            return result
        }

        /**
         * QUrl::authority() of a link whose only error is the port: the user-info as Qt re-prints it inside an authority,
         * '@' whenever user-info was present, and the host in its Unicode form; no port.
         */
        private fun prettyAuthority(link: String, url: ParsedLink): String {
            val hierStart = if (url.scheme.isNotEmpty()) url.scheme.length + 1 else 0
            val sb = StringBuilder()
            if (link.startsWith("//", hierStart)) {
                var end = link.length
                for (i in hierStart + 2 until link.length) {
                    val c = link[i]
                    if (c == '/' || c == '?' || c == '#') {
                        end = i
                        break
                    }
                }
                val auth = link.substring(hierStart + 2, end)
                val at = auth.indexOf('@')
                if (at >= 0) {
                    val userInfo = auth.substring(0, at)
                    val delim = userInfo.indexOf(':')
                    sb.append(recodeUserInfo(if (delim >= 0) userInfo.substring(0, delim) else userInfo, encodeColon = true))
                    if (delim >= 0) sb.append(':').append(recodeUserInfo(userInfo.substring(delim + 1), encodeColon = false))
                    sb.append('@')
                }
            }
            sb.append(prettyHost(url.host, bracketed = true))
            return sb.toString()
        }

        private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

        private fun escapeByte(s: String, i: Int): Int {
            if (i + 2 >= s.length || s[i] != '%') return -1
            val h = Character.digit(s[i + 1], 16)
            val l = Character.digit(s[i + 2], 16)
            return if (h < 0 || l < 0) -1 else (h shl 4) or l
        }

        private fun appendEscape(sb: StringBuilder, byte: Int) {
            sb.append('%').append(HEX[(byte shr 4) and 0xF]).append(HEX[byte and 0xF])
        }

        // One raw user-info component (TolerantMode) to its PrettyDecoded-in-authority form; the ':' delimiter is
        // encoded in the user name only (userNameInAuthority vs passwordInAuthority in qurl.cpp)
        private fun recodeUserInfo(raw: String, encodeColon: Boolean): String {
            val s = LinkCodec.tolerant(raw)
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val b = escapeByte(s, i)
                if (b < 0) {
                    if (c == '[' || c == ']' || c == '`' || c.code < 0x20 || c.code == 0x7F || (encodeColon && c == ':')) appendEscape(sb, c.code)
                    else sb.append(c)
                    i++
                    continue
                }
                if (b < 0x80) {
                    val ch = b.toChar()
                    if (isAlnum(ch) || DECODED_IN_AUTHORITY.indexOf(ch) >= 0) sb.append(ch) else appendEscape(sb, b)
                    i += 3
                    continue
                }
                val consumed = decodeUtf8Escapes(s, i, b, sb)
                if (consumed > 0) {
                    i += consumed
                } else {
                    appendEscape(sb, b)
                    i += 3
                }
            }
            return sb.toString()
        }

        // A run of consecutive escapes forming one strictly valid UTF-8 sequence is decoded; returns the characters consumed
        private fun decodeUtf8Escapes(s: String, start: Int, lead: Int, sb: StringBuilder): Int {
            val need = when (lead) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> return 0
            }
            val bytes = ByteArray(need)
            for (k in 0 until need) {
                val b = escapeByte(s, start + 3 * k)
                if (b < 0) return 0
                bytes[k] = b.toByte()
            }
            return try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                sb.append(decoder.decode(ByteBuffer.wrap(bytes)))
                3 * need
            } catch (e: CharacterCodingException) {
                0
            }
        }

        /**
         * The host as QUrl::host() / QUrl::authority() print it (PrettyDecoded): reg-names in Unicode (ACE labels
         * decoded), an IPv6 literal with its brackets inside an authority and its zone id decoded outside of one.
         */
        private fun prettyHost(hostFullyEncoded: String, bracketed: Boolean): String {
            if (hostFullyEncoded.contains(':')) {
                return if (bracketed) "[$hostFullyEncoded]" else hostFullyEncoded.replace("%25", "%")
            }
            if (!hostFullyEncoded.contains("xn--")) return hostFullyEncoded
            return hostFullyEncoded.split('.').joinToString(".") { label ->
                if (label.startsWith("xn--")) punycodeDecode(label.substring(4)) ?: label else label
            }
        }

        // RFC 3492 section 6.2; null for input the algorithm rejects, in which case Qt keeps the ACE label
        private fun punycodeDecode(input: String): String? {
            val output = ArrayList<Int>()
            var n = 128
            var i = 0
            var bias = 72
            val delimiter = input.lastIndexOf('-')
            val basicEnd = if (delimiter > 0) delimiter else 0
            for (j in 0 until basicEnd) {
                val c = input[j]
                if (c.code >= 0x80) return null
                output.add(c.code)
            }
            var pos = if (delimiter > 0) delimiter + 1 else 0
            while (pos < input.length) {
                val oldI = i
                var w = 1
                var k = 36
                while (true) {
                    if (pos >= input.length) return null
                    val c = input[pos++]
                    val digit = when (c) {
                        in 'a'..'z' -> c - 'a'
                        in 'A'..'Z' -> c - 'A'
                        in '0'..'9' -> c - '0' + 26
                        else -> return null
                    }
                    if (digit > (Int.MAX_VALUE - i) / w) return null
                    i += digit * w
                    val t = if (k <= bias) 1 else if (k >= bias + 26) 26 else k - bias
                    if (digit < t) break
                    if (w > Int.MAX_VALUE / (36 - t)) return null
                    w *= 36 - t
                    k += 36
                }
                val count = output.size + 1
                bias = punycodeAdapt(i - oldI, count, oldI == 0)
                if (i / count > Int.MAX_VALUE - n) return null
                n += i / count
                i %= count
                if (n > 0x10FFFF || (n in 0xD800..0xDFFF)) return null
                output.add(i, n)
                i++
            }
            val sb = StringBuilder()
            for (cp in output) sb.appendCodePoint(cp)
            return sb.toString()
        }

        private fun punycodeAdapt(delta0: Int, numPoints: Int, firstTime: Boolean): Int {
            var delta = if (firstTime) delta0 / 700 else delta0 / 2
            delta += delta / numPoints
            var k = 0
            while (delta > ((36 - 1) * 26) / 2) {
                delta /= 36 - 1
                k += 36
            }
            return k + (36 - 1 + 1) * delta / (delta + 38)
        }
    }
}
