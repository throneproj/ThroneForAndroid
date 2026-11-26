package io.nekohasekai.sagernet.ui.scan

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.InvalidationTracker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.scan.StartMode
import io.nekohasekai.sagernet.database.IpListSchema
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutResolverScanBinding
import io.nekohasekai.sagernet.databinding.LayoutScanEntryRowBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.outbound.common.DnsTunnels
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.scan.IpList
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.scan.IpListParse
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScan
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.scan.ScanConfig
import io.nekohasekai.sagernet.ui.SaveDocument
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.iplist.IpListsActivity
import io.nekohasekai.sagernet.widget.applyInsetPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DialogScanInstance: one saved resolver scan of a DNS tunnel profile, opened by [ScannerUi.findResolvers]: its base
 * IP list and limits, its results and the run panel. The settings are saved when a run starts and when the screen
 * closes, and locked while the scan runs; the results are read again as the run reports them.
 */
class ResolverScanActivity : ThemedActivity() {

    companion object {
        private const val EXTRA_SCAN_ID = "scanId"
        private const val EXTRA_FROM_EDITOR = "fromEditor"

        private const val RESULTS_PREVIEW = 50_000
        private const val RESULTS_RELOAD_MS = 1000L
        private const val TABLES_RELOAD_MS = 300L
        private const val RUN_WAIT_MS = 2000L
        private const val STOP_AFTER_MAX = 1_000_000
        private const val CONCURRENCY_MIN = 1
        private const val CONCURRENCY_MAX = 1000
        private val CONCURRENCY_PRESETS = listOf("8", "16", "32", "64", "128", "256")

        private const val COLUMN_NONE = -1
        private const val COLUMN_ADDRESS = 0
        private const val COLUMN_PORT = 1
        private const val COLUMN_LOSS = 2
        private const val COLUMN_LATENCY = 3

        private const val KEY_BASE_LIST = "baseList"
        private const val KEY_FORM_FILLED = "formFilled"
        private const val KEY_SORT_COLUMN = "sortColumn"
        private const val KEY_SORT_DESCENDING = "sortDescending"
        private const val KEY_EXPORT_LIST = "exportList"

        /**
         * [fromEditor]: opened from the open editor of the scan's profile, which then takes "Use as resolvers" itself.
         * Like DialogScanInstance::Open, a screen already open on the scan comes to the front instead of a second one.
         */
        fun intent(context: Context, scanId: Long, fromEditor: Boolean): Intent =
            Intent(context, ResolverScanActivity::class.java)
                .putExtra(EXTRA_SCAN_ID, scanId)
                .putExtra(EXTRA_FROM_EDITOR, fromEditor)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        private fun key(entry: IpListEntry) = entry.cidr + "|" + entry.port

        // IpEntriesModel's address sort key: IPv4 before IPv6 before anything else, by address, then prefix length.
        private fun addressKey(cidr: String): String {
            val slash = cidr.indexOf('/')
            val address = if (slash < 0) cidr else cidr.substring(0, slash)
            fun prefix(full: Int) = (if (slash < 0) full else cidr.substring(slash + 1).toIntOrNull() ?: 0).toString().padStart(3, '0')
            Hosts.parseIpv4(address)?.let { return "4" + it.toString(16).padStart(8, '0') + "/" + prefix(32) }
            Hosts.parseIpv6(address)?.let { bytes ->
                val key = StringBuilder("6")
                for (byte in bytes) key.append((byte.toInt() and 0xFF).toString(16).padStart(2, '0'))
                return key.append('/').append(prefix(128)).toString()
            }
            return "9$cidr"
        }

        private fun <K : Comparable<K>> sortedBy(entries: List<IpListEntry>, descending: Boolean, keyOf: (IpListEntry) -> K): List<IpListEntry> {
            val ascending = compareBy<Pair<K, IpListEntry>> { it.first }
            return entries.map { keyOf(it) to it }.sortedWith(if (descending) ascending.reversed() else ascending).map { it.second }
        }

        /** scanInstanceSortEntries: the view's stable sort, which copy and export follow too. */
        private fun sortEntries(entries: List<IpListEntry>, column: Int, descending: Boolean): List<IpListEntry> {
            if (entries.size < 2) return entries
            return when (column) {
                COLUMN_ADDRESS -> sortedBy(entries, descending) { addressKey(it.cidr) }
                COLUMN_PORT -> sortedBy(entries, descending) { it.port }
                COLUMN_LOSS -> sortedBy(entries, descending) { if (it.lossPct >= 0) it.lossPct else Int.MAX_VALUE }
                COLUMN_LATENCY -> sortedBy(entries, descending) { if (it.latencyMs > 0) it.latencyMs else Int.MAX_VALUE }
                else -> entries
            }
        }
    }

    sealed class Event {
        class Snack(@JvmField val text: String) : Event()
        class Notice(@JvmField val title: String, @JvmField val text: String) : Event()
    }

    class Results(@JvmField val listId: Long, @JvmField val count: Int, @JvmField val entries: List<IpListEntry>)

    class ScanModel(private val state: SavedStateHandle) : ViewModel() {
        var scanId = -1L
            private set
        val row = MutableStateFlow<IpScan?>(null)
        val gone = MutableStateFlow(false)
        val lists = MutableStateFlow<List<IpList>?>(null)
        val results = MutableStateFlow<Results?>(null)

        /** The results in the view's order. */
        val shown = MutableStateFlow<List<IpListEntry>>(emptyList())

        /** A results task (save, copy, export, remove) runs. */
        val busy = MutableStateFlow(false)
        val events = Channel<Event>(Channel.BUFFERED)

        /** Keys of the selected results; not kept past the process. */
        val selection = HashSet<String>()

        var baseListId: Long
            get() = state[KEY_BASE_LIST] ?: -1L
            set(value) {
                state[KEY_BASE_LIST] = value
            }
        var formFilled: Boolean
            get() = state[KEY_FORM_FILLED] ?: false
            set(value) {
                state[KEY_FORM_FILLED] = value
            }
        var sortColumn: Int
            get() = state[KEY_SORT_COLUMN] ?: COLUMN_NONE
            private set(value) {
                state[KEY_SORT_COLUMN] = value
            }
        var sortDescending: Boolean
            get() = state[KEY_SORT_DESCENDING] ?: false
            private set(value) {
                state[KEY_SORT_DESCENDING] = value
            }

        /** The result list the file picker was opened for; the picker may answer a re-created screen. */
        var exportListId: Long
            get() = state[KEY_EXPORT_LIST] ?: -1L
            set(value) {
                state[KEY_EXPORT_LIST] = value
            }

        /**
         * ScanManager::IsRunning: the mirror is empty until the service first reports, so a fresh process asks it too
         * before writing over a run.
         */
        suspend fun engineRunning(): Boolean = ScanClient.isRunning(scanId) || ScanClient.state(scanId)?.running == true

        private var attached = false
        private var refreshOnStart = false
        private val observer = AtomicReference<InvalidationTracker.Observer?>(null)
        private val cleared = AtomicBoolean(false)
        private val pendingTables = HashSet<String>()
        private var tablesJob: Job? = null
        private var rowJob: Job? = null
        private var listsJob: Job? = null
        private var listsStale = false
        private var sortJob: Job? = null

        private var resultsTimer: Job? = null
        private var resultsGeneration = 0L
        private var resultsLoading = false
        private var loadedListId = -1L
        private var loadedCount = -1

        fun attach(scanId: Long) {
            if (attached) return
            attached = true
            this.scanId = scanId
            reloadRow()
            reloadLists()
            reloadResults()
            viewModelScope.launch(Dispatchers.IO) {
                observer.set(IpListsRepo.addObserver { tables -> onTables(tables) })
                if (cleared.get()) observer.getAndSet(null)?.let { IpListsRepo.removeObserver(it) }
            }
        }

        /** Each return to the screen but the first reads everything again: run reports were missed meanwhile. */
        fun started() {
            if (refreshOnStart) {
                reloadRow()
                reloadLists()
                reloadResults()
            }
            refreshOnStart = true
        }

        override fun onCleared() {
            cleared.set(true)
            observer.getAndSet(null)?.let { runOnIoDispatcher { IpListsRepo.removeObserver(it) } }
        }

        // Room's thread.
        private fun onTables(tables: Set<String>) {
            synchronized(pendingTables) { pendingTables.addAll(tables) }
            viewModelScope.launch(Dispatchers.Main) {
                if (tablesJob?.isActive == true) return@launch
                tablesJob = launch {
                    delay(TABLES_RELOAD_MS)
                    val changed = synchronized(pendingTables) { pendingTables.toSet().also { pendingTables.clear() } }
                    if (IpListSchema.SCANS in changed) reloadRow()
                    if (IpListSchema.LISTS in changed || IpListSchema.ENTRIES in changed) {
                        // A run rewrites its result list all the time, and the settings are locked meanwhile anyway.
                        if (ScanClient.isRunning(scanId)) listsStale = true else reloadLists()
                    }
                }
            }
        }

        fun runStopped() {
            if (listsStale) reloadLists()
        }

        fun reloadRow() {
            rowJob?.cancel()
            rowJob = viewModelScope.launch { freshRow() }
        }

        /** The row as stored now; null (and the screen closes) once the scan is gone. */
        suspend fun freshRow(): IpScan? {
            val scan = withContext(Dispatchers.IO) { IpScansRepo.getIpScan(scanId) }
            if (scan == null || scan.kind != IpScan.Kind.Resolver) {
                gone.value = true
                return null
            }
            row.value = scan
            if (results.value != null && scan.resultListId != loadedListId) scheduleResultsReload()
            return scan
        }

        fun reloadLists() {
            listsStale = false
            listsJob?.cancel()
            listsJob = viewModelScope.launch {
                lists.value = withContext(Dispatchers.IO) { IpListsRepo.getAllIpLists() }
            }
        }

        fun scheduleResultsReload() {
            if (resultsTimer?.isActive == true) return
            resultsTimer = viewModelScope.launch {
                delay(RESULTS_RELOAD_MS)
                resultsTimer = null
                reloadResults()
            }
        }

        // DialogScanInstance::reloadResults: a load in flight sees the newer generation when it lands and starts this one then.
        fun reloadResults() {
            resultsTimer?.cancel()
            resultsTimer = null
            resultsGeneration++
            if (resultsLoading) return
            val generation = resultsGeneration
            resultsLoading = true
            viewModelScope.launch {
                val scan = withContext(Dispatchers.IO) { IpScansRepo.getIpScan(scanId) }
                if (scan != null && scan.kind == IpScan.Kind.Resolver) row.value = scan
                val listId = (scan ?: row.value)?.resultListId ?: -1L
                val known = if (ScanClient.isRunning(scanId) && listId == loadedListId) loadedCount else -1
                var count = 0
                var unchanged = false
                var entries: List<IpListEntry> = emptyList()
                withContext(Dispatchers.IO) {
                    count = if (listId >= 0) IpListsRepo.entryCount(listId) else 0
                    unchanged = count == known
                    if (!unchanged && count > 0) {
                        entries = IpListsRepo.getEntries(listId, 0, RESULTS_PREVIEW)
                        count = if (entries.size < RESULTS_PREVIEW) entries.size else maxOf(count, entries.size)
                    }
                }
                resultsLoading = false
                if (resultsGeneration != generation) {
                    reloadResults()
                    return@launch
                }
                if (!unchanged) applyResults(listId, count, entries)
            }
        }

        private fun applyResults(listId: Long, count: Int, entries: List<IpListEntry>) {
            loadedListId = listId
            loadedCount = count
            if (selection.isNotEmpty()) {
                val present = entries.mapTo(HashSet()) { key(it) }
                selection.retainAll(present)
            }
            results.value = Results(listId, count, entries)
            resort()
        }

        // Unsorted rows keep the list's best-first order; a third tap on a column returns to it.
        fun cycleSort(column: Int) {
            when {
                sortColumn != column -> {
                    sortColumn = column
                    sortDescending = false
                }

                !sortDescending -> sortDescending = true
                else -> {
                    sortColumn = COLUMN_NONE
                    sortDescending = false
                }
            }
            resort()
        }

        private fun resort() {
            val entries = results.value?.entries.orEmpty()
            val column = sortColumn
            val descending = sortDescending
            sortJob?.cancel()
            sortJob = viewModelScope.launch {
                shown.value = if (column == COLUMN_NONE) entries else withContext(Dispatchers.Default) {
                    sortEntries(entries, column, descending)
                }
            }
        }

        suspend fun saveSettings(baseListId: Long, config: ScanConfig): String {
            var error = ""
            val saved = withContext(Dispatchers.IO) {
                // Re-read, so the progress columns written by the last run are kept.
                val stored = IpScansRepo.getIpScan(scanId)
                if (stored == null) {
                    error = app.getString(R.string.scan_resolver_gone)
                    return@withContext null
                }
                stored.baseListId = baseListId
                stored.config = config
                if (!IpScansRepo.saveScan(stored)) {
                    error = app.getString(R.string.scan_store_failed)
                    return@withContext null
                }
                stored
            }
            if (saved != null) row.value = saved
            return error
        }

        private fun task(work: suspend () -> Unit) {
            if (busy.value) return
            busy.value = true
            viewModelScope.launch {
                try {
                    work()
                } finally {
                    busy.value = false
                }
            }
        }

        fun saveAsList(listId: Long, name: String, expected: Int) = task {
            val copy = withContext(Dispatchers.IO) { IpListsRepo.copyAsUserList(listId, name) }
            if (copy == null) {
                events.send(Event.Notice(app.getString(R.string.scan_resolver_save_as), app.getString(R.string.scan_resolver_list_store_failed)))
            } else {
                val saved = if (copy.entryCount > 0) copy.entryCount else expected
                events.send(Event.Snack(app.resources.getQuantityString(R.plurals.scan_resolver_saved, saved, saved, name)))
            }
        }

        /** Blocking: the whole list in the view's order, which [sorting] took on the main thread. */
        private fun allSorted(listId: Long, sorting: Pair<Int, Boolean>): List<IpListEntry> =
            sortEntries(IpListsRepo.getEntries(listId), sorting.first, sorting.second)

        fun copyAll(listId: Long) = task {
            val sorting = sortColumn to sortDescending
            val entries = withContext(Dispatchers.IO) { allSorted(listId, sorting) }
            if (entries.isEmpty()) return@task
            val text = withContext(Dispatchers.Default) { entries.joinToString("\n") { IpListParse.formatEntry(it) } }
            events.send(Event.Snack(copiedText(text, entries.size)))
        }

        fun export(listId: Long, uri: Uri) = task {
            val sorting = sortColumn to sortDescending
            val failed = withContext(Dispatchers.IO) {
                try {
                    val entries = allSorted(listId, sorting)
                    openForWrite(uri).bufferedWriter().use { writer ->
                        for (entry in entries) {
                            writer.write(IpListParse.formatEntry(entry))
                            writer.write("\n")
                        }
                    }
                    false
                } catch (e: Exception) {
                    Logs.w(e)
                    true
                }
            }
            if (failed) {
                val title = app.getString(R.string.scan_resolver_export)
                events.send(Event.Notice(title, app.getString(R.string.scan_resolver_export_failed, uri.lastPathSegment ?: uri.toString())))
            }
        }

        // "t" truncates a file picked to be overwritten; not every provider takes it.
        private fun openForWrite(uri: Uri): OutputStream {
            val truncating = try {
                app.contentResolver.openOutputStream(uri, "wt")
            } catch (e: Exception) {
                null
            }
            return truncating ?: app.contentResolver.openOutputStream(uri) ?: throw IOException(uri.toString())
        }

        fun remove(listId: Long, entries: List<IpListEntry>) = task {
            withContext(Dispatchers.IO) { IpListsRepo.removeEntries(listId, entries) }
            if (!engineRunning()) withContext(Dispatchers.IO) { syncFoundCount() }
            reloadResults()
        }

        // scanInstanceSyncFoundCount: a stopped scan's found count follows the removals.
        private fun syncFoundCount() {
            val stored = IpScansRepo.getIpScan(scanId) ?: return
            val found = if (stored.resultListId >= 0) IpListsRepo.entryCount(stored.resultListId) else 0
            IpScansRepo.saveProgress(scanId, stored.mode, stored.activeCursor, stored.activeTotal, found, stored.removed)
        }

        /** DialogScanInstance::useResultsAsResolvers; the message to show, null when there is nothing to use. */
        suspend fun useAsResolvers(fromEditor: Boolean): String? {
            val scan = freshRow() ?: return null
            val listId = scan.resultListId
            var offer = -1L
            val message = withContext(Dispatchers.IO) {
                if (listId < 0 || ScannerUi.resultCount(scan) == 0) return@withContext null
                val profileId = scan.config.resolverProfileId
                val profile = if (profileId > 0) ProfileManager.getProfile(profileId) else null
                val source = profile?.let { DnsTunnels.profileResolverSource(it.outbound) }
                if (profile == null || source == null) return@withContext app.getString(R.string.scan_resolver_profile_gone)
                val listName = IpListsRepo.getIpListHeader(listId)?.name ?: listId.toString()
                val profileName = profile.displayName()
                if (fromEditor) {
                    // The open editor saves its own list choice back on Apply, so the choice goes to the editor.
                    offer = profileId
                    return@withContext app.getString(R.string.scan_resolver_editor_takes, profileName, listName)
                }
                if (source.ipListId.toLong() != listId) {
                    source.ipListId = listId.toInt()
                    try {
                        val used = ProfileManager.runningUses(profileId)
                        ProfileManager.updateProfile(profile.putOutbound(profile.outbound))
                        if (used) ProfileManager.noteRestartNeeded(profile.groupId)
                    } catch (e: Exception) {
                        Logs.w(e)
                        return@withContext app.getString(R.string.scan_resolver_profile_store_failed)
                    }
                }
                app.getString(R.string.scan_resolver_profile_takes, profileName, listName)
            }
            if (offer > 0) ScannerUi.offerResolverList(offer, listId)
            return message
        }
    }

    private val model: ScanModel by viewModels()
    private lateinit var binding: LayoutResolverScanBinding
    private var scanId = -1L
    private var fromEditor = false
    private var run: ScanClient.RunState? = null
    private var logLines: List<String> = emptyList()
    private var listChoices: List<Pair<Long, String>> = emptyList()
    private var acting = false
    private var closing = false
    private val adapter = ResultsAdapter()
    private lateinit var statusColors: ColorStateList
    private var highlightColor = 0

    private val exportFile = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScannerUi.launchDone()
        binding = LayoutResolverScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.root.findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.scan_resolver_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        scanId = intent.getLongExtra(EXTRA_SCAN_ID, -1L)
        fromEditor = intent.getBooleanExtra(EXTRA_FROM_EDITOR, false)
        if (scanId < 0) {
            finish()
            return
        }
        binding.content.applyInsetPadding(horizontal = true)
        binding.runPanel.applyInsetPadding(bottom = true, horizontal = true, ime = true)
        statusColors = binding.status.textColors

        setupSettings()
        setupResults()
        binding.startButton.setOnClickListener { startClicked() }
        binding.pauseButton.setOnClickListener { pauseClicked() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = close()
        })

        model.attach(scanId)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.started()
                launch { ScanClient.observe(scanId).collect { onRun(it) } }
                launch { ScanClient.results.collect { if (it == scanId) model.scheduleResultsReload() } }
                launch { model.row.collect { scan -> if (scan != null) onRow(scan) } }
                launch { model.lists.collect { lists -> if (lists != null) showLists(lists) } }
                launch { model.results.collect { showResults(it) } }
                launch { model.shown.collect { adapter.submit(it) } }
                launch { model.busy.collect { render() } }
                launch {
                    model.gone.collect { gone ->
                        if (gone && !closing) {
                            closing = true
                            finish()
                        }
                    }
                }
                launch { model.events.receiveAsFlow().collect { show(it) } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ScannerUi.launchDone()
        val other = intent.getLongExtra(EXTRA_SCAN_ID, -1L)
        if (other == scanId) {
            setIntent(intent)
            fromEditor = intent.getBooleanExtra(EXTRA_FROM_EDITOR, false)
        } else if (other >= 0) {
            // Another scan's screen came to the front; this one gets its own.
            startActivity(Intent(intent).setFlags(0))
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.content, text, Snackbar.LENGTH_LONG)

    private fun show(event: Event) {
        when (event) {
            is Event.Snack -> snackbar(event.text).show()
            is Event.Notice -> ScannerUi.notice(this, event.title, event.text)
        }
    }

    // ------------------------------------------------------------------------------------------------ settings

    private fun setupSettings() {
        binding.manageLists.setOnClickListener {
            startActivity(Intent(this, IpListsActivity::class.java).putExtra(IpListsActivity.EXTRA_SELECT_LIST_ID, model.baseListId))
        }
        binding.baseList.setOnItemClickListener { _, _, position, _ ->
            listChoices.getOrNull(position)?.let { model.baseListId = it.first }
        }
        binding.concurrencyLayout.placeholderText = IpScan.defaultConfig(IpScan.Kind.Resolver).concurrency.toString()
        binding.concurrency.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, CONCURRENCY_PRESETS))
        binding.stopAfter.doAfterTextChanged { checkStopAfter() }
        binding.concurrency.doAfterTextChanged { checkConcurrency() }
    }

    // DialogScanInstance::loadForm
    private fun fillForm(scan: IpScan) {
        model.baseListId = scan.baseListId
        showBaseList()
        binding.stopAfter.setText(if (scan.config.stopAfter > 0) scan.config.stopAfter.toString() else "")
        binding.concurrency.setText(scan.config.concurrency.toString(), false)
        checkForm()
    }

    private fun showLists(lists: List<IpList>) {
        val choices = ArrayList<Pair<Long, String>>()
        choices.add(-1L to getString(R.string.scan_resolver_select_list))
        for (list in lists) {
            // "Rescan current results" covers the scan's own list.
            if (list.role == IpList.Role.ScanResult && list.relatedTestId == scanId) continue
            choices.add(list.id to resources.getQuantityString(R.plurals.scan_resolver_list_entries, list.entryCount, list.name, list.entryCount))
        }
        listChoices = choices
        binding.baseList.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, choices.map { it.second }))
        showBaseList()
    }

    // A list that is gone shows as unselected; the stored id stays until another is picked, and the run names the problem.
    private fun showBaseList() {
        val choice = listChoices.firstOrNull { it.first == model.baseListId } ?: listChoices.firstOrNull() ?: return
        binding.baseList.setText(choice.second, false)
    }

    private fun stopAfterText() = binding.stopAfter.text?.toString()?.trim().orEmpty()

    private fun concurrencyText() = binding.concurrency.text?.toString()?.trim().orEmpty()

    private fun rangeError(min: Int, max: Int) = getString(R.string.scan_resolver_range, min, max)

    private fun checkStopAfter(): Boolean {
        val text = stopAfterText()
        val ok = text.isEmpty() || text.toIntOrNull()?.let { it in 0..STOP_AFTER_MAX } == true
        binding.stopAfterLayout.error = if (ok) null else rangeError(0, STOP_AFTER_MAX)
        return ok
    }

    private fun checkConcurrency(): Boolean {
        val ok = concurrencyText().toIntOrNull()?.let { it in CONCURRENCY_MIN..CONCURRENCY_MAX } == true
        binding.concurrencyLayout.error = if (ok) null else rangeError(CONCURRENCY_MIN, CONCURRENCY_MAX)
        return ok
    }

    private fun checkForm(): Boolean {
        val stopAfter = checkStopAfter()
        return checkConcurrency() && stopAfter
    }

    private fun isDirty(scan: IpScan): Boolean {
        if (model.baseListId != scan.baseListId) return true
        val stopAfter = stopAfterText().let { if (it.isEmpty()) 0 else it.toIntOrNull() }
        return stopAfter != scan.config.stopAfter || concurrencyText().toIntOrNull() != scan.config.concurrency
    }

    // DialogScanInstance::save; "" once stored, else why not.
    private suspend fun save(): String {
        val scan = model.row.value ?: return getString(R.string.scan_resolver_gone)
        val config = scan.config.copy()
        val stopText = stopAfterText()
        config.stopAfter = 0
        if (stopText.isNotEmpty()) {
            val value = stopText.toIntOrNull()
            if (value == null || value !in 0..STOP_AFTER_MAX) {
                checkForm()
                return getString(R.string.scan_resolver_stop_after_invalid, 0, STOP_AFTER_MAX)
            }
            config.stopAfter = value
        }
        val concurrency = concurrencyText().toIntOrNull()
        if (concurrency == null || concurrency !in CONCURRENCY_MIN..CONCURRENCY_MAX) {
            checkForm()
            return getString(R.string.scan_resolver_concurrency_invalid, CONCURRENCY_MIN, CONCURRENCY_MAX)
        }
        config.concurrency = concurrency
        if (model.engineRunning()) return getString(R.string.scan_resolver_pause_first)
        return model.saveSettings(model.baseListId, config)
    }

    private suspend fun saveIfDirty(): Boolean {
        val scan = model.row.value ?: return false
        if (!isDirty(scan)) return true
        if (!checkForm()) return false
        val error = save()
        if (error.isEmpty()) return true
        ScannerUi.notice(this, getString(R.string.scan_resolver_title), error)
        return false
    }

    // DialogScanInstance::reject: unsaved settings are saved on the way out.
    private fun close() {
        if (closing || acting) return
        launchAction {
            val scan = model.row.value
            if (scan != null && isDirty(scan)) {
                val error = save()
                if (error.isNotEmpty() &&
                    !ScannerUi.ask(this@ResolverScanActivity, getString(R.string.scan_resolver_title), getString(R.string.scan_resolver_not_saved, error))
                ) return@launchAction
            }
            closing = true
            finish()
        }
    }

    // ------------------------------------------------------------------------------------------------ run

    private fun launchAction(block: suspend CoroutineScope.() -> Unit) {
        if (acting) return
        acting = true
        render()
        lifecycleScope.launch {
            try {
                block()
            } finally {
                acting = false
                if (!closing) render()
            }
        }
    }

    /** The run's first state after a start, so the buttons do not come back in between. */
    private suspend fun awaitRun(before: ScanClient.RunState?) {
        if (run?.running == true) return
        withTimeoutOrNull(RUN_WAIT_MS) { ScanClient.observe(scanId).first { it?.running == true || (it != null && it != before) } }
    }

    // DialogScanInstance::startClicked
    private fun startClicked() = launchAction {
        val scan = model.freshRow() ?: return@launchAction
        val results = withContext(Dispatchers.IO) { ScannerUi.resultCount(scan) }
        if (results <= 0) {
            startFromBeginning()
            return@launchAction
        }
        val popup = PopupMenu(this@ResolverScanActivity, binding.startButton)
        popup.menu.add(0, 1, 0, R.string.scan_resolver_from_beginning)
        popup.menu.add(0, 2, 1, R.string.scan_resolver_rescan)
        popup.setOnMenuItemClickListener { item ->
            launchAction { if (item.itemId == 1) startFromBeginning() else rescan() }
            true
        }
        popup.show()
    }

    private suspend fun startFromBeginning() {
        if (!saveIfDirty()) return
        val scan = model.freshRow() ?: return
        if (!ScannerUi.confirmStartOver(this, scan)) return
        val before = run
        if (ScannerUi.startScan(this, scanId, StartMode.FromInitial)) awaitRun(before)
        model.reloadRow()
    }

    // DialogScanInstance::pauseClicked
    private fun pauseClicked() {
        if (run?.running == true) {
            ScanClient.pause(scanId)
            return
        }
        launchAction {
            if (!saveIfDirty()) return@launchAction
            val scan = model.freshRow() ?: return@launchAction
            val before = run
            if (ScannerUi.isResumable(scan) && ScannerUi.startScan(this@ResolverScanActivity, scanId, StartMode.Resume)) awaitRun(before)
            model.reloadRow()
        }
    }

    private suspend fun rescan() {
        if (!saveIfDirty()) return
        val before = run
        if (ScannerUi.startScan(this, scanId, StartMode.RescanResults)) awaitRun(before)
        model.reloadRow()
    }

    private fun onRun(state: ScanClient.RunState?) {
        val previous = run
        run = state
        val running = state?.running == true
        if (previous == null || previous.running != running || previous.status != state?.status) {
            model.reloadRow()
            model.scheduleResultsReload()
            if (!running) model.runStopped()
        }
        // A stopped scan has no live log; the last lines stay up until the next run.
        if (state != null && (running || state.log.isNotEmpty())) logLines = state.log
        render()
    }

    private fun onRow(scan: IpScan) {
        if (!model.formFilled) {
            model.formFilled = true
            fillForm(scan)
        }
        supportActionBar?.subtitle = scan.name
        render()
    }

    // DialogScanInstance::refreshRun and applyLock
    private fun render() {
        val scan = model.row.value ?: return
        val state = run
        val running = state?.running == true
        val live = ScannerUi.live(scan, state)

        if (running && live.total == 0L) {
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.setProgressCompat(ScannerUi.permille(live.tested, live.total), false)
        }

        val line = ScannerUi.statusLine(this, scan, live, true)
        binding.status.text = line.text
        if (line.error) binding.status.setTextColor(getColorAttr(R.attr.colorError)) else binding.status.setTextColor(statusColors)

        val logs = listOf(binding.log0, binding.log1, binding.log2)
        val first = maxOf(0, logLines.size - logs.size)
        logs.forEachIndexed { i, label -> label.text = logLines.getOrNull(first + i).orEmpty() }

        val busy = model.busy.value
        val resumable = !running && ScannerUi.isResumable(scan)
        val idle = !running && !busy && !acting
        binding.startButton.isEnabled = idle
        binding.pauseButton.setText(if (resumable) R.string.scan_resolver_resume else R.string.scan_resolver_pause)
        binding.pauseButton.isEnabled = if (running) !live.stopping && !acting else idle && resumable

        val enabled = !running
        binding.intro.isEnabled = enabled
        binding.baseListLayout.isEnabled = enabled
        binding.manageLists.isEnabled = enabled
        binding.stopAfterLayout.isEnabled = enabled
        binding.concurrencyLayout.isEnabled = enabled
        val hasResults = !busy && (model.results.value?.count ?: 0) > 0 && adapter.itemCount > 0
        binding.removeResults.isEnabled = !running && hasResults
        binding.saveAsList.isEnabled = hasResults
        binding.copyResults.isEnabled = hasResults
        binding.exportResults.isEnabled = hasResults
        binding.useAsResolvers.isEnabled = hasResults && !acting
    }

    // ------------------------------------------------------------------------------------------------ results

    private fun setupResults() {
        binding.results.layoutManager = LinearLayoutManager(this)
        binding.results.adapter = adapter
        ViewCompat.setTooltipText(binding.useAsResolvers, getString(R.string.scan_resolver_use_tip))
        ViewCompat.setTooltipText(binding.copyResults, getString(R.string.scan_resolver_copy_tip))
        ViewCompat.setTooltipText(binding.columnLoss, getString(R.string.scan_column_loss_tip))
        binding.useAsResolvers.setOnClickListener { useAsResolvers() }
        binding.saveAsList.setOnClickListener { saveAsList() }
        binding.copyResults.setOnClickListener { copyResults() }
        binding.exportResults.setOnClickListener { exportResults() }
        binding.removeResults.setOnClickListener { removeSelected() }
        binding.columnAddress.setOnClickListener { model.cycleSort(COLUMN_ADDRESS); showColumns() }
        binding.columnPort.setOnClickListener { model.cycleSort(COLUMN_PORT); showColumns() }
        binding.columnLoss.setOnClickListener { model.cycleSort(COLUMN_LOSS); showColumns() }
        binding.columnLatency.setOnClickListener { model.cycleSort(COLUMN_LATENCY); showColumns() }
        showColumns()
    }

    private fun showColumns() {
        fun title(column: Int, text: Int): String {
            val name = getString(text)
            if (model.sortColumn != column) return name
            return name + if (model.sortDescending) " ▼" else " ▲"
        }
        binding.columnAddress.text = title(COLUMN_ADDRESS, R.string.scan_column_address)
        binding.columnPort.text = title(COLUMN_PORT, R.string.scan_column_port)
        binding.columnLoss.text = title(COLUMN_LOSS, R.string.scan_column_loss)
        binding.columnLatency.text = title(COLUMN_LATENCY, R.string.scan_column_latency)
    }

    private fun showResults(results: Results?) {
        val count = results?.count ?: 0
        val loaded = results?.entries?.size ?: 0
        binding.resultsSummary.text = when {
            count == 0 -> getString(R.string.scan_resolver_no_results)
            loaded < count -> getString(R.string.scan_resolver_showing_first, loaded, count)
            else -> resources.getQuantityString(R.plurals.scan_resolver_result_count, count, count)
        }
        render()
    }

    private fun useAsResolvers() {
        val title = getString(R.string.scan_resolver_use)
        launchAction {
            val message = model.useAsResolvers(fromEditor) ?: return@launchAction
            ScannerUi.notice(this@ResolverScanActivity, title, message)
        }
    }

    private fun saveAsList() {
        val scan = model.row.value ?: return
        val count = model.results.value?.count ?: 0
        if (scan.resultListId < 0 || count == 0 || model.busy.value) return
        promptName(getString(R.string.scan_resolver_results_name, scan.name)) { name ->
            if (name.isNotEmpty() && !model.busy.value) model.saveAsList(scan.resultListId, name, count)
        }
    }

    private fun promptName(text: String, onDone: (String) -> Unit) {
        val layout = TextInputLayout(this).apply { hint = getString(R.string.scan_resolver_save_as_name) }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            setText(text)
            setSelection(text.length)
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog: AlertDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_resolver_save_as)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onDone(input.text?.toString()?.trim().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        input.requestFocus()
    }

    private fun copyResults() {
        val selected = adapter.selectedInOrder()
        if (selected.isNotEmpty()) {
            snackbar(copiedText(selected.joinToString("\n") { IpListParse.formatEntry(it) }, selected.size)).show()
            return
        }
        val scan = model.row.value ?: return
        if (scan.resultListId < 0 || (model.results.value?.count ?: 0) == 0 || model.busy.value) return
        model.copyAll(scan.resultListId)
    }

    private fun exportResults() {
        val scan = model.row.value ?: return
        if (scan.resultListId < 0 || (model.results.value?.count ?: 0) == 0 || model.busy.value) return
        model.exportListId = scan.resultListId
        startFilesForResult(exportFile, scan.name.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".txt")
    }

    // The picker has already made the file, so a list that cannot be found is said, not skipped.
    private fun exportTo(uri: Uri) = lifecycleScope.launch {
        val listId = model.exportListId.takeIf { it >= 0 } ?: model.freshRow()?.resultListId ?: -1L
        if (listId >= 0) {
            model.export(listId, uri)
        } else {
            val text = getString(R.string.scan_resolver_export_failed, uri.lastPathSegment ?: uri.toString())
            ScannerUi.notice(this@ResolverScanActivity, getString(R.string.scan_resolver_export), text)
        }
    }

    private fun removeSelected() {
        val scan = model.row.value ?: return
        if (scan.resultListId < 0 || model.busy.value || run?.running == true) return
        val entries = adapter.selectedInOrder()
        if (entries.isEmpty()) return
        model.remove(scan.resultListId, entries)
    }

    private fun highlight(): Int {
        if (highlightColor == 0) {
            val accent = getColorAttr(R.attr.colorAccent)
            highlightColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 48), getColorAttr(R.attr.colorSurface))
        }
        return highlightColor
    }

    private class EntryHolder(val binding: LayoutScanEntryRowBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class ResultsAdapter : RecyclerView.Adapter<EntryHolder>() {
        private var entries: List<IpListEntry> = emptyList()

        fun submit(next: List<IpListEntry>) {
            entries = next
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
            render()
        }

        /** The selected entries in the view's order. */
        fun selectedInOrder(): List<IpListEntry> =
            if (model.selection.isEmpty()) emptyList() else entries.filter { key(it) in model.selection }

        override fun getItemCount() = entries.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val holder = EntryHolder(LayoutScanEntryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.itemView.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                val key = key(entries[position])
                if (!model.selection.remove(key)) model.selection.add(key)
                notifyItemChanged(position)
            }
            return holder
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val entry = entries[position]
            val row = holder.binding
            row.address.text = entry.cidr
            row.port.text = if (entry.port > 0) entry.port.toString() else ""
            row.first.text = if (entry.lossPct >= 0) getString(R.string.scan_value_percent, entry.lossPct) else ""
            row.second.text = if (entry.latencyMs > 0) getString(R.string.scan_value_ms, entry.latencyMs) else ""
            holder.itemView.setBackgroundColor(if (key(entry) in model.selection) highlight() else 0)
        }
    }
}

private fun copiedText(text: String, count: Int): String = if (SagerNet.trySetPrimaryClip(text)) {
    app.resources.getQuantityString(R.plurals.scan_resolver_copied, count, count)
} else {
    app.getString(R.string.action_export_err)
}
