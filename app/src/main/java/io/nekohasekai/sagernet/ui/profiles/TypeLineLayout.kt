package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * The last line of a profile card: the type at the start and the traffic at the end, on one baseline; the traffic
 * gives way (ellipsized) when the two do not fit.
 */
class TypeLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = dp2px(8)

    private lateinit var type: TextView
    private lateinit var traffic: TextView

    override fun onFinishInflate() {
        super.onFinishInflate()
        type = findViewById(R.id.profile_type)
        traffic = findViewById(R.id.traffic_text)
    }

    override fun shouldDelayChildPressedState() = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bounded = MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED
        val content = if (bounded) {
            (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        } else Int.MAX_VALUE
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        fun upTo(size: Int) =
            if (bounded) MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.AT_MOST) else free

        type.measure(upTo(content), free)
        if (!traffic.isGone) traffic.measure(upTo(content - type.measuredWidth - gap), free)
        val trafficWidth = if (traffic.isGone) 0 else gap + traffic.measuredWidth
        setMeasuredDimension(
            resolveSize(type.measuredWidth + trafficWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(lineTextHeight(type, traffic) + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val end = r - l - paddingRight
        val height = b - t - paddingTop - paddingBottom

        // x counts from the start edge, the right one in RTL
        fun place(child: View, x: Int, top: Int) {
            val left = if (rtl) end - x - child.measuredWidth else paddingLeft + x
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }

        place(type, 0, paddingTop + lineTextTop(type, height, type, traffic))
        if (!traffic.isGone) {
            val y = paddingTop + lineTextTop(traffic, height, type, traffic)
            place(traffic, end - paddingLeft - traffic.measuredWidth, y)
        }
    }
}

/** The height of the shown [views] on one baseline: emoji make a text taller than its neighbours. */
internal fun lineTextHeight(vararg views: TextView): Int {
    val shown = views.filter { !it.isGone }
    if (shown.isEmpty()) return 0
    return shown.maxOf { it.baseline } + shown.maxOf { it.measuredHeight - it.baseline }
}

/** The top of [view] in a line [height] tall where the shown [views] share one baseline, centred. */
internal fun lineTextTop(view: TextView, height: Int, vararg views: TextView): Int {
    val shown = views.filter { !it.isGone }
    if (shown.isEmpty()) return 0
    return (height - lineTextHeight(*views)) / 2 + shown.maxOf { it.baseline } - view.baseline
}
