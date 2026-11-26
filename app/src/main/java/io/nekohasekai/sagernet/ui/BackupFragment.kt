package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.backup.BackupExport
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.backup.ThrBackup
import io.nekohasekai.sagernet.database.backup.WebDavBackup
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.databinding.LayoutWebdavBackupItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ktx.triggerFullRestart
import io.nekohasekai.sagernet.ui.scan.ScanClient
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Tools › Backup: the desktop's "Backup and Restore" (dialog_basic_settings.cpp:575-835) on `.thrbackup` files. A
 * backup (saved, shared or uploaded to WebDAV) holds the checked parts; a restore accepts any stream that starts
 * with "THRN", shows the desktop's part dialog, stops the service and restarts the app afterwards.
 */
class BackupFragment : Fragment(R.layout.layout_backup) {

    companion object {
        private const val ARG_RESTORE = "restore"
        private const val MIME = "application/octet-stream"
        private const val SHARE_DIR = "backup"

        /** A Backup page that opens the restore dialog for [restore] once. */
        fun newInstance(restore: Uri?) = BackupFragment().apply {
            if (restore != null) arguments = bundleOf(ARG_RESTORE to restore.toString())
        }

        fun fileName(): String = "Throne-backup-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.thrbackup"

        /** BackupArchive::PartNames, Android's four and the desktop-only ones. */
        private val PART_NAMES = setOf("profiles", "routes", "settings", "otp", "ip_lists", "icons")
    }

    private var binding: LayoutBackupBinding? = null
    private var busy = false
    private var progress: AlertDialog? = null
    private var restoreDialog: AlertDialog? = null

    /** The backup the restore dialog shows; discarded when the dialog goes away without a restore. */
    private var pending: BackupRestore.Loaded? = null

    private val saveBackup = registerForActivityResult(SaveDocument(MIME)) { uri ->
        if (uri != null) createTo(uri)
    }

    private val pickBackup = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) openBackup(uri)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val binding = LayoutBackupBinding.bind(view)
        this.binding = binding
        view.applyListInsets()

        binding.backupCreate.setOnClickListener {
            if (selection() != null) startFilesForResult(saveBackup, fileName())
        }
        binding.backupShare.setOnClickListener { selection()?.let(::share) }
        binding.backupRestore.setOnClickListener { startFilesForResult(pickBackup, "*/*") }
        binding.webdavSettings.setOnClickListener {
            startActivity(Intent(requireContext(), WebDAVSettingsActivity::class.java))
        }
        binding.backupToWebdav.setOnClickListener { selection()?.let(::uploadToWebDav) }
        binding.restoreFromWebdav.setOnClickListener { restoreFromWebDav() }
        restoreCheckedParts(binding)

        val restore = arguments?.getString(ARG_RESTORE)
        if (restore != null) {
            arguments?.remove(ARG_RESTORE)
            if (savedInstanceState == null) openBackup(Uri.parse(restore))
        }
    }

    override fun onDestroyView() {
        restoreDialog?.dismiss()
        restoreDialog = null
        pending?.discard()
        pending = null
        progress?.dismiss()
        progress = null
        binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------------------------------------ create

    /** The checked parts (the desktop requires at least one), or null with a message. */
    private fun selection(): BackupExport.Selection? {
        val b = binding ?: return null
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return null
        }
        val selection = BackupExport.Selection(
            profiles = b.backupProfiles.isChecked,
            routes = b.backupRoutes.isChecked,
            settings = b.backupSettings.isChecked,
            ipLists = b.backupIpLists.isChecked,
        )
        if (!selection.any()) {
            snackbar(R.string.backup_select_part).show()
            return null
        }
        return selection
    }

    /** basicSettingsRestoreCheckedParts: the parts of the last backup made; none, or a name it does not know, ticks all. */
    private fun restoreCheckedParts(b: LayoutBackupBinding) {
        val saved = DataStore.backupParts
        if (saved.isEmpty() || saved.any { it !in PART_NAMES }) return
        val selection = BackupExport.Selection(
            profiles = "profiles" in saved,
            routes = "routes" in saved,
            settings = "settings" in saved,
            ipLists = "ip_lists" in saved,
        )
        if (!selection.any()) return
        b.backupProfiles.isChecked = selection.profiles
        b.backupRoutes.isChecked = selection.routes
        b.backupSettings.isChecked = selection.settings
        b.backupIpLists.isChecked = selection.ipLists
    }

    /** basicSettingsRememberCheckedParts, once a backup is under way. */
    private fun rememberCheckedParts(selection: BackupExport.Selection) {
        val names = listOfNotNull(
            "profiles".takeIf { selection.profiles },
            "routes".takeIf { selection.routes },
            "settings".takeIf { selection.settings },
            "ip_lists".takeIf { selection.ipLists },
        )
        if (DataStore.backupParts != names) DataStore.backupParts = names
    }

    private fun createTo(uri: Uri) {
        val selection = selection() ?: return
        rememberCheckedParts(selection)
        runTask(R.string.backup_creating, R.string.backup_failed, work = {
            app.contentResolver.openOutputStream(uri)?.use { BackupExport.write(selection, it) }
                ?: error("cannot write $uri")
        }) {
            snackbar(getString(R.string.backup_created, included(selection))).show()
        }
    }

    private fun share(selection: BackupExport.Selection) {
        rememberCheckedParts(selection)
        runTask(R.string.backup_creating, R.string.backup_failed, work = {
            val dir = File(app.cacheDir, SHARE_DIR).apply { mkdirs() }
            val stale = System.currentTimeMillis() - 10 * 60_000L
            dir.listFiles()?.forEach { if (it.lastModified() < stale) it.delete() }
            val file = File(dir, fileName())
            try {
                file.outputStream().use { BackupExport.write(selection, it) }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            file
        }) { file ->
            val uri = FileProvider.getUriForFile(requireContext(), BuildConfig.APPLICATION_ID + ".cache", file)
            val send = Intent(Intent.ACTION_SEND)
                .setType(MIME)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(file.name, uri)
            try {
                startActivity(Intent.createChooser(send, getString(R.string.abc_shareactionprovider_share_with)))
            } catch (_: ActivityNotFoundException) {
                snackbar(R.string.share_target_missing).show()
            }
        }
    }

    private fun uploadToWebDav(selection: BackupExport.Selection) {
        val dav = webDav() ?: return
        rememberCheckedParts(selection)
        runTask(R.string.webdav_uploading, R.string.webdav_backup_failed, work = {
            val file = File(app.cacheDir, "thrupload-${System.currentTimeMillis()}.thrbackup")
            try {
                file.outputStream().use { BackupExport.write(selection, it) }
                val parts = ThrBackup.Parts(
                    profiles = selection.profiles, routes = selection.routes, settings = selection.settings,
                    ipLists = selection.ipLists,
                )
                dav.upload(file, parts)
            } finally {
                file.delete()
            }
        }) { upload ->
            val indexError = upload.indexError
            if (indexError == null) {
                snackbar(getString(R.string.webdav_backup_uploaded, upload.file)).show()
            } else {
                alert(getString(R.string.webdav_index_upload_failed, indexError))
            }
        }
    }

    private fun included(selection: BackupExport.Selection): String = listOfNotNull(
        R.string.backup_part_profiles.takeIf { selection.profiles },
        R.string.backup_part_routes.takeIf { selection.routes },
        R.string.backup_part_settings.takeIf { selection.settings },
        R.string.backup_part_ip_lists.takeIf { selection.ipLists },
    ).joinToString(", ") { getString(it) }

    // ------------------------------------------------------------------------------------------------ restore

    /** Parses [uri] (any name, any MIME type: only the magic counts) and shows the restore dialog. */
    private fun openBackup(uri: Uri) {
        if (busy) {
            snackbar(R.string.restore_in_progress).show()
            return
        }
        runTask(R.string.backup_reading, R.string.backup_restore_failed, work = {
            app.contentResolver.openInputStream(uri)?.use { BackupRestore.load(it) } ?: error("cannot read $uri")
        }) { showRestoreDialog(it) }
    }

    /** The folder's backups, newest first; a tap shows the backup's details. */
    private fun restoreFromWebDav() {
        val dav = webDav() ?: return
        runTask(R.string.webdav_listing, R.string.webdav_restore_failed, work = { dav.list() }) { backups ->
            if (backups.isEmpty()) {
                alert(getString(R.string.webdav_no_backups, dav.directory.toString()))
                return@runTask
            }
            val adapter = object : ArrayAdapter<WebDavBackup.Entry>(requireContext(), 0, backups) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    val item = convertView?.let(LayoutWebdavBackupItemBinding::bind)
                        ?: LayoutWebdavBackupItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
                    item.title.text = backupTitle(backups[position])
                    item.summary.text = backupSummary(backups[position])
                    return item.root
                }
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.webdav_choose_backup)
                .setAdapter(adapter) { _, which -> showWebDavBackup(dav, backups[which]) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Everything the index knows about [entry]; server texts stay plain text. */
    private fun showWebDavBackup(dav: WebDavBackup, entry: WebDavBackup.Entry) {
        val details = listOfNotNull(
            getString(R.string.webdav_detail_device, deviceLabel(entry)),
            entry.platform.takeIf { it.isNotEmpty() }
                ?.let { getString(R.string.webdav_detail_platform, platformName(it)) },
            entry.appVersion.takeIf { it.isNotEmpty() }?.let { getString(R.string.webdav_detail_version, it) },
            partNames(entry.parts).takeIf { it.isNotEmpty() }?.let { getString(R.string.webdav_detail_parts, it) },
            entry.size.takeIf { it >= 0 }?.let {
                getString(R.string.webdav_detail_size, Formatter.formatShortFileSize(requireContext(), it))
            },
            getString(R.string.webdav_detail_file, WebDavBackup.plain(entry.file)),
        ).joinToString("\n")
        val note = if (entry.indexed) "" else "\n\n" + getString(R.string.webdav_detail_unindexed)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(backupDate(entry))
            .setMessage(details + note)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                runTask(R.string.webdav_downloading, R.string.webdav_restore_failed, work = { dav.download(entry) }) {
                    showRestoreDialog(it, entry.deviceName)
                }
            }
            .setNeutralButton(R.string.webdav_delete) { _, _ -> deleteFromWebDav(dav, entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** After a confirmation; the list comes back refreshed. */
    private fun deleteFromWebDav(dav: WebDavBackup, entry: WebDavBackup.Entry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.webdav_delete)
            .setMessage(getString(R.string.webdav_delete_confirm, backupTitle(entry)))
            .setPositiveButton(R.string.webdav_delete) { _, _ ->
                runTask(R.string.webdav_deleting, R.string.webdav_delete_failed, work = { dav.delete(entry) }) { error ->
                    if (error == null) {
                        snackbar(R.string.webdav_backup_deleted).show()
                        restoreFromWebDav()
                    } else {
                        alert(getString(R.string.webdav_index_delete_failed, error)) {
                            if (view != null) restoreFromWebDav()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun backupDate(entry: WebDavBackup.Entry): String = if (entry.createdAt > 0) {
        DateUtils.formatDateTime(
            requireContext(), entry.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR or
                DateUtils.FORMAT_ABBREV_MONTH
        )
    } else {
        getString(R.string.backup_unknown_date)
    }

    private fun deviceLabel(entry: WebDavBackup.Entry): String {
        val name = entry.deviceName.ifEmpty { getString(R.string.webdav_unknown_device) }
        return if (entry.thisDevice) getString(R.string.webdav_this_device, name) else name
    }

    /** `<date> · <device>`. */
    private fun backupTitle(entry: WebDavBackup.Entry): String = "${backupDate(entry)} · ${deviceLabel(entry)}"

    /** `<platform> · <version> · <parts> · <size>`; a file missing from the index shows its name instead. */
    private fun backupSummary(entry: WebDavBackup.Entry): String = listOfNotNull(
        entry.platform.takeIf { it.isNotEmpty() }?.let(::platformName),
        entry.appVersion.takeIf { it.isNotEmpty() },
        partNames(entry.parts).takeIf { it.isNotEmpty() },
        WebDavBackup.plain(entry.file).takeIf { !entry.indexed },
        entry.size.takeIf { it >= 0 }?.let { Formatter.formatShortFileSize(requireContext(), it) },
    ).joinToString(" · ")

    /** The contract's part names that this app knows, readable; unknown ones are left out. */
    private fun partNames(parts: List<String>): String = parts.distinct().mapNotNull { part ->
        when (part) {
            WebDavBackup.PART_PROFILES -> R.string.webdav_part_profiles
            WebDavBackup.PART_ROUTES -> R.string.webdav_part_routes
            WebDavBackup.PART_SETTINGS -> R.string.webdav_part_settings
            WebDavBackup.PART_OTP -> R.string.webdav_part_otp
            WebDavBackup.PART_IP_LISTS -> R.string.webdav_part_ip_lists
            WebDavBackup.PART_ICONS -> R.string.webdav_part_icons
            else -> null
        }
    }.joinToString(", ") { getString(it) }

    private fun webDav(): WebDavBackup? {
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return null
        }
        val dav = try {
            WebDavBackup.configured()
        } catch (e: Exception) {
            snackbar(e.readableMessage).show()
            return null
        }
        if (dav == null) snackbar(R.string.webdav_server_empty).show()
        return dav
    }

    /**
     * The desktop's restore dialog (dialog_basic_settings.cpp:732-788); [knownDevice] names the device when the backup
     * itself does not (a WebDAV index entry).
     */
    private fun showRestoreDialog(backup: BackupRestore.Loaded, knownDevice: String = "") {
        val available = BackupRestore.available(backup.contents)
        if (!available.any()) {
            backup.discard()
            alert(getString(R.string.backup_error_nothing))
            return
        }
        pending?.discard()
        pending = backup
        val view = LayoutImportBinding.inflate(layoutInflater)
        val created = backup.contents.createdAt.ifEmpty { getString(R.string.backup_unknown_date) }
        val platform = platformName(backup.contents.platform)
        val device = WebDavBackup.plain(backup.contents.deviceName).ifEmpty { knownDevice }
        view.restoreHeader.text = if (device.isEmpty()) {
            getString(R.string.backup_restore_header, created, platform)
        } else {
            getString(R.string.backup_restore_header_device, created, device, platform)
        }
        for ((box, on) in listOf(
            view.restoreProfiles to available.profiles,
            view.restoreRoutes to available.routes,
            view.restoreSettings to available.settings,
            view.restoreIpLists to available.ipLists,
        )) {
            box.isEnabled = on
            box.isChecked = on
        }
        view.restoreOtp.isVisible = backup.contents.parts.otp
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.backup_restore_title)
            .setView(view.root)
            .setPositiveButton(R.string.backup_restore, null)
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                if (pending === backup) {
                    pending = null
                    backup.discard()
                }
                restoreDialog = null
            }
            .show()
        restoreDialog = dialog
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val choice = BackupRestore.Choice(
                profiles = available.profiles && view.restoreProfiles.isChecked,
                routes = available.routes && view.restoreRoutes.isChecked,
                settings = available.settings && view.restoreSettings.isChecked,
                ipLists = available.ipLists && view.restoreIpLists.isChecked,
            )
            if (!choice.any()) {
                Toast.makeText(requireContext(), R.string.backup_restore_select_part, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            pending = null
            dialog.dismiss()
            restore(backup, choice)
        }
    }

    private fun platformName(platform: String): String = when (platform) {
        ThrBackup.PLATFORM_ANDROID -> getString(R.string.backup_platform_android)
        "winnt" -> getString(R.string.backup_platform_windows)
        "linux" -> getString(R.string.backup_platform_linux)
        "darwin" -> getString(R.string.backup_platform_macos)
        "" -> getString(R.string.backup_platform_unknown)
        else -> platform
    }

    /** Runs to the end even when the screen goes away, then restarts the app as the desktop does. */
    private fun restore(backup: BackupRestore.Loaded, choice: BackupRestore.Choice) {
        val activity = requireActivity()
        busy = true
        showProgress(R.string.backup_restoring)
        runOnDefaultDispatcher {
            val result = runCatching {
                stopService()
                // As the desktop's DatabaseManager: no scan may write its progress into the restored database.
                ScanClient.stopAll(persist = false)
                BackupRestore.restore(backup, choice)
            }
            onMainDispatcher {
                busy = false
                progress?.dismiss()
                progress = null
                result.onSuccess { warnings -> restored(activity, warnings) }.onFailure {
                    Logs.w(it)
                    alert(activity.getString(R.string.backup_restore_failed, describe(it)))
                }
            }
        }
    }

    private suspend fun stopService() {
        fun running() = DataStore.serviceState.canStop || DataStore.serviceState == BaseService.State.Stopping
        if (!running()) return
        SagerNet.stopService()
        withTimeoutOrNull(5000L) {
            while (running()) delay(100L)
        }
    }

    private fun restored(activity: Activity, warnings: List<String>) {
        if (activity.isFinishing || activity.isDestroyed) {
            triggerFullRestart(app)
            return
        }
        val text = if (warnings.isEmpty()) {
            activity.getString(R.string.backup_restored)
        } else {
            activity.getString(R.string.backup_restored_warnings, warnings.joinToString("\n") { "• $it" })
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.backup_restore_title)
            .setMessage(text)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> triggerFullRestart(activity) }
            .show()
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** [work] off the main thread under a progress dialog, then [done] or [failure] (`%s` = the error), while the view lives. */
    private fun <T> runTask(@StringRes text: Int, @StringRes failure: Int, work: suspend () -> T, done: (T) -> Unit) {
        if (busy) {
            snackbar(R.string.backup_in_progress).show()
            return
        }
        busy = true
        showProgress(text)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { work() } }
            busy = false
            progress?.dismiss()
            progress = null
            result.onSuccess(done).onFailure {
                Logs.w(it)
                alert(app.getString(failure, describe(it)))
            }
        }
    }

    private fun showProgress(@StringRes text: Int) {
        val view = LayoutProgressBinding.inflate(layoutInflater)
        view.content.setText(text)
        progress?.dismiss()
        progress = MaterialAlertDialogBuilder(requireContext()).setView(view.root).setCancelable(false).show()
    }

    private fun describe(e: Throwable): String = when (e) {
        is ThrBackup.FormatException -> when (e.kind) {
            ThrBackup.Kind.NOT_BACKUP -> app.getString(R.string.backup_error_not_backup)
            ThrBackup.Kind.UNSUPPORTED_VERSION -> app.getString(R.string.backup_error_version, e.version)
            ThrBackup.Kind.CORRUPT -> app.getString(R.string.backup_error_corrupt)
        }

        else -> e.readableMessage
    }

    /** [then] runs when the dialog goes away; not at all when there is no screen to show it on. */
    private fun alert(text: String, then: (() -> Unit)? = null) {
        val context = context
        if (context == null || !isAdded) {
            MessageStore.showMessage(text)
            return
        }
        MaterialAlertDialogBuilder(context).setMessage(text).setPositiveButton(android.R.string.ok, null)
            .setOnDismissListener { then?.invoke() }
            .show()
    }
}
