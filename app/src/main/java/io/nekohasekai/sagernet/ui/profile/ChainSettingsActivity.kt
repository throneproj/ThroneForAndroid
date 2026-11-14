package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.IChainStepCallback
import io.nekohasekai.sagernet.aidl.ICoreService
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.bg.test.ChainStepReport
import io.nekohasekai.sagernet.bg.test.ChainStepResult
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAddEntityBinding
import io.nekohasekai.sagernet.databinding.LayoutProfileBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.types.Chain
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.ui.FormIssue
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.test.TestFormat
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.widget.applyInsetPadding
import io.nekohasekai.sagernet.widget.applyListInsets
import moe.matsuri.nb4a.Protocols.getProtocolColor
import moe.matsuri.nb4a.proxy.PreferenceBindingManager

class ChainSettingsActivity : ProfileSettingsActivity<Chain>(R.layout.layout_chain_settings) {

    override fun createEntity() = Chain()
    override val supportsRawJson = false

    private val pbm = PreferenceBindingManager().apply { text("name") }

    private companion object {
        const val KEY_CHAIN_IDS = "chainProxyIds"

        /** The row above the hop list that carries the list's problems (name_preferences.xml). */
        const val KEY_HOPS = "chainHops"

        /** Rebinds a row in place for its hop test result. */
        val STEP_PAYLOAD = Any()

        const val DANGER_FALLBACK = 0xFFE53935.toInt()
    }

    val proxyList = ArrayList<ProxyEntity>()

    /** Stored hops left out on load because their profile no longer exists. */
    private var droppedHops = 0
    private var hopsLoaded = false

    /** The last hop test's verdict per row (edit_chain.cpp showStepResult); null when there is none. */
    private var stepRows: List<StepRow>? = null
    private var stepTest: ChainStepClient? = null
    private lateinit var stepStatus: TextView

    override fun issueKey(field: String): String? = if (field == "list") KEY_HOPS else null

    /** edit_chain.cpp bindValidation. */
    override fun ValidationForm.bindValidation() {
        addIssueSource {
            if (!hopsLoaded) return@addIssueSource emptyList()
            val label = getString(R.string.validation_chain_hops)
            val issues = ArrayList<FormIssue>()
            hopsError().takeIf { it.isNotEmpty() }?.let { issues.add(FormIssue(Severity.Error, label, it, KEY_HOPS)) }
            if (droppedHops > 0) {
                issues.add(FormIssue(Severity.Warning, label, vplural(R.plurals.validation_chain_dropped, droppedHops), KEY_HOPS))
            }
            issues
        }
    }

    /** Adding, removing, replacing and dragging hops; a test result no longer describes the list. */
    private fun hopsChanged() {
        form?.revalidate(KEY_HOPS)
        if (stepTest == null && stepStatus.isVisible) clearStepResults()
    }

    private fun refreshForm() {
        form?.refresh()
    }

    private fun storedHopIds(): List<Long> = (DataStore.profileCacheStore.getString(KEY_CHAIN_IDS) ?: "").split(",")
        .mapNotNull { it.takeIf { it.isNotBlank() }?.toLongOrNull() }

    /** Until the hops are loaded (off the main thread) the stored ids stand in for them, so a chain does not flash "empty". */
    override fun validationOutbound(): Chain {
        if (hopsLoaded) return super.validationOutbound()
        val copy = createEntity()
        copy.parseFromJson(ensureEditingOutbound().exportToJson())
        pbm.fromCacheAll(copy)
        copy.list = ArrayList(storedHopIds())
        return copy
    }

    private fun hopsError(): String {
        if (proxyList.isEmpty()) return getString(R.string.validation_chain_empty)
        // The picker only refuses circular references; the chain builder refuses this too.
        for ((i, hop) in proxyList.withIndex()) {
            if (hop.type == "chain") return getString(R.string.validation_chain_hop_chain, i + 1)
        }
        return scanError(proxyList.map { it.outbound })
    }

    /** edit_chain.cpp editChainScanError: the hops above an auto selector and the ones below it are built as two separate chains. */
    private fun scanError(topFirst: List<Outbound>): String {
        var selectorAt = -1
        for ((i, hop) in topFirst.withIndex()) {
            if (hop.type != "autoselector") continue
            if (selectorAt >= 0) return getString(R.string.validation_chain_selector_twice)
            selectorAt = i
        }
        if (selectorAt < 0) return segmentError(topFirst)
        if (topFirst.any { it.isXrayFullConfig() || (it as? Custom)?.isFullConfig() == true }) {
            return getString(R.string.validation_chain_selector_custom)
        }
        // The selector stays in the lower part: an Xray hop below it switches back to sing-box to reach it.
        return segmentError(topFirst.subList(selectorAt, topFirst.size)).ifEmpty { segmentError(topFirst.subList(0, selectorAt)) }
    }

    /**
     * edit_chain.cpp editChainSegmentError: generate.cpp's chain scan over the build order, which reads the list
     * bottom-up. The extra-core rules are left out, Android has no extra cores.
     */
    private fun segmentError(topFirst: List<Outbound>): String {
        val count = topFirst.size
        var xrayFullConfigs = 0
        var xrayFullConfigIndex = -1
        var xrayHops = 0
        var transitions = 0
        var customFullConfig = false
        var inXray = false
        for (k in 0 until count) {
            val hop = topFirst[count - 1 - k]
            val xray = hop.isXray()
            if (xray != inXray) transitions++
            inXray = xray
            if (hop.isXrayFullConfig()) {
                xrayFullConfigs++
                xrayFullConfigIndex = k
            }
            if (xray) xrayHops++
            if ((hop as? Custom)?.isFullConfig() == true) customFullConfig = true
        }
        if (customFullConfig) return getString(R.string.validation_chain_custom_full_config)
        if (xrayFullConfigs > 1) return getString(R.string.validation_chain_xray_full_configs)
        if (xrayFullConfigs > 0 && xrayHops > 0) return getString(R.string.validation_chain_xray_full_config_mixed)
        // A custom Xray config is reached through its local socks port, so nothing can come after it.
        if (xrayFullConfigs == 1 && count > 1 && xrayFullConfigIndex != count - 1) {
            return getString(R.string.validation_chain_xray_full_config_last)
        }
        if (transitions > 2) return getString(R.string.validation_chain_transitions)
        return ""
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.chain_settings_menu, menu)
        menu.findItem(R.id.action_test_hops)?.isEnabled = stepTest == null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_test_hops) {
            testHops()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        stepTest?.cancel()
        stepTest = null
        super.onDestroy()
    }

    /** edit_chain.cpp on_test_hops_clicked: tests the hops as listed, without saving anything. */
    private fun testHops() {
        if (stepTest != null || !hopsLoaded) return
        val error = hopsError()
        if (error.isNotEmpty()) {
            clearStepResults()
            setStepStatus(error, StepRole.DANGER)
            return
        }
        val hops = proxyList.map { it.id }
        val names = proxyList.map { it.displayName() }
        val testing = StepRow(getString(R.string.connection_test_testing), "", StepRole.MUTED)
        showStepRows(List(hops.size) { testing })
        setStepStatus(getString(R.string.chain_step_testing), StepRole.MUTED)
        val client = ChainStepClient(hops.toLongArray(), DataStore.editingGroup) { onStepOutcome(hops, names, it) }
        stepTest = client
        invalidateOptionsMenu()
        client.start()
    }

    private fun onStepOutcome(hops: List<Long>, names: List<String>, outcome: StepOutcome) {
        stepTest = null
        invalidateOptionsMenu()
        if (outcome !is StepOutcome.Done) {
            clearStepResults()
            val message = if (outcome is StepOutcome.Busy) R.string.chain_step_busy else R.string.test_panel_failure_unreachable
            setStepStatus(getString(message), StepRole.DANGER)
            return
        }
        if (proxyList.map { it.id } != hops) {
            clearStepResults()
            setStepStatus(getString(R.string.chain_step_hops_changed), StepRole.MUTED)
            return
        }
        val verdict = judgeSteps(outcome.report, names)
        showStepRows(verdict.rows)
        setStepStatus(verdict.status, verdict.statusRole)
    }

    private fun showStepRows(rows: List<StepRow>?) {
        stepRows = rows
        configurationAdapter.notifyItemRangeChanged(1, proxyList.size, STEP_PAYLOAD)
    }

    private fun clearStepResults() {
        showStepRows(null)
        stepStatus.isVisible = false
    }

    private fun setStepStatus(text: String, role: StepRole) {
        stepStatus.text = text
        stepStatus.setTextColor(roleColor(role))
        stepStatus.isVisible = true
    }

    @ColorInt
    private fun roleColor(role: StepRole): Int = when (role) {
        StepRole.SUCCESS -> TestFormat.latencyColor(1)
        StepRole.DANGER -> TestFormat.themeColor(this, R.attr.colorError, DANGER_FALLBACK)
        StepRole.MUTED -> getColorAttr(android.R.attr.textColorSecondary)
    }

    private fun stepLatencyText(result: ChainStepResult): String =
        if (result.latency == ProxyEntity.LATENCY_CONNECT_ONLY) getString(R.string.test_connect_ok)
        else getString(R.string.test_panel_ms, result.latency)

    private fun aloneNote(result: ChainStepResult?): String = when {
        result == null -> ""
        ProxyEntity.isWorking(result.latency) -> getString(R.string.chain_step_alone, stepLatencyText(result))
        else -> getString(R.string.chain_step_alone_unavailable)
    }

    private fun joinLines(vararg lines: String): String = lines.filter { it.isNotEmpty() }.joinToString("\n")

    /** edit_chain.cpp judgeSteps: a row per hop and the status line; [names] is the hop list the test ran on. */
    private fun judgeSteps(report: ChainStepReport, names: List<String>): StepVerdict {
        val through = HashMap<Int, ChainStepResult>()
        val alone = HashMap<Int, ChainStepResult>()
        for (result in report.results) (if (result.alone) alone else through)[result.hop] = result
        val invalid = report.invalidHops.toSet()

        val rows = ArrayList<StepRow>()
        var breakAt = -1
        for (i in names.indices) {
            val throughResult = through[i]
            val aloneResult = alone[i]
            rows += when {
                i in invalid -> {
                    if (breakAt < 0) breakAt = i
                    StepRow(getString(R.string.chain_step_invalid), getString(R.string.chain_step_invalid_sum), StepRole.DANGER)
                }

                throughResult != null -> when {
                    ProxyEntity.isWorking(throughResult.latency) -> StepRow(
                        stepLatencyText(throughResult),
                        getString(if (i == 0) R.string.chain_step_works else R.string.chain_step_works_through),
                        StepRole.SUCCESS,
                    )

                    breakAt >= 0 -> StepRow(
                        getString(R.string.chain_step_not_reached),
                        joinLines(getString(R.string.chain_step_not_reached_sum), aloneNote(aloneResult)),
                        StepRole.MUTED,
                    )

                    else -> {
                        breakAt = i
                        if (aloneResult != null && ProxyEntity.isWorking(aloneResult.latency)) {
                            StepRow(
                                getString(R.string.chain_step_fails_through),
                                joinLines(
                                    getString(R.string.chain_step_fails_through_sum, stepLatencyText(aloneResult)),
                                    throughResult.error,
                                ),
                                StepRole.DANGER,
                            )
                        } else {
                            StepRow(getString(R.string.unavailable), joinLines(aloneNote(aloneResult), throughResult.error), StepRole.DANGER)
                        }
                    }
                }

                aloneResult != null -> StepRow(
                    if (ProxyEntity.isWorking(aloneResult.latency)) stepLatencyText(aloneResult) else getString(R.string.unavailable),
                    joinLines(getString(R.string.chain_step_alone_only_sum), aloneResult.error),
                    StepRole.MUTED,
                )

                else -> StepRow(getString(R.string.chain_step_not_tested), "", StepRole.MUTED)
            }
        }

        val landing = names.size
        val throughLanding = through[landing]
        val aloneLanding = alone[landing]
        val (status, role) = when {
            report.error.isNotEmpty() -> report.error to StepRole.DANGER
            report.aborted -> getString(R.string.chain_step_stopped) to StepRole.MUTED
            breakAt >= 0 -> getString(R.string.chain_step_breaks_at, breakAt + 1, names[breakAt]) to StepRole.DANGER
            !report.hasLanding -> getString(R.string.chain_step_all_work) to StepRole.SUCCESS
            throughLanding != null && ProxyEntity.isWorking(throughLanding.latency) ->
                getString(R.string.chain_step_landing_works, stepLatencyText(throughLanding)) to StepRole.SUCCESS

            aloneLanding != null && ProxyEntity.isWorking(aloneLanding.latency) ->
                getString(R.string.chain_step_landing_fails_through) to StepRole.DANGER

            else -> getString(R.string.chain_step_landing_unavailable) to StepRole.DANGER
        }
        return StepVerdict(rows, status, role)
    }

    override fun Chain.init() {
        pbm.writeToCacheAll(this)
        DataStore.profileCacheStore.putString(KEY_CHAIN_IDS, list.joinToString(","))
    }

    override fun Chain.serialize() {
        pbm.fromCacheAll(this)
        list = ArrayList(proxyList.map { it.id })
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.name_preferences)
        pbm.setPreferenceFragment(this)
    }

    lateinit var configurationList: RecyclerView
    lateinit var configurationAdapter: ProxiesAdapter
    lateinit var layoutManager: LinearLayoutManager

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar!!.setTitle(R.string.chain_settings)
        stepStatus = findViewById(R.id.step_status)
        stepStatus.applyInsetPadding(horizontal = true)
        configurationList = findViewById(R.id.configuration_list)
        configurationList.applyListInsets()
        layoutManager = FixedLinearLayoutManager(configurationList)
        configurationList.layoutManager = layoutManager
        configurationAdapter = ProxiesAdapter()
        configurationList.adapter = configurationAdapter

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.START
        ) {
            override fun getSwipeDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getSwipeDirs(recyclerView, viewHolder)
            } else 0

            override fun getDragDirs(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) = if (viewHolder is ProfileHolder) {
                super.getDragDirs(recyclerView, viewHolder)
            } else 0

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                return if (target !is ProfileHolder) false else {
                    configurationAdapter.move(
                        viewHolder.bindingAdapterPosition, target.bindingAdapterPosition
                    )
                    true
                }
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                configurationAdapter.remove(viewHolder.bindingAdapterPosition)
            }

        }).attachToRecyclerView(configurationList)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // the profile list below takes the bottom inset
        listView.applyInsetPadding(horizontal = true)
        view.rootView.findViewById<RecyclerView>(R.id.recycler_view).apply {
            (layoutParams ?: LinearLayout.LayoutParams(-1, -2)).apply {
                height = -2
                layoutParams = this
            }
        }

        runOnDefaultDispatcher {
            configurationAdapter.reload()
        }
    }

    inner class ProxiesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        suspend fun reload() {
            val idList = storedHopIds()
            var dropped = 0
            if (idList.isNotEmpty()) {
                val profiles = ProfileManager.getProfiles(idList).map { it.id to it }.toMap()
                for (id in idList) {
                    val profile = profiles[id]
                    if (profile == null) dropped++ else proxyList.add(profile)
                }
            }
            onMainDispatcher {
                droppedHops = dropped
                hopsLoaded = true
                notifyDataSetChanged()
                refreshForm()
            }
        }

        fun move(from: Int, to: Int) {
            val toMove = proxyList[to - 1]
            proxyList[to - 1] = proxyList[from - 1]
            proxyList[from - 1] = toMove
            notifyItemMoved(from, to)
            DataStore.dirty = true
            hopsChanged()
        }

        fun remove(index: Int) {
            proxyList.removeAt(index - 1)
            notifyItemRemoved(index)
            DataStore.dirty = true
            hopsChanged()
        }

        override fun getItemId(position: Int): Long {
            return if (position == 0) 0 else proxyList[position - 1].id
        }

        override fun getItemViewType(position: Int): Int {
            return if (position == 0) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == 0) {
                AddHolder(LayoutAddEntityBinding.inflate(layoutInflater, parent, false))
            } else {
                ProfileHolder(LayoutProfileBinding.inflate(layoutInflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is AddHolder) {
                holder.bind()
            } else if (holder is ProfileHolder) {
                holder.bind(proxyList[position - 1], position - 1)
            }
        }

        override fun getItemCount(): Int {
            return proxyList.size + 1
        }

    }

    fun testProfileAllowed(profile: ProxyEntity): Boolean {
        if (profile.id == DataStore.editingId) return false

        for (entity in proxyList) {
            if (testProfileContains(entity, profile)) return false
        }

        return true
    }

    fun testProfileContains(profile: ProxyEntity, anotherProfile: ProxyEntity): Boolean {
        if (!profile.isChain() || !anotherProfile.isChain()) return false
        if (profile.id == anotherProfile.id) return true
        val proxies = profile.requireChain().list
        if (proxies.contains(anotherProfile.id)) return true
        if (proxies.isNotEmpty()) {
            for (entity in ProfileManager.getProfiles(proxies)) {
                if (testProfileContains(entity, anotherProfile)) {
                    return true
                }
            }
        }
        return false
    }

    var replacing = 0

    val selectProfileForAdd =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { (resultCode, data) ->
            if (resultCode == Activity.RESULT_OK) runOnDefaultDispatcher {
                DataStore.dirty = true

                val profile = ProfileManager.getProfile(
                    data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID, 0
                    )
                )!!

                if (!testProfileAllowed(profile)) {
                    onMainDispatcher {
                        MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(R.string.circular_reference)
                            .setMessage(R.string.circular_reference_sum)
                            .setPositiveButton(android.R.string.ok, null).show()
                    }
                } else {
                    configurationList.post {
                        if (replacing != 0) {
                            proxyList[replacing - 1] = profile
                            configurationAdapter.notifyItemChanged(replacing)
                        } else {
                            proxyList.add(profile)
                            configurationAdapter.notifyItemInserted(proxyList.size)
                        }
                        hopsChanged()
                    }
                }
            }
        }

    inner class AddHolder(val binding: LayoutAddEntityBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind() {
            binding.root.setOnClickListener {
                replacing = 0
                selectProfileForAdd.launch(
                    Intent(this@ChainSettingsActivity, ProfileSelectActivity::class.java)
                        .putExtra(ProfileSelectActivity.EXTRA_ALLOW_AUTO_SELECTORS, true)
                )
            }
        }
    }

    inner class ProfileHolder(binding: LayoutProfileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        val profileName = binding.profileName
        val profileType = binding.profileType
        val trafficText: TextView = binding.trafficText
        val editButton = binding.edit
        val shareLayout = binding.share
        val stepResult: TextView = binding.profileResult
        val stepExplanation: TextView = binding.profileError

        fun bind(proxyEntity: ProxyEntity, index: Int) {

            profileName.text = proxyEntity.displayName()
            profileType.text = proxyEntity.displayType()
            profileType.setTextColor(getProtocolColor(proxyEntity.type))

            val rx = proxyEntity.rx
            val tx = proxyEntity.tx

            val showTraffic = rx + tx != 0L
            trafficText.isVisible = showTraffic
            if (showTraffic) {
                trafficText.text = itemView.context.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(itemView.context, tx),
                    Formatter.formatFileSize(itemView.context, rx)
                )
            }

            editButton.setOnClickListener {
                replacing = bindingAdapterPosition
                selectProfileForAdd.launch(Intent(
                    this@ChainSettingsActivity, ProfileSelectActivity::class.java
                ).apply {
                    putExtra(ProfileSelectActivity.EXTRA_SELECTED, proxyEntity)
                    putExtra(ProfileSelectActivity.EXTRA_ALLOW_AUTO_SELECTORS, true)
                })
            }

            shareLayout.isVisible = false

            val step = stepRows?.getOrNull(index)
            stepResult.isVisible = step != null
            stepExplanation.isVisible = !step?.explanation.isNullOrEmpty()
            if (step != null) {
                stepResult.text = step.text
                stepResult.setTextColor(roleColor(step.role))
                stepExplanation.text = step.explanation
            }
            // The explanation line is cut at two lines; a tap shows all of it (the desktop's tooltip).
            val explanation = step?.explanation?.takeIf { it.isNotEmpty() }
            itemView.setOnClickListener(explanation?.let { text ->
                View.OnClickListener {
                    MaterialAlertDialogBuilder(this@ChainSettingsActivity).setTitle(proxyEntity.displayName())
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            })
        }

    }

}

/** edit_chain.cpp's colour roles of a hop test result. */
private enum class StepRole { SUCCESS, DANGER, MUTED }

/** A hop's result text and its explanation (the desktop's tooltip). */
private class StepRow(val text: String, val explanation: String, val role: StepRole)

private class StepVerdict(val rows: List<StepRow>, val status: String, val statusRole: StepRole)

private sealed class StepOutcome {
    class Done(val report: ChainStepReport) : StepOutcome()
    object Busy : StepOutcome()
    object Unreachable : StepOutcome()
}

/**
 * Binds :bg for one hop test (ICoreService.startChainStepTest) and hands its outcome to [onOutcome] on the main
 * thread, at most once; [cancel] stops the test in :bg and drops the outcome. Main thread only.
 */
private class ChainStepClient(
    private val hops: LongArray,
    private val groupId: Long,
    private val onOutcome: (StepOutcome) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var unbind: (() -> Unit)? = null
    private var service: ICoreService? = null
    private var started = false
    private var cancelled = false
    private var finished = false

    private val timeout = Runnable { if (service == null) finish(StepOutcome.Unreachable) }

    private val callback = object : IChainStepCallback.Stub() {
        override fun onDone(report: ChainStepReport?) {
            main.post { finish(if (report != null) StepOutcome.Done(report) else StepOutcome.Unreachable) }
        }
    }

    fun start() {
        unbind = try {
            CoreServiceClient.bindPersistent(
                onConnected = { service -> main.post { onConnected(service) } },
                onDisconnected = { main.post { finish(StepOutcome.Unreachable) } },
            )
        } catch (e: Exception) {
            Logs.w(e)
            main.post { finish(StepOutcome.Unreachable) }
            return
        }
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
    }

    fun cancel() {
        if (finished) return
        finished = true
        cancelled = true
        main.removeCallbacks(timeout)
        val service = service
        // With the start still in flight, its answer stops the test and releases the binding.
        if (service == null) release() else if (started) stopAndRelease(service)
    }

    private fun onConnected(service: ICoreService) {
        if (finished || this.service != null) return
        this.service = service
        main.removeCallbacks(timeout)
        runOnDefaultDispatcher {
            val accepted = try {
                service.startChainStepTest(hops, groupId, callback)
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            onMainDispatcher {
                when {
                    cancelled -> if (accepted == true) stopAndRelease(service) else release()
                    accepted == null -> finish(StepOutcome.Unreachable)
                    !accepted -> finish(StepOutcome.Busy)
                    else -> started = true
                }
            }
        }
    }

    private fun finish(outcome: StepOutcome) {
        if (finished) return
        finished = true
        main.removeCallbacks(timeout)
        release()
        onOutcome(outcome)
    }

    private fun stopAndRelease(service: ICoreService) {
        runOnDefaultDispatcher {
            try {
                service.stopChainStepTest()
            } catch (e: Exception) {
                Logs.w(e)
            }
            onMainDispatcher { release() }
        }
    }

    private fun release() {
        unbind?.invoke()
        unbind = null
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
    }
}
