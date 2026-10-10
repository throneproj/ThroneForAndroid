package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.database.preference.SettingsStore
import io.nekohasekai.sagernet.outbound.link.Hosts
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Every desktop settings key the app adopts (include/database/SettingsRepo.h, the maps of SettingsRepo.cpp) with its
 * desktop type and the Android default. The log filters are not adopted yet. An entry is the DataStore property
 * delegate of its key (property = lowerCamelCase of the key), gives
 * the preference screens their default through [SettingsStore], and validates restored values ([Setting.decode]):
 * a stored value its entry rejects reads as the default.
 */
object SettingsRegistry {

    sealed class Setting<T>(@JvmField val key: String, @JvmField val default: T) : ReadWriteProperty<Any?, T> {

        /** [value] in the desktop encoding. */
        abstract fun encode(value: T): String

        /** [raw] (desktop encoding) as a value of this key, or null when the key does not accept it. */
        abstract fun decode(raw: String): T?

        fun accepts(raw: String): Boolean = decode(raw) != null

        val encodedDefault: String by lazy { encode(default) }

        fun read(store: SettingsStore): T = store.getRaw(key)?.let(::decode) ?: default

        fun write(store: SettingsStore, value: T) = store.putString(key, encode(value))

        override fun getValue(thisRef: Any?, property: KProperty<*>): T = read(DataStore.configurationStore)

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) =
            write(DataStore.configurationStore, value)
    }

    class BoolSetting(key: String, default: Boolean) : Setting<Boolean>(key, default) {
        override fun encode(value: Boolean) = SettingsStore.encodeBoolean(value)
        override fun decode(raw: String): Boolean? = when (raw) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }

    class IntSetting(key: String, default: Int, private val valid: (Int) -> Boolean = { true }) :
        Setting<Int>(key, default) {
        override fun encode(value: Int) = value.toString()
        override fun decode(raw: String) = SettingsStore.decodeInt(raw)?.takeIf(valid)
    }

    class LongSetting(key: String, default: Long, private val valid: (Long) -> Boolean = { true }) :
        Setting<Long>(key, default) {
        override fun encode(value: Long) = value.toString()
        override fun decode(raw: String) = SettingsStore.decodeLong(raw)?.takeIf(valid)
    }

    class StringSetting(key: String, default: String, private val valid: (String) -> Boolean = { true }) :
        Setting<String>(key, default) {
        override fun encode(value: String) = value
        override fun decode(raw: String) = raw.takeIf(valid)
    }

    class StringListSetting(
        key: String,
        default: List<String>,
        private val valid: (List<String>) -> Boolean = { true },
    ) : Setting<List<String>>(key, default) {
        override fun encode(value: List<String>) = SettingsStore.encodeList(value)
        override fun decode(raw: String) = SettingsStore.decodeList(raw)?.takeIf(valid)
    }

    private val entries = ArrayList<Setting<*>>()

    private fun <S : Setting<*>> add(setting: S): S = setting.also { entries.add(it) }
    private fun bool(key: String, default: Boolean) = add(BoolSetting(key, default))
    private fun int(key: String, default: Int, valid: (Int) -> Boolean = { true }) = add(IntSetting(key, default, valid))
    private fun long(key: String, default: Long, valid: (Long) -> Boolean = { true }) =
        add(LongSetting(key, default, valid))

    private fun string(key: String, default: String, valid: (String) -> Boolean = { true }) =
        add(StringSetting(key, default, valid))

    private fun oneOf(key: String, default: String, values: List<String>) = string(key, default) { it in values }
    private fun stringList(key: String, default: List<String>, valid: (List<String>) -> Boolean = { true }) =
        add(StringListSetting(key, default, valid))

    // ------------------------------------------------------------------------------------------------ enums

    /** Const.hpp:16-18. */
    @JvmField
    val DOMAIN_STRATEGIES = listOf("", "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6")

    /** Most verbose first (BS.cpp:48). */
    @JvmField
    val LOG_LEVELS = listOf("trace", "debug", "info", "warn", "error", "fatal", "panic")

    @JvmField
    val XRAY_LOG_LEVELS = listOf("debug", "info", "warning", "error", "none")

    @JvmField
    val MUX_PROTOCOLS = listOf("h2mux", "smux", "yamux")

    @JvmField
    val FRAGMENT_IMPLEMENTATIONS = listOf("built-in", "custom")

    /** TLS.h:9. */
    @JvmField
    val TLS_SPOOF_METHODS = listOf("", "wrong-sequence", "wrong-checksum", "wrong-ack", "wrong-md5", "wrong-timestamp")

    @JvmField
    val DNS_FINAL_OUTS = listOf("remote", "direct")

    @JvmField
    val NTP_OUTBOUNDS = listOf("direct", "proxy")

    @JvmField
    val WARP_MODES = listOf("wireguard", "masque")

    /** defaultTunPrivateRanges (SettingsRepo.h:18-21). */
    @JvmField
    val DEFAULT_PRIVATE_RANGES = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4",
        "fc00::/7", "fe80::/10", "ff00::/8",
    )

    const val DEFAULT_TUN_IPV4_CIDR = "172.19.0.1/24"
    const val DEFAULT_TUN_IPV6_CIDR = "fdfe:dcba:9876::1/96"
    const val LOOPBACK_ADDRESS = "127.0.0.1"
    const val LAN_ADDRESS = "::"

    /** Sign-encoded interval minutes below this count as off (GuiUtils.hpp:45-59). */
    const val MIN_AUTO_UPDATE_MINUTES = 30

    // ------------------------------------------------------------------------------------------------ general

    /** Android "Auto connect" (decision D6). */
    @JvmField val REMEMBER_ENABLE = bool("remember_enable", false)
    @JvmField val SKIP_DELETE_CONFIRMATION = bool("skip_delete_confirmation", false)
    /** The selected group tab; resolved through GroupRepo (0 or a missing group = the first group). */
    @JvmField val CURRENT_GROUP = long("current_group", 0L) { it >= 0 }
    @JvmField val SHOW_CONFIG_SECURITY = bool("show_config_security", false)
    @JvmField val ALLOW_BETA_UPDATE = bool("allow_beta_update", false)

    // ------------------------------------------------------------------------------------------------ inbound

    /** Android adds the per-user offset on first use (DataStore.inboundSocksPort). */
    @JvmField val INBOUND_SOCKS_PORT = int("inbound_socks_port", 2080, SettingValidators::isPort)
    @JvmField val INBOUND_ADDRESS = string("inbound_address", LOOPBACK_ADDRESS) { it.isNotBlank() }
    @JvmField val DISABLE_MIXED_INBOUND = bool("disable_mixed_inbound", false)
    @JvmField val RANDOM_INBOUND_PORT = bool("random_inbound_port", false)
    @JvmField val INBOUND_AUTH = bool("inbound_auth", false)
    @JvmField val INBOUND_USER = string("inbound_user", "")
    @JvmField val INBOUND_PASS = string("inbound_pass", "")
    @JvmField val CUSTOM_INBOUND = string("custom_inbound", "{\"inbounds\": []}")

    // ------------------------------------------------------------------------------------------------ tun

    /** Android 9000 (decision D2; desktop 1500). */
    @JvmField val VPN_MTU = int("vpn_mtu", 9000) { it in 1000..10000 }
    @JvmField val VPN_IPV6 = bool("vpn_ipv6", false)
    @JvmField val VPN_TUN_IPV4_CIDR = string("vpn_tun_ipv4_cidr", DEFAULT_TUN_IPV4_CIDR) {
        SettingValidators.isCidr(it, ipv6 = false)
    }
    @JvmField val VPN_TUN_IPV6_CIDR = string("vpn_tun_ipv6_cidr", DEFAULT_TUN_IPV6_CIDR) {
        SettingValidators.isCidr(it, ipv6 = true)
    }
    @JvmField val DISABLE_PRIVATE_RANGE_BYPASS = bool("disable_private_range_bypass", false)
    @JvmField val VPN_PRIVATE_RANGES = stringList("vpn_private_ranges", DEFAULT_PRIVATE_RANGES) { ranges ->
        ranges.all { SettingValidators.isPrivateRange(it) }
    }
    @JvmField val ENABLE_TUN_ROUTING = bool("enable_tun_routing", false)

    // ------------------------------------------------------------------------------------------------ routing

    @JvmField val CURRENT_ROUTE_ID = long("current_route_id", 1L) { it > 0 }
    @JvmField val OUTBOUND_DOMAIN_STRATEGY = oneOf("outbound_domain_strategy", "", DOMAIN_STRATEGIES)
    @JvmField val DOMAIN_STRATEGY = oneOf("domain_strategy", "", DOMAIN_STRATEGIES)
    /** 0 GitHub, 1 jsDelivr (Cloudflare), 2 Gcore, 3 Quantil, 4 Fastly, 5 CDN (Const.hpp:51-62). */
    @JvmField val RULESET_MIRROR = int("ruleset_mirror", 1) { it in 0..5 }
    @JvmField val ADBLOCK_ENABLE = bool("adblock_enable", false)
    /** Sign-encoded minutes: negative is off, effective only from [MIN_AUTO_UPDATE_MINUTES]. */
    @JvmField val ROUTE_AUTO_UPDATE = int("route_auto_update", -1440)
    /** Epoch seconds. */
    @JvmField val ROUTE_AUTO_UPDATE_LAST = long("route_auto_update_last", 0L) { it >= 0 }

    // ------------------------------------------------------------------------------------------------ dns

    @JvmField val REMOTE_DNS = string("remote_dns", "https://8.8.8.8/dns-query")
    @JvmField val REMOTE_DNS_DISABLE_IPV6 = bool("remote_dns_disable_ipv6", false)
    @JvmField val DIRECT_DNS = string("direct_dns", "localhost")
    @JvmField val DIRECT_DNS_DISABLE_IPV6 = bool("direct_dns_disable_ipv6", false)
    @JvmField val CORE_BOX_UNDERLYING_DNS = string("core_box_underlying_dns", "")
    @JvmField val DNS_FINAL_OUT = oneOf("dns_final_out", "remote", DNS_FINAL_OUTS)
    @JvmField val ENABLE_DNS_ROUTING = bool("enable_dns_routing", true)
    /** Decision D4. */
    @JvmField val FAKEDNS = bool("fakedns", false)
    @JvmField val FAKEIP_DISABLE_IPV6 = bool("fakeip_disable_ipv6", false)
    @JvmField val DNS_USE_HOSTS = bool("dns_use_hosts", false)
    @JvmField val DNS_PREDEFINED_ENABLE = bool("dns_predefined_enable", true)
    @JvmField val DNS_PREDEFINED_RULES = stringList("dns_predefined_rules", listOf("127.0.0.1 localhost")) {
        SettingValidators.isPredefinedDns(it)
    }
    @JvmField val DNS_CACHE_CAPACITY = int("dns_cache_capacity", 65536) { it >= 0 }
    @JvmField val DNS_QUERY_TIMEOUT = string("dns_query_timeout", "", SettingValidators::isDurationOrEmpty)
    @JvmField val DNS_OPTIMISTIC = bool("dns_optimistic", false)
    @JvmField val DNS_OPTIMISTIC_TIMEOUT = string("dns_optimistic_timeout", "", SettingValidators::isDurationOrEmpty)
    @JvmField val DNS_DISABLE_CACHE = bool("dns_disable_cache", false)
    @JvmField val DNS_DISABLE_EXPIRE = bool("dns_disable_expire", false)
    @JvmField val DNS_PERSIST_CACHE = bool("dns_persist_cache", false)
    @JvmField val DNS_REVERSE_MAPPING = bool("dns_reverse_mapping", false)
    @JvmField val USE_DNS_OBJECT = bool("use_dns_object", false)
    @JvmField val DNS_OBJECT = string("dns_object", "")

    // ------------------------------------------------------------------------------------------------ presets

    @JvmField val MUX_PROTOCOL = oneOf("mux_protocol", "smux", MUX_PROTOCOLS)
    @JvmField val MUX_CONCURRENCY = int("mux_concurrency", 8) { it >= 0 }
    @JvmField val MUX_PADDING = bool("mux_padding", false)
    @JvmField val MUX_DEFAULT_ON = bool("mux_default_on", false)
    @JvmField val XRAY_MUX_CONCURRENCY = int("xray_mux_concurrency", 8) { it >= 0 }
    @JvmField val XRAY_MUX_DEFAULT_ON = bool("xray_mux_default_on", false)
    @JvmField val FRAGMENT_DEFAULT_ON = bool("fragment_default_on", false)
    @JvmField val FRAGMENT_IMPLEMENTATION = oneOf("fragment_implementation", "built-in", FRAGMENT_IMPLEMENTATIONS)
    @JvmField val FRAGMENT_SIZE = string("fragment_size", "10-100", SettingValidators::isRangeOrEmpty)
    @JvmField val FRAGMENT_SLEEP = string("fragment_sleep", "2-5", SettingValidators::isRangeOrEmpty)
    @JvmField val TLS_TRICKS_DEFAULT_ON = bool("tls_tricks_default_on", false)
    @JvmField val UTLS_FINGERPRINT = string("utlsFingerprint", "")
    /** The spoof keys only travel through restores: the Android generator never emits `spoof` (decision D8). */
    @JvmField val TLS_SPOOF = string("tls_spoof", "")
    @JvmField val TLS_SPOOF_METHOD = oneOf("tls_spoof_method", "", TLS_SPOOF_METHODS)
    @JvmField val TLS_SPOOF_DEFAULT_ON = bool("tls_spoof_default_on", false)
    @JvmField val H2_IDLE_TIMEOUT = string("h2_idle_timeout", "", SettingValidators::isDurationOrEmpty)
    @JvmField val H2_KEEP_ALIVE_PERIOD = string("h2_keep_alive_period", "", SettingValidators::isDurationOrEmpty)
    @JvmField val H2_STREAM_RECEIVE_WINDOW = string("h2_stream_receive_window", "")
    @JvmField val H2_CONNECTION_RECEIVE_WINDOW = string("h2_connection_receive_window", "")
    @JvmField val H2_MAX_CONCURRENT_STREAMS = int("h2_max_concurrent_streams", 0) { it >= 0 }
    @JvmField val QUIC_INITIAL_PACKET_SIZE = int("quic_initial_packet_size", 0) { it >= 0 }
    @JvmField val QUIC_DISABLE_PATH_MTU_DISCOVERY = bool("quic_disable_path_mtu_discovery", false)

    // ------------------------------------------------------------------------------------------------ testing

    @JvmField val TEST_URL = string("test_url", "http://cp.cloudflare.com/") { it.isNotBlank() }
    @JvmField val URL_TEST_TIMEOUT_MS = int("url_test_timeout_ms", 3000) { it > 0 }
    @JvmField val TEST_CONCURRENT = int("test_concurrent", 10) { it > 0 }
    @JvmField val DIRECT_TEST_URL = string("direct_test_url", "")
    /** 0 download + upload, 1 download, 2 upload, 3 simple download, 4 country (Const.hpp:39-49). */
    @JvmField val SPEED_TEST_MODE = int("speed_test_mode", 0) { it in 0..4 }
    @JvmField val SPEED_TEST_TIMEOUT_MS = int("speed_test_timeout_ms", 5000) { it > 0 }
    @JvmField val SIMPLE_DL_URL = string("simple_dl_url", "http://cachefly.cachefly.net/1mb.test") {
        SpeedTestSettings.isValidHttpUrl(it)
    }

    // ------------------------------------------------------------------------------------------------ subscriptions

    /** Empty means the app's own user agent. */
    @JvmField val USER_AGENT2 = string("user_agent2", "")
    @JvmField val NET_USE_PROXY = bool("net_use_proxy", false)
    @JvmField val NET_INSECURE = bool("net_insecure", false)
    /** Sign-encoded minutes like [ROUTE_AUTO_UPDATE]. */
    @JvmField val SUB_AUTO_UPDATE = int("sub_auto_update", -30)
    /** Epoch seconds. */
    @JvmField val SUB_AUTO_UPDATE_LAST = long("sub_auto_update_last", 0L) { it >= 0 }
    @JvmField val SUB_CLEAR = bool("sub_clear", false)
    @JvmField val SUB_SHOW_CHANGE_POPUP = bool("sub_show_change_popup", true)
    @JvmField val SUB_SEND_HWID = bool("sub_send_hwid", false)
    /** `key=value` pairs separated by commas. */
    @JvmField val SUB_CUSTOM_HWID_PARAMS = string("sub_custom_hwid_params", "")
    @JvmField val ALLOW_STOPPING_ACTIVE_PROFILE = bool("allow_stopping_active_profile", false)

    // ------------------------------------------------------------------------------------------------ warp

    @JvmField val ENABLE_WARP = bool("enable_warp", false)
    @JvmField val WARP_MODE = oneOf("warp_mode", "wireguard", WARP_MODES)
    @JvmField val WARP_EP = string("warp_ep", "")
    @JvmField val WARP_PRIVATE_KEY = string("warp_private_key", "")
    @JvmField val WARP_PUBLIC_KEY = string("warp_public_key", "")
    @JvmField val WARP_IFC_ADDRS = stringList("warp_ifc_addrs", emptyList())
    @JvmField val WARP_RESERVED = stringList("warp_reserved", emptyList())
    @JvmField val WARP_TOS_ACCEPTED = bool("warp_tos_accepted", false)
    @JvmField val WARP_MASQUE_EP = string("warp_masque_ep", "")
    @JvmField val WARP_MASQUE_PRIVATE_KEY = string("warp_masque_private_key", "")
    @JvmField val WARP_MASQUE_PEER_PUBLIC_KEY = string("warp_masque_peer_public_key", "")
    @JvmField val WARP_MASQUE_IFC_ADDRS = stringList("warp_masque_ifc_addrs", emptyList())
    @JvmField val WARP_MASQUE_SNI = string("warp_masque_sni", "consumer-masque.cloudflareclient.com")
    /** 0 HTTP/3 with fallback, 1 HTTP/3 only, 2 HTTP/2 (SettingsRepo.h:266). */
    @JvmField val WARP_MASQUE_HTTP_MODE = int("warp_masque_http_mode", 0) { it in 0..2 }
    /** Registration API hosts tried in order; empty = api.cloudflareclient.com. */
    @JvmField val WARP_API_HOSTS = stringList("warp_api_hosts", emptyList())

    // ------------------------------------------------------------------------------------------------ core

    /** Android "warn" (decision D9; desktop "info"). */
    @JvmField val LOG_LEVEL = oneOf("log_level", "warn", LOG_LEVELS)
    @JvmField val XRAY_LOG_LEVEL = oneOf("xray_log_level", "warning", XRAY_LOG_LEVELS)
    @JvmField val ENABLE_STATS = bool("enable_stats", true)
    @JvmField val DISABLE_TRAFFIC_STATS = bool("disable_traffic_stats", false)
    /** Sign-encoded port: at most 0 is off. */
    @JvmField val CORE_BOX_CLASH_API = int("core_box_clash_api", -9090) { it in -65535..65535 }
    @JvmField val CORE_BOX_CLASH_LISTEN_ADDR = string("core_box_clash_listen_addr", LOOPBACK_ADDRESS) { it.isNotBlank() }
    @JvmField val CORE_BOX_CLASH_API_SECRET = string("core_box_clash_api_secret", "")
    /**
     * The sing-box API / dashboard port, sign-encoded like core_box_clash_api (at most 0 is off). Android serves the
     * dashboard by default, where it stands in for the desktop's stats panels (desktop -9091).
     */
    @JvmField val CORE_BOX_API_PORT = int("core_box_api_port", 9091) { it in -65535..65535 }
    /** Generated on first use when empty (SettingsRepo.cpp:15-19). */
    @JvmField val CORE_BOX_API_SECRET = string("core_box_api_secret", "")
    @JvmField val CORE_DNS_IN_PORT = int("core_dns_in_port", 5533, SettingValidators::isPort)
    /** 0 XHTTP only, 1 XHTTP and Reality, 2 all VLESS (Const.hpp:70-75). */
    @JvmField val XRAY_VLESS_PREFERENCE = int("xray_vless_preference", 1) { it in 0..2 }
    /** Where geoip.dat / geosite.dat come from when an Xray config needs them (SettingsRepo.h:305-307). */
    @JvmField val XRAY_GEOIP_URL = string("xray_geoip_url", "https://github.com/Loyalsoldier/v2ray-rules-dat/raw/release/geoip.dat")
    @JvmField val XRAY_GEOSITE_URL = string("xray_geosite_url", "https://github.com/Loyalsoldier/v2ray-rules-dat/raw/release/geosite.dat")
    @JvmField val SKIP_CERT = bool("skip_cert", false)
    @JvmField val USE_MOZILLA_CERTS = bool("use_mozilla_certs", false)
    @JvmField val ENABLE_NTP = bool("enable_ntp", false)
    @JvmField val NTP_SERVER_ADDRESS = string("ntp_server_address", "")
    @JvmField val NTP_SERVER_PORT = int("ntp_server_port", 0) { it in 0..65535 }
    @JvmField val NTP_INTERVAL = string("ntp_interval", "", SettingValidators::isDurationOrEmpty)
    @JvmField val NTP_OUTBOUND = oneOf("ntp_outbound", "direct", NTP_OUTBOUNDS)

    /** The Android-only keys of the same table (camelCase, never read by the desktop). */
    @JvmField
    val ANDROID_KEYS: Set<String> = setOf(
        Key.SERVICE_MODE, Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL, Key.APPEND_HTTP_PROXY, Key.HTTP_PROXY_BYPASS,
        Key.METERED_NETWORK, Key.WAKE_RESET_CONNECTIONS, Key.NETWORK_CHANGE_RESET_CONNECTIONS,
        Key.SHOW_DIRECT_SPEED, Key.SHOW_GROUP_IN_NOTIFICATION, Key.NOTIFICATION_ACTIONS,
        Key.USE_SYSTEM_THEME, Key.APP_THEME, Key.NIGHT_THEME, Key.AMOLED_THEME, Key.APP_LANGUAGE,
        Key.ALWAYS_SHOW_ADDRESS, Key.GROUP_LAYOUT_MODE,
        Key.HIDE_FROM_RECENT_APPS, Key.LOG_BUF_SIZE, Key.APP_TLS_VERSION,
        Key.WEBDAV_SERVER, Key.WEBDAV_USERNAME, Key.WEBDAV_PASSWORD, Key.WEBDAV_PATH,
        Key.PROFILE_CURRENT, Key.RUNNING_PROFILES, Key.PROFILE_ID, Key.PREVIEW_HINT_DISMISSED_VERSION,
        Key.UPDATE_CHECK_AUTO, Key.UPDATE_SKIPPED_VERSION_CODE, Key.RESUME_AFTER_UPDATE, Key.BATTERY_PROMPT_SHOWN,
        Key.LOG_EXPORT_REDACT, Key.HWID_FALLBACK, Key.WIFI_PERMISSION_ASKED, Key.SERVICE_ERROR,
        Key.SERVICE_ERROR_DNS, Key.SERVICE_ERROR_GEO, Key.SERVICE_ERROR_RULE_SETS, Key.START_WITHOUT_RULE_SETS,
    )

    /**
     * Keys a backup never exports and a restore never overwrites (R10 §8.3). core_box_api_port is one: the desktop
     * keeps it off by default and Android on, so neither side's backup should flip the other's dashboard.
     */
    @JvmField
    val DEVICE_LOCAL_KEYS: Set<String> = setOf(
        Key.WEBDAV_SERVER, Key.WEBDAV_USERNAME, Key.WEBDAV_PASSWORD, Key.WEBDAV_PATH,
        Key.BATTERY_PROMPT_SHOWN, Key.HWID_FALLBACK, Key.RESUME_AFTER_UPDATE, Key.UPDATE_SKIPPED_VERSION_CODE,
        Key.WIFI_PERMISSION_ASKED, Key.SERVICE_ERROR, Key.SERVICE_ERROR_DNS, Key.SERVICE_ERROR_GEO,
        Key.SERVICE_ERROR_RULE_SETS, Key.START_WITHOUT_RULE_SETS, Key.RUNNING_PROFILES, CORE_BOX_API_PORT.key,
    )

    // ------------------------------------------------------------------------------------------------ lookup

    /** Every registered key, in declaration order. */
    @JvmStatic
    val all: List<Setting<*>> get() = entries

    private val byKey: Map<String, Setting<*>> by lazy { entries.associateBy { it.key } }

    @JvmStatic
    fun find(key: String): Setting<*>? = byKey[key]

    /** The encoded default of a registered key, null for any other key. */
    @JvmStatic
    fun defaultOf(key: String): String? = byKey[key]?.encodedDefault

    /** Whether log_level [level] lets a line of [lineLevel] through; an unknown [level] counts as the default. */
    @JvmStatic
    fun logAllows(level: String, lineLevel: String): Boolean {
        val current = LOG_LEVELS.indexOf(level).takeIf { it >= 0 } ?: LOG_LEVELS.indexOf(LOG_LEVEL.default)
        return LOG_LEVELS.indexOf(lineLevel) >= current
    }

    /** An inbound address counts as LAN access unless it is a loopback one (mainwindow_setup.cpp:742). */
    @JvmStatic
    fun isLoopbackAddress(address: String): Boolean {
        val a = address.trim().removePrefix("[").removeSuffix("]")
        return a.isEmpty() || a == "localhost" || a == "::1" || a.startsWith("127.")
    }
}

/** The desktop's input checks (dialog_vpn_settings.cpp:20-41, generate.cpp IsValidDuration, dialog_preset_settings.cpp). */
object SettingValidators {

    private val DURATION = Regex("^(?:\\d+(?:\\.\\d+)?(?:ns|us|ms|s|m|h|d))+$")
    private val RANGE = Regex("^[0-9]+(-[0-9]+)?$")

    @JvmStatic
    fun isPort(port: Int): Boolean = port in 1..65535

    @JvmStatic
    fun isPort(text: String): Boolean = text.trim().toIntOrNull()?.let(::isPort) == true

    @JvmStatic
    fun isDuration(text: String): Boolean = DURATION.matches(text)

    @JvmStatic
    fun isDurationOrEmpty(text: String): Boolean = text.isEmpty() || isDuration(text)

    /** A fragment size / sleep: `n` or `min-max`. */
    @JvmStatic
    fun isRangeOrEmpty(text: String): Boolean = text.isEmpty() || RANGE.matches(text)

    /** `address/prefix` of the requested family (IsValidCIDR). */
    @JvmStatic
    fun isCidr(text: String, ipv6: Boolean): Boolean {
        val parts = text.trim().split('/')
        if (parts.size != 2) return false
        val prefix = parts[1].trim().toIntOrNull() ?: return false
        val address = parts[0].trim()
        return if (ipv6) {
            address.contains(':') && Hosts.parseIpv6(address) != null && prefix in 0..128
        } else {
            !address.contains(':') && address.contains('.') && Hosts.parseIpv4(address) != null && prefix in 0..32
        }
    }

    /** An address or CIDR of either family that does not cover every address (IsValidRange + the /0 check). */
    @JvmStatic
    fun isPrivateRange(text: String): Boolean {
        val range = text.trim()
        if (!range.contains('.') && !range.contains(':')) return false
        val parts = range.split('/')
        if (parts.size > 2) return false
        val ipv6 = parts[0].contains(':')
        if (parts.size == 1) return if (ipv6) Hosts.parseIpv6(parts[0]) != null else Hosts.parseIpv4(parts[0]) != null
        return isCidr(range, ipv6) && parts[1].trim().toInt() > 0
    }

    /** Hosts-file lines `address name…` (ParsePredefinedDNS). */
    @JvmStatic
    fun isPredefinedDns(lines: List<String>): Boolean =
        io.nekohasekai.sagernet.outbound.config.PredefinedDns.parse(lines) != null

    /** Non-blank lines of a multi-line editor. */
    @JvmStatic
    fun lines(text: String?): List<String> = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }
}
