package io.nekohasekai.sagernet.ui.profiles

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.ktx.getColorAttr
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * SubscriptionInfoCard (SubscriptionInfoCard.cpp) for the current group: its name and the provider's title, the quota
 * bar, the time left, the portal and support links and the announcement (two lines, the whole text in a dialog). It is
 * wanted for a subscription whose last fetch said something, unless the group turned it off (Android-only
 * SubscriptionOptions.showInfoCard); [ProfilesHeader] shows it, and hides it with the header in a compact height.
 */
internal class SubscriptionInfoCard(val view: View) {

    companion object {
        private val SIZE_UNITS = arrayOf("B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB", "ZiB", "YiB")
        private val WHITESPACE = Regex("\\s+")

        /** ReadableSize (Utils.cpp:223-243): 1024-based, two decimals. */
        fun readableSize(size: Long): String {
            var value = size.toDouble()
            var unit = 0
            while (value >= 1024.0 && unit < SIZE_UNITS.size - 1) {
                value /= 1024.0
                unit++
            }
            return String.format(Locale.ROOT, "%.2f %s", value, SIZE_UNITS[unit])
        }

        /** DisplayTime(seconds, ShortFormat). */
        fun displayTime(seconds: Long): String =
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(seconds * 1000))

        private fun nowSeconds() = System.currentTimeMillis() / 1000

        /** expiryText (SubscriptionInfoCard.cpp:319-331): the time left, "Expired", "" without an expiry. */
        fun expiryText(context: Context, expire: Long, now: Long = nowSeconds()): String {
            if (expire <= 0) return ""
            val left = expire - now
            return when {
                left < 0 -> context.getString(R.string.subinfo_expired)
                left < 3600 -> context.getString(R.string.subinfo_minutes_left, maxOf(1L, left / 60))
                left < 86400 -> context.getString(R.string.subinfo_hours_left, left / 3600)
                else -> context.getString(R.string.subinfo_days_left, left / 86400)
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
                val used = readableSize(info.used)
                val remain = if (info.total > 0) readableSize(info.remaining) else "∞"
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
    }

    private val context: Context = view.context
    private val title: TextView = view.findViewById(R.id.sub_card_title)
    private val expiry: View = view.findViewById(R.id.sub_card_expiry)
    private val expiryIcon: ImageView = view.findViewById(R.id.sub_card_expiry_icon)
    private val expiryLabel: TextView = view.findViewById(R.id.sub_card_expiry_text)
    private val portal: View = view.findViewById(R.id.sub_card_portal)
    private val support: View = view.findViewById(R.id.sub_card_support)
    private val quota: View = view.findViewById(R.id.sub_card_quota)
    private val quotaBar: LinearProgressIndicator = view.findViewById(R.id.sub_card_quota_bar)
    private val quotaLabel: TextView = view.findViewById(R.id.sub_card_quota_text)
    private val announce: View = view.findViewById(R.id.sub_card_announce)
    private val announceLabel: TextView = view.findViewById(R.id.sub_card_announce_text)

    private val secondary = context.getColorAttr(android.R.attr.textColorSecondary)
    private val danger = context.getColorAttr(R.attr.colorError)
    private val ok = ContextCompat.getColor(context, R.color.material_green_500)

    private var groupName = ""
    private var portalUri: Uri? = null
    private var supportUri: Uri? = null
    private var fullAnnounce = ""

    /** The last [bind] had something to show. */
    var wanted = false
        private set

    init {
        quotaBar.trackColor = ColorUtils.setAlphaComponent(context.getColorAttr(android.R.attr.textColorPrimary), 0x1F)
        portal.setOnClickListener { portalUri?.let(::open) }
        support.setOnClickListener { supportUri?.let(::open) }
        announce.setOnClickListener { showAnnouncement() }
    }

    /** [group]: the current tab's, null for none (the picker). Also redraws the time left. */
    fun bind(group: ProxyGroup?) {
        wanted = group != null && group.isSubscription && group.subOptions.showInfoCard && render(group, group.subInfo)
    }

    /** updateData (SubscriptionInfoCard.cpp:465-540); false when there is nothing to show. */
    private fun render(group: ProxyGroup, info: SubUserInfo): Boolean {
        if (!info.valid) return false
        val now = nowSeconds()
        groupName = group.displayName()
        val provider = info.title.trim().takeIf { it.isNotEmpty() && it != groupName }
        title.text = if (provider == null) groupName else SpannableStringBuilder(groupName).append(" · ")
            .append(provider, ForegroundColorSpan(secondary), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val tip = arrayListOf(groupName)
        if (provider != null) tip.add(context.getString(R.string.subinfo_provider, provider))
        if (group.subLastUpdate > 0) tip.add(context.getString(R.string.grp_last_update, displayTime(group.subLastUpdate)))
        TooltipCompat.setTooltipText(title, tip.joinToString("\n"))

        quota.isVisible = info.hasQuota
        if (info.hasQuota) {
            val percent = info.percentUsed.toInt()
            quotaBar.setProgressCompat(if (info.total > 0) percent else 100, false)
            quotaBar.setIndicatorColor(if (info.isExpired(now) || (info.total > 0 && percent >= 90)) danger else ok)
            val used = readableSize(info.used)
            quotaLabel.text = if (info.total > 0) {
                context.getString(R.string.subinfo_quota, used, readableSize(info.total), percent)
            } else {
                context.getString(R.string.subinfo_quota_unlimited, used)
            }
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

        val text = info.announce.trim()
        fullAnnounce = if (text.equals("base64:", ignoreCase = true)) "" else text
        announce.isVisible = fullAnnounce.isNotEmpty()
        announceLabel.text = fullAnnounce.replace(WHITESPACE, " ")

        return info.hasQuota || info.expire > 0 || portalUri != null || supportUri != null ||
            fullAnnounce.isNotEmpty() || provider != null
    }

    /** QDesktopServices::openUrl: the browser, or the app of a tg: link. */
    private fun open(uri: Uri) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, context.getString(R.string.subinfo_no_app, uri.toString()), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAnnouncement() {
        if (fullAnnounce.isEmpty()) return
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.subinfo_announcement_title, groupName))
            .setMessage(fullAnnounce)
            .setPositiveButton(android.R.string.ok, null)
            .show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }
}
