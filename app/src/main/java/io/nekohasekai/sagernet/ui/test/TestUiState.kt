package io.nekohasekai.sagernet.ui.test

import kotlin.math.min

enum class RowPhase { QUEUED, TESTING, DONE }

/**
 * Live state of one profile in the running (or last) session; result fields follow the desktop encodings.
 * [ok]/[failed] are the session's verdict for its kind; a DONE row with neither was aborted or never reported.
 * [inconclusive]: a URL failure while the local network was down or changed, which kept the earlier result.
 */
data class RowState(
    val phase: RowPhase,
    val latency: Int = 0,
    val error: String = "",
    val dlSpeed: String = "",
    val ulSpeed: String = "",
    val country: String = "",
    val ip: String = "",
    val ok: Boolean = false,
    val failed: Boolean = false,
    val inconclusive: Boolean = false,
) {
    val aborted: Boolean get() = phase == RowPhase.DONE && !ok && !failed
}

/** Why a session ended without (all of) its results. */
enum class TestFailure { NONE, EMPTY, BUSY, UNREACHABLE, CORE_DIED }

/** A working result ranked by the session's metric: latency (URL) or throughput (speed). */
data class RankedResult(
    val profileId: Long,
    val name: String = "",
    val latency: Int = 0,
    val dlSpeed: String = "",
    val ulSpeed: String = "",
    val dlBps: Double = 0.0,
    val ulBps: Double = 0.0,
    val country: String = "",
)

data class CountryCount(val code: String, val count: Int)

/** Latency counts per bin (upper bounds [EDGES], the last bin is open) plus failures and VPN connect-only results. */
data class LatencyHistogram(
    val bins: List<Int> = List(BIN_COUNT) { 0 },
    val failed: Int = 0,
    val connectOnly: Int = 0,
) {
    /** Counts of the desktop colour bands ≤100 ms, ≤300 ms, >300 ms. */
    fun bandCounts(): IntArray {
        val bands = IntArray(3)
        bins.forEachIndexed { i, n -> bands[if (i < 5) 0 else if (i < 10) 1 else 2] += n }
        return bands
    }

    companion object {
        /** Five bins per desktop band: ≤100, ≤300, >300 ms. */
        @JvmField
        val EDGES = intArrayOf(20, 40, 60, 80, 100, 140, 180, 220, 260, 300, 500, 700, 1000, 2000)
        val BIN_COUNT = EDGES.size + 1

        fun binOf(ms: Int): Int {
            for (i in EDGES.indices) if (ms <= EDGES[i]) return i
            return EDGES.size
        }

        /** A latency inside bin [i], for its colour band. */
        fun sampleOf(i: Int): Int = if (i < EDGES.size) EDGES[i] else EDGES.last() + 1
    }
}

/** One throughput sample of the profile in flight: [t] seconds since its test began, [bps] bits per second. */
data class SpeedSample(val t: Float, val bps: Float, val upload: Boolean)

/**
 * How far the testing rows of a speed test are, as a share of their slot: [from] at [since] (elapsedRealtime), then
 * growing linearly to [to] over [durationMs] and holding there until the next phase or the result.
 */
data class TestingProgress(val from: Float, val to: Float, val since: Long, val durationMs: Long) {

    fun at(now: Long): Float =
        if (durationMs <= 0 || now <= since) from else from + (to - from) * min(1f, (now - since).toFloat() / durationMs)

    fun settled(now: Long): Boolean = now - since >= durationMs

    companion object {
        val NONE = TestingProgress(0f, 0f, 0L, 0L)
    }
}

/** The profile a speed test measures right now (from the 100 ms snapshots). */
data class SpeedLive(
    val profileId: Long,
    val name: String,
    val stage: String,
    val dlBps: Double,
    val ulBps: Double,
    val dlBytes: Long,
    val ulBytes: Long,
    val pingMs: Long,
    val server: String,
    val serverCountry: String,
    val finished: Boolean,
    val samples: List<SpeedSample>,
)

/**
 * The running or last session as the UI shows it. [kind] < 0 means no session (idle or dismissed). Counters count
 * delivered results: [done] = [ok] + [failed] + aborted; [total] is the engine's count once the session started.
 */
data class TestUiState(
    val session: Int = 0,
    val kind: Int = -1,
    val scopeLabel: String = "",
    val running: Boolean = false,
    val cancelled: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val ok: Int = 0,
    val failed: Int = 0,
    val rows: Map<Long, RowState> = emptyMap(),
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
    /** The tested group (sort action), 0 when the scope is not a group. */
    val groupId: Long = 0L,
    val testCurrent: Boolean = false,
    /** Effective `speed_test_mode` of a speed session. */
    val speedMode: Int = 0,
    val preparing: Boolean = false,
    val stopping: Boolean = false,
    val failure: TestFailure = TestFailure.NONE,
    /** Rows in [RowPhase.TESTING]. */
    val testing: Int = 0,
    val histogram: LatencyHistogram = LatencyHistogram(),
    /** Best working results first (at most [TestSessionClient.RANK_LIMIT]). */
    val ranking: List<RankedResult> = emptyList(),
    /** Working results the ranking orders; a Connect OK latency has no value to rank by. */
    val rankable: Int = 0,
    val countries: List<CountryCount> = emptyList(),
    val distinctIps: Int = 0,
    val speed: SpeedLive? = null,
    /** Speed tests: the testing rows' progress over the phase budgets; null draws them whole (URL / IP batches). */
    val testingProgress: TestingProgress? = null,
    /** Failed profiles removed from the panel's "Remove unavailable". */
    val removed: Set<Long> = emptySet(),
) {
    val active: Boolean get() = kind >= 0

    val finished: Boolean get() = active && !running

    val pending: Int get() = (total - done).coerceAtLeast(0)

    val aborted: Int get() = (done - ok - failed).coerceAtLeast(0)

    /** Ids of the failed profiles still present. */
    fun failedIds(): List<Long> = rows.entries.filter { it.value.failed && it.key !in removed }.map { it.key }

    /** Ids of the profiles left untested because the network was unavailable. */
    fun inconclusiveIds(): List<Long> = rows.entries.filter { it.value.inconclusive }.map { it.key }

    companion object {
        /** `speed_test_mode` values (desktop TestConfig::SpeedTestMode). */
        const val SPEED_MODE_FULL = 0
        const val SPEED_MODE_DOWNLOAD = 1
        const val SPEED_MODE_UPLOAD = 2
        const val SPEED_MODE_SIMPLE_DOWNLOAD = 3
    }
}
