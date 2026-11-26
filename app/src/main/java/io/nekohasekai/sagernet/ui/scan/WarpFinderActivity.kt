package io.nekohasekai.sagernet.ui.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutScanEntryRowBinding
import io.nekohasekai.sagernet.databinding.LayoutWarpFinderBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.scan.EndpointSources
import io.nekohasekai.sagernet.scan.IpListEntry
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.applyInsetPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DialogWarpFinder: searches for WARP endpoints that work on this network, for a stored WARP profile or an editor's
 * unsaved draft, and hands the chosen one back with the AmneziaWG junk or HTTP/2 fallback the search needed. The run
 * lives in :bg; the screen keeps it across re-creation and forgets it once closed for good.
 */
class WarpFinderActivity : ThemedActivity() {

    companion object {
        private const val EXTRA_PROFILE_ID = "profileId"
        private const val EXTRA_DRAFT_TYPE = "draftType"
        private const val EXTRA_DRAFT_JSON = "draftJson"
        private const val EXTRA_DRAFT_GROUP = "draftGroup"
        private const val EXTRA_DRAFT_ENDPOINT = "draftEndpoint"
        private const val EXTRA_CIDR = "cidr"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_LATENCY = "latency"
        private const val EXTRA_LOSS = "loss"
        private const val EXTRA_JUNK = "junk"
        private const val EXTRA_HTTP2 = "http2"
        private const val EXTRA_OWN_ADDRESS = "ownAddress"

        private const val KEY_RUN = "run"
        private const val KEY_ERROR = "error"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_STARTED_AT = "startedAt"
        private const val KEY_SELECTED = "selected"
        private const val KEY_BY_USER = "byUser"

        private fun key(entry: IpListEntry) = entry.cidr + "|" + entry.port

        /** DialogWarpFinder::OverrideOrigin: what an endpoint source makes the profile dial instead; "" for its own address. Blocking. */
        private fun overrideOrigin(entity: ProxyEntity?): String = try {
            if (entity == null || EndpointSources.overrideBlocker(entity).isNotEmpty()) {
                ""
            } else {
                val endpoint = EndpointSources.resolve(EndpointSources.effective(entity))
                if (endpoint.address.isEmpty()) "" else endpoint.origin
            }
        } catch (e: Exception) {
            Logs.w(e)
            ""
        }
    }

    /** What to search for: stored profile [profileId], or an editor's unsaved [draft] of it (id 0 for a new profile). */
    class Request(@JvmField val profileId: Long, @JvmField val draft: ProxyEntity? = null)

    /** DialogWarpFinder::endpointChosen; [ownAddress]: the profile dialed another address, using the endpoint switches it back. */
    class Choice(
        @JvmField val profileId: Long,
        @JvmField val entry: IpListEntry,
        @JvmField val junk: Boolean,
        @JvmField val http2: Boolean,
        @JvmField val ownAddress: Boolean,
    )

    class Contract : ActivityResultContract<Request, Choice?>() {
        override fun createIntent(context: Context, input: Request): Intent {
            val intent = Intent(context, WarpFinderActivity::class.java).putExtra(EXTRA_PROFILE_ID, input.profileId)
            input.draft?.let {
                intent.putExtra(EXTRA_DRAFT_TYPE, it.type)
                    .putExtra(EXTRA_DRAFT_JSON, it.outboundJson)
                    .putExtra(EXTRA_DRAFT_GROUP, it.groupId)
                    .putExtra(EXTRA_DRAFT_ENDPOINT, it.endpointJson)
            }
            return intent
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Choice? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val cidr = intent.getStringExtra(EXTRA_CIDR) ?: return null
            val entry = IpListEntry(
                cidr, intent.getIntExtra(EXTRA_PORT, 0), intent.getIntExtra(EXTRA_LATENCY, 0), intent.getIntExtra(EXTRA_LOSS, -1),
            )
            return Choice(
                intent.getLongExtra(EXTRA_PROFILE_ID, 0L), entry, intent.getBooleanExtra(EXTRA_JUNK, false),
                intent.getBooleanExtra(EXTRA_HTTP2, false), intent.getBooleanExtra(EXTRA_OWN_ADDRESS, false),
            )
        }
    }

    class FinderModel(private val state: SavedStateHandle) : ViewModel() {
        /** The engine's run id (< 0) once started; 0 before. */
        val runId: StateFlow<Long> = state.getStateFlow(KEY_RUN, 0L)
        val startError: StateFlow<String> = state.getStateFlow(KEY_ERROR, "")

        /** Null until computed. */
        val origin: StateFlow<String?> = state.getStateFlow<String?>(KEY_ORIGIN, null)
        val starting = MutableStateFlow(false)

        /** The engine no longer knows the run this screen came back to. */
        val lost = MutableStateFlow(false)

        private var request: Request? = null
        private var generation = 0
        private var cleared = false

        var selectedKey: String?
            get() = state[KEY_SELECTED]
            set(value) {
                state[KEY_SELECTED] = value
            }

        /** Until the user picks a row, the selection follows the best endpoint. */
        var selectionByUser: Boolean
            get() = state[KEY_BY_USER] ?: false
            set(value) {
                state[KEY_BY_USER] = value
            }

        val startedAt: Long get() = state[KEY_STARTED_AT] ?: SystemClock.elapsedRealtime()

        fun attach(request: Request) {
            if (this.request != null) return
            this.request = request
            if (origin.value == null) viewModelScope.launch {
                state[KEY_ORIGIN] = withContext(Dispatchers.IO) {
                    overrideOrigin(request.draft ?: ProfileManager.getProfile(request.profileId))
                }
            }
            val id = runId.value
            if (id < 0) {
                viewModelScope.launch { if (ScanClient.state(id) == null) lost.value = true }
            } else if (startError.value.isEmpty()) {
                start()
            }
        }

        fun start() {
            val request = request ?: return
            forget()
            state[KEY_ERROR] = ""
            lost.value = false
            selectionByUser = false
            selectedKey = null
            state[KEY_STARTED_AT] = SystemClock.elapsedRealtime()
            starting.value = true
            val current = ++generation
            // Not in viewModelScope: a run started after the screen closed must still be forgotten.
            runOnMainDispatcher {
                val result = if (request.draft != null) {
                    ScanClient.startWarpFinder(request.draft)
                } else {
                    ScanClient.startWarpFinder(request.profileId)
                }
                if (cleared || current != generation) {
                    if (result.id < 0) ScanClient.forgetWarpFinder(result.id)
                    return@runOnMainDispatcher
                }
                starting.value = false
                if (result.id < 0) {
                    state[KEY_RUN] = result.id
                } else {
                    state[KEY_ERROR] = result.error.ifEmpty { app.getString(R.string.scan_finder_start_failed) }
                }
            }
        }

        fun stop() {
            val id = runId.value
            if (id < 0) ScanClient.stop(id)
        }

        fun forget() {
            val id = runId.value
            if (id >= 0) return
            state[KEY_RUN] = 0L
            ScanClient.forgetWarpFinder(id)
        }

        // A screen destroyed without finishing (the system reclaiming it) comes back to its run, so only a start
        // still on its way is dropped here; a finishing screen forgets its run in onDestroy.
        override fun onCleared() {
            cleared = true
        }
    }

    private val model: FinderModel by viewModels()
    private lateinit var binding: LayoutWarpFinderBinding
    private lateinit var request: Request
    private val adapter = HitAdapter()
    private var live: ScanClient.RunState? = null
    private lateinit var statusColors: ColorStateList

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ScannerUi.launchDone()
        binding = LayoutWarpFinderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.root.findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.scan_finder_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        binding.body.applyInsetPadding(horizontal = true)
        binding.buttons.applyInsetPadding(bottom = true, horizontal = true)
        statusColors = binding.status.textColors
        ViewCompat.setTooltipText(binding.lossColumn, getString(R.string.scan_column_loss_tip))
        binding.hits.layoutManager = LinearLayoutManager(this)
        binding.hits.adapter = adapter
        binding.run.setOnClickListener { runClicked() }
        binding.use.setOnClickListener { useSelected() }

        request = requestOf(intent)
        model.attach(request)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    model.runId.flatMapLatest { id -> if (id < 0) ScanClient.observe(id) else flowOf(null) }.collect {
                        live = it
                        render()
                    }
                }
                launch {
                    combine(model.startError, model.starting, model.lost, model.origin) { _, _, _, _ -> }.collect { render() }
                }
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        if (isFinishing) model.forget()
        super.onDestroy()
    }

    private fun requestOf(intent: Intent): Request {
        val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        val type = intent.getStringExtra(EXTRA_DRAFT_TYPE) ?: return Request(profileId)
        val draft = ProxyEntity(
            id = profileId,
            type = type,
            groupId = intent.getLongExtra(EXTRA_DRAFT_GROUP, 0L),
            outboundJson = intent.getStringExtra(EXTRA_DRAFT_JSON).orEmpty(),
            endpointJson = intent.getStringExtra(EXTRA_DRAFT_ENDPOINT) ?: "{}",
        )
        return Request(profileId, draft)
    }

    // DialogWarpFinder::refresh
    private fun render() {
        val started = model.runId.value < 0
        val state = if (started) live else null
        val lost = model.lost.value
        val starting = model.starting.value
        // A run whose first state is still on its way counts as running.
        val running = starting || (started && !lost && (state == null || state.running))
        val stopping = state != null && state.running && state.stopping
        val hits = state?.hits.orEmpty()
        val found = hits.size

        adapter.show(hits)

        var color = 0
        val status = when {
            !started && !starting -> {
                color = getColorAttr(R.attr.colorError)
                model.startError.value
            }

            running -> if (stopping) {
                getString(R.string.scan_finder_stopping)
            } else {
                state?.stage?.takeIf { it.isNotEmpty() } ?: getString(R.string.scan_stage_preparing)
            }

            found > 0 -> {
                val seconds = if (state != null && state.total > 0) {
                    minOf(state.tested, state.total)
                } else {
                    (SystemClock.elapsedRealtime() - model.startedAt) / 1000
                }
                color = ContextCompat.getColor(this, R.color.material_green_500)
                resources.getQuantityString(R.plurals.scan_finder_found_in, found, found, seconds)
            }

            lost -> {
                color = getColorAttr(R.attr.colorError)
                getString(R.string.scan_error_service_died)
            }

            state != null && state.error.isNotEmpty() -> {
                color = getColorAttr(R.attr.colorError)
                state.error
            }

            else -> state?.log?.lastOrNull() ?: getString(R.string.scan_finder_none_found)
        }
        binding.status.text = status
        if (color != 0) binding.status.setTextColor(color) else binding.status.setTextColor(statusColors)

        val detail = if (running && !stopping) state?.log?.lastOrNull().orEmpty() else ""
        binding.detail.text = detail
        binding.detail.isVisible = detail.isNotEmpty() && detail != status

        binding.progressRow.isVisible = running
        if (running) {
            val total = state?.total ?: 0L
            if (total <= 0L) {
                binding.progress.isIndeterminate = true
                binding.progressText.text = ""
            } else {
                val tested = minOf(state!!.tested, total)
                binding.progress.isIndeterminate = false
                binding.progress.setProgressCompat(ScannerUi.permille(tested, total), false)
                binding.progressText.text = getString(R.string.scan_progress_seconds, tested, total)
            }
            binding.found.text = resources.getQuantityString(R.plurals.scan_finder_found, found, found)
        }

        val notices = ArrayList<String>()
        if (state?.warpJunk == true) notices.add(getString(R.string.scan_finder_junk_notice))
        if (state?.warpHttp2 == true) notices.add(getString(R.string.scan_finder_http2_notice))
        val origin = model.origin.value.orEmpty()
        if (origin.isNotEmpty()) notices.add(getString(R.string.scan_finder_origin_notice, origin))
        binding.notices.text = notices.joinToString("\n")
        binding.notices.isVisible = notices.isNotEmpty()

        binding.placeholder.isVisible = running && found == 0
        binding.run.setText(if (running) R.string.stop else R.string.scan_finder_search_again)
        binding.run.isEnabled = !starting && (!running || !stopping)
        binding.use.isEnabled = adapter.selected() != null
    }

    // DialogWarpFinder::runClicked
    private fun runClicked() {
        val state = live
        val started = model.runId.value < 0
        if (started && !model.lost.value && (state == null || state.running)) {
            model.stop()
            return
        }
        model.start()
    }

    // DialogWarpFinder::useSelected
    private fun useSelected() {
        val entry = adapter.selected() ?: return
        val state = live
        val choice = Choice(
            request.profileId, entry, state?.warpJunk == true, state?.warpHttp2 == true, !model.origin.value.isNullOrEmpty(),
        )
        model.forget()
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_PROFILE_ID, choice.profileId)
                .putExtra(EXTRA_CIDR, entry.cidr)
                .putExtra(EXTRA_PORT, entry.port)
                .putExtra(EXTRA_LATENCY, entry.latencyMs)
                .putExtra(EXTRA_LOSS, entry.lossPct)
                .putExtra(EXTRA_JUNK, choice.junk)
                .putExtra(EXTRA_HTTP2, choice.http2)
                .putExtra(EXTRA_OWN_ADDRESS, choice.ownAddress),
        )
        finish()
    }

    private class HitHolder(val binding: LayoutScanEntryRowBinding) : RecyclerView.ViewHolder(binding.root)

    private inner class HitAdapter : RecyclerView.Adapter<HitHolder>() {
        private var hits: List<IpListEntry> = emptyList()
        private var selected = -1

        fun selected(): IpListEntry? = hits.getOrNull(selected)

        // DialogWarpFinder::showHits
        fun show(next: List<IpListEntry>) {
            if (next == hits) return
            val keep = if (model.selectionByUser) model.selectedKey else null
            hits = next
            selected = if (keep != null) next.indexOfFirst { key(it) == keep } else -1
            if (selected < 0) {
                model.selectionByUser = false
                selected = if (next.isEmpty()) -1 else 0
            }
            model.selectedKey = selected()?.let { key(it) }
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemCount() = hits.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HitHolder {
            val holder = HitHolder(LayoutScanEntryRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            holder.binding.port.isVisible = false
            holder.itemView.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION || position == selected) return@setOnClickListener
                val previous = selected
                selected = position
                model.selectionByUser = true
                model.selectedKey = selected()?.let { key(it) }
                if (previous >= 0) notifyItemChanged(previous)
                notifyItemChanged(position)
                this@WarpFinderActivity.binding.use.isEnabled = true
            }
            return holder
        }

        override fun onBindViewHolder(holder: HitHolder, position: Int) {
            val hit = hits[position]
            val context = holder.itemView.context
            val unknown = "—"
            holder.binding.address.text = if (hit.port > 0) Hosts.displayAddress(hit.cidr, hit.port) else hit.cidr
            holder.binding.first.text = if (hit.latencyMs > 0) context.getString(R.string.scan_value_ms, hit.latencyMs) else unknown
            holder.binding.second.text = if (hit.lossPct >= 0) context.getString(R.string.scan_value_percent, hit.lossPct) else unknown
            holder.itemView.setBackgroundColor(if (position == selected) highlight(context) else 0)
        }
    }

    private var highlightColor = 0

    private fun highlight(context: Context): Int {
        if (highlightColor == 0) {
            val accent = context.getColorAttr(R.attr.colorAccent)
            highlightColor = ColorUtils.compositeColors(ColorUtils.setAlphaComponent(accent, 48), context.getColorAttr(R.attr.colorSurface))
        }
        return highlightColor
    }
}
