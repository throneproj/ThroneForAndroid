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
import androidx.preference.MultiSelectListPreference
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
import io.nekohasekai.sagernet.group.CountryRules
import io.nekohasekai.sagernet.group.DeviceDetails
import io.nekohasekai.sagernet.group.RequestIdentity
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.group.SubscriptionNameFilter
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.scan.EndpointResolution
import io.nekohasekai.sagernet.scan.EndpointSource
import io.nekohasekai.sagernet.scan.EndpointSources
import io.nekohasekai.sagernet.ui.iplist.IpListChoices
import io.nekohasekai.sagernet.ui.iplist.IpListMenu
import io.nekohasekai.sagernet.ui.settings.DefaultSummaryProvider
import io.nekohasekai.sagernet.ui.settings.SettingChecks
import io.nekohasekai.sagernet.ui.test.TestFormat
import io.nekohasekai.sagernet.validation.Empty
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.vtr
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference

/**
 * Edit Group (dialog_edit_group + dialog_edit_group_advanced): Common, Subscription, the collapsed Advanced
 * subscription options and the server address source. The type is chosen only while creating; a new group is saved
 * without being updated.
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
        private const val STATE_LOADED_FRONT = "loadedFrontProxy"
        private const val STATE_LOADED_LANDING = "loadedLandingProxy"
        private const val STATE_LOADED_ENDPOINT = "loadedEndpoint"
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
        private const val KEY_NAME_FILTER = "groupSubNameInclude"
        private const val KEY_NAME_EXCLUDE = "groupSubNameExclude"
        private const val KEY_TYPE_EXCLUDE = "groupSubTypeExclude"
        private const val KEY_COUNTRY_FILTER = "groupSubCountryFilter"
        private val NAME_FILTERS = listOf(KEY_NAME_FILTER, KEY_NAME_EXCLUDE)

        private const val KEY_ENDPOINT_MODE = "groupEndpointMode"
        private const val KEY_ENDPOINT_ADDRESS = "groupEndpointAddress"
        private const val KEY_ENDPOINT_LIST = "groupEndpointList"
        private const val KEY_ENDPOINT_HINT = "groupEndpointHint"
        private const val ENDPOINT_INHERIT = "inherit"
        private const val ENDPOINT_ADDRESS = "address"
        private const val ENDPOINT_LIST = "list"

        private val HOST_NAME = Regex("""^(?:(?!-)[A-Za-z0-9_-]{1,63}(?<!-)\.)*(?![0-9]+$)(?!-)[A-Za-z0-9_-]{1,63}(?<!-)$""")

        /** groupEndpointHost: empty unless [raw] is a bare IP address or host name, without scheme, port or path. */
        private fun endpointHost(raw: String): String {
            var text = raw.trim()
            if (text.startsWith('[') && text.endsWith(']')) text = text.substring(1, text.length - 1)
            if (Hosts.isIpv6Address(text)) {
                val zone = text.indexOf('%')
                val bytes = Hosts.parseIpv6(if (zone >= 0) text.substring(0, zone) else text) ?: return ""
                return Hosts.formatIpv6(bytes) + (if (zone >= 0) text.substring(zone) else "")
            }
            // "1.2.3" reads as IPv4 and "999.1.1.1" would pass as a name; both are typos, so neither is accepted.
            Hosts.parseIpv4(text)?.let { return if (Hosts.formatIpv4(it) == text) text else "" }
            val ace = Hosts.toAceHost(text)
            return if (ace.length <= 253 && HOST_NAME.matches(ace)) ace else ""
        }

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

    /** The group's profiles, the most the country filter's IP test after an update can take. */
    private var profileCount = 0

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

    /**
     * The front/landing ids as the editor first loaded them: [save] writes one only when it changed, so a profile
     * deleted meanwhile (ClearHopReferences) is not restored.
     */
    private var loadedFront = -1L
    private var loadedLanding = -1L

    /** endpoint_json as loaded, kept the same way: a list deleted meanwhile is not pointed at again. */
    private var loadedEndpoint = "{}"

    /** The "Routing profile" menu as (route id, label), Default ("-1") first. */
    private var routeChoices: List<Pair<String, String>> = emptyList()

    /** The menu's value on open; while it stays, the stored route_id is kept, a raw one the menu does not offer included. */
    private var initialRoute = "-1"

    /** Stored type_exclude ids this build does not know: offered under their id, so they are kept until unticked. */
    private var unknownTypes: List<String> = emptyList()

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
        if (savedInstanceState != null) {
            loadedFront = savedInstanceState.getLong(STATE_LOADED_FRONT, -1L)
            loadedLanding = savedInstanceState.getLong(STATE_LOADED_LANDING, -1L)
            loadedEndpoint = savedInstanceState.getString(STATE_LOADED_ENDPOINT) ?: "{}"
        }
        onBackPressedDispatcher.addCallback(this) { close() }

        val fresh = savedInstanceState == null
        val link = intent.getStringExtra(EXTRA_GROUP_SUBSCRIPTION_LINK)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val group = if (editingId > 0) GroupRepo.get(editingId) ?: return@withContext false else ProxyGroup()
                loadRouteChoices(group)
                // The endpoint section offers "IP list" from the first frame only when it knows the lists.
                try {
                    IpListChoices.load()
                } catch (e: Exception) {
                    Logs.w(e)
                }
                unknownTypes = group.subOptions.typeExclude.filter { id -> SubscriptionNameFilter.PROTOCOLS.none { it.id == id } }
                if (fresh) {
                    writeToCache(group)
                    loadedFront = proxyId(KEY_FRONT)
                    loadedLanding = proxyId(KEY_LANDING)
                    loadedEndpoint = group.endpointJson
                    val prefilled = editingId == 0L && !link.isNullOrBlank()
                    if (prefilled) {
                        DataStore.groupType = TYPE_SUBSCRIPTION
                        DataStore.subscriptionLink = link!!.trim()
                    }
                    DataStore.dirty = prefilled
                }
                groupCount = GroupRepo.ids().size
                if (editingId > 0) profileCount = ProfileManager.memberIds(editingId).size
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
        outState.putLong(STATE_LOADED_FRONT, loadedFront)
        outState.putLong(STATE_LOADED_LANDING, loadedLanding)
        outState.putString(STATE_LOADED_ENDPOINT, loadedEndpoint)
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

    /** The question names the other groups that lose one of its profiles as front/landing proxy (HopReferenceWarning). */
    private fun confirmRemove() {
        val name = ProxyGroup(name = DataStore.groupName.trim()).displayName()
        lifecycleScope.launch {
            val warning = withContext(Dispatchers.IO) { GroupRepo.hopWarning(ProfileManager.memberIds(editingId), editingId) }
            confirmAction(getString(R.string.confirm_remove_group), name + warning, R.string.delete) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { GroupRepo.delete(editingId) }
                    SubscriptionScheduler.schedule()
                    finish()
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ load / save

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
        store.putString(KEY_NAME_FILTER, options.nameFilter)
        store.putString(KEY_NAME_EXCLUDE, options.nameExclude)
        store.putStringSet(KEY_TYPE_EXCLUDE, options.typeExclude.toMutableSet())
        store.putString(KEY_COUNTRY_FILTER, options.countryFilter)
        // A group's "own" source changes nothing, like Inherit, so it shows as "Profiles' own addresses".
        val endpoint = EndpointSource.fromJsonText(group.endpointJson)
        store.putString(
            KEY_ENDPOINT_MODE,
            when (endpoint.mode) {
                EndpointSource.Mode.Address -> ENDPOINT_ADDRESS
                EndpointSource.Mode.IpList -> ENDPOINT_LIST
                else -> ENDPOINT_INHERIT
            },
        )
        store.putString(KEY_ENDPOINT_ADDRESS, endpoint.address)
        store.putString(KEY_ENDPOINT_LIST, endpoint.ipListId.toString())
    }

    /** DialogEditGroupAdvanced::accept's endpoint: the shown mode with its address or list. */
    private fun shownEndpoint(): EndpointSource {
        val store = DataStore.profileCacheStore
        return when (store.getString(KEY_ENDPOINT_MODE)) {
            ENDPOINT_ADDRESS -> EndpointSource(EndpointSource.Mode.Address, address = endpointHost(store.getString(KEY_ENDPOINT_ADDRESS).orEmpty()))
            ENDPOINT_LIST -> store.getString(KEY_ENDPOINT_LIST)?.toLongOrNull()?.takeIf { it >= 0 }
                ?.let { EndpointSource(EndpointSource.Mode.IpList, ipListId = it) } ?: EndpointSource()

            else -> EndpointSource()
        }
    }

    /** editGroupRunningProfileInherits: a profile of this group the running config uses follows the group's source. */
    private fun runningMemberInherits(): Boolean {
        val started = ProfileManager.runningProfileId()
        if (started <= 0L) return false
        val ids = (DataStore.runningProfiles.mapNotNull { it.toLongOrNull() } + started).distinct()
        return ProfileManager.getProfiles(ids).any {
            it.groupId == editingId && EndpointSource.fromJsonText(it.endpointJson).mode == EndpointSource.Mode.Inherit
        }
    }

    /**
     * DialogEditGroupAdvanced::accept: the strings trimmed, disabled fields kept, the interval as entered (the form
     * refuses 1-29); the ticked types in the desktop's order, then the unknown ones; the country rules normalized.
     */
    private fun optionsFromCache(): SubscriptionOptions {
        val store = DataStore.profileCacheStore
        fun text(key: String) = store.getString(key).orEmpty().trim()
        fun flag(key: String) = store.getBoolean(key, false)
        // overrideAt: index 0 is "Keep default".
        fun overrideAt(key: String): Int? = store.getString(key)?.toIntOrNull()?.takeIf { it > 0 }?.minus(1)
        val minutes = text(KEY_UPDATE_INTERVAL).toIntOrNull() ?: 0
        val ticked = store.getStringSet(KEY_TYPE_EXCLUDE).orEmpty()
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
            nameFilter = text(KEY_NAME_FILTER),
            nameExclude = text(KEY_NAME_EXCLUDE),
            typeExclude = (SubscriptionNameFilter.PROTOCOLS.map { it.id } + unknownTypes).filter { it in ticked },
            countryFilter = CountryRules.normalize(text(KEY_COUNTRY_FILTER)),
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
        var refreshNeeded = false
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
            val options = optionsFromCache()
            // DialogEditGroup::accept: new filters apply to an existing subscription right away.
            refreshNeeded = editingId > 0 && newUrl.isNotEmpty() && !group.archive && !group.subOptions.sameFilters(options)
            group.subOptions = options
            proxyId(KEY_FRONT).let { if (it != loadedFront) group.frontProxyId = it }
            proxyId(KEY_LANDING).let { if (it != loadedLanding) group.landingProxyId = it }
            val pickedRoute = store.getString(KEY_ROUTE) ?: initialRoute
            if (pickedRoute != initialRoute) group.routeId = pickedRoute.toLongOrNull()?.takeIf { it > 0 } ?: -1L
            group.importSubRoute = newUrl.isNotEmpty() && store.getBoolean(KEY_IMPORT_ROUTE, false)
            val endpoint = shownEndpoint()
            val loaded = EndpointSource.fromJsonText(loadedEndpoint).let { if (it.mode == EndpointSource.Mode.Own) EndpointSource() else it }
            val endpointChanged = endpoint != loaded
            if (endpointChanged) group.endpointJson = endpoint.toJsonText()
            // dialog_edit_group.cpp: the running profile of this group now resolves to another routing profile.
            val running = RouteManager.runningRouteId()
            val restart = editingId > 0 && running > 0L &&
                ProfileManager.getProfile(ProfileManager.runningProfileId())?.groupId == editingId &&
                RouteManager.resolveRouteId(group.routeId) != running
            if (editingId > 0) GroupRepo.save(group) else GroupRepo.add(group)
            SubscriptionScheduler.schedule()
            restart || (endpointChanged && editingId > 0 && runningMemberInherits())
        }
        if (refreshNeeded) SubscriptionClient.refreshGroup(editingId, showDiff = true)
        if (restartNeeded) setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART_NEEDED, true))
        finish()
    }

    // ------------------------------------------------------------------------------------------------ front / landing

    /** get_proxy_name: "[group] name"; null when the profile or its group is gone, shown as a missing profile. */
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
                summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> { it.entry ?: "" }
            }

            overrideMenu(
                KEY_TLS_VERSION,
                listOf(getString(R.string.sub_version_auto), getString(R.string.sub_tls_12), getString(R.string.sub_tls_13)),
                host.defaultTlsVersion.value,
                getString(R.string.sub_tls_version_tip),
            )
            overrideMenu(
                KEY_HTTP_VERSION,
                listOf(getString(R.string.sub_version_auto), getString(R.string.sub_http_11)),
                host.defaultHttpVersion.value,
                getString(R.string.sub_http_version_tip),
            )
            var serverTip = getString(R.string.grp_server_interval_tip)
            if (host.serverIntervalHours > 0) {
                serverTip += "\n" + getString(R.string.grp_server_interval_last, host.serverIntervalHours)
            }
            // ResolveAutoUpdate: the inherited choice follows the global switch, so it is "Ignore" while that is off.
            val globalOn = host.globalAutoUpdate >= SubscriptionScheduler.MIN_INTERVAL_MINUTES
            overrideMenu(
                KEY_SERVER_INTERVAL,
                listOf(getString(R.string.grp_server_interval_use), getString(R.string.grp_server_interval_ignore)),
                if (globalOn && host.globalRespectServerInterval) 0 else 1,
                serverTip,
            )
            updateInterval()

            nameFilter(KEY_NAME_FILTER, R.string.grp_filter_include_empty)
            nameFilter(KEY_NAME_EXCLUDE, R.string.grp_filter_exclude_empty)
            typeFilter()
            countryFilter()
            setupEndpoint()

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
            validation.bind(KEY_COUNTRY_FILTER) { text ->
                val bad = CountryRules.problems(text)
                if (bad.isEmpty()) "" else vtr(R.string.validation_country_rules, bad.joinToString(", "))
            }
            validation.bind(KEY_ENDPOINT_ADDRESS) { text ->
                Validation.host(text).ifEmpty { if (endpointHost(text).isEmpty()) getString(R.string.endpoint_fixed_address_invalid) else "" }
            }.label(getString(R.string.endpoint_fixed_address)).onlyIf {
                DataStore.profileCacheStore.getString(KEY_ENDPOINT_MODE) == ENDPOINT_ADDRESS
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

        /** "Remove types": the protocols of FilterProtocols(), then the unknown stored ids; the summary lists the ticked ones. */
        private fun typeFilter() {
            val preference = findPreference<MultiSelectListPreference>(KEY_TYPE_EXCLUDE) ?: return
            val protocols = SubscriptionNameFilter.PROTOCOLS
            val ids = protocols.map { it.id } + host.unknownTypes
            val labels = protocols.map { getString(it.label) } + host.unknownTypes
            preference.entries = labels.toTypedArray()
            preference.entryValues = ids.toTypedArray()
            preference.summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> {
                val ticked = it.values
                val shown = ids.indices.filter { i -> ids[i] in ticked }.joinToString(", ") { i -> labels[i] }
                shown.ifEmpty { getString(R.string.grp_filter_exclude_empty) } + "\n" + getString(R.string.grp_filter_type_exclude_tip)
            }
        }

        /**
         * The rules as entered, then what they keep and remove and the warning that an update IP-tests the profiles
         * of unknown country (syncCountryHint); rules with a bad code show as entered, the form marks them.
         */
        private fun countryFilter() {
            val preference = findPreference<EditTextPreference>(KEY_COUNTRY_FILTER) ?: return
            fun names(codes: Set<String>) = codes.sorted().joinToString(", ") { TestFormat.countryName(it) }
            preference.summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                val text = it.text?.trim().orEmpty()
                val bad = ArrayList<String>()
                val rules = CountryRules.parse(text, bad)
                if (text.isEmpty()) {
                    getString(R.string.grp_filter_include_empty)
                } else if (bad.isNotEmpty() || !rules.active) {
                    text
                } else {
                    val summary = ArrayList<String>()
                    if (rules.keep.isNotEmpty()) summary.add(getString(R.string.grp_filter_countries_keeps, names(rules.keep)))
                    if (rules.drop.isNotEmpty()) summary.add(getString(R.string.grp_filter_countries_removes, names(rules.drop)))
                    val count = host.profileCount
                    val warning = if (count > 0) {
                        resources.getQuantityString(R.plurals.grp_filter_countries_test_count, count, count)
                    } else {
                        getString(R.string.grp_filter_countries_test)
                    }
                    text + "\n" + summary.joinToString(" ") + "\n" + warning
                }
            }
            preference.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                it.setSingleLine()
                it.hint = getString(R.string.grp_filter_countries_hint)
            }
        }

        private var endpointMenu: IpListMenu? = null
        private var endpointHintFor = Long.MIN_VALUE
        private var endpointHintJob: Job? = null

        /** loadEndpoint: the group's server address source; "IP list" picks the first list and needs one. */
        private fun setupEndpoint() {
            val mode = findPreference<SimpleMenuPreference>(KEY_ENDPOINT_MODE) ?: return
            val list = findPreference<SimpleMenuPreference>(KEY_ENDPOINT_LIST) ?: return
            val menu = IpListMenu(list, none = false)
            endpointMenu = menu
            applyEndpointModes()
            if (mode.value !in listOf(ENDPOINT_INHERIT, ENDPOINT_ADDRESS, ENDPOINT_LIST)) mode.value = ENDPOINT_INHERIT
            mode.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> { (it.entry ?: "").toString() }
            list.summaryProvider = Preference.SummaryProvider<SimpleMenuPreference> { (it.entry ?: "").toString() }
            mode.setOnPreferenceChangeListener { _, newValue -> newValue != ENDPOINT_LIST || menu.selectFirstIfUnset() }
            findPreference<EditTextPreference>(KEY_ENDPOINT_ADDRESS)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                it.setSingleLine()
                it.hint = getString(R.string.endpoint_fixed_address_hint)
            }
            menu.load(this) {
                applyEndpointModes()
                refreshState()
            }
        }

        /** Like the desktop's disabled entry: "IP list" is offered only while there is a list to pick, or it is the current choice. */
        private fun applyEndpointModes() {
            val mode = findPreference<SimpleMenuPreference>(KEY_ENDPOINT_MODE) ?: return
            val offerList = mode.value == ENDPOINT_LIST || endpointMenu?.choices()?.isNotEmpty() == true
            val entries = arrayListOf<CharSequence>(getString(R.string.endpoint_group_mode_own), getString(R.string.endpoint_group_mode_address))
            val values = arrayListOf<CharSequence>(ENDPOINT_INHERIT, ENDPOINT_ADDRESS)
            if (offerList) {
                entries.add(getString(R.string.endpoint_mode_list))
                values.add(ENDPOINT_LIST)
            }
            mode.entries = entries.toTypedArray()
            mode.entryValues = values.toTypedArray()
        }

        /** syncEndpoint's hint: what the chosen list makes the profiles dial; hidden outside IP list mode. */
        private fun syncEndpointHint(listId: Long) {
            if (listId == endpointHintFor) return
            endpointHintFor = listId
            endpointHintJob?.cancel()
            val hint = findPreference<Preference>(KEY_ENDPOINT_HINT) ?: return
            if (listId < 0) {
                hint.isVisible = false
                return
            }
            endpointHintJob = lifecycleScope.launch {
                val resolution: EndpointResolution? = withContext(Dispatchers.IO) {
                    try {
                        EndpointSources.resolve(EndpointSource(EndpointSource.Mode.IpList, ipListId = listId))
                    } catch (e: Exception) {
                        Logs.w(e)
                        null
                    }
                }
                val text = when {
                    resolution == null -> ""
                    resolution.address.isNotEmpty() ->
                        getString(R.string.endpoint_group_hint_connects, resolution.displayTarget(), resolution.origin)

                    resolution.problem.isNotEmpty() -> getString(R.string.endpoint_group_hint_own_used, resolution.problem)
                    else -> ""
                }
                hint.summary = text
                hint.isVisible = text.isNotEmpty()
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

            val endpoint = store.getString(KEY_ENDPOINT_MODE) ?: ENDPOINT_INHERIT
            findPreference<Preference>(KEY_ENDPOINT_ADDRESS)?.isVisible = endpoint == ENDPOINT_ADDRESS
            findPreference<Preference>(KEY_ENDPOINT_LIST)?.isVisible = endpoint == ENDPOINT_LIST
            syncEndpointHint(if (endpoint == ENDPOINT_LIST) endpointMenu?.selectedId() ?: -1L else -1L)
            form?.refresh()
        }
    }
}
