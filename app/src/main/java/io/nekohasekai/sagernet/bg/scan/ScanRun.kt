package io.nekohasekai.sagernet.bg.scan

import android.os.SystemClock
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.scan.IpListEntry
import java.util.concurrent.atomic.AtomicBoolean

/** Scanner::StartMode. */
enum class StartMode(@JvmField val code: Int) {
    /** Falls back to the initial pass when there is nothing to continue. */
    Resume(0),
    FromInitial(1),
    RescanResults(2);

    companion object {
        @JvmStatic
        fun of(code: Int): StartMode = entries.firstOrNull { it.code == code } ?: Resume
    }
}

/** Scanner::StartResult. */
class StartResult(@JvmField val error: String = "", @JvmField val targetsChanged: Boolean = false) {
    val ok: Boolean get() = error.isEmpty() && !targetsChanged
}

/**
 * Scanner::ScanLiveState, plus the core session's counters (QueryScan) and the result list a resolver run writes.
 * Copied whole under the run's lock; the lists are replaced, never changed in place.
 */
data class ScanLiveState(
    @JvmField var running: Boolean = false,
    @JvmField var stopping: Boolean = false,
    @JvmField var tested: Long = 0L,
    @JvmField var total: Long = 0L,
    @JvmField var found: Int = 0,
    @JvmField var removed: Int = 0,
    @JvmField var stage: String = "",
    @JvmField var log: List<String> = emptyList(),
    @JvmField var error: String = "",
    /** WARP runs: plain WireGuard got no answer, AmneziaWG junk did. */
    @JvmField var warpJunk: Boolean = false,
    /** WARP runs: MASQUE over HTTP/3 got no answer, HTTP/2 did. */
    @JvmField var warpHttp2: Boolean = false,
    /** Finder runs only: confirmed endpoints, best first. */
    @JvmField var hits: List<IpListEntry> = emptyList(),
    @JvmField var probed: Long = 0L,
    @JvmField var probePassed: Long = 0L,
    @JvmField var urlTested: Long = 0L,
    @JvmField var urlPassed: Long = 0L,
    @JvmField var resultListId: Long = -1L,
) {
    fun pushLog(line: String) {
        log = (log + line).takeLast(LOG_LINES)
    }

    companion object {
        /** ScanManager::kLogLines. */
        const val LOG_LINES = 3
    }
}

/** Scanner::ScanRunHooks; [finished] runs once on the run thread after the last database write. */
internal class ScanRunHooks(
    @JvmField val progress: () -> Unit,
    @JvmField val results: () -> Unit,
    @JvmField val finished: () -> Unit,
)

/** Scanner::ScanRun: one running scan, owned by [ScanEngine] while it works on threads of its own. */
internal interface ScanRun {
    val sessionId: String

    fun start()

    /** The caller also sends StopScan, which ends the call in flight. */
    fun requestStop()

    fun disablePersist()

    val isStopping: Boolean

    /** The final status is stored; a later status write would overwrite it. */
    val isFinalized: Boolean

    fun liveState(): ScanLiveState
}

/**
 * Our own VPN starting or stopping while a scan runs. The core protects scan sockets around the Tun only while an
 * instance runs and DataStore.vpnService is set: a stopping VPN clears it before the box closes, a starting one is up
 * before its instance runs. Probes in that window fail for reasons that say nothing about the targets, so a call that
 * overlapped one is run again once the state has settled. Changes are latched by a poll while any run is active.
 */
internal object VpnWindow {

    private const val POLL_MS = 100L

    /** How long the state must hold after a change before probing resumes. */
    private const val SETTLE_MS = 1_000L

    private val lock = Any()
    private var users = 0
    private var poller: Thread? = null
    private var lastState: State? = null
    private var lastChangeAt = Long.MIN_VALUE / 2
    private var epoch = 0L

    private class State(val service: BaseService.State, val instance: Any?, val vpn: Any?) {
        fun same(other: State): Boolean = service == other.service && instance === other.instance && vpn === other.vpn
    }

    fun acquire() = synchronized(lock) {
        // What changed while no scan ran opens no window: the first user starts from a fresh sample.
        if (users++ == 0) lastState = current() else sampleLocked()
        if (poller != null) return@synchronized
        val thread = Thread({
            while (true) {
                synchronized(lock) {
                    if (users == 0) {
                        poller = null
                        return@Thread
                    }
                    sampleLocked()
                }
                sleep(POLL_MS)
            }
        }, "scan-vpn-window")
        thread.isDaemon = true
        poller = thread
        thread.start()
    }

    fun release() = synchronized(lock) {
        if (users > 0) users--
    }

    private fun current() = State(DataStore.serviceState, CoreRuntime.running?.instance, DataStore.vpnService)

    private fun sampleLocked() {
        val state = current()
        val previous = lastState
        if (previous != null && !previous.same(state)) {
            epoch++
            lastChangeAt = SystemClock.elapsedRealtime()
        }
        lastState = state
    }

    private fun transitional(): Boolean {
        val state = DataStore.serviceState
        return state == BaseService.State.Connecting || state == BaseService.State.Stopping
    }

    /** Taken right before a call; -1 when a window is open already. */
    fun mark(): Long = synchronized(lock) {
        sampleLocked()
        if (transitional()) -1L else epoch
    }

    /** Whether a call that began at [mark] overlapped a window. */
    fun disturbed(mark: Long): Boolean = synchronized(lock) {
        sampleLocked()
        mark < 0 || transitional() || epoch != mark
    }

    /**
     * Blocks until no window is open and the state has held for [SETTLE_MS], or [stop] is set; [onWait] runs once when
     * it has to wait. Returns the milliseconds waited.
     */
    fun awaitSettled(stop: AtomicBoolean, onWait: () -> Unit): Long {
        val begin = SystemClock.elapsedRealtime()
        var announced = false
        while (!stop.get()) {
            val settled = synchronized(lock) {
                sampleLocked()
                !transitional() && SystemClock.elapsedRealtime() - lastChangeAt >= SETTLE_MS
            }
            if (settled) break
            if (!announced) {
                announced = true
                onWait()
            }
            sleep(POLL_MS)
        }
        return SystemClock.elapsedRealtime() - begin
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
