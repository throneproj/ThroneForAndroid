package io.nekohasekai.sagernet.ui.warp

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.LOCALHOST
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.appRequestsNeedProxy
import io.nekohasekai.sagernet.ktx.appRequestsViaProxy
import io.nekohasekai.sagernet.outbound.config.GeneratorSettings
import io.nekohasekai.sagernet.outbound.config.WarpHop
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Main-process side of the WARP registration (src/configs/sub/warp.cpp); the core call itself runs in :bg. */
object WarpClient {

    const val TUNNEL_WIREGUARD = "wireguard"
    const val TUNNEL_MASQUE = "masque"
    const val DEFAULT_API_HOST = "api.cloudflareclient.com"
    const val TERMS_URL = "https://www.cloudflare.com/application/terms/"

    /** One registration of the requested tunnel type; device id, token and license are not stored (as on the desktop). */
    class Identity(
        val deviceId: String,
        val token: String,
        val license: String,
        val ipv4: String,
        val ipv6: String,
        val privateKey: String,
        val peerPublicKey: String,
        val endpoint: String,
        val reserved: List<String>,
    ) {
        /** `ipv4/32`, `ipv6/128` (dialog_manage_routes.cpp:481-483). */
        val addresses: List<String>
            get() = listOfNotNull(
                ipv4.takeIf { it.isNotEmpty() }?.let { "$it/32" },
                ipv6.takeIf { it.isNotEmpty() }?.let { "$it/128" },
            )

        init {
            require(privateKey.isNotBlank() && peerPublicKey.isNotBlank() && endpoint.isNotBlank() &&
                (ipv4.isNotBlank() || ipv6.isNotBlank())) { "WARP registration returned an incomplete identity" }
        }

        fun toBeanJson(tunnelType: String): String = WarpHop.outbound(GeneratorSettings(
            warpMode = tunnelType,
            warpPrivateKey = privateKey, warpPublicKey = peerPublicKey,
            warpEp = endpoint, warpIfcAddrs = addresses, warpReserved = reserved,
            warpMasquePrivateKey = privateKey, warpMasquePeerPublicKey = peerPublicKey,
            warpMasqueEp = endpoint, warpMasqueIfcAddrs = addresses,
        )).exportToJson().toCompact()

        /** The endpoint split into host (IPv6 unbracketed) and port (edit_wireguard.cpp:44-49); null without a port. */
        fun endpointHostPort(): Pair<String, String>? {
            val sep = endpoint.lastIndexOf(':')
            if (sep <= 0) return null
            var host = endpoint.substring(0, sep)
            if (host.startsWith('[') && host.endsWith(']')) host = host.substring(1, host.length - 1)
            return host to endpoint.substring(sep + 1)
        }
    }

    fun modeName(mode: String): String =
        app.getString(when (mode) {
            TUNNEL_MASQUE -> R.string.warp_mode_masque
            "amnezia" -> R.string.warp_mode_amnezia
            else -> R.string.warp_mode_wireguard
        })

    /** generate.cpp:559-571: the key, endpoint or addresses of the active mode are missing. */
    fun isGenerated(): Boolean = (DataStore.warpChainOrder != "warp-both" || WarpHop.validEntry(GeneratorSettings(
        warpMode = DataStore.warpMode, warpEntryConfig = DataStore.warpEntryConfig,
        warpPrivateKey = DataStore.warpPrivateKey, warpMasquePrivateKey = DataStore.warpMasquePrivateKey,
    ))) && primaryGenerated()

    private fun primaryGenerated(): Boolean = if (DataStore.warpMode == TUNNEL_MASQUE) {
        DataStore.warpMasquePrivateKey.isNotEmpty() && DataStore.warpMasquePeerPublicKey.isNotEmpty() &&
            DataStore.warpMasqueEp.isNotEmpty() && DataStore.warpMasqueIfcAddrs.isNotEmpty()
    } else {
        DataStore.warpPrivateKey.isNotEmpty() && DataStore.warpPublicKey.isNotEmpty() &&
            DataStore.warpEp.isNotEmpty() && DataStore.warpIfcAddrs.isNotEmpty()
    }

    /** normalizeWarpApiHosts (dialog_manage_routes.cpp:38-49). */
    fun normalizeApiHosts(text: String?): List<String> {
        val hosts = ArrayList<String>()
        for (line in text.orEmpty().split('\n')) {
            var host = line.trim().lowercase()
            if (host.startsWith("https://")) host = host.substring(8)
            else if (host.startsWith("http://")) host = host.substring(7)
            val end = host.indexOfFirst { it == '/' || it == '?' || it == '#' }
            if (end >= 0) host = host.substring(0, end)
            if (host.isNotEmpty() && host !in hosts) hosts.add(host)
        }
        return hosts
    }

    /**
     * warpProxy (warp.cpp:10-25) with the Android app-request rule of ktx/Nets.kt httpGet: when app requests use the
     * proxy they go through the mixed inbound, which speaks HTTP; without that inbound a VPN tunnels the request.
     * While no profile runs only net_use_proxy fails, the proxy service mode goes direct. "" is a direct request.
     */
    fun proxy(): String {
        if (!appRequestsViaProxy()) return ""
        if (!DataStore.serviceState.connected) {
            if (appRequestsNeedProxy()) error(app.getString(R.string.warp_proxy_not_started))
            return ""
        }
        if (DataStore.mixedInboundDisabled) return ""
        var host = DataStore.inboundAddress.let { if (it == "::") LOCALHOST else it }
        if (host.contains(':')) host = "[$host]"
        val credentials = if (DataStore.inboundAuth) {
            val user = LinkCodec.percentEncodeAll(DataStore.inboundUser)
            "$user:${LinkCodec.percentEncodeAll(DataStore.inboundPass)}@"
        } else {
            ""
        }
        return "http://$credentials$host:${DataStore.inboundSocksPort}"
    }

    /** RegisterWarp (warp.cpp:28-54) through ICoreService.warpRegister; failures throw with the core's text. */
    suspend fun register(tunnelType: String): Identity = withContext(Dispatchers.IO) {
        val proxy = proxy()
        val hosts = DataStore.warpApiHosts.toTypedArray()
        val raw = try {
            CoreServiceClient.call { it.warpRegister(tunnelType, proxy, hosts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
        val result = raw?.let { JsonInput.parseObjectOrNull(it) }
            ?: error(app.getString(R.string.warp_core_unreachable))
        val coreError = result.string("error")
        if (coreError.isNotEmpty()) error(coreError)
        Identity(
            deviceId = result.string("device_id"),
            token = result.string("token"),
            license = result.string("license"),
            ipv4 = result.string("ipv4"),
            ipv6 = result.string("ipv6"),
            privateKey = result.string("private_key"),
            peerPublicKey = result.string("peer_public_key"),
            endpoint = result.string("endpoint"),
            reserved = result.string("reserved").split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
