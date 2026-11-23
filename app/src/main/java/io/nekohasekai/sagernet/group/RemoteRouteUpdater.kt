package io.nekohasekai.sagernet.group

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteCoroutineWorker
import androidx.work.multiprocess.RemoteListenableWorker
import androidx.work.multiprocess.RemoteWorkManager
import androidx.work.multiprocess.RemoteWorkerService
import com.google.common.util.concurrent.ListenableFuture
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.RouteRepo
import io.nekohasekai.sagernet.database.RouteRuleEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.fetchText
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RouteRule
import io.nekohasekai.sagernet.route.RouteShare
import io.nekohasekai.sagernet.route.RuleSets
import io.nekohasekai.sagernet.route.RuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Remote route profiles (RouteUpdater.cpp) and their periodic auto update (route_auto_update). */
object RemoteRouteUpdater {

    /** route_auto_update is sign-encoded minutes and only takes effect from 30 (mainwindow_setup.cpp:1095-1096). */
    const val MIN_INTERVAL_MINUTES = 30

    private const val WORK_NAME = "RemoteRouteUpdater"

    /**
     * RouteUpdate::UpdateProfile (RouteUpdater.cpp) without saving: fetches [p]'s URL through the rule-set mirror and
     * replaces its remote rules and default outbound, keeping its local rules and a non-empty local name. [p] is left
     * unchanged on error, which is returned.
     */
    fun fetchInto(p: RouteProfile, warnings: MutableList<String>? = null): String? {
        if (!p.is_remote) return "not a remote routing profile"
        val url = p.remote_url.trim()
        if (url.isEmpty()) return "remote URL is empty"
        val body = try {
            fetchText(RuleSets.mirrorLink(url, DataStore.rulesetMirror)).body
        } catch (e: Exception) {
            return e.readableMessage
        }
        refreshRuleSetList()
        return applyContent(p, body, warnings, fetched = true)
    }

    private const val RULE_SET_LIST_MAX_AGE_MS = 10 * 60 * 1000L
    private val ruleSetListLock = Any()
    private var ruleSetListCheckedAt = 0L

    /**
     * The fetched rules' rule-set names are checked against srslist.h, so a stale copy would drop the rules that name
     * newly published sets. Refreshed at most once per [RULE_SET_LIST_MAX_AGE_MS] per process; a failure keeps the old copy.
     */
    private fun refreshRuleSetList() {
        synchronized(ruleSetListLock) {
            val now = System.currentTimeMillis()
            if (now - ruleSetListCheckedAt < RULE_SET_LIST_MAX_AGE_MS) return
            ruleSetListCheckedAt = now
            RouteRepo.refresh()?.let { Logs.w("routeprofiles refresh before a remote route update: $it") }
        }
    }

    /** What [fetchAndCommit] did: the fetch [error], else whether the fetched [copy] was [saved]. */
    private class Commit(val copy: RouteProfile, val error: String?, val saved: Boolean)

    /**
     * Fetches into a copy of [p], a profile as stored, and saves the copy only while the stored profile is still [p]
     * (UI_update_all_remote_routes): a profile edited or deleted during the fetch keeps that change.
     */
    private fun fetchAndCommit(p: RouteProfile, warnings: MutableList<String>): Commit {
        val copy = p.copy()
        fetchInto(copy, warnings)?.let { return Commit(copy, it, false) }
        return Commit(copy, null, RouteManager.saveIfUnchanged(p, copy))
    }

    /** [fetchInto] a copy of [p], a profile as stored, and save it; returns the error, or null. */
    fun update(p: RouteProfile): String? {
        val warnings = ArrayList<String>()
        val commit = fetchAndCommit(p, warnings)
        commit.error?.let { return it }
        if (!commit.saved) return "the routing profile was changed or deleted during the update"
        if (warnings.isNotEmpty()) Logs.w("Remote routing profile ${p.name}: ${warnings.joinToString("; ")}")
        return null
    }

    /**
     * UI_update_all_remote_routes (RouteUpdater.cpp): (updated count, "name: error" failures). A profile changed or
     * deleted during its fetch is skipped, and the next run updates it. An update that changed the running routing
     * profile asks for a reload.
     */
    fun updateAll(onlyAutoUpdate: Boolean): Pair<Int, List<String>> {
        var updated = 0
        val failures = ArrayList<String>()
        for (id in RouteManager.all().map { it.id }) {
            val p = RouteManager.get(id) ?: continue
            if (!p.is_remote || p.remote_url.isBlank()) continue
            if (onlyAutoUpdate && !p.auto_update) continue
            Logs.i("Updating remote routing profile: ${p.name}")
            val warnings = ArrayList<String>()
            val commit = fetchAndCommit(p, warnings)
            if (commit.error != null) {
                failures.add("${p.name}: ${commit.error}")
                Logs.w("Remote routing profile ${p.name} failed: ${commit.error}")
                continue
            }
            if (!commit.saved) continue
            updated++
            if (warnings.isNotEmpty()) Logs.w("Remote routing profile ${p.name}: ${warnings.joinToString("; ")}")
            if (p.id == RouteManager.runningRouteId() && routeFingerprint(commit.copy) != routeFingerprint(p)) {
                SubscriptionQueue.reloadNeeded(-1L)
            }
        }
        Logs.i("Remote routing profiles: $updated updated, ${failures.size} failed")
        return updated to failures
    }

    /**
     * handle_add_remote_routes (mainwindow_deeplink.cpp:179-219): each entry is saved at once, prefilled from the
     * routeprofiles snapshot when it has the URL so it works offline, then fetched online in the background.
     * Returns the new profile ids; current_route_id is not changed.
     */
    fun addRemote(entries: List<RouteShare.RemoteEntry>, autoUpdate: Boolean): List<Long> {
        val ids = entries.map { entry ->
            val p = RouteProfile().apply {
                name = entry.name
                is_remote = true
                remote_url = entry.url
                auto_update = autoUpdate
            }
            RouteRepo.snapshot(entry.url)?.let { applyContent(p, it, null, fetched = false) }
            RouteManager.save(p)
        }
        if (ids.isNotEmpty()) runOnIoDispatcher {
            var fetched = 0
            for (id in ids) {
                val p = RouteManager.get(id) ?: continue
                val error = update(p)
                if (error == null) fetched++ else Logs.w("Remote routing profile ${p.name} failed: $error")
            }
            Logs.i("Added remote routing profiles: $fetched of ${ids.size} fetched")
        }
        return ids
    }

    /**
     * replaceRules (RouteUpdater.cpp): [fetched]'s rules become [p]'s remote ones, [p] takes its default outbound and
     * stops being raw. Local rules (endpoint rules included) stay put; the fetched rules take the old remote slots in
     * order, extras follow the last slot (the top when there was none: remote takes precedence), and slots left over
     * close up. Rules switched off locally stay off, matched by name. [fetched] is left as it is.
     */
    internal fun replaceRules(p: RouteProfile, fetched: RouteProfile) {
        val remoteAt = ArrayList<Int>()
        val disabledNames = HashSet<String>()
        val rules = ArrayList<RouteRule>()
        p.rules.forEachIndexed { i, rule ->
            if (!rule.remote) {
                rules.add(rule)
            } else {
                remoteAt.add(i)
                if (rule.disabled) disabledNames.add(rule.name)
            }
        }
        p.is_raw = false
        p.raw_route = ""
        var next = if (remoteAt.isEmpty()) 0 else remoteAt.last() + 1
        var placed = 0
        for (rule in fetched.rules) {
            if (rule.type == RuleType.ENDPOINT_PREFERRED_BY.id) continue
            val copy = rule.copy().also {
                if (it.name in disabledNames) it.disabled = true
                it.remote = true
            }
            val at = if (placed < remoteAt.size) remoteAt[placed] else next++
            rules.add(minOf(at, rules.size), copy)
            placed++
        }
        p.rules = rules
        p.default_outbound_id = fetched.default_outbound_id
    }

    /** routeFingerprint (RouteUpdater.cpp): what a rule swap can change, compared to skip or flag an update. */
    internal fun routeFingerprint(p: RouteProfile): List<Any> =
        listOf<Any>(p.default_outbound_id, p.is_raw, p.raw_route) + p.rules.mapIndexed { i, rule -> RouteRuleEntity.of(0L, i, rule) }

    /**
     * The swap of UpdateProfile: rules, default outbound and an empty name are taken ([replaceRules]); a fetched
     * `dns` is not, so [p] keeps its own DNS settings.
     */
    private fun applyContent(p: RouteProfile, content: String, warnings: MutableList<String>?, fetched: Boolean): String? {
        val imported = RouteShare.fromShareInput(content, RouteManager.profileIdResolver())
        val remote = imported.profile
            ?: return imported.fatal.ifEmpty { "could not parse a routing profile from the response" }
        replaceRules(p, remote)
        if (p.name.isBlank() && remote.name.isNotBlank()) p.name = remote.name
        if (fetched) p.remote_last_update = System.currentTimeMillis() / 1000
        warnings?.addAll(imported.warnings)
        return null
    }

    /**
     * (Re)schedules the periodic update from route_auto_update, or cancels it below [MIN_INTERVAL_MINUTES]. Call it
     * after the interval changes and at app start: UPDATE registers the job again and keeps the current period's start.
     */
    fun schedule() {
        runOnDefaultDispatcher {
            val workManager = RemoteWorkManager.getInstance(app)
            val interval = DataStore.routeAutoUpdate
            try {
                if (interval < MIN_INTERVAL_MINUTES) {
                    workManager.cancelUniqueWork(WORK_NAME).await()
                    return@runOnDefaultDispatcher
                }
                val period = interval * 60L
                val now = System.currentTimeMillis() / 1000
                val initialDelay = (DataStore.routeAutoUpdateLast + period - now).coerceIn(0L, period)
                val request = PeriodicWorkRequest.Builder(UpdateTask::class.java, interval.toLong(), TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setInitialDelay(initialDelay, TimeUnit.SECONDS)
                    .setInputData(
                        // The worker runs in the :bg process, like the subscription updater.
                        Data.Builder()
                            .putString(RemoteListenableWorker.ARGUMENT_PACKAGE_NAME, app.packageName)
                            .putString(RemoteListenableWorker.ARGUMENT_CLASS_NAME, RemoteWorkerService::class.java.name)
                            .putInt(KEY_INTERVAL, interval)
                            .build()
                    )
                    .build()
                workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).await()
            } catch (e: Throwable) {
                Logs.w("RemoteRouteUpdater: scheduling failed", e)
            }
        }
    }

    private const val KEY_INTERVAL = "route_auto_update"

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({
            try {
                cont.resume(get())
            } catch (e: Throwable) {
                cont.resumeWithException(e)
            }
        }, { it.run() })
    }

    class UpdateTask(appContext: Context, params: WorkerParameters) : RemoteCoroutineWorker(appContext, params) {
        override suspend fun doRemoteWork(): Result {
            val interval = DataStore.routeAutoUpdate
            // The setting changed without a reschedule (e.g. a restored backup): follow it from now on.
            if (inputData.getInt(KEY_INTERVAL, interval) != interval) schedule()
            if (interval < MIN_INTERVAL_MINUTES) return Result.success()
            val now = System.currentTimeMillis() / 1000
            // A run within half an interval of the last one is a re-enqueue, not a new period.
            if (now - DataStore.routeAutoUpdateLast < interval * 60L / 2) return Result.success()
            DataStore.routeAutoUpdateLast = now
            withContext(Dispatchers.IO) { updateAll(true) }
            return Result.success()
        }
    }
}
