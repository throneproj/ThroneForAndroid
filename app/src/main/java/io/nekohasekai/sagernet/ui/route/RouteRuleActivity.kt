package io.nekohasekai.sagernet.ui.route

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RuleType
import io.nekohasekai.sagernet.ui.AppListActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.WifiPermissionFlow
import io.nekohasekai.sagernet.ui.profile.multilineInput
import io.nekohasekai.sagernet.ui.profile.portInput
import io.nekohasekai.sagernet.ui.profile.setVisible
import io.nekohasekai.sagernet.ui.settings.LinesSummaryProvider
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.StringLinesPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * One route rule: the well-known fields on top, everything else in a collapsed "Advanced" group. The rule comes in
 * as [EXTRA_RULE] (RouteJson) with its list position [EXTRA_INDEX] (-1 for a new rule) and goes back the same way
 * with RESULT_OK, or [RESULT_DELETE]. Fields are bound to the profile cache store by their member names.
 */
class RouteRuleActivity : ThemedActivity(R.layout.layout_config_settings), OnPreferenceDataStoreChangeListener {

    companion object {
        const val EXTRA_RULE = "rule"
        const val EXTRA_INDEX = "index"
        const val RESULT_DELETE = RESULT_FIRST_USER

        private const val STATE_ADVANCED = "advancedExpanded"
        private const val KEY_ADVANCED_TOGGLE = "advancedToggle"
        private const val KEY_ADVANCED_CATEGORY = "advancedCategory"

        private val TEXT_KEYS = listOf(
            "name", "action", "outbound_id", "reject_method", "strategy", "network", "protocol", "ip_version",
            "override_address", "override_port",
        )
        private val LIST_KEYS = listOf(
            "domain_suffix", "domain", "ip_cidr", "rule_set", "package_name", "network_type", "domain_keyword",
            "domain_regex", "source_ip_cidr", "port", "port_range", "source_port", "source_port_range", "inbound",
            "process_name", "process_path", "process_path_regex", "wifi_ssid", "wifi_bssid",
        )
        private val BOOL_KEYS = listOf(
            "sniff_override_dest", "ip_is_private", "source_ip_is_private", "invert", "no_drop", "network_is_expensive",
        )

        /** The members inside the collapsed group. */
        private val ADVANCED_KEYS = listOf(
            "domain_keyword", "domain_regex", "ip_is_private", "source_ip_cidr", "source_ip_is_private", "port",
            "port_range", "source_port", "source_port_range", "network", "protocol", "ip_version", "inbound", "invert",
            "override_address", "override_port", "no_drop", "process_name", "process_path", "process_path_regex",
            "network_is_expensive", "wifi_ssid", "wifi_bssid",
        )

        /** Android names apps by package, never by process: these show only when a desktop rule brought a value. */
        private val DESKTOP_ONLY_KEYS = listOf("process_name", "process_path", "process_path_regex")
    }

    private val pbm = PreferenceBindingManager().apply {
        for (key in TEXT_KEYS + LIST_KEYS) text(key)
        for (key in BOOL_KEYS) bool(key)
    }

    private lateinit var rule: RouteRule
    private var index = -1
    private var loaded = false
    private var advancedExpanded = false

    /** Every server profile as (id, "[group] name"). */
    private var servers: List<Pair<Long, String>> = emptyList()

    private val fragment get() = supportFragmentManager.findFragmentById(R.id.settings) as? RuleFragment

    private val ruleSetPicker = registerForActivityResult(RuleSetPickerActivity.Contract()) { list ->
        if (list != null) setListValue("rule_set", list)
    }

    private val appPicker = registerForActivityResult(AppListActivity.Contract(unknownEntry = true)) { list ->
        if (list != null) setListValue("package_name", list)
    }

    private val wifiFlow = WifiPermissionFlow(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_rule_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        rule = RouteJson.ruleFromJson(intent.getStringExtra(EXTRA_RULE))
        index = intent.getIntExtra(EXTRA_INDEX, -1)
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false
        onBackPressedDispatcher.addCallback(this) { close() }

        val fresh = savedInstanceState == null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                servers = RouteServers.list()
                PackageCache.awaitLoadSync()
                if (fresh) {
                    pbm.writeToCacheAll(rule)
                    DataStore.dirty = false
                }
            }
            loaded = true
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, RuleFragment())
                .commitNowAllowingStateLoss()
            DataStore.profileCacheStore.registerChangeListener(this@RouteRuleActivity)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED, advancedExpanded)
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROFILE_DIRTY) return
        DataStore.dirty = true
        runOnUiThread { fragment?.refreshState() }
    }

    private fun setListValue(key: String, values: List<String>) {
        val text = values.joinToString("\n")
        if (text != DataStore.profileCacheStore.getString(key).orEmpty()) {
            DataStore.profileCacheStore.putString(key, text)
            DataStore.dirty = true
        }
        fragment?.findPreference<StringLinesPreference>(key)?.refresh()
    }

    /** Checks the stored network types; OK keeps the checked ones in sing-box's order, Cancel changes nothing. */
    private fun pickNetworkTypes(current: List<String>) {
        val types = RouteRule.NETWORK_TYPES
        val checked = BooleanArray(types.size) { types[it] in current }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_rule_network_type)
            .setMultiChoiceItems(R.array.route_rule_network_type_entries, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                setListValue("network_type", types.filterIndexed { i, _ -> checked[i] })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                if (loaded) save()
                return true
            }

            R.id.action_delete -> {
                if (index >= 0) setResult(RESULT_DELETE, Intent().putExtra(EXTRA_INDEX, index))
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun close() {
        if (!loaded || !DataStore.dirty) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_changes_prompt)
            .setPositiveButton(R.string.save) { _, _ -> save() }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun message(title: Int, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun save() {
        val edited = rule.copy()
        pbm.fromCacheAll(edited)
        edited.name = edited.name.trim()
        val problems = RouteRuleChecks.problems(this, edited)
        if (problems.isNotEmpty()) {
            message(R.string.route_rule_invalid_title, problems.joinToString("\n"))
            return
        }
        lifecycleScope.launch {
            val unknown = withContext(Dispatchers.IO) {
                RouteRuleChecks.unknownRuleSets(edited.rule_set, RouteManager.catalog())
            }
            if (unknown.isNotEmpty()) {
                message(R.string.route_rule_invalid_title, getString(R.string.route_rule_unknown_rule_sets, unknown.joinToString(", ")))
                return@launch
            }
            // D11: a desktop simple rule keeps its type only while it still fits it.
            val type = RuleType.ofId(edited.type)
            if (type != RuleType.CUSTOM && !edited.fitsType(type)) edited.type = RuleType.CUSTOM.id
            val action = edited.effectiveAction()
            if (!RouteRuleChecks.hasConditions(edited) && (action == "route" || action == "bypass" || action == "reject")) {
                MaterialAlertDialogBuilder(this@RouteRuleActivity)
                    .setTitle(R.string.route_rule_catch_all_title)
                    .setMessage(R.string.route_rule_catch_all)
                    .setPositiveButton(R.string.yes) { _, _ -> finishWith(edited) }
                    .setNegativeButton(R.string.no, null)
                    .show()
            } else {
                finishWith(edited)
            }
        }
    }

    private fun finishWith(edited: RouteRule, checkWifi: Boolean = true) {
        val usesWifi = edited.wifi_ssid.any { it.isNotBlank() } || edited.wifi_bssid.any { it.isNotBlank() }
        if (checkWifi && usesWifi && WifiStateAccess.status(this) != WifiStateAccess.Status.OK) {
            wifiFlow.run { finishWith(edited, false) }
            return
        }
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RULE, RouteJson.ruleToJson(edited)).putExtra(EXTRA_INDEX, index))
        finish()
    }

    class RuleFragment : PreferenceFragmentCompat() {

        private val host get() = requireActivity() as RouteRuleActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            // A fragment restored before the activity reloaded its data stays empty; the activity replaces it.
            if (!host.loaded) return
            addPreferencesFromResource(R.xml.route_rule_preferences)

            setupOutbounds()
            for (key in listOf("action", "reject_method", "strategy", "network", "protocol", "ip_version")) {
                findPreference<SimpleMenuPreference>(key)?.ensureValue()
            }

            val multiline = LIST_KEYS - setOf("rule_set", "package_name", "network_type")
            multilineInput(*multiline.toTypedArray())
            for (key in multiline) findPreference<EditTextPreference>(key)?.summaryProvider = LinesSummaryProvider(maxLines = 3)
            refreshWifiHint()
            portInput("override_port")

            findPreference<StringLinesPreference>("rule_set")!!.apply {
                summaryProvider = Preference.SummaryProvider<StringLinesPreference> { p ->
                    summarize(p.values.map { RuleSetLabels.shortName(it) }, 3)
                }
                setOnPreferenceClickListener {
                    host.ruleSetPicker.launch(values)
                    true
                }
            }
            findPreference<StringLinesPreference>("package_name")!!.apply {
                summaryProvider = Preference.SummaryProvider<StringLinesPreference> { p ->
                    val packages = p.values
                    if (packages.size > 5) getString(R.string.apps_message, packages.size)
                    else summarize(packages.map {
                        if (it == RouteRule.UNKNOWN_PACKAGE) getString(R.string.route_rule_unknown_apps) else PackageCache.loadLabel(it)
                    }, 5, ", ")
                }
                setOnPreferenceClickListener {
                    host.appPicker.launch(values)
                    true
                }
            }
            findPreference<StringLinesPreference>("network_type")!!.apply {
                summaryProvider = Preference.SummaryProvider<StringLinesPreference> { p ->
                    val labels = resources.getStringArray(R.array.route_rule_network_type_entries)
                    val names = p.values.map { labels.getOrNull(RouteRule.NETWORK_TYPES.indexOf(it)) ?: it }
                    if (names.isEmpty()) getString(androidx.preference.R.string.not_set) else names.joinToString(", ")
                }
                setOnPreferenceClickListener {
                    host.pickNetworkTypes(values)
                    true
                }
            }
            findPreference<Preference>(KEY_ADVANCED_TOGGLE)!!.setOnPreferenceClickListener {
                host.advancedExpanded = !host.advancedExpanded
                refreshState()
                true
            }
            refreshState()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.layoutManager = FixedLinearLayoutManager(listView)
            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onResume() {
            super.onResume()
            if (preferenceScreen != null) refreshWifiHint()
        }

        /** The Wi-Fi fields say when location access is missing; setting the provider re-renders the summary. */
        private fun refreshWifiHint() {
            val missing = WifiStateAccess.status(requireContext()) != WifiStateAccess.Status.OK
            val lines = LinesSummaryProvider(maxLines = 3)
            for (key in listOf("wifi_ssid", "wifi_bssid")) {
                findPreference<EditTextPreference>(key)?.summaryProvider =
                    Preference.SummaryProvider<EditTextPreference> { p ->
                        val summary = lines.provideSummary(p)
                        if (missing) "$summary\n${getString(R.string.wifi_rule_needs_location)}" else summary
                    }
            }
        }

        private fun summarize(items: List<String>, max: Int, separator: String = "\n"): String {
            if (items.isEmpty()) return getString(androidx.preference.R.string.not_set)
            val shown = items.take(max).joinToString(separator)
            return if (items.size > max) shown + separator + "…" else shown
        }

        /** proxy, direct, block, warp-bypass, then every server as "[group] name"; stored values outside that list stay selectable. */
        private fun setupOutbounds() {
            val pref = findPreference<SimpleMenuPreference>("outbound_id") ?: return
            val current = pref.value?.toLongOrNull() ?: OutboundIds.DIRECT
            val entries = ArrayList<CharSequence>()
            val values = ArrayList<CharSequence>()
            fun add(label: String, id: Long) {
                entries.add(label)
                values.add(id.toString())
            }
            add(OutboundIds.toName(OutboundIds.PROXY), OutboundIds.PROXY)
            add(OutboundIds.toName(OutboundIds.DIRECT), OutboundIds.DIRECT)
            add(OutboundIds.toName(OutboundIds.BLOCK), OutboundIds.BLOCK)
            add(RouteTexts.WARP_BYPASS, OutboundIds.WARP_BYPASS)
            if (current == OutboundIds.HIJACK_DNS) add("hijack-dns", OutboundIds.HIJACK_DNS)
            for ((id, label) in host.servers) add(label, id)
            if (current > 0 && host.servers.none { it.first == current }) {
                add(getString(R.string.route_rule_missing_server, current), current)
            }
            pref.entries = entries.toTypedArray()
            pref.entryValues = values.toTypedArray()
            pref.value = current.toString()
        }

        /** A stored value the menu does not offer (e.g. from a desktop profile) is added so it is not replaced. */
        private fun SimpleMenuPreference.ensureValue() {
            val v = value ?: return
            if (entryValues?.any { it.toString() == v } == true) return
            val entryList = ArrayList<CharSequence>()
            val valueList = ArrayList<CharSequence>()
            entries?.let { entryList.addAll(it) }
            entryValues?.let { valueList.addAll(it) }
            entryList.add(v)
            valueList.add(v)
            entries = entryList.toTypedArray()
            entryValues = valueList.toTypedArray()
            value = v
        }

        /** Shows the fields of the chosen action and counts the advanced fields that are set. */
        fun refreshState() {
            if (preferenceScreen == null) return
            val store = DataStore.profileCacheStore
            val action = store.getString("action") ?: "route"
            val outbound = store.getString("outbound_id")?.toLongOrNull() ?: OutboundIds.DIRECT
            val effective = if (action != "route") action else when (outbound) {
                OutboundIds.BLOCK -> "reject"
                OutboundIds.HIJACK_DNS -> "hijack-dns"
                else -> action
            }
            val routes = effective == "route" || effective == "bypass"
            setVisible(action == "route" || action == "bypass", "outbound_id")
            setVisible(effective == "reject", "reject_method", "no_drop")
            setVisible(effective == "resolve", "strategy")
            setVisible(effective == "sniff", "sniff_override_dest")
            setVisible(routes || effective == "route-options", "override_address", "override_port")
            for (key in DESKTOP_ONLY_KEYS) findPreference<Preference>(key)?.isVisible = !store.getString(key).isNullOrBlank()

            val set = ADVANCED_KEYS.count { key ->
                val pref = findPreference<Preference>(key)
                if (pref == null || !pref.isVisible) return@count false
                if (pref is SwitchPreference) store.getBoolean(key, false) else !store.getString(key).isNullOrBlank()
            }
            val expanded = host.advancedExpanded
            findPreference<PreferenceCategory>(KEY_ADVANCED_CATEGORY)?.isVisible = expanded
            findPreference<Preference>(KEY_ADVANCED_TOGGLE)?.apply {
                title = if (set > 0) getString(R.string.route_rule_advanced_count, set) else getString(R.string.route_rule_advanced)
                summary = getString(if (expanded) R.string.route_rule_advanced_hide else R.string.route_rule_advanced_show)
                setIcon(if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
            }
        }
    }
}
