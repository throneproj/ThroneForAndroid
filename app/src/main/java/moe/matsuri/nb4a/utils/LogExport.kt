package moe.matsuri.nb4a.utils

import android.content.Context
import android.os.Build
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.update.UpdateChecker
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Share logs" / "Save logs…" and the crash report: one text file with an allowlisted header (after the desktop's
 * diagnostics client.json), core.log, the previous service session and the app's logcat, optionally redacted.
 */
object LogExport {

    private const val LOGCAT_LINES = 2000
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    // Non-secret settings only; values such as DNS URLs, inbound credentials or API secrets never leave the device.
    private val SETTINGS = listOf(
        SettingsRegistry.VPN_MTU, SettingsRegistry.VPN_IPV6,
        SettingsRegistry.ENABLE_TUN_ROUTING, SettingsRegistry.DISABLE_PRIVATE_RANGE_BYPASS,
        SettingsRegistry.DOMAIN_STRATEGY, SettingsRegistry.OUTBOUND_DOMAIN_STRATEGY, SettingsRegistry.FAKEDNS,
        SettingsRegistry.ENABLE_DNS_ROUTING, SettingsRegistry.USE_DNS_OBJECT, SettingsRegistry.ENABLE_STATS,
        SettingsRegistry.LOG_LEVEL, SettingsRegistry.XRAY_LOG_LEVEL, SettingsRegistry.MUX_DEFAULT_ON,
        SettingsRegistry.XRAY_MUX_DEFAULT_ON, SettingsRegistry.FRAGMENT_DEFAULT_ON,
        SettingsRegistry.FRAGMENT_IMPLEMENTATION, SettingsRegistry.TLS_TRICKS_DEFAULT_ON,
        SettingsRegistry.UTLS_FINGERPRINT, SettingsRegistry.NET_USE_PROXY, SettingsRegistry.ENABLE_WARP,
        SettingsRegistry.CURRENT_ROUTE_ID,
    ).map { it.key } + listOf(Key.PROXY_APPS, Key.BYPASS_MODE, Key.METERED_NETWORK)

    fun fileName(): String = "throne-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"

    /** Writes cacheDir/log/<[fileName]> (shared through the FileProvider). Blocking: call it off the main thread. */
    fun build(context: Context, redact: Boolean, hideDestinations: Boolean): File {
        val dir = File(context.cacheDir, "log").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.forEach { if (now - it.lastModified() > MAX_AGE_MS) it.delete() }
        val file = File(dir, fileName())
        val redactor = if (redact) LogRedactor(hideDestinations) else null
        file.bufferedWriter().use { out ->
            out.write(header(context, redact, hideDestinations))
            section(out, "core.log", String(CoreLog.read(0)), redactor)
            if (CoreLog.previousFile.exists()) {
                section(out, "core.log.prev (previous service session)", String(CoreLog.readPrevious()), redactor)
            }
            section(out, "logcat (this app, last $LOGCAT_LINES lines)", logcat(), redactor)
        }
        return file
    }

    private fun section(out: Writer, title: String, text: String, redactor: LogRedactor?) {
        out.write("\n===== $title =====\n")
        text.lineSequence().forEach { line ->
            out.write(redactor?.redact(line) ?: line)
            out.write("\n")
        }
    }

    private fun header(context: Context, redact: Boolean, hideDestinations: Boolean): String {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val store = DataStore.configurationStore
        val settings = SETTINGS.joinToString(", ") { key ->
            "$key=${runCatching { store.getString(key) }.getOrNull() ?: SettingsRegistry.defaultOf(key) ?: ""}"
        }
        val profileType = runCatching { ProfileManager.getProfile(DataStore.selectedProxy)?.type }.getOrNull() ?: "none"
        val buildType = if (BuildConfig.DEBUG) "debug" else "release"
        val redaction = when {
            !redact -> "off"
            hideDestinations -> "on, destinations hidden (best effort: review before sharing)"
            else -> "on (best effort: review before sharing)"
        }
        return buildString {
            appendLine("===== Throne for Android log export =====")
            appendLine("exported  : ${utc.format(Date())}")
            appendLine(
                "app       : ${SagerNet.appVersionNameForDisplay} (versionCode ${BuildConfig.VERSION_CODE}), " +
                        "flavor ${BuildConfig.FLAVOR}, $buildType, package ${BuildConfig.APPLICATION_ID}"
            )
            appendLine("core      : ThroneCore ${BuildConfig.THRONE_CORE_REF} (versions: the \"core:\" line of core.log)")
            appendLine(
                "android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), " +
                        "security patch ${Build.VERSION.SECURITY_PATCH}"
            )
            appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(
                "abi       : installed ${UpdateChecker.installedAbi(context) ?: "unknown"}; " +
                        "supported ${Build.SUPPORTED_ABIS.joinToString(", ")}"
            )
            appendLine(
                "service   : ${DataStore.serviceState.name.lowercase()}, mode ${DataStore.serviceMode}, " +
                        "profile type $profileType"
            )
            appendLine("settings  : $settings")
            appendLine("redaction : $redaction")
        }
    }

    private fun logcat(): String = try {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.destroy() }
    } catch (e: Exception) {
        "logcat failed: ${e.stackTraceToString()}"
    }
}
