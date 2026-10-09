package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink

/** Peer (wireguard.h:6-25, wireguard.cpp:21-122): the single peer of a wireguard endpoint. */
class WireGuardPeer {
    @JvmField var address: String = ""
    @JvmField var port: Int = 0
    @JvmField var public_key: String = ""
    @JvmField var pre_shared_key: String = ""
    @JvmField var reserved: MutableList<Int> = ArrayList()

    /** Seconds, or an AmneziaWG 3.0 range such as "22-30". */
    @JvmField var persistent_keepalive: String = ""

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** wireguard.cpp:21-53; values are FullyDecoded because PrettyDecoded keeps %2F/%2B/%2C and corrupts base64 keys. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        address = url.host
        port = url.port(51820)
        when {
            q.has("public_key") -> public_key = q.valueFully("public_key")
            q.has("publickey") -> public_key = q.valueFully("publickey")
            q.has("peer_public_key") -> public_key = q.valueFully("peer_public_key")
        }
        when {
            q.has("pre_shared_key") -> pre_shared_key = q.valueFully("pre_shared_key")
            q.has("preshared_key") -> pre_shared_key = q.valueFully("preshared_key")
            q.has("presharedkey") -> pre_shared_key = q.valueFully("presharedkey")
            q.has("psk") -> pre_shared_key = q.valueFully("psk")
        }
        if (q.has("reserved")) {
            val raw = q.valueFully("reserved")
            if (raw.isNotEmpty()) {
                for (item in QtStrings.splitSkipEmpty(raw.replace(',', '-'), "-")) {
                    val value = QtStrings.toIntOrNull(item) ?: continue
                    if (value in 0..255) reserved.add(value)
                }
            }
        }
        when {
            q.has("persistent_keepalive_interval") -> persistent_keepalive = q.valueFully("persistent_keepalive_interval")
            q.has("persistent_keepalive") -> persistent_keepalive = q.valueFully("persistent_keepalive")
            q.has("keepalive") -> persistent_keepalive = q.valueFully("keepalive")
        }
        return true
    }

    /** wireguard.cpp:55-69: the keepalive accepts a number or a range string. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("address")) address = obj.string("address")
        if (obj.contains("port")) port = obj.int("port")
        if (obj.contains("public_key")) public_key = obj.string("public_key")
        if (obj.contains("pre_shared_key")) pre_shared_key = obj.string("pre_shared_key")
        if (obj.contains("reserved")) reserved = obj.array("reserved").mapTo(ArrayList()) { JsonValues.toInt(it) }
        if (obj.contains("persistent_keepalive_interval")) {
            persistent_keepalive = if (obj.isString("persistent_keepalive_interval")) obj.string("persistent_keepalive_interval")
            else obj.int("persistent_keepalive_interval").toString()
        }
        return true
    }

    /** wireguard.cpp:71-84. */
    fun exportToLink(): List<Pair<String, String>> {
        val q = ArrayList<Pair<String, String>>()
        if (public_key.isNotEmpty()) q.add("public_key" to public_key)
        if (pre_shared_key.isNotEmpty()) q.add("pre_shared_key" to pre_shared_key)
        if (reserved.isNotEmpty()) q.add("reserved" to reserved.joinToString("-"))
        if (persistent_keepalive.isNotEmpty()) q.add("persistent_keepalive_interval" to persistent_keepalive)
        return q
    }

    /** wireguard.cpp:86-95. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (address.isNotEmpty()) obj["address"] = address
        if (port > 0) obj["port"] = port
        if (public_key.isNotEmpty()) obj["public_key"] = public_key
        if (pre_shared_key.isNotEmpty()) obj["pre_shared_key"] = pre_shared_key
        if (reserved.isNotEmpty()) obj["reserved"] = reservedArray()
        writeKeepalive(obj)
        return obj
    }

    /** wireguard.cpp:97-107: ExportToJson plus the catch-all allowed_ips. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        obj["allowed_ips"] = JsonValues.stringArray(listOf("0.0.0.0/0", "::/0"))
        return obj
    }

    private fun reservedArray(): JsonArray {
        val arr = JsonArray()
        for (value in reserved) arr.add(value)
        return arr
    }

    // wireguard.cpp:110-122: a range must be a string and a plain interval a number; anything else makes the core
    // refuse to start, so a value that is neither is dropped.
    private fun writeKeepalive(obj: JsonObject) {
        if (persistent_keepalive.isEmpty()) return
        val seconds = QtStrings.toIntOrNull(persistent_keepalive)
        if (seconds != null) {
            if (seconds > 0) obj["persistent_keepalive_interval"] = seconds
            return
        }
        if (KEEPALIVE_RANGE.containsMatchIn(persistent_keepalive)) obj["persistent_keepalive_interval"] = persistent_keepalive
    }

    companion object {
        private val KEEPALIVE_RANGE = Regex("^\\d+-\\d+$")
    }
}

/**
 * wireguard / AmneziaWG (include/configs/outbounds/wireguard.h, src/configs/outbounds/wireguard.cpp): a sing-box
 * endpoint. The server address and port live in [peer]; the base `server`/`serverPort` are only what the last link
 * parse left there, exactly as on the desktop, so callers use [getAddress]/[getPort].
 */
class WireGuard : Outbound("wireguard") {
    @JvmField var private_key: String = ""
    @JvmField var peer: WireGuardPeer = WireGuardPeer()
    @JvmField var address: MutableList<String> = ArrayList()
    @JvmField var mtu: Int = 1420
    @JvmField var system: Boolean = false
    @JvmField var worker_count: Int = 0
    @JvmField var udp_timeout: String = ""

    /** AmneziaWG: jc/jmin/jmax and s1-s4 are integers; h1-h4 (magic headers) and i1-i5 (signature packets) are strings. */
    @JvmField var enable_amnezia: Boolean = false
    @JvmField var jc: Int = 0
    @JvmField var jmin: Int = 0
    @JvmField var jmax: Int = 0
    @JvmField var s1: Int = 0
    @JvmField var s2: Int = 0
    @JvmField var s3: Int = 0
    @JvmField var s4: Int = 0
    @JvmField var h1: String = ""
    @JvmField var h2: String = ""
    @JvmField var h3: String = ""
    @JvmField var h4: String = ""
    @JvmField var i1: String = ""
    @JvmField var i2: String = ""
    @JvmField var i3: String = ""
    @JvmField var i4: String = ""
    @JvmField var i5: String = ""

    /** AmneziaWG 3.0: header_protection_key is a base64 32-byte key; the rest are ranges ("30" or "22-30"). */
    @JvmField var header_protection_key: String = ""
    @JvmField var content_padding_addition: String = ""
    @JvmField var rekey_after_time: String = ""
    @JvmField var rekey_timeout: String = ""
    @JvmField var reject_after_time: String = ""
    @JvmField var keepalive_timeout: String = ""
    @JvmField var max_handshake_attempts: String = ""

    /** AmneziaWG 3.1 */
    @JvmField var random_trailers: Boolean = false
    @JvmField var disable_cookies: Boolean = false

    /**
     * wireguard.cpp:124-245: wg-quick / AmneziaWG INI text, or a wg:// / wireguard:// / awg:// URL. The scheme is not
     * checked, and awg:// alone does not enable AmneziaWG; only its parameters do, as on the desktop.
     */
    override fun parseFromLink(link: String): Boolean {
        if (link.contains("[Interface]") && link.contains("[Peer]")) return parseIni(link)

        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)

        when {
            q.has("private_key") -> private_key = q.valueFully("private_key")
            q.has("privatekey") -> private_key = q.valueFully("privatekey")
            url.userName.isNotEmpty() -> private_key = url.userName
        }

        peer.parseFromLink(url)
        server = peer.address
        serverPort = peer.port

        when {
            q.has("local_address") -> address = QtStrings.splitSkipEmpty(q.valueFully("local_address"), "-")
            q.has("address") -> address = QtStrings.splitSkipEmpty(q.valueFully("address").replace(" ", ""), ",")
            q.has("ip") -> address = QtStrings.splitSkipEmpty(q.valueFully("ip").replace(" ", ""), ",")
        }

        if (q.has("mtu")) mtu = QtStrings.toInt(q.valueFully("mtu"))
        if (q.has("use_system_interface")) system = q.valueFully("use_system_interface") == "true"
        if (q.has("workers")) worker_count = QtStrings.toInt(q.valueFully("workers"))
        if (q.has("udp_timeout")) udp_timeout = q.valueFully("udp_timeout")

        if (q.valueFully("enable_amnezia") == "true") enable_amnezia = true
        fun intKey(key: String, set: (Int) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(QtStrings.toInt(q.valueFully(key)))
            enable_amnezia = true
            return true
        }
        fun strKey(key: String, set: (String) -> Unit): Boolean {
            if (!q.has(key)) return false
            set(q.valueFully(key))
            enable_amnezia = true
            return true
        }
        intKey("jc") { jc = it }
        intKey("jmin") { jmin = it }
        intKey("jmax") { jmax = it }
        intKey("s1") { s1 = it }
        intKey("s2") { s2 = it }
        intKey("s3") { s3 = it }
        intKey("s4") { s4 = it }
        strKey("h1") { h1 = it }
        strKey("h2") { h2 = it }
        strKey("h3") { h3 = it }
        strKey("h4") { h4 = it }
        strKey("i1") { i1 = it }
        strKey("i2") { i2 = it }
        strKey("i3") { i3 = it }
        strKey("i4") { i4 = it }
        strKey("i5") { i5 = it }
        if (!strKey("header_protection_key") { header_protection_key = it }) strKey("headerprotectionkey") { header_protection_key = it }
        if (!strKey("content_padding_addition") { content_padding_addition = it }) strKey("contentpaddingaddition") { content_padding_addition = it }
        strKey("rekey_after_time") { rekey_after_time = it }
        strKey("rekey_timeout") { rekey_timeout = it }
        strKey("reject_after_time") { reject_after_time = it }
        strKey("keepalive_timeout") { keepalive_timeout = it }
        strKey("max_handshake_attempts") { max_handshake_attempts = it }
        strKey("random_trailers") { random_trailers = parseAmneziaBool(it) }
        strKey("disable_cookies") { disable_cookies = parseAmneziaBool(it) }

        fixAddress()
        return !(private_key.isEmpty() || peer.public_key.isEmpty() || server.isEmpty())
    }

    // wireguard.cpp:125-184: keys are matched lower-cased; no base parse, so the name and dial fields stay untouched.
    private fun parseIni(text: String): Boolean {
        for (line in QtStrings.split(text, "\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith('#')) continue
            if (trimmed == "[Peer]" || trimmed == "[Interface]") continue
            val eqIdx = trimmed.indexOf('=')
            if (eqIdx < 0) continue
            val key = trimmed.substring(0, eqIdx).trim().lowercase()
            val value = trimmed.substring(eqIdx + 1).trim()
            when (key) {
                "privatekey" -> private_key = value
                "address" -> address = QtStrings.splitSkipEmpty(value.replace(" ", ""), ",")
                "mtu" -> mtu = QtStrings.toInt(value)
                "publickey" -> peer.public_key = value
                "presharedkey", "preshared_key", "psk" -> peer.pre_shared_key = value
                "persistentkeepalive", "persistent_keepalive" -> peer.persistent_keepalive = value
                "endpoint" -> {
                    val lastColon = value.lastIndexOf(':')
                    if (lastColon != -1) {
                        var host = value.substring(0, lastColon).trim()
                        if (host.startsWith('[') && host.endsWith(']')) {
                            host = if (host.length >= 2) host.substring(1, host.length - 1) else ""
                        }
                        val port = QtStrings.toInt(value.substring(lastColon + 1).trim())
                        peer.address = host
                        peer.port = if (port > 0) port else 51820
                        server = peer.address
                        serverPort = peer.port
                    }
                }
                "jc" -> { jc = QtStrings.toInt(value); enable_amnezia = true }
                "jmin" -> { jmin = QtStrings.toInt(value); enable_amnezia = true }
                "jmax" -> { jmax = QtStrings.toInt(value); enable_amnezia = true }
                "s1" -> { s1 = QtStrings.toInt(value); enable_amnezia = true }
                "s2" -> { s2 = QtStrings.toInt(value); enable_amnezia = true }
                "s3" -> { s3 = QtStrings.toInt(value); enable_amnezia = true }
                "s4" -> { s4 = QtStrings.toInt(value); enable_amnezia = true }
                "h1" -> { h1 = value; enable_amnezia = true }
                "h2" -> { h2 = value; enable_amnezia = true }
                "h3" -> { h3 = value; enable_amnezia = true }
                "h4" -> { h4 = value; enable_amnezia = true }
                "i1" -> { i1 = value; enable_amnezia = true }
                "i2" -> { i2 = value; enable_amnezia = true }
                "i3" -> { i3 = value; enable_amnezia = true }
                "i4" -> { i4 = value; enable_amnezia = true }
                "i5" -> { i5 = value; enable_amnezia = true }
                "headerprotectionkey", "header_protection_key" -> { header_protection_key = value; enable_amnezia = true }
                "contentpaddingaddition", "content_padding_addition" -> { content_padding_addition = value; enable_amnezia = true }
                "rekeyaftertime", "rekey_after_time" -> { rekey_after_time = value; enable_amnezia = true }
                "rekeytimeout", "rekey_timeout" -> { rekey_timeout = value; enable_amnezia = true }
                "rejectaftertime", "reject_after_time" -> { reject_after_time = value; enable_amnezia = true }
                "keepalivetimeout", "keepalive_timeout" -> { keepalive_timeout = value; enable_amnezia = true }
                "maxhandshakeattempts", "max_handshake_attempts" -> { max_handshake_attempts = value; enable_amnezia = true }
                "randomtrailers", "random_trailers" -> { random_trailers = parseAmneziaBool(value); enable_amnezia = true }
                "disablecookies", "disable_cookies" -> { disable_cookies = parseAmneziaBool(value); enable_amnezia = true }
            }
        }
        fixAddress()
        return private_key.isNotEmpty() && peer.public_key.isNotEmpty()
    }

    /** wireguard.cpp:247-260. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "wireguard") return false
        super.parseFromJson(obj)
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("peers") && obj.isArray("peers")) {
            val peers = obj.array("peers")
            if (peers.isNotEmpty()) peer.parseFromJson(peers[0] as? JsonObject ?: JsonObject())
        }
        if (obj.contains("address")) address = obj.array("address").strings()
        if (obj.contains("mtu")) mtu = obj.int("mtu")
        if (obj.contains("system")) system = obj.bool("system")
        if (obj.contains("worker_count")) worker_count = obj.int("worker_count")
        if (obj.contains("udp_timeout")) udp_timeout = obj.string("udp_timeout")
        if (obj.contains("amnezia_wg")) amneziaFromJson(obj.obj("amnezia_wg"))
        fixAddress()
        return true
    }

    /** wireguard.cpp:262-312: `wg://<peer address>:<peer port>?...`, dial items before the peer items. */
    override fun exportToLink(): String {
        val url = LinkBuilder("wg")
        url.host = peer.address
        url.port = peer.port
        if (name.isNotEmpty()) url.fragment = name

        if (private_key.isNotEmpty()) url.addQueryItem("private_key", private_key)
        if (address.isNotEmpty()) url.addQueryItem("local_address", address.joinToString("-"))
        if (mtu > 0 && mtu != 1420) url.addQueryItem("mtu", mtu.toString())
        if (system) url.addQueryItem("use_system_interface", "true")
        if (worker_count > 0) url.addQueryItem("workers", worker_count.toString())
        if (udp_timeout.isNotEmpty()) url.addQueryItem("udp_timeout", udp_timeout)

        if (enable_amnezia) {
            url.addQueryItem("enable_amnezia", "true")
            if (jc > 0) url.addQueryItem("jc", jc.toString())
            if (jmin > 0) url.addQueryItem("jmin", jmin.toString())
            if (jmax > 0) url.addQueryItem("jmax", jmax.toString())
            if (s1 > 0) url.addQueryItem("s1", s1.toString())
            if (s2 > 0) url.addQueryItem("s2", s2.toString())
            if (s3 > 0) url.addQueryItem("s3", s3.toString())
            if (s4 > 0) url.addQueryItem("s4", s4.toString())
            if (h1.isNotEmpty()) url.addQueryItem("h1", h1)
            if (h2.isNotEmpty()) url.addQueryItem("h2", h2)
            if (h3.isNotEmpty()) url.addQueryItem("h3", h3)
            if (h4.isNotEmpty()) url.addQueryItem("h4", h4)
            if (i1.isNotEmpty()) url.addQueryItem("i1", i1)
            if (i2.isNotEmpty()) url.addQueryItem("i2", i2)
            if (i3.isNotEmpty()) url.addQueryItem("i3", i3)
            if (i4.isNotEmpty()) url.addQueryItem("i4", i4)
            if (i5.isNotEmpty()) url.addQueryItem("i5", i5)
            if (header_protection_key.isNotEmpty()) url.addQueryItem("header_protection_key", header_protection_key)
            if (content_padding_addition.isNotEmpty()) url.addQueryItem("content_padding_addition", content_padding_addition)
            if (rekey_after_time.isNotEmpty()) url.addQueryItem("rekey_after_time", rekey_after_time)
            if (rekey_timeout.isNotEmpty()) url.addQueryItem("rekey_timeout", rekey_timeout)
            if (reject_after_time.isNotEmpty()) url.addQueryItem("reject_after_time", reject_after_time)
            if (keepalive_timeout.isNotEmpty()) url.addQueryItem("keepalive_timeout", keepalive_timeout)
            if (max_handshake_attempts.isNotEmpty()) url.addQueryItem("max_handshake_attempts", max_handshake_attempts)
            if (random_trailers) url.addQueryItem("random_trailers", "true")
            if (disable_cookies) url.addQueryItem("disable_cookies", "true")
        }

        url.addQueryItems(baseLinkQuery())
        url.addQueryItems(peer.exportToLink())
        return url.build()
    }

    /** wireguard.cpp:314-335: no server/server_port; the peer carries them. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.exportToJson())
        writeFields(obj)
        val peerObj = peer.exportToJson()
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return obj
    }

    /** wireguard.cpp:337-357. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "wireguard"
        if (name.isNotEmpty()) obj["tag"] = name
        obj.merge(dial.build(ctx))
        writeFields(obj)
        val peerObj = peer.build(ctx)
        if (peerObj.isNotEmpty()) obj["peers"] = JsonArray.of(peerObj)
        return BuildResult(obj)
    }

    private fun writeFields(obj: JsonObject) {
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (address.isNotEmpty()) obj["address"] = JsonValues.stringArray(address)
        if (mtu > 0) obj["mtu"] = mtu
        if (system) obj["system"] = system
        if (worker_count > 0) obj["worker_count"] = worker_count
        if (udp_timeout.isNotEmpty()) obj["udp_timeout"] = udp_timeout
        val amnezia = amneziaToJson()
        if (amnezia.isNotEmpty()) obj["amnezia_wg"] = amnezia
    }

    /** wireguard.cpp:359-373: the peer holds the address the profile list and the DNS resolver work with. */
    override fun setAddress(newAddr: String) {
        peer.address = newAddr
    }

    override fun getAddress(): String = peer.address

    override fun setPort(newPort: Int) {
        peer.port = newPort
    }

    override fun getPort(): String = peer.port.toString()

    /** wireguard.cpp:375-377. */
    override fun displayAddress(): String = Hosts.displayAddress(peer.address, peer.port)

    /** wireguard.cpp:379-381. */
    override fun displayType(): String = "WireGuard"

    /** wireguard.cpp:383-385. */
    override fun security(): SecurityInfo = SecurityInfo("Encrypted", if (enable_amnezia) "AmneziaWG" else "", SecurityLevel.Secure)

    /** wireguard.cpp:387-389. */
    override fun isEndpoint(): Boolean = true

    /** wireguard.cpp:391-420. */
    private fun amneziaToJson(): JsonObject {
        val obj = JsonObject()
        if (!enable_amnezia) return obj
        if (jc > 0) obj["jc"] = jc
        if (jmin > 0) obj["jmin"] = jmin
        if (jmax > 0) obj["jmax"] = jmax
        if (s1 > 0) obj["s1"] = s1
        if (s2 > 0) obj["s2"] = s2
        if (s3 > 0) obj["s3"] = s3
        if (s4 > 0) obj["s4"] = s4
        if (h1.isNotEmpty()) obj["h1"] = h1
        if (h2.isNotEmpty()) obj["h2"] = h2
        if (h3.isNotEmpty()) obj["h3"] = h3
        if (h4.isNotEmpty()) obj["h4"] = h4
        if (i1.isNotEmpty()) obj["i1"] = i1
        if (i2.isNotEmpty()) obj["i2"] = i2
        if (i3.isNotEmpty()) obj["i3"] = i3
        if (i4.isNotEmpty()) obj["i4"] = i4
        if (i5.isNotEmpty()) obj["i5"] = i5
        if (header_protection_key.isNotEmpty()) obj["header_protection_key"] = header_protection_key
        if (content_padding_addition.isNotEmpty()) obj["content_padding_addition"] = content_padding_addition
        if (rekey_after_time.isNotEmpty()) obj["rekey_after_time"] = rekey_after_time
        if (rekey_timeout.isNotEmpty()) obj["rekey_timeout"] = rekey_timeout
        if (reject_after_time.isNotEmpty()) obj["reject_after_time"] = reject_after_time
        if (keepalive_timeout.isNotEmpty()) obj["keepalive_timeout"] = keepalive_timeout
        if (max_handshake_attempts.isNotEmpty()) obj["max_handshake_attempts"] = max_handshake_attempts
        if (random_trailers) obj["random_trailers"] = true
        if (disable_cookies) obj["disable_cookies"] = true
        return obj
    }

    /** wireguard.cpp:422-450: any non-empty block switches AmneziaWG on. */
    private fun amneziaFromJson(obj: JsonObject) {
        if (obj.isEmpty()) return
        enable_amnezia = true
        if (obj.contains("jc")) jc = obj.int("jc")
        if (obj.contains("jmin")) jmin = obj.int("jmin")
        if (obj.contains("jmax")) jmax = obj.int("jmax")
        if (obj.contains("s1")) s1 = obj.int("s1")
        if (obj.contains("s2")) s2 = obj.int("s2")
        if (obj.contains("s3")) s3 = obj.int("s3")
        if (obj.contains("s4")) s4 = obj.int("s4")
        if (obj.contains("h1")) h1 = obj.string("h1")
        if (obj.contains("h2")) h2 = obj.string("h2")
        if (obj.contains("h3")) h3 = obj.string("h3")
        if (obj.contains("h4")) h4 = obj.string("h4")
        if (obj.contains("i1")) i1 = obj.string("i1")
        if (obj.contains("i2")) i2 = obj.string("i2")
        if (obj.contains("i3")) i3 = obj.string("i3")
        if (obj.contains("i4")) i4 = obj.string("i4")
        if (obj.contains("i5")) i5 = obj.string("i5")
        if (obj.contains("header_protection_key")) header_protection_key = obj.string("header_protection_key")
        if (obj.contains("content_padding_addition")) content_padding_addition = amneziaRange(obj["content_padding_addition"])
        if (obj.contains("rekey_after_time")) rekey_after_time = amneziaRange(obj["rekey_after_time"])
        if (obj.contains("rekey_timeout")) rekey_timeout = amneziaRange(obj["rekey_timeout"])
        if (obj.contains("reject_after_time")) reject_after_time = amneziaRange(obj["reject_after_time"])
        if (obj.contains("keepalive_timeout")) keepalive_timeout = amneziaRange(obj["keepalive_timeout"])
        if (obj.contains("max_handshake_attempts")) max_handshake_attempts = amneziaRange(obj["max_handshake_attempts"])
        if (obj.contains("random_trailers")) random_trailers = obj.bool("random_trailers")
        if (obj.contains("disable_cookies")) disable_cookies = obj.bool("disable_cookies")
    }

    /** wireguard.cpp:453-455: ranges are written as strings, but sing-box also accepts a bare number. */
    private fun amneziaRange(value: Any?): String = value as? String ?: JsonValues.toInt(value).toString()

    /** wireguard.cpp:457-470: bare IPs get their host prefix length (an empty item becomes "/32", as on the desktop). */
    private fun fixAddress() {
        val fixed = ArrayList<String>(address.size)
        for (addr in address) {
            var trimmed = addr.trim()
            if (!trimmed.contains("/")) trimmed += if (trimmed.contains(":")) "/128" else "/32"
            fixed.add(trimmed)
        }
        address = fixed
    }

    companion object {
        /** wireguard.cpp:16-19. */
        @JvmStatic
        fun parseAmneziaBool(value: String): Boolean {
            val trimmed = value.trim().lowercase()
            return trimmed == "true" || trimmed == "1" || trimmed == "on" || trimmed == "yes"
        }
    }
}
