package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.`import`.ImportReport
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.ScannerActivity
import io.nekohasekai.sagernet.ui.SubscribeFlows
import io.nekohasekai.sagernet.ui.SubscriptionReportDialog
import io.nekohasekai.sagernet.ui.profile.AnyTLSSettingsActivity
import io.nekohasekai.sagernet.ui.profile.AutoSelectorSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ChainSettingsActivity
import io.nekohasekai.sagernet.ui.profile.CustomSettingsActivity
import io.nekohasekai.sagernet.ui.profile.DirectSettingsActivity
import io.nekohasekai.sagernet.ui.profile.DnsttSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HttpSettingsActivity
import io.nekohasekai.sagernet.ui.profile.HysteriaSettingsActivity
import io.nekohasekai.sagernet.ui.profile.JuicitySettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasqueSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MasterDnsSettingsActivity
import io.nekohasekai.sagernet.ui.profile.MieruSettingsActivity
import io.nekohasekai.sagernet.ui.profile.NaiveSettingsActivity
import io.nekohasekai.sagernet.ui.profile.OpenConnectSettingsActivity
import io.nekohasekai.sagernet.ui.profile.OpenVpnSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SSHSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowTLSSettingsActivity
import io.nekohasekai.sagernet.ui.profile.ShadowsocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SnellSettingsActivity
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrojanSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TrustTunnelSettingsActivity
import io.nekohasekai.sagernet.ui.profile.TuicSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VMessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.VlessSettingsActivity
import io.nekohasekai.sagernet.ui.profile.WireGuardSettingsActivity
import io.nekohasekai.sagernet.ui.profile.XrayVlessSettingsActivity
import java.util.zip.ZipInputStream

/** The "+" menu: scan, clipboard, file and the new-profile editors. Imports go to the current tab's group. */
class ProfileImports(private val host: ConfigurationFragment) {

    private val importFile = host.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) importFile(uri)
    }

    /** Devices without a camera (TVs) have nothing to scan with. */
    fun prepare(menu: Menu) {
        menu.findItem(R.id.action_scan_qr_code)?.isVisible = hasCamera(host.requireContext())
    }

    private fun hasCamera(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_scan_qr_code -> host.startActivity(Intent(host.requireContext(), ScannerActivity::class.java))
            R.id.action_import_clipboard -> importClipboard()
            R.id.action_import_file -> host.startFilesForResult(importFile, "*/*")
            else -> {
                val editor = editorFor(item.itemId) ?: return false
                host.startActivity(Intent(host.requireContext(), editor))
            }
        }
        return true
    }

    /** import_or_handle_deeplink: links, documents, deep links and subscription URLs, into the current tab's group. */
    private fun importClipboard() {
        val text = SagerNet.getClipboardText()
        if (text.isBlank()) {
            host.snackbar(R.string.clipboard_empty).show()
        } else {
            SubscribeFlows.importText(host.requireActivity(), text, host.currentGroupId)
        }
    }

    /** importFromFiles for a text file; a zip (Android extra) holds one document per entry (WireGuard / OpenVPN). */
    private fun importFile(uri: Uri) {
        val groupId = host.currentGroupId
        val activity = host.requireActivity()
        val resolver = activity.contentResolver
        host.launchIo {
            try {
                val fileName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                if (fileName?.endsWith(".zip", true) != true) {
                    host.onUi { SubscribeFlows.importUri(activity, uri, groupId) }
                    return@launchIo
                }
                val outbounds = mutableListOf<Outbound>()
                val report = ImportReport()
                val preference = SettingsMapper.xrayVlessPreference()
                val ctx = SettingsMapper.buildContext()
                ZipInputStream(resolver.openInputStream(uri)!!).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (entry.isDirectory) continue
                        val result = ProfileImport.parse(zip.bufferedReader().readText(), preference, ctx)
                        for (message in result.messages) Logs.w(message)
                        report.addAll(result.reports)
                        outbounds.addAll(result.outbounds)
                        zip.closeEntry()
                    }
                }
                if (outbounds.isNotEmpty()) ProfileManager.addProfileBatch(outbounds, groupId)
                report.setImported(outbounds.size)
                host.onUi {
                    when {
                        // The import box already says how many were imported.
                        report.hasProblems() ->
                            SubscriptionReportDialog.show(requireContext(), getString(R.string.validation_import_title), report.text())
                        outbounds.isEmpty() -> snackbar(R.string.no_proxies_found_in_file).show()
                        else -> snackbar(resources.getQuantityString(R.plurals.added, outbounds.size, outbounds.size)).show()
                    }
                }
            } catch (e: Exception) {
                Logs.w(e)
                host.onUi { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun editorFor(itemId: Int): Class<*>? = when (itemId) {
        R.id.action_new_auto_selector -> AutoSelectorSettingsActivity::class.java
        R.id.action_new_socks -> SocksSettingsActivity::class.java
        R.id.action_new_http -> HttpSettingsActivity::class.java
        R.id.action_new_ss -> ShadowsocksSettingsActivity::class.java
        R.id.action_new_vmess -> VMessSettingsActivity::class.java
        R.id.action_new_vless -> VlessSettingsActivity::class.java
        R.id.action_new_xray_vless -> XrayVlessSettingsActivity::class.java
        R.id.action_new_trojan -> TrojanSettingsActivity::class.java
        R.id.action_new_mieru -> MieruSettingsActivity::class.java
        R.id.action_new_naive -> NaiveSettingsActivity::class.java
        R.id.action_new_hysteria -> HysteriaSettingsActivity::class.java
        R.id.action_new_tuic -> TuicSettingsActivity::class.java
        R.id.action_new_juicity -> JuicitySettingsActivity::class.java
        R.id.action_new_ssh -> SSHSettingsActivity::class.java
        R.id.action_new_snell -> SnellSettingsActivity::class.java
        R.id.action_new_wg -> WireGuardSettingsActivity::class.java
        R.id.action_new_shadowtls -> ShadowTLSSettingsActivity::class.java
        R.id.action_new_anytls -> AnyTLSSettingsActivity::class.java
        R.id.action_new_trusttunnel -> TrustTunnelSettingsActivity::class.java
        R.id.action_new_dnstt -> DnsttSettingsActivity::class.java
        R.id.action_new_masterdns -> MasterDnsSettingsActivity::class.java
        R.id.action_new_direct -> DirectSettingsActivity::class.java
        R.id.action_new_masque -> MasqueSettingsActivity::class.java
        R.id.action_new_openvpn -> OpenVpnSettingsActivity::class.java
        R.id.action_new_openconnect -> OpenConnectSettingsActivity::class.java
        R.id.action_new_config -> CustomSettingsActivity::class.java
        R.id.action_new_chain -> ChainSettingsActivity::class.java
        else -> null
    }
}
