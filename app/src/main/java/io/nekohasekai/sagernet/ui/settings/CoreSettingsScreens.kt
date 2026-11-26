package io.nekohasekai.sagernet.ui.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.outbound.OutboundChecks
import io.nekohasekai.sagernet.outbound.common.QuicChecks
import io.nekohasekai.sagernet.outbound.common.TlsChecks
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.profile.MuxBlock
import io.nekohasekai.sagernet.ui.profile.XrayMuxBlock
import io.nekohasekai.sagernet.ui.profile.keepUnlistedValue
import io.nekohasekai.sagernet.ui.test.ServiceCheckSelector
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import io.nekohasekai.sagernet.widget.ExitIp
import moe.matsuri.nb4a.ui.LongClickListPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.UrlTestPreference
import kotlin.math.abs

/**
 * Preset Settings: the globals a profile inherits unless it sets its own (multiplex, TLS fragment, TLS tricks, uTLS,
 * HTTP/2 and QUIC). TLS spoof is left out on Android (decision D8).
 */
class PresetSettingsFragment : SettingsScreenFragment(R.xml.settings_presets) {

    override fun bind() {
        reloadOn(
            SettingsRegistry.MUX_DEFAULT_ON.key, SettingsRegistry.MUX_PROTOCOL.key, SettingsRegistry.MUX_PADDING.key,
            SettingsRegistry.XRAY_MUX_DEFAULT_ON.key, SettingsRegistry.XRAY_MUX_XUDP_PROXY_UDP443.key,
            SettingsRegistry.FRAGMENT_DEFAULT_ON.key, SettingsRegistry.TLS_TRICKS_DEFAULT_ON.key,
            SettingsRegistry.UTLS_FINGERPRINT.key, SettingsRegistry.QUIC_DISABLE_PATH_MTU_DISCOVERY.key,
        )
        bindSingBoxMux()
        bindXrayMux()

        // Size and sleep only feed the custom (dialer-level) fragment (dialog_preset_settings.cpp:40-47).
        val implementation = pref<SimpleMenuPreference>(SettingsRegistry.FRAGMENT_IMPLEMENTATION.key)
        implementation.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
            getString(R.string.preset_fragment_implementation_sum, it.entry ?: it.value.orEmpty())
        }
        val size = pref<EditTextPreference>(SettingsRegistry.FRAGMENT_SIZE.key)
        val sleep = pref<EditTextPreference>(SettingsRegistry.FRAGMENT_SLEEP.key)
        gateText(size.key, OutboundChecks::tlsFragmentSize, invalid = R.string.invalid_fragment_range, valid = SettingValidators::isRangeOrEmpty)
        validation.bind(size.key, OutboundChecks::tlsFragmentSizeLarge).warning()
        gateText(sleep.key, OutboundChecks::tlsFragmentSleep, invalid = R.string.invalid_fragment_range, valid = SettingValidators::isRangeOrEmpty)
        fragmentRangeSummary(size, SettingsRegistry.FRAGMENT_SIZE.default)
        fragmentRangeSummary(sleep, SettingsRegistry.FRAGMENT_SLEEP.default)
        fun syncFragment(value: String) {
            val custom = value == "custom"
            size.isEnabled = custom
            sleep.isEnabled = custom
        }
        syncFragment(DataStore.fragmentImplementation)
        implementation.setOnPreferenceChangeListener { _, newValue ->
            syncFragment(newValue as String)
            needReload()
            true
        }
        // the desktop combo is editable, so a restored fingerprint may be one the menu does not list
        pref<SimpleMenuPreference>(SettingsRegistry.UTLS_FINGERPRINT.key).keepUnlistedValue()
        validation.bind(SettingsRegistry.UTLS_FINGERPRINT.key, TlsChecks::singBoxFingerprint)

        for (key in listOf(SettingsRegistry.H2_IDLE_TIMEOUT.key, SettingsRegistry.H2_KEEP_ALIVE_PERIOD.key)) {
            gateText(key, Validation.Rules.duration(), invalid = R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        }
        gateText(SettingsRegistry.H2_STREAM_RECEIVE_WINDOW.key, Validation.Rules.memoryBytes(true))
        gateText(SettingsRegistry.H2_CONNECTION_RECEIVE_WINDOW.key, Validation.Rules.memoryBytes(true))
        // 0 keeps the core default and is shown as such (dialog_preset_settings.cpp:12-16).
        checkInt(SettingsRegistry.H2_MAX_CONCURRENT_STREAMS.key, R.string.invalid_number, blankAs = 0) { it >= 0 }
        gateInt(SettingsRegistry.QUIC_INITIAL_PACKET_SIZE.key, QuicChecks::quicInitialPacketSize, blankAs = 0)
        placeholder(SettingsRegistry.H2_IDLE_TIMEOUT.key, "30s")
        placeholder(SettingsRegistry.H2_KEEP_ALIVE_PERIOD.key, "15s")
        placeholder(SettingsRegistry.H2_STREAM_RECEIVE_WINDOW.key, "8 MB")
        placeholder(SettingsRegistry.H2_CONNECTION_RECEIVE_WINDOW.key, "64 MB")
        zeroAsDefault(SettingsRegistry.H2_MAX_CONCURRENT_STREAMS.key)
        zeroAsDefault(SettingsRegistry.QUIC_INITIAL_PACKET_SIZE.key)
    }

    /** The sing-box multiplex preset, the desktop's MuxSettingsWidget (dialog_preset_settings.cpp:28, 75, 104). */
    private fun bindSingBoxMux() {
        val count = Validation.Rules.int(1, Int.MAX_VALUE.toLong(), Empty.Required)
        for (setting in listOf(SettingsRegistry.MUX_CONCURRENCY, SettingsRegistry.MUX_MAX_CONNECTIONS)) {
            gateInt(setting.key, count)
        }
        gateInt(SettingsRegistry.MUX_MIN_STREAMS.key, Validation.Rules.int(0, Int.MAX_VALUE.toLong()), blankAs = 0)
        // The speeds stay on screen so they can be set before TCP Brutal is switched on; like the desktop they are
        // required only while it is on, and switching it on is refused until both are set.
        val brutal = pref<SwitchPreference>(SettingsRegistry.MUX_BRUTAL.key)
        var brutalOn = brutal.isChecked
        for (setting in listOf(SettingsRegistry.MUX_BRUTAL_UP_MBPS, SettingsRegistry.MUX_BRUTAL_DOWN_MBPS)) {
            gateInt(setting.key, count).onlyIf { brutalOn }
        }

        val keys = mapOf(
            "mode" to SettingsRegistry.MUX_MODE.key,
            "max_streams" to SettingsRegistry.MUX_CONCURRENCY.key,
            "max_connections" to SettingsRegistry.MUX_MAX_CONNECTIONS.key,
            "min_streams" to SettingsRegistry.MUX_MIN_STREAMS.key,
            "brutal.up_mbps" to SettingsRegistry.MUX_BRUTAL_UP_MBPS.key,
            "brutal.down_mbps" to SettingsRegistry.MUX_BRUTAL_DOWN_MBPS.key,
        )
        val mode = pref<SimpleMenuPreference>(SettingsRegistry.MUX_MODE.key)
        fun sync(modeValue: String = mode.value.orEmpty()) =
            MuxBlock.poolVisibility(this, modeValue, brutalOn, speedsAlways = true) { keys.getValue(it) }
        sync()
        mode.setOnPreferenceChangeListener { _, newValue ->
            sync(modeValue = newValue as String)
            needReload()
            true
        }
        brutal.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            if (on && (DataStore.muxBrutalUpMbps <= 0 || DataStore.muxBrutalDownMbps <= 0)) {
                refuse(brutal, getString(R.string.validation_mux_brutal_both_speeds))
                return@setOnPreferenceChangeListener false
            }
            brutalOn = on
            sync()
            validation.refresh()
            needReload()
            true
        }
        MuxBlock.modeSummary(this, mode.key)
    }

    /** The Xray mux preset, the desktop's XrayMuxSettingsWidget (dialog_preset_settings.cpp:30, 76, 106). */
    private fun bindXrayMux() {
        gateInt(SettingsRegistry.XRAY_MUX_CONCURRENCY.key, XrayMuxBlock.COUNT_RULE)
        gateInt(SettingsRegistry.XRAY_MUX_MAX_CONNECTIONS.key, XrayMuxBlock.MAX_CONNECTIONS_RULE, blankAs = 0)
        gateInt(SettingsRegistry.XRAY_MUX_XUDP_CONCURRENCY.key, XrayMuxBlock.COUNT_RULE)
        val keys = mapOf(
            "concurrency" to SettingsRegistry.XRAY_MUX_CONCURRENCY.key,
            "maxConnections" to SettingsRegistry.XRAY_MUX_MAX_CONNECTIONS.key,
            "xudpConcurrency" to SettingsRegistry.XRAY_MUX_XUDP_CONCURRENCY.key,
            "xudpProxyUDP443" to SettingsRegistry.XRAY_MUX_XUDP_PROXY_UDP443.key,
        )
        // After the gates, whose number input is unsigned.
        XrayMuxBlock.inputs(this) { keys.getValue(it) }
    }

    private fun placeholder(key: String, defaultText: String) {
        pref<EditTextPreference>(key).summaryProvider = DefaultSummaryProvider(defaultText)
    }

    private fun fragmentRangeSummary(preference: EditTextPreference, default: String) {
        preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            when {
                !it.isEnabled -> getString(R.string.preset_custom_implementation_only)
                it.text.isNullOrEmpty() -> getString(R.string.setting_default_value, default)
                else -> it.text
            }
        }
    }

    private fun zeroAsDefault(key: String) {
        pref<EditTextPreference>(key).summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
            val text = preference.text.orEmpty()
            if (text.isEmpty() || text == "0") getString(R.string.setting_default) else text
        }
    }
}

/** Basic Settings › Common › Testing. */
class TestingSettingsFragment : SettingsScreenFragment(R.xml.settings_testing) {

    override fun bind() {
        bindUrlTest()
        gateText(
            SettingsRegistry.DIRECT_TEST_URL.key, Validation.Rules.url(SettingChecks.HTTP), reload = false,
            invalid = R.string.speed_test_url_invalid,
        ) { it.isEmpty() || SpeedTestSettings.isValidHttpUrl(it) }
        pref<EditTextPreference>(SettingsRegistry.DIRECT_TEST_URL.key).summaryProvider =
            DefaultSummaryProvider(getString(R.string.direct_test_url_empty))

        // Off drops the shown exit and a lookup in flight; on waits for the next connection or stats bar tap.
        pref<SwitchPreference>(SettingsRegistry.EXIT_COUNTRY_LOOKUP.key).setOnPreferenceChangeListener { _, newValue ->
            if (newValue == false) ExitIp.clear()
            true
        }
        // A newer desktop's service id stays stored (it runs as "auto" here) instead of being replaced by the menu.
        pref<SimpleMenuPreference>(SettingsRegistry.EXIT_LOOKUP_PROVIDER.key).keepUnlistedValue()

        // Only the URL test treats 0 as its default; the download/upload test would time out at once.
        gateInt(SettingsRegistry.SPEED_TEST_TIMEOUT_MS.key, { SettingChecks.millis(it, 5000, zeroIsDefault = false) }, reload = false)

        val simpleUrl = pref<EditTextPreference>(SettingsRegistry.SIMPLE_DL_URL.key)
        simpleUrl.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        gateText(
            simpleUrl.key, Validation.Rules.url(SettingChecks.HTTP, Empty.Required), reload = false,
            invalid = R.string.speed_test_url_invalid, valid = SpeedTestSettings::isValidHttpUrl,
        )
        simpleUrl.isEnabled = DataStore.speedTestMode == SpeedTestSettings.SIMPLE_DOWNLOAD
        pref<SimpleMenuPreference>(SettingsRegistry.SPEED_TEST_MODE.key).setOnPreferenceChangeListener { _, newValue ->
            simpleUrl.isEnabled = (newValue as String).toIntOrNull() == SpeedTestSettings.SIMPLE_DOWNLOAD
            true
        }
        ServiceCheckSelector.bind(pref(ServiceCheckSelector.PREFERENCE_KEY))
    }

    /**
     * The URL test's concurrency and timeout are edited in its URL dialog: their problems refuse that dialog's commit,
     * and the stored values mark the URL row.
     */
    private fun bindUrlTest() {
        val urlTest = pref<UrlTestPreference>(SettingsRegistry.TEST_URL.key)
        validation.bind(urlTest.key, Validation.Rules.url(SettingChecks.HTTP, Empty.Required)).gate()
        fun problems(concurrency: String, timeout: String): List<String> = listOf(
            // The desktop allows 0, but the stored setting must be positive: the dialog would save 0 as 5.
            R.string.test_concurrency to Validation.int(concurrency, 1, 499, Empty.Required),
            R.string.connection_test_timeout to SettingChecks.millis(timeout, 3000, zeroIsDefault = true),
        ).filter { it.second.isNotEmpty() }.map { vtr(R.string.validation_form_issue, getString(it.first), it.second) }

        val save = urlTest.onPreferenceChangeListener
        urlTest.setOnPreferenceChangeListener { preference, newValue ->
            val problem = problems(
                urlTest.concurrent?.text?.toString() ?: DataStore.testConcurrent.toString(),
                urlTest.timeout?.text?.toString() ?: DataStore.urlTestTimeoutMs.toString(),
            ).firstOrNull()
            if (problem != null) {
                refuse(preference, problem)
                false
            } else {
                save?.onPreferenceChange(preference, newValue) ?: true
            }
        }
        validation.bind(urlTest.key) {
            problems(DataStore.testConcurrent.toString(), DataStore.urlTestTimeoutMs.toString()).joinToString("\n")
        }
    }
}

/**
 * Logging, statistics, the Clash API, the sing-box API that serves the dashboard, Xray import preference,
 * certificate defaults and the NTP client.
 */
class CoreSettingsFragment : SettingsScreenFragment(R.xml.settings_core) {

    // After the write, so the sort reads the new value: Type/Security keys depend on skip_cert (desktop 6906912f).
    private val skipCertListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key != SettingsRegistry.SKIP_CERT.key) return
            runOnIoDispatcher {
                for (group in SagerDatabase.groupDao.allGroups()) {
                    val method = group.sort.method
                    if (method == GroupSortMethod.BY_TYPE || method == GroupSortMethod.BY_SECURITY) {
                        GroupSort.resortLater(group.id)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        DataStore.configurationStore.unregisterChangeListener(skipCertListener)
        super.onDestroy()
    }

    override fun bind() {
        DataStore.configurationStore.registerChangeListener(skipCertListener)
        val logLevel = pref<LongClickListPreference>(SettingsRegistry.LOG_LEVEL.key)
        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
            if (context == null) return@setOnLongClickListener true
            val view = EditText(context).apply {
                inputType = EditorInfo.TYPE_CLASS_NUMBER
                var size = DataStore.logBufSize
                if (size == 0) size = moe.matsuri.nb4a.utils.CoreLog.DEFAULT_SIZE_KB
                setText(size.toString())
            }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.log_buffer_size)
                .setView(view)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DataStore.logBufSize = view.text.toString().toIntOrNull()?.takeIf { it > 0 }
                        ?.coerceIn(moe.matsuri.nb4a.utils.CoreLog.MIN_SIZE_KB, moe.matsuri.nb4a.utils.CoreLog.MAX_SIZE_KB) ?: 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        reloadOn(
            SettingsRegistry.XRAY_LOG_LEVEL.key, SettingsRegistry.ENABLE_STATS.key, SettingsRegistry.SKIP_CERT.key,
            SettingsRegistry.USE_MOZILLA_CERTS.key, SettingsRegistry.XRAY_USE_SYSTEM_CA.key,
            SettingsRegistry.NTP_OUTBOUND.key,
        )

        bindClashApi()
        bindApiDashboard()
        bindNtp()
    }

    /** core_box_clash_api is one sign-encoded port: at most 0 is off, the magnitude is kept while off. */
    private fun bindClashApi() {
        val enabled = pref<SwitchPreference>(KEY_CLASH_ENABLED)
        val port = pref<EditTextPreference>(KEY_CLASH_PORT)
        val listen = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_CLASH_LISTEN_ADDR.key)
        val secret = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_CLASH_API_SECRET.key)
        fun portValue() = abs(DataStore.coreBoxClashApi).takeIf { it > 0 } ?: -SettingsRegistry.CORE_BOX_CLASH_API.default
        fun sync(on: Boolean) {
            port.isEnabled = on
            listen.isEnabled = on
            secret.isEnabled = on
        }

        enabled.isChecked = DataStore.clashApiEnabled
        port.text = portValue().toString()
        port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        secret.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        sync(enabled.isChecked)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            if (on && refusedByPorts(enabled, SettingsRegistry.CORE_BOX_CLASH_API.key to portValue().toString())) {
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxClashApi = if (on) portValue() else -portValue()
            sync(on)
            needReload()
            true
        }
        port.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || !SettingValidators.isPort(value)) {
                toast(R.string.invalid_port, newValue?.toString().orEmpty())
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxClashApi = if (enabled.isChecked) value else -value
            needReload()
            true
        }
        validation.bind(port.key, SettingChecks.listenPort(SettingsRegistry.Listener.CLASH_API)).gate()
        // Empty listens on the loopback address, as the desktop's placeholder says.
        gateText(listen.key, Validation.Rules.host(Empty.Allowed))
        listen.summaryProvider = DefaultSummaryProvider(SettingsRegistry.LOOPBACK_ADDRESS)
        checkText(secret.key, R.string.invalid_value) { true }
    }

    /**
     * core_box_api_port, sign-encoded like core_box_clash_api; it also shows the dashboard in the drawer. The secret is
     * never stored empty (sing-box reads that as no authentication): clearing it makes a new random one.
     */
    private fun bindApiDashboard() {
        val enabled = pref<SwitchPreference>(KEY_API_ENABLED)
        val port = pref<EditTextPreference>(KEY_API_PORT)
        val secret = pref<EditTextPreference>(SettingsRegistry.CORE_BOX_API_SECRET.key)
        fun portValue() = abs(DataStore.coreBoxApiPort).takeIf { it > 0 } ?: abs(SettingsRegistry.CORE_BOX_API_PORT.default)
        fun sync(on: Boolean) {
            port.isEnabled = on
            secret.isEnabled = on
        }

        enabled.isChecked = DataStore.apiDashboardEnabled
        port.text = portValue().toString()
        port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        secret.text = DataStore.coreBoxApiSecret
        secret.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        sync(enabled.isChecked)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            if (on && refusedByPorts(enabled, SettingsRegistry.CORE_BOX_API_PORT.key to portValue().toString())) {
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxApiPort = if (on) portValue() else -portValue()
            sync(on)
            (activity as? MainActivity)?.refreshNavMenu(on)
            needReload()
            true
        }
        port.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || !SettingValidators.isPort(value)) {
                toast(R.string.invalid_port, newValue?.toString().orEmpty())
                return@setOnPreferenceChangeListener false
            }
            DataStore.coreBoxApiPort = if (enabled.isChecked) value else -value
            needReload()
            true
        }
        validation.bind(port.key, SettingChecks.listenPort(SettingsRegistry.Listener.BOX_API)).gate()
        secret.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim().orEmpty()
            // Reading an empty secret back generates and saves a new one.
            if (value.isEmpty()) DataStore.coreBoxApiSecret = ""
            secret.text = value.ifEmpty { DataStore.coreBoxApiSecret }
            needReload()
            false
        }
    }

    private fun bindNtp() {
        val enable = pref<SwitchPreference>(SettingsRegistry.ENABLE_NTP.key)
        val fields = listOf(
            SettingsRegistry.NTP_SERVER_ADDRESS.key, SettingsRegistry.NTP_SERVER_PORT.key,
            SettingsRegistry.NTP_INTERVAL.key, SettingsRegistry.NTP_OUTBOUND.key,
        ).map { pref<Preference>(it) }
        fun sync(on: Boolean) = fields.forEach { it.isEnabled = on }
        sync(enable.isChecked)
        enable.setOnPreferenceChangeListener { _, newValue ->
            sync(newValue as Boolean)
            needReload()
            true
        }
        gateText(SettingsRegistry.NTP_SERVER_ADDRESS.key, Validation.Rules.host(Empty.Allowed))
        // The core turns port 0 into 123.
        gateInt(SettingsRegistry.NTP_SERVER_PORT.key, { if (Validation.toInt(it) == 0L) "" else Validation.port(it, Empty.Allowed) }, blankAs = 0)
        gateText(
            SettingsRegistry.NTP_INTERVAL.key, Validation.Rules.duration(),
            invalid = R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty,
        )
        pref<EditTextPreference>(SettingsRegistry.NTP_SERVER_PORT.key).summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { preference ->
                val text = preference.text.orEmpty()
                if (text.isEmpty() || text == "0") getString(R.string.setting_default) else text
            }
        pref<EditTextPreference>(SettingsRegistry.NTP_INTERVAL.key).summaryProvider =
            DefaultSummaryProvider("30m")
    }

    private companion object {
        const val KEY_CLASH_ENABLED = "coreClashApiEnabled"
        const val KEY_CLASH_PORT = "coreClashApiPort"
        const val KEY_API_ENABLED = "coreApiDashboardEnabled"
        const val KEY_API_PORT = "coreApiDashboardPort"
    }
}
