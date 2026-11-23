package io.nekohasekai.sagernet.ui.settings

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.SettingsRegistry.Listener
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Family
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr

/**
 * The editor-only checks of dialog_basic_settings.cpp, dialog_vpn_settings.cpp and dialog_edit_group_advanced.cpp,
 * shared by the settings screens and the group editor: "" when acceptable, else one sentence.
 */
object SettingChecks {

    @JvmField
    val HTTP = listOf("http", "https")

    const val MIN_AUTO_UPDATE_MINUTES = 30
    const val MAX_AUTO_UPDATE_MINUTES = 525600

    private const val MAX_HEADER_VALUE = 1000
    private val HWID_PARAM_KEYS = setOf("hwid", "os", "osversion", "model")

    /** sub_auto_update / route_auto_update while enabled. */
    @JvmField
    val autoUpdateMinutes: Rule =
        Validation.Rules.int(MIN_AUTO_UPDATE_MINUTES.toLong(), MAX_AUTO_UPDATE_MINUTES.toLong(), Empty.Required)

    /** GroupUpdater sends only values below 1000 characters without line breaks, and silently falls back otherwise. */
    @JvmStatic
    fun headerValue(text: String): String =
        if (text.length < MAX_HEADER_VALUE && '\n' !in text && '\r' !in text) "" else vtr(R.string.validation_header_value)

    /** The keys applyCustomHwidParams reads; it skips anything else without a word. */
    @JvmStatic
    fun hwidParams(text: String): String {
        for (item in Validation.items(text, Validation.Split.Comma)) {
            val eq = item.indexOf('=')
            val value = if (eq > 0) item.substring(eq + 1).trim() else ""
            if (eq > 0 && item.substring(0, eq).trim().lowercase() in HWID_PARAM_KEYS && value.isNotEmpty() && headerValue(value).isEmpty()) {
                continue
            }
            return vtr(R.string.validation_hwid_param, if (item.length > 40) item.take(39) + "…" else item)
        }
        return ""
    }

    /** The test runners read a bare integer; anything else, "3s" included, becomes 0. */
    @JvmStatic
    fun millis(text: String, example: Int, zeroIsDefault: Boolean): String {
        val ms = Validation.toInt(text)
        if (ms != null && ms >= (if (zeroIsDefault) 0L else 1L) && ms <= Int.MAX_VALUE) return ""
        return vtr(if (zeroIsDefault) R.string.validation_millis_or_default else R.string.validation_millis, example.toString())
    }

    /** A group's own update interval: empty or 0 keeps the default, anything else is at least 30 minutes. */
    @JvmStatic
    fun groupUpdateInterval(text: String): String {
        val t = text.trim()
        if (t.isEmpty() || Validation.toInt(t) == 0L) return ""
        Validation.int(t, 0, MAX_AUTO_UPDATE_MINUTES.toLong()).let { if (it.isNotEmpty()) return it }
        val minutes = Validation.toInt(t) ?: return ""
        return if (minutes < MIN_AUTO_UPDATE_MINUTES) vtr(R.string.validation_group_update_interval) else ""
    }

    /** A Tun private range: a CIDR that does not cover every address. */
    @JvmStatic
    fun privateRange(range: String): String {
        Validation.cidr(range).let { if (it.isNotEmpty()) return it }
        // Excluding a default route leaves the Tun with no routes at all.
        return if (range.trim().substringAfterLast('/') == "0") vtr(R.string.validation_private_range_all) else ""
    }

    /** Older builds saved bare addresses; the editor shows them as one-address prefixes (dialog_vpn_settings WithPrefixLength). */
    @JvmStatic
    fun withPrefixLength(saved: String): String {
        val range = saved.trim()
        if ('/' in range) return range
        if (Validation.ip(range, Family.V4).isEmpty()) return "$range/32"
        if (Validation.ip(range, Family.V6).isEmpty()) return "$range/128"
        return range
    }

    /** The name of a local listener whose port must differ; the inbound and the core screens each hold two of them. */
    @StringRes
    fun label(listener: Listener): Int = when (listener) {
        Listener.MIXED -> R.string.cag_mixed_inbound
        Listener.DNS_IN -> R.string.dns_in_port
        Listener.CLASH_API -> R.string.cag_clash_api
        Listener.BOX_API -> R.string.cag_api_dashboard
    }

    /** Why [text] as the port of [own] collides with another listener; only while [own] itself listens. */
    @JvmStatic
    fun portCollision(own: Listener, text: String): String {
        if (SettingsRegistry.listenerPort(own) == null) return ""
        val port = Validation.toInt(text)?.takeIf { it > 0 } ?: return ""
        val other = Listener.entries.firstOrNull { it != own && SettingsRegistry.listenerPort(it)?.toLong() == port }
            ?: return ""
        return vtr(R.string.validation_port_used, port.toString(), vtr(label(other)))
    }

    /**
     * The commit check of the listener switches (SettingsService::Update's PortConflict): "" when [pending] (key ->
     * stored text) leaves every listening port distinct, else why not.
     */
    @JvmStatic
    fun portConflict(pending: Map<String, String>): String {
        val conflict = SettingsRegistry.portConflict(pending) ?: return ""
        val port = conflict.port.toString()
        val holder = vtr(label(conflict.holder))
        return if (conflict.reenabled) {
            vtr(R.string.validation_port_reenabled, vtr(label(conflict.listener)), port, holder)
        } else {
            vtr(R.string.validation_port_used, port, holder)
        }
    }

    /** Validation::Rules::Port plus [portCollision]. */
    @JvmStatic
    fun listenPort(own: Listener): Rule = { text -> Validation.port(text).ifEmpty { portCollision(own, text) } }
}
