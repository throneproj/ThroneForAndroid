package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.import.ClashProxy
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.QtStrings
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.ParseDiag
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.acceptOptional
import io.nekohasekai.sagernet.validation.addIssue

/** anyTLS (include/configs/outbounds/anyTLS.h, src/configs/outbounds/anyTLS.cpp). */
class AnyTls : Outbound("anytls") {
    @JvmField var password: String = ""
    @JvmField var idle_session_check_interval: String = "30s"
    @JvmField var idle_session_timeout: String = "30s"
    @JvmField var min_idle_session: Int = 5
    @JvmField var tls: Tls = Tls()

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** anyTLS::ParseFromLink: malformed durations and counts are dropped. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        val q = url.query
        super.parseFromLink(url)
        dropFastOpen()
        password = url.userName
        if (serverPort == 0) serverPort = 443
        if (q.has("idle_session_check_interval")) {
            idle_session_check_interval =
                importDuration("idle_session_check_interval", q.value("idle_session_check_interval"), idle_session_check_interval)
        }
        if (q.has("idle_session_timeout")) {
            idle_session_timeout = importDuration("idle_session_timeout", q.value("idle_session_timeout"), idle_session_timeout)
        }
        if (q.has("min_idle_session")) {
            val value = q.value("min_idle_session").trim()
            if (acceptOptional("min_idle_session", Validation.int(value, 0, Int.MAX_VALUE.toLong()), value)) {
                min_idle_session = QtStrings.toInt(value)
            }
        }
        tls.parseFromLink(url)
        tls.enabled = true
        return true
    }

    /** anyTLS.cpp:42-54: the Clash intervals are seconds. */
    override fun parseFromClash(node: JsonObject): Boolean {
        val proxy = ClashProxy(node)
        if (proxy.type != "anytls") return false
        baseParseFromClash(proxy)
        password = proxy.string("password")
        val checkInterval = proxy.int("idle-session-check-interval")
        if (checkInterval > 0) idle_session_check_interval = "${checkInterval}s"
        val timeout = proxy.int("idle-session-timeout")
        if (timeout > 0) idle_session_timeout = "${timeout}s"
        val minIdle = proxy.int("min-idle-session")
        if (minIdle > 0) min_idle_session = minIdle
        tls.parseFromClash(proxy)
        tls.enabled = true
        return true
    }

    /** anyTLS::ParseFromJson: stored values load as they are; only an import drops a malformed duration or tcp_fast_open. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "anytls") return false
        super.parseFromJson(obj)
        if (obj.contains("password")) password = obj.string("password")
        fun readDuration(key: String, current: String): String {
            if (!obj.contains(key)) return current
            return if (ParseDiag.active()) importDuration(key, obj.string(key), current) else obj.string(key)
        }
        idle_session_check_interval = readDuration("idle_session_check_interval", idle_session_check_interval)
        idle_session_timeout = readDuration("idle_session_timeout", idle_session_timeout)
        if (obj.contains("min_idle_session")) min_idle_session = obj.int("min_idle_session")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        if (ParseDiag.active()) dropFastOpen()
        return true
    }

    /** anyTlsDropFastOpen. */
    private fun dropFastOpen() {
        dial.tcp_fast_open = false
    }

    /** anyTLS.cpp:56-75. */
    override fun exportToLink(): String {
        val url = LinkBuilder("anytls")
        // a password missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (password.isNotEmpty()) url.setUserName(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (idle_session_check_interval.isNotEmpty()) url.addQueryItem("idle_session_check_interval", idle_session_check_interval)
        if (idle_session_timeout.isNotEmpty()) url.addQueryItem("idle_session_timeout", idle_session_timeout)
        if (min_idle_session > 0) url.addQueryItem("min_idle_session", min_idle_session.toString())
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** anyTLS.cpp:77-88: `tls` is always written. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "anytls"
        obj.merge(baseExportToJson())
        if (password.isNotEmpty()) obj["password"] = password
        if (idle_session_check_interval.isNotEmpty()) obj["idle_session_check_interval"] = idle_session_check_interval
        if (idle_session_timeout.isNotEmpty()) obj["idle_session_timeout"] = idle_session_timeout
        if (min_idle_session > 0) obj["min_idle_session"] = min_idle_session
        obj["tls"] = tls.exportToJson()
        return obj
    }

    /** anyTLS::Build. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "anytls"
        obj.merge(baseBuild(ctx))
        // The core refuses to start an AnyTLS outbound with tcp_fast_open.
        obj.remove("tcp_fast_open")
        if (password.isNotEmpty()) obj["password"] = password
        if (idle_session_check_interval.isNotEmpty()) obj["idle_session_check_interval"] = idle_session_check_interval
        if (idle_session_timeout.isNotEmpty()) obj["idle_session_timeout"] = idle_session_timeout
        if (min_idle_session > 0) obj["min_idle_session"] = min_idle_session
        obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** anyTLS.cpp:103-106. */
    override fun displayType(): String = "AnyTLS"

    /** anyTLS::Validate. */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "password", Validation.required(password))
        issues.addIssue(Severity.Error, "idle_session_check_interval", Validation.duration(idle_session_check_interval), idle_session_check_interval)
        issues.addIssue(Severity.Error, "idle_session_timeout", Validation.duration(idle_session_timeout), idle_session_timeout)
    }

    private companion object {
        /** anyTlsImportDuration: the trimmed value when it is a duration, else [current] (reported). */
        fun importDuration(key: String, raw: String, current: String): String {
            val value = raw.trim()
            return if (acceptOptional(key, Validation.duration(value), value)) value else current
        }
    }
}
