package io.nekohasekai.sagernet.outbound.types

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.BuildContext
import io.nekohasekai.sagernet.outbound.BuildResult
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.SecurityInfo
import io.nekohasekai.sagernet.outbound.SecurityLevel
import io.nekohasekai.sagernet.outbound.common.Tls
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.link.Base64Strict
import io.nekohasekai.sagernet.outbound.link.LinkBuilder
import io.nekohasekai.sagernet.outbound.link.LinkParser
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.validation.Severity
import io.nekohasekai.sagernet.validation.Validation
import io.nekohasekai.sagernet.validation.addIssue
import io.nekohasekai.sagernet.validation.vtr

/** juicity cmd/client/run.go: the first decoding that succeeds wins, so 64 hex digits decode as 48 bytes of base64. */
private fun juicityPinnedHashSize(text: String): Int {
    if (text.length % 4 == 0) {
        for (urlSafe in listOf(true, false)) Base64Strict.decode(text, urlSafe)?.let { return it.size }
    }
    return if (Validation.hex(text, 0, 0).isEmpty()) text.length / 2 else 0
}

/** The Checks of juicity.h. */
object JuicityChecks {
    /** Decoded like juicity: padded URL base64, else padded standard base64, else hex; the result must be 32 bytes. */
    @JvmStatic
    fun juicityPinnedCertChain(text: String): String {
        val value = text.trim()
        if (value.isEmpty() || juicityPinnedHashSize(value) == 32) return ""
        if (Validation.hex(value, 64, 64).isEmpty()) return vtr(R.string.validation_tls_pin_base64)
        return vtr(R.string.validation_juicity_certchain)
    }
}

/** juicity (include/configs/outbounds/juicity.h, src/configs/outbounds/juicity.cpp). */
class Juicity : Outbound("juicity") {
    @JvmField var uuid: String = ""
    @JvmField var password: String = ""
    @JvmField var pinned_certchain_sha256: String = ""
    @JvmField var tls: Tls = Tls()

    /** juicity.h:14-17: uTLS is never emitted. */
    init {
        tls.utls.supported = false
    }

    override fun hasTls(): Boolean = true
    override fun mustTls(): Boolean = true
    override fun getTls(): Tls = tls

    /** juicity::Validate (juicity.cpp). */
    override fun validate(issues: MutableList<Issue>, ctx: BuildContext) {
        super.validate(issues, ctx)
        issues.addIssue(Severity.Error, "uuid", Validation.uuid(uuid))
        issues.addIssue(
            Severity.Error, "pinned_certchain_sha256", JuicityChecks.juicityPinnedCertChain(pinned_certchain_sha256),
            pinned_certchain_sha256,
        )
        val certificateOptions = tls.certificate.any { it.isNotBlank() } || tls.certificate_path.isNotBlank() ||
            tls.certificate_sha256.any { it.isNotBlank() } || tls.certificate_public_key_sha256.any { it.isNotBlank() }
        if (pinned_certchain_sha256.isNotEmpty() && certificateOptions) {
            issues.addIssue(Severity.Error, "pinned_certchain_sha256", vtr(R.string.validation_juicity_certchain_conflict))
        }
    }

    /** juicity.cpp:9-25. */
    override fun parseFromLink(link: String): Boolean {
        val url = LinkParser.parse(link)
        if (!url.isValid) return false
        super.parseFromLink(url)
        val (linkUuid, linkPassword) = url.userInfoPair()
        uuid = linkUuid
        password = linkPassword
        if (url.query.has("pinned_certchain_sha256")) {
            pinned_certchain_sha256 = url.query.valueFully("pinned_certchain_sha256").trim()
        }
        tls.parseFromLink(url)
        tls.enabled = true
        if (serverPort == 0) serverPort = 443
        return !(uuid.isEmpty() || server.isEmpty())
    }

    /** juicity.cpp:27-35. */
    override fun parseFromJson(obj: JsonObject): Boolean {
        if (obj.isEmpty() || obj.string("type") != "juicity") return false
        super.parseFromJson(obj)
        if (obj.contains("uuid")) uuid = obj.string("uuid")
        if (obj.contains("password")) password = obj.string("password")
        if (obj.contains("pinned_certchain_sha256")) pinned_certchain_sha256 = obj.string("pinned_certchain_sha256")
        if (obj.contains("tls")) tls.parseFromJson(obj.obj("tls"))
        return true
    }

    /** juicity.cpp:37-53. */
    override fun exportToLink(): String {
        val url = LinkBuilder("juicity")
        // a uuid missing from the link or JSON is a null QString on the desktop, which adds no user-info
        if (uuid.isNotEmpty()) url.setUserName(uuid)
        if (password.isNotEmpty()) url.setPassword(password)
        url.host = server
        url.port = serverPort
        if (name.isNotEmpty()) url.fragment = name
        if (pinned_certchain_sha256.isNotEmpty()) url.addQueryItem("pinned_certchain_sha256", pinned_certchain_sha256)
        url.addQueryItems(tls.exportToLink())
        url.addQueryItems(baseLinkQuery())
        return url.build()
    }

    /** juicity.cpp:55-64. */
    override fun exportToJson(): JsonObject {
        val obj = JsonObject()
        obj["type"] = "juicity"
        obj.merge(baseExportToJson())
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (pinned_certchain_sha256.isNotEmpty()) obj["pinned_certchain_sha256"] = pinned_certchain_sha256
        if (tls.enabled) obj["tls"] = tls.exportToJson()
        return obj
    }

    /** juicity.cpp:66-75. */
    override fun build(ctx: BuildContext): BuildResult {
        val obj = JsonObject()
        obj["type"] = "juicity"
        obj.merge(baseBuild(ctx))
        if (uuid.isNotEmpty()) obj["uuid"] = uuid
        if (password.isNotEmpty()) obj["password"] = password
        if (pinned_certchain_sha256.isNotEmpty()) obj["pinned_certchain_sha256"] = pinned_certchain_sha256
        if (tls.enabled) obj["tls"] = tls.build(ctx)
        return BuildResult(obj)
    }

    /** juicity.cpp:77-80. */
    override fun displayType(): String = "Juicity"

    /** juicity::GetSecurity. */
    override fun security(): SecurityInfo {
        val info = securityFromTls("QUIC")
        // Like a certificate pin: the core checks the chain hash in place of the CA chain, insecure or not.
        if (pinned_certchain_sha256.isNotEmpty() && !tls.reality.enabled) {
            return info.copy(label = "TLS", level = SecurityLevel.Secure, caVerified = false)
        }
        return info
    }
}
