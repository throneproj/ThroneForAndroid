package io.nekohasekai.sagernet.ui.settings

import android.content.Intent
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ui.AppManagerActivity
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.route.RouteQuickSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.EditConfigPreference
import kotlin.math.abs

/** The mixed inbound (Basic Settings › Inbound Settings) plus the Android HTTP proxy bypass list. */
class InboundSettingsFragment : SettingsScreenFragment(R.xml.settings_inbound) {

    override fun beforeInflate() {
        DataStore.initGlobal()
    }

    override fun bind() {
        val port = pref<EditTextPreference>(SettingsRegistry.INBOUND_SOCKS_PORT.key)
        val randomPort = pref<SwitchPreference>(SettingsRegistry.RANDOM_INBOUND_PORT.key)
        val allowLan = pref<SwitchPreference>(KEY_ALLOW_LAN)
        val auth = pref<SwitchPreference>(SettingsRegistry.INBOUND_AUTH.key)
        val user = pref<EditTextPreference>(SettingsRegistry.INBOUND_USER.key)
        val pass = pref<EditTextPreference>(SettingsRegistry.INBOUND_PASS.key)
        val httpProxyBypass = pref<EditTextPreference>(Key.HTTP_PROXY_BYPASS)

        checkText(port.key, R.string.invalid_port, valid = SettingValidators::isPort)
        port.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        pass.summaryProvider = GroupSettingsActivity.PasswordSummaryProvider
        httpProxyBypass.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        httpProxyBypass.summaryProvider = LinesSummaryProvider(maxLines = 1)
        pref<EditConfigPreference>(SettingsRegistry.CUSTOM_INBOUND.key).useConfigStore(SettingsRegistry.CUSTOM_INBOUND.key)

        allowLan.isChecked = DataStore.allowLanAccess
        allowLan.setOnPreferenceChangeListener { _, newValue ->
            DataStore.inboundAddress = if (newValue as Boolean) SettingsRegistry.LAN_ADDRESS else SettingsRegistry.LOOPBACK_ADDRESS
            needReload()
            true
        }

        fun updateMixedState(disabled: Boolean) {
            for (p in listOf(port, randomPort, allowLan, auth, user, pass, httpProxyBypass)) p.isEnabled = !disabled
            if (disabled) {
                port.summaryProvider = null
                port.summary = getString(R.string.mixed_inbound_disabled)
            } else {
                port.summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
            }
            user.isEnabled = !disabled && auth.isChecked
            pass.isEnabled = !disabled && auth.isChecked
        }
        updateMixedState(DataStore.disableMixedInbound)
        pref<SwitchPreference>(SettingsRegistry.DISABLE_MIXED_INBOUND.key).setOnPreferenceChangeListener { _, newValue ->
            val disabled = newValue as Boolean
            if (disabled && DataStore.serviceMode == Key.MODE_PROXY) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.disable_mixed_inbound_proxy_toast, DataStore.inboundSocksPort),
                    Toast.LENGTH_LONG
                ).show()
            }
            updateMixedState(disabled)
            needReload()
            true
        }
        auth.setOnPreferenceChangeListener { _, newValue ->
            user.isEnabled = newValue as Boolean
            pass.isEnabled = newValue
            needReload()
            true
        }
        reloadOn(randomPort.key, user.key, pass.key, httpProxyBypass.key)
    }

    private companion object {
        const val KEY_ALLOW_LAN = "inboundAllowLan"
    }
}

/** Tun Settings: stack, MTU, IPv6, tun routing, private range bypass, per-app proxy and the tun addresses. */
class TunSettingsFragment : SettingsScreenFragment(R.xml.settings_tun) {

    private lateinit var proxyApps: SwitchPreference

    override fun bind() {
        reloadOn(
            SettingsRegistry.VPN_MTU.key, SettingsRegistry.VPN_IPV6.key,
            SettingsRegistry.ENABLE_TUN_ROUTING.key,
        )

        val ranges = pref<StringListPreference>(SettingsRegistry.VPN_PRIVATE_RANGES.key)
        val bypass = pref<SwitchPreference>(KEY_BYPASS_PRIVATE_RANGES)
        bypass.isChecked = !DataStore.disablePrivateRangeBypass
        ranges.isEnabled = bypass.isChecked
        bypass.setOnPreferenceChangeListener { _, newValue ->
            DataStore.disablePrivateRangeBypass = !(newValue as Boolean)
            ranges.isEnabled = newValue
            needReload()
            true
        }
        ranges.setOnPreferenceChangeListener { _, newValue ->
            val bad = SettingValidators.lines(newValue as String?).firstOrNull { !SettingValidators.isPrivateRange(it) }
            if (bad != null) {
                toast(R.string.invalid_private_range, bad)
                return@setOnPreferenceChangeListener false
            }
            needReload()
            true
        }
        pref<Preference>(KEY_RESTORE_RANGES).setOnPreferenceClickListener {
            ranges.text = SettingsRegistry.DEFAULT_PRIVATE_RANGES.joinToString("\n")
            needReload()
            true
        }

        val ipv4 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV4_CIDR.key)
        val ipv6 = pref<EditTextPreference>(SettingsRegistry.VPN_TUN_IPV6_CIDR.key)
        checkText(ipv4.key, R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = false) }
        checkText(ipv6.key, R.string.invalid_cidr) { SettingValidators.isCidr(it, ipv6 = true) }
        pref<Preference>(KEY_RESTORE_ADDRESSES).setOnPreferenceClickListener {
            ipv4.text = SettingsRegistry.DEFAULT_TUN_IPV4_CIDR
            ipv6.text = SettingsRegistry.DEFAULT_TUN_IPV6_CIDR
            needReload()
            true
        }

        proxyApps = pref(Key.PROXY_APPS)
        proxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }
    }

    override fun onResume() {
        super.onResume()
        if (::proxyApps.isInitialized) proxyApps.isChecked = DataStore.proxyApps
    }

    private companion object {
        const val KEY_BYPASS_PRIVATE_RANGES = "tunBypassPrivateRanges"
        const val KEY_RESTORE_RANGES = "tunRestoreRanges"
        const val KEY_RESTORE_ADDRESSES = "tunRestoreAddresses"
    }
}

/**
 * Routing Settings › Common (domain strategies, rule-set mirror), AdBlock and the remote route profile auto update;
 * the route profiles themselves live on the Route screen.
 */
class RoutingSettingsFragment : SettingsScreenFragment(R.xml.settings_routing) {

    override fun bind() {
        pref<Preference>(KEY_ROUTE_PROFILES).setOnPreferenceClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_route)
            true
        }
        pref<Preference>(KEY_CURRENT_ROUTE).setOnPreferenceClickListener {
            RouteQuickSwitch.show(this) { showCurrentRoute() }
            true
        }

        reloadOn(
            SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY.key, SettingsRegistry.DOMAIN_STRATEGY.key,
            SettingsRegistry.RULESET_MIRROR.key, SettingsRegistry.ADBLOCK_ENABLE.key,
        )

        val enabled = pref<SwitchPreference>(KEY_AUTO_UPDATE)
        val interval = pref<EditTextPreference>(KEY_AUTO_UPDATE_INTERVAL)
        fun minutes() = abs(DataStore.routeAutoUpdate).takeIf { it > 0 } ?: -SettingsRegistry.ROUTE_AUTO_UPDATE.default

        enabled.isChecked = DataStore.routeAutoUpdate > 0
        interval.text = minutes().toString()
        interval.isEnabled = enabled.isChecked
        interval.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            getString(R.string.auto_update_interval_sum, minutes())
        }
        interval.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)

        enabled.setOnPreferenceChangeListener { _, newValue ->
            val on = newValue as Boolean
            DataStore.routeAutoUpdate = if (on) minutes() else -minutes()
            interval.isEnabled = on
            RemoteRouteUpdater.schedule()
            true
        }
        interval.setOnPreferenceChangeListener { _, newValue ->
            val value = newValue?.toString()?.trim()?.toIntOrNull()
            if (value == null || value < SettingsRegistry.MIN_AUTO_UPDATE_MINUTES) {
                toast(R.string.auto_update_interval_invalid, SettingsRegistry.MIN_AUTO_UPDATE_MINUTES)
                return@setOnPreferenceChangeListener false
            }
            DataStore.routeAutoUpdate = if (enabled.isChecked) value else -value
            RemoteRouteUpdater.schedule()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        showCurrentRoute()
    }

    private fun showCurrentRoute() {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { RouteManager.current().name }
            findPreference<Preference>(KEY_CURRENT_ROUTE)?.summary = name
        }
    }

    private companion object {
        const val KEY_AUTO_UPDATE = "routeAutoUpdateEnabled"
        const val KEY_AUTO_UPDATE_INTERVAL = "routeAutoUpdateInterval"
        const val KEY_ROUTE_PROFILES = "routeProfiles"
        const val KEY_CURRENT_ROUTE = "currentRouteProfile"
    }
}
