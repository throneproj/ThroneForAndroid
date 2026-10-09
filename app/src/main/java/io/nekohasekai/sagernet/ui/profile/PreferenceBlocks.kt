package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import android.text.InputType
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import androidx.preference.TwoStatePreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.common.Ech
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.ui.SimpleMenuPreference

// Small helpers shared by the profile editors: every function tolerates a screen that omits the preference.

fun PreferenceFragmentCompat.setVisible(visible: Boolean, vararg keys: String) {
    for (key in keys) findPreference<Preference>(key)?.isVisible = visible
}

fun PreferenceFragmentCompat.passwordSummary(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.summaryProvider =
        ProfileSettingsActivity.PasswordSummaryProvider
}

fun PreferenceFragmentCompat.portInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
}

fun PreferenceFragmentCompat.numberInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
}

/** Multi-line editing for list fields (one item per line) and PEM blocks. */
fun PreferenceFragmentCompat.multilineInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener { editText ->
        editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        editText.isSingleLine = false
        editText.minLines = 3
        editText.setSelection(editText.text.length)
    }
}

/**
 * The ECH rows under [prefix] (edit_advanced.cpp): `queryTarget` is an unbound row showing query_server_name and the
 * resolver as one `domain+resolver` text (ECH::QueryTarget) and writing both back through ECH::SetQueryTarget, and a
 * bare base64 config is PEM-wrapped as it is entered (ECH::NormalizeConfig).
 */
fun PreferenceFragmentCompat.echInputs(prefix: String) {
    val store = DataStore.profileCacheStore
    findPreference<EditTextPreference>("$prefix.queryTarget")?.let { row ->
        val ech = Ech()
        ech.serverName = store.getString("$prefix.serverName").orEmpty()
        ech.resolver = store.getString("$prefix.resolver").orEmpty()
        row.text = ech.queryTarget()
        row.setOnPreferenceChangeListener { _, newValue ->
            ech.setQueryTarget(newValue as String)
            store.putString("$prefix.serverName", ech.serverName)
            store.putString("$prefix.resolver", ech.resolver)
            true
        }
    }
    findPreference<EditTextPreference>("$prefix.config")?.setOnPreferenceChangeListener { row, newValue ->
        val lines = (newValue as String).split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        (row as EditTextPreference).text = Ech.normalizeConfig(lines).joinToString("\n")
        false
    }
}

/** Applies [apply] to the switch's current value and again on every change. */
fun PreferenceFragmentCompat.onSwitch(key: String, apply: (Boolean) -> Unit) {
    val pref = findPreference<TwoStatePreference>(key) ?: return
    apply(pref.isChecked)
    pref.setOnPreferenceChangeListener { _, newValue ->
        apply(newValue as Boolean)
        true
    }
}

/** Applies [apply] to the menu's current value and again on every change. */
fun PreferenceFragmentCompat.onMenu(key: String, apply: (String) -> Unit) {
    val pref = findPreference<SimpleMenuPreference>(key) ?: return
    apply(pref.value ?: "")
    pref.setOnPreferenceChangeListener { _, newValue ->
        apply(newValue as String)
        true
    }
}

private fun PreferenceCategory.setChildrenVisible(visible: Boolean, except: Set<String> = emptySet()) {
    for (i in 0 until preferenceCount) {
        val child = getPreference(i)
        if (child.key !in except) child.isVisible = visible
    }
}

/**
 * Keeps a value the menu does not list (an imported fingerprint, say): it is appended as its own entry, because the
 * dropdown's spinner selects entry 0 on its first layout and would overwrite an unlisted value with it.
 */
fun ListPreference.keepUnlistedValue() {
    val current = value
    if (current.isNullOrEmpty() || entryValues.any { it.toString() == current }) return
    entries = entries + current
    entryValues = entryValues + current
}

/** A summary provider that re-reads its inputs: setting it again re-runs it. */
fun Preference.refreshSummary() {
    summaryProvider = summaryProvider
}

fun PreferenceFragmentCompat.fixedSummary(key: String, @StringRes text: Int) {
    findPreference<Preference>(key)?.summaryProvider = Preference.SummaryProvider<Preference> {
        it.context.getString(text)
    }
}

// Keep-default hints: an override left unset shows what the preset (Settings › Presets) resolves it to.

private fun defaultSummary(context: Context, preset: String, coreDefault: String? = null): String = when {
    preset.isNotEmpty() -> context.getString(R.string.preset_default, preset)
    coreDefault != null -> context.getString(R.string.setting_default_value, coreDefault)
    else -> context.getString(R.string.setting_default)
}

/** A Keep default / On / Off menu: "Keep default (On)" when the preset turns it on. */
fun PreferenceFragmentCompat.presetTriSummary(key: String, defaultOn: Boolean) {
    findPreference<ListPreference>(key)?.summaryProvider = Preference.SummaryProvider<ListPreference> {
        val on = it.context.getString(R.string.tri_state_on)
        val off = it.context.getString(R.string.tri_state_off)
        when (it.value) {
            "1" -> on
            "2" -> off
            else -> it.context.getString(R.string.preset_keep_default, if (defaultOn) on else off)
        }
    }
}

/** A text override: empty inherits [preset], and the core's [coreDefault] when the preset is empty too. */
fun PreferenceFragmentCompat.presetTextSummary(key: String, preset: String, coreDefault: String? = null) {
    findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
        it.text.orEmpty().ifEmpty { defaultSummary(it.context, preset, coreDefault) }
    }
}

/** An integer override: 0 inherits [preset] (0 there too = the core's default) while [inherits] holds. */
fun PreferenceFragmentCompat.presetIntSummary(key: String, preset: Int, inherits: () -> Boolean = { true }) {
    findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
        val value = it.text?.trim()?.toIntOrNull() ?: 0
        when {
            value > 0 -> value.toString()
            !inherits() -> "0"
            preset > 0 -> it.context.getString(R.string.preset_zero_default, preset.toString())
            else -> it.context.getString(R.string.setting_default)
        }
    }
}

/** A menu whose empty value ("Default") inherits [preset]; unlisted values stay selectable. */
fun PreferenceFragmentCompat.presetMenuSummary(key: String, preset: String) {
    val menu = findPreference<ListPreference>(key) ?: return
    menu.keepUnlistedValue()
    menu.summaryProvider = Preference.SummaryProvider<ListPreference> {
        val value = it.value.orEmpty()
        if (value.isEmpty()) defaultSummary(it.context, preset) else it.entry ?: value
    }
}

/** The sing-box TLS block (`tls.*`), shared by every type with `hasTls()`. */
object TlsBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "tls") {
        pbm.bool("$prefix.enabled")
        pbm.bool("$prefix.disable_sni")
        pbm.text("$prefix.server_name")
        pbm.bool("$prefix.insecure")
        pbm.text("$prefix.alpn")
        pbm.text("$prefix.min_version")
        pbm.text("$prefix.max_version")
        pbm.text("$prefix.certificate")
        pbm.text("$prefix.certificate_path")
        pbm.text("$prefix.certificate_sha256")
        pbm.text("$prefix.certificate_public_key_sha256")
        pbm.tri("$prefix.fragment", "$prefix.fragment_unspecified")
        pbm.text("$prefix.fragment_fallback_delay")
        pbm.bool("$prefix.record_fragment")
        // never emitted (D8) and not on screen, bound so imported values round-trip
        pbm.tri("$prefix.spoof_enabled", "$prefix.spoof_unspecified")
        pbm.text("$prefix.spoof")
        pbm.text("$prefix.spoof_method")
        pbm.tri("$prefix.tls_tricks", "$prefix.tls_tricks_unspecified")
        // cache value kept equal to fingerPrint.isNotEmpty() by setup, see utlsMenu
        pbm.bool("$prefix.utls.enabled")
        pbm.text("$prefix.utls.fingerPrint")
        pbm.bool("$prefix.reality.enabled")
        pbm.text("$prefix.reality.public_key")
        pbm.text("$prefix.reality.short_id")
        pbm.bool("$prefix.ech.enabled")
        pbm.text("$prefix.ech.config")
        pbm.text("$prefix.ech.config_path")
        // no rows of their own: both are edited through the ech.queryTarget row, see echInputs
        pbm.text("$prefix.ech.serverName")
        pbm.text("$prefix.ech.resolver")
    }

    /**
     * Visibility wiring: with [mustTls] the enable switch is hidden and the block is always shown, otherwise the
     * block follows the switch; the Reality / ECH switches gate their own fields. Unset overrides show what
     * [presets] resolves them to (dialog_edit_profile.cpp:756-778 for the enabled states).
     */
    fun setup(
        pf: PreferenceFragmentCompat,
        mustTls: Boolean,
        prefix: String = "tls",
        presets: BuildContext = SettingsMapper.buildContext(),
    ) = with(pf) {
        val enabledKey = "$prefix.enabled"
        val delayKey = "$prefix.fragment_fallback_delay"
        val security = findPreference<PreferenceCategory>("tlsCategory")
        val camouflage = findPreference<PreferenceCategory>("tlsCamouflageCategory")
        val ech = findPreference<PreferenceCategory>("tlsEchCategory")

        var enabled = mustTls || findPreference<TwoStatePreference>(enabledKey)?.isChecked ?: true
        var fragment = findPreference<ListPreference>("$prefix.fragment")?.value
        fun applyVisibility() {
            security?.setChildrenVisible(enabled, setOf(enabledKey))
            camouflage?.isVisible = enabled
            ech?.isVisible = enabled
            setVisible(enabled && fragment != "2", delayKey)
        }

        multilineInput(
            "$prefix.alpn", "$prefix.certificate", "$prefix.certificate_sha256", "$prefix.certificate_public_key_sha256",
            "$prefix.ech.config",
        )
        echInputs("$prefix.ech")
        if (mustTls) {
            findPreference<Preference>(enabledKey)?.isVisible = false
        } else {
            onSwitch(enabledKey) {
                enabled = it
                applyVisibility()
            }
        }
        onMenu("$prefix.fragment") {
            fragment = it
            applyVisibility()
        }
        applyVisibility()
        onSwitch("$prefix.reality.enabled") { setVisible(it, "$prefix.reality.public_key", "$prefix.reality.short_id") }
        onSwitch("$prefix.ech.enabled") { setVisible(it, "$prefix.ech.config", "$prefix.ech.config_path", "$prefix.ech.queryTarget") }

        presetTriSummary("$prefix.fragment", presets.fragmentDefaultOn)
        presetTriSummary("$prefix.tls_tricks", presets.tlsTricksDefaultOn)
        // the custom implementation fragments at the dialer and has no fallback delay (TLS.cpp:540-543)
        if (presets.fragmentImplementation == "custom") {
            findPreference<Preference>(delayKey)?.isEnabled = false
            fixedSummary(delayKey, R.string.preset_fallback_delay_unused)
        }
        if (presets.skipCert) findPreference<Preference>("$prefix.insecure")?.summary = getString(R.string.preset_insecure_forced)
        utlsMenu("$prefix.utls", presets.utlsFingerprint)
    }

    /**
     * The one uTLS control: a fingerprint menu whose "Default" inherits the preset. `utls.enabled` has no row: its
     * cache value is derived from the fingerprint here and on every change (dialog_edit_profile.cpp:832-833).
     */
    private fun PreferenceFragmentCompat.utlsMenu(prefix: String, preset: String) {
        val menu = findPreference<ListPreference>("$prefix.fingerPrint") ?: return
        fun derive(fingerprint: String) = DataStore.profileCacheStore.putBoolean("$prefix.enabled", fingerprint.isNotEmpty())
        presetMenuSummary(menu.key, preset)
        derive(menu.value.orEmpty())
        menu.setOnPreferenceChangeListener { _, newValue ->
            derive(newValue as String)
            true
        }
    }
}

/** The sing-box multiplex block (`multiplex.*`), shared by shadowsocks, vmess, vless and trojan. */
object MuxBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "multiplex") {
        pbm.tri("$prefix.enabled", "$prefix.unspecified")
        pbm.text("$prefix.protocol")
        pbm.int("$prefix.max_connections")
        pbm.int("$prefix.min_streams")
        pbm.int("$prefix.max_streams")
        pbm.bool("$prefix.padding")
        pbm.bool("$prefix.brutal.enabled")
        pbm.int("$prefix.brutal.up_mbps")
        pbm.int("$prefix.brutal.down_mbps")
    }

    fun setup(
        pf: PreferenceFragmentCompat,
        prefix: String = "multiplex",
        presets: BuildContext = SettingsMapper.buildContext(),
    ) = with(pf) {
        numberInput("$prefix.max_connections", "$prefix.min_streams", "$prefix.max_streams", "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps")
        val category = findPreference<PreferenceCategory>("muxCategory")
        onMenu("$prefix.enabled") { state ->
            category?.setChildrenVisible(state == "1", setOf("$prefix.enabled"))
            if (state == "1") {
                val brutal = findPreference<SwitchPreference>("$prefix.brutal.enabled")?.isChecked ?: false
                setVisible(brutal, "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps")
            }
        }
        onSwitch("$prefix.brutal.enabled") { setVisible(it, "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps") }

        // multiplex.cpp:128-137
        presetTriSummary("$prefix.enabled", presets.muxDefaultOn)
        presetMenuSummary("$prefix.protocol", presets.muxProtocol)
        fun unset(key: String) = (findPreference<EditTextPreference>(key)?.text?.trim()?.toIntOrNull() ?: 0) == 0
        presetIntSummary("$prefix.max_streams", presets.muxConcurrency) {
            unset("$prefix.max_connections") && unset("$prefix.min_streams")
        }
        val maxStreams = findPreference<Preference>("$prefix.max_streams")
        for (key in arrayOf("$prefix.max_connections", "$prefix.min_streams")) {
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                // the new value is stored after this returns
                listView?.post { maxStreams?.refreshSummary() }
                true
            }
        }
        if (presets.muxPadding) findPreference<Preference>("$prefix.padding")?.summary = getString(R.string.preset_forced_on)
    }
}

/** The QUIC tuning block (`quic.*`) of hysteria, tuic and masque. */
object QuicBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "quic") {
        pbm.text("$prefix.idle_timeout")
        pbm.text("$prefix.keep_alive_period")
        pbm.text("$prefix.stream_receive_window")
        pbm.text("$prefix.connection_receive_window")
        pbm.int("$prefix.max_concurrent_streams")
        pbm.int("$prefix.initial_packet_size")
        pbm.tri("$prefix.disable_path_mtu_discovery", "$prefix.disable_path_mtu_discovery_unspecified")
    }

    /** QUICFields.cpp:70-86; the core defaults are the desktop's placeholders (edit_advanced.ui). */
    fun setup(
        pf: PreferenceFragmentCompat,
        prefix: String = "quic",
        presets: BuildContext = SettingsMapper.buildContext(),
    ) = with(pf) {
        numberInput("$prefix.max_concurrent_streams", "$prefix.initial_packet_size")
        presetTextSummary("$prefix.idle_timeout", presets.h2IdleTimeout.trim(), "30s")
        presetTextSummary("$prefix.keep_alive_period", presets.h2KeepAlivePeriod.trim(), "15s")
        presetTextSummary("$prefix.stream_receive_window", presets.h2StreamReceiveWindow.trim(), "8 MB")
        presetTextSummary("$prefix.connection_receive_window", presets.h2ConnectionReceiveWindow.trim(), "64 MB")
        presetIntSummary("$prefix.max_concurrent_streams", presets.h2MaxConcurrentStreams)
        presetIntSummary("$prefix.initial_packet_size", presets.quicInitialPacketSize)
        presetTriSummary("$prefix.disable_path_mtu_discovery", presets.quicDisablePathMtuDiscovery)
    }
}

/** The sing-box V2Ray transport block (`transport.*`) of vmess, vless and trojan. */
object TransportBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "transport") {
        pbm.text("$prefix.type")
        pbm.text("$prefix.host")
        pbm.text("$prefix.path")
        pbm.text("$prefix.method")
        pbm.text("$prefix.headers")
        pbm.text("$prefix.idle_timeout")
        pbm.text("$prefix.ping_timeout")
        pbm.int("$prefix.max_early_data")
        pbm.text("$prefix.early_data_header_name")
        pbm.text("$prefix.service_name")
    }

    fun setup(pf: PreferenceFragmentCompat, prefix: String = "transport") = with(pf) {
        numberInput("$prefix.max_early_data")
        multilineInput("$prefix.headers")
        // the model stores "" for plain TCP; the menu needs a selectable value
        findPreference<SimpleMenuPreference>("$prefix.type")?.let { if (it.value.isNullOrEmpty()) it.value = "tcp" }
        onMenu("$prefix.type") { type ->
            val hostPath = type == "ws" || type == "http" || type == "httpupgrade"
            setVisible(hostPath, "$prefix.host", "$prefix.path")
            setVisible(type == "http", "$prefix.method")
            setVisible(type == "ws" || type == "http" || type == "httpupgrade", "$prefix.headers")
            setVisible(type == "http" || type == "grpc", "$prefix.idle_timeout", "$prefix.ping_timeout")
            setVisible(type == "ws", "$prefix.max_early_data", "$prefix.early_data_header_name")
            setVisible(type == "grpc", "$prefix.service_name")
        }
    }
}

/** The dial fields every outbound carries (`dial.*`). */
object DialBlock {
    fun bind(pbm: PreferenceBindingManager, prefix: String = "dial") {
        pbm.text("$prefix.bind_interface")
        pbm.text("$prefix.inet4_bind_address")
        pbm.text("$prefix.inet6_bind_address")
        pbm.text("$prefix.connect_timeout")
        pbm.bool("$prefix.tcp_fast_open")
        pbm.bool("$prefix.tcp_multi_path")
        pbm.bool("$prefix.udp_fragment")
        pbm.bool("$prefix.reuse_addr")
    }
}
