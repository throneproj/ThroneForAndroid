package io.nekohasekai.sagernet.database.backup

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RouteManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.database.backup.DesktopSchema.ENTITY_IDS
import io.nekohasekai.sagernet.database.backup.DesktopSchema.GROUPS
import io.nekohasekai.sagernet.database.backup.DesktopSchema.GROUPS_ORDER
import io.nekohasekai.sagernet.database.backup.DesktopSchema.IP_LISTS
import io.nekohasekai.sagernet.database.backup.DesktopSchema.IP_LIST_ENTRIES
import io.nekohasekai.sagernet.database.backup.DesktopSchema.IP_SCANS
import io.nekohasekai.sagernet.database.backup.DesktopSchema.MARKERS
import io.nekohasekai.sagernet.database.backup.DesktopSchema.PROFILES
import io.nekohasekai.sagernet.database.backup.DesktopSchema.ROUTE_PROFILES
import io.nekohasekai.sagernet.database.backup.DesktopSchema.ROUTE_RULES
import io.nekohasekai.sagernet.database.backup.DesktopSchema.SETTINGS
import io.nekohasekai.sagernet.group.RemoteRouteUpdater
import io.nekohasekai.sagernet.group.SubscriptionScheduler
import io.nekohasekai.sagernet.scan.EndpointSources
import io.nekohasekai.sagernet.scan.IpListUpdater
import io.nekohasekai.sagernet.scan.ScannerStartup
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues
import io.nekohasekai.sagernet.route.RouteProfile
import java.io.File
import java.io.InputStream

/**
 * Restores any `.thrbackup`, desktop- or Android-made (R10 §8.8, the desktop's restoreSelective, Database.cpp:339-398):
 * every chosen part is read and checked first, then written in one SagerDatabase transaction that replaces the part's
 * tables with ids kept as they are, raises the id counters to the backup's `entity_ids`, merges the settings (§8.3)
 * and repairs the selection. Nothing changes when anything fails before the commit.
 */
object BackupRestore {

    /** The parts to restore. */
    data class Choice(val profiles: Boolean, val routes: Boolean, val settings: Boolean, val ipLists: Boolean = false) {
        fun any(): Boolean = profiles || routes || settings || ipLists
    }

    class RestoreException(message: String) : Exception(message)

    /** A parsed backup; its database entry waits in a temporary file until [discard] or [restore]. */
    class Loaded(val contents: ThrBackup.Contents, internal val database: File) {
        fun discard() = BackupTemp.delete(database)
    }

    /** Parses a container from [input], streaming the database entry into a temporary file. */
    fun load(input: InputStream): Loaded {
        BackupTemp.cleanStale()
        val file = BackupTemp.newFile(BackupTemp.RESTORE)
        try {
            return Loaded(ThrBackup.read(input, file), file)
        } catch (e: Throwable) {
            BackupTemp.delete(file)
            throw e
        }
    }

    /** The desktop's available parts: OTP, the desktop's tray icons and older Android icon packs are never restored here. */
    fun available(contents: ThrBackup.Contents): Choice = Choice(
        profiles = contents.parts.profiles,
        routes = contents.parts.routes,
        settings = contents.parts.settings,
        ipLists = contents.parts.ipLists,
    )

    /** Restores [choice] from [backup] and discards it; returns the warnings to show. */
    fun restore(backup: Loaded, choice: Choice): List<String> {
        try {
            val warnings = ArrayList<String>()
            if (choice.any()) {
                val staged = stage(backup.database, choice, warnings)
                commit(staged, choice, backup.contents.isAndroid, warnings)
                if (choice.profiles || choice.settings) SubscriptionScheduler.schedule()
                if (choice.settings) RemoteRouteUpdater.schedule()
                if (choice.ipLists) {
                    ScannerStartup.ensureDefaults()
                    IpListUpdater.schedule()
                }
                if (choice.profiles || choice.ipLists) EndpointSources.invalidateDisplayCache()
            }
            return warnings
        } finally {
            backup.discard()
        }
    }

    // ------------------------------------------------------------------------------------------------ phase A

    private class Staged {
        var groups: SqlTable? = null
        var profiles: SqlTable? = null
        var routeProfiles: SqlTable? = null
        var routeRules: SqlTable? = null
        var settings: LinkedHashMap<String, String>? = null

        /** Null when the backup has no markers table (the desktop then clears its markers). */
        var markers: SqlTable? = null

        /** Each null when the backup lacks the table, which then stays as it is (copyTable). */
        var ipLists: SqlTable? = null
        var ipListEntries: SqlTable? = null
        var ipScans: SqlTable? = null
        val counters = HashMap<String, Long>()
    }

    private fun stage(file: File, choice: Choice, warnings: MutableList<String>): Staged {
        // The desktop's snapshot is a WAL-mode file; this private copy is switched to a rollback journal.
        val bak = try {
            SQLiteDatabase.openDatabase(
                file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                DatabaseErrorHandler { }
            )
        } catch (e: Exception) {
            throw RestoreException(app.getString(R.string.backup_error_database, e.message.orEmpty()))
        }
        try {
            bak.disableWriteAheadLogging()
            val main = SagerDatabase.instance.openHelper.writableDatabase
            val staged = Staged()
            if (choice.profiles) stageProfiles(bak, main, staged, warnings)
            if (choice.routes) stageRoutes(bak, main, staged, warnings)
            if (choice.settings) stageSettings(bak, main, staged, warnings)
            if (choice.ipLists) stageIpLists(bak, main, staged, warnings)
            if (bak.hasTable(ENTITY_IDS)) {
                val columns = bak.tableColumns(ENTITY_IDS).mapTo(HashSet()) { it.name }
                // Database.cpp restoreSelective: the three entity counters with either part, the IP ones with theirs.
                val counters = (if (choice.profiles || choice.routes) COUNTERS else emptyList()) +
                    (if (choice.ipLists) IP_COUNTERS else emptyList())
                for ((table, column) in counters) {
                    if (column !in columns) continue
                    bak.longQuery("SELECT MAX(`$column`) FROM `$ENTITY_IDS`")?.let { staged.counters[table] = it }
                }
            }
            return staged
        } finally {
            bak.close()
        }
    }

    private fun require(bak: SQLiteDatabase, table: String) {
        if (!bak.hasTable(table)) throw RestoreException(app.getString(R.string.backup_error_missing_table, table))
    }

    /**
     * copyTable (Database.cpp:296-313): Android's columns that the backup has, plus [extra] ones the caller fills.
     * A NULL where Android's column is NOT NULL takes the column's default; a row still missing a value is skipped.
     * A NOT NULL column without default that the backup lacks fails the restore, as the desktop's INSERT would.
     */
    private fun stageTable(
        bak: SQLiteDatabase,
        main: SupportSQLiteDatabase,
        table: String,
        order: String,
        warnings: MutableList<String>,
        extra: List<String> = emptyList(),
    ): SqlTable {
        val mainColumns = main.tableColumns(table)
        val bakColumns = bak.tableColumns(table).mapTo(HashSet()) { it.name }
        mainColumns.firstOrNull { it.notNull && it.defaultSql == null && it.name !in bakColumns && it.name !in extra }?.let {
            throw RestoreException(app.getString(R.string.backup_error_missing_column, table, it.name))
        }
        val shared = mainColumns.filter { it.name in bakColumns && it.name !in extra }
        val defaults = shared.map { column ->
            if (column.notNull && column.defaultSql != null) {
                bak.rawQuery("SELECT ${column.defaultSql}", null).use { if (it.moveToFirst()) it.value(0) else null }
            } else null
        }
        val out = SqlTable(shared.map { it.name } + extra)
        var skipped = 0
        bak.rawQuery("SELECT ${quoted(shared.map { it.name })} FROM `$table` ORDER BY $order", null).use { c ->
            while (c.moveToNext()) {
                val row = arrayOfNulls<Any?>(out.columns.size)
                var complete = true
                for (i in shared.indices) {
                    val v = c.value(i) ?: if (shared[i].notNull) defaults[i] else null
                    if (v == null && shared[i].notNull) {
                        complete = false
                        break
                    }
                    row[i] = v
                }
                if (complete) out.rows.add(row) else skipped++
            }
        }
        if (skipped > 0) warnings.add(app.getString(R.string.backup_warn_rows_skipped, skipped, table))
        return out
    }

    /**
     * `groups_order` becomes `display_order` and each group's `profiles_json` position becomes `user_order` (dense per
     * group). What the desktop tolerates but Android's foreign key does not is repaired with a warning.
     */
    private fun stageProfiles(bak: SQLiteDatabase, main: SupportSQLiteDatabase, staged: Staged, warnings: MutableList<String>) {
        require(bak, GROUPS)
        require(bak, PROFILES)
        val groups = stageTable(bak, main, GROUPS, "`id`", warnings, extra = listOf("display_order"))
            .withColumns(listOf("visible_columns", "test_items_to_show", "custom_order_json"))
        val groupId = groups.index("id")
        val groupIds = groups.rows.mapTo(LinkedHashSet()) { it[groupId] as Long }
        stageVisibleColumns(groups)

        val tabs = LinkedHashMap<Long, Long>()
        var orphanTabs = 0
        if (bak.hasTable(GROUPS_ORDER)) {
            bak.rawQuery("SELECT group_id, display_order FROM groups_order ORDER BY display_order, group_id", null).use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0) || c.isNull(1)) continue
                    val id = c.getLong(0)
                    if (id in groupIds) tabs.putIfAbsent(id, c.getLong(1)) else orphanTabs++
                }
            }
        }
        if (orphanTabs > 0) warnings.add(app.getString(R.string.backup_warn_orphan_tabs, orphanTabs))
        val untabbed = groupIds.filter { it !in tabs }
        if (untabbed.isNotEmpty()) {
            var next = (tabs.values.maxOrNull() ?: 0L) + 1
            for (id in untabbed) tabs[id] = next++
            warnings.add(app.getString(R.string.backup_warn_untabbed_groups, untabbed.size))
        }
        val displayOrder = groups.index("display_order")
        for (row in groups.rows) row[displayOrder] = tabs.getValue(row[groupId] as Long)
        val tabOrder = tabs.entries.sortedWith(compareBy({ it.value }, { it.key })).map { it.key }

        val listed = HashMap<Long, List<Long>>()
        if (bak.tableColumns(GROUPS).any { it.name == "profiles_json" }) {
            bak.rawQuery("SELECT id, profiles_json FROM groups", null).use { c ->
                while (c.moveToNext()) {
                    val text = if (c.isNull(1)) continue else c.getString(1)
                    val ids = (JsonInput.parseValue(text) as? JsonArray)?.map { JsonValues.toInteger(it) } ?: continue
                    listed[c.getLong(0)] = ids
                }
            }
        }

        val profiles = stageTable(bak, main, PROFILES, "`id`", warnings, extra = listOf("user_order"))
        val profileId = profiles.index("id")
        val gid = profiles.index("gid")
        var moved = 0
        var dropped = 0
        profiles.rows.removeAll { row ->
            val current = row[gid] as? Long
            if (current != null && current in groupIds) return@removeAll false
            val id = row[profileId] as Long
            val target = tabOrder.firstOrNull { listed[it]?.contains(id) == true } ?: tabOrder.firstOrNull()
            if (target == null) {
                dropped++
                return@removeAll true
            }
            row[gid] = target
            moved++
            false
        }
        if (moved > 0) warnings.add(app.getString(R.string.backup_warn_moved_profiles, moved))
        if (dropped > 0) warnings.add(app.getString(R.string.backup_warn_dropped_profiles, dropped))

        val members = profiles.rows.groupBy({ it[gid] as Long }, { it[profileId] as Long })
        val arranged = stageCustomOrder(groups, members)
        val position = HashMap<Long, Long>()
        var unlisted = 0
        for ((group, ids) in members) {
            val own = ids.toHashSet()
            val ordered = LinkedHashSet<Long>()
            listed[group]?.forEach { if (it in own) ordered.add(it) }
            val rest = ids.filter { it !in ordered }.sorted()
            unlisted += rest.size
            ordered.addAll(rest)
            // Group::Arrangement; normalizePinsLocked then puts the pins at the head.
            val shown = arranged[group]?.let { order -> LinkedHashSet(order).apply { addAll(ordered) } } ?: ordered
            shown.forEachIndexed { index, id -> position[id] = index.toLong() }
        }
        if (unlisted > 0) warnings.add(app.getString(R.string.backup_warn_unlisted_profiles, unlisted))
        val userOrder = profiles.index("user_order")
        for (row in profiles.rows) row[userOrder] = position.getValue(row[profileId] as Long)

        staged.groups = groups
        staged.profiles = profiles
    }

    /**
     * visible_columns, else the columns an older backup's test_items_to_show showed (GroupsRepo.cpp's migration), and
     * test_items_to_show mirroring it, which a desktop backup lacks.
     */
    private fun stageVisibleColumns(groups: SqlTable) {
        val visible = groups.index("visible_columns")
        val shown = groups.index("test_items_to_show")
        for (row in groups.rows) {
            val bits = sqlLong(row[visible])?.toInt()
                ?: sqlLong(row[shown])?.let { ProxyGroup.visibleColumnsFor(it.toInt()) }
                ?: ProxyGroup.VisibleColumns.DEFAULT
            row[visible] = bits.toLong()
            row[shown] = ProxyGroup.testItemsFor(bits).toLong()
        }
    }

    /**
     * Group::NormalizePins on load: each custom_order keeps its own members only. Returns the custom order of each
     * group that is not kept sorted, which comes first in its arrangement.
     */
    private fun stageCustomOrder(groups: SqlTable, members: Map<Long, List<Long>>): Map<Long, List<Long>> {
        val id = groups.index("id")
        val column = groups.index("custom_order_json")
        val sortMethod = groups.index("sort_method")
        val arranged = HashMap<Long, List<Long>>()
        for (row in groups.rows) {
            val group = row[id] as Long
            val own = members[group].orEmpty().toHashSet()
            val order = ProxyGroup.parsePinned(row[column] as? String).filter { it in own }
            row[column] = ProxyGroup.customOrderToJson(order)
            val method = if (sortMethod < 0) GroupSortMethod.RAW else GroupSortMethod.of(sqlLong(row[sortMethod])?.toInt() ?: 0)
            if (!GroupSort.reads(method, SortInput.MEMBERSHIP)) arranged[group] = order
        }
        return arranged
    }

    /**
     * kIpListTables, ids kept, each only when the backup has it (copyTable). Entries of a list the backup lacks would
     * break Android's foreign key, so they are skipped.
     */
    private fun stageIpLists(bak: SQLiteDatabase, main: SupportSQLiteDatabase, staged: Staged, warnings: MutableList<String>) {
        if (bak.hasTable(IP_SCANS)) staged.ipScans = stageTable(bak, main, IP_SCANS, "`id`", warnings)
        if (!bak.hasTable(IP_LISTS)) return
        val lists = stageTable(bak, main, IP_LISTS, "`id`", warnings)
        staged.ipLists = lists
        if (!bak.hasTable(IP_LIST_ENTRIES)) return
        val entries = stageTable(bak, main, IP_LIST_ENTRIES, "`ip_list_id`, `entry_order`", warnings)
        val listId = lists.index("id")
        val ids = lists.rows.mapTo(HashSet()) { it[listId] }
        val owner = entries.index("ip_list_id")
        val before = entries.rows.size
        entries.rows.removeAll { it[owner] !in ids }
        if (entries.rows.size < before) {
            warnings.add(app.getString(R.string.backup_warn_rows_skipped, before - entries.rows.size, IP_LIST_ENTRIES))
        }
        staged.ipListEntries = entries
    }

    private fun stageRoutes(bak: SQLiteDatabase, main: SupportSQLiteDatabase, staged: Staged, warnings: MutableList<String>) {
        require(bak, ROUTE_PROFILES)
        val profiles = stageTable(bak, main, ROUTE_PROFILES, "`id`", warnings)
        val ids = profiles.index("id").let { i -> profiles.rows.mapTo(HashSet()) { it[i] as Long } }
        val rules = if (bak.hasTable(ROUTE_RULES)) {
            stageTable(bak, main, ROUTE_RULES, "`route_profile_id`, `rule_order`", warnings)
        } else {
            SqlTable(emptyList())
        }
        val owner = rules.index("route_profile_id")
        val before = rules.rows.size
        if (owner >= 0) rules.rows.removeAll { it[owner] !in ids }
        if (rules.rows.size < before) warnings.add(app.getString(R.string.backup_warn_orphan_rules, before - rules.rows.size))
        if (owner >= 0) {
            skipUnsupportedRules(bak, main, rules, warnings)
            remapRemoteRules(bak, profiles, rules)
            // RoutesRepo numbers each profile's rules 0..n-1: close the gaps of skipped rows and of the backup itself.
            val order = rules.index("rule_order")
            val next = HashMap<Any?, Long>()
            for (row in rules.rows) {
                val n = next[row[owner]] ?: 0L
                row[order] = n
                next[row[owner]] = n + 1
            }
        }
        val raw = profiles.index("is_raw").takeIf { it >= 0 }?.let { i -> profiles.rows.count { (it[i] as? Long ?: 0L) != 0L } } ?: 0
        if (raw > 0) warnings.add(app.getString(R.string.backup_warn_raw_routes, raw))
        staged.routeProfiles = profiles
        staged.routeRules = rules
    }

    /**
     * `remote_rules` lists rule positions (RoutesRepo.cpp routesRepoRemoteIndexes), which the rules skipped here shift:
     * each position follows its rule, and a skipped remote rule drops out. NULL stays NULL (origins not tracked).
     */
    private fun remapRemoteRules(bak: SQLiteDatabase, profiles: SqlTable, rules: SqlTable) {
        val column = profiles.index("remote_rules")
        if (column < 0) return
        val original = HashMap<Pair<Any?, Any?>, Int>()
        val counted = HashMap<Any?, Int>()
        bak.rawQuery("SELECT `route_profile_id`, `rule_order` FROM `$ROUTE_RULES` ORDER BY `route_profile_id`, `rule_order`", null).use { c ->
            while (c.moveToNext()) {
                val profile = c.value(0)
                val n = counted[profile] ?: 0
                original[profile to c.value(1)] = n
                counted[profile] = n + 1
            }
        }
        val owner = rules.index("route_profile_id")
        val order = rules.index("rule_order")
        val positions = HashMap<Any?, HashMap<Int, Int>>()
        val kept = HashMap<Any?, Int>()
        for (row in rules.rows) {
            val n = kept[row[owner]] ?: 0
            original[row[owner] to row[order]]?.let { positions.getOrPut(row[owner]) { HashMap() }[it] = n }
            kept[row[owner]] = n + 1
        }
        val id = profiles.index("id")
        for (row in profiles.rows) {
            val array = (row[column] as? String)?.let { JsonInput.parseValue(it) } as? JsonArray ?: continue
            val moved = positions[row[id]].orEmpty()
            val out = JsonArray()
            array.filter { it is Long || it is Double }.mapNotNull { moved[JsonValues.toInt(it)] }.distinct().sorted().forEach { out.add(it) }
            row[column] = out.toCompact()
        }
    }

    /**
     * A backup column Android lacks is a rule field it does not support: a rule with a value there (anything but
     * NULL, '', '[]', 0 or '0') is skipped whole, since restoring it without that field would widen what it matches.
     */
    private fun skipUnsupportedRules(bak: SQLiteDatabase, main: SupportSQLiteDatabase, rules: SqlTable, warnings: MutableList<String>) {
        val known = main.tableColumns(ROUTE_RULES).mapTo(HashSet()) { it.name }
        val unknown = bak.tableColumns(ROUTE_RULES).map { it.name }.filter { it !in known }
        if (unknown.isEmpty()) return
        val hasValue = unknown.map { sqlName(it) }.map { "($it IS NOT NULL AND $it NOT IN ('', '[]', 0, '0'))" }
        val offending = HashMap<Pair<Any?, Any?>, List<String>>()
        bak.rawQuery(
            "SELECT `route_profile_id`, `rule_order`, ${hasValue.joinToString(",")} FROM `$ROUTE_RULES` " +
                "WHERE ${hasValue.joinToString(" OR ")}",
            null
        ).use { c ->
            while (c.moveToNext()) offending[c.value(0) to c.value(1)] = unknown.filterIndexed { i, _ -> c.getLong(i + 2) != 0L }
        }
        val owner = rules.index("route_profile_id")
        val order = rules.index("rule_order")
        val used = HashSet<String>()
        val before = rules.rows.size
        rules.rows.removeAll { row ->
            val columns = offending[row[owner] to row[order]] ?: return@removeAll false
            used.addAll(columns)
            true
        }
        val skipped = before - rules.rows.size
        if (skipped > 0) {
            val fields = unknown.filter { it in used }.joinToString(", ")
            warnings.add(app.getString(R.string.backup_warn_unsupported_rules, skipped, fields))
        }
    }

    /** A backup's own column name as an SQL identifier; it is data, so a backtick in it is escaped. */
    private fun sqlName(name: String): String = "`" + name.replace("`", "``") + "`"

    private fun stageSettings(bak: SQLiteDatabase, main: SupportSQLiteDatabase, staged: Staged, warnings: MutableList<String>) {
        require(bak, SETTINGS)
        val rows = LinkedHashMap<String, String>()
        var skipped = 0
        bak.rawQuery("SELECT key, value FROM settings", null).use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0) || c.isNull(1)) {
                    skipped++
                    continue
                }
                rows[c.getString(0)] = c.getString(1)
            }
        }
        if (skipped > 0) warnings.add(app.getString(R.string.backup_warn_rows_skipped, skipped, SETTINGS))
        staged.settings = rows
        staged.markers = if (bak.hasTable(MARKERS)) stageTable(bak, main, MARKERS, "`key`", warnings) else null
    }

    // ------------------------------------------------------------------------------------------------ phase B

    private fun commit(staged: Staged, choice: Choice, fromAndroid: Boolean, warnings: MutableList<String>) {
        val lanWasOn = DataStore.allowLanAccess
        SagerDatabase.instance.runInTransaction {
            val db = SagerDatabase.instance.openHelper.writableDatabase
            db.execSQL("PRAGMA defer_foreign_keys = ON")
            if (choice.profiles) {
                db.execSQL("DELETE FROM `$PROFILES`")
                db.execSQL("DELETE FROM `$GROUPS`")
                insertRows(db, GROUPS, staged.groups!!)
                insertRows(db, PROFILES, staged.profiles!!)
            }
            if (choice.routes) {
                db.execSQL("DELETE FROM `$ROUTE_RULES`")
                db.execSQL("DELETE FROM `$ROUTE_PROFILES`")
                insertRows(db, ROUTE_PROFILES, staged.routeProfiles!!)
                insertRows(db, ROUTE_RULES, staged.routeRules!!)
            }
            // Database.cpp:590-594: route_id and source_gid point across the two parts; restored apart, they would name unrelated rows.
            if (choice.profiles != choice.routes) {
                db.execSQL("UPDATE `$GROUPS` SET `route_id` = -1")
                db.execSQL("UPDATE `$ROUTE_PROFILES` SET `source_gid` = -1")
            }
            if (choice.ipLists) {
                val tables = listOf(IP_LISTS to staged.ipLists, IP_SCANS to staged.ipScans, IP_LIST_ENTRIES to staged.ipListEntries)
                for ((table, data) in tables.asReversed()) if (data != null) db.execSQL("DELETE FROM `$table`")
                for ((table, data) in tables) if (data != null) insertRows(db, table, data)
            }
            // Staged only for the parts restored: the three entity counters with either part, the IP ones with theirs.
            for ((table, value) in staged.counters) raiseSequence(db, table, value)
            if (choice.settings) applySettings(db, staged, choice, fromAndroid)
            // Configs.cpp:32-44 initDB: an empty table gets its Default row, with a fresh id.
            if (choice.profiles) SagerDatabase.groupDao.insertDefaultIfEmpty(SagerDatabase.defaultGroupName())
            // GroupsRepo's NormalizePins on load, once for the restored groups: the pins head their order.
            if (choice.profiles) for (gid in SagerDatabase.groupDao.idsWithPins()) ProfileManager.normalizePinsLocked(gid)
            if (choice.routes && (db.longQuery("SELECT COUNT(*) FROM `$ROUTE_PROFILES` WHERE `is_raw` = 0") ?: 0L) == 0L) {
                RouteManager.save(RouteProfile.defaultProfile())
            }
            fixSelection(db, choice, staged, fromAndroid, lanWasOn, warnings)
            db.query("PRAGMA foreign_key_check").use { c ->
                if (c.moveToFirst()) throw RestoreException(app.getString(R.string.backup_error_foreign_key, c.getString(0)))
            }
        }
    }

    private fun insertRows(db: SupportSQLiteDatabase, table: String, data: SqlTable) {
        if (data.rows.isEmpty()) return
        db.compileStatement(insertSql(table, data.columns)).use { statement ->
            for (row in data.rows) {
                statement.clearBindings()
                row.forEachIndexed { i, v -> statement.bindValue(i + 1, v) }
                statement.executeInsert()
            }
        }
    }

    /** `sqlite_sequence` plays `entity_ids`: never lowered, so no id the backup's device used is handed out again. */
    private fun raiseSequence(db: SupportSQLiteDatabase, table: String, value: Long) {
        if (value <= 0L) return
        val updated = db.compileStatement("UPDATE sqlite_sequence SET seq = MAX(seq, ?) WHERE name = ?").use {
            it.bindLong(1, value)
            it.bindString(2, table)
            it.executeUpdateDelete()
        }
        if (updated == 0) db.execSQL("INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)", arrayOf<Any?>(table, value))
    }

    /**
     * R10 §8.3: every restore keeps this phone's device-local and [SettingsRegistry.RESTORE_KEPT_KEYS] rows and the
     * selection ids of the tables it leaves ([keptSelection]). An Android backup replaces every other row; a desktop
     * backup replaces the desktop rows but the Host ones and keeps this phone's Android-only rows (adopting only those
     * the phone lacks). Unknown and desktop-only keys are stored as they are so they go back to the desktop, but
     * exit_through migrates as on load ([DataStore.migrateLegacyExit]), the retired exit rows go, and the restored rows
     * and markers go through [DataStore.migrateSettings].
     */
    private fun applySettings(
        db: SupportSQLiteDatabase,
        staged: Staged,
        choice: Choice,
        fromAndroid: Boolean,
    ) {
        val local = SettingsRegistry.DEVICE_LOCAL_KEYS + SettingsRegistry.RESTORE_KEPT_KEYS + keptSelection(choice) +
            (if (fromAndroid) emptySet<String>() else SettingsRegistry.HOST_KEYS)
        val kept = if (fromAndroid) local else local + SettingsRegistry.ANDROID_KEYS
        db.execSQL(
            "DELETE FROM `$SETTINGS` WHERE `key` NOT IN (${kept.joinToString(",") { "?" }})",
            kept.toTypedArray<Any?>()
        )
        db.compileStatement("INSERT OR REPLACE INTO `$SETTINGS` (`key`, `value`) VALUES (?, ?)").use { replace ->
            db.compileStatement("INSERT OR IGNORE INTO `$SETTINGS` (`key`, `value`) VALUES (?, ?)").use { ignore ->
                for ((key, value) in staged.settings.orEmpty()) {
                    if (key in local) continue
                    val statement = if (!fromAndroid && key in SettingsRegistry.ANDROID_KEYS) ignore else replace
                    statement.clearBindings()
                    statement.bindString(1, key)
                    statement.bindString(2, value)
                    statement.executeInsert()
                }
            }
        }
        // A backup from before exit_through brings enable_warp instead, and an older desktop's may exit through Tor or
        // Psiphon; the process may have migrated already, so it is done here too.
        SettingsRegistry.migratedExitThrough { readSetting(db, it) }?.let { writeSetting(db, SettingsRegistry.EXIT_THROUGH.key, it) }
        for (key in SettingsRegistry.RETIRED_EXIT_KEYS) writeSetting(db, key, null)
        // Database.cpp:357-359: without a markers table the backup predates the migrations, which then run again.
        db.execSQL("DELETE FROM `$MARKERS`")
        staged.markers?.let { insertRows(db, MARKERS, it) }
        DataStore.migrateSettings(db)
    }

    private fun readSetting(db: SupportSQLiteDatabase, key: String): String? =
        db.query("SELECT `value` FROM `$SETTINGS` WHERE `key` = ?", arrayOf<Any?>(key)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    private fun writeSetting(db: SupportSQLiteDatabase, key: String, value: String?) {
        if (value == null) {
            db.execSQL("DELETE FROM `$SETTINGS` WHERE `key` = ?", arrayOf<Any?>(key))
        } else {
            db.execSQL("INSERT OR REPLACE INTO `$SETTINGS` (`key`, `value`) VALUES (?, ?)", arrayOf<Any?>(key, value))
        }
    }

    /** Database.cpp:548-555: a selection whose table stays keeps this phone's id; the backup's would name an unrelated row. */
    private fun keptSelection(choice: Choice): Set<String> = buildSet {
        if (!choice.profiles) addAll(listOf(SettingsRegistry.CURRENT_GROUP.key, Key.PROFILE_ID, Key.PROFILE_CURRENT, REMEMBER_ID))
        if (!choice.routes) add(SettingsRegistry.CURRENT_ROUTE_ID.key)
    }

    /**
     * Database.cpp:568-591: the ids kept in settings must name existing rows, and one this phone kept for a table the
     * backup replaced now names an unrelated row. So `current_group` (else the first tab), `current_route_id` (a
     * non-raw profile, else the first one) and the selected / last profile (a desktop backup's `remember_id` when it
     * names a restored profile, else none) follow the replaced tables; `inbound_address` goes back to loopback unless
     * LAN access was already on.
     */
    private fun fixSelection(
        db: SupportSQLiteDatabase,
        choice: Choice,
        staged: Staged,
        fromAndroid: Boolean,
        lanWasOn: Boolean,
        warnings: MutableList<String>,
    ) {
        fun ids(sql: String): List<Long> = db.query(sql).use { c ->
            val out = ArrayList<Long>()
            while (c.moveToNext()) out.add(c.getLong(0))
            out
        }

        fun id(key: String): Long? = readSetting(db, key)?.trim()?.toLongOrNull()

        if (choice.profiles || choice.settings) {
            // Without the backup's settings, the ids are still this phone's.
            val stale = choice.profiles && !choice.settings
            val groups = ids("SELECT `id` FROM `$GROUPS` ORDER BY `display_order`, `id`")
            val groupKey = SettingsRegistry.CURRENT_GROUP.key
            if (stale || id(groupKey) !in groups) writeSetting(db, groupKey, groups.firstOrNull()?.toString())

            val profiles = ids("SELECT `id` FROM `$PROFILES`").toHashSet()
            // A desktop backup keeps this phone's Android-only selection, which its profiles replace just the same.
            val staleProfile = stale || choice.profiles && !fromAndroid
            for (selection in listOf(Key.PROFILE_ID, Key.PROFILE_CURRENT)) {
                val selected = id(selection) ?: continue
                if (selected > 0L && (staleProfile || selected !in profiles)) writeSetting(db, selection, null)
            }
            val rememberId = id(REMEMBER_ID)
            if (rememberId != null && rememberId >= 0L && (stale || rememberId !in profiles)) {
                writeSetting(db, REMEMBER_ID, DesktopSchema.NO_PROFILE_ID.toString())
            }
            // remember_id names a profile of the same backup only when its profiles came along.
            if (choice.settings && choice.profiles && !fromAndroid) {
                val remember = staged.settings?.get(REMEMBER_ID)?.trim()?.toLongOrNull()?.takeIf { it in profiles }
                if (remember != null) {
                    writeSetting(db, Key.PROFILE_ID, remember.toString())
                    writeSetting(db, Key.PROFILE_CURRENT, remember.toString())
                }
            }
        }

        if (choice.routes || choice.settings) {
            val usable = ids("SELECT `id` FROM `$ROUTE_PROFILES` WHERE `is_raw` = 0 ORDER BY `id`")
            val key = SettingsRegistry.CURRENT_ROUTE_ID.key
            val stale = choice.routes && !choice.settings
            if (stale || id(key) !in usable) writeSetting(db, key, usable.firstOrNull()?.toString())
        }

        if (choice.settings && !lanWasOn) {
            val key = SettingsRegistry.INBOUND_ADDRESS.key
            val address = readSetting(db, key)
            if (address != null && !SettingsRegistry.isLoopbackAddress(address)) {
                writeSetting(db, key, SettingsRegistry.LOOPBACK_ADDRESS)
                warnings.add(app.getString(R.string.backup_warn_lan_access, address))
            }
        }
    }

    private const val REMEMBER_ID = "remember_id"

    /** entity_ids column -> the Room table whose AUTOINCREMENT sequence plays it. */
    private val COUNTERS = listOf(
        PROFILES to "profile_last_id",
        GROUPS to "group_last_id",
        ROUTE_PROFILES to "route_profile_last_id",
    )

    private val IP_COUNTERS = listOf(
        IP_LISTS to "ip_list_last_id",
        IP_SCANS to "ip_scan_last_id",
    )
}
