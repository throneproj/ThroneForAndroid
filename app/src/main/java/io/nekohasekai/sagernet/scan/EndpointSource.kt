package io.nekohasekai.sagernet.scan

import androidx.room.InvalidationTracker
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.IpListSchema
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.common.ResolverSources
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.types.Hysteria
import io.nekohasekai.sagernet.outbound.types.Mieru
import io.nekohasekai.sagernet.outbound.types.OpenConnect
import io.nekohasekai.sagernet.outbound.types.OpenVpn
import io.nekohasekai.sagernet.outbound.types.jsonIntOr

/**
 * Configs::EndpointSource (EndpointSource.h/.cpp): where a profile's server address comes from when its config is
 * built. Stored as `endpoint_json` on profiles and groups; `{}` is [Mode.Inherit].
 */
data class EndpointSource(
    @JvmField val mode: Mode = Mode.Inherit,
    /** [Mode.Address] only (groups): a host without a port. */
    @JvmField val address: String = "",
    /** [Mode.IpList] only. */
    @JvmField val ipListId: Long = -1L,
) {
    /** Inherit: a profile follows its group; on a group it changes nothing. */
    enum class Mode { Inherit, Own, Address, IpList }

    fun toJson(): JsonObject = when (mode) {
        Mode.Inherit -> JsonObject()
        Mode.Own -> jsonObjectOf("mode" to "own")
        Mode.Address -> jsonObjectOf("mode" to "address", "address" to address)
        Mode.IpList -> jsonObjectOf("mode" to "list", "list" to ipListId)
    }

    /** The `endpoint_json` text. */
    fun toJsonText(): String = toJson().toCompact()

    companion object {
        @JvmStatic
        fun fromJson(obj: JsonObject): EndpointSource {
            when (obj.string("mode")) {
                "own" -> return EndpointSource(Mode.Own)
                "address" -> {
                    val address = obj.string("address").trim()
                    if (address.isNotEmpty()) return EndpointSource(Mode.Address, address = address)
                }

                "list" -> {
                    val id = jsonIntOr(obj, "list", -1)
                    if (id >= 0) return EndpointSource(Mode.IpList, ipListId = id.toLong())
                }
            }
            return EndpointSource()
        }

        /** An `endpoint_json` value; anything unreadable inherits. */
        @JvmStatic
        fun fromJsonText(text: String): EndpointSource {
            val trimmed = text.trim()
            if (trimmed.isEmpty() || trimmed == "{}") return EndpointSource()
            return JsonInput.parseObjectOrNull(trimmed)?.let { fromJson(it) } ?: EndpointSource()
        }
    }
}

/** Configs::EndpointResolution: what an endpoint source makes a profile dial. */
class EndpointResolution(
    /** Empty: the profile dials its own address. */
    @JvmField val address: String = "",
    /** 0: the profile keeps its own port. */
    @JvmField val port: Int = 0,
    /** Where the address comes from, for hints and logs, e.g. IP list "Scan CF Result". */
    @JvmField val origin: String = "",
    /** Why a configured source supplied no address: a missing or empty IP list. */
    @JvmField val problem: String = "",
) {
    fun displayTarget(): String = if (port > 0) Hosts.displayAddress(address, port) else address
}

/**
 * The endpoint-source half of generate_profiles.cpp: resolving a source, CloneProfileWithServer, the build-time
 * application with its per-build cache, and the display helpers with theirs. Everything that reads an IP list blocks.
 */
object EndpointSources {

    enum class Serverless { None, Missing, NoServer, Realm }

    /** Protocols::Serverless. */
    private val SERVERLESS_TYPES = setOf("autoselector", "tailscale", "dnstt", "masterdns", "direct", "custom", "extracore", "chain")

    private class BuildCache {
        @JvmField val lists = HashMap<Long, EndpointResolution>()
        @JvmField val groups = HashMap<Long, EndpointSource>()
        @JvmField val loggedLists = HashSet<Long>()
    }

    private val current = ThreadLocal<BuildCache?>()

    private val displayLock = Any()
    private val displayLists = HashMap<Long, EndpointResolution>()
    private val displayGroups = HashMap<Long, EndpointSource>()

    // The display cache is only trusted while an observer clears it on every change.
    @Volatile
    private var watching = false

    /**
     * GenerateEndpointScope: builds [block] runs on this thread resolve each IP list and group once and log a list's
     * problem once; it is a [ResolverSources.scope] too. Nested scopes share the outermost; without one every lookup
     * reads afresh and logs nothing.
     */
    @JvmStatic
    fun <T> scope(block: () -> T): T {
        if (current.get() != null) return ResolverSources.scope(block)
        current.set(BuildCache())
        try {
            return ResolverSources.scope(block)
        } finally {
            current.remove()
        }
    }

    /** generateServerlessReason; [type] is the profile's stored type. */
    @JvmStatic
    fun serverlessReason(type: String, outbound: Outbound?): Serverless {
        if (outbound == null) return Serverless.Missing
        // Before the invalid check: tailscale and extracore load as invalid here yet are serverless on the desktop.
        if (OutboundFactory.canonicalType(type) in SERVERLESS_TYPES) return Serverless.NoServer
        if (outbound.invalid) return Serverless.Missing
        if (outbound.isExtraCore() || outbound.isXrayFullConfig()) return Serverless.NoServer
        if (outbound is Hysteria && outbound.realmActive()) return Serverless.Realm
        return Serverless.None
    }

    @JvmStatic
    fun serverlessReason(entity: ProxyEntity?): Serverless =
        if (entity == null) Serverless.Missing else serverlessReason(entity.type, entity.outbound)

    /** EndpointOverrideBlocker: why the profile has no single server address a source could replace; "" when it has one. */
    @JvmStatic
    fun overrideBlocker(entity: ProxyEntity?): String = when (serverlessReason(entity)) {
        Serverless.Missing -> app.getString(R.string.endpoint_blocker_missing)
        Serverless.NoServer -> app.getString(R.string.endpoint_blocker_no_server, entity?.type.orEmpty())
        Serverless.Realm -> app.getString(R.string.endpoint_blocker_realm)
        Serverless.None -> ""
    }

    /** A group's own source. Profiles read in light rows carry no source: [effective] needs the full row. */
    @JvmStatic
    fun groupSource(groupId: Long): EndpointSource = groupSource(groupId, display = false)

    private fun groupSource(groupId: Long, display: Boolean): EndpointSource {
        val cache = current.get()
        cache?.groups?.get(groupId)?.let { return it }
        val cached = display && watching
        if (cached) synchronized(displayLock) { displayGroups[groupId] }?.let { return it }
        val group = SagerDatabase.groupDao.getById(groupId)
        val source = if (group != null) EndpointSource.fromJsonText(group.endpointJson) else EndpointSource()
        cache?.groups?.put(groupId, source)
        if (cached) synchronized(displayLock) { displayGroups[groupId] = source }
        return source
    }

    /** EffectiveEndpointSource: the profile's own source, or its group's while it inherits. */
    @JvmStatic
    fun effective(entity: ProxyEntity): EndpointSource = effective(entity, display = false)

    private fun effective(entity: ProxyEntity, display: Boolean): EndpointSource {
        val own = EndpointSource.fromJsonText(entity.endpointJson)
        if (own.mode != EndpointSource.Mode.Inherit) return own
        return groupSource(entity.groupId, display)
    }

    /** ResolveEndpointSource: what [source] makes a profile dial; Inherit and Own supply nothing. */
    @JvmStatic
    fun resolve(source: EndpointSource): EndpointResolution {
        if (source.mode == EndpointSource.Mode.Address) {
            return EndpointResolution(source.address.trim(), 0, app.getString(R.string.endpoint_origin_group_address))
        }
        if (source.mode != EndpointSource.Mode.IpList) return EndpointResolution()
        val cache = current.get()
        cache?.lists?.get(source.ipListId)?.let { return it }
        val header = IpListsRepo.getIpListHeader(source.ipListId)
        val resolution = if (header == null || header.isHidden) {
            EndpointResolution(problem = app.getString(R.string.iplist_error_gone))
        } else {
            val origin = app.getString(R.string.endpoint_origin_list, header.name)
            val best = IpListsRepo.getBestEntry(source.ipListId)
            if (best != null) {
                EndpointResolution(best.cidr.substringBefore('/'), best.port, origin)
            } else {
                EndpointResolution(origin = origin, problem = app.getString(R.string.endpoint_problem_list_empty, header.name))
            }
        }
        cache?.lists?.put(source.ipListId, resolution)
        return resolution
    }

    private fun unwrap(host: String): String = host.replace("[", "").replace("]", "")

    private fun deepClone(type: String, base: Outbound): Outbound? {
        val clone = OutboundFactory.newByType(type)
        if (clone.invalid) return null
        if (!clone.parseFromJson(base.exportToJson().copy())) return null
        return clone
    }

    /**
     * CloneProfileWithServer: a deep copy of [base] (stored as [type]) dialing [address], and [port] when > 0, that
     * keeps the original domain as SNI / Host; null when the type has no single dialable server.
     */
    @JvmStatic
    fun cloneWithServer(type: String, base: Outbound, address: String, port: Int): Outbound? {
        if (serverlessReason(type, base) != Serverless.None) return null
        val clone = deepClone(type, base) ?: return null

        var original = base.getAddress().trim()
        if (clone is OpenVpn) {
            // server/server_port and servers are mutually exclusive; the first remote supplies what the scan keeps.
            clone.servers.firstOrNull()?.let { first ->
                if (original.isEmpty()) original = first.server.trim()
                if (first.server_port > 0) clone.serverPort = first.server_port
                if (first.network.isNotEmpty()) clone.network = first.network
            }
            clone.servers.clear()
            clone.remote_random = false
        }
        original = unwrap(original)

        if (original.isNotEmpty() && !Hosts.isIpAddress(original)) {
            if (clone.hasTls()) clone.getTls().let { if (it.server_name.isEmpty()) it.server_name = original }
            if (clone.hasTransport()) {
                val transport = clone.getTransport()
                if (transport.host.isEmpty() && (transport.type == "ws" || transport.type == "httpupgrade" || transport.type == "http")) {
                    transport.host = original
                }
            }
            if (clone.hasXrayStream()) {
                val stream = clone.getXrayStream()
                if (stream.security == "tls" && stream.tls.serverName.isEmpty()) stream.tls.serverName = original
                if (stream.security == "reality" && stream.reality.serverName.isEmpty()) stream.reality.serverName = original
                if (stream.network == "ws" && stream.ws.host.isEmpty()) stream.ws.host = original
                if (stream.network == "httpupgrade" && stream.httpupgrade.host.isEmpty()) stream.httpupgrade.host = original
                if (stream.network == "xhttp" && stream.xhttp.host.isEmpty()) stream.xhttp.host = original
            }
            if (clone is OpenConnect && clone.tls.server_name.isEmpty()) clone.tls.server_name = original
        }

        if (port > 0) {
            if (clone is Hysteria) {
                clone.server_ports.clear()
                clone.hop_interval = ""
                clone.hop_interval_max = ""
            }
            if (clone is Mieru) clone.server_ports = ""
        }

        clone.setAddress(unwrap(address.trim()))
        if (port > 0) clone.setPort(port)
        return clone
    }

    @JvmStatic
    fun cloneWithServer(entity: ProxyEntity, address: String, port: Int): Outbound? =
        cloneWithServer(entity.type, entity.outbound, address, port)

    /**
     * generateEndpointApply: the outbound a config build uses for [entity], a clone dialing its effective source's
     * address, else its own. Inside a [scope] a source that supplies nothing is logged once per IP list.
     */
    @JvmStatic
    fun apply(entity: ProxyEntity): Outbound {
        val outbound = entity.outbound
        if (serverlessReason(entity.type, outbound) != Serverless.None) return outbound
        val source = effective(entity)
        if (source.mode != EndpointSource.Mode.Address && source.mode != EndpointSource.Mode.IpList) return outbound
        val resolution = resolve(source)
        if (resolution.address.isNotEmpty()) return cloneWithServer(entity.type, outbound, resolution.address, resolution.port) ?: outbound
        val cache = current.get()
        if (resolution.problem.isNotEmpty() && cache != null && cache.loggedLists.add(source.ipListId)) {
            Logs.w(app.getString(R.string.endpoint_log_own_address, outbound.displayTypeAndName(), resolution.problem))
        }
        return outbound
    }

    // generateDisplayEndpoint
    private fun displayEndpoint(entity: ProxyEntity): EndpointResolution {
        if (serverlessReason(entity.type, entity.outbound) != Serverless.None) return EndpointResolution()
        val source = effective(entity, display = true)
        if (source.mode == EndpointSource.Mode.Address) return EndpointResolution(source.address.trim())
        if (source.mode != EndpointSource.Mode.IpList) return EndpointResolution()
        val cached = watching
        if (cached) synchronized(displayLock) { displayLists[source.ipListId] }?.let { return it }
        val resolution = resolve(source)
        if (cached) synchronized(displayLock) { displayLists[source.ipListId] = resolution }
        return resolution
    }

    /** EffectiveEndpointHost: for display, the host [entity] (a full row) dials instead of its own, or "". */
    @JvmStatic
    fun effectiveHost(entity: ProxyEntity): String = try {
        displayEndpoint(entity).address
    } catch (e: Exception) {
        Logs.w(e)
        ""
    }

    /** DisplayEffectiveAddress: the profile's address as its type shows it, with the effective host in place of its own. */
    @JvmStatic
    fun displayEffectiveAddress(entity: ProxyEntity): String {
        val endpoint = try {
            displayEndpoint(entity)
        } catch (e: Exception) {
            Logs.w(e)
            EndpointResolution()
        }
        if (endpoint.address.isNotEmpty()) {
            cloneWithServer(entity.type, entity.outbound, endpoint.address, endpoint.port)?.let { return it.displayAddress() }
        }
        return entity.outbound.displayAddress()
    }

    /** InvalidateEndpointDisplayCache: [watch] calls it on every change; call it after changes it cannot see. */
    @JvmStatic
    fun invalidateDisplayCache() {
        synchronized(displayLock) {
            displayLists.clear()
            displayGroups.clear()
        }
    }

    /**
     * Turns the display cache on in this process, cleared whenever the IP lists or the groups change in either process.
     * Blocking: call it off the main thread.
     */
    @JvmStatic
    fun watch() {
        synchronized(this) {
            if (watching) return
            SagerDatabase.instance.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(arrayOf(IpListSchema.LISTS, IpListSchema.ENTRIES, ProxyGroup.TABLE)) {
                    override fun onInvalidated(tables: Set<String>) = invalidateDisplayCache()
                }
            )
            invalidateDisplayCache()
            watching = true
        }
    }
}
