package io.nekohasekai.sagernet.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.TrustTunnel
import io.nekohasekai.sagernet.outbound.types.TrustTunnelChecks
import io.nekohasekai.sagernet.outbound.types.trustTunnelCongestionControls
import io.nekohasekai.sagernet.ui.json.ProfileJson

class TrustTunnelSettingsActivity : BindingSettingsActivity<TrustTunnel>() {

    override fun createEntity() = TrustTunnel()
    override val preferencesResource = R.xml.trusttunnel_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("quic")
        pbm.text("congestion_control")
        pbm.text("custom_sni")
        pbm.text("client_random")
        pbm.text("client_random_auth_key")
        pbm.bool("health_check")
        DialBlock.bindQuicOpener(pbm)
        TlsBlock.bind(pbm)
    }

    override fun TrustTunnel.serialize() {
        pbm.fromCacheAll(this)
        client_random_auth_key = client_random_auth_key.trim()
    }

    override fun TrustTunnel.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        // edit_trusttunnel.cpp: older builds saved translated item text ("CUBIC"); unknown values fall back to bbr, as in
        // sing-trusttunnel, and the menu stores the lower-case name
        val congestion = congestion_control.trim().replace(' ', '_')
        DataStore.profileCacheStore.putString(
            "congestion_control",
            trustTunnelCongestionControls.firstOrNull { it.isNotEmpty() && it.equals(congestion, ignoreCase = true) } ?: "bbr",
        )
    }

    /** The credentials issue goes to the missing field, a TLS conflict to the option that causes it (edit_trusttunnel.cpp). */
    override fun issueKey(field: String): String? = when (field) {
        "username" -> if (DataStore.profileCacheStore.getString("username").isNullOrBlank()) null else "password"
        "tls" -> tlsConflictKey()
        else -> null
    }

    private fun tlsConflictKey(): String {
        val outbound = validationOutbound()
        val tls = outbound.tls
        val keyed = outbound.client_random.isNotEmpty() || outbound.client_random_auth_key.isNotEmpty()
        val clientRandom = keyed && TrustTunnelChecks.trustTunnelTlsConflict(
            true, false, outbound.quic, tls.ech.enabled, tls.reality.enabled, tls.disable_sni,
            if (tls.utls.enabled) tls.utls.fingerPrint else "",
        ).isNotEmpty()
        return when {
            !clientRandom -> "custom_sni"
            outbound.client_random.isNotEmpty() -> "client_random"
            else -> "client_random_auth_key"
        }
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
        // QUIC dials through qtls, which takes no uTLS config (dialog_edit_profile.cpp:759-763); it is TLS 1.3 only,
        // where Go does not let the cipher suites be chosen
        onSwitch("quic") { quic ->
            for (key in arrayOf("tls.utls.fingerPrint", "tls.cipher_suites")) findPreference<Preference>(key)?.isEnabled = !quic
            setVisible(quic, "dial.quic_opener")
        }
    }

}
