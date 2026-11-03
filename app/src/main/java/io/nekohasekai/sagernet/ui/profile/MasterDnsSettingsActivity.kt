package io.nekohasekai.sagernet.ui.profile

import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.MasterDns
import io.nekohasekai.sagernet.outbound.types.MasterDnsChecks
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Validation

/**
 * MasterDNS (edit_masterdns.cpp). Resolver IP lists have no Android counterpart, so only the typed lines are edited;
 * a desktop list id survives the save untouched.
 */
class MasterDnsSettingsActivity : BindingSettingsActivity<MasterDns>() {

    override fun createEntity() = MasterDns()
    override val preferencesResource = R.xml.masterdns_preferences

    init {
        pbm.text("name")
        pbm.text("domains")
        pbm.text("mode")
        pbm.text("encryption_method")
        pbm.text("encryption_key")
        pbm.text("resolvers.lines")
        pbm.text("balancing_strategy")
        for (key in COUNTS) pbm.int(key)
        pbm.text("upload_compression")
        pbm.text("download_compression")
        pbm.bool("base64_response")
        pbm.text("mtu_test_timeout")
        pbm.bool("mtu_auto_remove_low")
    }

    // edit_masterdns.cpp onStart: an empty mode or method opens as the default, an unset count as empty text.
    override fun MasterDns.writeUnboundToCache() {
        val store = DataStore.profileCacheStore
        store.putString("mode", mode.ifEmpty { "socks5" })
        store.putString("encryption_method", encryption_method.ifEmpty { "xor" })
        for (key in COUNTS) {
            if ((store.getString(key)?.toIntOrNull() ?: 0) <= 0) store.putString(key, "")
        }
    }

    override fun MasterDns.readUnboundFromCache() {
        mtu_test_timeout = mtu_test_timeout.trim()
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        numberInput(*COUNTS)
        passwordSummary("encryption_key")
        multilineInput("domains", "resolvers.lines")
        for (key in arrayOf("mode", "encryption_method", "balancing_strategy", "upload_compression", "download_compression")) {
            findPreference<ListPreference>(key)?.keepUnlistedValue()
        }
        // The desktop placeholders: MasterDnsVPN's defaults.
        for ((key, value) in DEFAULTS) {
            findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text.orEmpty().trim().ifEmpty { pref.context.getString(R.string.setting_default_value, value) }
            }
        }
    }

    override fun issueKey(field: String): String? = when (field) {
        "domain" -> "domains"
        "resolver_list" -> "resolvers.lines"
        else -> null
    }

    // Number text the binding would read as 0.
    override fun ValidationForm.bindValidation() {
        bind("packet_duplication", Validation.Rules.int(0, 10))
        bind("setup_packet_duplication", Validation.Rules.int(0, MasterDnsChecks.MAX_SETUP_DUPLICATION.toLong()))
        for (key in arrayOf("mtu_min_upload", "mtu_max_upload", "mtu_min_download", "mtu_max_download", "mtu_test_retries", "mtu_test_parallelism")) {
            bind(key, Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
        }
    }

    companion object {
        private val COUNTS = arrayOf(
            "packet_duplication", "setup_packet_duplication", "mtu_min_upload", "mtu_max_upload", "mtu_min_download",
            "mtu_max_download", "mtu_test_retries", "mtu_test_parallelism",
        )
        private val DEFAULTS = listOf(
            "packet_duplication" to "3", "setup_packet_duplication" to "4", "mtu_min_upload" to "38", "mtu_max_upload" to "150",
            "mtu_min_download" to "200", "mtu_max_download" to "4000", "mtu_test_retries" to "2", "mtu_test_timeout" to "2s",
            "mtu_test_parallelism" to "32",
        )
    }
}
