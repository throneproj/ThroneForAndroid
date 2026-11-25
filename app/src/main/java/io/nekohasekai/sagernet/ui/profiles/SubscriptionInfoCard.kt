package io.nekohasekai.sagernet.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.getColorAttr
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * SubscriptionInfoCard (SubscriptionInfoCard.cpp) for the current group: its name, the quota bar, the time left and
 * the portal and support links; the name and the info button open a sheet with everything the provider sent (its
 * title, the announcement) and the update schedule. It is wanted for a subscription whose last fetch said something,
 * unless the group turned it off (Android-only SubscriptionOptions.showInfoCard); [ProfilesHeader] shows it, and
 * hides it with the header in a compact height.
 */
internal class SubscriptionInfoCard(val view: View) {

    companion object {
        private val QUOTA_UNITS = arrayOf("B", "KB", "MB", "GB", "TB", "PB", "EB")

        /** UI-only state, kept out of the group and its backups: the hash of the last announcement opened per group. */
        private const val PREFS = "subscription_card"
        private const val KEY_ANNOUNCE_SEEN = "announce_seen_"

        private fun roundQuota(value: Double): String {
            val decimals = when {
                value < 10 -> 2
                value < 100 -> 1
                else -> 0
            }
            val text = String.format(Locale.ROOT, "%.${decimals}f", value)
            return if ('.' in text) text.trimEnd('0').trimEnd('.') else text
        }

        /** [quotaSize] as the number and the unit. */
        private fun quotaParts(bytes: Long): Pair<String, String> {
            if (bytes < 1024) return bytes.coerceAtLeast(0L).toString() to QUOTA_UNITS[0]
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < QUOTA_UNITS.size - 1) {
                value /= 1024.0
                unit++
            }
            var text = roundQuota(value)
            // 1023.996 MB rounds to 1024: one unit up it is 1 GB
            if (text.toDouble() >= 1024.0 && unit < QUOTA_UNITS.size - 1) {
                value /= 1024.0
                unit++
                text = roundQuota(value)
            }
            return text to QUOTA_UNITS[unit]
        }

        /** 1024-based, two decimals under 10, one under 100, none above, trailing zeros dropped: "5 GB", "40.1 KB". */
        fun quotaSize(bytes: Long): String = quotaParts(bytes).let { (value, unit) -> "$value $unit" }

        /** The card's quota: "3.22 / 5 GB · 64%", "40.1 KB / 50 GB · 0%", "3.22 GB / ∞" without a total. */
        fun quotaText(context: Context, info: SubUserInfo): String {
            if (info.total <= 0) return quotaSize(info.used) + " / ∞"
            val (used, usedUnit) = quotaParts(info.used)
            val (total, totalUnit) = quotaParts(info.total)
            val pair = if (usedUnit == totalUnit) "$used / $total $totalUnit" else "$used $usedUnit / $total $totalUnit"
            return context.getString(R.string.subinfo_quota, pair, info.percentUsed.toInt())
        }

        /** DisplayTime(seconds, ShortFormat). */
        fun displayTime(seconds: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

        private fun nowSeconds() = System.currentTimeMillis() / 1000

        /** expiryText (SubscriptionInfoCard.cpp:319-330): the time left, "Expired", "" without an expiry. */
        fun expiryText(context: Context, expire: Long, now: Long = nowSeconds()): String {
            if (expire <= 0) return ""
            val left = expire - now
            return when {
                left < 0 -> context.getString(R.string.subinfo_expired)
                left >= 86400 -> context.getString(R.string.subinfo_days_hours_left, left / 86400, left % 86400 / 3600)
                left >= 3600 -> context.getString(R.string.subinfo_hours_minutes_left, left / 3600, left % 3600 / 60)
                else -> context.getString(R.string.subinfo_minutes_left, maxOf(1L, left / 60))
            }
        }

        /**
         * ParseSubInfo (GroupItem.cpp:15-30) for the groups screen: the quota with the expiry date, then the time left.
         * Unlike the desktop, a missing expiry is left out instead of printing "None".
         */
        fun summary(context: Context, info: SubUserInfo): String {
            if (!info.valid) return ""
            val parts = ArrayList<String>()
            if (info.hasQuota) {
                val used = quotaSize(info.used)
                val remain = if (info.total > 0) quotaSize(info.remaining) else "∞"
                parts.add(
                    if (info.expire > 0) context.getString(R.string.grp_sub_info, used, remain, displayTime(info.expire))
                    else context.getString(R.string.grp_sub_info_no_expire, used, remain)
                )
            }
            if (info.expire > 0) parts.add(expiryText(context, info.expire))
            return parts.joinToString(" · ")
        }

        /** linkUrl (SubscriptionInfoCard.cpp:125-130): http(s), and tg: for the support link. */
        private fun linkUri(raw: String, allowTg: Boolean): Uri? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val uri = Uri.parse(text)
            return when (uri.scheme?.lowercase()) {
                "http", "https" -> uri.takeIf { !it.host.isNullOrEmpty() }
                "tg" -> uri.takeIf { allowTg }
                else -> null
            }
        }

        /** The announcement worth showing: trimmed, without the bare "base64:" some panels send. */
        private fun announcement(info: SubUserInfo): String {
            val text = info.announce.trim()
            return if (text.equals("base64:", ignoreCase = true)) "" else text
        }
    }

    private val context: Context = view.context
    private val title: TextView = view.findViewById(R.id.sub_card_title)
    private val expiry: View = view.findViewById(R.id.sub_card_expiry)
    private val expiryIcon: ImageView = view.findViewById(R.id.sub_card_expiry_icon)
    private val expiryLabel: TextView = view.findViewById(R.id.sub_card_expiry_text)
    private val details: View = view.findViewById(R.id.sub_card_details)
    private val detailsIcon: ImageView = view.findViewById(R.id.sub_card_details_icon)
    private val detailsDot: View = view.findViewById(R.id.sub_card_details_dot)
    private val portal: View = view.findViewById(R.id.sub_card_portal)
    private val support: View = view.findViewById(R.id.sub_card_support)
    private val quota: View = view.findViewById(R.id.sub_card_quota)
    private val quotaBar: LinearProgressIndicator = view.findViewById(R.id.sub_card_quota_bar)
    private val quotaLabel: TextView = view.findViewById(R.id.sub_card_quota_text)

    private val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
    private val accent = context.getColorAttr(R.attr.accentOrTextSecondary)
    private val danger = context.getColorAttr(R.attr.colorError)
    private val ok = ContextCompat.getColor(context, R.color.material_green_500)

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var group: ProxyGroup? = null
    private var portalUri: Uri? = null
    private var supportUri: Uri? = null
    private var sheet: BottomSheetDialog? = null

    /** The last [bind] had something to show. */
    var wanted = false
        private set

    init {
        quotaBar.trackColor = ColorUtils.setAlphaComponent(context.getColorAttr(android.R.attr.textColorPrimary), 0x1F)
        portal.setOnClickListener { portalUri?.let(::open) }
        support.setOnClickListener { supportUri?.let(::open) }
        title.setOnClickListener { showDetails() }
        details.setOnClickListener { showDetails() }
    }

    /** [group]: the current tab's, null for none (the picker). Also redraws the time left. */
    fun bind(group: ProxyGroup?) {
        wanted = group != null && group.isSubscription && group.subOptions.showInfoCard && render(group, group.subInfo)
        this.group = group.takeIf { wanted }
    }

    /** Closes the details sheet with the view. */
    fun dismissDetails() {
        sheet?.dismiss()
        sheet = null
    }

    /** updateData (SubscriptionInfoCard.cpp:465-540); false when there is nothing to show. */
    private fun render(group: ProxyGroup, info: SubUserInfo): Boolean {
        if (!info.valid) return false
        val now = nowSeconds()
        val name = group.displayName()
        title.text = name

        quota.isVisible = info.hasQuota
        if (info.hasQuota) {
            val percent = info.percentUsed.toInt()
            quotaBar.setProgressCompat(if (info.total > 0) percent else 100, false)
            quotaBar.setIndicatorColor(if (info.isExpired(now) || (info.total > 0 && percent >= 90)) danger else ok)
            quotaLabel.text = quotaText(context, info)
        }

        expiry.isVisible = info.expire > 0
        if (info.expire > 0) {
            val urgent = (info.expire - now) / 86400 <= 3
            val color = if (urgent) danger else secondary
            expiryIcon.setImageResource(if (urgent) R.drawable.ic_baseline_warning_24 else R.drawable.ic_baseline_hourglass_empty_24)
            ImageViewCompat.setImageTintList(expiryIcon, ColorStateList.valueOf(color))
            expiryLabel.setTextColor(color)
            expiryLabel.text = expiryText(context, info.expire, now)
            val expires = context.getString(R.string.subinfo_expires, displayTime(info.expire))
            expiry.contentDescription = expires
            TooltipCompat.setTooltipText(expiry, expires)
        }

        portalUri = linkUri(info.webUrl, allowTg = false)
        supportUri = linkUri(info.supportUrl, allowTg = true)
        portal.isVisible = portalUri != null
        support.isVisible = supportUri != null
        TooltipCompat.setTooltipText(portal, context.getString(R.string.subinfo_portal_tip, info.webUrl.trim()))
        TooltipCompat.setTooltipText(support, context.getString(R.string.subinfo_support_tip, info.supportUrl.trim()))

        val announce = announcement(info)
        showUnseen(announce.isNotEmpty() && !seen(group.id, announce))

        val provider = info.title.trim().let { it.isNotEmpty() && it != name }
        return info.hasQuota || info.expire > 0 || portalUri != null || supportUri != null ||
            announce.isNotEmpty() || provider
    }

    private fun seen(groupId: Long, announce: String): Boolean {
        val key = KEY_ANNOUNCE_SEEN + groupId
        return prefs.contains(key) && prefs.getInt(key, 0) == announce.hashCode()
    }

    /** The info button in the accent colour with a dot while an announcement waits to be read. */
    private fun showUnseen(unseen: Boolean) {
        detailsDot.isVisible = unseen
        ImageViewCompat.setImageTintList(detailsIcon, ColorStateList.valueOf(if (unseen) accent else secondary))
        val description = context.getString(if (unseen) R.string.subinfo_details_new else R.string.subinfo_details)
        details.contentDescription = description
        TooltipCompat.setTooltipText(details, description)
    }

    /** QDesktopServices::openUrl: the browser, or the app of a tg: link. */
    private fun open(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.subinfo_no_app, uri.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDetails() {
        val group = group ?: return
        val info = group.subInfo
        val now = nowSeconds()
        val name = group.displayName()
        val announce = announcement(info)
        if (announce.isNotEmpty()) {
            prefs.edit().putInt(KEY_ANNOUNCE_SEEN + group.id, announce.hashCode()).apply()
            showUnseen(false)
        }

        val inflater = LayoutInflater.from(context)
        val content = inflater.inflate(R.layout.layout_subscription_details, null)
        content.findViewById<TextView>(R.id.sub_details_title).text = name
        val rows: ViewGroup = content.findViewById(R.id.sub_details_rows)
        fun row(label: Int, value: CharSequence): TextView {
            val item = inflater.inflate(R.layout.item_subscription_detail, rows, false)
            item.findViewById<TextView>(R.id.detail_label).setText(label)
            rows.addView(item)
            return item.findViewById<TextView>(R.id.detail_value).also { it.text = value }
        }

        info.title.trim().takeIf { it.isNotEmpty() && it != name }?.let { row(R.string.subinfo_label_provider, it) }
        if (info.hasQuota) {
            val used = quotaSize(info.used)
            row(
                R.string.subinfo_label_traffic,
                if (info.total > 0) {
                    context.getString(R.string.subinfo_traffic_used, used, quotaSize(info.total), info.percentUsed.toInt())
                } else {
                    context.getString(R.string.subinfo_traffic_unlimited, used)
                },
            )
        }
        if (info.expire > 0) {
            val date = displayTime(info.expire)
            val value = if (info.isExpired(now)) {
                row(R.string.subinfo_expired, date)
            } else {
                row(R.string.subinfo_label_expires, date + " · " + expiryText(context, info.expire, now))
            }
            if ((info.expire - now) / 86400 <= 3) value.setTextColor(danger)
        }
        if (group.subLastUpdate > 0) row(R.string.subinfo_label_last_update, displayTime(group.subLastUpdate))
        SubscriptionScheduler.resolve(group)?.let { auto ->
            val source = when (auto.source) {
                SubscriptionScheduler.IntervalSource.SERVER -> R.string.subinfo_auto_update_server
                SubscriptionScheduler.IntervalSource.GROUP -> R.string.subinfo_auto_update_group
                SubscriptionScheduler.IntervalSource.GLOBAL -> R.string.subinfo_auto_update_global
            }
            row(
                R.string.subinfo_label_auto_update,
                context.getString(R.string.subinfo_auto_update_every, intervalText(auto.seconds), context.getString(source)),
            )
        }
        if (announce.isNotEmpty()) {
            row(R.string.subinfo_label_announcement, announce).apply {
                // Each paragraph runs and aligns in its own script's direction, whatever the app's language.
                textDirection = View.TEXT_DIRECTION_ANY_RTL
                textAlignment = View.TEXT_ALIGNMENT_TEXT_START
                setTextIsSelectable(true)
            }
        }

        val website = portalUri
        val help = supportUri
        content.findViewById<View>(R.id.sub_details_website).apply {
            isVisible = website != null
            setOnClickListener { website?.let(::open) }
        }
        content.findViewById<View>(R.id.sub_details_support).apply {
            isVisible = help != null
            setOnClickListener { help?.let(::open) }
        }
        content.findViewById<View>(R.id.sub_details_button_gap).isVisible = website != null && help != null
        content.findViewById<View>(R.id.sub_details_buttons).isVisible = website != null || help != null

        dismissDetails()
        val dialog = BottomSheetDialog(context)
        dialog.setContentView(content)
        dialog.setOnDismissListener { if (sheet === dialog) sheet = null }
        sheet = dialog
        dialog.show()
    }

    /** "6 hours", "2 days", "1 hour, 30 minutes" in the app's language; whole days only from two days up. */
    private fun intervalText(seconds: Long): String {
        val minutes = seconds / 60
        val measures = ArrayList<Measure>()
        if (minutes >= 2 * 1440 && minutes % 1440 == 0L) {
            measures.add(Measure(minutes / 1440, MeasureUnit.DAY))
        } else {
            if (minutes >= 60) measures.add(Measure(minutes / 60, MeasureUnit.HOUR))
            if (minutes % 60 > 0 || measures.isEmpty()) measures.add(Measure(minutes % 60, MeasureUnit.MINUTE))
        }
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.WIDE).formatMeasures(*measures.toTypedArray())
    }
}
