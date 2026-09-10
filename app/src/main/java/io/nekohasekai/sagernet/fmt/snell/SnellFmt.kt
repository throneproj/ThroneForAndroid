package io.nekohasekai.sagernet.fmt.snell

import io.nekohasekai.sagernet.ktx.urlSafe
import io.nekohasekai.sagernet.ktx.unUrlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

// URI 格式: snell://base64(psk)@server:port?version=6&userkey=base64(userkey)&mode=default&reuse=true&network=tcp#name
fun parseSnell(url: String): SnellBean {
    // 先按标准 URI 解析；解析失败或未提取到 psk 时回退解析非标准链接
    // （psk@host:port、query 别名、fragment 节点名）
    val standard = runCatching { parseStandardSnell(url) }.getOrNull()
    if (standard != null && standard.psk.isNotBlank() && standard.serverAddress.isNotBlank()) {
        return standard
    }
    return parseSnellFallback(url)
}

private fun parseStandardSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
        ?: error("Invalid snell URL")

    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        psk = link.username.unUrlSafe()
        name = link.fragment ?: ""

        link.queryParameter("version")?.toIntOrNull()?.let {
            version = it.coerceIn(1, 6)
        }
        link.queryParameter("userkey")?.let { userKey = it.unUrlSafe() }
        link.queryParameter("obfs-mode")?.let { obfsMode = it }
        link.queryParameter("obfs-host")?.let { obfsHost = it }
        link.queryParameter("reuse")?.let { reuse = it.toBoolean() }
        link.queryParameter("network")?.let { network = it }
        link.queryParameter("mode")?.let { mode = it }
    }
}

// 非标准 snell:// 链接的回退解析：snell://psk@host:port?query#name
private fun parseSnellFallback(url: String): SnellBean {
    val body = url.trim().removePrefix("snell://")
    if (body.isEmpty()) error("Invalid snell URL")

    val mainPart = body.substringBefore('#')
    val rawName = body.substringAfter('#', "")
    val query = if (mainPart.contains('?')) mainPart.substringAfter('?') else ""
    val authority = if (mainPart.contains('?')) mainPart.substringBefore('?') else mainPart

    // psk 可能含未编码的 base64 字符，取最后一个 '@' 分隔
    val atIndex = authority.lastIndexOf('@')
    if (atIndex <= 0) error("Invalid snell URL")
    val psk = authority.substring(0, atIndex)
    val hostPort = authority.substring(atIndex + 1)

    val host: String
    val port: Int
    if (hostPort.startsWith("[")) {
        // IPv6 [::1]:port
        val close = hostPort.indexOf(']')
        if (close <= 1 || hostPort.getOrNull(close + 1) != ':') error("Invalid snell URL")
        host = hostPort.substring(1, close)
        port = hostPort.substring(close + 2).toIntOrNull() ?: error("Invalid snell URL")
    } else {
        val colon = hostPort.lastIndexOf(':')
        if (colon <= 0) error("Invalid snell URL")
        host = hostPort.substring(0, colon)
        port = hostPort.substring(colon + 1).toIntOrNull() ?: error("Invalid snell URL")
    }

    return SnellBean().apply {
        serverAddress = host
        serverPort = port
        psk = psk.unUrlSafe()
        name = if (rawName.isEmpty()) "" else runCatching {
            URLDecoder.decode(rawName, "UTF-8")
        }.getOrDefault(rawName)

        for (pair in query.split('&')) {
            if (pair.isBlank()) continue
            val key = pair.substringBefore('=').trim().lowercase()
            val value = pair.substringAfter('=', "")
            when (key) {
                "version" -> value.toIntOrNull()?.let { version = it.coerceIn(1, 6) }
                "userkey" -> userKey = value.unUrlSafe()
                "obfs-mode", "obfsmode" -> obfsMode = value
                "obfs-host", "obfshost" -> obfsHost = value
                "reuse" -> reuse = value.toBoolean()
                "network" -> network = value
                "mode" -> mode = value
            }
        }
    }
}

fun SnellBean.toUri(): String {
    val builder = StringBuilder("snell://")
    builder.append(psk.urlSafe()).append("@")
    builder.append(serverAddress).append(":").append(serverPort)

    val params = mutableListOf<String>()
    params.add("version=$version")
    if (userKey.isNotBlank()) params.add("userkey=${userKey.urlSafe()}")
    if (version == 6) {
        if (mode.isNotBlank() && mode != "default") params.add("mode=$mode")
    } else {
        if (obfsMode.isNotBlank()) params.add("obfs-mode=$obfsMode")
        if (obfsHost.isNotBlank()) params.add("obfs-host=$obfsHost")
    }
    if (reuse) params.add("reuse=true")
    if (network.isNotBlank()) params.add("network=$network")

    builder.append("?").append(params.joinToString("&"))

    if (name.isNotBlank()) {
        builder.append("#").append(name.urlSafe())
    }

    return builder.toString()
}

fun parseClashSnell(proxy: Map<String, Any?>): SnellBean {
    return SnellBean().apply {
        name = proxy["name"] as? String ?: ""
        serverAddress = proxy["server"] as? String ?: ""
        serverPort = (proxy["port"] as? Number)?.toInt() ?: 443
        psk = proxy["psk"] as? String ?: ""

        val clashVersion = ((proxy["version"] as? Number)?.toInt() ?: 4).coerceIn(1, 5)
        version = if (clashVersion == 5) 4 else clashVersion

        reuse = proxy["reuse"] as? Boolean ?: false

        val udpEnabled = proxy["udp"] as? Boolean ?: false
        network = if (udpEnabled) {
            ""
        } else {
            "tcp"
        }

        // obfs-opts
        (proxy["obfs-opts"] as? Map<*, *>)?.let { obfsOpts ->
            obfsMode = obfsOpts["mode"] as? String ?: ""
            obfsHost = obfsOpts["host"] as? String ?: ""
        }
    }
}
