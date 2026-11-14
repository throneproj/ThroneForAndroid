package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorTierBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlan
import io.nekohasekai.sagernet.outbound.config.AutoSelectorPlanner
import io.nekohasekai.sagernet.outbound.config.AutoSelectorSkip
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.AutoSelectorChecks
import io.nekohasekai.sagernet.ui.FormIssue
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EditAutoSelector (edit_autoselector.cpp + .ui): the tracked groups, the name filter, the strategy with its ordered
 * priority tiers, balancing, the preferred profile with "Use automatic", the live plan summary and the collapsed
 * Advanced groups with the desktop's limits. Saving goes through the validation form ([bindValidation]) and prunes
 * `pool` to the members that still qualify.
 */
class AutoSelectorSettingsActivity : BindingSettingsActivity<AutoSelector>() {

    private companion object {
        /** "Servers from": the checked group ids as a string set; [AutoSelector.gids] is not a binding. */
        const val KEY_GIDS = "gids"
        const val KEY_PINNED = "autoselPinned"
        const val KEY_PLAN = "autoselPlan"
        const val KEY_ADVANCED = "autoselAdvanced"
        const val KEY_BALANCE_HINT = "autoselBalanceHint"
        const val KEY_STRATEGY = "strategy"

        /** The "Add tier" row; the cache holds the edited tiers under it as `{"tiers":[…]}` ([AutoSelector.tiers] is not a binding). */
        const val KEY_TIERS = "tiers"
        const val STATE_ADVANCED = "autoselAdvancedExpanded"
        val ADVANCED_CATEGORIES =
            listOf("autoselMembership", "autoselHealth", "autoselSwitching", "autoselBalancing", "autoselEndpoints")
        val FAILBACK_KEYS = listOf("failbackChecks", "failbackDelaySec", "failbackInterrupt")

        /** The fields refreshPlanSummary reads. */
        val PLAN_KEYS = setOf(
            KEY_GIDS, "nameFilter", "countryFilter", "excludeUnavailable", "poolCap", "buildLimit", "resultValidityMins",
            KEY_STRATEGY, KEY_TIERS,
        )

        /** Top-level rows are this far apart in order, so the tier rows fit between the strategy and "Add tier". */
        const val ROW_ORDER_GAP = 1000

        /** A tier's row; Validate's `tiers[i]` issues land on it. */
        fun tierKey(index: Int) = "tiers[$index]"
    }

    /** A spin box of edit_autoselector.ui: its range, unit and the text of 0 when it has one. */
    private class IntField(
        val key: String,
        val min: Int,
        val max: Int,
        @PluralsRes val unit: Int = 0,
        @StringRes val zero: Int = 0,
    )

    private val intFields = listOf(
        IntField("buildLimit", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("poolCap", 1, AutoSelector.MAX_POOL_CAP, R.plurals.autosel_profiles),
        IntField("resultValidityMins", 0, 10080, R.plurals.autosel_minutes, R.string.autosel_always_retest),
        IntField("expected", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("activeSize", 1, AutoSelector.MAX_BUILD_LIMIT, R.plurals.autosel_profiles),
        IntField("intervalSec", 10, 3600, R.plurals.autosel_seconds),
        IntField("benchIntervalSec", 10, 86400, R.plurals.autosel_seconds),
        IntField("watchIntervalSec", 5, 3600, R.plurals.autosel_seconds),
        IntField("sampling", 2, 60),
        IntField("toleranceMs", 0, 10000, R.plurals.autosel_milliseconds),
        IntField("maxRTTms", 0, 60000, R.plurals.autosel_milliseconds, R.string.autosel_no_limit),
        IntField("dialRetries", 0, 5),
        IntField("failbackChecks", 1, 60, R.plurals.autosel_checks),
        IntField("failbackDelaySec", 1, 3600, R.plurals.autosel_seconds),
        IntField("siteMemoryMins", 1, 1440, R.plurals.autosel_minutes),
        IntField("balanceIntervalSec", 5, 3600, R.plurals.autosel_seconds),
    )

    override fun createEntity() = AutoSelector().apply { gids = arrayListOf(DataStore.editingGroup) }
    override val preferencesResource = R.xml.autoselector_preferences

    // Like a chain, a selector is not a sing-box outbound the schema could check, and its JSON holds local ids.
    override val supportsRawJson = false

    init {
        pbm.text("name")
        pbm.text("nameFilter")
        pbm.bool("balance")
        pbm.text("countryFilter")
        pbm.bool("excludeUnavailable")
        pbm.bool("interruptOnSwitch")
        pbm.text(KEY_STRATEGY)
        pbm.bool("failbackInterrupt")
        pbm.bool("passive")
        pbm.text("balanceMode")
        pbm.text("testURL")
        pbm.text("connectivityURL")
        for (field in intFields) pbm.int(field.key)
    }

    /** The pin as edited: "Use automatic" clears it at once, like the desktop. */
    private var pinnedId = -1L
    private var advancedExpanded = false
    private var balanceOn = false
    private var balanceMode = "rotate"
    private var strategy = AutoSelector.STRATEGY_LATENCY
    private val prioritySelected get() = strategy == AutoSelector.STRATEGY_PRIORITY
    private var planJob: Job? = null
    private var fragment: PreferenceFragmentCompat? = null
    private val tierRows = ArrayList<Preference>()

    /** Every group id, archived ones included; at least one checked group must be among them. */
    private var knownGroups: Set<Long> = emptySet()

    /** The groups "Servers from" lists, by id with their entry text: a tier's group choices. */
    private var listedGroups: List<Pair<Long, CharSequence>> = emptyList()

    /** Every group id in tab order: the order checked groups are saved in. */
    @Volatile
    private var groupOrder: List<Long> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false
        super.onCreate(savedInstanceState)
        supportActionBar?.setTitle(R.string.autosel_title)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED, advancedExpanded)
    }

    override fun AutoSelector.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putStringSet(KEY_GIDS, gids.mapTo(LinkedHashSet()) { it.toString() })
        putTiers(tiers)
    }

    override fun AutoSelector.serialize() {
        pbm.fromCacheAll(this)
        gids = checkedGroups(gids)
        tiers = cachedTiers()
        nameFilter = nameFilter.trim()
        countryFilter = countryFilter.trim()
        testURL = testURL.trim()
        connectivityURL = connectivityURL.trim()
        pinnedID = pinnedId
        val id = DataStore.editingId
        // The runtime writes these while the editor is open (after a start and after a ranking).
        if (id > 0) AutoSelectorProfiles.load(id)?.let { stored ->
            pool = stored.pool
            poolRankedAt = stored.poolRankedAt
            lastBuilt = stored.lastBuilt
            lastBuiltAt = stored.lastBuiltAt
            history = stored.history
        }
        normalize()
        // The pool is a ranking, so a filter change can leave entries that no longer qualify.
        val eligible = AutoSelectorPlanner(SettingsMapper.selectorStore()).rankingCandidates(id, this).toHashSet()
        pool = pool.filterTo(ArrayList()) { it in eligible }
    }

    /** serialize() runs the planner and reads the database, so Validate gets the screen's fields on a plain copy. */
    override fun validationOutbound(): AutoSelector {
        val copy = createEntity()
        copy.parseFromJson(ensureEditingOutbound().exportToJson())
        pbm.fromCacheAll(copy)
        copy.gids = checkedGroups(copy.gids)
        copy.tiers = cachedTiers()
        copy.nameFilter = copy.nameFilter.trim()
        copy.countryFilter = copy.countryFilter.trim()
        copy.testURL = copy.testURL.trim()
        copy.connectivityURL = copy.connectivityURL.trim()
        return copy
    }

    /**
     * Validate's `tiers[i]` issues sit on that tier's row ("Tier N: …" in the save dialog, like the desktop's tier rule),
     * its `tiers` issue on "Add tier".
     */
    override fun issueKey(field: String): String? = if (field.startsWith("tiers[")) field.substringBefore('.') else null

    /** edit_autoselector.cpp bindValidation plus onEnd's name check; AutoSelector.validate covers the filters, URLs and tiers. */
    override fun ValidationForm.bindValidation() {
        bind("name") { if (it.isEmpty()) getString(R.string.autosel_name_empty) else "" }
        // The form reads a row's text, which a multi-select does not have, so the rule reads the checked set itself.
        bind(KEY_GIDS) { _ -> if (checkedIds().any { it in knownGroups }) "" else getString(R.string.autosel_select_group) }
        // Rows of the collapsed Advanced section are skipped by rules, so these always run and reveal the section.
        addIssueSource {
            fun value(key: String) = DataStore.profileCacheStore.getString(key)?.trim()?.toIntOrNull() ?: 0
            val buildLimit = value("buildLimit")
            val interval = value("intervalSec")
            val activeSize = value("activeSize")
            listOfNotNull(
                crossIssue("buildLimit", "poolCap", AutoSelectorChecks.autoSelectorBuildLimit(buildLimit, value("poolCap"))),
                crossIssue("benchIntervalSec", "intervalSec", AutoSelectorChecks.autoSelectorBenchInterval(value("benchIntervalSec"), interval)),
                crossIssue("watchIntervalSec", "intervalSec", AutoSelectorChecks.autoSelectorWatchInterval(value("watchIntervalSec"), interval)),
                crossIssue("activeSize", "expected", AutoSelectorChecks.autoSelectorReadyCount(activeSize, value("expected"))),
                crossIssue("activeSize", "buildLimit", AutoSelectorChecks.autoSelectorCloseCount(activeSize, buildLimit)),
                // Sampling stays checkable in latency mode, where failback is not used.
                if (!prioritySelected) null else crossIssue(
                    "failbackChecks", "sampling",
                    AutoSelectorChecks.autoSelectorFailbackChecks(value("failbackChecks"), value("sampling")),
                ),
            )
        }
        revealer = { key ->
            val section = fragment?.findPreference<Preference>(key)?.parent?.key
            if (!advancedExpanded && section != null && section in ADVANCED_CATEGORIES) {
                advancedExpanded = true
                refreshAdvanced()
            }
        }
    }

    private fun crossIssue(key: String, other: String, message: String): FormIssue? {
        if (message.isEmpty()) return null
        val label = fragment?.findPreference<Preference>(key)?.title?.toString() ?: key
        return FormIssue(Severity.Error, label, message, key, listOf(other))
    }

    override fun PreferenceFragmentCompat.onPreferencesCreated() {
        fragment = this
        val stored = ensureEditingOutbound()
        pinnedId = stored.pinnedID
        // edit_autoselector.cpp onStart: a stored bad value in the collapsed section is shown, not hidden.
        if (AutoSelectorChecks.autoSelectorCountries(stored.countryFilter).isNotEmpty() ||
            AutoSelectorChecks.autoSelectorUrl(stored.testURL).isNotEmpty() ||
            AutoSelectorChecks.autoSelectorUrl(stored.connectivityURL).isNotEmpty()
        ) advancedExpanded = true
        tierRows.clear()
        renumberRows()
        setupGroups()
        setupTexts()
        for (field in intFields) setupInt(field)
        findPreference<ListPreference>(KEY_STRATEGY)?.summaryProvider = Preference.SummaryProvider<ListPreference> {
            getString(
                if (it.value == AutoSelector.STRATEGY_PRIORITY) R.string.autosel_strategy_priority_tip
                else R.string.autosel_strategy_latency_tip
            )
        }
        findPreference<Preference>(KEY_TIERS)?.setOnPreferenceClickListener {
            editTier(-1)
            true
        }
        // The change listeners run before the new value is stored, so the state comes from their arguments.
        onSwitch("balance") {
            balanceOn = it
            refreshBalance()
        }
        onMenu("balanceMode") {
            balanceMode = it
            refreshBalance()
        }
        onMenu(KEY_STRATEGY) {
            strategy = it
            refreshStrategy()
            refreshBalance()
        }
        onSwitch("passive") { findPreference<Preference>("siteMemoryMins")?.isEnabled = it }
        findPreference<Preference>(KEY_PINNED)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(this@AutoSelectorSettingsActivity)
                .setTitle(R.string.autosel_pinned)
                .setMessage(R.string.autosel_use_automatic_tip)
                .setPositiveButton(R.string.autosel_use_automatic) { _, _ -> releasePin() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
        findPreference<Preference>(KEY_ADVANCED)?.setOnPreferenceClickListener {
            advancedExpanded = !advancedExpanded
            refreshAdvanced()
            true
        }
        refreshAdvanced()
        refreshPinned()
        refreshPlan()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        super.onPreferenceDataStoreChanged(store, key)
        if (key in PLAN_KEYS) runOnMainDispatcher { refreshPlan() }
    }

    override fun onDestroy() {
        fragment = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** "Servers from": non-archived groups in tab order; a checked group outside that list stays listed, so it can be unchecked. */
    private fun PreferenceFragmentCompat.setupGroups() {
        val pref = findPreference<MultiSelectListPreference>(KEY_GIDS) ?: return
        val groups = GroupRepo.all()
        knownGroups = groups.mapTo(HashSet()) { it.id }
        groupOrder = groups.map { it.id }
        val checked = checkedIds()
        val entries = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        val listed = ArrayList<Pair<Long, CharSequence>>()
        for (group in groups) {
            if (group.archive && group.id !in checked) continue
            entries.add(if (group.archive) getString(R.string.autosel_group_archived, group.displayName()) else group.displayName())
            values.add(group.id.toString())
            listed.add(group.id to entries.last())
        }
        for (id in checkedGroups(ensureEditingOutbound().gids)) {
            if (id in knownGroups) continue
            entries.add(getString(R.string.autosel_group_missing, id))
            values.add(id.toString())
            listed.add(id to entries.last())
        }
        listedGroups = listed
        pref.entries = entries.toTypedArray()
        pref.entryValues = values.toTypedArray()
        pref.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
            val chosen = p.values
            p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                .joinToString(getString(R.string.autosel_list_separator)) { p.entries[it] }
                .ifEmpty { getString(R.string.autosel_groups_none) }
        }
    }

    /** The ids checked in "Servers from". */
    private fun checkedIds(): Set<Long> = DataStore.profileCacheStore.getStringSet(KEY_GIDS).orEmpty()
        .mapNotNullTo(HashSet()) { text -> text.trim().toLongOrNull()?.takeIf { it >= 0 } }

    /** The checked groups in tab order; a checked id no group has any more follows, in its [previous] order. */
    private fun checkedGroups(previous: List<Long>): MutableList<Long> {
        val checked = checkedIds()
        val order = groupOrder.ifEmpty { GroupRepo.all().map { it.id } }
        val out = order.filterTo(ArrayList()) { it in checked }
        for (id in previous + checked.sorted()) if (id in checked && id !in out) out.add(id)
        return out
    }

    private fun PreferenceFragmentCompat.setupTexts() {
        fun summary(key: String, empty: () -> CharSequence) {
            findPreference<EditTextPreference>(key)?.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.trim().orEmpty().ifEmpty { empty().toString() }
            }
        }
        summary("nameFilter") { getString(R.string.autosel_name_filter_empty) }
        summary("countryFilter") { getString(R.string.autosel_country_filter_empty) }
        summary("testURL") { getString(R.string.autosel_test_url_empty, DataStore.testUrl) }
        summary("connectivityURL") {
            val direct = DataStore.directTestUrl
            if (direct.isBlank()) getString(R.string.autosel_connectivity_url_none)
            else getString(R.string.autosel_connectivity_url_empty, direct)
        }
    }

    /** A spin box: numbers only, out-of-range input clamped, the unit and the special 0 text in the summary. */
    private fun PreferenceFragmentCompat.setupInt(field: IntField) {
        val pref = findPreference<EditTextPreference>(field.key) ?: return
        pref.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        pref.setOnPreferenceChangeListener { preference, newValue ->
            val typed = (newValue as? String)?.trim()?.toIntOrNull()
            val current = (preference as EditTextPreference).text?.trim()?.toIntOrNull() ?: field.min
            val clamped = (typed ?: current).coerceIn(field.min, field.max)
            if (clamped.toString() == newValue) return@setOnPreferenceChangeListener true
            preference.text = clamped.toString()
            false
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
            val value = it.text?.trim()?.toIntOrNull() ?: 0
            when {
                value == 0 && field.zero != 0 -> getString(field.zero)
                field.unit != 0 -> resources.getQuantityString(field.unit, value, value)
                else -> value.toString()
            }
        }
    }

    /** updateBalanceEnabled (cpp:526-546). */
    private fun refreshBalance() {
        val fragment = fragment ?: return
        val on = balanceOn
        val rotating = on && balanceMode != "connection"
        fragment.findPreference<Preference>("balanceMode")?.isEnabled = on
        fragment.findPreference<Preference>("balanceIntervalSec")?.isEnabled = rotating
        val hint = getString(
            when {
                !on -> R.string.autosel_balance_hint_off
                rotating -> R.string.autosel_balance_hint_rotate
                else -> R.string.autosel_balance_hint_connection
            }
        )
        fragment.findPreference<Preference>(KEY_BALANCE_HINT)?.summary =
            if (on && prioritySelected) hint + " " + getString(R.string.autosel_balance_hint_priority) else hint
    }

    /** updateStrategyControls (cpp:381-389): the tiers only show, and the failback rows only work, in priority order. */
    private fun refreshStrategy() {
        val fragment = fragment ?: return
        fragment.findPreference<Preference>(KEY_TIERS)?.isVisible = prioritySelected
        for (key in FAILBACK_KEYS) fragment.findPreference<Preference>(key)?.isEnabled = prioritySelected
        refreshTierRows()
    }

    // ------------------------------------------------------------------------------------------------ tiers

    /** Top-level rows get [ROW_ORDER_GAP] apart in their XML order, leaving room for the tier rows. */
    private fun PreferenceFragmentCompat.renumberRows() {
        val screen = preferenceScreen ?: return
        for (i in 0 until screen.preferenceCount) screen.getPreference(i).order = (i + 1) * ROW_ORDER_GAP
    }

    private fun putTiers(tiers: List<AutoSelector.Tier>) {
        val array = JsonArray()
        for (tier in tiers) array.add(jsonObjectOf("gid" to tier.gid, "name_filter" to tier.nameFilter))
        DataStore.profileCacheStore.putString(KEY_TIERS, jsonObjectOf("tiers" to array).toCompact())
    }

    /** The tiers as edited, read back the way the profile parses them. */
    private fun cachedTiers(): MutableList<AutoSelector.Tier> {
        val parsed = AutoSelector()
        parsed.parseFromJson(JsonInput.parseObject(DataStore.profileCacheStore.getString(KEY_TIERS).orEmpty()))
        return parsed.tiers
    }

    /** setTierRows: one row per tier between the strategy and "Add tier", each opening [editTier]. */
    private fun refreshTierRows() {
        val fragment = fragment ?: return
        val screen = fragment.preferenceScreen ?: return
        val strategyRow = fragment.findPreference<Preference>(KEY_STRATEGY) ?: return
        for (row in tierRows) screen.removePreference(row)
        tierRows.clear()
        for ((i, tier) in cachedTiers().withIndex()) {
            val row = Preference(fragment.preferenceManager.context)
            row.key = tierKey(i)
            row.order = strategyRow.order + 1 + i
            row.isPersistent = false
            row.title = getString(R.string.autosel_tier_title, i + 1)
            row.summary = tier.label()
            row.isVisible = prioritySelected
            row.setOnPreferenceClickListener {
                editTier(i)
                true
            }
            screen.addPreference(row)
            tierRows.add(row)
        }
        form?.refresh()
    }

    /**
     * A tier of the table (appendTierRow, tier_remove, moveTier): its group ("Any group", the groups "Servers from"
     * lists, a stored unlisted one kept) and name pattern; [index] -1 appends a new one.
     */
    private fun editTier(index: Int) {
        val tiers = cachedTiers()
        val tier = tiers.getOrNull(index) ?: AutoSelector.Tier()
        val ids = arrayListOf(-1L)
        val names = arrayListOf<CharSequence>(getString(R.string.autosel_tier_any_group))
        for ((id, name) in listedGroups) {
            ids.add(id)
            names.add(name)
        }
        // Kept selectable so saving never turns a tier for an unlisted group into "any group".
        if (tier.gid >= 0 && tier.gid !in ids) {
            ids.add(tier.gid)
            names.add(AutoSelector.Tier(tier.gid).label())
        }
        var chosen = ids.indexOf(tier.gid).coerceAtLeast(0)

        val binding = LayoutAutoSelectorTierBinding.inflate(layoutInflater)
        binding.group.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names))
        binding.group.setText(names[chosen], false)
        binding.group.setOnItemClickListener { _, _, position, _ -> chosen = position }
        binding.names.doAfterTextChanged {
            binding.namesLayout.error = Validation.regex(it?.toString().orEmpty()).ifEmpty { null }
        }
        binding.names.setText(tier.nameFilter)
        binding.move.isVisible = index >= 0
        binding.moveUp.isEnabled = index > 0
        binding.moveDown.isEnabled = index >= 0 && index < tiers.size - 1

        fun edited() = AutoSelector.Tier(ids[chosen], binding.names.text?.toString().orEmpty().trim())
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.autosel_tier_title, (if (index >= 0) index else tiers.size) + 1))
            .setView(binding.root)
            .setPositiveButton(android.R.string.ok) { _, _ -> applyTier(index, edited(), 0) }
            .setNegativeButton(android.R.string.cancel, null)
        if (index >= 0) builder.setNeutralButton(R.string.autosel_tier_remove) { _, _ -> removeTier(index) }
        val dialog = builder.show()
        binding.moveUp.setOnClickListener {
            applyTier(index, edited(), -1)
            dialog.dismiss()
        }
        binding.moveDown.setOnClickListener {
            applyTier(index, edited(), 1)
            dialog.dismiss()
        }
    }

    /** onTiersChanged: [tier] stored at [index] (-1 appends) and moved by [offset], then the rows, the check and the plan. */
    private fun applyTier(index: Int, tier: AutoSelector.Tier, offset: Int) {
        val tiers = cachedTiers()
        if (index !in tiers.indices) {
            tiers.add(tier)
        } else {
            tiers[index] = tier
            if (index + offset in tiers.indices) Collections.swap(tiers, index, index + offset)
        }
        storeTiers(tiers)
    }

    private fun removeTier(index: Int) {
        val tiers = cachedTiers()
        if (index !in tiers.indices) return
        tiers.removeAt(index)
        storeTiers(tiers)
    }

    // The desktop checks the whole table on every change, so every tier row is revalidated.
    private fun storeTiers(tiers: List<AutoSelector.Tier>) {
        putTiers(tiers)
        refreshTierRows()
        val form = form ?: return
        form.revalidate(KEY_TIERS)
        for (i in tiers.indices) form.revalidate(tierKey(i))
    }

    private fun refreshAdvanced() {
        val fragment = fragment ?: return
        for (key in ADVANCED_CATEGORIES) fragment.findPreference<PreferenceCategory>(key)?.isVisible = advancedExpanded
        fragment.findPreference<Preference>(KEY_ADVANCED)?.apply {
            summary = getString(if (advancedExpanded) R.string.autosel_advanced_hide else R.string.autosel_advanced_show)
            setIcon(if (advancedExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        }
        form?.refresh()
    }

    /** refreshPinnedRow (cpp:205-218): shown only while a member is pinned. */
    private fun refreshPinned() {
        val row = fragment?.findPreference<Preference>(KEY_PINNED) ?: return
        row.isVisible = pinnedId >= 0
        if (pinnedId < 0) return
        val id = pinnedId
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { ProfileManager.getProfile(id)?.outbound?.displayName() }.getOrNull()
            }
            if (pinnedId == id) row.summary = getString(
                R.string.autosel_pinned_summary, name ?: getString(R.string.autosel_pinned_missing)
            )
        }
    }

    /** Unlike the rest of the form, releasing the pin is written at once, and a running core releases its own copy. */
    private fun releasePin() {
        pinnedId = -1
        refreshPinned()
        val id = DataStore.editingId
        runOnDefaultDispatcher {
            try {
                ensureEditingOutbound().pinnedID = -1
                if (id <= 0) return@runOnDefaultDispatcher
                val stored = AutoSelectorProfiles.load(id) ?: return@runOnDefaultDispatcher
                if (stored.pinnedID >= 0) {
                    stored.pinnedID = -1
                    AutoSelectorProfiles.save(id, stored)
                }
                val release = Intent(Action.AUTO_SELECTOR_AUTOMATIC).putExtra(Action.EXTRA_PROFILE_ID, id)
                sendBroadcast(release.setPackage(packageName))
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ plan

    /** refreshPlanSummary (cpp:242-295) on a throwaway copy: planning normalises what it gets. */
    private fun refreshPlan() {
        val row = fragment?.findPreference<Preference>(KEY_PLAN) ?: return
        val store = DataStore.profileCacheStore
        val preview = AutoSelector()
        preview.parseFromJson(ensureEditingOutbound().exportToJson())
        preview.gids = checkedGroups(preview.gids)
        preview.nameFilter = store.getString("nameFilter").orEmpty().trim()
        preview.countryFilter = store.getString("countryFilter").orEmpty().trim()
        preview.excludeUnavailable = store.getBoolean("excludeUnavailable", true)
        store.getString("poolCap")?.trim()?.toIntOrNull()?.let { preview.poolCap = it }
        store.getString("buildLimit")?.trim()?.toIntOrNull()?.let { preview.buildLimit = it }
        store.getString("resultValidityMins")?.trim()?.toIntOrNull()?.let { preview.resultValidityMins = it }
        store.getString(KEY_STRATEGY)?.let { preview.strategy = it }
        preview.tiers = cachedTiers()
        val selectorId = DataStore.editingId
        planJob?.cancel()
        planJob = lifecycleScope.launch {
            delay(150)
            val plan = withContext(Dispatchers.IO) {
                runCatching { AutoSelectorPlanner(SettingsMapper.selectorStore()).plan(selectorId, preview) }
                    .onFailure { Logs.w(it) }.getOrNull()
            } ?: return@launch
            row.summary = describe(plan)
        }
    }

    private fun describe(plan: AutoSelectorPlan): String {
        if (!plan.ok) {
            return getString(
                when {
                    plan.groups.isNotEmpty() -> R.string.autosel_error_no_members
                    plan.missingGroups > 0 -> R.string.autosel_error_no_group
                    else -> R.string.autosel_error_no_tracked_group
                }
            )
        }
        val lines = ArrayList<String>()
        lines.add(
            if (plan.groups.size > 1) {
                getString(R.string.autosel_plan_counts_groups, plan.eligible, plan.membersInGroup, plan.groups.size, plan.build.size)
            } else {
                getString(R.string.autosel_plan_counts, plan.eligible, plan.membersInGroup, plan.build.size)
            }
        )
        val separator = getString(R.string.autosel_list_separator)
        if (plan.tierBuilt.isNotEmpty()) {
            val rest = plan.tierBuilt.size - 1
            val counts = ArrayList<String>()
            val empty = ArrayList<String>()
            val crowded = ArrayList<String>()
            for (i in 0..rest) {
                val tier = if (i < rest) (i + 1).toString() else getString(R.string.autosel_tier_everything_else)
                counts.add(getString(R.string.autosel_plan_tier_count, tier, plan.tierBuilt[i]))
                if (i < rest && plan.tierBuilt[i] == 0) {
                    (if (plan.tierEligible.getOrElse(i) { 0 } > 0) crowded else empty).add((i + 1).toString())
                }
            }
            lines.add(getString(R.string.autosel_plan_by_tier, counts.joinToString(separator)))
            if (empty.size == 1) lines.add(getString(R.string.autosel_plan_tier_empty, empty[0]))
            if (empty.size > 1) lines.add(getString(R.string.autosel_plan_tiers_empty, empty.joinToString(separator)))
            if (crowded.size == 1) lines.add(getString(R.string.autosel_plan_tier_crowded, crowded[0]))
            if (crowded.size > 1) lines.add(getString(R.string.autosel_plan_tiers_crowded, crowded.joinToString(separator)))
        }
        if (plan.missingGroups > 0) {
            lines.add(resources.getQuantityString(R.plurals.autosel_plan_missing_groups, plan.missingGroups, plan.missingGroups))
        }
        if (plan.skipped.isNotEmpty()) {
            val reasons = plan.skipped.joinToString(separator) { (skip, count) ->
                getString(R.string.autosel_plan_skip_item, count, getString(skipText(skip)))
            }
            lines.add(getString(R.string.autosel_plan_skipped, reasons))
        }
        if (plan.keptUnavailable > 0) lines.add(getString(R.string.autosel_plan_kept_unavailable, plan.keptUnavailable))
        if (plan.truncated) {
            val text = if (plan.tierBuilt.isNotEmpty()) R.string.autosel_plan_truncated_tiers else R.string.autosel_plan_truncated
            lines.add(getString(text, plan.poolCapUsed))
        }
        if (plan.rankedByTest > 0) lines.add(getString(R.string.autosel_plan_reused, plan.rankedByTest))
        if (plan.needsRanking) lines.add(getString(R.string.autosel_plan_needs_ranking))
        return lines.joinToString(" ")
    }

    /** AutoSelectorSkipReason (AutoSelectorPlan.cpp:225-243). */
    @StringRes
    private fun skipText(skip: AutoSelectorSkip): Int = when (skip) {
        AutoSelectorSkip.Missing -> R.string.autosel_skip_missing
        AutoSelectorSkip.MetaType -> R.string.autosel_skip_meta
        AutoSelectorSkip.CoreTransitions -> R.string.autosel_skip_core_transitions
        AutoSelectorSkip.ExtraCore -> R.string.autosel_skip_extra_core
        AutoSelectorSkip.FullConfig -> R.string.autosel_skip_full_config
        AutoSelectorSkip.Malformed -> R.string.autosel_skip_malformed
        AutoSelectorSkip.Tailscale -> R.string.autosel_skip_tailscale
        AutoSelectorSkip.ManagementEndpoint -> R.string.autosel_skip_management
        AutoSelectorSkip.NameFilter -> R.string.autosel_skip_name
        AutoSelectorSkip.CountryFilter -> R.string.autosel_skip_country
        AutoSelectorSkip.Unavailable -> R.string.autosel_skip_unavailable
        AutoSelectorSkip.XrayFullChained -> R.string.autosel_skip_xray_chained
        AutoSelectorSkip.Unsupported -> R.string.autosel_skip_unsupported
    }
}
