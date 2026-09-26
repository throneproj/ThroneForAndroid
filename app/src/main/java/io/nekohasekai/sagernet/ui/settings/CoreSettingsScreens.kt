package io.nekohasekai.sagernet.ui.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.needRestart
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.profile.keepUnlistedValue
import moe.matsuri.nb4a.ui.LongClickListPreference
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import kotlin.math.abs

/**
 * Preset Settings: the globals a profile inherits unless it sets its own (multiplex, TLS fragment, TLS tricks, uTLS,
 * HTTP/2 and QUIC). TLS spoof is left out on Android (decision D8).
 */
class PresetSettingsFragment : SettingsScreenFragment(R.xml.settings_presets) {

    override fun bind() {
        reloadOn(
            SettingsRegistry.MUX_DEFAULT_ON.key, SettingsRegistry.MUX_PROTOCOL.key, SettingsRegistry.MUX_PADDING.key,
            SettingsRegistry.XRAY_MUX_DEFAULT_ON.key, SettingsRegistry.FRAGMENT_DEFAULT_ON.key,
            SettingsRegistry.TLS_TRICKS_DEFAULT_ON.key, SettingsRegistry.UTLS_FINGERPRINT.key,
            SettingsRegistry.QUIC_DISABLE_PATH_MTU_DISCOVERY.key,
        )
        checkInt(SettingsRegistry.MUX_CONCURRENCY.key, R.string.invalid_number) { it >= 0 }
        checkInt(SettingsRegistry.XRAY_MUX_CONCURRENCY.key, R.string.invalid_number) { it >= 0 }

        // Size and sleep only feed the custom (dialer-level) fragment (dialog_preset_settings.cpp:40-47).
        val implementation = pref<SimpleMenuPreference>(SettingsRegistry.FRAGMENT_IMPLEMENTATION.key)
        implementation.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
            getString(R.string.preset_fragment_implementation_sum, it.entry ?: it.value.orEmpty())
        }
        val size = pref<EditTextPreference>(SettingsRegistry.FRAGMENT_SIZE.key)
        val sleep = pref<EditTextPreference>(SettingsRegistry.FRAGMENT_SLEEP.key)
        checkText(size.key, R.string.invalid_fragment_range, valid = SettingValidators::isRangeOrEmpty)
        checkText(sleep.key, R.string.invalid_fragment_range, valid = SettingValidators::isRangeOrEmpty)
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

        checkText(SettingsRegistry.H2_IDLE_TIMEOUT.key, R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        checkText(SettingsRegistry.H2_KEEP_ALIVE_PERIOD.key, R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        checkText(SettingsRegistry.H2_STREAM_RECEIVE_WINDOW.key, R.string.invalid_value) { true }
        checkText(SettingsRegistry.H2_CONNECTION_RECEIVE_WINDOW.key, R.string.invalid_value) { true }
        // 0 keeps the core default and is shown as such (dialog_preset_settings.cpp:12-16).
        checkInt(SettingsRegistry.H2_MAX_CONCURRENT_STREAMS.key, R.string.invalid_number, blankAs = 0) { it >= 0 }
        checkInt(SettingsRegistry.QUIC_INITIAL_PACKET_SIZE.key, R.string.invalid_number, blankAs = 0) { it >= 0 }
        placeholder(SettingsRegistry.H2_IDLE_TIMEOUT.key, "30s")
        placeholder(SettingsRegistry.H2_KEEP_ALIVE_PERIOD.key, "15s")
        placeholder(SettingsRegistry.H2_STREAM_RECEIVE_WINDOW.key, "8 MB")
        placeholder(SettingsRegistry.H2_CONNECTION_RECEIVE_WINDOW.key, "64 MB")
        zeroAsDefault(SettingsRegistry.H2_MAX_CONCURRENT_STREAMS.key)
        zeroAsDefault(SettingsRegistry.QUIC_INITIAL_PACKET_SIZE.key)
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
        checkText(SettingsRegistry.DIRECT_TEST_URL.key, R.string.speed_test_url_invalid, reload = false) {
            it.isEmpty() || SpeedTestSettings.isValidHttpUrl(it)
        }
        pref<EditTextPreference>(SettingsRegistry.DIRECT_TEST_URL.key).summaryProvider =
            DefaultSummaryProvider(getString(R.string.direct_test_url_empty))

        checkInt(SettingsRegistry.SPEED_TEST_TIMEOUT_MS.key, R.string.speed_test_timeout_invalid, reload = false) { it > 0 }

        val simpleUrl = pref<EditTextPreference>(SettingsRegistry.SIMPLE_DL_URL.key)
        simpleUrl.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        checkText(simpleUrl.key, R.string.speed_test_url_invalid, reload = false, valid = SpeedTestSettings::isValidHttpUrl)
        simpleUrl.isEnabled = DataStore.speedTestMode == SpeedTestSettings.SIMPLE_DOWNLOAD
        pref<SimpleMenuPreference>(SettingsRegistry.SPEED_TEST_MODE.key).setOnPreferenceChangeListener { _, newValue ->
            simpleUrl.isEnabled = (newValue as String).toIntOrNull() == SpeedTestSettings.SIMPLE_DOWNLOAD
            true
        }
    }
}

/**
 * Logging, statistics, the Clash API, the sing-box API that serves the dashboard, Xray import preference,
 * certificate defaults and the NTP client.
 */
class CoreSettingsFragment : SettingsScreenFragment(R.xml.settings_core) {

    override fun bind() {
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
                    DataStore.logBufSize = view.text.toString().toIntOrNull()?.takeIf { it > 0 } ?: 50
                    needRestart()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        reloadOn(
            SettingsRegistry.XRAY_LOG_LEVEL.key, SettingsRegistry.ENABLE_STATS.key,
            SettingsRegistry.DISABLE_TRAFFIC_STATS.key, SettingsRegistry.SKIP_CERT.key,
            SettingsRegistry.USE_MOZILLA_CERTS.key, SettingsRegistry.NTP_OUTBOUND.key,
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
        checkText(listen.key, R.string.invalid_address) { it.isNotEmpty() }
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
        checkText(SettingsRegistry.NTP_SERVER_ADDRESS.key, R.string.invalid_address) { true }
        checkInt(SettingsRegistry.NTP_SERVER_PORT.key, R.string.invalid_port, blankAs = 0) { it in 0..65535 }
        checkText(SettingsRegistry.NTP_INTERVAL.key, R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
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
