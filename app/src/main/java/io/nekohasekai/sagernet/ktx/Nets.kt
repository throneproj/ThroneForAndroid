@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import android.net.Network
import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SubTlsVersion
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.ConnectionSpec
import okhttp3.Credentials
import okhttp3.Dns
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import okhttp3.TlsVersion
import okio.Buffer
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
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

/**
 * Whether an app request that would use the proxy fails while no profile runs: only with net_use_proxy (or a
 * request that asks for the proxy itself). The proxy service mode alone goes direct then.
 */
fun appRequestsNeedProxy(useProxy: Boolean = false): Boolean = useProxy || DataStore.netUseProxy

/** The VPN while it is this app's default network. */
fun tunnelNetwork(): Network? {
    val network = SagerNet.connectivity.activeNetwork ?: return null
    val capabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: return null
    return network.takeIf { capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
}

/**
 * A VPN without the mixed inbound carries the app's proxied requests itself (VpnService keeps the app inside then):
 * they are bound to it, and fail rather than go around the tunnel while it is not the app's network.
 */
private fun OkHttpClient.Builder.viaTunnel(): OkHttpClient.Builder {
    val network = tunnelNetwork()
        ?: return addInterceptor { throw IOException(app.getString(R.string.request_tunnel_unavailable)) }
    return proxy(Proxy.NO_PROXY)
        .socketFactory(network.socketFactory)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
        })
}

// App-internal HTTP (subscriptions, update check): through the mixed inbound when [viaProxy] and the service is
// connected and that inbound exists, through the VPN without it, direct otherwise. The main process never loads the core.
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
    if (viaProxy && DataStore.serviceState.connected) {
        if (DataStore.mixedInboundDisabled) {
            builder.viaTunnel()
        } else {
            builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(LOCALHOST, DataStore.inboundSocksPort)))
            if (DataStore.inboundAuth) SocksAuthenticator.install()
        }
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
    /** Android extra: appTLSVersion "1.3" allows TLS 1.3 only while [tlsVersion] is AUTO. */
    @JvmField val restrictTls13: Boolean = DataStore.appTLSVersion == "1.3",
    /** tlsProtocol: AUTO offers the platform's versions, TLS12 / TLS13 only that one. */
    @JvmField val tlsVersion: SubTlsVersion = SubTlsVersion.AUTO,
    /** http2: false speaks HTTP/1.1 only. */
    @JvmField val http2: Boolean = true,
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
 * except from https to http, net_insecure, the TLS and HTTP versions, the user agent (options or user_agent2 / the app
 * default), a body cap. A URL's `user:pass@` goes out as Basic auth, only while the redirects stay on its origin.
 * With net_use_proxy, the proxy service mode or [HttpGetOptions.useProxy] the request goes through the mixed inbound
 * as an HTTP proxy; a VPN without the mixed inbound carries the request itself ([viaTunnel]). While no profile runs it fails
 * with net_use_proxy or useProxy, and goes direct in the proxy service mode ([appRequestsNeedProxy]).
 * Blocking: call it off the main thread.
 */
fun httpGet(url: String, options: HttpGetOptions = HttpGetOptions()): HttpGetResponse {
    val tlsVersion = if (options.tlsVersion == SubTlsVersion.AUTO && options.restrictTls13) SubTlsVersion.TLS13
    else options.tlsVersion
    if (tlsVersion == SubTlsVersion.TLS13 && !tls13Supported) {
        return HttpGetResponse(ByteArray(0), null, app.getString(R.string.subs_tls_unsupported))
    }
    val builder = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
    val connected = DataStore.serviceState.connected
    if (!connected && appRequestsNeedProxy(options.useProxy)) {
        return HttpGetResponse(ByteArray(0), null, app.getString(R.string.subs_request_proxy_no_profile))
    }
    if (connected && (options.useProxy || appRequestsViaProxy())) {
        if (DataStore.mixedInboundDisabled) {
            builder.viaTunnel()
        } else {
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
    when (tlsVersion) {
        SubTlsVersion.AUTO -> Unit
        SubTlsVersion.TLS12 -> builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_2).build(),
                ConnectionSpec.CLEARTEXT,
            )
        )

        SubTlsVersion.TLS13 -> builder.connectionSpecs(
            listOf(
                ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build(),
                ConnectionSpec.CLEARTEXT,
            )
        )
    }
    if (!options.http2) builder.protocols(listOf(Protocol.HTTP_1_1))
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
        // OkHttp keeps a URL's (decoded) userinfo but never sends it.
        var authorization: String? = null
        if (target.username.isNotEmpty() || target.password.isNotEmpty()) {
            authorization = Credentials.basic(target.username, target.password, Charsets.UTF_8)
            target = target.newBuilder().username("").password("").build()
        }
        var redirects = 0
        while (true) {
            val request = Request.Builder().url(target).headers(requestHeaders)
                .apply { authorization?.let { header("Authorization", it) } }
                .build()
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
                    // Like OkHttp's own redirects: the credentials never follow a hop to another scheme, host or port.
                    if (location.scheme != target.scheme || location.host != target.host || location.port != target.port) {
                        authorization = null
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
 * as an HTTP proxy when [viaProxy] and it exists, else the VPN) with OkHttp following redirects itself. Blocking calls.
 */
fun appHttpClient(viaProxy: Boolean, timeoutSeconds: Long = 30): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
    if (viaProxy && DataStore.mixedInboundDisabled) {
        builder.viaTunnel()
    } else if (viaProxy) {
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

/** QSslSocket::isProtocolSupported(TlsV1_3): Android 10 and later. */
private val tls13Supported: Boolean by lazy {
    runCatching { "TLSv1.3" in SSLContext.getDefault().supportedSSLParameters.protocols }.getOrDefault(false)
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
