package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.TypeAccess
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import kotlin.reflect.KMutableProperty1

/**
 * RuleIssue (RouteRule.h). [attr] and [related] (the other attribute of a cross-field problem) are member names, which
 * are also the rule editor's preference keys: the desktop's outbound, method and override_destination are
 * outbound_id, reject_method and sniff_override_dest. [value] is the offending list item, empty when the message is
 * about the attribute as a whole.
 */
data class RuleIssue(
    @JvmField val severity: Severity,
    @JvmField val attr: String,
    @JvmField val message: String,
    @JvmField val value: String = "",
    @JvmField val related: String = "",
)

/**
 * What [RouteRule.validate] reads besides the rule: srslist.h's ruleSetList ([catalog]; null or empty leaves rule-set
 * names unjudged, URLs are still checked) and ProfilesRepo ([profiles]; null leaves server outbounds unchecked).
 */
class RuleEnv(@JvmField val catalog: RuleSetCatalog? = null, @JvmField val profiles: ProfileProvider? = null) {
    companion object {
        /** The stored profiles, each read once; the profile database allows main-thread reads. */
        @JvmStatic
        fun storedProfiles(): ProfileProvider {
            val cache = HashMap<Long, Outbound?>()
            return ProfileProvider { id ->
                if (cache.containsKey(id)) cache[id] else ProfileManager.getProfile(id)?.outbound.also { cache[id] = it }
            }
        }

        /** The stored profiles and the rule-set list; the list may be read from a file, so call it off the main thread. */
        @JvmStatic
        fun stored(): RuleEnv = RuleEnv(RouteManager.catalog(), storedProfiles())
    }
}

/**
 * RouteRule (include/database/entities/RouteRule.h, src/database/entities/RouteRule.cpp). Members carry the desktop
 * member names so preference bindings, backups and the desktop columns line up; [package_name] is the Android
 * addition the desktop also stores (column `package_name_json`, rule key `package_name`). [package_name_regex],
 * [network_type] and [network_is_expensive] exist only on Android: the desktop drops a rule carrying them on import.
 */
@Suppress("PropertyName")
class RouteRule {
    @JvmField var name: String = ""
    @JvmField var type: Int = RuleType.CUSTOM.id
    /** Kept in the profile but left out of the generated config. */
    @JvmField var disabled: Boolean = false
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
    /** Go regexes on the package name; an app matching one counts as listed in [package_name]. */
    @JvmField var package_name_regex: MutableList<String> = mutableListOf()
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
    @JvmField var tls_fragment: Boolean = false
    @JvmField var tls_fragment_fallback_delay: String = ""
    @JvmField var tls_record_fragment: Boolean = false
    @JvmField var tls_spoof: String = ""
    @JvmField var tls_spoof_method: String = ""
    @JvmField var sniffers: MutableList<String> = mutableListOf()
    @JvmField var sniff_override_dest: Boolean = false
    @JvmField var strategy: String = ""

    fun copy(): RouteRule {
        val c = RouteRule()
        c.name = name
        c.type = type
        c.disabled = disabled
        for (f in STRING_FIELDS) f.set(c, f.get(this))
        for (f in LIST_FIELDS) f.set(c, ArrayList(f.get(this)))
        for (f in BOOL_FIELDS) f.set(c, f.get(this))
        c.outbound_id = outbound_id
        c.action = action
        return c
    }

    /** RouteRule::effective_action: the action get_rule_json emits, a route to -3 is a reject and a route to -4 a DNS hijack. */
    fun effectiveAction(): String {
        if (action != "route") return action
        return when (outbound_id) {
            OutboundIds.BLOCK -> "reject"
            OutboundIds.HIJACK_DNS -> "hijack-dns"
            else -> action
        }
    }

    /**
     * get_rule_json (RouteRule.cpp) without its side effect on [action]. The view form names the outbound
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
        putStrings(obj, "package_name_regex", package_name_regex)
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
            // the core fails on an unknown method and on no_drop alongside drop
            val method = reject_method.trim().lowercase()
            if (method in REJECT_METHODS) obj["method"] = method
            if (no_drop && method != "drop") obj["no_drop"] = true
        }
        if (act == "route" || act == "route-options" || act == "bypass") {
            if (override_address.isNotBlank()) obj["override_address"] = override_address.trim()
            val port = QtStrings.toInt(override_port)
            if (port > 0) obj["override_port"] = port
            if (tls_fragment) {
                obj["tls_fragment"] = true
                // the core reads the delay only alongside tls_fragment
                if (tls_fragment_fallback_delay.isNotBlank()) obj["tls_fragment_fallback_delay"] = tls_fragment_fallback_delay.trim()
            }
            if (tls_record_fragment) obj["tls_record_fragment"] = true
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
     * The rule as the core takes it. Apps and package regexes are one condition, any of them matching, where sing-box
     * would require both package_name and package_name_regex; and it cannot match "no identified app", which becomes
     * an inverted `.*` package_name_regex (true only while no package is known). So a rule with both, or with
     * [UNKNOWN_PACKAGE] in Apps, becomes a logical `or` of those parts and-ed with the rule's other conditions;
     * invert then applies to the whole.
     */
    fun toConfigJson(outboundTag: String?): JsonObject {
        val flat = toRuleJson(false, outboundTag)
        val apps = (flat["package_name"] as? JsonArray)?.strings().orEmpty()
        val regexes = (flat["package_name_regex"] as? JsonArray)?.strings().orEmpty()
        val packages = apps.filter { it != UNKNOWN_PACKAGE }
        val unknown = UNKNOWN_PACKAGE in apps
        if (!unknown && (packages.isEmpty() || regexes.isEmpty())) return flat
        flat.remove("package_name")
        flat.remove("package_name_regex")
        val invert = flat.remove("invert") == true
        val logical = jsonObjectOf("type" to "logical", "mode" to "and")
        val own = JsonObject()
        for ((key, value) in flat) if (key in ACTION_KEYS) logical[key] = value else own[key] = value
        val appRules = JsonArray()
        if (packages.isNotEmpty()) appRules.add(jsonObjectOf("package_name" to JsonValues.stringArray(packages)))
        if (regexes.isNotEmpty()) appRules.add(jsonObjectOf("package_name_regex" to JsonValues.stringArray(regexes)))
        if (unknown) appRules.add(jsonObjectOf("package_name_regex" to JsonArray.of(".*"), "invert" to true))
        val appMatch = if (appRules.size == 1) appRules[0] else jsonObjectOf("type" to "logical", "mode" to "or", "rules" to appRules)
        val rules = JsonArray()
        if (own.isNotEmpty()) rules.add(own)
        rules.add(appMatch)
        logical["rules"] = rules
        if (invert) logical["invert"] = true
        return logical
    }

    /** Whether the match depends on the connection's app, which DNS rules and tun routes cannot follow. */
    fun matchesByApp(): Boolean = !blank(package_name) || !blank(package_name_regex)

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
     * The member names of the attributes that are not at their default (the desktop's is_attribute_at_default), in
     * the desktop attribute order; `action` and the outbound are not included.
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
        l("package_name_regex", package_name_regex)
        l("network_type", network_type)
        b("network_is_expensive", network_is_expensive)
        l("wifi_ssid", wifi_ssid)
        l("wifi_bssid", wifi_bssid)
        l("rule_set", rule_set)
        b("invert", invert)
        s("override_address", override_address)
        // 0 is the core's "unset"; any other text counts, or a bad value would be hidden with it.
        if (override_port.isNotBlank() && override_port.trim() != "0") out.add("override_port")
        b("tls_fragment", tls_fragment)
        s("tls_fragment_fallback_delay", tls_fragment_fallback_delay)
        b("tls_record_fragment", tls_record_fragment)
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

    /** get_current_value_string by member name: a scalar as one item, the outbound as its id, a list as it is. */
    fun currentValues(attr: String): List<String> {
        if (attr == "action") return listOf(action)
        if (attr == "outbound_id") return listOf(outbound_id.toString())
        STRINGS_BY_NAME[attr]?.let { return listOf(it.get(this)) }
        return LISTS_BY_NAME[attr]?.get(this) ?: emptyList()
    }

    /** set_field_value for a list member: trimmed, empty items dropped. */
    fun setValues(attr: String, values: List<String>) {
        LISTS_BY_NAME[attr]?.set(this, values.map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() })
    }

    /** clear_attribute_value: the member back to its default; `action` is left alone. */
    fun clearAttribute(attr: String) {
        when (attr) {
            "action" -> {}
            "outbound_id" -> outbound_id = OutboundIds.DIRECT
            else -> {
                STRINGS_BY_NAME[attr]?.set(this, "")
                LISTS_BY_NAME[attr]?.set(this, mutableListOf())
                BOOLS_BY_NAME[attr]?.set(this, false)
            }
        }
    }

    /** RouteRule::has_match_conditions: whether anything narrows which connections the rule applies to. */
    fun hasMatchConditions(): Boolean {
        if (source_ip_is_private || ip_is_private || network_is_expensive) return true
        for (attr in ATTRIBUTES) {
            if (!isMatchAttribute(attr) || attr in BOOLS_BY_NAME) continue
            if (currentValues(attr).any { it.isNotBlank() }) return true
        }
        return false
    }

    /**
     * RouteRule::validate: every problem the core would refuse at start, or that leaves the rule not doing what it
     * says. TLS spoof is never emitted on Android (D8), so its values and its pairing with an action go unreported.
     */
    @JvmOverloads
    fun validate(env: RuleEnv = RuleEnv()): List<RuleIssue> {
        val out = ArrayList<RuleIssue>()
        if (type == RuleType.ENDPOINT_PREFERRED_BY.id) return out
        val act = effectiveAction()
        fun applies(attr: String) = ACTION_SCOPES[attr]?.contains(act) ?: true
        fun add(severity: Severity, attr: String, message: String, related: String = "") {
            out.add(RuleIssue(severity, attr, message, "", related))
        }

        for (attr in ATTRIBUTES) {
            if (attr == "outbound_id" || attr in BOOLS_BY_NAME || attr in SPOOF_ATTRIBUTES || !applies(attr)) continue
            // The editor hides the delay without tls_fragment and nothing emits it then.
            if (attr == "tls_fragment_fallback_delay" && !tls_fragment) continue
            for (item in currentValues(attr)) checkValue(attr, item, env.catalog)?.let { out.add(it) }
        }

        if (act == "route" || act == "bypass") {
            val message = outboundProblem(outbound_id, env.profiles)
            if (message.isNotEmpty()) add(Severity.Error, "outbound_id", message)
        }
        // The core's RouteOptionsActionOptions.UnmarshalJSON checks; route and bypass rules accept both fragment kinds.
        if (act == "route-options") {
            if (tls_fragment && tls_record_fragment) {
                add(Severity.Error, "tls_record_fragment", vtr(R.string.validation_route_fragment_exclusive), "tls_fragment")
            }
            val anyOption = override_address.isNotBlank() || QtStrings.toInt(override_port) > 0 || tls_fragment ||
                tls_record_fragment || tls_spoof.isNotBlank()
            // Without conditions either, the rule is empty and dropped at save.
            if (!anyOption && hasMatchConditions()) add(Severity.Error, "action", vtr(R.string.validation_route_options_empty))
        }
        if (applies("tls_fragment") && tls_fragment_fallback_delay.isNotBlank() && !tls_fragment) {
            add(Severity.Warning, "tls_fragment_fallback_delay", vtr(R.string.validation_route_fallback_delay_unused), "tls_fragment")
        }
        if (act == "reject" && no_drop && reject_method.trim().lowercase() == "drop") {
            add(Severity.Warning, "no_drop", vtr(R.string.validation_route_no_drop_unused), "reject_method")
        }

        val nonDefault = nonDefaultAttributes()
        for (attr in ATTRIBUTES) {
            val scope = ACTION_SCOPES[attr] ?: continue
            val atDefault = if (attr == "outbound_id") outbound_id == OutboundIds.DIRECT else attr !in nonDefault
            if (act in scope || attr in SPOOF_ATTRIBUTES || atDefault) continue
            // The block or dns_out outbound is what makes a route rule a reject or hijack-dns one.
            if (attr == "outbound_id" && action == "route") continue
            add(Severity.Warning, attr, vtr(R.string.validation_route_ignored_by_action, act, scope.joinToString(", ")), "action")
        }

        if (type == RuleType.CUSTOM.id && (act == "route" || act == "bypass" || act == "reject") && !hasMatchConditions()) {
            add(Severity.Warning, "action", vtr(R.string.validation_route_matches_all))
        }
        return out
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
            RouteRule::override_address, RouteRule::override_port, RouteRule::tls_fragment_fallback_delay,
            RouteRule::tls_spoof, RouteRule::tls_spoof_method, RouteRule::strategy,
        )

        /** List members; each is the desktop column `<name>_json`. */
        val LIST_FIELDS: List<KMutableProperty1<RouteRule, MutableList<String>>> = listOf(
            RouteRule::inbound, RouteRule::domain, RouteRule::domain_suffix, RouteRule::domain_keyword,
            RouteRule::domain_regex, RouteRule::source_ip_cidr, RouteRule::ip_cidr, RouteRule::source_port,
            RouteRule::source_port_range, RouteRule::port, RouteRule::port_range, RouteRule::process_name,
            RouteRule::process_path, RouteRule::process_path_regex, RouteRule::package_name,
            RouteRule::package_name_regex, RouteRule::network_type, RouteRule::wifi_ssid, RouteRule::wifi_bssid,
            RouteRule::rule_set, RouteRule::sniffers,
        )

        val BOOL_FIELDS: List<KMutableProperty1<RouteRule, Boolean>> = listOf(
            RouteRule::source_ip_is_private, RouteRule::ip_is_private, RouteRule::invert, RouteRule::no_drop,
            RouteRule::sniff_override_dest, RouteRule::network_is_expensive, RouteRule::tls_fragment,
            RouteRule::tls_record_fragment,
        )

        /** The package_name entry for connections whose app is not identified (no owner, or a uid without a package). */
        const val UNKNOWN_PACKAGE = "unknown"

        /** sing-box's network_type values (constant/network.go). */
        val NETWORK_TYPES = listOf("wifi", "cellular", "ethernet", "other")

        /** Rule JSON keys that configure the action rather than narrow the match. */
        private val ACTION_KEYS = setOf(
            "action", "outbound", "method", "no_drop", "override_address", "override_port", "tls_fragment",
            "tls_fragment_fallback_delay", "tls_record_fragment", "tls_spoof", "tls_spoof_method",
            "override_destination", "strategy",
        )

        private val ADDRESS_ATTRIBUTES = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "rule_set", "ip_cidr")
        private val PROCESS_ATTRIBUTES = setOf("process_path", "process_name")

        /** SingboxOptions::rejectMethods (Const.hpp). */
        private val REJECT_METHODS = listOf("default", "drop", "reply")

        /** get_attributes by member name, plus Android's package_name_regex, network_type and network_is_expensive. */
        private val ATTRIBUTES = listOf(
            "ip_version", "network", "protocol", "inbound", "domain", "domain_suffix", "domain_keyword", "domain_regex",
            "source_ip_cidr", "source_ip_is_private", "ip_cidr", "ip_is_private", "source_port", "source_port_range",
            "port", "port_range", "process_name", "process_path", "process_path_regex", "package_name",
            "package_name_regex", "network_type", "network_is_expensive", "wifi_ssid", "wifi_bssid", "rule_set",
            "invert", "action", "outbound_id", "override_address", "override_port", "tls_fragment",
            "tls_fragment_fallback_delay", "tls_record_fragment", "tls_spoof", "tls_spoof_method", "reject_method",
            "no_drop", "sniff_override_dest", "strategy",
        )

        private val MATCH_ATTRIBUTES = setOf(
            "ip_version", "network", "protocol", "inbound", "domain", "domain_suffix", "domain_keyword", "domain_regex",
            "source_ip_cidr", "source_ip_is_private", "ip_cidr", "ip_is_private", "source_port", "source_port_range",
            "port", "port_range", "process_name", "process_path", "process_path_regex", "package_name",
            "package_name_regex", "network_type", "network_is_expensive", "wifi_ssid", "wifi_bssid", "rule_set",
            "invert",
        )

        private val STRINGS_BY_NAME = STRING_FIELDS.associateBy { it.name }
        private val LISTS_BY_NAME = LIST_FIELDS.associateBy { it.name }
        private val BOOLS_BY_NAME = BOOL_FIELDS.associateBy { it.name }

        /** Android never emits rule-level TLS spoof (D8). */
        private val SPOOF_ATTRIBUTES = setOf("tls_spoof", "tls_spoof_method")

        private val ROUTE_LIKE = listOf("route", "route-options", "bypass")

        /** routeRuleActionScopes: the actions get_rule_json emits each action-specific attribute for. */
        private val ACTION_SCOPES: Map<String, List<String>> = mapOf(
            "outbound_id" to listOf("route", "bypass"),
            "override_address" to ROUTE_LIKE,
            "override_port" to ROUTE_LIKE,
            "tls_fragment" to ROUTE_LIKE,
            "tls_fragment_fallback_delay" to ROUTE_LIKE,
            "tls_record_fragment" to ROUTE_LIKE,
            "tls_spoof" to ROUTE_LIKE,
            "tls_spoof_method" to ROUTE_LIKE,
            "reject_method" to listOf("reject"),
            "no_drop" to listOf("reject"),
            "sniff_override_dest" to listOf("sniff"),
            "strategy" to listOf("resolve"),
        )

        private val PORT_RANGE_STYLE = Validation.PortRangeStyle(
            Validation.RangeSep.Colon, allowSingle = false, allowOpenEnds = true, checkOrder = false,
        )

        private fun blank(list: List<String>): Boolean = list.none { it.isNotBlank() }

        @JvmStatic
        fun isListAttribute(attr: String): Boolean = attr in LISTS_BY_NAME

        @JvmStatic
        fun isMatchAttribute(attr: String): Boolean = attr in MATCH_ATTRIBUTES

        /**
         * RouteRule::check_value: one list item or scalar value of [attr] on its own, null when acceptable; the outbound
         * and the cross-field rules are [validate]'s. Rule-set names are judged only against a non-empty [catalog].
         */
        @JvmStatic
        @JvmOverloads
        fun checkValue(attr: String, raw: String, catalog: RuleSetCatalog? = null): RuleIssue? {
            val value = raw.trim()
            if (value.isEmpty()) return null
            var severity = Severity.Error
            val message = when (attr) {
                "domain", "domain_suffix", "domain_keyword" -> {
                    severity = Severity.Warning
                    domainProblem(value)
                }

                "domain_regex", "process_path_regex", "package_name_regex" -> {
                    severity = Severity.Warning
                    Validation.goRegex(value)
                }

                "ip_cidr", "source_ip_cidr" -> Validation.cidr(value, Family.Any, allowBareIp = true)
                "port", "source_port" -> when {
                    value.contains(':') || value.contains('-') -> vtr(
                        if (attr == "port") R.string.validation_route_port_range_field else R.string.validation_route_source_port_range_field
                    )
                    // Listable[uint16] in the core, so 0 is accepted.
                    value != "0" -> Validation.port(value)
                    else -> ""
                }

                "port_range", "source_port_range" -> Validation.portRange(value, PORT_RANGE_STYLE).ifEmpty {
                    severity = Severity.Warning
                    Validation.portRangeOrder(value, PORT_RANGE_STYLE)
                }

                "process_name" -> {
                    severity = Severity.Warning
                    if (value.contains('/') || value.contains('\\')) vtr(R.string.validation_route_process_name_path) else ""
                }

                "process_path" -> {
                    severity = Severity.Warning
                    if (absolutePath(value)) "" else vtr(R.string.validation_route_process_path_absolute)
                }

                "rule_set" -> ruleSetProblem(value, catalog)
                "override_address" -> overrideAddressProblem(value)
                "override_port" -> if (value != "0") Validation.port(value) else ""
                "tls_fragment_fallback_delay" -> Validation.duration(value)
                "tls_spoof" -> {
                    val bare = if (value.startsWith('[') && value.endsWith(']')) value.substring(1, value.length - 1) else value
                    when {
                        Validation.ip(bare).isEmpty() -> {
                            severity = Severity.Warning
                            vtr(R.string.validation_route_spoof_ip)
                        }

                        !value.contains("://") && hasPortSuffix(value) -> vtr(R.string.validation_route_spoof_port)
                        else -> Validation.host(value)
                    }
                }

                "ip_version" -> Validation.oneOf(value, listOf("4", "6"))
                "network" -> Validation.oneOf(value, listOf("tcp", "udp", "icmp"))
                "protocol" -> Validation.oneOf(value, SNIFF_PROTOCOLS)
                "action" -> Validation.oneOf(
                    value, listOf("route", "route-options", "direct", "bypass", "reject", "hijack-dns", "sniff", "resolve")
                )
                // get_rule_json lowercases it.
                "reject_method" -> Validation.oneOf(value.lowercase(), REJECT_METHODS)
                "strategy" -> Validation.oneOf(value, listOf("as_is", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only"))
                "tls_spoof_method" -> Validation.oneOf(
                    value, listOf("wrong-sequence", "wrong-checksum", "wrong-ack", "wrong-md5", "wrong-timestamp")
                )
                // The core's InterfaceType refuses any other name.
                "network_type" -> Validation.oneOf(value, NETWORK_TYPES)
                else -> ""
            }
            if (message.isEmpty()) return null
            return RuleIssue(severity, attr, message, if (isListAttribute(attr)) value else "")
        }

        /** RouteRule::cross_checks: the (attr, related, severity) shapes [validate] reports cross-field problems in. */
        @JvmStatic
        fun crossChecks(): List<RuleIssue> {
            val out = mutableListOf(
                RuleIssue(Severity.Error, "tls_record_fragment", "", "", "tls_fragment"),
                RuleIssue(Severity.Warning, "tls_fragment_fallback_delay", "", "", "tls_fragment"),
                RuleIssue(Severity.Warning, "no_drop", "", "", "reject_method"),
            )
            for (attr in ATTRIBUTES) {
                if (attr in ACTION_SCOPES && attr !in SPOOF_ATTRIBUTES) out.add(RuleIssue(Severity.Warning, attr, "", "", "action"))
            }
            return out
        }

        /**
         * routeRuleOutboundProblem: calculatePrerequisites' checks of a route outbound, which otherwise fail the start
         * without naming the rule. Server ids are checked only with [profiles].
         */
        @JvmStatic
        fun outboundProblem(id: Long, profiles: ProfileProvider?): String {
            if (id == OutboundIds.PROXY || id == OutboundIds.DIRECT || id == OutboundIds.WARP_BYPASS) return ""
            if (id < 0) return vtr(R.string.validation_route_outbound_invalid)
            if (profiles == null) return ""
            val ent = profiles.get(id) ?: return vtr(R.string.validation_route_outbound_missing)
            val name = ent.displayName()
            if (unroutable(ent)) return vtr(R.string.validation_route_outbound_unroutable, name)
            if (ent.type != "chain") return ""
            val hops = TypeAccess.chainHops(ent)
            if (hops.isEmpty()) return vtr(R.string.validation_route_outbound_chain_empty, name)
            for (hopId in hops) {
                val hop = profiles.get(hopId)
                if (hop == null || hop.type == "chain" || unroutable(hop)) return vtr(R.string.validation_route_outbound_chain_hop, name)
                if (hop.type == "autoselector") return vtr(R.string.validation_route_outbound_chain_selector, name)
            }
            return ""
        }

        /** SingboxOptions::SniffProtocols (Const.hpp), with ntp. */
        val SNIFF_PROTOCOLS = listOf("tls", "http", "quic", "dns", "stun", "bittorrent", "dtls", "ssh", "rdp", "ntp")

        private fun unroutable(o: Outbound): Boolean = o.isExtraCore() || o.isXrayFullConfig() || TypeAccess.isCustomFullConfig(o)

        /** "host:1234", not an IPv6 address. */
        private fun hasPortSuffix(text: String): Boolean {
            val colon = text.lastIndexOf(':')
            if (colon <= 0 || text.indexOf(':') != colon) return false
            val port = text.substring(colon + 1)
            return port.isNotEmpty() && port.all { it in '0'..'9' }
        }

        /** Shared profiles travel between systems, so both path styles count. */
        private fun absolutePath(text: String): Boolean {
            if (text.startsWith('/') || text.startsWith('\\')) return true
            return text.length >= 3 && text[0].isLetter() && text[1] == ':' && (text[2] == '\\' || text[2] == '/')
        }

        /** The core accepts any non-empty item; these just never match. */
        private fun domainProblem(value: String): String = when {
            value.contains("://") -> vtr(R.string.validation_route_domain_scheme)
            value.any { it.isWhitespace() } -> vtr(R.string.validation_route_domain_spaces)
            value.contains('/') -> vtr(R.string.validation_route_domain_path)
            value.contains(':') -> vtr(R.string.validation_route_domain_port)
            value != value.lowercase() -> vtr(R.string.validation_route_domain_lowercase)
            else -> ""
        }

        /** ParseSocksaddrHostPort unwraps one pair of brackets around the whole value. */
        private fun overrideAddressProblem(value: String): String {
            if (value.startsWith('[')) {
                if (value.length > 2 && value.endsWith(']')) return Validation.ip(value.substring(1, value.length - 1))
                return vtr(R.string.validation_route_override_address_brackets)
            }
            if (Validation.ip(value).isEmpty()) return ""
            if (hasPortSuffix(value)) return vtr(R.string.validation_route_override_address_port)
            return Validation.host(value)
        }

        /**
         * A srslist name (or the adblock set, which the build also knows), or an http(s) URL naming an .srs file, the
         * only kind of download that can succeed. Without a catalog only what looks like a URL is judged.
         */
        private fun ruleSetProblem(value: String, catalog: RuleSetCatalog?): String {
            if (RuleSets.isUrl(value) && UrlText.parse(value)?.host?.isNotEmpty() == true) return ""
            if (catalog != null && catalog.size > 0) {
                if (value == RuleSets.ADBLOCK_TAG || catalog.urlOf(value) != null) return ""
            } else if (!value.contains("://")) {
                return ""
            }
            return vtr(R.string.validation_route_rule_set_unknown)
        }
    }
}
