package io.nekohasekai.sagernet.bg.scan

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.OutboundFactory
import io.nekohasekai.sagernet.outbound.config.ConfigGenerator
import io.nekohasekai.sagernet.outbound.config.GeneratedConfig
import io.nekohasekai.sagernet.outbound.config.ProfileProvider
import io.nekohasekai.sagernet.outbound.config.TestCandidate
import io.nekohasekai.sagernet.scan.EndpointSources

/** Configs::ScanTestTarget: [port] 0 keeps the profile's port. */
internal class ScanTestTarget(@JvmField val address: String, @JvmField val port: Int)

/** Configs::ScanTestBuild: [tagToTarget] maps each outbound tag to its index in the targets. */
internal class ScanTestBuild(
    @JvmField val error: String,
    @JvmField val build: GeneratedConfig? = null,
    @JvmField val tagToTarget: Map<String, Int> = emptyMap(),
    @JvmField val unsupported: List<Int> = emptyList(),
)

/** BuildScanTestConfig and ValidateScanBase (generate_test.cpp) on Android's generator. Blocking; :bg only. */
internal object ScanTestConfigs {

    private const val SYNTHETIC_ID_BASE = -1_000_000L

    // scanBaseBlocker / scanCloneBlocker.
    private fun blocker(base: ProxyEntity?): String = when (EndpointSources.serverlessReason(base)) {
        EndpointSources.Serverless.Missing -> app.getString(R.string.endpoint_blocker_missing)
        EndpointSources.Serverless.NoServer -> app.getString(R.string.scan_blocker_no_server, base?.type.orEmpty())
        EndpointSources.Serverless.Realm -> app.getString(R.string.scan_blocker_realm)
        EndpointSources.Serverless.None -> ""
    }

    private fun deepClone(base: ProxyEntity): Outbound? {
        val clone = OutboundFactory.newByType(base.type)
        if (clone.invalid || !clone.parseFromJson(base.outbound.exportToJson().copy())) return null
        return clone
    }

    /** ValidateScanBase: IsValid on a throwaway copy, so the stored profile's results are never touched; "" = valid. */
    fun validateBase(base: ProxyEntity?): String {
        blocker(base).let { if (it.isNotEmpty()) return it }
        val clone = deepClone(base!!) ?: return app.getString(R.string.scan_base_copy_failed)
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        val ctx = SettingsMapper.buildContext(dns).copy(buildingTestConfig = true)
        val reason = EndpointSources.scope {
            val built = if (clone.isXray()) clone.buildXray(ctx) else clone.buildChecked(ctx)
            if (!built.ok) built.error
            else SettingsMapper.memberCheck()?.rejected(listOf(SYNTHETIC_ID_BASE to clone), ctx)?.values?.firstOrNull().orEmpty()
        }
        if (reason.isEmpty()) return ""
        Logs.w("Invalid ent ${clone.displayTypeAndName()}: $reason")
        return app.getString(R.string.scan_error_profile_invalid)
    }

    /**
     * BuildScanTestConfig: one probe box with [base] cloned onto each target (ids from -1000000 down), without the
     * group's landing / front proxy. A target the profile cannot be pointed at is [ScanTestBuild.unsupported].
     */
    fun build(base: ProxyEntity?, targets: List<ScanTestTarget>): ScanTestBuild = EndpointSources.scope {
        val blocker = blocker(base)
        if (blocker.isNotEmpty()) return@scope ScanTestBuild(blocker)
        val overrides = HashMap<Long, Outbound>()
        val synthToTarget = HashMap<Long, Int>()
        val candidates = ArrayList<TestCandidate>()
        val unsupported = ArrayList<Int>()
        var nextId = SYNTHETIC_ID_BASE
        for ((index, target) in targets.withIndex()) {
            val item = EndpointSources.cloneWithServer(base!!.type, base.outbound, target.address, target.port)
            if (item == null) {
                unsupported.add(index)
                continue
            }
            val id = nextId--
            overrides[id] = item
            synthToTarget[id] = index
            candidates.add(TestCandidate(id))
        }
        if (candidates.isEmpty()) {
            val error = if (targets.isNotEmpty()) app.getString(R.string.scan_error_cannot_point) else ""
            return@scope ScanTestBuild(error, unsupported = unsupported)
        }
        val profiles = ProfileProvider { id ->
            overrides[id] ?: if (id > 0) SagerDatabase.proxyDao.getById(id)?.let { EndpointSources.apply(it) } else null
        }
        val dns = RouteManager.customDns(DataStore.currentRouteId)
        val build = ConfigGenerator(profiles, SettingsMapper.generatorSettings(dns), SettingsMapper.buildContext(dns))
            .buildScanTest(candidates)
        if (!build.ok) return@scope ScanTestBuild(build.error.orEmpty(), unsupported = unsupported)
        // Unsupported or vanished candidates are still skipped rather than failing the box.
        if (build.outboundTags.isEmpty() && build.skipped.isNotEmpty()) {
            return@scope ScanTestBuild(build.skipped.values.first(), unsupported = unsupported)
        }
        val tagToTarget = LinkedHashMap<String, Int>()
        for ((tag, id) in build.tagToProfileId) tagToTarget[tag] = synthToTarget[id] ?: -1
        ScanTestBuild("", build, tagToTarget, unsupported)
    }
}
