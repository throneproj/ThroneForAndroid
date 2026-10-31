package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.Chain
import io.nekohasekai.sagernet.outbound.types.Custom
import io.nekohasekai.sagernet.outbound.types.Socks
import io.nekohasekai.sagernet.outbound.types.XrayVless
import io.nekohasekai.sagernet.route.OutboundIds
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.ui.warp.WarpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class WarpModesTest {
    private fun key(byte: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { byte.toByte() })

    private fun settings(mode: String = "wireguard", order: String = "proxy-first"): GeneratorSettings {
        val primary = GeneratorSettings(
            dnsQueryTimeout = "5s", dnsOptimisticTimeout = "5s",
            enableWarp = true, warpMode = mode, warpChainOrder = order,
            warpEp = "162.159.192.1:2408", warpPrivateKey = key(1), warpPublicKey = key(2),
            warpIfcAddrs = listOf("172.16.0.2/32"), warpReserved = listOf("1", "2", "3"),
            warpMasqueEp = "162.159.198.1:443", warpMasquePrivateKey = key(1), warpMasquePeerPublicKey = key(2),
            warpMasqueIfcAddrs = listOf("172.16.0.2/32"),
        )
        val entry = primary.copy(warpPrivateKey = key(3), warpMasquePrivateKey = key(3))
        return primary.copy(warpEntryConfig = WarpHop.outbound(entry).exportToJson().toCompact())
    }

    private fun socks() = Socks().apply { server = "192.0.2.1"; serverPort = 1080 }
    private fun xray() = XrayVless().apply {
        server = "192.0.2.2"; serverPort = 443
        uuid = "00000000-0000-4000-8000-000000000001"
        streamSetting.network = "xhttp"
    }

    private fun build(settings: GeneratorSettings, profile: Outbound = socks()): GeneratedConfig =
        ConfigGenerator(ProfileProvider { if (it == 1L) profile else null }, settings).build(1).also {
            assertTrue(it.error, it.ok)
        }

    private fun objects(core: JsonObject): List<JsonObject> =
        (core.array("outbounds") + core.array("endpoints")).filterIsInstance<JsonObject>()

    private fun find(core: JsonObject, tag: String) = objects(core).single { it.string("tag") == tag }

    @Test fun allTransportsPlaceEntryAndExitWithoutWrappingBypass() {
        for (mode in listOf("wireguard", "amnezia", "masque")) {
            for (order in listOf("proxy-first", "warp-first", "warp-both")) {
                val config = JsonInput.parseObject(build(settings(mode, order)).coreConfig)
                val endpoints = config.array("endpoints").filterIsInstance<JsonObject>()
                assertEquals(if (order == "warp-both") 2 else 1, endpoints.size)
                val entry = endpoints.singleOrNull { it.string("tag") == "warp-entry" }
                assertEquals(order != "proxy-first", entry != null)
                if (entry != null) assertFalse(entry.contains("detour"))
                if (entry != null) {
                    val head = if (order == "warp-first") find(config, "proxy")
                        else find(config, find(config, "proxy").string("detour"))
                    assertEquals("socks", head.string("type"))
                    val alias = find(config, head.string("detour"))
                    assertEquals("warp-entry", alias.array("outbounds").single())
                }
                if (order == "warp-first") assertEquals("socks", find(config, "proxy").string("type"))
                else assertEquals(if (mode == "masque") "masque" else "wireguard", find(config, "proxy").string("type"))
                val bypass = find(config, "warp-bypass")
                if (order == "proxy-first") assertFalse(bypass.contains("detour"))
                else {
                    val raw = find(config, bypass.array("outbounds").single() as String)
                    assertEquals("socks", raw.string("type"))
                    assertFalse(raw.contains("detour"))
                }
                if (order == "warp-both") assertEquals(2, endpoints.map { it.string("private_key") }.distinct().size)
            }
        }
    }

    @Test fun amneziaAddsCamouflageWithoutChangingWireguardIdentity() {
        val settings = settings("amnezia", "warp-both")
        for (entry in listOf(false, true)) {
            val result = WarpHop.outbound(settings, entry).buildChecked(BuildContext.DEFAULT)
            assertTrue(result.error, result.ok)
            val config = result.json.toCompact()
            assertTrue(config.contains("<b 0x"))
            assertTrue(config.contains("\"jc\":4"))
        }
        for (entry in listOf(false, true)) {
            val plain = WarpHop.outbound(settings.copy(warpMode = "wireguard"), entry).buildChecked(BuildContext.DEFAULT)
            assertFalse(plain.json.toCompact().contains("<b 0x"))
        }
    }

    @Test fun dualWarpRejectsMissingWrongOrReusedIdentity() {
        val valid = settings(order = "warp-both")
        assertFalse(WarpHop.missing(valid))
        for (entry in listOf("", "{}", "garbage", WarpHop.outbound(valid).exportToJson().toCompact())) {
            val invalid = valid.copy(warpEntryConfig = entry)
            assertTrue(WarpHop.missing(invalid))
            val result = ConfigGenerator(ProfileProvider { socks() }, invalid).build(1)
            assertFalse(result.ok)
            assertEquals(WarpHop.MISSING_ERROR, result.error)
        }
        assertTrue(WarpHop.missing(valid.copy(warpMode = "masque")))
    }

    @Test fun xrayEntryAndDualModesHaveDistinctBridgeTargets() {
        for (order in listOf("warp-first", "warp-both")) {
            val generated = build(settings(order = order), xray())
            assertTrue(generated.needXray)
            val core = JsonInput.parseObject(generated.coreConfig)
            val xray = JsonInput.parseObject(generated.xrayConfig!!)
            assertEquals(1, core.array("endpoints").filterIsInstance<JsonObject>().count { it.string("tag") == "warp-entry" })
            for (doc in listOf(core, xray)) {
                val outbounds = doc.array("outbounds").filterIsInstance<JsonObject>()
                assertEquals(outbounds.size, outbounds.map { it.string("tag") }.distinct().size)
            }
            assertTrue(xray.toCompact().contains("dialerProxy"))
        }
    }

    @Test fun selectorMembersShareOneEntryEndpointAndKeepBypassSeparate() {
        val members = mapOf(1L to socks(), 2L to xray())
        val selector = AutoSelector().apply { gids.add(9L) }
        val group = SelectorGroup(9, "Test", -1, -1, null, null,
            members.map { (id, outbound) -> SelectorMember(id, outbound, 0, 0, "") })
        val routing = RoutingInput(RoutingInput.DEFAULT.profile, RoutingInput.DEFAULT.catalog, SelectorStore { group })
        for (order in listOf("warp-first", "warp-both")) {
            val generated = ConfigGenerator(ProfileProvider { if (it == 3L) selector else members[it] }, settings(order = order), routing = routing).build(3)
            assertTrue(generated.error, generated.ok)
            val core = JsonInput.parseObject(generated.coreConfig)
            val all = objects(core)
            assertEquals(all.size, all.map { it.string("tag") }.distinct().size)
            assertEquals(1, all.count { it.string("tag") == "warp-entry" })
            assertEquals(2, find(core, "warp-bypass").array("outbounds").size)
            assertEquals(if (order == "warp-both") "warp-main" else "proxy", generated.autoSelector!!.groupTag)
        }
    }

    @Test fun entryWrapsTheWholeChainWhileRouteOutboundsStayIndependent() {
        val profiles = (1L..5L).associateWith { id -> socks().apply { server = "192.0.2.$id" } } +
            (6L to Chain().apply { list.addAll(listOf(1L, 2L)) })
        val route = RouteProfile().apply {
            rules.add(RouteRule().apply { domain.add("separate.example"); outbound_id = 5L })
            rules.add(RouteRule().apply { domain.add("bypass.example"); outbound_id = OutboundIds.WARP_BYPASS })
        }
        val routing = RoutingInput(route, RoutingInput.DEFAULT.catalog)
        for (order in listOf("proxy-first", "warp-first", "warp-both")) {
            val generated = ConfigGenerator(ProfileProvider { profiles[it] }, settings(order = order), routing = routing)
                .build(6, landingProxyId = 4, frontProxyId = 3)
            assertTrue(generated.error, generated.ok)
            val core = JsonInput.parseObject(generated.coreConfig)
            fun path(start: String): List<String> {
                val servers = ArrayList<String>()
                var hop = find(core, start)
                repeat(objects(core).size) {
                    if (hop.string("tag") == "warp-entry") { servers.add("entry"); return servers }
                    if (hop.contains("server")) servers.add(hop.string("server"))
                    val next = hop.string("detour").ifEmpty { hop.array("outbounds").firstOrNull() as? String ?: "" }
                    if (next.isEmpty()) return servers
                    hop = find(core, next)
                }
                error("Outbound cycle")
            }
            val chain = listOf("192.0.2.4", "192.0.2.2", "192.0.2.1", "192.0.2.3")
            // WireGuard endpoints carry the server in peers, so only the profile hops and entry marker appear here.
            assertEquals(chain + if (order == "proxy-first") emptyList() else listOf("entry"), path("proxy"))
            assertEquals(chain, path("warp-bypass"))
            val separate = objects(core).single { it.string("server") == "192.0.2.5" }
            assertFalse(separate.contains("detour"))
            val rules = core.obj("route").array("rules").filterIsInstance<JsonObject>()
            assertTrue(rules.any { it.array("domain").contains("bypass.example") && it.string("outbound") == "warp-bypass" })
        }
    }

    @Test fun disabledWarpDoesNotRequireOrEmitAnIdentity() {
        val config = JsonInput.parseObject(build(settings(order = "warp-both").copy(
            enableWarp = false, warpEntryConfig = "", warpPrivateKey = "",
        )).coreConfig)
        assertTrue(config.array("endpoints").isEmpty())
        assertFalse(find(config, "proxy").contains("detour"))
    }

    @Test fun incompleteRegistrationsCannotReplaceSavedIdentities() {
        fun identity(key: String = "key", peer: String = "peer", endpoint: String = "192.0.2.1:2408", ipv4: String = "172.16.0.2") =
            WarpClient.Identity("", "", "", ipv4, "", key, peer, endpoint, emptyList())
        identity()
        for (invalid in listOf<() -> Unit>(
            { identity(key = " ") }, { identity(peer = "") }, { identity(endpoint = "") }, { identity(ipv4 = "") },
        )) {
            assertThrows(IllegalArgumentException::class.java, invalid)
        }
    }

    @Test fun selectorSkipsMembersThatCannotDialThroughEntryWarp() {
        val members = mapOf(1L to socks(), 2L to Custom().apply {
            subtype = Custom.CUSTOM_XRAY_FULL_CONFIG
            config = "{\"outbounds\":[{\"protocol\":\"freedom\",\"tag\":\"direct\"}]}"
        })
        val selector = AutoSelector().apply { gids.add(9L) }
        val group = SelectorGroup(9, "Test", -1, -1, null, null,
            members.map { (id, outbound) -> SelectorMember(id, outbound, 0, 0, "") })
        val routing = RoutingInput(RoutingInput.DEFAULT.profile, RoutingInput.DEFAULT.catalog, SelectorStore { group })
        for (order in listOf("warp-first", "warp-both")) {
            val generated = ConfigGenerator(ProfileProvider { if (it == 3L) selector else members[it] }, settings(order = order), routing = routing).build(3)
            assertTrue(generated.error, generated.ok)
            assertEquals(listOf(1L), generated.autoSelector!!.members.values.toList())
            assertTrue(generated.autoSelector.rejected.containsKey(2L))
        }
    }
}
