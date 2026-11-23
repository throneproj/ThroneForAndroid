package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import android.content.Intent
import android.text.InputType
import android.view.MenuItem
import android.widget.FrameLayout
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.nameList
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Multi-selection in the current tab (the desktop's selected rows): long press starts it, taps toggle, the toolbar
 * turns into its contextual actions. Back or the close icon end it. Like the desktop's per-group selected rows, a
 * tab switch parks the selection and the tab gets it back when it is shown again.
 */
class SelectionMode(private val host: ConfigurationFragment) {

    private val checked = LinkedHashSet<Long>()

    /** The selections of the tabs not shown, by group. */
    private val parked = HashMap<Long, LinkedHashSet<Long>>()

    /** The row whose long press (and drag) started the selection. */
    private var dragStartedId = 0L

    var active = false
        private set
    var groupId = 0L
        private set

    fun isChecked(id: Long) = id in checked

    fun start(profile: ProxyEntity) {
        if (active) return toggle(profile.id)
        active = true
        groupId = profile.groupId
        checked.clear()
        checked.add(profile.id)
        dragStartedId = 0L
        host.onSelectionModeChanged(true)
        refreshPinTitle()
    }

    /** A long press that turned into a drag: selecting unless the row really moves. */
    fun startFromDrag(profile: ProxyEntity) {
        if (active) return
        start(profile)
        dragStartedId = profile.id
    }

    fun onDragFinished(moved: Boolean) {
        val startedByDrag = dragStartedId
        dragStartedId = 0L
        if (moved && startedByDrag != 0L && checked.size == 1 && startedByDrag in checked) finish()
    }

    fun toggle(id: Long) {
        if (!active) return
        dragStartedId = 0L
        if (!checked.remove(id)) checked.add(id)
        if (checked.isEmpty()) {
            finish()
        } else {
            host.onSelectionChanged(listOf(id))
            refreshPinTitle()
        }
    }

    fun finish() {
        if (!active) return
        active = false
        checked.clear()
        dragStartedId = 0L
        host.onSelectionModeChanged(false)
    }

    /** The screen goes away: every tab's selection ends. */
    fun reset() {
        parked.clear()
        finish()
    }

    /** The tab of [shownGroupId] came into view: the previous tab's selection is parked and this one's restored. */
    fun onGroupShown(shownGroupId: Long) {
        if (active && shownGroupId == groupId) return
        if (active) parked[groupId] = LinkedHashSet(checked)
        val restored = parked.remove(shownGroupId)
        // the rows deleted meanwhile, when the list already knows its members
        host.listFor(shownGroupId)?.adapter?.takeIf { it.loaded }?.memberIds?.let { members ->
            restored?.retainAll(members.toHashSet())
        }
        if (restored.isNullOrEmpty()) return finish()
        active = true
        groupId = shownGroupId
        checked.clear()
        checked.addAll(restored)
        dragStartedId = 0L
        host.onSelectionModeChanged(true)
        refreshPinTitle()
    }

    val count: Int get() = checked.size

    /** The checked profiles in list order. */
    fun ids(): List<Long> {
        val order = host.listFor(groupId)?.adapter?.memberIds ?: return checked.toList()
        return order.filter { it in checked }
    }

    private fun selectAll() {
        val shown = host.listFor(groupId)?.adapter?.displayedIds ?: return
        checked.addAll(shown)
        host.onSelectionChanged(null)
        refreshPinTitle()
    }

    /** Every checked profile is pinned, so the action unpins (refresh_pin_action). */
    private fun allPinned(): Boolean {
        val adapter = host.listFor(groupId)?.adapter ?: return false
        return checked.isNotEmpty() && checked.all(adapter::isPinned)
    }

    private fun refreshPinTitle() {
        host.toolbar?.menu?.findItem(R.id.action_selection_pin)
            ?.setTitle(if (allPinned()) R.string.profiles_unpin else R.string.profiles_pin)
    }

    /** on_menu_pin_triggered. */
    private fun pin(ids: List<Long>) {
        val gid = groupId
        val unpin = allPinned()
        finish()
        ProfileItemMenu.setPinned(host, gid, ids, pin = !unpin)
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        if (!active) return false
        val ids = ids()
        when (item.itemId) {
            R.id.action_selection_test_url -> test(TestSpec.KIND_URL, ids)
            R.id.action_selection_test_ip -> test(TestSpec.KIND_IP, ids)
            R.id.action_selection_test_speed -> test(TestSpec.KIND_SPEED, ids)
            R.id.action_selection_test_service -> {
                ProfileTests.startServiceProfiles(host, ids, groupId)
                finish()
            }

            R.id.action_selection_all -> selectAll()
            R.id.action_selection_delete -> delete(ids)
            R.id.action_selection_pin -> pin(ids)
            R.id.action_selection_clear_results -> clearResults(ids)
            R.id.action_selection_move -> moveToGroup(ids)
            R.id.action_selection_copy_links -> copyLinks(ids, throne = false)
            R.id.action_selection_copy_throne -> copyLinks(ids, throne = true)
            R.id.action_selection_qr_links -> showQr(ids, throne = false)
            R.id.action_selection_qr_throne -> showQr(ids, throne = true)
            else -> return false
        }
        return true
    }

    private fun test(kind: Int, ids: List<Long>) {
        ProfileTests.startProfiles(host, kind, ids, groupId)
        finish()
    }

    /** on_menu_delete_triggered: asks unless skip_delete_confirmation; the running profile is stopped. */
    private fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val run = {
            finish()
            GroupActions.deleteProfiles(host, ids, stopRunning = true)
        }
        if (DataStore.skipDeleteConfirmation) {
            run()
            return
        }
        val title = host.resources.getQuantityString(R.plurals.confirm_remove_profiles, ids.size, ids.size)
        confirm(ids, title, R.string.delete, deleting = true, run = run)
    }

    private fun clearResults(ids: List<Long>) {
        val gid = groupId
        confirm(ids, host.getString(R.string.confirm_clear_test_results), R.string.confirm_clear) {
            finish()
            host.launchIo {
                ProfileManager.clearTestResults(ids)
                GroupRepo.postReload(gid)
            }
        }
    }

    /** [title] above the names of [ids] in list order; [deleting] adds the groups that lose one as front/landing proxy. */
    private fun confirm(ids: List<Long>, title: String, @StringRes action: Int, deleting: Boolean = false, run: () -> Unit) {
        host.launchIo {
            // only the names the dialog shows are parsed
            val shown = ids.take(10)
            val position = shown.withIndex().associate { it.value to it.index }
            val names = ProfileManager.getProfiles(shown).sortedBy { position[it.id] }.map { it.displayName() }
            val warning = if (deleting) GroupRepo.hopWarning(ids) else ""
            host.onUi {
                val context = requireContext()
                context.confirmAction(title, context.nameList(names, total = ids.size) + warning, action, run)
            }
        }
    }

    /** on_menu_move_to_group: to the end of another group (as the profile editor's "Move"), ProfileService::MoveToGroup. */
    private fun moveToGroup(ids: List<Long>) {
        val activity = host.activity ?: return
        val scope = host.viewLifecycleOwner.lifecycleScope
        pickMoveTarget(activity, scope, host.groups(), groupId) { target, created ->
            val outcome = ProfileManager.moveToGroup(ids, target.id, SubscriptionClient.states.value.keys)
            host.onUi {
                val refusal = outcome.refusal
                if (refusal == null) {
                    this@SelectionMode.finish()
                    if (created) SelectionMode.editGroup(requireContext(), target)
                }
                snackbar(
                    if (refusal != null) SubscriptionClient.refusalText(refusal, move = true)
                    else resources.getQuantityString(R.plurals.profiles_moved, outcome.moved, outcome.moved, target.displayName())
                ).show()
            }
        }
    }

    /** on_menu_copy_links (share link, the Throne link when there is none) / on_menu_copy_links_nkr. */
    private fun copyLinks(ids: List<Long>, throne: Boolean) {
        finish()
        host.launchIo {
            val links = links(ids, throne).map { it.first }
            if (links.isEmpty()) return@launchIo
            host.onUi { copy(links) }
        }
    }

    /** Android extra (#140): the links copyLinks would copy, in one code; when they do not fit, the copy is offered. */
    private fun showQr(ids: List<Long>, throne: Boolean) {
        finish()
        host.launchIo {
            val named = links(ids, throne)
            if (named.isEmpty()) return@launchIo
            val links = named.map { it.first }
            val text = links.joinToString("\n")
            val fits = QRCodeDialog.fits(text)
            host.onUi {
                if (fits) {
                    QRCodeDialog(text, requireContext().nameList(named.map { it.second }, limit = 3))
                        .showAllowingStateLoss(parentFragmentManager)
                } else {
                    snackbar(R.string.qr_too_large).setAction(R.string.profiles_copy_links) { copy(links) }.show()
                }
            }
        }
    }

    /** The links of [ids] in list order with their profile names, profiles without one left out. */
    private fun links(ids: List<Long>, throne: Boolean): List<Pair<String, String>> {
        val position = ids.withIndex().associate { it.value to it.index }
        return ProfileManager.getProfiles(ids).sortedBy { position[it.id] }.mapNotNull { profile ->
            val link = if (throne) profile.exportJsonLink() else profile.exportLink().ifEmpty { profile.exportJsonLink() }
            if (link.isEmpty()) null else link to profile.displayName()
        }
    }

    private fun ConfigurationFragment.copy(links: List<String>) {
        val copied = SagerNet.trySetPrimaryClip(links.joinToString("\n"))
        snackbar(
            if (copied) resources.getQuantityString(R.plurals.profiles_copied, links.size, links.size)
            else getString(R.string.action_export_err)
        ).show()
    }

    companion object {

        /**
         * The "Move to group" menu (mainwindow_menus.cpp): the [groups] a move may target (not [sourceGid], not archived,
         * not a subscription), then "New group", which asks for a name and adds a basic group. [onPicked] gets the
         * target off the main thread, and whether "New group" just created it. The subscription queue's states are
         * watched from the menu on (within [scope]), so the move knows which groups are updating.
         */
        fun pickMoveTarget(
            activity: FragmentActivity,
            scope: CoroutineScope,
            groups: List<ProxyGroup>,
            sourceGid: Long,
            onPicked: suspend (target: ProxyGroup, created: Boolean) -> Unit,
        ) {
            val targets = groups.filter { it.id != sourceGid && !it.archive && !it.isSubscription }
            val watch = scope.launch { SubscriptionClient.states.collect {} }
            var picked = false
            fun pick(created: Boolean, target: suspend () -> ProxyGroup) = runOnDefaultDispatcher {
                try {
                    onPicked(target(), created)
                } finally {
                    watch.cancel()
                }
            }
            val names = targets.map { it.displayName() } + activity.getString(R.string.grp_new_group)
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.profiles_move_to_group)
                .setItems(names.toTypedArray()) { _, which ->
                    picked = true
                    if (which < targets.size) {
                        pick(created = false) { targets[which] }
                    } else {
                        askGroupName(activity, onCancel = { watch.cancel() }) { name ->
                            pick(created = true) { GroupRepo.add(ProxyGroup(name = name)) }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { if (!picked) watch.cancel() }
                .show()
        }

        /** The editor of a group "New group" created, for what its name prompt does not ask (DialogEditGroup). */
        fun editGroup(context: Context, group: ProxyGroup) {
            context.startActivity(
                Intent(context, GroupSettingsActivity::class.java).putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
            )
        }

        /** DialogEditGroup's name for a new basic group; [onName] gets it trimmed, never blank. */
        private fun askGroupName(activity: FragmentActivity, onCancel: () -> Unit, onName: (String) -> Unit) {
            val layout = TextInputLayout(activity).apply { hint = activity.getString(R.string.grp_name) }
            val input = TextInputEditText(layout.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT
                isSingleLine = true
            }
            layout.addView(input)
            val container = FrameLayout(activity).apply {
                setPadding(dp2px(20), dp2px(12), dp2px(20), 0)
                addView(layout)
            }
            var named = false
            val dialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.grp_new_group)
                .setView(container)
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setOnDismissListener { if (!named) onCancel() }
                .show()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                val problem = Validation.required(name)
                if (problem.isNotEmpty()) {
                    layout.error = problem
                    return@setOnClickListener
                }
                named = true
                dialog.dismiss()
                onName(name)
            }
        }
    }
}
