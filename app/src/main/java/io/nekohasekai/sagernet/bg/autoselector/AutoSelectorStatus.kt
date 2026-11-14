package io.nekohasekai.sagernet.bg.autoselector

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf

/**
 * What the service reports about the auto-selector it runs (AutoSelectorView, AutoSelectorMonitor.hpp), carried as JSON
 * by ISagerNetService.autoSelectorStatus and cbAutoSelectorUpdate. [members] is the core's member table in rank order;
 * only a status asked for with its members carries it, so the callbacks stay small.
 */
data class AutoSelectorStatus(
    val phase: String = PHASE_IDLE,
    val selectorId: Long = -1,
    val selectorName: String = "",
    /** Profiles URL-tested before a (re)build ([PHASE_MEASURING]). */
    val measuring: Int = 0,
    /** While running: starting | probing | ready | suspended ("starting" until the core first reports). */
    val corePhase: String = "",
    val selectedId: Long = -1,
    val selectedName: String = "",
    /** The member the core holds as pinned, -1 = automatic. */
    val pinnedId: Long = -1,
    /** Unix ms of the core's last switch, 0 = none. */
    val lastSwitchMs: Long = 0,
    val lastSwitchReason: String = "",
    /** Members in the running build. */
    val membersTotal: Int = 0,
    val suspended: Boolean = false,
    /** Members dial through the chain hops in front of the selector. */
    val upstream: Boolean = false,
    val membersProbed: Int = 0,
    val membersAlive: Int = 0,
    val membersQualified: Int = 0,
    val membersCooldown: Int = 0,
    val probesInFlight: Int = 0,
    val roundsCompleted: Int = 0,
    val lastRoundMs: Long = 0,
    val nextRoundMs: Long = 0,
    val balance: Boolean = false,
    val exhaustedSinceMs: Long = 0,
    /** latency | priority ("" until the core first reports). */
    val strategy: String = "",
    /** What a recovered higher-tier member must pass before the selector moves back up to it. */
    val failbackChecks: Int = 0,
    val failbackDelayMs: Long = 0,
    /** Indexed by priority; the last entry names the members that match no tier. */
    val tierLabels: List<String> = emptyList(),
    /** The selected member's tier, -1 while none is selected; the callbacks carry no member table to read it from. */
    val selectedPriority: Int = -1,
    val membersSuspect: Int = 0,
    val passive: Boolean = false,
    /** Sites holding the member that last answered them. */
    val sitesRemembered: Int = 0,
    val members: List<Member> = emptyList(),
    /** The runtime's last notice (a refused pin, a rebuild), "" = none. */
    val notice: String = "",
    val noticeAtMs: Long = 0,
) {

    /** AutoSelectorMemberView. */
    data class Member(
        val id: Long,
        val tag: String,
        val name: String = "",
        val selected: Boolean = false,
        val selectedUdp: Boolean = false,
        /** ok | degraded | suspect | untested | dead | cooldown. */
        val state: String = "",
        val rank: Int = 0,
        val qualified: Boolean = false,
        val active: Boolean = false,
        val averageMs: Int = 0,
        val deviationMs: Int = 0,
        val minMs: Int = 0,
        val maxMs: Int = 0,
        val samples: Int = 0,
        val failures: Int = 0,
        val probes: Int = 0,
        val dialTotal: Int = 0,
        val dialFail: Int = 0,
        val lastOkMs: Long = 0,
        val lastProbeMs: Long = 0,
        val cooldownUntilMs: Long = 0,
        val lastError: String = "",
        /** Tier index, 0 = most preferred. */
        val priority: Int = 0,
        /** Successful checks in a row since the last failure. */
        val streak: Int = 0,
        val lastFailureMs: Long = 0,
        /** Passive scoring, from the real connections the member carried; time-weighted average, 0 = none yet. */
        val firstByteMs: Int = 0,
        val passiveAnswered: Int = 0,
        val passiveFailed: Int = 0,
        /** Decayed and rounded: what the suspect rule weighs. */
        val recentAnswered: Int = 0,
        val recentFailed: Int = 0,
        val suspectUntilMs: Long = 0,
    ) {
        val isDead: Boolean get() = state == STATE_DEAD
        val isUsable: Boolean get() = state == STATE_OK || state == STATE_DEGRADED

        /** Passes its probes, but recent real connections mostly fail. */
        val isSuspect: Boolean get() = state == STATE_SUSPECT
        val hasProblem: Boolean
            get() = state == STATE_DEAD || state == STATE_COOLDOWN || state == STATE_DEGRADED || state == STATE_SUSPECT

        internal fun toJson(): JsonObject = jsonObjectOf(
            "id" to id,
            "tag" to tag,
            "name" to name,
            "selected" to selected,
            "selected_udp" to selectedUdp,
            "state" to state,
            "rank" to rank,
            "qualified" to qualified,
            "active" to active,
            "average_ms" to averageMs,
            "deviation_ms" to deviationMs,
            "min_ms" to minMs,
            "max_ms" to maxMs,
            "samples" to samples,
            "failures" to failures,
            "probes" to probes,
            "dial_total" to dialTotal,
            "dial_fail" to dialFail,
            "last_ok_ms" to lastOkMs,
            "last_probe_ms" to lastProbeMs,
            "cooldown_until_ms" to cooldownUntilMs,
            "last_error" to lastError,
            "priority" to priority,
            "streak" to streak,
            "last_failure_ms" to lastFailureMs,
            "first_byte_ms" to firstByteMs,
            "passive_answered" to passiveAnswered,
            "passive_failed" to passiveFailed,
            "recent_answered" to recentAnswered,
            "recent_failed" to recentFailed,
            "suspect_until_ms" to suspectUntilMs,
        )

        internal companion object {
            fun fromJson(obj: JsonObject) = Member(
                id = obj.integer("id"),
                tag = obj.string("tag"),
                name = obj.string("name"),
                selected = obj.bool("selected"),
                selectedUdp = obj.bool("selected_udp"),
                state = obj.string("state"),
                rank = obj.int("rank"),
                qualified = obj.bool("qualified"),
                active = obj.bool("active"),
                averageMs = obj.int("average_ms"),
                deviationMs = obj.int("deviation_ms"),
                minMs = obj.int("min_ms"),
                maxMs = obj.int("max_ms"),
                samples = obj.int("samples"),
                failures = obj.int("failures"),
                probes = obj.int("probes"),
                dialTotal = obj.int("dial_total"),
                dialFail = obj.int("dial_fail"),
                lastOkMs = obj.integer("last_ok_ms"),
                lastProbeMs = obj.integer("last_probe_ms"),
                cooldownUntilMs = obj.integer("cooldown_until_ms"),
                lastError = obj.string("last_error"),
                priority = obj.int("priority"),
                streak = obj.int("streak"),
                lastFailureMs = obj.integer("last_failure_ms"),
                firstByteMs = obj.int("first_byte_ms"),
                passiveAnswered = obj.int("passive_answered"),
                passiveFailed = obj.int("passive_failed"),
                recentAnswered = obj.int("recent_answered"),
                recentFailed = obj.int("recent_failed"),
                suspectUntilMs = obj.integer("suspect_until_ms"),
            )
        }
    }

    /** A selector is being measured or run. */
    val active: Boolean get() = phase != PHASE_IDLE

    val prioritized: Boolean get() = strategy == STRATEGY_PRIORITY

    fun member(id: Long): Member? = members.firstOrNull { it.id == id }

    fun toJson(): String {
        val obj = jsonObjectOf(
            "phase" to phase,
            "selector_id" to selectorId,
            "selector_name" to selectorName,
            "measuring" to measuring,
            "core_phase" to corePhase,
            "selected_id" to selectedId,
            "selected_name" to selectedName,
            "pinned_id" to pinnedId,
            "last_switch_ms" to lastSwitchMs,
            "last_switch_reason" to lastSwitchReason,
            "members_total" to membersTotal,
            "suspended" to suspended,
            "upstream" to upstream,
            "members_probed" to membersProbed,
            "members_alive" to membersAlive,
            "members_qualified" to membersQualified,
            "members_cooldown" to membersCooldown,
            "probes_in_flight" to probesInFlight,
            "rounds_completed" to roundsCompleted,
            "last_round_ms" to lastRoundMs,
            "next_round_ms" to nextRoundMs,
            "balance" to balance,
            "exhausted_since_ms" to exhaustedSinceMs,
            "strategy" to strategy,
            "failback_checks" to failbackChecks,
            "failback_delay_ms" to failbackDelayMs,
            "selected_priority" to selectedPriority,
            "members_suspect" to membersSuspect,
            "passive" to passive,
            "sites_remembered" to sitesRemembered,
            "notice" to notice,
            "notice_at_ms" to noticeAtMs,
        )
        obj["tier_labels"] = JsonArray().also { array -> tierLabels.forEach { array.add(it) } }
        obj["members"] = JsonArray().also { array -> members.forEach { array.add(it.toJson()) } }
        return obj.toCompact()
    }

    companion object {
        const val PHASE_IDLE = "idle"

        /** Candidates are URL-tested before the selector (re)starts. */
        const val PHASE_MEASURING = "measuring"

        /** The core runs the selector ([corePhase] tells how far it got). */
        const val PHASE_RUNNING = "running"

        const val STATE_OK = "ok"
        const val STATE_DEGRADED = "degraded"
        const val STATE_UNTESTED = "untested"
        const val STATE_DEAD = "dead"
        const val STATE_COOLDOWN = "cooldown"
        const val STATE_SUSPECT = "suspect"

        const val STRATEGY_PRIORITY = "priority"

        @JvmField
        val IDLE = AutoSelectorStatus()

        /** Anything unreadable is [IDLE]. */
        @JvmStatic
        fun parse(json: String?): AutoSelectorStatus {
            if (json.isNullOrBlank()) return IDLE
            val obj = JsonInput.parseObjectOrNull(json) ?: return IDLE
            return AutoSelectorStatus(
                phase = obj.string("phase").ifEmpty { PHASE_IDLE },
                selectorId = if (obj.contains("selector_id")) obj.integer("selector_id") else -1,
                selectorName = obj.string("selector_name"),
                measuring = obj.int("measuring"),
                corePhase = obj.string("core_phase"),
                selectedId = if (obj.contains("selected_id")) obj.integer("selected_id") else -1,
                selectedName = obj.string("selected_name"),
                pinnedId = if (obj.contains("pinned_id")) obj.integer("pinned_id") else -1,
                lastSwitchMs = obj.integer("last_switch_ms"),
                lastSwitchReason = obj.string("last_switch_reason"),
                membersTotal = obj.int("members_total"),
                suspended = obj.bool("suspended"),
                upstream = obj.bool("upstream"),
                membersProbed = obj.int("members_probed"),
                membersAlive = obj.int("members_alive"),
                membersQualified = obj.int("members_qualified"),
                membersCooldown = obj.int("members_cooldown"),
                probesInFlight = obj.int("probes_in_flight"),
                roundsCompleted = obj.int("rounds_completed"),
                lastRoundMs = obj.integer("last_round_ms"),
                nextRoundMs = obj.integer("next_round_ms"),
                balance = obj.bool("balance"),
                exhaustedSinceMs = obj.integer("exhausted_since_ms"),
                strategy = obj.string("strategy"),
                failbackChecks = obj.int("failback_checks"),
                failbackDelayMs = obj.integer("failback_delay_ms"),
                tierLabels = obj.array("tier_labels").map { it as? String ?: "" },
                selectedPriority = if (obj.contains("selected_priority")) obj.int("selected_priority") else -1,
                membersSuspect = obj.int("members_suspect"),
                passive = obj.bool("passive"),
                sitesRemembered = obj.int("sites_remembered"),
                members = obj.array("members").mapNotNull { (it as? JsonObject)?.let(Member::fromJson) },
                notice = obj.string("notice"),
                noticeAtMs = obj.integer("notice_at_ms"),
            )
        }
    }
}
