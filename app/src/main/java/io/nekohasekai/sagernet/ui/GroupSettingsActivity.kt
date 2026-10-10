package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SendHwid
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.DeviceDetails
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionNameFilter
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ui.settings.DefaultSummaryProvider
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Edit Group (dialog_edit_group + dialog_edit_group_advanced): Common, Subscription and the collapsed Advanced
 * subscription options. The type is chosen only while creating; a new group is saved without being updated.
 * Fields live in the profile cache store while editing.
 */
class GroupSettingsActivity : ThemedActivity(R.layout.layout_config_settings), OnPreferenceDataStoreChangeListener {

    companion object {
        const val EXTRA_GROUP_ID = "id"

        /** Ignored: a group created here is never updated right away (DialogEditGroup). */
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"

        /** A new group starts as a subscription with this URL. */
        const val EXTRA_GROUP_SUBSCRIPTION_LINK = "subscription_link"

        /** Values of the "Type" menu (@array/group_types). */
        const val TYPE_BASIC = 0
        const val TYPE_SUBSCRIPTION = 1

        private const val STATE_ADVANCED = "advancedExpanded"
        private const val AUTO_SELECTOR = "autoselector"

        private const val KEY_FRONT = "groupFrontProxyId"
        private const val KEY_LANDING = "groupLandingProxyId"
        private const val KEY_AUTO_CLEAR = "groupAutoClearUnavailable"
        private const val KEY_SUBSCRIPTION = "groupSubscription"
        private const val KEY_SKIP_AUTO_UPDATE = "groupSkipAutoUpdate"
        private const val KEY_SHOW_INFO_CARD = "groupSubShowInfoCard"
        private const val KEY_ADVANCED_TOGGLE = "groupAdvancedToggle"
        private val ADVANCED_CATEGORIES = listOf("groupAdvancedRequest", "groupAdvancedUpdate", "groupAdvancedAfterUpdate")
        private const val KEY_USER_AGENT = "groupSubUserAgent"
        private const val KEY_SEND_HWID = "groupSubSendHwid"
        private const val KEY_HWID = "groupSubHwid"
        private const val KEY_HWID_OS = "groupSubHwidOs"
        private const val KEY_HWID_OS_VERSION = "groupSubHwidOsVersion"
        private const val KEY_HWID_MODEL = "groupSubHwidModel"
        private const val KEY_KEEP_WORKING = "groupSubKeepWorking"
        private const val KEY_REMOVE_DUPLICATES = "groupSubRemoveDuplicates"
        private const val KEY_REMOVE_INSECURE = "groupSubRemoveInsecure"
        private const val KEY_REMOVE_INVALID = "groupSubRemoveInvalid"
        private const val KEY_URL_TEST = "groupSubUrlTest"
        private const val KEY_REMOVE_UNAVAILABLE = "groupSubRemoveUnavailable"
        private const val KEY_SORT_BY_LATENCY = "groupSubSortByLatency"
        private const val KEY_FILTER = "groupFilter"
        private const val KEY_NAME_INCLUDE = "groupSubNameInclude"
        private const val KEY_NAME_EXCLUDE = "groupSubNameExclude"
        private val NAME_FILTERS = listOf(
            KEY_NAME_INCLUDE to R.string.grp_filter_include,
            KEY_NAME_EXCLUDE to R.string.grp_filter_exclude,
        )

        private val HWID_KEYS = listOf(KEY_HWID, KEY_HWID_OS, KEY_HWID_OS_VERSION, KEY_HWID_MODEL)
        private val OPTION_TEXT_KEYS = listOf(KEY_USER_AGENT) + HWID_KEYS
        private val OPTION_BOOL_KEYS = listOf(
            KEY_KEEP_WORKING, KEY_REMOVE_DUPLICATES, KEY_REMOVE_INSECURE, KEY_REMOVE_INVALID, KEY_URL_TEST,
            KEY_REMOVE_UNAVAILABLE, KEY_SORT_BY_LATENCY,
        )
    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "•".repeat(text.length)
            }
        }
    }

    private enum class SaveResult { OK, GONE, URL_REQUIRED }

    private var editingId = 0L
    private var loaded = false
    private var advancedExpanded = false
    private var groupCount = 0

    /** ResolveIdentity(nullptr), resolved while loading: the Advanced placeholders and the "Keep Default" state. */
    private var defaultUserAgent = ""
    private var defaultDevice = DeviceDetails("", "", "", "")
    private var globalSendHwid = false

    /** "[group] name" of the profiles the front/landing menus offer. */
    private val proxyLabels = HashMap<Long, String>()

    private val fragment get() = supportFragmentManager.findFragmentById(R.id.settings) as? GroupPreferenceFragment

    private val selectFront = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onProfilePicked(KEY_FRONT, it)
    }

    private val selectLanding = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onProfilePicked(KEY_LANDING, it)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.grp_edit_group)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        editingId = intent.getLongExtra(EXTRA_GROUP_ID, 0L)
        advancedExpanded = savedInstanceState?.getBoolean(STATE_ADVANCED) ?: false
        onBackPressedDispatcher.addCallback(this) { close() }

        val fresh = savedInstanceState == null
        val link = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext false else ProxyGroup()
                if (editingId > 0) resetDanglingProxies(group)
                if (fresh) {
                    writeToCache(group)
                    val prefilled = editingId == 0L && !link.isNullOrBlank()
                    if (prefilled) {
                        DataStore.groupType = TYPE_SUBSCRIPTION
                        DataStore.subscriptionLink = link!!.trim()
                    }
                    DataStore.dirty = prefilled
                }
                groupCount = GroupRepo.ids().size
                val identity = RequestIdentity.resolve(null)
                defaultUserAgent = identity.userAgent
                defaultDevice = identity.device
                globalSendHwid = identity.sendHwid
                for (key in listOf(KEY_FRONT, KEY_LANDING)) {
                    val id = proxyId(key)
                    if (id > 0) proxyLabel(id)?.let { proxyLabels[id] = it }
                }
                true
            }
            if (!found) {
                finish()
                return@launch
            }
            loaded = true
            invalidateOptionsMenu()
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, GroupPreferenceFragment())
                .commitNowAllowingStateLoss()
            DataStore.profileCacheStore.registerChangeListener(this@GroupSettingsActivity)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // A new group's delete discards it; the last group cannot be removed.
        menu.findItem(R.id.action_delete)?.isVisible = loaded && (editingId == 0L || groupCount > 1)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> {
                if (loaded) lifecycleScope.launch { save() }
                return true
            }

            R.id.action_delete -> {
                if (editingId == 0L) finish() else confirmRemove()
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
            .setPositiveButton(R.string.save) { _, _ -> lifecycleScope.launch { save() } }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmRemove() {
        val name = ProxyGroup(name = DataStore.groupName.trim()).displayName()
        confirmAction(getString(R.string.confirm_remove_group), name, R.string.delete) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { GroupRepo.delete(editingId) }
                finish()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ load / save

    /** dialog_edit_group.cpp:42-49: a front/landing profile that no longer exists is reset and saved on open. */
    private suspend fun resetDanglingProxies(group: ProxyGroup) {
        var changed = false
        if (group.frontProxyId > 0 && ProfileManager.getProfile(group.frontProxyId) == null) {
            group.frontProxyId = -1L
            changed = true
        }
        if (group.landingProxyId > 0 && ProfileManager.getProfile(group.landingProxyId) == null) {
            group.landingProxyId = -1L
            changed = true
        }
        if (changed) GroupRepo.save(group)
    }

    private fun writeToCache(group: ProxyGroup) {
        val store = DataStore.profileCacheStore
        DataStore.groupName = group.name
        DataStore.groupType = if (group.isSubscription) TYPE_SUBSCRIPTION else TYPE_BASIC
        DataStore.subscriptionLink = group.url
        store.putString(KEY_FRONT, group.frontProxyId.coerceAtLeast(-1L).toString())
        store.putString(KEY_LANDING, group.landingProxyId.coerceAtLeast(-1L).toString())
        store.putBoolean(KEY_AUTO_CLEAR, group.autoClearUnavailable)
        store.putBoolean(KEY_SKIP_AUTO_UPDATE, group.skipAutoUpdate)
        store.putBoolean(KEY_SHOW_INFO_CARD, group.subOptions.showInfoCard)
        val options = group.subOptions
        store.putString(KEY_USER_AGENT, options.userAgent)
        store.putString(KEY_SEND_HWID, options.sendHwid.value.toString())
        store.putString(KEY_HWID, options.hwid)
        store.putString(KEY_HWID_OS, options.hwidOs)
        store.putString(KEY_HWID_OS_VERSION, options.hwidOsVersion)
        store.putString(KEY_HWID_MODEL, options.hwidModel)
        store.putBoolean(KEY_KEEP_WORKING, options.keepWorking)
        store.putBoolean(KEY_REMOVE_DUPLICATES, options.removeDuplicates)
        store.putBoolean(KEY_REMOVE_INSECURE, options.removeInsecure)
        store.putBoolean(KEY_REMOVE_INVALID, options.removeInvalid)
        store.putBoolean(KEY_URL_TEST, options.urlTest)
        store.putBoolean(KEY_REMOVE_UNAVAILABLE, options.removeUnavailable)
        store.putBoolean(KEY_SORT_BY_LATENCY, options.sortByLatency)
        store.putString(KEY_NAME_INCLUDE, options.nameInclude)
        store.putString(KEY_NAME_EXCLUDE, options.nameExclude)
    }

    /** DialogEditGroupAdvanced::accept: the strings trimmed, disabled fields kept. */
    private fun optionsFromCache(): SubscriptionOptions {
        val store = DataStore.profileCacheStore
        fun text(key: String) = store.getString(key).orEmpty().trim()
        fun flag(key: String) = store.getBoolean(key, false)
        return SubscriptionOptions(
            userAgent = text(KEY_USER_AGENT),
            sendHwid = sendHwidMode(),
            hwid = text(KEY_HWID),
            hwidOs = text(KEY_HWID_OS),
            hwidOsVersion = text(KEY_HWID_OS_VERSION),
            hwidModel = text(KEY_HWID_MODEL),
            keepWorking = flag(KEY_KEEP_WORKING),
            removeDuplicates = flag(KEY_REMOVE_DUPLICATES),
            removeInsecure = flag(KEY_REMOVE_INSECURE),
            removeInvalid = flag(KEY_REMOVE_INVALID),
            urlTest = flag(KEY_URL_TEST),
            removeUnavailable = flag(KEY_REMOVE_UNAVAILABLE),
            sortByLatency = flag(KEY_SORT_BY_LATENCY),
            nameInclude = text(KEY_NAME_INCLUDE),
            nameExclude = text(KEY_NAME_EXCLUDE),
            showInfoCard = store.getBoolean(KEY_SHOW_INFO_CARD, true),
        )
    }

    /** A subscription's name filters must compile, like the auto selector's name filter (edit_autoselector onEnd). */
    private fun nameFilterError(): String? {
        if (DataStore.groupType != TYPE_SUBSCRIPTION) return null
        val store = DataStore.profileCacheStore
        for ((key, title) in NAME_FILTERS) {
            val error = SubscriptionNameFilter.error(store.getString(key).orEmpty().trim()) ?: continue
            return getString(R.string.grp_filter_bad_regex, getString(title), error)
        }
        return null
    }

    private fun sendHwidMode(): SendHwid =
        SendHwid.of(DataStore.profileCacheStore.getString(KEY_SEND_HWID)?.toIntOrNull() ?: SendHwid.KEEP_DEFAULT.value)

    private fun proxyId(key: String): Long =
        DataStore.profileCacheStore.getString(key)?.toLongOrNull()?.takeIf { it > 0 } ?: -1L

    /** DialogEditGroup::accept; an existing subscription keeps a URL ("Please input URL"). */
    private suspend fun save() {
        nameFilterError()?.let {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.grp_filter)
                .setMessage(it)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val result = withContext(Dispatchers.IO) {
            val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext SaveResult.GONE else ProxyGroup()
            val url = DataStore.subscriptionLink.trim()
            if (editingId > 0 && group.isSubscription && url.isEmpty()) return@withContext SaveResult.URL_REQUIRED
            val store = DataStore.profileCacheStore
            group.name = DataStore.groupName.trim()
            group.autoClearUnavailable = store.getBoolean(KEY_AUTO_CLEAR, false)
            group.url = if (DataStore.groupType == TYPE_SUBSCRIPTION) url else ""
            group.skipAutoUpdate = store.getBoolean(KEY_SKIP_AUTO_UPDATE, false)
            group.subOptions = optionsFromCache()
            group.frontProxyId = proxyId(KEY_FRONT)
            group.landingProxyId = proxyId(KEY_LANDING)
            if (editingId > 0) GroupRepo.save(group) else GroupRepo.add(group)
            SaveResult.OK
        }
        when (result) {
            SaveResult.URL_REQUIRED -> Toast.makeText(this, R.string.group_url_required, Toast.LENGTH_SHORT).show()
            else -> finish()
        }
    }

    // ------------------------------------------------------------------------------------------------ front / landing

    /** get_proxy_name: "[group] name"; null when the profile or its group is gone. */
    private fun proxyLabel(id: Long): String? {
        val profile = ProfileManager.getProfile(id) ?: return null
        val group = GroupRepo.get(profile.groupId) ?: return null
        return "[" + group.displayName() + "] " + profile.displayName()
    }

    private fun pickProfile(key: String) {
        val launcher: ActivityResultLauncher<Intent> = if (key == KEY_FRONT) selectFront else selectLanding
        val current = proxyId(key)
        lifecycleScope.launch {
            val selected = withContext(Dispatchers.IO) { ProfileManager.getProfile(current) }
            launcher.launch(Intent(this@GroupSettingsActivity, ProfileSelectActivity::class.java).apply {
                selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
            })
        }
    }

    /** Auto selectors are refused: they move server on their own (dialog_edit_group.cpp:63). */
    private fun onProfilePicked(key: String, result: ActivityResult) {
        if (result.resultCode != Activity.RESULT_OK) return
        val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: return
        if (id <= 0) return
        lifecycleScope.launch {
            val picked = withContext(Dispatchers.IO) {
                val profile = ProfileManager.getProfile(id) ?: return@withContext null
                profile to proxyLabel(id)
            } ?: return@launch
            if (picked.first.type == AUTO_SELECTOR) {
                Toast.makeText(this@GroupSettingsActivity, R.string.grp_no_auto_selector, Toast.LENGTH_LONG).show()
                return@launch
            }
            picked.second?.let { proxyLabels[id] = it }
            fragment?.setProxy(key, id)
        }
    }

    // ------------------------------------------------------------------------------------------------ screen

    class GroupPreferenceFragment : PreferenceFragmentCompat() {

        private val host get() = requireActivity() as GroupSettingsActivity

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            // A fragment restored before the activity reloaded its data stays empty; the activity replaces it.
            if (!host.loaded) return
            addPreferencesFromResource(R.xml.group_preferences)

            findPreference<SimpleMenuPreference>(Key.GROUP_TYPE)!!.isEnabled = host.editingId == 0L
            setupProxyMenu(KEY_FRONT)
            setupProxyMenu(KEY_LANDING)

            findPreference<EditTextPreference>(KEY_USER_AGENT)!!.placeholder(host.defaultUserAgent)
            val device = host.defaultDevice
            findPreference<EditTextPreference>(KEY_HWID)!!.placeholder(device.hwid)
            findPreference<EditTextPreference>(KEY_HWID_OS)!!.placeholder(device.os)
            findPreference<EditTextPreference>(KEY_HWID_OS_VERSION)!!.placeholder(device.osVersion)
            findPreference<EditTextPreference>(KEY_HWID_MODEL)!!.placeholder(device.model)

            findPreference<SimpleMenuPreference>(KEY_SEND_HWID)!!.apply {
                val state = getString(if (host.globalSendHwid) R.string.on else R.string.off)
                entries = arrayOf(getString(R.string.grp_keep_default_state, state), getString(R.string.on), getString(R.string.off))
                if (value !in listOf("0", "1", "2")) value = SendHwid.KEEP_DEFAULT.value.toString()
                summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                    (it.entry ?: "").toString() + "\n" + getString(R.string.grp_send_hwid_tip)
                }
            }

            nameFilter(KEY_NAME_INCLUDE, R.string.grp_filter_include_empty)
            nameFilter(KEY_NAME_EXCLUDE, R.string.grp_filter_exclude_empty)

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

        /** Empty means the resolved global value, shown as the summary and as the input hint. */
        private fun EditTextPreference.placeholder(value: String) {
            val shown = value.ifEmpty { getString(R.string.grp_not_available) }
            summaryProvider = DefaultSummaryProvider(shown)
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT
                it.setSingleLine()
                it.hint = shown
            }
        }

        /** The pattern, or what an empty filter means; an invalid one says so before the save refuses it. */
        private fun nameFilter(key: String, empty: Int) {
            val preference = findPreference<EditTextPreference>(key) ?: return
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val text = it.text?.trim().orEmpty()
                val error = SubscriptionNameFilter.error(text)
                when {
                    text.isEmpty() -> getString(empty)
                    error != null -> getString(R.string.grp_filter_invalid, text, error)
                    else -> text
                }
            }
            preference.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                it.setSingleLine()
            }
        }

        private fun proxyText(id: Long): String = when {
            id <= 0 -> getString(R.string.grp_none)
            else -> host.proxyLabels[id] ?: getString(R.string.grp_missing_profile, id)
        }

        /** "None", the current profile as "[group] name", then "Select Profile…" which opens the profile picker. */
        private fun setupProxyMenu(key: String) {
            val preference = findPreference<Preference>(key) ?: return
            preference.summary = proxyText(host.proxyId(key))
            preference.setOnPreferenceClickListener {
                val current = host.proxyId(key)
                val items = arrayListOf<CharSequence>(getString(R.string.grp_none))
                if (current > 0) items.add(proxyText(current))
                items.add(getString(R.string.route_profile))
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(preference.title)
                    .setSingleChoiceItems(items.toTypedArray(), if (current > 0) 1 else 0) { dialog, which ->
                        dialog.dismiss()
                        when (which) {
                            0 -> setProxy(key, -1L)
                            items.lastIndex -> host.pickProfile(key)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        fun setProxy(key: String, id: Long) {
            if (host.proxyId(key) != id) DataStore.profileCacheStore.putString(key, id.toString())
            findPreference<Preference>(key)?.summary = proxyText(id)
        }

        /** Section visibility, the HWID fields' enable rule and the count of set Advanced options. */
        fun refreshState() {
            if (preferenceScreen == null) return
            val store = DataStore.profileCacheStore
            val subscription = DataStore.groupType == TYPE_SUBSCRIPTION
            findPreference<PreferenceCategory>(KEY_SUBSCRIPTION)?.isVisible = subscription
            findPreference<PreferenceCategory>(KEY_FILTER)?.isVisible = subscription
            val expanded = subscription && host.advancedExpanded
            for (key in ADVANCED_CATEGORIES) findPreference<PreferenceCategory>(key)?.isVisible = expanded

            val mode = host.sendHwidMode()
            val sent = mode == SendHwid.ON || (mode == SendHwid.KEEP_DEFAULT && host.globalSendHwid)
            for (key in HWID_KEYS) findPreference<Preference>(key)?.isEnabled = sent

            val set = OPTION_TEXT_KEYS.count { !store.getString(it).isNullOrBlank() } +
                OPTION_BOOL_KEYS.count { store.getBoolean(it, false) } +
                (if (mode != SendHwid.KEEP_DEFAULT) 1 else 0)
            findPreference<Preference>(KEY_ADVANCED_TOGGLE)?.apply {
                title = if (set > 0) getString(R.string.grp_advanced_count, set) else getString(R.string.grp_advanced)
                summary = getString(if (host.advancedExpanded) R.string.grp_advanced_hide else R.string.grp_advanced_show)
                setIcon(if (host.advancedExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
            }
        }
    }
}
