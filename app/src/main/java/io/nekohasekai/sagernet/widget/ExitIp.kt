package io.nekohasekai.sagernet.widget

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appHttpClient
import io.nekohasekai.sagernet.ktx.appUserAgent
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.json.JsonInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.Proxy

/**
 * runningCountryInfo (Profile.h:62): the running connection's exit as ip-api.com sees it, looked up once it connects
 * (mainwindow_profile_lifecycle.cpp:384-396) and kept in memory only. Main thread.
 */
object ExitIp {

    class Info(val ip: String, val countryCode: String, val country: String, val city: String)

    private const val LOOKUP_URL = "http://ip-api.com/json/"
    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_BYTES = 64 * 1024L

    var info: Info? = null
        private set

    /** The stats bar on screen; told about every change of [info]. */
    var listener: (() -> Unit)? = null

    private var generation = 0
    private var job: Job? = null

    /** Once per connection: nothing while a result is held or a lookup runs. */
    fun ensure() {
        if (info == null && job == null) refresh()
    }

    fun refresh() {
        if (DataStore.serviceState != BaseService.State.Connected) return clear()
        job?.cancel()
        val token = ++generation
        job = runOnMainDispatcher {
            val result = withContext(Dispatchers.IO) {
                try {
                    lookup()
                } catch (e: Exception) {
                    Logs.w("exit IP lookup: ${e.readableMessage}")
                    null
                }
            }
            if (token != generation) return@runOnMainDispatcher
            job = null
            update(result)
        }
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        update(null)
    }

    private fun update(value: Info?) {
        if (info === value) return
        info = value
        listener?.invoke()
    }

    /** Blocking; null when the running connection cannot carry the lookup. */
    private fun lookup(): Info? {
        // "Only route advertised network" rejects this probe.
        if (exitsThroughVpn(ProfileManager.getProfile(DataStore.currentProfile))) return null
        val client = client() ?: return null
        val request = Request.Builder().url(LOOKUP_URL).header("User-Agent", appUserAgent()).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val json = JsonInput.parseObjectOrNull(response.peekBody(MAX_BYTES).string()) ?: return null
            val ip = json.string("query").ifEmpty { return null }
            return Info(ip, json.string("countryCode"), json.string("country"), json.string("city"))
        }
    }

    /**
     * The desktop asks through the mixed inbound (HttpGet useProxy, HTTPRequestHelper.cpp:36), which the core routes in
     * either service mode.
     * A VPN without that inbound carries the app's own traffic only while its per-app lists leave this app inside: the
     * request is then bound to the VPN network, and is skipped otherwise rather than sent around the tunnel.
     */
    private fun client(): OkHttpClient? {
        if (!DataStore.mixedInboundDisabled) return appHttpClient(viaProxy = true, timeoutSeconds = TIMEOUT_SECONDS)
        val network = tunnelNetwork() ?: return null
        return appHttpClient(viaProxy = false, timeoutSeconds = TIMEOUT_SECONDS).newBuilder()
            .proxy(Proxy.NO_PROXY)
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .build()
    }

    /** The default network of this app's UID when it is the VPN. */
    private fun tunnelNetwork(): Network? {
        val network = SagerNet.connectivity.activeNetwork ?: return null
        val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
    }
}
