package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.ServiceTestHandler

/**
 * Service availability checks (runServiceChecks / runServiceProbe, TestRunner.cpp:387-455, 661-758): batches of
 * [SERVICE_BATCH_SIZE] through the core's embedded definitions; each result is stored as the desktop's
 * `profiles.service_checks` document.
 */
internal class ServiceTestRunner(session: TestSession) : LatencySweep(session) {

    override val batchSize: Int get() = SERVICE_BATCH_SIZE

    /** The definitions' categories in their order (ServiceChecks::Prepare), read once per session. */
    @Volatile
    private var categories: List<Category> = emptyList()

    suspend fun runChecks(profiles: List<ProxyEntity>) {
        if (profiles.size > TestSpec.SERVICE_MAX_PROFILES) {
            val reason = "A service check can test up to ${TestSpec.SERVICE_MAX_PROFILES} profiles at a time"
            Logs.w(reason)
            report(profiles.map { ProbeResult(it.id, error = reason, measured = false) })
            return
        }
        categories = try {
            parseCategories(Mobile.parseServiceChecks(""))
        } catch (e: Exception) {
            Logs.w("Service check failed: ${e.readableMessage}")
            report(profiles.map { ProbeResult(it.id, error = e.readableMessage, measured = false) })
            return
        }
        run(profiles)
    }

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        val request = probe.request().apply {
            timeoutMs = SERVICE_REQUEST_TIMEOUT_MS
            maxConcurrency = SERVICE_CONCURRENCY
        }
        val known = categories
        Mobile.startServiceTest(CoreRuntime.platform, request, object : ServiceTestHandler {
            override fun onResult(tag: String?, resultJSON: String?, err: String?) = guarded {
                val id = probe.profileOf(tag) ?: return@guarded
                val error = err.orEmpty()
                // An aborted check says nothing about the profile, so its previous one stays.
                val document = if (ProxyEntity.isTestAborted(error)) {
                    ""
                } else {
                    resultDocument(resultJSON.orEmpty(), error, known, System.currentTimeMillis() / 1000)
                }
                emit(ProbeResult(id, error = error, service = document))
            }

            override fun onDone() = guarded(done)
        })
    }

    override fun report(results: List<ProbeResult>) = session.reportService(results)

    /** Nothing is stored for a candidate the generator rejected: it was never checked. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason, measured = false)

    class Category(val id: String, val name: String, val icon: String)

    companion object {
        /** kTestRunnerServiceBatchSize / Concurrency / RequestTimeoutMs (TestRunner.cpp:88-90). */
        const val SERVICE_BATCH_SIZE = 25
        const val SERVICE_CONCURRENCY = 4
        const val SERVICE_REQUEST_TIMEOUT_MS = 10_000

        /** The categories of a ParseServiceChecks description ({version, revision, categories, checks}). */
        fun parseCategories(description: String): List<Category> =
            JsonInput.parseObject(description).array("categories").mapNotNull { value ->
                (value as? JsonObject)?.let { Category(it.string("id"), it.string("name"), it.string("icon")) }
            }

        /**
         * ServiceChecks::ResultJson (ServiceChecks.cpp:155-206): the core's {checks, ip_info} under the desktop's column
         * keys, with the definition categories the checks use in definition order, then any they name that the
         * definitions do not list.
         */
        fun resultDocument(resultJson: String, error: String, categories: List<Category>, checkedAt: Long): String {
            val root = jsonObjectOf("at" to checkedAt, "error" to error)
            if (error.isNotEmpty()) return root.toCompact()
            val result = JsonInput.parseObject(resultJson)
            val checks = JsonArray()
            val used = LinkedHashSet<String>()
            for (value in result.array("checks")) {
                val check = value as? JsonObject ?: continue
                val category = check.string("category")
                used.add(category)
                checks.add(
                    jsonObjectOf(
                        "id" to check.string("id"),
                        "name" to check.string("name"),
                        "cat" to category,
                        "status" to check.string("status"),
                        "region" to check.string("region"),
                        "note" to check.string("note"),
                    )
                )
            }
            val cats = JsonArray()
            for (category in categories) {
                if (!used.remove(category.id)) continue
                cats.add(jsonObjectOf("id" to category.id, "name" to category.name, "icon" to category.icon))
            }
            for (id in used) cats.add(jsonObjectOf("id" to id, "name" to id, "icon" to ""))
            val ip = JsonArray()
            for (value in result.array("ip_info")) {
                val info = value as? JsonObject ?: continue
                val flags = JsonArray()
                for (flag in info.array("flags").strings()) flags.add(flag)
                ip.add(
                    jsonObjectOf(
                        "provider" to info.string("provider"),
                        "ip" to info.string("ip"),
                        "cc" to info.string("country_code"),
                        "asn" to info.string("asn"),
                        "org" to info.string("org"),
                        "type" to info.string("type"),
                        "risk" to info.int("risk_score"),
                        "flags" to flags,
                        "error" to info.string("error"),
                    )
                )
            }
            root["cats"] = cats
            root["checks"] = checks
            root["ip"] = ip
            return root.toCompact()
        }
    }
}
