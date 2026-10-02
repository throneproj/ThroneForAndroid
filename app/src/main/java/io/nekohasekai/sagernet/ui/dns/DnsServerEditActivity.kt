package io.nekohasekai.sagernet.ui.dns

import android.os.Bundle
import android.view.MenuItem
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DnsServerEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.settings.DefaultSummaryProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class DnsServerEditActivity : ThemedActivity(R.layout.layout_config_settings) {

    companion object {
        const val EXTRA_SERVER_ID = "server_id"
    }

    private var serverId: Long = 0
    private var server: DnsServerEntity? = null
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(if (serverId > 0) R.string.dns_server_edit else R.string.dns_server_add)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0L)
        onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { finish() }
        })

        lifecycleScope.launch {
            server = if (serverId > 0) withContext(Dispatchers.IO) { SagerDatabase.instance.dnsServerDao().load(serverId) } else null
            loaded = true
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, EditFragment(server ?: DnsServerEntity()))
                .commitNowAllowingStateLoss()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_apply -> { if (loaded) save(); true }
        R.id.action_delete -> { if (serverId > 0) delete(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun sendResult() {
        supportFragmentManager.setFragmentResult("refresh", Bundle())
    }

    private fun save() {
        val fragment = supportFragmentManager.findFragmentById(R.id.settings) as? EditFragment ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SagerDatabase.instance.dnsServerDao().save(fragment.toEntity(serverId)) }
            setResult(RESULT_OK)
            sendResult()
            finish()
        }
    }

    private fun delete() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dns_server_delete_title)
            .setPositiveButton(R.string.yes) { _, _ ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { SagerDatabase.instance.dnsServerDao().delete(serverId) }
                    setResult(RESULT_OK)
                    sendResult()
                    finish()
                }
            }
            .setNegativeButton(R.string.no, null)
            .show()
    }

    class EditFragment(private val initial: DnsServerEntity) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.dns_server_edit)
            findPreference<EditTextPreference>("tag")?.text = initial.tag
            findPreference<EditTextPreference>("address")?.text = initial.address
            findPreference<SimpleMenuPreference>("type")?.value = initial.type
            findPreference<EditTextPreference>("bind_interface")?.text = initial.bindInterface
            findPreference<EditTextPreference>("detour")?.text = initial.detour
            findPreference<SwitchPreference>("disable_cache")?.isChecked = initial.disableCache
            findPreference<SwitchPreference>("disable_expire")?.isChecked = initial.disableExpire
            findPreference<SwitchPreference>("ip_is_private")?.isChecked = initial.ipIsPrivate
            findPreference<EditTextPreference>("client_subnet")?.text = initial.clientSubnet
            findPreference<SwitchPreference>("reject_expired")?.isChecked = initial.rejectExpired
            findPreference<EditTextPreference>("tag")?.summaryProvider = DefaultSummaryProvider("")
            findPreference<EditTextPreference>("address")?.summaryProvider = DefaultSummaryProvider("")
            findPreference<EditTextPreference>("bind_interface")?.summaryProvider = DefaultSummaryProvider("")
            findPreference<EditTextPreference>("client_subnet")?.summaryProvider = DefaultSummaryProvider("")
        }

        fun toEntity(id: Long) = DnsServerEntity(
            id = id,
            tag = findPreference<EditTextPreference>("tag")?.text?.trim() ?: "",
            address = findPreference<EditTextPreference>("address")?.text?.trim() ?: "",
            type = findPreference<SimpleMenuPreference>("type")?.value ?: "udp",
            bindInterface = findPreference<EditTextPreference>("bind_interface")?.text?.trim() ?: "",
            detour = findPreference<EditTextPreference>("detour")?.text?.trim() ?: "",
            disableCache = findPreference<SwitchPreference>("disable_cache")?.isChecked ?: false,
            disableExpire = findPreference<SwitchPreference>("disable_expire")?.isChecked ?: false,
            ipIsPrivate = findPreference<SwitchPreference>("ip_is_private")?.isChecked ?: false,
            clientSubnet = findPreference<EditTextPreference>("client_subnet")?.text?.trim() ?: "",
            rejectExpired = findPreference<SwitchPreference>("reject_expired")?.isChecked ?: true,
        )
    }
}
