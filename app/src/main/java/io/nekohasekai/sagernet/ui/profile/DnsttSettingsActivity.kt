package io.nekohasekai.sagernet.ui.profile

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.common.DnsTunnelChecks
import io.nekohasekai.sagernet.outbound.types.Dnstt
import io.nekohasekai.sagernet.outbound.types.DnsttChecks
import io.nekohasekai.sagernet.outbound.types.dnsTunnelNumberText
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr

/**
 * DNSTT and VayDNS (edit_dnstt.cpp): the mode menu folds mode and dnstt_compat into one choice and shows the VayDNS
 * rows, the backend menu the rows of the service behind the tunnel. Resolver IP lists have no Android counterpart, so
 * only the typed lines are edited; a desktop list id survives the save untouched.
 */
class DnsttSettingsActivity : BindingSettingsActivity<Dnstt>() {

    override fun createEntity() = Dnstt()
    override val preferencesResource = R.xml.dnstt_preferences

    init {
        pbm.text("name")
        pbm.text("domain")
        pbm.text("public_key")
        pbm.text("record_type")
        pbm.int("client_id_size")
        pbm.int("max_qname_len")
        pbm.text("resolvers.lines")
        pbm.text("resolver_strategy")
        pbm.bool("tcp_fallback")
        pbm.text("backend")
        pbm.text("socks_username")
        pbm.text("socks_password")
        pbm.bool("socks_udp_over_tcp")
        pbm.text("ss_method")
        pbm.text("ss_password")
        pbm.bool("ss_udp_over_tcp")
        pbm.text("ssh_user")
        pbm.text("ssh_password")
        pbm.text("ssh_private_key")
        pbm.text("ssh_private_key_passphrase")
        pbm.text("ssh_host_key")
        pbm.text("ssh_host_key_algorithms")
        pbm.text("ssh_client_version")
        pbm.text("idle_timeout")
        pbm.text("keepalive_interval")
    }

    private val socksRows = arrayOf("socks_username", "socks_password", "socks_udp_over_tcp")
    private val shadowsocksRows = arrayOf("ss_method", "ss_password", "ss_udp_over_tcp")
    private val sshRows = arrayOf(
        "ssh_user", "ssh_password", "ssh_private_key", "ssh_private_key_passphrase", "ssh_host_key", "ssh_host_key_algorithms",
        "ssh_client_version",
    )

    /** The mode menu's value: [MODE_CLASSIC], [MODE_VAYDNS] or [MODE_COMPAT]; set before the menu persists it. */
    private var selectedMode = MODE_CLASSIC
    private var payloadRow: Preference? = null
    private var defaultRows: List<Preference> = emptyList()

    private fun native() = selectedMode == MODE_VAYDNS

    override fun Dnstt.writeUnboundToCache() {
        val store = DataStore.profileCacheStore
        store.putString(KEY_MODE, if (mode != "vaydns") MODE_CLASSIC else if (dnstt_compat) MODE_COMPAT else MODE_VAYDNS)
        store.putString(KEY_RPS, if (rps > 0) dnsTunnelNumberText(rps) else "")
        for (key in arrayOf("client_id_size", "max_qname_len")) {
            if ((store.getString(key)?.toIntOrNull() ?: 0) <= 0) store.putString(key, "")
        }
    }

    // edit_dnstt.cpp onEnd: the rows a mode hides are cleared, and a rate that is not a positive number is none.
    override fun Dnstt.readUnboundFromCache() {
        val store = DataStore.profileCacheStore
        val selected = store.getString(KEY_MODE).orEmpty()
        mode = if (selected == MODE_VAYDNS || selected == MODE_COMPAT) "vaydns" else "dnstt"
        dnstt_compat = selected == MODE_COMPAT
        if (selected != MODE_VAYDNS) {
            record_type = ""
            client_id_size = 0
        }
        if (mode != "vaydns") max_qname_len = 0
        domain = domain.trim()
        public_key = public_key.trim()
        idle_timeout = idle_timeout.trim()
        keepalive_interval = keepalive_interval.trim()
        socks_username = socks_username.trim()
        ss_method = ss_method.trim()
        ssh_user = ssh_user.trim()
        ssh_client_version = ssh_client_version.trim()
        // QString::toDouble: the row shows QString::number, which writes small and large rates with an exponent.
        val text = store.getString(KEY_RPS).orEmpty().trim().removeSuffix(".")
        val rate = if (RPS_TEXT.matches(text)) text.toDoubleOrNull() else null
        rps = if (rate != null && rate.isFinite() && rate > 0) rate else 0.0
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        numberInput("client_id_size", "max_qname_len")
        findPreference<EditTextPreference>(KEY_RPS)?.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            editText.isSingleLine = true
            editText.setSelection(editText.text.length)
        }
        passwordSummary("socks_password", "ss_password", "ssh_password", "ssh_private_key_passphrase")
        multilineInput("resolvers.lines", "ssh_private_key", "ssh_host_key", "ssh_host_key_algorithms")
        for (key in arrayOf("record_type", "resolver_strategy", "backend", "ss_method")) {
            findPreference<ListPreference>(key)?.keepUnlistedValue()
        }

        // The desktop placeholders: the core's defaults for the mode.
        defaultRows = listOfNotNull(
            defaultSummary("client_id_size") { "2" },
            defaultSummary("max_qname_len") { if (native()) "101" else "253" },
            defaultSummary("idle_timeout") { if (native()) "10s" else "2m" },
            defaultSummary("keepalive_interval") { if (native()) "2s" else "10s" },
            defaultSummary(KEY_RPS) { null },
        )
        payloadRow = findPreference("payload")

        onMenu(KEY_MODE) { selected ->
            selectedMode = selected.ifEmpty { MODE_CLASSIC }
            setVisible(native(), "record_type", "client_id_size")
            setVisible(selectedMode != MODE_CLASSIC, "max_qname_len")
            for (row in defaultRows) row.refreshSummary()
            updatePayload()
        }
        onMenu("backend") { backend ->
            setVisible(backend == "socks", *socksRows)
            setVisible(backend == "shadowsocks", *shadowsocksRows)
            setVisible(backend == "ssh", *sshRows)
        }
    }

    /** Empty text shows the core's default, which [value] gives for the current mode (null: no number to show). */
    private fun PreferenceFragmentCompat.defaultSummary(key: String, value: () -> String?): Preference? {
        val row = findPreference<EditTextPreference>(key) ?: return null
        row.summaryProvider = Preference.SummaryProvider<EditTextPreference> { pref ->
            pref.text.orEmpty().trim().ifEmpty {
                val shown = value()
                if (shown == null) pref.context.getString(R.string.setting_default)
                else pref.context.getString(R.string.setting_default_value, shown)
            }
        }
        return row
    }

    // edit_dnstt.cpp updatePayload: the bytes each query carries, shown once the domain is usable.
    private fun updatePayload() {
        val row = payloadRow ?: return
        val store = DataStore.profileCacheStore
        val domain = store.getString("domain").orEmpty().trim()
        if (DnsTunnelChecks.dnsTunnelDomain(domain).isNotEmpty()) {
            row.summary = ""
            return
        }
        val mode = if (selectedMode == MODE_CLASSIC) "dnstt" else "vaydns"
        val qname = if (mode == "vaydns") QtStrings.toInt(store.getString("max_qname_len").orEmpty()) else 0
        val clientId = if (native()) QtStrings.toInt(store.getString("client_id_size").orEmpty()) else 0
        val mtu = DnsttChecks.dnsttMtu(mode, selectedMode == MODE_COMPAT, domain, qname, clientId)
        row.summary = getString(R.string.dnstt_payload_bytes, maxOf(mtu, 0).toString())
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key in PAYLOAD_INPUTS) updatePayload()
    }

    override fun issueKey(field: String): String? = when (field) {
        "dnstt_compat" -> KEY_MODE
        // dnstt::Validate files the missing-authentication warning under ssh_auth; edit_dnstt.cpp anchors it on the password.
        "ssh_auth" -> "ssh_password"
        "resolver_list" -> "resolvers.lines"
        else -> null
    }

    // Number text the binding would read as 0, and the rate the model only sees parsed.
    override fun ValidationForm.bindValidation() {
        bind("client_id_size", Validation.Rules.int(0, 253))
        bind("max_qname_len", Validation.Rules.int(0, 253))
        bind(KEY_RPS) { text -> if (text.isEmpty() || RPS_TEXT.matches(text)) "" else vtr(R.string.validation_must_be_number) }
    }

    companion object {
        private const val KEY_MODE = "mode"
        private const val KEY_RPS = "rps"
        private const val MODE_CLASSIC = "dnstt"
        private const val MODE_VAYDNS = "vaydns"
        private const val MODE_COMPAT = "vaydns_compat"
        private val PAYLOAD_INPUTS = setOf("domain", "max_qname_len", "client_id_size", KEY_MODE)
        private val RPS_TEXT = Regex("[0-9]+(\\.[0-9]*)?([eE][+-]?[0-9]+)?")
    }
}
