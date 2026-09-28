package io.nekohasekai.sagernet.ui.route

import android.content.Context
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleSets

/** Display texts shared by the routing screens. Action and field tokens are the sing-box / desktop names. */
internal object RouteTexts {

    const val WARP_BYPASS = "warp-bypass"

    /** proxy, direct, block, warp-bypass, the server's name from [servers], or "missing server". */
    fun outboundName(context: Context, id: Long, servers: Map<Long, String>): String = when (id) {
        OutboundIds.PROXY, OutboundIds.DIRECT, OutboundIds.BLOCK, OutboundIds.WARP_BYPASS -> OutboundIds.toName(id)
        OutboundIds.HIJACK_DNS -> "hijack-dns"
        else -> servers[id] ?: context.getString(R.string.route_rule_missing_server, id)
    }

    /** The rule's effect: "route → direct", "reject", "sniff", …, coloured like the old route list. */
    fun ruleAction(context: Context, rule: RouteRule, servers: Map<Long, String>): CharSequence {
        val action = rule.effectiveAction()
        val text: String
        val color: Int
        when (action) {
            "route", "bypass" -> {
                text = action + " → " + outboundName(context, rule.outbound_id, servers)
                color = when (rule.outbound_id) {
                    OutboundIds.DIRECT -> R.color.color_route_direct
                    else -> R.color.color_route_proxy
                }
            }

            "reject" -> {
                text = if (rule.reject_method.isBlank()) action else action + " (" + rule.reject_method.trim() + ")"
                color = R.color.color_route_block
            }

            "resolve" -> {
                text = if (rule.strategy.isBlank()) action else action + " (" + rule.strategy.trim() + ")"
                color = R.color.color_route_config
            }

            else -> {
                text = action
                color = R.color.color_route_config
            }
        }
        return SpannableStringBuilder(text).apply {
            setSpan(ForegroundColorSpan(ContextCompat.getColor(context, color)), 0, length, 0)
        }
    }

    /** The main conditions, e.g. "suffix: google.com +2 · rule-set: geosite-ir". */
    fun ruleConditions(context: Context, rule: RouteRule, appLabels: Map<String, String> = emptyMap()): String {
        val parts = ArrayList<String>()
        fun list(label: String, values: List<String>, show: (String) -> String = { it }) {
            val items = values.map { it.trim() }.filter { it.isNotEmpty() }
            if (items.isEmpty()) return
            parts.add(label + ": " + show(items[0]) + if (items.size > 1) " +" + (items.size - 1) else "")
        }

        fun scalar(label: String, value: String) {
            if (value.isNotBlank()) parts.add(label + ": " + value.trim())
        }

        list("suffix", rule.domain_suffix)
        list("domain", rule.domain)
        list("keyword", rule.domain_keyword)
        list("regex", rule.domain_regex)
        list("rule-set", rule.rule_set) { RuleSetLabels.shortName(it) }
        list("ip", rule.ip_cidr)
        if (rule.ip_is_private) parts.add(context.getString(R.string.route_summary_private_ip))
        list("app", rule.package_name) {
            if (it == RouteRule.UNKNOWN_PACKAGE) context.getString(R.string.route_rule_unknown_apps) else appLabels[it] ?: it
        }
        list("process", rule.process_name)
        list("path", rule.process_path)
        list("path regex", rule.process_path_regex)
        list("port", rule.port)
        list("port range", rule.port_range)
        scalar("protocol", rule.protocol)
        scalar("network", rule.network)
        scalar("ip version", rule.ip_version)
        list("source ip", rule.source_ip_cidr)
        if (rule.source_ip_is_private) parts.add(context.getString(R.string.route_summary_private_source_ip))
        list("source port", rule.source_port)
        list("source port range", rule.source_port_range)
        list("inbound", rule.inbound)
        list("network type", rule.network_type)
        if (rule.network_is_expensive) parts.add(context.getString(R.string.route_summary_metered))
        list("ssid", rule.wifi_ssid)
        list("bssid", rule.wifi_bssid)
        if (parts.isEmpty()) {
            parts.add(context.getString(R.string.route_summary_no_conditions))
        }
        if (rule.invert) parts.add(context.getString(R.string.route_summary_inverted))
        return parts.joinToString(" · ")
    }

    /** Epoch seconds as a short date and time. */
    fun dateTime(context: Context, epochSeconds: Long): String = DateUtils.formatDateTime(
        context, epochSeconds * 1000,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
    )
}

/** Server profiles as route targets. Both read the database: call them off the main thread. */
internal object RouteServers {

    /**
     * Every server profile as (id, "[group] name"), groups and profiles in their list order. Auto selectors are left
     * out: they are not a fixed server, so a rule routing to one fails the build (generate.cpp:1281-1286).
     */
    fun list(): List<Pair<Long, String>> {
        val out = ArrayList<Pair<Long, String>>()
        for (group in SagerDatabase.groupDao.allGroups()) {
            for (profile in SagerDatabase.proxyDao.getByGroup(group.id)) {
                if (profile.type == "autoselector") continue
                out.add(profile.id to "[" + group.displayName() + "] " + profile.displayName())
            }
        }
        return out
    }

    /** The display names of the server profiles among [ids]; missing ones are left out. */
    fun names(ids: Collection<Long>): Map<Long, String> {
        val wanted = ids.filter { it > 0 }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        return ProfileManager.getProfiles(wanted).associate { it.id to it.displayName() }
    }
}

internal object RuleSetLabels {

    /** A rule-set name as is; an .srs URL as its file name. */
    fun shortName(entry: String): String =
        if (RuleSets.isUrl(entry)) Uri.parse(entry.trim()).lastPathSegment ?: entry else entry

    /** Where a rule-set comes from: "owner/repo" for GitHub files, else the host. */
    fun source(url: String): String {
        val uri = Uri.parse(url.trim())
        val host = uri.host ?: return url
        if (host.equals("raw.githubusercontent.com", true) || host.equals("github.com", true)) {
            val segments = uri.pathSegments
            if (segments.size >= 2) return segments[0] + "/" + segments[1]
        }
        return host
    }
}
