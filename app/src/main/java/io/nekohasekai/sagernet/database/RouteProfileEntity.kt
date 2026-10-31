package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.route.RouteDns
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule

/**
 * The desktop's `route_profiles` row (RoutesRepo.cpp:14-49). The raw and endpoint columns only carry desktop data
 * through restores and backups; Android never builds from them.
 */
@Entity(tableName = RouteProfileEntity.TABLE)
data class RouteProfileEntity(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "default_outbound_id", defaultValue = "-1") var defaultOutboundId: Long = -1L,
    @ColumnInfo(name = "is_remote", defaultValue = "0") var isRemote: Boolean = false,
    @ColumnInfo(name = "remote_url", defaultValue = "") var remoteUrl: String = "",
    @ColumnInfo(name = "auto_update", defaultValue = "0") var autoUpdate: Boolean = false,
    @ColumnInfo(name = "remote_last_update", defaultValue = "0") var remoteLastUpdate: Long = 0L,
    @ColumnInfo(name = "is_raw", defaultValue = "0") var isRaw: Boolean = false,
    @ColumnInfo(name = "raw_route", defaultValue = "") var rawRoute: String = "",
    @ColumnInfo(name = "prevent_modifications", defaultValue = "0") var preventModifications: Boolean = false,
    /** Compact JSON int array. */
    @ColumnInfo(name = "endpoint_profile_ids", defaultValue = "[]") var endpointProfileIds: String = "[]",
    @ColumnInfo(name = "inner_hop_endpoint_ids", defaultValue = "[]") var innerHopEndpointIds: String = "[]",
    /** The profile's RouteDns as Qt-compact JSON, '' when it has none. */
    @ColumnInfo(name = "dns_json", defaultValue = "") var dnsJson: String = "",
    @ColumnInfo(name = "custom_dns", defaultValue = "0") var customDns: Boolean = false,
    /** The subscription group whose refreshes overwrite this profile's rules, -1 = none (RoutesRepo.cpp). */
    @ColumnInfo(name = "source_gid", defaultValue = "-1") var sourceGid: Long = -1L,
) {

    /** custom_dns only counts when dns_json holds an object (RoutesRepo.cpp routeProfileFromJson). */
    fun toModel(rules: List<RouteRuleEntity>): RouteProfile = RouteProfile().also {
        it.id = id
        it.name = name
        it.default_outbound_id = defaultOutboundId
        it.is_remote = isRemote
        it.remote_url = remoteUrl
        it.auto_update = autoUpdate
        it.remote_last_update = remoteLastUpdate
        it.is_raw = isRaw
        it.raw_route = rawRoute
        it.prevent_modifications = preventModifications
        it.endpoint_profile_ids = endpointProfileIds
        it.inner_hop_endpoint_ids = innerHopEndpointIds
        it.dns = dnsFromJson(dnsJson)
        it.custom_dns = customDns && it.dns != null
        it.source_gid = sourceGid
        it.rules = rules.sortedBy { r -> r.ruleOrder }.mapTo(ArrayList<RouteRule>()) { r -> r.toModel() }
    }

    companion object {
        const val TABLE = "route_profiles"

        fun of(p: RouteProfile) = RouteProfileEntity(
            id = p.id,
            name = p.name,
            defaultOutboundId = p.default_outbound_id,
            isRemote = p.is_remote,
            remoteUrl = p.remote_url,
            autoUpdate = p.auto_update,
            remoteLastUpdate = p.remote_last_update,
            isRaw = p.is_raw,
            rawRoute = p.raw_route,
            preventModifications = p.prevent_modifications,
            endpointProfileIds = p.endpoint_profile_ids,
            innerHopEndpointIds = p.inner_hop_endpoint_ids,
            dnsJson = p.dns?.toJson()?.toCompact() ?: "",
            customDns = p.custom_dns,
            sourceGid = p.source_gid,
        )

        /** A stored dns_json; anything but a JSON object reads as no DNS. */
        fun dnsFromJson(text: String?): RouteDns? {
            if (text.isNullOrBlank()) return null
            return JsonInput.parseObjectOrNull(text)?.let(RouteDns::fromJson)
        }
    }
}
