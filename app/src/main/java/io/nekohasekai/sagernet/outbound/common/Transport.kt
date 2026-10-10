package io.nekohasekai.sagernet.outbound.common

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.outbound.link.Hosts
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.outbound.link.ParsedLink

/** Transport (include/configs/common/transport.h, src/configs/common/transport.cpp): the sing-box V2Ray transport. */
class Transport {
    @JvmField var type: String = ""
    @JvmField var host: String = ""
    @JvmField var path: String = ""
    @JvmField var method: String = ""
    @JvmField var headers: MutableList<String> = ArrayList()
    @JvmField var idle_timeout: String = ""
    @JvmField var ping_timeout: String = ""
    @JvmField var max_early_data: Int = 0
    @JvmField var early_data_header_name: String = ""
    @JvmField var service_name: String = ""

    /** Never persisted: the parsed source asked for the raw TCP HTTP header, which sing-box reproduces only without TLS. */
    @JvmField var rawHttpHeader: Boolean = false

    fun parseFromLink(link: String): Boolean = parseFromLink(LinkParser.parse(link))

    /** transport.cpp:9-42. */
    fun parseFromLink(url: ParsedLink): Boolean {
        if (!url.isValid) return false
        val q = url.query
        val linkType = q.value("type")
        val rawHttp = (linkType.isEmpty() || linkType == "tcp" || linkType == "raw") && q.value("headerType") == "http"
        if (q.has("type")) type = if (linkType == "raw") "tcp" else linkType
        if (rawHttp || type == "h2") {
            type = "http"
            method = "GET"
        }
        // a link naming no transport (the throneExtra of a V2RayN link) keeps what the first parse found
        if (q.has("type") || rawHttp) rawHttpHeader = rawHttp
        if (q.has("host")) host = q.valueFully("host")
        if (q.has("path")) path = q.valueFully("path")
        // the raw header's server accepts any of its comma-listed paths, while sing-box sends exactly one
        if (rawHttp) path = QtStrings.sectionFirstSkipEmpty(path, ',').trim()
        if (q.has("method")) method = q.value("method")
        if (q.has("headers")) {
            headers = QtStrings.split(q.valueFully("headers"), "|")
            if (headers.size % 2 != 0) headers.clear()
        }
        if (q.has("idle_timeout")) idle_timeout = q.value("idle_timeout")
        if (q.has("ping_timeout")) ping_timeout = q.value("ping_timeout")
        if (q.has("max_early_data")) max_early_data = QtStrings.toInt(q.value("max_early_data"))
        if (q.has("early_data_header_name")) early_data_header_name = q.value("early_data_header_name")
        if (q.has("serviceName")) service_name = q.valueFully("serviceName")
        return true
    }

    /** transport.cpp:43-77. */
    fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty()) return false
        if (obj.contains("type")) type = obj.string("type")
        if (obj.contains("host")) {
            host = if (obj.isArray("host")) obj.array("host").strings().joinToString(",") else obj.string("host")
        }
        if (obj.contains("path")) path = obj.string("path")
        if (obj.contains("method")) method = obj.string("method")
        if (obj.contains("headers") && obj.isObject("headers")) {
            val headerObj = obj.obj("headers").copy()
            if (type == "ws") {
                if (headerObj.contains("Host")) {
                    if (headerObj.isString("Host")) {
                        host = headerObj.string("Host")
                    } else if (headerObj.isArray("Host")) {
                        for (v in headerObj.array("Host")) {
                            if (v is String) {
                                host = v
                                break
                            }
                        }
                    }
                    headerObj.remove("Host")
                }
            }
            headers = JsonValues.objectToPairList(headerObj)
        }
        if (obj.contains("idle_timeout")) idle_timeout = obj.string("idle_timeout")
        if (obj.contains("ping_timeout")) ping_timeout = obj.string("ping_timeout")
        if (obj.contains("max_early_data")) max_early_data = obj.int("max_early_data")
        if (obj.contains("early_data_header_name")) early_data_header_name = obj.string("early_data_header_name")
        if (obj.contains("service_name")) service_name = obj.string("service_name")
        return true
    }

    /**
     * transport.cpp:78-126: ws / httpupgrade, grpc, h2 and http-opts in that priority; false when none applies.
     * Android also maps `network: http` without a method, which Clash sends as GET.
     */
    fun parseFromClash(proxy: ClashProxy): Boolean {
        val network = proxy.string("network")
        val ws = proxy.obj("ws-opts")
        val wsPath = ws.string("path")
        if (wsPath.isNotEmpty() || network == "ws") {
            if (ws.bool("v2ray-http-upgrade")) {
                type = "httpupgrade"
            } else {
                type = "ws"
                early_data_header_name = ws.string("early-data-header-name")
                max_early_data = ws.int("max-early-data")
            }
            val wsHeaders = ws.stringMap("headers")
            val servername = proxy.string("servername")
            if (servername.isNotEmpty()) host = servername else wsHeaders["Host"]?.let { host = it }
            for ((key, value) in wsHeaders) headers.add("$key=$value")
            path = wsPath
            return true
        }
        val grpcServiceName = proxy.obj("grpc-opts").string("grpc-service-name")
        if (grpcServiceName.isNotEmpty() || network == "grpc") {
            type = "grpc"
            service_name = grpcServiceName
            return true
        }
        val h2 = proxy.obj("h2-opts")
        val h2Path = h2.string("path")
        if (h2Path.isNotEmpty() || network == "h2") {
            type = "http"
            h2.strings("host").firstOrNull()?.let { host = it }
            path = h2Path
            return true
        }
        val http = proxy.obj("http-opts")
        val httpMethod = http.string("method")
        if (httpMethod.isNotEmpty() || network == "http") {
            type = "http"
            http.stringListMap("headers")["Host"]?.firstOrNull()?.let { host = it }
            http.strings("path").firstOrNull()?.let { path = it }
            method = httpMethod.ifEmpty { "GET" }
            rawHttpHeader = true
            return true
        }
        return false
    }

    /** transport.cpp:127-130. */
    fun exportToLink(): List<Pair<String, String>> = exportToLink(true)

    /** transport.cpp:131-152: in plaintext the http transport is the raw HTTP header, which other clients spell type=tcp&headerType=http. */
    fun exportToLink(tlsEnabled: Boolean): List<Pair<String, String>> {
        if (type.isEmpty() || type == "tcp") return emptyList()
        val q = ArrayList<Pair<String, String>>()
        if (type == "http" && !tlsEnabled) {
            q.add("type" to "tcp")
            q.add("headerType" to "http")
        } else {
            q.add("type" to type)
        }
        if (host.isNotEmpty()) q.add("host" to host)
        if (path.isNotEmpty()) q.add("path" to path)
        if (method.isNotEmpty()) q.add("method" to method)
        if (headers.isNotEmpty()) q.add("headers" to headers.joinToString("|"))
        if (idle_timeout.isNotEmpty()) q.add("idle_timeout" to idle_timeout)
        if (ping_timeout.isNotEmpty()) q.add("ping_timeout" to ping_timeout)
        if (max_early_data > 0) q.add("max_early_data" to max_early_data.toString())
        if (early_data_header_name.isNotEmpty()) q.add("early_data_header_name" to early_data_header_name)
        if (service_name.isNotEmpty()) q.add("serviceName" to service_name)
        return q
    }

    /** transport.cpp:153-184. */
    fun exportToJson(): JsonObject {
        val obj = JsonObject()
        if (type.isEmpty() || type == "tcp") return obj
        obj["type"] = type
        if (path.contains("?ed=")) {
            val spl = path.split("?ed=")
            obj["path"] = spl[0]
            obj["max_early_data"] = QtStrings.toInt(spl[1])
            obj["early_data_header_name"] = "Sec-WebSocket-Protocol"
        } else {
            if (path.isNotEmpty()) obj["path"] = path
            if (max_early_data > 0) obj["max_early_data"] = max_early_data
            if (early_data_header_name.isNotEmpty()) obj["early_data_header_name"] = early_data_header_name
        }
        if (method.isNotEmpty()) obj["method"] = method
        if (headers.isNotEmpty()) obj["headers"] = JsonValues.pairListToObject(headers)
        if (host.isNotEmpty()) {
            if (type == "http" || type == "httpupgrade") obj["host"] = Hosts.toAceHost(host)
            if (type == "ws") {
                val headersObj = obj["headers"] as? JsonObject ?: JsonObject()
                headersObj["Host"] = Hosts.toAceHost(host)
                obj["headers"] = headersObj
            }
        }
        if (idle_timeout.isNotEmpty()) obj["idle_timeout"] = idle_timeout
        if (ping_timeout.isNotEmpty()) obj["ping_timeout"] = ping_timeout
        if (service_name.isNotEmpty()) obj["service_name"] = service_name
        return obj
    }

    /** transport.cpp:185-191. */
    fun exportIdentity(): JsonObject {
        val obj = JsonObject()
        if (type.isEmpty() || type == "tcp") return obj
        obj["type"] = type
        return obj
    }

    /** transport.cpp:192-202: a comma list of http hosts becomes an array of trimmed hosts. */
    fun build(ctx: BuildContext): JsonObject {
        val obj = exportToJson()
        val exported = obj.string("host")
        if (type == "http" && exported.contains(',')) {
            val hosts = QtStrings.splitSkipEmpty(exported, ",").map { it.trim() }
            obj["host"] = JsonValues.stringArray(hosts)
        }
        return obj
    }
}
