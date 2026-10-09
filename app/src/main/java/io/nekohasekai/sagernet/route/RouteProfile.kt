package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.outbound.json.JsonObject

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
    @JvmField var rules: MutableList<RouteRule> = mutableListOf()

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
        c.rules = rules.mapTo(ArrayList()) { it.copy() }
        return c
    }

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
