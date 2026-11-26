package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.appwidget.Widgets
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.LiveStatsRecorder
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrafficLooper(val data: BaseService.Data, private val sc: CoroutineScope) {

    companion object {
        private const val TRAFFIC_BATCH_SIZE = 500
    }

    /** One profile's totals: [rx]/[tx] as shown, [persistedRx]/[persistedTx] what the database already holds. */
    private class ProfileTraffic(val id: Long, var rx: Long, var tx: Long) {
        var persistedRx = rx
        var persistedTx = tx
        var changed = false
        var credited = false
    }

    private var job: Job? = null
    private val stateMutex = Mutex()
    private var trafficUpdater: TrafficUpdater? = null
    private val profiles = LinkedHashMap<Long, ProfileTraffic>()

    // A tag credits every profile it carries (an auto-selector's pool tag: the selector and the member), and a
    // profile sums the tags that carry it.
    private val tagProfiles = LinkedHashMap<String, List<ProfileTraffic>>()
    private var sessionTx = 0L
    private var sessionRx = 0L

    private data class LoopSnapshot(
        val speed: SpeedDisplayData,
        val trafficUpdates: ArrayList<TrafficData>,
    )

    /** Bytes a speed test stored for a profile counted here: they join its totals as already persisted. */
    private val credits = ProfileManager.CreditListener { profileId, rx, tx ->
        stateMutex.withLock {
            profiles[profileId]?.apply {
                this.rx += rx
                this.tx += tx
                persistedRx += rx
                persistedTx += tx
                credited = true
            }
        }
    }

    suspend fun stop() {
        ProfileManager.removeCreditListener(credits)
        job?.cancelAndJoin()
        job = null
        LiveStatsRecorder.stopped()
        stateMutex.withLock {
            // Polling pauses with the screen off; the core's counters outlive the box, so collect the rest now.
            try {
                collect()
            } catch (e: Throwable) {
                Logs.w(e)
            }
            val traffic = ArrayList<TrafficData>()
            SagerDatabase.instance.runInTransaction {
                for (item in profiles.values) {
                    if (item.id <= 0L) continue
                    persist(item)
                    traffic.add(TrafficData(id = item.id, rx = item.rx, tx = item.tx))
                }
            }
            if (traffic.isNotEmpty()) GroupSort.repositionLater(traffic.map { it.id }, SortInput.TRAFFIC)
            data.proxy?.trafficMap?.values?.forEach { entities ->
                for (entity in entities) profiles[entity.id]?.let {
                    entity.rx = it.rx
                    entity.tx = it.tx
                }
            }
            if (traffic.isNotEmpty()) {
                val batches = traffic.chunked(TRAFFIC_BATCH_SIZE).map { TrafficDataBatch(ArrayList(it)) }
                data.binder.broadcast { callback -> batches.forEach { callback.cbTrafficUpdate(it) } }
            }
        }
        Logs.d("finally traffic post done")
    }

    /** Writes what moved since the last write as an increment, so traffic credited meanwhile (speed tests) stays. */
    private fun persist(item: ProfileTraffic) {
        val rx = item.rx - item.persistedRx
        val tx = item.tx - item.persistedTx
        if (rx == 0L && tx == 0L) return
        SagerDatabase.proxyDao.addTraffic(item.id, rx, tx)
        item.persistedRx = item.rx
        item.persistedTx = item.tx
    }

    fun start() {
        ProfileManager.addCreditListener(credits)
        job = sc.launch { loop() }
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        val targetIds = profileIds.asSequence().filter { it > 0L }.toHashSet()
        if (targetIds.isEmpty()) return

        stateMutex.withLock {
            collect()
            val updates = linkedMapOf<Long, TrafficData>()
            profiles.values.forEach { item ->
                if (item.id > 0L && item.id !in targetIds && item.changed) {
                    updates[item.id] = TrafficData(id = item.id, rx = item.rx, tx = item.tx)
                }
            }
            data.proxy?.trafficMap?.values?.forEach { entities ->
                entities.forEach { entity ->
                    if (entity.id in targetIds) {
                        entity.tx = 0L
                        entity.rx = 0L
                    }
                }
            }
            targetIds.forEach { id ->
                profiles[id]?.apply {
                    rx = 0L
                    tx = 0L
                    persistedRx = 0L
                    persistedTx = 0L
                    changed = false
                }
                updates[id] = TrafficData(id = id, rx = 0L, tx = 0L)
            }
            ProfileManager.resetTraffic(targetIds.toLongArray())
            val batches = updates.values.chunked(TRAFFIC_BATCH_SIZE).map { TrafficDataBatch(ArrayList(it)) }
            data.binder.broadcast(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) { callback ->
                batches.forEach { callback.cbTrafficUpdate(it) }
            }
        }
    }

    private fun ensureUpdater(proxy: ProxyInstance): TrafficUpdater {
        trafficUpdater?.let { return it }
        profiles.clear()
        tagProfiles.clear()
        proxy.trafficMap.forEach { (tag, entities) ->
            tagProfiles[tag] = entities.map { entity ->
                profiles.getOrPut(entity.id) { ProfileTraffic(entity.id, entity.rx, entity.tx) }
            }
            Logs.d("traffic count $tag to ${entities.joinToString { it.id.toString() }}")
        }
        return TrafficUpdater(proxy.box, tagProfiles.keys + CoreConfig.TAG_DIRECT).also { trafficUpdater = it }
    }

    /** One tick: the core's deltas credited to the profiles; false before the box is up. */
    private fun collect(): Boolean {
        val proxy = data.proxy ?: return false
        if (!proxy.isInitialized()) return false
        val updater = ensureUpdater(proxy)
        updater.updateAll()
        for (item in profiles.values) {
            item.changed = item.credited
            item.credited = false
        }
        for ((tag, items) in tagProfiles) {
            val stat = updater.stats[tag] ?: continue
            if (stat.rx == 0L && stat.tx == 0L) continue
            sessionRx += stat.rx
            sessionTx += stat.tx
            for (item in items) {
                item.rx += stat.rx
                item.tx += stat.tx
                item.changed = true
            }
        }
        return true
    }

    private suspend fun loop() {
        val delayMs = 1000L

        while (currentCoroutineContext().isActive) {
            val proxy = data.proxy
            if (proxy == null || !proxy.isInitialized()) {
                delay(delayMs)
                continue
            }
            // Nothing shows the speed with the screen off: stop waking the core until it is on again (#18).
            val screenOn = data.notification?.screenOn
            if (screenOn != null && !screenOn.value) {
                screenOn.first { it }
                continue
            }

            val snapshot = stateMutex.withLock {
                if (!collect()) return@withLock null
                currentCoroutineContext().ensureActive()
                val stats = trafficUpdater!!.stats
                var mainTxRate = 0L
                var mainRxRate = 0L
                for (tag in tagProfiles.keys) {
                    val stat = stats[tag] ?: continue
                    mainTxRate += stat.txRate
                    mainRxRate += stat.rxRate
                }
                val direct = stats[CoreConfig.TAG_DIRECT]
                LiveStatsRecorder.record(mainRxRate, mainTxRate, direct?.rxRate ?: 0L, direct?.txRate ?: 0L, proxy.boxOrNull)
                val trafficUpdates = arrayListOf<TrafficData>()
                for (item in profiles.values) {
                    if (item.id > 0L && item.changed) {
                        trafficUpdates.add(TrafficData(id = item.id, rx = item.rx, tx = item.tx))
                    }
                }
                val snapshot = LoopSnapshot(
                    speed = SpeedDisplayData(
                        mainTxRate,
                        mainRxRate,
                        direct?.txRate ?: 0L,
                        direct?.rxRate ?: 0L,
                        sessionTx,
                        sessionRx,
                    ),
                    trafficUpdates = trafficUpdates,
                )
                if (data.state == BaseService.State.Connected &&
                    data.binder.hasCallback(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
                ) {
                    data.binder.broadcast(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) { callback ->
                        callback.cbSpeedUpdate(snapshot.speed)
                        snapshot.trafficUpdates.chunked(TRAFFIC_BATCH_SIZE).forEach {
                            callback.cbTrafficUpdate(TrafficDataBatch(ArrayList(it)))
                        }
                    }
                }
                snapshot
            }
            if (snapshot == null) {
                delay(delayMs)
                continue
            }
            currentCoroutineContext().ensureActive()

            data.notification?.apply {
                if (listenPostSpeed) postNotificationSpeedUpdate(snapshot.speed)
            }
            Widgets.pushSpeed(app, snapshot.speed.txRateProxy, snapshot.speed.rxRateProxy)

            delay(delayMs)
        }
    }
}
