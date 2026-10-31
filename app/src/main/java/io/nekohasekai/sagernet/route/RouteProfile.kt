package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vtr

/**
 * A RouteProfile (include/database/entities/RouteProfile.h). [id] 0 means not saved yet. The desktop's raw profile
 * ([is_raw], [raw_route], [prevent_modifications]) and endpoint lists are carried verbatim so backups give them
 * back to the desktop; Android never builds a config from them, and a raw profile is never current.
 */
@Suppress("PropertyName")
class RouteProfile {
    @JvmField var id: Long = 0L
    @JvmField var name: String = ""
    @JvmField var default_outbound_id: Long = OutboundIds.PROXY
    @JvmField var is_remote: Boolean = false
    @JvmField var remote_url: String = ""
    @JvmField var auto_update: Boolean = false
    @JvmField var remote_last_update: Long = 0L
    @JvmField var is_raw: Boolean = false
    @JvmField var raw_route: String = ""
    @JvmField var prevent_modifications: Boolean = false

    /** Compact JSON int arrays of openvpn/openconnect profile ids. */
    @JvmField var endpoint_profile_ids: String = "[]"
    @JvmField var inner_hop_endpoint_ids: String = "[]"

    /** Picks between the global DNS settings and [dns], which is kept either way once set. */
    @JvmField var custom_dns: Boolean = false
    @JvmField var dns: RouteDns? = null

    @JvmField var rules: MutableList<RouteRule> = mutableListOf()

    /** The subscription group whose refreshes overwrite [rules], -1 = none; local only, never shared. */
    @JvmField var source_gid: Long = -1L

    fun copy(): RouteProfile {
        val c = RouteProfile()
        c.id = id
        c.name = name
        c.default_outbound_id = default_outbound_id
        c.is_remote = is_remote
        c.remote_url = remote_url
        c.auto_update = auto_update
        c.remote_last_update = remote_last_update
        c.is_raw = is_raw
        c.raw_route = raw_route
        c.prevent_modifications = prevent_modifications
        c.endpoint_profile_ids = endpoint_profile_ids
        c.inner_hop_endpoint_ids = inner_hop_endpoint_ids
        c.custom_dns = custom_dns
        c.dns = dns
        c.rules = rules.mapTo(ArrayList()) { it.copy() }
        c.source_gid = source_gid
        return c
    }

    /**
     * AppendSimpleRule (RouteProfile.cpp:1200-1240) for an address line ("suffix:example.com") and the simple rule of
     * [outbound] (PROXY, DIRECT, BLOCK or WARP_BYPASS), which is added at the end when missing; false when the line is
     * rejected. Values are kept once, domain/suffix/keyword lowercased (add_simple_address_rule).
     */
    fun appendSimpleAddressRule(rawLine: String, outbound: Long, catalog: RuleSetCatalog? = null): Boolean {
        val line = rawLine.trim()
        val colon = line.indexOf(':')
        if (colon < 0) return false
        val prefix = line.substring(0, colon).trim()
        if (prefix !in ADDRESS_PREFIXES) return false
        if (checkSimpleRule(line, catalog)?.severity == Severity.Error) return false
        val value = line.substring(colon + 1).trim()
        val type = when (outbound) {
            OutboundIds.PROXY -> RuleType.SIMPLE_ADDRESS_PROXY
            OutboundIds.DIRECT -> RuleType.SIMPLE_ADDRESS_BYPASS
            OutboundIds.WARP_BYPASS -> RuleType.SIMPLE_ADDRESS_WARP_BYPASS
            else -> RuleType.SIMPLE_ADDRESS_BLOCK
        }
        val existing = rules.firstOrNull { it.type == type.id }
        val rule = existing ?: RouteRule().apply {
            this.type = type.id
            name = SIMPLE_ADDRESS_NAMES.getValue(type)
            if (type == RuleType.SIMPLE_ADDRESS_BLOCK) action = "reject" else outbound_id = outbound
        }
        val values = when (prefix) {
            "domain" -> rule.domain
            "suffix" -> rule.domain_suffix
            "keyword" -> rule.domain_keyword
            "regex" -> rule.domain_regex
            "ruleset" -> rule.rule_set
            else -> rule.ip_cidr
        }
        // sing-box lowercases the host but takes these as written; a regex stays as written, lowercasing can change it.
        val stored = if (prefix == "domain" || prefix == "suffix" || prefix == "keyword") value.lowercase() else value
        if (stored !in values) values.add(stored)
        if (existing == null) rules.add(rule)
        return true
    }

    /** ActiveDns: the DNS this profile imposes; null while the global settings apply. */
    fun activeDns(): RouteDns? = if (custom_dns) dns else null

    /** The number of profile ids in [endpoint_profile_ids]. */
    fun endpointCount(): Int = RULE_IDS.findAll(endpoint_profile_ids).count()

    fun isEmpty(): Boolean = rules.all { it.isEmpty() }

    /**
     * get_used_outbounds (RouteProfile.cpp:672-685), deduplicated (D12) and limited to the rules whose JSON
     * carries an outbound, so a leftover id on a reject or sniff rule does not require that server.
     */
    fun usedOutboundIds(): List<Long> {
        val out = LinkedHashSet<Long>()
        for (rule in rules) {
            if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) continue
            val action = rule.effectiveAction()
            if (action == "route" || action == "bypass") out.add(rule.outbound_id)
        }
        return ArrayList(out)
    }

    fun directSites(): List<String> = sites(OutboundIds.DIRECT)

    fun proxySites(): List<String> = sites(OutboundIds.PROXY)

    /** A direct or proxy route rule that applies only on some networks: its [RouteRule.networkConditions] and sites. */
    class ConditionalSites(val outbound: Long, val conditions: JsonObject, val sites: List<String>)

    /** The direct and proxy route rules that apply only on some networks, in rule order, for DNS rules that follow them. */
    fun conditionalSites(): List<ConditionalSites> {
        val out = ArrayList<ConditionalSites>()
        for (rule in rules) {
            if (rule.outbound_id != OutboundIds.DIRECT && rule.outbound_id != OutboundIds.PROXY) continue
            if (rule.action != "route" || rule.invert || rule.matchesByApp()) continue
            val conditions = rule.networkConditions()
            if (conditions.isEmpty()) continue
            val sites = ruleSites(rule)
            if (sites.isNotEmpty()) out.add(ConditionalSites(rule.outbound_id, conditions, sites))
        }
        return out
    }

    /** get_direct_ips (RouteProfile.cpp:745-759) over the [unconditional] rules only. */
    fun directIps(): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.outbound_id != OutboundIds.DIRECT || rule.action != "route" || !unconditional(rule)) continue
            for (entry in rule.rule_set) {
                val e = entry.trim()
                if (e.startsWith("geoip-")) out.add("ruleset:$e")
            }
            addPrefixed(out, "ip:", rule.ip_cidr)
        }
        return out
    }

    /** get_hijacked_ips (RouteProfile.cpp:761-772): the CIDRs non-direct route and reject rules take away from direct. */
    fun hijackedIps(privateRanges: List<String>): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.action == "route" && rule.outbound_id == OutboundIds.DIRECT) continue
            if (rule.action != "route" && rule.action != "reject") continue
            if (rule.ip_is_private) addPrefixed(out, "", privateRanges)
            addPrefixed(out, "", rule.ip_cidr)
        }
        return out
    }

    /** get_direct_sites / get_proxy_sites (RouteProfile.cpp:697-743) over the [unconditional] rules only. */
    private fun sites(outbound: Long): List<String> {
        val out = ArrayList<String>()
        for (rule in rules) {
            if (rule.outbound_id != outbound || rule.action != "route" || !unconditional(rule)) continue
            out.addAll(ruleSites(rule))
        }
        return out
    }

    private fun ruleSites(rule: RouteRule): List<String> {
        val out = ArrayList<String>()
        for (entry in rule.rule_set) {
            val e = entry.trim()
            if (e.startsWith("geosite-")) out.add("ruleset:$e")
        }
        addPrefixed(out, "domain:", rule.domain)
        addPrefixed(out, "suffix:", rule.domain_suffix)
        addPrefixed(out, "keyword:", rule.domain_keyword)
        addPrefixed(out, "regex:", rule.domain_regex)
        return out
    }

    /**
     * Whether the rule's sites and addresses may become DNS rules and tun routes that hold on every network for
     * every app. Unlike the desktop, inverted rules (their values are what they do NOT match), app rules and
     * network-dependent rules are left out; the route rule alone decides for them.
     */
    private fun unconditional(rule: RouteRule): Boolean =
        !rule.invert && !rule.matchesByApp() && rule.networkConditions().isEmpty()

    private fun addPrefixed(out: MutableList<String>, prefix: String, values: List<String>) {
        for (value in values) {
            val v = value.trim()
            if (v.isNotEmpty()) out.add(prefix + v)
        }
    }

    companion object {
        private val RULE_IDS = Regex("-?\\d+")

        /** The member each Basic-tab "prefix:value" line fills (add_simple_address_rule / add_simple_process_rule). */
        private val SIMPLE_RULE_ATTRS = mapOf(
            "domain" to "domain", "suffix" to "domain_suffix", "keyword" to "domain_keyword", "regex" to "domain_regex",
            "ruleset" to "rule_set", "ip" to "ip_cidr", "processName" to "process_name", "processPath" to "process_path",
        )

        private val ADDRESS_PREFIXES = setOf("domain", "suffix", "keyword", "regex", "ruleset", "ip")

        /** ruleTypeToString (RouteRule.h:47-56): the names get_simple_rules gives the address rules. */
        private val SIMPLE_ADDRESS_NAMES = mapOf(
            RuleType.SIMPLE_ADDRESS_PROXY to "Simple Address Proxy",
            RuleType.SIMPLE_ADDRESS_BYPASS to "Simple Address Bypass",
            RuleType.SIMPLE_ADDRESS_BLOCK to "Simple Address Block",
            RuleType.SIMPLE_ADDRESS_WARP_BYPASS to "Simple Address Warp-bypass",
        )

        /**
         * RouteProfile::CheckSimpleRule: one "prefix:value" line of the desktop's Basic tab on its own, null when
         * acceptable. Android edits no simple rules; only [appendSimpleAddressRule] applies them, for subscription imports.
         */
        @JvmStatic
        @JvmOverloads
        fun checkSimpleRule(rawLine: String, catalog: RuleSetCatalog? = null): RuleIssue? {
            val line = rawLine.trim()
            if (line.isEmpty()) return null
            val colon = line.indexOf(':')
            val prefix = if (colon < 0) "" else line.substring(0, colon).trim()
            val attr = SIMPLE_RULE_ATTRS[prefix] ?: return RuleIssue(Severity.Error, "", vtr(R.string.validation_route_simple_prefix))
            var value = line.substring(colon + 1).trim()
            if (value.isEmpty()) return RuleIssue(Severity.Error, attr, vtr(R.string.validation_route_simple_value, prefix))
            // Stored lowercased by add_simple_address_rule.
            if (prefix == "domain" || prefix == "suffix" || prefix == "keyword") value = value.lowercase()
            return RouteRule.checkValue(attr, value, catalog)
        }

        /** GetDefaultChain (RouteProfile.cpp:661-670). */
        fun defaultProfile(): RouteProfile = RouteProfile().apply {
            name = "Default"
            rules.add(RouteRule().apply {
                name = "Route DNS"
                action = "hijack-dns"
                protocol = "dns"
            })
        }
    }
}
