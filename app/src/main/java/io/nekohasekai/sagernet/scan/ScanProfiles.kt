package io.nekohasekai.sagernet.scan

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.common.DnsTunnels
import io.nekohasekai.sagernet.outbound.common.ResolverFlavor
import io.nekohasekai.sagernet.outbound.common.ResolverSource
import io.nekohasekai.sagernet.outbound.common.ResolverSources
import io.nekohasekai.sagernet.outbound.common.upgradeXrayFinalmask
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.types.Dnstt
import io.nekohasekai.sagernet.outbound.types.Masque
import io.nekohasekai.sagernet.outbound.types.MasterDns
import io.nekohasekai.sagernet.outbound.types.WireGuard

/** A scan's base profile: a deep copy with id -1, never the stored row; [sourceId] is the original's id. */
class ScanBase(@JvmField val profile: ProxyEntity?, @JvmField val sourceId: Long, @JvmField val error: String) {
    val ok: Boolean get() = profile != null
}

/** What the core needs to probe resolvers through a DNS tunnel profile (ScanWorker::prepareResolverProbe). */
class ResolverProbe(
    /** dnstt | masterdns | xdns. */
    @JvmField val protocol: String,
    /** Compact JSON: the dnstt / masterdns outbound built with a placeholder resolver, or the xdns mask's settings. */
    @JvmField val configJson: String,
    /** The plain UDP resolvers the profile would use now, single addresses in order; system and DoT/DoH/TCP left out. */
    @JvmField val profileResolvers: List<IpListEntry>,
    @JvmField val error: String,
) {
    val ok: Boolean get() = error.isEmpty()
}

/** Scanner's ScanProfiles (ScanProfiles.h/.cpp) on ProxyEntity. */
object ScanProfiles {

    // Cloudflare's WARP peer key, the same for every WARP account.
    const val WARP_PEER_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
    private const val WARP_HOST_SUFFIX = ".cloudflareclient.com"

    /** DnsTunnelProtocol: dnstt | masterdns | xdns for a profile a DNS resolver scan can probe for; "" otherwise. */
    @JvmStatic
    fun dnsTunnelProtocol(outbound: Outbound?): String = when {
        outbound == null || outbound.invalid || !DnsTunnels.isDnsTunnelProfile(outbound) -> ""
        outbound is Dnstt -> "dnstt"
        outbound is MasterDns -> "masterdns"
        else -> "xdns"
    }

    @JvmStatic
    fun isDnsTunnelBase(outbound: Outbound?): Boolean = dnsTunnelProtocol(outbound).isNotEmpty()

    /** True for the public key every Cloudflare WARP WireGuard peer uses. */
    @JvmStatic
    fun isWarpPeerKey(publicKey: String): Boolean = publicKey.trim() == WARP_PEER_KEY

    /** A WireGuard or AmneziaWG profile whose peer is Cloudflare WARP, or a MASQUE profile. */
    @JvmStatic
    fun isWarpScanProfile(outbound: Outbound?): Boolean {
        if (outbound == null || outbound.invalid) return false
        if (outbound is Masque) return true
        if (outbound !is WireGuard) return false
        if (isWarpPeerKey(outbound.peer.public_key)) return true
        return outbound.peer.address.trim().endsWith(WARP_HOST_SUFFIX, ignoreCase = true)
    }

    /** ApplyWarpJunk: the AmneziaWG junk WARP accepts ([WarpPresets]), with the rest of the AmneziaWG set cleared. */
    @JvmStatic
    fun applyWarpJunk(outbound: WireGuard) {
        outbound.enable_amnezia = true
        outbound.jc = WarpPresets.JUNK_COUNT
        outbound.jmin = WarpPresets.JUNK_MIN
        outbound.jmax = WarpPresets.JUNK_MAX
        // Leftover AmneziaWG fields would change the wire format Cloudflare expects.
        outbound.s1 = 0
        outbound.s2 = 0
        outbound.s3 = 0
        outbound.s4 = 0
        outbound.h1 = ""
        outbound.h2 = ""
        outbound.h3 = ""
        outbound.h4 = ""
        outbound.i1 = ""
        outbound.i2 = ""
        outbound.i3 = ""
        outbound.i4 = ""
        outbound.i5 = ""
        outbound.header_protection_key = ""
        outbound.content_padding_addition = ""
        outbound.rekey_after_time = ""
        outbound.rekey_timeout = ""
        outbound.reject_after_time = ""
        outbound.keepalive_timeout = ""
        outbound.max_handshake_attempts = ""
        outbound.random_trailers = false
        outbound.disable_cookies = false
    }

    /**
     * ApplyWarpEndpoint: points the WARP profile [entity] at [entry], with the junk (unless it has AmneziaWG) or the
     * HTTP/2 fallback (unless it is on HTTP/2 alone) the run needed, and stores the outbound back into the entity; an
     * endpoint source that would replace the address becomes Own. False for a non-WARP profile or an entry that is not
     * one address. The caller saves the entity.
     */
    @JvmStatic
    fun applyWarpEndpoint(entity: ProxyEntity, entry: IpListEntry, junk: Boolean, http2: Boolean): Boolean {
        val out = entity.outbound
        if (!isWarpScanProfile(out)) return false
        val canonical = IpListParse.normalizeCidr(entry.cidr.trim())
        if (canonical.isEmpty() || !IpListParse.isHostCidr(canonical)) return false
        val previous = out.getAddress().trim().replace("[", "").replace("]", "")
        // The run probed with the old host name as SNI, as cloneWithServer keeps it.
        if (previous.isNotEmpty() && !Hosts.isIpAddress(previous) && out.hasTls()) {
            val tls = out.getTls()
            if (tls.server_name.isEmpty()) tls.server_name = previous
        }
        out.setAddress(canonical.substringBefore('/'))
        if (entry.port in 1..65535) out.setPort(entry.port)
        if (out is WireGuard && junk && !out.enable_amnezia) applyWarpJunk(out)
        if (out is Masque && http2 && out.http_version != 2) {
            out.http_version = 0
            out.disable_version_fallback = false
        }
        entity.putOutbound(out)
        if (EndpointSources.resolve(EndpointSources.effective(entity)).address.isNotEmpty()) {
            entity.endpointJson = EndpointSource(EndpointSource.Mode.Own).toJsonText()
        }
        return true
    }

    /** WarpCarrier: "wireguard" | "masque" for a WARP scan profile; "" otherwise. */
    @JvmStatic
    fun warpCarrier(outbound: Outbound?): String = when {
        !isWarpScanProfile(outbound) -> ""
        outbound is Masque -> "masque"
        else -> "wireguard"
    }

    /** WarpProbeEndpoint: the profile's own sing-box endpoint object for the core's WARP probe; nothing is overridden. */
    @JvmStatic
    @JvmOverloads
    fun warpProbeEndpoint(outbound: Outbound?, ctx: BuildContext = SettingsMapper.buildContext()): BuildResult {
        if (outbound == null || !isWarpScanProfile(outbound)) return BuildResult(JsonObject(), app.getString(R.string.scan_base_not_warp))
        val built = outbound.buildChecked(ctx)
        if (!built.ok) return BuildResult(JsonObject(), built.error)
        if (built.json.isEmpty()) return BuildResult(built.json, app.getString(R.string.scan_base_warp_build))
        return built
    }

    private fun clone(source: ProxyEntity): ProxyEntity? {
        val outbound = OutboundFactory.newByType(source.type)
        if (outbound.invalid) return null
        if (!outbound.parseFromJson(source.outbound.exportToJson().copy())) return null
        return ProxyEntity(id = -1L, groupId = source.groupId).putOutbound(outbound)
    }

    /** ResolveScanBase: a resolver scan's DNS tunnel profile, as a deep copy. */
    @JvmStatic
    fun resolveScanBase(scan: IpScan): ScanBase {
        val profileId = scan.config.resolverProfileId
        val live = if (profileId >= 0) {
            try {
                SagerDatabase.proxyDao.getById(profileId)
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
        } else null
        if (live == null || live.outbound.invalid) return ScanBase(null, profileId, app.getString(R.string.scan_base_choose_tunnel))
        if (!isDnsTunnelBase(live.outbound)) return ScanBase(null, profileId, app.getString(R.string.scan_base_not_tunnel))
        val copy = clone(live) ?: return ScanBase(null, profileId, app.getString(R.string.scan_base_copy_failed))
        return ScanBase(copy, profileId, "")
    }

    /** CloneWarpScanBase: a deep copy of a WARP profile, an editor's unsaved draft too. */
    @JvmStatic
    fun cloneWarpScanBase(profile: ProxyEntity?): ScanBase {
        val sourceId = profile?.id ?: -1L
        if (profile == null || !isWarpScanProfile(profile.outbound)) return ScanBase(null, sourceId, app.getString(R.string.scan_base_not_warp))
        val copy = clone(profile) ?: return ScanBase(null, sourceId, app.getString(R.string.scan_base_copy_failed))
        return ScanBase(copy, sourceId, "")
    }

    // Single addresses only, in order; ranges and anything unparsable are dropped.
    private fun hostEntries(lines: List<String>): List<IpListEntry> {
        if (lines.isEmpty()) return emptyList()
        return IpListParse.parseIpListText(lines.joinToString("\n")).entries.filter { IpListParse.isHostCidr(it.cidr) }
    }

    /** scanWorkerProfileResolvers: the plain UDP resolvers [source] gives now; DoT/DoH/TCP lines and system are left out. */
    @JvmStatic
    fun profileResolvers(source: ResolverSource, protocol: String, owner: String, ctx: BuildContext): List<IpListEntry> {
        val flavor = when (protocol) {
            "dnstt" -> ResolverFlavor.Dnstt
            "masterdns" -> ResolverFlavor.MasterDns
            else -> ResolverFlavor.Xdns
        }
        val built = ResolverSources.build(source, flavor, owner, ctx)
        val lines = ArrayList<String>()
        for (value in built.resolvers) {
            if (value is JsonObject) {
                if (value.string("type") == "udp") lines.add(IpListParse.formatTarget(value.string("server"), value.int("server_port")))
                continue
            }
            var text = (value as? String).orEmpty().trim()
            if (text.startsWith("udp://", ignoreCase = true)) text = text.substring(6)
            else if (text.contains("://")) continue
            lines.add(text)
        }
        return hostEntries(lines)
    }

    // Exact keys suffice only on a finalmask upgradeXrayFinalmask has canonicalized.
    private fun xdnsSettings(finalmask: JsonObject): JsonObject {
        for (mask in finalmask.array("udp")) {
            if (mask is JsonObject && mask.string("type") == "xdns") return mask.obj("settings")
        }
        return JsonObject()
    }

    /**
     * ScanWorker::prepareResolverProbe for [base], the copy [resolveScanBase] made: the tunnel's config for the core and
     * the resolvers the profile uses now. Blocking when the profile names a resolver IP list.
     */
    @JvmStatic
    @JvmOverloads
    fun prepareResolverProbe(base: ProxyEntity?, ctx: BuildContext = SettingsMapper.buildContext()): ResolverProbe {
        val unusable = app.getString(R.string.scan_base_tunnel_unusable)
        if (base == null) return ResolverProbe("", "", emptyList(), unusable)
        val outbound = base.outbound
        val protocol = dnsTunnelProtocol(outbound)
        if (protocol.isEmpty()) return ResolverProbe(protocol, "", emptyList(), unusable)
        val source = DnsTunnels.profileResolverSource(outbound)
        val profileResolvers = if (source != null) profileResolvers(source, protocol, base.name.orEmpty(), ctx) else emptyList()
        val config = if (protocol == "xdns") {
            val finalmask = outbound.getXrayStream().finalmask.copy()
            upgradeXrayFinalmask(finalmask)
            xdnsSettings(finalmask)
        } else if (source != null) {
            // The core replaces the resolvers per target; a placeholder keeps the build off the IP lists.
            val savedLines = source.lines
            val savedList = source.ipListId
            source.lines = listOf("127.0.0.1")
            source.ipListId = -1
            val built = try {
                outbound.buildChecked(ctx)
            } finally {
                source.lines = savedLines
                source.ipListId = savedList
            }
            if (!built.ok) return ResolverProbe(protocol, "", profileResolvers, built.error)
            built.json
        } else {
            JsonObject()
        }
        if (config.isEmpty()) return ResolverProbe(protocol, "", profileResolvers, unusable)
        return ResolverProbe(protocol, config.toCompact(), profileResolvers, "")
    }
}
