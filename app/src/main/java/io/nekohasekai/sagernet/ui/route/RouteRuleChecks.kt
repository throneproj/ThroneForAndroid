package io.nekohasekai.sagernet.ui.route

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleEnv
import io.nekohasekai.sagernet.route.RuleIssue
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr

/**
 * The rule editor's checks on top of [RouteRule.validate] (RouteItem.cpp binds the same issues per field). Issue
 * attributes are the member names, i.e. the editor's preference keys.
 */
internal object RouteRuleChecks {

    /** An IPv4/IPv6 address or CIDR (sing-box takes both in ip_cidr / source_ip_cidr). */
    fun isAddressOrCidr(text: String): Boolean = text.isNotBlank() && RouteRule.checkValue("ip_cidr", text) == null

    /** One port, 0 included (the core's Listable[uint16]). */
    fun isPort(text: String): Boolean = text.isNotBlank() && RouteRule.checkValue("port", text) == null

    /** `start:end`, `:end` or `start:` (route/rule/rule_item_port_range.go); a reversed range only warns. */
    fun isPortRange(text: String): Boolean = text.isNotBlank() && RouteRule.checkValue("port_range", text)?.severity != Severity.Error

    /** Why [pattern] would fail as a Go regular expression, or null. */
    @Suppress("UNUSED_PARAMETER")
    fun regexError(context: Context, pattern: String): String? = Validation.goRegex(pattern).ifEmpty { null }

    /** The rule-set entries [catalog] does not know and that are no usable .srs URL. An empty catalog judges only URLs. */
    fun unknownRuleSets(entries: List<String>, catalog: RuleSetCatalog): List<String> = entries.map { it.trim() }
        .filter { it.isNotEmpty() && RouteRule.checkValue("rule_set", it, catalog)?.severity == Severity.Error }
        .distinct()

    /**
     * [RouteRule.editorIssues] against the stored profiles (the database allows main-thread reads); rule-set names are
     * judged only with a [catalog] (RouteManager.catalog(), which may read a file: off the main thread).
     */
    fun issues(rule: RouteRule, catalog: RuleSetCatalog? = null): List<RuleIssue> =
        rule.editorIssues(RuleEnv(catalog, RuleEnv.storedProfiles()))

    /** One line per error of [rule], which the core would refuse or which leaves the rule not doing what it says. */
    fun problems(context: Context, rule: RouteRule): List<String> =
        issues(rule).filter { it.severity == Severity.Error }.map { describe(context, it) }

    /** One line per warning of [rule]; none of them keeps the rule from saving. */
    fun warnings(context: Context, rule: RouteRule, catalog: RuleSetCatalog? = null): List<String> =
        issues(rule, catalog).filter { it.severity == Severity.Warning }.map { describe(context, it) }

    /**
     * The message a field shows for [severity]: the first issue of its own ([RuleIssue.attr] is [key], no related
     * attribute), as RouteItem::bindAttributeEditor does; "" when there is none.
     */
    fun fieldMessage(issues: List<RuleIssue>, key: String, severity: Severity): String =
        issues.firstOrNull { it.attr == key && it.related.isEmpty() && it.severity == severity }?.message.orEmpty()

    /** [issue] with the field's label and, for a list item, the item: "Port range ("2000:1000"): …". */
    fun describe(context: Context, issue: RuleIssue): String {
        val label = label(context, issue.attr)
        if (issue.value.isEmpty()) return context.getString(R.string.validation_route_issue, label, issue.message)
        return context.getString(R.string.validation_route_issue_value, label, elide(issue.value), issue.message)
    }

    /** The rule editor's title of the member [attr], or the name itself for one without a row. */
    fun label(context: Context, attr: String): String = LABELS[attr]?.let { context.getString(it) } ?: attr

    fun elide(text: String): String = if (text.length > 40) text.take(39) + "…" else text

    /**
     * RouteItem's routeItemFirstWarningLine: the first line of [text] whose [check] (RouteRule.checkValue of the row,
     * given the trimmed line) is a warning, worded like the form's line errors.
     */
    fun firstWarningLine(text: String, check: (String) -> RuleIssue?): String {
        text.split('\n').forEachIndexed { i, raw ->
            val line = raw.trim()
            val issue = check(line) ?: return@forEachIndexed
            if (issue.severity == Severity.Warning) return vtr(R.string.validation_form_line, (i + 1).toString(), elide(line), issue.message)
        }
        return ""
    }

    /** The error [check] reports for one line, or "". */
    fun lineError(line: String, check: (String) -> RuleIssue?): String =
        check(line)?.takeIf { it.severity == Severity.Error }?.message.orEmpty()

    private val LABELS: Map<String, Int> = mapOf(
        "action" to R.string.route_rule_action,
        "outbound_id" to R.string.route_rule_outbound,
        "reject_method" to R.string.route_rule_reject_method,
        "no_drop" to R.string.route_rule_no_drop,
        "strategy" to R.string.route_rule_strategy,
        "sniff_override_dest" to R.string.route_rule_override_destination,
        "domain" to R.string.route_rule_domain,
        "domain_suffix" to R.string.route_rule_domain_suffix,
        "domain_keyword" to R.string.route_rule_domain_keyword,
        "domain_regex" to R.string.route_rule_domain_regex,
        "ip_cidr" to R.string.route_rule_ip_cidr,
        "ip_is_private" to R.string.route_rule_ip_is_private,
        "source_ip_cidr" to R.string.route_rule_source_ip_cidr,
        "source_ip_is_private" to R.string.route_rule_source_ip_is_private,
        "port" to R.string.route_rule_port,
        "port_range" to R.string.route_rule_port_range,
        "source_port" to R.string.route_rule_source_port,
        "source_port_range" to R.string.route_rule_source_port_range,
        "network" to R.string.route_rule_network,
        "protocol" to R.string.route_rule_protocol,
        "ip_version" to R.string.route_rule_ip_version,
        "inbound" to R.string.route_rule_inbound,
        "invert" to R.string.route_rule_invert,
        "rule_set" to R.string.route_rule_rule_sets,
        "package_name" to R.string.route_rule_apps,
        "package_name_regex" to R.string.route_rule_package_name_regex,
        "network_type" to R.string.route_rule_network_type,
        "network_is_expensive" to R.string.route_rule_network_is_expensive,
        "process_name" to R.string.route_rule_process_name,
        "process_path" to R.string.route_rule_process_path,
        "process_path_regex" to R.string.route_rule_process_path_regex,
        "wifi_ssid" to R.string.route_rule_wifi_ssid,
        "wifi_bssid" to R.string.route_rule_wifi_bssid,
        "override_address" to R.string.route_rule_override_address,
        "override_port" to R.string.route_rule_override_port,
        "tls_fragment" to R.string.route_rule_tls_fragment,
        "tls_fragment_fallback_delay" to R.string.route_rule_tls_fragment_fallback_delay,
        "tls_record_fragment" to R.string.route_rule_tls_fragment,
    )
}
