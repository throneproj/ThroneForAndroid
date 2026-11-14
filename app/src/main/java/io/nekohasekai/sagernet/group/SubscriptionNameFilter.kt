package io.nekohasekai.sagernet.group

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * The name and type filters of a subscription (SubscriptionFilter.cpp, f5fb80b4), applied as a fetch is parsed.
 * The desktop's country filter is only carried in the options: Android does not apply it.
 */
internal object SubscriptionNameFilter {

    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** FilterProtocol: [id] is what `type_exclude` stores, [types] the profile types it covers. */
    class Protocol(val id: String, @StringRes val label: Int, val types: Set<String>)

    /** FilterProtocols(), in the desktop's order. */
    val PROTOCOLS: List<Protocol> = listOf(
        Protocol("shadowsocks", R.string.action_shadowsocks, setOf("shadowsocks")),
        Protocol("vmess", R.string.action_vmess, setOf("vmess")),
        Protocol("vless", R.string.action_vless, setOf("vless", "xrayvless")),
        Protocol("trojan", R.string.action_trojan, setOf("trojan")),
        Protocol("hysteria", R.string.action_hysteria, setOf("hysteria")),
        Protocol("tuic", R.string.action_tuic, setOf("tuic")),
        Protocol("anytls", R.string.action_anytls, setOf("anytls")),
        Protocol("shadowtls", R.string.action_shadowtls, setOf("shadowtls")),
        Protocol("wireguard", R.string.action_wireguard, setOf("wireguard")),
        Protocol("socks", R.string.action_socks, setOf("socks")),
        Protocol("http", R.string.action_http, setOf("http")),
        Protocol("ssh", R.string.action_ssh, setOf("ssh")),
        Protocol("snell", R.string.action_snell, setOf("snell")),
        Protocol("juicity", R.string.action_juicity, setOf("juicity")),
        Protocol("mieru", R.string.action_mieru, setOf("mieru")),
        Protocol("naive", R.string.action_naive, setOf("naive")),
        Protocol("trusttunnel", R.string.action_trusttunnel, setOf("trusttunnel")),
        Protocol("masque", R.string.action_masque, setOf("masque")),
        Protocol("dnstt", R.string.action_dnstt, setOf("dnstt")),
        Protocol("masterdns", R.string.action_masterdns, setOf("masterdns")),
        Protocol("openvpn", R.string.action_openvpn, setOf("openvpn")),
        Protocol("openconnect", R.string.action_openconnect, setOf("openconnect")),
        Protocol("custom", R.string.custom_config, setOf("custom")),
    )

    /** Why [pattern] does not compile; null when it is blank or valid. */
    fun error(pattern: String): String? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Pattern.compile(trimmed, FLAGS)
            null
        } catch (e: PatternSyntaxException) {
            e.description ?: e.message.orEmpty()
        }
    }

    /**
     * ImportFilter without the country rules. A stored pattern that no longer compiles, or an unknown protocol id,
     * is skipped with a warning in the log, never read as matching everything.
     */
    class Filter(options: SubscriptionOptions, group: String) {
        private val include = compile(options.nameFilter, group)
        private val exclude = compile(options.nameExclude, group)
        private val types = HashSet<String>()

        init {
            for (id in options.typeExclude) {
                val protocol = PROTOCOLS.firstOrNull { it.id == id }
                if (protocol == null) {
                    Logs.w("$group: " + app.getString(R.string.subs_filter_unknown_type, id))
                    continue
                }
                types.addAll(protocol.types)
            }
        }

        val active: Boolean get() = include != null || exclude != null || types.isNotEmpty()

        /** judge: the type first, then the exclusion, then the inclusion, on the display name. */
        fun admits(type: String, name: () -> String): Boolean {
            if (type in types) return false
            if (include == null && exclude == null) return true
            val display = name()
            if (exclude != null && exclude.matcher(display).find()) return false
            return include == null || include.matcher(display).find()
        }

        fun admits(outbound: Outbound): Boolean = admits(outbound.type) { outbound.displayName() }
    }

    private fun compile(pattern: String, group: String): Pattern? {
        val trimmed = pattern.trim()
        if (trimmed.isEmpty()) return null
        return try {
            Pattern.compile(trimmed, FLAGS)
        } catch (e: PatternSyntaxException) {
            Logs.w("$group: " + app.getString(R.string.subs_filter_ignored, trimmed, e.description ?: e.message.orEmpty()))
            null
        }
    }
}
