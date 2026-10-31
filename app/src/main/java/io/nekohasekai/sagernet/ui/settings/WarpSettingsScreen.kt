package io.nekohasekai.sagernet.ui.settings

import android.content.Context
import android.util.AttributeSet
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.warp.WarpClient
import io.nekohasekai.sagernet.ui.warp.WarpGenerate
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Routing Settings › WARP: the desktop Warp tab (dialog_manage_routes.cpp:433-503, 570-590), saved as it is edited.
 * The WARP fields only shape the generated config while WARP is enabled.
 */
class WarpSettingsFragment : SettingsScreenFragment(R.xml.settings_warp) {

    private var amneziaPending = false
    private var amneziaError = ""

    override fun bind() {
        val enable = pref<SwitchPreference>(SettingsRegistry.ENABLE_WARP.key)
        enable.setOnPreferenceChangeListener { _, newValue ->
            val enabled = newValue as Boolean
            DataStore.enableWarp = enabled
            if (DataStore.serviceState.started) SagerNet.reloadService()
            if (enabled && !WarpClient.isGenerated()) toast(R.string.warp_not_generated)
            true
        }

        val wireGuard = pref<PreferenceCategory>(KEY_WIREGUARD)
        val masque = pref<PreferenceCategory>(KEY_MASQUE)
        val mode = pref<SimpleMenuPreference>(SettingsRegistry.WARP_MODE.key)
        mode.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
            getString(R.string.warp_mode_sum, it.entry ?: "")
        }
        fun showMode(value: String?) {
            masque.isVisible = value == WarpClient.TUNNEL_MASQUE
            wireGuard.isVisible = !masque.isVisible
            if (value == WarpClient.MODE_AMNEZIA && DataStore.warpAmneziaPackets.isEmpty()) generateAmneziaPackets()
        }
        showMode(mode.value)
        mode.setOnPreferenceChangeListener { _, newValue ->
            showMode(newValue as String)
            reloadIfEnabled()
            true
        }
        validation.addRule(listOf(enable.key, mode.key), label = mode.title?.toString()) {
            when {
                !enable.isChecked || mode.value != WarpClient.MODE_AMNEZIA -> ""
                DataStore.warpAmneziaPackets.isNotEmpty() -> ""
                amneziaPending -> getString(R.string.warp_amnezia_generating)
                else -> amneziaError.ifEmpty { getString(R.string.warp_amnezia_missing) }
            }
        }

        for (setting in listOf(
            SettingsRegistry.WARP_EP, SettingsRegistry.WARP_PRIVATE_KEY, SettingsRegistry.WARP_PUBLIC_KEY,
            SettingsRegistry.WARP_MASQUE_EP, SettingsRegistry.WARP_MASQUE_PRIVATE_KEY,
            SettingsRegistry.WARP_MASQUE_PEER_PUBLIC_KEY, SettingsRegistry.WARP_MASQUE_SNI,
        )) trimmedText(setting.key)
        for (setting in listOf(SettingsRegistry.WARP_PRIVATE_KEY, SettingsRegistry.WARP_MASQUE_PRIVATE_KEY)) {
            pref<EditTextPreference>(setting.key).summaryProvider = ProfileSettingsActivity.PasswordSummaryProvider
        }
        for (setting in listOf(
            SettingsRegistry.WARP_IFC_ADDRS, SettingsRegistry.WARP_RESERVED, SettingsRegistry.WARP_MASQUE_IFC_ADDRS,
        )) itemList(setting.key)

        val httpMode = pref<SimpleMenuPreference>(SettingsRegistry.WARP_MASQUE_HTTP_MODE.key)
        httpMode.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
            getString(R.string.warp_http_version_sum, it.entry ?: "")
        }
        httpMode.setOnPreferenceChangeListener { _, _ ->
            reloadIfEnabled()
            true
        }

        pref<Preference>(KEY_GENERATE).setOnPreferenceClickListener { button ->
            val masqueMode = mode.value == WarpClient.TUNNEL_MASQUE
            val tunnelType = if (masqueMode) WarpClient.TUNNEL_MASQUE else WarpClient.TUNNEL_WIREGUARD
            val onPackets = if (mode.value == WarpClient.MODE_AMNEZIA) ::packetsGenerated else null
            WarpGenerate.generate(
                this, requireContext(), button, tunnelType, R.string.warp_generate_failed, onPackets,
            ) { fill(it, masqueMode) }
            true
        }

        // editWarpApiHosts (dialog_manage_routes.cpp:51-81): shows the default host while empty, saves normalised.
        val hosts = pref<StringListPreference>(SettingsRegistry.WARP_API_HOSTS.key)
        hosts.dialogMessage = getString(R.string.warp_api_hosts_message, WarpClient.DEFAULT_API_HOST)
        hosts.summaryProvider = Preference.SummaryProvider<StringListPreference> {
            if (it.text.isNullOrBlank()) {
                getString(R.string.setting_default_value, WarpClient.DEFAULT_API_HOST)
            } else {
                LinesSummaryProvider(maxLines = 3).provideSummary(it)
            }
        }
        hosts.setOnBindEditTextListener { editText ->
            if (editText.text.isNullOrBlank()) editText.setText(WarpClient.DEFAULT_API_HOST)
            editText.setHorizontallyScrolling(true)
            editText.setSelection(editText.text.length)
        }
        hosts.setOnPreferenceChangeListener { _, newValue ->
            hosts.text = WarpClient.normalizeApiHosts(newValue as String?).joinToString("\n")
            false
        }
    }

    /** The fields of the generated mode (dialog_manage_routes.cpp:480-495), saved at once. */
    private fun fill(identity: WarpClient.Identity, masque: Boolean) {
        val addresses = identity.addresses.joinToString("\n")
        if (masque) {
            setText(SettingsRegistry.WARP_MASQUE_PRIVATE_KEY, identity.privateKey)
            setText(SettingsRegistry.WARP_MASQUE_PEER_PUBLIC_KEY, identity.peerPublicKey)
            setText(SettingsRegistry.WARP_MASQUE_EP, identity.endpoint)
            setText(SettingsRegistry.WARP_MASQUE_IFC_ADDRS, addresses)
        } else {
            setText(SettingsRegistry.WARP_PRIVATE_KEY, identity.privateKey)
            setText(SettingsRegistry.WARP_PUBLIC_KEY, identity.peerPublicKey)
            setText(SettingsRegistry.WARP_EP, identity.endpoint)
            setText(SettingsRegistry.WARP_IFC_ADDRS, addresses)
            setText(SettingsRegistry.WARP_RESERVED, identity.reserved.joinToString("\n"))
        }
        reloadIfEnabled()
    }

    /** generate_amnezia_packets (dialog_manage_routes.cpp): picking the amnezia mode without packets makes them. */
    private fun generateAmneziaPackets() {
        if (amneziaPending) return
        amneziaPending = true
        validation.refresh()
        lifecycleScope.launch {
            val result = WarpClient.generateCamouflage()
            amneziaPending = false
            packetsGenerated(result)
            if (result.isSuccess && DataStore.warpMode == WarpClient.MODE_AMNEZIA) reloadIfEnabled()
        }
    }

    /** New packets are saved at once; a failure keeps the old ones and is shown on the mode row. */
    private fun packetsGenerated(result: Result<List<String>>) {
        result.onSuccess { DataStore.warpAmneziaPackets = it }
        amneziaError = result.exceptionOrNull()?.readableMessage.orEmpty()
        validation.refresh()
    }

    private fun setText(setting: SettingsRegistry.Setting<*>, value: String) {
        pref<EditTextPreference>(setting.key).text = value
    }

    private fun reloadIfEnabled() {
        if (DataStore.enableWarp) needReload()
    }

    private fun trimmedText(key: String) {
        val preference = pref<EditTextPreference>(key)
        preference.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty()
            val value = raw.trim()
            if (value != preference.text) reloadIfEnabled()
            if (value != raw) {
                preference.text = value
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    /** One item per line; commas separate items too, as in the desktop's single-line fields. */
    private fun itemList(key: String) {
        val preference = pref<StringListPreference>(key)
        preference.setOnPreferenceChangeListener { _, newValue ->
            val text = newValue?.toString().orEmpty().split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n")
            if (text != preference.text) {
                preference.text = text
                reloadIfEnabled()
            }
            false
        }
    }

    private companion object {
        const val KEY_WIREGUARD = "warpWireGuard"
        const val KEY_MASQUE = "warpMasque"
        const val KEY_GENERATE = "warpGenerate"
    }
}

/**
 * The Settings › Routing row of the WARP screen, summarised as "On · WireGuard" or "Off"; the summary follows
 * changes made elsewhere on the screen (the route quick switch toggles WARP).
 */
class WarpEntryPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : Preference(context, attrs), OnPreferenceDataStoreChangeListener {

    override fun getSummary(): CharSequence = if (DataStore.enableWarp) {
        context.getString(R.string.warp_state_on, WarpClient.modeName(DataStore.warpMode))
    } else {
        context.getString(R.string.warp_state_off)
    }

    override fun onAttached() {
        super.onAttached()
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onDetached() {
        DataStore.configurationStore.unregisterChangeListener(this)
        super.onDetached()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == SettingsRegistry.ENABLE_WARP.key || key == SettingsRegistry.WARP_MODE.key) {
            runOnMainDispatcher { notifyChanged() }
        }
    }
}
