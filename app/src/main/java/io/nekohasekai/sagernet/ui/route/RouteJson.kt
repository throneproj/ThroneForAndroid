package io.nekohasekai.sagernet.ui.route

import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteDns
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import org.json.JSONArray
import org.json.JSONObject

/** Every member of a route profile or rule as JSON, to hand them between the routing screens in Intents. */
internal object RouteJson {

    fun ruleToJson(r: RouteRule): String = ruleObject(r).toString()

    fun ruleFromJson(text: String?): RouteRule = ruleFrom(JSONObject(text ?: "{}"))

    fun profileToJson(p: RouteProfile): String = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("default_outbound_id", p.default_outbound_id)
        put("is_remote", p.is_remote)
        put("remote_url", p.remote_url)
        put("auto_update", p.auto_update)
        put("remote_last_update", p.remote_last_update)
        put("custom_dns", p.custom_dns)
        p.dns?.let { put("dns", JSONObject(it.toJson().toCompact())) }
        put("rules", JSONArray().apply { for (r in p.rules) put(ruleObject(r)) })
    }.toString()

    fun profileFromJson(text: String?): RouteProfile {
        val obj = JSONObject(text ?: "{}")
        return RouteProfile().apply {
            id = obj.optLong("id")
            name = obj.optString("name")
            default_outbound_id = obj.optLong("default_outbound_id", OutboundIds.PROXY)
            is_remote = obj.optBoolean("is_remote")
            remote_url = obj.optString("remote_url")
            auto_update = obj.optBoolean("auto_update")
            remote_last_update = obj.optLong("remote_last_update")
            dns = obj.optJSONObject("dns")?.let { RouteDns.fromJson(JsonInput.fromOrgJson(it)) }
            custom_dns = obj.optBoolean("custom_dns") && dns != null
            val arr = obj.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) rules.add(ruleFrom(arr.optJSONObject(i) ?: continue))
        }
    }

    private fun ruleObject(r: RouteRule) = JSONObject().apply {
        put("name", r.name)
        put("type", r.type)
        put("disabled", r.disabled)
        put("remote", r.remote)
        put("action", r.action)
        put("outbound_id", r.outbound_id)
        for (f in RouteRule.STRING_FIELDS) put(f.name, f.get(r))
        for (f in RouteRule.BOOL_FIELDS) put(f.name, f.get(r))
        for (f in RouteRule.LIST_FIELDS) put(f.name, JSONArray(f.get(r)))
    }

    private fun ruleFrom(obj: JSONObject) = RouteRule().apply {
        name = obj.optString("name")
        type = obj.optInt("type")
        disabled = obj.optBoolean("disabled")
        remote = obj.optBoolean("remote")
        action = obj.optString("action", "route")
        outbound_id = obj.optLong("outbound_id", OutboundIds.DIRECT)
        for (f in RouteRule.STRING_FIELDS) f.set(this, obj.optString(f.name))
        for (f in RouteRule.BOOL_FIELDS) f.set(this, obj.optBoolean(f.name))
        for (f in RouteRule.LIST_FIELDS) {
            val values = obj.optJSONArray(f.name) ?: continue
            f.set(this, (0 until values.length()).map { values.optString(it) }.filterTo(ArrayList()) { it.isNotBlank() })
        }
    }
}
