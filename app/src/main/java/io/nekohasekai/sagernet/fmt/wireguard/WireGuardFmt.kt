package io.nekohasekai.sagernet.fmt.wireguard

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma
import org.ini4j.Ini
import java.net.URLDecoder
import java.io.StringReader

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

// AWG（AmneziaWG）混淆参数键：当前内核暂不支持发射，仅识别并以兼容标记命名
private val AWG_PARAM_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

private const val AWG_COMPAT_PREFIX = "[AWG-Compat]"

fun parseWireGuardConfig(conf: String): List<WireGuardBean> {
    val ini = Ini().apply {
        config.isMultiSection = true
        load(StringReader(conf))
    }
    val iface = ini["Interface"] ?: error("Missing 'Interface' selection")
    val localAddresses = iface.getAll("Address")
        ?.flatMap { value -> value.split(',') }
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        .orEmpty()
    if (localAddresses.isEmpty()) error("Empty address in 'Interface' selection")

    val baseBean = WireGuardBean().applyDefaultValues().apply {
        localAddress = localAddresses.joinToString("\n")
        privateKey = iface["PrivateKey"]?.trim().orEmpty()
        iface["MTU"]?.trim()?.toIntOrNull()?.let { mtu = it }
        listenPort = iface["ListenPort"]?.trim()?.toIntOrNull() ?: 0
    }

    val peers = ini.getAll("Peer")
    if (peers.isNullOrEmpty()) error("Missing 'Peer' selections")

    val beans = peers.mapNotNull { peer ->
        val (serverAddress, serverPort) = parseEndpoint(peer["Endpoint"] ?: return@mapNotNull null)
            ?: return@mapNotNull null
        val publicKey = peer["PublicKey"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: return@mapNotNull null

        baseBean.clone().apply {
            this.serverAddress = serverAddress
            this.serverPort = serverPort
            peerPublicKey = publicKey
            peerPreSharedKey = peer["PresharedKey"]?.trim().orEmpty()
            persistentKeepaliveInterval =
                peer["PersistentKeepalive"]?.trim()?.toIntOrNull() ?: 0
            reserved = peer["Reserved"]?.trim().orEmpty()
        }.applyDefaultValues()
    }
    if (beans.isEmpty()) error("Empty available peer list")

    // AWG 混淆参数检测：存在时以兼容标记命名，提示该配置含暂不支持的混淆参数
    val hasAwgParams = iface.keySet().any { it.lowercase() in AWG_PARAM_KEYS }
    if (hasAwgParams) {
        beans.forEach { bean ->
            bean.name = if (bean.name.isNullOrBlank()) {
                AWG_COMPAT_PREFIX
            } else {
                "$AWG_COMPAT_PREFIX ${bean.name}"
            }
        }
    }
    return beans
}

// wireguard:// 与 awg:// 分享链接解析：
// 形式一为 base64 编码的整段配置回退，形式二为 privkey@host:port?query#name
fun parseWireGuardLink(link: String): List<WireGuardBean> {
    val isAwg = link.startsWith("awg://", ignoreCase = true)
    val body = link.substringAfter("://", "")
    if (body.isBlank()) error("invalid wireguard link $link")

    // 形式一：base64 编码的整段 WireGuard 配置
    if (!body.contains('@') && !body.contains('?')) {
        val payload = body.substringBefore('#')
        val conf = runCatching { String(Util.b64Decode(payload)).trim() }.getOrNull()
        if (conf != null && conf.contains("[Interface]")) {
            val rawName = body.substringAfter('#', "")
            val beans = parseWireGuardConfig(conf)
            if (rawName.isNotBlank()) {
                val name = runCatching { URLDecoder.decode(rawName, "UTF-8") }.getOrDefault(rawName)
                beans.forEach { if (it.name.isNullOrBlank()) it.name = name }
            }
            return beans
        }
        error("invalid wireguard link $link")
    }

    // 形式二：privkey@host:port?query#name
    val mainPart = body.substringBefore('#')
    val rawName = body.substringAfter('#', "")
    val query = if (mainPart.contains('?')) mainPart.substringAfter('?') else ""
    val authority = if (mainPart.contains('?')) mainPart.substringBefore('?') else mainPart

    val atIndex = authority.lastIndexOf('@')
    if (atIndex <= 0) error("invalid wireguard link $link")
    val privateKey = authority.substring(0, atIndex)
    val (serverAddress, serverPort) = parseEndpoint(authority.substring(atIndex + 1))
        ?: error("invalid wireguard endpoint in $link")

    val params = HashMap<String, String>()
    for (pair in query.split('&')) {
        if (pair.isBlank()) continue
        val key = pair.substringBefore('=').trim().lowercase()
        val value = pair.substringAfter('=', "")
        params[key] = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }

    val peerPublicKey = params["publickey"] ?: params["public-key"]
        ?: error("missing peer public key in $link")

    val hasAwgParams = params.keys.any { it.lowercase() in AWG_PARAM_KEYS }

    val bean = WireGuardBean().applyDefaultValues().apply {
        privateKey = privateKey
        serverAddress = serverAddress
        serverPort = serverPort
        peerPublicKey = peerPublicKey
        peerPreSharedKey = params["presharedkey"] ?: params["pre-shared-key"] ?: ""
        localAddress = params["address"]?.split(',')
            ?.joinToString("\n") { it.trim() }
            ?.takeIf { it.isNotEmpty() } ?: ""
        mtu = params["mtu"]?.toIntOrNull() ?: 1420
        persistentKeepaliveInterval = params["keepalive"]?.toIntOrNull() ?: 0
        reserved = params["reserved"] ?: ""
        name = if (rawName.isEmpty()) "" else runCatching {
            URLDecoder.decode(rawName, "UTF-8")
        }.getOrDefault(rawName)
        if (isAwg || hasAwgParams) {
            name = if (name.isBlank()) AWG_COMPAT_PREFIX else "$AWG_COMPAT_PREFIX $name"
        }
    }
    return listOf(bean)
}

private fun parseEndpoint(value: String): Pair<String, Int>? {
    val endpoint = value.trim()
    val address: String
    val portValue: String
    if (endpoint.startsWith('[')) {
        val closingBracket = endpoint.indexOf(']')
        if (closingBracket <= 1 || endpoint.getOrNull(closingBracket + 1) != ':') return null
        address = endpoint.substring(1, closingBracket).trim()
        portValue = endpoint.substring(closingBracket + 2).trim()
    } else {
        val separator = endpoint.lastIndexOf(':')
        if (separator <= 0) return null
        address = endpoint.substring(0, separator).trim()
        portValue = endpoint.substring(separator + 1).trim()
    }
    val port = portValue.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return address.takeIf { it.isNotEmpty() }?.let { it to port }
}

fun parseWireGuardEndpoint(json: JsonObject): WireGuardBean? {
    if (json.stringValue("type") != "wireguard") return null
    val peer = json.arrayValue("peers")
        ?.firstOrNull()
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: return null

    val localAddresses = json.listableStrings("address") ?: return null
    val privateKey = json.stringValue("private_key") ?: return null
    val serverAddress = peer.stringValue("address") ?: return null
    val serverPort = peer.intValue("port")?.takeIf { it in 1..65535 } ?: return null
    val publicKey = peer.stringValue("public_key") ?: return null

    return WireGuardBean().apply {
        name = json.stringValue("tag").orEmpty()
        localAddress = localAddresses.joinToString("\n")
        this.privateKey = privateKey
        mtu = json.intValue("mtu") ?: 0
        listenPort = json.intValue("listen_port") ?: 0
        this.serverAddress = serverAddress
        this.serverPort = serverPort
        peerPublicKey = publicKey
        peerPreSharedKey = peer.stringValue("pre_shared_key").orEmpty()
        persistentKeepaliveInterval = peer.intValue("persistent_keepalive_interval") ?: 0
        reserved = peer.reservedValue().orEmpty()
    }.applyDefaultValues()
}

fun parseWireGuardEndpoints(root: JsonObject): List<WireGuardBean> {
    return root.arrayValue("endpoints")
        ?.mapNotNull { endpoint ->
            endpoint.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let(::parseWireGuardEndpoint)
        }
        .orEmpty()
}

private fun JsonObject.stringValue(name: String): String? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString.trim().takeIf { it.isNotEmpty() }
}

private fun JsonObject.intValue(name: String): Int? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive) return null
    return value.asJsonPrimitive.asString.trim().toIntOrNull()
}

private fun JsonObject.arrayValue(name: String): JsonArray? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    return value.takeIf(JsonElement::isJsonArray)?.asJsonArray
}

private fun JsonObject.listableStrings(name: String): List<String>? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    val values = when {
        value.isJsonArray -> value.asJsonArray.toList()
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value)
        else -> return null
    }
    return values.mapNotNull { element ->
        element.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }.takeIf { it.isNotEmpty() }
}

private fun JsonObject.reservedValue(): String? {
    val value = get("reserved")?.takeUnless(JsonElement::isJsonNull) ?: return null
    return when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.trim()
        value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
            element.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.asString?.trim()
        }.joinToString(", ")
        else -> null
    }?.takeIf { it.isNotEmpty() }
}

fun genReserved(anyStr: String): String {
    val values = anyStr
        .trim()
        .removeSurrounding("[", "]")
        .split(Regex("[,\\s]+"))
        .filter { it.isNotEmpty() }
        .map { value -> value.toIntOrNull()?.takeIf { it in 0..255 } ?: return anyStr }
    if (values.size != 3) return anyStr
    val bits = (values[0] shl 16) or (values[1] shl 8) or values[2]
    return buildString(4) {
        append(BASE64_ALPHABET[(bits ushr 18) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 12) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 6) and 0x3F])
        append(BASE64_ALPHABET[bits and 0x3F])
    }
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        mtu = bean.mtu?.takeIf { it > 0 }
        listen_port = bean.listenPort?.takeIf { it > 0 }
        peers = listOf(
            SingBoxOptions.Endpoint_WireGuardPeer().apply {
                address = bean.serverAddress?.takeIf { it.isNotBlank() }
                port = bean.serverPort?.takeIf { it > 0 }
                public_key = bean.peerPublicKey
                pre_shared_key = bean.peerPreSharedKey.takeIf { it.isNotBlank() }
                allowed_ips = listOf("0.0.0.0/0", "::/0")
                persistent_keepalive_interval = bean.persistentKeepaliveInterval?.takeIf { it > 0 }
                reserved = bean.reserved.takeIf { it.isNotBlank() }?.let(::genReserved)
            }
        )
    }
}
