package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.appwidget.Widgets
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorRuntime
import io.nekohasekai.sagernet.bg.proto.LocalDnsFailedException
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.bg.proto.urlTestCurrent
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileOrder
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.PlatformNotifications
import io.nekohasekai.sagernet.utils.WifiStateAccess
import io.throneproj.mobile.Instance
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var wifiMonitor: WifiStateAccess.Monitor? = null

        // Pause and wake are JNI calls into the core: run them in order and off the main thread.
        private val idleLock = Mutex()

        @Volatile
        private var pausedBox: Instance? = null

        /** [box] is paused for device idle. */
        fun idlePaused(box: Instance): Boolean = pausedBox === box

        val receiver = broadcastReceiver { ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats()
                Action.RELOAD -> service.reload()
                Action.CLOSE -> service.stopRunner()
                Action.SWITCH_NEXT -> service.switchRelative(1)
                Action.SWITCH_PREVIOUS -> service.switchRelative(-1)
                Action.SWITCH_PROFILE -> service.switchProfile(intent.getLongExtra(Action.EXTRA_PROFILE_ID, 0L))
                Action.REFRESH_WIFI_STATE -> refreshWifiState(ctx)
                Action.AUTO_SELECTOR_AUTOMATIC -> AutoSelectorRuntime.releasePin(intent.getLongExtra(Action.EXTRA_PROFILE_ID, 0L))
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> applyIdleMode()

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    proxy?.boxOrNull?.resetNetwork()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
            Widgets.push(service as Context)
        }

        /** Reads the idle mode when the job runs, so the last of several quick events always wins. */
        private fun applyIdleMode() = runOnDefaultDispatcher {
            idleLock.withLock {
                val box = proxy?.boxOrNull ?: return@withLock
                if (SagerNet.power.isDeviceIdleMode) {
                    if (pausedBox !== box) {
                        box.pause()
                        pausedBox = box
                    }
                } else if (pausedBox === box) {
                    pausedBox = null
                    box.wake()
                    if (DataStore.configurationStore.getBoolean(Key.WAKE_RESET_CONNECTIONS, true)) {
                        box.resetNetwork()
                    }
                }
            }
        }

        private fun refreshWifiState(ctx: Context) = runOnDefaultDispatcher {
            val box = proxy?.boxOrNull ?: return@runOnDefaultDispatcher
            if (!box.needWIFIState()) return@runOnDefaultDispatcher
            box.updateWIFIState()
            if (WifiStateAccess.status(ctx) == WifiStateAccess.Status.OK) {
                PlatformNotifications.cancelWifiRulesInactive(ctx)
            }
        }
    }

    class Binder(private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            val proxy = data?.proxy?.takeIf { it.isInitialized() } ?: error("core not started")
            try {
                return runBlocking {
                    urlTestCurrent(
                        proxy.box, proxy.core, DataStore.testUrl, DataStore.urlTestTimeoutMs,
                        exitsThroughVpn(proxy.profile),
                    )
                }
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        /**
         * Instance.updateRuleSets (core/internal/rulesets UpdateAll): every remote rule-set of the running instance,
         * at most 5 at a time within 60 s. Arrives on a binder thread, so blocking that long is fine.
         */
        override fun updateRuleSets(): String = try {
            val box = data?.takeIf { it.state.connected }?.proxy?.boxOrNull
            if (box == null) {
                ruleSetUpdateJson(0, "not running")
            } else {
                val update = box.updateRuleSets()
                ruleSetUpdateJson(update.updated, update.error ?: "")
            }
        } catch (e: Throwable) {
            ruleSetUpdateJson(0, e.readableMessage)
        }

        private fun ruleSetUpdateJson(updated: Int, error: String): String =
            jsonObjectOf("updated" to updated, "error" to error).toCompact()

        override fun autoSelectorStatus(withMembers: Boolean): String = AutoSelectorRuntime.statusJson(withMembers)

        override fun autoSelectorRecheck() = AutoSelectorRuntime.recheck()

        override fun autoSelectorSelect(memberProfileId: Long) = AutoSelectorRuntime.select(memberProfileId)

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        override fun close() {
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> AutoSelectorRuntime.restart(this) { stopRunner(true) }
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        /** Runs [id] instead of the current profile, staying in the foreground; the running profile is left alone. */
        fun switchProfile(id: Long) {
            if (id <= 0L) return
            DataStore.selectedProxy = id
            val s = data.state
            if (s.canStop && data.proxy?.profile?.id == id) return
            runOnDefaultDispatcher {
                data.binder.broadcast { it.cbSelectorUpdate(id) }
            }
            // While stopping, a restart in flight starts whatever is selected once the old core is gone.
            if (s.canStop) AutoSelectorRuntime.restart(this) { stopRunner(true) } else Widgets.push(this as Context)
        }

        /** The neighbour [step] away in the group of the selected profile, wrapping around. */
        fun switchRelative(step: Int) {
            val current = DataStore.selectedProxy.takeIf { it > 0L } ?: data.proxy?.profile?.id ?: return
            val target = ProfileOrder.neighbour(current, step) ?: return
            switchProfile(target.id)
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses(): Throwable? {
            val proxy = data.proxy
            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            var cleanupError: Throwable? = null
            fun recordCleanupFailure(stage: String, error: Throwable) {
                if (cleanupError == null) {
                    cleanupError = error
                } else if (cleanupError !== error) {
                    cleanupError?.addSuppressed(error)
                }
                Logs.w(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=$stage failed " +
                        "type=${error.javaClass.name} message=${error.message}"
                )
            }
            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill begin"
            )
            try {
                data.wifiMonitor?.stop()
            } catch (error: Throwable) {
                recordCleanupFailure("wifi-monitor-stop", error)
            } finally {
                data.wifiMonitor = null
            }

            try {
                proxy?.close()
                Logs.i(
                    "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                        "profileId=${proxy?.profile?.id ?: -1L} stage=proxy-close success"
                )
            } catch (error: Throwable) {
                recordCleanupFailure("proxy-close", error)
            }

            try {
                DefaultNetworkListener.stop(this)
            } catch (error: Throwable) {
                recordCleanupFailure("network-listener-stop", error)
            }

            Logs.i(
                "ServiceLifecycleTrace serviceId=$serviceId proxyId=$proxyId " +
                    "profileId=${proxy?.profile?.id ?: -1L} stage=kill done " +
                    "hasCleanupError=${cleanupError != null}"
            )
            return cleanupError
        }

        /**
         * A restart keeps the service, its notification and the receiver: the new core starts in place, so a switch
         * from the notification or a widget never needs another foreground-service start from the background.
         */
        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null

            val serviceId = Integer.toHexString(System.identityHashCode(data))
            val proxy = data.proxy
            val proxyId = proxy?.let { Integer.toHexString(System.identityHashCode(it)) } ?: "none"
            val caller = Thread.currentThread().stackTrace.firstOrNull { frame ->
                frame.className != Thread::class.java.name && frame.methodName != "stopRunner"
            }?.let { frame -> "${frame.className}.${frame.methodName}:${frame.lineNumber}" }
                ?: "unknown"
            Logs.i(
                "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId restart=$restart " +
                    "state=${data.state} profileId=${proxy?.profile?.id ?: -1L} " +
                    "hasMessage=${msg != null} caller=$caller"
            )
            if (data.state == State.Stopping) {
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=ignored-already-stopping"
                )
                return
            }
            this as Service

            data.changeState(State.Stopping)
            val originalMessage = msg

            runOnMainDispatcher {
                var cleanupError: Throwable? = null
                fun recordCleanupFailure(stage: String, error: Throwable) {
                    if (cleanupError == null) {
                        cleanupError = error
                    } else if (cleanupError !== error) {
                        cleanupError?.addSuppressed(error)
                    }
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=$stage failed type=${error.javaClass.name} " +
                            "message=${error.message}"
                    )
                }

                try {
                    data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                } catch (error: Throwable) {
                    recordCleanupFailure("connecting-job-cancel", error)
                } finally {
                    data.connectingJob = null
                }

                val keepNotification = restart && data.notification != null
                if (keepNotification) {
                    try {
                        data.notification?.postNotificationTitle(getString(R.string.notification_switching))
                    } catch (error: Throwable) {
                        recordCleanupFailure("notification-title", error)
                    }
                } else {
                    try {
                        data.notification?.destroy()
                    } catch (error: Throwable) {
                        recordCleanupFailure("notification-destroy", error)
                    } finally {
                        data.notification = null
                    }
                }

                try {
                    killProcesses()?.let { recordCleanupFailure("process-cleanup", it) }
                } catch (error: Throwable) {
                    recordCleanupFailure("process-cleanup-boundary", error)
                }

                if (!keepNotification) {
                    try {
                        if (data.closeReceiverRegistered) {
                            unregisterReceiver(data.receiver)
                        }
                    } catch (error: Throwable) {
                        recordCleanupFailure("receiver-unregister", error)
                    } finally {
                        data.closeReceiverRegistered = false
                    }
                    PlatformNotifications.cancelWifiRulesInactive(this@Interface)
                }
                data.proxy = null

                cleanupError?.let { error ->
                    Logs.w(
                        "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                            "stage=cleanup failed type=${error.javaClass.name} " +
                            "message=${error.message} suppressed=${error.suppressed.size} " +
                            "originalMessagePreserved=${originalMessage != null}"
                    )
                }

                try {
                    data.changeState(State.Stopped, originalMessage)
                } catch (error: Throwable) {
                    recordCleanupFailure("state-stopped", error)
                }
                Logs.i(
                    "ServiceStopTrace serviceId=$serviceId proxyId=$proxyId " +
                        "stage=stopped restart=$restart hasCleanupError=${cleanupError != null}"
                )

                try {
                    when {
                        keepNotification -> startProxy()
                        restart -> startRunner()
                        else -> stopSelf() // stop the service if nothing has bound to it
                    }
                } catch (error: Throwable) {
                    recordCleanupFailure("service-finish", error)
                    if (keepNotification) {
                        failRunner("${getString(R.string.service_failed)} ${error.readableMessage}")
                    }
                }
            }
        }

        /**
         * A start that failed: MainActivity keeps showing [message] until the next start, also on a later visit, and
         * offers the DNS settings with it when [dnsSettings].
         */
        fun failRunner(message: String, dnsSettings: Boolean = false) {
            if (data.state != State.Stopping) {
                DataStore.serviceError = message
                DataStore.serviceErrorDns = dnsSettings
            }
            stopRunner(false, message)
        }

        fun persistStats() {
            // TODO NEW save app stats?
        }

        // networks
        var upstreamInterfaceName: String?

        suspend fun preInit() {
            // Tracks the underlying network for VpnService.setUnderlyingNetworks only; the
            // "reset on network change" switch is applied by NativeInterface's monitor callback.
            DefaultNetworkListener.start(this) { network ->
                if (network == null) return@start
                SagerNet.connectivity.getLinkProperties(network)?.also { link ->
                    SagerNet.underlyingNetwork = network
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        Logs.d("Network changed: $oldName -> ${link.interfaceName}")
                        upstreamInterfaceName = link.interfaceName
                    }
                }
            }
        }

        suspend fun lateInit() {
            data.notification?.postConnected()
        }

        /** Wi-Fi rules (route, DNS or rule-set ones) need location access and a fresh Wi-Fi state on roaming. */
        fun watchWifiRules(proxy: ProxyInstance) {
            this as Context
            val box = proxy.boxOrNull ?: return
            if (!box.needWIFIState()) {
                PlatformNotifications.cancelWifiRulesInactive(this)
                return
            }
            data.wifiMonitor = WifiStateAccess.Monitor(this) { box.updateWIFIState() }.also { it.start() }
            if (WifiStateAccess.status(this) == WifiStateAccess.Status.OK) {
                PlatformNotifications.cancelWifiRulesInactive(this)
            } else {
                PlatformNotifications.wifiRulesInactive(this)
            }
        }

        /** Always-on VPN starts the service by itself; without a profile it can only explain why nothing connects. */
        fun onNoProfile() {}

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            startProxy()
            return Service.START_NOT_STICKY
        }

        /** Starts the selected profile; the notification and the receiver of a restart are reused. */
        fun startProxy() {
            DataStore.baseService = this
            val data = data
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            this as Context
            if (profile == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                if (data.notification == null) data.notification = createNotification("")
                onNoProfile()
                stopRunner(false, getString(R.string.profile_empty))
                return
            }
            PlatformNotifications.cancelAlwaysOnNoProfile(this)

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.rememberEnable
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    addAction(Action.SWITCH_NEXT)
                    addAction(Action.SWITCH_PREVIOUS)
                    addAction(Action.SWITCH_PROFILE)
                    addAction(Action.REFRESH_WIFI_STATE)
                    addAction(Action.AUTO_SELECTOR_AUTOMATIC)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null,
                        Context.RECEIVER_EXPORTED
                    )
                } else {
                    registerReceiver(
                        data.receiver,
                        filter,
                        "$packageName.SERVICE",
                        null
                    )
                }
                data.closeReceiverRegistered = true
            }

            if (DataStore.serviceError.isNotEmpty()) {
                DataStore.serviceError = ""
                DataStore.serviceErrorDns = false
            }
            data.changeState(State.Connecting)
            // startForeground before anything can stop the service (see the link above).
            val title = ServiceNotification.genTitle(profile)
            val notification = data.notification
            if (notification == null) {
                data.notification = createNotification(title)
            } else {
                runOnMainDispatcher { notification.refresh(title) }
            }
            data.connectingJob = CoroutineScope(Dispatchers.Main).launch {
                try {
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    startProcesses()
                    data.changeState(State.Connected)
                    runCatching { watchWifiRules(proxy) }.onFailure { Logs.w(it) }

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    failRunner(getString(R.string.invalid_server))
                } catch (exc: LocalDnsFailedException) {
                    failRunner(
                        if (exc.servers.isEmpty()) getString(R.string.local_dns_failed_system)
                        else getString(R.string.local_dns_failed, exc.servers),
                        dnsSettings = true,
                    )
                } catch (exc: Throwable) {
                    // gomobile surfaces Go errors as go.Universe$proxyerror: message only, no stack worth logging
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    failRunner("${getString(R.string.service_failed)} ${exc.readableMessage}")
                } finally {
                    data.connectingJob = null
                }
            }
        }
    }

}
