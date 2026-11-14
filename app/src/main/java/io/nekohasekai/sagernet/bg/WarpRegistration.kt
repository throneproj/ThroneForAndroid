package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.WarpRegisterRequest

/**
 * :bg side of ICoreService.warpRegister (rpc/warp.go through core/mobile/warp.go). Blocks the binder thread for at
 * most 10 s + 10 s per API host; like the desktop RPC, failures travel in "error" instead of as exceptions.
 */
object WarpRegistration {

    /** Returns sorted-key JSON: {"device_id","endpoint","error","ipv4","ipv6","license","peer_public_key","private_key","reserved","token"}. */
    fun register(
        tunnelType: String,
        proxy: String,
        apiHosts: Array<String>,
        tlsFragment: Boolean,
        tlsRecordFragment: Boolean,
        tlsFragmentFallbackDelay: String,
    ): String = try {
        val request = WarpRegisterRequest()
        request.tunnelType = tunnelType
        request.proxy = proxy
        request.setTLSFragment(tlsFragment)
        request.setTLSRecordFragment(tlsRecordFragment)
        request.setTLSFragmentFallbackDelay(tlsFragmentFallbackDelay)
        for (host in apiHosts) request.addAPIHost(host)
        val identity = Mobile.warpRegister(request)
        jsonObjectOf(
            "device_id" to identity.deviceID.orEmpty(),
            "endpoint" to identity.endpoint.orEmpty(),
            "error" to "",
            "ipv4" to identity.iPv4.orEmpty(),
            "ipv6" to identity.iPv6.orEmpty(),
            "license" to identity.license.orEmpty(),
            "peer_public_key" to identity.peerPublicKey.orEmpty(),
            "private_key" to identity.privateKey.orEmpty(),
            "reserved" to identity.reserved.orEmpty(),
            "token" to identity.token.orEmpty(),
        ).toCompact()
    } catch (e: Throwable) {
        Logs.w(e)
        jsonObjectOf("error" to e.readableMessage).toCompact()
    }
}
