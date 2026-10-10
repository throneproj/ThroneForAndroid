package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.throneproj.mobile.Instance
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.DeflaterOutputStream

/**
 * #65 (Android-only, the desktop has no equivalent): sing-box fetches every remote rule-set without a cached copy
 * while the router starts, and one it cannot fetch fails the whole start (route/rule/rule_set_remote.go:134-138).
 * Once the user agrees, the next start goes without waiting for them (GeneratorSettings.deferRuleSets): uncached
 * sets begin empty and download through `proxy` once the box is up, and the cache file keeps them for later starts.
 */
object DeferredRuleSets {

    /** StartContext's E.Cause(err, "initial rule-set: ", tag). */
    private const val START_FAILURE = "initial rule-set: "

    /** How long the consent waits for the start it was given for (VPN consent, a Wi-Fi prompt); a stale one lapses. */
    private const val CONSENT_WINDOW_MS = 5 * 60_000L

    /**
     * The core's updater fetches a never-updated set as soon as the box is up, but once: a failure then waits out the
     * 24 h update_interval (route/rule/rule_set_updater.go:45-66). Every set is fetched again after each of these
     * pauses until one round succeeds; a set the updater got meanwhile only costs a conditional request.
     */
    private val RETRY_PAUSES_MS = longArrayOf(15_000L, 60_000L, 300_000L)

    /**
     * A deferred start of this service run has not got all its sets yet: its restarts (a reload, a profile switch,
     * an auto-selector rebuild) do not wait for them either, else they would fail on the same set.
     */
    @Volatile
    private var downloading = false

    fun isStartFailure(message: String?): Boolean = message?.contains(START_FAILURE) == true

    /** The failed set's tag and the cause, from the core's start error. */
    fun describe(message: String): String = message.substringAfter(START_FAILURE)

    /** Spends the consent: whether this start goes without waiting for the rule-sets. */
    fun consume(): Boolean {
        val at = DataStore.startWithoutRuleSets
        if (at != 0L) DataStore.startWithoutRuleSets = 0L
        return downloading || at != 0L && System.currentTimeMillis() - at in 0..CONSENT_WINDOW_MS
    }

    /** The service stopped for good: its next start waits for the rule-sets again. */
    fun serviceStopped() {
        downloading = false
    }

    /** The empty binary set (common/srs/binary.go Read): magic, version 1, a zlib stream holding rule count 0. */
    fun writeEmptySet() {
        val bytes = ByteArrayOutputStream().also { out ->
            out.write(byteArrayOf(0x53, 0x52, 0x53, 1))
            DeflaterOutputStream(out).use { it.write(0) }
        }.toByteArray()
        val file = File(File(app.filesDir, CoreRuntime.WORKING_DIR), ConfigGenerator.EMPTY_RULE_SET_PATH)
        if (file.isFile && file.readBytes().contentEquals(bytes)) return
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun remoteTags(coreConfig: String): List<String> =
        JsonInput.parseObject(coreConfig).obj("route").array("rule_set").mapNotNull { item ->
            (item as? JsonObject)?.takeIf { it.string("type") == "remote" }?.string("tag")
        }

    /** Logs the deferred start of [box], which runs [coreConfig], and retries its downloads while it runs. */
    fun afterStart(box: Instance, coreConfig: String) {
        downloading = true
        retry(box, coreConfig)
    }

    private fun retry(box: Instance, coreConfig: String) = runOnIoDispatcher {
        val tags = remoteTags(coreConfig)
        if (tags.isEmpty()) {
            if (CoreRuntime.running?.instance === box) downloading = false
            return@runOnIoDispatcher
        }
        Logs.i(
            "rule-sets: started without waiting for ${tags.joinToString()}; those without a cached copy start " +
                "empty and download through the proxy"
        )
        for ((round, pause) in RETRY_PAUSES_MS.withIndex()) {
            delay(pause)
            if (CoreRuntime.running?.instance !== box) return@runOnIoDispatcher
            val result = try {
                box.updateRuleSets()
            } catch (e: Exception) {
                Logs.w("rule-sets: download through the proxy stopped: ${e.readableMessage}")
                return@runOnIoDispatcher
            }
            val failures = result.error.orEmpty()
            if (failures.isEmpty()) {
                if (CoreRuntime.running?.instance === box) downloading = false
                Logs.i("rule-sets: all ${result.updated} downloaded through the proxy and cached for later starts")
                return@runOnIoDispatcher
            }
            val next = if (round < RETRY_PAUSES_MS.lastIndex) "retrying" else "no more retries for this connection"
            Logs.w("rule-sets: ${result.updated} up to date, $next; failed: ${failures.replace('\n', ';')}")
        }
    }
}
