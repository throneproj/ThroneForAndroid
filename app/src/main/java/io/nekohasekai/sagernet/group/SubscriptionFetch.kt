package io.nekohasekai.sagernet.group

import androidx.core.net.toUri
import io.nekohasekai.sagernet.R
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

    /** [error] is null on success; [subInfo] is what the headers and the body's head said ([SubscriptionMetadata]). */
    class Result(@JvmField val body: String, @JvmField val subInfo: SubUserInfo, @JvmField val error: String?)

    fun fetch(url: String, name: String, identity: RequestIdentity): Result {
        Logs.i(">>>>>>>> " + app.getString(R.string.subs_requesting, name))
        val result = if (url.startsWith("content://", ignoreCase = true)) readContent(url) else request(url, identity)
        if (result.error != null) {
            Logs.w("<<<<<<<< " + app.getString(R.string.subs_request_error, name, result.error + "\n" + result.body))
            return Result("", SubUserInfo(), result.error)
        }
        Logs.i("<<<<<<<< " + app.getString(R.string.subs_request_finished, name))
        return result
    }

    private fun request(url: String, identity: RequestIdentity): Result {
        val response = httpGet(
            url,
            HttpGetOptions(userAgent = identity.userAgent, headers = identity.headers(), maxBytes = MAX_BYTES),
        )
        if (!response.ok) {
            val body = String(response.data, 0, minOf(response.data.size, MAX_LOGGED_BODY), Charsets.UTF_8)
            return Result(body, SubUserInfo(), response.error)
        }
        val body = String(response.data, Charsets.UTF_8)
        return Result(body, SubscriptionMetadata.read(response::header, body), null)
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
