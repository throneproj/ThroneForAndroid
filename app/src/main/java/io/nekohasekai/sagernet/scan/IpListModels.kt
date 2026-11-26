package io.nekohasekai.sagernet.scan

import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import java.security.MessageDigest

/**
 * Configs::IpListEntry (IpList.h). [cidr] is canonical: the masked network, host addresses without a suffix
 * ("1.2.3.4", "1.2.3.0/24", "2606:4700::/32"). [latencyMs] (0 = unknown) and [lossPct] (the share of handshakes or
 * burst queries left unanswered, -1 = unknown) are kept by scan result lists only.
 */
data class IpListEntry(
    @JvmField val cidr: String,
    @JvmField val port: Int = 0,
    @JvmField val latencyMs: Int = 0,
    @JvmField val lossPct: Int = -1,
)

/** Configs::IpList: a header, with [entries] only when [entriesLoaded]; header loads fill [entryCount] instead. */
class IpList(
    @JvmField var id: Long = -1L,
    @JvmField var name: String = "",
    /** The scan that owns this list (result or snapshot); -1 for user lists. */
    @JvmField var relatedTestId: Long = -1L,
    @JvmField var role: Role = Role.User,
    @JvmField var sourceKind: SourceKind = SourceKind.Manual,
    /** [SourceKind.Url]: an http(s) URL serving text. */
    @JvmField var source: String = "",
    @JvmField var autoUpdate: Boolean = false,
    /** Minutes; values below 30 are treated as 30. */
    @JvmField var updateInterval: Int = 1440,
    /** Epoch seconds of the last successful refresh; on a scan snapshot, the replace-pending flag. */
    @JvmField var lastUpdate: Long = 0L,
    @JvmField var lastError: String = "",
    @JvmField var entries: List<IpListEntry> = emptyList(),
    @JvmField var entryCount: Int = 0,
    @JvmField var entriesLoaded: Boolean = false,
) {
    enum class Role(@JvmField val code: Int) {
        User(0), ScanResult(1), ScanSnapshot(2);

        companion object {
            @JvmStatic
            fun of(code: Int): Role = Role.entries.firstOrNull { it.code == code } ?: User
        }
    }

    enum class SourceKind(@JvmField val code: Int) {
        Manual(0), Url(1);

        companion object {
            /** A retired rule-set source (2) loads as Manual: its entries stay and it never refreshes. */
            @JvmStatic
            fun of(code: Int): SourceKind = if (code == Url.code) Url else Manual
        }
    }

    val isRemote: Boolean get() = sourceKind != SourceKind.Manual && source.isNotEmpty()

    val isHidden: Boolean get() = role == Role.ScanSnapshot
}

/** Configs::ScanConfig (IpScan.h/.cpp). WARP scans use only [warpProfileId]: their flow fixes ports, concurrency and the stop rule. */
class ScanConfig(
    /** Not editable; stored values stay so [targetSpecHash] keeps paused scans resumable. */
    @JvmField var ports: List<Int> = emptyList(),
    @JvmField var portMode: PortMode = PortMode.Merge,
    @JvmField var shuffle: Boolean = true,
    @JvmField var scanIpv6: Boolean = false,
    /** Completes after this many hits since the run started; 0 = no limit. */
    @JvmField var stopAfter: Int = 0,
    @JvmField var concurrency: Int = 64,
    @JvmField var spawnIntervalMs: Int = 2,
    @JvmField var warpProfileId: Long = -1L,
    @JvmField var resolverProfileId: Long = -1L,
) {
    /** Ignore = only [ports], Merge = the entry's own port plus [ports], List = only the entry's own port. */
    enum class PortMode(@JvmField val key: String) { Ignore("ignore"), Merge("merge"), List("list") }

    fun copy(): ScanConfig =
        ScanConfig(ports, portMode, shuffle, scanIpv6, stopAfter, concurrency, spawnIntervalMs, warpProfileId, resolverProfileId)

    fun toJson(): JsonObject {
        val portsArray = JsonArray()
        for (port in ports) portsArray.add(port)
        return jsonObjectOf(
            "ports" to portsArray,
            "portMode" to portMode.key,
            "shuffle" to shuffle,
            "ipv6" to scanIpv6,
            "stopAfter" to stopAfter,
            "concurrency" to concurrency,
            "spawnIntervalMs" to spawnIntervalMs,
            "warp" to jsonObjectOf("profileId" to warpProfileId),
            "resolver" to jsonObjectOf("profileId" to resolverProfileId),
        )
    }

    /** Missing keys keep the values of the object it is called on. */
    fun fromJson(obj: JsonObject) {
        (obj["ports"] as? JsonArray)?.let { array ->
            val list = ArrayList<Int>()
            for (item in array) if (item is Long || item is Double) list.add(JsonValues.toInt(item))
            ports = cleanPorts(list)
        }
        readBool(obj, "shuffle")?.let { shuffle = it }
        val mode = obj.string("portMode")
        if (mode.isNotEmpty()) {
            portMode = when (mode) {
                "ignore" -> PortMode.Ignore
                "list" -> PortMode.List
                else -> PortMode.Merge
            }
        }
        readBool(obj, "ipv6")?.let { scanIpv6 = it }
        readInt(obj, "stopAfter", 0, 1_000_000_000)?.let { stopAfter = it }
        readInt(obj, "concurrency", 1, 1000)?.let { concurrency = it }
        readInt(obj, "spawnIntervalMs", 0, 1000)?.let { spawnIntervalMs = it }
        // A scan from before the redesign may name no profile; Start then asks for one.
        readInt(obj.obj("warp"), "profileId", -1, Int.MAX_VALUE)?.let { warpProfileId = it.toLong() }
        readInt(obj.obj("resolver"), "profileId", -1, Int.MAX_VALUE)?.let { resolverProfileId = it.toLong() }
    }

    /** Hash of everything that decides the target order; a resume is only valid while it is unchanged. */
    fun targetSpecHash(baseListId: Long): String {
        // Stored order, not sorted: the core maps each position to ports[i % n], so a reorder is a different target set.
        val text = "$baseListId|${cleanPorts(ports).joinToString(",")}|${portMode.key}|${if (shuffle) 1 else 0}|${if (scanIpv6) 1 else 0}"
        val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    companion object {
        @JvmStatic
        fun cleanPorts(ports: List<Int>): List<Int> {
            val out = ArrayList<Int>()
            for (port in ports) if (port in 1..65535 && port !in out) out.add(port)
            return out
        }

        private fun readBool(obj: JsonObject, key: String): Boolean? = obj[key] as? Boolean

        // QJsonValue::isDouble covers every JSON number; the value is clamped, then truncated like static_cast<int>.
        private fun readInt(obj: JsonObject, key: String, min: Int, max: Int): Int? {
            val v = obj[key]
            if (v !is Long && v !is Double) return null
            return JsonValues.toDouble(v).coerceIn(min.toDouble(), max.toDouble()).toInt()
        }
    }
}

/** Configs::IpScan (IpScan.h): a saved scan; only resolver scans are stored now. */
class IpScan(
    @JvmField var id: Long = -1L,
    @JvmField var name: String = "",
    @JvmField var kind: Kind = Kind.Warp,
    @JvmField var baseListId: Long = -1L,
    @JvmField var resultListId: Long = -1L,
    @JvmField var config: ScanConfig = ScanConfig(),
    @JvmField var status: Status = Status.Idle,
    @JvmField var mode: Mode = Mode.Initial,
    /** Initial pass: iterates a snapshot of the base list taken when the pass started. */
    @JvmField var snapshotListId: Long = -1L,
    @JvmField var seed: Long = 0L,
    @JvmField var cursor: Long = 0L,
    @JvmField var total: Long = 0L,
    @JvmField var specHash: String = "",
    /** Rescan pass: iterates a snapshot of the result list; failures are removed from the result list. */
    @JvmField var rescanSnapshotListId: Long = -1L,
    @JvmField var rescanCursor: Long = 0L,
    @JvmField var rescanTotal: Long = 0L,
    @JvmField var found: Int = 0,
    @JvmField var removed: Int = 0,
    @JvmField var lastError: String = "",
    @JvmField var startedAt: Long = 0L,
    @JvmField var finishedAt: Long = 0L,
) {
    enum class Kind(@JvmField val code: Int) { Warp(1), Resolver(2) }

    enum class Status(@JvmField val code: Int) {
        Idle(0), Running(1), Paused(2), Completed(3), Failed(4);

        companion object {
            @JvmStatic
            fun of(code: Int): Status = Status.entries.firstOrNull { it.code == code } ?: Idle
        }
    }

    enum class Mode(@JvmField val code: Int) {
        Initial(0), RescanResult(1);

        companion object {
            @JvmStatic
            fun of(code: Int): Mode = if (code == RescanResult.code) RescanResult else Initial
        }
    }

    // cursor and total are quint64 on the desktop.
    val activeCursor: Long get() = if (mode == Mode.RescanResult) rescanCursor else cursor

    val activeTotal: Long get() = if (mode == Mode.RescanResult) rescanTotal else total

    fun canResumeInitial(): Boolean = snapshotListId >= 0 && total != 0L && java.lang.Long.compareUnsigned(cursor, total) < 0

    companion object {
        @JvmStatic
        fun defaultConfig(kind: Kind): ScanConfig {
            val config = ScanConfig(concurrency = 64, spawnIntervalMs = 2)
            if (kind == Kind.Resolver) {
                // A portless entry is probed on 53; ports never multiply resolver targets.
                config.ports = emptyList()
                config.portMode = ScanConfig.PortMode.List
                // The seed lists are pre-shuffled below their first tier, which must go first.
                config.shuffle = false
                config.stopAfter = 20
            }
            return config
        }
    }
}
