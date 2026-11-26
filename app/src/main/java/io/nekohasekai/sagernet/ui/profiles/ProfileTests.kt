package io.nekohasekai.sagernet.ui.profiles

import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.test.ServiceCatalog
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.test.ServiceCheckSelector
import io.nekohasekai.sagernet.ui.test.ServiceResults
import io.nekohasekai.sagernet.ui.test.TestSessionClient

/**
 * The test entry points of the profiles screen (desktop TestRunner scopes: group, selection, one profile); the
 * running connection's speed test is on the stats bar's long-press menu (MainActivity), its service check there and
 * on the running profile's menu.
 */
internal object ProfileTests {

    /** Url Test / Speedtest / Resolve out IP for the group, in list order. */
    fun startGroup(host: ConfigurationFragment, kind: Int, groupId: Long) {
        confirmSpeed(host, kind, many = true) { showTested(kind, groupId, TestSessionClient.startGroup(kind, groupId)) }
    }

    /** Selected profiles (or one); [groupId] lets the panel offer "Sort" afterwards. */
    fun startProfiles(host: ConfigurationFragment, kind: Int, ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        confirmSpeed(host, kind, many = ids.size > 1) {
            showTested(kind, groupId, TestSessionClient.startProfiles(kind, ids, groupId))
        }
    }

    /**
     * testPanelSeeded (mainwindow_view.cpp): a test that starts turns on the rows' results of its kind in group
     * [groupId], latency, exit IP or speed; a service check turns on the exit IP (the desktop's column of the service
     * icons) only while the group shows the icons.
     */
    internal fun showTestedColumn(kind: Int, groupId: Long) {
        if (groupId <= 0L) return
        val bit = when (kind) {
            TestSpec.KIND_URL -> ProxyGroup.VisibleColumns.LATENCY
            TestSpec.KIND_IP -> ProxyGroup.VisibleColumns.EXIT_IP
            TestSpec.KIND_SPEED -> ProxyGroup.VisibleColumns.SPEED
            // Service results have their own line, shown by show_services rather than a column bit.
            else -> return
        }
        runOnDefaultDispatcher {
            var shown = false
            val group = GroupFields.update(groupId) {
                if (it.visibleColumns and bit == 0) {
                    it.updateVisibleColumns(it.visibleColumns or bit)
                    shown = true
                }
            }
            if (shown && group != null) GroupRepo.postUpdate(group)
        }
    }

    /** [showTestedColumn] for a test of the running connection, in the running profile's group. */
    internal fun showTestedColumnOfRunning(kind: Int) {
        runOnDefaultDispatcher {
            ProfileManager.getProfile(ProfileManager.runningProfileId())?.let { showTestedColumn(kind, it.groupId) }
        }
    }

    /** [started] passed through, after [showTestedColumn] when the test started. */
    private fun showTested(kind: Int, groupId: Long, started: Boolean): Boolean {
        if (started) showTestedColumn(kind, groupId)
        return started
    }

    /** Speed tests of more than one profile ask first: they move a lot of data. */
    private fun confirmSpeed(host: ConfigurationFragment, kind: Int, many: Boolean, start: () -> Boolean) {
        if (kind != TestSpec.KIND_SPEED || !many) return report(host, start())
        MaterialAlertDialogBuilder(host.requireContext())
            .setTitle(R.string.speed_test_confirm_title)
            .setMessage(R.string.speed_test_confirm_message)
            .setPositiveButton(R.string.profiles_test_start) { _, _ -> report(host, start()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Service Check Group: the group's members, counted like the selection (desktop group->Profiles()). */
    fun startServiceGroup(host: ConfigurationFragment, groupId: Long) {
        host.launchIo {
            val count = ProfileManager.memberIds(groupId).size
            host.onUi {
                confirmService(host.requireContext(), count, { busy(host) }) {
                    val started = TestSessionClient.startGroup(TestSpec.KIND_SERVICE, groupId)
                    showTested(TestSpec.KIND_SERVICE, groupId, started)
                }
            }
        }
    }

    /** Service Check of the selected profiles (or one). */
    fun startServiceProfiles(host: ConfigurationFragment, ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        confirmService(host.requireContext(), ids.size, { busy(host) }) {
            val started = TestSessionClient.startProfiles(TestSpec.KIND_SERVICE, ids, groupId)
            showTested(TestSpec.KIND_SERVICE, groupId, started)
        }
    }

    /** runCurrentServiceCheck: the running connection, checked through its live box and stored on its profile. */
    fun startServiceCurrent(host: ConfigurationFragment) = startServiceCurrent(host.requireContext()) { busy(host) }

    fun startServiceCurrent(context: Context, onBusy: () -> Unit) {
        confirmService(context, 1, onBusy) {
            TestSessionClient.startCurrent(TestSpec.KIND_SERVICE).also {
                if (it) showTestedColumnOfRunning(TestSpec.KIND_SERVICE)
            }
        }
    }

    /** startServiceChecks (mainwindow_setup.cpp): refused above the runner's limit, asked first above 20 profiles. */
    private fun confirmService(context: Context, count: Int, onBusy: () -> Unit, start: () -> Boolean) {
        fun report(started: Boolean) {
            if (!started) onBusy()
        }
        // Only known once :bg described the definitions; before that :bg refuses the run itself.
        val nothingSelected = ServiceCatalog.saved()?.enabled(ServiceResults.disabled)?.isEmpty() == true
        when {
            nothingSelected -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.service_check)
                .setMessage(R.string.service_check_none_selected)
                .setPositiveButton(R.string.service_check_services) { _, _ -> ServiceCheckSelector.show(context) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            count > TestSpec.SERVICE_MAX_PROFILES -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.service_check)
                .setMessage(context.getString(R.string.service_check_too_many, TestSpec.SERVICE_MAX_PROFILES))
                .setPositiveButton(android.R.string.ok, null)
                .show()

            count > TestSpec.SERVICE_CONFIRM_ABOVE -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.service_check)
                .setMessage(context.getString(R.string.service_check_confirm, count))
                .setPositiveButton(R.string.profiles_test_start) { _, _ -> report(start()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            else -> report(start())
        }
    }

    private fun report(host: ConfigurationFragment, started: Boolean) {
        if (!started) busy(host)
    }

    private fun busy(host: ConfigurationFragment) {
        host.snackbar(R.string.profiles_test_busy).show()
    }
}
