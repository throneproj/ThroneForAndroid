package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.JsonWriter
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vtr

/** The desktop's route share formats (RouteProfile.cpp). */
object RouteShare {
    const val ROUTE_LINK_PREFIX = "throne://route/"
    const val REMOTE_ROUTE_LINK_PREFIX = "throne://remoteroute/"
    private const val KIND = "throne-route-profile"

    /** The keys a rule object may carry: name, type, outbound and disabled besides the ones [setField] reads. */
    private val RULE_KEYS = setOf(
        "name", "type", "outbound", "action", "ip_version", "network", "protocol", "inbound", "domain", "domain_suffix",
        "domain_keyword", "domain_regex", "source_ip_cidr", "source_ip_is_private", "ip_cidr", "ip_is_private",
        "source_port", "source_port_range", "port", "port_range", "process_name", "process_path", "process_path_regex",
        "package_name", "package_name_regex", "network_type", "network_is_expensive", "wifi_ssid", "wifi_bssid",
        "rule_set", "invert", "method", "reject_method", "no_drop", "override_address", "override_port", "tls_fragment",
        "tls_fragment_fallback_delay", "tls_record_fragment", "tls_spoof", "tls_spoof_method", "override_destination",
        "strategy", "sniffers", "disabled",
    )

    class Imported(val profile: RouteProfile?, val fatal: String, val warnings: List<String>, val legacyArray: Boolean)

    class RemoteEntry(val url: String, val name: String)

    /** ToShareObject for a structured profile; rules whose server is missing are left out. */
    fun toShareObject(p: RouteProfile, profileName: (Long) -> String?): JsonObject {
        val root = JsonObject()
        root["kind"] = KIND
        root["v"] = 1
        root["name"] = p.name
        p.activeDns()?.let { root["dns"] = it.toJson() }
        root["default_outbound"] = OutboundIds.toName(p.default_outbound_id)
        val rules = JsonArray()
        for (rule in p.rules) {
            val type = RuleType.ofId(rule.type)
            if (type == RuleType.ENDPOINT_PREFERRED_BY) continue
            if (type != RuleType.CUSTOM && rule.isEmpty()) continue
            val obj = rule.toRuleJson(true, null, profileName)
            if (obj.isEmpty()) continue
            obj["name"] = rule.name
            obj["type"] = type.token
            if (rule.disabled) obj["disabled"] = true
            rules.add(obj)
        }
        root["rules"] = rules
        return root
    }

    /** ToShareLink: compact key-sorted JSON, base64url without padding. */
    fun toShareLink(p: RouteProfile, profileName: (Long) -> String?): String =
        ROUTE_LINK_PREFIX + Base64Strict.encode(JsonWriter.write(toShareObject(p, profileName)), urlSafe = true, padding = false)

    /**
     * FromShareInput: a throne://route link (any case), share JSON, base64url or standard base64 of it with or without
     * padding, or a legacy rule array. Raw profiles are refused; endpoints and endpoint rules are dropped with a
     * warning (D11), as are rules with fields Android does not support ([parseRuleObject]); a root `dns` object is
     * adopted as the profile's own DNS when the core could use it ([adoptDns]). Rule outbound names
     * resolve through [profileIdByName]; [env] gives the rule-set list and the profiles the rules are validated
     * against (the default reads them, so call this off the main thread).
     */
    fun fromShareInput(text: String, profileIdByName: (String) -> Long?, env: RuleEnv = RuleEnv.stored()): Imported {
        var input = text.trim()
        if (input.isEmpty()) return failure("Empty input")
        if (input.startsWith(ROUTE_LINK_PREFIX, ignoreCase = true)) {
            input = UrlText.deepLinkPayload(input, ROUTE_LINK_PREFIX.length)
            if (input.isEmpty()) return failure("Deep link has no data")
        }
        val doc = parseDocument(input)
            ?: parseDocument(String(Base64Strict.decodeLenient(input, urlSafe = true), Charsets.UTF_8))
            ?: parseDocument(String(Base64Strict.decodeLenient(input, urlSafe = false), Charsets.UTF_8))
            ?: return failure("Input is not valid JSON, base64, or a Throne route link")
        val warnings = ArrayList<String>()
        return when (doc) {
            is JsonObject -> fromObject(doc, warnings, profileIdByName, env)
            is JsonArray -> fromLegacyArray(doc, warnings, profileIdByName, env)
            else -> failure("Unsupported input")
        }
    }

    /**
     * FromRemoteRoutesLink: null when [text] is not a throne://remoteroute link; for an invalid one an
     * [IllegalArgumentException] carries the desktop's error text, so a returned list is never empty.
     */
    fun fromRemoteRoutesLink(text: String): List<RemoteEntry>? {
        val input = text.trim()
        if (!input.startsWith(REMOTE_ROUTE_LINK_PREFIX, ignoreCase = true)) return null
        val base64 = UrlText.deepLinkPayload(input, REMOTE_ROUTE_LINK_PREFIX.length)
        if (base64.isEmpty()) throw IllegalArgumentException("Deep link has no data")
        val data = Base64Strict.decodeToString(base64)
        if (data.isEmpty()) throw IllegalArgumentException("Base64 is invalid.")
        val out = ArrayList<RemoteEntry>()
        for (line in data.split('\n')) {
            val entry = line.trim()
            if (!entry.startsWith("http://", ignoreCase = true) && !entry.startsWith("https://", ignoreCase = true)) continue
            val hash = entry.indexOf('#')
            val url = if (hash >= 0) entry.substring(0, hash) else entry
            val host = UrlText.parse(url)?.host.orEmpty()
            if (host.isEmpty()) continue
            val name = if (hash >= 0) UrlText.percentDecode(entry.substring(hash + 1)) else ""
            out.add(RemoteEntry(url, name.ifEmpty { host }))
        }
        if (out.isEmpty()) throw IllegalArgumentException("The link did not contain any valid http(s) routing profile URLs.")
        return out
    }

    private fun failure(fatal: String) = Imported(null, fatal, emptyList(), false)

    private fun parseDocument(text: String): Any? {
        if (text.isBlank()) return null
        val value = JsonInput.parseValue(text)
        return if (value is JsonObject || value is JsonArray) value else null
    }

    private fun fromObject(root: JsonObject, warnings: MutableList<String>, profileIdByName: (String) -> Long?, env: RuleEnv): Imported {
        if (root.string("kind") != KIND) return failure("Unrecognized route object")
        if (root.bool("raw")) return failure("raw routing profiles are not supported on Android")
        val profile = RouteProfile()
        profile.name = root.string("name")
        val defaultOutbound = root.string("default_outbound")
        profile.default_outbound_id = OutboundIds.fromName(defaultOutbound) ?: OutboundIds.PROXY
        if (defaultOutbound.isNotEmpty() && OutboundIds.toName(profile.default_outbound_id) != defaultOutbound) {
            warnings.add(vtr(R.string.validation_route_default_outbound, defaultOutbound))
        }
        if (root.array("endpoints").isNotEmpty()) warnings.add("endpoints are not supported on Android and were dropped")
        var fallbackNum = 1
        for ((i, value) in root.array("rules").withIndex()) {
            if (value !is JsonObject) {
                warnings.add(vtr(R.string.validation_route_rule_not_object, (i + 1).toString()))
                continue
            }
            val type = RuleType.ofToken(value.string("type"))
            val name = value.string("name")
            if (type == RuleType.ENDPOINT_PREFERRED_BY) {
                warnings.add("endpoint rule \"$name\" dropped: endpoints are not supported on Android")
                continue
            }
            val rule = parseRuleObject(value, name.ifEmpty { "#${i + 1}" }, warnings, profileIdByName, env) ?: continue
            rule.type = type.id
            rule.name = name.ifEmpty { "rule_" + fallbackNum++ }
            profile.rules.add(rule)
        }
        adoptDns(profile, root, warnings)
        return Imported(profile, "", warnings, false)
    }

    /** routeProfileAdoptDns: a usable `dns` object becomes the profile's own DNS; another is dropped with a warning. */
    private fun adoptDns(profile: RouteProfile, root: JsonObject, warnings: MutableList<String>) {
        if (!root.isObject("dns")) return
        val dns = RouteDns.fromJson(root.obj("dns"))
        val problem = dns.problem()
        if (problem.isNotEmpty()) {
            warnings.add(vtr(R.string.validation_route_dns_dropped, problem))
            return
        }
        profile.custom_dns = true
        profile.dns = dns
    }

    /**
     * parseJsonArray: the legacy bare rule array. An array is nothing but its rules, so one whose rules are all
     * dropped fails like an empty one.
     */
    private fun fromLegacyArray(arr: JsonArray, warnings: MutableList<String>, profileIdByName: (String) -> Long?, env: RuleEnv): Imported {
        if (arr.isEmpty()) return failure("Input is not a valid json array")
        val profile = RouteProfile()
        var ruleId = 1
        for ((i, item) in arr.withIndex()) {
            if (item !is JsonObject) return failure("expected array of json objects but have member of type '${qtJsonType(item)}'")
            val name = item.string("name")
            val rule = parseRuleObject(item, name.ifEmpty { "#${i + 1}" }, warnings, profileIdByName, env) ?: continue
            rule.name = name.ifEmpty { "imported rule #" + ruleId++ }
            profile.rules.add(rule)
        }
        if (profile.rules.isEmpty()) return failure("No rule in the array can be imported:\n" + warnings.joinToString("\n"))
        return Imported(profile, "", warnings, true)
    }

    /**
     * parse_rule_object plus the D12 fixes: warp-bypass/block names, reject_method. A rule with a key outside
     * [RULE_KEYS] is dropped whole, with a warning naming it by [label], and null returned: importing it without that
     * field would widen its match. The parsed rule is then validated ([checkImportedRule]).
     */
    private fun parseRuleObject(
        obj: JsonObject,
        label: String,
        warnings: MutableList<String>,
        profileIdByName: (String) -> Long?,
        env: RuleEnv,
    ): RouteRule? {
        val keys = obj.sortedKeys()
        keys.firstOrNull { it !in RULE_KEYS }?.let {
            warnings.add("rule \"$label\" dropped: unsupported field \"$it\"")
            return null
        }
        val rule = RouteRule()
        // The caller sets it too; validation needs it now, since simple rules have fixed actions.
        rule.type = RuleType.ofToken(obj.string("type")).id
        for (key in keys) {
            if (key == "name" || key == "type") continue
            val value = obj[key]
            // QJsonValue::toBool: anything but a JSON true leaves the rule enabled; an endpoint rule cannot be disabled.
            if (key == "disabled") {
                rule.disabled = value == true && rule.type != RuleType.ENDPOINT_PREFERRED_BY.id
                continue
            }
            if (key == "outbound") {
                rule.outbound_id = when (value) {
                    is Long, is Double -> when (val id = JsonValues.toInt(value).toLong()) {
                        OutboundIds.PROXY, OutboundIds.DIRECT, OutboundIds.BLOCK, OutboundIds.WARP_BYPASS -> id
                        else -> {
                            warnings.add("outbound id $id not found, using proxy")
                            OutboundIds.PROXY
                        }
                    }

                    is String -> OutboundIds.fromName(value) ?: profileIdByName(value) ?: run {
                        warnings.add("outbound \"$value\" not found, using proxy")
                        OutboundIds.PROXY
                    }

                    else -> {
                        warnings.add(vtr(R.string.validation_route_rule_outbound_type, label))
                        OutboundIds.PROXY
                    }
                }
                continue
            }
            val items = ArrayList<String>()
            var usable = true
            if (value is JsonArray) {
                for (item in value) {
                    val text = scalarText(item)
                    if (text == null) {
                        usable = false
                        break
                    }
                    items.add(text)
                }
            } else {
                val text = scalarText(value)
                if (text == null) usable = false else items.add(text)
            }
            val member = memberOf(key)
            if (!usable) {
                if (RouteRule.isMatchAttribute(member)) {
                    warnings.add(vtr(R.string.validation_route_rule_dropped_type, label, key))
                    return null
                }
                warnings.add(vtr(R.string.validation_route_rule_ignored_type, label, key))
                continue
            }
            if (items.size > 1 && !RouteRule.isListAttribute(member)) {
                warnings.add(vtr(R.string.validation_route_rule_single_value, label, key, items.first()))
            }
            setField(rule, key, items)
        }
        if (!checkImportedRule(rule, label, warnings, env)) return null
        return rule
    }

    /**
     * routeProfileCheckImportedRule: a bad condition drops the rule, which would otherwise match more; a bad option
     * is cleared to the core's default.
     */
    private fun checkImportedRule(rule: RouteRule, label: String, warnings: MutableList<String>, env: RuleEnv): Boolean {
        // Each pass repairs one error, and every repair removes it.
        for (pass in 0 until 64) {
            val issues = rule.validate(env)
            val error = issues.firstOrNull { it.severity == Severity.Error }
            if (error == null) {
                for (issue in issues) warnings.add(vtr(R.string.validation_route_rule_warning, label, issueText(issue)))
                return true
            }
            // Without invert one item less only narrows the rule; a custom rule whose condition loses every item would match more.
            if (!rule.invert && RouteRule.isListAttribute(error.attr) && error.value.isNotEmpty()) {
                val kept = ArrayList<String>()
                val removed = ArrayList<RuleIssue>()
                for (item in rule.currentValues(error.attr)) {
                    val issue = RouteRule.checkValue(error.attr, item, env.catalog)
                    if (issue == null || issue.severity != Severity.Error) kept.add(item) else removed.add(issue)
                }
                if (kept.isEmpty() && rule.type == RuleType.CUSTOM.id && RouteRule.isMatchAttribute(error.attr)) {
                    warnings.add(vtr(R.string.validation_route_rule_dropped, label, issueText(error)))
                    return false
                }
                for (issue in removed) warnings.add(vtr(R.string.validation_route_rule_item_removed, label, issueText(issue)))
                rule.setValues(error.attr, kept)
                continue
            }
            if (error.attr == "action" || RouteRule.isMatchAttribute(error.attr)) {
                warnings.add(vtr(R.string.validation_route_rule_dropped, label, issueText(error)))
                return false
            }
            if (error.attr == "outbound_id") {
                rule.outbound_id = OutboundIds.PROXY
                warnings.add(vtr(R.string.validation_route_rule_proxy, label, error.message))
            } else {
                rule.clearAttribute(error.attr)
                warnings.add(vtr(R.string.validation_route_rule_cleared, label, shareKey(error.attr), error.message))
            }
        }
        return false
    }

    /** routeProfileIssueText, naming the attribute by its share key. */
    private fun issueText(issue: RuleIssue): String {
        if (issue.value.isEmpty()) return vtr(R.string.validation_route_issue, shareKey(issue.attr), issue.message)
        val value = if (issue.value.length > 60) issue.value.take(59) + "…" else issue.value
        return vtr(R.string.validation_route_issue_value, shareKey(issue.attr), value, issue.message)
    }

    /** The member [setField] fills from a share [key]. */
    private fun memberOf(key: String): String = when (key) {
        "method", "reject_method" -> "reject_method"
        "override_destination" -> "sniff_override_dest"
        else -> key
    }

    /** The share key of a member, as the imported JSON names it. */
    private fun shareKey(attr: String): String = when (attr) {
        "reject_method" -> "method"
        "sniff_override_dest" -> "override_destination"
        "outbound_id" -> "outbound"
        else -> attr
    }

    /** routeProfileScalarText: objects and null carry no value a rule field can hold. */
    private fun scalarText(value: Any?): String? = when (value) {
        is Boolean -> if (value) "true" else "false"
        is String -> value
        is Long -> value.toString()
        is Double -> JsonWriter.formatDouble(value)
        else -> null
    }

    /** set_field_value plus Android's package_name_regex, network_type and network_is_expensive. */
    private fun setField(rule: RouteRule, key: String, values: List<String>) {
        val scalar = values.firstOrNull()?.trim() ?: ""
        val list = values.map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }
        when (key) {
            "ip_version" -> rule.ip_version = scalar
            "network" -> rule.network = scalar
            "protocol" -> rule.protocol = scalar
            "inbound" -> rule.inbound = list
            "domain" -> rule.domain = list
            "domain_suffix" -> rule.domain_suffix = list
            "domain_keyword" -> rule.domain_keyword = list
            "domain_regex" -> rule.domain_regex = list
            "source_ip_cidr" -> rule.source_ip_cidr = list
            "source_ip_is_private" -> rule.source_ip_is_private = scalar == "true"
            "ip_cidr" -> rule.ip_cidr = list
            "ip_is_private" -> rule.ip_is_private = scalar == "true"
            "source_port" -> rule.source_port = list
            "source_port_range" -> rule.source_port_range = list
            "port" -> rule.port = list
            "port_range" -> rule.port_range = list
            "process_name" -> rule.process_name = list
            "process_path" -> rule.process_path = list
            "process_path_regex" -> rule.process_path_regex = list
            "package_name" -> rule.package_name = list
            "package_name_regex" -> rule.package_name_regex = list
            "network_type" -> rule.network_type = list
            "network_is_expensive" -> rule.network_is_expensive = scalar == "true"
            "wifi_ssid" -> rule.wifi_ssid = list
            "wifi_bssid" -> rule.wifi_bssid = list
            "sniffers" -> rule.sniffers = list
            "rule_set" -> rule.rule_set = list
            "invert" -> rule.invert = scalar == "true"
            // The core reads an empty action as route, which get_rule_json would emit without its outbound.
            "action" -> rule.action = scalar.ifEmpty { "route" }
            "method", "reject_method" -> rule.reject_method = scalar
            "no_drop" -> rule.no_drop = scalar == "true"
            "override_address" -> rule.override_address = scalar
            "override_port" -> rule.override_port = scalar
            "tls_fragment" -> rule.tls_fragment = scalar == "true"
            "tls_fragment_fallback_delay" -> rule.tls_fragment_fallback_delay = scalar
            "tls_record_fragment" -> rule.tls_record_fragment = scalar == "true"
            "tls_spoof" -> rule.tls_spoof = scalar
            "tls_spoof_method" -> rule.tls_spoof_method = scalar
            "override_destination" -> rule.sniff_override_dest = scalar == "true"
            "strategy" -> rule.strategy = scalar
        }
    }

    /** QJsonValue::Type as the desktop prints it in the legacy-array error. */
    private fun qtJsonType(value: Any?): Int = when (value) {
        is Boolean -> 0x1
        is Long, is Double -> 0x2
        is String -> 0x3
        is JsonArray -> 0x4
        is JsonObject -> 0x5
        else -> 0x0
    }
}
