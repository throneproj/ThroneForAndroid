package io.nekohasekai.sagernet.ui.iplist

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutIpListItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.scan.DefaultIpLists
import io.nekohasekai.sagernet.scan.IpList
import io.nekohasekai.sagernet.scan.IpListParse
import io.nekohasekai.sagernet.scan.IpListUpdater
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.ui.SaveDocument
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.scan.ScanClient
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * dialog_ip_lists.cpp: the IP lists with their entry counts, sources and update state, reached from the routing
 * screen. Tap a row to edit it (a scan's list opens read-only), long-press to drag it into another order; its menu
 * refreshes, duplicates, exports or deletes it. New lists come from the editor, files, the clipboard, a URL or the
 * default seeds.
 */
class IpListsActivity : ThemedActivity(R.layout.layout_ip_lists) {

    companion object {
        private const val MAX_IMPORT_BYTES = 50L * 1024 * 1024
        private const val MAX_SHOWN_PROBLEMS = 20
        /** MainWindow::showIpListsDialog(selectListId): the list to scroll to once loaded. */
        const val EXTRA_SELECT_LIST_ID = "selectListId"
        private const val RELOAD_THROTTLE = 500L
        private const val STATE_EXPORT = "exportId"
        private const val STATE_SELECT = "selectId"
        private const val STATE_DUPLICATE_ID = "duplicateId"
        private const val STATE_DUPLICATE_NAME = "duplicateName"
        private const val STATE_DUPLICATE_TEXT = "duplicateText"
        private val UNSAFE_FILE_CHARS = Regex("[\\\\/:*?\"<>|]")

        private fun displayName(resolver: ContentResolver, uri: Uri): String {
            val name = try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            } catch (e: Exception) {
                null
            }
            return name?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty().substringAfterLast('/')
        }

        /** QFileInfo::completeBaseName: up to the last '.'. */
        private fun completeBaseName(fileName: String): String =
            if (fileName.contains('.')) fileName.substring(0, fileName.lastIndexOf('.')) else fileName

        private fun uniqueName(base: String, taken: Set<String>): String {
            if (base !in taken) return base
            var n = 2
            while ("$base ($n)" in taken) n++
            return "$base ($n)"
        }

        /** Null past 50 MB; bounded, since a pipe reports no size and may never end. */
        private fun readLimited(resolver: ContentResolver, uri: Uri): ByteArray? {
            val stream = resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_IMPORT_BYTES) return null
                    out.write(buffer, 0, n)
                }
                return out.toByteArray()
            }
        }

        /** ipListsUtf8Bytes: UTF-16 and UTF-32 text, told by its byte order mark, as UTF-8. */
        private fun utf8Bytes(bytes: ByteArray): ByteArray {
            fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
            val (charset, bom) = when {
                at(0) == 0x00 && at(1) == 0x00 && at(2) == 0xFE && at(3) == 0xFF -> "UTF-32BE" to 4
                at(0) == 0xFF && at(1) == 0xFE && at(2) == 0x00 && at(3) == 0x00 -> "UTF-32LE" to 4
                at(0) == 0xFE && at(1) == 0xFF -> "UTF-16BE" to 2
                at(0) == 0xFF && at(1) == 0xFE -> "UTF-16LE" to 2
                else -> return bytes
            }
            return try {
                String(bytes, bom, bytes.size - bom, Charset.forName(charset)).toByteArray(Charsets.UTF_8)
            } catch (e: Exception) {
                bytes
            }
        }
    }

    class ImportReport(val created: Int, val imported: Long, val lastId: Long, val problems: List<String>)

    class ListsModel : ViewModel() {
        var importing = false
            private set
        val version = MutableStateFlow(0)
        val reports = Channel<ImportReport>(Channel.UNLIMITED)

        /** importFromFiles: each file a new list named after it. */
        fun import(uris: List<Uri>) {
            if (importing || uris.isEmpty()) return
            importing = true
            version.value++
            viewModelScope.launch {
                val report = withContext(Dispatchers.IO) { importFiles(uris) }
                importing = false
                version.value++
                reports.send(report)
            }
        }

        private fun importFiles(uris: List<Uri>): ImportReport {
            val resolver = app.contentResolver
            val taken = IpListsRepo.getAllIpLists().mapTo(HashSet()) { it.name }
            var created = 0
            var imported = 0L
            var lastId = -1L
            val problems = ArrayList<String>()
            for (uri in uris) {
                val fileName = displayName(resolver, uri)
                val raw = try {
                    readLimited(resolver, uri)
                } catch (e: Exception) {
                    Logs.w(e)
                    problems.add(app.getString(R.string.iplist_import_cannot_open, fileName))
                    continue
                }
                if (raw == null) {
                    problems.add(app.getString(R.string.iplist_import_too_large, fileName))
                    continue
                }
                val outcome = IpListUpdater.parseImportBytes(utf8Bytes(raw))
                if (outcome.error.isNotEmpty()) {
                    problems.add("$fileName: ${outcome.error}")
                    continue
                }
                if (outcome.entries.isEmpty()) {
                    problems.add(app.getString(R.string.iplist_import_no_addresses, fileName))
                    continue
                }
                val list = IpList(
                    name = uniqueName(completeBaseName(fileName).ifEmpty { fileName }, taken),
                    entries = outcome.entries,
                    entryCount = outcome.entries.size,
                    entriesLoaded = true,
                )
                if (!IpListsRepo.addIpList(list)) {
                    problems.add(app.getString(R.string.iplist_import_not_stored, fileName))
                    continue
                }
                taken.add(list.name)
                created++
                imported += list.entryCount
                lastId = list.id
                if (outcome.rejected > 0) {
                    var problem = app.resources.getQuantityString(
                        R.plurals.iplist_import_unreadable, outcome.rejected, fileName, IpListTexts.number(outcome.rejected),
                    )
                    if (outcome.samples.isNotEmpty()) problem += " (" + outcome.samples.joinToString(", ") + ")"
                    problems.add(problem)
                }
            }
            return ImportReport(created, imported, lastId, problems)
        }
    }

    private val model: ListsModel by viewModels()
    private lateinit var recycler: RecyclerView
    private lateinit var empty: TextView
    private lateinit var importBar: LinearProgressIndicator
    private val adapter = ListsAdapter()
    private val dragger = ItemTouchHelper(DragCallback())
    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private var pendingSelect = -1L
    private var exportId = -1L

    /** The open "Duplicate" prompt: the list's id and name and the typed text, kept for a recreated screen. */
    private var duplicateDialog: AlertDialog? = null
    private var duplicateOf = IpList()
    private var duplicateText = ""

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val id = result.data?.getLongExtra(IpListEditActivity.EXTRA_LIST_ID, -1L) ?: -1L
        if (result.resultCode != RESULT_OK || id < 0) return@registerForActivityResult
        pendingSelect = id
        reloads.trySend(Unit)
    }

    private val pickFiles = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        model.import(uris)
    }

    private val exportFile = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        if (uri != null) exportTo(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.iplist_title)
            setDisplayHomeAsUpEnabled(true)
        }
        if (savedInstanceState != null) {
            exportId = savedInstanceState.getLong(STATE_EXPORT, -1L)
            pendingSelect = savedInstanceState.getLong(STATE_SELECT, -1L)
            val duplicateId = savedInstanceState.getLong(STATE_DUPLICATE_ID, -1L)
            if (duplicateId >= 0) {
                val list = IpList(id = duplicateId, name = savedInstanceState.getString(STATE_DUPLICATE_NAME).orEmpty())
                duplicate(list, savedInstanceState.getString(STATE_DUPLICATE_TEXT))
            }
        } else {
            pendingSelect = intent.getLongExtra(EXTRA_SELECT_LIST_ID, -1L)
        }
        recycler = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        importBar = findViewById(R.id.importing)
        recycler.layoutManager = FixedLinearLayoutManager(recycler)
        (recycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        recycler.adapter = adapter
        recycler.applyListInsets()
        dragger.attachToRecyclerView(recycler)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { model.version.collect { renderImport() } }
                launch { for (report in model.reports) showReport(report) }
                watch()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_EXPORT, exportId)
        outState.putLong(STATE_SELECT, pendingSelect)
        if (duplicateDialog != null) {
            outState.putLong(STATE_DUPLICATE_ID, duplicateOf.id)
            outState.putString(STATE_DUPLICATE_NAME, duplicateOf.name)
            outState.putString(STATE_DUPLICATE_TEXT, duplicateText)
        }
    }

    override fun onDestroy() {
        duplicateDialog?.dismiss()
        super.onDestroy()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(R.id.coordinator), text, Snackbar.LENGTH_LONG)

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    /** Reloads on writes from either process and on refreshes finishing here, at most twice a second. */
    private suspend fun watch() {
        val observer = withContext(Dispatchers.IO) { IpListsRepo.addObserver { reloads.trySend(Unit) } }
        val listener = IpListUpdater.Listener { _, _ -> reloads.trySend(Unit) }
        IpListUpdater.addListener(listener)
        try {
            reloads.trySend(Unit)
            for (signal in reloads) {
                load()
                delay(RELOAD_THROTTLE)
            }
        } finally {
            IpListUpdater.removeListener(listener)
            withContext(NonCancellable + Dispatchers.IO) { IpListsRepo.removeObserver(observer) }
        }
    }

    private suspend fun load() {
        val (lists, names) = withContext(Dispatchers.IO) {
            val lists = IpListsRepo.getAllIpLists()
            val names = if (lists.any { it.role == IpList.Role.ScanResult }) {
                IpScansRepo.getAllIpScans().associate { it.id to it.name }
            } else {
                emptyMap()
            }
            lists to names
        }
        if (!adapter.submit(lists, names)) return
        empty.isVisible = lists.isEmpty()
        if (pendingSelect >= 0) {
            val index = lists.indexOfFirst { it.id == pendingSelect }
            if (index >= 0) recycler.scrollToPosition(index)
            pendingSelect = -1L
        }
    }

    private fun renderImport() {
        importBar.isVisible = model.importing
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.ip_lists_menu, menu)
        menu.findItem(R.id.action_ip_list_import)?.apply {
            isEnabled = !model.importing
            setTitle(if (model.importing) R.string.iplist_importing else R.string.iplist_import)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_ip_list_new -> editor.launch(IpListEditActivity.newIntent(this))
            R.id.action_ip_list_from_text -> editor.launch(IpListEditActivity.newIntent(this, fromClipboard = true))
            R.id.action_ip_list_from_file -> pickFiles.launch("*/*")
            R.id.action_ip_list_from_url -> editor.launch(IpListEditActivity.newIntent(this, url = true))
            R.id.action_ip_list_defaults -> addDefaults()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun message(title: String, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun quantity(id: Int, n: Int): String = resources.getQuantityString(id, n, IpListTexts.number(n))

    private fun refreshable(list: IpList) = list.isRemote && list.role == IpList.Role.User

    private fun edit(list: IpList) {
        editor.launch(IpListEditActivity.intent(this, list.id))
    }

    private fun refresh(list: IpList) {
        IpListUpdater.refresh(list.id)
        adapter.notifyList(list.id)
    }

    private fun addDefaults() {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { DefaultIpLists.ensureAll() }
            if (added == 0) {
                message(getString(R.string.iplist_title), getString(R.string.iplist_defaults_present))
                return@launch
            }
            IpListUpdater.schedule()
            reloads.trySend(Unit)
        }
    }

    private fun showReport(report: ImportReport) {
        if (report.lastId >= 0) pendingSelect = report.lastId
        reloads.trySend(Unit)
        val title = getString(R.string.iplist_import_title)
        if (report.created == 0) {
            message(title, getString(R.string.iplist_import_nothing) + "\n" + problemsText(report.problems))
            return
        }
        var summary = quantity(R.plurals.iplist_imported_lists, report.created) + " " +
            quantity(R.plurals.iplist_imported_entries, minOf(report.imported, Int.MAX_VALUE.toLong()).toInt())
        if (report.problems.isNotEmpty()) summary += "\n\n" + problemsText(report.problems)
        message(title, summary)
    }

    private fun problemsText(problems: List<String>): String {
        if (problems.size <= MAX_SHOWN_PROBLEMS) return problems.joinToString("\n")
        return problems.take(MAX_SHOWN_PROBLEMS).joinToString("\n") + "\n" +
            quantity(R.plurals.iplist_problems_more, problems.size - MAX_SHOWN_PROBLEMS)
    }

    /** [text]: a recreated prompt's. */
    private fun duplicate(list: IpList, text: String? = null) {
        if (duplicateDialog != null) return
        val suggested = getString(R.string.iplist_copy_name, list.name)
        val title = getString(R.string.iplist_duplicate_title)
        duplicateOf = list
        duplicateText = text ?: suggested
        duplicateDialog = IpListTexts.prompt(
            this, title, getString(R.string.iplist_duplicate_name), duplicateText,
            onText = { duplicateText = it },
        ) { typed ->
            val name = typed.ifEmpty { suggested }
            lifecycleScope.launch {
                val copy = withContext(Dispatchers.IO) { IpListsRepo.copyAsUserList(list.id, name) }
                if (copy == null) {
                    message(title, getString(R.string.iplist_duplicate_failed))
                    return@launch
                }
                pendingSelect = copy.id
                reloads.trySend(Unit)
            }
        }.apply { setOnDismissListener { duplicateDialog = null } }
    }

    private fun export(list: IpList) {
        exportId = list.id
        val fileName = list.name.replace(UNSAFE_FILE_CHARS, "_").ifBlank { "ip-list" }
        exportFile.launch("$fileName.txt")
    }

    private fun openForWrite(uri: Uri): OutputStream {
        // "t" truncates a file picked to be overwritten; not every provider takes it.
        val truncating = try {
            contentResolver.openOutputStream(uri, "wt")
        } catch (e: Exception) {
            null
        }
        return truncating ?: contentResolver.openOutputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }

    private fun exportTo(uri: Uri) {
        val id = exportId
        if (id < 0) return
        lifecycleScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    val entries = IpListsRepo.getEntries(id)
                    val text = StringBuilder(entries.size * 20)
                    for (entry in entries) text.append(IpListParse.formatEntry(entry)).append('\n')
                    openForWrite(uri).use { it.write(text.toString().toByteArray(Charsets.UTF_8)) }
                    entries.size
                } catch (e: Exception) {
                    Logs.w(e)
                    -1
                }
            }
            val where = withContext(Dispatchers.IO) { displayName(contentResolver, uri) }
            if (written < 0) {
                message(getString(R.string.iplist_export_title), getString(R.string.iplist_export_failed, where))
            } else {
                snackbar(resources.getQuantityString(R.plurals.iplist_exported, written, IpListTexts.number(written), where)).show()
            }
        }
    }

    /** deleteList: detached at once, so no scan adopts it, then deleted in the background. */
    private fun delete(list: IpList) {
        val title = getString(R.string.iplist_delete_title)
        if (IpListUpdater.isRefreshing(list.id)) {
            message(title, getString(R.string.iplist_delete_refreshing, list.name))
            return
        }
        lifecycleScope.launch {
            // ScanManager::IsRunning: the engine's own view, as a stored Running may predate a :bg restart.
            val running = if (list.role == IpList.Role.ScanResult) {
                ScanClient.activeRuns().filter { it.running && !it.finder }.mapTo(HashSet()) { it.scanId }
            } else {
                emptySet()
            }
            val scans = withContext(Dispatchers.IO) { IpScansRepo.getAllIpScans() }
            var question = getString(R.string.iplist_delete_question, list.name)
            val targetOf = ArrayList<String>()
            for (scan in scans) {
                if (scan.baseListId == list.id) targetOf.add(scan.name)
                if (list.role != IpList.Role.ScanResult || scan.id != list.relatedTestId) continue
                if (scan.id in running) {
                    message(title, getString(R.string.iplist_delete_scan_running, scan.name))
                    return@launch
                }
                question += "\n\n" + getString(R.string.iplist_delete_scan_result, scan.name)
            }
            if (targetOf.isNotEmpty()) question += "\n\n" + getString(R.string.iplist_delete_scan_targets, targetOf.joinToString(", "))
            confirmAction(title, question, R.string.iplist_delete) {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { IpListsRepo.detachIpList(list.id) }
                    reloads.trySend(Unit)
                    runOnIoDispatcher {
                        IpListsRepo.deleteIpList(list.id, true)
                        IpListUpdater.schedule()
                    }
                }
            }
        }
    }

    private fun showMenu(anchor: View, list: IpList) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.ip_list_item_menu, popup.menu)
        val menu = popup.menu
        if (list.role != IpList.Role.User) menu.findItem(R.id.action_ip_list_edit)?.setTitle(R.string.iplist_view)
        if (!refreshable(list) || IpListUpdater.isRefreshing(list.id)) menu.removeItem(R.id.action_ip_list_refresh)
        if (SagerNet.isTv) {
            // D-pad long presses cannot drag; Move up / Move down go before Delete, as in the group menu.
            val index = adapter.lists.indexOfFirst { it.id == list.id }
            menu.removeItem(R.id.action_ip_list_delete)
            if (index > 0) menu.add(Menu.NONE, R.id.action_move_up, Menu.NONE, R.string.grp_move_up)
            if (index in 0 until adapter.itemCount - 1) menu.add(Menu.NONE, R.id.action_move_down, Menu.NONE, R.string.grp_move_down)
            menu.add(Menu.NONE, R.id.action_ip_list_delete, Menu.NONE, R.string.iplist_delete)
        }
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_ip_list_edit -> edit(list)
                R.id.action_ip_list_refresh -> refresh(list)
                R.id.action_ip_list_duplicate -> duplicate(list)
                R.id.action_ip_list_export -> export(list)
                R.id.action_move_up -> adapter.step(list.id, -1)
                R.id.action_move_down -> adapter.step(list.id, 1)
                R.id.action_ip_list_delete -> delete(list)
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun isLongPressDragEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            adapter.move(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) adapter.dragging = true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            if (!adapter.dragging) return
            adapter.dragging = false
            adapter.commitMove()
        }
    }

    private inner class ListsAdapter : RecyclerView.Adapter<ListHolder>() {
        val lists = ArrayList<IpList>()
        var scanNames: Map<Long, String> = emptyMap()
            private set
        var dragging = false
        private var moved = false
        private var reloadAfterDrag = false

        init {
            setHasStableIds(true)
        }

        override fun getItemId(position: Int) = lists[position].id

        override fun getItemCount() = lists.size

        /** False while a drag holds the rows; the reload follows the drop. */
        @SuppressLint("NotifyDataSetChanged")
        fun submit(fresh: List<IpList>, names: Map<Long, String>): Boolean {
            if (dragging) {
                reloadAfterDrag = true
                return false
            }
            val sameRows = fresh.size == lists.size && fresh.indices.all { fresh[it].id == lists[it].id }
            lists.clear()
            lists.addAll(fresh)
            scanNames = names
            if (sameRows) notifyItemRangeChanged(0, lists.size) else notifyDataSetChanged()
            return true
        }

        fun notifyList(id: Long) {
            val index = lists.indexOfFirst { it.id == id }
            if (index >= 0) notifyItemChanged(index)
        }

        fun move(from: Int, to: Int) {
            if (from !in lists.indices || to !in lists.indices) return
            lists.add(to, lists.removeAt(from))
            moved = true
            notifyItemMoved(from, to)
        }

        /** UpdateIpListsOrder for the order a drag left. */
        fun commitMove() {
            if (moved) {
                moved = false
                val ids = lists.map { it.id }
                runOnIoDispatcher { IpListsRepo.updateIpListsOrder(ids) }
            }
            if (reloadAfterDrag) {
                reloadAfterDrag = false
                reloads.trySend(Unit)
            }
        }

        fun step(id: Long, delta: Int) {
            val from = lists.indexOfFirst { it.id == id }
            val to = from + delta
            if (from < 0 || to !in lists.indices) return
            move(from, to)
            commitMove()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            ListHolder(LayoutIpListItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ListHolder, position: Int) {
            holder.bind(lists[position])
        }
    }

    private inner class ListHolder(val binding: LayoutIpListItemBinding) : RecyclerView.ViewHolder(binding.root) {
        private var list: IpList? = null

        init {
            binding.root.setOnClickListener { list?.let { edit(it) } }
            binding.edit.setOnClickListener { list?.let { edit(it) } }
            binding.refresh.setOnClickListener { list?.let { refresh(it) } }
            binding.options.setOnClickListener { anchor -> list?.let { showMenu(anchor, it) } }
            binding.root.setOnLongClickListener {
                if (!it.isInTouchMode) return@setOnLongClickListener false
                dragger.startDrag(this)
                true
            }
        }

        fun bind(list: IpList) {
            this.list = list
            val context = this@IpListsActivity
            val refreshing = IpListUpdater.isRefreshing(list.id)
            binding.listName.text = list.name
            binding.listSubtitle.text = IpListTexts.subtitle(context, list, adapter.scanNames[list.relatedTestId])
            binding.listStatus.isVisible = refreshing
            binding.listStatus.setText(R.string.iplist_refreshing)
            val error = if (refreshing) "" else IpListTexts.error(context, list)
            binding.listError.isVisible = error.isNotEmpty()
            binding.listError.text = error
            binding.refreshProgress.isVisible = refreshing
            binding.refresh.isVisible = refreshable(list) && !refreshing
            binding.edit.isVisible = list.role == IpList.Role.User
        }
    }
}
