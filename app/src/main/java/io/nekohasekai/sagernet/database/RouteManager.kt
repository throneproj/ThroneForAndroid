package io.nekohasekai.sagernet.database

import androidx.room.InvalidationTracker
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import io.nekohasekai.sagernet.route.RouteDns
import io.nekohasekai.sagernet.route.RouteProfile
import io.nekohasekai.sagernet.route.RuleSetCatalog
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList

/** The route profile repository (the desktop's RoutesRepo) over `route_profiles` / `route_rules`. */
object RouteManager {

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private var observing = false

    private val dao get() = SagerDatabase.routeDao

    /** Every profile in id order; an empty table is given the Default profile first. */
    fun all(): List<RouteProfile> {
        val profiles = load()
        if (profiles.isNotEmpty()) return profiles
        ensureDefault()
        return load()
    }

    fun get(id: Long): RouteProfile? = SagerDatabase.instance.runInTransaction(Callable {
        dao.getProfile(id)?.toModel(dao.rulesOf(id))
    })

    /**
     * GetCustomDns (RoutesRepo.cpp): the profile's ActiveDns without loading its rules; null when it follows the
     * global DNS settings or does not exist.
     */
    fun customDns(id: Long): RouteDns? = RouteProfileEntity.dnsFromJson(dao.customDnsJson(id))

    /**
     * The profile named by current_route_id; a missing or raw one falls back to the first profile Android can use
     * (a new Default one when only raw profiles exist) and fixes the setting.
     */
    fun current(): RouteProfile {
        get(DataStore.currentRouteId)?.takeIf { !it.is_raw }?.let { return it }
        val first = usable().firstOrNull() ?: RouteProfile.defaultProfile().also { save(it) }
        DataStore.currentRouteId = first.id
        return first
    }

    /** Every profile but the desktop's raw ones, which Android keeps read-only and never uses. */
    fun usable(): List<RouteProfile> = all().filterNot { it.is_raw }

    fun isUsable(id: Long): Boolean = id > 0L && dao.isUsable(id)

    /**
     * ResolveRouteId (RunningProfiles.cpp): a group override naming a profile Android can use, else the global one.
     * Raw profiles count as gone, like for [current].
     */
    fun resolveRouteId(groupRouteId: Long): Long =
        if (isUsable(groupRouteId)) groupRouteId else globalRouteId()

    /** RouteIdFor: the routing profile a profile of group [gid] is built with. */
    fun routeIdFor(gid: Long): Long = resolveRouteId(groupRouteId(gid))

    /** The profile [routeIdFor] names. */
    fun routeFor(gid: Long): RouteProfile =
        groupRouteId(gid).takeIf { it > 0L }?.let { get(it) }?.takeIf { !it.is_raw } ?: current()

    /** RunningRouteId: the routing profile the running config was built with; 0 when stopped or for a custom full config. */
    fun runningRouteId(): Long = if (DataStore.serviceState.started) DataStore.runningRouteId else 0L

    /**
     * ActiveRouteId: the routing profile in effect, the running config's else the global one. For "which route
     * applies"; the user's global choice stays current_route_id.
     */
    fun activeRouteId(): Long = runningRouteId().takeIf { it > 0L } ?: DataStore.currentRouteId

    /**
     * ConnectionModes.cpp:220-226: the group of the running profile when its own routing profile is the one running,
     * so switching the global profile leaves the running config as it is; null otherwise.
     */
    fun groupKeepingRunningRoute(): ProxyGroup? {
        val running = runningRouteId().takeIf { it > 0L } ?: return null
        val profileId = ProfileManager.runningProfileId().takeIf { it > 0L } ?: return null
        val gid = SagerDatabase.proxyDao.getById(profileId)?.groupId ?: return null
        val group = SagerDatabase.groupDao.getById(gid) ?: return null
        return group.takeIf { it.routeId == running && isUsable(running) }
    }

    /** RoutesRepo::OwnedBy: the profiles the subscription of group [gid] keeps updated, in id order. */
    fun ownedBy(gid: Long): List<Long> = if (gid > 0L) dao.ownedBy(gid) else emptyList()

    /**
     * GroupService.cpp:160-164: deletes the profiles group [gid] owns, current_route_id moving off them; the number
     * deleted. Unlike [delete], it may take the last profile, [current] then creates a new Default one.
     */
    fun deleteOwnedBy(gid: Long): Int {
        val ids = ownedBy(gid)
        if (ids.isEmpty()) return 0
        SagerDatabase.instance.runInTransaction {
            for (id in ids) dao.deleteProfile(id)
            forgetDeleted(ids.toSet(), emptySet())
        }
        if (DataStore.currentRouteId in ids) current()
        return ids.size
    }

    private fun groupRouteId(gid: Long): Long =
        (if (gid > 0L) SagerDatabase.groupDao.getById(gid)?.routeId else null) ?: -1L

    private fun globalRouteId(): Long = DataStore.currentRouteId.takeIf { isUsable(it) } ?: current().id

    /**
     * RoutesRepo::forgetDeleted: groups routed by a deleted profile follow the global one again, and the groups that
     * owned one stop importing it.
     */
    private fun forgetDeleted(deleted: Set<Long>, owners: Collection<Long>) {
        for (group in SagerDatabase.groupDao.allGroups()) {
            if (group.routeId in deleted) {
                SagerDatabase.groupDao.setRouteId(group.id, -1L)
                Logs.i("[Groups] ${group.displayName()}: routing profile reset to Default (its profile was deleted)")
            }
            if (group.id in owners && group.importSubRoute) {
                SagerDatabase.groupDao.stopRouteImport(group.id)
                Logs.i("[Groups] ${group.displayName()}: stopped importing the subscription's routing profile (it was deleted)")
            }
        }
    }

    /** Inserts when [p] has no id (and assigns it), else upserts the row; the rules are replaced in list order. */
    fun save(p: RouteProfile): Long {
        SagerDatabase.instance.runInTransaction {
            val entity = RouteProfileEntity.of(p)
            if (p.id <= 0L) {
                entity.id = 0L
                p.id = dao.insertProfile(entity)
            } else if (dao.updateProfile(entity) == 0) {
                dao.insertProfile(entity)
            }
            dao.deleteRules(p.id)
            if (p.rules.isNotEmpty()) {
                dao.insertRules(p.rules.mapIndexed { index, rule -> RouteRuleEntity.of(p.id, index, rule) })
            }
        }
        return p.id
    }

    /**
     * Refuses to delete the last profile; deleting the current one makes the first remaining profile current. Groups
     * routed by it follow the global profile again, and the group owning it stops importing (RoutesRepo::forgetDeleted).
     */
    fun delete(id: Long): Boolean {
        val remaining = SagerDatabase.instance.runInTransaction(Callable {
            val ids = dao.allIds()
            if (id !in ids || ids.size <= 1) return@Callable null
            val owners = dao.sourceGroups(listOf(id))
            dao.deleteProfile(id)
            forgetDeleted(setOf(id), owners)
            ids - id
        }) ?: return false
        if (DataStore.currentRouteId == id) DataStore.currentRouteId = remaining.first()
        return true
    }

    fun ensureDefault() {
        SagerDatabase.instance.runInTransaction {
            if (dao.count() == 0L) save(RouteProfile.defaultProfile())
        }
    }

    /**
     * [l] runs on a background thread after any change to the route tables, including changes made by the
     * other process (the database has multi-instance invalidation).
     */
    fun addListener(l: () -> Unit) {
        listeners.add(l)
        observe()
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    /** The name of a server profile as shares carry it (the desktop's DisplayName). */
    fun profileName(id: Long): String? = ProfileManager.getProfile(id)?.displayName()

    /**
     * A share-import resolver for rule outbound names: the first profile whose name matches exactly
     * (ProfilesRepo::GetProfileByName), then one whose display name does, each by lowest id. The stored name is the
     * outbound's, so only the profiles without one are parsed, plus the rare rows a stored name cannot speak for; each
     * name is looked up once.
     */
    fun profileIdResolver(): (String) -> Long? {
        val dao = SagerDatabase.proxyDao
        val unnamed by lazy { dao.getUnnamed() }
        // A row whose type or data does not parse has no outbound name and shows its type, whatever name it stores.
        val rows by lazy { SagerDatabase.groupDao.allGroups().flatMap { dao.getNamesByGroup(it.id) } }
        fun firstOf(ids: List<Long>, match: (ProxyEntity) -> Boolean): Long? =
            ids.chunked(ProfileManager.CHUNK).flatMap { dao.getEntities(it) }.filter(match).minOfOrNull { it.id }

        fun resolve(name: String): Long? {
            val named = dao.idByName(name)?.let { id ->
                if (dao.getById(id)?.outbound?.name == name) id
                else firstOf(rows.filter { it.name == name }.map { it.id }) { it.outbound.name == name }
            }
            listOfNotNull(named, unnamed.firstOrNull { it.outbound.name == name }?.id).minOrNull()?.let { return it }
            val shownType = rows.filter { !it.name.isNullOrEmpty() && it.type == name }.map { it.id }
            return listOfNotNull(
                unnamed.firstOrNull { it.displayName() == name }?.id,
                firstOf(shownType) { it.displayName() == name },
            ).minOrNull()
        }

        val resolved = HashMap<String, Long?>()
        return { name ->
            when {
                name.isBlank() -> null
                name in resolved -> resolved[name]
                else -> resolve(name).also { resolved[name] = it }
            }
        }
    }

    @Volatile
    private var catalogCache: Pair<Long, RuleSetCatalog>? = null

    /** The rule-set list: the runtime copy of srslist.h, else the bundled asset, else empty; cached per source. */
    fun catalog(): RuleSetCatalog {
        val file = RouteRepo.srsListFile()
        val stamp = if (file.isFile) file.lastModified() else ASSET_STAMP
        catalogCache?.let { if (it.first == stamp) return it.second }
        var catalog = if (stamp != ASSET_STAMP) readCatalog(file) else null
        if (catalog == null || catalog.size == 0) {
            catalog = RouteRepo.readAsset(RouteRepo.SRS_LIST)?.let { RuleSetCatalog.parseSrsList(it) }
                ?: RuleSetCatalog(emptyList())
        }
        catalogCache = stamp to catalog
        return catalog
    }

    fun invalidateCatalog() {
        catalogCache = null
    }

    private const val ASSET_STAMP = -1L

    private fun readCatalog(file: File): RuleSetCatalog? = try {
        RuleSetCatalog.parseSrsList(file.readText())
    } catch (e: Exception) {
        Logs.w("read ${file.name}", e)
        null
    }

    private fun load(): List<RouteProfile> = SagerDatabase.instance.runInTransaction(Callable {
        val rules = dao.allRules().groupBy { it.routeProfileId }
        dao.allProfiles().map { it.toModel(rules[it.id].orEmpty()) }
    })

    private fun observe() {
        synchronized(this) {
            if (observing) return
            observing = true
        }
        runOnIoDispatcher {
            SagerDatabase.instance.invalidationTracker.addObserver(
                object : InvalidationTracker.Observer(arrayOf(RouteProfileEntity.TABLE, RouteRuleEntity.TABLE)) {
                    override fun onInvalidated(tables: Set<String>) {
                        for (l in listeners) l()
                    }
                }
            )
        }
    }
}
