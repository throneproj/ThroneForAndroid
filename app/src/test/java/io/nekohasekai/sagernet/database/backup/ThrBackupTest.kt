package io.nekohasekai.sagernet.database.backup

import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.GregorianCalendar

/** The `.thrbackup` container as dialog_basic_settings.cpp writes and reads it (QDataStream, LE, Qt_6_0). */
class ThrBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val sqliteHeader = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)

    private fun u32(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun qstring(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(u32(s.length * 2L))
        for (c in s) {
            out.write(c.code and 0xFF)
            out.write(c.code shr 8)
        }
        return out.toByteArray()
    }

    private fun container(version: Long, meta: String?, vararg entries: Pair<String?, ByteArray?>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("THRN".toByteArray())
        out.write(u32(version))
        out.write(if (meta == null) u32(0xFFFFFFFFL) else qstring(meta))
        out.write(u32(entries.size.toLong()))
        for ((key, value) in entries) {
            out.write(if (key == null) u32(0xFFFFFFFFL) else qstring(key))
            if (value == null) out.write(u32(0xFFFFFFFFL)) else {
                out.write(u32(value.size.toLong()))
                out.write(value)
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): Pair<ThrBackup.Contents, ByteArray?> {
        val db = temp.newFile()
        db.delete()
        val contents = ThrBackup.read(ByteArrayInputStream(bytes), db)
        return contents to (if (db.exists()) db.readBytes() else null)
    }

    private fun expectError(kind: ThrBackup.Kind, bytes: ByteArray) {
        try {
            read(bytes)
            fail("expected $kind")
        } catch (e: ThrBackup.FormatException) {
            assertEquals(kind, e.kind)
        }
    }

    @Test
    fun minimalLayout() {
        val db = temp.newFile("snapshot.db").apply { writeBytes(sqliteHeader) }
        val out = ByteArrayOutputStream()
        val meta = JsonObject().apply { this["platform"] = "android" }
        ThrBackup.write(out, meta, mapOf(ThrBackup.DATABASE to ThrBackup.Payload.FromFile(db)))
        val expected = ByteArrayOutputStream().apply {
            write(byteArrayOf(0x54, 0x48, 0x52, 0x4E, 0x02, 0x00, 0x00, 0x00))
            write(qstring("{\"platform\":\"android\"}"))
            write(byteArrayOf(0x01, 0x00, 0x00, 0x00))
            write(byteArrayOf(0x10, 0x00, 0x00, 0x00, 0x64, 0x00, 0x61, 0x00, 0x74, 0x00, 0x61, 0x00, 0x62, 0x00, 0x61, 0x00, 0x73, 0x00, 0x65, 0x00))
            write(u32(16))
            write(sqliteHeader)
        }.toByteArray()
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun roundTripKeepsEntriesInQMapOrder() {
        val db = temp.newFile("snapshot.db").apply { writeBytes(sqliteHeader + ByteArray(70_000) { it.toByte() }) }
        val parts = ThrBackup.Parts(profiles = true, settings = true)
        val meta = ThrBackup.androidMeta(parts, "1.0", "device-id", "Pixel")
        // UTF-16 order puts the surrogate pair (0xD83D) before U+FFFD, code point order would not.
        val keys = listOf("icons/�.png", "icons/😀.png", "about.txt")
        val files = LinkedHashMap<String, ThrBackup.Payload>()
        files[ThrBackup.DATABASE] = ThrBackup.Payload.FromFile(db)
        keys.forEachIndexed { i, key -> files[key] = ThrBackup.Payload.Bytes(byteArrayOf(i.toByte())) }
        val out = ByteArrayOutputStream()
        ThrBackup.write(out, meta, files)

        val (contents, database) = read(out.toByteArray())
        assertEquals(2, contents.formatVersion)
        assertArrayEquals(db.readBytes(), database)
        assertEquals(meta.toCompact(), contents.meta.toCompact())
        assertTrue(contents.isAndroid)
        assertEquals("Pixel", contents.deviceName)
        assertEquals(setOf(ThrBackup.DATABASE) + keys, contents.keys)
        assertEquals(ThrBackup.Parts(profiles = true, settings = true), contents.parts)

        val written = out.toByteArray()
        val order = listOf("about.txt", ThrBackup.DATABASE, "icons/😀.png", "icons/�.png")
        val positions = order.map { key -> indexOf(written, qstring(key)) }
        assertEquals(positions.sorted(), positions)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun androidMetaIsCompactAndSorted() {
        val created = GregorianCalendar(2026, Calendar.SEPTEMBER, 4, 3, 43, 0).time
        val meta = ThrBackup.androidMeta(ThrBackup.Parts(routes = true), "2.0", "device-id", "Pixel", created)
        assertEquals(
            "{\"android\":{\"app_version\":\"2.0\",\"icons\":false},\"app_version\":\"2.0\",\"backup_version\":2," +
                "\"created_at\":\"Fri Sep 4 03:43:00 2026\",\"device\":{\"id\":\"device-id\",\"name\":\"Pixel\"}," +
                "\"parts\":{\"icons\":false,\"ipLists\":false,\"otp\":false," +
                "\"profiles\":false,\"routes\":true,\"settings\":false},\"platform\":\"android\"}",
            meta.toCompact()
        )
    }

    @Test
    fun partsFromMeta() {
        val all = "{\"parts\":{\"icons\":true,\"otp\":true,\"profiles\":true,\"routes\":true,\"settings\":true}}"
        val db = ThrBackup.DATABASE to sqliteHeader
        val icon = "icons/Tun.png" to byteArrayOf(1)
        assertEquals(ThrBackup.Parts(true, true, true, true, true), read(container(2, all, db, icon)).first.parts)
        // v1, or v2 without "parts": the three DB parts when there is a database, never OTP
        assertEquals(ThrBackup.Parts(true, true, true, false, true), read(container(1, all, db, icon)).first.parts)
        assertEquals(ThrBackup.Parts(true, true, true, false, false), read(container(2, "{}", db)).first.parts)
        assertEquals(ThrBackup.Parts(true, true, true, false, false), read(container(2, "not json", db)).first.parts)
        assertEquals(ThrBackup.Parts(true, true, true, false, false), read(container(2, null, db)).first.parts)
        // QJsonValue::toBool: only JSON true
        val loose = "{\"parts\":{\"profiles\":1,\"routes\":\"true\",\"settings\":true}}"
        assertEquals(ThrBackup.Parts(settings = true), read(container(2, loose, db)).first.parts)
        assertEquals(ThrBackup.Parts(), read(container(2, "{\"parts\":[1]}", db)).first.parts)
        // flags need their files
        assertEquals(ThrBackup.Parts(icons = true), read(container(2, all, icon)).first.parts)
        assertFalse(read(container(2, all, db)).first.parts.icons)
    }

    @Test
    fun readsLikeQt() {
        val meta = "{\"created_at\":\"Wed Sep 24 03:43:00 2026\",\"platform\":\"winnt\"}"
        // a null value is an empty entry; the last of two equal keys wins; trailing bytes are ignored
        val bytes = container(
            2, meta,
            ThrBackup.DATABASE to byteArrayOf(1, 2, 3),
            "icons/a.png" to null,
            ThrBackup.DATABASE to sqliteHeader,
        ) + "trailing".toByteArray()
        val (contents, database) = read(bytes)
        assertEquals("Wed Sep 24 03:43:00 2026", contents.createdAt)
        assertEquals("winnt", contents.platform)
        assertEquals(setOf(ThrBackup.DATABASE, "icons/a.png"), contents.keys)
        assertArrayEquals(sqliteHeader, database)
        assertEquals(JsonInput.parseObject(meta), contents.meta)
    }

    @Test
    fun iconEntriesAreSkipped() {
        // older Android builds added their icon pack, the desktop adds its tray icons: only the database parts restore
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val oldAndroid = container(
            2,
            "{\"android\":{\"app_version\":\"1.7.0\",\"icons\":true},\"backup_version\":2,\"parts\":{\"icons\":false," +
                "\"otp\":false,\"profiles\":true,\"routes\":true,\"settings\":true},\"platform\":\"android\"}",
            "android/custom_icon/icon.png" to png,
            "android/custom_icon/tile.png" to png,
            ThrBackup.DATABASE to sqliteHeader,
        )
        val desktop = container(
            2,
            "{\"parts\":{\"icons\":true,\"otp\":false,\"profiles\":true,\"routes\":true,\"settings\":true},\"platform\":\"winnt\"}",
            ThrBackup.DATABASE to sqliteHeader,
            "icons/Proxy.png" to png,
            "icons/Tun.png" to png,
        )
        val desktopV1 = container(1, null, ThrBackup.DATABASE to sqliteHeader, "icons/Tun.png" to png)
        val databaseParts = BackupRestore.Choice(profiles = true, routes = true, settings = true)
        for ((bytes, icons) in listOf(
            oldAndroid to setOf("android/custom_icon/icon.png", "android/custom_icon/tile.png"),
            desktop to setOf("icons/Proxy.png", "icons/Tun.png"),
            desktopV1 to setOf("icons/Tun.png"),
        )) {
            val (contents, database) = read(bytes)
            assertEquals(icons + ThrBackup.DATABASE, contents.keys)
            assertArrayEquals(sqliteHeader, database)
            assertEquals(databaseParts, BackupRestore.available(contents))
        }
        // an old icons-only backup has nothing left to restore
        val iconsOnly = container(
            2,
            "{\"android\":{\"icons\":true},\"parts\":{\"icons\":false,\"otp\":false,\"profiles\":false,\"routes\":false," +
                "\"settings\":false},\"platform\":\"android\"}",
            "android/custom_icon/icon.png" to png,
            "android/custom_icon/tile.png" to png,
        )
        val (contents, database) = read(iconsOnly)
        assertNull(database)
        assertFalse(BackupRestore.available(contents).any())
    }

    @Test
    fun refusesWhatQtRefuses() {
        val db = ThrBackup.DATABASE to sqliteHeader
        expectError(ThrBackup.Kind.NOT_BACKUP, "THRX".toByteArray() + u32(2))
        expectError(ThrBackup.Kind.NOT_BACKUP, "TH".toByteArray())
        expectError(ThrBackup.Kind.UNSUPPORTED_VERSION, container(3, "{}", db))
        expectError(ThrBackup.Kind.UNSUPPORTED_VERSION, container(0, "{}", db))
        expectError(ThrBackup.Kind.CORRUPT, "THRN".toByteArray() + u32(2) + u32(3) + "abc".toByteArray() + u32(0))
        expectError(ThrBackup.Kind.CORRUPT, container(2, "{}", null to byteArrayOf(1)))
        expectError(ThrBackup.Kind.CORRUPT, container(2, "{}", db).let { it.copyOf(it.size - 3) })
        expectError(ThrBackup.Kind.CORRUPT, "THRN".toByteArray() + u32(2) + qstring("{}") + u32(1) + u32(0xFFFFFFFEL))
        assertTrue(ThrBackup.hasMagic(ByteArrayInputStream(container(2, "{}"))))
        assertFalse(ThrBackup.hasMagic(ByteArrayInputStream("{\"version\":3}".toByteArray())))
    }
}
