package io.nekohasekai.sagernet.bg

import android.os.Process
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import io.throneproj.mobile.Instance
import kotlin.random.Random

/** Fixed-capacity ring of points (desktop SeriesBuffer): one time and [fields] values per point; index 0 is the oldest. */
class LiveSeries(val capacity: Int, val fields: Int) {

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity * fields)
    private var head = 0

    var size = 0
        private set

    private fun slot(i: Int): Int = (head + i).let { if (it >= capacity) it - capacity else it }

    fun time(i: Int): Long = times[slot(i)]

    fun value(field: Int, i: Int): Float = values[slot(i) * fields + field]

    val latestTime: Long get() = if (size == 0) 0L else time(size - 1)

    fun push(t: Long, source: FloatArray, offset: Int = 0) {
        val s: Int
        if (size == capacity) {
            s = head
            head = slot(1)
        } else {
            s = slot(size)
            size++
        }
        times[s] = t
        source.copyInto(values, s * fields, offset, offset + fields)
    }

    /** The first point at or after [t]. */
    fun lowerBound(t: Long): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (time(mid) < t) lo = mid + 1 else hi = mid
        }
        return lo
    }

    fun clear() {
        head = 0
        size = 0
    }
}

/**
 * The live graphs' history in the service process (desktop TrafficLooper -> MainWindow::add_traffic_point): a point per
 * traffic tick of the running core, so a graph opened later starts with the last [CAPACITY] seconds. A stop breaks the
 * lines.
 */
object LiveStatsRecorder {

    const val CAPACITY = 600

    /** A tick this late follows a pause (screen off): its rates average the pause, so it only restarts the series. */
    private const val GAP_MS = 2500L

    private val session = Random.nextLong(1L, Long.MAX_VALUE)
    private val series = LiveSeries(CAPACITY, LiveStatsBatch.FIELDS)
    private val point = FloatArray(LiveStatsBatch.FIELDS)
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private var serial = 0L
    private var lastTick = 0L
    private var lastCpuMs = 0L

    /** One traffic tick of the running core: rates in bytes per second. */
    @Synchronized
    fun record(proxyDown: Long, proxyUp: Long, directDown: Long, directUp: Long, box: Instance?) {
        val now = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val wall = now - lastTick
        val gap = lastTick == 0L || wall > GAP_MS || wall <= 0L
        val lastCpu = lastCpuMs
        lastTick = now
        lastCpuMs = cpuMs
        if (gap) return
        val status = box?.let { runCatching { it.status() }.getOrNull() }
        point[LiveStatsBatch.PROXY_DOWN] = proxyDown.toFloat()
        point[LiveStatsBatch.PROXY_UP] = proxyUp.toFloat()
        point[LiveStatsBatch.DIRECT_DOWN] = directDown.toFloat()
        point[LiveStatsBatch.DIRECT_UP] = directUp.toFloat()
        point[LiveStatsBatch.CPU] = (cpuMs - lastCpu).coerceAtLeast(0L) * 100f / wall / cores
        point[LiveStatsBatch.MEMORY] = status?.memory?.takeIf { it > 0L }?.toFloat() ?: Float.NaN
        point[LiveStatsBatch.CONNECTIONS] = status?.connectionsIn?.toFloat() ?: Float.NaN
        series.push(now, point)
        serial++
    }

    /** The core stopped: a point without values breaks the lines, and the next core's first tick restarts them. */
    @Synchronized
    fun stopped() {
        lastTick = 0L
        if (series.size == 0 || series.value(LiveStatsBatch.PROXY_DOWN, series.size - 1).isNaN()) return
        point.fill(Float.NaN)
        series.push(SystemClock.elapsedRealtime(), point)
        serial++
    }

    /** The points after [after] of [session]; all of them for another session. */
    @Synchronized
    fun batch(session: Long, after: Long): LiveStatsBatch {
        val since = if (session == this.session) after else 0L
        val count = (serial - since).coerceIn(0L, series.size.toLong()).toInt()
        val first = series.size - count
        val fields = LiveStatsBatch.FIELDS
        val values = FloatArray(count * fields)
        for (i in 0 until count) {
            for (f in 0 until fields) values[i * fields + f] = series.value(f, first + i)
        }
        return LiveStatsBatch(
            session = this.session,
            serial = serial,
            times = LongArray(count) { series.time(first + it) },
            values = values,
        )
    }
}
