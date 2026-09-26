package io.nekohasekai.sagernet.ui.settings

import android.text.InputType
import android.text.format.Formatter
import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.XrayGeoAssets
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.DialogGeoDownloadBinding
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import java.text.DateFormat
import java.util.Date

/**
 * Settings › Core › Xray geo assets: where geoip.dat and geosite.dat come from (the provider combos of
 * dialog_basic_settings.cpp:260-290) and the installed files with "Download now" (:510-575). A changed source drops
 * the file it replaces; the next config that needs it downloads it again.
 */
class XrayGeoSettingsFragment : SettingsScreenFragment(R.xml.settings_xray_geo) {

    private var downloadJob: Job? = null

    override fun bind() {
        val source = pref<SimpleMenuPreference>(KEY_SOURCE)
        val providers = XrayGeoAssets.PROVIDERS
        source.entries = (providers.map { it.name } + getString(R.string.xray_geo_source_custom)).toTypedArray()
        source.entryValues = (providers.indices.map { it.toString() } + VALUE_CUSTOM).toTypedArray()
        source.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> { it.entry }
        // Both files come from the picked provider; Custom leaves the URLs to be edited.
        source.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toIntOrNull()?.let(providers::getOrNull)
            if (provider != null &&
                (setUrl(XrayGeoAssets.GEOIP, provider.geoip) or setUrl(XrayGeoAssets.GEOSITE, provider.geosite))
            ) {
                sourceChanged()
            }
            true
        }
        for (file in XrayGeoAssets.FILES) {
            bindUrl(file)
            pref<Preference>(fileKey(file)).apply {
                title = file
                setOnPreferenceClickListener {
                    download(listOf(file))
                    true
                }
            }
        }
        pref<Preference>(KEY_DOWNLOAD).setOnPreferenceClickListener {
            download(XrayGeoAssets.FILES)
            true
        }
        syncSource()
    }

    override fun onResume() {
        super.onResume()
        // A start or a test in the service process may have fetched a file meanwhile.
        refreshFiles()
    }

    private fun bindUrl(file: String) {
        val preference = pref<EditTextPreference>(urlKey(file))
        preference.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSelection(editText.text.length)
        }
        preference.setOnPreferenceChangeListener { _, newValue ->
            val url = newValue?.toString().orEmpty().trim()
            if (!XrayGeoAssets.isValidUrl(url)) {
                toast(R.string.xray_geo_invalid_url, url)
            } else if (setUrl(file, url)) {
                sourceChanged()
            }
            false
        }
    }

    /** Stores [url] as the source of [file] and drops the installed copy; false when it is the stored one already. */
    private fun setUrl(file: String, url: String): Boolean {
        val preference = pref<EditTextPreference>(urlKey(file))
        if (preference.text == url) return false
        preference.text = url
        XrayGeoAssets.delete(file)
        return true
    }

    private fun sourceChanged() {
        syncSource()
        refreshFiles()
        // A running Xray keeps the old data, and one it starts lazily would miss the file: a reload fetches it first.
        needReload()
    }

    /** The provider both stored URLs belong to, else Custom. */
    private fun syncSource() {
        val geoip = pref<EditTextPreference>(urlKey(XrayGeoAssets.GEOIP)).text.orEmpty()
        val geosite = pref<EditTextPreference>(urlKey(XrayGeoAssets.GEOSITE)).text.orEmpty()
        val index = XrayGeoAssets.PROVIDERS.indexOfFirst { it.geoip == geoip && it.geosite == geosite }
        pref<SimpleMenuPreference>(KEY_SOURCE).value = if (index >= 0) index.toString() else VALUE_CUSTOM
    }

    private fun refreshFiles() {
        val context = context ?: return
        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (file in XrayGeoAssets.FILES) {
            val installed = XrayGeoAssets.file(file)
            pref<Preference>(fileKey(file)).summary = if (installed.isFile && installed.length() > 0) {
                getString(
                    R.string.xray_geo_status_installed,
                    Formatter.formatShortFileSize(context, installed.length()),
                    dates.format(Date(installed.lastModified())),
                )
            } else {
                getString(R.string.xray_geo_status_missing)
            }
        }
    }

    /** Downloads [files] again, one after the other, behind a progress dialog whose Cancel stops it. */
    private fun download(files: List<String>) {
        if (downloadJob?.isActive == true) return
        val context = requireContext()
        val binding = DialogGeoDownloadBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.xray_geo_download_title)
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel) { _, _ -> downloadJob?.cancel() }
            .setCancelable(false)
            .show()
        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val errors = ArrayList<String>()
            try {
                for (file in files) {
                    showProgress(binding, file, null)
                    try {
                        XrayGeoAssets.download(file, force = true) { progress ->
                            withContext(Dispatchers.Main) { showProgress(binding, file, progress) }
                        }
                    } catch (e: XrayGeoAssets.DownloadException) {
                        errors.add(e.readableMessage)
                    }
                }
            } finally {
                runCatching { dialog.dismiss() }
                refreshFiles()
            }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.xray_geo_download_title)
                .setMessage(
                    if (errors.isEmpty()) getString(R.string.xray_geo_download_done, files.joinToString(", "))
                    else errors.joinToString("\n\n")
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun showProgress(binding: DialogGeoDownloadBinding, file: String, progress: XrayGeoAssets.Progress?) {
        binding.text.text = progress?.let(XrayGeoAssets::progressText) ?: getString(R.string.xray_geo_downloading, file)
        val percent = progress?.percent ?: -1
        val bar = binding.progress
        val indeterminate = percent < 0
        // The indicator switches mode only while hidden.
        if (bar.isIndeterminate != indeterminate) {
            bar.visibility = View.INVISIBLE
            bar.isIndeterminate = indeterminate
            bar.visibility = View.VISIBLE
        }
        if (!indeterminate) bar.setProgressCompat(percent, true)
    }

    private companion object {
        const val KEY_SOURCE = "xrayGeoSource"
        const val KEY_DOWNLOAD = "xrayGeoDownload"
        const val VALUE_CUSTOM = "custom"

        fun urlKey(file: String): String =
            if (file == XrayGeoAssets.GEOIP) SettingsRegistry.XRAY_GEOIP_URL.key else SettingsRegistry.XRAY_GEOSITE_URL.key

        fun fileKey(file: String): String = if (file == XrayGeoAssets.GEOIP) "xrayGeoFileGeoip" else "xrayGeoFileGeosite"
    }
}
