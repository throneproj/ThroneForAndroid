package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorClient
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorStatus
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorMemberBinding
import io.nekohasekai.sagernet.databinding.LayoutAutoSelectorStatusBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import io.nekohasekai.sagernet.ui.profile.profileSettingsIntent
import io.nekohasekai.sagernet.ui.profiles.ProfilesDbWatcher
import io.nekohasekai.sagernet.widget.applyInsetPadding
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auto Selector Stats (DialogAutoSelector, dialog_auto_selector.cpp): the headline, detail and footer of the running
 * selector, the core's member table in rank order with the selected and pinned ones marked, and Use this profile /
 * Back to automatic / Check all now. Without a running selector the last build is listed with its URL-test results.
 */
class AutoSelectorStatusActivity : ThemedActivity(), SagerConnection.Callback {

    companion object {
        const val EXTRA_SELECTOR_ID = "selectorId"
        private const val STATE_ONLY_PROBLEMS = "onlyProblems"

        /** The service's poll interval while the screen is on. */
        private const val TICK_MS = 2_000L

        fun intent(context: Context, selectorId: Long): Intent =
            Intent(context, AutoSelectorStatusActivity::class.java).putExtra(EXTRA_SELECTOR_ID, selectorId)
    }

    private class Stored(val name: String, val lastBuilt: List<Long>, val lastBuiltAt: Long, val pinnedId: Long)

    private class Row(
        val id: Long,
        val rank: Int,
        val name: String,
        val selected: Boolean,
        val pinned: Boolean,
        /** The core's member while the selector runs, null for a row of the last build. */
        val member: AutoSelectorStatus.Member?,
        val profile: ProxyEntity?,
    ) {
        val hasProblem: Boolean
            get() = member?.hasProblem ?: (profile?.isUnavailable() == true)
    }

    private lateinit var binding: LayoutAutoSelectorStatusBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_AUTO_SELECTOR, true)
    private val adapter = MemberAdapter()
    private val reloads = Channel<Unit>(Channel.CONFLATED)

    private val editor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.data?.getBooleanExtra(ProfileSettingsActivity.EXTRA_RESTART_NEEDED, false) == true) needReload()
    }

    private var selectorId = -1L
    private var onlyProblems = false
    private var status = AutoSelectorStatus.IDLE
    private var stored: Stored? = null
    private var profiles: Map<Long, ProxyEntity> = emptyMap()

    /** The ids [profiles] holds; null after a database change. */
    private var profileIds: List<Long>? = null
    private var storedStale = true
    private var shownNoticeAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutAutoSelectorStatusBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.autosel_stats_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }
        onlyProblems = savedInstanceState?.getBoolean(STATE_ONLY_PROBLEMS) ?: false
        val current = AutoSelectorClient.status.value
        selectorId = intent.getLongExtra(EXTRA_SELECTOR_ID, -1L).takeIf { it > 0 } ?: current.selectorId
        // A notice from before the screen opened is not news.
        shownNoticeAt = current.noticeAtMs

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.header.applyInsetPadding(horizontal = true)
        binding.empty.applyInsetPadding(horizontal = true)
        binding.list.applyListInsets()
        binding.recheck.setOnClickListener { withService { it.autoSelectorRecheck() } }
        binding.automatic.setOnClickListener { withService { it.autoSelectorSelect(-1) } }
        connection.connect(this, this)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Nothing watched the database while the screen was stopped.
                storedStale = true
                launch { AutoSelectorClient.status.collect { onStatus(it) } }
                launch {
                    while (true) {
                        reloads.trySend(Unit)
                        delay(TICK_MS)
                    }
                }
                ProfilesDbWatcher(onProfiles = {
                    storedStale = true
                    reloads.trySend(Unit)
                }, onGroups = {}).start(this)
                for (signal in reloads) {
                    load()
                    render()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ONLY_PROBLEMS, onlyProblems)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.auto_selector_status_menu, menu)
        menu.findItem(R.id.action_only_problems)?.isChecked = onlyProblems
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_only_problems -> {
            onlyProblems = !onlyProblems
            item.isChecked = onlyProblems
            render()
            true
        }

        R.id.action_edit_selector -> {
            val id = selectorId
            lifecycleScope.launch {
                val profile = withContext(Dispatchers.IO) { ProfileManager.getProfile(id) }
                if (profile != null) editor.launch(profile.profileSettingsIntent(this@AutoSelectorStatusActivity, false))
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.root, text, Snackbar.LENGTH_LONG)

    // ------------------------------------------------------------------------------------------------ service

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        reloads.trySend(Unit)
    }

    override fun onServiceConnected(service: ISagerNetService) = Unit

    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private fun withService(action: (ISagerNetService) -> Unit) {
        val service = connection.service
        if (service == null) {
            snackbar(R.string.autosel_stats_not_running).show()
            return
        }
        runOnDefaultDispatcher {
            try {
                action(service)
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ data

    /** The live build of this selector, when it is the one running. */
    private fun liveFor(status: AutoSelectorStatus) =
        status.active && status.selectorId == selectorId && status.members.isNotEmpty()

    /** The client's status (no member table): the notice, the selector to show, and a reload. */
    private fun onStatus(next: AutoSelectorStatus) {
        if (selectorId <= 0 && next.selectorId > 0) {
            selectorId = next.selectorId
            storedStale = true
        }
        if (next.notice.isNotEmpty() && next.noticeAtMs > shownNoticeAt) {
            shownNoticeAt = next.noticeAtMs
            snackbar(next.notice).show()
        }
        reloads.trySend(Unit)
    }

    private suspend fun load() {
        val id = selectorId
        status = pull(id)
        if (storedStale) {
            storedStale = false
            profileIds = null
            withContext(Dispatchers.IO) {
                runCatching {
                    AutoSelectorProfiles.load(id)?.let {
                        Stored(it.displayName(), it.lastBuilt.toList(), it.lastBuiltAt, it.pinnedID)
                    }
                }
            }.onSuccess { stored = it }.onFailure { Logs.w(it) }
        }
        val ids = if (liveFor(status)) status.members.map { it.id } else stored?.lastBuilt.orEmpty()
        if (ids == profileIds) return
        withContext(Dispatchers.IO) { runCatching { ProfileManager.getProfiles(ids).associateBy { it.id } } }
            .onSuccess {
                profiles = it
                profileIds = ids
            }
            .onFailure { Logs.w(it) }
    }

    /** The status with the member table the callbacks leave out, while this selector is the one running. */
    private suspend fun pull(id: Long): AutoSelectorStatus {
        val current = AutoSelectorClient.status.value
        val service = connection.service
        if (!current.active || current.selectorId != id || service == null) return current
        return withContext(Dispatchers.IO) {
            try {
                AutoSelectorStatus.parse(service.autoSelectorStatus(true))
            } catch (e: Exception) {
                Logs.w(e)
                current
            }
        }
    }

    private fun rows(): List<Row> {
        val status = status
        if (liveFor(status)) {
            return status.members.map { member ->
                Row(
                    id = member.id,
                    rank = member.rank,
                    name = member.name.ifEmpty { profiles[member.id]?.displayName() ?: member.tag },
                    selected = member.selected,
                    pinned = member.id == status.pinnedId,
                    member = member,
                    profile = profiles[member.id],
                )
            }
        }
        val stored = stored ?: return emptyList()
        return stored.lastBuilt.mapIndexed { index, id ->
            Row(id, index + 1, profiles[id]?.displayName() ?: "#$id", false, id == stored.pinnedId, null, profiles[id])
        }
    }

    // ------------------------------------------------------------------------------------------------ screen

    private fun render() {
        val status = status
        val live = liveFor(status)
        val ours = status.active && status.selectorId == selectorId
        val stored = stored
        supportActionBar?.subtitle = if (ours) status.selectorName else stored?.name

        binding.headline.text = if (ours) {
            AutoSelectorTexts.summary(this, status)
        } else {
            getString(R.string.autosel_stats_not_running)
        }
        val detail = when {
            live -> AutoSelectorTexts.detail(this, status)
            ours -> null
            stored == null -> null
            stored.lastBuiltAt > 0 -> getString(
                R.string.autosel_stats_last_build, AutoSelectorTexts.ago(this, stored.lastBuiltAt * 1000), stored.lastBuilt.size
            )

            else -> getString(R.string.autosel_stats_never_built)
        }
        binding.detail.text = detail
        binding.detail.isVisible = !detail.isNullOrEmpty()
        val footer = if (live) footer(status) else ""
        binding.footer.text = footer
        binding.footer.isVisible = footer.isNotEmpty()
        val measuring = ours && status.phase == AutoSelectorStatus.PHASE_MEASURING
        binding.progress.isVisible = measuring
        binding.recheck.isEnabled = live && !measuring
        binding.automatic.isVisible = live && status.pinnedId >= 0
        binding.automatic.isEnabled = !measuring

        val all = rows()
        val shown = if (onlyProblems) all.filter { it.hasProblem } else all
        adapter.submit(shown)
        binding.empty.isVisible = shown.isEmpty()
        binding.empty.setText(if (all.isNotEmpty()) R.string.autosel_stats_no_problems else R.string.autosel_stats_empty)
    }

    /** DialogAutoSelector::refresh's footer. */
    private fun footer(status: AutoSelectorStatus): String {
        val parts = ArrayList<String>()
        val now = System.currentTimeMillis()
        if (status.suspended) {
            parts.add(getString(R.string.autosel_footer_suspended))
        } else {
            if (status.roundsCompleted > 0) {
                parts.add(getString(R.string.autosel_footer_last_round, AutoSelectorTexts.ago(this, status.lastRoundMs, now)))
            }
            val next = (status.nextRoundMs - now) / 1000
            if (status.nextRoundMs > 0 && next > 0) parts.add(getString(R.string.autosel_footer_next_round, next))
        }
        status.member(status.pinnedId)?.let { member ->
            val pinned = member.name.ifEmpty { profiles[member.id]?.displayName() ?: member.tag }
            parts.add(
                if (member.id == status.selectedId) getString(R.string.autosel_footer_pinned_using, pinned)
                else getString(R.string.autosel_footer_pinned_other, pinned)
            )
        }
        if (status.lastSwitchReason.isNotEmpty() && status.lastSwitchMs > 0) {
            val ago = AutoSelectorTexts.ago(this, status.lastSwitchMs, now)
            parts.add(getString(R.string.autosel_footer_last_switch_reason, ago, status.lastSwitchReason))
        }
        if (status.exhaustedSinceMs > 0) parts.add(getString(R.string.autosel_footer_exhausted))
        return parts.joinToString(" ")
    }

    /** noteText (dialog_auto_selector.cpp:85-106); a row of the last build tells the choice and the last test error. */
    private fun note(row: Row): String {
        val member = row.member
        if (row.pinned) {
            return getString(
                when {
                    row.selected -> R.string.autosel_note_pinned_selected
                    member != null -> R.string.autosel_note_pinned_unusable
                    else -> R.string.autosel_note_pinned
                }
            )
        }
        if (row.selected) return getString(R.string.autosel_note_selected)
        if (member == null) {
            val profile = row.profile ?: return getString(R.string.autosel_note_missing)
            return if (profile.isUnavailable()) profile.testError.orEmpty() else ""
        }
        return when {
            member.state == AutoSelectorStatus.STATE_COOLDOWN -> {
                val secs = (member.cooldownUntilMs - System.currentTimeMillis()) / 1000
                if (secs > 0) getString(R.string.autosel_note_cooldown, secs) else getString(R.string.autosel_note_cooldown_now)
            }

            member.isDead -> member.lastError.ifEmpty { getString(R.string.autosel_note_dead) }
            member.qualified -> getString(R.string.autosel_note_qualified)
            member.state == AutoSelectorStatus.STATE_UNTESTED ->
                getString(if (member.active) R.string.autosel_note_checking else R.string.autosel_note_queued)

            member.failures > 0 -> getString(R.string.autosel_note_failures, member.failures, member.samples)
            else -> ""
        }
    }

    private fun stateText(member: AutoSelectorStatus.Member): String = when (member.state) {
        AutoSelectorStatus.STATE_OK -> getString(R.string.autosel_state_ok)
        AutoSelectorStatus.STATE_DEGRADED -> getString(R.string.autosel_state_degraded)
        AutoSelectorStatus.STATE_UNTESTED -> getString(R.string.autosel_state_untested)
        AutoSelectorStatus.STATE_DEAD -> getString(R.string.autosel_state_dead)
        AutoSelectorStatus.STATE_COOLDOWN -> getString(R.string.autosel_state_cooldown)
        else -> member.state
    }

    /** A stored URL-test result as the profile list shows it. */
    private fun latencyText(latency: Int): String = when {
        latency > 0 -> getString(R.string.autosel_ms, latency)
        latency == ProxyEntity.LATENCY_CONNECT_ONLY -> getString(R.string.test_connect_ok)
        latency < 0 -> getString(R.string.unavailable)
        else -> "-"
    }

    private fun onRowClick(row: Row) {
        val status = status
        val lines = ArrayList<String>()
        val member = row.member
        if (member != null) {
            lines.add(getString(R.string.autosel_row_state, stateText(member)))
            if (member.samples > 0 && member.averageMs > 0) {
                lines.add(getString(R.string.autosel_row_latency, member.averageMs, member.minMs, member.maxMs))
                lines.add(getString(R.string.autosel_row_jitter, member.deviationMs))
            }
            if (member.samples > 0) {
                lines.add(getString(R.string.autosel_row_checks, member.samples - member.failures, member.samples))
            }
            if (member.dialTotal > 0) {
                lines.add(getString(R.string.autosel_row_connects, member.dialTotal - member.dialFail, member.dialTotal))
            }
            lines.add(getString(R.string.autosel_row_last_ok, AutoSelectorTexts.ago(this, member.lastOkMs)))
            if (member.lastError.isNotEmpty()) lines.add(getString(R.string.autosel_row_error, member.lastError))
        }
        val profile = row.profile
        when {
            profile == null -> lines.add(getString(R.string.autosel_note_missing))
            profile.latency == 0 -> lines.add(getString(R.string.autosel_row_untested))
            else -> lines.add(
                getString(
                    R.string.autosel_row_tested, latencyText(profile.latency),
                    AutoSelectorTexts.ago(this, profile.latencyAt * 1000),
                )
            )
        }
        if (profile != null && profile.isUnavailable() && !profile.testError.isNullOrEmpty()) {
            lines.add(getString(R.string.autosel_row_error, profile.testError))
        }
        note(row).takeIf { it.isNotEmpty() && it != profile?.testError }?.let { lines.add(it) }

        val builder = MaterialAlertDialogBuilder(this).setTitle(row.name)
        val actionable = liveFor(status) && status.phase != AutoSelectorStatus.PHASE_MEASURING
        if (actionable) {
            if (row.pinned) {
                builder.setPositiveButton(R.string.autosel_back_to_automatic) { _, _ -> withService { it.autoSelectorSelect(-1) } }
            } else {
                builder.setPositiveButton(R.string.autosel_use_this) { _, _ -> withService { it.autoSelectorSelect(row.id) } }
                lines.add(getString(R.string.autosel_use_this_tip))
            }
        }
        builder.setMessage(lines.joinToString("\n\n"))
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class MemberAdapter : RecyclerView.Adapter<MemberHolder>() {

        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(rows: List<Row>) {
            this.rows = rows
            notifyDataSetChanged()
        }

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            MemberHolder(LayoutAutoSelectorMemberBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: MemberHolder, position: Int) = holder.bind(rows[position])
    }

    private inner class MemberHolder(private val row: LayoutAutoSelectorMemberBinding) :
        RecyclerView.ViewHolder(row.root) {

        private val plainColors = row.latency.textColors

        private fun colorLatency(@ColorInt color: Int) {
            if (color == 0) row.latency.setTextColor(plainColors) else row.latency.setTextColor(color)
        }

        fun bind(item: Row) {
            row.rank.text = item.rank.toString()
            row.name.text = item.name
            row.name.setTypeface(
                null,
                when {
                    item.selected && item.pinned -> Typeface.BOLD_ITALIC
                    item.selected -> Typeface.BOLD
                    item.pinned -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
            )
            val note = note(item)
            row.note.text = note
            row.note.isVisible = note.isNotEmpty()

            val member = item.member
            val profile = item.profile
            if (member != null) {
                row.latency.text = if (member.averageMs > 0) getString(R.string.autosel_ms, member.averageMs) else stateText(member)
                val latency = if (member.averageMs > 0) member.averageMs else if (member.isDead) -1 else 0
                colorLatency(ProxyEntity(latency = latency).latencyColor())
                row.age.text = getString(R.string.autosel_last_ok_short, AutoSelectorTexts.ago(this@AutoSelectorStatusActivity, member.lastOkMs))
                row.age.isVisible = true
            } else {
                val latency = profile?.latency ?: 0
                row.latency.text = latencyText(latency)
                colorLatency(profile?.latencyColor() ?: 0)
                val at = profile?.latencyAt ?: 0L
                row.age.text = AutoSelectorTexts.ago(this@AutoSelectorStatusActivity, at * 1000)
                row.age.isVisible = latency != 0 && at > 0
            }
            row.root.setOnClickListener { onRowClick(item) }
        }
    }
}
