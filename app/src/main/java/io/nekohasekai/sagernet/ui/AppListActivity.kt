package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.UiThread
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutAppListBinding
import io.nekohasekai.sagernet.databinding.LayoutAppsItemBinding
import io.nekohasekai.sagernet.ktx.crossFadeFrom
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * A multi-select package picker: [EXTRA_PACKAGES] carries the current selection in and the final one back out
 * (RESULT_OK on back or up). Selected packages that are not installed are listed too, so they survive a round trip.
 */
class AppListActivity : ThemedActivity() {

    companion object {
        const val EXTRA_PACKAGES = "packages"
        private const val EXTRA_UNKNOWN_ENTRY = "unknownEntry"
        private const val SWITCH = "switch"
        private const val STATE_SELECTED = "selected"
        private const val STATE_SYSTEM_APPS = "systemApps"
    }

    /** [unknownEntry] lists [RouteRule.UNKNOWN_PACKAGE] first, as the row for connections without a known app. */
    class Contract(private val unknownEntry: Boolean = false) : ActivityResultContract<List<String>, List<String>?>() {
        override fun createIntent(context: Context, input: List<String>) =
            Intent(context, AppListActivity::class.java).putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(input))
                .putExtra(EXTRA_UNKNOWN_ENTRY, unknownEntry)

        override fun parseResult(resultCode: Int, intent: Intent?): List<String>? =
            if (resultCode == RESULT_OK) intent?.getStringArrayListExtra(EXTRA_PACKAGES) else null
    }

    private class AppItem(
        val packageName: String,
        val info: ApplicationInfo?,
        val label: String,
        val unknown: Boolean = false,
    ) {
        val sys get() = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val uid get() = info?.uid
    }

    private inner class AppViewHolder(val binding: LayoutAppsItemBinding) :
        RecyclerView.ViewHolder(binding.root), View.OnClickListener {
        private lateinit var item: AppItem

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: AppItem) {
            item = app
            binding.title.text = app.label
            if (app.unknown) {
                binding.itemicon.setImageResource(R.drawable.ic_navigation_apps)
                binding.desc.setText(R.string.route_rule_unknown_apps_desc)
            } else {
                binding.itemicon.setImageDrawable(app.info?.loadIcon(packageManager) ?: packageManager.defaultActivityIcon)
                binding.desc.text = if (app.info != null) {
                    "${app.packageName} (${app.uid})"
                } else {
                    getString(R.string.app_not_installed, app.packageName)
                }
            }
            handlePayload(listOf(SWITCH))
        }

        fun handlePayload(payloads: List<String>) {
            if (payloads.contains(SWITCH)) binding.itemcheck.isChecked = item.packageName in selected
        }

        override fun onClick(v: View?) {
            if (!selected.remove(item.packageName)) selected.add(item.packageName)
            appsAdapter.notifyItemChanged(bindingAdapterPosition, SWITCH)
            updateSubtitle()
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {
        var filteredApps = apps

        suspend fun reload() {
            val cache = PackageCache.reload()
            val installed = cache.installedPackages.filterKeys { it != BuildConfig.APPLICATION_ID }
            val list = installed.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { AppItem(packageName, it, it.loadLabel(packageManager).toString()) }
            }.toMutableList()
            if (unknownEntry) {
                list.add(AppItem(RouteRule.UNKNOWN_PACKAGE, null, getString(R.string.route_rule_unknown_apps), unknown = true))
            }
            for (packageName in selected) {
                if (packageName !in installed && !(unknownEntry && packageName == RouteRule.UNKNOWN_PACKAGE)) {
                    val info = cache.installedApps[packageName]
                    list.add(AppItem(packageName, info, info?.loadLabel(packageManager)?.toString() ?: packageName))
                }
            }
            apps = sorted(list)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) =
            holder.bind(filteredApps[position])

        override fun onBindViewHolder(holder: AppViewHolder, position: Int, payloads: List<Any>) {
            if (payloads.isNotEmpty()) {
                @Suppress("UNCHECKED_CAST") holder.handlePayload(payloads as List<String>)
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder =
            AppViewHolder(LayoutAppsItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var filtered = if (constraint.isEmpty()) apps else apps.filter {
                    it.label.contains(constraint, true) || it.packageName.contains(constraint, true) ||
                        it.uid?.toString()?.contains(constraint) == true
                }
                if (!sysApps) filtered = filtered.filter { !it.sys || it.packageName in selected }
                count = filtered.size
                values = filtered
            }

            @Suppress("NotifyDataSetChanged")
            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<AppItem>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].label.firstOrNull()?.toString() ?: ""
        }
    }

    private lateinit var binding: LayoutAppListBinding
    private val selected = LinkedHashSet<String>()
    private var loader: Job? = null
    private var apps = emptyList<AppItem>()
    private val appsAdapter = AppsAdapter()
    private var sysApps = false
    private var unknownEntry = false

    private fun sorted(list: List<AppItem>) =
        list.sortedWith(compareBy({ !it.unknown }, { it.packageName !in selected }, { it.label }))

    private fun refilter() = appsAdapter.filter.filter(binding.search.text?.toString() ?: "")

    private fun updateSubtitle() {
        supportActionBar?.subtitle = getString(R.string.picker_selected, selected.size)
    }

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launch {
            binding.loading.crossFadeFrom(binding.list)
            withContext(Dispatchers.IO) { appsAdapter.reload() }
            refilter()
            // The unknown-app row alone is no app list: the permission hint still shows.
            if (apps.all { it.unknown }) {
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(binding.loading)
            } else {
                binding.list.crossFadeFrom(binding.loading)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appPlaceholder.openSettings.setOnClickListener {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                })
            } catch (_: Exception) {
            }
        }

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.select_apps)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        val initial = savedInstanceState?.getStringArrayList(STATE_SELECTED)
            ?: intent.getStringArrayListExtra(EXTRA_PACKAGES).orEmpty()
        initial.map { it.trim() }.filterTo(selected) { it.isNotEmpty() }
        unknownEntry = intent.getBooleanExtra(EXTRA_UNKNOWN_ENTRY, false)
        sysApps = savedInstanceState?.getBoolean(STATE_SYSTEM_APPS) ?: false
        updateSubtitle()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        // the app bar pads the status bar and the sides itself (applyTopInset); the list pads the navigation bar and the sides
        binding.list.applyListInsets(ime = true, horizontal = true)

        binding.search.addTextChangedListener { refilter() }

        binding.showSystemApps.isChecked = sysApps
        binding.showSystemApps.setOnCheckedChangeListener { _, isChecked ->
            sysApps = isChecked
            refilter()
        }

        onBackPressedDispatcher.addCallback(this) { finishWithResult() }

        loadApps()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(STATE_SELECTED, ArrayList(selected))
        outState.putBoolean(STATE_SYSTEM_APPS, sysApps)
    }

    private fun finishWithResult() {
        setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_PACKAGES, ArrayList(selected)))
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.app_list_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_invert_selections -> {
                for (app in apps) {
                    if (app.unknown) continue
                    if (!selected.remove(app.packageName)) selected.add(app.packageName)
                }
                apps = sorted(apps)
                updateSubtitle()
                refilter()
                return true
            }

            R.id.action_clear_selections -> {
                selected.clear()
                apps = sorted(apps)
                updateSubtitle()
                refilter()
                return true
            }

            R.id.action_add_package -> {
                PackageNameInput.show(this) { names ->
                    selected.addAll(names)
                    updateSubtitle()
                    loadApps()
                }
                return true
            }

            R.id.action_export_clipboard -> {
                val success = SagerNet.trySetPrimaryClip(selected.joinToString("\n"))
                Snackbar.make(
                    binding.list,
                    if (success) R.string.action_export_msg else R.string.action_export_err,
                    Snackbar.LENGTH_LONG
                ).show()
                return true
            }

            R.id.action_import_clipboard -> {
                // One package per line; a leading "true"/"false" line (the per-app proxy export) is skipped.
                val lines = SagerNet.getClipboardText().lines().map { it.trim() }.filter { it.isNotEmpty() }
                    .let { if (it.firstOrNull() == "true" || it.firstOrNull() == "false") it.drop(1) else it }
                if (lines.isEmpty()) {
                    Snackbar.make(binding.list, R.string.action_import_err, Snackbar.LENGTH_LONG).show()
                } else {
                    selected.clear()
                    selected.addAll(lines)
                    updateSubtitle()
                    Snackbar.make(binding.list, R.string.action_import_msg, Snackbar.LENGTH_LONG).show()
                    loadApps()
                }
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finishWithResult()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?) = if (keyCode == KeyEvent.KEYCODE_MENU) {
        if (binding.toolbar.isOverflowMenuShowing) binding.toolbar.hideOverflowMenu() else binding.toolbar.showOverflowMenu()
    } else super.onKeyUp(keyCode, event)

    override fun onDestroy() {
        loader?.cancel()
        super.onDestroy()
    }
}
