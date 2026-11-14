package io.nekohasekai.sagernet.route

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.config.DnsServers
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.nekohasekai.sagernet.validation.vtr

/**
 * RouteDns (include/database/entities/RouteProfile.h): the DNS settings a routing profile can take over. Local
 * override, hosts, predefined answers and the advanced settings stay global. [toJson] uses the desktop keys.
 */
data class RouteDns(
    val remoteDns: String = "https://8.8.8.8/dns-query",
    val remoteDnsDisableIpv6: Boolean = false,
    val directDns: String = "localhost",
    val directDnsDisableIpv6: Boolean = false,
    val dnsFinalOut: String = "remote",
    val enableDnsRouting: Boolean = true,
    val fakeDns: Boolean = false,
    val fakeIpDisableIpv6: Boolean = false,
) {
    /** RouteDns::ToJson (RouteProfile.cpp). */
    fun toJson(): JsonObject = jsonObjectOf(
        "remote_dns" to remoteDns,
        "remote_dns_disable_ipv6" to remoteDnsDisableIpv6,
        "direct_dns" to directDns,
        "direct_dns_disable_ipv6" to directDnsDisableIpv6,
        "dns_final_out" to dnsFinalOut,
        "enable_dns_routing" to enableDnsRouting,
        "fake_dns" to fakeDns,
        "fakeip_disable_ipv6" to fakeIpDisableIpv6,
    )

    /** RouteDns::Problem: why the core could not use it; "" when it can. */
    fun problem(): String {
        val remote = DnsServers.dnsAddressListProblem(remoteDns)
        if (remote.isNotEmpty()) return vtr(R.string.validation_route_dns_remote, remoteDns, remote)
        val direct = DnsServers.dnsAddressListProblem(directDns)
        if (direct.isNotEmpty()) return vtr(R.string.validation_route_dns_direct, directDns, direct)
        if (dnsFinalOut != "remote" && dnsFinalOut != "direct") return vtr(R.string.validation_route_dns_final, dnsFinalOut)
        return ""
    }

    companion object {
        /** RouteDns::FromJson: an absent key, or one of another JSON type, keeps its default; the servers are trimmed. */
        @JvmStatic
        fun fromJson(obj: JsonObject): RouteDns {
            val d = RouteDns()
            return RouteDns(
                remoteDns = (obj["remote_dns"] as? String ?: d.remoteDns).trim(),
                remoteDnsDisableIpv6 = obj["remote_dns_disable_ipv6"] as? Boolean ?: d.remoteDnsDisableIpv6,
                directDns = (obj["direct_dns"] as? String ?: d.directDns).trim(),
                directDnsDisableIpv6 = obj["direct_dns_disable_ipv6"] as? Boolean ?: d.directDnsDisableIpv6,
                dnsFinalOut = obj["dns_final_out"] as? String ?: d.dnsFinalOut,
                enableDnsRouting = obj["enable_dns_routing"] as? Boolean ?: d.enableDnsRouting,
                fakeDns = obj["fake_dns"] as? Boolean ?: d.fakeDns,
                fakeIpDisableIpv6 = obj["fakeip_disable_ipv6"] as? Boolean ?: d.fakeIpDisableIpv6,
            )
        }
    }
}
