package io.nekohasekai.sagernet.ui.profile

import android.text.InputType
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.types.WireGuard
import io.nekohasekai.sagernet.outbound.types.WireGuardChecks
import io.nekohasekai.sagernet.scan.ScanProfiles
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.scan.ScannerUi
import io.nekohasekai.sagernet.ui.scan.WarpFinderActivity
import io.nekohasekai.sagernet.ui.warp.WarpClient
import io.nekohasekai.sagernet.ui.warp.WarpGenerate
import io.nekohasekai.sagernet.ui.warp.setFieldText
import io.nekohasekai.sagernet.validation.Validation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** WireGuard / AmneziaWG: the endpoint lives in `peer.*`, the interface in the top-level fields. */
class WireGuardSettingsActivity : BindingSettingsActivity<WireGuard>() {

    override fun createEntity() = WireGuard()
    override val preferencesResource = R.xml.wireguard_preferences

    init {
        pbm.text("name")
        pbm.text("peer.address")
        pbm.int("peer.port")
        pbm.text("address")
        pbm.text("private_key")
        pbm.text("peer.public_key")
        pbm.text("peer.pre_shared_key")
        pbm.text("peer.reserved")
        pbm.text("peer.persistent_keepalive")
        pbm.int("mtu")
        pbm.bool("system")
        pbm.int("worker_count")
        pbm.text("udp_timeout")
        pbm.bool("enable_amnezia")
        for (field in listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4")) pbm.int(field)
        for (field in listOf(
            "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5", "header_protection_key", "content_padding_addition",
            "rekey_after_time", "rekey_timeout", "reject_after_time", "keepalive_timeout", "max_handshake_attempts",
        )) pbm.text(field)
        pbm.bool("random_trailers")
        pbm.bool("disable_cookies")
    }

    /** edit_wireguard.cpp onEnd: reserved and addresses take commas as well as lines, bare addresses get /32 or /128. */
    override fun WireGuard.serialize() {
        pbm.fromCacheAll(this)
        WireGuardChecks.wireguardReserved(reservedText(DataStore.profileCacheStore.getString("peer.reserved").orEmpty()), peer.reserved)
        address = address.flatMapTo(ArrayList()) { it.split(',') }
        fixAddress()
    }

    private fun reservedText(text: String) = text.replace('\n', ',')

    override fun issueKey(field: String): String? = when (field) {
        "server" -> "peer.address"
        "server_port" -> "peer.port"
        "public_key", "pre_shared_key", "reserved" -> "peer.$field"
        "persistent_keepalive_interval" -> "peer.persistent_keepalive"
        else -> null
    }

    /**
     * edit_wireguard.cpp / edit_wireguard_amnezia.cpp bindValidation, for the text the bindings lose: reserved bytes
     * and integers they would turn into 0. WireGuard.validate covers the rest, Amnezia rows included.
     */
    override fun ValidationForm.bindValidation() {
        val intMax = Int.MAX_VALUE.toLong()
        bind("peer.reserved") { WireGuardChecks.wireguardReserved(reservedText(it)) }
        for (key in listOf("mtu", "worker_count", "jc", "jmin", "jmax")) bind(key, Validation.Rules.int(0, intMax))
        for (key in listOf("s1", "s2", "s3", "s4")) bind(key, Validation.Rules.int(0, 65535))
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        portInput("peer.port")
        numberInput("mtu", "worker_count", "jc", "jmin", "jmax", "s1", "s2", "s3", "s4")
        passwordSummary("private_key", "peer.pre_shared_key")
        multilineInput("address", "peer.reserved")
        // stored one per line, shown on one
        findPreference<EditTextPreference>("peer.reserved")?.summaryProvider =
            Preference.SummaryProvider<EditTextPreference> { pref ->
                pref.text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
                    .ifEmpty { getString(androidx.preference.R.string.not_set) }
            }

        onSwitch("enable_amnezia") { on -> showAmnezia(this, on) }

        // edit_wireguard.cpp:39-51
        WarpGenerate.bindEditorRow(
            this@WireGuardSettingsActivity, this, WarpClient.TUNNEL_WIREGUARD, R.string.warp_wireguard_profile_name,
        ) { identity ->
            setFieldText("private_key", identity.privateKey)
            setFieldText("peer.public_key", identity.peerPublicKey)
            setFieldText("address", identity.addresses.joinToString("\n"))
            setFieldText("mtu", "1280")
            setFieldText("peer.persistent_keepalive", "30")
            identity.endpointHostPort()?.let { (host, port) ->
                setFieldText("peer.address", host)
                setFieldText("peer.port", port)
            }
            setFieldText("peer.reserved", identity.reserved.joinToString("\n"))
        }

        findPreference<Preference>(KEY_QUIC_SIGNATURE)?.setOnPreferenceClickListener { button ->
            askSignatureDomain(this, button)
            true
        }

        findPreference<Preference>(KEY_WARP_FIND)?.setOnPreferenceClickListener {
            ScannerUi.findWarpEndpoint(this@WireGuardSettingsActivity, warpFinder) { validationOutbound() }
            true
        }
        updateWarpFind(this)
    }

    private fun showAmnezia(screen: PreferenceFragmentCompat, on: Boolean) {
        val amnezia = screen.findPreference<PreferenceCategory>("amneziaCategory") ?: return
        for (i in 0 until amnezia.preferenceCount) {
            val child = amnezia.getPreference(i)
            if (child.key != "enable_amnezia") child.isVisible = on
        }
    }

    private fun currentScreen() = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key == "peer.public_key" || key == "peer.address") runOnUiThread { currentScreen()?.let { updateWarpFind(it) } }
    }

    // edit_wireguard.cpp updateWarpFind
    private fun updateWarpFind(screen: PreferenceFragmentCompat) {
        val row = screen.findPreference<Preference>(KEY_WARP_FIND) ?: return
        fun text(key: String) = screen.findPreference<EditTextPreference>(key)?.text.orEmpty().trim()
        val warp = ScanProfiles.isWarpPeerKey(text("peer.public_key")) || text("peer.address").endsWith(WARP_HOST_SUFFIX, ignoreCase = true)
        row.isEnabled = warp
        row.setSummary(if (warp) R.string.scan_finder_sum else R.string.scan_finder_sum_not_warp)
    }

    private val warpFinder = registerForActivityResult(WarpFinderActivity.Contract()) { choice ->
        val screen = currentScreen()
        if (choice != null && screen != null) applyWarpEndpoint(screen, choice)
    }

    // edit_wireguard.cpp applyWarpEndpoint
    private fun applyWarpEndpoint(screen: PreferenceFragmentCompat, choice: WarpFinderActivity.Choice) {
        screen.setFieldText("peer.address", choice.entry.cidr)
        if (choice.entry.port > 0) screen.setFieldText("peer.port", choice.entry.port.toString())
        val amnezia = screen.findPreference<TwoStatePreference>("enable_amnezia")
        if (choice.junk && amnezia != null && !amnezia.isChecked) {
            // The rows take the junk as Amnezia Options writes it; the rest of the AmneziaWG set is cleared.
            val junk = WireGuard().also { ScanProfiles.applyWarpJunk(it) }
            for ((key, value) in listOf(
                "jc" to junk.jc, "jmin" to junk.jmin, "jmax" to junk.jmax, "s1" to junk.s1, "s2" to junk.s2, "s3" to junk.s3,
                "s4" to junk.s4,
            )) screen.setFieldText(key, value.toString())
            for ((key, value) in listOf(
                "h1" to junk.h1, "h2" to junk.h2, "h3" to junk.h3, "h4" to junk.h4, "i1" to junk.i1, "i2" to junk.i2,
                "i3" to junk.i3, "i4" to junk.i4, "i5" to junk.i5, "header_protection_key" to junk.header_protection_key,
                "content_padding_addition" to junk.content_padding_addition, "rekey_after_time" to junk.rekey_after_time,
                "rekey_timeout" to junk.rekey_timeout, "reject_after_time" to junk.reject_after_time,
                "keepalive_timeout" to junk.keepalive_timeout, "max_handshake_attempts" to junk.max_handshake_attempts,
            )) screen.setFieldText(key, value)
            screen.findPreference<TwoStatePreference>("random_trailers")?.isChecked = junk.random_trailers
            screen.findPreference<TwoStatePreference>("disable_cookies")?.isChecked = junk.disable_cookies
            amnezia.isChecked = true
            showAmnezia(screen, true)
            form?.refresh()
        }
        if (choice.ownAddress) useOwnEndpoint()
    }

    // edit_wireguard_amnezia.cpp generateSignature
    private fun askSignatureDomain(screen: PreferenceFragmentCompat, button: Preference) {
        val layout = TextInputLayout(this).apply { hint = getString(R.string.amnezia_quic_signature_domain) }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            setText(lastSignatureDomain)
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.amnezia_quic_signature_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val domain = input.text?.toString()?.trim().orEmpty()
                if (domain.isNotEmpty()) {
                    lastSignatureDomain = domain
                    generateSignature(screen, button, domain)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateSignature(screen: PreferenceFragmentCompat, button: Preference, domain: String) {
        val idle = button.summary
        button.isEnabled = false
        button.summary = getString(R.string.generating)
        lifecycleScope.launch {
            val raw = try {
                withContext(Dispatchers.IO) { CoreServiceClient.call { it.genQuicSignature(domain) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            button.summary = idle
            button.isEnabled = true
            val result = raw?.let { JsonInput.parseObjectOrNull(it) }
            val packets = result?.array("packets")?.strings().orEmpty()
            val error = if (result == null) getString(R.string.warp_core_unreachable) else result.string("error")
            if (error.isNotEmpty() || packets.isEmpty()) {
                MaterialAlertDialogBuilder(this@WireGuardSettingsActivity)
                    .setTitle(R.string.amnezia_quic_signature_failed)
                    .apply { if (error.isNotEmpty()) setMessage(error) }
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@launch
            }
            // An "Edit as JSON" round trip may have rebuilt the screen meanwhile.
            val target = supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat ?: screen
            SIGNATURE_FIELDS.forEachIndexed { i, key -> target.setFieldText(key, packets.getOrElse(i) { "" }) }
        }
    }

    private companion object {
        const val KEY_QUIC_SIGNATURE = "amneziaQuicSignature"
        const val KEY_WARP_FIND = "warpFind"
        const val WARP_HOST_SUFFIX = ".cloudflareclient.com"
        val SIGNATURE_FIELDS = listOf("i1", "i2", "i3", "i4", "i5")

        // Offered again next time, like the desktop's lastSignatureDomain.
        var lastSignatureDomain = ""
    }

}
