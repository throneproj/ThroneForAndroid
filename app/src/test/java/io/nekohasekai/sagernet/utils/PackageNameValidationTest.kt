package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PackageNameValidationTest {

    private val regex = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    private fun isValidPackageName(name: String): Boolean =
        name == "android" || regex.matches(name)

    @Test
    fun testValidPackageNames() {
        val valid = listOf(
            "android",
            "com.android.chrome",
            "org.telegram.messenger",
            "io.nekohasekai.sagernet",
            "a.b.c",
            "com.example.app_123",
            "org.throneproj.mobile",
            "ru.yandex.searchplugin",
            "com.google.android.gms",
        )
        for (pkg in valid) {
            assertTrue("Expected '$pkg' to be valid", isValidPackageName(pkg))
        }
    }

    @Test
    fun testInvalidPackageNamesRejectUrlsAndGarbage() {
        val invalid = listOf(
            "https://www.gstatic.com/generate_204",
            "http://localhost:8080",
            "https://openspeedtest.com/",
            "content://com.android.providers.media/documents",
            "throne://route",
            "foo:bar",
            "http",
            "https",
            "true",
            "false",
            "unknown",
            "singleword",
            "com.example-app", // hyphen not allowed in Android package names
            "com..app",
            ".com.app",
            "com.app.",
            "123.com",
            "com.123app",
            "",
            "   ",
            "com.example app",
            "com.example/app",
        )
        for (pkg in invalid) {
            assertFalse("Expected '$pkg' to be invalid", isValidPackageName(pkg))
        }
    }

    @Test
    fun testClipboardImportParsing() {
        fun parseClipboard(text: String): Pair<Boolean?, List<String>>? {
            val lines = text.lines().take(2000).map { it.trim() }.filter { it.isNotEmpty() }
            val bypass = lines.firstOrNull()?.takeIf { it == "true" || it == "false" }?.toBoolean()
            val rawPackages = if (bypass != null) lines.drop(1) else lines
            val packages = rawPackages.filter { it != "unknown" && isValidPackageName(it) }
            if ((rawPackages.isNotEmpty() && packages.isEmpty()) || (bypass == null && packages.isEmpty())) {
                return null // Error
            }
            return bypass to packages.distinct()
        }

        // 1. Raw URL in clipboard -> must fail, do not import
        assertEquals(null, parseClipboard("https://www.gstatic.com/generate_204"))

        // 2. Bypass flag with URL only in clipboard -> must fail, do not wipe configuration
        assertEquals(null, parseClipboard("true\nhttps://www.gstatic.com/generate_204"))

        // 3. Empty clipboard -> must fail
        assertEquals(null, parseClipboard(""))
        assertEquals(null, parseClipboard("   \n\n  "))

        // 4. Valid clipboard with bypass true
        val result1 = parseClipboard("true\ncom.android.chrome\norg.telegram.messenger")
        assertEquals(true, result1?.first)
        assertEquals(listOf("com.android.chrome", "org.telegram.messenger"), result1?.second)

        // 5. Valid clipboard without bypass flag
        val result2 = parseClipboard("com.android.chrome\ncom.google.android.youtube")
        assertEquals(null, result2?.first)
        assertEquals(listOf("com.android.chrome", "com.google.android.youtube"), result2?.second)

        // 6. Mixed clipboard with URLs, comments, unknown token, and duplicates
        val mixedText = """
            com.android.chrome
            https://example.com/test
            unknown
            org.telegram.messenger
            com.android.chrome
            http://localhost:8080/
        """.trimIndent()
        val result3 = parseClipboard(mixedText)
        assertEquals(null, result3?.first)
        assertEquals(listOf("com.android.chrome", "org.telegram.messenger"), result3?.second)

        // 7. System "android" framework package is supported
        val result4 = parseClipboard("false\nandroid\ncom.android.vending")
        assertEquals(false, result4?.first)
        assertEquals(listOf("android", "com.android.vending"), result4?.second)
    }

    @Test
    fun testSelfHealingDirtyDatabasePurge() {
        val dirtyIndividual = """
            com.android.chrome
            https://www.gstatic.com/generate_204
            org.telegram.messenger
            http://speedtest.net
            android
            bad:package:name
        """.trimIndent()

        val validLines = dirtyIndividual.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && isValidPackageName(it) }
            .distinct()
            .toList()

        assertEquals(
            listOf("com.android.chrome", "org.telegram.messenger", "android"),
            validLines,
        )
    }
}
