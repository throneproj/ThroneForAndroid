package io.nekohasekai.sagernet.ui.profiles

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortAction
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileName
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.test.RowPhase
import io.nekohasekai.sagernet.ui.test.TestUiState
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The profiles of one group. The database is the source of truth, read like the desktop's ProfilesTableModel: a
 * reload (every change in this process through the repository listeners, from `:bg` through [ProfilesDbWatcher]
 * while the page is shown) reads the group's order and re-reads only the rows already loaded; any other row loads
 * when it comes into view. Only the live traffic counters, the drag order and profiles awaiting an undo live here.
 */
internal class ProfileListAdapter(private val fragment: ProfileListFragment) :
    RecyclerView.Adapter<ProfileRowHolder>(),
    ProfileManager.Listener,
    GroupRepo.Listener,
    UndoSnackbarManager.Interface<ProxyEntity> {

    companion object {
        /** Rebind in place (no change animation). */
        const val PAYLOAD_CONTENT = 1

        /** Only the selection / running / multi-select state changed. */
        const val PAYLOAD_STATE = 2

        /** Above this many rows a structural change is not animated. */
        private const val DIFF_LIMIT = 2000

        /** A query typed onto another waits this long for the next keystroke. */
        private const val QUERY_DEBOUNCE_MS = 200L

        /** While a test runs, the shown list reloads at most this often (re-sorting and other writers' changes). */
        private const val TEST_RELOAD_MS = 5000L

        /** Loaded rows kept: well over a screen of cards, the view cache and the prefetch on both sides. */
        private const val CACHE_SIZE = 300

        /** A row coming into view unloaded loads this many rows before and after it with it. */
        private const val PREFETCH = 40

        private const val LOAD_BATCH = 200

        private const val AUTO_SELECTOR = "autoselector"
    }

    /** What the filter matches a row against: its [ProxyEntity.searchText] while its type and JSON stay the same. */
    private class SearchKey(val type: String, val jsonLength: Int, val jsonHash: Int, val text: String)

    val groupId = fragment.groupId
    val host: ConfigurationFragment? get() = fragment.host

    /** The compact card of double column mode. */
    val isCompact: Boolean get() = fragment.isCompact

    /** The one-line rows of compactProfileRows (layout_profile_compact). */
    val isCompactRow: Boolean get() = fragment.isCompactRow

    /** Cards side by side: a grid row pairs their heights. */
    private val multiColumn: Boolean get() = fragment.isMultiColumn

    var group: ProxyGroup? = null
        private set
    val testItemsToShow: Int get() = group?.testItemsToShow ?: 0

    /** The group's order as stored. */
    var memberIds: List<Long> = emptyList()
        private set

    /** The auto selectors among [memberIds], for the pickers that hide them. */
    private var autoSelectors: Set<Long> = emptySet()

    /** The loaded rows, least recently bound first ([touch]); a reload keeps only the ones around the screen. */
    private val cache = object : LinkedHashMap<Long, ProxyEntity>(CACHE_SIZE * 2) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ProxyEntity>?) = size > CACHE_SIZE
    }

    /** Rows waiting for the row loader, and the ones it is reading. */
    private val pendingRows = LinkedHashSet<Long>()
    private val loadingRows = HashSet<Long>()
    private var rowLoader: Job? = null

    /** Counts the reloads started: a row read while one started may predate the change it reads. */
    private var generation = 0

    /** Traffic the service reported live, newer than the table's last flush; laid over rows as they load. */
    private val liveTraffic = HashMap<Long, TrafficData>()

    /** The shown rows: [memberIds] without the filtered and the undo-pending ones. */
    private val ids = ArrayList<Long>()
    val displayedIds: List<Long> get() = ids

    /** Position of each of [ids], built on demand; null after [ids] changed. */
    private var positions: HashMap<Long, Int>? = null

    private var query = ""
    val isFiltered: Boolean get() = query.isNotBlank()

    /** The rows matching the last filter computed, null while no filter is applied. */
    private var matches: Set<Long>? = null
    private var filterJob: Job? = null

    /** A reload came while the filter was reading the rows: it runs once more when done instead of restarting. */
    private var refilterAgain = false

    /** The first load came while filtered: [ProfileListFragment.onFirstLoad] waits for the first matches. */
    private var firstLoadPending = false

    /** Every row's [SearchKey] while a filter is applied, kept by the filter jobs, one at a time. */
    private val searchIndex = ConcurrentHashMap<Long, SearchKey>()

    /** The [generation] [searchIndex] was checked against, -1 for none. */
    private var indexedGeneration = -1

    private val hidden = HashSet<Long>()
    private var testState: TestUiState? = null
    private var phases: Map<Long, RowPhase> = emptyMap()
    private val pendingTraffic = HashSet<Long>()

    var loaded = false
        private set
    private var reloadJob: Job? = null
    private var reloadAgain = false

    /** The table changed without this list reloading: it reloads when shown, or when the running test ends. */
    private var stale = false
    private var reloadedAt = 0L
    var dragging = false
        private set
    private var orderChanged = false

    init {
        setHasStableIds(true)
    }

    fun phaseOf(id: Long): RowPhase? = phases[id]

    // ------------------------------------------------------------------------------------------------ adapter

    override fun getItemCount() = ids.size

    override fun getItemId(position: Int) = ids[position]

    override fun getItemViewType(position: Int) =
        if (isCompactRow) R.layout.layout_profile_compact else R.layout.layout_profile

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ProfileRowHolder(
        LayoutInflater.from(parent.context).inflate(viewType, parent, false), this
    )

    override fun onBindViewHolder(holder: ProfileRowHolder, position: Int) =
        onBindViewHolder(holder, position, emptyList())

    override fun onBindViewHolder(holder: ProfileRowHolder, position: Int, payloads: List<Any>) {
        val id = ids[position]
        val profile = touch(id)
        if (profile == null) {
            // a card still showing this row (it left the cache) keeps it until the fresh one arrives
            if (holder.boundProfile?.id != id) holder.bindPlaceholder()
            requestRows(position)
            return
        }
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE } &&
            holder.boundRows != null && holder.boundProfile?.id == profile.id
        ) {
            holder.bindState()
        } else {
            holder.bind(profile)
        }
    }

    override fun onViewRecycled(holder: ProfileRowHolder) {
        holder.onRecycled()
    }

    override fun onViewAttachedToWindow(holder: ProfileRowHolder) {
        super.onViewAttachedToWindow(holder)
        val profile = cache[holder.itemId] ?: return
        if (holder.boundTx == profile.tx && holder.boundRx == profile.rx) return
        if (fragment.isScrolling) pendingTraffic.add(profile.id) else notifyId(profile.id, PAYLOAD_CONTENT)
    }

    // ------------------------------------------------------------------------------------------------ loading

    /**
     * Reloads the group from the database; calls made while a reload runs (or during a drag) coalesce into one.
     * The running reload, null during a drag.
     */
    fun reload(): Job? {
        if (dragging) {
            reloadAgain = true
            return null
        }
        reloadJob?.takeIf { it.isActive }?.let {
            reloadAgain = true
            return it
        }
        val scope = fragment.scope ?: return null
        return scope.launch(Dispatchers.Main.immediate) {
            do {
                reloadAgain = false
                stale = false
                generation++
                // like the desktop's dataChanged: only the rows around the screen are read again
                val window = if (loaded) screenRows() else null
                val known = if (loaded) window ?: ArrayList(cache.keys) else null
                val selected = host?.pickedOrSelectedId() ?: 0L
                val (loadedGroup, names, rows) = withContext(Dispatchers.IO) {
                    val loadedGroup = GroupRepo.get(groupId)
                    val names = SagerDatabase.proxyDao.getNamesByGroup(groupId)
                    Triple(loadedGroup, names, ProfileManager.getProfiles(known ?: firstRows(loadedGroup, names, selected)))
                }
                // a removed group's page goes away with the tabs
                if (loadedGroup == null) return@launch
                if (dragging) {
                    reloadAgain = true
                    return@launch
                }
                apply(loadedGroup, names, rows, onlyRead = window != null)
                reloadedAt = SystemClock.uptimeMillis()
            } while (reloadAgain)
        }.also { reloadJob = it }
    }

    /** The shown rows' positions, null before the first layout. */
    private fun screen(): IntRange? {
        val layoutManager = fragment.list.layoutManager as? LinearLayoutManager ?: return null
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return null
        return maxOf(0, first - PREFETCH)..minOf(ids.size - 1, last + PREFETCH)
    }

    /** The rows on screen and [PREFETCH] on either side, which also covers the view cache's cards. */
    private fun screenRows(): List<Long>? = screen()?.map { ids[it] }

    /** The loaded row [id], now the most recently used one. */
    private fun touch(id: Long): ProxyEntity? = cache.remove(id)?.also { cache[id] = it }

    /** The rows the first screen is likely to show: the top, the remembered scroll position and the selected one. */
    private fun firstRows(group: ProxyGroup?, names: List<ProfileName>, selected: Long): List<Long> {
        val rows = LinkedHashSet<Long>()
        fun around(index: Int) {
            if (index < 0) return
            for (i in maxOf(0, index - PREFETCH) until minOf(names.size, index + PREFETCH)) rows.add(names[i].id)
        }
        around(0)
        around(group?.scrollLastProfile ?: -1)
        if (selected > 0) around(names.indexOfFirst { it.id == selected })
        return rows.toList()
    }

    /**
     * The profiles table changed in either process. Only the [shown] page reloads, and while a test runs at most every
     * [TEST_RELOAD_MS] (its finished rows arrive through [onTestState]); the others are stale until [onShown]. The
     * reload started, if any.
     */
    fun onTableChanged(shown: Boolean): Job? {
        val testing = testState?.running == true
        if (!shown || testing && SystemClock.uptimeMillis() - reloadedAt < TEST_RELOAD_MS) {
            stale = true
            return null
        }
        return reload()
    }

    /** The page came into view: a list that missed changes reloads. */
    fun onShown() {
        if (stale) reload()
    }

    /** [rows] are the re-read loaded rows; with [onlyRead] the others are dropped, their data is unknown now. */
    private fun apply(newGroup: ProxyGroup, names: List<ProfileName>, rows: List<ProxyEntity>, onlyRead: Boolean) {
        val displayChanged = group?.let { it.testItemsToShow != newGroup.testItemsToShow } == true
        group = newGroup
        memberIds = names.map { it.id }
        autoSelectors = names.filter { it.type == AUTO_SELECTOR }.mapTo(HashSet()) { it.id }
        val members = memberIds.toHashSet()
        val previous = HashMap<Long, ProxyEntity>(cache)
        if (onlyRead) cache.clear() else cache.keys.retainAll(members)
        pendingRows.retainAll(members)
        liveTraffic.keys.retainAll(members)
        hidden.retainAll(members)
        val changed = HashSet<Long>()
        for (entity in rows) {
            if (entity.groupId != groupId) continue
            withLiveTraffic(entity)
            val old = previous[entity.id]
            // an unchanged row keeps its instance, and with it the parsed outbound the card was bound to
            if (old != null && old == entity) {
                cache[entity.id] = old
                continue
            }
            cache[entity.id] = entity
            changed.add(entity.id)
        }
        val oldPhases = phases
        phases = computePhases()
        if (phases != oldPhases) memberIds.filterTo(changed) { oldPhases[it] != phases[it] }
        val first = !loaded
        loaded = true
        if (first && isFiltered) {
            // a page opened while searching shows no row until its matches are known, and scrolls within them
            matches = emptySet()
            firstLoadPending = true
        }
        publish(changed, displayChanged)
        fragment.showEmpty(memberIds.isEmpty())
        if (first && !firstLoadPending) fragment.onFirstLoad()
        // rows that are not loaded may have changed too: the filter checks every row again
        if (isFiltered) {
            if (filterJob?.isActive == true) refilterAgain = true else refilter(0L)
        }
    }

    /** Loads the unloaded rows around [position], batched with the other rows coming into view. */
    private fun requestRows(position: Int) {
        for (i in maxOf(0, position - PREFETCH)..minOf(ids.size - 1, position + PREFETCH)) {
            val id = ids[i]
            if (!cache.containsKey(id) && id !in loadingRows) pendingRows.add(id)
        }
        if (rowLoader?.isActive == true) return
        val scope = fragment.scope ?: return
        // after the layout pass that is binding the rows
        rowLoader = scope.launch(Dispatchers.Main) {
            while (true) {
                // a fling queues every row it passes: only those still near the screen are read
                screen()?.let { near -> pendingRows.retainAll { positionOf(it) in near } }
                if (pendingRows.isEmpty()) break
                val batch = pendingRows.take(LOAD_BATCH)
                pendingRows.removeAll(batch.toSet())
                loadingRows.addAll(batch)
                val started = generation
                val rows = try {
                    withContext(Dispatchers.IO) { ProfileManager.getProfiles(batch) }
                } catch (e: IOException) {
                    Logs.w(e)
                    return@launch
                } finally {
                    loadingRows.removeAll(batch.toSet())
                }
                if (started != generation) {
                    // a reload ran meanwhile, and these rows may have been read before the change it brought
                    batch.filterTo(pendingRows) { !cache.containsKey(it) && positionOf(it) >= 0 }
                    continue
                }
                for (entity in rows) {
                    if (entity.groupId != groupId || cache.containsKey(entity.id)) continue
                    withLiveTraffic(entity)
                    cache[entity.id] = entity
                    notifyId(entity.id, PAYLOAD_CONTENT)
                }
            }
        }
    }

    /** The service reports traffic live; the table only has its last flush. */
    private fun withLiveTraffic(entity: ProxyEntity) {
        val live = liveTraffic[entity.id] ?: return
        if (live.rx > entity.rx) entity.rx = live.rx
        if (live.tx > entity.tx) entity.tx = live.tx
    }

    /** [memberIds] without the hidden ones and, while a filter is applied, without the rows it did not match. */
    private fun visibleIds(): List<Long> {
        val matches = matches
        val hideAutoSelectors = host?.hideAutoSelectors == true
        return memberIds.filter { id ->
            id !in hidden && (!hideAutoSelectors || id !in autoSelectors) && (matches == null || id in matches)
        }
    }

    /**
     * Matches [query] against every row on a background thread after [delayMs] and publishes the result; an empty
     * query applies at once. A row matches when its display name, type or address contains the query. The rows are
     * read again after each reload, but only those whose outbound changed are parsed again.
     */
    private fun refilter(delayMs: Long) {
        val previous = filterJob
        previous?.cancel()
        filterJob = null
        refilterAgain = false
        val filter = query.trim().lowercase()
        if (filter.isEmpty()) {
            searchIndex.clear()
            indexedGeneration = -1
            if (matches != null) publishMatches(null)
            return
        }
        val scope = fragment.scope ?: return
        filterJob = scope.launch(Dispatchers.Main.immediate) {
            delay(delayMs)
            previous?.join()
            val members = memberIds
            val indexing = generation
            val reindex = indexedGeneration != indexing
            val found = try {
                withContext(Dispatchers.IO) {
                    if (reindex) {
                        for (chunk in members.chunked(LOAD_BATCH)) {
                            ensureActive()
                            for (row in ProfileManager.getProfiles(chunk)) {
                                val json = row.outboundJson
                                val known = searchIndex[row.id]
                                // an auto selector shows its tracked groups' current names, which its JSON does not hold
                                if (row.type != AUTO_SELECTOR && known != null && known.type == row.type &&
                                    known.jsonLength == json.length && known.jsonHash == json.hashCode()
                                ) continue
                                searchIndex[row.id] = SearchKey(row.type, json.length, json.hashCode(), row.searchText())
                            }
                        }
                        if (searchIndex.size > members.size) searchIndex.keys.retainAll(members.toHashSet())
                    }
                    members.filterTo(HashSet()) { id -> searchIndex[id]?.text?.contains(filter) == true }
                }
            } catch (e: IOException) {
                Logs.w(e)
                if (firstLoadPending) publishMatches(matches)
                return@launch
            }
            if (reindex) indexedGeneration = indexing
            publishMatches(found)
            if (refilterAgain) refilter(0L)
        }
    }

    private fun publishMatches(found: Set<Long>?) {
        matches = found
        publish(emptySet(), false)
        if (firstLoadPending) {
            firstLoadPending = false
            fragment.onFirstLoad()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun publish(changed: Set<Long>, all: Boolean) {
        val next = visibleIds()
        if (next == ids) {
            if (all) {
                notifyItemRangeChanged(0, ids.size, PAYLOAD_CONTENT)
            } else if (changed.isNotEmpty()) {
                changed.forEach { notifyId(it, PAYLOAD_CONTENT) }
            }
            return
        }
        val old = ArrayList(ids)
        ids.clear()
        ids.addAll(next)
        positions = null
        // grid rows re-pair after any insert/remove, which changes the neighbour alignment
        if (multiColumn || old.isEmpty() || old.size + next.size > DIFF_LIMIT) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(oldPosition: Int, newPosition: Int) = old[oldPosition] == next[newPosition]
            override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                !all && old[oldPosition] !in changed

            override fun getChangePayload(oldPosition: Int, newPosition: Int): Any = PAYLOAD_CONTENT
        }).dispatchUpdatesTo(this)
    }

    fun setQuery(text: String) {
        if (text == query) return
        val typing = isFiltered
        query = text
        if (loaded) refilter(if (typing) QUERY_DEBOUNCE_MS else 0L)
    }

    // ------------------------------------------------------------------------------------------------ row state

    private fun positionOf(id: Long): Int {
        val map = positions ?: HashMap<Long, Int>(ids.size * 2).also { map ->
            ids.forEachIndexed { index, rowId -> map[rowId] = index }
            positions = map
        }
        return map[id] ?: -1
    }

    fun notifyId(id: Long, payload: Int) {
        val index = positionOf(id)
        if (index >= 0) notifyItemChanged(index, payload)
    }

    /** Selection or running state changed for [changedIds] (null = every row). */
    fun notifyState(changedIds: Collection<Long>?) {
        if (changedIds == null) {
            notifyItemRangeChanged(0, ids.size, PAYLOAD_STATE)
        } else {
            changedIds.forEach { notifyId(it, PAYLOAD_STATE) }
        }
    }

    fun notifyAllContent() = notifyItemRangeChanged(0, ids.size, PAYLOAD_CONTENT)

    /** Queued / testing rows of a running session; results arrive through the database. */
    fun onTestState(state: TestUiState) {
        val ended = testState?.running == true && !state.running
        testState = state
        if (ended && host?.currentGroupId == groupId) onShown()
        val old = phases
        phases = computePhases()
        if (phases == old) return
        for (id in old.keys + phases.keys) if (old[id] != phases[id]) notifyId(id, PAYLOAD_CONTENT)
        // :bg stored the result before reporting it: show it now rather than at the next table reload
        val finished = old.keys.filter { it !in phases }
        if (finished.isNotEmpty()) refreshEntities(finished)
    }

    /** Re-reads the loaded ones of [profileIds]; the others are read fresh when they come into view. */
    private fun refreshEntities(profileIds: List<Long>) {
        val loadedIds = profileIds.filter { cache.containsKey(it) }
        if (loadedIds.isEmpty()) return
        val scope = fragment.scope ?: return
        scope.launch(Dispatchers.Main.immediate) {
            val fresh = withContext(Dispatchers.IO) { ProfileManager.getProfiles(loadedIds) }
            if (dragging) return@launch
            var outboundChanged = false
            for (entity in fresh) {
                val old = cache[entity.id] ?: continue
                if (entity.groupId != groupId) continue
                withLiveTraffic(entity)
                if (old == entity) continue
                cache[entity.id] = entity
                if (old.type != entity.type || old.outboundJson != entity.outboundJson) outboundChanged = true
                notifyId(entity.id, PAYLOAD_CONTENT)
            }
            if (outboundChanged && isFiltered) {
                indexedGeneration = -1
                refilter(0L)
            }
        }
    }

    private fun computePhases(): Map<Long, RowPhase> {
        val state = testState
        if (state == null || !state.running) return emptyMap()
        val map = HashMap<Long, RowPhase>()
        for (id in memberIds) {
            val row = state.rows[id] ?: continue
            if (row.phase != RowPhase.DONE) map[id] = row.phase
        }
        return map
    }

    /** The optional rows of the other cards of [position]'s grid row (an unloaded card has none yet). */
    fun neighbourRows(position: Int): Int {
        if (position == RecyclerView.NO_POSITION || !multiColumn) return 0
        val layoutManager = fragment.list.layoutManager as? FixedGridLayoutManager ?: return 0
        val span = layoutManager.spanCount
        val start = layoutManager.rowIndexOf(position) * span
        val end = minOf(start + span, ids.size)
        var rows = 0
        for (i in start until end) {
            if (i == position) continue
            val profile = cache[ids[i]] ?: continue
            rows = rows or RowContent.of(fragment.requireContext(), profile, this).rows
        }
        return rows
    }

    fun refreshRowNeighbours(position: Int) {
        if (position == RecyclerView.NO_POSITION || !multiColumn) return
        val layoutManager = fragment.list.layoutManager as? FixedGridLayoutManager ?: return
        val span = layoutManager.spanCount
        val start = layoutManager.rowIndexOf(position) * span
        fragment.list.post {
            val end = minOf(start + span, ids.size)
            for (i in start until end) if (i != position) notifyItemChanged(i, PAYLOAD_CONTENT)
        }
    }

    // ------------------------------------------------------------------------------------------------ traffic

    private fun applyTraffic(data: List<TrafficData>) {
        for (update in data) {
            liveTraffic[update.id] = update
            val profile = cache[update.id]
            if (profile == null) {
                // a card still showing a row that left the cache: the rebind loads it with this traffic laid over
                if (positionOf(update.id) >= 0) notifyId(update.id, PAYLOAD_CONTENT)
                continue
            }
            if (profile.tx == update.tx && profile.rx == update.rx) continue
            profile.tx = update.tx
            profile.rx = update.rx
            if (fragment.isScrolling) pendingTraffic.add(update.id) else notifyId(update.id, PAYLOAD_CONTENT)
        }
    }

    fun flushPendingTraffic() {
        if (pendingTraffic.isEmpty()) return
        pendingTraffic.forEach { notifyId(it, PAYLOAD_CONTENT) }
        pendingTraffic.clear()
    }

    /** Before the counters are reset in the database, so the reload that follows does not keep the old values. */
    fun clearTraffic() {
        liveTraffic.clear()
        for (profile in cache.values) {
            profile.tx = 0L
            profile.rx = 0L
        }
        notifyAllContent()
    }

    fun clearTraffic(id: Long) {
        liveTraffic.remove(id)
        cache[id]?.apply {
            tx = 0L
            rx = 0L
        }
        notifyId(id, PAYLOAD_CONTENT)
    }

    // ------------------------------------------------------------------------------------------------ order

    /** Drag and drop follows the desktop: refused while the list is filtered (and while selecting or picking). */
    fun canDrag(): Boolean {
        val host = host ?: return false
        if (host.select || isFiltered || !loaded) return false
        if (!dragging && host.selection.active) return false
        return DataStore.serviceState.let { it.canStop || it == BaseService.State.Stopped }
    }

    fun startDrag(profile: ProxyEntity) {
        dragging = true
        orderChanged = false
        fragment.undoManager?.flush()
        host?.selection?.startFromDrag(profile)
    }

    fun move(from: Int, to: Int) {
        if (from == to) return
        ids.add(to, ids.removeAt(from))
        positions = null
        notifyItemMoved(from, to)
        orderChanged = true
    }

    @SuppressLint("NotifyDataSetChanged")
    fun endDrag() {
        if (!dragging) return
        dragging = false
        val moved = orderChanged
        orderChanged = false
        if (moved) {
            val order = ids.toList()
            memberIds = order + memberIds.filter { it in hidden }
            runOnDefaultDispatcher {
                // A hand-placed row is a manual order, which a kept sort would undo (mainwindow_setup.cpp).
                GroupSort.setSort(groupId, GroupSortAction())
                ProfileManager.setOrder(groupId, order)
            }
            if (multiColumn) notifyDataSetChanged()
        }
        host?.selection?.onDragFinished(moved)
        host?.onDragEnded()
        if (reloadAgain) reload()
    }

    // ------------------------------------------------------------------------------------------------ undo delete

    /** The single-profile delete: hidden now, deleted when the undo snackbar goes away. */
    fun removeWithUndo(profile: ProxyEntity) {
        val manager = fragment.undoManager ?: return
        val index = positionOf(profile.id)
        if (index < 0) return
        hidden.add(profile.id)
        publish(emptySet(), false)
        manager.remove(index to profile)
    }

    override fun undo(actions: List<Pair<Int, ProxyEntity>>) {
        actions.forEach { hidden.remove(it.second.id) }
        publish(emptySet(), false)
    }

    override fun commit(actions: List<Pair<Int, ProxyEntity>>) {
        val profiles = actions.map { it.second }
        runOnDefaultDispatcher {
            for (profile in profiles) ProfileManager.deleteProfile(profile.groupId, profile.id)
        }
    }

    // ------------------------------------------------------------------------------------------------ listeners

    override suspend fun onAdd(profile: ProxyEntity) {
        if (profile.groupId == groupId) fragment.onMain { reload() }
    }

    override suspend fun onUpdated(data: List<TrafficData>) {
        fragment.onMain { applyTraffic(data) }
    }

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        // noTraffic posts announce a selection change (handled by the screen), not new data
        if (!noTraffic && profile.groupId == groupId) fragment.onMain { reload() }
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) {
        if (groupId == this.groupId) fragment.onMain { reload() }
    }

    override suspend fun groupAdd(group: ProxyGroup) = Unit

    override suspend fun groupRemoved(groupId: Long) = Unit

    override suspend fun groupUpdated(group: ProxyGroup) {
        if (group.id != groupId) return
        fragment.onMain {
            val displayChanged = this.group?.testItemsToShow != group.testItemsToShow
            this.group = group
            if (displayChanged) notifyAllContent()
        }
    }

    override suspend fun groupUpdated(groupId: Long) {
        if (groupId == this.groupId) fragment.onMain { reload() }
    }
}
