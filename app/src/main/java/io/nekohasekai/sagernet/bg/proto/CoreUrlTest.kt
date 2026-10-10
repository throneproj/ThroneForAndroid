package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.test.TestTags
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.completeWith
import io.nekohasekai.sagernet.outbound.types.Chain
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import io.throneproj.mobile.URLTestHandler
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The URL test through the running instance's `proxy` outbound (the stats bar's ISagerNetService.urlTest). With
 * [vpnExit] a failure whose tunnel is up is [ProxyEntity.LATENCY_CONNECT_ONLY] (url_test_current,
 * mainwindow_view.cpp:405-433).
 */
suspend fun urlTestCurrent(instance: Instance, core: CoreConfig, url: String, timeoutMs: Int, vpnExit: Boolean): Int {
    val request = TestRequest().apply {
        testCurrent = true
        this.url = url
        this.timeoutMs = timeoutMs
        maxConcurrency = 1
        core.outboundTags.forEach(::addOutboundTag)
        if (vpnExit) addVPNEndpointTag(CoreConfig.TAG_PROXY)
    }
    val latency = AtomicInteger(-1)
    val failure = AtomicReference<String?>(null)
    val tunnelUp = AtomicBoolean(false)
    TestTags.holding(listOf(TestTags.ofCurrent(core.coreConfig), TestTags.CURRENT)) {
        suspendCancellableCoroutine<Unit> { continuation ->
            Mobile.startURLTest(instance, CoreRuntime.platform, request, object : URLTestHandler {
                override fun onResult(tag: String?, latencyMs: Int, error: String?) {
                    if (error.isNullOrEmpty()) latency.set(latencyMs) else failure.set(error)
                }

                override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) {
                    tunnelUp.set(connected)
                }

                override fun onDone() {
                    continuation.completeWith(Result.success(Unit))
                }
            })
        }
    }
    val error = failure.get()
    if (error != null && tunnelUp.get() && !ProxyEntity.isTestAborted(error)) return ProxyEntity.LATENCY_CONNECT_ONLY
    val result = latency.get()
    if (error != null || result < 0) throw IllegalStateException(error ?: "url test produced no result")
    return result
}

/**
 * vpn_exit_endpoint (mainwindow_view.cpp:360-371): [profile] leaves through an OpenVPN or OpenConnect endpoint, a
 * chain through its last hop, so a failed test of its running instance asks for the tunnel verdict of `proxy`.
 */
internal fun exitsThroughVpn(profile: ProxyEntity?): Boolean {
    val exit = if (profile?.isChain() == true) {
        (profile.outbound as? Chain)?.list?.lastOrNull()?.let(ProfileManager::getProfile)
    } else {
        profile
    }
    return exit?.isVpnProfile() == true
}

/** What a probe box is started with: the config, its Xray half and the tags to measure. */
internal fun CoreConfig.applyTo(request: TestRequest) {
    request.coreConfig = coreConfig
    request.needXray = needXray
    request.xrayConfig = xrayConfig ?: ""
    request.xrayOutboundDNSStrategy = xrayDnsStrategy
    xrayFullConfigs.forEach(request::addXrayFullConfig)
    outboundTags.forEach(request::addOutboundTag)
}
