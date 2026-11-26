package io.nekohasekai.sagernet.bg.scan

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.scan.IpListEntry
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.TestRequest

/** libcore ScanTargetSpec. Every field is written: an omitted shuffle means true to the core. */
internal class ScanTargetSpec(
    @JvmField val entries: List<IpListEntry>,
    @JvmField val defaultPorts: List<Int> = emptyList(),
    @JvmField val portMode: String = "",
    @JvmField val shuffle: Boolean = false,
    /** The bits of a uint64; a negative value is a seed past 2^63. */
    @JvmField val seed: Long = 0L,
) {
    fun toJson(): JsonObject {
        val items = JsonArray()
        for (entry in entries) items.add(jsonObjectOf("cidr" to entry.cidr, "port" to entry.port))
        val ports = JsonArray()
        for (port in defaultPorts) ports.add(port)
        return jsonObjectOf(
            "entries" to items,
            "default_ports" to ports,
            "max_hosts_per_entry" to 0,
            "shuffle" to shuffle,
            "seed" to seed,
            "port_mode" to portMode,
        )
    }
}

/** libcore ScanDnsOptions, enabled. */
internal class ScanDnsOptions(@JvmField val protocol: String, @JvmField val configJson: String)

/** libcore ScanWarpOptions, enabled. */
internal class ScanWarpOptions(@JvmField val configJson: String, @JvmField val attempts: Int, @JvmField val timeoutMs: Int)

/** libcore ScanProbeRequest; [maxTargets] 0 only counts the targets. */
internal class ScanProbeRequest(
    @JvmField val sessionId: String,
    @JvmField val spec: ScanTargetSpec,
    @JvmField val cursor: Long = 0L,
    @JvmField val maxTargets: Int = 0,
    @JvmField val concurrency: Int = 0,
    @JvmField val spawnIntervalMs: Int = 0,
    @JvmField val dns: ScanDnsOptions? = null,
    @JvmField val warp: ScanWarpOptions? = null,
) {
    fun toJson(): JsonObject = jsonObjectOf(
        "session_id" to sessionId,
        "spec" to spec.toJson(),
        "cursor" to cursor,
        "max_targets" to maxTargets,
        "concurrency" to concurrency,
        "spawn_interval_ms" to spawnIntervalMs,
        "dns" to jsonObjectOf(
            "enabled" to (dns != null),
            "protocol" to dns?.protocol.orEmpty(),
            "config_json" to dns?.configJson.orEmpty(),
        ),
        "warp" to jsonObjectOf(
            "enabled" to (warp != null),
            "config_json" to warp?.configJson.orEmpty(),
            "attempts" to (warp?.attempts ?: 0),
            "timeout_ms" to (warp?.timeoutMs ?: 0),
        ),
    )
}

/** libcore ScanProbeResult. */
internal class ScanProbeResult(obj: JsonObject) {
    @JvmField val address: String = obj.string("address")
    @JvmField val port: Int = obj.int("port")
    @JvmField val passed: Boolean = obj.bool("passed")
    @JvmField val failedPhase: String = obj.string("failed_phase")
    @JvmField val error: String = obj.string("error")
    @JvmField val probePort: Int = obj.int("probe_port")
    @JvmField val localFailure: Boolean = obj.bool("local_failure")
    @JvmField val dnsMs: Int = obj.int("dns_ms")
    @JvmField val dnsStage: String = obj.string("dns_stage")
    @JvmField val sent: Int = obj.int("sent")
    @JvmField val answered: Int = obj.int("answered")
    @JvmField val handshakeMs: Int = obj.int("handshake_ms")
    @JvmField val shortNamesOnly: Boolean = obj.bool("short_names_only")
    @JvmField val dnsReason: String = obj.string("dns_reason")
}

/** libcore ScanProbeResponse. */
internal class ScanProbeResponse(
    @JvmField val error: String,
    @JvmField val total: Long = 0L,
    @JvmField val nextCursor: Long = 0L,
    @JvmField val aborted: Boolean = false,
    @JvmField val results: List<ScanProbeResult> = emptyList(),
    @JvmField val invalidEntries: Int = 0,
) {
    companion object {
        fun parse(text: String): ScanProbeResponse {
            val obj = JsonInput.parseObjectOrNull(text) ?: return ScanProbeResponse("malformed scan probe response")
            val results = ArrayList<ScanProbeResult>()
            for (item in obj.array("results")) if (item is JsonObject) results.add(ScanProbeResult(item))
            return ScanProbeResponse(
                obj.string("error"), obj.integer("total"), obj.integer("next_cursor"), obj.bool("aborted"), results,
                obj.int("invalid_entries"),
            )
        }
    }
}

/** libcore ScanEvent. */
internal class ScanEvent(obj: JsonObject) {
    @JvmField val seq: Long = obj.integer("seq")
    @JvmField val kind: String = obj.string("kind")
    @JvmField val phase: String = obj.string("phase")
    @JvmField val target: String = obj.string("target")
    @JvmField val latencyMs: Int = obj.int("latency_ms")
    @JvmField val error: String = obj.string("error")
}

/** libcore QueryScanResponse. */
internal class QueryScanResponse(obj: JsonObject) {
    @JvmField val events: List<ScanEvent> = obj.array("events").mapNotNull { (it as? JsonObject)?.let(::ScanEvent) }
    @JvmField val lastSeq: Long = obj.integer("last_seq")
    @JvmField val probed: Long = obj.integer("probed")
    @JvmField val probePassed: Long = obj.integer("probe_passed")
    @JvmField val urlTested: Long = obj.integer("url_tested")
    @JvmField val urlPassed: Long = obj.integer("url_passed")
}

/** One result of ScanURLTest. */
internal class ScanUrlResult(@JvmField val outboundTag: String, @JvmField val latencyMs: Int, @JvmField val error: String)

/** libcore ScanCheckNetworkResponse. */
internal class ScanNetworkCheck(@JvmField val up: Boolean, @JvmField val error: String, @JvmField val systemResolvers: List<String>)

/** Typed wrappers over the core's scan calls (core/mobile/scan.go); all block. */
internal object ScanBinding {

    /** ScanProbe: never throws; every failure comes back in the error. Blocks until done or StopScan. */
    fun probe(request: ScanProbeRequest): ScanProbeResponse = try {
        ScanProbeResponse.parse(Mobile.scanProbe(request.toJson().toCompact()))
    } catch (e: Exception) {
        Logs.w(e)
        ScanProbeResponse(e.readableMessage)
    }

    /** QueryScan: the session's events after [afterSeq] and its counters; null when the call failed. */
    fun query(sessionId: String, afterSeq: Long): QueryScanResponse? = try {
        JsonInput.parseObjectOrNull(Mobile.queryScan(sessionId, afterSeq))?.let(::QueryScanResponse)
    } catch (e: Exception) {
        Logs.w(e)
        null
    }

    fun stop(sessionId: String) {
        try {
            Mobile.stopScan(sessionId)
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    /**
     * ScanURLTest over a probe box built from [request]; [labels] are aligned with its outbound tags. Throws the core's
     * error, "scan stopped" after StopScan and "no default interface; the scan was paused" when the egress went away.
     */
    fun urlTest(sessionId: String, request: TestRequest, labels: List<String>, warmLatency: Boolean): List<ScanUrlResult> {
        // Built item by item: JsonValues.stringArray drops blank items, which would shift the alignment.
        val labelArray = JsonArray()
        for (label in labels) labelArray.add(label)
        val text = Mobile.scanURLTest(CoreRuntime.platform, sessionId, request, labelArray.toCompact(), warmLatency)
        val obj = JsonInput.parseObjectOrNull(text) ?: error("malformed scan URL test response")
        return obj.array("results").mapNotNull { item ->
            (item as? JsonObject)?.let { ScanUrlResult(it.string("outbound_tag"), it.int("latency_ms"), it.string("error")) }
        }
    }

    /** ScanCheckNetwork over TCP [targets] (host:port). */
    fun checkNetwork(targets: List<String>, timeoutMs: Int): ScanNetworkCheck =
        check(jsonObjectOf("targets" to JsonValues.stringArray(targets), "timeout_ms" to timeoutMs, "dns" to false))

    /** ScanCheckNetwork with "dns": a root NS query to each of [systemResolvers]; up when none is usable. */
    fun checkNetworkDns(systemResolvers: List<String>, timeoutMs: Int): ScanNetworkCheck = check(
        jsonObjectOf(
            "targets" to JsonArray(), "timeout_ms" to timeoutMs, "dns" to true,
            "system_resolvers" to JsonValues.stringArray(systemResolvers),
        )
    )

    private fun check(request: JsonObject): ScanNetworkCheck = try {
        val obj = JsonInput.parseObjectOrNull(Mobile.scanCheckNetwork(request.toCompact()))
        if (obj == null) ScanNetworkCheck(false, "malformed network check response", emptyList())
        else ScanNetworkCheck(obj.bool("up"), obj.string("error"), obj.array("system_resolvers").strings())
    } catch (e: Exception) {
        Logs.w(e)
        ScanNetworkCheck(false, e.readableMessage, emptyList())
    }
}
