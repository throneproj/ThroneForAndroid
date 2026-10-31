package io.nekohasekai.sagernet.ui.settings

import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DnsServerHistory
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingValidators
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.outbound.config.DnsServers
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Rule
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.EditConfigPreference

/** Routing Settings › DNS: the simple settings, predefined answers and the custom DNS object. */
class DnsSettingsFragment : SettingsScreenFragment(R.xml.settings_dns) {

    override fun bind() {
        // dialog_manage_routes.cpp's server rules.
        val server: Rule = { DnsServers.parseDnsAddress(it).why }
        gateText(SettingsRegistry.REMOTE_DNS.key, server, invalid = R.string.invalid_dns_address) { it.isNotEmpty() }
        gateText(SettingsRegistry.DIRECT_DNS.key, server, invalid = R.string.invalid_dns_address) { it.isNotEmpty() }
        pref<DnsServerPreference>(SettingsRegistry.REMOTE_DNS.key).suggestions = DnsServerHistory::remoteSuggestions
        pref<DnsServerPreference>(SettingsRegistry.DIRECT_DNS.key).suggestions = DnsServerHistory::directSuggestions
        rememberAccepted(SettingsRegistry.REMOTE_DNS.key, DnsServerHistory::rememberRemote)
        rememberAccepted(SettingsRegistry.DIRECT_DNS.key, DnsServerHistory::rememberDirect)
        gateText(SettingsRegistry.CORE_BOX_UNDERLYING_DNS.key, ::localDnsProblem, invalid = R.string.invalid_dns_address)
        reloadOn(
            SettingsRegistry.REMOTE_DNS_DISABLE_IPV6.key, SettingsRegistry.DIRECT_DNS_DISABLE_IPV6.key,
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

    /** DnsServerHistory::Remember for a server the screen took in place of another (dialog_manage_routes.cpp:635-640). */
    private fun rememberAccepted(key: String, remember: (String) -> Unit) {
        val preference = pref<EditTextPreference>(key)
        val check = preference.onPreferenceChangeListener
        preference.setOnPreferenceChangeListener { p, newValue ->
            val before = preference.text.orEmpty()
            val accepted = check?.onPreferenceChange(p, newValue) ?: true
            // A refused value leaves the text as it was; a trimmed one is stored by the check itself.
            val now = if (accepted) newValue?.toString().orEmpty() else preference.text.orEmpty()
            if (now != before) remember(now)
            accepted
        }
    }

    override fun onResume() {
        super.onResume()
        showRouteOverride()
    }

    /** The own DNS of the routing profile in effect replaces the servers and behaviour here (RouteDns::Current). */
    private fun showRouteOverride() {
        val note = findPreference<Preference>(KEY_ROUTE_OVERRIDE) ?: return
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                val id = RouteManager.activeRouteId()
                if (RouteManager.customDns(id) == null) null else SagerDatabase.routeDao.getProfile(id)?.name
            }
            note.summary = name?.let { getString(R.string.dns_route_override_note, it) }
            note.isVisible = name != null
        }
    }

    /** dns-local resolves the other servers' names, so it cannot depend on one itself. */
    private fun localDnsProblem(text: String): String {
        if (text.isEmpty()) return ""
        val parsed = DnsServers.parseDnsAddress(text)
        val address = parsed.address ?: return parsed.why
        if (address.type == "local" || address.type == "dhcp" || Validation.ip(address.server).isEmpty()) return ""
        return vtr(R.string.validation_dns_local_ip)
    }

    private companion object {
        const val KEY_SERVERS = "dnsServers"
        const val KEY_BEHAVIOUR = "dnsBehaviour"
        const val KEY_ADVANCED = "dnsAdvanced"
        const val KEY_ROUTE_OVERRIDE = "dnsRouteOverride"
    }
}

/** Routing Settings › DNS › Advanced Settings (dialog_manage_routes.cpp:167-254). */
class DnsAdvancedSettingsFragment : SettingsScreenFragment(R.xml.settings_dns_advanced) {

    override fun bind() {
        val capacity = SettingsRegistry.DNS_CACHE_CAPACITY.key
        gateInt(capacity, Validation.Rules.int(0, Int.MAX_VALUE.toLong(), Empty.Required))
        validation.bind(capacity) {
            val value = Validation.toInt(it)
            if (value != null && value in 1L..1023L) vtr(R.string.validation_dns_cache_small) else ""
        }.warning()
        for (key in listOf(SettingsRegistry.DNS_QUERY_TIMEOUT.key, SettingsRegistry.DNS_OPTIMISTIC_TIMEOUT.key)) {
            gateText(key, Validation.Rules.duration(), invalid = R.string.invalid_duration, valid = SettingValidators::isDurationOrEmpty)
        }
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
