package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** mieru.h mieruTransports. */
val mieruTransports = listOf("TCP", "UDP")

/** mieru.h mieruMultiplexing. */
val mieruMultiplexing = listOf("", "MULTIPLEXING_OFF", "MULTIPLEXING_LOW", "MULTIPLEXING_MIDDLE", "MULTIPLEXING_HIGH")

/** mieruMultiplexingLevels: the core also accepts the protobuf's MULTIPLEXING_DEFAULT. */
private val mieruMultiplexingLevels = mieruMultiplexing + "MULTIPLEXING_DEFAULT"

/** The Checks of mieru.h. */
object MieruChecks {
    private val RANGE_STYLE = Validation.PortRangeStyle(sep = Validation.RangeSep.Dash, checkOrder = false)

    /** mieruPortRange: validateMieruOptions wants both ends within 1-65535 and begin <= end. */
    internal fun mieruPortRange(item: String): String {
        Validation.portRange(item, RANGE_STYLE).let { if (it.isNotEmpty()) return it }
        val sides = item.trim().split('-')
        val begin = sides[0].toIntOrNull() ?: 0
        val end = sides[1].toIntOrNull() ?: 0
        if (begin == 0 || end == 0) return vtr(R.string.validation_mieru_port_range_bounds)
        if (begin > end) return vtr(R.string.validation_mieru_port_range_order, end.toString(), begin.toString())
        return ""
    }

    /** MieruServerPorts: the core reads each range with Sscanf("%d-%d"), so a single port must be written n-n. */
    @JvmStatic
    fun mieruServerPorts(ports: String): String = Validation.eachItem(ports, Validation.Split.Comma, { mieruPortRange(it) })

    /** MieruTrafficPattern: the core decodes it as padded standard base64. */
    @JvmStatic
    fun mieruTrafficPattern(pattern: String): String {
        Validation.base64(pattern).let { if (it.isNotEmpty()) return it }
        if (pattern.trim().length % 4 == 0) return ""
        return vtr(R.string.validation_mieru_traffic_pattern_padding)
    }
}

/** mieru (include/configs/outbounds/mieru.h, src/configs/outbounds/mieru.cpp). */
class Mieru : Outbound("mieru") {
    @JvmField var transport: String = "TCP"
    @JvmField var username: String = ""
    @JvmField var password: String = ""
    @JvmField var multiplexing: String = ""
    @JvmField var traffic_pattern: String = ""

    /** Comma-separated port ranges ("9000-9010,9020-9030"); exported as sing-box's server_ports array. */
    @JvmField var server_ports: String = ""

    /** mieru::ParseFromLink: only mieru's "simple" link is mappable; the base64-protobuf "standard" link is rejected. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid || url.host.isEmpty() || url.query.isEmpty()) return false
        val q = url.query
        super.parseFromLink(url)
        username = url.userName
        password = url.password

        // mieru pairs every port with its own protocol; one transport is modelled, so the first one wins
        val protocols = q.items.filter { it.first == "protocol" }
        if (protocols.isNotEmpty()) transport = protocols[0].second.uppercase()

        // the core only accepts ranges in server_ports, so an extra single port becomes "N-N"
        val ranges = ArrayList<String>()
        var haveSinglePort = false
        serverPort = 0
        for (item in q.items) {
            if (item.first != "port") continue
            val port = item.second.trim()
            if (port.isEmpty()) continue
            if (port.contains('-')) {
                if (acceptOptional("port", MieruChecks.mieruPortRange(port), port)) ranges.add(port)
            } else if (!acceptOptional("port", Validation.port(port), port)) {
                continue
            } else if (!haveSinglePort) {
                serverPort = QtStrings.toInt(port)
                haveSinglePort = true
            } else {
                ranges.add("$port-$port")
            }
        }
        server_ports = ranges.joinToString(",")

        if (q.has("multiplexing")) multiplexing = q.value("multiplexing")
        // mieru percent-encodes the base64 '+' and '/', which only the fully decoded form turns back
        if (q.has("traffic-pattern")) traffic_pattern = q.valueFully("traffic-pattern")
        importTuning()
        return true
    }

    /** mieru::ParseFromJson. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "mieru") return false
        super.parseFromJson(obj)
        if (obj.contains("transport")) transport = obj.string("transport")
        if (obj.contains("username")) username = obj.string("username")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("multiplexing")) multiplexing = obj.string("multiplexing")
        if (obj.contains("traffic_pattern")) traffic_pattern = obj.string("traffic_pattern")
        if (obj.contains("server_ports")) {
            // sing-box also takes a single range as a plain string
            server_ports = if (obj.isString("server_ports")) {
                obj.string("server_ports").trim()
            } else {
                obj.array("server_ports").strings().joinToString(",") { it.trim() }
            }
        }
        if (ParseDiag.active()) {
            transport = transport.trim().uppercase()
            importTuning()
        }
        return true
    }

    /** mieruImportTuning: an unknown multiplexing level or a malformed traffic pattern is dropped; a missing = padding is restored. */
    private fun importTuning() {
        multiplexing = multiplexing.trim()
        var pattern = traffic_pattern.trim()
        if (!acceptOptional("multiplexing", Validation.oneOf(multiplexing, mieruMultiplexingLevels), multiplexing)) multiplexing = ""
        if (Validation.base64(pattern).isEmpty()) {
            while (pattern.length % 4 != 0) pattern += "="
        }
        if (!acceptOptional("traffic_pattern", MieruChecks.mieruTrafficPattern(pattern))) pattern = ""
        traffic_pattern = pattern
    }

    /**
     * mieru.cpp:83-115: no authority port; every port is a repeated "port" item paired with a "protocol" item.
     * The desktop calls setUserName/setPassword unconditionally, which for the null QString of an unset member
     * clears the section; only non-empty values are written here (a stored profile never carries empty ones).
     */
    override fun exportToLink(): String {
        val url = LinkBuilder("mierus")
        if (username.isNotEmpty()) url.setUserName(username)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        if (name.isNotEmpty()) url.fragment = name

        // mieru requires a profile name, which is not modelled
        url.addQueryItem("profile", "default")

        val protocol = if (transport.isEmpty()) "TCP" else transport.uppercase()
        if (serverPort > 0) {
            url.addQueryItem("port", serverPort.toString())
            url.addQueryItem("protocol", protocol)
        }
        for (part in QtStrings.splitSkipEmpty(server_ports, ",")) {
            val range = part.trim()
            if (range.isEmpty()) continue
            url.addQueryItem("port", range)
            url.addQueryItem("protocol", protocol)
        }

        if (multiplexing.isNotEmpty()) url.addQueryItem("multiplexing", multiplexing)
        if (traffic_pattern.isNotEmpty()) url.addQueryItem("traffic-pattern", traffic_pattern)
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** mieru.cpp:117-130. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "mieru"
        obj.merge(baseExportToJson())
        writeFields(obj)
        return obj
    }

    /** mieru.cpp:132-145. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "mieru"
        obj.merge(baseBuild(ctx))
        writeFields(obj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject) {
        obj["transport"] = if (transport.isEmpty()) "TCP" else transport
        if (username.isNotEmpty()) obj["username"] = username
        if (password.isNotEmpty()) obj["password"] = password
        if (multiplexing.isNotEmpty()) obj["multiplexing"] = multiplexing
        if (traffic_pattern.isNotEmpty()) obj["traffic_pattern"] = traffic_pattern
        val ports = serverPortsArray()
        if (ports.isNotEmpty()) obj["server_ports"] = ports
    }

    /** mieru.cpp:11-18 splitServerPorts. */
    private fun serverPortsArray(): JsonArray {
        val arr = JsonArray()
        for (part in QtStrings.splitSkipEmpty(server_ports, ",")) {
            val range = part.trim()
            if (range.isNotEmpty()) arr.add(range)
        }
        return arr
    }

    /** mieru.cpp:147-150. */
    override fun displayType(): String = "Mieru"

    /** mieru::ServerPortOptional: server_ports supplies the ports. */
    override fun serverPortOptional(): Boolean = serverPortsArray().isNotEmpty()

    /** mieru::Validate. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "username", Validation.required(username))
        issues.addIssue(Severity.Error, "password", Validation.required(password))
        issues.addIssue(Severity.Error, "transport", Validation.oneOf(transport, mieruTransports), transport)
        issues.addIssue(Severity.Error, "multiplexing", Validation.oneOf(multiplexing, mieruMultiplexingLevels), multiplexing)
        issues.addIssue(Severity.Error, "server_ports", MieruChecks.mieruServerPorts(server_ports), server_ports)
        issues.addIssue(Severity.Error, "traffic_pattern", MieruChecks.mieruTrafficPattern(traffic_pattern))
    }

    /** mieru.cpp:152-155. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", "", SecurityLevel.Secure)
}
