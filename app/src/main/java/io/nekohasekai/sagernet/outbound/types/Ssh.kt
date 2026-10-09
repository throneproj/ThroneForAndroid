package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser

/** ssh (include/configs/outbounds/ssh.h, src/configs/outbounds/ssh.cpp). */
class Ssh : Outbound("ssh") {
    @JvmField var user: String = ""
    @JvmField var password: String = ""
    @JvmField var private_key: String = ""
    @JvmField var private_key_path: String = ""
    @JvmField var private_key_passphrase: String = ""
    @JvmField var host_key: MutableList<String> = ArrayList()
    @JvmField var host_key_algorithms: MutableList<String> = ArrayList()
    @JvmField var client_version: String = ""

    /** ssh.cpp:10-47: no user-info and no default port; keys travel base64-encoded (`-` separates list items). */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        if (q.has("user")) user = q.valueFully("user")
        if (q.has("password")) password = q.valueFully("password")
        val privateKeyB64 = q.value("private_key")
        if (privateKeyB64.isNotEmpty()) private_key = String(Base64Strict.decodeLenient(privateKeyB64), Charsets.UTF_8)
        if (q.has("private_key_path")) private_key_path = q.valueFully("private_key_path")
        if (q.has("private_key_passphrase")) private_key_passphrase = q.valueFully("private_key_passphrase")
        val hostKeysRaw = q.value("host_key")
        if (hostKeysRaw.isNotEmpty()) {
            for (item in hostKeysRaw.split("-")) {
                val decoded = Base64Strict.decodeLenient(item)
                if (decoded.isNotEmpty()) host_key.add(String(decoded, Charsets.UTF_8))
            }
        }
        val hostKeyAlgsRaw = q.value("host_key_algorithms")
        if (hostKeyAlgsRaw.isNotEmpty()) {
            for (item in hostKeyAlgsRaw.split("-")) {
                val decoded = Base64Strict.decodeLenient(item)
                if (decoded.isNotEmpty()) host_key_algorithms.add(String(decoded, Charsets.UTF_8))
            }
        }
        if (q.has("client_version")) client_version = q.valueFully("client_version")
        return server.isNotEmpty()
    }

    /** ssh.cpp:68-84. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "ssh") return false
        baseParseFromClash(proxy)
        user = proxy.string("username")
        password = proxy.string("password")
        val key = proxy.string("private-key")
        if (key.isNotEmpty()) private_key = key
        val passphrase = proxy.string("private-key-passphrase")
        if (passphrase.isNotEmpty()) private_key_passphrase = passphrase
        host_key.addAll(proxy.strings("host-key"))
        host_key_algorithms.addAll(proxy.strings("host-key-algorithms"))
        return true
    }

    /** ssh.cpp:49-66. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "ssh") return false
        super.parseFromJson(obj)
        if (obj.contains("user")) user = obj.string("user")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("private_key")) private_key = obj.string("private_key")
        if (obj.contains("private_key_path")) private_key_path = obj.string("private_key_path")
        if (obj.contains("private_key_passphrase")) private_key_passphrase = obj.string("private_key_passphrase")
        if (obj.contains("host_key")) host_key = obj.array("host_key").strings()
        if (obj.contains("host_key_algorithms")) host_key_algorithms = obj.array("host_key_algorithms").strings()
        if (obj.contains("client_version")) client_version = obj.string("client_version")
        return true
    }

    /** ssh.cpp:86-125. */
    override fun exportToLink(): String {
        val url = LinkBuilder("ssh")
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (user.isNotEmpty()) url.addQueryItem("user", user)
        if (password.isNotEmpty()) url.addQueryItem("password", password)
        if (private_key.isNotEmpty()) url.addQueryItem("private_key", Base64Strict.encode(private_key, padding = false))
        if (private_key_path.isNotEmpty()) url.addQueryItem("private_key_path", private_key_path)
        if (private_key_passphrase.isNotEmpty()) url.addQueryItem("private_key_passphrase", private_key_passphrase)
        if (host_key.isNotEmpty()) url.addQueryItem("host_key", host_key.joinToString("-") { Base64Strict.encode(it, padding = false) })
        if (host_key_algorithms.isNotEmpty()) url.addQueryItem("host_key_algorithms", host_key_algorithms.joinToString("-") { Base64Strict.encode(it, padding = false) })
        if (client_version.isNotEmpty()) url.addQueryItem("client_version", client_version)
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** ssh.cpp:127-141. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "ssh"
        obj.merge(baseExportToJson())
        if (user.isNotEmpty()) obj["user"] = user
        if (password.isNotEmpty()) obj["password"] = password
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (private_key_path.isNotEmpty()) obj["private_key_path"] = private_key_path
        if (private_key_passphrase.isNotEmpty()) obj["private_key_passphrase"] = private_key_passphrase
        if (host_key.isNotEmpty()) obj["host_key"] = JsonValues.stringArray(host_key)
        if (host_key_algorithms.isNotEmpty()) obj["host_key_algorithms"] = JsonValues.stringArray(host_key_algorithms)
        if (client_version.isNotEmpty()) obj["client_version"] = client_version
        return obj
    }

    /** ssh.cpp:143-157. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "ssh"
        obj.merge(baseBuild(ctx))
        if (user.isNotEmpty()) obj["user"] = user
        if (password.isNotEmpty()) obj["password"] = password
        if (private_key.isNotEmpty()) obj["private_key"] = private_key
        if (private_key_path.isNotEmpty()) obj["private_key_path"] = private_key_path
        if (private_key_passphrase.isNotEmpty()) obj["private_key_passphrase"] = private_key_passphrase
        if (host_key.isNotEmpty()) obj["host_key"] = JsonValues.stringArray(host_key)
        if (host_key_algorithms.isNotEmpty()) obj["host_key_algorithms"] = JsonValues.stringArray(host_key_algorithms)
        if (client_version.isNotEmpty()) obj["client_version"] = client_version
        return BuildResult(obj)
    }

    /** ssh.cpp:159-162. */
    override fun displayType(): String = "SSH"

    /** ssh.cpp:164-169: without a host key the core accepts whatever key the server presents. */
    override fun security(): SecurityInfo {
        if (host_key.isEmpty()) return SecurityInfo("Unverified Host Key", "", SecurityLevel.Weak)
        return SecurityInfo("Encrypted", "", SecurityLevel.Secure)
    }
}
