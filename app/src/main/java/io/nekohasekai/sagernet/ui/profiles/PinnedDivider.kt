package io.nekohasekai.sagernet.ui.profiles

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.dp2pxf
import io.nekohasekai.sagernet.ktx.getColorAttr

/** The thin line under the last pinned row (ProfilesTableView's endsPinnedBlock), in single-column lists. */
internal class PinnedDivider(private val adapter: ProfileListAdapter) : RecyclerView.ItemDecoration() {

    private val gap = dp2px(8)
    private val inset = dp2pxf(12)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp2pxf(1)
    }

    private fun ends(parent: RecyclerView, view: View): Boolean {
        val edge = adapter.pinEdge
        return edge != 0L && parent.getChildItemId(view) == edge
    }

    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.set(0, 0, 0, if (ends(parent, view)) gap else 0)
    }

    override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (adapter.pinEdge == 0L) return
        val layoutManager = parent.layoutManager ?: return
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (!ends(parent, child)) continue
            paint.color = parent.context.getColorAttr(android.R.attr.textColorSecondary)
            paint.alpha = 0x60
            val y = layoutManager.getDecoratedBottom(child) - gap / 2f + child.translationY
            c.drawLine(child.left + inset, y, child.right - inset, y, paint)
        }
    }
}
