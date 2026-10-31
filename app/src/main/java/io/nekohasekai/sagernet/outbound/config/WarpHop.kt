package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.types.Masque
import io.nekohasekai.sagernet.outbound.types.WireGuard
import io.nekohasekai.sagernet.outbound.types.WireGuardPeer

/** Built-in WARP identities, shared by the main chain and selector members. */
object WarpHop {
    const val PROFILE_ID = -2408L
    const val ENTRY_PROFILE_ID = -2409L

    fun before(settings: GeneratorSettings) = settings.enableWarp && settings.warpChainOrder in listOf("warp-first", "warp-both")
    fun after(settings: GeneratorSettings) = settings.enableWarp && settings.warpChainOrder != "warp-first"

    const val MISSING_ERROR =
        "WARP needs a generated config. Entry and exit mode requires two independent identities. Generate the config in Routing Settings."

    /** Whether the identity of the active warp_mode is incomplete (:559-567). */
    @JvmStatic
    fun missing(settings: GeneratorSettings): Boolean =
        (settings.warpChainOrder == "warp-both" && !validEntry(settings)) || primaryMissing(settings)

    private fun primaryMissing(settings: GeneratorSettings): Boolean =
        if (settings.warpMode == "masque") {
            settings.warpMasquePrivateKey.isEmpty() || settings.warpMasquePeerPublicKey.isEmpty() ||
                settings.warpMasqueEp.isEmpty() || settings.warpMasqueIfcAddrs.isEmpty()
        } else {
            settings.warpPrivateKey.isEmpty() || settings.warpPublicKey.isEmpty() ||
                settings.warpEp.isEmpty() || settings.warpIfcAddrs.isEmpty()
        }

    /** getWarpProfile (:475-524): named "warp", MTU 1280, an endpoint either way. */
    @JvmStatic
    fun outbound(settings: GeneratorSettings, entry: Boolean = false): Outbound {
        val identity = if (entry && settings.warpChainOrder == "warp-both")
            JsonInput.parseObject(settings.warpEntryConfig) else null
        if (settings.warpMode == "masque") {
            val masque = Masque()
            masque.name = "warp"
            val (host, port) = splitEndpoint(settings.warpMasqueEp, 443)
            masque.server = host
            masque.serverPort = port
            masque.private_key = settings.warpMasquePrivateKey
            masque.peer_public_key = settings.warpMasquePeerPublicKey
            masque.address = settings.warpMasqueIfcAddrs.toMutableList()
            if (identity != null) {
                masque.private_key = identity.string("private_key")
                masque.peer_public_key = identity.string("peer_public_key")
                masque.address = identity.array("address").filterIsInstance<String>().toMutableList()
            }
            masque.mtu = 1280
            if (settings.warpMasqueSni.isNotEmpty()) masque.tls.server_name = settings.warpMasqueSni
            when (settings.warpMasqueHttpMode) {
                1 -> {
                    masque.http_version = 3
                    masque.disable_version_fallback = true
                }

                2 -> masque.http_version = 2
            }
            return masque
        }

        val wireGuard = WireGuard()
        wireGuard.name = "warp"
        val (host, port) = splitEndpoint(settings.warpEp, 2408)
        wireGuard.server = host
        wireGuard.serverPort = port
        wireGuard.private_key = settings.warpPrivateKey
        wireGuard.address = settings.warpIfcAddrs.toMutableList()
        wireGuard.peer = WireGuardPeer().apply {
            public_key = settings.warpPublicKey
            address = host
            this.port = port
            reserved = settings.warpReserved.mapTo(ArrayList()) { QtStrings.toInt(it) }
            persistent_keepalive = "10"
        }
        if (identity != null) {
            wireGuard.private_key = identity.string("private_key")
            wireGuard.address = identity.array("address").filterIsInstance<String>().toMutableList()
            val peer = identity.array("peers").firstOrNull() as? JsonObject ?: JsonObject()
            wireGuard.peer.public_key = peer.string("public_key")
            wireGuard.peer.reserved = peer.array("reserved").map { JsonValues.toInt(it) }.toMutableList()
        }
        if (settings.warpMode == "amnezia") {
            wireGuard.enable_amnezia = true
            wireGuard.jc = 4
            wireGuard.jmin = 40
            wireGuard.jmax = 70
            wireGuard.h1 = "1"
            wireGuard.h2 = "2"
            wireGuard.h3 = "3"
            wireGuard.h4 = "4"
            wireGuard.i1 = WarpCamouflage.packets.random()
        }
        wireGuard.mtu = 1280
        return wireGuard
    }

    fun validEntry(settings: GeneratorSettings): Boolean {
        val config = JsonInput.parseObject(settings.warpEntryConfig)
        val masque = settings.warpMode == "masque"
        val key = config.string("private_key").trim()
        val primaryKey = if (masque) settings.warpMasquePrivateKey else settings.warpPrivateKey
        if (config.string("type") != if (masque) "masque" else "wireguard") return false
        if (key.isBlank() || key == primaryKey.trim() || config.array("address").isEmpty()) return false
        return if (masque) config.string("peer_public_key").isNotBlank()
        else (config.array("peers").firstOrNull() as? JsonObject)?.string("public_key")?.isNotBlank() == true
    }

    /** splitWarpEndpoint (:457-473): host, host:port, [v6] or [v6]:port; a bare IPv6 address has no port. */
    @JvmStatic
    fun splitEndpoint(endpoint: String, defaultPort: Int): Pair<String, Int> {
        val ep = endpoint.trim()
        var host = ep
        var portText = ""
        if (ep.startsWith('[')) {
            val close = ep.indexOf(']')
            if (close < 0) return host to defaultPort
            host = ep.substring(1, close)
            if (ep.substring(close + 1).startsWith(':')) portText = ep.substring(close + 2)
        } else if (ep.count { it == ':' } == 1) {
            val separator = ep.lastIndexOf(':')
            host = ep.substring(0, separator)
            portText = ep.substring(separator + 1)
        }
        val parsed = QtStrings.toInt(portText)
        return host to if (parsed in 1..65535) parsed else defaultPort
    }
}
