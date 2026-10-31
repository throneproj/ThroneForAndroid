package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
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

    suspend fun save(group: ProxyGroup) {
        dao.update(group)
        notify { groupUpdated(group) }
    }

    /**
     * GroupsRepo::DeleteGroup with the callers' rules: the last group is never deleted (returns false), a running
     * profile of the group is stopped first. The members go by the foreign key cascade.
     */
    suspend fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids || ids.size <= 1) return@Callable null
            ids - id
        }) ?: return false
        val running = ProfileManager.runningProfileId()
        if (running > 0 && ProfileManager.getProfile(running)?.groupId == id) SagerNet.stopService()
        val name = dao.getById(id)?.displayName()
        dao.deleteById(id)
        // GroupService.cpp:160-164: the routing profiles its subscription provided go with it.
        if (RouteManager.deleteOwnedBy(id) > 0) {
            Logs.i("[Groups] $name: deleted the routing profile(s) its subscription provided")
        }
        if (SettingsRegistry.CURRENT_GROUP.read(DataStore.configurationStore) == id) setCurrent(remaining.first())
        if (DataStore.selectedProxy > 0 && ProfileManager.getProfile(DataStore.selectedProxy) == null) {
            DataStore.selectedProxy = 0L
        }
        notify { groupRemoved(id) }
        return true
    }

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
