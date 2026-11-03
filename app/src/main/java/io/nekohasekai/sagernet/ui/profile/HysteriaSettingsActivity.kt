package io.nekohasekai.sagernet.ui.profile

import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.outbound.types.Hysteria
import io.nekohasekai.sagernet.outbound.types.hysteriaObfsTypes
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.json.ProfileJson
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr

/** Hysteria v1 and v2 on one screen; the version menu gates the version-specific fields and the v2 realm block. */
class HysteriaSettingsActivity : BindingSettingsActivity<Hysteria>() {

    override fun createEntity() = Hysteria().apply { protocol_version = "2" }
    override val preferencesResource = R.xml.hysteria_preferences

    init {
        pbm.text("name")
        pbm.text("protocol_version")
        pbm.text("server")
        pbm.int("serverPort")
        pbm.text("server_ports")
        pbm.text("hop_interval")
        pbm.text("hop_interval_max")
        pbm.text("auth_type")
        pbm.text("auth")
        pbm.text("password")
        pbm.text("obfs_type")
        pbm.text("obfs")
        pbm.int("up_mbps")
        pbm.int("down_mbps")
        pbm.int("recv_window_conn")
        pbm.int("recv_window")
        pbm.bool("disable_mtu_discovery")
        pbm.int("min_packet_size")
        pbm.int("max_packet_size")
        pbm.text("bbr_profile")
        pbm.bool("disable_chrome_parrot")
        pbm.bool("realm_enabled")
        pbm.text("realm_server_url")
        pbm.text("realm_token")
        pbm.text("realm_id")
        pbm.text("realm_stun_servers")
        pbm.int("realm_ip_version")
        pbm.bool("realm_port_mapping")
        pbm.text("realm_port_mapping_timeout")
        pbm.text("realm_port_mapping_lifetime")
        TlsBlock.bind(pbm)
        QuicBlock.bind(pbm)
    }

    private val v1Only = arrayOf("auth_type", "auth", "recv_window_conn", "recv_window", "disable_mtu_discovery")
    private val v2Only = arrayOf("password", "obfs_type", "hop_interval_max", "bbr_profile", "disable_chrome_parrot")
    private val realmFields = arrayOf(
        "realm_server_url", "realm_token", "realm_id", "realm_stun_servers", "realm_ip_version", "realm_port_mapping",
        "realm_port_mapping_timeout", "realm_port_mapping_lifetime",
    )

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("serverPort")
        numberInput("up_mbps", "down_mbps", "recv_window_conn", "recv_window", "min_packet_size", "max_packet_size")
        passwordSummary("auth", "password", "obfs", "realm_token")
        multilineInput("server_ports", "realm_stun_servers")

        val realm = findPreference<PreferenceCategory>("hysteriaRealmCategory")
        // edit_hysteria.cpp editHysteriaLayout: v2 hides the password under None and shows the sizes only for gecko
        fun applyObfs(version: String, type: String) {
            val v2 = version == "2"
            setVisible(!v2 || type.isNotEmpty(), "obfs")
            setVisible(v2 && type == "gecko", "min_packet_size", "max_packet_size")
        }
        fun applyVersion(version: String) {
            val v2 = version == "2"
            setVisible(!v2, *v1Only)
            setVisible(v2, *v2Only)
            realm?.isVisible = v2
            applyObfs(version, cached("obfs_type"))
        }
        onMenu("protocol_version") { applyVersion(it) }
        onMenu("obfs_type") { applyObfs(cached("protocol_version"), it) }
        onMenu("auth_type") { setVisible(it.isNotEmpty(), "auth") }
        onSwitch("realm_enabled") { setVisible(it, *realmFields) }

        TlsBlock.setup(this, mustTls = true)
        QuicBlock.setup(this)
    }

    override fun Hysteria.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, ProfileJson.text(this))
        // edit_hysteria.cpp: without a password no obfs reaches the core, so that opens as None too; otherwise the
        // type is matched ignoring case and an unknown one shows the first type
        val obfsType = obfs_type.trim()
        DataStore.profileCacheStore.putString(
            "obfs_type",
            if (obfs.isEmpty() || obfsType.isEmpty()) ""
            else hysteriaObfsTypes.firstOrNull { it.equals(obfsType, ignoreCase = true) } ?: hysteriaObfsTypes[0],
        )
    }

    private fun cached(key: String) = DataStore.profileCacheStore.getString(key)?.trim().orEmpty()

    private fun isV2() = cached("protocol_version") == "2"

    private fun gecko() = isV2() && cached("obfs_type").equals("gecko", ignoreCase = true)

    override fun issueKey(field: String): String? = when (field) {
        // the auth row is hidden until a type is chosen
        "auth" -> if (cached("auth_type").isEmpty()) "auth_type" else null
        "obfs.type" -> "obfs_type"
        "obfs.min_packet_size" -> "min_packet_size"
        "realm" -> "realm_enabled"
        else -> null
    }

    // edit_hysteria.cpp and edit_hysteria_realm.cpp: number text the binding would read as 0, and the realm fields
    override fun ValidationForm.bindValidation() {
        for (key in listOf("up_mbps", "down_mbps", "recv_window_conn", "recv_window")) {
            bind(key, Validation.Rules.int(0, Int.MAX_VALUE.toLong()))
        }
        val packetSize = Validation.Rules.int(0, 2048)
        for (key in listOf("min_packet_size", "max_packet_size")) {
            // Validate already checks a readable size while gecko obfuscation uses it
            bind(key) { text ->
                if (gecko() && DataStore.profileCacheStore.getString("obfs").orEmpty().isNotEmpty() && text.toIntOrNull() != null) ""
                else packetSize(text)
            }
        }
        bind("obfs") { if (it.isEmpty()) vtr(R.string.validation_hysteria_obfs_password) else "" }
            .raw().warning().onlyIf { isV2() && cached("obfs_type").isNotEmpty() }
        bind("realm_server_url", Validation.Rules.url(listOf("http", "https")))
        bindLines("realm_stun_servers", Validation.Rules.hostPort(false))
        bind("realm_port_mapping_timeout", Validation.Rules.duration())
        bind("realm_port_mapping_lifetime", Validation.Rules.duration())
    }

}
