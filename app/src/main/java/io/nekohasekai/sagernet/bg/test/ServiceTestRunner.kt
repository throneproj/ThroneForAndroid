package io.nekohasekai.sagernet.bg.test

import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.autoselector.AutoSelectorProfiles
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject
import io.nekohasekai.sagernet.outbound.json.jsonObjectOf
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.ServiceTestHandler
import io.throneproj.mobile.TestRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Service availability checks (runServiceSession / runServiceProbe, TestRunner.cpp): batches of [SERVICE_BATCH_SIZE]
 * through the picked definitions ([ServiceDefinitions]) limited to the services turned on; each result is stored as
 * the desktop's `profiles.service_checks` document.
 */
internal class ServiceTestRunner(session: TestSession) : LatencySweep(session) {

    override val batchSize: Int get() = SERVICE_BATCH_SIZE

    /** Prepared once per session, like the desktop's. */
    @Volatile
    private var definitions = ServiceDefinitions.Definitions("", ServiceCatalog.EMPTY)

    @Volatile
    private var enabled: List<String> = emptyList()

    suspend fun runChecks(profiles: List<ProxyEntity>) {
        if (profiles.size > TestSpec.SERVICE_MAX_PROFILES) {
            val reason = "A service check can test up to ${TestSpec.SERVICE_MAX_PROFILES} profiles at a time"
            Logs.w(reason)
            report(profiles.map { ProbeResult(it.id, error = reason, measured = false) })
            return
        }
        if (prepare(profiles.map { it.id })) run(profiles)
    }

    /**
     * runCurrentServiceCheck (TestRunner.cpp): the running instance's `proxy` exit, checked through the live box and
     * stored on the running profile. Auto-selectors are skipped, as by every service check.
     */
    suspend fun runCurrent(running: CoreRuntime.RunningCore?) {
        if (running == null) {
            report(listOf(ProbeResult(0L, error = UrlTestRunner.ERROR_NOT_RUNNING, measured = false)))
            return
        }
        val profileId = running.profileId
        if (ProfileManager.getProfile(profileId)?.type == AutoSelectorProfiles.TYPE) {
            report(listOf(ProbeResult(profileId, error = ERROR_AUTO_SELECTOR, measured = false)))
            return
        }
        if (!prepare(listOf(profileId))) return
        val request = filled(TestRequest())
        val categories = definitions.catalog.categories
        val result = AtomicReference(ProbeResult(profileId, error = ERROR_NO_RESULT, measured = false))
        try {
            // Sessions never overlap, so only the stats bar's test can report this tag meanwhile.
            TestTags.holding(listOf(TestTags.CURRENT)) {
                session.awaitCore { done ->
                    running.instance.startServiceTest(request, Handler({ profileId }, categories, result::set, done))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = e.readableMessage
            if (!ProxyEntity.isTestAborted(error)) Logs.w("Service check failed: $error")
            result.set(ProbeResult(profileId, error = error, measured = false))
        }
        // The live instance changed under the check, so its answers may describe another exit.
        if (CoreRuntime.running?.profileId != profileId) {
            report(listOf(ProbeResult(profileId, error = ERROR_ABORTED, measured = false)))
            return
        }
        report(listOf(result.get()))
    }

    /** The definitions and the services turned on; false, with [profileIds] reported unmeasured, when none can run. */
    private suspend fun prepare(profileIds: List<Long>): Boolean {
        val prepared = withContext(Dispatchers.IO) { ServiceDefinitions.prepare() }
        if (prepared.error.isNotEmpty()) {
            Logs.w("Service check failed: ${prepared.error}")
            report(profileIds.map { ProbeResult(it, error = prepared.error, measured = false) })
            return false
        }
        val only = prepared.catalog.enabled(SettingsRegistry.SERVICE_CHECK_DISABLED.read(DataStore.configurationStore))
        // An empty list would run every check.
        if (only.isEmpty()) {
            Logs.w("No services are selected for the service check. Choose them in Settings › Testing.")
            report(profileIds.map { ProbeResult(it, error = ERROR_NO_SERVICES, measured = false) })
            return false
        }
        definitions = prepared
        enabled = only
        return true
    }

    private fun filled(request: TestRequest): TestRequest = request.apply {
        timeoutMs = SERVICE_REQUEST_TIMEOUT_MS
        maxConcurrency = SERVICE_CONCURRENCY
        serviceChecks = definitions.text
        for (id in enabled) addServiceCheck(id)
    }

    override fun start(probe: TestProbe, batch: List<ProxyEntity>, emit: (ProbeResult) -> Unit, done: () -> Unit) {
        val request = filled(probe.request())
        val handler = Handler(probe::profileOf, definitions.catalog.categories, emit, done)
        Mobile.startServiceTest(CoreRuntime.platform, request, handler)
    }

    override fun report(results: List<ProbeResult>) = session.reportService(results)

    /** Nothing is stored for a candidate the generator rejected: it was never checked. */
    override fun invalid(profileId: Long, reason: String) = ProbeResult(profileId, error = reason, measured = false)

    /** Each result as the stored document; an aborted check says nothing about the profile, so its previous one stays. */
    private class Handler(
        private val profileOf: (String?) -> Long?,
        private val categories: List<ServiceCatalog.Category>,
        private val emit: (ProbeResult) -> Unit,
        private val done: () -> Unit,
    ) : ServiceTestHandler {

        override fun onResult(tag: String?, resultJSON: String?, err: String?) = guarded {
            val id = profileOf(tag) ?: return@guarded
            val error = err.orEmpty()
            val document = if (ProxyEntity.isTestAborted(error)) {
                ""
            } else {
                resultDocument(resultJSON.orEmpty(), error, categories, System.currentTimeMillis() / 1000)
            }
            emit(ProbeResult(id, error = error, service = document))
        }

        override fun onDone() = guarded(done)
    }

    companion object {
        /** kTestRunnerServiceBatchSize / Concurrency / RequestTimeoutMs (TestRunner.cpp). */
        const val SERVICE_BATCH_SIZE = 25
        const val SERVICE_CONCURRENCY = 4
        const val SERVICE_REQUEST_TIMEOUT_MS = 10_000

        const val ERROR_NO_SERVICES = "no services selected"
        const val ERROR_AUTO_SELECTOR = "an auto-selector is not service-checked"

        /**
         * ServiceChecks::ResultJson (ServiceChecks.cpp): the core's {checks, ip_info} under the desktop's column
         * keys, with the definition categories the checks use in definition order, then any they name that the
         * definitions do not list.
         */
        fun resultDocument(
            resultJson: String,
            error: String,
            categories: List<ServiceCatalog.Category>,
            checkedAt: Long,
        ): String {
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
