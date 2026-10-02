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
import androidx.preference.MultiSelectListPreference
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
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.ui.profile.setVisible
import org.json.JSONObject
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
            "override_address", "override_port", "logical_mode", "balancer_mode",
        )
        private val LIST_KEYS = listOf(
            "domain_suffix", "domain", "ip_cidr", "rule_set", "package_name", "domain_keyword", "domain_regex",
            "source_ip_cidr", "port", "port_range", "source_port", "source_port_range", "inbound", "process_name",
            "process_path", "process_path_regex", "wifi_ssid", "wifi_bssid",
            "default_interface_address", "dns_server", "balancer_sticky_hash",
        )
        private val BOOL_KEYS = listOf("sniff_override_dest", "ip_is_private", "source_ip_is_private", "invert", "no_drop")

        /** The members inside the collapsed group. */
        private val ADVANCED_KEYS = listOf(
            "domain_keyword", "domain_regex", "ip_is_private", "source_ip_cidr", "source_ip_is_private", "port",
            "port_range", "source_port", "source_port_range", "network", "protocol", "ip_version", "inbound", "invert",
            "override_address", "override_port", "no_drop", "process_name", "process_path", "process_path_regex",
            "wifi_ssid", "wifi_bssid",
            "logical_mode", "rules_json", "default_interface_address", "dns_server",
            "balancer_mode", "balancer_pool", "balancer_pool_tolerance", "balancer_sticky_hash",
        )

        /** Android names apps by package, never by process: these show only when a desktop rule brought a value. */
        private val DESKTOP_ONLY_KEYS = listOf("process_name", "process_path", "process_path_regex")
    }

    private val pbm = PreferenceBindingManager().apply {
        for (key in TEXT_KEYS + LIST_KEYS) text(key)
        // rules_json is a raw JSON blob (a plain EditTextPreference, not a newline-joined list): bind it too, so
        // open() writes the current JSON into the cache and save() reads the user's edit back from it.
        text("rules_json")
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

    private val appPicker = registerForActivityResult(AppListActivity.Contract()) { list ->
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
            findPreference<Preference>("jsonPaste")?.setOnPreferenceClickListener {
                showJsonPasteDialog()
                true
            }
            for (key in listOf("action", "reject_method", "strategy", "network", "protocol", "ip_version")) {
                findPreference<SimpleMenuPreference>(key)?.ensureValue()
            }
            val multiline = LIST_KEYS - setOf("rule_set", "package_name", "rules_json", "default_interface_address", "dns_server", "balancer_sticky_hash")
            multilineInput(*multiline.toTypedArray())
            // StringLinesPreference fields need custom handling
            for (key in listOf("default_interface_address", "dns_server", "balancer_sticky_hash")) {
                findPreference<io.nekohasekai.sagernet.widget.StringLinesPreference>(key)?.apply {
                    summaryProvider = LinesSummaryProvider(maxLines = 3)
                }
            }
            // rules_json is raw JSON, not line-separated values — use plain EditTextPreference
            findPreference<EditTextPreference>("rules_json")?.apply {
                dialogMessage = getString(R.string.route_rule_rules_json_hint)
                setOnBindEditTextListener { et ->
                    val v = DataStore.profileCacheStore.getString("rules_json") ?: ""
                    et.setText(v)
                    et.setSelection(et.text.length)
                }
                setOnPreferenceChangeListener { _, newVal ->
                    val s = newVal as? String ?: return@setOnPreferenceChangeListener false
                    DataStore.profileCacheStore.putString("rules_json", s)
                    true
                }
            }
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
                    else summarize(packages.map { PackageCache.loadLabel(it) }, 5, ", ")
                }
                setOnPreferenceClickListener {
                    host.appPicker.launch(values)
                    true
                }
            }
            // balancer_pool and balancer_pool_tolerance are Int EditTextPreferences — parse as integers
            for (key in listOf("balancer_pool", "balancer_pool_tolerance")) {
                findPreference<EditTextPreference>(key)?.apply {
                    setOnBindEditTextListener { et ->
                        val v = DataStore.profileCacheStore.getString(key) ?: "0"
                        et.setText(if (v.isBlank()) "0" else v)
                        et.setSelection(et.text.length)
                    }
                    setOnPreferenceChangeListener { _, newVal ->
                        val s = newVal as? String ?: return@setOnPreferenceChangeListener false
                        if (s.isEmpty() || s.toIntOrNull() != null) {
                            DataStore.profileCacheStore.putString(key, s)
                            true
                        } else {
                            false
                        }
                    }
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

        /** Paste a sing-box rule JSON fragment and merge fields into the current rule. */
        private fun showJsonPasteDialog() {
            val input = androidx.appcompat.widget.AppCompatEditText(requireContext()).apply {
                hint = getString(R.string.route_rule_json_paste_hint)
                setSingleLine(false)
                maxLines = 10
                minLines = 5
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.route_rule_json_paste_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val text = input.text?.toString()?.trim() ?: return@setPositiveButton
                    try {
                        val obj = JsonInput.parseValue(text) as? JSONObject
                            ?: throw IllegalArgumentException("not a JSON object")
                        val imported = mergeRuleFromJson(host.rule, obj)
                        DataStore.dirty = true
                        host.message(R.string.route_rule_json_paste_success,
                            getString(R.string.route_rule_json_paste_success, imported))
                        val frag = this@RuleFragment
                        frag.refreshState()
                    } catch (e: Exception) {
                        host.message(R.string.route_rule_invalid_title,
                            getString(R.string.route_rule_json_paste_error, e.message))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        /** Merge fields from a sing-box rule JSON fragment into [rule]. Returns count of fields merged. */
        private fun mergeRuleFromJson(rule: RouteRule, obj: JSONObject): Int {
            var count = 0
            fun setStr(key: String, setter: (String) -> Unit) {
                if (obj.has(key)) { setter(obj.optString(key)); count++ }
            }
            fun setList(key: String, setter: (MutableList<String>) -> Unit) {
                if (obj.has(key)) {
                    val arr = obj.optJSONArray(key)
                    if (arr != null) {
                        setter((0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList())
                        count++
                    }
                }
            }
            fun setBool(key: String, setter: (Boolean) -> Unit) {
                if (obj.has(key)) { setter(obj.optBoolean(key)); count++ }
            }
            fun setInt(key: String, setter: (Int) -> Unit) {
                if (obj.has(key)) { setter(obj.optInt(key)); count++ }
            }

            setStr("logical_mode") { rule.logical_mode = it; DataStore.profileCacheStore.putString("logical_mode", it) }
            setStr("balancer_mode") { rule.balancer_mode = it; DataStore.profileCacheStore.putString("balancer_mode", it) }
            if (obj.has("network")) {
                val netArr = obj.optJSONArray("network")
                if (netArr != null) {
                    val vals = (0 until netArr.length()).map { netArr.optString(it) }.filter { it.isNotBlank() }
                    rule.network = vals.joinToString("\n")
                    DataStore.profileCacheStore.putString("network", rule.network)
                    count++
                }
            }
            if (obj.has("protocol")) {
                val protoArr = obj.optJSONArray("protocol")
                if (protoArr != null) {
                    val vals = (0 until protoArr.length()).map { protoArr.optString(it) }.filter { it.isNotBlank() }
                    rule.protocol = vals.joinToString("\n")
                    DataStore.profileCacheStore.putString("protocol", rule.protocol)
                    count++
                }
            }
            setStr("ip_version") { rule.ip_version = it; DataStore.profileCacheStore.putString("ip_version", it) }
            setStr("strategy") { rule.strategy = it; DataStore.profileCacheStore.putString("strategy", it) }
            setStr("reject_method") { rule.reject_method = it; DataStore.profileCacheStore.putString("reject_method", it) }
            setStr("override_address") { rule.override_address = it; DataStore.profileCacheStore.putString("override_address", it) }
            setStr("override_port") { rule.override_port = it; DataStore.profileCacheStore.putString("override_port", it) }
            setStr("tls_spoof") { rule.tls_spoof = it; DataStore.profileCacheStore.putString("tls_spoof", it) }
            setStr("tls_spoof_method") { rule.tls_spoof_method = it; DataStore.profileCacheStore.putString("tls_spoof_method", it) }
            setStr("name") { rule.name = it; DataStore.profileCacheStore.putString("name", it) }
            setStr("action") { rule.action = it; DataStore.profileCacheStore.putString("action", it) }
            if (obj.has("outbound_id")) { val oid = obj.optLong("outbound_id"); rule.outbound_id = oid; DataStore.profileCacheStore.putString("outbound_id", oid.toString()); count++ }
            setBool("invert") { rule.invert = it; DataStore.profileCacheStore.putBoolean("invert", it) }
            setBool("no_drop") { rule.no_drop = it; DataStore.profileCacheStore.putBoolean("no_drop", it) }
            setBool("ip_is_private") { rule.ip_is_private = it; DataStore.profileCacheStore.putBoolean("ip_is_private", it) }
            setBool("source_ip_is_private") { rule.source_ip_is_private = it; DataStore.profileCacheStore.putBoolean("source_ip_is_private", it) }
            setBool("sniff_override_dest") { rule.sniff_override_dest = it; DataStore.profileCacheStore.putBoolean("sniff_override_dest", it) }
            setList("domain") { rule.domain = it; DataStore.profileCacheStore.putString("domain", it.joinToString("\n")) }
            setList("domain_suffix") { rule.domain_suffix = it; DataStore.profileCacheStore.putString("domain_suffix", it.joinToString("\n")) }
            setList("domain_keyword") { rule.domain_keyword = it; DataStore.profileCacheStore.putString("domain_keyword", it.joinToString("\n")) }
            setList("domain_regex") { rule.domain_regex = it; DataStore.profileCacheStore.putString("domain_regex", it.joinToString("\n")) }
            setList("ip_cidr") { rule.ip_cidr = it; DataStore.profileCacheStore.putString("ip_cidr", it.joinToString("\n")) }
            setList("source_ip_cidr") { rule.source_ip_cidr = it; DataStore.profileCacheStore.putString("source_ip_cidr", it.joinToString("\n")) }
            setList("port") { rule.port = it; DataStore.profileCacheStore.putString("port", it.joinToString("\n")) }
            setList("port_range") { rule.port_range = it; DataStore.profileCacheStore.putString("port_range", it.joinToString("\n")) }
            setList("source_port") { rule.source_port = it; DataStore.profileCacheStore.putString("source_port", it.joinToString("\n")) }
            setList("source_port_range") { rule.source_port_range = it; DataStore.profileCacheStore.putString("source_port_range", it.joinToString("\n")) }
            setList("process_name") { rule.process_name = it; DataStore.profileCacheStore.putString("process_name", it.joinToString("\n")) }
            setList("process_path") { rule.process_path = it; DataStore.profileCacheStore.putString("process_path", it.joinToString("\n")) }
            setList("process_path_regex") { rule.process_path_regex = it; DataStore.profileCacheStore.putString("process_path_regex", it.joinToString("\n")) }
            setList("package_name") { rule.package_name = it; DataStore.profileCacheStore.putString("package_name", it.joinToString("\n")) }
            setList("rule_set") { rule.rule_set = it; DataStore.profileCacheStore.putString("rule_set", it.joinToString("\n")) }
            setList("inbound") { rule.inbound = it; DataStore.profileCacheStore.putString("inbound", it.joinToString("\n")) }
            setList("wifi_ssid") { rule.wifi_ssid = it; DataStore.profileCacheStore.putString("wifi_ssid", it.joinToString("\n")) }
            setList("wifi_bssid") { rule.wifi_bssid = it; DataStore.profileCacheStore.putString("wifi_bssid", it.joinToString("\n")) }
            setList("sniffers") { rule.sniffers = it; DataStore.profileCacheStore.putString("sniffers", it.joinToString("\n")) }
            setList("default_interface_address") { rule.default_interface_address = it; DataStore.profileCacheStore.putString("default_interface_address", it.joinToString("\n")) }
            setList("dns_server") { rule.dns_server = it; DataStore.profileCacheStore.putString("dns_server", it.joinToString("\n")) }
            setList("balancer_sticky_hash") { rule.balancer_sticky_hash = it; DataStore.profileCacheStore.putString("balancer_sticky_hash", it.joinToString("\n")) }
            if (obj.has("rules")) {
                val rulesArr = obj.optJSONArray("rules")
                if (rulesArr != null) {
                    rule.rules_json = rulesArr.toString()
                    DataStore.profileCacheStore.putString("rules_json", rule.rules_json)
                    count++
                }
            }
            if (obj.has("pool")) { rule.balancer_pool = obj.optInt("pool"); DataStore.profileCacheStore.putString("balancer_pool", rule.balancer_pool.toString()); count++ }
            if (obj.has("pool_tolerance")) { rule.balancer_pool_tolerance = obj.optInt("pool_tolerance"); DataStore.profileCacheStore.putString("balancer_pool_tolerance", rule.balancer_pool_tolerance.toString()); count++ }
            if (obj.has("mode")) {
                val m = obj.optString("mode")
                if (m == "round_robin") { rule.balancer_mode = "round_robin"; DataStore.profileCacheStore.putString("balancer_mode", "round_robin") }
                if (m == "and" || m == "or") { rule.logical_mode = m; DataStore.profileCacheStore.putString("logical_mode", m) }
                if (m.isNotBlank() && m != "round_robin" && m != "and" && m != "or") { rule.balancer_mode = m; DataStore.profileCacheStore.putString("balancer_mode", m) }
                count++
            }
            return count
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
