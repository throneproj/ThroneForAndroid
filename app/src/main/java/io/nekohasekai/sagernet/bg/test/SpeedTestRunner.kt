package io.nekohasekai.sagernet.bg.test

import android.os.SystemClock
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Instance
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SpeedTestHandler
import io.throneproj.mobile.SpeedTestResult
import io.throneproj.mobile.TestRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Speed tests (runSpeedTests / runSpeedProbe, TestRunner.cpp:408-649): one profile per test build and probe, strictly
 * in order, except the country mode, which looks a batch up at once and streams its results. The profile in flight is
 * sampled every 100 ms and the bytes it moves are credited to it.
 */
internal class SpeedTestRunner(private val session: TestSession) {

    private val mode = session.options.speedMode
    private val modeName = SpeedTestSettings.modeName(mode)
    private val countryOnly = mode == SpeedTestSettings.COUNTRY
    private val traffic = TrafficCredit(session.options.creditTraffic)

    suspend fun run(profiles: List<ProxyEntity>) {
        for (slice in profiles.chunked(if (countryOnly) TEST_BATCH_SIZE else 1)) {
            if (session.cancelled) break
            val excluded = session.screenXrayFullConfigs(slice)
            for (entry in excluded) session.reportSpeedUnmeasured(entry.profileId, entry.reason)
            val left = excluded.mapTo(HashSet()) { it.profileId }
            val ids = slice.map { it.id }.filter { it !in left }
            if (ids.isEmpty()) continue
            val names = slice.associate { it.id to it.displayName() }
            val generated = CoreConfigs.buildTest(ids)
            if (!generated.ok) {
                val error = generated.error ?: "config generation failed"
                Logs.w("Failed to build batch test config: $error")
                ids.forEach { session.reportSpeedUnmeasured(it, error) }
                continue
            }
            for ((id, reason) in generated.skipped) session.reportSpeedUnmeasured(id, reason)
            session.ensureSharedXrayAssets(generated.xrayConfig)
            for (probe in TestProbe.plan(generated)) {
                runProbe(probe.request(), probe.profileIds, probe::profileOf, names, null)
            }
        }
    }

    /** Test current (F6): the running box's `proxy` exit, stored on the running profile. */
    suspend fun runCurrent(running: CoreRuntime.RunningCore?) {
        if (running == null) {
            session.reportSpeedUnmeasured(0L, UrlTestRunner.ERROR_NOT_RUNNING)
            return
        }
        val name = ProfileManager.getProfile(running.profileId)?.displayName().orEmpty()
        val request = TestRequest().apply { testCurrent = true }
        runProbe(request, listOf(running.profileId), { running.profileId }, mapOf(running.profileId to name), running.instance)
    }

    private suspend fun runProbe(
        request: TestRequest,
        profileIds: List<Long>,
        profileOf: (String?) -> Long?,
        names: Map<Long, String>,
        current: Instance?,
    ) = coroutineScope {
        if (session.cancelled) {
            profileIds.forEach { session.reportSpeedUnmeasured(it, ERROR_ABORTED) }
            return@coroutineScope
        }
        request.apply {
            testDownload = mode == SpeedTestSettings.FULL || mode == SpeedTestSettings.DOWNLOAD_ONLY
            testUpload = mode == SpeedTestSettings.FULL || mode == SpeedTestSettings.UPLOAD_ONLY
            simpleDownload = mode == SpeedTestSettings.SIMPLE_DOWNLOAD
            simpleDownloadAddr = session.options.simpleDlUrl
            timeoutMs = session.options.speedTimeoutMs
            onlyCountry = countryOnly
            countryConcurrency = session.options.concurrency
        }
        traffic.reset()
        val results = Channel<SpeedTestResult>(Channel.UNLIMITED)
        val reported = HashSet<Long>()
        // Country lookups stream their results; the other modes measure one profile and report it once it is done.
        val streamer = if (countryOnly) launch { for (result in results) report(result, profileOf, names, reported) } else null
        val poller = if (countryOnly) null else {
            profileIds.singleOrNull()?.let {
                session.speedProgress(SpeedTestSnapshot(it, names[it].orEmpty(), modeName, SpeedTestSnapshot.STAGE_DISCOVERY))
            }
            // The core keeps the previous test's last sample, flagged running for a moment once the next one starts.
            val stale = Mobile.querySpeedTest()
            launch { poll(stale, profileOf, names) }
        }
        val failure = try {
            session.awaitCore { done ->
                Mobile.startSpeedTest(current, CoreRuntime.platform, request, object : SpeedTestHandler {
                    override fun onResult(result: SpeedTestResult?) = guarded {
                        if (result != null) results.trySend(result)
                    }

                    override fun onDone() = guarded(done)
                })
            }
            null
        } catch (e: CancellationException) {
            poller?.cancel()
            throw e
        } catch (e: Exception) {
            e
        }
        results.close()
        poller?.cancelAndJoin()
        streamer?.join()
        if (failure != null) {
            Logs.w(failure)
            traffic.flush()
            profileIds.forEach {
                session.reportSpeedUnmeasured(it, session.startFailure(names[it].orEmpty(), failure.readableMessage))
            }
            return@coroutineScope
        }
        for (result in results) report(result, profileOf, names, reported)
        traffic.flush()
        for (id in profileIds) {
            if (id !in reported) session.reportSpeedUnmeasured(id, if (session.cancelled) ERROR_ABORTED else ERROR_NO_RESULT)
        }
    }

    /** The result loop of runSpeedProbe (TestRunner.cpp:614-648) for one result; a profile's first result counts. */
    private fun report(
        result: SpeedTestResult,
        profileOf: (String?) -> Long?,
        names: Map<Long, String>,
        reported: MutableSet<Long>,
    ) {
        val id = profileOf(result.tag) ?: return
        if (!reported.add(id)) return
        traffic.credit(id, result.tag, result.ulBytes, result.dlBytes)
        if (!countryOnly) session.speedProgress(snapshot(id, names[id], result, done = true))
        // A stopped country lookup comes back neither cancelled nor failed, just empty.
        val stopped = countryOnly && session.cancelled && result.error.isNullOrEmpty() &&
            result.serverCountry.isNullOrEmpty()
        if (result.cancelled || stopped) {
            session.reportSpeedUnmeasured(id, ERROR_ABORTED)
        } else {
            session.reportSpeed(id, result)
        }
    }

    /** pollSpeedTest (TestRunner.cpp:500-533). */
    private suspend fun poll(stale: SpeedTestResult, profileOf: (String?) -> Long?, names: Map<Long, String>) {
        var fresh = false
        var flushedAt = SystemClock.elapsedRealtime()
        while (true) {
            delay(SPEED_POLL_INTERVAL_MS)
            val sample = Mobile.querySpeedTest()
            if (!sample.running) continue
            if (!fresh) {
                if (sample.sameAs(stale)) continue
                fresh = true
            }
            val id = profileOf(sample.tag) ?: continue
            traffic.credit(id, sample.tag, sample.ulBytes, sample.dlBytes)
            session.speedProgress(snapshot(id, names[id], sample, done = false))
            val now = SystemClock.elapsedRealtime()
            if (now - flushedAt >= TRAFFIC_FLUSH_INTERVAL_MS) {
                flushedAt = now
                traffic.flush()
            }
        }
    }

    private fun snapshot(id: Long, name: String?, result: SpeedTestResult, done: Boolean): SpeedTestSnapshot {
        val error = result.error.orEmpty()
        val stage = when {
            !done && result.dlBytes == 0L && result.ulBytes == 0L -> SpeedTestSnapshot.STAGE_LATENCY
            !done && result.ulBytes > 0L -> SpeedTestSnapshot.STAGE_UPLOAD
            !done -> SpeedTestSnapshot.STAGE_DOWNLOAD
            result.cancelled -> SpeedTestSnapshot.STAGE_CANCELLED
            error.isNotEmpty() -> SpeedTestSnapshot.STAGE_ERROR
            else -> SpeedTestSnapshot.STAGE_COMPLETE
        }
        return SpeedTestSnapshot(
            profileId = id,
            profileName = name.orEmpty(),
            mode = modeName,
            stage = stage,
            downloadBitsPerSecond = GroupSort.bitrateToBps(result.dlSpeed).coerceAtLeast(0.0).toLong(),
            uploadBitsPerSecond = GroupSort.bitrateToBps(result.ulSpeed).coerceAtLeast(0.0).toLong(),
            downloadBytes = result.dlBytes,
            uploadBytes = result.ulBytes,
            latencyMs = result.latency.toLong(),
            serverName = result.serverName.orEmpty(),
            serverCountry = result.serverCountry.orEmpty(),
            error = error,
            cancelled = result.cancelled,
            done = done,
            downloadSpeed = result.dlSpeed.orEmpty(),
            uploadSpeed = result.ulSpeed.orEmpty(),
        )
    }

    private fun SpeedTestResult.sameAs(other: SpeedTestResult): Boolean =
        tag == other.tag && dlBytes == other.dlBytes && ulBytes == other.ulBytes && dlSpeed == other.dlSpeed &&
            ulSpeed == other.ulSpeed && latency == other.latency && serverName == other.serverName

    /**
     * creditTraffic (TestRunner.cpp:481-498): probes dial around the core's tracker, so the bytes a speed test moves are
     * added to the tested profile here, diffed per tag (the running profile's counters are the traffic looper's).
     */
    private class TrafficCredit(private val enabled: Boolean) {
        private val base = HashMap<String, LongArray>()
        private val pending = HashMap<Long, LongArray>()

        @Synchronized
        fun reset() = base.clear()

        @Synchronized
        fun credit(profileId: Long, tag: String?, up: Long, down: Long) {
            if (!enabled) return
            val last = base.getOrPut(tag.orEmpty()) { LongArray(2) }
            val dUp = if (up >= last[0]) up - last[0] else up
            val dDown = if (down >= last[1]) down - last[1] else down
            last[0] = up
            last[1] = down
            if (dUp <= 0 && dDown <= 0) return
            val sum = pending.getOrPut(profileId) { LongArray(2) }
            sum[0] += dUp
            sum[1] += dDown
        }

        suspend fun flush() {
            val batch = synchronized(this) { HashMap(pending).also { pending.clear() } }
            for ((id, delta) in batch) {
                try {
                    ProfileManager.addTraffic(id, delta[1], delta[0])
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }
    }

    private companion object {
        /** kSpeedPollIntervalMs (TestRunner.cpp:23). */
        const val SPEED_POLL_INTERVAL_MS = 100L
        const val TRAFFIC_FLUSH_INTERVAL_MS = 1000L
    }
}
