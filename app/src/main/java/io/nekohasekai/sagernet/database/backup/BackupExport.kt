package io.nekohasekai.sagernet.database.backup

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.MarkerEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsRegistry
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
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonValues
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Callable

/**
 * Writes a `.thrbackup` the desktop restores (R10 §8.7): the desktop DDL for every table in a temporary SQLite file,
 * the chosen parts copied by column name from one consistent snapshot of SagerDatabase, `profiles_json` from
 * `user_order`, `groups_order` from `display_order`, `entity_ids` from `sqlite_sequence`, the settings without the
 * device-local keys and with the selection translated (`current_group`, `current_route_id`, `remember_id`), the
 * markers, the IP lists and scans, an empty `otp_profiles`; then the pre-close checks the desktop relies on.
 */
object BackupExport {

    /** The parts to write. */
    data class Selection(
        val profiles: Boolean,
        val routes: Boolean,
        val settings: Boolean,
        val ipLists: Boolean = false,
    ) {
        fun any(): Boolean = profiles || routes || settings || ipLists
    }

    /** Writes the backup to [out]: the database entry and nothing else. */
    fun write(selection: Selection, out: OutputStream) {
        if (!selection.any()) error(app.getString(R.string.backup_select_part))
        BackupTemp.cleanStale()
        val temp = BackupTemp.newFile(BackupTemp.EXPORT)
        try {
            buildDatabase(temp, selection)
            val parts = ThrBackup.Parts(
                profiles = selection.profiles, routes = selection.routes, settings = selection.settings,
                ipLists = selection.ipLists,
            )
            val files = mapOf(ThrBackup.DATABASE to ThrBackup.Payload.FromFile(temp))
            val meta = ThrBackup.androidMeta(
                parts, BuildConfig.VERSION_NAME, DataStore.deviceId, DataStore.effectiveDeviceName()
            )
            ThrBackup.write(out, meta, files)
        } finally {
            BackupTemp.delete(temp)
        }
    }

    private class Snapshot {
        var profiles: SqlTable? = null
        var groups: SqlTable? = null
        var routeProfiles: SqlTable? = null
        var routeRules: SqlTable? = null
        var markers: SqlTable? = null
        var settings: Map<String, String>? = null
        var ipLists: SqlTable? = null
        var ipListEntries: SqlTable? = null
        var ipScans: SqlTable? = null

        /** Group id -> member ids in list order. */
        val members = HashMap<Long, MutableList<Long>>()

        /** (group id, display_order) in tab order. */
        val tabs = ArrayList<Pair<Long, Long>>()
        val counters = HashMap<String, Long>()
    }

    private fun buildDatabase(file: File, selection: Selection) {
        val db = SQLiteDatabase.openDatabase(
            file.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            DatabaseErrorHandler { }
        )
        try {
            db.disableWriteAheadLogging()
            db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
            for (sql in DesktopSchema.DDL) db.execSQL(sql)
            val desktopColumns = listOf(
                PROFILES, GROUPS, ROUTE_PROFILES, ROUTE_RULES, SETTINGS, MARKERS, IP_LISTS, IP_LIST_ENTRIES, IP_SCANS,
            ).associateWith { table -> db.tableColumns(table).map { it.name } }
            val snapshot = snapshot(selection, desktopColumns)
            db.beginTransaction()
            try {
                fill(db, snapshot, selection)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            verify(db, selection)
        } finally {
            db.close()
        }
    }

    /** Everything read in one SagerDatabase transaction, so the parts agree with each other. */
    private fun snapshot(selection: Selection, desktopColumns: Map<String, List<String>>): Snapshot =
        SagerDatabase.instance.runInTransaction(Callable {
            val live = SagerDatabase.instance.openHelper.writableDatabase
            val s = Snapshot()

            fun copy(table: String, order: String): SqlTable {
                val android = live.tableColumns(table).mapTo(HashSet()) { it.name }
                val columns = desktopColumns.getValue(table).filter { it in android }
                if (columns.isEmpty()) return SqlTable(columns)
                return live.query("SELECT ${quoted(columns)} FROM `$table` ORDER BY $order").use { it.readTable(columns) }
            }

            fun maxId(table: String): Long =
                if (live.hasTable(table)) live.longQuery("SELECT MAX(`id`) FROM `$table`") ?: 0L else 0L

            live.query("SELECT `id`, `display_order` FROM `groups` ORDER BY `display_order`, `id`").use { c ->
                while (c.moveToNext()) s.tabs.add(c.getLong(0) to c.getLong(1))
            }
            live.query("SELECT `gid`, `id` FROM `profiles` ORDER BY `gid`, `user_order`, `id`").use { c ->
                while (c.moveToNext()) s.members.getOrPut(c.getLong(0)) { ArrayList() }.add(c.getLong(1))
            }
            val routes = ArrayList<Pair<Long, Boolean>>()
            live.query("SELECT `id`, `is_raw` FROM `route_profiles` ORDER BY `id`").use { c ->
                while (c.moveToNext()) routes.add(c.getLong(0) to (c.getLong(1) != 0L))
            }
            val sequences = HashMap<String, Long>()
            runCatching {
                live.query("SELECT `name`, `seq` FROM `sqlite_sequence`").use { c ->
                    while (c.moveToNext()) sequences[c.getString(0)] = c.getLong(1)
                }
            }
            val profileIds = s.members.values.flatten()
            val groupIds = s.tabs.map { it.first }
            // Database.cpp keeps entity_ids in every backup; the counters never go below the ids in use.
            s.counters[PROFILES] = maxOf(sequences[PROFILES] ?: 0L, profileIds.maxOrNull() ?: 0L)
            s.counters[GROUPS] = maxOf(sequences[GROUPS] ?: 0L, groupIds.maxOrNull() ?: 0L)
            s.counters[ROUTE_PROFILES] = maxOf(sequences[ROUTE_PROFILES] ?: 0L, routes.maxOfOrNull { it.first } ?: 0L)
            s.counters[IP_LISTS] = maxOf(sequences[IP_LISTS] ?: 0L, maxId(IP_LISTS))
            s.counters[IP_SCANS] = maxOf(sequences[IP_SCANS] ?: 0L, maxId(IP_SCANS))

            if (selection.profiles) {
                s.profiles = copy(PROFILES, "`id`")
                s.groups = copy(GROUPS, "`display_order`, `id`")
            }
            if (selection.routes) {
                s.routeProfiles = copy(ROUTE_PROFILES, "`id`")
                s.routeRules = copy(ROUTE_RULES, "`route_profile_id`, `rule_order`")
            }
            if (selection.settings) {
                val stored = LinkedHashMap<String, String>()
                live.query("SELECT `key`, `value` FROM `settings` ORDER BY `key`").use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0) ?: continue
                        stored[key] = c.getString(1) ?: continue
                    }
                }
                s.settings = exportedSettings(stored, groupIds, profileIds.toHashSet(), routes)
                val markers = copy(MARKERS, "`key`")
                val keyIndex = markers.index("key")
                if (markers.rows.none { it[keyIndex] == MarkerEntity.TUN_PRIVATE_RANGES_IPV6 }) {
                    // Android's vpn_private_ranges default already has the IPv6 ranges (DatabaseManager.cpp:130-146).
                    val row = arrayOfNulls<Any?>(markers.columns.size)
                    row[keyIndex] = MarkerEntity.TUN_PRIVATE_RANGES_IPV6
                    markers.index("marked_at").takeIf { it >= 0 }?.let { row[it] = System.currentTimeMillis() / 1000 }
                    markers.rows.add(row)
                }
                s.markers = markers
            }
            if (selection.ipLists) {
                val lists = copy(IP_LISTS, "`id`")
                val entries = copy(IP_LIST_ENTRIES, "`ip_list_id`, `entry_order`")
                val listId = lists.index("id")
                val listIds: Set<Any?> = if (listId < 0) emptySet() else lists.rows.mapTo(HashSet()) { it[listId] }
                val entryList = entries.index("ip_list_id")
                if (entryList >= 0) entries.rows.removeAll { it[entryList] !in listIds }
                s.ipLists = lists
                s.ipListEntries = entries
                s.ipScans = copy(IP_SCANS, "`id`")
            }
            s
        })

    /**
     * Every row but the device-local keys; the selection as the desktop reads it: `current_group` the group shown,
     * `current_route_id` the profile in use, `remember_id` the selected (else last started) profile.
     */
    private fun exportedSettings(
        stored: Map<String, String>,
        groupIds: List<Long>,
        profileIds: Set<Long>,
        routes: List<Pair<Long, Boolean>>,
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, value) in stored) if (key !in SettingsRegistry.DEVICE_LOCAL_KEYS) out[key] = value
        fun id(key: String): Long? = stored[key]?.trim()?.toLongOrNull()

        val group = id(SettingsRegistry.CURRENT_GROUP.key)?.takeIf { it in groupIds } ?: groupIds.firstOrNull()
        if (group != null) out[SettingsRegistry.CURRENT_GROUP.key] = group.toString()

        val usable = routes.filter { !it.second }.map { it.first }
        val route = id(SettingsRegistry.CURRENT_ROUTE_ID.key)?.takeIf { it in usable }
            ?: usable.firstOrNull() ?: routes.firstOrNull()?.first
        if (route != null) out[SettingsRegistry.CURRENT_ROUTE_ID.key] = route.toString()

        val remember = listOf(Key.PROFILE_ID, Key.PROFILE_CURRENT, REMEMBER_ID).firstNotNullOfOrNull { key ->
            id(key)?.takeIf { it in profileIds }
        } ?: DesktopSchema.NO_PROFILE_ID
        out[REMEMBER_ID] = remember.toString()
        return out
    }

    private fun fill(db: SQLiteDatabase, s: Snapshot, selection: Selection) {
        db.execSQL(
            "INSERT INTO entity_ids (profile_last_id, group_last_id, route_profile_last_id, otp_profile_last_id, " +
                "ip_list_last_id, ip_scan_last_id) VALUES (?, ?, ?, 0, ?, ?)",
            arrayOf<Any?>(
                s.counters[PROFILES], s.counters[GROUPS], s.counters[ROUTE_PROFILES], s.counters[IP_LISTS], s.counters[IP_SCANS],
            )
        )
        s.groups?.let { groups ->
            val id = groups.index("id")
            val columns = groups.columns + "profiles_json"
            val rows = groups.rows.map { row ->
                val members = JsonArray()
                s.members[row[id] as Long].orEmpty().forEach { members.add(it) }
                row + members.toCompact()
            }
            insertRows(db, GROUPS, columns, rows)
            insertRows(db, GROUPS_ORDER, listOf("group_id", "display_order"), s.tabs.map { arrayOf(it.first, it.second) })
        }
        s.profiles?.let { insertRows(db, PROFILES, it.columns, it.rows) }
        s.routeProfiles?.let { insertRows(db, ROUTE_PROFILES, it.columns, it.rows) }
        s.routeRules?.let { rules ->
            // RoutesRepo writes each profile's rules as 0..n-1; keep the order, close any gaps.
            val profile = rules.index("route_profile_id")
            val order = rules.index("rule_order")
            var last: Any? = null
            var next = 0L
            for (row in rules.rows) {
                if (row[profile] != last) {
                    last = row[profile]
                    next = 0L
                }
                row[order] = next++
            }
            insertRows(db, ROUTE_RULES, rules.columns, rules.rows)
        }
        if (selection.settings) {
            insertRows(db, SETTINGS, listOf("key", "value"), s.settings.orEmpty().map { arrayOf(it.key, it.value) })
            s.markers?.let { insertRows(db, MARKERS, it.columns, it.rows) }
        }
        for ((table, data) in listOf(IP_LISTS to s.ipLists, IP_LIST_ENTRIES to s.ipListEntries, IP_SCANS to s.ipScans)) {
            data?.let { insertRows(db, table, it.columns, it.rows) }
        }
    }

    private fun insertRows(db: SQLiteDatabase, table: String, columns: List<String>, rows: List<Array<out Any?>>) {
        if (rows.isEmpty()) return
        db.compileStatement(insertSql(table, columns)).use { statement ->
            for (row in rows) {
                statement.clearBindings()
                row.forEachIndexed { i, v -> statement.bindValue(i + 1, v) }
                statement.executeInsert()
            }
        }
    }

    /** R10 §8.7 step 5: what the desktop assumes but never checks (a groups_order row without its group crashes it). */
    private fun verify(db: SQLiteDatabase, selection: Selection) {
        fun zero(what: String, sql: String) {
            val n = db.longQuery(sql) ?: 0L
            check(n == 0L) { "backup check failed: $what ($n)" }
        }
        if (selection.profiles) {
            zero("groups without one tab", "SELECT COUNT(*) FROM groups g WHERE (SELECT COUNT(*) FROM groups_order o WHERE o.group_id = g.id) != 1")
            zero("tabs without a group", "SELECT COUNT(*) FROM groups_order o WHERE NOT EXISTS (SELECT 1 FROM groups g WHERE g.id = o.group_id)")
            zero("profiles without a group", "SELECT COUNT(*) FROM profiles p WHERE NOT EXISTS (SELECT 1 FROM groups g WHERE g.id = p.gid)")
            val members = HashMap<Long, MutableSet<Long>>()
            db.rawQuery("SELECT gid, id FROM profiles", null).use { c ->
                while (c.moveToNext()) members.getOrPut(c.getLong(0)) { HashSet() }.add(c.getLong(1))
            }
            db.rawQuery("SELECT id, profiles_json FROM groups", null).use { c ->
                while (c.moveToNext()) {
                    val listed = (JsonInput.parseValue(c.getString(1)) as? JsonArray)?.map { JsonValues.toInteger(it) }
                    check(listed != null && listed.size == listed.toSet().size && listed.toSet() == members[c.getLong(0)].orEmpty()) {
                        "backup check failed: profiles_json of group ${c.getLong(0)}"
                    }
                }
            }
        }
        if (selection.routes) {
            zero("rules without a routing profile", "SELECT COUNT(*) FROM route_rules r WHERE NOT EXISTS (SELECT 1 FROM route_profiles p WHERE p.id = r.route_profile_id)")
            zero(
                "rule order", "SELECT COUNT(*) FROM (SELECT COUNT(*) AS n, MIN(rule_order) AS lo, MAX(rule_order) AS hi " +
                    "FROM route_rules GROUP BY route_profile_id) WHERE lo != 0 OR hi != n - 1"
            )
            if (selection.settings && (db.longQuery("SELECT COUNT(*) FROM route_profiles") ?: 0L) > 0L) {
                zero(
                    "current_route_id", "SELECT COUNT(*) FROM settings s WHERE s.key = 'current_route_id' AND " +
                        "NOT EXISTS (SELECT 1 FROM route_profiles p WHERE p.id = CAST(s.value AS INTEGER))"
                )
            }
        }
    }

    private const val REMEMBER_ID = "remember_id"
}
