package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.TestShowItems
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.ui.test.RowPhase
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.Protocols.getProtocolColor
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * The texts of one card, computed apart from the views so the grid can align the error lines of neighbours: the
 * result holds the desktop DisplayTestResult as separate items that wrap between each other (or the live test phase)
 * and sits beside the type when it fits ([TypeLineLayout]); the error line is the Android-only `test_error` of a
 * failed test.
 */
internal class RowContent(
    val result: CharSequence,
    val resultDescription: String?,
    val resultDimmed: Boolean,
    val testing: Boolean,
    val traffic: String,
    val trafficDescription: String?,
    val address: String,
    val error: String?,
) {
    /** The lines under the type line, which a grid neighbour keeps room for; the lines above align by themselves. */
    val rows: Int
        get() = if (error != null) ROW_ERROR else 0

    /** One result item: [spoken] stands in for its symbols. */
    private class Item(val text: String, @ColorInt val color: Int, val spoken: String)

    companion object {
        const val ROW_ERROR = 1

        private const val LRO = "\u202D"
        private const val NBSP = "\u00A0"

        /** Between items, where the line may break. */
        private const val GAP = "\u2002 "

        /** A core rate: "34.13Mbps". */
        private val RATE = Regex("""(\d+(?:\.\d+)?)\s*([KMG]bps)""", RegexOption.IGNORE_CASE)

        fun of(context: Context, profile: ProxyEntity, adapter: ProfileListAdapter): RowContent {
            val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
            val showTraffic = profile.rx + profile.tx != 0L
            val up = Formatter.formatShortFileSize(context, profile.tx)
            val down = Formatter.formatShortFileSize(context, profile.rx)
            val address = if (adapter.host?.alwaysShowAddress == true && profile.outbound.name.isNotBlank()) {
                profile.displayAddress()
            } else ""

            val phase = adapter.phaseOf(profile.id)
            val latency = profile.latency
            val color = profile.latencyColor().takeIf { it != 0 } ?: secondary
            val result = SpannableStringBuilder()
            var description: String? = null
            // Profile::DisplayTestResult: a failed or connect-only test shows its verdict alone
            when {
                phase == RowPhase.TESTING -> result.append(context.getString(R.string.connection_test_testing))
                latency == ProxyEntity.LATENCY_CONNECT_ONLY || latency < 0 -> result.append(
                    context.getString(
                        if (latency == ProxyEntity.LATENCY_CONNECT_ONLY) R.string.test_connect_ok else R.string.unavailable
                    ),
                    ForegroundColorSpan(color),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )

                else -> {
                    val items = testResult(context, profile, TestShowItems.of(adapter.testItemsToShow), color, secondary)
                    if (items.isNotEmpty()) {
                        result.append(LRO)
                        items.forEachIndexed { index, item ->
                            if (index > 0) result.append(GAP)
                            result.append(item.text, ForegroundColorSpan(item.color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                        description = items.joinToString(", ") { it.spoken }
                    } else if (phase == RowPhase.QUEUED) {
                        result.append(context.getString(R.string.profiles_test_queued))
                    }
                }
            }
            val error = if (phase == null) profile.testError?.takeIf { it.isNotBlank() } else null
            return RowContent(
                result = result,
                resultDescription = description,
                resultDimmed = phase == RowPhase.QUEUED,
                testing = phase == RowPhase.TESTING,
                traffic = if (showTraffic) context.getString(R.string.traffic, up, down) else "",
                trafficDescription = if (showTraffic) context.getString(R.string.profile_row_traffic, up, down) else null,
                address = address,
                error = error,
            )
        }

        /**
         * The items of Profile::DisplayTestResult (Profile.cpp:36-56): flag and latency in the latency colour, then
         * the speeds and the egress IP as the group's test_items_to_show allows.
         */
        private fun testResult(
            context: Context,
            profile: ProxyEntity,
            show: TestShowItems,
            @ColorInt color: Int,
            @ColorInt secondary: Int,
        ): List<Item> {
            val latency = profile.latency
            val items = ArrayList<Item>()
            val head = ArrayList<String>()
            val headSpoken = ArrayList<String>()
            profile.testCountry?.takeIf { it.isNotEmpty() }?.let { country ->
                val code = country.length == 2 && country.all { it in 'A'..'Z' || it in 'a'..'z' }
                head += if (code) ProxyEntity.countryFlag(country) else country
                headSpoken += if (code) countryName(country) else country
            }
            if (latency > 0) {
                head += "$latency${NBSP}ms"
                headSpoken += "$latency ms"
            }
            if (head.isNotEmpty()) items += Item(head.joinToString(NBSP), color, headSpoken.joinToString(", "))
            if (show.showSpeed) speeds(context, profile.dlSpeed, profile.ulSpeed, secondary)?.let { items += it }
            val ip = profile.ipOut.orEmpty()
            if (show.showIp && ip.isNotEmpty()) {
                items += Item("🌐$NBSP$ip", secondary, context.getString(R.string.profile_row_ip, ip))
            }
            return items
        }

        /** "↓34.1 ↑11.6 Mbps": three significant digits, one unit when both rates share it; "N/A" is left out. */
        private fun speeds(context: Context, dl: String?, ul: String?, @ColorInt color: Int): Item? {
            fun parse(text: String?): Pair<String, String>? {
                if (text.isNullOrEmpty() || text == ProxyEntity.SPEED_NA) return null
                val match = RATE.matchEntire(text.trim()) ?: return text to ""
                val value = BigDecimal(match.groupValues[1])
                val decimals = when {
                    value >= BigDecimal(100) -> 0
                    value >= BigDecimal.TEN -> 1
                    else -> 2
                }
                return value.setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() to
                    match.groupValues[2]
            }

            val down = parse(dl)
            val up = parse(ul)
            if (down == null && up == null) return null
            val shared = down != null && up != null && down.second.isNotEmpty() && down.second == up.second
            fun part(arrow: String, rate: Pair<String, String>, unit: Boolean) =
                arrow + rate.first + if (unit && rate.second.isNotEmpty()) NBSP + rate.second else ""

            val text = listOfNotNull(down?.let { part("↓", it, !shared) }, up?.let { part("↑", it, true) })
                .joinToString(NBSP)
            val spoken = listOfNotNull(
                down?.let { context.getString(R.string.profile_row_download, "${it.first} ${it.second}".trim()) },
                up?.let { context.getString(R.string.profile_row_upload, "${it.first} ${it.second}".trim()) },
            ).joinToString(", ")
            return Item(text, color, spoken)
        }

        private fun countryName(code: String): String =
            runCatching { Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build().displayCountry }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: code
    }
}

internal class ProfileRowHolder(view: View, private val adapter: ProfileListAdapter) :
    RecyclerView.ViewHolder(view) {

    private val card = view as MaterialCardView
    private val selectCheck: CheckBox = view.findViewById(R.id.select_check)
    private val name: TextView = view.findViewById(R.id.profile_name)
    private val editButton: View = view.findViewById(R.id.edit)
    private val shareButton: View = view.findViewById(R.id.share)
    private val moreButton: View = view.findViewById(R.id.more)
    private val address: TextView = view.findViewById(R.id.profile_address)
    private val result: TextView = view.findViewById(R.id.profile_result)
    private val testProgress: View = view.findViewById(R.id.test_progress)
    private val type: TextView = view.findViewById(R.id.profile_type)
    private val traffic: TextView = view.findViewById(R.id.traffic_text)
    private val error: TextView = view.findViewById(R.id.profile_error)
    private val selectedIndicator: View = view.findViewById(R.id.selected_indicator)

    lateinit var profile: ProxyEntity
        private set

    /** The optional rows of the last bind ([RowContent.rows]), null before the first. */
    var boundRows: Int? = null
        private set
    var boundTx = Long.MIN_VALUE
        private set
    var boundRx = Long.MIN_VALUE
        private set

    init {
        view.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.onRowClick(profile)
        }
        view.setOnLongClickListener { v ->
            ::profile.isInitialized && adapter.host?.onRowLongClick(this, profile, v.isInTouchMode) == true
        }
        editButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.edit(profile, adapter.group)
        }
        shareButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.showShare(it, profile)
        }
        moreButton.setOnClickListener {
            if (::profile.isInitialized) adapter.host?.itemMenu?.showMenu(it, profile, adapter)
        }
        val showError = View.OnClickListener {
            val text = if (::profile.isInitialized) profile.testError else null
            if (!text.isNullOrBlank()) it.context.alert(text).tryToShow()
        }
        result.setOnClickListener(showError)
        error.setOnClickListener(showError)
        result.isFocusable = false
        view.findViewById<ImageView>(R.id.shareIcon).setColorFilter(Color.GRAY)
    }

    fun bind(profile: ProxyEntity) {
        this.profile = profile
        val context = itemView.context
        name.text = profile.displayName()
        type.text = profile.displayType()
        type.setTextColor(context.getProtocolColor(profile.type))
        bindContent()
        bindState()
    }

    /** The address, result, traffic and error texts. */
    fun bindContent() {
        val content = RowContent.of(itemView.context, profile, adapter)
        address.text = content.address
        address.isVisible = content.address.isNotEmpty()
        result.text = content.result
        result.contentDescription = content.resultDescription
        result.alpha = if (content.resultDimmed) 0.5f else 1f
        result.isVisible = content.result.isNotEmpty()
        testProgress.isVisible = content.testing
        traffic.text = content.traffic
        traffic.contentDescription = content.trafficDescription
        traffic.isVisible = content.traffic.isNotEmpty()
        error.text = content.error?.let(Protocols::genFriendlyMsg)
        error.maxLines = if (adapter.isCompact) 1 else 2
        result.isClickable = content.error != null
        error.isClickable = content.error != null

        val rows = content.rows
        val reserved = adapter.neighbourRows(bindingAdapterPosition) and rows.inv()
        error.visibility = rowVisibility(rows, reserved, RowContent.ROW_ERROR)
        val previous = boundRows
        boundRows = rows
        boundTx = profile.tx
        boundRx = profile.rx
        if (previous != null && previous != rows) adapter.refreshRowNeighbours(bindingAdapterPosition)
    }

    /** A recycled card belongs to no grid row yet: its next bind must not refresh neighbours. */
    fun onRecycled() {
        boundRows = null
        boundTx = Long.MIN_VALUE
        boundRx = Long.MIN_VALUE
    }

    /** A row missing here but shown by a card of the same grid row keeps its height (INVISIBLE). */
    private fun rowVisibility(rows: Int, reserved: Int, row: Int) = when {
        rows and row != 0 -> View.VISIBLE
        reserved and row != 0 -> View.INVISIBLE
        else -> View.GONE
    }

    /** Selection, running state, multi-select check and the row buttons. */
    fun bindState() {
        val host = adapter.host ?: return
        val id = profile.id
        val selecting = host.selection.active
        val buttons = !host.select && !selecting
        selectCheck.isVisible = selecting
        selectCheck.isChecked = selecting && host.selection.isChecked(id)
        editButton.isVisible = buttons && !adapter.isCompact
        shareButton.isVisible = buttons && !adapter.isCompact && !profile.isChain()
        moreButton.isVisible = buttons
        applyCardColors(host.isSelectedProfile(id), selectCheck.isChecked)
    }

    private fun applyCardColors(selected: Boolean, checked: Boolean) {
        val context = card.context
        val surface = context.getColorAttr(R.attr.colorSurface)
        var background = surface
        selectedIndicator.isVisible = selected
        card.strokeWidth = 0
        card.cardElevation = context.resources.getDimension(R.dimen.profile_card_elevation_classic)
        if (selected) {
            val primary = context.getColorAttr(R.attr.selectedColorPrimary)
            background = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(primary, 20), surface)
        }
        if (checked) {
            val accent = context.getColorAttr(R.attr.colorAccent)
            background = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 48), background)
        }
        card.setCardBackgroundColor(background)
    }
}
