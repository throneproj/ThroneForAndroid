package io.nekohasekai.sagernet.ui.iplist

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutIpListEditHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutIpListEntryBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.scan.IpList
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.scan.IpListParse
import io.nekohasekai.sagernet.scan.IpListParseResult
import io.nekohasekai.sagernet.scan.IpListUpdater
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * dialog_edit_ip_list.cpp: a list's name, source and entries. Entries are added through a dialog with the parser's
 * feedback and removed one by one instead of edited as text, and a stored list is read page by page, so long lists
 * stay smooth. Lists a scan owns open read-only. The result carries the saved list's id as [EXTRA_LIST_ID].
 */
class IpListEditActivity : ThemedActivity(R.layout.layout_ip_list_edit) {

    companion object {
        const val EXTRA_LIST_ID = "listId"

        /** A new list with a URL source (Import > From URL). */
        const val EXTRA_URL_SOURCE = "urlSource"

        /** A new manual list that starts by adding the clipboard's text (Import > From text). */
        const val EXTRA_FROM_CLIPBOARD = "fromClipboard"

        private const val PAGE = 500
        private const val CACHED_PAGES = 12
        private const val LIVE_PARSE_LINES = 20_000
        private const val TYPE_HEADER = 0
        private const val TYPE_ENTRY = 1
        private const val MENU_COPY = 1
        private const val MENU_REMOVE = 2
        private const val KEY_DRAFT = "draft"
        private const val STATE_SAVE_AS = "saveAs"

        /** Longer entry lists and dialog texts are not kept across a process restart: the saved state stays small. */
        private const val DRAFT_ENTRIES = 2_000
        private const val DRAFT_TEXT = 50_000

        @JvmStatic
        fun intent(context: Context, listId: Long): Intent =
            Intent(context, IpListEditActivity::class.java).putExtra(EXTRA_LIST_ID, listId)

        @JvmStatic
        @JvmOverloads
        fun newIntent(context: Context, url: Boolean = false, fromClipboard: Boolean = false): Intent =
            Intent(context, IpListEditActivity::class.java)
                .putExtra(EXTRA_URL_SOURCE, url)
                .putExtra(EXTRA_FROM_CLIPBOARD, fromClipboard)

        private fun key(entry: IpListEntry) = entry.cidr + " " + entry.port
    }

    sealed class Event {
        class Warning(val text: String) : Event()
        class Saved(val id: Long) : Event()
        object SaveNow : Event()
    }

    class EditorModel(private val state: SavedStateHandle) : ViewModel() {
        private class Stored(val list: IpList, val generation: Long, val owner: String, val all: List<IpListEntry>?)

        /** The unsaved edits kept when the process was stopped; [start] applies them. */
        private val draft: Bundle? = state.get<Bundle>(KEY_DRAFT)?.takeIf { it.containsKey("name") }

        init {
            state.setSavedStateProvider(KEY_DRAFT) { saveDraft() }
        }

        private var started = false
        var loaded = false
            private set
        var missing = false
            private set
        var isNew = true
            private set
        var scanOwned = false
            private set
        var ownerName = ""
            private set
        var managedNote = ""
            private set

        /** The list as stored; a new list's defaults. */
        var list = IpList()
            private set
        var clipboardPending = false

        var name = ""
        var kind = IpList.SourceKind.Manual
            private set
        var url = ""
            private set
        var autoUpdate = false
        var intervalText = ""
        var dirty = false

        private var storedTotal = 0
        private var storedGeneration = -1L
        private var scanEntries: List<IpListEntry>? = null

        /** A manual list's entries once edited; null while they are the stored ones. */
        var working: ArrayList<IpListEntry>? = null
            private set
        private var workingKeys: HashSet<String>? = null
        private var workingTooLarge = false

        var fetched: List<IpListEntry> = emptyList()
            private set
        var fetchedRejected = 0
            private set
        var fetchedSamples: List<String> = emptyList()
            private set
        private var fetchedKind = IpList.SourceKind.Manual
        private var fetchedSource = ""
        private var hasFetch = false

        var fetching = false
            private set
        var saving = false
            private set
        var materializing = false
            private set
        private var saveAfterFetch = false

        /** Shown in place of the summary until the source changes: why a fetch failed. */
        var notice = ""
            private set
        var urlError = ""
        var intervalError = ""

        /** The Add entries dialog's text while it is open, so a recreated screen shows it again. */
        var addText: String? = null

        /** Bumped whenever the rows the list shows change. */
        var rowsVersion = 0
            private set

        val version = MutableStateFlow(0)
        val events = Channel<Event>(Channel.UNLIMITED)
        val pageLoads = Channel<IntRange>(Channel.UNLIMITED)

        private var pageGeneration = 0
        private val pages = object : LinkedHashMap<Int, List<IpListEntry>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<IpListEntry>>?): Boolean =
                size > CACHED_PAGES
        }
        private val loadingPages = HashSet<Int>()

        val editable: Boolean get() = loaded && !scanOwned && !missing

        fun changed() {
            version.value++
        }

        private fun rowsChanged() {
            rowsVersion++
            changed()
        }

        fun start(listId: Long, urlSource: Boolean, fromClipboard: Boolean) {
            if (started) return
            started = true
            val restored = draft
            if (listId < 0) {
                kind = if (urlSource) IpList.SourceKind.Url else IpList.SourceKind.Manual
                autoUpdate = list.autoUpdate
                intervalText = maxOf(IpListUpdater.MIN_INTERVAL_MINUTES, list.updateInterval).toString()
                clipboardPending = fromClipboard && !urlSource
                // The draft says whether the clipboard was taken already.
                if (restored != null) applyDraft(restored)
                loaded = true
                rowsChanged()
                return
            }
            viewModelScope.launch {
                val stored = withContext(Dispatchers.IO) { readStored(listId) }
                isNew = false
                loaded = true
                if (stored == null) {
                    missing = true
                    rowsChanged()
                    return@launch
                }
                list = stored.list
                scanOwned = list.role != IpList.Role.User
                ownerName = stored.owner
                managedNote = if (scanOwned) managedNoteFor(stored.owner) else ""
                name = list.name
                kind = list.sourceKind
                url = if (kind == IpList.SourceKind.Url) list.source else ""
                autoUpdate = list.autoUpdate
                intervalText = maxOf(IpListUpdater.MIN_INTERVAL_MINUTES, list.updateInterval).toString()
                storedTotal = list.entryCount
                storedGeneration = stored.generation
                scanEntries = stored.all
                if (!scanOwned) {
                    if (restored != null) applyDraft(restored)
                    markAll()
                }
                rowsChanged()
            }
        }

        /** The edits to keep if the process is stopped: the header, and the entries and dialog text while short. */
        private fun saveDraft(): Bundle {
            if (!loaded) return draft ?: Bundle()
            if (!editable) return Bundle()
            return Bundle().apply {
                putString("name", name)
                putInt("kind", kind.code)
                putString("url", url)
                putBoolean("autoUpdate", autoUpdate)
                putString("interval", intervalText)
                putBoolean("dirty", dirty)
                putBoolean("clipboard", clipboardPending)
                val rows = working
                if (rows != null && rows.size <= DRAFT_ENTRIES) {
                    putStringArray("cidrs", Array(rows.size) { rows[it].cidr })
                    putIntArray("ports", IntArray(rows.size) { rows[it].port })
                    putIntArray("latency", IntArray(rows.size) { rows[it].latencyMs })
                    putIntArray("loss", IntArray(rows.size) { rows[it].lossPct })
                    putBoolean("tooLarge", workingTooLarge)
                }
                addText?.takeIf { it.length <= DRAFT_TEXT }?.let { putString("addText", it) }
            }
        }

        private fun applyDraft(bundle: Bundle) {
            name = bundle.getString("name").orEmpty()
            kind = IpList.SourceKind.of(bundle.getInt("kind"))
            url = bundle.getString("url").orEmpty()
            autoUpdate = bundle.getBoolean("autoUpdate")
            intervalText = bundle.getString("interval").orEmpty()
            dirty = bundle.getBoolean("dirty")
            clipboardPending = bundle.getBoolean("clipboard")
            addText = bundle.getString("addText")
            val cidrs = bundle.getStringArray("cidrs") ?: return
            val ports = bundle.getIntArray("ports") ?: return
            val latency = bundle.getIntArray("latency") ?: return
            val loss = bundle.getIntArray("loss") ?: return
            if (ports.size != cidrs.size || latency.size != cidrs.size || loss.size != cidrs.size) return
            val entries = ArrayList<IpListEntry>(cidrs.size)
            for (i in cidrs.indices) entries.add(IpListEntry(cidrs[i], ports[i], latency[i], loss[i]))
            setWorking(entries, bundle.getBoolean("tooLarge"))
        }

        private fun readStored(id: Long): Stored? {
            val header = IpListsRepo.getIpListHeader(id) ?: return null
            if (header.isHidden) return null
            header.entryCount = IpListsRepo.entryCount(id)
            val generation = runCatching { SagerDatabase.ipListDao.generation(id) }.getOrNull() ?: -1L
            if (header.role == IpList.Role.User) return Stored(header, generation, "", null)
            val owner = IpScansRepo.getIpScan(header.relatedTestId)?.name.orEmpty()
            return Stored(header, generation, owner, IpListsRepo.getEntries(id))
        }

        private fun managedNoteFor(owner: String): String =
            if (owner.isEmpty()) app.getString(R.string.iplist_managed_by_deleted)
            else app.getString(R.string.iplist_managed_by, owner)

        /** Follows changes made elsewhere: a refresh, a running scan, a delete. */
        suspend fun reloadStored() {
            if (!loaded || isNew || missing || saving) return
            val id = list.id
            val stored = withContext(Dispatchers.IO) { readStored(id) }
            if (saving || missing) return
            if (stored == null) {
                missing = true
                rowsChanged()
                return
            }
            list = stored.list
            if (scanOwned) {
                ownerName = stored.owner
                managedNote = managedNoteFor(stored.owner)
                scanEntries = stored.all
                rowsChanged()
                return
            }
            if (stored.generation != storedGeneration || stored.list.entryCount != storedTotal) {
                storedGeneration = stored.generation
                storedTotal = stored.list.entryCount
                resetPages()
                rowsChanged()
            } else {
                changed()
            }
        }

        fun fetchMatches(): Boolean = hasFetch && fetchedKind == kind && fetchedSource == url.trim()

        private fun memoryRows(): List<IpListEntry>? = when {
            scanOwned -> scanEntries
            kind == IpList.SourceKind.Url -> if (fetchMatches()) fetched else null
            else -> working
        }

        fun rowCount(): Int = when {
            !loaded || missing -> 0
            scanOwned -> scanEntries?.size ?: 0
            else -> memoryRows()?.size ?: if (isNew) 0 else storedTotal
        }

        fun entryAt(position: Int): IpListEntry? {
            if (!loaded || missing) return null
            memoryRows()?.let { return it.getOrNull(position) }
            if (scanOwned || isNew) return null
            return storedAt(position)
        }

        /** kEditIpListEditableEntries: the list as opened (or fetched) was too long to edit. */
        fun tooLarge(): Boolean = if (working != null) workingTooLarge else !isNew && storedTotal > IpListTexts.MAX_EDITABLE

        fun entriesEditable(): Boolean = editable && kind == IpList.SourceKind.Manual && !tooLarge()

        fun setKind(value: IpList.SourceKind) {
            if (kind == value) return
            // What the URL fetched becomes the manual entries, as the desktop editor's text does.
            if (value == IpList.SourceKind.Manual && working == null && fetchMatches()) {
                setWorking(ArrayList(fetched), fetched.size > IpListTexts.MAX_EDITABLE)
            }
            kind = value
            notice = ""
            urlError = ""
            intervalError = ""
            dirty = true
            rowsChanged()
        }

        fun setUrl(value: String) {
            if (url == value) return
            val matched = fetchMatches()
            url = value
            notice = ""
            urlError = ""
            dirty = true
            if (fetchMatches() != matched) rowsChanged() else changed()
        }

        fun urlProblem(): String {
            if (kind != IpList.SourceKind.Url) return ""
            val source = url.trim()
            val valid = IpListTexts.isHttpUrl(source) && IpListTexts.urlHost(source).isNotEmpty()
            return if (valid) "" else app.getString(R.string.iplist_url_invalid)
        }

        fun parseInterval(text: String): Int? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return IpListTexts.DEFAULT_INTERVAL
            return trimmed.toIntOrNull()?.takeIf { it in IpListUpdater.MIN_INTERVAL_MINUTES..IpListTexts.MAX_INTERVAL }
        }

        fun intervalProblem(): String {
            if (kind != IpList.SourceKind.Url || !autoUpdate || parseInterval(intervalText) != null) return ""
            return app.getString(R.string.iplist_interval_range, IpListUpdater.MIN_INTERVAL_MINUTES, IpListTexts.MAX_INTERVAL)
        }

        fun markAll() {
            urlError = urlProblem()
            intervalError = intervalProblem()
        }

        /** The URL's file name without its extension, else its host, else "IP list". */
        fun defaultName(): String {
            val source = if (kind == IpList.SourceKind.Url) url.trim() else ""
            var result = ""
            if (source.isNotEmpty()) {
                result = if (IpListTexts.isHttpUrl(source)) {
                    val uri = Uri.parse(source)
                    val file = uri.path.orEmpty().substringAfterLast('/')
                    val base = if (file.contains('.')) file.substring(0, file.lastIndexOf('.')) else file
                    base.ifEmpty { uri.host.orEmpty() }
                } else {
                    source
                }
            }
            return result.ifEmpty { app.getString(R.string.iplist_default_name) }
        }

        private fun setWorking(entries: ArrayList<IpListEntry>, tooLarge: Boolean) {
            working = entries
            workingKeys = null
            workingTooLarge = tooLarge
        }

        fun keys(): HashSet<String> {
            workingKeys?.let { return it }
            val entries = working.orEmpty()
            val set = HashSet<String>(entries.size * 2 + 16)
            for (entry in entries) set.add(key(entry))
            workingKeys = set
            return set
        }

        /** Loads a stored manual list's entries to edit them; false while they cannot be edited. */
        suspend fun ensureWorking(): Boolean {
            if (working != null) return true
            if (!entriesEditable() || materializing) return false
            if (isNew || storedTotal == 0) {
                setWorking(ArrayList(), false)
                rowsChanged()
                return true
            }
            materializing = true
            changed()
            try {
                val id = list.id
                val entries = withContext(Dispatchers.IO) { IpListsRepo.getEntries(id) }
                if (working == null) setWorking(ArrayList(entries), entries.size > IpListTexts.MAX_EDITABLE)
            } finally {
                materializing = false
                rowsChanged()
            }
            return working != null
        }

        fun addEntries(entries: List<IpListEntry>) {
            val target = working ?: return
            val keys = keys()
            var added = 0
            for (entry in entries) {
                if (!keys.add(key(entry))) continue
                target.add(entry)
                added++
            }
            if (added == 0) return
            dirty = true
            rowsChanged()
        }

        fun removeEntry(entry: IpListEntry) {
            val target = working ?: return
            val index = target.indexOfFirst { it.cidr == entry.cidr && it.port == entry.port }
            if (index < 0) return
            target.removeAt(index)
            workingKeys?.remove(key(entry))
            dirty = true
            rowsChanged()
        }

        private fun storedAt(position: Int): IpListEntry? {
            val page = position / PAGE
            val rows = pages[page]
            if (rows == null) {
                loadPage(page)
                return null
            }
            val offset = position % PAGE
            prefetch(if (offset >= PAGE / 2) page + 1 else page - 1)
            return rows.getOrNull(offset)
        }

        private fun prefetch(page: Int) {
            if (page < 0 || page.toLong() * PAGE >= storedTotal || pages.containsKey(page)) return
            loadPage(page)
        }

        private fun loadPage(page: Int) {
            if (!loadingPages.add(page)) return
            val generation = pageGeneration
            val id = list.id
            viewModelScope.launch {
                val rows = withContext(Dispatchers.IO) { IpListsRepo.getEntries(id, page * PAGE, PAGE) }
                if (generation != pageGeneration) return@launch
                loadingPages.remove(page)
                pages[page] = rows
                pageLoads.trySend(page * PAGE until page * PAGE + PAGE)
            }
        }

        private fun resetPages() {
            pageGeneration++
            pages.clear()
            loadingPages.clear()
        }

        /** startFetch / onFetched; [thenSave] saves once the fetch matches the inputs. */
        fun startFetch(thenSave: Boolean) {
            if (fetching || kind == IpList.SourceKind.Manual) return
            val problem = urlProblem()
            if (problem.isNotEmpty()) {
                if (thenSave) events.trySend(Event.Warning(problem))
                notice = problem
                urlError = problem
                changed()
                return
            }
            val sourceKind = kind
            val source = url.trim()
            fetching = true
            saveAfterFetch = thenSave
            notice = ""
            changed()
            viewModelScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        IpListUpdater.fetchEntries(sourceKind, source)
                    } catch (e: Exception) {
                        IpListUpdater.ImportOutcome(error = e.readableMessage)
                    }
                }
                fetching = false
                val save = saveAfterFetch
                saveAfterFetch = false
                if (outcome.error.isNotEmpty()) {
                    notice = app.getString(R.string.iplist_fetch_failed, outcome.error)
                    changed()
                    if (save) events.send(Event.Warning(app.getString(R.string.iplist_could_not_fetch, outcome.error)))
                    return@launch
                }
                fetched = outcome.entries
                fetchedRejected = outcome.rejected
                fetchedSamples = outcome.samples
                fetchedKind = sourceKind
                fetchedSource = source
                hasFetch = true
                dirty = true
                rowsChanged()
                if (save && fetchMatches()) events.send(Event.SaveNow)
            }
        }

        fun store(candidate: IpList, entries: List<IpListEntry>, writeEntries: Boolean) {
            if (saving) return
            saving = true
            changed()
            val add = isNew
            viewModelScope.launch {
                val stored = withContext(Dispatchers.IO) {
                    val done = if (add) {
                        IpListsRepo.addIpList(candidate)
                    } else {
                        IpListsRepo.saveHeader(candidate) && (!writeEntries || IpListsRepo.replaceEntries(candidate.id, entries))
                    }
                    if (done) IpListUpdater.schedule()
                    done
                }
                saving = false
                if (!stored) {
                    changed()
                    events.send(Event.Warning(app.getString(R.string.iplist_store_failed)))
                    return@launch
                }
                dirty = false
                list = candidate
                changed()
                events.send(Event.Saved(candidate.id))
            }
        }
    }

    private class AddPreview(val lines: Int, val parsed: IpListParseResult?, val fresh: List<IpListEntry>, val present: Int)

    private val model: EditorModel by viewModels()
    private lateinit var recycler: FastScrollRecyclerView
    private val adapter = EditorAdapter()
    private var header: HeaderHolder? = null
    private var menuState = ""
    private var addDialog: AlertDialog? = null
    private var addOpening = false

    /** The "Save as IP list" prompt's text while it is open, and one to show again once the list has loaded. */
    private var saveAsText: String? = null
    private var saveAsDialog: AlertDialog? = null
    private var pendingSaveAs: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingSaveAs = savedInstanceState?.getString(STATE_SAVE_AS)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        recycler = findViewById(R.id.list)
        recycler.layoutManager = FixedLinearLayoutManager(recycler)
        recycler.itemAnimator = null
        recycler.adapter = adapter
        recycler.applyListInsets(ime = true)
        onBackPressedDispatcher.addCallback(this) { close() }
        model.start(
            intent.getLongExtra(EXTRA_LIST_ID, -1L),
            intent.getBooleanExtra(EXTRA_URL_SOURCE, false),
            intent.getBooleanExtra(EXTRA_FROM_CLIPBOARD, false),
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.version.collect { render() } }
                launch { for (range in model.pageLoads) adapter.pageLoaded(range) }
                launch { for (event in model.events) handle(event) }
                launch { observeStored() }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        (saveAsText ?: pendingSaveAs)?.let { outState.putString(STATE_SAVE_AS, it) }
    }

    override fun onDestroy() {
        // The model keeps the Add entries text, so the recreated screen shows the dialog again.
        addDialog?.apply {
            setOnDismissListener(null)
            dismiss()
        }
        addDialog = null
        saveAsDialog?.dismiss()
        super.onDestroy()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(R.id.coordinator), text, Snackbar.LENGTH_LONG)

    // The clipboard reads empty until the window has focus (Android 10+).
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) takeClipboard()
    }

    private fun takeClipboard() {
        if (!model.clipboardPending || !model.loaded || !hasWindowFocus()) return
        model.clipboardPending = false
        addEntries(SagerNet.getClipboardText())
    }

    private suspend fun observeStored() {
        val signal = Channel<Unit>(Channel.CONFLATED)
        val observer = withContext(Dispatchers.IO) { IpListsRepo.addObserver { signal.trySend(Unit) } }
        try {
            signal.trySend(Unit)
            for (unit in signal) {
                model.reloadStored()
                delay(1000)
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { IpListsRepo.removeObserver(observer) }
        }
    }

    private fun render() {
        val m = model
        supportActionBar?.setTitle(
            when {
                m.scanOwned -> R.string.iplist_view_title
                m.isNew -> R.string.iplist_new_title
                else -> R.string.iplist_edit_title
            }
        )
        header?.bind()
        adapter.sync()
        val state = listOf(m.loaded, m.missing, m.scanOwned, m.fetching, m.saving, m.rowCount() > 0).toString()
        if (state != menuState) {
            menuState = state
            invalidateOptionsMenu()
        }
        takeClipboard()
        m.addText?.let { if (addDialog == null && !addOpening && m.entriesEditable()) addEntries(it) }
        pendingSaveAs?.let {
            if (!m.loaded) return@let
            pendingSaveAs = null
            saveAs(it)
        }
    }

    private fun handle(event: Event) {
        when (event) {
            is Event.Warning -> warning(event.text)
            is Event.Saved -> {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_LIST_ID, event.id))
                finish()
            }

            Event.SaveNow -> save()
        }
    }

    private fun warning(text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(supportActionBar?.title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun quantity(id: Int, n: Int): String = resources.getQuantityString(id, n, IpListTexts.number(n))

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ip_list_edit_menu, menu)
        val m = model
        menu.findItem(R.id.action_apply)?.apply {
            isVisible = !m.scanOwned && !m.missing
            isEnabled = m.loaded && !m.fetching && !m.saving
        }
        menu.findItem(R.id.action_ip_list_save_as)?.isVisible = m.scanOwned && !m.missing && m.rowCount() > 0
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_apply -> save()
            R.id.action_ip_list_save_as -> saveAs()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun close() {
        val m = model
        if (m.saving) return
        if (!m.dirty || !m.editable) {
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

    /** accept(): a URL whose entries were not fetched yet is fetched first, then saved. */
    private fun save() {
        val m = model
        if (!m.loaded || m.fetching || m.saving || m.materializing) return
        if (!m.editable) {
            finish()
            return
        }
        m.markAll()
        if (m.urlError.isNotEmpty() || m.intervalError.isNotEmpty()) {
            render()
            recycler.scrollToPosition(0)
            return
        }
        val remote = m.kind != IpList.SourceKind.Manual
        val source = if (remote) m.url.trim() else ""
        val original = m.list
        if (!m.isNew && IpListUpdater.isRefreshing(original.id)) {
            warning(getString(R.string.iplist_refresh_busy))
            return
        }
        val sourceChanged = m.isNew || m.kind != original.sourceKind || source != original.source
        var entries: List<IpListEntry> = emptyList()
        var writeEntries = false
        var fromFetch = false
        if (!remote) {
            val working = m.working
            if (working != null) {
                entries = ArrayList(working)
                writeEntries = true
            } else if (m.isNew) {
                writeEntries = true
            }
        } else if (m.fetchMatches()) {
            entries = m.fetched
            writeEntries = true
            fromFetch = true
        } else if (sourceChanged) {
            m.startFetch(thenSave = true)
            return
        }
        val candidate = IpList(
            id = original.id,
            name = m.name.trim().ifEmpty { m.defaultName() },
            relatedTestId = original.relatedTestId,
            role = original.role,
            sourceKind = m.kind,
            source = source,
            autoUpdate = remote && m.autoUpdate,
            updateInterval = m.parseInterval(m.intervalText) ?: original.updateInterval,
            lastUpdate = if (sourceChanged) 0L else original.lastUpdate,
            lastError = if (remote) original.lastError else "",
        )
        if (fromFetch) {
            candidate.lastUpdate = System.currentTimeMillis() / 1000
            candidate.lastError = ""
        }
        if (m.isNew) {
            candidate.entries = entries
            candidate.entryCount = entries.size
            candidate.entriesLoaded = true
        }
        m.store(candidate, entries, writeEntries)
    }

    /** dialog_scan_instance.cpp's Save as IP list, for a list a scan owns; [text] is a recreated prompt's. */
    private fun saveAs(text: String? = null) {
        val m = model
        if (!m.scanOwned || m.missing || saveAsDialog != null) return
        val suggested = if (m.ownerName.isNotEmpty()) getString(R.string.iplist_results_name, m.ownerName)
        else getString(R.string.iplist_copy_name, m.list.name)
        val shown = text ?: suggested
        saveAsText = shown
        saveAsDialog = IpListTexts.prompt(
            this, getString(R.string.iplist_save_as), getString(R.string.iplist_save_as_name), shown,
            onText = { saveAsText = it },
        ) { name ->
            if (name.isEmpty()) return@prompt
            val id = m.list.id
            lifecycleScope.launch {
                val copy = withContext(Dispatchers.IO) { IpListsRepo.copyAsUserList(id, name) }
                if (copy == null) {
                    warning(getString(R.string.iplist_store_failed))
                    return@launch
                }
                snackbar(
                    resources.getQuantityString(R.plurals.iplist_saved, copy.entryCount, IpListTexts.number(copy.entryCount), name)
                ).show()
            }
        }.apply {
            setOnDismissListener {
                saveAsDialog = null
                saveAsText = null
            }
        }
    }

    private fun summary(): Pair<String, Boolean> {
        val m = model
        if (!m.loaded) return "" to false
        if (m.missing) return getString(R.string.iplist_missing) to true
        if (m.fetching) return getString(R.string.iplist_fetching) to false
        if (m.notice.isNotEmpty()) return m.notice to true
        val count = m.rowCount()
        val manual = m.kind == IpList.SourceKind.Manual
        if (!m.scanOwned && manual && !m.tooLarge()) {
            return (if (count == 0) getString(R.string.iplist_no_entries) else IpListTexts.entries(this, count)) to false
        }
        if (!manual && m.fetchMatches()) {
            val parts = arrayListOf(quantity(R.plurals.iplist_fetched, m.fetched.size))
            if (m.fetchedRejected > 0) parts.add(IpListTexts.unreadable(this, m.fetchedRejected, m.fetchedSamples))
            return parts.joinToString(" · ") to false
        }
        val sourceChanged = m.isNew || m.kind != m.list.sourceKind || (!manual && m.url.trim() != m.list.source)
        if (!manual && sourceChanged && !m.scanOwned) return getString(R.string.iplist_fetched_on_save) to false
        val parts = arrayListOf(IpListTexts.entries(this, count))
        if (manual && !m.scanOwned) {
            parts.add(getString(R.string.iplist_read_only_large, IpListTexts.number(IpListTexts.MAX_EDITABLE)))
        }
        var error = false
        if (m.list.isRemote && !manual) {
            if (m.list.lastUpdate > 0) parts.add(getString(R.string.iplist_updated_at, IpListTexts.dateTime(this, m.list.lastUpdate)))
            if (m.list.lastError.isNotEmpty()) {
                parts.add(getString(R.string.iplist_last_update_failed_lower, m.list.lastError))
                error = true
            }
        }
        return parts.joinToString(" · ") to error
    }

    private fun metrics(entry: IpListEntry): String {
        val parts = ArrayList<String>(2)
        if (entry.latencyMs > 0) parts.add(getString(R.string.iplist_entry_latency, entry.latencyMs))
        if (entry.lossPct >= 0) parts.add(getString(R.string.iplist_entry_loss, entry.lossPct))
        return parts.joinToString(" · ")
    }

    private fun showEntryMenu(anchor: View, entry: IpListEntry) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(Menu.NONE, MENU_COPY, Menu.NONE, R.string.action_copy)
        if (model.entriesEditable() && !model.saving) popup.menu.add(Menu.NONE, MENU_REMOVE, Menu.NONE, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_COPY -> {
                    val copied = SagerNet.trySetPrimaryClip(IpListParse.formatEntry(entry))
                    snackbar(if (copied) R.string.grp_copied else R.string.action_export_err).show()
                }

                MENU_REMOVE -> lifecycleScope.launch {
                    if (model.ensureWorking()) model.removeEntry(entry)
                }

                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun addEntries(prefill: String) {
        if (addDialog != null || addOpening) return
        addOpening = true
        lifecycleScope.launch {
            try {
                if (model.ensureWorking()) showAddDialog(prefill)
            } finally {
                addOpening = false
            }
        }
    }

    private fun preview(text: String, keys: Set<String>, full: Boolean): AddPreview {
        var lines = 1
        for (c in text) if (c == '\n') lines++
        if (!full && lines > LIVE_PARSE_LINES) return AddPreview(lines, null, emptyList(), 0)
        val parsed = IpListParse.parseIpListText(text)
        val fresh = parsed.entries.filter { key(it) !in keys }
        return AddPreview(lines, parsed, fresh, parsed.entries.size - fresh.size)
    }

    /** The desktop editor's text box and its live summary, for the lines to add. */
    private fun showAddDialog(prefill: String) {
        // A copy: the preview reads it off the main thread.
        val keys = HashSet(model.keys())
        val hint = getString(R.string.iplist_entries_hint)
        val layout = TextInputLayout(this).apply { helperText = hint }
        val input = TextInputEditText(layout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            minLines = 4
            maxLines = 8
            setText(prefill)
        }
        layout.addView(input)
        val container = FrameLayout(this).apply {
            setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
            addView(layout)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.iplist_add_entries)
            .setView(container)
            .setPositiveButton(R.string.iplist_add, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        addDialog = dialog
        model.addText = prefill
        var feedback: Job? = null

        fun showFeedback(result: AddPreview?) {
            val parsed = result?.parsed
            val text = when {
                result == null -> hint
                parsed == null -> quantity(R.plurals.iplist_lines, result.lines)
                else -> {
                    val parts = arrayListOf(IpListTexts.entries(this, result.fresh.size))
                    val duplicates = parsed.duplicates + result.present
                    if (duplicates > 0) parts.add(quantity(R.plurals.iplist_duplicates, duplicates))
                    if (parsed.rejected > 0) parts.add(IpListTexts.unreadable(this, parsed.rejected, parsed.samples))
                    parts.joinToString(" · ")
                }
            }
            if (parsed != null && parsed.rejected > 0) {
                layout.error = text
            } else {
                layout.isErrorEnabled = false
                layout.helperText = text
            }
        }

        fun schedule(text: String, wait: Long) {
            feedback?.cancel()
            if (text.isBlank()) {
                showFeedback(null)
                return
            }
            feedback = lifecycleScope.launch {
                delay(wait)
                showFeedback(withContext(Dispatchers.Default) { preview(text, keys, full = false) })
            }
        }

        input.doAfterTextChanged {
            val text = it?.toString().orEmpty()
            model.addText = text
            schedule(text, 400)
        }
        dialog.setOnDismissListener {
            feedback?.cancel()
            addDialog = null
            model.addText = null
        }
        dialog.show()
        if (prefill.isNotBlank()) schedule(prefill, 0)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { button ->
            val text = input.text?.toString().orEmpty()
            if (text.isBlank()) {
                dialog.dismiss()
                return@setOnClickListener
            }
            button.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.Default) { preview(text, keys, full = true) }
                button.isEnabled = true
                val parsed = result.parsed ?: return@launch
                val commit = {
                    feedback?.cancel()
                    model.addEntries(result.fresh)
                    dialog.dismiss()
                }
                if (parsed.rejected == 0) {
                    commit()
                    return@launch
                }
                var message = quantity(R.plurals.iplist_dropped, parsed.rejected)
                if (parsed.samples.isNotEmpty()) {
                    message += "\n" + getString(R.string.iplist_for_example, parsed.samples.joinToString(", "))
                }
                confirmAction(getString(R.string.iplist_add_entries), message, R.string.iplist_add) { commit() }
            }
        }
        input.requestFocus()
    }

    private fun TextInputLayout.showError(message: String) {
        if (message.isEmpty()) isErrorEnabled = false else error = message
    }

    /** One adapter for the header and the rows, so the fast scroller can measure both. */
    private inner class EditorAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>(),
        FastScrollRecyclerView.MeasurableAdapter<RecyclerView.ViewHolder> {

        private var rowsVersion = -1
        private var shown = 0
        private var headerHeight = dp2px(320)
        private var rowHeight = dp2px(40)

        /** Brings the row count in line with the model; the adapter's count changes only here. */
        fun sync() {
            if (rowsVersion == model.rowsVersion) return
            rowsVersion = model.rowsVersion
            val old = shown
            val new = model.rowCount()
            shown = new
            val common = minOf(old, new)
            if (common > 0) notifyItemRangeChanged(1, common)
            if (new > old) notifyItemRangeInserted(1 + old, new - old)
            else if (old > new) notifyItemRangeRemoved(1 + new, old - new)
        }

        fun pageLoaded(range: IntRange) {
            val last = minOf(range.last, shown - 1)
            if (last >= range.first) notifyItemRangeChanged(1 + range.first, last - range.first + 1)
        }

        override fun getItemCount() = 1 + shown

        override fun getItemViewType(position: Int) = if (position == 0) TYPE_HEADER else TYPE_ENTRY

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == TYPE_HEADER) {
                HeaderHolder(LayoutIpListEditHeaderBinding.inflate(layoutInflater, parent, false))
            } else {
                EntryHolder(LayoutIpListEntryBinding.inflate(layoutInflater, parent, false))
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> {
                    header = holder
                    holder.bind()
                }

                is EntryHolder -> holder.bind(position - 1)
            }
        }

        override fun getViewTypeHeight(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder?, viewType: Int): Int {
            val measured = viewHolder?.itemView?.height ?: 0
            return if (viewType == TYPE_HEADER) {
                if (measured > 0) headerHeight = measured
                headerHeight
            } else {
                if (measured > 0) rowHeight = measured
                rowHeight
            }
        }
    }

    private inner class HeaderHolder(val binding: LayoutIpListEditHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var updating = false
        private val summaryColors: ColorStateList = binding.summary.textColors
        private val errorColor = getColorAttr(R.attr.colorError)

        init {
            binding.nameLayout.isExpandedHintEnabled = false
            binding.urlLayout.placeholderText = "https://example.com/ranges.txt"
            binding.intervalLayout.placeholderText = IpListTexts.DEFAULT_INTERVAL.toString()
            binding.name.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.name = it?.toString().orEmpty()
                model.dirty = true
            }
            binding.url.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.setUrl(it?.toString().orEmpty())
            }
            binding.url.setOnFocusChangeListener { _, focused ->
                if (focused || !model.editable) return@setOnFocusChangeListener
                model.urlError = model.urlProblem()
                bindErrors()
            }
            binding.interval.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.intervalText = it?.toString().orEmpty()
                model.dirty = true
                if (model.intervalError.isNotEmpty()) {
                    model.intervalError = ""
                    bindErrors()
                }
            }
            binding.interval.setOnFocusChangeListener { _, focused ->
                if (focused || !model.editable) return@setOnFocusChangeListener
                model.intervalError = model.intervalProblem()
                bindErrors()
            }
            binding.autoUpdate.setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                model.autoUpdate = checked
                model.dirty = true
                if (!checked) model.intervalError = ""
                model.changed()
            }
            binding.sourceKind.setOnCheckedStateChangeListener { _, ids ->
                if (updating) return@setOnCheckedStateChangeListener
                model.setKind(if (ids.firstOrNull() == R.id.source_url) IpList.SourceKind.Url else IpList.SourceKind.Manual)
            }
            binding.addEntries.setOnClickListener { addEntries("") }
            binding.fetch.setOnClickListener { model.startFetch(thenSave = false) }
        }

        fun bind() {
            val m = model
            val editable = m.editable
            val inputs = editable && !m.fetching && !m.saving
            val remote = m.kind == IpList.SourceKind.Url
            updating = true
            binding.managedNote.isVisible = m.managedNote.isNotEmpty()
            binding.managedNote.text = m.managedNote
            if (binding.name.text?.toString() != m.name) binding.name.setText(m.name)
            binding.name.isEnabled = editable
            binding.nameLayout.placeholderText = if (editable) m.defaultName() else null
            binding.sourceGroup.isVisible = editable
            binding.sourceKind.check(if (remote) R.id.source_url else R.id.source_manual)
            binding.sourceManual.isEnabled = inputs
            binding.sourceUrl.isEnabled = inputs
            binding.remoteGroup.isVisible = remote
            if (binding.url.text?.toString() != m.url) binding.url.setText(m.url)
            binding.url.isEnabled = inputs
            binding.updateGroup.isVisible = remote && editable
            binding.autoUpdate.isChecked = m.autoUpdate
            binding.autoUpdate.isEnabled = editable
            if (binding.interval.text?.toString() != m.intervalText) binding.interval.setText(m.intervalText)
            binding.interval.isEnabled = editable && m.autoUpdate
            updating = false
            bindErrors()
            val (text, error) = summary()
            binding.summary.text = text
            if (error) binding.summary.setTextColor(errorColor) else binding.summary.setTextColor(summaryColors)
            binding.addEntries.isVisible = editable && !remote
            binding.addEntries.isEnabled = m.entriesEditable() && !m.saving && !m.materializing
            binding.fetch.isVisible = editable && remote
            binding.fetch.isEnabled = !m.fetching && !m.saving
            binding.fetch.setText(if (m.fetching) R.string.iplist_fetching else R.string.iplist_fetch_now)
        }

        fun bindErrors() {
            binding.urlLayout.showError(model.urlError)
            binding.intervalLayout.showError(model.intervalError)
        }
    }

    private inner class EntryHolder(val binding: LayoutIpListEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        private var entry: IpListEntry? = null

        init {
            binding.root.setOnClickListener { view -> entry?.let { showEntryMenu(view, it) } }
        }

        fun bind(index: Int) {
            val entry = model.entryAt(index)
            this.entry = entry
            if (entry == null) {
                binding.address.text = "…"
                binding.metrics.isVisible = false
                return
            }
            binding.address.text = IpListParse.formatEntry(entry)
            val metrics = metrics(entry)
            binding.metrics.isVisible = metrics.isNotEmpty()
            binding.metrics.text = metrics
        }
    }
}
