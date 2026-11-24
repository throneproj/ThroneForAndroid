package io.nekohasekai.sagernet.bg.proto

import io.throneproj.mobile.Instance
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Per-tag traffic of the running box, read once per tick. */
class TrafficUpdater(box: Instance, tags: Collection<String>) {

    class TagStat(val tag: String) {
        /** Bytes moved since the previous tick. */
        var tx = 0L
        var rx = 0L
        var txRate = 0L
        var rxRate = 0L
        var lastUpdate = 0L
    }

    val stats: Map<String, TagStat> = tags.associateWith { TagStat(it) }
    private val ordered = stats.values.toList()
    private val reader = box.newTrafficReader(ordered.joinToString("\n") { it.tag })

    fun updateAll() {
        val now = System.currentTimeMillis()
        // One call for every tag: uplink then downlink per tag, each already a delta (the core swaps its counters to 0).
        val buffer = ByteBuffer.wrap(reader.read() ?: ByteArray(0)).order(ByteOrder.LITTLE_ENDIAN)
        for (stat in ordered) {
            val present = buffer.remaining() >= 16
            stat.tx = if (present) buffer.long.coerceAtLeast(0) else 0L
            stat.rx = if (present) buffer.long.coerceAtLeast(0) else 0L
            val interval = now - stat.lastUpdate
            if (stat.lastUpdate > 0L && interval > 0L) {
                stat.txRate = stat.tx * 1000 / interval
                stat.rxRate = stat.rx * 1000 / interval
            } else {
                stat.txRate = 0
                stat.rxRate = 0
            }
            stat.lastUpdate = now
        }
    }
}
