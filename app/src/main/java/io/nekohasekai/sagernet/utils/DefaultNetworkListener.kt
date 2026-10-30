package io.nekohasekai.sagernet.utils

import android.annotation.TargetApi
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.runBlocking
import java.net.UnknownHostException

object DefaultNetworkListener {
    private sealed class NetworkMessage {
        class Start(val key: Any, val listener: (Network?) -> Unit) : NetworkMessage() {
            val processed = CompletableDeferred<Unit>()
        }
        class Get : NetworkMessage() {
            val response = CompletableDeferred<Network>()
        }

        class Stop(val key: Any) : NetworkMessage()

        class Put(val network: Network) : NetworkMessage()
        class Update(val network: Network) : NetworkMessage()
        class Lost(val network: Network) : NetworkMessage()
    }

    private val networkActor = GlobalScope.actor<NetworkMessage>(Dispatchers.Unconfined) {
        val listeners = mutableMapOf<Any, (Network?) -> Unit>()
        var network: Network? = null
        val pendingRequests = arrayListOf<NetworkMessage.Get>()
        for (message in channel) when (message) {
            is NetworkMessage.Start -> {
                try {
                    if (listeners.isEmpty()) register()
                    listeners[message.key] = message.listener
                    if (network != null) message.listener(network)
                    message.processed.complete(Unit)
                } catch (error: Throwable) {
                    val removed = listeners.remove(message.key) != null
                    if (removed && listeners.isEmpty()) runCatching { unregister() }
                    message.processed.completeExceptionally(error)
                }
            }
            is NetworkMessage.Get -> {
                check(listeners.isNotEmpty()) { "Getting network without any listeners is not supported" }
                if (network == null) pendingRequests += message else message.response.complete(
                    network
                )
            }
            is NetworkMessage.Stop -> if (listeners.isNotEmpty() && // was not empty
                listeners.remove(message.key) != null && listeners.isEmpty()
            ) {
                network = null
                unregister()
            }

            is NetworkMessage.Put -> {
                // Diagnostics: network event timeline for night / airplane mode (compare with Go UpdateDefaultInterface)
                // elapsed covers every listener, which run synchronously (the Unconfined actor runs on the callback thread)
                val start = SystemClock.elapsedRealtime()
                network = message.network
                pendingRequests.forEach { it.response.complete(message.network) }
                pendingRequests.clear()
                listeners.values.forEach { it(network) }
                Logs.i("DefaultNetworkListener Put network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
            is NetworkMessage.Update -> if (network == message.network) {
                // This event storms (onCapabilitiesChanged) while switching networks or on a flaky signal: debug level
                val start = SystemClock.elapsedRealtime()
                listeners.values.forEach {
                    it(
                        network
                    )
                }
                Logs.d("DefaultNetworkListener Update network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
            is NetworkMessage.Lost -> if (network == message.network) {
                val start = SystemClock.elapsedRealtime()
                network = null
                listeners.values.forEach { it(null) }
                Logs.i("DefaultNetworkListener Lost network=${message.network} listeners=${listeners.size} elapsed=${SystemClock.elapsedRealtime() - start}ms thread=${Thread.currentThread().name}")
            }
        }
    }

    suspend fun start(key: Any, listener: (Network?) -> Unit) {
        val message = NetworkMessage.Start(key, listener)
        networkActor.send(message)
        // send only queues the message; the Start branch and the cached network's first callback may still be pending.
        // Wait for processed, or a test box created meanwhile may get default=nil from the Go monitor
        // and its first dial fails before updateDefaultInterface(wlan0, index).
        message.processed.await()
    }

    suspend fun get() = if (fallback) @TargetApi(23) {
        SagerNet.connectivity.activeNetwork
            ?: throw UnknownHostException() // failed to listen, return current if available
    } else NetworkMessage.Get().run {
        networkActor.send(this)
        response.await()
    }

    suspend fun stop(key: Any) = networkActor.send(NetworkMessage.Stop(key))

    // NB: below API 26 this runs on ConnectivityThread; 26+ dispatches through the callbackHandler given to register
    // onto a dedicated worker thread (dispatching to the main thread through mainHandler once blocked the UI
    // for seconds with several test listeners and onCapabilitiesChanged storms)
    private object Callback : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Logs.d("DefaultNetworkListener onAvailable enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Put(network)) }
        }

        override fun onCapabilitiesChanged(
            network: Network, networkCapabilities: NetworkCapabilities
        ) { // it's a good idea to refresh capabilities
            // Storm source: debug per event (info would flood the log)
            Logs.d("DefaultNetworkListener onCapabilitiesChanged enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLinkPropertiesChanged(
            network: Network, linkProperties: LinkProperties
        ) {
            Logs.d("DefaultNetworkListener onLinkPropertiesChanged enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Update(network)) }
        }

        override fun onLost(network: Network) {
            Logs.d("DefaultNetworkListener onLost enter network=$network thread=${Thread.currentThread().name}")
            runBlocking { networkActor.send(NetworkMessage.Lost(network)) }
        }
    }

    private var fallback = false
    private val request = NetworkRequest.Builder().apply {
        addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        if (Build.VERSION.SDK_INT == 23) {  // workarounds for OEM bugs
            removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            removeCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
        }
    }.build()
    private val callbackThread = HandlerThread("DefaultNetworkListener").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    /**
     * Unfortunately registerDefaultNetworkCallback is going to return VPN interface since Android P DP1:
     * https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately, we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    private fun register() {
        try {
            fallback = false
            when (Build.VERSION.SDK_INT) {
                in 31..Int.MAX_VALUE -> @TargetApi(31) {
                    SagerNet.connectivity.registerBestMatchingNetworkCallback(
                        request, Callback, callbackHandler
                    )
                }
                in 28 until 31 -> @TargetApi(28) {  // we want REQUEST here instead of LISTEN
                    SagerNet.connectivity.requestNetwork(request, Callback, callbackHandler)
                }
                in 26 until 28 -> @TargetApi(26) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback, callbackHandler)
                }
                in 24 until 26 -> @TargetApi(24) {
                    SagerNet.connectivity.registerDefaultNetworkCallback(Callback)
                }
                else -> {
                    SagerNet.connectivity.requestNetwork(request, Callback)
                    // known bug on API 23: https://stackoverflow.com/a/33509180/2245107
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
            fallback = true
        }
    }

    private fun unregister() = SagerNet.connectivity.unregisterNetworkCallback(Callback)
}
