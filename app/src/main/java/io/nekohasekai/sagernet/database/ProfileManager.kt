package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.Outbound
import java.io.IOException
import java.sql.SQLException
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The profile repository (the desktop's ProfilesRepo plus the list edits of Group.cpp) over `profiles`. A group's
 * members are its rows ordered by `user_order`, kept dense (0..n-1 = the desktop's `profiles_json` index).
 * Writes that change what a kept group sort reads queue a [GroupSort] re-sort, as ProfilesRepo.cpp does.
 */
object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: List<TrafficData>)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    /** Told about the bytes [addTraffic] credits, in the process that credits them. */
    fun interface CreditListener {
        suspend fun onCredited(profileId: Long, rx: Long, tx: Long)
    }

    /**
     * The result of [batchDeleteProfiles]: [kept] holds the requested ids that were not deleted, [stopped] the running
     * profile this delete stopped (allow_stopping_active_profile).
     */
    class DeleteOutcome(
        @JvmField val ok: Boolean,
        @JvmField val deleted: List<Long>,
        @JvmField val kept: List<Long>,
        @JvmField val stopped: ProxyEntity? = null,
    )

    /** SQLite's bound-parameter limit is 999; id lists are split below it. */
    internal const val CHUNK = 500

    private val listeners = ArrayList<Listener>()
    private val creditListeners = CopyOnWriteArrayList<CreditListener>()

    private val dao get() = SagerDatabase.proxyDao

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun addCreditListener(listener: CreditListener) {
        creditListeners.add(listener)
    }

    fun removeCreditListener(listener: CreditListener) {
        creditListeners.remove(listener)
    }

    // ------------------------------------------------------------------------------------------------ reads

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId <= 0L) return null
        return try {
            dao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            profileIds.chunked(CHUNK).flatMap { dao.getEntities(it) }
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    /** The group's profile ids in list order (Group::Profiles). */
    fun memberIds(groupId: Long): List<Long> = dao.getIdsByGroup(groupId)

    /** The group's profiles in list order. */
    fun members(groupId: Long): List<ProxyEntity> = dao.getByGroup(groupId)

    /** The profile the service runs (the desktop's started_id), 0 when stopped. */
    fun runningProfileId(): Long = if (DataStore.serviceState.started) DataStore.currentProfile else 0L

    /**
     * The running config was built from [profileId]: the started profile, or one of the profiles it pulled in (chain
     * hops, the group's landing / front proxy, auto-selector members, route outbounds), RunningUsesProfile.
     */
    fun runningUses(profileId: Long): Boolean {
        val running = runningProfileId()
        if (running <= 0L || profileId <= 0L) return false
        return profileId == running || profileId.toString() in DataStore.runningProfiles
    }

    // ------------------------------------------------------------------------------------------------ add

    /** ProfilesRepo::AddProfile: appended to group [groupId], the current group when it is not a group id (<= 0). */
    suspend fun addProfile(outbound: Outbound, groupId: Long = -1L): ProxyEntity {
        val gid = if (groupId <= 0) GroupRepo.currentId() else groupId
        val profile = ProxyEntity(groupId = gid).putOutbound(outbound)
        SagerDatabase.instance.runInTransaction {
            profile.userOrder = dao.nextOrder(gid)
            profile.id = dao.addProxy(profile)
        }
        iterator { onAdd(profile) }
        GroupSort.repositionLater(mapOf(gid to listOf(profile.id)), SortInput.MEMBERSHIP)
        return profile
    }

    suspend fun createProfile(groupId: Long, outbound: Outbound): ProxyEntity = addProfile(outbound, groupId)

    /**
     * ProfilesRepo::AddProfileBatch: appended to group [groupId] (the current group when <= 0) in input order,
     * inserted in chunks. The listeners get one reload of the group.
     */
    suspend fun addProfileBatch(outbounds: List<Outbound>, groupId: Long = -1L): List<ProxyEntity> {
        if (outbounds.isEmpty()) return emptyList()
        val gid = if (groupId <= 0) GroupRepo.currentId() else groupId
        val added = ArrayList<ProxyEntity>(outbounds.size)
        for (chunk in outbounds.chunked(CHUNK)) {
            val rows = SagerDatabase.instance.runInTransaction(Callable {
                var order = dao.nextOrder(gid)
                val rows = chunk.map { ProxyEntity(groupId = gid, userOrder = order++).putOutbound(it) }
                dao.insert(rows).forEachIndexed { index, id -> rows[index].id = id }
                rows
            })
            added.addAll(rows)
        }
        GroupRepo.postReload(gid)
        GroupSort.repositionLater(mapOf(gid to added.map { it.id }), SortInput.MEMBERSHIP)
        return added
    }

    // ------------------------------------------------------------------------------------------------ update

    suspend fun updateProfile(profile: ProxyEntity) {
        dao.updateProxy(profile)
        iterator { onUpdated(profile, false) }
    }

    suspend fun updateProfile(profiles: List<ProxyEntity>) {
        dao.updateProxy(profiles)
        profiles.forEach {
            iterator { onUpdated(it, false) }
        }
    }

    /**
     * Stores only the profile data (type, name, outbound JSON): test results and traffic written meanwhile stay.
     * Returns whether that data changed.
     */
    suspend fun updateOutbound(profile: ProxyEntity): Boolean {
        val before = dao.getById(profile.id)
        dao.updateOutbound(profile.id, profile.type, profile.name, profile.outboundJson)
        val stored = dao.getById(profile.id) ?: return false
        iterator { onUpdated(stored, false) }
        val changed = before == null || before.type != stored.type || before.name != stored.name ||
            before.outboundJson != stored.outboundJson
        if (changed) GroupSort.repositionLater(mapOf(stored.groupId to listOf(stored.id)), SortInput.CONFIG)
        return changed
    }

    suspend fun updateTraffic(profileId: Long, rx: Long, tx: Long) {
        dao.updateTraffic(profileId, rx, tx)
    }

    /**
     * Bytes moved around the running box's tracker (speed tests, TestRunner::creditTraffic): stored, and handed to the
     * [CreditListener]s so the traffic looper's live totals, which the rows show, include them.
     */
    suspend fun addTraffic(profileId: Long, rx: Long, tx: Long) {
        dao.addTraffic(profileId, rx, tx)
        for (listener in creditListeners) listener.onCredited(profileId, rx, tx)
    }

    suspend fun resetTraffic(profileIds: LongArray) {
        if (profileIds.isEmpty()) return
        SagerDatabase.instance.runInTransaction {
            profileIds.toList().chunked(CHUNK).forEach { dao.resetTraffic(it.toLongArray()) }
        }
        GroupSort.repositionLater(profileIds.toList(), SortInput.TRAFFIC)
    }

    // ------------------------------------------------------------------------------------------------ delete

    /** Deletes one profile (the list's own delete, no running-profile rule) and closes the gap in its group. */
    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        val deleted = SagerDatabase.instance.runInTransaction(Callable {
            // In a group numbered 0..n-1, shifting the rows behind the gap is the same as renumbering them all.
            val order = dao.orderInGroup(profileId, groupId)?.takeIf { dao.isDense(groupId) }
            if (dao.deleteById(profileId) == 0) return@Callable false
            if (order != null) dao.closeGap(groupId, order) else compactLocked(groupId)
            normalizePinsLocked(groupId)
            true
        })
        if (!deleted) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        iterator { onRemoved(groupId, profileId) }
    }

    /**
     * ProfilesRepo::BatchDeleteProfiles through GroupUpdater's deleteProfiles: when the running profile is among
     * [profileIds] the service is stopped if [stopRunning] (allow_stopping_active_profile), otherwise that profile is
     * kept. Each affected group is compacted and reloaded once.
     */
    suspend fun batchDeleteProfiles(
        profileIds: List<Long>,
        stopRunning: Boolean = DataStore.allowStoppingActiveProfile,
    ): DeleteOutcome {
        val requested = profileIds.distinct()
        if (requested.isEmpty()) return DeleteOutcome(true, emptyList(), emptyList())
        val toDelete = ArrayList(requested)
        val running = runningProfileId()
        var stopping: ProxyEntity? = null
        if (running > 0 && running in toDelete) {
            if (stopRunning) {
                stopping = dao.getById(running)
                SagerNet.stopService()
            } else {
                toDelete.remove(running)
            }
        }
        val groups = HashSet<Long>()
        val deleted = ArrayList<Long>()
        val ok = try {
            SagerDatabase.instance.runInTransaction {
                for (chunk in toDelete.chunked(CHUNK)) {
                    groups.addAll(dao.groupsOf(chunk))
                    val existing = dao.existingIds(chunk)
                    dao.deleteByIds(chunk)
                    deleted.addAll(existing)
                }
                for (gid in groups) {
                    compactLocked(gid)
                    normalizePinsLocked(gid)
                }
            }
            true
        } catch (e: Exception) {
            Logs.w(e)
            deleted.clear()
            false
        }
        if (DataStore.selectedProxy in deleted) DataStore.selectedProxy = 0L
        for (gid in groups) GroupRepo.postReload(gid)
        val deletedSet = deleted.toHashSet()
        return DeleteOutcome(ok, deleted, requested.filter { it !in deletedSet }, stopping?.takeIf { ok && it.id in deletedSet })
    }

    // ------------------------------------------------------------------------------------------------ order

    /**
     * The group's list becomes [ids] (Group::profiles = ids): positions 0..n-1 in that order; members missing from
     * [ids] follow in their current order, ids of other groups are ignored. A wholesale rewrite knows nothing of
     * pins, so the pinned profiles return to the head (NormalizePins).
     */
    suspend fun setOrder(groupId: Long, ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val current = dao.getPositionsByGroup(groupId)
            val members = current.mapTo(HashSet(current.size * 2)) { it.id }
            val wanted = ids.distinct().filter { it in members }
            val wantedSet = wanted.toHashSet()
            writeOrder(current, wanted + current.mapNotNull { row -> row.id.takeIf { it !in wantedSet } })
            normalizePinsLocked(groupId)
        }
        GroupRepo.postReload(groupId)
    }

    /** Android extra: moves [profileIds] to the end of group [groupId] in the given order. */
    suspend fun moveToGroup(profileIds: List<Long>, groupId: Long) {
        if (GroupRepo.get(groupId) == null) return
        val sources = HashSet<Long>()
        val moved = ArrayList<Long>()
        SagerDatabase.instance.runInTransaction {
            var order = dao.nextOrder(groupId)
            for (chunk in profileIds.distinct().chunked(CHUNK)) {
                val movable = dao.idsOutsideGroup(chunk, groupId).toHashSet()
                if (movable.isEmpty()) continue
                sources.addAll(dao.groupsOf(movable.toList()))
                for (id in chunk) {
                    if (id !in movable) continue
                    dao.setGroup(id, groupId, order++)
                    moved.add(id)
                }
            }
            // A pin stays with its group: the moved profiles arrive unpinned.
            for (gid in sources) {
                compactLocked(gid)
                normalizePinsLocked(gid)
            }
        }
        for (gid in sources) GroupRepo.postReload(gid)
        GroupRepo.postReload(groupId)
        GroupSort.repositionLater(mapOf(groupId to moved), SortInput.MEMBERSHIP)
    }

    /** Renumbers the group's members 0..n-1 in their current order. */
    private fun compactLocked(groupId: Long) {
        dao.getPositionsByGroup(groupId).forEachIndexed { index, row ->
            if (row.userOrder != index.toLong()) dao.setUserOrder(row.id, index.toLong())
        }
    }

    /**
     * Inside the transaction that read [current] (the group's getPositionsByGroup): [ids], members of that group, get
     * positions 0..n-1 in that order. Only the rows whose stored position differs are written.
     */
    internal fun writeOrder(current: List<ProfilePosition>, ids: List<Long>) {
        val stored = HashMap<Long, Long>(current.size * 2)
        for (row in current) stored[row.id] = row.userOrder
        ids.forEachIndexed { index, id ->
            if (stored[id] != index.toLong()) dao.setUserOrder(id, index.toLong())
        }
    }

    // ------------------------------------------------------------------------------------------------ pins

    /**
     * Group::NormalizePins inside a transaction: the pins of profiles no longer in the group go and the rest head its
     * order again, after anything that rewrites the order wholesale or removes members. True when anything changed.
     */
    internal fun normalizePinsLocked(groupId: Long): Boolean {
        val stored = SagerDatabase.groupDao.pinnedJson(groupId) ?: return false
        if (stored == ProxyGroup.NO_PINS) return false
        val current = dao.getPositionsByGroup(groupId)
        val members = current.mapTo(HashSet(current.size * 2)) { it.id }
        val kept = ProxyGroup.parsePinned(stored).filter { it in members }
        var changed = writePinsLocked(groupId, kept)
        if (kept.indices.any { current[it].id != kept[it] }) {
            val keptSet = kept.toHashSet()
            writeOrder(current, kept + current.mapNotNull { row -> row.id.takeIf { it !in keptSet } })
            changed = true
        }
        return changed
    }

    private fun writePinsLocked(groupId: Long, ids: List<Long>): Boolean =
        SagerDatabase.groupDao.setPinnedJson(groupId, ProxyGroup.pinnedToJson(ids)) > 0

    /** The group's order with its pins normalized: [pins] are the head of [ids]. */
    private class PinnedOrder(val current: List<ProfilePosition>, val pins: List<Long>) {
        val ids: MutableList<Long> = current.mapTo(ArrayList(current.size)) { it.id }
    }

    private fun pinnedOrderLocked(groupId: Long): PinnedOrder {
        normalizePinsLocked(groupId)
        return PinnedOrder(dao.getPositionsByGroup(groupId), ProxyGroup.parsePinned(SagerDatabase.groupDao.pinnedJson(groupId)))
    }

    /** The repair GroupsRepo runs on load: [normalizePinsLocked] in its own transaction. */
    fun normalizePins(groupId: Long): Boolean =
        SagerDatabase.instance.runInTransaction(Callable { normalizePinsLocked(groupId) })

    /** Group::PinProfiles: the unpinned ones of [ids] join the end of the pinned block in list order; false when none did. */
    suspend fun pinProfiles(groupId: Long, ids: Collection<Long>): Boolean {
        val wanted = ids.toHashSet()
        val pinned = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val joining = ArrayList<Long>()
            val rest = ArrayList<Long>()
            for (id in order.ids.drop(order.pins.size)) (if (id in wanted) joining else rest).add(id)
            if (joining.isEmpty()) return@Callable false
            val pins = order.pins + joining
            writeOrder(order.current, pins + rest)
            writePinsLocked(groupId, pins)
            true
        })
        if (pinned) GroupRepo.postReload(groupId)
        return pinned
    }

    /**
     * Group::UnpinProfiles: the pinned ones of [ids] lead the unpinned rows until a kept sort places them
     * (on_menu_pin_triggered); returns them.
     */
    suspend fun unpinProfiles(groupId: Long, ids: Collection<Long>): List<Long> {
        val wanted = ids.toHashSet()
        val leaving = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val (out, kept) = order.pins.partition { it in wanted }
            if (out.isNotEmpty()) {
                writeOrder(order.current, kept + out + order.ids.drop(order.pins.size))
                writePinsLocked(groupId, kept)
            }
            out
        })
        if (leaving.isNotEmpty()) {
            GroupRepo.postReload(groupId)
            GroupSort.repositionLater(mapOf(groupId to leaving), SortInput.MEMBERSHIP)
        }
        return leaving
    }

    /**
     * Group::MoveProfile, the drop of a drag: [profileId] lands above or below [targetId] and is pinned exactly when
     * the target is. A row placed among the unpinned ones is a manual order, which stops a kept sort. False when
     * nothing moved.
     */
    suspend fun moveProfile(groupId: Long, profileId: Long, targetId: Long, below: Boolean): Boolean {
        var sortStopped = false
        val moved = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val ids = order.ids
            val from = ids.indexOf(profileId)
            val target = ids.indexOf(targetId)
            if (from < 0 || target < 0) return@Callable false
            val head = order.pins.size
            val wasPinned = from < head
            val toPinned = target < head
            var to = if (below) target + 1 else target
            if (from < to) to--
            if (to == from && wasPinned == toPinned) return@Callable false
            // Both blocks stay contiguous: `to` falls inside the pinned block exactly when the target was pinned.
            ids.add(to, ids.removeAt(from))
            writeOrder(order.current, ids)
            writePinsLocked(groupId, ids.take(head - (if (wasPinned) 1 else 0) + (if (toPinned) 1 else 0)))
            if (!toPinned) sortStopped = SagerDatabase.groupDao.setSort(groupId, GroupSortMethod.RAW.ordinal, false) > 0
            true
        })
        if (sortStopped) GroupRepo.postUpdate(groupId)
        if (moved) GroupRepo.postReload(groupId)
        return moved
    }

    /**
     * One step up ([step] < 0) or down in the stored order, the D-pad alternative to a drag (Group::SwapProfiles):
     * never across the pinned block's edge. A step among the unpinned rows is a manual order, which stops a kept sort.
     */
    suspend fun swapProfile(groupId: Long, profileId: Long, step: Int): Boolean {
        var sortStopped = false
        val moved = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val ids = order.ids
            val from = ids.indexOf(profileId)
            val to = from + if (step > 0) 1 else -1
            if (from < 0 || to !in ids.indices) return@Callable false
            val head = order.pins.size
            if ((from < head) != (to < head)) return@Callable false
            Collections.swap(ids, from, to)
            writeOrder(order.current, ids)
            if (from < head) {
                writePinsLocked(groupId, ids.take(head))
            } else {
                sortStopped = SagerDatabase.groupDao.setSort(groupId, GroupSortMethod.RAW.ordinal, false) > 0
            }
            true
        })
        if (sortStopped) GroupRepo.postUpdate(groupId)
        if (moved) GroupRepo.postReload(groupId)
        return moved
    }

    // ------------------------------------------------------------------------------------------------ test results

    /** Persists the test-result columns of [profile] (not its traffic or data). */
    fun saveTestResult(profile: ProxyEntity) {
        dao.updateTestResult(
            profile.id, profile.latency, profile.latencyAt, profile.dlSpeed, profile.ulSpeed,
            profile.testCountry, profile.ipOut, profile.testError,
        )
    }

    /** TestRunner::applyUrlResult; returns the updated profile, null when it no longer exists. */
    fun saveUrlTestResult(profileId: Long, latencyMs: Int, error: String, connectOnly: Boolean = false): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        profile.applyUrlResult(latencyMs, error, connectOnly)
        saveTestResult(profile)
        return profile
    }

    /** TestRunner::applyIpResult. */
    fun saveIpTestResult(profileId: Long, ip: String, country: String, error: String): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        profile.applyIpResult(ip, country, error)
        saveTestResult(profile)
        return profile
    }

    /** The speed-test result loop of TestRunner.cpp:614-648; [country] is an ISO code. */
    fun saveSpeedTestResult(
        profileId: Long,
        dl: String,
        ul: String,
        latencyMs: Int,
        country: String,
        error: String,
    ): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        profile.applySpeedResult(dl, ul, latencyMs, country, error)
        saveTestResult(profile)
        return profile
    }

    /** TestRunner::applyServiceResult: [document] is the `service_checks` column (ServiceChecks::ResultJson). */
    fun saveServiceResult(profileId: Long, document: String): Boolean = dao.updateServiceChecks(profileId, document) > 0

    fun clearTestResults(profileIds: List<Long>) {
        if (profileIds.isEmpty()) return
        SagerDatabase.instance.runInTransaction { profileIds.chunked(CHUNK).forEach { dao.clearTestResults(it) } }
        GroupSort.repositionLater(profileIds, SortInput.TEST_RESULT)
    }

    fun clearGroupTestResults(groupId: Long) {
        dao.clearGroupTestResults(groupId)
    }

    // ------------------------------------------------------------------------------------------------ listeners

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: List<TrafficData>) {
        if (data.isEmpty()) return
        iterator { onUpdated(data) }
    }

}
