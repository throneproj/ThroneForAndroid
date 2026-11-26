package io.nekohasekai.sagernet.ui.iplist

import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.scan.EndpointResolution
import io.nekohasekai.sagernet.scan.EndpointSource
import io.nekohasekai.sagernet.scan.EndpointSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * EditAdvanced's Server Address box (edit_advanced.cpp loadEndpoint / syncEndpoint), appended to every profile editor:
 * Inherit from group, Own address or an IP list, and what the choice makes the profile dial. A profile without a single
 * server address to replace shows why instead, and only while a source would otherwise apply to it.
 */
class EndpointSourceSection(private val fragment: PreferenceFragmentCompat, private val groupId: Long) {

    companion object {
        const val KEY_MODE = "profileEndpointMode"
        const val KEY_LIST = "profileEndpointList"
        private const val KEY_CATEGORY = "profileEndpointCategory"
        private const val KEY_HINT = "profileEndpointHint"
        private const val KEY_LOADED = "profileEndpointLoaded"
        private const val KEY_BLOCKER = "profileEndpointBlocker"

        /** [KEY_BLOCKER] of a profile no source applies to: the section stays hidden. */
        private const val SILENT = "-"

        const val MODE_INHERIT = "inherit"
        const val MODE_OWN = "own"
        const val MODE_LIST = "list"

        /** Fills the profile cache for a freshly opened editor; [entity] is null for a new profile. Blocking. */
        @JvmStatic
        fun load(entity: ProxyEntity?, groupId: Long, outbound: Outbound) {
            val store = DataStore.profileCacheStore
            val json = entity?.endpointJson ?: "{}"
            val source = EndpointSource.fromJsonText(json)
            store.putString(KEY_LOADED, json)
            store.putString(
                KEY_MODE,
                when (source.mode) {
                    EndpointSource.Mode.Own -> MODE_OWN
                    EndpointSource.Mode.IpList -> MODE_LIST
                    else -> MODE_INHERIT
                },
            )
            store.putString(KEY_LIST, if (source.mode == EndpointSource.Mode.IpList) source.ipListId.toString() else "-1")
            val blocker = try {
                EndpointSources.overrideBlocker(ProxyEntity(groupId = groupId).putOutbound(outbound))
            } catch (e: Exception) {
                Logs.w(e)
                ""
            }
            val applies = when (source.mode) {
                EndpointSource.Mode.Own -> false
                EndpointSource.Mode.Inherit -> EndpointSources.groupSource(groupId).mode.let {
                    it == EndpointSource.Mode.Address || it == EndpointSource.Mode.IpList
                }

                else -> true
            }
            store.putString(KEY_BLOCKER, if (blocker.isEmpty()) "" else if (applies) blocker else SILENT)
            // The section offers "IP list" from the first frame only when it knows the lists.
            if (blocker.isEmpty()) {
                try {
                    IpListChoices.load()
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }

        /** The endpoint_json to store, or null while the shown source is the loaded one (or the section is blocked). */
        @JvmStatic
        fun pendingJson(): String? {
            val store = DataStore.profileCacheStore
            if (!store.getString(KEY_BLOCKER).isNullOrEmpty()) return null
            val loaded = EndpointSource.fromJsonText(store.getString(KEY_LOADED) ?: "{}")
            val shown = when (store.getString(KEY_MODE)) {
                MODE_OWN -> EndpointSource(EndpointSource.Mode.Own)
                MODE_LIST -> store.getString(KEY_LIST)?.toLongOrNull()?.takeIf { it >= 0 }
                    ?.let { EndpointSource(EndpointSource.Mode.IpList, ipListId = it) } ?: EndpointSource()

                else -> EndpointSource()
            }
            // A group's kind of source on a profile shows as Inherit (the desktop's combo has no entry for it); it is
            // kept until another source is picked.
            val loadedShown = if (loaded.mode == EndpointSource.Mode.Address) EndpointSource() else loaded
            return if (shown == loadedShown) null else shown.toJsonText()
        }

        /** Stores [json] as the profile's endpoint_json; false when the profile is gone. Blocking. */
        @JvmStatic
        fun store(profileId: Long, json: String): Boolean = try {
            SagerDatabase.proxyDao.updateEndpointJson(profileId, json) > 0
        } catch (e: Exception) {
            Logs.w(e)
            false
        }
    }

    private val store = DataStore.profileCacheStore
    private var modeRow: ListPreference? = null
    private var listMenu: IpListMenu? = null
    private var hintRow: Preference? = null
    private var hintFor: String? = null
    private var hintJob: Job? = null

    /** False while the profile cannot take a source: the rows are hidden then. */
    var active = false
        private set

    /** Call after the editor's own preferences exist, before the validation form starts. */
    fun attach() {
        fragment.addPreferencesFromResource(R.xml.endpoint_source_preferences)
        val category = fragment.findPreference<PreferenceCategory>(KEY_CATEGORY) ?: return
        val mode = fragment.findPreference<ListPreference>(KEY_MODE) ?: return
        val list = fragment.findPreference<ListPreference>(KEY_LIST) ?: return
        val hint = fragment.findPreference<Preference>(KEY_HINT) ?: return
        val blocker = store.getString(KEY_BLOCKER).orEmpty()
        if (blocker == SILENT) {
            category.isVisible = false
            return
        }
        if (blocker.isNotEmpty()) {
            mode.isVisible = false
            list.isVisible = false
            hint.summary = blocker
            hint.isVisible = true
            return
        }
        active = true
        modeRow = mode
        hintRow = hint
        val menu = IpListMenu(list, none = false)
        listMenu = menu
        list.summaryProvider = Preference.SummaryProvider<ListPreference> { (it.entry ?: "").toString() }
        mode.summaryProvider = Preference.SummaryProvider<ListPreference> { (it.entry ?: "").toString() }
        applyModes()
        // "IP list" picks the first list; refused only if the lists vanished since they were offered.
        mode.setOnPreferenceChangeListener { _, newValue -> newValue != MODE_LIST || menu.selectFirstIfUnset() }
        menu.load(fragment) {
            applyModes()
            hintFor = null
            refresh()
        }
        refresh()
    }

    /**
     * Like the desktop's disabled entry: "IP list" is offered only while there is a list to pick (known from [load]'s
     * read, or this screen's own), or while it is the current choice.
     */
    private fun applyModes() {
        val mode = modeRow ?: return
        val context = mode.context
        val current = mode.value ?: MODE_INHERIT
        val offerList = current == MODE_LIST || listMenu?.choices()?.isNotEmpty() == true
        val entries = arrayListOf<CharSequence>(context.getString(R.string.endpoint_mode_inherit), context.getString(R.string.endpoint_mode_own))
        val values = arrayListOf<CharSequence>(MODE_INHERIT, MODE_OWN)
        if (offerList) {
            entries.add(context.getString(R.string.endpoint_mode_list))
            values.add(MODE_LIST)
        }
        mode.entries = entries.toTypedArray()
        mode.entryValues = values.toTypedArray()
    }

    /** syncEndpoint: the list row in IP list mode, and the hint for the shown source. Call on the main thread. */
    fun refresh() {
        if (!active) return
        val mode = store.getString(KEY_MODE) ?: MODE_INHERIT
        fragment.findPreference<Preference>(KEY_LIST)?.isVisible = mode == MODE_LIST
        val listId = listMenu?.selectedId() ?: -1L
        val key = "$mode/$listId"
        if (key == hintFor) return
        hintFor = key
        hintJob?.cancel()
        hintJob = fragment.lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { hintText(mode, listId) }
            hintRow?.apply {
                summary = text
                isVisible = text.isNotEmpty()
            }
        }
    }

    /** The WARP finder's "use the profile's own address": the found endpoint is the profile's own. */
    fun useOwn() {
        if (active) modeRow?.value = MODE_OWN
    }

    private fun hintText(mode: String, listId: Long): String {
        val resolution: EndpointResolution = try {
            when (mode) {
                MODE_LIST -> if (listId >= 0) EndpointSources.resolve(EndpointSource(EndpointSource.Mode.IpList, ipListId = listId)) else return ""
                MODE_INHERIT -> EndpointSources.resolve(EndpointSources.groupSource(groupId))
                else -> return ""
            }
        } catch (e: Exception) {
            Logs.w(e)
            return ""
        }
        return when {
            resolution.address.isNotEmpty() -> app.getString(R.string.endpoint_hint_connects, resolution.displayTarget(), resolution.origin)
            resolution.problem.isNotEmpty() -> app.getString(R.string.endpoint_hint_own_used, resolution.problem)
            else -> ""
        }
    }
}
