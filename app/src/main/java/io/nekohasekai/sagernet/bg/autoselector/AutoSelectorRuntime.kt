package io.nekohasekai.sagernet.bg.autoselector

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.group.SubscriptionQueue
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.outbound.config.AutoSelectorBuild
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

/**
 * The :bg side of a started auto-selector: ranking before a start or a rebuild (profile_start,
 * mainwindow_profile_lifecycle.cpp:206-221; mainwindow_autoselector.cpp), the bookkeeping after a start (:305-327),
 * the monitor of the core's status (AutoSelectorMonitor: the status the UI shows, PersistHealth, the exhausted-pool
 * rebuild), pin / automatic / recheck applied to the running core, and the rebuild after a subscription refresh
 * touched the running members (on_subscription_group_changed). The running instance keeps serving while anything is
 * measured, and a (re)start goes ahead as soon as enough members work, rebuilt on the full ranking once every one is
 * measured when that changes what runs.
 */
object AutoSelectorRuntime {

    private const val POLL_MS = 2000L
    private const val POLL_SCREEN_OFF_MS = 10_000L
    private const val HEALTH_PERSIST_MS = 60_000L
    private const val EXHAUSTED_GRACE_MS = 20_000L
    private const val REBUILD_BACKOFF_MIN_S = 60
    private const val REBUILD_BACKOFF_MAX_S = 600
    private const val SETTLE_POLL_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Logs.w(e) })
    private val lock = Any()

    private class Run(
        val service: BaseService.Interface,
        val proxy: ProxyInstance,
        val build: AutoSelectorBuild,
        val core: AutoSelectorCore,
        val selectorName: String,
        /** Member profile id -> display name. */
        val names: Map<Long, String>,
    ) {
        var monitor: Job? = null

        /** Polls at once: an action was applied or the screen came on. */
        val kick = Channel<Unit>(Channel.CONFLATED)
        var sample: AutoSelectorCore.Sample? = null
        var exhaustedSinceMs = 0L
        var lastHealthPersistMs = System.currentTimeMillis()
    }

    /** [sweep] is the token of the sweep that shows it. */
    private class Measuring(val sweep: Any, val selectorId: Long, val selectorName: String, val count: Int)

    private var service: BaseService.Interface? = null
    private var startedProxy: ProxyInstance? = null
    private var run: Run? = null
    private var measuring: Measuring? = null

    /**
     * A token of the ranking sweep still running (rankingInFlight_): a start of any selector meanwhile does not rank,
     * as the test session would only take its sweep after this one.
     */
    private var sweep: Any? = null

    /** The selector of a ranked restart under way, so that its [beforeStart] skips ranking (rankedFor_); -1 = none. */
    private var rankedFor = -1L
    private var pending: Job? = null
    private var notice = ""
    private var noticeAtMs = 0L

    // Kept across builds like the desktop monitor's, so a pool that stays dead backs off 60 s -> 600 s.
    private var lastRebuildRequestMs = 0L
    private var rebuildBackoffS = 0

    private var sentJson = AutoSelectorStatus.IDLE.toJson()
    private val updates = Channel<Pair<BaseService.Binder, String>>(Channel.CONFLATED)

    init {
        scope.launch {
            for ((binder, json) in updates) binder.broadcast { it.cbAutoSelectorUpdate(json) }
        }
    }

    /** The :bg process start: subscription refreshes prune the selectors that track the refreshed group. */
    fun install() {
        SubscriptionQueue.addGroupChangedListener { gid, disturbed -> onGroupChanged(gid, disturbed) }
    }

    /** The status as the callbacks carry it, or with [withMembers] with the member table. */
    fun statusJson(withMembers: Boolean): String = synchronized(lock) { compose(withMembers) }.toJson()

    // ------------------------------------------------------------------------------------------------ start path

    /**
     * ProxyInstance.init, before the config is built: ranks the selector when its plan asks for measurements, until
     * enough members work.
     */
    suspend fun beforeStart(proxy: ProxyInstance) {
        val service = proxy.service ?: return
        val profile = proxy.profile
        // Right after a sweep a pool can still want ranking (validity 0): a ranked restart must not measure again.
        val justRanked = synchronized(lock) {
            this.service = service
            (rankedFor == profile.id).also { rankedFor = -1L }
        }
        if (profile.type != AutoSelectorProfiles.TYPE) {
            publish()
            return
        }
        if (justRanked) return
        if (!withContext(Dispatchers.IO) { runCatching { AutoSelectorProfiles.needsRanking(profile.id) }.getOrDefault(false) }) {
            return
        }
        measure(service, profile.id, emptyList())
        val title = withContext(Dispatchers.IO) { ServiceNotification.genTitle(profile) }
        service.data.notification?.postNotificationTitle(title)
    }

    /** ProxyInstance.launch, the box started: last_built, last_built_at and the history (:305-327), then the monitor. */
    fun onStarted(proxy: ProxyInstance) {
        val service = proxy.service ?: return
        val build = proxy.config.autoSelector
        synchronized(lock) {
            this.service = service
            startedProxy = proxy
        }
        if (build == null) {
            publish()
            return
        }
        val box = proxy.boxOrNull ?: return
        scope.launch(Dispatchers.IO) {
            val profiles = ProfileManager.getProfiles(build.memberIds).associateBy { it.id }
            val names = build.memberIds.associateWith { id -> profiles[id]?.outbound?.displayName().orEmpty() }
            val started = AutoSelectorProfiles.writes.withLock {
                val selector = AutoSelectorProfiles.load(build.selectorId)
                val selectorName = selector?.displayName().orEmpty().ifEmpty { proxy.profile.displayName() }
                val attaching = Run(service, proxy, build, AutoSelectorCore(box, build.groupTag), selectorName, names)
                val attached = synchronized(lock) {
                    if (startedProxy !== proxy) return@synchronized false
                    run?.monitor?.cancel()
                    run = attaching
                    true
                }
                if (!attached) return@launch
                if (selector != null) {
                    val now = System.currentTimeMillis() / 1000
                    selector.lastBuilt = build.memberIds.toMutableList()
                    selector.lastBuiltAt = now
                    selector.recordHistory(build.memberIds, names, now)
                    AutoSelectorProfiles.save(build.selectorId, selector)
                    Logs.i("[Auto selector] Running the best ${build.memberIds.size} of ${selector.pool.size} ranked profiles.")
                }
                attaching
            }
            publish()
            startMonitor(started)
        }
    }

    /** ProxyInstance.close: the monitor stops, the health is kept (AutoSelectorMonitor::Clear), a pending rebuild is dropped. */
    fun onClosed(proxy: ProxyInstance) {
        cancelPending()
        val closed = synchronized(lock) {
            if (startedProxy === proxy) startedProxy = null
            run?.takeIf { it.proxy === proxy }?.also { run = null }
        }
        if (closed != null) {
            closed.monitor?.cancel()
            persistHealth(closed)
        }
        publish()
    }

    /** BaseService ended stopped: a ranked restart that never got to start must not spare a later start its ranking. */
    fun serviceStopped() {
        synchronized(lock) { rankedFor = -1L }
    }

    /**
     * BaseService reload / switch of a running service: when the profile about to start is an auto-selector whose plan
     * wants measurements, the running instance keeps serving while they run until enough members work, then [proceed]
     * restarts without ranking again (profile_start ranks before it stops the running profile). Anything else proceeds
     * at once.
     */
    fun restart(service: BaseService.Interface, proceed: () -> Unit) {
        cancelPending()
        val target = DataStore.selectedProxy
        val selector = service.data.state == BaseService.State.Connected &&
            runCatching { ProfileManager.getProfile(target)?.type == AutoSelectorProfiles.TYPE }.getOrDefault(false)
        if (!selector) {
            proceed()
            return
        }
        launchPending {
            val needed = withContext(Dispatchers.IO) {
                runCatching { AutoSelectorProfiles.needsRanking(target) }.getOrDefault(false)
            }
            if (needed) measure(service, target, emptyList())
            if (claim() && service.data.state.canStop) {
                if (needed) synchronized(lock) { rankedFor = target }
                proceed()
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ actions

    /**
     * AutoSelectorMonitor::RequestSelect; [memberProfileId] < 0 goes back to automatic. The choice is stored first, so
     * the next build carries it even when the running core cannot take it any more.
     */
    fun select(memberProfileId: Long) {
        val run = current() ?: return post(R.string.autosel_notice_not_running)
        val memberId = if (memberProfileId < 0) -1L else memberProfileId
        val tag = if (memberId < 0) "" else run.build.tagOf(memberId) ?: return post(R.string.autosel_notice_not_in_pool)
        scope.launch(Dispatchers.IO) {
            // Stored as a profile id, not a tag: tags are positional within one build.
            AutoSelectorProfiles.writes.withLock {
                val selector = AutoSelectorProfiles.load(run.build.selectorId)
                if (selector != null && selector.pinnedID != memberId) {
                    selector.pinnedID = memberId
                    AutoSelectorProfiles.save(run.build.selectorId, selector)
                }
            }
            try {
                run.core.select(tag)
            } catch (e: Exception) {
                Logs.w(e)
                return@launch post(app.getString(R.string.autosel_notice_select_failed, e.readableMessage))
            }
            post(if (memberId < 0) R.string.autosel_notice_automatic else R.string.autosel_notice_pinned)
            run.kick.trySend(Unit)
        }
    }

    /** The editor's "Use automatic" (edit_autoselector.cpp:35-49): a running core holds its own copy of the pin. */
    fun releasePin(selectorId: Long) {
        if (current()?.build?.selectorId == selectorId) select(-1)
    }

    /** AutoSelectorMonitor::RequestRecheck: the core re-probes every member now. */
    fun recheck() {
        val run = current() ?: return post(R.string.autosel_notice_not_running)
        scope.launch(Dispatchers.IO) {
            try {
                run.core.recheck()
            } catch (e: Exception) {
                Logs.w(e)
                return@launch post(app.getString(R.string.autosel_notice_recheck_failed, e.readableMessage))
            }
            post(R.string.autosel_notice_rechecking)
            run.kick.trySend(Unit)
        }
    }

    // ------------------------------------------------------------------------------------------------ rebuilds

    /**
     * Measures while [run] keeps serving, until enough members work, then restarts it without ranking again: with
     * [stale] the unmeasured candidates plus [stale] (onPoolExhausted), without it only when the plan wants
     * measurements (profile_start).
     */
    private fun rebuild(run: Run, stale: Collection<Long>?) {
        val id = run.build.selectorId
        launchPending {
            val ranked = stale != null ||
                withContext(Dispatchers.IO) { runCatching { AutoSelectorProfiles.needsRanking(id) }.getOrDefault(false) }
            if (ranked) measure(run.service, id, stale.orEmpty())
            if (claim() && current() === run && run.service.data.state.canStop) {
                if (ranked) synchronized(lock) { rankedFor = id }
                run.service.stopRunner(true)
            }
        }
    }

    /** on_subscription_group_changed (mainwindow_autoselector.cpp:33-65). */
    private fun onGroupChanged(gid: Long, disturbed: List<Long>) {
        if (gid < 0) return
        scope.launch(Dispatchers.IO) {
            val disturbedSet = disturbed.toHashSet()
            val running = current()
            var restart = false
            for (id in AutoSelectorProfiles.ids()) {
                val (selector, prunedBuilt) = AutoSelectorProfiles.writes.withLock {
                    val selector = AutoSelectorProfiles.load(id)?.takeIf { gid in it.gids } ?: return@withLock null
                    val alive = AutoSelectorProfiles.existing(selector.pool + selector.lastBuilt)
                    val prunedPool = selector.pool.removeAll { it !in alive }
                    val prunedBuilt = selector.lastBuilt.removeAll { it !in alive }
                    if (prunedPool || prunedBuilt) AutoSelectorProfiles.save(id, selector)
                    selector to prunedBuilt
                } ?: continue
                if (running == null || running.build.selectorId != id) continue
                // A replaced member keeps its id, so only the disturbed set spots it.
                if (prunedBuilt || selector.lastBuilt.any { it in disturbedSet }) restart = true
            }
            if (!restart || running == null) return@launch
            Logs.i("[Auto selector] The subscription replaced profiles it was running on — rebuilding.")
            post(R.string.autosel_notice_subscription)
            withContext(Dispatchers.Main) { if (current() === running) rebuild(running, null) }
        }
    }

    /** on_auto_selector_exhausted (mainwindow_autoselector.cpp:67-82) behind the monitor's network check. */
    private fun onExhausted(run: Run) {
        if (SagerNet.underlyingNetwork == null) {
            Logs.w("[Auto selector] Every profile is failing, but this device has no network connection — keeping the current pool.")
            return
        }
        Logs.w("[Auto selector] Every running profile stopped working — rebuilding from the next best candidates.")
        post(R.string.autosel_notice_exhausted)
        scope.launch(Dispatchers.IO) {
            val stale = AutoSelectorProfiles.load(run.build.selectorId)?.lastBuilt ?: run.build.memberIds
            withContext(Dispatchers.Main) { if (current() === run) rebuild(run, stale) }
        }
    }

    /**
     * rank_auto_selector with the "Measuring N profiles" state in the status and the service notification. Returns once
     * the selector may be built: as soon as enough members work, the sweep then going on and [finishEarly] after it,
     * else once every candidate is measured. While a sweep still runs it returns at once, and the start builds from the
     * pool as it stands, with [stale] ranked as untested.
     */
    private suspend fun measure(service: BaseService.Interface, selectorId: Long, stale: Collection<Long>) {
        val token = Any()
        val claimed = synchronized(lock) { (sweep == null).also { if (it) sweep = token } }
        if (!claimed) {
            // The members that just died must not lead the rebuild again.
            if (stale.isNotEmpty()) withContext(Dispatchers.IO) {
                runCatching { AutoSelectorProfiles.writes.withLock { AutoSelectorProfiles.rerank(selectorId, stale) } }
                    .onFailure { Logs.w(it) }
            }
            return
        }
        val ready = CompletableDeferred<Unit>()
        // In the runtime's scope, not the caller's: after an early start the sweep outlives the call.
        val job = scope.launch(Dispatchers.IO) {
            var startedEarly = false
            try {
                val name = AutoSelectorProfiles.load(selectorId)?.displayName().orEmpty()
                    .ifEmpty { app.getString(R.string.autosel_title) }
                startedEarly = AutoSelectorProfiles.rank(
                    selectorId,
                    stale,
                    onMeasuring = { count ->
                        synchronized(lock) { measuring = Measuring(token, selectorId, name, count) }
                        publish()
                        service.data.notification?.postNotificationTitle(
                            app.getString(R.string.autosel_notification_measuring, name, count)
                        )
                    },
                    startEarly = {
                        stopMeasuring(token)
                        ready.complete(Unit)
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                releaseSweep(token)
                stopMeasuring(token)
                ready.complete(Unit)
            }
            if (startedEarly) finishEarly(service, selectorId)
        }
        try {
            ready.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The start was abandoned before the selector could run: nothing waits for the ranking any more. The token
            // goes now, so a start right after ranks anew instead of building on what this sweep left.
            releaseSweep(token)
            job.cancel()
            throw e
        }
    }

    /**
     * finishEarlySweep (AutoSelectorController.cpp:192-216): once the start the early ranking let through has settled,
     * reranks with every result in and, when that changes the build still running, restarts the selector on the full
     * ranking without ranking again.
     */
    private suspend fun finishEarly(service: BaseService.Interface, selectorId: Long) {
        val proxy = settled(service)?.takeIf { it.profile.id == selectorId }
        val built = proxy?.config?.autoSelector?.memberIds
        val changed = AutoSelectorProfiles.writes.withLock { AutoSelectorProfiles.rerankChanges(selectorId, built) }
        if (!changed) return
        withContext(Dispatchers.Main) {
            val data = service.data
            val same = data.proxy === proxy && data.state == BaseService.State.Connected && DataStore.selectedProxy == selectorId
            // A pending restart (another profile, a rebuild) replaces this run anyway.
            if (!same || synchronized(lock) { pending != null }) return@withContext
            Logs.i("[Auto selector] Every profile is measured and the best ones changed — rebuilding with the full ranking.")
            synchronized(lock) { rankedFor = selectorId }
            service.stopRunner(true)
        }
    }

    /**
     * Waits out a start or restart under way on [service] (BaseService state and start job are main-thread only): the
     * proxy running after it, null when the service ended stopped.
     */
    private suspend fun settled(service: BaseService.Interface): ProxyInstance? {
        while (true) {
            val (state, starting, proxy) = withContext(Dispatchers.Main) {
                Triple(service.data.state, service.data.connectingJob, service.data.proxy)
            }
            when (state) {
                BaseService.State.Connected -> return proxy
                // A restart's stop has no start job yet.
                BaseService.State.Connecting, BaseService.State.Stopping ->
                    if (starting != null) starting.join() else delay(SETTLE_POLL_MS)

                else -> return null
            }
        }
    }

    private fun releaseSweep(token: Any) {
        synchronized(lock) { if (sweep === token) sweep = null }
    }

    /** A cancelled sweep winding down must not clear the status of the sweep that replaced it. */
    private fun stopMeasuring(token: Any) {
        val cleared = synchronized(lock) { measuring?.takeIf { it.sweep === token }?.also { measuring = null } }
        if (cleared != null) publish()
    }

    private fun launchPending(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(Dispatchers.Main.immediate, CoroutineStart.LAZY, block)
        synchronized(lock) {
            pending?.cancel()
            pending = job
        }
        job.start()
    }

    /** The pending job takes itself out before it restarts, so the close it causes does not cancel it. */
    private suspend fun claim(): Boolean {
        val job = coroutineContext.job
        return synchronized(lock) { (pending === job).also { if (it) pending = null } }
    }

    private fun cancelPending() {
        synchronized(lock) {
            pending?.cancel()
            pending = null
        }
    }

    // ------------------------------------------------------------------------------------------------ monitor

    /** AutoSelectorMonitor::Loop: every 2 s, every 10 s while the screen is off. */
    private fun startMonitor(run: Run) {
        val job = scope.launch(Dispatchers.IO) {
            val screenOn = run.service.data.notification?.screenOn
            if (screenOn != null) launch { screenOn.drop(1).collect { if (it) run.kick.trySend(Unit) } }
            while (isActive) {
                val sample = try {
                    run.core.sample()
                } catch (e: Exception) {
                    Logs.w(e)
                    null
                }
                if (sample != null) onSample(run, sample)
                withTimeoutOrNull(if (screenOn?.value == false) POLL_SCREEN_OFF_MS else POLL_MS) { run.kick.receive() }
            }
        }
        val attached = synchronized(lock) {
            (this.run === run).also { if (it) run.monitor = job }
        }
        if (!attached) job.cancel()
    }

    /** AutoSelectorMonitor::poll after the read: the exhausted-pool rule and the PersistHealth schedule. */
    private fun onSample(run: Run, sample: AutoSelectorCore.Sample) {
        val now = System.currentTimeMillis()
        val idle = run.service.data.idlePaused(run.core.box)
        var exhausted = false
        var persist = false
        synchronized(lock) {
            if (this.run !== run) return
            run.sample = sample
            // "untested" counts as usable: it has not failed, only not been reached.
            val usable = sample.members.count { it.isUsable || it.state == AutoSelectorStatus.STATE_UNTESTED }
            // A suspended core (dropped Wi-Fi) must never count as an exhausted pool, nor one paused for device idle.
            val everythingDead = usable == 0 && sample.membersProbed > 0 && !sample.suspended && !idle &&
                sample.phase != "starting"
            if (!everythingDead) {
                run.exhaustedSinceMs = 0
            } else if (run.exhaustedSinceMs == 0L) {
                run.exhaustedSinceMs = now
            }
            if (run.exhaustedSinceMs != 0L && now - run.exhaustedSinceMs >= EXHAUSTED_GRACE_MS) {
                if (lastRebuildRequestMs == 0L || now - lastRebuildRequestMs >= rebuildBackoffS * 1000L) {
                    lastRebuildRequestMs = now
                    rebuildBackoffS = if (rebuildBackoffS == 0) REBUILD_BACKOFF_MIN_S
                    else minOf(rebuildBackoffS * 2, REBUILD_BACKOFF_MAX_S)
                    run.exhaustedSinceMs = 0
                    exhausted = true
                }
            }
            if (usable > 0) rebuildBackoffS = 0
            persist = now - run.lastHealthPersistMs >= HEALTH_PERSIST_MS
        }
        // Nothing shows it while the screen is off, and a frozen UI process would only pile the callbacks up; the
        // screen coming on polls and publishes at once.
        if (run.service.data.notification?.screenOn?.value != false) publish()
        if (persist) persistHealth(run)
        if (exhausted) onExhausted(run)
    }

    /** AutoSelectorMonitor::PersistHealth of the last sample. */
    private fun persistHealth(run: Run) {
        val members = synchronized(lock) {
            val sample = run.sample ?: return
            // A suspended core believes the local network is down, so nothing it reports describes the servers.
            if (sample.suspended) return
            run.lastHealthPersistMs = System.currentTimeMillis()
            sample.members
        }
        scope.launch(Dispatchers.IO) { AutoSelectorProfiles.saveHealth(run.build.members, members) }
    }

    // ------------------------------------------------------------------------------------------------ status

    private fun current(): Run? = synchronized(lock) { run }

    private fun post(@StringRes text: Int) = post(app.getString(text))

    private fun post(text: String) {
        synchronized(lock) {
            notice = text
            noticeAtMs = System.currentTimeMillis()
        }
        publish()
    }

    /** Sends the status without its member table to every callback when it changed. */
    private fun publish() {
        val update = synchronized(lock) {
            val json = compose(false).toJson()
            if (json == sentJson) return
            sentJson = json
            val binder = service?.data?.binder ?: return
            binder to json
        }
        updates.trySend(update)
    }

    /** AutoSelectorView of the running build (the member table only [withMembers]), or of the selector being measured. */
    private fun compose(withMembers: Boolean): AutoSelectorStatus {
        val run = run
        val measuring = measuring
        var status = AutoSelectorStatus(notice = notice, noticeAtMs = noticeAtMs)
        if (run != null) {
            val sample = run.sample
            val ids = run.build.members
            val selectedId = sample?.selectedTag?.let(ids::get) ?: -1L
            status = status.copy(
                phase = AutoSelectorStatus.PHASE_RUNNING,
                selectorId = run.build.selectorId,
                selectorName = run.selectorName,
                // AutoSelectorMonitor::SetBuild: "starting" until the first poll.
                corePhase = sample?.phase ?: "starting",
                selectedId = selectedId,
                selectedName = run.names[selectedId].orEmpty().ifEmpty { sample?.selectedTag.orEmpty() },
                pinnedId = sample?.pinnedTag?.let(ids::get) ?: -1L,
                lastSwitchMs = sample?.lastSwitchMs ?: 0,
                lastSwitchReason = sample?.lastSwitchReason.orEmpty(),
                membersTotal = ids.size,
                suspended = sample?.suspended == true,
                membersProbed = sample?.membersProbed ?: 0,
                membersAlive = sample?.membersAlive ?: 0,
                membersQualified = sample?.membersQualified ?: 0,
                membersCooldown = sample?.membersCooldown ?: 0,
                probesInFlight = sample?.probesInFlight ?: 0,
                roundsCompleted = sample?.roundsCompleted ?: 0,
                lastRoundMs = sample?.lastRoundMs ?: 0,
                nextRoundMs = sample?.nextRoundMs ?: 0,
                balance = sample?.balance == true,
                exhaustedSinceMs = run.exhaustedSinceMs,
                members = if (withMembers && sample != null) {
                    sample.members.mapNotNull { member ->
                        ids[member.tag]?.let { id -> member.copy(id = id, name = run.names[id].orEmpty()) }
                    }
                } else {
                    emptyList()
                },
            )
        }
        if (measuring != null) {
            if (run == null || run.build.selectorId != measuring.selectorId) {
                status = AutoSelectorStatus(
                    selectorId = measuring.selectorId,
                    selectorName = measuring.selectorName,
                    notice = notice,
                    noticeAtMs = noticeAtMs,
                )
            }
            status = status.copy(phase = AutoSelectorStatus.PHASE_MEASURING, measuring = measuring.count)
        }
        return status
    }
}
