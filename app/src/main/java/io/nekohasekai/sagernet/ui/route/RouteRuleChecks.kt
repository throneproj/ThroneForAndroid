package io.nekohasekai.sagernet.ui.route

import android.content.Context
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.route.RuleSets
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** The rule editor's input checks, matching what sing-box accepts for each field. */
internal object RouteRuleChecks {

    /** An IPv4/IPv6 address or CIDR (sing-box takes both in ip_cidr / source_ip_cidr). */
    fun isAddressOrCidr(text: String): Boolean {
        val t = text.trim()
        val ipv6 = t.contains(':')
        return if (t.contains('/')) SettingValidators.isCidr(t, ipv6) else SettingValidators.isCidr(t + if (ipv6) "/128" else "/32", ipv6)
    }

    fun isPort(text: String): Boolean = portNumber(text.trim()) != null

    /** `start:end`, `:end` or `start:` (route/rule/rule_item_port_range.go). */
    fun isPortRange(text: String): Boolean {
        val t = text.trim()
        val colon = t.indexOf(':')
        if (colon < 0 || colon != t.lastIndexOf(':')) return false
        val start = t.substring(0, colon).let { if (it.isEmpty()) 0 else portNumber(it) ?: return false }
        val end = t.substring(colon + 1).let { if (it.isEmpty()) 65535 else portNumber(it) ?: return false }
        return start <= end
    }

    private fun portNumber(s: String): Int? =
        if (s.isNotEmpty() && s.length <= 5 && s.all { it in '0'..'9' }) s.toInt().takeIf { it <= 65535 } else null

    /** Why [pattern] would fail as a Go regular expression, or null. */
    fun regexError(context: Context, pattern: String): String? {
        if (hasUnsupportedConstruct(pattern)) return context.getString(R.string.route_rule_regex_unsupported)
        return try {
            Pattern.compile(pattern.replace("(?P<", "(?<"))
            null
        } catch (e: PatternSyntaxException) {
            e.description ?: e.message ?: pattern
        }
    }

    /**
     * RE2 (sing-box) has no lookarounds, atomic groups or backreferences, which java.util.regex compiles. Escapes
     * and character classes are skipped so a literal "(?=" inside `[...]` or after a backslash does not count.
     */
    private fun hasUnsupportedConstruct(p: String): Boolean {
        var i = 0
        while (i < p.length) {
            when (p[i]) {
                '\\' -> {
                    if (i + 1 < p.length && p[i + 1] in '1'..'9') return true
                    i += 2
                    continue
                }

                '[' -> {
                    var j = i + 1
                    if (j < p.length && p[j] == '^') j++
                    if (j < p.length && p[j] == ']') j++
                    while (j < p.length && p[j] != ']') j += if (p[j] == '\\') 2 else 1
                    i = j + 1
                    continue
                }

                '(' -> if (p.startsWith("(?", i)) {
                    val rest = p.substring(i + 2)
                    if (rest.startsWith("=") || rest.startsWith("!") || rest.startsWith("<=") ||
                        rest.startsWith("<!") || rest.startsWith(">")
                    ) return true
                }
            }
            i++
        }
        return false
    }

    /** The rule-set names [catalog] does not know; URLs are not checked. An empty catalog cannot judge anything. */
    fun unknownRuleSets(entries: List<String>, catalog: RuleSetCatalog): List<String> {
        if (catalog.size == 0) return emptyList()
        return entries.map { it.trim() }
            .filter { it.isNotEmpty() && !RuleSets.isUrl(it) && catalog.urlOf(it) == null }
            .distinct()
    }

    /** One line per invalid field of [rule]; empty when every value is acceptable. */
    fun problems(context: Context, rule: RouteRule): List<String> {
        val out = ArrayList<String>()
        fun entries(@StringRes label: Int, values: List<String>, valid: (String) -> Boolean) {
            val bad = values.map { it.trim() }.filter { it.isNotEmpty() && !valid(it) }
            if (bad.isNotEmpty()) {
                out.add(context.getString(R.string.route_rule_invalid_entries, context.getString(label), bad.joinToString(", ")))
            }
        }

        fun regexes(@StringRes label: Int, values: List<String>) {
            for (value in values.map { it.trim() }.filter { it.isNotEmpty() }) {
                val error = regexError(context, value) ?: continue
                out.add(context.getString(R.string.route_rule_invalid_regex, context.getString(label), value, error))
            }
        }

        entries(R.string.route_rule_ip_cidr, rule.ip_cidr, ::isAddressOrCidr)
        entries(R.string.route_rule_source_ip_cidr, rule.source_ip_cidr, ::isAddressOrCidr)
        entries(R.string.route_rule_port, rule.port, ::isPort)
        entries(R.string.route_rule_source_port, rule.source_port, ::isPort)
        entries(R.string.route_rule_port_range, rule.port_range, ::isPortRange)
        entries(R.string.route_rule_source_port_range, rule.source_port_range, ::isPortRange)
        entries(R.string.route_rule_override_port, listOf(rule.override_port), ::isPort)
        regexes(R.string.route_rule_domain_regex, rule.domain_regex)
        regexes(R.string.route_rule_process_path_regex, rule.process_path_regex)
        regexes(R.string.route_rule_package_name_regex, rule.package_name_regex)
        return out
    }

    /** The match attributes, i.e. everything that narrows which connections the rule applies to. */
    fun hasConditions(rule: RouteRule): Boolean = rule.nonDefaultAttributes().any { it !in ACTION_OPTIONS }

    private val ACTION_OPTIONS = setOf(
        "invert", "override_address", "override_port", "tls_spoof", "tls_spoof_method", "reject_method", "no_drop",
        "sniff_override_dest", "strategy",
    )
}
