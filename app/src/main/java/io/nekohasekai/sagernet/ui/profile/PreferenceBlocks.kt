package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import android.text.InputType
import androidx.annotation.StringRes
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.common.Ech
import io.nekohasekai.sagernet.outbound.common.MUX_CUSTOM
import io.nekohasekai.sagernet.outbound.common.MuxSettings
import io.nekohasekai.sagernet.outbound.common.QuicChecks
import io.nekohasekai.sagernet.outbound.common.XRAY_MUX_MAX_COUNT
import io.nekohasekai.sagernet.outbound.common.XrayMuxSettings
import io.nekohasekai.sagernet.outbound.common.certificatePinsFromHex
import io.nekohasekai.sagernet.outbound.common.splitCipherSuites
import io.nekohasekai.sagernet.outbound.common.xrayXudpProxyUDP443Modes
import io.nekohasekai.sagernet.outbound.config.DnsServers
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import moe.matsuri.nb4a.proxy.CustomRows
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

fun PreferenceFragmentCompat.signedNumberInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnBindEditTextListener(EditTextPreferenceModifiers.SignedNumber)
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

/** Cipher suite names as entered (split by line, ':' or ','), stored joined by [separator] (splitCipherSuites). */
fun PreferenceFragmentCompat.cipherSuitesInput(key: String, separator: String) {
    findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { row, newValue ->
        (row as EditTextPreference).text = splitCipherSuites((newValue as String).replace('\n', ':')).joinToString(separator)
        false
    }
}

/** Pins pasted as hex SHA-256 (openssl's output) are stored in the base64 form sing-box takes (CertificatePinsFromHex). */
fun PreferenceFragmentCompat.certificatePinsInput(vararg keys: String) {
    for (key in keys) findPreference<EditTextPreference>(key)?.setOnPreferenceChangeListener { row, newValue ->
        (row as EditTextPreference).text = certificatePinsFromHex((newValue as String).split('\n')).joinToString("\n")
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

/** A Keep default / On / Off (/ Custom) menu: "Keep default (On)" when the preset turns it on. */
fun PreferenceFragmentCompat.presetTriSummary(key: String, defaultOn: Boolean) {
    findPreference<ListPreference>(key)?.summaryProvider = Preference.SummaryProvider<ListPreference> {
        val on = it.context.getString(R.string.tri_state_on)
        val off = it.context.getString(R.string.tri_state_off)
        when (it.value) {
            "1" -> on
            "2" -> off
            MUX_CUSTOM.toString() -> it.context.getString(R.string.mux_state_custom)
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
        pbm.text("$prefix.cipher_suites")
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
            "$prefix.ech.config", "$prefix.cipher_suites",
        )
        echInputs("$prefix.ech")
        cipherSuitesInput("$prefix.cipher_suites", "\n")
        certificatePinsInput("$prefix.certificate_sha256", "$prefix.certificate_public_key_sha256")
        if (mustTls) {
            findPreference<Preference>(enabledKey)?.isVisible = false
            // a new profile starts with TLS off, and with the switch hidden the block would be dropped on save
            // (dialog_edit_profile_singbox.cpp:130,172 forces it on through the security combo)
            DataStore.profileCacheStore.putBoolean(enabledKey, true)
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

/**
 * The sing-box multiplex block (`multiplex.*`), shared by shadowsocks, vmess, vless and trojan: the Keep default / On /
 * Off / Custom menu and, inline while it is Custom, the rows of the desktop's Custom dialog (MuxSettingsWidget.cpp)
 * holding Multiplex::CustomSettings (dialog_edit_profile_singbox.cpp:150-154, 188-192). The preset screen reuses
 * [poolVisibility] and [modeSummary] for the same rows under its own keys.
 */
object MuxBlock {
    const val MODE_STREAMS = "max_streams"
    const val MODE_CONNECTIONS = "max_connections"

    fun bind(pbm: PreferenceBindingManager, prefix: String = "multiplex") {
        pbm.muxState(prefix, Rows(prefix))
    }

    /** MuxSettingsWidget::setSettings / settings (MuxSettingsWidget.cpp:114-139): a count of 0 shows blank, min_streams as a number. */
    private class Rows(private val prefix: String) : CustomRows<MuxSettings> {
        private val store get() = DataStore.profileCacheStore

        private fun positive(value: Int) = if (value > 0) value.toString() else ""

        private fun number(key: String) = store.getString("$prefix.$key")?.trim()?.toIntOrNull() ?: 0

        override fun write(settings: MuxSettings) {
            store.putString("$prefix.protocol", settings.protocol)
            store.putString("$prefix.mode", if (settings.by_connections) MODE_CONNECTIONS else MODE_STREAMS)
            store.putString("$prefix.max_streams", positive(settings.max_streams))
            store.putString("$prefix.max_connections", positive(settings.max_connections))
            store.putString("$prefix.min_streams", maxOf(0, settings.min_streams).toString())
            store.putBoolean("$prefix.padding", settings.padding)
            store.putBoolean("$prefix.brutal.enabled", settings.brutal)
            store.putString("$prefix.brutal.up_mbps", positive(settings.brutal_up_mbps))
            store.putString("$prefix.brutal.down_mbps", positive(settings.brutal_down_mbps))
        }

        override fun read() = MuxSettings(
            protocol = store.getString("$prefix.protocol").orEmpty(),
            by_connections = store.getString("$prefix.mode") == MODE_CONNECTIONS,
            max_streams = number("max_streams"),
            max_connections = number("max_connections"),
            min_streams = number("min_streams"),
            padding = store.getBoolean("$prefix.padding", false),
            brutal = store.getBoolean("$prefix.brutal.enabled", false),
            brutal_up_mbps = number("brutal.up_mbps"),
            brutal_down_mbps = number("brutal.down_mbps"),
        )
    }

    fun setup(
        pf: PreferenceFragmentCompat,
        prefix: String = "multiplex",
        presets: BuildContext = SettingsMapper.buildContext(),
    ) = with(pf) {
        val stateKey = "$prefix.enabled"
        numberInput("$prefix.max_streams", "$prefix.max_connections", "$prefix.min_streams", "$prefix.brutal.up_mbps", "$prefix.brutal.down_mbps")
        findPreference<ListPreference>("$prefix.protocol")?.keepUnlistedValue()
        val category = findPreference<PreferenceCategory>("muxCategory")
        var custom = false
        var mode = MODE_STREAMS
        var brutal = false
        fun applyVisibility() {
            category?.setChildrenVisible(custom, setOf(stateKey))
            if (custom) poolVisibility(pf, mode, brutal) { "$prefix.$it" }
        }
        onMenu(stateKey) {
            custom = it == MUX_CUSTOM.toString()
            applyVisibility()
        }
        onMenu("$prefix.mode") {
            mode = it
            applyVisibility()
        }
        onSwitch("$prefix.brutal.enabled") {
            brutal = it
            applyVisibility()
        }
        presetTriSummary(stateKey, presets.muxDefaultOn)
        modeSummary(pf, "$prefix.mode")
    }

    /**
     * MuxSettingsWidget::updateControls (MuxSettingsWidget.cpp:152-170) over the rows [key] names by their profile
     * names (mode, max_streams, max_connections, min_streams, brutal.up_mbps, brutal.down_mbps): [mode] shows its pool
     * rows, the speeds show with [brutal], and TCP Brutal disables the mode and pool rows, since the core then sends
     * every stream over a single connection.
     */
    /** [speedsAlways]: the preset keeps the speed rows, which must be set before TCP Brutal can be switched on. */
    fun poolVisibility(
        pf: PreferenceFragmentCompat,
        mode: String,
        brutal: Boolean,
        speedsAlways: Boolean = false,
        key: (String) -> String,
    ) {
        val connections = mode == MODE_CONNECTIONS
        pf.setVisible(!connections, key("max_streams"))
        pf.setVisible(connections, key("max_connections"), key("min_streams"))
        pf.setVisible(brutal || speedsAlways, key("brutal.up_mbps"), key("brutal.down_mbps"))
        for (name in arrayOf("mode", "max_streams", "max_connections", "min_streams")) {
            pf.findPreference<Preference>(key(name))?.isEnabled = !brutal
        }
    }

    /** The mode menu's summary explains the selected mode (the desktop's mode tooltip). */
    fun modeSummary(pf: PreferenceFragmentCompat, key: String) {
        pf.findPreference<ListPreference>(key)?.summaryProvider = Preference.SummaryProvider<ListPreference> {
            it.context.getString(if (it.value == MODE_CONNECTIONS) R.string.mux_mode_max_connections_sum else R.string.mux_mode_max_streams_sum)
        }
    }

    /** MuxSettingsWidget::bindValidation (MuxSettingsWidget.cpp:141-150): checked while shown, so a profile's rows only under Custom. */
    fun bindValidation(form: ValidationForm, prefix: String = "multiplex") {
        val count = Validation.Rules.int(1, Int.MAX_VALUE.toLong(), Empty.Required)
        // warnings: the desktop checks these only inside its Custom dialog, a profile save never refuses them
        for (key in listOf("max_streams", "max_connections", "brutal.up_mbps", "brutal.down_mbps")) {
            form.bind("$prefix.$key", count).warning()
        }
        form.bind("$prefix.min_streams", Validation.Rules.int(0, Int.MAX_VALUE.toLong())).warning()
    }
}

/**
 * The Xray mux rows, the desktop's XrayMuxSettingsWidget (MuxSettingsWidget.cpp): a profile's Custom mux, and the Xray
 * preset. A row is named by its profile name: concurrency, maxConnections, xudpConcurrency, xudpProxyUDP443.
 */
object XrayMuxBlock {
    private val ROWS = listOf("concurrency", "maxConnections", "xudpConcurrency", "xudpProxyUDP443")

    /** Concurrency and XUDP Concurrency: -1 and 0 mean something to Xray, so blank is no value. */
    val COUNT_RULE = Validation.Rules.int(-1, XRAY_MUX_MAX_COUNT.toLong(), Empty.Required)

    /** Max Connections: blank is 0, no limit. */
    val MAX_CONNECTIONS_RULE = Validation.Rules.int(0, XRAY_MUX_MAX_COUNT.toLong())

    fun bind(pbm: PreferenceBindingManager, prefix: String = "multiplex") {
        pbm.muxState(prefix, Rows(prefix))
    }

    /** XrayMuxSettingsWidget::setSettings / settings: no connection limit shows blank, an unknown UDP/443 mode as Reject. */
    private class Rows(private val prefix: String) : CustomRows<XrayMuxSettings> {
        private val store get() = DataStore.profileCacheStore

        private fun number(key: String) = store.getString("$prefix.$key")?.trim()?.toIntOrNull() ?: 0

        override fun write(settings: XrayMuxSettings) {
            store.putString("$prefix.concurrency", settings.concurrency.toString())
            store.putString("$prefix.maxConnections", if (settings.max_connections > 0) settings.max_connections.toString() else "")
            store.putString("$prefix.xudpConcurrency", settings.xudp_concurrency.toString())
            val mode = settings.xudp_proxy_udp443
            store.putString("$prefix.xudpProxyUDP443", if (mode in xrayXudpProxyUDP443Modes) mode else xrayXudpProxyUDP443Modes[0])
        }

        override fun read() = XrayMuxSettings(
            concurrency = number("concurrency"),
            max_connections = number("maxConnections"),
            xudp_concurrency = number("xudpConcurrency"),
            xudp_proxy_udp443 = store.getString("$prefix.xudpProxyUDP443").orEmpty(),
        )
    }

    fun setup(
        pf: PreferenceFragmentCompat,
        prefix: String = "multiplex",
        presets: BuildContext = SettingsMapper.buildContext(),
    ) = with(pf) {
        val stateKey = "$prefix.enabled"
        inputs(pf) { "$prefix.$it" }
        val rows = ROWS.map { "$prefix.$it" }.toTypedArray()
        onMenu(stateKey) { setVisible(it == MUX_CUSTOM.toString(), *rows) }
        presetTriSummary(stateKey, presets.xrayMuxDefaultOn)
    }

    /** The rows' input types and summaries; [key] maps a row's profile name to the screen's key. */
    fun inputs(pf: PreferenceFragmentCompat, key: (String) -> String) = with(pf) {
        signedNumberInput(key("concurrency"), key("xudpConcurrency"))
        numberInput(key("maxConnections"))
        findPreference<EditTextPreference>(key("maxConnections"))?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val text = it.text?.trim().orEmpty()
            if (text.isEmpty() || text == "0") it.context.getString(R.string.xray_mux_no_limit) else text
        }
        // The menu's summary explains the selected mode (the desktop's UDP/443 tooltip).
        findPreference<ListPreference>(key("xudpProxyUDP443"))?.summaryProvider = Preference.SummaryProvider<ListPreference> {
            it.context.getString(
                when (it.value) {
                    "allow" -> R.string.xray_mux_udp443_allow_sum
                    "skip" -> R.string.xray_mux_udp443_skip_sum
                    else -> R.string.xray_mux_udp443_reject_sum
                },
            )
        }
    }

    /** XrayMuxSettingsWidget::bindValidation: checked while shown, so a profile's rows only under Custom. */
    fun bindValidation(form: ValidationForm, prefix: String = "multiplex") {
        form.bind("$prefix.concurrency", COUNT_RULE)
        form.bind("$prefix.maxConnections", MAX_CONNECTIONS_RULE)
        form.bind("$prefix.xudpConcurrency", COUNT_RULE)
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
        // the model stores "" for plain TCP; the menu needs a selectable value. Another stored type (quic, or one
        // sing-box lacks) stays as it is and validate flags it, rather than becoming tcp on save.
        findPreference<SimpleMenuPreference>("$prefix.type")?.let {
            if (it.value.isNullOrEmpty()) it.value = "tcp"
            it.keepUnlistedValue()
        }
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
        bindQuicOpener(pbm, prefix)
        pbm.bool("$prefix.reuse_addr")
    }

    /** The one dial field the QUIC protocols' editors show; Xray builds its own dialer, so its editors leave it out. */
    fun bindQuicOpener(pbm: PreferenceBindingManager, prefix: String = "dial") {
        pbm.bool("$prefix.quic_opener")
    }
}

/**
 * The shared blocks' editor-only checks (dialog_edit_profile_singbox.cpp bindSingboxStream, edit_advanced.cpp), bound
 * on every profile screen: rows a screen does not have stay inert. The model's Validate covers the rest.
 */
fun ValidationForm.bindProfileBlocks(outbound: Outbound) {
    if (outbound.hasTls()) {
        addAlias("tls.ech.query_server_name", "tls.ech.queryTarget")
        bind("tls.ech.queryTarget", ::echResolverRule).warning()
    }
    if (outbound.hasTransport()) {
        bind("transport.headers", ::headerLines)
        bind("transport.max_early_data", Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
    }
    if (outbound.hasMux()) MuxBlock.bindValidation(this)
    if (outbound.hasQuic()) {
        bind("quic.max_concurrent_streams", Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
        // validate sees the parsed number, where text beyond an Int has become 0
        bind("quic.initial_packet_size", QuicChecks::quicInitialPacketSize)
    }
}

/**
 * The header rows' text (headers_hint): a name line then a value line per header, so a "Name: value" line shifts every
 * pair after it. "|" separates headers in share links.
 */
fun headerLines(text: String): String {
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    if (lines.any { it.contains('|') }) return vtr(R.string.validation_header_pipe)
    if (lines.size % 2 != 0) return vtr(R.string.validation_header_lines)
    for (i in lines.indices step 2) {
        val message = Validation.httpToken(lines[i])
        if (message.isNotEmpty()) return vtr(R.string.validation_naive_header_name, lines[i], message)
    }
    return ""
}

/** generate.cpp falls back to the default resolver for an ECH resolver it cannot use; the domain part is validate's. */
private fun echResolverRule(text: String): String {
    val target = Ech()
    target.setQueryTarget(text)
    if (target.resolver.isEmpty()) return ""
    val parsed = DnsServers.parseDnsAddress(target.resolver)
    val address = parsed.address ?: return parsed.why
    return if (address.type == "dhcp") vtr(R.string.validation_ech_resolver_dhcp) else ""
}
