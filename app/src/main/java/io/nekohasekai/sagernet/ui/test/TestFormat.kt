package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.text.format.Formatter
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.ProxyEntity
import java.text.DecimalFormat
import java.util.Locale

internal object TestFormat {

    private fun scaled(bps: Double): Pair<Double, String> = when {
        bps >= 1e9 -> bps / 1e9 to "Gbps"
        bps >= 1e6 -> bps / 1e6 to "Mbps"
        bps >= 1e3 -> bps / 1e3 to "Kbps"
        else -> bps.coerceAtLeast(0.0) to "bps"
    }

    /** Three significant digits: "8.53 Mbps", "85.3 Mbps", "853 Mbps". */
    fun rate(bps: Double): String {
        val (value, unit) = scaled(bps)
        val pattern = when {
            unit == "bps" || value >= 100 -> "%.0f %s"
            value >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(Locale.getDefault(), pattern, value, unit)
    }

    /** Axis labels: no trailing zeros ("50 Mbps", "2.5 Mbps"). */
    fun axisRate(bps: Double): String {
        val (value, unit) = scaled(bps)
        return DecimalFormat("0.##").format(value) + " " + unit
    }

    fun bytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    fun latency(context: Context, ms: Int): String = context.getString(R.string.test_panel_ms, ms)

    /** "0:38", "12:05", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        } else {
            String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60)
        }
    }

    /** "38 s", "1 min 20 s", "1 h 2 min". */
    fun duration(context: Context, ms: Long): String {
        val s = ((ms + 500) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> context.getString(R.string.test_panel_seconds, s.toInt())
            s < 3600 -> context.getString(R.string.test_panel_minutes_seconds, (s / 60).toInt(), (s % 60).toInt())
            else -> context.getString(R.string.test_panel_hours_minutes, (s / 3600).toInt(), (s / 60 % 60).toInt())
        }
    }

    fun isCountryCode(code: String): Boolean = code.length == 2 && code.all { it in 'A'..'Z' || it in 'a'..'z' }

    fun flag(code: String): String = if (isCountryCode(code)) ProxyEntity.countryFlag(code) else ""

    fun countryName(code: String): String {
        if (!isCountryCode(code)) return code
        return runCatching { Locale.Builder().setRegion(code.uppercase(Locale.ROOT)).build().displayCountry }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: code
    }

    fun kindLabel(context: Context, kind: Int): String = context.getString(
        when (kind) {
            TestSpec.KIND_IP -> R.string.test_panel_kind_ip
            TestSpec.KIND_SPEED -> R.string.test_panel_kind_speed
            TestSpec.KIND_SERVICE -> R.string.service_check
            else -> R.string.test_panel_kind_url
        }
    )

    fun stageLabel(context: Context, stage: String): String = when (stage) {
        SpeedTestSnapshot.STAGE_DISCOVERY -> context.getString(R.string.test_panel_stage_discovery)
        SpeedTestSnapshot.STAGE_LATENCY -> context.getString(R.string.test_panel_stage_latency)
        SpeedTestSnapshot.STAGE_DOWNLOAD -> context.getString(R.string.test_panel_stage_download)
        SpeedTestSnapshot.STAGE_UPLOAD -> context.getString(R.string.test_panel_stage_upload)
        SpeedTestSnapshot.STAGE_COMPLETE -> context.getString(R.string.test_panel_stage_complete)
        SpeedTestSnapshot.STAGE_CANCELLED -> context.getString(R.string.test_panel_stage_cancelled)
        SpeedTestSnapshot.STAGE_ERROR -> context.getString(R.string.test_panel_stage_error)
        else -> ""
    }

    fun profileName(context: Context, profileId: Long, name: String): String =
        name.ifEmpty { context.getString(R.string.test_panel_unnamed, profileId) }

    /** Desktop latency colour band of [ms] (ProxyEntity.latencyColor). */
    @ColorInt
    fun latencyColor(ms: Int): Int = ProxyEntity(latency = ms).latencyColor()

    /** A theme colour that may be a literal or a (state list) resource. */
    @ColorInt
    fun themeColor(context: Context, @AttrRes attr: Int, @ColorInt fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attr, value, true)) return fallback
        return try {
            if (value.resourceId != 0) ContextCompat.getColor(context, value.resourceId) else value.data
        } catch (e: Exception) {
            fallback
        }
    }
}
