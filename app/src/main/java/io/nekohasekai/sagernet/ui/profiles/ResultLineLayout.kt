package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isGone
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import kotlin.math.max

/**
 * The result line of a profile card: the latency and the speeds at the start, after the spinner of a running test,
 * and the exit IP at the end. When the two do not fit on one line the exit IP takes a line of its own below, still at
 * the end; a part too wide for a line alone wraps there. Decided while measuring, so a bound card is laid out once.
 */
class ResultLineLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = dp2px(8)
    private val progressGap = dp2px(6)
    private val lineGap = dp2px(2)

    private lateinit var progress: View
    private lateinit var result: TextView
    private lateinit var exitIp: TextView

    /** The last measure put the exit IP on a line of its own; the first line is [firstLineHeight] tall. */
    private var split = false
    private var firstLineHeight = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        progress = findViewById(R.id.test_progress)
        result = findViewById(R.id.profile_result)
        exitIp = findViewById(R.id.profile_exit_ip)
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

        if (!progress.isGone) progress.measure(free, free)
        if (!result.isGone) result.measure(free, free)
        if (!exitIp.isGone) exitIp.measure(free, free)
        val startWidth = startWidth()
        val endWidth = endWidth()
        split = startWidth > 0 && endWidth > 0 && startWidth + gap + endWidth > content
        if (!result.isGone && startWidth > content) result.measure(upTo(content - leadWidth()), free)
        if (!exitIp.isGone && endWidth > content) exitIp.measure(upTo(content), free)

        val width: Int
        val height: Int
        if (split) {
            firstLineHeight = max(heightOf(progress), heightOf(result))
            width = max(startWidth(), endWidth())
            height = firstLineHeight + lineGap + exitIp.measuredHeight
        } else {
            firstLineHeight = max(heightOf(progress), lineTextHeight(result, exitIp))
            width = startWidth() + endWidth() + if (startWidth > 0 && endWidth > 0) gap else 0
            height = firstLineHeight
        }
        setMeasuredDimension(
            resolveSize(width + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val end = r - l - paddingRight
        val content = end - paddingLeft

        // x counts from the start edge, the right one in RTL
        fun place(child: View, x: Int, top: Int) {
            val left = if (rtl) end - x - child.measuredWidth else paddingLeft + x
            child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        }

        val top = paddingTop
        if (!progress.isGone) place(progress, 0, top + (firstLineHeight - progress.measuredHeight) / 2)
        if (split) {
            if (!result.isGone) place(result, leadWidth(), top + (firstLineHeight - result.measuredHeight) / 2)
            place(exitIp, content - exitIp.measuredWidth, top + firstLineHeight + lineGap)
        } else {
            if (!result.isGone) place(result, leadWidth(), top + lineTextTop(result, firstLineHeight, result, exitIp))
            if (!exitIp.isGone) {
                val y = top + lineTextTop(exitIp, firstLineHeight, result, exitIp)
                place(exitIp, content - exitIp.measuredWidth, y)
            }
        }
    }

    /** The spinner and its gap before the result text. */
    private fun leadWidth() =
        if (progress.isGone) 0 else progress.measuredWidth + if (result.isGone) 0 else progressGap

    private fun startWidth() = leadWidth() + if (result.isGone) 0 else result.measuredWidth

    private fun endWidth() = if (exitIp.isGone) 0 else exitIp.measuredWidth

    private fun heightOf(view: View) = if (view.isGone) 0 else view.measuredHeight
}
