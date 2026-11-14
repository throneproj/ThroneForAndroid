package io.nekohasekai.sagernet.ui

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus

/** The texts of AutoSelectorView (AutoSelectorMonitor.cpp:21-72) shared by the status line and the stats screen. */
internal object AutoSelectorTexts {

    /** AutoSelectorView::summary; null when no selector is measured or running. */
    fun summary(context: Context, status: AutoSelectorStatus, now: Long = System.currentTimeMillis()): String? {
        if (!status.active) return null
        if (status.phase == AutoSelectorStatus.PHASE_MEASURING) {
            return context.getString(R.string.autosel_status_measuring, status.measuring)
        }
        val total = status.membersTotal
        return when {
            status.suspended && status.upstream -> context.getString(R.string.autosel_status_paused_upstream, total)
            status.suspended -> context.getString(R.string.autosel_status_paused, total)
            status.corePhase == "starting" || status.corePhase == "probing" && status.membersProbed == 0 ->
                context.getString(R.string.autosel_status_starting, total)

            status.corePhase == "probing" -> context.getString(R.string.autosel_status_probing, status.membersProbed, total)
            status.membersAlive == 0 -> context.getString(R.string.autosel_status_no_working, total)
            else -> {
                val tier = status.selectedPriority
                val base = if (status.prioritized && tier >= 0) {
                    context.getString(R.string.autosel_status_on_tier, status.selectedName, tier + 1, status.membersAlive, total)
                } else {
                    context.getString(R.string.autosel_status_on_working, status.selectedName, status.membersAlive, total)
                }
                if (status.lastSwitchMs <= 0) base
                else base + context.getString(R.string.autosel_status_switched, ago(context, status.lastSwitchMs, now))
            }
        }
    }

    /** AutoSelectorView::detail. */
    fun detail(context: Context, status: AutoSelectorStatus): String? {
        if (!status.active || status.phase == AutoSelectorStatus.PHASE_MEASURING) return null
        val total = status.membersTotal
        val parts = ArrayList<String>()
        if (status.membersProbed > 0) {
            parts.add(context.getString(R.string.autosel_detail_working, status.membersAlive))
            if (status.membersCooldown > 0) parts.add(context.getString(R.string.autosel_detail_cooling, status.membersCooldown))
            if (status.membersSuspect > 0) parts.add(context.getString(R.string.autosel_detail_suspect, status.membersSuspect))
            val untested = total - status.membersProbed
            if (untested > 0) parts.add(context.getString(R.string.autosel_detail_untested, untested))
        }
        if (status.probesInFlight > 0) parts.add(context.getString(R.string.autosel_detail_checking, status.probesInFlight))
        if (status.balance) parts.add(context.getString(R.string.autosel_detail_balancing, status.membersQualified))
        if (parts.isEmpty()) return null
        return context.getString(
            R.string.autosel_detail_health, total, parts.joinToString(context.getString(R.string.autosel_list_separator))
        )
    }

    /** AutoSelectorView::tierLabel. */
    fun tierLabel(context: Context, status: AutoSelectorStatus, priority: Int): String =
        status.tierLabels.getOrNull(priority) ?: context.getString(R.string.autosel_tier_fallback, priority + 1)

    /** elapsedText / agoText. */
    fun ago(context: Context, ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return context.getString(R.string.autosel_never)
        val secs = (now - ms) / 1000
        return when {
            secs < 5 -> context.getString(R.string.autosel_just_now)
            secs < 60 -> context.getString(R.string.autosel_ago_seconds, secs)
            secs < 3600 -> context.getString(R.string.autosel_ago_minutes, secs / 60)
            secs < 86400 -> context.getString(R.string.autosel_ago_hours, secs / 3600)
            else -> context.getString(R.string.autosel_ago_days, secs / 86400)
        }
    }
}
