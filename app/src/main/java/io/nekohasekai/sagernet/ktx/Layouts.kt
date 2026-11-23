package io.nekohasekai.sagernet.ktx

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The system's "Remove animations". Animators already honour it; Scroller-driven motion (drawers, pager switches,
 * smooth scrolls) does not, so those ask here.
 */
fun animationsEnabled(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    ValueAnimator.areAnimatorsEnabled()
} else {
    Settings.Global.getFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
}

fun RecyclerView.scrollToPositionAnimated(position: Int) {
    if (animationsEnabled()) smoothScrollToPosition(position) else scrollToPosition(position)
}

fun DrawerLayout.openNavigation() = openDrawer(GravityCompat.START, animationsEnabled())

fun DrawerLayout.closeNavigation() = closeDrawer(GravityCompat.START, animationsEnabled())

class FixedLinearLayoutManager(val recyclerView: RecyclerView) :
    LinearLayoutManager(recyclerView.context, RecyclerView.VERTICAL, false) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }

}

class FixedGridLayoutManager(val recyclerView: RecyclerView, spanCount: Int) :
    GridLayoutManager(recyclerView.context, spanCount) {

    override fun onLayoutChildren(recycler: RecyclerView.Recycler?, state: RecyclerView.State?) {
        try {
            super.onLayoutChildren(recycler, state)
        } catch (ignored: IndexOutOfBoundsException) {
        }
    }

    override fun supportsPredictiveItemAnimations(): Boolean {
        return false
    }

    fun rowIndexOf(position: Int): Int =
        position / spanCount

}
