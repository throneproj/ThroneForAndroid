package io.nekohasekai.sagernet.ui.json

import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.ui.json.JsonRelaxations.ExtraKey
import io.nekohasekai.sagernet.ui.json.engine.JsonFormatter
import io.nekohasekai.sagernet.ui.json.engine.SchemaStore

/**
 * "Edit as JSON" of a typed profile: its ExportToJson, Throne's stored form, checked against the sing-box schema with
 * the keys only Throne writes accepted and any other unknown key reported as a warning.
 */
object ProfileJson {

    private val TLS_KEYS = listOf(ExtraKey("/tls", "spoof_enabled"), ExtraKey("/tls/ech", "resolver"))

    /** Root keys of the stored forms (the types' exportToJson) that sing-box does not have. */
    private val THRONE_KEYS = mapOf(
        "hysteria" to rootKeys("recv_window_conn", "recv_window", "disable_mtu_discovery"),
        "socks" to rootKeys("uot"),
        "openconnect" to rootKeys("only_advertised_routes", "otp_profile_id", "server_path", "tunnel_dns"),
        "openvpn" to rootKeys("only_advertised_routes", "otp_profile_id", "tunnel_dns"),
    )

    private fun rootKeys(vararg keys: String) = keys.map { ExtraKey("", it) }

    fun text(outbound: Outbound): String = text(outbound.exportToJson())

    fun text(json: JsonObject): String {
        if (json.isEmpty()) return ""
        val compact = json.toCompact()
        return JsonFormatter.format(compact) ?: compact
    }

    /** Xray-core profiles have the Xray shape, which the sing-box schema does not describe. */
    fun schemaRoots(outbound: Outbound): List<String> = when {
        outbound.isXray() -> emptyList()
        outbound.isEndpoint() -> listOf(SchemaStore.ENDPOINT)
        else -> listOf(SchemaStore.OUTBOUND)
    }

    fun relaxations(outbound: Outbound) = JsonRelaxations(
        extraKeys = TLS_KEYS + (THRONE_KEYS[outbound.type] ?: emptyList()),
        typeAliases = mapOf("openvpn" to "openvpn-client"),
        unknownKeysAsWarnings = true,
    )
}
