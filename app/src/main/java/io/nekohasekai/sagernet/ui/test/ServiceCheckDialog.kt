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
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject

/**
 * A profile's stored service check (ServiceCheckHtml::details): one section per category with each service's status,
 * region and note, then the exit IP's kind and every lookup's answer. Names, notes and answers come from downloaded
 * definitions and third parties; they are only ever shown as plain text.
 */
internal object ServiceCheckDialog {

    /** [document] is the profile's `service_checks` column; nothing is shown for an empty or unreadable one. */
    fun show(context: Context, profileName: String, document: String) {
        val root = JsonInput.parseObjectOrNull(document)?.takeIf { it.isNotEmpty() } ?: return
        val content = Builder(context).build(profileName, root)
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.service_check_results)
            .setView(NestedScrollView(context).apply { addView(content) })
            .setPositiveButton(android.R.string.ok, null)
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

        fun build(profileName: String, root: JsonObject): LinearLayout {
            val checked = checkedText(root.integer("at"))
            add(text(if (profileName.isEmpty()) checked else "$profileName · $checked", BODY).apply { setTextColor(muted) })
            val error = root.string("error")
            if (error.isNotEmpty()) {
                add(text(error, BODY).apply { setTextColor(danger) }, top = 8)
                return layout
            }
            val checks = root.array("checks").mapNotNull { it as? JsonObject }
            for (category in root.array("cats").mapNotNull { it as? JsonObject }) {
                val id = category.string("id")
                add(text(category.string("name"), HEADING), top = 12)
                val table = TableLayout(context).apply {
                    setColumnShrinkable(0, true)
                    setColumnShrinkable(2, true)
                }
                for (check in checks) {
                    if (check.string("cat") != id) continue
                    table.addView(checkRow(check))
                }
                add(table, top = 2)
            }
            val ip = root.array("ip").mapNotNull { it as? JsonObject }
            if (ip.isNotEmpty()) {
                add(text(context.getString(R.string.service_check_ip), HEADING), top = 12)
                val kind = ipKind(ip)
                if (kind.isNotEmpty()) add(text(kind, BODY), top = 2)
                for (entry in ip) add(text(ipLine(entry), BODY), top = 2)
            }
            return layout
        }

        private fun checkRow(check: JsonObject): TableRow {
            val status = check.string("status")
            val color = statusColor(status)
            val name = text(check.string("name"), BODY).apply {
                setTextColor(color)
                setTypeface(typeface, Typeface.BOLD)
                updatePaddingRelative(end = dp2px(12))
            }
            val state = text(statusText(status), BODY).apply {
                setTextColor(color)
                updatePaddingRelative(end = dp2px(12))
            }
            val extra = listOf(check.string("region"), check.string("note")).filter { it.isNotEmpty() }
            return TableRow(context).apply {
                updatePadding(top = dp2px(2), bottom = dp2px(2))
                addView(name)
                addView(state)
                addView(text(extra.joinToString(" — "), BODY))
            }
        }

        /** Unknown and failed checks are muted. */
        @ColorInt
        private fun statusColor(status: String): Int = when (status) {
            STATUS_AVAILABLE -> success
            STATUS_PARTIAL -> warning
            STATUS_RESTRICTED, STATUS_BLOCKED -> danger
            else -> muted
        }

        private fun statusText(status: String): String = when (status) {
            STATUS_AVAILABLE -> context.getString(R.string.service_status_available)
            STATUS_PARTIAL -> context.getString(R.string.service_status_partial)
            STATUS_RESTRICTED -> context.getString(R.string.service_status_restricted)
            STATUS_BLOCKED -> context.getString(R.string.service_status_blocked)
            STATUS_UNKNOWN -> context.getString(R.string.service_status_unknown)
            STATUS_FAILED -> context.getString(R.string.service_status_failed)
            else -> status
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

    private const val STATUS_AVAILABLE = "available"
    private const val STATUS_PARTIAL = "partial"
    private const val STATUS_RESTRICTED = "restricted"
    private const val STATUS_BLOCKED = "blocked"
    private const val STATUS_UNKNOWN = "unknown"
    private const val STATUS_FAILED = "failed"

    private val RISK_FLAGS = listOf("vpn", "proxy", "tor", "abuser", "anonymous")

    private val BODY = com.google.android.material.R.style.TextAppearance_MaterialComponents_Body2
    private val HEADING = com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2
}
