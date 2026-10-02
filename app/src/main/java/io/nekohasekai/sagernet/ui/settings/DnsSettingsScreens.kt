package io.nekohasekai.sagernet.ui.settings

import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.needReload
import android.content.Intent
import io.nekohasekai.sagernet.ui.dns.DnsServerListActivity
import moe.matsuri.nb4a.ui.EditConfigPreference

/** Routing Settings › DNS: the simple settings, predefined answers and the custom DNS object. */
class DnsSettingsFragment : SettingsScreenFragment(R.xml.settings_dns) {

    override fun bind() {
        // DNS Server Management
        pref<Preference>("dnsServerManagement")?.setOnPreferenceClickListener {
            startActivity(Intent(context, DnsServerListActivity::class.java))
            true
        }
        // remote_dns and direct_dns removed; DNS servers are now managed per-route via dns_server
        checkText(SettingsRegistry.CORE_BOX_UNDERLYING_DNS.key, R.string.invalid_dns_address) { true }
        reloadOn(
            SettingsRegistry.DNS_FINAL_OUT.key, SettingsRegistry.ENABLE_DNS_ROUTING.key,
            SettingsRegistry.FAKEIP_DISABLE_IPV6.key, SettingsRegistry.DNS_USE_HOSTS.key,
        )
        pref<EditTextPreference>(SettingsRegistry.CORE_BOX_UNDERLYING_DNS.key).summaryProvider =
            DefaultSummaryProvider("local")

        // FakeIP Disable IPv6 only means something with FakeIP on (dialog_manage_routes.cpp:328-329).
        val fakeIpv6 = pref<SwitchPreference>(SettingsRegistry.FAKEIP_DISABLE_IPV6.key)
        val fakedns = pref<SwitchPreference>(SettingsRegistry.FAKEDNS.key)
        fakeIpv6.isEnabled = fakedns.isChecked
        fakedns.setOnPreferenceChangeListener { _, newValue ->
            fakeIpv6.isEnabled = newValue as Boolean
            needReload()
            true
        }

        val rules = pref<StringListPreference>(SettingsRegistry.DNS_PREDEFINED_RULES.key)
        val predefined = pref<SwitchPreference>(SettingsRegistry.DNS_PREDEFINED_ENABLE.key)
        rules.isEnabled = predefined.isChecked
        predefined.setOnPreferenceChangeListener { _, newValue ->
            rules.isEnabled = newValue as Boolean
            needReload()
            true
        }
        rules.setOnPreferenceChangeListener { _, newValue ->
            val lines = SettingValidators.lines(newValue as String?)
            if (!SettingValidators.isPredefinedDns(lines)) {
                val bad = lines.firstOrNull { !SettingValidators.isPredefinedDns(listOf(it)) } ?: ""
                toast(R.string.invalid_predefined_dns, bad)
                return@setOnPreferenceChangeListener false
            }
            needReload()
            true
        }

        // A custom DNS object replaces the whole section (dialog_manage_routes.cpp:345-351).
        val servers = pref<PreferenceCategory>(KEY_SERVERS)
        val behaviour = pref<PreferenceCategory>(KEY_BEHAVIOUR)
        val advanced = pref<Preference>(KEY_ADVANCED)
        val dnsObject = pref<EditConfigPreference>(SettingsRegistry.DNS_OBJECT.key)
        dnsObject.useConfigStore(SettingsRegistry.DNS_OBJECT.key)
        fun sync(useObject: Boolean) {
            servers.isEnabled = !useObject
            behaviour.isEnabled = !useObject
            advanced.isEnabled = !useObject
            dnsObject.isEnabled = useObject
        }
        val useObject = pref<SwitchPreference>(SettingsRegistry.USE_DNS_OBJECT.key)
        sync(useObject.isChecked)
        useObject.setOnPreferenceChangeListener { _, newValue ->
            sync(newValue as Boolean)
            needReload()
            true
        }
    }

    private companion object {
        const val KEY_SERVERS = "dnsServers"
        const val KEY_BEHAVIOUR = "dnsBehaviour"
        const val KEY_ADVANCED = "dnsAdvanced"
    }
}

/** Routing Settings › DNS › Advanced Settings (dialog_manage_routes.cpp:167-254). */
class DnsAdvancedSettingsFragment : SettingsScreenFragment(R.xml.settings_dns_advanced) {

    override fun bind() {
        checkInt(SettingsRegistry.DNS_CACHE_CAPACITY.key, R.string.invalid_number) { it >= 0 }
        checkText(SettingsRegistry.DNS_QUERY_TIMEOUT.key, R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        checkText(SettingsRegistry.DNS_OPTIMISTIC_TIMEOUT.key, R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        pref<EditTextPreference>(SettingsRegistry.DNS_QUERY_TIMEOUT.key).summaryProvider = DefaultSummaryProvider("10s")
        pref<EditTextPreference>(SettingsRegistry.DNS_OPTIMISTIC_TIMEOUT.key).summaryProvider = DefaultSummaryProvider("3d")
        reloadOn(SettingsRegistry.DNS_PERSIST_CACHE.key, SettingsRegistry.DNS_REVERSE_MAPPING.key)

        val optimistic = pref<SwitchPreference>(SettingsRegistry.DNS_OPTIMISTIC.key)
        val optimisticTimeout = pref<Preference>(SettingsRegistry.DNS_OPTIMISTIC_TIMEOUT.key)
        val disableCache = pref<SwitchPreference>(SettingsRegistry.DNS_DISABLE_CACHE.key)
        val disableExpire = pref<SwitchPreference>(SettingsRegistry.DNS_DISABLE_EXPIRE.key)
        val persistCache = pref<Preference>(SettingsRegistry.DNS_PERSIST_CACHE.key)

        // The core refuses optimistic with either cache switch, and a disabled cache never reaches the cache file.
        fun sync(optimisticOn: Boolean, cacheOff: Boolean, expireOff: Boolean) {
            if (cacheOff || expireOff) {
                if (optimistic.isChecked) optimistic.isChecked = false
                optimistic.isEnabled = false
            } else {
                optimistic.isEnabled = true
            }
            val optimisticNow = optimisticOn && !cacheOff && !expireOff
            optimisticTimeout.isEnabled = optimisticNow
            disableCache.isEnabled = !optimisticNow
            disableExpire.isEnabled = !optimisticNow
            persistCache.isEnabled = !cacheOff
        }
        sync(optimistic.isChecked, disableCache.isChecked, disableExpire.isChecked)
        optimistic.setOnPreferenceChangeListener { _, newValue ->
            sync(newValue as Boolean, disableCache.isChecked, disableExpire.isChecked)
            needReload()
            true
        }
        disableCache.setOnPreferenceChangeListener { _, newValue ->
            sync(optimistic.isChecked, newValue as Boolean, disableExpire.isChecked)
            needReload()
            true
        }
        disableExpire.setOnPreferenceChangeListener { _, newValue ->
            sync(optimistic.isChecked, disableCache.isChecked, newValue as Boolean)
            needReload()
            true
        }
    }
}
