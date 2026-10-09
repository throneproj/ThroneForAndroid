package io.nekohasekai.sagernet.ui.profile

import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Naive

class NaiveSettingsActivity : BindingSettingsActivity<Naive>() {

    override fun createEntity() = Naive()
    override val preferencesResource = R.xml.naive_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("username")
        pbm.text("password")
        pbm.bool("quic")
        pbm.text("congestion_control")
        pbm.text("extra_headers")
        pbm.int("insecure_concurrency")
        pbm.bool("uot")
        // naive reads only server name, certificates, ECH and the fragment flag of the TLS block
        pbm.text("tls.server_name")
        pbm.text("tls.certificate")
        pbm.tri("tls.fragment", "tls.fragment_unspecified")
        pbm.bool("tls.ech.enabled")
        pbm.text("tls.ech.config")
        pbm.text("tls.ech.serverName")
        pbm.text("tls.ech.resolver")
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("insecure_concurrency")
        passwordSummary("password")
        multilineInput("extra_headers", "tls.certificate", "tls.ech.config")
        echInputs("tls.ech")
        onSwitch("tls.ech.enabled") { setVisible(it, "tls.ech.config", "tls.ech.queryTarget") }
        // limited TLS: only the custom (dialer-level) fragment reaches naive (dialog_edit_profile.cpp:765)
        if (DataStore.fragmentImplementation == "custom") {
            presetTriSummary("tls.fragment", DataStore.fragmentDefaultOn)
        } else {
            findPreference<Preference>("tls.fragment")?.isEnabled = false
            fixedSummary("tls.fragment", R.string.preset_fragment_custom_only)
        }
    }

}
