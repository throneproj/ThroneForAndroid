package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import kotlin.reflect.KMutableProperty1

/**
 * RouteRule (include/database/entities/RouteRule.h, src/database/entities/RouteRule.cpp). Members carry the desktop
 * member names so preference bindings, backups and the desktop columns line up; [package_name] is the Android
 * addition the desktop also stores (column `package_name_json`, rule key `package_name`). [network_type] and
 * [network_is_expensive] exist only on Android: the desktop drops a rule carrying them on import.
 */
@Suppress("PropertyName")
class RouteRule {
    @JvmField var name: String = ""
    @JvmField var type: Int = RuleType.CUSTOM.id
    @JvmField var ip_version: String = ""
    @JvmField var network: String = ""
    @JvmField var protocol: String = ""
    @JvmField var inbound: MutableList<String> = mutableListOf()
    @JvmField var domain: MutableList<String> = mutableListOf()
    @JvmField var domain_suffix: MutableList<String> = mutableListOf()
    @JvmField var domain_keyword: MutableList<String> = mutableListOf()
    @JvmField var domain_regex: MutableList<String> = mutableListOf()
    @JvmField var source_ip_cidr: MutableList<String> = mutableListOf()
    @JvmField var source_ip_is_private: Boolean = false
    @JvmField var ip_cidr: MutableList<String> = mutableListOf()
    @JvmField var ip_is_private: Boolean = false
    @JvmField var source_port: MutableList<String> = mutableListOf()
    @JvmField var source_port_range: MutableList<String> = mutableListOf()
    @JvmField var port: MutableList<String> = mutableListOf()
    @JvmField var port_range: MutableList<String> = mutableListOf()
    @JvmField var process_name: MutableList<String> = mutableListOf()
    @JvmField var process_path: MutableList<String> = mutableListOf()
    @JvmField var process_path_regex: MutableList<String> = mutableListOf()
    /** The apps the rule matches; [UNKNOWN_PACKAGE] also matches connections whose app is not identified. */
    @JvmField var package_name: MutableList<String> = mutableListOf()
    /** [NETWORK_TYPES] values the current default network must have. */
    @JvmField var network_type: MutableList<String> = mutableListOf()
    @JvmField var network_is_expensive: Boolean = false
    @JvmField var wifi_ssid: MutableList<String> = mutableListOf()
    @JvmField var wifi_bssid: MutableList<String> = mutableListOf()
    @JvmField var rule_set: MutableList<String> = mutableListOf()
    @JvmField var invert: Boolean = false
    @JvmField var outbound_id: Long = OutboundIds.DIRECT
    @JvmField var action: String = "route"
    @JvmField var reject_method: String = ""
    @JvmField var no_drop: Boolean = false
    @JvmField var override_address: String = ""
    @JvmField var override_port: String = ""
    @JvmField var tls_spoof: String = ""
    @JvmField var tls_spoof_method: String = ""
    @JvmField var sniffers: MutableList<String> = mutableListOf()
    @JvmField var sniff_override_dest: Boolean = false
    @JvmField var strategy: String = ""

    fun copy(): RouteRule {
        val c = RouteRule()
        c.name = name
        c.type = type
        for (f in STRING_FIELDS) f.set(c, f.get(this))
        for (f in LIST_FIELDS) f.set(c, ArrayList(f.get(this)))
        for (f in BOOL_FIELDS) f.set(c, f.get(this))
        c.outbound_id = outbound_id
        c.action = action
        return c
    }

    /** The action get_rule_json emits: a route to -3 is a reject and a route to -4 a DNS hijack (RouteRule.cpp:127-131). */
    fun effectiveAction(): String {
        if (action != "route") return action
        return when (outbound_id) {
            OutboundIds.BLOCK -> "reject"
            OutboundIds.HIJACK_DNS -> "hijack-dns"
            else -> action
        }
    }

    /**
     * get_rule_json (RouteRule.cpp:81-185) without its side effect on [action]. The view form names the outbound
     * (proxy, direct, block, warp-bypass or the server's display name from [profileName]) and is empty when that
     * server is missing; the config form uses [outboundTag], else the bare id like the desktop.
     */
    fun toRuleJson(forView: Boolean, outboundTag: String? = null, profileName: (Long) -> String? = { null }): JsonObject {
        if (type == RuleType.ENDPOINT_PREFERRED_BY.id) {
            val tag = if (forView) profileName(outbound_id) else outboundTag
            if (tag.isNullOrEmpty()) return JsonObject()
            return JsonObject().also {
                it["preferred_by"] = JsonArray.of(tag)
                it["action"] = "route"
                it["outbound"] = tag
            }
        }

        val obj = JsonObject()
        if (ip_version.isNotBlank()) obj["ip_version"] = QtStrings.toInt(ip_version)
        if (network.isNotBlank()) obj["network"] = network.trim()
        if (protocol.isNotBlank()) obj["protocol"] = protocol.trim()
        putStrings(obj, "inbound", inbound)
        putStrings(obj, "domain", domain)
        putStrings(obj, "domain_suffix", domain_suffix)
        putStrings(obj, "domain_keyword", domain_keyword)
        putStrings(obj, "domain_regex", domain_regex)
        putStrings(obj, "source_ip_cidr", source_ip_cidr)
        if (source_ip_is_private) obj["source_ip_is_private"] = true
        putStrings(obj, "ip_cidr", ip_cidr)
        if (ip_is_private) obj["ip_is_private"] = true
        putInts(obj, "source_port", source_port)
        putStrings(obj, "source_port_range", source_port_range)
        putInts(obj, "port", port)
        putStrings(obj, "port_range", port_range)
        putStrings(obj, "process_name", process_name)
        putStrings(obj, "process_path", process_path)
        putStrings(obj, "process_path_regex", process_path_regex)
        putStrings(obj, "package_name", package_name)
        putStrings(obj, "network_type", network_type)
        if (network_is_expensive) obj["network_is_expensive"] = true
        putStrings(obj, "wifi_ssid", wifi_ssid)
        putStrings(obj, "wifi_bssid", wifi_bssid)
        val ruleSets = JsonArray()
        for (entry in rule_set) {
            val e = entry.trim()
            if (e.isNotEmpty()) ruleSets.add(if (forView) e else RuleSets.tagFor(e))
        }
        if (ruleSets.isNotEmpty()) obj["rule_set"] = ruleSets
        if (invert) obj["invert"] = true

        val act = effectiveAction()
        obj["action"] = act
        if (act == "reject") {
            if (reject_method.isNotBlank()) obj["reject_method"] = reject_method.trim()
            if (no_drop) obj["no_drop"] = true
        }
        if (act == "route" || act == "route-options" || act == "bypass") {
            if (override_address.isNotBlank()) obj["override_address"] = override_address.trim()
            val port = QtStrings.toInt(override_port)
            if (port > 0) obj["override_port"] = port
            if (tls_spoof.isNotBlank()) {
                obj["tls_spoof"] = tls_spoof.trim()
                if (tls_spoof_method.isNotBlank()) obj["tls_spoof_method"] = tls_spoof_method.trim()
            }
            if (act == "route" || act == "bypass") {
                if (forView) {
                    val target = when (outbound_id) {
                        OutboundIds.PROXY, OutboundIds.DIRECT, OutboundIds.BLOCK, OutboundIds.WARP_BYPASS -> OutboundIds.toName(outbound_id)
                        else -> if (outbound_id > 0) profileName(outbound_id) else null
                    } ?: return JsonObject()
                    obj["outbound"] = target
                } else if (!outboundTag.isNullOrEmpty()) {
                    obj["outbound"] = outboundTag
                } else {
                    obj["outbound"] = outbound_id
                }
            }
        }
        if (act == "sniff" && sniff_override_dest) obj["override_destination"] = true
        if (act == "resolve" && strategy.isNotBlank()) obj["strategy"] = strategy.trim()
        return obj
    }

    /**
     * The rule as the core takes it. sing-box cannot match "no identified app" with package_name, so Apps holding
     * [UNKNOWN_PACKAGE] become a logical `or` of the listed packages and an inverted `.*` package_name_regex (true
     * only while no package is known), and-ed with the rule's other conditions; invert then applies to the whole.
     */
    fun toConfigJson(outboundTag: String?): JsonObject {
        val flat = toRuleJson(false, outboundTag)
        val apps = (flat["package_name"] as? JsonArray)?.strings() ?: return flat
        if (UNKNOWN_PACKAGE !in apps) return flat
        flat.remove("package_name")
        val invert = flat.remove("invert") == true
        val logical = jsonObjectOf("type" to "logical", "mode" to "and")
        val own = JsonObject()
        for ((key, value) in flat) if (key in ACTION_KEYS) logical[key] = value else own[key] = value
        val unidentified = jsonObjectOf("package_name_regex" to JsonArray.of(".*"), "invert" to true)
        val packages = apps.filter { it != UNKNOWN_PACKAGE }
        val appMatch = if (packages.isEmpty()) unidentified else jsonObjectOf(
            "type" to "logical", "mode" to "or",
            "rules" to JsonArray.of(jsonObjectOf("package_name" to JsonValues.stringArray(packages)), unidentified),
        )
        val rules = JsonArray()
        if (own.isNotEmpty()) rules.add(own)
        rules.add(appMatch)
        logical["rules"] = rules
        if (invert) logical["invert"] = true
        return logical
    }

    /** Whether the match depends on the connection's app, which DNS rules and tun routes cannot follow. */
    fun matchesByApp(): Boolean = !blank(package_name)

    /** The conditions on the current network (type, metering, Wi-Fi) as rule keys; DNS rules take the same keys. */
    fun networkConditions(): JsonObject {
        val obj = JsonObject()
        putStrings(obj, "network_type", network_type)
        if (network_is_expensive) obj["network_is_expensive"] = true
        putStrings(obj, "wifi_ssid", wifi_ssid)
        putStrings(obj, "wifi_bssid", wifi_bssid)
        return obj
    }

    /** RouteRule::isEmpty (RouteRule.cpp:586-604), judged on the effective action. */
    fun isEmpty(): Boolean {
        val t = RuleType.ofId(type)
        if (t != RuleType.CUSTOM) {
            if (t == RuleType.ENDPOINT_PREFERRED_BY) return false
            if (t.isSimpleAddress) {
                return blank(domain) && blank(domain_suffix) && blank(domain_keyword) &&
                    blank(domain_regex) && blank(rule_set) && blank(ip_cidr)
            }
            return blank(process_name) && blank(process_path)
        }
        val size = toRuleJson(false).size
        return when (effectiveAction()) {
            "route", "route-options", "hijack-dns" -> size <= 1
            else -> false
        }
    }

    /**
     * The member names of the attributes that are not at their default (the desktop's is_attribute_at_default,
     * RouteRule.cpp:213-237), in the desktop attribute order; `action` and the outbound are not included.
     */
    fun nonDefaultAttributes(): List<String> {
        val out = ArrayList<String>()
        fun s(n: String, v: String) {
            if (v.isNotBlank()) out.add(n)
        }

        fun l(n: String, v: List<String>) {
            if (!blank(v)) out.add(n)
        }

        fun b(n: String, v: Boolean) {
            if (v) out.add(n)
        }
        s("ip_version", ip_version)
        s("network", network)
        s("protocol", protocol)
        l("inbound", inbound)
        l("domain", domain)
        l("domain_suffix", domain_suffix)
        l("domain_keyword", domain_keyword)
        l("domain_regex", domain_regex)
        l("source_ip_cidr", source_ip_cidr)
        b("source_ip_is_private", source_ip_is_private)
        l("ip_cidr", ip_cidr)
        b("ip_is_private", ip_is_private)
        l("source_port", source_port)
        l("source_port_range", source_port_range)
        l("port", port)
        l("port_range", port_range)
        l("process_name", process_name)
        l("process_path", process_path)
        l("process_path_regex", process_path_regex)
        l("package_name", package_name)
        l("network_type", network_type)
        b("network_is_expensive", network_is_expensive)
        l("wifi_ssid", wifi_ssid)
        l("wifi_bssid", wifi_bssid)
        l("rule_set", rule_set)
        b("invert", invert)
        s("override_address", override_address)
        if (QtStrings.toInt(override_port) > 0) out.add("override_port")
        s("tls_spoof", tls_spoof)
        s("tls_spoof_method", tls_spoof_method)
        s("reject_method", reject_method)
        b("no_drop", no_drop)
        b("sniff_override_dest", sniff_override_dest)
        s("strategy", strategy)
        return out
    }

    /**
     * D11: whether the rule may keep the simple type [t] after an edit: its effective action and outbound still
     * match the type's fixed target and every set attribute is one the desktop lets that type edit (canEditAttr).
     */
    fun fitsType(t: RuleType): Boolean {
        if (t == RuleType.CUSTOM) return true
        val target = t.simpleOutbound ?: return false
        val act = effectiveAction()
        val targetFits = if (target == OutboundIds.BLOCK) act == "reject" else act == "route" && outbound_id == target
        if (!targetFits) return false
        val editable = if (t.isSimpleAddress) ADDRESS_ATTRIBUTES else PROCESS_ATTRIBUTES
        return nonDefaultAttributes().all { it in editable }
    }

    private fun putStrings(obj: JsonObject, key: String, list: List<String>) {
        val arr = JsonArray()
        for (item in list) {
            val v = item.trim()
            if (v.isNotEmpty()) arr.add(v)
        }
        if (arr.isNotEmpty()) obj[key] = arr
    }

    private fun putInts(obj: JsonObject, key: String, list: List<String>) {
        val arr = JsonArray()
        for (item in list) {
            if (item.isNotBlank()) arr.add(QtStrings.toInt(item))
        }
        if (arr.isNotEmpty()) obj[key] = arr
    }

    companion object {
        /** Scalar string members besides name and action. */
        val STRING_FIELDS: List<KMutableProperty1<RouteRule, String>> = listOf(
            RouteRule::ip_version, RouteRule::network, RouteRule::protocol, RouteRule::reject_method,
            RouteRule::override_address, RouteRule::override_port, RouteRule::tls_spoof,
            RouteRule::tls_spoof_method, RouteRule::strategy,
        )

        /** List members; each is the desktop column `<name>_json`. */
        val LIST_FIELDS: List<KMutableProperty1<RouteRule, MutableList<String>>> = listOf(
            RouteRule::inbound, RouteRule::domain, RouteRule::domain_suffix, RouteRule::domain_keyword,
            RouteRule::domain_regex, RouteRule::source_ip_cidr, RouteRule::ip_cidr, RouteRule::source_port,
            RouteRule::source_port_range, RouteRule::port, RouteRule::port_range, RouteRule::process_name,
            RouteRule::process_path, RouteRule::process_path_regex, RouteRule::package_name, RouteRule::network_type,
            RouteRule::wifi_ssid, RouteRule::wifi_bssid, RouteRule::rule_set, RouteRule::sniffers,
        )

        val BOOL_FIELDS: List<KMutableProperty1<RouteRule, Boolean>> = listOf(
            RouteRule::source_ip_is_private, RouteRule::ip_is_private, RouteRule::invert, RouteRule::no_drop,
            RouteRule::sniff_override_dest, RouteRule::network_is_expensive,
        )

        /** The package_name entry for connections whose app is not identified (no owner, or a uid without a package). */
        const val UNKNOWN_PACKAGE = "unknown"

        /** sing-box's network_type values (constant/network.go). */
        val NETWORK_TYPES = listOf("wifi", "cellular", "ethernet", "other")

        /** Rule JSON keys that configure the action rather than narrow the match. */
        private val ACTION_KEYS = setOf(
            "action", "outbound", "reject_method", "no_drop", "override_address", "override_port", "tls_spoof",
            "tls_spoof_method", "override_destination", "strategy",
        )

        private val ADDRESS_ATTRIBUTES = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "rule_set", "ip_cidr")
        private val PROCESS_ATTRIBUTES = setOf("process_path", "process_name")

        private fun blank(list: List<String>): Boolean = list.none { it.isNotBlank() }
    }
}
