package io.nekohasekai.sagernet.ui.profiles

import androidx.annotation.PluralsRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortAction
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.TestShowItems
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.confirmAction
import io.nekohasekai.sagernet.ktx.nameList
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity

/** The desktop's group actions on the current tab (mainwindow_profiles.cpp, mainwindow_setup.cpp). */
internal object GroupActions {

    fun updateSubscription(group: ProxyGroup) {
        if (group.isSubscription) SubscriptionClient.refreshGroup(group.id, showDiff = true)
    }

    fun clearTestResults(host: ConfigurationFragment, group: ProxyGroup) {
        host.requireContext().confirmAction(
            host.getString(R.string.confirm_clear_test_results), group.displayName(), R.string.confirm_clear,
        ) {
            host.launchIo {
                ProfileManager.clearGroupTestResults(group.id)
                GroupRepo.postReload(group.id)
            }
        }
    }

    /** on_menu_delete_repeat_triggered: ProfileFilter::Uniq keeps the first of each duplicate key. */
    fun removeDuplicates(host: ConfigurationFragment, groupId: Long) {
        host.launchIo {
            val skip = DataStore.skipDeleteConfirmation
            val seen = HashSet<String>()
            val duplicates = ProfileManager.members(groupId).filter { !seen.add(it.dedupKey()) }
            val names = shownNames(duplicates)
            host.onUi {
                if (duplicates.isEmpty()) {
                    snackbar(R.string.profiles_no_duplicates).show()
                } else {
                    confirmRemoval(this, skip, R.plurals.confirm_remove_duplicates, duplicates.size, names) {
                        deleteProfiles(this, duplicates.map { it.id }, stopRunning = true)
                    }
                }
            }
        }
    }

    /** clearUnavailableProfiles: BatchDeleteProfiles without stopping, so a running profile is kept. */
    fun removeUnavailable(host: ConfigurationFragment, groupId: Long) {
        host.launchIo {
            val skip = DataStore.skipDeleteConfirmation
            // The latency column decides, so the members are read without their data and only the shown ones with it.
            val unavailable = SagerDatabase.proxyDao.getWithoutDataByGroup(groupId)
                .filter { it.isUnavailable() }
                .sortedWith(compareBy<ProxyEntity> { it.userOrder }.thenBy { it.id })
                .map { it.id }
            val shown = unavailable.take(NAMES_SHOWN)
            val names = shownNames(ProfileManager.getProfiles(shown).sortedBy { shown.indexOf(it.id) })
            host.onUi {
                if (unavailable.isEmpty()) {
                    snackbar(R.string.profiles_no_unavailable).show()
                } else {
                    confirmRemoval(this, skip, R.plurals.confirm_remove_unavailable, unavailable.size, names) {
                        deleteProfiles(this, unavailable, stopRunning = false)
                    }
                }
            }
        }
    }

    /** on_menu_remove_insecure_triggered: EffectiveSecurity().isInsecure(). */
    fun removeInsecure(host: ConfigurationFragment, groupId: Long) {
        host.launchIo {
            val skip = DataStore.skipDeleteConfirmation
            val skipCert = DataStore.skipCert
            val insecure = ProfileManager.members(groupId).filter { it.outbound.effectiveSecurity(skipCert).isInsecure }
            val names = shownNames(insecure)
            host.onUi {
                if (insecure.isEmpty()) {
                    snackbar(R.string.profiles_no_insecure).show()
                } else {
                    confirmRemoval(this, skip, R.plurals.confirm_remove_insecure, insecure.size, names) {
                        deleteProfiles(this, insecure.map { it.id }, stopRunning = true)
                    }
                }
            }
        }
    }

    /** The core validates in `:bg`; the confirmation comes first since the list is only known there. */
    fun removeInvalid(host: ConfigurationFragment, group: ProxyGroup) {
        host.launchIo {
            val skip = DataStore.skipDeleteConfirmation
            host.onUi {
                if (skip) {
                    groupAction(this, group.id, "remove_invalid")
                } else {
                    requireContext().confirmAction(
                        getString(R.string.confirm_remove_invalid),
                        getString(R.string.confirm_remove_invalid_message, group.displayName()),
                        R.string.delete,
                    ) { groupAction(this, group.id, "remove_invalid") }
                }
            }
        }
    }

    /** on_menu_resolve_domain_triggered: always asks. */
    fun resolveDomains(host: ConfigurationFragment, group: ProxyGroup) {
        host.requireContext().confirmAction(
            host.getString(R.string.confirm_resolve_domains),
            host.getString(R.string.confirm_resolve_domains_message, group.displayName()),
            R.string.confirm_resolve,
        ) { groupAction(host, group.id, "resolve_domains") }
    }

    private fun groupAction(host: ConfigurationFragment, groupId: Long, action: String) {
        host.setGroupBusy(groupId, true)
        host.launchIo {
            val report = try {
                SubscriptionClient.groupAction(groupId, action)
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            host.onUi {
                setGroupBusy(groupId, false)
                if (report.isNotBlank()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setMessage(report)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    /** The names a confirmation lists, worked out off the main thread for the shown ones only. */
    private fun shownNames(profiles: List<ProxyEntity>): List<String> = profiles.take(NAMES_SHOWN).map { it.displayName() }

    /** [names] are those of the first of [count] profiles; the rest are only counted. */
    private fun confirmRemoval(
        host: ConfigurationFragment,
        skip: Boolean,
        @PluralsRes question: Int,
        count: Int,
        names: List<String>,
        remove: () -> Unit,
    ) {
        if (skip) return remove()
        val context = host.requireContext()
        context.confirmAction(
            context.resources.getQuantityString(question, count, count),
            context.nameList(names, total = count),
            R.string.delete,
            remove,
        )
    }

    /** BatchDeleteProfiles with the caller's stopRunningProfile; a kept running profile is reported. */
    fun deleteProfiles(host: ConfigurationFragment, ids: List<Long>, stopRunning: Boolean) {
        host.launchIo {
            val outcome = ProfileManager.batchDeleteProfiles(ids, stopRunning)
            host.onUi {
                val message = when {
                    !outcome.ok -> getString(R.string.profiles_delete_failed)
                    outcome.kept.isNotEmpty() -> getString(R.string.profiles_running_kept)
                    else -> resources.getQuantityString(R.plurals.removed, outcome.deleted.size, outcome.deleted.size)
                }
                snackbar(message).show()
            }
        }
    }

    /**
     * sortCurrentGroup (mainwindow_view.cpp): stores the sub-criterion the method reads ([save]), then keeps the group
     * sorted by the method from now on.
     */
    fun sort(
        host: ConfigurationFragment,
        groupId: Long,
        method: GroupSortMethod,
        descending: Boolean,
        save: ((ProxyGroup) -> Unit)? = null,
    ) {
        host.launchIo {
            if (save != null) GroupFields.update(groupId, save)?.let { GroupRepo.postUpdate(it) }
            GroupSort.setSort(groupId, GroupSortAction(method, descending))
            GroupSort.resortLater(groupId)
        }
    }

    /** stopSortingCurrentGroup: the order as it is becomes the manual one. */
    fun stopSorting(host: ConfigurationFragment, groupId: Long) {
        host.launchIo { GroupSort.setSort(groupId, GroupSortAction()) }
    }

    /** The test result column's "Include: Out IP / Speed" (updateTestItemsToShow). */
    fun setShownItems(host: ConfigurationFragment, groupId: Long, outIp: Boolean, speed: Boolean) {
        val value = when {
            outIp && speed -> TestShowItems.ALL
            outIp -> TestShowItems.IP_ONLY
            speed -> TestShowItems.SPEED_ONLY
            else -> TestShowItems.NONE
        }.value
        host.launchIo {
            GroupFields.update(groupId) { it.testItemsToShow = value }?.let { GroupRepo.postUpdate(it) }
        }
    }

    /** The running service resets its counters too (and stores the zeros); a stopped one leaves it to the table. */
    fun clearTraffic(host: ConfigurationFragment, group: ProxyGroup) {
        host.requireContext().confirmAction(
            host.getString(R.string.confirm_clear_traffic), group.displayName(), R.string.confirm_clear,
        ) {
            val service = (host.activity as? MainActivity)?.connection?.service
            host.listFor(group.id)?.adapter?.clearTraffic()
            host.launchIo {
                val ids = ProfileManager.memberIds(group.id).toLongArray()
                try {
                    service?.resetTraffic(ids)
                } catch (e: Exception) {
                    Logs.w(e)
                }
                ProfileManager.resetTraffic(ids)
            }
        }
    }

    /** nameList's default limit. */
    private const val NAMES_SHOWN = 10
}
