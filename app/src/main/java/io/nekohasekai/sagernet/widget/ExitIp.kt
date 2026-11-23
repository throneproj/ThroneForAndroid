package io.nekohasekai.sagernet.widget

import android.os.SystemClock
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.exitsThroughVpn
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appHttpClient
import io.nekohasekai.sagernet.ktx.appUserAgent
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.tunnelNetwork
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.ui.test.TestFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * runningCountryInfo (Profile.h:62): the running connection's exit as an IP lookup service sees it, looked up once it
 * connects (mainwindow_profile_lifecycle.cpp:384-396) and kept in memory only. With `exit_country_lookup` off no
 * service is asked and nothing is held; `exit_lookup_provider` picks one of [PROVIDERS], or "auto" for [AUTO_CHAIN].
 * Main thread.
 */
object ExitIp {

    class Info(val ip: String, val countryCode: String, val country: String, val city: String)

    /** One lookup service; [parse] reads a 2xx body, null on the service's failure signal or without a country. */
    private class Provider(val id: String, val url: String, val parse: (String) -> Info?)

    private const val TIMEOUT_SECONDS = 10L
    private const val MAX_BYTES = 64 * 1024L
    private const val CHAIN_BUDGET_MS = 20_000L

    /** The ids are stored in `exit_lookup_provider` and shared with the desktop. */
    private val PROVIDERS = listOf(
        Provider("ip-api", "http://ip-api.com/json/") { body ->
            JsonInput.parseObjectOrNull(body)?.takeIf { it.string("status") == "success" }?.let {
                normalized(it.string("query"), it.string("countryCode"), it.string("country"), it.string("city"))
            }
        },
        Provider("ipwho", "https://ipwho.is/") { body ->
            JsonInput.parseObjectOrNull(body)?.takeIf { it["success"] != false }?.let {
                normalized(it.string("ip"), it.string("country_code"), it.string("country"), it.string("city"))
            }
        },
        Provider("ipinfo", "https://ipinfo.io/json") { body ->
            JsonInput.parseObjectOrNull(body)?.let {
                normalized(it.string("ip"), it.string("country"), "", it.string("city"))
            }
        },
        Provider("ipapi-co", "https://ipapi.co/json/") { body ->
            JsonInput.parseObjectOrNull(body)?.takeIf { it["error"] != true }?.let {
                normalized(it.string("ip"), it.string("country_code"), it.string("country_name"), it.string("city"))
            }
        },
        Provider("ip2location", "https://api.ip2location.io/") { body ->
            JsonInput.parseObjectOrNull(body)?.takeIf { !it.isObject("error") }?.let {
                normalized(
                    it.string("ip"), it.string("country_code"), it.string("country_name"), it.string("city_name"),
                )
            }
        },
        Provider("freeipapi", "https://free.freeipapi.com/api/v1/json") { body ->
            JsonInput.parseObjectOrNull(body)?.let {
                normalized(
                    it.string("ipAddress"), it.string("countryCode"), it.string("countryName"), it.string("cityName"),
                )
            }
        },
        // Plain `key=value` lines, not JSON.
        Provider("cloudflare", "https://www.cloudflare.com/cdn-cgi/trace") { body ->
            val fields = body.lineSequence().mapNotNull { line ->
                val eq = line.indexOf('=')
                if (eq > 0) line.substring(0, eq).trim() to line.substring(eq + 1) else null
            }.toMap()
            normalized(fields["ip"].orEmpty(), fields["loc"].orEmpty(), "", "")
        },
    )

    /**
     * "auto", and any id this build does not know: the first service that names a country wins. ip2location leads
     * because the IP & country test asks it too, so both name the same country (ExitLookup.cpp LookupExit).
     */
    private val AUTO_CHAIN = listOf("ip2location", "ip-api", "ipwho", "ipinfo", "cloudflare").map { id ->
        PROVIDERS.first { it.id == id }
    }

    var info: Info? = null
        private set

    /** The stats bar on screen; told about every change of [info] or [refreshing]. */
    var listener: (() -> Unit)? = null

    private var generation = 0
    private var job: Job? = null

    /** A lookup runs; [info] keeps the previous result until it ends. */
    val refreshing: Boolean get() = job != null

    /** Once per connection: nothing while a result is held or a lookup runs. */
    fun ensure() {
        if (!DataStore.exitCountryLookup) return clear()
        if (info == null && job == null) refresh()
    }

    fun refresh() {
        if (!DataStore.exitCountryLookup || DataStore.serviceState != BaseService.State.Connected) return clear()
        job?.cancel()
        val token = ++generation
        job = runOnMainDispatcher {
            val result = withContext(Dispatchers.IO) {
                try {
                    lookup { isActive }
                } catch (e: Exception) {
                    Logs.w("exit IP lookup: ${e.readableMessage}")
                    null
                }
            }
            if (token != generation) return@runOnMainDispatcher
            job = null
            update(result)
        }
        listener?.invoke()
    }

    fun clear() {
        generation++
        job?.cancel()
        job = null
        update(null)
    }

    private fun update(value: Info?) {
        info = value
        listener?.invoke()
    }

    /** Blocking; null when the running connection cannot carry the lookup or no service named a country in time. */
    private fun lookup(active: () -> Boolean): Info? {
        // "Only route advertised network" rejects this probe.
        if (exitsThroughVpn(ProfileManager.getProfile(DataStore.currentProfile))) return null
        val client = client() ?: return null
        val selected = DataStore.exitLookupProvider
        val chain = PROVIDERS.find { it.id == selected }?.let { listOf(it) } ?: AUTO_CHAIN
        val deadline = SystemClock.elapsedRealtime() + CHAIN_BUDGET_MS
        for (provider in chain) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0 || !active()) break
            try {
                query(client, provider, remaining)?.let { return it }
                Logs.w("exit IP lookup: ${provider.id} named no country")
            } catch (e: Exception) {
                Logs.w("exit IP lookup: ${provider.id}: ${e.readableMessage}")
            }
        }
        return null
    }

    private fun query(client: OkHttpClient, provider: Provider, timeoutMs: Long): Info? {
        val request = Request.Builder().url(provider.url).header("User-Agent", appUserAgent()).build()
        val call = client.newCall(request)
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return provider.parse(response.peekBody(MAX_BYTES).string())
        }
    }

    /** A service's fields; null when it named neither a country code nor a country. */
    private fun normalized(ip: String, code: String, country: String, city: String): Info? {
        val countryCode = countryCode(code)
        val name = country.trim().ifEmpty { if (countryCode.isEmpty()) "" else TestFormat.countryName(countryCode) }
        if (countryCode.isEmpty() && name.isEmpty()) return null
        return Info(ip.trim(), countryCode, name, city.trim())
    }

    /** Two ASCII letters, else empty; XX (unknown) and T1 (Tor) are Cloudflare's markers. */
    private fun countryCode(raw: String): String {
        val code = raw.trim().uppercase(Locale.ROOT)
        return code.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } && it != "XX" && it != "T1" }.orEmpty()
    }

    /**
     * The desktop asks through the mixed inbound (HttpGet useProxy, HTTPRequestHelper.cpp:36), which the core routes in
     * either service mode.
     * A VPN without that inbound carries the request itself (appHttpClient); until it is this app's network the lookup
     * is skipped rather than failed.
     */
    private fun client(): OkHttpClient? {
        if (DataStore.mixedInboundDisabled && tunnelNetwork() == null) return null
        return appHttpClient(viaProxy = true, timeoutSeconds = TIMEOUT_SECONDS)
    }
}
