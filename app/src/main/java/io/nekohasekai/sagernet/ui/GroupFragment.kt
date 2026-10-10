package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.InvalidationTracker
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.profiles.SubscriptionInfoCard
import io.nekohasekai.sagernet.widget.applyListInsets
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The groups screen (the desktop's Manage Groups dialog and GroupItem rows) in tab order: drag to reorder, update,
 * edit, share and remove. Rows follow the database (both processes write it) and the subscription queue's states.
 */
class GroupFragment : ToolbarFragment(R.layout.layout_group), Toolbar.OnMenuItemClickListener {

    private lateinit var groupListView: RecyclerView
    private lateinit var groupAdapter: GroupAdapter

    private val reloads = Channel<Unit>(Channel.CONFLATED)
    private val tableObserver = object : InvalidationTracker.Observer(arrayOf(ProxyGroup.TABLE, ProxyEntity.TABLE)) {
        override fun onInvalidated(tables: Set<String>) {
            reloads.trySend(Unit)
        }
    }

    /** The group whose members "Export to file" writes. */
    private var exportGroup: ProxyGroup? = null

    private val exportProfiles = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        val group = exportGroup ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        runOnDefaultDispatcher {
            val message = try {
                val links = ProfileManager.members(group.id).joinToString("\n") {
                    it.exportLink().ifEmpty { it.exportJsonLink() }
                }
                context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { it.write(links) }
                context.getString(R.string.action_export_msg)
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            withContext(Dispatchers.Main) { snackbar(message).show() }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        toolbar?.setTitle(R.string.menu_group)
        toolbar?.inflateMenu(R.menu.add_group_menu)
        toolbar?.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        groupListView.applyListInsets()
        groupListView.layoutManager = FixedLinearLayoutManager(groupListView)
        groupAdapter = GroupAdapter()
        groupListView.adapter = groupAdapter
        ItemTouchHelper(DragCallback()).attachToRecyclerView(groupListView)

        val owner = viewLifecycleOwner
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                val tracker = SagerDatabase.instance.invalidationTracker
                withContext(Dispatchers.IO) { tracker.addObserver(tableObserver) }
                try {
                    launch { SubscriptionClient.states.collect { groupAdapter.updateStates(it) } }
                    reloads.trySend(Unit)
                    for (ignored in reloads) {
                        groupAdapter.reload()
                        delay(RELOAD_INTERVAL_MS)
                    }
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { tracker.removeObserver(tableObserver) }
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> startActivity(Intent(requireContext(), GroupSettingsActivity::class.java))

            // dialog_manage_groups: every subscription, skip_auto_update included
            R.id.action_update_all -> requireContext().confirmAction(
                getString(R.string.grp_update_all_confirm), null, R.string.group_update,
            ) { runOnDefaultDispatcher { SubscriptionClient.refreshAll(false) } }
        }
        return true
    }

    private fun edit(group: ProxyGroup) {
        startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
            putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
        })
    }

    private fun copyLinks(group: ProxyGroup, deepLinks: Boolean) {
        runOnDefaultDispatcher {
            val links = ProfileManager.members(group.id)
                .map { if (deepLinks) it.exportJsonLink() else it.exportLink() }
                .filter { it.isNotEmpty() }
                .joinToString("\n")
            withContext(Dispatchers.Main) {
                val copied = SagerNet.trySetPrimaryClip(links)
                snackbar(if (copied) R.string.grp_copied else R.string.action_export_err).show()
            }
        }
    }

    private fun confirmClear(group: ProxyGroup) {
        requireContext().confirmAction(
            getString(R.string.confirm_clear_group), group.displayName(), R.string.confirm_clear,
        ) {
            runOnDefaultDispatcher {
                val outcome = ProfileManager.batchDeleteProfiles(ProfileManager.memberIds(group.id))
                if (outcome.kept.isNotEmpty()) withContext(Dispatchers.Main) {
                    snackbar(R.string.grp_running_kept).show()
                }
            }
        }
    }

    /** GroupItem::on_remove_clicked: never the last group; a running profile of the group is stopped first. */
    private fun confirmRemove(group: ProxyGroup) {
        requireContext().confirmAction(getString(R.string.confirm_remove_group), group.displayName(), R.string.delete) {
            runOnDefaultDispatcher { GroupRepo.delete(group.id) }
        }
    }

    private data class Row(val group: ProxyGroup, val count: Long)

    private inner class DragCallback : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {

        override fun onMove(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
        ): Boolean {
            groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) groupAdapter.dragging = true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            groupAdapter.dragging = false
            groupAdapter.commitMove()
        }
    }

    private inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>() {

        val rows = ArrayList<Row>()
        var states: Map<Long, Int> = emptyMap()
        var dragging = false
        private var moved = false
        private var reloadAfterDrag = false

        init {
            setHasStableIds(true)
        }

        suspend fun reload() {
            val fresh = withContext(Dispatchers.IO) {
                GroupRepo.all().map { Row(it, SagerDatabase.proxyDao.countByGroup(it.id)) }
            }
            if (dragging) {
                reloadAfterDrag = true
                return
            }
            val old = ArrayList(rows)
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = fresh.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    old[oldPosition].group.id == fresh[newPosition].group.id

                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                    old[oldPosition] == fresh[newPosition]
            })
            rows.clear()
            rows.addAll(fresh)
            diff.dispatchUpdatesTo(this)
        }

        /** gid → 1 queued / 2 running; a group leaving the queue is reloaded. */
        fun updateStates(newStates: Map<Long, Int>) {
            val old = states
            states = newStates
            for (gid in old.keys + newStates.keys) {
                if (old[gid] == newStates[gid]) continue
                val index = rows.indexOfFirst { it.group.id == gid }
                if (index >= 0) notifyItemChanged(index, PAYLOAD_STATE)
                if (newStates[gid] == null) reloads.trySend(Unit)
            }
        }

        fun move(from: Int, to: Int) {
            if (from !in rows.indices || to !in rows.indices) return
            rows.add(to, rows.removeAt(from))
            moved = true
            notifyItemMoved(from, to)
        }

        fun commitMove() {
            if (moved) {
                moved = false
                val ids = rows.map { it.group.id }
                runOnDefaultDispatcher { GroupRepo.setDisplayOrder(ids) }
            }
            if (reloadAfterDrag) {
                reloadAfterDrag = false
                reloads.trySend(Unit)
            }
        }

        /** "Move up" / "Move down" for keyboards and D-pads. */
        fun step(group: ProxyGroup, delta: Int) {
            val from = rows.indexOfFirst { it.group.id == group.id }
            val to = from + delta
            if (from < 0 || to !in rows.indices) return
            move(from, to)
            commitMove()
        }

        override fun getItemId(position: Int) = rows[position].group.id

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: GroupHolder, position: Int) = holder.bind(rows[position])

        override fun onBindViewHolder(holder: GroupHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_STATE }) {
                holder.bindState()
            } else {
                holder.bind(rows[position])
            }
        }
    }

    private inner class GroupHolder(val binding: LayoutGroupItemBinding) : RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var row: Row

        fun bind(row: Row) {
            this.row = row
            val group = row.group
            binding.groupName.text = group.displayName()
            binding.groupType.text = typeText(group)
            binding.groupUrl.isVisible = group.isSubscription
            binding.groupUrl.text = group.url
            val info = if (group.isSubscription) infoText(group) else ""
            binding.groupInfo.isVisible = info.isNotEmpty()
            binding.groupInfo.text = info
            binding.groupUpdate.isVisible = group.isSubscription
            bindState()

            binding.root.setOnClickListener { edit(group) }
            binding.edit.setOnClickListener { edit(group) }
            binding.groupUpdate.setOnClickListener {
                runOnDefaultDispatcher { SubscriptionClient.refreshGroup(group.id, true) }
            }
            binding.options.setOnClickListener { anchor ->
                val popup = PopupMenu(requireContext(), anchor)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)
                val menu = popup.menu
                val index = groupAdapter.rows.indexOfFirst { it.group.id == group.id }
                menu.findItem(R.id.action_share_subscription).isVisible = group.isSubscription
                val empty = row.count == 0L
                menu.findItem(R.id.action_copy_links).isVisible = !empty
                menu.findItem(R.id.action_copy_deep_links).isVisible = !empty
                menu.findItem(R.id.action_export_file).isVisible = !empty
                menu.findItem(R.id.action_clear).isVisible = !empty
                menu.findItem(R.id.action_move_up).isVisible = index > 0
                menu.findItem(R.id.action_move_down).isVisible = index in 0 until groupAdapter.rows.size - 1
                menu.findItem(R.id.action_remove).isVisible = groupAdapter.rows.size > 1
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }
        }

        /** The progress bar and "N profiles · Queued/Updating…" follow the queue state only. */
        fun bindState() {
            val state = groupAdapter.states[row.group.id] ?: 0
            binding.subscriptionUpdateProgress.isVisible = state != 0
            binding.groupUpdate.isEnabled = state == 0
            val count = resources.getQuantityString(R.plurals.grp_profile_count, row.count.toInt(), row.count)
            binding.groupStatus.text = when (state) {
                1 -> count + " · " + getString(R.string.grp_state_queued)
                2 -> count + " · " + getString(R.string.grp_state_running)
                else -> count
            }
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {
            val group = row.group
            when (item.itemId) {
                R.id.action_universal_clipboard -> {
                    val copied = SagerNet.trySetPrimaryClip(group.url)
                    snackbar(if (copied) R.string.grp_copied else R.string.action_export_err).show()
                }

                R.id.action_universal_qr -> QRCodeDialog(group.url, group.displayName())
                    .showAllowingStateLoss(parentFragmentManager)

                R.id.action_copy_links -> copyLinks(group, deepLinks = false)
                R.id.action_copy_deep_links -> copyLinks(group, deepLinks = true)
                R.id.action_export_file -> {
                    exportGroup = group
                    startFilesForResult(exportProfiles, "profiles_${group.displayName()}.txt")
                }

                R.id.action_clear -> confirmClear(group)
                R.id.action_move_up -> groupAdapter.step(group, -1)
                R.id.action_move_down -> groupAdapter.step(group, 1)
                R.id.action_remove -> confirmRemove(group)
                else -> return false
            }
            return true
        }

        /** GroupItem::refresh_data: "Basic" / "Subscription", prefixed "Archive" for archived groups. */
        private fun typeText(group: ProxyGroup): String {
            val type = getString(if (group.isSubscription) R.string.subscription else R.string.group_basic)
            return if (group.archive) getString(R.string.group_archived) + " " + type else type
        }

        /** GroupItem::refresh_data (GroupItem.cpp:68-80): "Last update: …" and the subscription info, one per line. */
        private fun infoText(group: ProxyGroup): String {
            val lines = ArrayList<String>()
            if (group.subLastUpdate != 0L) {
                lines.add(getString(R.string.grp_last_update, SubscriptionInfoCard.displayTime(group.subLastUpdate)))
            }
            SubscriptionInfoCard.summary(requireContext(), group.subInfo).takeIf { it.isNotEmpty() }?.let(lines::add)
            return lines.joinToString("\n")
        }
    }

    private companion object {
        const val PAYLOAD_STATE = "state"
        const val RELOAD_INTERVAL_MS = 300L
    }
}
