package io.nekohasekai.sagernet.ui.iplist

import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.profile.refreshSummary
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IpListChoice(@JvmField val id: Long, @JvmField val name: String)

/** The visible IP lists in list order, as the choosers offer them. */
object IpListChoices {

    @Volatile
    private var last: List<IpListChoice>? = null

    /** Blocking. */
    fun load(): List<IpListChoice> = IpListsRepo.getAllIpLists().map { IpListChoice(it.id, it.name) }.also { last = it }

    /** The lists of the last [load], so a chooser opens filled before its own load ends. */
    fun cached(): List<IpListChoice>? = last
}

/**
 * An IP list chooser over a menu preference whose value is the list id: [none] puts "None" (-1) first, and a gone
 * list stays selected as "Missing list", so saving never points somewhere else by itself. The current value is always
 * among the entries: the dropdown's spinner would otherwise replace it with the first entry on its first layout.
 */
class IpListMenu(private val preference: ListPreference, private val none: Boolean) {

    private var lists: List<IpListChoice>? = IpListChoices.cached()

    /** Whether [lists] comes from this screen's own load. */
    var loaded = false
        private set

    init {
        apply()
    }

    fun selectedId(): Long = preference.value?.toLongOrNull() ?: -1L

    fun choices(): List<IpListChoice> = lists.orEmpty()

    /** The selected list is gone (or hidden while it is deleted); known only once loaded. */
    fun missing(): Boolean {
        val id = selectedId()
        return loaded && id >= 0 && choices().none { it.id == id }
    }

    /** Selects the first list unless a known one is selected; false when there is none. */
    fun selectFirstIfUnset(): Boolean {
        val id = selectedId()
        if (id >= 0 && choices().any { it.id == id }) return true
        val first = choices().firstOrNull() ?: return false
        preference.value = first.id.toString()
        apply()
        return true
    }

    /** Selects [id], which may be newer than [choices]: it shows as "…" until the next [load]. */
    fun select(id: Long) {
        loaded = false
        apply(id)
        preference.value = id.toString()
    }

    fun apply(current: Long = selectedId()) {
        val context = preference.context
        val entries = ArrayList<CharSequence>()
        val values = ArrayList<CharSequence>()
        if (none) {
            entries.add(context.getString(R.string.iplist_none))
            values.add("-1")
        }
        val known = choices()
        if (current >= 0 && known.none { it.id == current }) {
            entries.add(if (loaded) context.getString(R.string.iplist_missing_list) else "…")
            values.add(current.toString())
        }
        for (list in known) {
            entries.add(list.name)
            values.add(list.id.toString())
        }
        preference.entries = entries.toTypedArray()
        preference.entryValues = values.toTypedArray()
    }

    /** Reads the lists off the main thread, then refreshes the entries and the summary and calls [onLoaded]. */
    fun load(fragment: PreferenceFragmentCompat, onLoaded: () -> Unit = {}) {
        fragment.lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { IpListChoices.load() }
            lists = fresh
            loaded = true
            apply()
            preference.refreshSummary()
            onLoaded()
        }
    }
}

/**
 * ResolverSourceWidget's IP list row for the profile editors: the list id is kept in the profile cache under [key],
 * written with the outbound's other unbound rows and read back with them.
 */
class ResolverListRow(private val key: String) {

    private var menu: IpListMenu? = null
    private var fragment: PreferenceFragmentCompat? = null
    private var onLoaded: () -> Unit = {}

    /** A [select] that came before the screen existed. */
    private var pending = -1L

    fun write(ipListId: Int) {
        DataStore.profileCacheStore.putString(key, (if (ipListId >= 0) ipListId else -1).toString())
    }

    fun read(): Int = DataStore.profileCacheStore.getString(key)?.toIntOrNull()?.takeIf { it >= 0 } ?: -1

    /** Call from onPreferencesCreated; [onLoaded] runs once the lists are read (refresh the form there). */
    fun attach(fragment: PreferenceFragmentCompat, onLoaded: () -> Unit) {
        val preference = fragment.findPreference<ListPreference>(key) ?: return
        preference.summaryProvider = Preference.SummaryProvider<ListPreference> {
            (it.entry ?: "").toString() + "\n" + it.context.getString(R.string.resolver_iplist_hint)
        }
        val row = IpListMenu(preference, none = true)
        menu = row
        this.fragment = fragment
        this.onLoaded = onLoaded
        if (pending >= 0) row.select(pending)
        pending = -1L
        row.load(fragment, onLoaded)
    }

    /** The resolver scan's "Use as resolvers" (ScannerUi.takeResolverList): selects [listId], a list that may be new. */
    fun select(listId: Long) {
        val row = menu
        val screen = fragment
        if (row == null || screen == null) {
            pending = listId
            return
        }
        row.select(listId)
        row.load(screen, onLoaded)
    }

    /** The desktop's warning on a list that no longer exists. */
    fun bindValidation(form: ValidationForm) {
        form.addRule(listOf(key), Severity.Warning) {
            if (menu?.missing() == true) vtr(R.string.resolver_iplist_missing) else ""
        }
    }
}
