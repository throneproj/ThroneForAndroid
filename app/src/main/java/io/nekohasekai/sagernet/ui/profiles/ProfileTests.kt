package io.nekohasekai.sagernet.ui.profiles

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.test.TestSpec
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.test.TestSessionClient

/**
 * The test entry points of the profiles screen (desktop TestRunner scopes: group, selection, one profile); the
 * running connection's speed test is on the stats bar's long-press menu (MainActivity).
 */
internal object ProfileTests {

    /** Url Test / Speedtest / Resolve out IP for the group, in list order. */
    fun startGroup(host: ConfigurationFragment, kind: Int, groupId: Long) {
        confirmSpeed(host, kind, many = true) { TestSessionClient.startGroup(kind, groupId) }
    }

    /** Selected profiles (or one); [groupId] lets the panel offer "Sort" afterwards. */
    fun startProfiles(host: ConfigurationFragment, kind: Int, ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        confirmSpeed(host, kind, many = ids.size > 1) { TestSessionClient.startProfiles(kind, ids, groupId) }
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
                confirmService(this, count) { TestSessionClient.startGroup(TestSpec.KIND_SERVICE, groupId) }
            }
        }
    }

    /** Service Check of the selected profiles (or one). */
    fun startServiceProfiles(host: ConfigurationFragment, ids: List<Long>, groupId: Long) {
        if (ids.isEmpty()) return
        confirmService(host, ids.size) { TestSessionClient.startProfiles(TestSpec.KIND_SERVICE, ids, groupId) }
    }

    /** startServiceChecks (mainwindow_setup.cpp): refused above the runner's limit, asked first above 20 profiles. */
    private fun confirmService(host: ConfigurationFragment, count: Int, start: () -> Boolean) {
        val context = host.requireContext()
        when {
            count > TestSpec.SERVICE_MAX_PROFILES -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.service_check)
                .setMessage(context.getString(R.string.service_check_too_many, TestSpec.SERVICE_MAX_PROFILES))
                .setPositiveButton(android.R.string.ok, null)
                .show()

            count > TestSpec.SERVICE_CONFIRM_ABOVE -> MaterialAlertDialogBuilder(context)
                .setTitle(R.string.service_check)
                .setMessage(context.getString(R.string.service_check_confirm, count))
                .setPositiveButton(R.string.profiles_test_start) { _, _ -> report(host, start()) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

            else -> report(host, start())
        }
    }

    private fun report(host: ConfigurationFragment, started: Boolean) {
        if (!started) host.snackbar(R.string.profiles_test_busy).show()
    }
}
