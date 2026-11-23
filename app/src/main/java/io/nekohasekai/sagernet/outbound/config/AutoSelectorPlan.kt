package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.Custom
import java.util.EnumMap
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/** AutoSelectorSkip (AutoSelectorPlan.h:12-27) with the texts of AutoSelectorSkipReason (AutoSelectorPlan.cpp:225-243). */
enum class AutoSelectorSkip(@JvmField val reason: String) {
    Missing("missing profile"),
    MetaType("chain or auto selector"),
    CoreTransitions("needs too many core switches"),
    ExtraCore("extra-core profile"),
    FullConfig("full config profile"),
    Malformed("config does not parse"),
    Tailscale("Tailscale profile"),
    ManagementEndpoint("OpenVPN or OpenConnect profile"),
    NameFilter("filtered out by name"),
    CountryFilter("filtered out by country"),
    XrayFullChained("Xray full config cannot be combined with the group's proxies"),

    /** Android only: a stored type this build cannot generate. */
    Unsupported("type this build cannot use"),
}

/** One profile of a tracked group as the plan reads it: the parsed outbound and the stored test columns. */
class SelectorMember(
    @JvmField val id: Long,
    /** Null when the row is gone. */
    @JvmField val outbound: Outbound?,
    /** 0 untested, ms, -1 failed, -2 connect-only. */
    @JvmField val latency: Int,
    /** Unix seconds of [latency], 0 = never. */
    @JvmField val latencyAt: Long,
    /** ISO code of the last IP test, "" = unknown. */
    @JvmField val testCountry: String,
)

/** GroupsRepo::GetGroup with Group::Profiles(): the members in list order and the resolved landing / front proxy. */
class SelectorGroup(
    @JvmField val id: Long,
    @JvmField val name: String,
    /** -1 (any id <= 0) = none. */
    @JvmField val landingProxyId: Long,
    @JvmField val frontProxyId: Long,
    /** Null when unset or gone. */
    @JvmField val landingProxy: Outbound?,
    @JvmField val frontProxy: Outbound?,
    @JvmField val members: List<SelectorMember>,
)

/** The storage the auto-selector plan reads: a tracked group, or null when it does not exist. */
fun interface SelectorStore {
    fun group(gid: Long): SelectorGroup?

    companion object {
        @JvmField
        val NONE = SelectorStore { null }
    }
}

/** AutoSelectorPlan (AutoSelectorPlan.h:31-58) over every tracked group. */
class AutoSelectorPlan internal constructor() {
    /** Eligible members, best first, capped at [AutoSelector.POOL_CAP]; in priority mode tier by tier, each tier keeping a share. */
    var pool: List<Long> = emptyList()
        internal set

    /** The members of [pool] that enter the config, capped at build_limit the same way. */
    var build: List<Long> = emptyList()
        internal set

    /** Priority strategy only. Member id -> index of the first tier it matches, tiers.size when none. */
    val tierOf: Map<Long, Int> get() = tiers

    /** Members of [build] per tier; the extra last slot counts those matching no tier. */
    var tierBuilt: List<Int> = emptyList()
        internal set

    /** Eligible members per tier before the caps, laid out like [tierBuilt]. */
    var tierEligible: List<Int> = emptyList()
        internal set

    internal val tiers = HashMap<Long, Int>()

    /** Members of all tracked groups, each counted once; the selector itself is not one. */
    var membersInGroup = 0
        internal set

    /** Tracked groups that no longer exist; they are skipped. */
    var missingGroups = 0
        internal set
    var eligible = 0
        internal set

    /** Eligible members whose fresh result will be reused. */
    var rankedByTest = 0
        internal set

    /** Skip reason and count, in [AutoSelectorSkip] order. */
    var skipped: List<Pair<AutoSelectorSkip, Int>> = emptyList()
        internal set
    var truncated = false
        internal set
    var poolCapUsed = 0
        internal set
    var buildLimitUsed = 0
        internal set

    /** The caller should URL-test [AutoSelectorPlanner.unmeasuredCandidates] and rerank before building. */
    var needsRanking = false
        internal set

    /** Empty when the plan succeeded. */
    var error = ""
        internal set

    /** The tracked groups that exist, in [AutoSelector.gids] order. */
    var groups: List<SelectorGroup> = emptyList()
        internal set

    internal var memberById: Map<Long, SelectorMember> = emptyMap()
    internal var memberGroups: Map<Long, SelectorGroup> = emptyMap()

    val ok: Boolean get() = error.isEmpty()

    fun member(id: Long): SelectorMember? = memberById[id]

    /** The group member [id] was taken from: it was planned against that group's landing / front proxy, and is built with it. */
    fun groupOf(id: Long): SelectorGroup? = memberGroups[id]

    /** The editor's plan summary (edit_autoselector.cpp:260-295): the error, or what would run and why. */
    fun summary(): String {
        if (error.isNotEmpty()) return error
        val lines = ArrayList<String>()
        val scope = if (groups.size > 1) "in the ${groups.size} groups" else "in the group"
        lines.add("$eligible of $membersInGroup profiles $scope can be used; ${build.size} would run.")
        if (tierBuilt.isNotEmpty()) {
            val rest = tierBuilt.size - 1
            val empty = ArrayList<String>()
            val crowded = ArrayList<String>()
            val counts = tierBuilt.mapIndexed { i, count ->
                if (i < rest && count == 0) (if (tierEligible.getOrElse(i) { 0 } > 0) crowded else empty).add("${i + 1}")
                "${if (i < rest) "${i + 1}" else "everything else"} → $count"
            }
            lines.add("By tier: ${counts.joinToString(", ")}.")
            if (empty.size == 1) lines.add("No usable profile belongs to tier ${empty[0]}.")
            if (empty.size > 1) lines.add("No usable profile belongs to tiers ${empty.joinToString(", ")}.")
            if (crowded.size == 1) lines.add("Too few profiles may run to cover every tier, so tier ${crowded[0]} runs none.")
            if (crowded.size > 1) {
                lines.add("Too few profiles may run to cover every tier, so tiers ${crowded.joinToString(", ")} run none.")
            }
        }
        if (missingGroups == 1) lines.add("1 tracked group no longer exists and is skipped.")
        if (missingGroups > 1) lines.add("$missingGroups tracked groups no longer exist and are skipped.")
        if (skipped.isNotEmpty()) {
            lines.add("Skipped: " + skipped.joinToString(", ") { (skip, count) -> "$count ${skip.reason}" } + ".")
        }
        if (truncated) {
            val kept = if (tierBuilt.isNotEmpty()) "the best-ranked ones of each tier" else "the best-ranked ones"
            lines.add("More than $poolCapUsed profiles match, so only $kept are kept.")
        }
        if (rankedByTest > 0) lines.add("$rankedByTest have a recent test result that will be reused.")
        if (needsRanking) {
            lines.add(
                "The rest will be measured as the selector starts: it runs as soon as enough profiles work, and " +
                    "switches to the full ranking once all are measured.",
            )
        }
        return lines.joinToString(" ")
    }
}

/**
 * The planning of src/configs/AutoSelectorPlan.cpp: which members of the tracked groups run, in which order, and
 * whether some must be measured first. Every call normalises the selector like the desktop, except [rerank].
 * [clock] is unix seconds.
 */
class AutoSelectorPlanner @JvmOverloads constructor(
    private val store: SelectorStore,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    /**
     * The tracked groups that exist, in [AutoSelector.gids] order, and their members in that order: an id listed by
     * more than one group is taken once, with the first group listing it.
     */
    private class Tracked(val groups: List<SelectorGroup>, val missing: Int) {
        val members = ArrayList<SelectorMember>()
        val byId = HashMap<Long, SelectorMember>()
        val groupOf = HashMap<Long, SelectorGroup>()

        init {
            for (group in groups) {
                for (member in group.members) {
                    if (member.id in groupOf) continue
                    groupOf[member.id] = group
                    byId[member.id] = member
                    members.add(member)
                }
            }
        }
    }

    private fun track(selector: AutoSelector): Tracked {
        val groups = ArrayList<SelectorGroup>()
        var missing = 0
        val seen = HashSet<Long>()
        for (gid in selector.gids) {
            if (!seen.add(gid)) continue
            val group = store.group(gid)
            if (group == null) missing++ else groups.add(group)
        }
        return Tracked(groups, missing)
    }

    /**
     * PlanAutoSelector (cpp:353-407); [selectorId] is excluded from the members, missing groups are skipped. In priority
     * mode the members are stable-sorted by tier and both caps keep each tier a share ([AutoSelectorPlan.tierOf]).
     */
    fun plan(selectorId: Long, selector: AutoSelector): AutoSelectorPlan {
        val plan = AutoSelectorPlan()
        selector.normalize()
        plan.poolCapUsed = AutoSelector.POOL_CAP
        plan.buildLimitUsed = selector.buildLimit
        val now = clock()
        val tracked = track(selector)
        plan.missingGroups = tracked.missing
        if (tracked.groups.isEmpty()) {
            plan.error = if (selector.gids.isEmpty()) "Auto selector tracks no group"
            else "Auto selector points at a group that no longer exists"
            return plan
        }
        plan.groups = tracked.groups
        plan.memberById = tracked.byId
        plan.memberGroups = tracked.groupOf
        val members = eligibleMembers(selectorId, selector, tracked, now, plan)
        plan.eligible = members.size
        if (members.isEmpty()) {
            plan.error = "Auto selector has no usable members"
            return plan
        }

        var ordered = orderMembers(members, selector.pool, selector, tracked, now)
        val noTier = selector.tiers.size
        plan.truncated = ordered.size > AutoSelector.POOL_CAP
        if (selector.prioritized) {
            // The core reads this order as its ranking prior, and capByTier keeps it.
            ordered = ordered.sortedBy { plan.tiers[it] ?: noTier }
            plan.tierEligible = tierCounts(ordered, plan.tiers, noTier)
            ordered = capByTier(ordered, AutoSelector.POOL_CAP, plan.tiers, noTier)
            plan.pool = ordered
            plan.build = capByTier(ordered, selector.buildLimit, plan.tiers, noTier)
            plan.tierBuilt = tierCounts(plan.build, plan.tiers, noTier)
        } else {
            if (plan.truncated) ordered = ordered.take(AutoSelector.POOL_CAP)
            plan.pool = ordered
            plan.build = ordered.take(selector.buildLimit)
        }
        if (ordered.size > selector.buildLimit) {
            plan.needsRanking = plan.build.any { effectiveLatency(tracked.byId[it], now) == 0 }
        }
        return plan
    }

    /** AutoSelectorRankingCandidates (cpp:286-293): every eligible member, groups in tracked order, each in group order. */
    fun rankingCandidates(selectorId: Long, selector: AutoSelector): List<Long> {
        selector.normalize()
        return eligibleMembers(selectorId, selector, track(selector), clock(), null)
    }

    /**
     * AutoSelectorUnmeasuredCandidates (cpp:423-444): the candidates without a fresh result, plus those in [stale]
     * (members whose stored result no longer reflects reality), to URL-test before [rerank]; and how many of the other
     * candidates already count as working ([memberWorks]).
     */
    @JvmOverloads
    fun unmeasuredCandidates(
        selectorId: Long, selector: AutoSelector, stale: Collection<Long> = emptyList(),
    ): Pair<List<Long>, Int> {
        selector.normalize()
        val tracked = track(selector)
        val now = clock()
        val staleSet = stale.toHashSet()
        val needed = ArrayList<Long>()
        var working = 0
        for (id in eligibleMembers(selectorId, selector, tracked, now, null)) {
            val latency = if (id in staleSet) 0 else effectiveLatency(tracked.byId[id], now)
            if (latency == 0) needed.add(id) else if (qualifyingLatency(latency, selector)) working++
        }
        return needed to working
    }

    /**
     * AutoSelectorBuildChanged (cpp:463-482): whether the build the plan takes now drops a member of [built] (the
     * members of the last build) or adds one known to work.
     */
    @JvmOverloads
    fun buildChanged(selectorId: Long, selector: AutoSelector, built: Collection<Long> = selector.lastBuilt): Boolean {
        val plan = plan(selectorId, selector)
        if (!plan.ok) return false
        val next = plan.build.toHashSet()
        val builtSet = built.toHashSet()
        if (builtSet.any { it !in next }) return true
        // The build drops members that fail validation, so an unbuilt one counts only when it is known to work.
        val now = clock()
        return next.any { it !in builtSet && qualifyingLatency(effectiveLatency(plan.member(it), now), selector) }
    }

    /**
     * RerankAutoSelectorPool (cpp:484-498): every eligible member by effective latency, capped at
     * [AutoSelector.POOL_CAP], written into [AutoSelector.pool] / [AutoSelector.poolRankedAt]; [untrusted] members rank
     * as untested whatever their stored result says. The caller saves the profile.
     */
    @JvmOverloads
    fun rerank(selectorId: Long, selector: AutoSelector, untrusted: Collection<Long> = emptyList()): List<Long> {
        val tracked = track(selector)
        val now = clock()
        val eligible = eligibleMembers(selectorId, selector, tracked, now, null)
        var members = sortByLatency(eligible, selector, tracked.byId, now, untrusted.toHashSet())
        if (members.size > AutoSelector.POOL_CAP) members = members.take(AutoSelector.POOL_CAP)
        selector.pool = members.toMutableList()
        selector.poolRankedAt = clock()
        return members
    }

    // MemberTierFilter: [name] null with [hasName] is an invalid pattern.
    private class TierFilter(val gid: Long, val name: Pattern?, val hasName: Boolean)

    // excludedCountries: "!RU" items, removed whether or not includes are given; [tiers] in tier order, priority strategy only.
    private class Filters(
        val name: Pattern?, val countries: Set<String>, val excludedCountries: Set<String>, val tiers: List<TierFilter>,
    )

    // buildFilters (cpp:40-67): an invalid regex filters nothing (the editor refuses to save one).
    private fun buildFilters(selector: AutoSelector): Filters {
        var name: Pattern? = null
        if (selector.nameFilter.trim().isNotEmpty()) {
            name = try {
                Pattern.compile(selector.nameFilter, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: PatternSyntaxException) {
                null
            }
        }
        val countries = HashSet<String>()
        val excludedCountries = HashSet<String>()
        for (item in selector.countryFilter.split(',')) {
            var code = item.trim()
            val exclude = code.startsWith('!')
            if (exclude) code = code.substring(1).trim()
            if (code.isEmpty()) continue
            (if (exclude) excludedCountries else countries).add(code.uppercase())
        }
        val tiers = if (!selector.prioritized) emptyList() else selector.tiers.map { tier ->
            val pattern = tier.nameFilter.trim()
            if (pattern.isEmpty()) return@map TierFilter(tier.gid, null, false)
            val compiled = try {
                Pattern.compile(pattern, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
            } catch (e: PatternSyntaxException) {
                null
            }
            TierFilter(tier.gid, compiled, true)
        }
        return Filters(name, countries, excludedCountries, tiers)
    }

    // memberTierOf (cpp:69-80): first match wins, tiers.size when none does; an invalid pattern matches nothing.
    private fun memberTierOf(member: Outbound, group: SelectorGroup, filters: Filters): Int {
        val name = member.displayName()
        for ((i, tier) in filters.tiers.withIndex()) {
            if (tier.gid >= 0 && group.id != tier.gid) continue
            if (tier.hasName && (tier.name == null || !tier.name.matcher(name).find())) continue
            return i
        }
        return filters.tiers.size
    }

    // eligibilityOf (cpp:91-122); null = eligible. A failed result only ranks a member last, never excludes it.
    private fun eligibilityOf(member: SelectorMember, filters: Filters): AutoSelectorSkip? {
        val outbound = member.outbound ?: return AutoSelectorSkip.Missing
        val type = outbound.type
        if (type == "chain" || type == "autoselector") return AutoSelectorSkip.MetaType
        if (type == "tailscale") return AutoSelectorSkip.Tailscale
        if (type == "openvpn" || type == "openconnect") return AutoSelectorSkip.ManagementEndpoint
        if (type == "extracore" || outbound.isExtraCore()) return AutoSelectorSkip.ExtraCore
        // A known type that is still invalid is a row whose stored JSON does not parse.
        if (outbound.invalid) {
            return if (OutboundFactory.canonicalType(type) in OutboundFactory.ALL_TYPES) AutoSelectorSkip.Malformed
            else AutoSelectorSkip.Unsupported
        }
        if (type == "custom") {
            val custom = outbound as? Custom ?: return AutoSelectorSkip.Missing
            // Only a sing-box full config is excluded; an Xray one gets its own instance.
            if (custom.subtype != Custom.CUSTOM_OUTBOUND && custom.subtype != Custom.CUSTOM_XRAY_OUTBOUND &&
                custom.subtype != Custom.CUSTOM_XRAY_FULL_CONFIG
            ) return AutoSelectorSkip.FullConfig
            if (custom.configObject().isEmpty()) return AutoSelectorSkip.Malformed
        }
        if (filters.name != null && !filters.name.matcher(outbound.displayName()).find()) return AutoSelectorSkip.NameFilter
        if (filters.countries.isNotEmpty() || filters.excludedCountries.isNotEmpty()) {
            // Unknown country is skipped either way: an exclusion cannot vouch for a profile never IP-tested.
            val country = member.testCountry.trim().uppercase()
            if (country.isEmpty() || country in filters.excludedCountries ||
                (filters.countries.isNotEmpty() && country !in filters.countries)
            ) return AutoSelectorSkip.CountryFilter
        }
        return null
    }

    // eligibleMembers (cpp:200-264), each member against the landing / front proxy of its group; [plan] collects the counters when set.
    private fun eligibleMembers(
        selectorId: Long, selector: AutoSelector, tracked: Tracked, now: Long, plan: AutoSelectorPlan?,
    ): List<Long> {
        val filters = buildFilters(selector)
        val skips = EnumMap<AutoSelectorSkip, Int>(AutoSelectorSkip::class.java)
        val members = ArrayList<Long>()
        for (member in tracked.members) {
            if (member.id == selectorId) continue
            val group = tracked.groupOf[member.id] ?: continue
            if (plan != null) plan.membersInGroup++
            val skip = eligibilityOf(member, filters)
            if (skip != null) {
                skips.merge(skip, 1, Int::plus)
                continue
            }
            val outbound = member.outbound ?: continue
            if (outbound.isXrayFullConfig()) {
                if (!xrayFullConfigFitsChain(group.landingProxy, group.frontProxy)) {
                    skips.merge(AutoSelectorSkip.XrayFullChained, 1, Int::plus)
                    continue
                }
            } else if (coreTransitions(group.landingProxy, outbound, group.frontProxy) > 2) {
                skips.merge(AutoSelectorSkip.CoreTransitions, 1, Int::plus)
                continue
            }
            if (plan != null && selector.prioritized) plan.tiers[member.id] = memberTierOf(outbound, group, filters)
            members.add(member.id)
            if (plan != null && effectiveLatency(member, now) > 0) plan.rankedByTest++
        }
        if (plan != null) plan.skipped = skips.entries.map { it.key to it.value }
        return members
    }

    // orderMembers (cpp:266-296): the persisted pool is the core's ranking prior; newcomers follow by latency, over-cap
    // then failed members last.
    private fun orderMembers(
        members: List<Long>, persistedPool: List<Long>, selector: AutoSelector, tracked: Tracked, now: Long,
    ): List<Long> {
        val eligible = members.toHashSet()
        val ordered = ArrayList<Long>(members.size)
        val placed = HashSet<Long>()
        for (id in persistedPool) {
            if (id !in eligible || !placed.add(id)) continue
            ordered.add(id)
        }
        ordered.addAll(sortByLatency(members.filter { it !in placed }, selector, tracked.byId, now))
        val demotion = ordered.associateWith { id ->
            val latency = effectiveLatency(tracked.byId[id], now)
            when {
                latency < 0 -> 2
                overCap(latency, selector) -> 1
                else -> 0
            }
        }
        return ordered.sortedBy { demotion[it] ?: 0 }
    }

    // capByTier (cpp:296-330): cuts a tier-sorted list to [limit] without crowding out a tier: each one first gets a
    // fair share, the rest goes in tier order.
    private fun capByTier(ordered: List<Long>, limit: Int, tierOf: Map<Long, Int>, noTier: Int): List<Long> {
        if (ordered.size <= limit) return ordered
        val byTier = List(noTier + 1) { ArrayList<Long>() }
        for (id in ordered) byTier[tierOf[id] ?: noTier].add(id)
        val nonEmpty = byTier.count { it.isNotEmpty() }
        val reserve = maxOf(1, limit / (2 * nonEmpty))
        val quota = IntArray(byTier.size)
        var given = 0
        for (i in byTier.indices) {
            quota[i] = minOf(byTier[i].size, reserve)
            given += quota[i]
        }
        if (given > limit) {
            // More tiers than slots: one each, in tier order.
            given = 0
            for (i in byTier.indices) {
                quota[i] = if (byTier[i].isNotEmpty() && given < limit) 1 else 0
                given += quota[i]
            }
        }
        for (i in byTier.indices) {
            if (given >= limit) break
            val extra = minOf(byTier[i].size - quota[i], limit - given)
            quota[i] += extra
            given += extra
        }
        val capped = ArrayList<Long>(given)
        for (i in byTier.indices) capped.addAll(byTier[i].subList(0, quota[i]))
        return capped
    }

    private fun tierCounts(ids: List<Long>, tierOf: Map<Long, Int>, noTier: Int): List<Int> {
        val counts = IntArray(noTier + 1)
        for (id in ids) counts[tierOf[id] ?: noTier]++
        return counts.toList()
    }

    // sortByLatency (cpp:188-198): each effective latency read once up front, [untrusted] members as untested, then
    // a stable sort.
    private fun sortByLatency(
        ids: List<Long>, selector: AutoSelector, byId: Map<Long, SelectorMember>, now: Long, untrusted: Set<Long> = emptySet(),
    ): List<Long> {
        val latency = ids.associateWith { if (it in untrusted) 0 else effectiveLatency(byId[it], now) }
        return ids.sortedWith(byLatency(latency, selector))
    }

    // byLatency (cpp:178-185) as a comparator: qualifying ascending, then untested, then over the cap ascending, then failed.
    private fun byLatency(latency: Map<Long, Int>, selector: AutoSelector): Comparator<Long> {
        return Comparator { left, right ->
            val leftLatency = latency[left] ?: 0
            val rightLatency = latency[right] ?: 0
            val leftRank = latencyRank(leftLatency, selector)
            val rightRank = latencyRank(rightLatency, selector)
            when {
                leftRank != rightRank -> leftRank.compareTo(rightRank)
                leftLatency > 0 -> leftLatency.compareTo(rightLatency)
                else -> 0
            }
        }
    }

    companion object {
        // ProxyEntity.LATENCY_CONNECT_ONLY; the outbound layer has no database.
        private const val LATENCY_CONNECT_ONLY = -2

        /**
         * AutoSelectorResultValiditySecs (cpp:334-337): how long a stored result counts, a success (connect-only
         * included) for a day, a failure for an hour so it gets retested.
         */
        @JvmStatic
        fun resultValiditySecs(latency: Int): Long =
            if (latency < 0 && latency != LATENCY_CONNECT_ONLY) AutoSelector.FAILURE_VALIDITY_SECS
            else AutoSelector.SUCCESS_VALIDITY_SECS

        /** hasFreshResult (cpp:83-89): a stored result inside its validity window; rows without a timestamp are stale. */
        @JvmStatic
        fun hasFreshResult(member: SelectorMember?, now: Long): Boolean {
            if (member == null || member.latency == 0) return false
            if (member.latencyAt <= 0) return false
            return now - member.latencyAt <= resultValiditySecs(member.latency)
        }

        /** effectiveLatency (cpp:168-171): stale results count as untested (0). */
        @JvmStatic
        fun effectiveLatency(member: SelectorMember?, now: Long): Int =
            if (member != null && hasFreshResult(member, now)) member.latency else 0

        /**
         * AutoSelectorMemberWorks (cpp:446-452): the ranking counts [member] as working, a success inside the validity
         * window and under the latency cap.
         */
        @JvmStatic
        fun memberWorks(member: SelectorMember?, selector: AutoSelector, now: Long): Boolean =
            qualifyingLatency(effectiveLatency(member, now), selector)

        /** AutoSelectorStartQuorum (cpp:454-461): working members a selector needs before it may start while its sweep runs. */
        @JvmStatic
        fun startQuorum(selector: AutoSelector): Int {
            selector.normalize()
            return selector.activeSize
        }

        // overCap (cpp:150-153), the core's qualifies(): at or over the cap a member is only a fallback.
        private fun overCap(latency: Int, selector: AutoSelector): Boolean =
            latency > 0 && selector.maxRTTms > 0 && latency >= selector.maxRTTms

        private fun qualifyingLatency(latency: Int, selector: AutoSelector): Boolean =
            latency > 0 && !overCap(latency, selector)

        // latencyRank (cpp:160-166): untested outranks over-cap and failed, as it may still qualify, so a fresh
        // subscription gets explored.
        private fun latencyRank(latency: Int, selector: AutoSelector): Int = when {
            latency > 0 -> if (overCap(latency, selector)) 2 else 0
            latency == 0 -> 1
            else -> 3
        }

        // coreTransitions (cpp:84-98): a chain may hand off sing-box <-> Xray at most twice.
        private fun coreTransitions(landing: Outbound?, member: Outbound, front: Outbound?): Int {
            var transitions = 0
            var inXray = false
            for (hop in listOf(landing, member, front)) {
                if (hop == null) continue
                val xray = hop.isXray()
                if (xray != inXray) transitions++
                inXray = xray
            }
            return transitions
        }

        // xrayFullConfigFitsChain (cpp:100-108): chainScanError's rules for a full-config member.
        private fun xrayFullConfigFitsChain(landing: Outbound?, front: Outbound?): Boolean {
            if (front != null) return false
            if (landing == null) return true
            return !landing.isXray() && !landing.isXrayFullConfig() && !landing.isExtraCore()
        }
    }
}
