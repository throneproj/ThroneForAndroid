package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.whenStarted
import com.google.android.material.bottomappbar.BottomAppBar
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearancePathProvider
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.test.TestFormat
import io.nekohasekai.sagernet.SagerNet
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class StatsBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
    defStyleAttr: Int = R.attr.bottomAppBarStyle,
) : BottomAppBar(context, attrs, defStyleAttr) {
    companion object {
        private const val INITIAL_HIDE_DELAY_MS = 100L
        private const val SCROLL_TOGGLE_THRESHOLD_DP = 8f
    }

    private enum class Transition {
        ShowImmediate,
        ShowAnimated,
        HideImmediate,
        HideAfterStart,
    }

    private lateinit var statusText: TextView
    private lateinit var txText: TextView
    private lateinit var rxText: TextView
    private var exitText: TextView? = null
    private var statusLine: CharSequence = ""
    private var stateReported = false
    private val exitListener = { showExit() }
    @Suppress("unused")
    private lateinit var behavior: YourBehavior
    private var currentState = BaseService.State.Idle
    private var pendingTransition: Transition? = Transition.HideImmediate
    private var transitionJob: Job? = null

    private var scrollHidden = false
    private var scrollDirection = 0 // 1 = hide (dy>0), -1 = show (dy<0), 0 = none
    private var scrollAccumulatedDy = 0
    private val scrollToggleThresholdPx =
        (SCROLL_TOGGLE_THRESHOLD_DP * resources.displayMetrics.density).toInt().coerceAtLeast(8)

    var useExternalScrollDriver = false
        set(value) {
            if (field == value) return
            field = value
            syncScrollHiddenFromView()
            resetScrollDriverState()
            updateHideOnScroll()
        }

    var allowShow = false
        set(value) {
            field = value
            updateHideOnScroll()
        }

    init {
        alpha = 0f
        // Replaces the rectangular content ripple and stats_focus, which also filled the FAB cradle (#92).
        (background as? MaterialShapeDrawable)?.let { shape ->
            val highlight = MaterialColors.getColor(this, R.attr.colorControlHighlight, 0x33FFFFFF)
            val stroke = MaterialColors.getColor(this, R.attr.whiteOrTextPrimary, 0xFFFFFFFF.toInt())
            foreground = RippleDrawable(
                ColorStateList.valueOf(highlight),
                ShapeFocusDrawable(shape, stroke, 2 * resources.displayMetrics.density),
                ShapeMaskDrawable(shape),
            )
        }
    }

    override fun getBehavior(): YourBehavior {
        if (!this::behavior.isInitialized) behavior = YourBehavior()
        return behavior
    }

    inner class YourBehavior : Behavior() {

        override fun onStartNestedScroll(
            coordinatorLayout: CoordinatorLayout,
            child: BottomAppBar,
            directTarget: View,
            target: View,
            nestedScrollAxes: Int,
            type: Int,
        ): Boolean {
            if (useExternalScrollDriver) return false
            return super.onStartNestedScroll(
                coordinatorLayout,
                child,
                directTarget,
                target,
                nestedScrollAxes,
                type,
            )
        }

        override fun slideUp(child: BottomAppBar) {
            if (!allowShow) return
            super.slideUp(child)
        }
    }

    // The ripple renders its mask once and keeps it until invalidated. A cradle animation (FAB hide/show interpolation,
    // offsets) invalidates the background shape, so that drops the ripple's mask too; the mask is only redrawn while a
    // ripple shows.
    override fun invalidateDrawable(drawable: Drawable) {
        super.invalidateDrawable(drawable)
        if (drawable === background) foreground?.invalidateSelf()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // BottomAppBar places the cradle here, not always through an invalidation of the shape.
        foreground?.invalidateSelf()
        val transition = pendingTransition
        if (transition != null) {
            pendingTransition = null
            applyTransition(transition)
        } else if (alpha == 0f) {
            alpha = 1f
        }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        statusText = findViewById(R.id.status)
        txText = findViewById(R.id.tx)
        rxText = findViewById(R.id.rx)
        exitText = findViewById(R.id.exit_country)
        super.setOnClickListener(l)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ExitIp.listener = exitListener
        showExit()
    }

    // A rotation keeps the lookup; a destroyed activity drops it, since the service may restart unseen meanwhile.
    override fun onDetachedFromWindow() {
        if (ExitIp.listener === exitListener) ExitIp.listener = null
        if ((context as? Activity)?.isChangingConfigurations != true) ExitIp.clear()
        super.onDetachedFromWindow()
    }

    private fun setStatus(text: CharSequence) {
        statusLine = text
        statusText.text = text
        updateTooltip()
    }

    private fun updateTooltip() {
        val exit = exitText?.takeIf { it.isVisible }?.contentDescription
        TooltipCompat.setTooltipText(this, if (exit == null) statusLine else "$statusLine\n$exit")
    }

    /**
     * runningCountryInfo (mainwindow_view.cpp:151-165): the exit's flag and country, no IP or city. Hidden while
     * exit_country_lookup is off: ExitIp then holds no result and runs no lookup, a tap's refresh() included.
     */
    private fun showExit() {
        val view = exitText ?: return
        val connected = currentState == BaseService.State.Connected
        val info = ExitIp.info?.takeIf { connected }
        // A running lookup dims the previous exit, or holds a placeholder when there is none yet.
        val refreshing = connected && ExitIp.refreshing
        view.alpha = if (refreshing) 0.5f else 1f
        if (info == null) {
            view.text = "…"
            view.contentDescription = null
            view.isVisible = refreshing
        } else {
            val country = if (TestFormat.isCountryCode(info.countryCode)) {
                TestFormat.countryName(info.countryCode)
            } else {
                info.country
            }
            val flag = TestFormat.flag(info.countryCode)
            view.text = if (flag.isEmpty()) country else "$flag\u00A0$country"
            view.contentDescription = country
            view.isVisible = true
        }
        updateTooltip()
    }

    // On TV the bar stays put: it holds the URL test and must stay reachable by D-pad.
    private fun updateHideOnScroll() {
        hideOnScroll = !SagerNet.isTv &&
            !useExternalScrollDriver && allowShow && currentState == BaseService.State.Connected
        isFocusable = shouldShow()
    }

    private fun shouldShow(): Boolean {
        return allowShow && currentState == BaseService.State.Connected
    }

    private fun resetScrollDriverState() {
        scrollDirection = 0
        scrollAccumulatedDy = 0
    }

    private fun syncScrollHiddenFromView() {
        if (!isLaidOut || height <= 0) return
        scrollHidden = translationY >= height / 2f
    }

    fun onListScrolled(dy: Int) {
        if (SagerNet.isTv || !useExternalScrollDriver || !shouldShow() || dy == 0) return

        val direction = if (dy > 0) 1 else -1
        if (direction != scrollDirection) {
            scrollDirection = direction
            scrollAccumulatedDy = 0
        }
        scrollAccumulatedDy += dy

        val wantHidden = scrollAccumulatedDy > 0
        if (wantHidden == scrollHidden) {
            if (abs(scrollAccumulatedDy) > scrollToggleThresholdPx) {
                scrollAccumulatedDy = direction * scrollToggleThresholdPx
            }
            return
        }
        if (abs(scrollAccumulatedDy) < scrollToggleThresholdPx) return

        scrollHidden = wantHidden
        scrollAccumulatedDy = 0
        isFocusable = !wantHidden
        if (wantHidden) performHide() else performShow()
    }

    fun syncMainControls(
        showControls: Boolean,
        state: BaseService.State,
        showWhenConnected: Boolean,
        animate: Boolean,
    ) {
        currentState = state
        allowShow = showControls
        when {
            !showControls || state != BaseService.State.Connected -> {
                applyTransition(
                    if (animate && showControls) Transition.HideAfterStart else Transition.HideImmediate
                )
            }

            showWhenConnected -> applyTransition(
                if (animate) Transition.ShowAnimated else Transition.ShowImmediate
            )
            alpha == 0f && isLaidOut -> alpha = 1f
        }
    }

    private fun applyTransition(transition: Transition) {
        if (transition == Transition.HideImmediate && hasPendingDelayedHide()) return
        cancelPendingTransition()
        if (!isLaidOut || height == 0) {
            pendingTransition = transition
            alpha = if (transition == Transition.HideImmediate && !allowShow) 0f else 1f
            return
        }
        pendingTransition = null
        when (transition) {
            Transition.ShowImmediate -> commitVisible(animated = false)
            Transition.ShowAnimated -> commitVisible(animated = true)
            Transition.HideImmediate -> commitHidden(animated = false)
            Transition.HideAfterStart -> commitHidden(animated = true)
        }
    }

    private fun commitVisible(animated: Boolean) {
        alpha = 1f
        scrollHidden = false
        resetScrollDriverState()
        if (animated) {
            performShow()
        } else {
            getBehavior().slideUp(this)
            animate().cancel()
            translationY = 0f
        }
    }

    private fun commitHidden(animated: Boolean) {
        alpha = 1f
        scrollHidden = true
        resetScrollDriverState()
        if (!animated) {
            syncHiddenPosition()
            return
        }
        val activity = context as? MainActivity
        if (activity == null) {
            post {
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
            return
        }
        transitionJob = activity.lifecycleScope.launch(Dispatchers.Main) {
            delay(INITIAL_HIDE_DELAY_MS)
            activity.whenStarted {
                transitionJob = null
                if (shouldShow()) {
                    commitVisible(animated = false)
                } else if (isLaidOut && height > 0) {
                    performHide()
                } else {
                    pendingTransition = Transition.HideAfterStart
                }
            }
        }
    }

    private fun syncHiddenPosition() {
        getBehavior().slideDown(this)
        animate().cancel()
        translationY = height.toFloat()
        alpha = 1f
    }

    private fun cancelPendingTransition() {
        transitionJob?.cancel()
        transitionJob = null
    }

    private fun hasPendingDelayedHide(): Boolean {
        return pendingTransition == Transition.HideAfterStart || transitionJob != null
    }

    fun changeState(state: BaseService.State) {
        currentState = state
        updateHideOnScroll()
        when {
            state == BaseService.State.Connected -> ExitIp.ensure()
            // MainActivity.onCreate reports Idle before its service connection does: a rotation keeps the lookup.
            state != BaseService.State.Idle || stateReported -> ExitIp.clear()
        }
        stateReported = true
        showExit()
        if (state == BaseService.State.Connected) {
            setStatus(app.getText(R.string.vpn_connected))
        } else {
            updateSpeed(0, 0)
            setStatus(
                context.getText(
                    when (state) {
                        BaseService.State.Connecting -> R.string.connecting
                        BaseService.State.Stopping -> R.string.stopping
                        else -> R.string.not_connected
                    }
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, txRate)
            )
        }"
        rxText.text = "▼  ${
            context.getString(
                R.string.speed, Formatter.formatFileSize(context, rxRate)
            )
        }"
    }

    fun testConnection() {
        val activity = context as MainActivity
        isEnabled = false
        setStatus(app.getText(R.string.connection_test_testing))
        runOnDefaultDispatcher {
            try {
                val elapsed = activity.urlTest()
        onMainDispatcher {
                    isEnabled = true
                    setStatus(
                        if (elapsed == ProxyEntity.LATENCY_CONNECT_ONLY) {
                            app.getText(R.string.test_connect_ok)
                        } else {
                            "$elapsed ms"
                        }
                    )
                }

            } catch (e: Exception) {
                Logs.w(e.toString())
                onMainDispatcher {
                    isEnabled = true
                    setStatus(app.getText(R.string.connection_test_testing))

                    activity.snackbar(
                        app.getString(
                            R.string.connection_test_error, e.readableMessage
                        )
                    ).show()
                }
            }
            // After the test, not beside it: one request at a time through the running connection.
            onMainDispatcher { ExitIp.refresh() }
        }
    }

}

/** The bar background's live outline (cradle included) for the same bounds. */
private class BarOutline(private val shape: MaterialShapeDrawable) {
    private val provider = ShapeAppearancePathProvider()
    private val rect = RectF()
    private val path = Path()

    fun of(bounds: Rect): Path {
        rect.set(bounds)
        path.rewind()
        provider.calculatePath(shape.shapeAppearanceModel, shape.interpolation, rect, path)
        return path
    }
}

/** The ripple's mask; translucent, since RippleDrawable only clips to the bounds for an opaque one. */
private class ShapeMaskDrawable(shape: MaterialShapeDrawable) : Drawable() {
    private val outline = BarOutline(shape)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        canvas.drawPath(outline.of(bounds), paint)
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The focus stroke of stats_focus, inside the outline; the ripple draws the focused fill. */
private class ShapeFocusDrawable(shape: MaterialShapeDrawable, strokeColor: Int, strokeWidthPx: Float) : Drawable() {
    private val outline = BarOutline(shape)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = strokeColor
        // Clipped to the outline, so only the inner half shows.
        strokeWidth = strokeWidthPx * 2
    }
    private var focused = false

    override fun isStateful() = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = android.R.attr.state_focused in state
        if (now == focused) return false
        focused = now
        invalidateSelf()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (!focused) return
        val path = outline.of(bounds)
        val save = canvas.save()
        canvas.clipPath(path)
        canvas.drawPath(path, paint)
        canvas.restoreToCount(save)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
