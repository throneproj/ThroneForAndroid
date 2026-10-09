package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        // notificationActions entries; the template shows at most three buttons, in this order.
        const val ACTION_STOP = "stop"
        const val ACTION_PREVIOUS = "previous"
        const val ACTION_NEXT = "next"
        const val ACTION_SWITCH = "switch"
        const val ACTION_RESET = "reset"
        val DEFAULT_ACTIONS = listOf(ACTION_STOP, ACTION_NEXT, ACTION_SWITCH)
        private val ACTION_ORDER = listOf(ACTION_STOP, ACTION_PREVIOUS, ACTION_NEXT, ACTION_SWITCH, ACTION_RESET)

        fun notificationActions(): List<String> {
            val stored = DataStore.configurationStore.getStringList(Key.NOTIFICATION_ACTIONS) ?: return DEFAULT_ACTIONS
            return ACTION_ORDER.filter { it in stored }.take(3)
        }

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                SagerDatabase.groupDao.getById(ent.groupId)?.displayName() else null
            return if (gn == null) ent.displayName() else "[$gn] ${ent.displayName()}"
        }
    }

    var listenPostSpeed = SagerNet.power.isInteractive

    /** Whether the screen is on; the traffic looper stops polling the core while it is off. */
    val screenOn = MutableStateFlow(SagerNet.power.isInteractive)

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        useBuilder {
            if (showDirectSpeed) {
                val speedDetail = (service as Context).getString(
                    R.string.speed_detail, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.txRateDirect)
                    ), service.getString(
                        R.string.speed,
                        Formatter.formatFileSize(service, stats.rxRateDirect)
                    )
                )
                it.setStyle(NotificationCompat.BigTextStyle().bigText(speedDetail))
                it.setContentText(speedDetail)
            } else {
                val speedSimple = (service as Context).getString(
                    R.string.traffic, service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.txRateProxy)
                    ), service.getString(
                        R.string.speed, Formatter.formatFileSize(service, stats.rxRateProxy)
                    )
                )
                it.setContentText(speedSimple)
            }
            it.setSubText(
                service.getString(
                    R.string.traffic,
                    Formatter.formatFileSize(service, stats.txTotal),
                    Formatter.formatFileSize(service, stats.rxTotal)
                )
            )
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        useBuilder {
            it.setContentTitle(newTitle)
        }
        update()
    }

    /** A restart in place: the new profile's title and buttons, and no speed of the old one. */
    suspend fun refresh(newTitle: String) {
        updateActions()
        useBuilder {
            it.setContentTitle(newTitle)
            it.setContentText(null)
            it.setSubText(null)
            it.setStyle(null)
        }
        update()
    }

    /** What a start waits for (an Xray geo asset download) under the title, [percent] -1 while unknown; null clears it. */
    suspend fun postStartProgress(text: String?, percent: Int) {
        useBuilder {
            it.setContentText(text)
            if (text == null) it.setProgress(0, 0, false) else it.setProgress(100, percent.coerceAtLeast(0), percent < 0)
        }
        update()
    }

    suspend fun postConnected() {
        updateActions()
        useBuilder { it.priority = NotificationCompat.PRIORITY_LOW }
        update()
    }

    private val showDirectSpeed = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        builder.color = service.getColorAttr(R.attr.colorPrimary)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        val actions = notificationActions()
        val profileId = service.data.proxy?.profile?.id ?: DataStore.selectedProxy
        val canCycle = (ACTION_NEXT in actions || ACTION_PREVIOUS in actions) && ProfileOrder.canCycle(profileId)
        useBuilder {
            it.clearActions()
            for (action in actions) when (action) {
                ACTION_STOP -> it.addAction(
                    broadcastAction(R.drawable.ic_notification_stop, service.getText(R.string.stop), Action.CLOSE, 1)
                )

                ACTION_PREVIOUS -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_previous, service.getText(R.string.notification_previous),
                        Action.SWITCH_PREVIOUS, 2,
                    )
                )

                ACTION_NEXT -> if (canCycle) it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_next, service.getText(R.string.notification_next),
                        Action.SWITCH_NEXT, 3,
                    )
                )

                ACTION_SWITCH -> it.addAction(
                    NotificationCompat.Action.Builder(
                        R.drawable.ic_notification_switch, service.getText(R.string.notification_switch),
                        PendingIntent.getActivity(
                            service, 4,
                            Intent(service, SwitchActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            flags,
                        )
                    ).setShowsUserInterface(true).build()
                )

                ACTION_RESET -> it.addAction(
                    broadcastAction(
                        R.drawable.ic_notification_reset, service.getText(R.string.reset_connections),
                        Action.RESET_UPSTREAM_CONNECTIONS, 5,
                    )
                )
            }
        }
    }

    private fun broadcastAction(icon: Int, title: CharSequence, action: String, requestCode: Int) =
        NotificationCompat.Action.Builder(
            icon, title, PendingIntent.getBroadcast(
                service as Context, requestCode, Intent(action).setPackage(service.packageName), flags
            )
        ).setShowsUserInterface(false).build()

    override fun onReceive(context: Context, intent: Intent) {
        val on = intent.action == Intent.ACTION_SCREEN_ON
        screenOn.value = on
        listenPostSpeed = on
    }


    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
