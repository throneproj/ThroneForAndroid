package io.nekohasekai.sagernet.outbound.config

/**
 * BuildConfigResult / BuildTestConfigResult (include/configs/generate.h:35-63) as the service layer consumes them:
 * every JSON document is compact, key-sorted text.
 *
 * - [coreConfig]: the sing-box config.
 * - [xrayConfig]: the Xray config when one is needed (generated, or the wrapped custom xrayfullconfig of a started
 *   profile), else null. [needXray] is `isXrayNeeded`.
 * - [xrayDnsStrategy]: `xray_outbound_dns_strategy` ([io.nekohasekai.sagernet.outbound.BuildContext.xrayOutboundDomainStrategy])
 *   when any Xray instance runs, else "".
 * - [xrayFullConfigs]: opaque custom Xray full configs, one instance each: test candidates, or the members of a
 *   started auto-selector.
 * - [outboundTags] / [tagToProfileId]: the ingress tag of every built test candidate (`proxy-<id>-0`,
 *   `xrayfull-<id>-0`) and its profile id (test builds only).
 * - [fullConfigs]: custom `fullconfig` test candidates passed through with their `inbounds` emptied, by profile id.
 * - [skipped]: test candidates that were not built, with the reason.
 * - [tunIPv4Cidr]: the tun address of the started config (generated tun inbound or the custom full config's).
 * - [error]: null on success.
 * - [involvedProfileIds]: every profile a started config was built from (the started profile, chain hops, the
 *   group's landing / front proxy, auto-selector members, route outbounds and their hops), so an edit to any of
 *   them can prompt a restart (`involvedProfiles`, generate.h:48-49); empty for test builds and failures.
 * - [autoSelector]: what a started auto-selector was built with (`autoSelectors`, generate.h:27-31, 45), else null.
 */
class GeneratedConfig(
    @JvmField val coreConfig: String,
    @JvmField val xrayConfig: String?,
    @JvmField val needXray: Boolean,
    @JvmField val xrayDnsStrategy: String,
    @JvmField val xrayFullConfigs: List<String>,
    @JvmField val outboundTags: List<String>,
    @JvmField val tagToProfileId: Map<String, Long>,
    @JvmField val fullConfigs: Map<Long, String>,
    @JvmField val skipped: Map<Long, String>,
    @JvmField val tunIPv4Cidr: String?,
    @JvmField val error: String?,
    @JvmField val involvedProfileIds: Set<Long> = emptySet(),
    @JvmField val autoSelector: AutoSelectorBuild? = null,
) {
    val ok: Boolean get() = error == null

    companion object {
        @JvmStatic
        fun failure(error: String): GeneratedConfig = GeneratedConfig(
            coreConfig = "",
            xrayConfig = null,
            needXray = false,
            xrayDnsStrategy = "",
            xrayFullConfigs = emptyList(),
            outboundTags = emptyList(),
            tagToProfileId = emptyMap(),
            fullConfigs = emptyMap(),
            skipped = emptyMap(),
            tunIPv4Cidr = null,
            error = error,
        )
    }
}

/**
 * AutoSelectorBuildInfo (generate.h:27-31) of a started auto-selector, plus what the runtime needs around it: the
 * core status names members by [members] tags, and pinning maps a profile id back to its tag.
 */
class AutoSelectorBuild(
    /** The core `auto-selector` outbound: `proxy`, or `warp-bypass` under WARP. */
    @JvmField val groupTag: String,
    @JvmField val selectorId: Long,
    /** Member ingress tag (`pool-<i>-0`) -> member profile id, in pool order: the plan's build minus [rejected]. */
    @JvmField val members: Map<String, Long>,
    /** Members of the plan's build left out because the structural or the core check refused them, with the reason. */
    @JvmField val rejected: Map<Long, String>,
    /** interval_sec after Normalize(), for the lazy Xray idle window. */
    @JvmField val intervalSec: Int,
    /** Built behind the WARP hop: every member's bytes then land on `proxy`. */
    @JvmField val warp: Boolean,
) {
    val memberIds: List<Long> get() = members.values.toList()

    /** The member tag of a profile id, null when it is not built. */
    fun tagOf(profileId: Long): String? = members.entries.firstOrNull { it.value == profileId }?.key
}

/** One profile to test (BuildTestConfig takes profiles with their group's front / landing proxy, generate.cpp:2614-2621). */
class TestCandidate @JvmOverloads constructor(
    @JvmField val id: Long,
    /** The group's landing proxy id; 0 or negative when the group has none. */
    @JvmField val landingProxyId: Long = -1,
    /** The group's front proxy id; 0 or negative when the group has none. */
    @JvmField val frontProxyId: Long = -1,
)
