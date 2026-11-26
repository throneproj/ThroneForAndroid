package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.Outbound
import java.io.IOException
import java.sql.SQLException
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
     * profile this delete stopped (allow_stopping_active_profile), [clearedHops] the groups that lost a deleted profile
     * as front/landing proxy.
     */
    class DeleteOutcome(
        @JvmField val ok: Boolean,
        @JvmField val deleted: List<Long>,
        @JvmField val kept: List<Long>,
        @JvmField val stopped: ProxyEntity? = null,
        @JvmField val clearedHops: List<GroupRepo.HopReference> = emptyList(),
    )

    /** Why a group takes no imported or moved profiles: ImportService::CheckTarget's refusals and MoveToGroup's [BUSY]. */
    enum class TargetRefusal { NOT_FOUND, ARCHIVED, SUBSCRIPTION, BUSY }

    /** The result of [moveToGroup]: how many profiles moved, or why none did. */
    class MoveOutcome(@JvmField val moved: Int, @JvmField val refusal: TargetRefusal? = null)

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

    /**
     * Whether a change to [profileIds] leaves the running config stale: it uses one of them besides the running
     * profile itself, which a delete either stops or keeps. Read before the change, which may stop the service.
     */
    internal fun runningUsesOther(profileIds: Collection<Long>): Boolean {
        val running = runningProfileId()
        return running > 0L && profileIds.any { it != running && runningUses(it) }
    }

    /**
     * ProfileService's noteRestartNeeded for a change made in the main process: the window's reload hint. The deletes of
     * :bg (subscription refreshes, test clean-ups) give none, as GroupUpdater's do not.
     */
    internal fun noteRestartNeeded(groupId: Long) {
        if (!SagerNet.application.isBgProcess) SubscriptionClient.reloadNeeded(groupId)
    }

    /**
     * ImportService::CheckTarget: null when group [groupId] takes imported or moved profiles. An archived group takes
     * none, nor does a subscription, whose next update would delete them.
     */
    fun checkTarget(groupId: Long): TargetRefusal? {
        val group = GroupRepo.get(groupId) ?: return TargetRefusal.NOT_FOUND
        return when {
            group.archive -> TargetRefusal.ARCHIVED
            group.isSubscription -> TargetRefusal.SUBSCRIPTION
            else -> null
        }
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

    /**
     * Deletes one profile (the list's own delete, no running-profile rule) and closes the gap in its group; groups
     * lose it as front/landing proxy, and one the running config uses gives the reload hint (ProfileService::Delete).
     */
    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        val used = runningUsesOther(listOf(profileId))
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
        GroupRepo.clearHopReferences(listOf(profileId))
        if (used) noteRestartNeeded(groupId)
    }

    /**
     * ProfilesRepo::BatchDeleteProfiles through GroupUpdater's deleteProfiles: when the running profile is among
     * [profileIds] the service is stopped if [stopRunning] (allow_stopping_active_profile), otherwise that profile is
     * kept. Each affected group is compacted and reloaded once, and groups lose the deleted ones as front/landing proxy
     * (ClearHopReferences). A delete that leaves the service running with a config built from a deleted profile gives
     * the reload hint ([noteRestartNeeded]).
     */
    suspend fun batchDeleteProfiles(
        profileIds: List<Long>,
        stopRunning: Boolean = DataStore.allowStoppingActiveProfile,
    ): DeleteOutcome {
        val requested = profileIds.distinct()
        if (requested.isEmpty()) return DeleteOutcome(true, emptyList(), emptyList())
        val toDelete = ArrayList(requested)
        val running = runningProfileId()
        val used = requested.filter { runningUsesOther(listOf(it)) }
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
        val clearedHops = GroupRepo.clearHopReferences(deleted)
        val deletedSet = deleted.toHashSet()
        if (stopping == null && used.any { it in deletedSet }) noteRestartNeeded(-1L)
        return DeleteOutcome(
            ok, deleted, requested.filter { it !in deletedSet }, stopping?.takeIf { ok && it.id in deletedSet }, clearedHops,
        )
    }

    // ------------------------------------------------------------------------------------------------ order

    /**
     * The group's list becomes [ids] (Group::profiles = ids): positions 0..n-1 in that order; members missing from
     * [ids] follow in their current order, ids of other groups are ignored. An unsorted group keeps its manual
     * arrangement (custom_order), the members it lacks following it in that order. A wholesale rewrite knows nothing
     * of pins, so the pinned profiles return to the head (NormalizePins).
     */
    suspend fun setOrder(groupId: Long, ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val current = dao.getPositionsByGroup(groupId)
            val members = current.mapTo(HashSet(current.size * 2)) { it.id }
            val wanted = ids.distinct().filter { it in members }
            val wantedSet = wanted.toHashSet()
            var order = wanted + current.mapNotNull { row -> row.id.takeIf { it !in wantedSet } }
            val group = SagerDatabase.groupDao.getById(groupId)
            if (group != null && !group.keepsSorted()) order = orderedMembers(group.customOrder, order)
            writeOrder(current, order)
            normalizePinsLocked(groupId)
        }
        GroupRepo.postReload(groupId)
    }

    /**
     * Group::RestoreOriginalOrder: the arrangement a kept sort saved comes back, the members that joined since
     * following it by id, and the group is unsorted again. False when the group is gone.
     */
    suspend fun restoreOriginalOrder(groupId: Long): Boolean {
        val restored = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val group = SagerDatabase.groupDao.getById(groupId) ?: return@Callable false
            val remaining = order.ids.toHashSet()
            val arranged = ArrayList<Long>(remaining.size)
            for (id in group.customOrder) if (remaining.remove(id)) arranged.add(id)
            arranged.addAll(remaining.sorted())
            SagerDatabase.groupDao.setSort(groupId, GroupSortMethod.RAW.ordinal, false)
            SagerDatabase.groupDao.setCustomOrderJson(groupId, ProxyGroup.customOrderToJson(arranged))
            val pins = order.pins.toHashSet()
            writeOrder(order.current, order.pins + arranged.filter { it !in pins })
            true
        })
        if (restored) {
            GroupRepo.postUpdate(groupId)
            GroupRepo.postReload(groupId)
        }
        return restored
    }

    /** orderedMembers (Group.cpp): each of [members] once, those in [preferred] first in its order, then the rest. */
    internal fun orderedMembers(preferred: List<Long>, members: List<Long>): List<Long> {
        val remaining = members.toHashSet()
        val order = ArrayList<Long>(remaining.size)
        for (id in preferred) if (remaining.remove(id)) order.add(id)
        for (id in members) if (remaining.remove(id)) order.add(id)
        return order
    }

    /**
     * Group::Arrangement inside a transaction, for [ids], the group's list order with [pins] pinned rows heading it:
     * an unsorted group's custom_order with the members it lacks following in list order (the list shows it below
     * the pins), or where a kept sort puts every member, the pinned ones included.
     */
    private fun arrangementLocked(group: ProxyGroup, ids: List<Long>, pins: Int): List<Long> = if (group.keepsSorted()) {
        GroupSort.sortedArrangement(group, ids, pins)
    } else {
        orderedMembers(group.customOrder, ids)
    }

    /** Group::SetSort starting a kept sort, inside its transaction: the arrangement the sort replaces is kept. */
    internal fun saveArrangementLocked(group: ProxyGroup) {
        val arranged = orderedMembers(group.customOrder, memberIds(group.id))
        SagerDatabase.groupDao.setCustomOrderJson(group.id, ProxyGroup.customOrderToJson(arranged))
    }

    /**
     * Group::SetSort ending a kept sort, inside its transaction: the list shows the arrangement below the pins again.
     * True when the order moved.
     */
    internal fun arrangeLocked(group: ProxyGroup): Boolean {
        val order = pinnedOrderLocked(group.id)
        val pins = order.pins.toHashSet()
        val arranged = order.pins + orderedMembers(group.customOrder, order.ids).filter { it !in pins }
        if (arranged == order.ids) return false
        writeOrder(order.current, arranged)
        return true
    }

    /**
     * ProfileService::MoveToGroup: [profileIds] to the end of group [groupId] in the given order, refused for a target
     * [checkTarget] refuses. Refused too while the subscription of a group they leave is [updating] (queued or running):
     * the refresh rewrites its group from a snapshot, which would bring a moved profile back or delete it. Moving a
     * profile the running config uses gives the reload hint, as that config was built with the old group's proxies,
     * routing and endpoint.
     */
    suspend fun moveToGroup(profileIds: List<Long>, groupId: Long, updating: Collection<Long>): MoveOutcome {
        checkTarget(groupId)?.let { return MoveOutcome(0, it) }
        val busy = updating.toHashSet()
        val sources = HashSet<Long>()
        val moved = ArrayList<Long>()
        val refusal = SagerDatabase.instance.runInTransaction(Callable<TargetRefusal?> {
            val movable = ArrayList<Long>()
            for (chunk in profileIds.distinct().chunked(CHUNK)) {
                val outside = dao.idsOutsideGroup(chunk, groupId).toHashSet()
                chunk.filterTo(movable) { it in outside }
            }
            for (chunk in movable.chunked(CHUNK)) sources.addAll(dao.groupsOf(chunk))
            if (sources.any { it in busy }) return@Callable TargetRefusal.BUSY
            var order = dao.nextOrder(groupId)
            for (id in movable) {
                dao.setGroup(id, groupId, order++)
                moved.add(id)
            }
            // A pin stays with its group: the moved profiles arrive unpinned.
            for (gid in sources) {
                compactLocked(gid)
                normalizePinsLocked(gid)
            }
            null
        })
        if (refusal != null) return MoveOutcome(0, refusal)
        if (moved.isEmpty()) return MoveOutcome(0)
        for (gid in sources) GroupRepo.postReload(gid)
        GroupRepo.postReload(groupId)
        GroupSort.repositionLater(mapOf(groupId to moved), SortInput.MEMBERSHIP)
        if (moved.any { runningUses(it) }) noteRestartNeeded(groupId)
        return MoveOutcome(moved.size)
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
     * Group::NormalizePins inside a transaction: the pins and the custom order of profiles no longer in the group go
     * and the pins head its order again, after anything that rewrites the order wholesale or removes members. True
     * when anything changed.
     */
    internal fun normalizePinsLocked(groupId: Long): Boolean {
        val groupDao = SagerDatabase.groupDao
        val stored = groupDao.pinnedJson(groupId) ?: return false
        val arranged = groupDao.getCustomOrderJson(groupId) ?: ProxyGroup.NO_PINS
        if (stored == ProxyGroup.NO_PINS && arranged == ProxyGroup.NO_PINS) return false
        val current = dao.getPositionsByGroup(groupId)
        val members = current.mapTo(HashSet(current.size * 2)) { it.id }
        var changed = false
        if (arranged != ProxyGroup.NO_PINS) {
            val order = ProxyGroup.parsePinned(arranged).filter { it in members }
            changed = groupDao.setCustomOrderJson(groupId, ProxyGroup.customOrderToJson(order)) > 0
        }
        if (stored == ProxyGroup.NO_PINS) return changed
        val kept = ProxyGroup.parsePinned(stored).filter { it in members }
        if (writePinsLocked(groupId, kept)) changed = true
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

    /**
     * Group::PinProfiles: the unpinned ones of [ids] join the end of the pinned block in list order, keeping their
     * place in the arrangement for when they are unpinned; false when none did.
     */
    suspend fun pinProfiles(groupId: Long, ids: Collection<Long>): Boolean {
        val wanted = ids.toHashSet()
        val pinned = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val joining = ArrayList<Long>()
            val rest = ArrayList<Long>()
            for (id in order.ids.drop(order.pins.size)) (if (id in wanted) joining else rest).add(id)
            if (joining.isEmpty()) return@Callable false
            // The list order loses their place once they head it: an unsorted group keeps it in its arrangement.
            val group = SagerDatabase.groupDao.getById(groupId)
            if (group != null && !group.keepsSorted()) {
                val arranged = orderedMembers(group.customOrder, order.ids)
                SagerDatabase.groupDao.setCustomOrderJson(groupId, ProxyGroup.customOrderToJson(arranged))
            }
            val pins = order.pins + joining
            writeOrder(order.current, pins + rest)
            writePinsLocked(groupId, pins)
            true
        })
        if (pinned) GroupRepo.postReload(groupId)
        return pinned
    }

    /**
     * Group::UnpinProfiles: the pinned ones of [ids] go back to their place in the arrangement; under a kept sort
     * they lead the unpinned rows until the sort places them (on_menu_pin_triggered's ResortLater). Returns them.
     */
    suspend fun unpinProfiles(groupId: Long, ids: Collection<Long>): List<Long> {
        val wanted = ids.toHashSet()
        var sorted = false
        val leaving = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val (out, kept) = order.pins.partition { it in wanted }
            if (out.isEmpty()) return@Callable out
            val group = SagerDatabase.groupDao.getById(groupId)
            sorted = group?.keepsSorted() == true
            val rest = if (group == null || sorted) {
                out + order.ids.drop(order.pins.size)
            } else {
                val keptSet = kept.toHashSet()
                orderedMembers(group.customOrder, order.ids).filter { it !in keptSet }
            }
            writeOrder(order.current, kept + rest)
            writePinsLocked(groupId, kept)
            out
        })
        if (leaving.isNotEmpty()) {
            GroupRepo.postReload(groupId)
            if (sorted) GroupSort.resortLater(groupId)
        }
        return leaving
    }

    /**
     * Group::MoveProfile, the drop of a drag: [profileId] lands above or below [targetId] and is pinned exactly when
     * the target is. A row placed among the unpinned ones becomes the manual arrangement, which stops a kept sort
     * (the caller asks first). False when nothing moved.
     */
    suspend fun moveProfile(groupId: Long, profileId: Long, targetId: Long, below: Boolean): Boolean {
        var sortChanged = false
        val moved = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val from = order.ids.indexOf(profileId)
            val target = order.ids.indexOf(targetId)
            if (from < 0 || target < 0) return@Callable false
            val head = order.pins.size
            val toPinned = target < head
            var to = if (below) target + 1 else target
            if (from < to) to--
            if (to == from && (from < head) == toPinned) return@Callable false
            sortChanged = moveLocked(groupId, order, from, to, toPinned)
            true
        })
        if (sortChanged) GroupRepo.postUpdate(groupId)
        if (moved) GroupRepo.postReload(groupId)
        return moved
    }

    /**
     * One step up ([step] < 0) or down in the stored order, the D-pad alternative to a drag with its rules: never
     * across the pinned block's edge, and a step among the unpinned rows stops a kept sort (the caller asks first).
     */
    suspend fun swapProfile(groupId: Long, profileId: Long, step: Int): Boolean {
        var sortChanged = false
        val moved = SagerDatabase.instance.runInTransaction(Callable {
            val order = pinnedOrderLocked(groupId)
            val from = order.ids.indexOf(profileId)
            val to = from + if (step > 0) 1 else -1
            if (from < 0 || to !in order.ids.indices) return@Callable false
            val head = order.pins.size
            if ((from < head) != (to < head)) return@Callable false
            sortChanged = moveLocked(groupId, order, from, to, from < head)
            true
        })
        if (sortChanged) GroupRepo.postUpdate(groupId)
        if (moved) GroupRepo.postReload(groupId)
        return moved
    }

    /**
     * Group::MoveProfile inside a transaction: the row at [from] of the list order moves to [to], pinned when
     * [toPinned]; `to` must fall inside the pinned block exactly then, so both blocks stay contiguous. Unless a kept
     * sort goes on placing the unpinned rows (a move inside the pinned block), the new order fills the unpinned slots
     * of the arrangement, which becomes custom_order, and the group is unsorted. True when its sort changed.
     */
    private fun moveLocked(groupId: Long, order: PinnedOrder, from: Int, to: Int, toPinned: Boolean): Boolean {
        val group = SagerDatabase.groupDao.getById(groupId) ?: return false
        val ids = order.ids
        val head = order.pins.size
        val arranged = if (group.keepsSorted() && toPinned) {
            null
        } else {
            arrangementLocked(group, ids.toList(), head).toMutableList()
        }
        ids.add(to, ids.removeAt(from))
        val pinCount = head - (if (from < head) 1 else 0) + (if (toPinned) 1 else 0)
        val pins = ids.take(pinCount)
        writeOrder(order.current, ids)
        writePinsLocked(groupId, pins)
        if (arranged == null) return false
        // Both lists hold every member once, so the unpinned slots match the unpinned rows one for one.
        val pinSet = pins.toHashSet()
        var next = pinCount
        for (i in arranged.indices) if (arranged[i] !in pinSet) arranged[i] = ids[next++]
        SagerDatabase.groupDao.setCustomOrderJson(groupId, ProxyGroup.customOrderToJson(arranged))
        return SagerDatabase.groupDao.setSort(groupId, GroupSortMethod.RAW.ordinal, false) > 0
    }

    // ------------------------------------------------------------------------------------------------ test results

    /** Persists the test-result columns of [profile] (not its traffic or data). */
    fun saveTestResult(profile: ProxyEntity) {
        dao.updateTestResult(
            profile.id, profile.latency, profile.latencyAt, profile.dlSpeed, profile.ulSpeed,
            profile.testCountry, profile.ipOut, profile.testError,
        )
    }

    /**
     * TestRunner::applyUrlResult; returns the updated profile, null when it no longer exists or the test was aborted
     * (nothing saved).
     */
    fun saveUrlTestResult(profileId: Long, latencyMs: Int, error: String, connectOnly: Boolean = false): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        if (!profile.applyUrlResult(latencyMs, error, connectOnly)) return null
        saveTestResult(profile)
        return profile
    }

    /** TestRunner::applyIpResult; null as for [saveUrlTestResult]. */
    fun saveIpTestResult(profileId: Long, ip: String, country: String, error: String): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        if (!profile.applyIpResult(ip, country, error)) return null
        saveTestResult(profile)
        return profile
    }

    /** The speed-test result loop of TestRunner::runSpeedProbe; null as for [saveUrlTestResult]. */
    fun saveSpeedTestResult(profileId: Long, dl: String, ul: String, error: String): ProxyEntity? {
        val profile = getProfile(profileId) ?: return null
        if (!profile.applySpeedResult(dl, ul, error)) return null
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

    /** The results a group's Clear results empties, one kind at a time (ProfileService::ClearTestResults' TestKind). */
    enum class ResultKind { LATENCY, EXIT_IP, SPEED, SERVICES }

    /**
     * ProfileService::ClearTestResults over group [groupId]: the results of [kinds], every result when null. The
     * error text stays while a failed result that is kept may be the one it explains.
     */
    fun clearGroupTestResults(groupId: Long, kinds: Collection<ResultKind>? = null) {
        if (kinds == null) {
            dao.clearGroupTestResults(groupId)
            return
        }
        SagerDatabase.instance.runInTransaction {
            for (kind in kinds.toSet()) {
                when (kind) {
                    ResultKind.LATENCY -> dao.clearGroupLatency(groupId)
                    ResultKind.EXIT_IP -> dao.clearGroupExitIp(groupId)
                    ResultKind.SPEED -> dao.clearGroupSpeed(groupId)
                    ResultKind.SERVICES -> dao.clearGroupServiceChecks(groupId)
                }
            }
        }
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
