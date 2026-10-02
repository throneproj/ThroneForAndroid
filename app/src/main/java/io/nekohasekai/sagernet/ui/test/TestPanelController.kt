package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.CornerFamily
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortAction
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.nameList
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.widget.bars
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The bulk-test panel of the profiles screen, inflated into [container] (a slot dedicated to it at the bottom of
 * the profile list; the controller replaces its children and toggles its visibility). Construct it in
 * `onViewCreated`: it follows [TestSessionClient.state] for the view lifecycle, appears when a session starts and
 * stays after it ends until dismissed.
 *
 * With [side] the slot stands beside the list instead (a wide landscape window, its full height): the panel is always
 * expanded, its body takes the height the header leaves, and it slides in from the end.
 */
class TestPanelController(
    private val fragment: Fragment,
    private val container: ViewGroup,
    private val side: Boolean = false,
) {

    /** Selects a profile like a tap in the list; by default the panel selects it and reloads a running service. */
    var onSelectProfile: ((profileId: Long) -> Unit)? = null

    /** Called when the panel starts to appear, and when it has gone (after sliding out). */
    var onVisibilityChanged: ((visible: Boolean) -> Unit)? = null

    /**
     * The panel's height in pixels above the navigation bar while shown, 0 when hidden (e.g. to pad the list it
     * overlays). The panel reaches down to the bottom of [container]'s parent. Always 0 beside the list.
     */
    var onHeightChanged: ((height: Int) -> Unit)? = null

    /**
     * Views of other hierarchies floating over the bottom of the panel (MainActivity's FAB and stats bar): the content
     * stays above them wherever they are, e.g. while the stats bar hides on scroll.
     */
    var floatingViews: List<View> = emptyList()

    val isShown: Boolean get() = shown

    private val context: Context = container.context
    private val density = context.resources.displayMetrics.density
    private val owner: LifecycleOwner = try {
        fragment.viewLifecycleOwner
    } catch (e: IllegalStateException) {
        fragment
    }

    private val root = LayoutInflater.from(context)
        .inflate(R.layout.layout_test_panel, container, false) as MaterialCardView
    private val content: View = root.findViewById(R.id.test_panel_content)
    private val header: View = root.findViewById(R.id.test_panel_header)
    private val icon: ImageView = root.findViewById(R.id.test_panel_icon)
    private val title: TextView = root.findViewById(R.id.test_panel_title)
    private val status: TextView = root.findViewById(R.id.test_panel_status)
    private val stopButton: MaterialButton = root.findViewById(R.id.test_panel_stop)
    private val closeButton: ImageView = root.findViewById(R.id.test_panel_close)
    private val expandButton: ImageView = root.findViewById(R.id.test_panel_expand)
    private val liveLine: TextView = root.findViewById(R.id.test_panel_live)
    private val progress: SegmentedProgressView = root.findViewById(R.id.test_panel_progress)
    private val actions: View = root.findViewById(R.id.test_panel_actions)
    private val connectAction: Chip = root.findViewById(R.id.test_panel_action_connect)
    private val sortAction: Chip = root.findViewById(R.id.test_panel_action_sort)
    private val removeAction: Chip = root.findViewById(R.id.test_panel_action_remove)
    private val stopOtherAction: Chip = root.findViewById(R.id.test_panel_action_stop_other)
    private val body: TestPanelScrollView = root.findViewById(R.id.test_panel_body)
    private val okCount: TextView = root.findViewById(R.id.test_panel_ok_count)
    private val failedCount: TextView = root.findViewById(R.id.test_panel_failed_count)
    private val pendingCount: TextView = root.findViewById(R.id.test_panel_pending_count)
    private val pendingLabel: TextView = root.findViewById(R.id.test_panel_pending_label)
    private val latencySection: View = root.findViewById(R.id.test_panel_latency_section)
    private val latencyTitle: TextView = root.findViewById(R.id.test_panel_latency_title)
    private val histogram: LatencyHistogramView = root.findViewById(R.id.test_panel_histogram)
    private val legend: TextView = root.findViewById(R.id.test_panel_histogram_legend)
    private val speedSection: View = root.findViewById(R.id.test_panel_speed_section)
    private val speedProfile: TextView = root.findViewById(R.id.test_panel_speed_profile)
    private val speedServer: TextView = root.findViewById(R.id.test_panel_speed_server)
    private val chart: ThroughputChartView = root.findViewById(R.id.test_panel_chart)
    private val speedValues: TextView = root.findViewById(R.id.test_panel_speed_values)
    private val countrySection: View = root.findViewById(R.id.test_panel_country_section)
    private val countryTitle: TextView = root.findViewById(R.id.test_panel_country_title)
    private val chips: ChipGroup = root.findViewById(R.id.test_panel_countries)
    private val ipsLine: TextView = root.findViewById(R.id.test_panel_ips)
    private val rankSection: View = root.findViewById(R.id.test_panel_rank_section)
    private val rankTitle: TextView = root.findViewById(R.id.test_panel_rank_title)
    private val rankList: LinearLayout = root.findViewById(R.id.test_panel_rank_list)
    private val rankMore: TextView = root.findViewById(R.id.test_panel_rank_more)
    private val rankRows = ArrayList<RankRow>()

    @ColorInt
    private val accent = TestFormat.themeColor(context, R.attr.primaryOrTextPrimary, 0xFF607D8B.toInt())

    @ColorInt
    private val textPrimary = TestFormat.themeColor(context, android.R.attr.textColorPrimary, Color.BLACK)

    @ColorInt
    private val textSecondary = TestFormat.themeColor(context, android.R.attr.textColorSecondary, Color.GRAY)

    @ColorInt
    private val errorColor = TestFormat.themeColor(context, R.attr.colorError, FAILED_COLOR)

    @ColorInt
    private val okColor = TestFormat.latencyColor(1)

    @ColorInt
    private val uploadColor = companionColor(accent)

    @ColorInt
    private val trackColor = ColorUtils.setAlphaComponent(textPrimary, 0x1F)

    private val rtl = context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private var rendered: TestUiState? = null
    private var shown = false

    // The bottom sheet's state survives the screen; the side panel does not touch it.
    private var expanded = side || TestSessionClient.panelExpanded
    private var iconKind = Int.MIN_VALUE

    @ColorInt
    private var iconTint = 0
    private var legendFor: LatencyHistogram? = null
    private var reportedHeight = -1
    private var selectedId = 0L
    private var runningId = 0L
    private var bottomInset = 0
    private val clearanceGap = (4 * density).roundToInt()
    private val location = IntArray(2)

    private val clearanceListener = ViewTreeObserver.OnPreDrawListener {
        updateClearance()
        true
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setExpanded(false)
    }

    private val rankClick = View.OnClickListener { view ->
        (view.tag as? RankedResult)?.let { select(it.profileId, displayName(it)) }
    }

    private class RankRow(val view: View) {
        val index: TextView = view.findViewById(R.id.rank_index)
        val name: TextView = view.findViewById(R.id.rank_name)
        val bars: SpeedBarsView = view.findViewById(R.id.rank_bars)
        val value: TextView = view.findViewById(R.id.rank_value)
    }

    init {
        container.removeAllViews()
        container.addView(root)
        root.isVisible = false
        container.isVisible = false
        style()
        if (side) fillSlot()
        wire()
        applyExpanded()
        // Posted: the listener runs inside the layout pass and the callback may re-layout the list.
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(v: View?, l: Int, t: Int, r: Int, b: Int, oldL: Int, oldT: Int, oldR: Int, oldB: Int) {
                root.post(Runnable { reportHeight() })
            }
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            bottomInset = insets.bars().bottom
            root.post(Runnable { reportHeight() })
            insets
        }
        // Every frame: the stats bar and the FAB move without any layout of this hierarchy.
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = v.viewTreeObserver.addOnPreDrawListener(clearanceListener)

            override fun onViewDetachedFromWindow(v: View) =
                v.viewTreeObserver.removeOnPreDrawListener(clearanceListener)
        })
        if (root.isAttachedToWindow) root.viewTreeObserver.addOnPreDrawListener(clearanceListener)
        fragment.activity?.onBackPressedDispatcher?.addCallback(owner, backCallback)
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TestSessionClient.state.collect { render(it) } }
                launch {
                    // Elapsed time and ETA tick between results.
                    TestSessionClient.state.map { it.running }.distinctUntilChanged().collectLatest { running ->
                        while (running) {
                            delay(TICK_MS)
                            rendered?.let(::renderStatus)
                        }
                    }
                }
            }
        }
    }

    /** The bottom sheet's body shows or collapses (beside the list it always shows). */
    fun setExpanded(value: Boolean) {
        if (side || expanded == value) return
        expanded = value
        TestSessionClient.panelExpanded = value
        if (shown) TransitionManager.beginDelayedTransition(root, AutoTransition().setDuration(EXPAND_MS))
        applyExpanded()
        rendered?.takeIf { it.active }?.let {
            if (value) renderBody(it)
            renderLive(it)
        }
        backCallback.isEnabled = shown && expanded
    }

    /**
     * The selected profile and the running one (0 while stopped): "Connect to fastest" is not offered for the selected
     * one, and "Remove unavailable" leaves the running one alone like the desktop's clearUnavailableProfiles.
     */
    fun setProfileState(selectedId: Long, runningId: Long) {
        if (this.selectedId == selectedId && this.runningId == runningId) return
        this.selectedId = selectedId
        this.runningId = runningId
        rendered?.takeIf { it.active }?.let(::renderActions)
    }

    // ------------------------------------------------------------------------------------------------ setup

    private fun style() {
        val radius = context.resources.getDimension(R.dimen.card_corner_radius)
        // the bottom sheet rounds its top; beside the list only the corner between the list and the header is round
        val shape = root.shapeAppearanceModel.toBuilder().setAllCornerSizes(0f)
        if (!side || !rtl) shape.setTopLeftCorner(CornerFamily.ROUNDED, radius)
        if (!side || rtl) shape.setTopRightCorner(CornerFamily.ROUNDED, radius)
        root.shapeAppearanceModel = shape.build()
        progress.okColor = okColor
        progress.failedColor = FAILED_COLOR
        progress.testingColor = ColorUtils.setAlphaComponent(accent, 0x73)
        progress.trackColor = trackColor
        histogram.labelColor = textSecondary
        histogram.axisColor = trackColor
        chart.downloadColor = accent
        chart.uploadColor = uploadColor
        chart.gridColor = trackColor
        chart.labelColor = textSecondary
        okCount.setTextColor(okColor)
        failedCount.setTextColor(FAILED_COLOR)
        latencyTitle.setTextColor(accent)
        countryTitle.setTextColor(accent)
        rankTitle.setTextColor(accent)
        val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x33))
        stopButton.setTextColor(accent)
        stopButton.rippleColor = ripple
        ImageViewCompat.setImageTintList(closeButton, ColorStateList.valueOf(textSecondary))
        ImageViewCompat.setImageTintList(expandButton, ColorStateList.valueOf(textSecondary))
        connectAction.chipBackgroundColor = ColorStateList.valueOf(accent)
        connectAction.setTextColor(if (ColorUtils.calculateLuminance(accent) > 0.5) Color.BLACK else Color.WHITE)
        for (chip in listOf(sortAction, removeAction, stopOtherAction)) {
            chip.chipBackgroundColor = ColorStateList.valueOf(Color.TRANSPARENT)
            chip.chipStrokeColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x80))
            chip.chipStrokeWidth = density
            chip.setTextColor(accent)
            chip.rippleColor = ripple
        }
        if (!side) body.maxHeight = (context.resources.displayMetrics.heightPixels * BODY_MAX_FRACTION).toInt()
    }

    /** Beside the list: the slot's whole height, the body taking what the header leaves; nothing to collapse. */
    private fun fillSlot() {
        root.updateLayoutParams<ViewGroup.LayoutParams> { height = ViewGroup.LayoutParams.MATCH_PARENT }
        content.updateLayoutParams<ViewGroup.LayoutParams> { height = ViewGroup.LayoutParams.MATCH_PARENT }
        body.updateLayoutParams<LinearLayout.LayoutParams> {
            height = 0
            weight = 1f
        }
        expandButton.isVisible = false
        header.isClickable = false
        header.isFocusable = false
        header.background = null
    }

    private fun wire() {
        if (!side) wireSheet()
        stopButton.setOnClickListener { TestSessionClient.stop() }
        closeButton.setOnClickListener { TestSessionClient.dismiss() }
        connectAction.setOnClickListener { rendered?.let(::connectFastest) }
        sortAction.setOnClickListener { rendered?.let(::sort) }
        removeAction.setOnClickListener { rendered?.let(::removeUnavailable) }
        stopOtherAction.setOnClickListener {
            TestSessionClient.stopBackgroundSession()
            TestSessionClient.dismiss()
        }
    }

    /** The bottom sheet expands and collapses with a tap on the header or the chevron. */
    private fun wireSheet() {
        header.setOnClickListener { setExpanded(!expanded) }
        expandButton.setOnClickListener { setExpanded(!expanded) }
    }

    private fun applyExpanded() {
        body.isVisible = expanded
        if (side) return
        expandButton.setImageResource(
            if (expanded) R.drawable.ic_baseline_expand_more_24 else R.drawable.ic_baseline_expand_less_24
        )
        val label = context.getString(if (expanded) R.string.test_panel_collapse else R.string.test_panel_expand)
        expandButton.contentDescription = label
        ViewCompat.replaceAccessibilityAction(header, AccessibilityActionCompat.ACTION_CLICK, label, null)
    }

    // ------------------------------------------------------------------------------------------------ render

    private fun render(s: TestUiState) {
        setShown(s.active)
        if (s.active) {
            renderHeader(s)
            renderStatus(s)
            renderLive(s)
            renderProgress(s)
            renderActions(s)
            if (expanded) renderBody(s)
        }
        rendered = s
        backCallback.isEnabled = shown && expanded && !side
    }

    private fun setShown(show: Boolean) {
        if (shown == show) return
        shown = show
        root.animate().cancel()
        // the bottom sheet rises from below, the side panel comes in from the end
        val offset = 24 * density
        val offsetX = if (!side) 0f else if (rtl) -offset else offset
        val offsetY = if (side) 0f else offset
        if (show) {
            updateClearance()
            container.isVisible = true
            root.isVisible = true
            root.alpha = 0f
            root.translationX = offsetX
            root.translationY = offsetY
            root.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(SHOW_MS).start()
            onVisibilityChanged?.invoke(true)
        } else {
            root.animate().alpha(0f).translationX(offsetX).translationY(offsetY).setDuration(HIDE_MS).withEndAction {
                if (!shown) {
                    root.isVisible = false
                    container.isVisible = false
                    reportHeight()
                    onVisibilityChanged?.invoke(false)
                }
            }.start()
        }
    }

    private fun reportHeight() {
        // beside the list the panel covers no rows
        val height = if (!side && shown && root.isVisible) (root.height - bottomInset).coerceAtLeast(0) else 0
        if (height == reportedHeight) return
        reportedHeight = height
        onHeightChanged?.invoke(height)
    }

    /**
     * The panel reaches the bottom of its slot, under the navigation bar and the floating views; its content ends
     * above whichever of them reaches highest over that edge.
     */
    private fun updateClearance() {
        if (!shown) return
        val slot = container.parent as? View ?: return
        slot.getLocationInWindow(location)
        val bottom = location[1] + slot.height
        // across the panel's own width: a FAB beside the panel leaves its content where it is
        container.getLocationInWindow(location)
        val left = location[0]
        val right = left + container.width
        var clearance = bottomInset
        for (view in floatingViews) {
            if (!view.isShown || view.alpha == 0f) continue
            view.getLocationInWindow(location)
            if (location[0] >= right || location[0] + view.width * view.scaleX <= left) continue
            val reach = bottom - location[1]
            if (reach > 0) clearance = max(clearance, reach + clearanceGap)
        }
        if (content.paddingBottom != clearance) content.updatePadding(bottom = clearance)
    }

    private fun renderHeader(s: TestUiState) {
        if (iconKind != s.kind) {
            iconKind = s.kind
            icon.setImageResource(
                when (s.kind) {
                    TestSpec.KIND_IP -> R.drawable.ic_test_ip_24
                    TestSpec.KIND_SPEED -> R.drawable.ic_test_speed_24
                    else -> R.drawable.ic_test_latency_24
                }
            )
        }
        val tint = when {
            s.running -> accent
            s.failure != TestFailure.NONE -> errorColor
            !s.cancelled && s.ok > 0 -> okColor
            else -> textSecondary
        }
        if (tint != iconTint) {
            iconTint = tint
            ImageViewCompat.setImageTintList(icon, ColorStateList.valueOf(tint))
        }
        val kind = TestFormat.kindLabel(context, s.kind)
        title.setTextIfChanged(
            if (s.scopeLabel.isEmpty()) kind else context.getString(R.string.test_panel_title, kind, s.scopeLabel)
        )
        stopButton.isVisible = s.running
        stopButton.isEnabled = !s.stopping
        stopButton.setTextIfChanged(
            context.getString(if (s.stopping) R.string.test_panel_stopping else R.string.test_panel_stop)
        )
        closeButton.isVisible = !s.running
    }

    private fun renderStatus(s: TestUiState) {
        if (!s.active) return
        var color = textSecondary
        val text = when {
            s.failure != TestFailure.NONE -> {
                color = errorColor
                failureText(s.failure)
            }

            s.running && s.total == 0 -> context.getString(R.string.test_panel_preparing)
            s.running -> progressText(s)
            else -> {
                color = textPrimary
                summaryText(s)
            }
        }
        // Announce the outcome once, not every progress tick.
        val region = if (s.running) ViewCompat.ACCESSIBILITY_LIVE_REGION_NONE else ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE
        if (ViewCompat.getAccessibilityLiveRegion(status) != region) ViewCompat.setAccessibilityLiveRegion(status, region)
        status.setTextIfChanged(text)
        if (status.currentTextColor != color) status.setTextColor(color)
    }

    private fun renderLive(s: TestUiState) {
        val live = s.speed
        val show = !expanded && s.running && s.kind == TestSpec.KIND_SPEED && !s.countryMode && live != null
        liveLine.isVisible = show
        if (!show || live == null) return
        val detail = if (live.dlBps <= 0 && live.ulBps <= 0) {
            TestFormat.stageLabel(context, live.stage)
        } else {
            speedText(live.dlBps, live.ulBps, s.speedMode, " ")
        }
        val name = TestFormat.profileName(context, live.profileId, live.name)
        liveLine.setTextIfChanged(if (detail.isEmpty()) name else name + SEP + detail)
    }

    private fun renderProgress(s: TestUiState) {
        progress.setCounts(
            s.ok, s.failed, if (s.running) s.testing else 0, s.total, s.running && s.total == 0, s.testingProgress,
        )
        progress.contentDescription =
            context.getString(R.string.test_panel_progress_description, s.done, s.total, s.ok, s.failed)
    }

    private fun renderActions(s: TestUiState) {
        val canAct = s.finished && !s.testCurrent
        val best = s.ranking.firstOrNull()
        connectAction.isVisible = canAct && best != null && best.profileId != selectedId
        if (connectAction.isVisible) {
            connectAction.setTextIfChanged(
                context.getString(
                    if (runningId > 0) R.string.test_panel_action_connect_fastest
                    else R.string.test_panel_action_select_fastest
                )
            )
        }
        sortAction.isVisible = canAct && s.groupId > 0 && s.ok > 0 && s.kind != TestSpec.KIND_IP
        if (sortAction.isVisible) {
            sortAction.setTextIfChanged(
                context.getString(
                    if (s.kind == TestSpec.KIND_SPEED && !s.countryMode) R.string.test_panel_action_sort_speed
                    else R.string.test_panel_action_sort_latency
                )
            )
        }
        val removable = if (canAct && s.kind != TestSpec.KIND_IP) removableIds(s).size else 0
        removeAction.isVisible = removable > 0
        if (removable > 0) removeAction.setTextIfChanged(context.getString(R.string.test_panel_action_remove, removable))
        stopOtherAction.isVisible = s.finished && s.failure == TestFailure.BUSY
        actions.isVisible = connectAction.isVisible || sortAction.isVisible || removeAction.isVisible ||
            stopOtherAction.isVisible
    }

    private fun removableIds(s: TestUiState): List<Long> = s.failedIds().filter { it != runningId }

    private fun renderBody(s: TestUiState) {
        okCount.setTextIfChanged(s.ok.toString())
        failedCount.setTextIfChanged(s.failed.toString())
        pendingCount.setTextIfChanged(s.pending.toString())
        pendingLabel.setTextIfChanged(
            context.getString(if (s.running) R.string.test_panel_pending else R.string.test_panel_skipped)
        )
        val speed = s.kind == TestSpec.KIND_SPEED && !s.countryMode

        latencySection.isVisible = s.kind == TestSpec.KIND_URL && !s.testCurrent
        if (latencySection.isVisible) renderHistogram(s)

        countrySection.isVisible = (s.kind == TestSpec.KIND_IP || s.countryMode) && s.countries.isNotEmpty()
        if (countrySection.isVisible) renderCountries(s)

        speedSection.isVisible = speed && s.running && s.speed != null
        if (speedSection.isVisible) renderSpeed(s)

        rankSection.isVisible = s.kind != TestSpec.KIND_IP && !s.testCurrent && s.ranking.isNotEmpty()
        if (rankSection.isVisible) renderRanking(s, if (speed) TestSessionClient.RANK_LIMIT else FASTEST_LIMIT, speed)
    }

    private fun renderHistogram(s: TestUiState) {
        val data = s.histogram
        histogram.setData(data)
        if (legendFor == data) return
        legendFor = data
        val bands = data.bandCounts()
        val text = SpannableStringBuilder()
        fun item(@ColorInt color: Int, label: String, count: Int) {
            if (text.isNotEmpty()) text.append("   ")
            val start = text.length
            text.append('●')
            text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text.append(' ').append(label).append(' ').append(count.toString())
        }
        item(TestFormat.latencyColor(1), context.getString(R.string.test_panel_band_fast), bands[0])
        item(TestFormat.latencyColor(101), context.getString(R.string.test_panel_band_medium), bands[1])
        item(TestFormat.latencyColor(301), context.getString(R.string.test_panel_band_slow), bands[2])
        item(TestFormat.latencyColor(-1), context.getString(R.string.test_panel_failed), data.failed)
        if (data.connectOnly > 0) {
            item(TestFormat.latencyColor(-2), context.getString(R.string.test_panel_band_connect_only), data.connectOnly)
        }
        legend.text = text
        histogram.contentDescription = context.getString(
            R.string.test_panel_histogram_description, bands[0], bands[1], bands[2], data.failed
        )
    }

    private fun renderCountries(s: TestUiState) {
        val list = s.countries
        val visible = min(list.size, MAX_CHIPS)
        val extra = list.size - visible
        val needed = visible + if (extra > 0) 1 else 0
        while (chips.childCount > needed) chips.removeViewAt(chips.childCount - 1)
        while (chips.childCount < needed) chips.addView(newChip())
        for (i in 0 until visible) {
            val country = list[i]
            val chip = chips.getChildAt(i) as Chip
            val code = if (TestFormat.isCountryCode(country.code)) country.code.uppercase() else country.code
            chip.setTextIfChanged("${TestFormat.flag(country.code)} $code  ${country.count}".trim())
            chip.contentDescription = context.getString(
                R.string.test_panel_country_description, TestFormat.countryName(country.code), country.count
            )
        }
        if (extra > 0) (chips.getChildAt(visible) as Chip).apply {
            setTextIfChanged(context.getString(R.string.test_panel_more, extra))
            contentDescription = null
        }
        ipsLine.isVisible = s.kind == TestSpec.KIND_IP && s.distinctIps > 0
        if (ipsLine.isVisible) ipsLine.setTextIfChanged(context.getString(R.string.test_panel_distinct_ips, s.distinctIps))
    }

    private fun newChip(): Chip = Chip(context).apply {
        isCheckable = false
        isClickable = false
        isFocusable = false
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = 28 * density
        chipStartPadding = 8 * density
        chipEndPadding = 8 * density
        textStartPadding = 2 * density
        textEndPadding = 2 * density
        chipStrokeWidth = 0f
        chipBackgroundColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 0x1F))
        rippleColor = ColorStateList.valueOf(Color.TRANSPARENT)
        setTextColor(textPrimary)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private fun renderSpeed(s: TestUiState) {
        val live = s.speed ?: return
        speedProfile.setTextIfChanged(TestFormat.profileName(context, live.profileId, live.name))
        val parts = ArrayList<String>(3)
        TestFormat.stageLabel(context, live.stage).takeIf { it.isNotEmpty() }?.let(parts::add)
        val server = listOf(live.server, live.serverCountry).filter { it.isNotBlank() && it != "N/A" }
        if (server.isNotEmpty()) parts.add(server.joinToString(", "))
        if (live.pingMs > 0) parts.add(context.getString(R.string.test_panel_ping, live.pingMs.toInt()))
        speedServer.setTextIfChanged(parts.joinToString(SEP))
        chart.setSamples(live.samples)
        chart.contentDescription = context.getString(
            R.string.test_panel_chart_description, TestFormat.rate(live.dlBps), TestFormat.rate(live.ulBps)
        )
        val values = SpannableStringBuilder()
        if (showDownload(s.speedMode)) {
            appendColored(values, "↓ " + TestFormat.rate(live.dlBps), accent)
            values.append(SEP).append(TestFormat.bytes(context, live.dlBytes))
        }
        if (showUpload(s.speedMode)) {
            if (values.isNotEmpty()) values.append("     ")
            appendColored(values, "↑ " + TestFormat.rate(live.ulBps), uploadColor)
            values.append(SEP).append(TestFormat.bytes(context, live.ulBytes))
        }
        speedValues.text = values
    }

    private fun renderRanking(s: TestUiState, limit: Int, speed: Boolean) {
        rankTitle.setTextIfChanged(
            context.getString(
                when {
                    speed -> R.string.test_panel_completed
                    s.running -> R.string.test_panel_fastest_so_far
                    else -> R.string.test_panel_fastest
                }
            )
        )
        val list = if (s.ranking.size > limit) s.ranking.subList(0, limit) else s.ranking
        while (rankRows.size > list.size) {
            rankRows.removeAt(rankRows.size - 1)
            rankList.removeViewAt(rankList.childCount - 1)
        }
        while (rankRows.size < list.size) {
            val row = RankRow(LayoutInflater.from(context).inflate(R.layout.item_test_rank, rankList, false))
            row.view.setOnClickListener(rankClick)
            row.bars.downloadColor = accent
            row.bars.uploadColor = uploadColor
            row.bars.trackColor = trackColor
            rankRows.add(row)
            rankList.addView(row.view)
        }
        var maxDl = 1.0
        var maxUl = 1.0
        for (r in list) {
            if (r.dlBps > maxDl) maxDl = r.dlBps
            if (r.ulBps > maxUl) maxUl = r.ulBps
        }
        list.forEachIndexed { i, r ->
            val row = rankRows[i]
            val name = displayName(r)
            row.index.setTextIfChanged((i + 1).toString())
            row.name.setTextIfChanged(name)
            val value: String
            if (speed) {
                value = speedText(r.dlBps, r.ulBps, s.speedMode, "\n")
                row.value.setTextColor(textPrimary)
                row.bars.isVisible = true
                row.bars.setFractions(
                    if (showDownload(s.speedMode)) (r.dlBps / maxDl).toFloat() else -1f,
                    if (showUpload(s.speedMode)) (r.ulBps / maxUl).toFloat() else -1f,
                )
            } else {
                val flag = TestFormat.flag(r.country)
                val latency = TestFormat.latency(context, r.latency)
                value = if (flag.isEmpty()) latency else "$flag $latency"
                row.value.setTextColor(TestFormat.latencyColor(r.latency))
                row.bars.isVisible = false
            }
            row.value.setTextIfChanged(value)
            row.view.tag = r
            row.view.contentDescription =
                context.getString(R.string.test_panel_select_description, name, value.replace('\n', ' '))
        }
        val extra = s.rankable - list.size
        rankMore.isVisible = extra > 0
        if (extra > 0) rankMore.setTextIfChanged(context.getString(R.string.test_panel_more, extra))
    }

    // ------------------------------------------------------------------------------------------------ texts

    private fun progressText(s: TestUiState): String {
        val elapsed = System.currentTimeMillis() - s.startedAt
        val text = StringBuilder(context.getString(R.string.test_panel_progress, s.done, s.total))
        text.append(SEP).append(TestFormat.clock(elapsed))
        if (s.stopping) {
            text.append(SEP).append(context.getString(R.string.test_panel_stopping))
        } else if (s.done > 0 && s.pending > 0 && elapsed >= MIN_ETA_ELAPSED_MS) {
            val eta = (elapsed.toDouble() / s.done * s.pending).toLong()
            text.append(SEP).append(context.getString(R.string.test_panel_eta, TestFormat.clock(eta)))
        }
        return text.toString()
    }

    private fun summaryText(s: TestUiState): String {
        if (s.testCurrent) return currentSummary(s)
        val elapsed = TestFormat.duration(context, s.finishedAt - s.startedAt)
        val tested = s.ok + s.failed
        val head = if (s.cancelled) {
            context.getString(R.string.test_panel_summary_stopped, tested, s.total, elapsed)
        } else {
            context.getString(R.string.test_panel_summary_tested, tested, elapsed)
        }
        val tail = when {
            s.kind == TestSpec.KIND_IP -> if (s.ok > 0) {
                context.getString(R.string.test_panel_summary_resolved, s.ok, s.countries.size)
            } else {
                context.getString(R.string.test_panel_summary_none_resolved)
            }

            s.ok == 0 -> context.getString(R.string.test_panel_summary_none_working)
            s.countryMode -> context.getString(R.string.test_panel_summary_working_countries, s.ok, s.countries.size)
            else -> s.ranking.firstOrNull()?.let {
                context.getString(R.string.test_panel_summary_working_fastest, s.ok, bestText(s, it))
            } ?: context.getString(R.string.test_panel_summary_working, s.ok)
        }
        return context.getString(R.string.test_panel_summary, head, tail)
    }

    private fun currentSummary(s: TestUiState): String {
        val row = s.rows.values.firstOrNull { it.ok || it.failed }
            ?: return context.getString(R.string.test_panel_stage_cancelled)
        if (row.failed) return context.getString(R.string.test_panel_current_unavailable, row.error)
        return when {
            s.kind == TestSpec.KIND_IP -> listOf(TestFormat.flag(row.country), row.ip).filter { it.isNotEmpty() }
                .joinToString(" ")

            s.countryMode && row.latency <= 0 -> TestFormat.countryName(row.country)

            s.countryMode -> context.getString(
                R.string.test_panel_current_country,
                TestFormat.countryName(row.country),
                TestFormat.latency(context, row.latency),
            )

            s.kind == TestSpec.KIND_SPEED -> speedText(
                GroupSort.bitrateToBps(row.dlSpeed).coerceAtLeast(0.0),
                GroupSort.bitrateToBps(row.ulSpeed).coerceAtLeast(0.0),
                s.speedMode,
                "  ",
            )

            row.latency > 0 -> TestFormat.latency(context, row.latency)
            else -> context.getString(R.string.test_panel_band_connect_only)
        }
    }

    private fun bestText(s: TestUiState, best: RankedResult): String = when {
        s.kind == TestSpec.KIND_SPEED && !s.countryMode ->
            TestFormat.rate(if (s.speedMode == TestUiState.SPEED_MODE_UPLOAD) best.ulBps else best.dlBps)

        else -> TestFormat.latency(context, best.latency)
    }

    private fun failureText(failure: TestFailure): String = context.getString(
        when (failure) {
            TestFailure.EMPTY -> R.string.test_panel_failure_empty
            TestFailure.BUSY -> R.string.test_panel_failure_busy
            TestFailure.CORE_DIED -> R.string.test_panel_failure_core_died
            else -> R.string.test_panel_failure_unreachable
        }
    )

    private fun speedText(dlBps: Double, ulBps: Double, mode: Int, separator: String): String {
        val parts = ArrayList<String>(2)
        if (showDownload(mode)) parts.add("↓ " + TestFormat.rate(dlBps))
        if (showUpload(mode)) parts.add("↑ " + TestFormat.rate(ulBps))
        return parts.joinToString(separator)
    }

    private fun showDownload(mode: Int) = mode != TestUiState.SPEED_MODE_UPLOAD

    private fun showUpload(mode: Int) = mode == TestUiState.SPEED_MODE_FULL || mode == TestUiState.SPEED_MODE_UPLOAD

    private fun displayName(r: RankedResult) = TestFormat.profileName(context, r.profileId, r.name)

    // ------------------------------------------------------------------------------------------------ actions

    private fun select(profileId: Long, name: String) {
        val custom = onSelectProfile
        if (custom != null) {
            custom(profileId)
        } else {
            runOnDefaultDispatcher {
                val last = DataStore.selectedProxy
                if (last == profileId) return@runOnDefaultDispatcher
                DataStore.selectedProxy = profileId
                ProfileManager.postUpdate(last, noTraffic = true)
                ProfileManager.postUpdate(profileId, noTraffic = true)
                if (DataStore.serviceState.canStop) SagerNet.reloadService()
            }
        }
        if (fragment.isAdded) fragment.snackbar(context.getString(R.string.test_panel_selected, name)).show()
    }

    private fun connectFastest(s: TestUiState) {
        val best = s.ranking.firstOrNull() ?: return
        select(best.profileId, displayName(best))
    }

    private fun sort(s: TestUiState) {
        val groupId = s.groupId
        if (groupId <= 0) return
        val bySpeed = s.kind == TestSpec.KIND_SPEED && !s.countryMode
        val upload = s.speedMode == TestUiState.SPEED_MODE_UPLOAD
        runOnDefaultDispatcher {
            val sorted = if (bySpeed) {
                // Fastest first; "N/A" (failed) sorts after untested like the desktop's bitrate key.
                val order = ProfileManager.members(groupId)
                    .sortedByDescending { GroupSort.bitrateToBps(if (upload) it.ulSpeed else it.dlSpeed) }
                ProfileManager.setOrder(groupId, order.map { it.id })
                true
            } else {
                GroupSort.sortProfiles(groupId, GroupSortAction(GroupSortMethod.BY_LATENCY))
            }
            onMainDispatcher {
                if (fragment.isAdded) {
                    fragment.snackbar(if (sorted) R.string.test_panel_sorted else R.string.test_panel_sort_busy).show()
                }
            }
        }
    }

    private fun removeUnavailable(s: TestUiState) {
        val candidates = removableIds(s)
        if (candidates.isEmpty()) return
        runOnDefaultDispatcher {
            // auto_clear_unavailable may already have deleted them in :bg.
            val position = candidates.withIndex().associate { it.value to it.index }
            val existing = ProfileManager.getProfiles(candidates).sortedBy { position[it.id] }
            val existingIds = existing.map { it.id }
            val existingSet = existingIds.toHashSet()
            val gone = candidates.filter { it !in existingSet }
            if (gone.isNotEmpty()) TestSessionClient.markRemoved(gone)
            if (existing.isEmpty()) return@runOnDefaultDispatcher
            val skipConfirmation = DataStore.skipDeleteConfirmation
            onMainDispatcher {
                if (!fragment.isAdded) return@onMainDispatcher
                if (skipConfirmation) {
                    deleteProfiles(existingIds)
                } else {
                    context.confirmAction(
                        context.resources.getQuantityString(
                            R.plurals.confirm_remove_unavailable, existing.size, existing.size
                        ),
                        context.nameList(existing.map { it.displayName() }),
                        R.string.delete,
                    ) { deleteProfiles(existingIds) }
                }
            }
        }
    }

    private fun deleteProfiles(ids: List<Long>) {
        runOnDefaultDispatcher {
            val running = ProfileManager.runningProfileId()
            val outcome = ProfileManager.batchDeleteProfiles(ids, stopRunning = false)
            TestSessionClient.markRemoved(outcome.deleted)
            val keptRunning = running > 0 && running in outcome.kept
            onMainDispatcher {
                if (!fragment.isAdded) return@onMainDispatcher
                val text = if (keptRunning) {
                    context.getString(R.string.test_panel_removed_kept, outcome.deleted.size)
                } else {
                    context.getString(R.string.test_panel_removed, outcome.deleted.size)
                }
                fragment.snackbar(text).show()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private fun appendColored(text: SpannableStringBuilder, part: String, @ColorInt color: Int) {
        val start = text.length
        text.append(part)
        text.setSpan(ForegroundColorSpan(color), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun TextView.setTextIfChanged(value: CharSequence) {
        if (!TextUtils.equals(text, value)) text = value
    }

    private companion object {
        const val FAILED_COLOR = 0xFFE53935.toInt()
        const val FASTEST_LIMIT = 5
        const val MAX_CHIPS = 16
        const val TICK_MS = 1000L
        const val EXPAND_MS = 180L
        const val SHOW_MS = 220L
        const val HIDE_MS = 160L
        const val MIN_ETA_ELAPSED_MS = 2000L
        const val BODY_MAX_FRACTION = 0.45f
        const val SEP = " · "

        /** A hue far from [color] for the upload curve; amber when the accent is grey. */
        @ColorInt
        fun companionColor(@ColorInt color: Int): Int {
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            if (hsl[1] < 0.2f) return 0xFFFFA000.toInt()
            hsl[0] = (hsl[0] + 150f) % 360f
            hsl[1] = hsl[1].coerceAtLeast(0.55f)
            hsl[2] = hsl[2].coerceIn(0.45f, 0.6f)
            return ColorUtils.HSLToColor(hsl)
        }
    }
}
