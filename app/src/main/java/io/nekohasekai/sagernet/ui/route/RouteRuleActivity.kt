package io.nekohasekai.sagernet.ui.route

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.addCallback
import androidx.core.text.buildSpannedString
import androidx.core.text.color
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
import io.nekohasekai.sagernet.route.RuleIssue
import io.nekohasekai.sagernet.route.RuleSetCatalog
import io.nekohasekai.sagernet.route.RuleType
import io.nekohasekai.sagernet.ui.AppListActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.WifiPermissionFlow
import io.nekohasekai.sagernet.ui.profile.multilineInput
import io.nekohasekai.sagernet.ui.profile.portInput
import io.nekohasekai.sagernet.ui.profile.setVisible
import io.nekohasekai.sagernet.ui.settings.LinesSummaryProvider
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.nekohasekai.sagernet.validation.Severity
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
        private const val STATE_FRAGMENT_DELAY = "fragmentDelay"
        private const val KEY_ADVANCED_TOGGLE = "advancedToggle"
        private const val KEY_ADVANCED_CATEGORY = "advancedCategory"

        /** The Fragment TLS menu (RouteRuleEditor fragmentCombo): its value holds both flags as bits. */
        private const val KEY_FRAGMENT = "tls_fragment"
        private const val FRAGMENT_HANDSHAKE = 1
        private const val FRAGMENT_RECORDS = 2
        private const val FRAGMENT_BOTH = 3

        private val TEXT_KEYS = listOf(
            "name", "action", "outbound_id", "reject_method", "strategy", "network", "protocol", "ip_version",
            "override_address", "override_port", "tls_fragment_fallback_delay",
        )

        /** The single-value rows that show their own issues; the action row carries the whole-rule ones. */
        private val SCALAR_KEYS = TEXT_KEYS - setOf("name", "action")

        private val LIST_KEYS = listOf(
            "domain_suffix", "domain", "ip_cidr", "rule_set", "package_name", "network_type", "domain_keyword",
            "domain_regex", "source_ip_cidr", "port", "port_range", "source_port", "source_port_range", "inbound",
            "package_name_regex", "process_name", "process_path", "process_path_regex", "wifi_ssid", "wifi_bssid",
        )
        private val BOOL_KEYS = listOf(
            "sniff_override_dest", "ip_is_private", "source_ip_is_private", "invert", "no_drop", "network_is_expensive",
        )

        /** The members inside the collapsed group. */
        private val ADVANCED_KEYS = listOf(
            "domain_keyword", "domain_regex", "ip_is_private", "source_ip_cidr", "source_ip_is_private", "port",
            "port_range", "source_port", "source_port_range", "network", "protocol", "ip_version", "inbound", "invert",
            "override_address", "override_port", KEY_FRAGMENT, "tls_fragment_fallback_delay",
            "no_drop", "package_name_regex", "process_name", "process_path", "process_path_regex",
            "network_is_expensive", "wifi_ssid", "wifi_bssid",
        )

        /** Android names apps by package, never by process: these show only when a desktop rule brought a value. */
        private val DESKTOP_ONLY_KEYS = listOf("process_name", "process_path", "process_path_regex")

        /** The row that shows [attr]: both fragment flags are on the Fragment TLS menu. */
        private fun rowOf(attr: String): String = if (attr == "tls_record_fragment") KEY_FRAGMENT else attr

        private fun fragmentMode(handshake: Boolean, records: Boolean): Int =
            (if (handshake) FRAGMENT_HANDSHAKE else 0) or (if (records) FRAGMENT_RECORDS else 0)

        private fun cachedFragmentMode(): Int = DataStore.profileCacheStore.getString(KEY_FRAGMENT)?.toIntOrNull() ?: 0
    }

    private val pbm = PreferenceBindingManager().apply {
        for (key in TEXT_KEYS + LIST_KEYS) text(key)
        for (key in BOOL_KEYS) bool(key)
    }

    private lateinit var rule: RouteRule
    private var index = -1
    private var loaded = false
    private var advancedExpanded = false

    /** RouteRuleEditor Stash::fragmentDelay: the fallback delay a Fragment TLS choice without the handshake cleared. */
    private var fragmentDelay = ""

    /** Every server profile as (id, "[group] name"). */
    private var servers: List<Pair<Long, String>> = emptyList()

    /** The rule-set list, so rule_set names are judged while editing. */
    private var catalog: RuleSetCatalog? = null

    private var issueCache: List<RuleIssue>? = null

    /** RouteRule.checkValue per list row and line, shared by the row's error and warning bindings; dropped when the row changes. */
    private val lineChecks = HashMap<String, HashMap<String, RuleIssue?>>()

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
        fragmentDelay = savedInstanceState?.getString(STATE_FRAGMENT_DELAY).orEmpty()
        onBackPressedDispatcher.addCallback(this) { close() }

        val fresh = savedInstanceState == null
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                servers = RouteServers.list()
                catalog = RouteManager.catalog()
                PackageCache.awaitLoadSync()
                if (fresh) {
                    pbm.writeToCacheAll(rule)
                    DataStore.profileCacheStore.putString(
                        KEY_FRAGMENT, fragmentMode(rule.tls_fragment, rule.tls_record_fragment).toString(),
                    )
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
        outState.putString(STATE_FRAGMENT_DELAY, fragmentDelay)
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == Key.PROFILE_DIRTY) return
        issueCache = null
        DataStore.dirty = true
        runOnUiThread {
            lineChecks.remove(key)
            fragment?.refreshState()
            // RouteItem::scheduleRuleCheck: any field can change the whole-rule verdicts on the action row.
            fragment?.form?.revalidate("action")
        }
    }

    /** The rule as the screen shows it. */
    private fun editedRule(): RouteRule = rule.copy().also {
        pbm.fromCacheAll(it)
        it.name = it.name.trim()
        val mode = cachedFragmentMode()
        it.tls_fragment = (mode and FRAGMENT_HANDSHAKE) != 0
        it.tls_record_fragment = (mode and FRAGMENT_RECORDS) != 0
    }

    /** RouteRule::validate of [editedRule], kept until the next change in the cache store. */
    private fun currentIssues(): List<RuleIssue> =
        issueCache ?: RouteRuleChecks.issues(editedRule(), catalog).also { issueCache = it }

    private fun checkLine(key: String, line: String): RuleIssue? {
        val memo = lineChecks.getOrPut(key) { HashMap() }
        if (memo.containsKey(line)) return memo[line]
        return RouteRule.checkValue(key, line, catalog).also { memo[line] = it }
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
        // RouteItem::accept: errors keep the editor open, warnings never do.
        if (fragment?.form?.validateAll() == false) return
        val edited = editedRule()
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
            // RuleProblems made an enabled catch-all rule an error, so the form has refused it already.
            finishWith(edited)
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

        var form: ValidationForm? = null
            private set

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            // A fragment restored before the activity reloaded its data stays empty; the activity replaces it.
            if (!host.loaded) return
            addPreferencesFromResource(R.xml.route_rule_preferences)

            setupOutbounds()
            setupActions()
            for (key in listOf("action", "reject_method", "strategy", "network", "protocol", "ip_version")) {
                findPreference<SimpleMenuPreference>(key)?.ensureValue()
            }
            setupFragment()

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
            attachValidation()
        }

        /**
         * RouteItem::bindAttributeEditor and bindCurrentRule: list rows per line, single values by their own issues,
         * cross-field issues on both rows, and whole-rule issues (and those of rows not shown) on the action row.
         */
        private fun attachValidation() {
            val validation = ValidationForm(this)
            for (key in LIST_KEYS) {
                val check = { line: String -> host.checkLine(key, line) }
                validation.bindLines(key) { RouteRuleChecks.lineError(it, check) }.cached()
                validation.bind(key) { RouteRuleChecks.firstWarningLine(it, check) }.warning().raw().cached()
            }
            for (key in SCALAR_KEYS) {
                validation.bind(key) { RouteRuleChecks.fieldMessage(host.currentIssues(), key, Severity.Error) }
                validation.bind(key) { RouteRuleChecks.fieldMessage(host.currentIssues(), key, Severity.Warning) }.warning()
            }
            validation.bind("action") { wholeRuleMessage(Severity.Error) }
            validation.bind("action") { wholeRuleMessage(Severity.Warning) }.warning()
            for (shape in RouteRule.crossChecks()) {
                val keys = listOf(shape.attr, shape.related).map { rowOf(it) }.distinct()
                    .filter { findPreference<Preference>(it) != null }
                if (keys.isEmpty()) continue
                validation.addRule(keys, shape.severity) {
                    val issue = host.currentIssues().firstOrNull {
                        it.attr == shape.attr && it.related == shape.related && it.severity == shape.severity
                    } ?: return@addRule ""
                    // Marked on the other row only, so it names its own.
                    if (rowActive(shape.attr)) issue.message else RouteRuleChecks.describe(requireContext(), issue)
                }
            }
            validation.collapsed = { collapsedRow(it) }
            validation.revealer = { key ->
                val pref = findPreference<Preference>(key)
                if (pref != null && collapsedRow(pref)) {
                    host.advancedExpanded = true
                    refreshState()
                }
            }
            validation.start()
            if (host.index >= 0 || DataStore.dirty) validation.markAll()
            form = validation
        }

        private fun collapsedRow(pref: Preference): Boolean =
            !host.advancedExpanded && pref.isVisible && pref.parent?.key == KEY_ADVANCED_CATEGORY

        /** The form's own notion of a row it checks: shown (or only collapsed) and enabled. */
        private fun rowActive(key: String): Boolean {
            val pref = (if (key.isEmpty()) null else findPreference<Preference>(rowOf(key))) ?: return false
            return (pref.isShown || collapsedRow(pref)) && pref.isEnabled
        }

        private fun wholeRuleMessage(severity: Severity): String {
            for (issue in host.currentIssues()) {
                if (issue.severity != severity) continue
                if (issue.attr == "action" && issue.related.isEmpty()) return issue.message
                if (!rowActive(issue.attr) && !rowActive(issue.related)) return RouteRuleChecks.describe(requireContext(), issue)
            }
            return ""
        }

        /** The worst issue on a shown Advanced row, which the collapsed toggle reports like the desktop's tab icons. */
        private fun advancedSeverity(): Severity? {
            fun advanced(key: String) = rowOf(key).let { it in ADVANCED_KEYS && findPreference<Preference>(it)?.isVisible == true }
            var worst: Severity? = null
            for (issue in host.currentIssues()) {
                if (!advanced(issue.attr) && !advanced(issue.related)) continue
                if (issue.severity == Severity.Error) return Severity.Error
                worst = Severity.Warning
            }
            return worst
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.layoutManager = FixedLinearLayoutManager(listView)
            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)
        }

        override fun onResume() {
            super.onResume()
            if (preferenceScreen != null) {
                refreshWifiHint()
                form?.refresh()
            }
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

        /**
         * proxy, direct, block, Proxy without exit (warp-bypass, explained while chosen), then every server as
         * "[group] name"; stored values outside that list stay selectable.
         */
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
            add(getString(R.string.route_outbound_no_exit), OutboundIds.WARP_BYPASS)
            if (current == OutboundIds.HIJACK_DNS) add("hijack-dns", OutboundIds.HIJACK_DNS)
            for ((id, label) in host.servers) add(label, id)
            if (current > 0 && host.servers.none { it.first == current }) {
                add(getString(R.string.route_rule_missing_server, current), current)
            }
            pref.entries = entries.toTypedArray()
            pref.entryValues = values.toTypedArray()
            pref.value = current.toString()
            pref.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                val label = it.entry ?: it.value.orEmpty()
                if (it.value == OutboundIds.WARP_BYPASS.toString()) "$label\n${getString(R.string.route_outbound_no_exit_hint)}" else label
            }
        }

        /**
         * RouteThenPicker: Throne already sniffs and hijacks DNS before the profile's rules, so these are offered only
         * to a rule that has one.
         */
        private fun setupActions() {
            val pref = findPreference<SimpleMenuPreference>("action") ?: return
            val current = host.rule.effectiveAction()
            val actions = resources.getStringArray(R.array.route_rule_action_values)
                .filter { (it != "hijack-dns" && it != "sniff") || it == current }
                .toTypedArray<CharSequence>()
            pref.entries = actions
            pref.entryValues = actions
            pref.value?.let { pref.value = it }
        }

        /**
         * RouteRuleEditor::setFragment: the core reads the fallback delay only with tls_fragment, so a choice without
         * the handshake sets the delay aside until one with it is picked.
         */
        private fun setupFragment() {
            val pref = findPreference<SimpleMenuPreference>(KEY_FRAGMENT) ?: return
            pref.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> { p ->
                val mode = p.value?.toIntOrNull() ?: 0
                val lines = arrayListOf(p.entry ?: "")
                if ((mode and FRAGMENT_HANDSHAKE) != 0) lines.add(getString(R.string.route_rule_tls_fragment_sum))
                if ((mode and FRAGMENT_RECORDS) != 0) lines.add(getString(R.string.route_rule_tls_record_fragment_sum))
                lines.joinToString("\n")
            }
            pref.setOnPreferenceChangeListener { _, newValue ->
                val handshake = (((newValue as? String)?.toIntOrNull() ?: 0) and FRAGMENT_HANDSHAKE) != 0
                val delay = DataStore.profileCacheStore.getString("tls_fragment_fallback_delay").orEmpty()
                val delayPref = findPreference<EditTextPreference>("tls_fragment_fallback_delay")
                if (!handshake && delay.isNotBlank()) {
                    host.fragmentDelay = delay
                    delayPref?.text = ""
                } else if (handshake && delay.isBlank() && host.fragmentDelay.isNotEmpty()) {
                    delayPref?.text = host.fragmentDelay
                    host.fragmentDelay = ""
                }
                true
            }
        }

        /** RouteRuleEditor::loadFragment: route-options takes only one of them, so it offers both only to a rule that has both. */
        private fun refreshFragmentEntries(effective: String, mode: Int) {
            val pref = findPreference<SimpleMenuPreference>(KEY_FRAGMENT) ?: return
            val count = if (mode == FRAGMENT_BOTH || effective != "route-options") 4 else 3
            if (pref.entries?.size == count) return
            val labels = resources.getStringArray(R.array.route_rule_tls_fragment_entries)
            pref.entries = Array<CharSequence>(count) { labels[it] }
            pref.entryValues = Array<CharSequence>(count) { it.toString() }
            pref.value = mode.toString()
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
            val routeOptions = routes || effective == "route-options"
            setVisible(routeOptions, "override_address", "override_port", KEY_FRAGMENT)
            val mode = cachedFragmentMode()
            refreshFragmentEntries(effective, mode)
            // A delay left without tls_fragment stays visible, so its warning can be acted on.
            val delay = (mode and FRAGMENT_HANDSHAKE) != 0 || !store.getString("tls_fragment_fallback_delay").isNullOrBlank()
            setVisible(routeOptions && delay, "tls_fragment_fallback_delay")
            for (key in DESKTOP_ONLY_KEYS) findPreference<Preference>(key)?.isVisible = !store.getString(key).isNullOrBlank()

            val set = ADVANCED_KEYS.count { key ->
                val pref = findPreference<Preference>(key)
                when {
                    pref == null || !pref.isVisible -> false
                    key == KEY_FRAGMENT -> mode != 0
                    pref is SwitchPreference -> store.getBoolean(key, false)
                    else -> !store.getString(key).isNullOrBlank()
                }
            }
            val expanded = host.advancedExpanded
            findPreference<PreferenceCategory>(KEY_ADVANCED_CATEGORY)?.isVisible = expanded
            findPreference<Preference>(KEY_ADVANCED_TOGGLE)?.apply {
                title = if (set > 0) getString(R.string.route_rule_advanced_count, set) else getString(R.string.route_rule_advanced)
                val state = getString(if (expanded) R.string.route_rule_advanced_hide else R.string.route_rule_advanced_show)
                val worst = if (expanded) null else advancedSeverity()
                summary = if (worst == null) state else buildSpannedString {
                    append(state).append('\n')
                    color(ValidationForm.colorOf(requireContext(), worst)) {
                        append(getString(
                            if (worst == Severity.Error) R.string.validation_route_advanced_errors else R.string.validation_route_advanced_warnings
                        ))
                    }
                }
                setIcon(if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
            }
        }
    }
}
