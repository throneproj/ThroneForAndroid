package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SubHttpVersion
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.ktx.HttpGetOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.httpGet
import io.nekohasekai.sagernet.ktx.readableMessage
import java.io.ByteArrayOutputStream

/** GroupUpdater::fetch (GroupUpdater.cpp:448-463), plus the Android `content://` subscription URLs. */
object SubscriptionFetch {

    /** kMaxSubscriptionBytes (GroupUpdater.cpp:24). */
    const val MAX_BYTES = 64L * 1024 * 1024

    /** The body of an HTTP error status is logged up to this size. */
    private const val MAX_LOGGED_BODY = 4096

    /**
     * [error] is null on success; [subInfo] is what the headers and the body's head said ([SubscriptionMetadata]);
     * [routing] is the trimmed `routing` header (GroupUpdater.cpp fetch), "" when absent.
     */
    class Result(
        @JvmField val body: String,
        @JvmField val subInfo: SubUserInfo,
        @JvmField val error: String?,
        @JvmField val routing: String = "",
    )

    fun fetch(url: String, rawName: String, identity: RequestIdentity): Result {
        // ImportUrl names the request by its URL, whose userinfo is the Basic auth credential.
        val name = withoutUserInfo(rawName)
        Logs.i(">>>>>>>> " + app.getString(R.string.subs_requesting, name))
        val result = if (url.startsWith("content://", ignoreCase = true)) readContent(url) else request(url, identity)
        if (result.error != null) {
            val reason = withoutUrls(result.error, url)
            Logs.w("<<<<<<<< " + app.getString(R.string.subs_request_error, name, reason + "\n" + withoutUrls(result.body, url)))
            return Result("", SubUserInfo(), reason)
        }
        Logs.i("<<<<<<<< " + app.getString(R.string.subs_request_finished, name))
        if (result.subInfo.serverInterval > 0) {
            Logs.i(app.getString(R.string.subs_server_interval, result.subInfo.serverInterval))
        }
        return result
    }

    private val URL = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^\s"'<>]+""")

    private val USER_INFO = Regex("""([A-Za-z][A-Za-z0-9+.\-]*://)[^/?#\s]*@""")

    private fun withoutUserInfo(text: String): String = USER_INFO.replace(text, "$1")

    /** withoutUrls (GroupUpdater.cpp): an HTTP error text can embed the request or redirect URL, and with it the subscription token. */
    private fun withoutUrls(text: String, url: String): String {
        fun hostOf(u: String): String = runCatching { u.toUri().host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "\u2026"
        var redacted = text
        if (url.isNotEmpty()) redacted = redacted.replace(url, hostOf(url))
        return URL.replace(redacted) { hostOf(it.value) }
    }

    private fun request(url: String, identity: RequestIdentity): Result {
        val response = httpGet(
            url,
            HttpGetOptions(
                userAgent = identity.userAgent,
                headers = identity.headers(),
                maxBytes = MAX_BYTES,
                tlsVersion = identity.tlsVersion,
                http2 = identity.httpVersion != SubHttpVersion.HTTP11,
            ),
        )
        if (!response.ok) {
            val body = String(response.data, 0, minOf(response.data.size, MAX_LOGGED_BODY), Charsets.UTF_8)
            return Result(body, SubUserInfo(), response.error)
        }
        val body = String(response.data, Charsets.UTF_8)
        return Result(body, SubscriptionMetadata.read(response::header, body), null, response.header(SubscriptionRoute.HEADER).trim())
    }

    private fun readContent(url: String): Result = try {
        val input = app.contentResolver.openInputStream(url.toUri()) ?: error("cannot open $url")
        val out = ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_BYTES) error(app.getString(R.string.subs_response_too_large, MAX_BYTES / (1024 * 1024)))
            }
        }
        val body = out.toString(Charsets.UTF_8.name())
        Result(body, SubscriptionMetadata.read({ "" }, body), null)
    } catch (e: Exception) {
        Result("", SubUserInfo(), e.readableMessage)
    }
}
