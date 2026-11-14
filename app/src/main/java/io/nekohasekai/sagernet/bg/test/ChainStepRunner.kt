package io.nekohasekai.sagernet.bg.test

import android.os.Parcelable
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.IChainStepCallback
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfig
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.bg.proto.applyTo
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.completeWith
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.PortLease
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest
import io.throneproj.mobile.URLTestHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import java.util.concurrent.ConcurrentHashMap

/** ChainStepResult (TestRunner.h): one probe's verdict, [latency] as stored (ms, -1 failed, -2 Connect OK). */
@Parcelize
class ChainStepResult(val hop: Int, val alone: Boolean, val latency: Int, val error: String) : Parcelable

/** ChainStepReport (TestRunner.h): a hop equal to the hop count is the group's landing proxy. */
@Parcelize
class ChainStepReport(
    val error: String = "",
    val invalidHops: IntArray = IntArray(0),
    val hasLanding: Boolean = false,
    val aborted: Boolean = false,
    val results: List<ChainStepResult> = emptyList(),
) : Parcelable

/**
 * TestRunner::runChainStepTest in :bg: URL-tests the chain editor's unsaved hops ([CoreConfigs.buildChainStepTest])
 * with the URL test settings, stores nothing and reports once. It holds the engine's session lock for the whole run,
 * so it is refused while a session runs and sessions wait for it.
 */
internal object ChainStepRunner {

    fun start(hops: List<Long>, groupId: Long, callback: IChainStepCallback): Boolean {
        val run = Run(hops, groupId)
        return TestEngine.startExclusive(run::stop) {
            var report = ChainStepReport(aborted = true)
            try {
                report = run.execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                report = ChainStepReport(error = e.readableMessage)
            } finally {
                try {
                    callback.onDone(report)
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }
    }

    private class Outcome(val latency: Int, val error: String)

    private class Run(private val hops: List<Long>, private val groupId: Long) {

        @Volatile
        private var stopped = false

        fun stop() {
            stopped = true
        }

        suspend fun execute(): ChainStepReport {
            // The probe box binds the bridge ports late; leased until the test is over.
            val ports = PortLease()
            try {
                val step = CoreConfigs.buildChainStepTest(hops, groupId, ports)
                val invalidHops = step.invalidHops.sorted().toIntArray()
                val build = step.build
                if (step.error.isNotEmpty() || build == null || build.outboundTags.isEmpty()) {
                    return ChainStepReport(step.error, invalidHops, step.hasLanding)
                }
                if (stopped) return ChainStepReport(invalidHops = invalidHops, hasLanding = step.hasLanding, aborted = true)

                val request = TestRequest().apply {
                    CoreConfig.from(build).applyTo(this)
                    url = DataStore.testUrl
                    timeoutMs = DataStore.urlTestTimeoutMs
                    maxConcurrency = DataStore.testConcurrent
                    step.vpnEndpointTags.forEach { addVPNEndpointTag(it) }
                }
                val outcomes = ConcurrentHashMap<String, Outcome>()
                val tunnelsUp: MutableSet<String> = ConcurrentHashMap.newKeySet()
                try {
                    TestTags.holding(build.outboundTags) {
                        suspendCancellableCoroutine<Unit> { continuation ->
                            Mobile.startURLTest(
                                null, CoreRuntime.platform, request,
                                Handler(outcomes, tunnelsUp) { continuation.completeWith(Result.success(Unit)) },
                            )
                            // A stop landing while the test starts may have re-armed the core's test context first.
                            if (stopped) Mobile.stopTests()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    val error = e.message?.takeIf { it.isNotBlank() } ?: app.getString(R.string.chain_step_no_answer)
                    return ChainStepReport(error, invalidHops, step.hasLanding)
                }

                var aborted = false
                val results = ArrayList<ChainStepResult>()
                for ((tag, outcome) in outcomes) {
                    val probe = step.tagToProbe[tag] ?: continue
                    if (outcome.error.isNotEmpty() && ProxyEntity.isTestAborted(outcome.error)) {
                        aborted = true
                        continue
                    }
                    results.add(
                        when {
                            outcome.error.isEmpty() -> ChainStepResult(probe.hop, probe.alone, outcome.latency, "")
                            tag in tunnelsUp -> ChainStepResult(probe.hop, probe.alone, ProxyEntity.LATENCY_CONNECT_ONLY, "")
                            else -> ChainStepResult(probe.hop, probe.alone, -1, outcome.error)
                        }
                    )
                }
                return ChainStepReport("", invalidHops, step.hasLanding, aborted, results)
            } finally {
                ports.close()
            }
        }
    }

    /** Collects the results and which OpenVPN / OpenConnect tunnels came up; [done] once the core is through. */
    private class Handler(
        private val outcomes: MutableMap<String, Outcome>,
        private val tunnelsUp: MutableSet<String>,
        private val done: () -> Unit,
    ) : URLTestHandler {
        override fun onResult(tag: String?, latencyMs: Int, error: String?) = guarded {
            if (tag != null) outcomes[tag] = Outcome(latencyMs, error.orEmpty())
        }

        override fun onVPNStatus(tag: String?, connected: Boolean, state: String?, err: String?) = guarded {
            if (tag != null && connected) tunnelsUp.add(tag)
        }

        override fun onDone() = guarded(done)
    }
}
