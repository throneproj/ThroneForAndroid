package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.group.SubscriptionCountryVerdicts
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The group repository (the desktop's GroupsRepo, GroupsRepo.cpp): the `groups` table in tab order
 * (`display_order`), the current group (`current_group`) and the Default group of an empty table.
 * Listeners are in-process; writes of the other process arrive through Room's multi-instance invalidation.
 */
object GroupRepo {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)
        suspend fun groupRemoved(groupId: Long)

        /** The group's members changed (added, removed, reordered, sorted, cleared): reload them. */
        suspend fun groupUpdated(groupId: Long)

        /** The tab order changed. */
        suspend fun groupsReordered() {}
    }

    /** GroupsRepo's HopReference: group [groupId] uses profile [profileId] as its landing proxy when [landing], else as its front proxy. */
    class HopReference(
        @JvmField val groupId: Long,
        @JvmField val groupName: String,
        @JvmField val landing: Boolean,
        @JvmField val profileId: Long,
    )

    /** The most references a delete confirmation lists (HopReferenceWarning). */
    private const val HOPS_SHOWN = 5

    private val listeners = CopyOnWriteArrayList<Listener>()

    private val dao get() = SagerDatabase.groupDao

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private suspend fun notify(what: suspend Listener.() -> Unit) {
        for (listener in listeners) listener.what()
    }

    // ------------------------------------------------------------------------------------------------ reads

    /** Every group in tab order; an empty table first gets the Default group. */
    fun all(): List<ProxyGroup> {
        val groups = dao.allGroups()
        if (groups.isNotEmpty()) return groups
        ensureDefault()
        return dao.allGroups()
    }

    /** The group ids in tab order (GetGroupsTabOrder). */
    fun ids(): List<Long> {
        val ids = dao.allIds()
        if (ids.isNotEmpty()) return ids
        ensureDefault()
        return dao.allIds()
    }

    fun get(id: Long): ProxyGroup? = if (id > 0) dao.getById(id) else null

    /** current_group when it names a group, else the first group, which then becomes current. */
    fun currentId(): Long {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored > 0 && dao.getById(stored) != null) return stored
        val first = ids().first()
        setCurrent(first)
        return first
    }

    fun current(): ProxyGroup {
        val stored = SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore)
        if (stored > 0) dao.getById(stored)?.let { return it }
        val first = all().first()
        setCurrent(first.id)
        return first
    }

    fun setCurrent(id: Long) {
        val store = DataStore.configurationStore
        if (SettingsRegistry.CURRENT_GROUP.read(store) != id) SettingsRegistry.CURRENT_GROUP.write(store, id)
    }

    /** Configs.cpp:35-39: the "Default" group of an empty table. */
    fun ensureDefault() {
        dao.insertDefaultIfEmpty(SagerDatabase.defaultGroupName())
    }

    // ------------------------------------------------------------------------------------------------ writes

    /** GroupsRepo::AddGroup: a new id and the last tab (display_order = MAX + 1). */
    suspend fun add(group: ProxyGroup): ProxyGroup {
        SagerDatabase.instance.runInTransaction {
            group.id = 0L
            group.displayOrder = dao.nextDisplayOrder()
            group.id = dao.insert(group)
        }
        notify { groupAdd(group) }
        return group
    }

    /**
     * The stored pins and custom order stay: only their own DAO writes change them, and [group] may be an older copy.
     */
    suspend fun save(group: ProxyGroup) {
        SagerDatabase.instance.runInTransaction {
            dao.pinnedJson(group.id)?.let { group.pinnedJson = it }
            dao.getCustomOrderJson(group.id)?.let { group.customOrderJson = it }
            dao.update(group)
        }
        notify { groupUpdated(group) }
    }

    /**
     * GroupsRepo::DeleteGroup with the callers' rules: the last group is never deleted (returns false), a running
     * profile of the group is stopped first. The members go by the foreign key cascade, and other groups lose them as
     * front/landing proxy.
     */
    suspend fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids || ids.size <= 1) return@Callable null
            ids - id
        }) ?: return false
        val running = ProfileManager.runningProfileId()
        val stopping = running > 0 && ProfileManager.getProfile(running)?.groupId == id
        val members = ProfileManager.memberIds(id)
        val used = !stopping && ProfileManager.runningUsesOther(members)
        if (stopping) SagerNet.stopService()
        val name = dao.getById(id)?.displayName()
        dao.deleteById(id)
        SubscriptionCountryVerdicts.clear(id)
        // GroupService.cpp:160-164: the routing profiles its subscription provided go with it.
        if (RouteManager.deleteOwnedBy(id) > 0) {
            Logs.i("[Groups] $name: deleted the routing profile(s) its subscription provided")
        }
        if (SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore) == id) setCurrent(remaining.first())
        if (DataStore.selectedProxy > 0 && ProfileManager.getProfile(DataStore.selectedProxy) == null) {
            DataStore.selectedProxy = 0L
        }
        notify { groupRemoved(id) }
        clearHopReferences(members)
        if (used) ProfileManager.noteRestartNeeded(id)
        return true
    }

    // ------------------------------------------------------------------------------------------------ front / landing

    /** GroupsRepo::HopReferences: groups whose front or landing proxy is one of [profileIds]; [exceptGroup] (a group being deleted) is left out. */
    fun hopReferences(profileIds: Collection<Long>, exceptGroup: Long = -1L): List<HopReference> {
        if (profileIds.isEmpty()) return emptyList()
        val ids = profileIds.toHashSet()
        val refs = ArrayList<HopReference>()
        for (group in dao.allGroups()) {
            if (group.id == exceptGroup) continue
            if (group.frontProxyId > 0 && group.frontProxyId in ids) {
                refs.add(HopReference(group.id, group.displayName(), false, group.frontProxyId))
            }
            if (group.landingProxyId > 0 && group.landingProxyId in ids) {
                refs.add(HopReference(group.id, group.displayName(), true, group.landingProxyId))
            }
        }
        return refs
    }

    /**
     * GroupsRepo::ClearHopReferences: the front/landing proxies among [deletedProfileIds] become none, each logged; a
     * group left without one connects directly instead of building on a dangling id. Returns the cleared references.
     */
    suspend fun clearHopReferences(deletedProfileIds: Collection<Long>): List<HopReference> {
        if (deletedProfileIds.isEmpty()) return emptyList()
        val cleared = SagerDatabase.instance.runInTransaction(Callable {
            hopReferences(deletedProfileIds).filter { ref ->
                val changed = if (ref.landing) {
                    dao.clearLandingProxy(ref.groupId, ref.profileId)
                } else {
                    dao.clearFrontProxy(ref.groupId, ref.profileId)
                }
                changed > 0
            }
        })
        for (ref in cleared) {
            Logs.i("[Groups] ${ref.groupName}: ${if (ref.landing) "landing" else "front"} proxy removed (its profile was deleted)")
        }
        for (groupId in cleared.mapTo(LinkedHashSet()) { it.groupId }) postUpdate(groupId)
        return cleared
    }

    /**
     * HopReferenceWarning: for a delete confirmation, the groups that would lose one of [profileIds] as front/landing
     * proxy (a few, the rest counted), "" for none; [deletedGroup] is the group being deleted with them.
     */
    fun hopWarning(profileIds: Collection<Long>, deletedGroup: Long = -1L): String {
        val refs = hopReferences(profileIds, deletedGroup)
        if (refs.isEmpty()) return ""
        val text = StringBuilder("\n\n").append(app.getString(R.string.hop_refs_warning))
        for (ref in refs.take(HOPS_SHOWN)) text.append('\n').append(hopLine(ref))
        val more = refs.size - HOPS_SHOWN
        if (more > 0) text.append('\n').append(app.resources.getQuantityString(R.plurals.confirm_more_names, more, more))
        return text.toString()
    }

    /** GroupUpdater's hopNotice: the report lines for the [cleared] references, "" for none. */
    fun hopNotice(cleared: List<HopReference>): String {
        if (cleared.isEmpty()) return ""
        val text = StringBuilder("\n").append(app.getString(R.string.hop_refs_cleared))
        for (ref in cleared) text.append('\n').append(hopLine(ref))
        return text.toString()
    }

    private fun hopLine(ref: HopReference): String =
        app.getString(if (ref.landing) R.string.hop_ref_landing else R.string.hop_ref_front, ref.groupName)

    /** SetGroupsTabOrder: [ids] become the tabs 0..n-1; groups missing from [ids] follow in their current order. */
    suspend fun setDisplayOrder(ids: List<Long>) {
        SagerDatabase.instance.runInTransaction {
            val existing = dao.allIds()
            val ordered = ids.distinct().filter { it in existing } + existing.filter { it !in ids }
            ordered.forEachIndexed { index, groupId -> dao.setDisplayOrder(groupId, index.toLong()) }
        }
        notify { groupsReordered() }
    }

    /** Tells the listeners to reload the members of [groupId]. */
    suspend fun postReload(groupId: Long) {
        notify { groupUpdated(groupId) }
    }

    /** Tells the listeners that [group] changed (name, options...) without writing it. */
    suspend fun postUpdate(group: ProxyGroup) {
        notify { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(get(groupId) ?: return)
    }
}
