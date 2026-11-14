package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.RouteRuleOrigin
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.ui.route.RouteJson
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.RouteTestRequest

/** RunningRouteRules (RunningProfiles.hpp): what names the running config's route rules and outbounds; [routeId] 0 for a custom full config. */
class RunningRouteRules(
    val origins: List<RouteRuleOrigin>,
    val outboundNames: Map<String, String>,
    val routeId: Long,
)

/**
 * :bg side of ICoreService.testRoute, the backend of the desktop's rule tester (core/mobile/routetest.go): the running
 * instance's route rules, or a config built for a routing profile (BuildRuleTestConfig) and started stripped. Blocks
 * the binder thread up to the core's 30 s timeout; like warpRegister, failures travel in "error".
 */
object RouteTester {

    /** "error_code" of a config that could not be built: the routing profile is gone, no profile can build it, or [ERROR_BUILD] with "error". */
    const val ERROR_ROUTE_GONE = "route_gone"
    const val ERROR_NO_PROFILE = "no_profile"
    const val ERROR_BUILD = "build"

    private const val MAX_BUILD_ATTEMPTS = 4

    /**
     * [requestJson]: {"domain","ip","port","network","protocol","package_name"} for the core, then "running" to test
     * the running instance, else "route_json" (an unsaved RouteJson profile) or "route_id" to build and start stripped.
     * Returns {"error","error_code","not_running","result" (the core's JSON),
     * "origins":[{"kind","rule","name"}],"outbounds":{tag: name},"route_id","built_with"}.
     */
    fun test(requestJson: String): String = try {
        val request = JsonInput.parseObject(requestJson)
        if (request.bool("running")) testRunning(request) else testOffline(request)
    } catch (e: Throwable) {
        Logs.w(e)
        jsonObjectOf("error" to e.readableMessage).toCompact()
    }

    private fun testRunning(request: JsonObject): String {
        val running = CoreRuntime.running ?: return notRunning()
        val result = JsonInput.parseObject(running.instance.testRoute(coreRequest(request)))
        // Closed between the lookup and the call.
        if (CoreRuntime.running?.instance !== running.instance || result.string("error").contains("not running", ignoreCase = true)) {
            return notRunning()
        }
        val rules = running.routeRules
        return response(result, rules?.origins.orEmpty(), rules?.outboundNames.orEmpty(), rules?.routeId ?: 0L, "")
    }

    private fun testOffline(request: JsonObject): String {
        val draft = request.string("route_json")
        val route = (if (draft.isNotEmpty()) RouteJson.profileFromJson(draft) else RouteManager.get(request.integer("route_id")))
            ?.takeUnless { it.is_raw }
            ?: return failure(ERROR_ROUTE_GONE)
        val built = buildRuleTestConfig(route)
        val config = built.config
        if (config == null || built.profile == null) return failure(if (built.error.isEmpty()) ERROR_NO_PROFILE else ERROR_BUILD, built.error)
        val coreRequest = coreRequest(request)
        coreRequest.setConfig(config.coreConfig)
        val result = JsonInput.parseObject(Mobile.testRoute(CoreRuntime.platform, coreRequest))
        return response(result, config.routeRuleOrigins, config.outboundNames, route.id, built.profile.displayName())
    }

    private class RuleTestBuild(val config: GeneratedConfig?, val profile: ProxyEntity?, val error: String = "")

    /**
     * BuildRuleTestConfig (generate.cpp): [route] built with the selected profile when it can build routes, else with
     * any other by id; the first error comes back after four failed builds. The route screens select no profile, so
     * there is no preferred one ahead of the selected.
     */
    private fun buildRuleTestConfig(route: RouteProfile): RuleTestBuild {
        val candidates = sequence {
            yield(DataStore.selectedProxy)
            yieldAll(SagerDatabase.groupDao.allIds().flatMap { SagerDatabase.proxyDao.getIdsByGroup(it) }.sorted())
        }
        val tried = HashSet<Long>()
        var attempts = 0
        var firstError = ""
        for (id in candidates) {
            if (id <= 0L || !tried.add(id)) continue
            val entity = SagerDatabase.proxyDao.getById(id) ?: continue
            if (!canBuildRoutes(entity)) continue
            try {
                return RuleTestBuild(CoreConfigs.buildMain(entity, route = route), entity)
            } catch (e: Exception) {
                if (firstError.isEmpty()) firstError = e.readableMessage
            }
            if (++attempts >= MAX_BUILD_ATTEMPTS) break
        }
        return RuleTestBuild(null, null, firstError)
    }

    private fun canBuildRoutes(entity: ProxyEntity): Boolean {
        val outbound = entity.outbound
        if (outbound.invalid || outbound is AutoSelector) return false
        return (outbound as? Custom)?.isFullConfig() != true
    }

    private fun coreRequest(request: JsonObject): RouteTestRequest = RouteTestRequest().apply {
        setDomain(request.string("domain"))
        setIP(request.string("ip"))
        setPort(request.int("port"))
        setNetwork(request.string("network"))
        setProtocol(request.string("protocol"))
        setPackageName(request.string("package_name"))
    }

    private fun response(
        result: JsonObject, origins: List<RouteRuleOrigin>, outboundNames: Map<String, String>, routeId: Long, builtWith: String,
    ): String {
        val originArray = JsonArray()
        for (origin in origins) originArray.add(jsonObjectOf("kind" to origin.kind.id, "rule" to origin.profileRule, "name" to origin.name))
        val outbounds = JsonObject()
        for ((tag, name) in outboundNames) outbounds[tag] = name
        return jsonObjectOf(
            "result" to result,
            "origins" to originArray,
            "outbounds" to outbounds,
            "route_id" to routeId,
            "built_with" to builtWith,
        ).toCompact()
    }

    private fun notRunning(): String = jsonObjectOf("not_running" to true).toCompact()

    private fun failure(code: String, error: String = ""): String = jsonObjectOf("error_code" to code, "error" to error).toCompact()
}
