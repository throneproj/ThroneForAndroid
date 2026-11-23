package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.HttpGetOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.httpGet
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.route.RuleSets
import io.throneproj.mobile.Mobile
import java.io.File
import java.io.IOException

/**
 * ServiceChecks::Prepare (ServiceChecks.cpp), :bg only: the core's embedded definitions, or the copy downloaded from
 * the routeprofiles repo when its revision is newer. The download is tried at most once a day.
 */
internal object ServiceDefinitions {

    private const val URL = "https://raw.githubusercontent.com/throneproj/routeprofiles/servicecheck/checks-v1.json"
    private const val ATTEMPT_INTERVAL_SECS = 24 * 60 * 60L
    private const val MAX_BYTES = 256 * 1024L
    private const val VERSION = 1

    private const val CACHE_FILE = "service_checks.json"

    /** Unix seconds of the last download attempt, failed or not. */
    private const val ATTEMPT_FILE = "service_checks.attempt"

    /**
     * [text] is TestRequest.serviceChecks, empty for the embedded copy; [list] is its description, what
     * [ServiceCatalog.saved] reads. With an [error] nothing can run.
     */
    class Definitions(val text: String, val catalog: ServiceCatalog, val error: String = "", val list: String = "")

    /** Blocking. Two refreshes at once would race on the cache and its attempt stamp. */
    @Synchronized
    fun prepare(): Definitions {
        val embedded = try {
            describe("")
        } catch (e: Exception) {
            return Definitions("", ServiceCatalog.EMPTY, e.readableMessage)
        }
        var cached = read(CACHE_FILE)
        var parsed = cached?.let(::usable)
        val fresh = refresh()
        if (fresh != null) {
            cached = fresh.first
            parsed = fresh.second
        }
        val picked = parsed?.takeIf { it.integer("revision") > embedded.integer("revision") }
        val description = picked ?: embedded
        val list = description.toCompact()
        ServiceCatalog.save(list)
        return Definitions(if (picked != null) cached.orEmpty() else "", ServiceCatalog.parse(description), list = list)
    }

    private fun describe(document: String): JsonObject = JsonInput.parseObject(Mobile.parseServiceChecks(document))

    private fun usable(document: String): JsonObject? = try {
        describe(document).takeIf { it.int("version") == VERSION }
    } catch (e: Exception) {
        null
    }

    /** A newer valid download, stored, with its description; null when none is due or it failed. */
    private fun refresh(): Pair<String, JsonObject>? {
        val now = System.currentTimeMillis() / 1000
        val attempted = read(ATTEMPT_FILE)?.trim()?.toLongOrNull() ?: 0L
        // A stamp in the future means the clock went back, and waiting it out could take years.
        if (attempted in 1..now && now - attempted < ATTEMPT_INTERVAL_SECS) return null
        write(ATTEMPT_FILE, now.toString())

        val response = httpGet(RuleSets.mirrorLink(URL, DataStore.rulesetMirror), HttpGetOptions(maxBytes = MAX_BYTES))
        if (!response.ok) {
            Logs.w("[Service check] Definitions download failed: ${response.error}")
            return null
        }
        val text = String(response.data, Charsets.UTF_8)
        val description = try {
            describe(text)
        } catch (e: Exception) {
            Logs.w("[Service check] Downloaded definitions rejected: ${e.readableMessage}")
            return null
        }
        if (description.int("version") != VERSION) {
            Logs.w("[Service check] Downloaded definitions rejected: unsupported version ${description.int("version")}")
            return null
        }
        return if (write(CACHE_FILE, text)) text to description else null
    }

    private fun file(name: String) = File(app.noBackupFilesDir, name)

    private fun read(name: String): String? = try {
        file(name).takeIf { it.isFile }?.takeIf { it.length() <= MAX_BYTES }?.readText()
    } catch (e: IOException) {
        null
    }

    private fun write(name: String, text: String): Boolean {
        val target = file(name)
        val tmp = File(target.parentFile, "$name.tmp")
        return try {
            tmp.writeText(text)
            tmp.renameTo(target) || throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            Logs.w("[Service check] Cannot write $name: ${e.readableMessage}")
            false
        }
    }
}
