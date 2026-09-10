package io.nekohasekai.sagernet.fmt.trojan

import io.nekohasekai.sagernet.fmt.v2ray.parseDuckSoft
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder

fun parseTrojan(server: String): TrojanBean {

    // 先剥离 #fragment 并 URL-decode 作为节点名，避免异常 fragment 干扰 URL 解析
    val fragmentIndex = server.indexOf('#')
    var linkBody = server
    var displayName = ""
    if (fragmentIndex >= 0) {
        val rawName = server.substring(fragmentIndex + 1)
        displayName = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
        linkBody = server.substring(0, fragmentIndex)
    }

    val link = linkBody.replace("trojan://", "https://").toHttpUrlOrNull()
        ?: error("invalid trojan link $server")

    return TrojanBean().apply {
        parseDuckSoft(link)
        if (displayName.isNotBlank()) name = displayName
        link.queryParameter("allowInsecure")
            ?.apply { if (this == "1" || this == "true") allowInsecure = true }
        link.queryParameter("peer")?.apply { if (this.isNotBlank()) sni = this }
    }

}
