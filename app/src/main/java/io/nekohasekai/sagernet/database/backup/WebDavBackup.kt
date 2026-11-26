package io.nekohasekai.sagernet.database.backup

import android.util.Xml
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.newHttpClient
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * `.thrbackup` files in `<webdavServer>/<webdavPath>/` over WebDAV with Basic auth (the device-local settings), and
 * the `throne_backups.json` index next to them, shared with the desktop (WebDAV backup contract v1). The folder
 * listing decides which backups exist; the index adds who made each one and how to check its download.
 */
class WebDavBackup private constructor(private val base: HttpUrl, private val segments: List<String>) {

    /**
     * A backup in the folder. An [indexed] one comes from the index; any other is synthesized from the listing.
     * [createdAt] is epoch milliseconds (0 = unknown), [size] -1 when unknown. The texts are untrusted and already
     * passed through [plain]; [file] is the exact name on the server.
     */
    class Entry(
        val file: String,
        val createdAt: Long,
        val size: Long,
        val deviceName: String,
        val platform: String,
        val appVersion: String,
        val parts: List<String>,
        val indexed: Boolean,
        val thisDevice: Boolean,
        internal val expectedSize: Long,
        internal val expectedSha256: String,
    )

    /** A finished upload as [file]; [indexError] says why the index was not updated, null when it was. */
    class Upload(val file: String, val indexError: String?)

    private class Listed(val name: String, val size: Long, val modified: Long)

    private class Index(
        val root: JsonObject,
        val entries: MutableList<JsonObject>,
        val format: Long,
        val etag: String?,
        val existed: Boolean,
    )

    /** None without a user or password, as desktop: an empty pair would still go out as `Basic Og==`. */
    private val authorization: String? = run {
        val user = DataStore.webdavUsername.orEmpty()
        val password = DataStore.webdavPassword.orEmpty()
        if (user.isEmpty() && password.isEmpty()) null else Credentials.basic(user, password, Charsets.UTF_8)
    }

    /** Direct; net_insecure lets self-signed and private-CA servers through, as on desktop. */
    private val client = newHttpClient(viaProxy = false, allowInsecure = DataStore.netInsecure, timeoutSeconds = 60)
        .newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    /** For PROPFIND, MKCOL, PUT and DELETE: a followed 301/302 may turn into a GET. */
    private val direct = client.newBuilder().followRedirects(false).followSslRedirects(false).build()

    /** The folder, with a collection's trailing slash. */
    val directory: HttpUrl = collection(segments.size)

    /** Reaches the folder with the credentials, creating it when it is missing. */
    fun check() = ensureDirectory()

    /**
     * Uploads [file], a backup of [parts], under a new name, then records it in the index. The PUT decides success:
     * an index failure only comes back as [Upload.indexError].
     */
    fun upload(file: File, parts: ThrBackup.Parts): Upload {
        ensureDirectory()
        val now = Date()
        val name = fileName(now)
        val digest = MessageDigest.getInstance("SHA-256")
        val size = file.inputStream().use { Measured(it, digest).drain() }
        val put = request(fileUrl(name)).put(file.asRequestBody(OCTET_STREAM.toMediaType())).build()
        direct.newCall(put).execute().use { requireOk(it) }
        val entry = JsonObject().apply {
            this["file"] = name
            this["created_at"] = utc(ISO_FORMAT).format(now)
            this["size"] = size
            this["sha256"] = hex(digest.digest())
            this["device_id"] = DataStore.deviceId
            this["device_name"] = DataStore.effectiveDeviceName()
            this["platform"] = ThrBackup.PLATFORM_ANDROID
            this["app_version"] = BuildConfig.VERSION_NAME
            this["parts"] = JsonArray().apply {
                if (parts.profiles) add(PART_PROFILES)
                if (parts.routes) add(PART_ROUTES)
                if (parts.settings) add(PART_SETTINGS)
                if (parts.otp) add(PART_OTP)
                if (parts.ipLists) add(PART_IP_LISTS)
                if (parts.icons) add(PART_ICONS)
            }
        }
        val indexError = updateIndexSafely(keep = name) { entries ->
            entries.removeAll { it.string("file") == name }
            entries.add(0, entry)
        }
        return Upload(name, indexError)
    }

    /** The folder's backups joined with the index, newest first; empty when the folder does not exist. */
    fun list(): List<Entry> {
        val listed = listFolder() ?: return emptyList()
        val index = try {
            readIndex().entries
        } catch (e: IOException) {
            Logs.w("WebDAV: $INDEX_FILE unreadable", e)
            emptyList()
        }
        val byFile = HashMap<String, JsonObject>()
        for (entry in index) byFile.putIfAbsent(entry.string("file"), entry)
        val self = DataStore.deviceId
        return listed.map { remote -> byFile[remote.name]?.let { indexed(it, remote, self) } ?: synthesized(remote) }
            .sortedWith(compareByDescending<Entry> { it.createdAt }.thenByDescending { it.file })
    }

    /**
     * Downloads and parses [entry]. An indexed entry's size and SHA-256 must match the whole file, so a backup replaced
     * or damaged since it was recorded is refused.
     */
    fun download(entry: Entry): BackupRestore.Loaded {
        val request = request(fileUrl(entry.file)).get().build()
        return client.newCall(request).execute().use { response ->
            requireOk(response)
            val body = response.body ?: throw IOException(app.getString(R.string.webdav_server_error))
            val digest = MessageDigest.getInstance("SHA-256")
            Measured(body.byteStream(), digest).use { input ->
                val loaded = BackupRestore.load(input)
                if (entry.expectedSize >= 0 || entry.expectedSha256.isNotEmpty()) {
                    val matches = try {
                        // ThrBackup.read stops after the last entry: the digest has to cover the trailing bytes too.
                        input.drain()
                        (entry.expectedSize < 0 || input.count == entry.expectedSize) &&
                            (entry.expectedSha256.isEmpty() || hex(digest.digest()) == entry.expectedSha256)
                    } catch (e: Throwable) {
                        loaded.discard()
                        throw e
                    }
                    if (!matches) {
                        loaded.discard()
                        throw IOException(app.getString(R.string.webdav_backup_mismatch))
                    }
                }
                loaded
            }
        }
    }

    /** Deletes [entry]'s file (already gone is fine), then drops it from the index; returns why that part failed. */
    fun delete(entry: Entry): String? {
        val request = request(fileUrl(entry.file)).delete().build()
        direct.newCall(request).execute().use { if (it.code != 404) requireOk(it) }
        return updateIndexSafely(keep = null) { entries -> entries.removeAll { it.string("file") == entry.file } }
    }

    // ------------------------------------------------------------------------------------------------ index

    private fun updateIndexSafely(keep: String?, change: (MutableList<JsonObject>) -> Unit): String? = try {
        updateIndex(keep, change)
        null
    } catch (e: Exception) {
        Logs.w("WebDAV: $INDEX_FILE not updated", e)
        e.readableMessage
    }

    /**
     * Read-modify-write under the index's ETag: `If-Match` with a strong ETag, `If-None-Match: *` while there is no
     * index. A 412 means another device wrote in between and starts the next round; the last round writes
     * unconditionally, for servers that mishandle conditional requests. Entries whose file is no longer listed are
     * dropped, except [keep], which a server may list late.
     */
    private fun updateIndex(keep: String?, change: (MutableList<JsonObject>) -> Unit) {
        for (round in 1..INDEX_ROUNDS) {
            val index = readIndex()
            if (index.format > INDEX_FORMAT) {
                Logs.w("WebDAV: $INDEX_FILE has format ${index.format}, newer than $INDEX_FORMAT; left unchanged")
                return
            }
            val listed = listFolder().orEmpty().mapTo(HashSet()) { it.name }
            val entries = index.entries
            entries.retainAll { it.string("file").let { file -> file in listed || file == keep } }
            change(entries)
            val ordered = entries.sortedByDescending { parseIso(it.string("created_at")) }.take(MAX_ENTRIES)
            index.root["format"] = INDEX_FORMAT
            index.root["backups"] = JsonArray().apply { ordered.forEach { add(it) } }
            val body = index.root.toCompact().toByteArray(Charsets.UTF_8).toRequestBody(JSON.toMediaType())
            val put = request(fileUrl(INDEX_FILE)).put(body)
            if (round < INDEX_ROUNDS) {
                if (index.etag != null) {
                    put.header("If-Match", index.etag)
                } else if (!index.existed) {
                    put.header("If-None-Match", "*")
                }
            }
            val conflict = direct.newCall(put.build()).execute().use { response ->
                if (response.code == 412 && round < INDEX_ROUNDS) return@use true
                requireOk(response)
                false
            }
            if (!conflict) return
        }
    }

    /** GET the index; a missing, oversized or malformed one reads as empty (the ETag is kept for the next write). */
    private fun readIndex(): Index {
        val request = request(fileUrl(INDEX_FILE)).get().build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 404) return@use Index(JsonObject(), ArrayList(), INDEX_FORMAT, null, existed = false)
            requireOk(response)
            val etag = response.header("ETag")?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            val root = response.body?.byteStream()?.use { readCapped(it) }
                ?.let { JsonInput.parseObjectOrNull(it.removePrefix("\uFEFF")) } ?: JsonObject()
            val format = if (root.isNumber("format")) root.integer("format") else INDEX_FORMAT
            val seen = HashSet<String>()
            val entries = root.array("backups").mapNotNullTo(ArrayList<JsonObject>()) { item ->
                (item as? JsonObject)?.takeIf { isSafeName(it.string("file")) && seen.add(it.string("file")) }
            }
            Index(root, entries, format, etag, existed = true)
        }
    }

    private fun readCapped(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_INDEX_BYTES) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun indexed(o: JsonObject, listed: Listed, self: String): Entry {
        val expectedSize = o.takeIf { it.isNumber("size") }?.integer("size")?.takeIf { it >= 0 } ?: -1L
        val deviceId = o.string("device_id")
        return Entry(
            file = listed.name,
            createdAt = parseIso(o.string("created_at")).takeIf { it > 0 } ?: listed.modified,
            size = if (expectedSize >= 0) expectedSize else listed.size,
            deviceName = plain(o.string("device_name")),
            platform = plain(o.string("platform")),
            appVersion = plain(o.string("app_version")),
            parts = o.array("parts").mapNotNull { (it as? String)?.let(::plain) },
            indexed = true,
            thisDevice = deviceId.isNotEmpty() && deviceId == self,
            expectedSize = expectedSize,
            expectedSha256 = o.string("sha256").lowercase(Locale.ROOT).takeIf { SHA256.matches(it) }.orEmpty(),
        )
    }

    private fun synthesized(listed: Listed) = Entry(
        file = listed.name,
        createdAt = listed.modified.takeIf { it > 0 } ?: stampTime(listed.name),
        size = listed.size,
        deviceName = "",
        platform = "",
        appVersion = "",
        parts = emptyList(),
        indexed = false,
        thisDevice = false,
        expectedSize = -1L,
        expectedSha256 = "",
    )

    // ------------------------------------------------------------------------------------------------ transport

    private fun request(url: HttpUrl) = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        .apply { authorization?.let { header("Authorization", it) } }

    /** The first [depth] folder segments as a collection URL. */
    private fun collection(depth: Int): HttpUrl = base.newBuilder().apply {
        segments.take(depth).forEach { addPathSegment(it) }
        addPathSegment("")
    }.build()

    private fun fileUrl(name: String): HttpUrl = directory.newBuilder().addPathSegment(name).build()

    /** PROPFIND the folder; on 404 MKCOL each configured segment in turn (405 = already there). */
    private fun ensureDirectory() {
        val probe = request(directory).method("PROPFIND", null).header("Depth", "0").build()
        val missing = direct.newCall(probe).execute().use { response ->
            if (response.code != 404) requireOk(response)
            response.code == 404
        }
        if (!missing) return
        for (depth in 1..segments.size) {
            val mkcol = request(collection(depth)).method("MKCOL", null).build()
            direct.newCall(mkcol).execute().use { response ->
                when {
                    response.isSuccessful || response.code == 405 -> Unit
                    response.code in 300..399 || response.code == 401 || response.code == 403 -> requireOk(response)
                    else -> throw IOException(app.getString(R.string.webdav_create_dir_failed))
                }
            }
        }
    }

    /** PROPFIND Depth 1: the folder's `.thrbackup` files, null when the folder does not exist. */
    private fun listFolder(): List<Listed>? {
        val request = request(directory)
            .method("PROPFIND", PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .header("Depth", "1")
            .build()
        return direct.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            requireOk(response)
            val body = response.body ?: return@use emptyList()
            body.byteStream().use { parse(it) }.distinctBy { it.name }
        }
    }

    private fun requireOk(response: Response) {
        if (response.isSuccessful) return
        throw IOException(
            when (response.code) {
                in 300..399 -> {
                    val location = response.header("Location").orEmpty().trim()
                    val target = location.takeIf { it.isNotEmpty() }
                        ?.let { response.request.url.resolve(it)?.toString() ?: it } ?: "?"
                    app.getString(R.string.webdav_redirected, plain(target))
                }

                401 -> app.getString(R.string.webdav_auth_error)
                403 -> app.getString(R.string.webdav_permission_denied)
                404 -> app.getString(R.string.webdav_server_not_found)
                in 500..599 -> app.getString(R.string.webdav_server_error)
                else -> app.getString(R.string.webdav_connect_failed, response.code)
            }
        )
    }

    /** A multistatus body: each `response` whose `href` is a file named `*.thrbackup` (namespace prefixes vary). */
    private fun parse(input: InputStream): List<Listed> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(input, null)
        val out = ArrayList<Listed>()
        val text = StringBuilder()
        val self = directory.encodedPath.trimEnd('/')
        var href: String? = null
        var size = -1L
        var modified = 0L
        var collection = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    text.setLength(0)
                    if (parser.namespace == DAV) when (parser.name) {
                        "response" -> {
                            href = null
                            size = -1L
                            modified = 0L
                            collection = false
                        }

                        "collection" -> collection = true
                    }
                }

                XmlPullParser.TEXT -> text.append(parser.text)
                XmlPullParser.END_TAG -> if (parser.namespace == DAV) when (parser.name) {
                    "href" -> href = text.toString().trim()
                    "getcontentlength" -> size = text.toString().trim().toLongOrNull() ?: -1L
                    "getlastmodified" -> modified = parseHttpDate(text.toString().trim())
                    "response" -> {
                        val url = href?.let { directory.resolve(it) }
                        val name = url?.pathSegments?.lastOrNull { it.isNotEmpty() }
                        if (!collection && url != null && name != null && url.encodedPath.trimEnd('/') != self &&
                            name.endsWith(EXTENSION, ignoreCase = true)
                        ) {
                            out.add(Listed(name, size, modified))
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    /** The bytes it passes on, counted and hashed; [skip] reads through so nothing escapes the digest. */
    private class Measured(input: InputStream, digest: MessageDigest) : DigestInputStream(input, digest) {
        var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long {
            val buffer = ByteArray(minOf(n, 64 * 1024L).toInt().coerceAtLeast(1))
            var left = n
            while (left > 0) {
                val r = read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (r < 0) break
                left -= r
            }
            return n - left
        }

        /** Reads to the end; returns the total count. */
        fun drain(): Long {
            val buffer = ByteArray(64 * 1024)
            while (read(buffer, 0, buffer.size) >= 0) Unit
            return count
        }
    }

    companion object {
        const val EXTENSION = ".thrbackup"
        const val INDEX_FILE = "throne_backups.json"

        const val PART_PROFILES = "profiles"
        const val PART_ROUTES = "routes"
        const val PART_SETTINGS = "settings"
        const val PART_OTP = "otp"
        const val PART_IP_LISTS = "ip_lists"
        const val PART_ICONS = "icons"

        private const val DAV = "DAV:"
        private const val OCTET_STREAM = "application/octet-stream"
        private const val JSON = "application/json"
        private const val INDEX_FORMAT = 1L
        private const val INDEX_ROUNDS = 4
        private const val MAX_INDEX_BYTES = 4 shl 20
        private const val MAX_ENTRIES = 1000
        private const val DISPLAY_MAX = 200
        private const val STAMP_FORMAT = "yyyyMMdd_HHmmss"
        private const val ISO_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        private val STAMP = Regex("\\d{8}_\\d{6}")
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val UNSAFE_VERSION = Regex("[^A-Za-z0-9._-]")
        private const val PROPFIND_BODY = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/>" +
            "<d:getetag/></d:prop></d:propfind>"

        /** The configured server, or null when there is none. */
        fun configured(): WebDavBackup? {
            val server = DataStore.webdavServer?.trim().orEmpty()
            if (server.isEmpty()) return null
            if (!server.startsWith("http://", true) && !server.startsWith("https://", true)) {
                throw IOException("Invalid server URL: must start with http:// or https://")
            }
            val base = server.toHttpUrlOrNull() ?: throw IOException("Invalid server URL: $server")
            val path = DataStore.webdavPath?.trim('/')?.takeIf { it.isNotBlank() } ?: "Throne"
            return WebDavBackup(base, path.split('/').filter { it.isNotBlank() })
        }

        /** `throne_backup_<version>_<UTC yyyyMMdd_HHmmss>_<4 hex>.thrbackup`: two devices may upload in one second. */
        fun fileName(date: Date = Date()): String {
            val version = BuildConfig.VERSION_NAME.replace(UNSAFE_VERSION, "-")
            val random = Integer.toHexString(Random.nextInt(0x10000)).padStart(4, '0')
            return "throne_backup_${version}_${utc(STAMP_FORMAT).format(date)}_$random$EXTENSION"
        }

        /** Server-supplied text for display: control characters (line breaks too) become spaces, at most 200 chars. */
        fun plain(text: String): String = buildString(text.length) {
            for (c in text) append(if (c.isISOControl()) ' ' else c)
        }.trim().take(DISPLAY_MAX)

        /** An index `file` the client may build a URL from: a bare `*.thrbackup` name. */
        private fun isSafeName(name: String): Boolean =
            '/' !in name && '\\' !in name && name != "." && name != ".." && name.endsWith(EXTENSION, ignoreCase = true)

        private fun utc(pattern: String) = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }

        /** ISO-8601 with seconds (fractions and offsets accepted) as epoch milliseconds; 0 when unparsable. */
        private fun parseIso(text: String): Long {
            if (text.isEmpty()) return 0L
            for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssX", "yyyy-MM-dd'T'HH:mm:ss.SSSX")) {
                runCatching { utc(pattern).parse(text)?.time }.getOrNull()?.let { return it }
            }
            return 0L
        }

        /** The UTC stamp a Throne file name carries, as epoch milliseconds; 0 when there is none. */
        private fun stampTime(name: String): Long = STAMP.find(name)?.value?.let { stamp ->
            runCatching { utc(STAMP_FORMAT).parse(stamp)?.time }.getOrNull()
        } ?: 0L

        private fun parseHttpDate(text: String): Long = try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
                .parse(text)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }

        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
