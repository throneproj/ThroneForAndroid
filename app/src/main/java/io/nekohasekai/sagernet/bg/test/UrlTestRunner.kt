package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import io.throneproj.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * URL latency tests (runUrlProbe, TestRunner.cpp:159-232): results stream as the core reports them, then a failed
 * OpenVPN/OpenConnect profile whose tunnel the core reports up is reported again as Connect OK.
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
        Mobile.startURLTest(null, CoreRuntime.platform, request, Handler(probe::profileOf, emit, done))
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
                        Handler({ running.profileId }, result::set, done),
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
     * the verdict (applyUrlResult's vpnConnected, TestRunner.cpp:127-142).
     */
    private class Handler(
        private val profileOf: (String?) -> Long?,
        private val emit: (ProbeResult) -> Unit,
        private val done: () -> Unit,
    ) : URLTestHandler {
        // Results may land on another Go thread than the verdicts.
        private val failures = ConcurrentHashMap<String, ProbeResult>()

        override fun onResult(tag: String?, latencyMs: Int, error: String?) = guarded {
            val id = profileOf(tag) ?: return@guarded
            val result = ProbeResult(id, latency = latencyMs, error = error.orEmpty())
            if (tag != null && result.error.isNotEmpty() && !ProxyEntity.isTestAborted(result.error)) {
                failures[tag] = result
            }
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
    }
}
