package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.database.ProxyEntity
import io.throneproj.mobile.IPTestHandler
import io.throneproj.mobile.Mobile

/**
 * Egress IP and country tests (runIpProbe, TestRunner.cpp:234-293) through the exit lookup provider setting: results
 * stream as the core reports them.
 */
internal class IpTestRunner(session: TestSession) : LatencySweep(session) {

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        val request = probe.request().apply {
            timeoutMs = session.options.timeoutMs
            maxConcurrency = session.options.concurrency
            ipProvider = session.options.ipProvider
        }
        Mobile.startIPTest(CoreRuntime.platform, request, object : IPTestHandler {
            override fun onResult(tag: String?, ip: String?, countryCode: String?, error: String?) = guarded {
                val id = probe.profileOf(tag) ?: return@guarded
                val country = CountryNames.normalizeRegion(countryCode)
                emit(ProbeResult(id, ip = ip.orEmpty(), country = country, error = error.orEmpty()))
            }

            override fun onDone() = guarded(done)
        })
    }

    override fun report(results: List<ProbeResult>) = session.reportIp(results)

    /** Not tested: the desktop only flags the latency in memory for an invalid candidate. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason, measured = false)
}
