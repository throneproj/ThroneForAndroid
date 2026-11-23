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

    /** How many of the ranked pool enter the config. */
    @JvmField var buildLimit: Int = DEFAULT_BUILD_LIMIT

    /** Empty = the global test_url. */
    @JvmField var testURL: String = ""

    /** Direct outage probe; empty = the global direct_test_url. */
    @JvmField var connectivityURL: String = ""
    @JvmField var intervalSec: Int = DEFAULT_INTERVAL_SEC
    @JvmField var toleranceMs: Int = DEFAULT_TOLERANCE_MS

    /** 0 = no ceiling; slower members are only a fallback. */
    @JvmField var maxRTTms: Int = 0

    /** Members kept confirmed working (the failover pool). */
    @JvmField var expected: Int = DEFAULT_EXPECTED
    @JvmField var interruptOnSwitch: Boolean = true

    @JvmField var balance: Boolean = false

    /** "rotate" or "connection". */
    @JvmField var balanceMode: String = "rotate"

    /** "latency" or "priority". */
    @JvmField var strategy: String = STRATEGY_LATENCY

    /** A member takes the first tier it matches; members matching none form an implicit last tier. */
    @JvmField var tiers: MutableList<Tier> = ArrayList()

    /** Also judge members by how the real connections they carry end, not only by probes. */
    @JvmField var passive: Boolean = false

    /** Members the core re-checks every interval: the ready set plus a margin of challengers (autoselector.h:75). */
    val activeSize: Int get() = minOf(expected + CLOSE_MARGIN, buildLimit)

    /** autoselector.h:77: [built] is how many members the config holds. */
    fun benchIntervalSec(built: Int): Int = maxOf(intervalSec, (2 * built).coerceIn(600, 1800))

    /** Re-checks the in-use member; a break that still accepts connections raises no error elsewhere. */
    val watchIntervalSec: Int get() = minOf(WATCH_INTERVAL_SEC, intervalSec)

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

    /** One priority tier: [gid] -1 = any tracked group, [nameFilter] a case-insensitive regex, "" = any name. */
    data class Tier(val gid: Long = -1, val nameFilter: String = "") {
        /** The tier half of autoSelector::TierLabel. */
        fun label(): String {
            val parts = ArrayList<String>()
            if (gid >= 0) parts.add(groupNames?.nameOf(gid) ?: vtr(R.string.autosel_tier_missing_group))
            val pattern = nameFilter.trim()
            if (pattern.isNotEmpty()) parts.add(vtr(R.string.autosel_tier_names_matching, pattern))
            if (parts.isEmpty()) return vtr(R.string.autosel_tier_any_profile)
            return parts.joinToString(vtr(R.string.autosel_list_separator))
        }
    }

    val prioritized: Boolean get() = strategy == STRATEGY_PRIORITY

    /** autoSelector::TierLabel: index tiers.size names the members that match no tier. */
    fun tierLabel(index: Int): String = tiers.getOrNull(index)?.label() ?: vtr(R.string.autosel_tier_everything_else)

    /** autoselector.h:97. */
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
        if (!prioritized) return
        issues.addIssue(Severity.Error, "tiers", AutoSelectorChecks.autoSelectorTierCount(tiers.size))
        for ((i, tier) in tiers.withIndex()) {
            val field = "tiers[$i]"
            issues.addIssue(Severity.Error, field, AutoSelectorChecks.autoSelectorTierMatch(tier.gid, tier.nameFilter))
            issues.addIssue(Severity.Error, "$field.name_filter", Validation.regex(tier.nameFilter), tier.nameFilter)
        }
    }

    /**
     * autoselector.h:110-150. A value of the wrong JSON type falls back to the default. Keys of settings that are now
     * fixed (pool_cap, result_validity_mins, sampling, failback_*, …) are ignored, so older profiles still load.
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
        if (obj.contains("build_limit")) buildLimit = intOr(obj["build_limit"], DEFAULT_BUILD_LIMIT)
        if (obj.contains("test_url")) testURL = obj.string("test_url")
        if (obj.contains("connectivity_url")) connectivityURL = obj.string("connectivity_url")
        if (obj.contains("interval_sec")) intervalSec = intOr(obj["interval_sec"], DEFAULT_INTERVAL_SEC)
        if (obj.contains("tolerance_ms")) toleranceMs = intOr(obj["tolerance_ms"], DEFAULT_TOLERANCE_MS)
        if (obj.contains("max_rtt_ms")) maxRTTms = intOr(obj["max_rtt_ms"], 0)
        if (obj.contains("expected")) expected = intOr(obj["expected"], DEFAULT_EXPECTED)
        if (obj.contains("interrupt_on_switch")) interruptOnSwitch = boolOr(obj["interrupt_on_switch"], true)
        if (obj.contains("balance")) balance = boolOr(obj["balance"], false)
        if (obj.contains("balance_mode")) balanceMode = obj["balance_mode"] as? String ?: "rotate"
        if (obj.contains("strategy")) strategy = obj["strategy"] as? String ?: STRATEGY_LATENCY
        if (obj.contains("tiers")) {
            tiers = obj.array("tiers").mapTo(ArrayList()) { value ->
                val entry = value as? JsonObject ?: JsonObject()
                Tier(longOr(entry["gid"], -1), entry.string("name_filter"))
            }
        }
        if (obj.contains("passive")) passive = boolOr(obj["passive"], false)
        if (obj.contains("pool")) pool = obj.array("pool").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        if (obj.contains("pool_ranked_at")) poolRankedAt = obj.double("pool_ranked_at").toLong()
        if (obj.contains("pinned_id")) pinnedID = longOr(obj["pinned_id"], -1)
        if (obj.contains("last_built")) lastBuilt = obj.array("last_built").mapTo(ArrayList()) { JsonValues.toInteger(it) }
        if (obj.contains("last_built_at")) lastBuiltAt = obj.double("last_built_at").toLong()
        if (obj.contains("history")) history = obj.array("history").copy()
        normalize()
        return true
    }

    /** autoselector.h:152-183: every key is always written; "gid" (the first tracked group) keeps older builds tracking one. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["name"] = name
        obj["type"] = "autoselector"
        obj["gid"] = gids.firstOrNull() ?: -1L
        obj["gids"] = idArray(gids)
        obj["name_filter"] = nameFilter
        obj["country_filter"] = countryFilter
        obj["build_limit"] = buildLimit
        obj["test_url"] = testURL
        obj["connectivity_url"] = connectivityURL
        obj["interval_sec"] = intervalSec
        obj["tolerance_ms"] = toleranceMs
        obj["max_rtt_ms"] = maxRTTms
        obj["expected"] = expected
        obj["interrupt_on_switch"] = interruptOnSwitch
        obj["balance"] = balance
        obj["balance_mode"] = balanceMode
        obj["strategy"] = strategy
        obj["tiers"] = JsonArray().also { array ->
            tiers.forEach { array.add(jsonObjectOf("gid" to it.gid, "name_filter" to it.nameFilter)) }
        }
        obj["passive"] = passive
        obj["pool"] = idArray(pool)
        obj["pool_ranked_at"] = poolRankedAt
        obj["pinned_id"] = pinnedID
        obj["last_built"] = idArray(lastBuilt)
        obj["last_built_at"] = lastBuiltAt
        obj["history"] = history.copy()
        return obj
    }

    /** autoselector.h:185-189: the group outbound is assembled by the generator, where members are resolvable. */
    override fun build(ctx: BuildContext): BuildResult =
        BuildResult(JsonObject(), "Cannot call Build on an auto selector config")

    /** autoselector.h:192-211: clamps what a hand-edited profile could put out of range; runs before every plan. */
    fun normalize() {
        val seen = HashSet<Long>()
        gids = gids.filterTo(ArrayList()) { it >= 0 && seen.add(it) }
        if (buildLimit < 1) buildLimit = 1
        if (buildLimit > MAX_BUILD_LIMIT) buildLimit = MAX_BUILD_LIMIT
        if (intervalSec < 10) intervalSec = 10
        if (expected < 1) expected = 1
        if (expected > buildLimit) expected = buildLimit
        if (toleranceMs < MIN_TOLERANCE_MS) toleranceMs = MIN_TOLERANCE_MS
        if (maxRTTms < 0) maxRTTms = 0
        if (balanceMode != "connection") balanceMode = "rotate"
        if (strategy != STRATEGY_PRIORITY) strategy = STRATEGY_LATENCY
        if (tiers.any { it.gid < -1 }) tiers = tiers.mapTo(ArrayList()) { if (it.gid < -1) it.copy(gid = -1) else it }
    }

    /** autoselector.h:213-220. */
    class HistoryEntry(
        @JvmField var id: Long = -1,
        @JvmField var firstUsed: Long = 0,
        @JvmField var lastUsed: Long = 0,
        @JvmField var builds: Int = 0,
        @JvmField var failures: Int = 0,
        @JvmField var name: String = "",
    )

    /** autoselector.h:224-240: entries without a valid id are dropped. */
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

    /** autoselector.h:243-281: newest first, so the LRU trim at the tail drops the least recently used. */
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

    /** autoselector.h:20-37: defaults of the saved settings, and the fixed values of those no longer saved. */
    companion object {
        const val DEFAULT_BUILD_LIMIT = 300
        const val DEFAULT_INTERVAL_SEC = 300
        const val DEFAULT_TOLERANCE_MS = 300
        const val DEFAULT_EXPECTED = 3
        const val MIN_TOLERANCE_MS = 10
        const val MAX_BUILD_LIMIT = 500
        const val POOL_CAP = 3000
        const val SUCCESS_VALIDITY_SECS = 86400L
        const val FAILURE_VALIDITY_SECS = 3600L
        const val WATCH_INTERVAL_SEC = 15
        const val SAMPLING = 10
        const val DIAL_RETRIES = 2
        const val FAILBACK_CHECKS = 3
        const val FAILBACK_DELAY_SEC = 60
        const val SITE_MEMORY_MINS = 60
        const val BALANCE_INTERVAL_SEC = 30
        const val CLOSE_MARGIN = 5
        const val MAX_HISTORY_ENTRIES = 2000
        const val STRATEGY_LATENCY = "latency"
        const val STRATEGY_PRIORITY = "priority"

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

/** The Checks of autoselector.h: the free-text fields and the tiers. */
object AutoSelectorChecks {
    /** The plan matches these against the two-letter country of each profile's last test; "!RU" excludes one. */
    @JvmStatic
    fun autoSelectorCountries(text: String): String = Validation.eachItem(text, Validation.Split.Comma, { item ->
        val code = if (item.startsWith('!')) item.substring(1).trim() else item
        if (code.length == 2 && code.all { it in 'A'..'Z' || it in 'a'..'z' }) "" else vtr(R.string.validation_autoselector_countries)
    })

    @JvmStatic
    fun autoSelectorUrl(text: String): String = Validation.url(text, listOf("http", "https"))

    /** A tier with neither would take every profile, leaving the tiers below it empty. */
    @JvmStatic
    fun autoSelectorTierMatch(gid: Long, nameFilter: String): String =
        if (gid >= 0 || nameFilter.trim().isNotEmpty()) "" else vtr(R.string.validation_autoselector_tier_match)

    @JvmStatic
    fun autoSelectorTierCount(tiers: Int): String =
        if (tiers > 0) "" else vtr(R.string.validation_autoselector_tier_count)
}
