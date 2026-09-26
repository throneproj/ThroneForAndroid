package io.nekohasekai.sagernet.bg

import android.os.Process
import android.os.SystemClock
import android.text.format.Formatter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.appHttpClient
import io.nekohasekai.sagernet.ktx.appRequestsViaProxy
import io.nekohasekai.sagernet.ktx.appUserAgent
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.serviceConnected
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Xray's data files (geoip.dat, geosite.dat, `ext:` files) in the core's asset directory, the app's files dir
 * (XRAY_LOCATION_ASSET, core/mobile/mobile.go). The desktop asks before fetching a missing one after a start or a test
 * failed on it (handleXrayGeoAssetError, mainwindow_profile_lifecycle.cpp:97-170); here geoip.dat and geosite.dat are
 * fetched without asking, before an Xray config that reads them starts or is tested, from xray_geoip_url /
 * xray_geosite_url. Works in both processes: the settings screen downloads from the main one.
 */
object XrayGeoAssets {

    const val GEOIP = "geoip.dat"
    const val GEOSITE = "geosite.dat"

    @JvmField
    val FILES = listOf(GEOIP, GEOSITE)

    class Provider(@JvmField val name: String, @JvmField val geoip: String, @JvmField val geosite: String)

    /** XrayGeoAssetProviders (Const.hpp:77-98): both files must come from one provider; v2fly's geosite is dlc.dat. */
    @JvmField
    val PROVIDERS = listOf(
        Provider(
            "Loyalsoldier (global / China)",
            "https://github.com/Loyalsoldier/v2ray-rules-dat/raw/release/geoip.dat",
            "https://github.com/Loyalsoldier/v2ray-rules-dat/raw/release/geosite.dat",
        ),
        Provider(
            "Chocolate4U (Iran)",
            "https://github.com/Chocolate4U/Iran-v2ray-rules/raw/release/geoip.dat",
            "https://github.com/Chocolate4U/Iran-v2ray-rules/raw/release/geosite.dat",
        ),
        Provider(
            "runetfreedom (Russia)",
            "https://github.com/runetfreedom/russia-v2ray-rules-dat/raw/release/geoip.dat",
            "https://github.com/runetfreedom/russia-v2ray-rules-dat/raw/release/geosite.dat",
        ),
        Provider(
            "v2fly (upstream)",
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat",
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat",
        ),
    )

    /** [bytes] of [file] so far, of [total] (-1 when the server does not say). */
    class Progress(@JvmField val file: String, @JvmField val bytes: Long, @JvmField val total: Long) {
        /** -1 while the size is unknown. */
        val percent: Int get() = if (total > 0) (bytes * 100 / total).toInt().coerceIn(0, 100) else -1
    }

    /**
     * A data file that could not be had; the message names the file, the source and the reason. [fetchable] is false
     * when no file involved has a source (an `ext:` file), so trying again or elsewhere cannot help.
     */
    class DownloadException(
        message: String,
        cause: Throwable? = null,
        @JvmField val fetchable: Boolean = true,
    ) : IOException(message, cause)

    private const val TIMEOUT_SECONDS = 30L
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val STALE_TEMP_MS = 60 * 60 * 1000L
    private const val BUFFER_SIZE = 64 * 1024

    /** geodata's prefixes that name a file (rule_parser.go); `geoip:` / `geosite:` read the default files. */
    private val EXT_PREFIXES = listOf("ext:", "ext-ip:", "ext-domain:", "ext-site:")

    /** The category of "failed to check code X from geosite.dat" (geodat_loader.go), as the desktop reads it. */
    private val CATEGORY = Regex("""code\s+(\S+)\s+from""")

    private val locks = ConcurrentHashMap<String, Mutex>()

    fun file(name: String): File = File(app.filesDir, name)

    /** Present and not empty. */
    fun installed(name: String): Boolean = file(name).let { it.isFile && it.length() > 0 }

    fun delete(name: String) {
        file(name).delete()
    }

    /** The configured source of [name]; an empty setting falls back to the default provider. */
    fun urlOf(name: String): String {
        val setting = if (name == GEOIP) SettingsRegistry.XRAY_GEOIP_URL else SettingsRegistry.XRAY_GEOSITE_URL
        val url = if (name == GEOIP) DataStore.xrayGeoipUrl else DataStore.xrayGeositeUrl
        return url.trim().ifEmpty { setting.default }
    }

    fun isValidUrl(url: String): Boolean = url.toHttpUrlOrNull() != null

    fun isGeoError(error: String): Boolean = error.contains(GEOIP) || error.contains(GEOSITE)

    /**
     * The data files [xrayConfigs] read: every string, and every object key (`dns.hosts`), that is a geodata rule
     * (rule_parser.go): `geoip:` needs geoip.dat, `geosite:` geosite.dat, `ext:<file>:<code>` that file.
     */
    fun needed(xrayConfigs: Collection<String>): Set<String> {
        val files = LinkedHashSet<String>()
        for (config in xrayConfigs) {
            if (!config.contains("geoip:") && !config.contains("geosite:") && !config.contains("ext")) continue
            collect(JsonInput.parseObjectOrNull(config) ?: continue, files)
        }
        return files
    }

    fun missing(files: Collection<String>): Set<String> = files.filterTo(LinkedHashSet()) { !installed(it) }

    private fun collect(value: Any?, files: MutableSet<String>) {
        when (value) {
            is String -> assetOf(value)?.let(files::add)
            is JsonObject -> for ((key, child) in value) {
                assetOf(key)?.let(files::add)
                collect(child, files)
            }

            is JsonArray -> for (child in value) collect(child, files)
        }
    }

    private fun assetOf(rule: String): String? {
        val r = rule.trimStart('!')
        if (r.startsWith("geoip:")) return GEOIP
        if (r.startsWith("geosite:")) return GEOSITE
        val prefix = EXT_PREFIXES.firstOrNull { r.startsWith(it) } ?: return null
        return r.substring(prefix.length).substringBefore(':', "").takeIf { it.isNotEmpty() }
    }

    /**
     * Fetches whatever of [files] is missing, one after the other; returns the files still missing, with the reason.
     * Only geoip.dat and geosite.dat have a source. [abort] is polled while a file streams in.
     */
    suspend fun ensure(
        files: Collection<String>,
        abort: () -> Boolean = { false },
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): Map<String, String> {
        val failures = LinkedHashMap<String, String>()
        for (name in files) {
            if (installed(name)) continue
            try {
                download(name, force = false, abort = abort, onProgress = onProgress)
            } catch (e: DownloadException) {
                failures[name] = e.readableMessage
            }
        }
        return failures
    }

    /**
     * Downloads [name] from its source into place (a temp file of this process, then a rename), unless another caller
     * of this process fetched it meanwhile and [force] is off. Through the mixed inbound when a core runs connected and
     * app requests use the proxy, direct otherwise. Throws [DownloadException].
     */
    suspend fun download(
        name: String,
        force: Boolean = false,
        abort: () -> Boolean = { false },
        onProgress: (suspend (Progress) -> Unit)? = null,
    ): Unit = withContext(Dispatchers.IO) {
        if (name != GEOIP && name != GEOSITE) {
            throw DownloadException(app.getString(R.string.xray_geo_not_downloadable, name), fetchable = false)
        }
        locks.computeIfAbsent(name) { Mutex() }.withLock {
            if (!force && installed(name)) return@withContext
            val url = urlOf(name)
            Logs.i("Downloading Xray geo asset $name from $url")
            try {
                fetch(name, url, abort, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A cancelled caller surfaces here as the IOException of its cancelled call.
                currentCoroutineContext().ensureActive()
                Logs.w("Failed to download Xray geo asset $name: ${e.readableMessage}")
                throw DownloadException(app.getString(R.string.xray_geo_download_failed, name, url, e.readableMessage), e)
            }
            Logs.i("Downloaded Xray geo asset $name")
        }
    }

    private suspend fun fetch(
        name: String,
        url: String,
        abort: () -> Boolean,
        onProgress: (suspend (Progress) -> Unit)?,
    ): Unit = coroutineScope {
        val target = file(name)
        // Per process: the settings screen and :bg may fetch the same file at once, each renames a complete copy.
        val temp = File(target.parentFile, "$name.${Process.myPid()}.tmp")
        sweepStaleTemps(name, temp)
        val client = appHttpClient(viaProxy = appRequestsViaProxy() && serviceConnected(), timeoutSeconds = TIMEOUT_SECONDS)
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", appUserAgent()).build())
        // A blocking read ignores coroutine cancellation; cancelling the call ends it.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.execute().use { response ->
                if (downgraded(response)) throw IOException(app.getString(R.string.subs_insecure_redirect))
                if (!response.isSuccessful) throw IOException(app.getString(R.string.xray_geo_http_status, response.code))
                val body = response.body ?: throw IOException(app.getString(R.string.xray_geo_empty_response))
                val total = body.contentLength()
                var bytes = 0L
                var reportedAt = 0L
                FileOutputStream(temp).use { out ->
                    val input = body.byteStream()
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        if (abort()) throw IOException(app.getString(R.string.xray_geo_aborted))
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        bytes += n
                        val now = SystemClock.elapsedRealtime()
                        if (onProgress != null && now - reportedAt >= PROGRESS_INTERVAL_MS) {
                            reportedAt = now
                            onProgress(Progress(name, bytes, total))
                        }
                    }
                    out.fd.sync()
                }
                if (bytes == 0L) throw IOException(app.getString(R.string.xray_geo_empty_response))
                onProgress?.invoke(Progress(name, bytes, total))
                if (!temp.renameTo(target)) throw IOException(app.getString(R.string.xray_geo_save_failed, target.path))
            }
        } finally {
            watcher.cancel()
            temp.delete()
        }
    }

    /** NoLessSafeRedirectPolicy (HTTPRequestHelper.cpp:124): a redirect chain must never leave https for http. */
    private fun downgraded(response: Response): Boolean {
        var hop = response
        while (true) {
            val prior = hop.priorResponse ?: return false
            if (prior.request.url.isHttps && !hop.request.url.isHttps) return true
            hop = prior
        }
    }

    /** Temp files a killed process left behind; a live download keeps its file fresh. */
    private fun sweepStaleTemps(name: String, own: File) {
        val now = System.currentTimeMillis()
        own.parentFile?.listFiles { file ->
            file != own && file.name.startsWith("$name.") && file.name.endsWith(".tmp") &&
                now - file.lastModified() > STALE_TEMP_MS
        }?.forEach { it.delete() }
    }

    fun progressText(progress: Progress): String = if (progress.percent >= 0) {
        app.getString(R.string.xray_geo_downloading_percent, progress.file, progress.percent)
    } else {
        app.getString(R.string.xray_geo_downloading_bytes, progress.file, Formatter.formatShortFileSize(app, progress.bytes))
    }

    /**
     * handleXrayGeoAssetError's diagnosis of an Xray error that mentions geoip.dat / geosite.dat, as a message for the
     * config [contextName]: an installed file that lacks the category (named when the error carries it), else the file
     * that is not installed. Null for any other error.
     */
    fun describeFailure(error: String, contextName: String): String? {
        val referenced = FILES.filter { error.contains(it) }
        if (referenced.isEmpty()) return null
        // "failed to open <file>" (geodat_loader.go): Xray could not read it at all, installed or not.
        val lacking = if (error.contains("failed to open")) null else referenced.lastOrNull { installed(it) }
        if (lacking != null) {
            val category = CATEGORY.find(error)?.groupValues?.get(1).orEmpty()
            val needed = if (category.isEmpty()) {
                app.getString(R.string.xray_geo_some_category)
            } else {
                "${lacking.removeSuffix(".dat")}:${category.lowercase()}"
            }
            return app.getString(R.string.xray_geo_missing_category, contextName, needed, lacking)
        }
        return app.getString(R.string.xray_geo_missing_file, contextName, referenced.joinToString(", "))
    }
}
