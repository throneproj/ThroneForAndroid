package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RouteShare
import io.nekohasekai.sagernet.route.RuleType
import io.nekohasekai.sagernet.route.UrlText
import io.nekohasekai.sagernet.validation.Validation

/**
 * The routing profile a subscription provides (RouteUpdater.cpp ParseSubscriptionRoute / ImportSubscriptionRoute):
 * a Happ routing profile (`happ://routing/add|onadd/<base64 or JSON>`, bare base64 or JSON) or a throne://route
 * share, from the `routing` header or a routing link in the body. It becomes the group's own routing profile, and each
 * refresh overwrites that profile's rules. DNS settings from the subscription are never used.
 */
internal object SubscriptionRoute {

    /** The response header that carries it (GroupUpdater.cpp fetch). */
    const val HEADER = "routing"

    /** [profile] is null when there is nothing to import, with [error] saying why when that is a problem. */
    class Parsed(val profile: RouteProfile?, val imported: Int, val dropped: Int, val error: String)

    /**
     * GroupUpdater.cpp:988-992: the [header], else ("off" carries nothing) the first routing link of the [body].
     */
    fun valueOf(header: String, body: String): String {
        if (header.isNotEmpty() && !header.equals("off", ignoreCase = true)) return header
        return ProfileImport.shippedRoutingLink(body).ifEmpty { header }
    }

    /** ParseSubscriptionRoute. */
    fun parse(value: String): Parsed {
        val counts = Counts()
        var error = ""
        val profile = try {
            parseRoute(value.trim(), counts)
        } catch (e: Exception) {
            error = e.message ?: e.toString()
            null
        }
        return Parsed(profile, counts.imported, counts.dropped, error)
    }

    /**
     * ImportSubscriptionRoute: saves [value]'s profile as the routing profile group [gid] owns, created on the first
     * import (and made the group's routing profile when it has none), else with its rules replaced; asks for a reload
     * when that changes the running config's routing profile.
     */
    fun importRoute(gid: Long, value: String) {
        val parsed = parse(value)
        val fetched = parsed.profile
        if (fetched == null && parsed.error.isEmpty()) return
        val group = GroupRepo.get(gid)?.takeIf { it.importSubRoute } ?: return
        val groupName = group.displayName()
        if (fetched == null) {
            Logs.w("[Routing] $groupName: the routing profile the subscription provides was ignored: ${parsed.error}")
            return
        }
        val counts = if (parsed.dropped > 0) "${parsed.imported} entries, ${parsed.dropped} skipped" else "${parsed.imported} entries"
        val current = RouteManager.ownedBy(gid).firstOrNull()?.let { RouteManager.get(it) }
        if (current == null) {
            fetched.id = 0L
            fetched.source_gid = gid
            if (fetched.name.isBlank()) fetched.name = app.getString(R.string.route_subscription_profile_name, groupName)
            RouteManager.save(fetched)
            if (group.routeId <= 0L || RouteManager.get(group.routeId) == null) SagerDatabase.groupDao.setRouteId(gid, fetched.id)
            Logs.i("[Routing] $groupName: created the routing profile \"${fetched.name}\" from the subscription ($counts)")
            val running = RouteManager.runningRouteId()
            if (running > 0L && startedInGroup(gid) && RouteManager.routeIdFor(gid) != running) SubscriptionQueue.reloadNeeded(gid)
            return
        }
        val updated = current.copy()
        RemoteRouteUpdater.replaceRules(updated, fetched)
        if (RemoteRouteUpdater.routeFingerprint(updated) == RemoteRouteUpdater.routeFingerprint(current)) return
        RouteManager.save(updated)
        Logs.i("[Routing] $groupName: updated the routing profile \"${updated.name}\" from the subscription ($counts)")
        if (updated.id == RouteManager.runningRouteId()) SubscriptionQueue.reloadNeeded(gid)
    }

    private class Counts {
        var imported = 0
        var dropped = 0
    }

    private class RouteError(message: String) : Exception(message)

    private const val ROUTING_MARKER = "://routing/"

    private fun parseRoute(input: String, counts: Counts): RouteProfile? {
        var text = input
        if (text.isEmpty() || text.equals("off", ignoreCase = true)) return null
        if (text.startsWith(RouteShare.ROUTE_LINK_PREFIX, ignoreCase = true)) return fromThroneShare(text, counts)

        val marker = text.indexOf(ROUTING_MARKER, ignoreCase = true)
        if (marker >= 0) {
            val rest = text.substring(marker + ROUTING_MARKER.length)
            val slash = rest.indexOf('/')
            val verb = (if (slash < 0) rest else rest.substring(0, slash)).trim().lowercase()
            if (verb == "off") return null
            if (verb != "add" && verb != "onadd") throw RouteError("unknown routing link action \"$verb\"")
            text = if (slash < 0) "" else UrlText.percentDecode(rest.substring(slash + 1)).trim()
            if (text.isEmpty()) return null
        }

        val json = if (text.startsWith('{')) text else decodeLenientBase64(text)
        if (json.isNullOrEmpty()) throw RouteError("the routing profile is not valid base64")
        val obj = JsonInput.parseObjectOrNull(json) ?: throw RouteError("the routing profile is not a JSON object")
        return fromHapp(obj, counts)
    }

    /** Standard or URL-safe, padded or not, possibly wrapped. */
    private fun decodeLenientBase64(text: String): String? {
        val sb = StringBuilder(text.length)
        for (c in text) {
            when {
                c.isWhitespace() -> {}
                c == '-' -> sb.append('+')
                c == '_' -> sb.append('/')
                else -> sb.append(c)
            }
        }
        val bytes = Base64Strict.decode(sb.trimEnd('=').toString()) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** happValue: Happ keys match case-insensitively; null when absent. */
    private fun happValue(obj: JsonObject, key: String): Any? =
        obj.keys().firstOrNull { it.equals(key, ignoreCase = true) }?.let { obj[it] }

    private fun happList(value: Any?): List<String> = when (value) {
        is JsonArray -> value.filterIsInstance<String>()
        is String -> value.split(',').filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private sealed class HappEntry {
        object Skip : HappEntry()
        object Dropped : HappEntry()
        object PrivateIp : HappEntry()
        class Line(val line: String) : HappEntry()
    }

    /** happEntry: an Xray-style entry as a simple-rule line ("suffix:example.com", "ruleset:geosite-google", "ip:10.0.0.0/8"). */
    private fun happEntry(raw: String, ipList: Boolean): HappEntry {
        val entry = raw.trim()
        if (entry.isEmpty()) return HappEntry.Skip
        if (entry.startsWith('!') || entry.contains('@')) return HappEntry.Dropped
        if ((entry.contains('.') || entry.contains(':')) && Validation.cidr(entry, allowBareIp = true).isEmpty()) {
            return HappEntry.Line("ip:$entry")
        }
        val colon = entry.indexOf(':')
        if (colon < 0) return if (ipList) HappEntry.Dropped else HappEntry.Line("keyword:" + entry.lowercase())
        val prefix = entry.substring(0, colon).trim().lowercase()
        val value = entry.substring(colon + 1).trim()
        if (value.isEmpty()) return HappEntry.Dropped
        if (prefix == "geoip" && value.equals("private", ignoreCase = true)) return HappEntry.PrivateIp
        return when (prefix) {
            "geosite", "geoip" -> HappEntry.Line("ruleset:$prefix-" + value.lowercase())
            "domain" -> HappEntry.Line("suffix:" + value.lowercase())
            "full" -> HappEntry.Line("domain:" + value.lowercase())
            "regexp" -> HappEntry.Line("regex:$value")
            "keyword" -> HappEntry.Line("keyword:" + value.lowercase())
            else -> HappEntry.Dropped
        }
    }

    private val HAPP_KEYS = listOf("Name", "GlobalProxy", "DirectSites", "DirectIp", "ProxySites", "ProxyIp", "BlockSites", "BlockIp")

    /** fromHapp: simple rules in RouteOrder (block, proxy, direct by default), private ranges as rules of their own. */
    private fun fromHapp(obj: JsonObject, counts: Counts): RouteProfile {
        if (HAPP_KEYS.none { happValue(obj, it) != null }) throw RouteError("the routing profile is not in the Happ format")

        val profile = RouteProfile()
        profile.name = (happValue(obj, "Name") as? String).orEmpty().trim()
        val global = happValue(obj, "GlobalProxy")
        val globalProxy = if (global is Boolean) global else !(global as? String).orEmpty().trim().equals("false", ignoreCase = true)
        profile.default_outbound_id = if (globalProxy) OutboundIds.PROXY else OutboundIds.DIRECT

        val order = LinkedHashSet<Long>()
        val orderValue = happValue(obj, "RouteOrder")
        val words = if (orderValue is JsonArray) happList(orderValue)
        else (orderValue as? String).orEmpty().split(NOT_LETTERS).filter { it.isNotEmpty() }
        for (word in words) {
            when (word.trim().lowercase()) {
                "block" -> order.add(OutboundIds.BLOCK)
                "proxy" -> order.add(OutboundIds.PROXY)
                "direct" -> order.add(OutboundIds.DIRECT)
            }
        }
        order.addAll(listOf(OutboundIds.BLOCK, OutboundIds.PROXY, OutboundIds.DIRECT))

        val catalog = RouteManager.catalog()
        for (target in order) {
            val (sitesKey, ipKey) = when (target) {
                OutboundIds.BLOCK -> "BlockSites" to "BlockIp"
                OutboundIds.PROXY -> "ProxySites" to "ProxyIp"
                else -> "DirectSites" to "DirectIp"
            }
            var privateIp = false
            val lines = ArrayList<String>()
            for (ipList in listOf(false, true)) {
                for (item in happList(happValue(obj, if (ipList) ipKey else sitesKey))) {
                    when (val entry = happEntry(item, ipList)) {
                        HappEntry.Skip -> {}
                        HappEntry.Dropped -> counts.dropped++
                        HappEntry.PrivateIp -> privateIp = true
                        is HappEntry.Line -> lines.add(entry.line)
                    }
                }
            }
            // Simple rules ignore ip_is_private, so private ranges get a rule of their own.
            if (privateIp) {
                profile.rules.add(RouteRule().apply {
                    name = when (target) {
                        OutboundIds.BLOCK -> "Private IP Block"
                        OutboundIds.PROXY -> "Private IP Proxy"
                        else -> "Private IP Bypass"
                    }
                    ip_is_private = true
                    if (target == OutboundIds.BLOCK) action = "reject" else outbound_id = target
                })
                counts.imported++
            }
            for (line in lines) {
                if (profile.appendSimpleAddressRule(line, target, catalog)) counts.imported++ else counts.dropped++
            }
        }
        return profile
    }

    private val NOT_LETTERS = Regex("[^A-Za-z]+")

    /** fromThroneShare: no raw profile, no DNS or endpoints, and only the rules that need nothing local. */
    private fun fromThroneShare(text: String, counts: Counts): RouteProfile {
        val imported = RouteShare.fromShareInput(text, RouteManager.profileIdResolver())
        val profile = imported.profile
            ?: throw RouteError(imported.fatal.ifEmpty { "could not parse a routing profile from the response" })
        profile.custom_dns = false
        profile.dns = null
        profile.endpoint_profile_ids = "[]"
        profile.inner_hop_endpoint_ids = "[]"
        val kept = ArrayList<RouteRule>()
        for (rule in profile.rules) {
            if (subscriptionSafeRule(rule)) {
                kept.add(rule)
                counts.imported++
            } else {
                counts.dropped++
            }
        }
        profile.rules = kept
        if (!builtinOutbound(profile.default_outbound_id)) profile.default_outbound_id = OutboundIds.PROXY
        return profile
    }

    private fun builtinOutbound(id: Long): Boolean = id == OutboundIds.PROXY || id == OutboundIds.DIRECT || id == OutboundIds.BLOCK

    private val SAFE_ACTIONS = setOf("reject", "hijack-dns", "sniff", "resolve", "route-options")

    /** subscriptionSafeRule: nothing that names a local server, rewrites the destination or needs a local WARP or endpoint setup. */
    private fun subscriptionSafeRule(rule: RouteRule): Boolean {
        if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) return false
        if (rule.override_address.isNotEmpty() || rule.override_port.isNotEmpty()) return false
        if (rule.action == "route" || rule.action == "bypass") {
            return builtinOutbound(rule.outbound_id) || rule.outbound_id == OutboundIds.HIJACK_DNS
        }
        return rule.action in SAFE_ACTIONS
    }

    private fun startedInGroup(gid: Long): Boolean {
        val started = ProfileManager.runningProfileId().takeIf { it > 0L } ?: return false
        return ProfileManager.getProfile(started)?.groupId == gid
    }
}
