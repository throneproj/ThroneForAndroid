package io.nekohasekai.sagernet.ui.profiles

import android.view.MenuItem
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.nameList
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment

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
    }

    fun onMenuItemClick(item: MenuItem): Boolean {
        if (!active) return false
        val ids = ids()
        when (item.itemId) {
            R.id.action_selection_test_url -> test(TestSpec.KIND_URL, ids)
            R.id.action_selection_test_ip -> test(TestSpec.KIND_IP, ids)
            R.id.action_selection_test_speed -> test(TestSpec.KIND_SPEED, ids)
            R.id.action_selection_all -> selectAll()
            R.id.action_selection_delete -> delete(ids)
            R.id.action_selection_clear_results -> clearResults(ids)
            R.id.action_selection_move -> moveToGroup(ids)
            R.id.action_selection_copy_links -> copyLinks(ids, throne = false)
            R.id.action_selection_copy_throne -> copyLinks(ids, throne = true)
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
        confirm(ids, title, R.string.delete, run)
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

    /** [title] above the names of [ids] in list order. */
    private fun confirm(ids: List<Long>, title: String, @StringRes action: Int, run: () -> Unit) {
        host.launchIo {
            // only the names the dialog shows are parsed
            val shown = ids.take(10)
            val position = shown.withIndex().associate { it.value to it.index }
            val names = ProfileManager.getProfiles(shown).sortedBy { position[it.id] }.map { it.displayName() }
            host.onUi {
                val context = requireContext()
                context.confirmAction(title, context.nameList(names, total = ids.size), action, run)
            }
        }
    }

    /** Android extra: to the end of another basic group (as the profile editor's "Move"). */
    private fun moveToGroup(ids: List<Long>) {
        val targets = host.groups().filter { !it.isSubscription && it.id != groupId }
        if (targets.isEmpty()) {
            host.snackbar(R.string.profiles_move_no_target).show()
            return
        }
        MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.profiles_move_to_group)
            .setItems(targets.map { it.displayName() }.toTypedArray()) { _, which ->
                val target = targets[which]
                finish()
                host.launchIo {
                    ProfileManager.moveToGroup(ids, target.id)
                    host.onUi {
                        snackbar(
                            resources.getQuantityString(R.plurals.profiles_moved, ids.size, ids.size, target.displayName())
                        ).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** on_menu_copy_links (share link, the Throne link when there is none) / on_menu_copy_links_nkr. */
    private fun copyLinks(ids: List<Long>, throne: Boolean) {
        finish()
        host.launchIo {
            val position = ids.withIndex().associate { it.value to it.index }
            val links = ProfileManager.getProfiles(ids).sortedBy { position[it.id] }.mapNotNull { profile ->
                val link = if (throne) profile.exportJsonLink() else profile.exportLink().ifEmpty { profile.exportJsonLink() }
                link.takeIf { it.isNotEmpty() }
            }
            if (links.isEmpty()) return@launchIo
            host.onUi {
                val copied = SagerNet.trySetPrimaryClip(links.joinToString("\n"))
                snackbar(
                    if (copied) resources.getQuantityString(R.plurals.profiles_copied, links.size, links.size)
                    else getString(R.string.action_export_err)
                ).show()
            }
        }
    }
}
