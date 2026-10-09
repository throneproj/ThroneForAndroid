package io.nekohasekai.sagernet.bg.test

import android.os.RemoteException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.aidl.ITestSessionCallback
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.XrayGeoAssets
import io.nekohasekai.sagernet.bg.proto.SpeedTestSnapshot
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.SpeedTestResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One test sweep (TestRunner's session): resolves the profiles, runs the kind's runner, persists every result as it
 * lands (desktop result codes) and streams it to [client], shows the progress notification, and after a URL sweep
 * clears the unavailable profiles of a group with `auto_clear_unavailable`.
 */
internal class TestSession(
    val id: Int,
    val spec: TestSpec,
    client: ITestSessionCallback?,
) {

    /** The spec's values, else the desktop settings. */
    class Options(spec: TestSpec) {
        val url: String = spec.url.ifBlank { DataStore.testUrl }
        val timeoutMs: Int = spec.timeoutMs.takeIf { it > 0 } ?: DataStore.urlTestTimeoutMs
        val concurrency: Int = spec.concurrency.takeIf { it > 0 } ?: DataStore.testConcurrent
        val speedMode: Int = spec.speedMode.takeIf { it in SpeedTestSettings.FULL..SpeedTestSettings.COUNTRY }
            ?: DataStore.speedTestMode
        val speedTimeoutMs: Int = spec.speedTimeoutMs.takeIf { it > 0 } ?: DataStore.speedTestTimeoutMs
        val simpleDlUrl: String = spec.simpleDlUrl.ifBlank { DataStore.simpleDlUrl }
        val creditTraffic: Boolean = !DataStore.disableTrafficStats
    }

    val kind: Int get() = spec.kind

    val options by lazy { Options(spec) }

    @Volatile
    var cancelled = false
        private set

    /** The engine's handle on the running sweep, cancelled when a stop does not wind it down in time. */
    @Volatile
    var job: Job? = null

    @Volatile
    private var client: ITestSessionCallback? = client

    @Volatile
    var total = 0
        private set

    private val done = AtomicInteger()
    private val ok = AtomicInteger()
    private val failed = AtomicInteger()

    val doneCount: Int get() = done.get()
    val okCount: Int get() = ok.get()
    val failedCount: Int get() = failed.get()

    private val notification = TestNotification(this)

    /** Geo asset downloads that failed in this session, by file: each is tried once per session. */
    private val assetFailures = ConcurrentHashMap<String, String>()

    /** Why profiles were left untested for a geo asset, shown as a warning once the session ends. */
    private val assetProblems: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** stop(): the core cancels the tests in flight, the sweep starts nothing new. */
    fun stop() {
        cancelled = true
        Mobile.stopTests()
    }

    suspend fun run() {
        notification.start()
        try {
            coroutineScope {
                val refresher = launch { notification.refreshLoop() }
                try {
                    if (spec.testCurrent) runCurrent() else runProfiles()
                } finally {
                    refresher.cancel()
                }
            }
        } catch (e: CancellationException) {
            stop()
            throw e
        } catch (e: Exception) {
            Logs.w(e)
        } finally {
            notification.finish(assetProblems)
            notifyClient { it.onDone(id, cancelled) }
        }
    }

    /**
     * Downloads what of [files] is missing, the progress in the notification; returns the files still missing with the
     * reason (a file that already failed in this session is not tried again).
     */
    suspend fun ensureAssets(files: Collection<String>): Map<String, String> {
        val failures = LinkedHashMap<String, String>()
        val pending = ArrayList<String>()
        for (file in files) {
            val known = assetFailures[file]
            if (known != null) failures[file] = known else pending.add(file)
        }
        if (pending.isEmpty()) return failures
        val fresh = try {
            XrayGeoAssets.ensure(pending, abort = { cancelled }) { notification.asset(XrayGeoAssets.progressText(it)) }
        } finally {
            notification.asset("")
        }
        for ((file, error) in fresh) {
            if (cancelled) {
                failures[file] = ERROR_ABORTED
            } else {
                assetFailures[file] = error
                failures[file] = error
                assetProblems.add(error)
            }
        }
        return failures
    }

    /** A profile left untested for a geo asset, for the warning at the end. */
    fun assetProblem(message: String) {
        assetProblems.add(message)
    }

    /** The core's error for a probe of [profileName] that could not start, a geo asset problem in words. */
    fun startFailure(profileName: String, error: String): String =
        XrayGeoAssets.describeFailure(error, profileName)?.also(::assetProblem) ?: error

    private suspend fun runProfiles() {
        val profiles = resolve()
        if (notification.scope.isBlank()) {
            notification.scope = profiles.firstOrNull()?.let { GroupRepo.get(it.groupId)?.displayName() }.orEmpty()
        }
        started(profiles.size)
        when (kind) {
            TestSpec.KIND_URL -> {
                UrlTestRunner(this).run(profiles)
                autoClearUnavailable(profiles)
            }

            TestSpec.KIND_IP -> IpTestRunner(this).run(profiles)
            TestSpec.KIND_SPEED -> SpeedTestRunner(this).run(profiles)
        }
    }

    private suspend fun runCurrent() {
        started(1)
        val running = CoreRuntime.running
        when (kind) {
            TestSpec.KIND_URL -> UrlTestRunner(this).runCurrent(running)
            TestSpec.KIND_SPEED -> SpeedTestRunner(this).runCurrent(running)
            else -> reportIp(
                listOf(
                    ProbeResult(
                        running?.profileId ?: 0L,
                        error = app.getString(R.string.test_engine_ip_current_unsupported),
                        measured = false,
                    )
                )
            )
        }
    }

    /** The requested profiles that exist, in order, without auto-selectors (withoutAutoSelectors, TestRunner.cpp:25-35). */
    private fun resolve(): List<ProxyEntity> {
        val ids = spec.profileIds.distinct()
        val rows = ProfileManager.getProfiles(ids).associateBy { it.id }
        return ids.mapNotNull { rows[it] }.filter { it.type != TYPE_AUTO_SELECTOR }
    }

    private fun started(count: Int) {
        total = count
        notifyClient { it.onStarted(id, kind, count) }
        notification.changed()
    }

    /** TestRunner.cpp:393-404: the first tested profile's group drops its unavailable ones; the running one stays. */
    private suspend fun autoClearUnavailable(profiles: List<ProxyEntity>) {
        val group = profiles.firstOrNull()?.let { GroupRepo.get(it.groupId) } ?: return
        if (!group.autoClearUnavailable) return
        val unavailable = ProfileManager.getProfiles(profiles.map { it.id }).filter { it.isUnavailable() }.map { it.id }
        if (unavailable.isEmpty()) return
        Logs.i("URL test finished, clearing ${unavailable.size} unavailable profiles")
        ProfileManager.batchDeleteProfiles(unavailable, stopRunning = false)
    }

    // ------------------------------------------------------------------------------------------------ results

    /**
     * applyUrlResult (TestRunner.cpp:127-142); the callback carries the latency code as stored. A Connect OK verdict
     * turns the failure its profile already reported into a success.
     */
    fun reportUrl(results: List<ProbeResult>) {
        persist(results) { ProfileManager.saveUrlTestResult(it.profileId, it.latency, it.error, it.connectOnly) }
        for (result in results) {
            val latency = if (result.measured) latencyCode(result.latency, result.error, result.connectOnly) else 0
            if (result.connectOnly) {
                failed.decrementAndGet()
                ok.incrementAndGet()
                notification.changed()
            } else {
                counted(ProxyEntity.isWorking(latency), ProxyEntity.isUnavailable(latency))
            }
            notifyClient { it.onUrlResult(result.profileId, latency, if (result.connectOnly) "" else result.error) }
        }
    }

    /** The running connection's URL test is shown, never stored. */
    fun reportCurrentUrl(result: ProbeResult) {
        val latency = latencyCode(result.latency, result.error, result.connectOnly)
        counted(ProxyEntity.isWorking(latency), ProxyEntity.isUnavailable(latency))
        notifyClient { it.onUrlResult(result.profileId, latency, if (result.connectOnly) "" else result.error) }
    }

    /** applyIpResult (TestRunner.cpp:144-157). */
    fun reportIp(results: List<ProbeResult>) {
        persist(results) { ProfileManager.saveIpTestResult(it.profileId, it.ip, it.country, it.error) }
        for (result in results) {
            val success = result.measured && result.error.isEmpty()
            counted(success, result.measured && !success && !ProxyEntity.isTestAborted(result.error))
            val error = if (result.measured) result.error else notMeasured(result.error)
            notifyClient {
                it.onIpResult(
                    result.profileId, if (success) result.ip else "", if (success) result.country else "", error,
                )
            }
        }
    }

    /**
     * The speed result loop of TestRunner.cpp:632-647 (the server's country name mapped to its code); the callback
     * carries the values as stored.
     */
    fun reportSpeed(profileId: Long, result: SpeedTestResult) {
        val error = result.error.orEmpty()
        val dl = result.dlSpeed.orEmpty()
        val ul = result.ulSpeed.orEmpty()
        val country = CountryNames.toCode(result.serverCountry)
        if (error.isNotEmpty()) Logs.w("[$profileId] speed test error: $error")
        val stored = try {
            if (error.isEmpty()) {
                ProfileManager.saveSpeedTestResult(profileId, dl, ul, result.latency, country, "")
            } else {
                ProfileManager.saveSpeedTestResult(profileId, "", "", 0, "", error)
            }
        } catch (e: Exception) {
            Logs.w(e)
            null
        }
        counted(error.isEmpty(), error.isNotEmpty())
        notifyClient {
            if (stored != null) {
                it.onSpeedResult(
                    profileId, stored.dlSpeed.orEmpty(), stored.ulSpeed.orEmpty(), stored.latency,
                    stored.testCountry.orEmpty(), error,
                )
            } else if (error.isEmpty()) {
                it.onSpeedResult(profileId, dl, ul, result.latency, country, error)
            } else {
                it.onSpeedResult(profileId, ProxyEntity.SPEED_NA, ProxyEntity.SPEED_NA, -1, "", error)
            }
        }
    }

    /** A profile the speed test did not measure (stopped, skipped, probe failure): reported, nothing stored. */
    fun reportSpeedUnmeasured(profileId: Long, reason: String) {
        counted(false, false)
        notifyClient { it.onSpeedResult(profileId, "", "", 0, "", notMeasured(reason)) }
    }

    fun speedProgress(snapshot: SpeedTestSnapshot) {
        notification.speed(snapshot)
        notifyClient { it.onSpeedProgress(snapshot) }
    }

    /** Saves the measured results in one transaction, in order. */
    private fun persist(results: List<ProbeResult>, save: (ProbeResult) -> Unit) {
        if (results.none { it.measured }) return
        try {
            SagerDatabase.instance.runInTransaction {
                for (result in results) {
                    if (!result.measured) continue
                    try {
                        save(result)
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    private fun counted(success: Boolean, failure: Boolean) {
        done.incrementAndGet()
        if (success) ok.incrementAndGet()
        if (failure) failed.incrementAndGet()
        notification.changed()
    }

    private inline fun notifyClient(block: (ITestSessionCallback) -> Unit) {
        val callback = client ?: return
        try {
            block(callback)
        } catch (e: RemoteException) {
            // The client is gone; the sweep goes on and keeps persisting.
            client = null
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    companion object {
        const val TYPE_AUTO_SELECTOR = "autoselector"

        /** The latency code of a core result: ms, aborted = untested (0), a VPN tunnel that is up, else failed (-1). */
        fun latencyCode(latencyMs: Int, error: String, connectOnly: Boolean): Int = when {
            error.isEmpty() -> latencyMs
            ProxyEntity.isTestAborted(error) -> 0
            connectOnly -> ProxyEntity.LATENCY_CONNECT_ONLY
            else -> -1
        }

        /**
         * IP and speed results have no untested code, so one the core never measured (skip, stop, probe failure) reads
         * as aborted and keeps its reason.
         */
        fun notMeasured(reason: String): String =
            if (ProxyEntity.isTestAborted(reason)) reason else "$ERROR_ABORTED: $reason"
    }
}
