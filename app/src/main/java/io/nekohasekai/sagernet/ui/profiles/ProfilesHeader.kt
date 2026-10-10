package io.nekohasekai.sagernet.ui.profiles

import android.os.SystemClock
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.marginBottom
import androidx.core.view.marginTop
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * The toolbar, the group tabs, the status line and the subscription card above the profile lists. With [autoHide] (a
 * compact height, e.g. a phone in landscape) they go away while the list scrolls down and come back when it scrolls up,
 * like the stats bar, and the list and the test panel get their height. The app bar itself stays: it keeps covering
 * the status bar.
 */
internal class ProfilesHeader(
    private val root: ViewGroup,
    private val toolbar: View,
    private val tabs: View,
    private val status: View,
    /** The current group's [SubscriptionInfoCard]. */
    private val card: View,
    private val autoHide: Boolean,
    /** Keeps the header shown: the selection's actions, the search, the keyboard. */
    private val pinned: () -> Boolean,
    /** No toggle at all, e.g. while a row is dragged. */
    private val frozen: () -> Boolean,
) {

    private companion object {
        const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
        const val TOGGLE_MS = 200L
    }

    private val threshold =
        (SCROLL_TOGGLE_THRESHOLD_DP * root.resources.displayMetrics.density).toInt().coerceAtLeast(8)
    private var direction = 0
    private var accumulated = 0
    private var hidden = false
    private var movingUntil = 0L

    /**
     * The header is sliding. The list is laid out at its final place, then animated from the old one; under a still
     * finger RecyclerView reports that movement as a scroll, so scrolls in this window are not the user's.
     */
    val moving: Boolean get() = SystemClock.uptimeMillis() < movingUntil

    /** The tabs show for two groups or more. */
    var tabsWanted = false
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** The status line shows while it has a text. */
    var statusWanted = false
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    /** The subscription card shows while the current group has one. */
    var cardWanted = false
        set(value) {
            if (field == value) return
            field = value
            apply()
        }

    // The pages lay their rows out once at the final size and ride on the pager's animated bounds; a transition inside
    // a RecyclerView would also suppress its layout, which stops the scroll.
    private val transition: Transition = TransitionSet()
        .setOrdering(TransitionSet.ORDERING_TOGETHER)
        .addTransition(ChangeBounds())
        .addTransition(Fade().addTarget(toolbar).addTarget(tabs).addTarget(status).addTarget(card))
        .setDuration(TOGGLE_MS)
        .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
        .excludeChildren(RecyclerView::class.java, true)

    /**
     * The current tab's list scrolled by [dy] as the user sees it (0 after a layout); StatsBar.onListScrolled's
     * thresholds.
     */
    fun onListScrolled(list: RecyclerView, dy: Int) {
        if (!autoHide) return
        // at its top, or too short to scroll
        if (!list.canScrollVertically(-1)) {
            show()
            return
        }
        if (dy == 0) return
        val dir = if (dy > 0) 1 else -1
        if (dir != direction) {
            direction = dir
            accumulated = 0
        }
        accumulated += dy
        val wantHidden = accumulated > 0
        if (wantHidden == hidden) {
            if (abs(accumulated) > threshold) accumulated = dir * threshold
            return
        }
        if (abs(accumulated) < threshold) return
        accumulated = 0
        if (!wantHidden || (!pinned() && hasRoom(list))) setHidden(wantHidden)
    }

    fun show() {
        direction = 0
        accumulated = 0
        setHidden(false)
    }

    /** A pin or the drag changed: a pinned header comes back. */
    fun update() {
        if (hidden && pinned()) show()
    }

    /**
     * Hiding lets the list grow upwards by the header's height, and under a finger its rows stay in place: the list
     * scrolls back by that height. It must be scrolled further than that (and so still scroll once it is taller), or it
     * lands at its top, which would bring the header straight back.
     */
    private fun hasRoom(list: RecyclerView): Boolean {
        val gain = toolbar.height + (if (tabs.isVisible) tabs.height else 0) + (if (status.isVisible) status.height else 0) +
            (if (card.isVisible) card.height + card.marginTop + card.marginBottom else 0)
        return list.computeVerticalScrollOffset() > gain + 2 * threshold
    }

    private fun setHidden(value: Boolean) {
        if (hidden == value || frozen()) return
        hidden = value
        if (root.isLaidOut) {
            // starts at the next frame
            movingUntil = SystemClock.uptimeMillis() + TOGGLE_MS + 100
            TransitionManager.beginDelayedTransition(root, transition)
        }
        apply()
    }

    private fun apply() {
        toolbar.isGone = hidden
        tabs.isVisible = tabsWanted && !hidden
        status.isVisible = statusWanted && !hidden
        card.isVisible = cardWanted && !hidden
    }
}
