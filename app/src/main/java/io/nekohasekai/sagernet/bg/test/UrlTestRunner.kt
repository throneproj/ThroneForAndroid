package io.nekohasekai.sagernet.bg.test

import android.net.Network
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import io.throneproj.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import java.net.Inet6Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * URL latency tests (runUrlProbe, TestRunner.cpp:320-418): results stream as the core reports them, then a failed
 * OpenVPN/OpenConnect profile whose tunnel the core reports up is reported again as Connect OK. A failure while the
 * local network was down or changed is inconclusive: the profile keeps its earlier result.
 */
internal class UrlTestRunner(session: TestSession) : LatencySweep(session) {

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        val request = probe.request().apply {
            url = session.options.url
            timeoutMs = session.options.timeoutMs
            maxConcurrency = session.options.concurrency
        }
        // The probe box closes with the test, so the verdict is asked for up front (TestRunner.cpp:171-176).
        probe.tagsOf(batch.filter { it.isVpnProfile() }.mapTo(HashSet()) { it.id }).forEach(request::addVPNEndpointTag)
        val network = SagerNet.underlyingNetwork
        systemResolvers(network).forEach(request::addSystemResolver)
        // Gone or replaced since the probe started; Android's own validation is not asked, censored networks block it.
        val lost = { SagerNet.underlyingNetwork.let { it == null || it != network } }
        Mobile.startURLTest(null, CoreRuntime.platform, request, Handler(probe::profileOf, lost, emit, done))
    }

    override fun report(results: List<ProbeResult>) = session.reportUrl(results)

    /** BuildTestConfig marks it SetLatency(-1): a failed test with the generator's reason. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason)

    /** url_test_current (mainwindow_view.cpp:405-433): the running instance's `proxy` exit, shown and never stored. */
    suspend fun runCurrent(running: CoreRuntime.RunningCore?) {
        if (running == null) {
            session.reportCurrentUrl(ProbeResult(0L, error = ERROR_NOT_RUNNING))
            return
        }
        val request = TestRequest().apply {
            testCurrent = true
            url = session.options.url
            timeoutMs = session.options.timeoutMs
            maxConcurrency = 1
            if (exitsThroughVpn(ProfileManager.getProfile(running.profileId))) addVPNEndpointTag(CoreConfig.TAG_PROXY)
        }
        // The last emitted result wins: the verdict comes after the failure it replaces.
        val result = AtomicReference(ProbeResult(running.profileId, error = ERROR_NO_RESULT))
        try {
            // Sessions never overlap, so only the stats bar's test can report this tag meanwhile.
            TestTags.holding(listOf(TestTags.CURRENT)) {
                session.awaitCore { done ->
                    Mobile.startURLTest(
                        running.instance, CoreRuntime.platform, request,
                        Handler({ running.profileId }, null, result::set, done),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            result.set(ProbeResult(running.profileId, error = e.readableMessage))
        }
        session.reportCurrentUrl(result.get())
    }

    /**
     * Emits every result; once they are in, a failed (not aborted) VPN profile whose tunnel is up is emitted again with
     * the verdict (TestRunner.cpp:401-410), which wins over an inconclusive one. A failure is inconclusive when the
     * core found the local network down or [networkLost] says so; null (the running connection's test, never stored)
     * skips that.
     */
    private class Handler(
        private val profileOf: (String?) -> Long?,
        private val networkLost: (() -> Boolean)?,
        private val emit: (ProbeResult) -> Unit,
        private val done: () -> Unit,
    ) : URLTestHandler {
        // Results may land on another Go thread than the verdicts.
        private val failures = ConcurrentHashMap<String, ProbeResult>()

        override fun onResult(tag: String?, latencyMs: Int, error: String?, inconclusive: Boolean) = guarded {
            val id = profileOf(tag) ?: return@guarded
            val message = error.orEmpty()
            val failed = message.isNotEmpty() && !ProxyEntity.isTestAborted(message)
            val lost = failed && networkLost != null && (inconclusive || networkLost.invoke())
            val result = ProbeResult(id, latency = latencyMs, error = message, inconclusive = lost)
            if (tag != null && failed) failures[tag] = result
            emit(result)
        }

        override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) = guarded {
            val failure = failures.remove(tag.orEmpty()) ?: return@guarded
            if (connected) {
                emit(ProbeResult(failure.profileId, failure.latency, error = failure.error, connectOnly = true))
            }
        }

        override fun onDone() = guarded(done)
    }

    companion object {
        /** The core's errInstanceNotRunning text. */
        const val ERROR_NOT_RUNNING = "instance is not running"

        /**
         * The DNS servers of the physical [network] (never this app's VPN), which the core asks whether the local
         * network is up when tests fail; the ones the desktop's usableResolver refuses are left out.
         */
        internal fun systemResolvers(network: Network?): List<String> {
            if (network == null) return emptyList()
            val servers = runCatching { SagerNet.connectivity.getLinkProperties(network)?.dnsServers }
                .onFailure { Logs.w(it) }.getOrNull() ?: return emptyList()
            return servers.filterNot {
                it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isMulticastAddress ||
                    it is Inet6Address && it.isSiteLocalAddress
            }.mapNotNull { address -> address.hostAddress?.takeIf { '%' !in it } }.distinct()
        }
    }
}
