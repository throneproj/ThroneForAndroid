@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.TlsVersion
import okio.Buffer
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

const val LOCALHOST = "127.0.0.1"

class HttpFetchResult(val body: String, private val headers: Headers) {
    fun header(name: String): String = headers[name] ?: ""
}

/** user_agent2, or the app's own user agent while it is empty (SettingsRepo::GetUserAgent). */
fun appUserAgent(): String = DataStore.userAgent2.ifBlank { USER_AGENT }

/** App requests use the mixed inbound with net_use_proxy or in proxy service mode (HTTPRequestHelper.cpp:26). */
fun appRequestsViaProxy(): Boolean = DataStore.netUseProxy || DataStore.serviceMode == Key.MODE_PROXY

// App-internal HTTP (subscriptions, update check): through the mixed inbound when [viaProxy] and the service is
// connected and that inbound exists, direct otherwise. The main process never loads the core.
fun newHttpClient(
    viaProxy: Boolean = true,
    allowInsecure: Boolean = false,
    restrictTls13: Boolean = false,
    timeoutSeconds: Long = 30,
): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    if (viaProxy && DataStore.serviceState.connected && !DataStore.mixedInboundDisabled) {
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCALHOST, DataStore.inboundSocksPort)))
        if (DataStore.inboundAuth) SocksAuthenticator.install()
    }
    if (restrictTls13) {
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS)
                    .tlsVersions(TlsVersion.TLS_1_3)
                    .build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
    }
    if (allowInsecure) {
        val trustManager = TrustAllManager
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

fun fetchText(
    url: String,
    userAgent: String = appUserAgent(),
    viaProxy: Boolean = appRequestsViaProxy(),
    allowInsecure: Boolean = DataStore.netInsecure,
    restrictTls13: Boolean = false,
): HttpFetchResult {
    val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
    newHttpClient(viaProxy, allowInsecure, restrictTls13).newCall(request).execute().use { response ->
        if (!response.isSuccessful) error("HTTP ${response.code}")
        return HttpFetchResult(response.body?.string().orEmpty(), response.headers)
    }
}

/** HttpGetOptions (HTTPRequestHelper.hpp): [maxBytes] 0 = no cap; [headers] are sent raw after the user agent. */
class HttpGetOptions(
    @JvmField val userAgent: String = "",
    @JvmField val headers: List<Pair<String, String>> = emptyList(),
    @JvmField val maxBytes: Long = 0L,
    @JvmField val useProxy: Boolean = false,
    /** Android extra: appTLSVersion "1.3" allows TLS 1.3 only. */
    @JvmField val restrictTls13: Boolean = DataStore.appTLSVersion == "1.3",
)

/** HTTPResponse: [error] is empty on success; [data] also holds the body of an HTTP error status. */
class HttpGetResponse(@JvmField val data: ByteArray, private val headers: Headers?, @JvmField val error: String) {
    val ok: Boolean get() = error.isEmpty()

    /** GetHeader: case-insensitive, "" when absent. */
    fun header(name: String): String = headers?.get(name) ?: ""
}

private const val MAX_REDIRECTS = 20

/**
 * NetworkRequestHelper::HttpGet (HTTPRequestHelper.cpp:25-91): 10 s connect/inactivity timeouts, redirects followed
 * except from https to http, net_insecure, the user agent (options or user_agent2 / the app default), a body cap.
 * With net_use_proxy, the proxy service mode or [HttpGetOptions.useProxy] the request goes through the mixed inbound
 * as an HTTP proxy, and fails while no profile runs; a VPN without the mixed inbound tunnels the request instead.
 * Blocking: call it off the main thread.
 */
fun httpGet(url: String, options: HttpGetOptions = HttpGetOptions()): HttpGetResponse {
    val builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
    if (options.useProxy || appRequestsViaProxy()) {
        if (!DataStore.serviceState.connected) {
            return HttpGetResponse(ByteArray(0), null, app.getString(R.string.subs_request_proxy_no_profile))
        }
        if (!DataStore.mixedInboundDisabled) {
            val address = DataStore.inboundAddress.let { if (it == "::") LOCALHOST else it }
            builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, DataStore.inboundSocksPort)))
            if (DataStore.inboundAuth) {
                val credential = Credentials.basic(DataStore.inboundUser, DataStore.inboundPass)
                builder.proxyAuthenticator(object : okhttp3.Authenticator {
                    override fun authenticate(route: Route?, response: Response): Request? {
                        if (response.request.header("Proxy-Authorization") != null) return null
                        return response.request.newBuilder().header("Proxy-Authorization", credential).build()
                    }
                })
            }
        }
    }
    if (options.restrictTls13) {
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
    }
    if (DataStore.netInsecure) {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(TrustAllManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, TrustAllManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    val client = builder.build()

    val headers = Headers.Builder()
        .addUnsafeNonAscii("User-Agent", options.userAgent.ifEmpty { appUserAgent() })
    for ((name, value) in options.headers) headers.addUnsafeNonAscii(name, value)
    val requestHeaders = headers.build()

    try {
        var target = url.toHttpUrl()
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(target).headers(requestHeaders).build()
            val response = client.newCall(request).execute()
            if (response.isRedirect) {
                val location = response.header("Location")?.let { target.resolve(it) }
                if (location != null) {
                    response.close()
                    // NoLessSafeRedirectPolicy
                    if (target.isHttps && !location.isHttps) {
                        return HttpGetResponse(ByteArray(0), null, app.getString(R.string.subs_insecure_redirect))
                    }
                    if (++redirects > MAX_REDIRECTS) {
                        return HttpGetResponse(ByteArray(0), null, app.getString(R.string.subs_too_many_redirects))
                    }
                    target = location
                    continue
                }
            }
            response.use {
                val body = readCapped(it, options.maxBytes)
                    ?: return HttpGetResponse(
                        ByteArray(0), it.headers,
                        app.getString(R.string.subs_response_too_large, options.maxBytes / (1024 * 1024)),
                    )
                val error = if (it.isSuccessful) "" else app.getString(
                    R.string.subs_http_error, target.toString(), "${it.code} ${it.message}".trim(),
                )
                return HttpGetResponse(body, it.headers, error)
            }
        }
    } catch (e: Exception) {
        return HttpGetResponse(ByteArray(0), null, e.readableMessage)
    }
}

/**
 * A core runs connected. The :bg process asks its own service; the main process keeps the state the service
 * connection reported.
 */
fun serviceConnected(): Boolean = DataStore.baseService?.data?.state?.connected ?: DataStore.serviceState.connected

/**
 * A client for app requests that stream their body: [httpGet]'s setup (net_insecure, app_tls_version, the mixed inbound
 * as an HTTP proxy when [viaProxy] and it exists) with OkHttp following redirects itself. Blocking calls.
 */
fun appHttpClient(viaProxy: Boolean, timeoutSeconds: Long = 30): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    if (viaProxy && !DataStore.mixedInboundDisabled) {
        val address = DataStore.inboundAddress.let { if (it == "::") LOCALHOST else it }
        builder.proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(address, DataStore.inboundSocksPort)))
        if (DataStore.inboundAuth) {
            val credential = Credentials.basic(DataStore.inboundUser, DataStore.inboundPass)
            builder.proxyAuthenticator(object : okhttp3.Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? {
                    if (response.request.header("Proxy-Authorization") != null) return null
                    return response.request.newBuilder().header("Proxy-Authorization", credential).build()
                }
            })
        }
    }
    if (DataStore.appTLSVersion == "1.3") {
        builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
    }
    if (DataStore.netInsecure) {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(TrustAllManager), SecureRandom())
        builder.sslSocketFactory(sslContext.socketFactory, TrustAllManager)
        builder.hostnameVerifier { _, _ -> true }
    }
    return builder.build()
}

/** The body, or null once it exceeds [maxBytes] (0 = no cap). */
private fun readCapped(response: Response, maxBytes: Long): ByteArray? {
    val body = response.body ?: return ByteArray(0)
    if (maxBytes > 0 && body.contentLength() > maxBytes) return null
    val buffer = Buffer()
    val source = body.source()
    while (source.read(buffer, 64 * 1024L) != -1L) {
        if (maxBytes > 0 && buffer.size > maxBytes) return null
    }
    return buffer.readByteArray()
}

// java.net's SOCKS5 client only takes credentials from the process-wide Authenticator.
private object SocksAuthenticator : Authenticator() {
    private val installed = AtomicBoolean(false)

    fun install() {
        if (installed.compareAndSet(false, true)) setDefault(this)
    }

    override fun getPasswordAuthentication(): PasswordAuthentication? {
        if (requestingProtocol?.startsWith("SOCKS", ignoreCase = true) != true) return null
        if (requestingHost != LOCALHOST && requestingSite?.isLoopbackAddress != true) return null
        return PasswordAuthentication(DataStore.inboundUser, DataStore.inboundPass.toCharArray())
    }
}

@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
private object TrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

fun String.isIpAddress(): Boolean {
    return NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

/** Whether a listener could bind [port] on loopback now; SO_REUSEADDR like the core's, so TIME_WAIT does not count. */
fun isLoopbackPortFree(port: Int): Boolean = try {
    ServerSocket().use {
        it.reuseAddress = true
        it.bind(InetSocketAddress(LOCALHOST, port))
    }
    true
} catch (_: IOException) {
    false
}

const val USER_AGENT = "Throne/Android/" + BuildConfig.VERSION_NAME
