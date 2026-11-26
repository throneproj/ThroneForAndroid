package io.nekohasekai.sagernet

import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.PreferenceProxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TestSettingsContractTest {

    @Test
    fun freshInstallDefaultsMatchThroneBaseline() {
        assertEquals("http://cp.cloudflare.com/", SettingsRegistry.TEST_URL.default)
        assertEquals(10, SettingsRegistry.TEST_CONCURRENT.default)
        assertEquals(3000, SettingsRegistry.URL_TEST_TIMEOUT_MS.default)
        assertEquals(SpeedTestSettings.FULL, SettingsRegistry.SPEED_TEST_MODE.default)
        assertEquals(5000, SettingsRegistry.SPEED_TEST_TIMEOUT_MS.default)
        assertEquals("http://cachefly.cachefly.net/5mb.test", SettingsRegistry.SIMPLE_DL_URL.default)
    }

    @Test
    fun persistedUpgradeValuesWinOverNewDefaults() {
        val values = mutableMapOf<String, Any>(
            SettingsRegistry.TEST_URL.key to "http://www.gstatic.com/generate_204",
            SettingsRegistry.TEST_CONCURRENT.key to 5,
        )
        val url = proxy(values, SettingsRegistry.TEST_URL.key, SettingsRegistry.TEST_URL.default)
        val concurrent = proxy(values, SettingsRegistry.TEST_CONCURRENT.key, SettingsRegistry.TEST_CONCURRENT.default)

        assertEquals("http://www.gstatic.com/generate_204", url.getter(url.name, url.defaultValue()))
        assertEquals(5, concurrent.getter(concurrent.name, concurrent.defaultValue()))

        values[SettingsRegistry.TEST_URL.key] = "https://example.com/custom-latency"
        values[SettingsRegistry.TEST_CONCURRENT.key] = 3
        assertEquals("https://example.com/custom-latency", url.getter(url.name, url.defaultValue()))
        assertEquals(3, concurrent.getter(concurrent.name, concurrent.defaultValue()))
    }

    @Test
    fun desktopBackupOnlyAcceptsValidTestValues() {
        assertFalse(SettingsRegistry.SPEED_TEST_MODE.accepts("invalid"))
        assertFalse(SettingsRegistry.SPEED_TEST_MODE.accepts("5"))
        assertTrue(SettingsRegistry.SPEED_TEST_MODE.accepts(SpeedTestSettings.SIMPLE_DOWNLOAD.toString()))
        // The removed "Only country" mode reads as the default, download + upload.
        assertFalse(SettingsRegistry.SPEED_TEST_MODE.accepts("4"))
        assertFalse(SettingsRegistry.SPEED_TEST_TIMEOUT_MS.accepts("0"))
        assertTrue(SettingsRegistry.SPEED_TEST_TIMEOUT_MS.accepts("5000"))
        assertFalse(SettingsRegistry.SIMPLE_DL_URL.accepts("file:///tmp/test.bin"))
        assertTrue(SettingsRegistry.SIMPLE_DL_URL.accepts("https://example.com/imported.bin"))

        assertEquals(SpeedTestSettings.MODE_SIMPLE_DOWNLOAD, SpeedTestSettings.modeName(SpeedTestSettings.SIMPLE_DOWNLOAD))
        assertEquals(SpeedTestSettings.MODE_DOWNLOAD_UPLOAD, SpeedTestSettings.modeName(4))
        assertEquals(SpeedTestSettings.MODE_DOWNLOAD_UPLOAD, SpeedTestSettings.modeName(42))
    }

    private fun <T : Any> proxy(
        values: MutableMap<String, Any>,
        key: String,
        default: T,
    ) = PreferenceProxy(
        name = key,
        defaultValue = { default },
        getter = { name, fallback -> values[name] as? T ?: fallback },
        setter = { name, value -> values[name] = value },
    )
}
