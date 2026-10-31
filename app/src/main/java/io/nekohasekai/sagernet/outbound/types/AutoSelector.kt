package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/**
 * autoSelector (include/configs/outbounds/autoselector.h, src/configs/outbounds/autoselector.cpp): a profile the
 * generator expands into the core `auto-selector` outbound over the members of the tracked groups [gids]
 * (outbound.config.AutoSelectorPlanner, ConfigGenerator). Profile and group ids are widened to Long for Room, like
 * [Chain.list]; they are local ids, so imports and restores remap them.
 */
class AutoSelector : Outbound("autoselector") {
    /** Tracked groups in the user's order; members are taken from each in turn. */
    @JvmField var gids: MutableList<Long> = ArrayList()

    /** Case-insensitive regex over the member display name, empty = all. */
    @JvmField var nameFilter: String = ""

    /** Comma-separated ISO codes matched against the members' test_country, empty = all. */
    @JvmField var countryFilter: String = ""
    @JvmField var excludeUnavailable: Boolean = true

    @JvmField var poolCap: Int = 1000
    @JvmField var buildLimit: Int = 300

    /** Minutes a stored URL-test result stays trustworthy; 0 always re-tests. */
    @JvmField var resultValidityMins: Int = 1440

    /** Empty = the global test_url. */
    @JvmField var testURL: String = ""

    /** Direct outage probe; empty = the global direct_test_url. */
    @JvmField var connectivityURL: String = ""
    @JvmField var intervalSec: Int = 300
    @JvmField var benchIntervalSec: Int = 600
    @JvmField var watchIntervalSec: Int = 15
    @JvmField var activeSize: Int = 8
    @JvmField var sampling: Int = 10
    @JvmField var toleranceMs: Int = 300

    /** 0 = no ceiling. */
    @JvmField var maxRTTms: Int = 0

    /** Members kept confirmed working (the failover pool). */
    @JvmField var expected: Int = 3
    @JvmField var dialRetries: Int = 2
    @JvmField var interruptOnSwitch: Boolean = true

    @JvmField var balance: Boolean = false

    /** "rotate" or "connection". */
    @JvmField var balanceMode: String = "rotate"
    @JvmField var balanceIntervalSec: Int = 30

    /** Ranked member ids of the last client-side sweep, best first. */
    @JvmField var pool: MutableList<Long> = ArrayList()

    /** Unix seconds. */
    @JvmField var poolRankedAt: Long = 0

    /** Hand-picked member id, -1 = automatic. */
    @JvmField var pinnedID: Long = -1
    @JvmField var lastBuilt: MutableList<Long> = ArrayList()
    @JvmField var lastBuiltAt: Long = 0

    /** Newest-first usage log of [HistoryEntry] objects. */
    @JvmField var history: JsonArray = JsonArray()

    /** autoselector.h:53. */
    override fun displayType(): String = "Auto Selector"

    /** autoselector.cpp:9-15: the names of the tracked groups that exist ("" until the app installs [groupNames]). */
    override fun displayAddress(): String {
        if (gids.none { it >= 0 }) return "no group"
        val lookup = groupNames ?: return ""
        val names = gids.mapNotNull { if (it >= 0) lookup.nameOf(it) else null }
        return if (names.isEmpty()) "missing group" else names.joinToString(", ")
    }

    /** autoselector.h: no security of its own; it inherits whatever its members use. */
    override fun security(): SecurityInfo = SecurityInfo()

    /** autoselector.h: the members carry the servers. */
    override fun hasServerEndpoint(): Boolean = false

    /** autoSelector::Validate (autoselector.cpp): parseFromJson already ran [normalize], so only the free-text fields can be wrong here. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "name_filter", Validation.regex(nameFilter), nameFilter)
        issues.addIssue(Severity.Error, "country_filter", AutoSelectorChecks.autoSelectorCountries(countryFilter), countryFilter)
        issues.addIssue(Severity.Error, "test_url", AutoSelectorChecks.autoSelectorUrl(testURL), testURL)
        issues.addIssue(Severity.Error, "connectivity_url", AutoSelectorChecks.autoSelectorUrl(connectivityURL), connectivityURL)
    }

    /**
     * autoselector.h:61-95. A value of the wrong JSON type falls back to the member default (the desktop's
     * QJsonValue::toInt(default) fallbacks differ from its member defaults for five keys; R11 lists that as a quirk).
     */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("name")) name = obj.string("name")
        // "gid" is what builds before multi-group tracking wrote; "gids" wins when both are present.
        if (obj.contains("gids")) {
            gids = obj.array("gids").mapNotNullTo(ArrayList()) { longOr(it, -1).takeIf { id -> id >= 0 } }
        } else if (obj.contains("gid")) {
            val gid = longOr(obj["gid"], -1)
            gids = if (gid >= 0) arrayListOf(gid) else ArrayList()
        }
        if (obj.contains("name_filter")) nameFilter = obj.string("name_filter")
        if (obj.contains("country_filter")) countryFilter = obj.string("country_filter")
        if (obj.contains("exclude_unavailable")) excludeUnavailable = boolOr(obj["exclude_unavailable"], true)
        if (obj.contains("pool_cap")) poolCap = intOr(obj["pool_cap"], 1000)
        if (obj.contains("build_limit")) buildLimit = intOr(obj["build_limit"], 300)
        if (obj.contains("result_validity_mins")) resultValidityMins = intOr(obj["result_validity_mins"], 1440)
        if (obj.contains("test_url")) testURL = obj.string("test_url")
        if (obj.contains("connectivity_url")) connectivityURL = obj.string("connectivity_url")
        if (obj.contains("interval_sec")) intervalSec = intOr(obj["interval_sec"], 300)
        if (obj.contains("bench_interval_sec")) benchIntervalSec = intOr(obj["bench_interval_sec"], 600)
        if (obj.contains("watch_interval_sec")) watchIntervalSec = intOr(obj["watch_interval_sec"], 15)
        if (obj.contains("active_size")) activeSize = intOr(obj["active_size"], 8)
        if (obj.contains("sampling")) sampling = intOr(obj["sampling"], 10)
        if (obj.contains("tolerance_ms")) toleranceMs = intOr(obj["tolerance_ms"], 300)
        if (obj.contains("max_rtt_ms")) maxRTTms = intOr(obj["max_rtt_ms"], 0)
        if (obj.contains("expected")) expected = intOr(obj["expected"], 3)
        if (obj.contains("dial_retries")) dialRetries = intOr(obj["dial_retries"], 2)
        if (obj.contains("interrupt_on_switch")) interruptOnSwitch = boolOr(obj["interrupt_on_switch"], true)
        if (obj.contains("balance")) balance = boolOr(obj["balance"], false)
        if (obj.contains("balance_mode")) balanceMode = obj["balance_mode"] as? String ?: "rotate"
        if (obj.contains("balance_interval_sec")) balanceIntervalSec = intOr(obj["balance_interval_sec"], 30)
        if (obj.contains("pool")) pool = obj.array("pool").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        if (obj.contains("pool_ranked_at")) poolRankedAt = obj.double("pool_ranked_at").toLong()
        if (obj.contains("pinned_id")) pinnedID = longOr(obj["pinned_id"], -1)
        if (obj.contains("last_built")) lastBuilt = obj.array("last_built").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        if (obj.contains("last_built_at")) lastBuiltAt = obj.double("last_built_at").toLong()
        if (obj.contains("history")) history = obj.array("history").copy()
        normalize()
        return true
    }

    /** autoselector.h:97-131: every key is always written; "gid" (the first tracked group) keeps older builds tracking one. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["name"] = name
        obj["type"] = "autoselector"
        obj["gid"] = gids.firstOrNull() ?: -1L
        obj["gids"] = idArray(gids)
        obj["name_filter"] = nameFilter
        obj["country_filter"] = countryFilter
        obj["exclude_unavailable"] = excludeUnavailable
        obj["pool_cap"] = poolCap
        obj["build_limit"] = buildLimit
        obj["result_validity_mins"] = resultValidityMins
        obj["test_url"] = testURL
        obj["connectivity_url"] = connectivityURL
        obj["interval_sec"] = intervalSec
        obj["bench_interval_sec"] = benchIntervalSec
        obj["watch_interval_sec"] = watchIntervalSec
        obj["active_size"] = activeSize
        obj["sampling"] = sampling
        obj["tolerance_ms"] = toleranceMs
        obj["max_rtt_ms"] = maxRTTms
        obj["expected"] = expected
        obj["dial_retries"] = dialRetries
        obj["interrupt_on_switch"] = interruptOnSwitch
        obj["balance"] = balance
        obj["balance_mode"] = balanceMode
        obj["balance_interval_sec"] = balanceIntervalSec
        obj["pool"] = idArray(pool)
        obj["pool_ranked_at"] = poolRankedAt
        obj["pinned_id"] = pinnedID
        obj["last_built"] = idArray(lastBuilt)
        obj["last_built_at"] = lastBuiltAt
        obj["history"] = history.copy()
        return obj
    }

    /** autoselector.h:133-137: the group outbound is assembled by the generator, where members are resolvable. */
    override fun build(ctx: BuildContext): BuildResult =
        BuildResult(JsonObject(), "Cannot call Build on an auto selector config")

    /** autoselector.h:140-167: clamps what a hand-edited profile could put out of range; runs before every plan. */
    fun normalize() {
        val seen = HashSet<Long>()
        gids = gids.filterTo(ArrayList()) { it >= 0 && seen.add(it) }
        if (poolCap < 1) poolCap = 1
        if (poolCap > MAX_POOL_CAP) poolCap = MAX_POOL_CAP
        if (buildLimit < 1) buildLimit = 1
        if (buildLimit > MAX_BUILD_LIMIT) buildLimit = MAX_BUILD_LIMIT
        if (buildLimit > poolCap) buildLimit = poolCap
        if (resultValidityMins < 0) resultValidityMins = 0
        if (intervalSec < 10) intervalSec = 10
        if (benchIntervalSec < intervalSec) benchIntervalSec = intervalSec
        if (watchIntervalSec < 5) watchIntervalSec = 5
        if (watchIntervalSec > intervalSec) watchIntervalSec = intervalSec
        if (activeSize < 1) activeSize = 1
        if (expected < 1) expected = 1
        // The ready set must sit inside the closely-checked set, or readiness rides on stale bench data.
        if (activeSize < expected) activeSize = expected
        if (activeSize > buildLimit) activeSize = buildLimit
        if (sampling < 2) sampling = 2
        if (sampling > 60) sampling = 60
        if (toleranceMs < 0) toleranceMs = 0
        if (maxRTTms < 0) maxRTTms = 0
        if (expected > buildLimit) expected = buildLimit
        if (dialRetries < 0) dialRetries = 0
        if (dialRetries > 5) dialRetries = 5
        if (balanceIntervalSec < 5) balanceIntervalSec = 5
        if (balanceMode != "connection") balanceMode = "rotate"
    }

    /** autoselector.h:172-179. */
    class HistoryEntry(
        @JvmField var id: Long = -1,
        @JvmField var firstUsed: Long = 0,
        @JvmField var lastUsed: Long = 0,
        @JvmField var builds: Int = 0,
        @JvmField var failures: Int = 0,
        @JvmField var name: String = "",
    )

    /** autoselector.h:183-199: entries without a valid id are dropped. */
    fun historyEntries(): List<HistoryEntry> {
        val entries = ArrayList<HistoryEntry>(history.size)
        for (value in history) {
            val obj = value as? JsonObject ?: JsonObject()
            val entry = HistoryEntry(
                id = longOr(obj["id"], -1),
                firstUsed = obj.double("first").toLong(),
                lastUsed = obj.double("last").toLong(),
                builds = obj.int("builds"),
                failures = obj.int("fails"),
                name = obj.string("name"),
            )
            if (entry.id >= 0) entries.add(entry)
        }
        return entries
    }

    /** autoselector.h:201-240: newest first, so the LRU trim at the tail drops the least recently used. */
    fun recordHistory(ids: List<Long>, names: Map<Long, String>, now: Long) {
        val byId = HashMap<Long, HistoryEntry>()
        val order = ArrayList<Long>()
        for (entry in historyEntries()) {
            byId[entry.id] = entry
            order.add(entry.id)
        }
        for (id in ids) {
            val existing = byId[id]
            val entry = existing ?: HistoryEntry(id = id, firstUsed = now)
            if (existing != null) order.remove(id)
            entry.lastUsed = now
            entry.builds++
            names[id]?.let { entry.name = it }
            byId[id] = entry
            order.add(0, id)
        }
        while (order.size > MAX_HISTORY_ENTRIES) byId.remove(order.removeAt(order.size - 1))
        val out = JsonArray()
        for (id in order) {
            val entry = byId[id] ?: HistoryEntry()
            out.add(
                jsonObjectOf(
                    "id" to entry.id,
                    "first" to entry.firstUsed,
                    "last" to entry.lastUsed,
                    "builds" to entry.builds,
                    "fails" to entry.failures,
                    "name" to entry.name,
                ),
            )
        }
        history = out
    }

    /** A tracked group's name for [displayAddress]; null when the group does not exist. */
    fun interface GroupNames {
        fun nameOf(gid: Long): String?
    }

    companion object {
        const val MAX_POOL_CAP = 3000
        const val MAX_BUILD_LIMIT = 500
        const val MAX_HISTORY_ENTRIES = 2000

        /** GroupsRepo lookup for [displayAddress]; the outbound layer has no database, so the app installs it. */
        @JvmStatic
        @Volatile
        var groupNames: GroupNames? = null

        private fun idArray(ids: List<Long>): JsonArray = JsonArray().also { array -> ids.forEach { array.add(it) } }

        // QJsonValue::toInt(defaultValue): a whole number that fits, else the default.
        private fun intOr(value: Any?, default: Int): Int = when (value) {
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else default
            is Double -> if (value == Math.rint(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE) value.toInt() else default
            else -> default
        }

        private fun longOr(value: Any?, default: Long): Long = when (value) {
            is Long -> value
            is Double -> if (value == Math.rint(value) && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) value.toLong() else default
            else -> default
        }

        private fun boolOr(value: Any?, default: Boolean): Boolean = value as? Boolean ?: default
    }
}

/** The Checks of autoselector.h: the free-text fields, and the bounds [AutoSelector.normalize] would otherwise clamp silently. */
object AutoSelectorChecks {
    /** The plan matches these against the two-letter country of each profile's last test; "!RU" excludes one. */
    @JvmStatic
    fun autoSelectorCountries(text: String): String = Validation.eachItem(text, Validation.Split.Comma, { item ->
        val code = if (item.startsWith('!')) item.substring(1).trim() else item
        if (code.length == 2 && code.all { it in 'A'..'Z' || it in 'a'..'z' }) "" else vtr(R.string.validation_autoselector_countries)
    })

    @JvmStatic
    fun autoSelectorUrl(text: String): String = Validation.url(text, listOf("http", "https"))

    @JvmStatic
    fun autoSelectorBuildLimit(buildLimit: Int, poolCap: Int): String =
        if (buildLimit <= poolCap) "" else vtr(R.string.validation_autoselector_build_limit, poolCap.toString())

    @JvmStatic
    fun autoSelectorBenchInterval(benchInterval: Int, interval: Int): String =
        if (benchInterval >= interval) "" else vtr(R.string.validation_autoselector_bench_interval, interval.toString())

    @JvmStatic
    fun autoSelectorWatchInterval(watchInterval: Int, interval: Int): String =
        if (watchInterval <= interval) "" else vtr(R.string.validation_autoselector_watch_interval, interval.toString())

    @JvmStatic
    fun autoSelectorReadyCount(activeSize: Int, expected: Int): String =
        if (activeSize >= expected) "" else vtr(R.string.validation_autoselector_ready_count, expected.toString())

    @JvmStatic
    fun autoSelectorCloseCount(activeSize: Int, buildLimit: Int): String =
        if (activeSize <= buildLimit) "" else vtr(R.string.validation_autoselector_close_count, buildLimit.toString())
}
