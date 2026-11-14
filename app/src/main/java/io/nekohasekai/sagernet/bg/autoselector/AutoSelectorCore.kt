package io.nekohasekai.sagernet.bg.autoselector

import io.throneproj.mobile.Instance
import io.throneproj.mobile.AutoSelectorStatus as CoreStatus

/**
 * The running core's auto-selector [groupTag] (the desktop's QueryAutoSelectors / AutoSelectorAction RPC,
 * core/mobile/autoselector.go). Every call blocks: run it off the main thread.
 */
class AutoSelectorCore(val box: Instance, private val groupTag: String) {

    /** The group's state now, null when the core does not run it. */
    fun sample(): Sample? {
        val statuses = box.queryAutoSelectors() ?: return null
        while (statuses.hasNext()) {
            val status = statuses.next() ?: continue
            if (status.tag == groupTag) return status.toSample()
        }
        return null
    }

    /** Pins [memberTag], "" hands the group back to automatic selection; throws the core's error. */
    fun select(memberTag: String) = box.autoSelectorAction(groupTag, "select", memberTag)

    /** Re-probes every member now, in the background; throws the core's error. */
    fun recheck() = box.autoSelectorAction(groupTag, "recheck", "")

    /** One status read in member tags; the runtime maps the tags to profiles. */
    class Sample(
        /** starting | probing | ready | suspended. */
        val phase: String,
        val selectedTag: String,
        /** "" = automatic; differs from [selectedTag] while the pinned member is not healthy. */
        val pinnedTag: String,
        val lastSwitchMs: Long,
        val lastSwitchReason: String,
        val suspended: Boolean,
        val membersProbed: Int,
        val membersAlive: Int,
        val membersQualified: Int,
        val membersCooldown: Int,
        val probesInFlight: Int,
        val roundsCompleted: Int,
        val lastRoundMs: Long,
        val nextRoundMs: Long,
        val balance: Boolean,
        /** latency | priority. */
        val strategy: String,
        /** Priority strategy: what a recovered higher-tier member must pass before the group moves back up to it. */
        val failbackChecks: Int,
        val failbackDelayMs: Long,
        /** Passive scoring is on; [sitesRemembered] counts sites holding a member that answered them. */
        val passive: Boolean,
        val sitesRemembered: Int,
        /** In rank order, by tag (id and name unset). */
        val members: List<AutoSelectorStatus.Member>,
    )

    private fun CoreStatus.toSample(): Sample {
        val iterator = members()
        val list = ArrayList<AutoSelectorStatus.Member>(iterator.len())
        while (iterator.hasNext()) {
            val member = iterator.next() ?: continue
            list.add(
                AutoSelectorStatus.Member(
                    id = -1,
                    tag = member.tag,
                    selected = member.selected,
                    selectedUdp = member.selectedUDP,
                    state = member.state,
                    rank = member.rank,
                    qualified = member.qualified,
                    active = member.active,
                    averageMs = member.averageMs,
                    deviationMs = member.deviationMs,
                    minMs = member.minMs,
                    maxMs = member.maxMs,
                    samples = member.samples,
                    failures = member.failures,
                    probes = member.probes,
                    dialTotal = member.dialTotal,
                    dialFail = member.dialFail,
                    lastOkMs = member.lastOkMs,
                    lastProbeMs = member.lastProbeMs,
                    cooldownUntilMs = member.cooldownUntilMs,
                    lastError = member.lastError,
                    priority = member.priority,
                    streak = member.streak,
                    lastFailureMs = member.lastFailureMs,
                    firstByteMs = member.firstByteMs,
                    passiveAnswered = member.passiveAnswered,
                    passiveFailed = member.passiveFailed,
                    recentAnswered = member.recentAnswered,
                    recentFailed = member.recentFailed,
                    suspectUntilMs = member.suspectUntilMs,
                )
            )
        }
        return Sample(
            phase = phase,
            selectedTag = selected,
            pinnedTag = pinned,
            lastSwitchMs = lastSwitchMs,
            lastSwitchReason = lastSwitchReason,
            suspended = suspended,
            membersProbed = membersProbed,
            membersAlive = membersAlive,
            membersQualified = membersQualified,
            membersCooldown = membersCooldown,
            probesInFlight = probesInFlight,
            roundsCompleted = roundsCompleted,
            lastRoundMs = lastRoundMs,
            nextRoundMs = nextRoundMs,
            balance = balance,
            strategy = strategy,
            failbackChecks = failbackChecks,
            failbackDelayMs = failbackDelayMs,
            passive = passive,
            sitesRemembered = sitesRemembered,
            members = list,
        )
    }
}
