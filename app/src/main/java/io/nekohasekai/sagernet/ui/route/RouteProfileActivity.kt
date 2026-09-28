package io.nekohasekai.sagernet.ui.route

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.databinding.LayoutRouteAddRuleBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteProfileHeaderBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteRawBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteRuleItemBinding
import io.nekohasekai.sagernet.databinding.LayoutRouteRulesTitleBinding
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A route profile: name, default outbound, the remote source of a remote profile, and the ordered rule list (drag
 * to reorder, swipe to delete, tap to edit in [RouteRuleActivity]). Edits stay in memory until Apply; the result
 * carries the saved id as [EXTRA_SAVED_ID].
 */
class RouteProfileActivity : ThemedActivity(R.layout.layout_route_profile) {

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_REMOTE = "remote"

        /** A new, unsaved profile to start from (RouteJson), e.g. an imported rule list that still needs a name. */
        const val EXTRA_PROFILE_JSON = "profile"
        const val EXTRA_SAVED_ID = "savedId"

        private val RULE_NAME = Regex("rule_(\\d{1,6})")

        fun nextRuleName(rules: List<RouteRule>): String {
            val used = rules.mapTo(HashSet()) { it.name }
            var n = rules.mapNotNull { RULE_NAME.matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0
            do {
                n++
            } while ("rule_$n" in used)
            return "rule_$n"
        }
    }

    class EditorModel : ViewModel() {
        var profile: RouteProfile? = null
        var servers: Map<Long, String> = emptyMap()
        var appLabels: Map<String, String> = emptyMap()
        var warnings: List<String> = emptyList()
        var dirty = false
        var fetching = false
        var saving = false
    }

    private val model: EditorModel by viewModels()
    private lateinit var list: RecyclerView
    private val headerAdapter = HeaderAdapter()
    private val titleAdapter = TitleAdapter()
    private val rulesAdapter = RulesAdapter()
    private val footerAdapter = FooterAdapter()
    private val rawAdapter = RawAdapter()
    private lateinit var touchHelper: ItemTouchHelper
    private lateinit var undoManager: UndoSnackbarManager<RouteRule>

    private val ruleEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onRuleResult(it.resultCode, it.data)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_profile_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        list = findViewById(R.id.rule_list)
        list.layoutManager = FixedLinearLayoutManager(list)
        list.adapter = ConcatAdapter(headerAdapter, rawAdapter, titleAdapter, rulesAdapter, footerAdapter)
        list.applyListInsets(ime = true)
        undoManager = UndoSnackbarManager(this, rulesAdapter)
        touchHelper = ItemTouchHelper(TouchCallback()).also { it.attachToRecyclerView(list) }
        onBackPressedDispatcher.addCallback(this) { close() }
        if (model.profile == null) load()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar =
        Snackbar.make(findViewById(R.id.coordinator), text, Snackbar.LENGTH_LONG)

    private fun load() {
        val id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val json = intent.getStringExtra(EXTRA_PROFILE_JSON)
        val remote = intent.getBooleanExtra(EXTRA_REMOTE, false)
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                val p = when {
                    id > 0L -> RouteManager.get(id)
                    json != null -> RouteJson.profileFromJson(json).apply { this.id = 0L }
                    else -> RouteProfile().apply {
                        is_remote = remote
                        // RouteItem.cpp:61-67 seeds an empty profile with the DNS hijack rule.
                        if (!remote) rules.add(RouteRule().apply {
                            name = "dns-hijack"
                            protocol = "dns"
                            action = "hijack-dns"
                        })
                    }
                } ?: return@withContext null
                nameRules(p)
                Triple(p, RouteServers.names(p.rules.map { it.outbound_id }), appLabels(labelledPackages(p.rules)))
            }
            if (loaded == null) {
                finish()
                return@launch
            }
            if (model.profile != null) return@launch
            model.profile = loaded.first
            model.servers = loaded.second
            model.appLabels = loaded.third
            model.dirty = json != null
            refreshAll()
        }
    }

    private fun nameRules(p: RouteProfile) {
        for (rule in p.rules) if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
    }

    private fun appLabels(packages: Collection<String>): Map<String, String> {
        if (packages.isEmpty()) return emptyMap()
        PackageCache.awaitLoadSync()
        return packages.distinct().associateWith { PackageCache.loadLabel(it) }
    }

    /** The packages the rules' summaries name, without the unknown-app entry. */
    private fun labelledPackages(rules: List<RouteRule>): List<String> =
        rules.flatMap { rule -> rule.package_name.filter { it != RouteRule.UNKNOWN_PACKAGE } }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshAll() {
        headerAdapter.notifyDataSetChanged()
        titleAdapter.notifyDataSetChanged()
        rulesAdapter.notifyDataSetChanged()
        footerAdapter.notifyDataSetChanged()
        rawAdapter.notifyDataSetChanged()
        invalidateOptionsMenu()
    }

    private fun markDirty() {
        model.dirty = true
    }

    private fun countChanged() = titleAdapter.notifyItemChanged(0)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.route_profile_menu, menu)
        if (model.profile?.is_raw == true) {
            menu.removeItem(R.id.action_add_rule)
            menu.removeItem(R.id.action_apply)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_add_rule -> addRule()
            R.id.action_apply -> save()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        close()
        return true
    }

    private fun close() {
        if (!model.dirty) {
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

    private fun message(title: Int, text: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** RouteItem::accept (RouteItem.cpp:597-649): name, remote URL, then the empty-rule filter and check. */
    private fun save() {
        val p = model.profile ?: return
        if (p.is_raw) return
        if (model.saving) return
        undoManager.flush()
        p.name = p.name.trim()
        if (p.name.isEmpty()) {
            message(R.string.route_profile_invalid_title, getString(R.string.route_profile_empty_name))
            return
        }
        if (p.is_remote) {
            p.remote_url = p.remote_url.trim()
            if (p.remote_url.isEmpty()) {
                message(R.string.route_profile_invalid_title, getString(R.string.route_profile_need_url))
                return
            }
        }
        val kept = p.rules.filterNot { it.isEmpty() }
        if (!p.is_remote && kept.isEmpty()) {
            message(R.string.route_profile_empty_title, getString(R.string.route_profile_empty))
            return
        }
        val removed = p.rules.size - kept.size
        val toSave = p.copy().apply { rules = kept.toMutableList() }
        model.saving = true
        lifecycleScope.launch {
            try {
                val id = withContext(Dispatchers.IO) { RouteManager.save(toSave) }
                if (removed > 0) {
                    Toast.makeText(
                        this@RouteProfileActivity,
                        resources.getQuantityString(R.plurals.route_profile_empty_rules_removed, removed, removed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                setResult(RESULT_OK, Intent().putExtra(EXTRA_SAVED_ID, id))
                finish()
            } finally {
                model.saving = false
            }
        }
    }

    private fun addRule() {
        val p = model.profile ?: return
        undoManager.flush()
        val rule = RouteRule().apply { name = nextRuleName(p.rules) }
        ruleEditor.launch(Intent(this, RouteRuleActivity::class.java).apply {
            putExtra(RouteRuleActivity.EXTRA_RULE, RouteJson.ruleToJson(rule))
            putExtra(RouteRuleActivity.EXTRA_INDEX, -1)
        })
    }

    private fun editRule(index: Int) {
        val rule = model.profile?.rules?.getOrNull(index) ?: return
        undoManager.flush()
        ruleEditor.launch(Intent(this, RouteRuleActivity::class.java).apply {
            putExtra(RouteRuleActivity.EXTRA_RULE, RouteJson.ruleToJson(rule))
            putExtra(RouteRuleActivity.EXTRA_INDEX, index)
        })
    }

    private fun onRuleResult(code: Int, data: Intent?) {
        val p = model.profile ?: return
        if (data == null) return
        val index = data.getIntExtra(RouteRuleActivity.EXTRA_INDEX, -1)
        when (code) {
            RESULT_OK -> {
                val rule = RouteJson.ruleFromJson(data.getStringExtra(RouteRuleActivity.EXTRA_RULE))
                if (index in p.rules.indices) {
                    if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
                    p.rules[index] = rule
                    rulesAdapter.notifyItemChanged(index)
                } else {
                    if (rule.name.isBlank()) rule.name = nextRuleName(p.rules)
                    p.rules.add(rule)
                    rulesAdapter.notifyItemInserted(p.rules.size - 1)
                    countChanged()
                    list.post { list.smoothScrollToPosition(list.adapter!!.itemCount - 1) }
                }
                markDirty()
                resolveNames(rule)
            }

            RouteRuleActivity.RESULT_DELETE -> if (index in p.rules.indices) {
                val removed = p.rules.removeAt(index)
                rulesAdapter.notifyItemRemoved(index)
                countChanged()
                markDirty()
                undoManager.remove(index to removed)
            }
        }
    }

    /** Loads the server and app names a changed rule shows but the editor does not know yet. */
    private fun resolveNames(rule: RouteRule) {
        val server = rule.outbound_id.takeIf { it > 0 && it !in model.servers }
        val packages = labelledPackages(listOf(rule)).filter { it !in model.appLabels }
        if (server == null && packages.isEmpty()) return
        lifecycleScope.launch {
            val (servers, labels) = withContext(Dispatchers.IO) {
                RouteServers.names(listOfNotNull(server)) to appLabels(packages)
            }
            model.servers = model.servers + servers
            model.appLabels = model.appLabels + labels
            rulesAdapter.notifyItemRangeChanged(0, rulesAdapter.itemCount)
        }
    }

    private fun fetch() {
        val p = model.profile ?: return
        val url = p.remote_url.trim()
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            message(R.string.route_profile_invalid_url_title, getString(R.string.route_profile_invalid_url))
            return
        }
        if (p.isEmpty()) {
            doFetch()
            return
        }
        confirmAction(
            getString(R.string.confirm_replace_rules),
            getString(R.string.route_profile_fetch_confirm),
            R.string.confirm_replace,
        ) { doFetch() }
    }

    /** The editor's "Fetch" (RouteItem.cpp:263-346): the fetched rules replace the edited ones; the typed name stays. */
    private fun doFetch() {
        val p = model.profile ?: return
        undoManager.flush()
        model.fetching = true
        headerAdapter.holder?.bindRemoteState()
        lifecycleScope.launch {
            try {
                val copy = p.copy()
                val warnings = ArrayList<String>()
                val error = withContext(Dispatchers.IO) { RemoteRouteUpdater.fetchInto(copy, warnings) }
                if (error != null) {
                    model.fetching = false
                    headerAdapter.holder?.bindRemoteState()
                    message(R.string.route_profile_fetch_failed, error)
                    return@launch
                }
                p.rules = copy.rules
                p.default_outbound_id = copy.default_outbound_id
                p.remote_last_update = copy.remote_last_update
                if (p.name.isBlank()) p.name = copy.name
                nameRules(p)
                val (servers, labels) = withContext(Dispatchers.IO) {
                    RouteServers.names(p.rules.map { it.outbound_id }) to appLabels(labelledPackages(p.rules))
                }
                model.servers = servers
                model.appLabels = labels
                model.warnings = warnings
                model.fetching = false
                markDirty()
                refreshAll()
                val loaded = getString(R.string.route_profile_fetched, p.rules.size)
                if (warnings.isEmpty()) {
                    message(R.string.route_profile_fetched_title, loaded)
                } else {
                    message(R.string.route_profile_fetched_warnings_title, loaded + "\n\n" + warnings.joinToString("\n"))
                }
            } finally {
                model.fetching = false
            }
        }
    }

    private inner class HeaderAdapter : RecyclerView.Adapter<HeaderHolder>() {
        var holder: HeaderHolder? = null

        override fun getItemCount() = if (model.profile == null) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            HeaderHolder(LayoutRouteProfileHeaderBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: HeaderHolder, position: Int) {
            this.holder = holder
            holder.bind()
        }
    }

    private inner class HeaderHolder(val binding: LayoutRouteProfileHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        private var updating = false

        private val chips = mapOf(
            R.id.outbound_proxy to OutboundIds.PROXY,
            R.id.outbound_direct to OutboundIds.DIRECT,
            R.id.outbound_block to OutboundIds.BLOCK,
            R.id.outbound_warp to OutboundIds.WARP_BYPASS,
        )

        init {
            binding.outboundProxy.text = OutboundIds.toName(OutboundIds.PROXY)
            binding.outboundDirect.text = OutboundIds.toName(OutboundIds.DIRECT)
            binding.outboundBlock.text = OutboundIds.toName(OutboundIds.BLOCK)
            binding.outboundWarp.text = RouteTexts.WARP_BYPASS
            binding.name.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.profile?.name = it?.toString().orEmpty()
                markDirty()
            }
            binding.url.doAfterTextChanged {
                if (updating) return@doAfterTextChanged
                model.profile?.remote_url = it?.toString().orEmpty()
                markDirty()
            }
            binding.autoUpdate.setOnCheckedChangeListener { _, checked ->
                if (updating) return@setOnCheckedChangeListener
                model.profile?.auto_update = checked
                markDirty()
            }
            binding.defaultOutbound.setOnCheckedStateChangeListener { _, ids ->
                if (updating) return@setOnCheckedStateChangeListener
                val outbound = ids.firstOrNull()?.let { chips[it] } ?: return@setOnCheckedStateChangeListener
                model.profile?.default_outbound_id = outbound
                markDirty()
            }
            binding.fetch.setOnClickListener { fetch() }
        }

        fun bind() {
            val p = model.profile ?: return
            updating = true
            if (binding.name.text?.toString() != p.name) binding.name.setText(p.name)
            val chip = chips.entries.firstOrNull { it.value == p.default_outbound_id }?.key ?: R.id.outbound_proxy
            binding.defaultOutbound.check(chip)
            binding.remoteGroup.isVisible = p.is_remote
            if (binding.url.text?.toString() != p.remote_url) binding.url.setText(p.remote_url)
            binding.autoUpdate.isChecked = p.auto_update
            updating = false
            bindRemoteState()
            bindReadOnly(p)
        }

        /** A desktop raw profile is shown, never edited. */
        private fun bindReadOnly(p: RouteProfile) {
            binding.name.isEnabled = !p.is_raw
            for (i in 0 until binding.defaultOutbound.childCount) binding.defaultOutbound.getChildAt(i).isEnabled = !p.is_raw
        }

        fun bindRemoteState() {
            val p = model.profile ?: return
            binding.lastUpdate.text = if (p.remote_last_update > 0) {
                getString(R.string.route_profile_last_update, RouteTexts.dateTime(this@RouteProfileActivity, p.remote_last_update))
            } else {
                getString(R.string.route_profile_never_updated)
            }
            binding.fetch.isEnabled = !model.fetching
            binding.fetch.setText(if (model.fetching) R.string.route_profile_fetching else R.string.route_profile_fetch)
            binding.warnings.isVisible = model.warnings.isNotEmpty()
            binding.warnings.text = model.warnings.joinToString("\n")
        }
    }

    /** The desktop raw route, read-only, or a note on the desktop endpoints Android does not run. */
    private inner class RawAdapter : RecyclerView.Adapter<RawHolder>() {
        override fun getItemCount(): Int {
            val p = model.profile ?: return 0
            return if (p.is_raw || p.endpointCount() > 0) 1 else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RawHolder(LayoutRouteRawBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RawHolder, position: Int) {
            val p = model.profile ?: return
            holder.binding.notice.text = if (p.is_raw) {
                getString(R.string.route_raw_notice)
            } else {
                resources.getQuantityString(R.plurals.route_endpoints_notice, p.endpointCount(), p.endpointCount())
            }
            holder.binding.rawScroll.isVisible = p.is_raw
            holder.binding.rawRoute.text = if (p.is_raw) p.raw_route else ""
        }
    }

    private class RawHolder(val binding: LayoutRouteRawBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TitleAdapter : RecyclerView.Adapter<TitleHolder>() {
        override fun getItemCount() = if (model.profile == null || model.profile?.is_raw == true) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            TitleHolder(LayoutRouteRulesTitleBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: TitleHolder, position: Int) {
            holder.binding.title.text = getString(R.string.route_profile_rules, model.profile?.rules?.size ?: 0)
        }
    }

    private class TitleHolder(val binding: LayoutRouteRulesTitleBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class RulesAdapter : RecyclerView.Adapter<RuleHolder>(), UndoSnackbarManager.Interface<RouteRule> {

        override fun getItemCount() = model.profile?.rules?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RuleHolder(LayoutRouteRuleItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: RuleHolder, position: Int) {
            holder.bind(model.profile!!.rules[position])
        }

        override fun undo(actions: List<Pair<Int, RouteRule>>) {
            val rules = model.profile?.rules ?: return
            for ((index, rule) in actions) {
                val at = index.coerceIn(0, rules.size)
                rules.add(at, rule)
                notifyItemInserted(at)
            }
            countChanged()
            markDirty()
        }

        override fun commit(actions: List<Pair<Int, RouteRule>>) {
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private inner class RuleHolder(val binding: LayoutRouteRuleItemBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) editRule(index)
            }
            binding.dragHandle.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(this)
                false
            }
        }

        fun bind(rule: RouteRule) {
            binding.ruleName.text = rule.name
            binding.ruleAction.text = RouteTexts.ruleAction(this@RouteProfileActivity, rule, model.servers)
            binding.ruleSummary.text = RouteTexts.ruleConditions(this@RouteProfileActivity, rule, model.appLabels)
        }
    }

    private inner class FooterAdapter : RecyclerView.Adapter<FooterHolder>() {
        override fun getItemCount() = if (model.profile == null || model.profile?.is_raw == true) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            FooterHolder(LayoutRouteAddRuleBinding.inflate(layoutInflater, parent, false)).also { holder ->
                holder.binding.root.setOnClickListener { addRule() }
            }

        override fun onBindViewHolder(holder: FooterHolder, position: Int) {
        }
    }

    private class FooterHolder(val binding: LayoutRouteAddRuleBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class TouchCallback : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START or ItemTouchHelper.END
    ) {
        override fun isLongPressDragEnabled() = false

        override fun getDragDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (viewHolder is RuleHolder) super.getDragDirs(recyclerView, viewHolder) else 0

        override fun getSwipeDirs(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            if (viewHolder is RuleHolder) super.getSwipeDirs(recyclerView, viewHolder) else 0

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            if (target !is RuleHolder) return false
            val rules = model.profile?.rules ?: return false
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            rules.add(to, rules.removeAt(from))
            rulesAdapter.notifyItemMoved(from, to)
            markDirty()
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val rules = model.profile?.rules ?: return
            val index = viewHolder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return
            val removed = rules.removeAt(index)
            rulesAdapter.notifyItemRemoved(index)
            countChanged()
            markDirty()
            undoManager.remove(index to removed)
        }
    }
}
