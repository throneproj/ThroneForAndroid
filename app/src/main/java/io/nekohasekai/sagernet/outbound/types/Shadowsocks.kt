package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.Multiplex
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkCodec
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink

/** shadowsocks (include/configs/outbounds/shadowsocks.h, src/configs/outbounds/shadowsocks.cpp). */
class Shadowsocks : Outbound("shadowsocks") {
    @JvmField var method: String = ""
    @JvmField var password: String = ""
    @JvmField var plugin: String = ""
    @JvmField var plugin_opts: String = ""
    @JvmField var uot: Boolean = false
    @JvmField var multiplex: Multiplex = Multiplex()

    override fun hasMux(): Boolean = true
    override fun getMux(): Multiplex = multiplex

    /** shadowsocks.cpp:9-45. */
    override fun parseFromLink(link: String): Boolean {
        val url: ParsedLink
        if (QtStrings.substrBefore(link, "#").contains("@")) {
            url = LinkParser.parse(link)
        } else {
            // v2rayN format: the whole URL after the scheme is base64 (url-safe alphabet, shadowsocks.cpp:16)
            var linkN = Base64Strict.decodeToString(QtStrings.substrBefore(QtStrings.substrAfter(link, "://"), "#"), urlSafe = true)
            if (linkN.isEmpty()) return false
            if (link.contains("#")) linkN += "#" + QtStrings.substrAfter(link, "#")
            url = LinkParser.parse("https://$linkN")
        }
        if (!url.isValid) return false
        val q = url.query
        // shadowsocks.cpp:23 re-parses url.toString(), which round-trips to the same components
        super.parseFromLink(url)

        if (url.password.isEmpty()) {
            // traditional format: "method:password" base64 in the username (url-safe alphabet, shadowsocks.cpp:27)
            val methodPassword = Base64Strict.decodeToString(url.userName, urlSafe = true)
            if (methodPassword.isEmpty()) return false
            method = QtStrings.substrBefore(methodPassword, ":")
            password = QtStrings.substrAfter(methodPassword, ":")
        } else {
            method = url.userName
            password = url.password
        }

        plugin = q.valueFully("plugin").replace("simple-obfs;", "obfs-local;")
        plugin_opts = QtStrings.substrAfter(plugin, ";")
        plugin = QtStrings.substrBefore(plugin, ";")
        if (plugin == "simple-obfs") plugin = "obfs-local"
        if (q.has("plugin-opts")) plugin_opts = q.valueFully("plugin-opts")
        if (q.has("uot")) uot = q.value("uot") == "true" || QtStrings.toInt(q.value("uot")) > 0
        // shadowsocks.cpp:42 hands the original text to the multiplex parser (not a valid URL in the v2rayN form)
        multiplex.parseFromLink(link)

        return !(server.isEmpty() || method.isEmpty() || password.isEmpty())
    }

    /** shadowsocks.cpp:64-94: the v2ray-plugin and obfs option blocks become SIP003 option strings. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "ss") return false
        baseParseFromClash(proxy)
        method = proxy.string("cipher")
        password = proxy.string("password")
        uot = proxy.bool("udp-over-tcp")
        val clashPlugin = proxy.string("plugin")
        if (clashPlugin.isNotEmpty()) {
            val opts = proxy.obj("plugin-opts")
            if (clashPlugin == "v2ray-plugin") {
                plugin = "v2ray-plugin"
                val items = ArrayList<String>()
                if (opts.bool("tls")) items.add("tls")
                opts.string("host").takeIf { it.isNotEmpty() }?.let { items.add("host=$it") }
                opts.string("path").takeIf { it.isNotEmpty() }?.let { items.add("path=$it") }
                opts.string("mode").takeIf { it.isNotEmpty() }?.let { items.add("mode=$it") }
                if (opts.bool("mux")) items.add("mux")
                plugin_opts = items.joinToString(";")
            } else if (clashPlugin == "obfs") {
                plugin = "obfs-local"
                val items = ArrayList<String>()
                opts.string("mode").takeIf { it.isNotEmpty() }?.let { items.add("obfs=$it") }
                opts.string("host").takeIf { it.isNotEmpty() }?.let { items.add("obfs-host=$it") }
                plugin_opts = items.joinToString(";")
            }
        }
        multiplex.parseFromClash(proxy)
        return true
    }

    /** shadowsocks.cpp:47-62. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "shadowsocks") return false
        super.parseFromJson(obj)
        if (obj.contains("method")) method = obj.string("method")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("plugin")) plugin = obj.string("plugin")
        if (obj.contains("plugin_opts")) plugin_opts = obj.string("plugin_opts")
        if (obj.contains("udp_over_tcp")) {
            if (obj.isBool("udp_over_tcp")) uot = obj.bool("udp_over_tcp")
            if (obj.isObject("udp_over_tcp")) uot = obj.obj("udp_over_tcp").bool("enabled")
        }
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return true
    }

    /** shadowsocks.cpp:96-112: one entry of a SIP008 subscription document. */
    fun parseFromSip008(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        super.parseFromJson(obj)
        if (obj.contains("remarks")) name = obj.string("remarks")
        if (obj.contains("method")) method = obj.string("method")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("plugin")) plugin = obj.string("plugin").replace("simple-obfs", "obfs-local")
        if (obj.contains("plugin_opts")) plugin_opts = obj.string("plugin_opts")
        if (obj.contains("uot")) {
            if (obj.isBool("uot")) uot = obj.bool("uot")
            if (obj.isObject("uot")) uot = obj.obj("uot").bool("enabled")
        }
        if (obj.contains("multiplex")) multiplex.parseFromJson(obj.obj("multiplex"))
        return !(server.isEmpty() || method.isEmpty() || password.isEmpty())
    }

    /** shadowsocks.cpp:114-144. */
    override fun exportToLink(): String {
        val url = LinkBuilder("ss")
        if (method.startsWith("2022-")) {
            url.setUserName(method)
            if (password.isNotEmpty()) url.setPassword(password)
        } else {
            // legacy SIP002 user-info: base64url WITH padding (shadowsocks.cpp:125)
            url.setUserName(Base64Strict.encode("$method:$password", urlSafe = true, padding = true))
        }
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (plugin.isNotEmpty()) {
            var pluginString = plugin
            if (plugin_opts.isNotEmpty()) pluginString += ";$plugin_opts"
            url.addQueryItemPercentEncoded("plugin", LinkCodec.percentEncodeAll(pluginString))
        }
        if (uot) url.addQueryItem("uot", "1")
        url.addQueryItems(multiplex.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** shadowsocks.cpp:146-158. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "shadowsocks"
        obj.merge(baseExportToJson())
        if (method.isNotEmpty()) obj["method"] = method
        if (password.isNotEmpty()) obj["password"] = password
        if (plugin.isNotEmpty()) obj["plugin"] = plugin
        if (plugin_opts.isNotEmpty()) obj["plugin_opts"] = plugin_opts
        if (uot) obj["udp_over_tcp"] = uot
        val muxObj = multiplex.exportToJson()
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return obj
    }

    /** shadowsocks.cpp:160-176: a "name;opts" plugin is split into the two fields first (mutating the profile). */
    override fun build(ctx: BuildContext): BuildResult {
        if (plugin.contains(";")) {
            plugin_opts = QtStrings.substrAfter(plugin, ";")
            plugin = QtStrings.substrBefore(plugin, ";")
        }
        val obj = JsonObject()
        obj["type"] = "shadowsocks"
        obj.merge(baseBuild(ctx))
        if (method.isNotEmpty()) obj["method"] = method
        if (password.isNotEmpty()) obj["password"] = password
        if (plugin.isNotEmpty()) obj["plugin"] = plugin
        if (plugin_opts.isNotEmpty()) obj["plugin_opts"] = plugin_opts
        if (uot) obj["udp_over_tcp"] = uot
        val muxObj = multiplex.build(ctx)
        if (muxObj.isNotEmpty()) obj["multiplex"] = muxObj
        return BuildResult(obj)
    }

    /** shadowsocks.cpp:178-181. */
    override fun displayType(): String = "Shadowsocks"

    /** shadowsocks.cpp:183-205. */
    override fun security(): SecurityInfo {
        if (method.isEmpty() || method == "none") return SecurityInfo("Raw", "", SecurityLevel.None)
        if (method in STREAM_CIPHERS) return SecurityInfo("Weak Cipher", "", SecurityLevel.Weak)
        return SecurityInfo("Encrypted", "", SecurityLevel.Secure)
    }

    companion object {
        // pre-AEAD stream ciphers: no integrity, trivially detectable (shadowsocks.cpp:192-196)
        private val STREAM_CIPHERS = setOf(
            "aes-128-ctr", "aes-192-ctr", "aes-256-ctr", "aes-128-cfb", "aes-192-cfb", "aes-256-cfb",
            "rc4-md5", "chacha20-ietf", "xchacha20",
        )
    }
}
