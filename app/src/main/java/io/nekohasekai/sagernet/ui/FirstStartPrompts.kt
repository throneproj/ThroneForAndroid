package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.settings.GeneralSettingsFragment
import io.nekohasekai.sagernet.utils.BatteryOptimizations

/**
 * The first start: the notification permission (13+), then the battery-optimization exemption, each asked once.
 * [shown] is set before the battery prompt shows, so a kill or a rotation never asks twice. Create it while the
 * activity initialises.
 */
class FirstStartPrompts(private val activity: MainActivity) {

    private val notificationPermission = activity.registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askBattery() }

    private val batteryRequest = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // The request reports nothing useful: ask the system what was decided.
        if (!BatteryOptimizations.isIgnoring(activity)) showLaterHint()
    }

    /**
     * Kept out of the database: a reinstall restores it from the cloud backup (only sager_net.db is backed up), and
     * the prompts must run again there. An upgrade carries the old database flag over; a fresh install that got the
     * database back from a backup (first install time == last update time) does not.
     */
    private val prefs by lazy { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var shown: Boolean
        get() {
            if (prefs.contains(KEY_SHOWN)) return prefs.getBoolean(KEY_SHOWN, false)
            val info = try {
                activity.packageManager.getPackageInfo(activity.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val upgraded = info != null && info.lastUpdateTime > info.firstInstallTime
            return (DataStore.batteryPromptShown && upgraded).also { shown = it }
        }
        set(value) = prefs.edit().putBoolean(KEY_SHOWN, value).apply()

    fun start() {
        if (shown) return
        if (Build.VERSION.SDK_INT >= 33 && !SagerNet.isTv &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            if (activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.notification_permission)
                    .setMessage(R.string.notification_permission_rationale)
                    .setPositiveButton(R.string.wifi_permission_continue) { _, _ -> requestNotifications() }
                    .setNegativeButton(R.string.not_now) { _, _ -> askBattery() }
                    .setOnCancelListener { askBattery() }
                    .show()
            } else {
                requestNotifications()
            }
        } else {
            askBattery()
        }
    }

    private fun requestNotifications() {
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (_: ActivityNotFoundException) {
            askBattery()
        }
    }

    private fun askBattery() {
        if (shown) return
        shown = true
        if (BatteryOptimizations.isIgnoring(activity)) return
        // No request screen (TVs, trimmed ROMs): nothing to offer.
        val intent = BatteryOptimizations.requestIntent(activity) ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.battery_prompt_title)
            .setMessage(R.string.battery_prompt_message)
            .setPositiveButton(R.string.battery_prompt_allow) { _, _ ->
                try {
                    batteryRequest.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    showLaterHint()
                } catch (_: SecurityException) {
                    showLaterHint()
                }
            }
            .setNegativeButton(R.string.not_now) { _, _ -> showLaterHint() }
            .setOnCancelListener { showLaterHint() }
            .show()
    }

    private fun showLaterHint() {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.snackbar(R.string.battery_prompt_later).setAction(R.string.settings) {
            activity.openSettingsScreen(
                GeneralSettingsFragment::class.java.name, activity.getString(R.string.settings_general)
            )
        }.show()
    }

    private companion object {
        const val PREFS = "first_start"
        const val KEY_SHOWN = "prompts_shown"
    }
}
