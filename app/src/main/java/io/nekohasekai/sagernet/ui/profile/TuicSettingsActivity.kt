package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Tuic
import io.nekohasekai.sagernet.outbound.types.ccAlgorithms
import io.nekohasekai.sagernet.ui.json.ProfileJson

class TuicSettingsActivity : BindingSettingsActivity<Tuic>() {

    override fun createEntity() = Tuic()
    override val preferencesResource = R.xml.tuic_preferences

    init {
        pbm.text("name")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("uuid")
        pbm.text("password")
        pbm.text("congestion_control")
        pbm.text("udp_relay_mode")
        pbm.bool("udp_over_stream")
        pbm.bool("zero_rtt_handshake")
        pbm.text("heartbeat")
        TlsBlock.bind(pbm)
        QuicBlock.bind(pbm)
        DialBlock.bindQuicOpener(pbm)
    }

    override fun Tuic.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        // edit_tuic.cpp: older builds saved translated item text ("BBR", "New Reno"); unknown values show the first item
        fun identifier(value: String, choices: List<String>): String {
            val text = value.trim().replace(' ', '_')
            return choices.firstOrNull { it.equals(text, ignoreCase = true) } ?: ""
        }
        DataStore.profileCacheStore.putString("congestion_control", identifier(congestion_control, ccAlgorithms))
        DataStore.profileCacheStore.putString("udp_relay_mode", identifier(udp_relay_mode, listOf("native", "quic")))
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        passwordSummary("password")
        TlsBlock.setup(this, mustTls = true)
        QuicBlock.setup(this)
    }

}
