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
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SendHwid
import io.nekohasekai.sagernet.database.SubHttpVersion
import io.nekohasekai.sagernet.database.SubTlsVersion
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.DeviceDetails
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionNameFilter
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ui.settings.DefaultSummaryProvider
import io.nekohasekai.sagernet.ui.settings.SettingChecks
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
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

        /** Result extra: the save changed the routing profile of the running profile's group (dialog_edit_group restartNeeded). */
        const val EXTRA_RESTART_NEEDED = "restartNeeded"

        /** Values of the "Type" menu (@array/group_types). */
        const val TYPE_BASIC = 0
        const val TYPE_SUBSCRIPTION = 1

        private const val STATE_ADVANCED = "advancedExpanded"
        private const val AUTO_SELECTOR = "autoselector"

        private const val KEY_FRONT = "groupFrontProxyId"
        private const val KEY_LANDING = "groupLandingProxyId"
        private const val KEY_AUTO_CLEAR = "groupAutoClearUnavailable"
        private const val KEY_ROUTE = "groupRouteId"
        private const val KEY_SUBSCRIPTION = "groupSubscription"
        private const val KEY_SKIP_AUTO_UPDATE = "groupSkipAutoUpdate"
        private const val KEY_IMPORT_ROUTE = "groupImportSubRoute"
        private const val KEY_SHOW_INFO_CARD = "groupSubShowInfoCard"
        private const val KEY_ADVANCED_TOGGLE = "groupAdvancedToggle"
        private val ADVANCED_CATEGORIES = listOf("groupAdvancedRequest", "groupAdvancedUpdate", "groupAdvancedAfterUpdate")
        private const val KEY_USER_AGENT = "groupSubUserAgent"
        private const val KEY_TLS_VERSION = "groupSubTlsVersion"
        private const val KEY_HTTP_VERSION = "groupSubHttpVersion"
        private const val KEY_UPDATE_INTERVAL = "groupSubUpdateInterval"
        private const val KEY_SERVER_INTERVAL = "groupSubServerInterval"
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
        private val NAME_FILTERS = listOf(KEY_NAME_INCLUDE, KEY_NAME_EXCLUDE)

        private val HWID_KEYS = listOf(KEY_HWID, KEY_HWID_OS, KEY_HWID_OS_VERSION, KEY_HWID_MODEL)
        private val OPTION_TEXT_KEYS = listOf(KEY_USER_AGENT) + HWID_KEYS
        private val OPTION_BOOL_KEYS = listOf(
            KEY_KEEP_WORKING, KEY_REMOVE_DUPLICATES, KEY_REMOVE_INSECURE, KEY_REMOVE_INVALID, KEY_URL_TEST,
            KEY_REMOVE_UNAVAILABLE, KEY_SORT_BY_LATENCY,
        )

        /** Override menus whose "0" is "Keep default" (DialogEditGroupAdvanced's override combos). */
        private val OPTION_MENU_KEYS = listOf(KEY_TLS_VERSION, KEY_HTTP_VERSION, KEY_SERVER_INTERVAL)

        /** overrideIndex: index 0 keeps the default, the enum values follow. */
        private fun overrideIndex(value: Int?): String = (if (value == null) 0 else value + 1).toString()
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

    private var editingId = 0L
    private var loaded = false
    private var advancedExpanded = false
    private var groupCount = 0

    /** ResolveIdentity(nullptr), resolved while loading: the Advanced placeholders and the "Keep Default" state. */
    private var defaultUserAgent = ""
    private var defaultDevice = DeviceDetails("", "", "", "")
    private var globalSendHwid = false
    private var defaultTlsVersion = SubTlsVersion.AUTO
    private var defaultHttpVersion = SubHttpVersion.AUTO

    /** sub_auto_update and sub_respect_server_interval, and the profile-update-interval the server last sent. */
    private var globalAutoUpdate = 0
    private var globalRespectServerInterval = false
    private var serverIntervalHours = 0

    /** "[group] name" of the profiles the front/landing menus offer. */
    private val proxyLabels = HashMap<Long, String>()

    /** The "Routing profile" menu as (route id, label), Default ("-1") first. */
    private var routeChoices: List<Pair<String, String>> = emptyList()

    /** The menu's value on open; while it stays, the stored route_id is kept, a raw one the menu does not offer included. */
    private var initialRoute = "-1"

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
                loadRouteChoices(group)
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
                defaultTlsVersion = identity.tlsVersion
                defaultHttpVersion = identity.httpVersion
                globalAutoUpdate = DataStore.subAutoUpdate
                globalRespectServerInterval = DataStore.subRespectServerInterval
                serverIntervalHours = group.subInfo.serverInterval
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
                SubscriptionScheduler.schedule()
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
        store.putString(KEY_ROUTE, initialRoute)
        store.putBoolean(KEY_SKIP_AUTO_UPDATE, group.skipAutoUpdate)
        store.putBoolean(KEY_IMPORT_ROUTE, group.importSubRoute)
        store.putBoolean(KEY_SHOW_INFO_CARD, group.subOptions.showInfoCard)
        val options = group.subOptions
        store.putString(KEY_USER_AGENT, options.userAgent)
        store.putString(KEY_TLS_VERSION, overrideIndex(options.tlsVersion?.value))
        store.putString(KEY_HTTP_VERSION, overrideIndex(options.httpVersion?.value))
        store.putString(KEY_UPDATE_INTERVAL, options.updateInterval.takeIf { it > 0 }?.toString().orEmpty())
        store.putString(
            KEY_SERVER_INTERVAL,
            when (options.respectServerInterval) {
                null -> "0"
                true -> "1"
                false -> "2"
            },
        )
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

    /** DialogEditGroupAdvanced::accept: the strings trimmed, disabled fields kept, the interval as entered (the form refuses 1-29). */
    private fun optionsFromCache(): SubscriptionOptions {
        val store = DataStore.profileCacheStore
        fun text(key: String) = store.getString(key).orEmpty().trim()
        fun flag(key: String) = store.getBoolean(key, false)
        // overrideAt: index 0 is "Keep default".
        fun overrideAt(key: String): Int? = store.getString(key)?.toIntOrNull()?.takeIf { it > 0 }?.minus(1)
        val minutes = text(KEY_UPDATE_INTERVAL).toIntOrNull() ?: 0
        return SubscriptionOptions(
            userAgent = text(KEY_USER_AGENT),
            tlsVersion = overrideAt(KEY_TLS_VERSION)?.let(SubTlsVersion::of),
            httpVersion = overrideAt(KEY_HTTP_VERSION)?.let(SubHttpVersion::of),
            sendHwid = sendHwidMode(),
            hwid = text(KEY_HWID),
            hwidOs = text(KEY_HWID_OS),
            hwidOsVersion = text(KEY_HWID_OS_VERSION),
            hwidModel = text(KEY_HWID_MODEL),
            updateInterval = if (minutes > 0) minutes else 0,
            respectServerInterval = overrideAt(KEY_SERVER_INTERVAL)?.let { it == 0 },
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

    private fun sendHwidMode(): SendHwid =
        SendHwid.of(DataStore.profileCacheStore.getString(KEY_SEND_HWID)?.toIntOrNull() ?: SendHwid.KEEP_DEFAULT.value)

    private fun proxyId(key: String): Long =
        DataStore.profileCacheStore.getString(key)?.toLongOrNull()?.takeIf { it > 0 } ?: -1L

    /**
     * dialog_edit_group.cpp: "Default (<global profile>)", then every routing profile Android can use, this
     * subscription's own marked. A dangling id builds with the global profile, so it shows as Default.
     */
    private fun loadRouteChoices(group: ProxyGroup) {
        val choices = arrayListOf("-1" to getString(R.string.grp_route_profile_default, RouteManager.current().name))
        for (route in RouteManager.usable()) {
            val name = route.name.ifBlank { "#" + route.id }
            val owned = editingId > 0 && route.source_gid == editingId
            choices.add(route.id.toString() to if (owned) getString(R.string.grp_route_profile_from_subscription, name) else name)
        }
        routeChoices = choices
        initialRoute = group.routeId.toString().takeIf { id -> choices.any { it.first == id } } ?: "-1"
    }

    /** DialogEditGroup::accept and DialogEditGroupAdvanced::accept behind one save gate. */
    private suspend fun save() {
        if (fragment?.form?.validateAll() == false) return
        val restartNeeded = withContext(Dispatchers.IO) {
            val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext false else ProxyGroup()
            val url = DataStore.subscriptionLink.trim()
            val store = DataStore.profileCacheStore
            // A basic group keeps no URL, even one typed before switching the type back.
            val newUrl = if (DataStore.groupType == TYPE_SUBSCRIPTION) url else ""
            // dialog_edit_group.cpp accept: what the old URL's server said no longer applies.
            if (group.url != newUrl) {
                group.subMetadata = SubUserInfo()
                group.subLastUpdate = 0L
                group.info = ""
            }
            group.name = DataStore.groupName.trim()
            group.autoClearUnavailable = store.getBoolean(KEY_AUTO_CLEAR, false)
            group.url = newUrl
            group.skipAutoUpdate = store.getBoolean(KEY_SKIP_AUTO_UPDATE, false)
            group.subOptions = optionsFromCache()
            group.frontProxyId = proxyId(KEY_FRONT)
            group.landingProxyId = proxyId(KEY_LANDING)
            val pickedRoute = store.getString(KEY_ROUTE) ?: initialRoute
            if (pickedRoute != initialRoute) group.routeId = pickedRoute.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            group.importSubRoute = newUrl.isNotEmpty() && store.getBoolean(KEY_IMPORT_ROUTE, false)
            // dialog_edit_group.cpp: the running profile of this group now resolves to another routing profile.
            val running = RouteManager.runningRouteId()
            val restart = editingId > 0 && running > 0L &&
                ProfileManager.getProfile(ProfileManager.runningProfileId())?.groupId == editingId &&
                RouteManager.resolveRouteId(group.routeId) != running
            if (editingId > 0) GroupRepo.save(group) else GroupRepo.add(group)
            SubscriptionScheduler.schedule()
            restart
        }
        if (restartNeeded) setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART_NEEDED, true))
        finish()
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
            findPreference<SimpleMenuPreference>(KEY_ROUTE)!!.apply {
                entries = host.routeChoices.map { it.second }.toTypedArray()
                entryValues = host.routeChoices.map { it.first }.toTypedArray()
                value = value?.takeIf { v -> host.routeChoices.any { it.first == v } } ?: "-1"
                summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                    (it.entry ?: "").toString() + "\n" + getString(R.string.grp_route_profile_tip)
                }
            }

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

            overrideMenu(
                KEY_TLS_VERSION,
                listOf(getString(R.string.sub_version_auto), getString(R.string.sub_tls_12), getString(R.string.sub_tls_13)),
                host.defaultTlsVersion.value,
                getString(R.string.grp_tls_version_tip),
            )
            overrideMenu(
                KEY_HTTP_VERSION,
                listOf(getString(R.string.sub_version_auto), getString(R.string.sub_http_11)),
                host.defaultHttpVersion.value,
                getString(R.string.grp_http_version_tip),
            )
            var serverTip = getString(R.string.grp_server_interval_tip)
            if (host.serverIntervalHours > 0) {
                serverTip += "\n" + getString(R.string.grp_server_interval_last, host.serverIntervalHours)
            }
            overrideMenu(
                KEY_SERVER_INTERVAL,
                listOf(getString(R.string.grp_server_interval_use), getString(R.string.grp_server_interval_ignore)),
                if (host.globalRespectServerInterval) 0 else 1,
                serverTip,
            )
            updateInterval()

            nameFilter(KEY_NAME_INCLUDE, R.string.grp_filter_include_empty)
            nameFilter(KEY_NAME_EXCLUDE, R.string.grp_filter_exclude_empty)

            findPreference<Preference>(KEY_ADVANCED_TOGGLE)!!.setOnPreferenceClickListener {
                host.advancedExpanded = !host.advancedExpanded
                refreshState()
                true
            }
            refreshState()
            attachValidation()
        }

        /** The save gate of [save]; null until the preferences exist. */
        var form: ValidationForm? = null
            private set

        /** dialog_edit_group's and dialog_edit_group_advanced's Validation::Form. */
        private fun attachValidation() {
            val validation = ValidationForm(this)
            validation.bind(Key.SUBSCRIPTION_LINK, ::subscriptionUrlError).onlyIf { DataStore.groupType == TYPE_SUBSCRIPTION }
            for (key in NAME_FILTERS) {
                validation.bind(key) { pattern -> SubscriptionNameFilter.error(pattern)?.let { vtr(R.string.validation_regex, it) }.orEmpty() }
            }
            validation.addIssueSource(::advancedIssues)
            validation.revealer = { key ->
                val category = findPreference<Preference>(key)?.parent?.key
                if (!host.advancedExpanded && category != null && category in ADVANCED_CATEGORIES) {
                    host.advancedExpanded = true
                    refreshState()
                }
            }
            validation.start()
            if (host.editingId > 0L) validation.markAll()
            form = validation
        }

        /** The fetcher reads content:// URLs (a picked file) too, and those carry no host. */
        private fun subscriptionUrlError(text: String): String {
            if (text.startsWith("content://", ignoreCase = true)) return ""
            return Validation.url(text, SettingChecks.HTTP, Empty.Required)
        }

        /**
         * The Advanced options are checked while collapsed too, as the desktop's own dialog checks them; the save gate
         * expands them to show the first error. Disabled ones (HWID values that are not sent) are skipped.
         */
        private fun advancedIssues(): List<FormIssue> {
            if (DataStore.groupType != TYPE_SUBSCRIPTION) return emptyList()
            val store = DataStore.profileCacheStore
            val issues = ArrayList<FormIssue>()
            fun check(key: String, message: String) {
                val preference = findPreference<Preference>(key) ?: return
                if (message.isEmpty() || !preference.isEnabled) return
                issues.add(FormIssue(Severity.Error, preference.title?.toString().orEmpty(), message, key))
            }
            for (key in OPTION_TEXT_KEYS) check(key, SettingChecks.headerValue(store.getString(key).orEmpty().trim()))
            check(KEY_UPDATE_INTERVAL, SettingChecks.groupUpdateInterval(store.getString(KEY_UPDATE_INTERVAL).orEmpty()))
            return issues
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

        /** "Keep default (resolved)" then [choices]; [defaultIndex] is the choice the global setting resolves to. */
        private fun overrideMenu(key: String, choices: List<String>, defaultIndex: Int, tip: String) {
            val preference = findPreference<SimpleMenuPreference>(key) ?: return
            val keep = getString(R.string.grp_keep_default_state, choices.getOrElse(defaultIndex) { choices[0] })
            preference.entries = (listOf<CharSequence>(keep) + choices).toTypedArray()
            if (preference.entryValues.take(choices.size + 1).none { it.toString() == preference.value }) {
                preference.value = "0"
            }
            preference.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> {
                (it.entry ?: "").toString() + "\n" + tip
            }
        }

        /** Empty or 0 is "Keep default" (sub_auto_update, or off); the form refuses 1-29. */
        private fun updateInterval() {
            val preference = findPreference<EditTextPreference>(KEY_UPDATE_INTERVAL) ?: return
            val global = host.globalAutoUpdate
            val keep = getString(
                R.string.grp_keep_default_state,
                if (global >= SubscriptionScheduler.MIN_INTERVAL_MINUTES) getString(R.string.auto_update_interval_sum, global)
                else getString(R.string.off),
            )
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val minutes = it.text?.trim()?.toIntOrNull()?.takeIf { m -> m > 0 }
                val shown = if (minutes == null) keep else getString(R.string.auto_update_interval_sum, minutes)
                shown + "\n" + getString(R.string.grp_update_interval_tip)
            }
            preference.setOnBindEditTextListener {
                EditTextPreferenceModifiers.Number.onBindEditText(it)
                it.hint = keep
            }
        }

        /** The pattern, or what an empty filter means; the form marks one that does not compile. */
        private fun nameFilter(key: String, empty: Int) {
            val preference = findPreference<EditTextPreference>(key) ?: return
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.trim().orEmpty().ifEmpty { getString(empty) }
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
                OPTION_MENU_KEYS.count { (store.getString(it)?.toIntOrNull() ?: 0) > 0 } +
                (if ((store.getString(KEY_UPDATE_INTERVAL)?.trim()?.toIntOrNull() ?: 0) > 0) 1 else 0) +
                (if (mode != SendHwid.KEEP_DEFAULT) 1 else 0)
            findPreference<Preference>(KEY_ADVANCED_TOGGLE)?.apply {
                title = if (set > 0) getString(R.string.grp_advanced_count, set) else getString(R.string.grp_advanced)
                summary = getString(if (host.advancedExpanded) R.string.grp_advanced_hide else R.string.grp_advanced_show)
                setIcon(if (host.advancedExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
            }
            form?.refresh()
        }
    }
}
