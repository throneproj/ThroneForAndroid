package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlin.reflect.KMutableProperty1

/**
 * RouteRule (include/database/entities/RouteRule.h, src/database/entities/RouteRule.cpp). Members carry the desktop
 * member names so preference bindings, backups and the desktop columns line up; [package_name] is the Android
 * addition (desktop column `package_name_json`, rule key `package_name`).
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
    @JvmField var package_name: MutableList<String> = mutableListOf()
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
    // lxbox/asteriskbox extensions
    @JvmField var logical_mode: String = ""
    @JvmField var rules_json: String = ""
    @JvmField var default_interface_address: MutableList<String> = mutableListOf()
    @JvmField var dns_server: MutableList<String> = mutableListOf()
    // urltest balancer (lxbox SPEC 019)
    @JvmField var balancer_mode: String = ""
    @JvmField var balancer_pool: Int = 0
    @JvmField var balancer_pool_tolerance: Int = 0
    @JvmField var balancer_sticky_hash: MutableList<String> = mutableListOf()

    fun copy(): RouteRule {
        val c = RouteRule()
        c.name = name
        c.type = type
        for (f in STRING_FIELDS) f.set(c, f.get(this))
        for (f in LIST_FIELDS) f.set(c, ArrayList(f.get(this)))
        for (f in BOOL_FIELDS) f.set(c, f.get(this))
        c.outbound_id = outbound_id
        c.action = action
        // non-reflection fields (Int and String not in STRING_FIELDS)
        c.balancer_pool = balancer_pool
        c.balancer_pool_tolerance = balancer_pool_tolerance
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

        // logical rule nesting (asteriskbox / lxbox) — checked FIRST, before any standard fields
        if (logical_mode.isNotBlank() && rules_json.isNotBlank()) {
            val logicalObj = JsonObject()
            logicalObj["type"] = "logical"
            logicalObj["mode"] = logical_mode.trim()
            logicalObj["rules"] = JsonInput.parseValue(rules_json) as? JsonArray ?: JsonArray()
            if (invert) logicalObj["invert"] = true
            return logicalObj
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
        // urltest balancer (lxbox SPEC 019)
        if (balancer_mode == "round_robin") {
            val balancer = JsonObject()
            if (balancer_pool > 0) balancer["pool"] = balancer_pool
            if (balancer_pool_tolerance > 0) balancer["pool_tolerance"] = balancer_pool_tolerance
            val sticky = JsonArray()
            for (s in balancer_sticky_hash) {
                val v = s.trim()
                if (v.isNotEmpty()) sticky.add(v)
            }
            if (sticky.isNotEmpty()) balancer["sticky_hash"] = sticky
            obj["mode"] = "round_robin"
            obj["balancer"] = balancer
        }
        // default_interface_address (asteriskbox)
        putStrings(obj, "default_interface_address", default_interface_address)
        // dns_server (route-level DNS server override)
        putStrings(obj, "dns_server", dns_server)
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
        s("logical_mode", logical_mode)
        if (rules_json.isNotBlank()) out.add("rules")
        l("default_interface_address", default_interface_address)
        l("dns_server", dns_server)
        s("balancer_mode", balancer_mode)
        if (balancer_pool != 0) out.add("balancer_pool")
        if (balancer_pool_tolerance != 0) out.add("balancer_pool_tolerance")
        l("balancer_sticky_hash", balancer_sticky_hash)
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
            RouteRule::tls_spoof_method, RouteRule::strategy, RouteRule::logical_mode, RouteRule::balancer_mode,
        )

        /** List members; each is the desktop column `<name>_json`. */
        val LIST_FIELDS: List<KMutableProperty1<RouteRule, MutableList<String>>> = listOf(
            RouteRule::inbound, RouteRule::domain, RouteRule::domain_suffix, RouteRule::domain_keyword,
            RouteRule::domain_regex, RouteRule::source_ip_cidr, RouteRule::ip_cidr, RouteRule::source_port,
            RouteRule::source_port_range, RouteRule::port, RouteRule::port_range, RouteRule::process_name,
            RouteRule::process_path, RouteRule::process_path_regex, RouteRule::package_name, RouteRule::wifi_ssid,
            RouteRule::wifi_bssid, RouteRule::rule_set, RouteRule::sniffers, RouteRule::default_interface_address,
            RouteRule::dns_server, RouteRule::balancer_sticky_hash,
        )

        val BOOL_FIELDS: List<KMutableProperty1<RouteRule, Boolean>> = listOf(
            RouteRule::source_ip_is_private, RouteRule::ip_is_private, RouteRule::invert, RouteRule::no_drop,
            RouteRule::sniff_override_dest,
        )

        private val ADDRESS_ATTRIBUTES = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "rule_set", "ip_cidr")
        private val PROCESS_ATTRIBUTES = setOf("process_path", "process_name")

        private fun blank(list: List<String>): Boolean = list.none { it.isNotBlank() }
    }
}
