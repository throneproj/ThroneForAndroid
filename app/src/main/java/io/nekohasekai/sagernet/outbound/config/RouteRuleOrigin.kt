package io.nekohasekai.sagernet.outbound.config

/**
 * RouteRuleOriginKind (include/configs/RouteRuleOrigin.h) as far as Android emits route rules: no raw profiles, extra
 * cores, system DNS, auxiliary endpoints, L3 bridge, tunnel gate or Tor rules. [id] is the wire name between the
 * processes.
 */
enum class RouteRuleOriginKind(val id: String) {
    PROFILE_RULE("profile_rule"),
    XRAY_BRIDGE("xray_bridge"),
    SNIFF("sniff"),
    RESOLVE("resolve"),
    DNS_HIJACK("dns_hijack"),
    DNS_INBOUND_REJECT("dns_inbound_reject"),
    AD_BLOCK("adblock"),
    BLOCK_FINAL("block_final");

    companion object {
        fun ofId(id: String): RouteRuleOriginKind? = entries.firstOrNull { it.id == id }
    }
}

/** RouteRuleOrigin: where one route.rules entry came from; [profileRule] indexes RouteProfile.rules, [name] is that rule's name. */
class RouteRuleOrigin(
    @JvmField val kind: RouteRuleOriginKind,
    @JvmField val profileRule: Int = -1,
    @JvmField val name: String = "",
)
