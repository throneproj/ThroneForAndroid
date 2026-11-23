package io.nekohasekai.sagernet.update

import android.content.Context
import android.os.Build
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.appRequestsViaProxy
import io.nekohasekai.sagernet.ktx.newHttpClient
import io.nekohasekai.sagernet.ktx.readableMessage
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * GitHub releases of ThroneForAndroid (the desktop's CheckUpdate: `allow_beta_update` admits pre-releases). A release
 * built by CI carries `throne-update.json` (buildScript/release_assets.py) and can be installed in-app; older releases
 * are offered in the browser only.
 */
object UpdateChecker {

    const val REPOSITORY = "throneproj/ThroneForAndroid"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
    private const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY/releases?per_page=20"

    // The API host, not raw.githubusercontent.com, which is blocked where the releases call still works (as desktop).
    private const val CHANGELOG_API = "https://api.github.com/repos/$REPOSITORY/contents/CHANGELOG.md"
    private const val MANIFEST = "throne-update.json"
    private const val MAX_MANIFESTS = 5
    private const val MAX_MANIFEST_BYTES = 256 * 1024
    private const val MAX_CHANGELOG_BYTES = 1024 * 1024

    class Asset(val name: String, val size: Long, val url: String)

    class Release(
        val tag: String,
        val htmlUrl: String,
        val body: String,
        val prerelease: Boolean,
        val assets: List<Asset>,
    )

    class ManifestAsset(val name: String, val size: Long, val sha256: String)

    /**
     * The newest eligible release. [apk] and [expected] are set when it can be installed in-app; otherwise
     * [browserReason] says why only the release page is offered. [notes] is Markdown, empty unless asked for.
     */
    class Offer(
        val release: Release,
        val versionName: String,
        val versionCode: Long,
        val abi: String?,
        val apk: Asset?,
        val expected: ManifestAsset?,
        val browserReason: String?,
        val notes: String,
    ) {
        val installable get() = apk != null && expected != null && browserReason == null
    }

    sealed class Result {
        object UpToDate : Result()
        class Available(val offer: Offer) : Result()
        class Failed(val message: String) : Result()
    }

    // Always verified TLS, unlike net_insecure: the downloaded APK is trusted on this channel's word.
    fun httpClient(timeoutSeconds: Long = 30): OkHttpClient =
        newHttpClient(viaProxy = appRequestsViaProxy(), allowInsecure = false, timeoutSeconds = timeoutSeconds)

    /** [withNotes]: also fetch the release notes, which only the updater UI shows. */
    fun check(context: Context, withNotes: Boolean = false): Result = try {
        find(context, withNotes)
    } catch (e: Exception) {
        Logs.w("Update check failed", e)
        Result.Failed(e.readableMessage)
    }

    private fun find(context: Context, withNotes: Boolean): Result {
        val client = httpClient()
        val allowBeta = DataStore.allowBetaUpdate
        val installed = Semver.parse(BuildConfig.VERSION_NAME)
        val candidates = fetchReleases(context, client)
            .filter { allowBeta || !it.prerelease }
            .mapNotNull { release -> Semver.parse(release.tag)?.let { release to it } }
            .filter { (_, version) -> installed == null || Semver.compare(version, installed) > 0 }
            .sortedWith { a, b -> Semver.compare(b.second, a.second) }
            .map { it.first }
        if (candidates.isEmpty()) return Result.UpToDate
        fun notes(release: Release) = if (withNotes) notesOf(client, release) else ""

        var best: Pair<Release, JSONObject>? = null
        // A newer release whose manifest is unreadable (its host can fail where the API answers) fails the check.
        var manifestError: String? = null
        for (release in candidates.filter { r -> r.assets.any { it.name == MANIFEST } }.take(MAX_MANIFESTS)) {
            val manifest = try {
                fetchManifest(client, release.assets.first { it.name == MANIFEST })
            } catch (e: Exception) {
                Logs.w("Update manifest of ${release.tag}: ${e.readableMessage}")
                if (manifestError == null) manifestError = "${release.tag}: ${e.readableMessage}"
                continue
            }
            if (manifest.optString("packageName") != context.packageName) continue
            val code = manifest.optLong("versionCode")
            if (code <= BuildConfig.VERSION_CODE || manifest.optInt("minSdk", 1) > Build.VERSION.SDK_INT) continue
            if (best == null || code > best.second.optLong("versionCode")) best = release to manifest
        }
        best?.let { (release, manifest) -> return Result.Available(offerOf(context, release, manifest, notes(release))) }
        manifestError?.let { return Result.Failed(it) }

        // Releases without a manifest predate the in-app updater: open them in the browser.
        val legacy = candidates.firstOrNull { r -> r.assets.none { it.name == MANIFEST } } ?: return Result.UpToDate
        return Result.Available(
            Offer(
                legacy, legacy.tag.removePrefix("v"), 0, null, null, null,
                context.getString(R.string.update_reason_no_manifest), notes(legacy)
            )
        )
    }

    private fun offerOf(context: Context, release: Release, manifest: JSONObject, notes: String): Offer {
        val versionName = manifest.optString("versionName").ifEmpty { release.tag.removePrefix("v") }
        val versionCode = manifest.getLong("versionCode")
        val assets = manifest.optJSONObject("assets") ?: JSONObject()
        fun browserOnly(abi: String?, reason: String) =
            Offer(release, versionName, versionCode, abi, null, null, reason, notes)
        if (manifest.optInt("schema") != 1) {
            return browserOnly(null, context.getString(R.string.update_reason_no_manifest))
        }
        // Never switch ABI silently: the installed split decides, the device list is only a fallback.
        val installedAbi = installedAbi(context)
        val abi = if (installedAbi != null) installedAbi.takeIf { assets.has(it) }
        else Build.SUPPORTED_ABIS.firstOrNull { assets.has(it) }
        if (abi == null) {
            val wanted = installedAbi ?: Build.SUPPORTED_ABIS.joinToString()
            return browserOnly(installedAbi, context.getString(R.string.update_reason_no_abi, wanted))
        }
        val entry = assets.getJSONObject(abi)
        val expected = ManifestAsset(entry.getString("name"), entry.getLong("size"), entry.getString("sha256").lowercase())
        val apk = release.assets.firstOrNull { it.name == expected.name && it.size == expected.size }
            ?: return browserOnly(abi, context.getString(R.string.update_reason_no_abi, abi))
        return Offer(release, versionName, versionCode, abi, apk, expected, null, notes)
    }

    /** The CHANGELOG.md sections up to the release, read at its tag; tags that predate the file keep their body. */
    private fun notesOf(client: OkHttpClient, release: Release): String {
        val notes = try {
            ReleaseNotes.select(fetchChangelog(client, release.tag), BuildConfig.VERSION_NAME, release.tag)
        } catch (e: Exception) {
            Logs.w("Changelog of ${release.tag}: ${e.readableMessage}")
            ""
        }
        return notes.ifEmpty { release.body.trim() }
    }

    private fun fetchChangelog(client: OkHttpClient, tag: String): String {
        val url = CHANGELOG_API.toHttpUrl().newBuilder().addQueryParameter("ref", tag).build()
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github.raw+json")
            .header("User-Agent", USER_AGENT)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: throw IOException("empty response")
            return String(readLimited(stream, MAX_CHANGELOG_BYTES), Charsets.UTF_8)
        }
    }

    /** The ABI of the installed split: the extracted native library directory (useLegacyPackaging) names it. */
    fun installedAbi(context: Context): String? {
        return when (File(context.applicationInfo.nativeLibraryDir ?: return null).name) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> null
        }
    }

    private fun fetchReleases(context: Context, client: OkHttpClient): List<Release> {
        val cache = File(context.cacheDir, "update/releases.json")
        val cached = runCatching { JSONObject(cache.readText()) }.getOrNull()
        val request = Request.Builder().url(RELEASES_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .apply { cached?.optString("etag")?.takeIf { it.isNotEmpty() }?.let { header("If-None-Match", it) } }
            .build()
        val body = client.newCall(request).execute().use { response ->
            when {
                response.code == 304 && cached != null -> cached.getString("body")
                response.isSuccessful -> response.body?.string().orEmpty().also { body ->
                    response.header("ETag")?.let { etag -> saveCache(cache, etag, body) }
                }
                else -> {
                    val message = runCatching { JSONObject(response.body?.string().orEmpty()).optString("message") }
                        .getOrNull().orEmpty()
                    throw IOException("GitHub: HTTP ${response.code} $message".trim())
                }
            }
        }
        return parseReleases(JSONArray(body))
    }

    private fun saveCache(cache: File, etag: String, body: String) {
        runCatching {
            cache.parentFile?.mkdirs()
            val temp = File(cache.path + ".tmp")
            temp.writeText(JSONObject().put("etag", etag).put("body", body).toString())
            if (!temp.renameTo(cache)) temp.delete()
        }
    }

    private fun parseReleases(array: JSONArray): List<Release> {
        val releases = ArrayList<Release>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            if (json.optBoolean("draft")) continue
            val assetsJson = json.optJSONArray("assets") ?: JSONArray()
            val assets = (0 until assetsJson.length()).mapNotNull { j ->
                val asset = assetsJson.optJSONObject(j) ?: return@mapNotNull null
                Asset(asset.optString("name"), asset.optLong("size"), asset.optString("browser_download_url"))
            }
            releases += Release(
                json.optString("tag_name"),
                json.optString("html_url").ifEmpty { RELEASES_PAGE },
                json.optString("body"),
                json.optBoolean("prerelease"),
                assets,
            )
        }
        return releases
    }

    private fun fetchManifest(client: OkHttpClient, asset: Asset): JSONObject {
        val request = Request.Builder().url(asset.url).header("User-Agent", USER_AGENT).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val stream = response.body?.byteStream() ?: throw IOException("empty response")
            return JSONObject(String(readLimited(stream, MAX_MANIFEST_BYTES)))
        }
    }

    private fun readLimited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > max) throw IOException("response larger than $max bytes")
        }
        return out.toByteArray()
    }
}
