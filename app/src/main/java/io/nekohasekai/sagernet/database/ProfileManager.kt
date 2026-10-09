package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.Outbound
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The profile repository (the desktop's ProfilesRepo plus the list edits of Group.cpp) over `profiles`. A group's
 * members are its rows ordered by `user_order`, kept dense (0..n-1 = the desktop's `profiles_json` index).
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

    /** The result of [batchDeleteProfiles]: [kept] holds the requested ids that were not deleted. */
    class DeleteOutcome(
        @JvmField val ok: Boolean,
        @JvmField val deleted: List<Long>,
        @JvmField val kept: List<Long>,
    )

    /** SQLite's bound-parameter limit is 999; id lists are split below it. */
    private const val CHUNK = 500

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
        return before == null || before.type != stored.type || before.name != stored.name ||
            before.outboundJson != stored.outboundJson
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
        profileIds.toList().chunked(CHUNK).forEach { dao.resetTraffic(it.toLongArray()) }
    }

    // ------------------------------------------------------------------------------------------------ delete

    /** Deletes one profile (the list's own delete, no running-profile rule) and closes the gap in its group. */
    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        if (dao.deleteById(profileId) == 0) return
        if (DataStore.selectedProxy == profileId) {
            DataStore.selectedProxy = 0L
        }
        compact(groupId)
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
        if (running > 0 && running in toDelete) {
            if (stopRunning) SagerNet.stopService() else toDelete.remove(running)
        }
        val groups = HashSet<Long>()
        val deleted = ArrayList<Long>()
        val ok = try {
            SagerDatabase.instance.runInTransaction {
                for (chunk in toDelete.chunked(CHUNK)) {
                    val rows = dao.getEntities(chunk)
                    rows.forEach { groups.add(it.groupId) }
                    dao.deleteByIds(chunk)
                    deleted.addAll(rows.map { it.id })
                }
                groups.forEach(::compactLocked)
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
        return DeleteOutcome(ok, deleted, requested.filter { it !in deletedSet })
    }

    // ------------------------------------------------------------------------------------------------ order

    /**
     * The group's list becomes [ids] (Group::profiles = ids): positions 0..n-1 in that order; members missing from
     * [ids] follow in their current order, ids of other groups are ignored.
     */
    suspend fun setOrder(groupId: Long, ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val current = dao.getIdsByGroup(groupId)
            val members = current.toHashSet()
            val wanted = ids.distinct().filter { it in members }
            val wantedSet = wanted.toHashSet()
            writeOrder(wanted + current.filter { it !in wantedSet })
        }
        GroupRepo.postReload(groupId)
    }

    /**
     * Group::EmplaceProfile (the desktop's drag and drop): the row at [from] is inserted after the row at [to], so
     * it lands at [to] when moving down and at [to] + 1 when moving up. False when an index is out of range.
     */
    suspend fun emplace(groupId: Long, from: Int, to: Int): Boolean {
        val moved = SagerDatabase.instance.runInTransaction(Callable {
            val ids = ArrayList(dao.getIdsByGroup(groupId))
            if (from !in ids.indices || to !in ids.indices) return@Callable false
            ids.add(to + 1, ids[from])
            if (from < to) ids.removeAt(from) else ids.removeAt(from + 1)
            writeOrder(ids)
            true
        })
        if (moved) GroupRepo.postReload(groupId)
        return moved
    }

    /** Android extra: moves [profileIds] to the end of group [groupId] in the given order. */
    suspend fun moveToGroup(profileIds: List<Long>, groupId: Long) {
        if (GroupRepo.get(groupId) == null) return
        val sources = HashSet<Long>()
        SagerDatabase.instance.runInTransaction {
            var order = dao.nextOrder(groupId)
            for (chunk in profileIds.distinct().chunked(CHUNK)) {
                val rows = dao.getEntities(chunk).associateBy { it.id }
                for (id in chunk) {
                    val row = rows[id] ?: continue
                    if (row.groupId == groupId) continue
                    sources.add(row.groupId)
                    dao.setGroup(id, groupId, order++)
                }
            }
            sources.forEach(::compactLocked)
        }
        for (gid in sources) GroupRepo.postReload(gid)
        GroupRepo.postReload(groupId)
    }

    /** Renumbers the group's members 0..n-1 in their current order. */
    fun compact(groupId: Long) {
        SagerDatabase.instance.runInTransaction { compactLocked(groupId) }
    }

    private fun compactLocked(groupId: Long) {
        writeOrder(dao.getIdsByGroup(groupId))
    }

    private fun writeOrder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> dao.setUserOrder(id, index.toLong()) }
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

    fun clearTestResults(profileIds: List<Long>) {
        profileIds.chunked(CHUNK).forEach { dao.clearTestResults(it) }
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
