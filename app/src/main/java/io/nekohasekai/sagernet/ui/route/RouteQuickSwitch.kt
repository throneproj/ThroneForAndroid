package io.nekohasekai.sagernet.ui.route

import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.settings.WarpSettingsFragment
import io.nekohasekai.sagernet.ui.warp.WarpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The routing-profile quick switch (the desktop Routing menu's profile list and its "Enable Warp" toggle). */
object RouteQuickSwitch {

    /** A single-choice list of the route profiles; picking another one makes it current. */
    fun show(fragment: Fragment, onSwitched: ((RouteProfile) -> Unit)? = null) {
        fragment.lifecycleScope.launch {
            val (profiles, currentId, warp) = withContext(Dispatchers.IO) {
                Triple(RouteManager.usable(), RouteManager.current().id, DataStore.enableWarp)
            }
            val context = fragment.context ?: return@launch
            val names = Array<CharSequence>(profiles.size) { profiles[it].name.ifBlank { "#" + profiles[it].id } }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.route_profile_quick_switch)
                .setSingleChoiceItems(names, profiles.indexOfFirst { it.id == currentId }) { dialog, which ->
                    dialog.dismiss()
                    val picked = profiles[which]
                    if (picked.id == currentId) return@setSingleChoiceItems
                    fragment.lifecycleScope.launch {
                        val kept = switchTo(picked.id)
                        fragment.snackbar(switchedMessage(fragment, names[which], kept)).show()
                        onSwitched?.invoke(picked)
                    }
                }
                .setNeutralButton(if (warp) R.string.warp_disable else R.string.warp_enable) { _, _ ->
                    setWarpEnabled(fragment, !warp)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /**
     * D15: current_route_id is global; a running service reloads at once, as the desktop restarts the profile, unless
     * the running profile's group keeps its own routing profile (ConnectionModes.cpp:220-226). Returns that group's
     * name, null when it does not apply.
     */
    suspend fun switchTo(id: Long): String? = withContext(Dispatchers.IO) {
        DataStore.currentRouteId = id
        if (!DataStore.serviceState.started) return@withContext null
        RouteManager.groupKeepingRunningRoute()?.let { return@withContext it.displayName() }
        SagerNet.reloadService()
        null
    }

    /** route_switched, followed by why the running profile did not follow when a group [kept] its own. */
    fun switchedMessage(fragment: Fragment, name: CharSequence, kept: String?): String {
        val switched = fragment.getString(R.string.route_switched, name)
        return if (kept == null) switched else switched + "\n" + fragment.getString(R.string.route_group_keeps_own, kept)
    }

    /** "Enable Warp" (mainwindow_setup.cpp:893-903): saves enable_warp and reloads a running service. */
    fun setWarpEnabled(fragment: Fragment, enabled: Boolean) {
        DataStore.enableWarp = enabled
        if (DataStore.serviceState.started) SagerNet.reloadService()
        val activity = fragment.activity as? MainActivity
        if (enabled && !WarpClient.isGenerated()) {
            fragment.snackbar(fragment.getString(R.string.warp_not_generated)).apply {
                if (activity != null) setAction(R.string.settings) {
                    val screen = WarpSettingsFragment::class.java.name
                    activity.openSettingsScreen(screen, activity.getString(R.string.warp_settings))
                }
            }.show()
        } else {
            fragment.snackbar(fragment.getString(if (enabled) R.string.warp_enabled else R.string.warp_disabled)).show()
        }
    }
}
