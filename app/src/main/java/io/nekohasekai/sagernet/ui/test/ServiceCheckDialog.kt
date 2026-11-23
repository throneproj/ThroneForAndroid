package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.outbound.json.JsonObject

/**
 * A profile's stored service check: one section per category with each shown service's logo, status, region and
 * note, then the exit IP's kind and every lookup's answer (the desktop's strip tooltips and summary line). Names,
 * notes and answers come from downloaded definitions and third parties; they are only ever shown as plain text.
 */
internal object ServiceCheckDialog {

    private const val ICON_DP = 20

    /** [document] is the profile's `service_checks` column; nothing is shown for an empty or unreadable one. */
    fun show(context: Context, profileName: String, document: String) {
        val result = ServiceResults.parse(document) ?: return
        val content = Builder(context).build(profileName, result)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.service_check_results)
            .setView(NestedScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.service_check_services) { _, _ -> ServiceCheckSelector.show(context) }
            .show()
    }

    private class Builder(private val context: Context) {

        @ColorInt
        private val success = TestFormat.latencyColor(1)

        @ColorInt
        private val warning = TestFormat.latencyColor(101)

        @ColorInt
        private val danger = TestFormat.latencyColor(301)

        @ColorInt
        private val muted = TestFormat.themeColor(context, android.R.attr.textColorSecondary, Color.GRAY)

        private val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(8), dp2px(24), dp2px(8))
        }

        fun build(profileName: String, result: ServiceResults.Document): LinearLayout {
            val checked = checkedText(result.checkedAt)
            add(text(if (profileName.isEmpty()) checked else "$profileName · $checked", BODY).apply { setTextColor(muted) })
            if (result.error.isNotEmpty()) {
                add(text(result.error, BODY).apply { setTextColor(danger) }, top = 8)
                return layout
            }
            val disabled = ServiceResults.disabled
            val checks = result.checks.filter { it.id !in disabled }
            for (category in result.categories) {
                val members = checks.filter { it.category == category.id }
                if (members.isEmpty()) continue
                add(text(ServiceResults.categoryName(context, category.id, category.name), HEADING), top = 12)
                for (check in members) add(checkRow(check), top = 6)
            }
            if (result.ip.isNotEmpty()) {
                add(text(context.getString(R.string.service_check_ip), HEADING), top = 12)
                val kind = ipKind(result.ip)
                if (kind.isNotEmpty()) add(text(kind, BODY), top = 2)
                for (entry in result.ip) add(text(ipLine(entry), BODY), top = 2)
            }
            return layout
        }

        /** The logo, then the name and status with the region and note under them; every text wraps (#150). */
        private fun checkRow(check: ServiceResults.Check): View {
            val size = dp2px(ICON_DP)
            val icon = ImageView(context).apply {
                setImageBitmap(ServiceIcons.bitmap(context, check.id, check.name, ServiceIcons.stateOf(check.status), size))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val headline = SpannableStringBuilder()
            headline.append(check.name, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            headline.append("  ")
            headline.append(
                ServiceResults.statusText(context, check.status),
                ForegroundColorSpan(statusColor(check.status)),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(headline, BODY))
                val extra = listOf(check.region, check.note).filter { it.isNotEmpty() }
                if (extra.isNotEmpty()) addView(text(extra.joinToString(" — "), BODY).apply { setTextColor(muted) })
            }
            return LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(icon, LinearLayout.LayoutParams(size, size).apply { marginEnd = dp2px(12) })
                addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

        /** Unknown and failed checks are muted. */
        @ColorInt
        private fun statusColor(status: String): Int = when (status) {
            ServiceResults.STATUS_AVAILABLE -> success
            ServiceResults.STATUS_PARTIAL -> warning
            ServiceResults.STATUS_RESTRICTED, ServiceResults.STATUS_BLOCKED -> danger
            else -> muted
        }

        /** Every provider's answer merged: the network kind, then the anonymizing or abuse flags any of them saw. */
        private fun ipKind(entries: List<JsonObject>): CharSequence {
            val traits = HashSet<String>()
            for (entry in entries) {
                traits.add(entry.string("type").lowercase())
                for (flag in entry.array("flags").strings()) traits.add(flag.lowercase())
            }
            val kind = when {
                "hosting" in traits || "datacenter" in traits -> context.getString(R.string.service_ip_datacenter)
                "mobile" in traits -> context.getString(R.string.service_ip_mobile)
                "business" in traits -> context.getString(R.string.service_ip_business)
                "isp" in traits -> context.getString(R.string.service_ip_residential)
                else -> ""
            }
            val text = SpannableStringBuilder(kind)
            val flagged = RISK_FLAGS.filter { it in traits }
            if (flagged.isNotEmpty()) {
                if (text.isNotEmpty()) text.append(", ")
                val start = text.length
                text.append(context.getString(R.string.service_ip_listed_as, flagged.joinToString(", ")))
                text.setSpan(ForegroundColorSpan(danger), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(StyleSpan(Typeface.BOLD), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            return text
        }

        private fun ipLine(entry: JsonObject): String {
            val provider = entry.string("provider")
            val error = entry.string("error")
            if (error.isNotEmpty()) return "$provider: $error"
            fun joined(parts: List<String>, separator: String) = parts.filter { it.isNotEmpty() }.joinToString(separator)
            val risk = if (entry.isNumber("risk")) entry.int("risk") else -1
            val details = joined(
                listOf(
                    joined(listOf(entry.string("ip"), entry.string("cc")), " "),
                    joined(listOf(entry.string("asn"), entry.string("org")), " "),
                    entry.string("type"),
                    if (risk >= 0) context.getString(R.string.service_ip_risk, risk) else "",
                    joined(entry.array("flags").strings(), ", "),
                ),
                " · ",
            )
            return if (details.isEmpty()) provider else "$provider: $details"
        }

        /** RuntimeStatsWidget::refreshServices' "Checked … ago", in the locale's own relative words. */
        private fun checkedText(at: Long): String {
            val now = System.currentTimeMillis()
            if (now / 1000 - at < 60) return context.getString(R.string.service_check_checked_now)
            val ago = DateUtils.getRelativeTimeSpanString(
                at * 1000, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE,
            )
            return context.getString(R.string.service_check_checked_at, ago)
        }

        private fun text(value: CharSequence, @StyleRes appearance: Int) = TextView(context).apply {
            TextViewCompat.setTextAppearance(this, appearance)
            text = value
        }

        private fun add(view: View, top: Int = 0) {
            layout.addView(view, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp2px(top) })
        }
    }

    private val RISK_FLAGS = listOf("vpn", "proxy", "tor", "abuser", "anonymous")

    private val BODY = com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
    private val HEADING = com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2
}
