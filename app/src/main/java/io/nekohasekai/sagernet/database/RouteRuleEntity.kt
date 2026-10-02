package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.route.RouteRule

/**
 * The desktop's `route_rules` row (RoutesRepo.cpp:51-94) plus `package_name_json`. List members are compact JSON
 * string arrays in the `<member>_json` columns, like the desktop stores them.
 */
@Entity(
    tableName = RouteRuleEntity.TABLE,
    primaryKeys = ["route_profile_id", "rule_order"],
    foreignKeys = [
        ForeignKey(
            entity = RouteProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["route_profile_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class RouteRuleEntity(
    @ColumnInfo(name = "route_profile_id") var routeProfileId: Long = 0L,
    @ColumnInfo(name = "rule_order") var ruleOrder: Int = 0,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "type", defaultValue = "0") var type: Int = 0,
    @ColumnInfo(name = "ip_version", defaultValue = "") var ipVersion: String = "",
    @ColumnInfo(name = "network", defaultValue = "") var network: String = "",
    @ColumnInfo(name = "protocol", defaultValue = "") var protocol: String = "",
    @ColumnInfo(name = "inbound_json", defaultValue = "[]") var inboundJson: String = "[]",
    @ColumnInfo(name = "domain_json", defaultValue = "[]") var domainJson: String = "[]",
    @ColumnInfo(name = "domain_suffix_json", defaultValue = "[]") var domainSuffixJson: String = "[]",
    @ColumnInfo(name = "domain_keyword_json", defaultValue = "[]") var domainKeywordJson: String = "[]",
    @ColumnInfo(name = "domain_regex_json", defaultValue = "[]") var domainRegexJson: String = "[]",
    @ColumnInfo(name = "source_ip_cidr_json", defaultValue = "[]") var sourceIpCidrJson: String = "[]",
    @ColumnInfo(name = "source_ip_is_private", defaultValue = "0") var sourceIpIsPrivate: Boolean = false,
    @ColumnInfo(name = "ip_cidr_json", defaultValue = "[]") var ipCidrJson: String = "[]",
    @ColumnInfo(name = "ip_is_private", defaultValue = "0") var ipIsPrivate: Boolean = false,
    @ColumnInfo(name = "source_port_json", defaultValue = "[]") var sourcePortJson: String = "[]",
    @ColumnInfo(name = "source_port_range_json", defaultValue = "[]") var sourcePortRangeJson: String = "[]",
    @ColumnInfo(name = "port_json", defaultValue = "[]") var portJson: String = "[]",
    @ColumnInfo(name = "port_range_json", defaultValue = "[]") var portRangeJson: String = "[]",
    @ColumnInfo(name = "process_name_json", defaultValue = "[]") var processNameJson: String = "[]",
    @ColumnInfo(name = "process_path_json", defaultValue = "[]") var processPathJson: String = "[]",
    @ColumnInfo(name = "process_path_regex_json", defaultValue = "[]") var processPathRegexJson: String = "[]",
    @ColumnInfo(name = "package_name_json", defaultValue = "[]") var packageNameJson: String = "[]",
    @ColumnInfo(name = "rule_set_json", defaultValue = "[]") var ruleSetJson: String = "[]",
    @ColumnInfo(name = "invert", defaultValue = "0") var invert: Boolean = false,
    @ColumnInfo(name = "outbound_id", defaultValue = "-2") var outboundId: Long = -2L,
    @ColumnInfo(name = "action", defaultValue = "route") var action: String = "route",
    @ColumnInfo(name = "reject_method", defaultValue = "") var rejectMethod: String = "",
    @ColumnInfo(name = "no_drop", defaultValue = "0") var noDrop: Boolean = false,
    @ColumnInfo(name = "override_address", defaultValue = "") var overrideAddress: String = "",
    @ColumnInfo(name = "override_port", defaultValue = "") var overridePort: String = "",
    @ColumnInfo(name = "sniffers_json", defaultValue = "[]") var sniffersJson: String = "[]",
    @ColumnInfo(name = "sniff_override_dest", defaultValue = "0") var sniffOverrideDest: Boolean = false,
    @ColumnInfo(name = "strategy", defaultValue = "") var strategy: String = "",
    @ColumnInfo(name = "wifi_ssid_json", defaultValue = "[]") var wifiSsidJson: String = "[]",
    @ColumnInfo(name = "wifi_bssid_json", defaultValue = "[]") var wifiBssidJson: String = "[]",
    @ColumnInfo(name = "tls_spoof", defaultValue = "") var tlsSpoof: String = "",
    @ColumnInfo(name = "tls_spoof_method", defaultValue = "") var tlsSpoofMethod: String = "",
    // lxbox/asteriskbox extensions
    @ColumnInfo(name = "logical_mode", defaultValue = "") var logicalMode: String = "",
    @ColumnInfo(name = "rules_json", defaultValue = "") var rulesJson: String = "",
    @ColumnInfo(name = "default_interface_address_json", defaultValue = "[]") var defaultInterfaceAddressJson: String = "[]",
    @ColumnInfo(name = "dns_server_json", defaultValue = "[]") var dnsServerJson: String = "[]",
    @ColumnInfo(name = "balancer_mode", defaultValue = "") var balancerMode: String = "",
    @ColumnInfo(name = "balancer_pool", defaultValue = "0") var balancerPool: Int = 0,
    @ColumnInfo(name = "balancer_pool_tolerance", defaultValue = "0") var balancerPoolTolerance: Int = 0,
    @ColumnInfo(name = "balancer_sticky_hash_json", defaultValue = "[]") var balancerStickyHashJson: String = "[]",
) {

    fun toModel(): RouteRule = RouteRule().also {
        it.name = name
        it.type = type
        it.ip_version = ipVersion
        it.network = network
        it.protocol = protocol
        it.inbound = listFromJson(inboundJson)
        it.domain = listFromJson(domainJson)
        it.domain_suffix = listFromJson(domainSuffixJson)
        it.domain_keyword = listFromJson(domainKeywordJson)
        it.domain_regex = listFromJson(domainRegexJson)
        it.source_ip_cidr = listFromJson(sourceIpCidrJson)
        it.source_ip_is_private = sourceIpIsPrivate
        it.ip_cidr = listFromJson(ipCidrJson)
        it.ip_is_private = ipIsPrivate
        it.source_port = listFromJson(sourcePortJson)
        it.source_port_range = listFromJson(sourcePortRangeJson)
        it.port = listFromJson(portJson)
        it.port_range = listFromJson(portRangeJson)
        it.process_name = listFromJson(processNameJson)
        it.process_path = listFromJson(processPathJson)
        it.process_path_regex = listFromJson(processPathRegexJson)
        it.package_name = listFromJson(packageNameJson)
        it.rule_set = listFromJson(ruleSetJson)
        it.invert = invert
        it.outbound_id = outboundId
        it.action = action
        it.reject_method = rejectMethod
        it.no_drop = noDrop
        it.override_address = overrideAddress
        it.override_port = overridePort
        it.sniffers = listFromJson(sniffersJson)
        it.sniff_override_dest = sniffOverrideDest
        it.strategy = strategy
        it.wifi_ssid = listFromJson(wifiSsidJson)
        it.wifi_bssid = listFromJson(wifiBssidJson)
        it.tls_spoof = tlsSpoof
        it.tls_spoof_method = tlsSpoofMethod
        it.logical_mode = logicalMode
        it.rules_json = rulesJson
        it.default_interface_address = listFromJson(defaultInterfaceAddressJson)
        it.dns_server = listFromJson(dnsServerJson)
        it.balancer_mode = balancerMode
        it.balancer_pool = balancerPool
        it.balancer_pool_tolerance = balancerPoolTolerance
        it.balancer_sticky_hash = listFromJson(balancerStickyHashJson)
    }

    companion object {
        const val TABLE = "route_rules"

        fun of(profileId: Long, order: Int, r: RouteRule) = RouteRuleEntity(
            routeProfileId = profileId,
            ruleOrder = order,
            name = r.name,
            type = r.type,
            ipVersion = r.ip_version,
            network = r.network,
            protocol = r.protocol,
            inboundJson = listToJson(r.inbound),
            domainJson = listToJson(r.domain),
            domainSuffixJson = listToJson(r.domain_suffix),
            domainKeywordJson = listToJson(r.domain_keyword),
            domainRegexJson = listToJson(r.domain_regex),
            sourceIpCidrJson = listToJson(r.source_ip_cidr),
            sourceIpIsPrivate = r.source_ip_is_private,
            ipCidrJson = listToJson(r.ip_cidr),
            ipIsPrivate = r.ip_is_private,
            sourcePortJson = listToJson(r.source_port),
            sourcePortRangeJson = listToJson(r.source_port_range),
            portJson = listToJson(r.port),
            portRangeJson = listToJson(r.port_range),
            processNameJson = listToJson(r.process_name),
            processPathJson = listToJson(r.process_path),
            processPathRegexJson = listToJson(r.process_path_regex),
            packageNameJson = listToJson(r.package_name),
            ruleSetJson = listToJson(r.rule_set),
            invert = r.invert,
            outboundId = r.outbound_id,
            action = r.action,
            rejectMethod = r.reject_method,
            noDrop = r.no_drop,
            overrideAddress = r.override_address,
            overridePort = r.override_port,
            sniffersJson = listToJson(r.sniffers),
            sniffOverrideDest = r.sniff_override_dest,
            strategy = r.strategy,
            wifiSsidJson = listToJson(r.wifi_ssid),
            wifiBssidJson = listToJson(r.wifi_bssid),
            tlsSpoof = r.tls_spoof,
            tlsSpoofMethod = r.tls_spoof_method,
            logicalMode = r.logical_mode,
            rulesJson = r.rules_json,
            defaultInterfaceAddressJson = listToJson(r.default_interface_address),
            dnsServerJson = listToJson(r.dns_server),
            balancerMode = r.balancer_mode,
            balancerPool = r.balancer_pool,
            balancerPoolTolerance = r.balancer_pool_tolerance,
            balancerStickyHashJson = listToJson(r.balancer_sticky_hash),
        )

        /** QListStr2QJsonArray (Utils.cpp:110-118) written compact. */
        fun listToJson(list: List<String>): String = JsonValues.stringArray(list).toCompact()

        /** A stored list column; anything that is not a JSON array reads as empty (RoutesRepo.cpp:426-429). */
        fun listFromJson(text: String?): MutableList<String> {
            if (text.isNullOrBlank()) return ArrayList()
            val arr = JsonInput.parseValue(text) as? JsonArray ?: return ArrayList()
            return arr.strings().filterTo(ArrayList()) { it.isNotBlank() }
        }
    }
}
