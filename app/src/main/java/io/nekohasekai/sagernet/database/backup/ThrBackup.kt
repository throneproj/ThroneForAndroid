package io.nekohasekai.sagernet.database.backup

import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop's `.thrbackup` container (dialog_basic_settings.cpp:492-835): a QDataStream (little-endian, Qt_6_0)
 * of the magic "THRN", a quint32 format version, the compact metadata JSON as a QString (UTF-16LE) and a
 * QMap<QString, QByteArray> of files: [DATABASE] (a SQLite file) and [DESKTOP_ICONS]`<name>` (the desktop's tray
 * icons); older Android builds also wrote `android/custom_icon/<name>`. Only the database is used, every other entry
 * is skipped. Formats 1..2 are read as strictly as Qt reads them; only format 2 is written, because the desktop
 * refuses anything newer.
 */
object ThrBackup {

    /** BACKUP_FORMAT_VERSION. */
    const val FORMAT_VERSION = 2

    /** BACKUP_CONTENT_VERSION; written, never read. */
    const val CONTENT_VERSION = 2

    const val PLATFORM_ANDROID = "android"
    const val DATABASE = "database"
    const val DESKTOP_ICONS = "icons/"

    private val MAGIC = byteArrayOf(0x54, 0x48, 0x52, 0x4E)
    private const val NULL_SIZE = 0xFFFFFFFFL

    /** 0xFFFFFFFE only announces a 64-bit size from Qt_6_7 on, so a Qt_6_0 stream holds at most this. */
    private const val MAX_SIZE = 0xFFFFFFFDL
    private const val MAX_TEXT = 16L shl 20

    enum class Kind { NOT_BACKUP, UNSUPPORTED_VERSION, CORRUPT }

    class FormatException(val kind: Kind, message: String, val version: Long = 0L) : IOException(message)

    /** Configs::BackupParts (Database.h:29-39). */
    data class Parts(
        val profiles: Boolean = false,
        val routes: Boolean = false,
        val settings: Boolean = false,
        val otp: Boolean = false,
        val icons: Boolean = false,
        /** The scanner's IP lists and saved scans (meta key "ipLists"). */
        val ipLists: Boolean = false,
    ) {
        fun anyDb(): Boolean = profiles || routes || settings || otp || ipLists
        fun any(): Boolean = anyDb() || icons
    }

    /** A parsed container; the database entry went to the file given to [read]. */
    class Contents(
        val formatVersion: Int,
        val meta: JsonObject,
        val keys: Set<String>,
    ) {
        val parts: Parts = partsFromMeta(formatVersion, meta, keys)
        val createdAt: String get() = meta.string("created_at")
        val platform: String get() = meta.string("platform")
        val isAndroid: Boolean get() = platform == PLATFORM_ANDROID

        /** The name of the device that made the backup, empty when unknown; untrusted, plain text only. */
        val deviceName: String get() = meta.obj("device").string("name")
    }

    /** Written by bytes or copied from a file (the SQLite snapshot). */
    sealed class Payload {
        abstract val size: Long

        class Bytes(val bytes: ByteArray) : Payload() {
            override val size: Long get() = bytes.size.toLong()
        }

        class FromFile(val file: File) : Payload() {
            override val size: Long = file.length()
        }
    }

    /** Whether [input] starts with the magic; consumes up to 4 bytes. */
    fun hasMagic(input: InputStream): Boolean {
        val head = ByteArray(4)
        var n = 0
        while (n < 4) {
            val r = input.read(head, n, 4 - n)
            if (r < 0) return false
            n += r
        }
        return head.contentEquals(MAGIC)
    }

    /**
     * Reads a container. The [DATABASE] entry is streamed into [database] (skipped when null); all other entries are
     * skipped. Trailing bytes are ignored, like Qt does.
     */
    fun read(input: InputStream, database: File?): Contents {
        val r = Reader(if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024))
        if (!hasMagic(r.input)) throw FormatException(Kind.NOT_BACKUP, "Not a valid Throne backup file.")
        val version = r.u32()
        if (version < 1 || version > FORMAT_VERSION) {
            throw FormatException(Kind.UNSUPPORTED_VERSION, "Unsupported backup format version: $version", version)
        }
        val metaText = r.qstring()
        val meta = metaText?.let { JsonInput.parseObjectOrNull(it) } ?: JsonObject()
        val count = r.u32()
        if (count == NULL_SIZE) throw corrupt("invalid entry count")
        val keys = LinkedHashSet<String>()
        var i = 0L
        while (i < count) {
            val key = r.qstring() ?: throw corrupt("null entry name")
            val size = r.size() ?: 0L
            keys.add(key)
            if (key == DATABASE && database != null) {
                database.outputStream().use { r.copy(size, it) }
            } else {
                r.copy(size, null)
            }
            i++
        }
        return Contents(version.toInt(), meta, keys)
    }

    /** BackupPartsFromMeta (dialog_basic_settings.cpp:496-515); only JSON `true` counts, as QJsonValue::toBool. */
    fun partsFromMeta(formatVersion: Int, meta: JsonObject, keys: Collection<String>): Parts {
        val hasDatabase = DATABASE in keys
        val hasIcons = keys.any { it.startsWith(DESKTOP_ICONS) }
        if (formatVersion >= 2 && meta.contains("parts")) {
            val po = meta.obj("parts")
            return Parts(
                profiles = po.bool("profiles") && hasDatabase,
                routes = po.bool("routes") && hasDatabase,
                settings = po.bool("settings") && hasDatabase,
                otp = po.bool("otp") && hasDatabase,
                icons = po.bool("icons") && hasIcons,
                ipLists = po.bool("ipLists") && hasDatabase,
            )
        }
        return Parts(profiles = hasDatabase, routes = hasDatabase, settings = hasDatabase, icons = hasIcons)
    }

    /** Writes format 2 with [files] in QMap order (ascending UTF-16 code units, which is String.compareTo). */
    fun write(output: OutputStream, meta: JsonObject, files: Map<String, Payload>) {
        val out = BufferedOutputStream(output, 64 * 1024)
        out.write(MAGIC)
        u32(out, FORMAT_VERSION.toLong())
        qstring(out, meta.toCompact())
        u32(out, files.size.toLong())
        for (key in files.keys.sorted()) {
            val payload = files.getValue(key)
            if (payload.size > MAX_SIZE) throw IOException("$key is too large for a backup")
            qstring(out, key)
            u32(out, payload.size)
            when (payload) {
                is Payload.Bytes -> out.write(payload.bytes)
                is Payload.FromFile -> payload.file.inputStream().use { copyExactly(it, out, payload.size) }
            }
        }
        out.flush()
    }

    /**
     * The metadata an Android backup carries. Both `icons` flags stay false: Android writes no icons, and the desktop's
     * part means its tray icons. `device` and the top-level `app_version` are the shared WebDAV contract's.
     */
    fun androidMeta(
        parts: Parts,
        appVersion: String,
        deviceId: String,
        deviceName: String,
        created: Date = Date(),
    ): JsonObject =
        JsonObject().apply {
            this["android"] = JsonObject().apply {
                this["app_version"] = appVersion
                this["icons"] = false
            }
            this["app_version"] = appVersion
            this["backup_version"] = CONTENT_VERSION
            this["created_at"] = textDate(created)
            this["device"] = JsonObject().apply {
                this["id"] = deviceId
                this["name"] = deviceName
            }
            this["parts"] = JsonObject().apply {
                this["icons"] = false
                this["ipLists"] = parts.ipLists
                this["otp"] = parts.otp
                this["profiles"] = parts.profiles
                this["routes"] = parts.routes
                this["settings"] = parts.settings
            }
            this["platform"] = PLATFORM_ANDROID
        }

    /** QDateTime::currentDateTime().toString(Qt::TextDate) in local time: "Wed Sep 24 03:43:00 2026". */
    fun textDate(date: Date): String = SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", Locale.US).format(date)

    private fun corrupt(what: String) = FormatException(Kind.CORRUPT, "Damaged backup file: $what")

    private class Reader(val input: InputStream) {
        private val four = ByteArray(4)
        private val buffer = ByteArray(64 * 1024)

        fun readFully(b: ByteArray, len: Int) {
            var n = 0
            while (n < len) {
                val r = input.read(b, n, len - n)
                if (r < 0) throw corrupt("unexpected end of file")
                n += r
            }
        }

        fun u32(): Long {
            readFully(four, 4)
            return (four[0].toLong() and 0xFF) or ((four[1].toLong() and 0xFF) shl 8) or
                ((four[2].toLong() and 0xFF) shl 16) or ((four[3].toLong() and 0xFF) shl 24)
        }

        /** A QByteArray / QString length; null for the null marker. */
        fun size(): Long? {
            val n = u32()
            if (n == NULL_SIZE) return null
            if (n > MAX_SIZE) throw corrupt("invalid length")
            return n
        }

        /** Qt keeps lone surrogates and refuses an odd byte count (ReadCorruptData). */
        fun qstring(): String? {
            val n = size() ?: return null
            if (n % 2 != 0L) throw corrupt("odd string length")
            if (n > MAX_TEXT) throw corrupt("string too long")
            val bytes = ByteArray(n.toInt())
            readFully(bytes, bytes.size)
            val chars = CharArray(bytes.size / 2) { i ->
                ((bytes[2 * i].toInt() and 0xFF) or ((bytes[2 * i + 1].toInt() and 0xFF) shl 8)).toChar()
            }
            return String(chars)
        }

        fun copy(n: Long, out: OutputStream?) {
            var left = n
            while (left > 0) {
                val r = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (r < 0) throw corrupt("unexpected end of file")
                out?.write(buffer, 0, r)
                left -= r
            }
        }
    }

    private fun u32(out: OutputStream, v: Long) {
        out.write((v and 0xFF).toInt())
        out.write(((v shr 8) and 0xFF).toInt())
        out.write(((v shr 16) and 0xFF).toInt())
        out.write(((v shr 24) and 0xFF).toInt())
    }

    private fun qstring(out: OutputStream, s: String) {
        val bytes = ByteArray(s.length * 2)
        for (i in s.indices) {
            val c = s[i].code
            bytes[2 * i] = (c and 0xFF).toByte()
            bytes[2 * i + 1] = (c shr 8).toByte()
        }
        u32(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buffer = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val r = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            if (r < 0) throw IOException("file shrank while being written")
            out.write(buffer, 0, r)
            left -= r
        }
    }
}
