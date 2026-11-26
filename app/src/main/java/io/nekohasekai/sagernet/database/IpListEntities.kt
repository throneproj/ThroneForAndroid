package io.nekohasekai.sagernet.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The desktop's `ip_lists` (IpListsRepo.cpp createTables): an IP list's header. Readers see only the entries of
 * [entriesGeneration]; a bulk write fills another generation and flips to it in one transaction.
 */
@Entity(tableName = IpListSchema.LISTS)
data class IpListEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") var id: Long = 0L,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "related_test_id", defaultValue = "-1") var relatedTestId: Long = -1L,
    @ColumnInfo(name = "role", defaultValue = "0") var role: Int = 0,
    @ColumnInfo(name = "source_kind", defaultValue = "0") var sourceKind: Int = 0,
    @ColumnInfo(name = "source", defaultValue = "") var source: String = "",
    @ColumnInfo(name = "auto_update", defaultValue = "0") var autoUpdate: Boolean = false,
    @ColumnInfo(name = "update_interval", defaultValue = "1440") var updateInterval: Int = 1440,
    @ColumnInfo(name = "last_update", defaultValue = "0") var lastUpdate: Long = 0L,
    @ColumnInfo(name = "last_error", defaultValue = "") var lastError: String = "",
    @ColumnInfo(name = "sort_order", defaultValue = "0") var sortOrder: Int = 0,
    @ColumnInfo(name = "entries_generation", defaultValue = "0") var entriesGeneration: Long = 0L,
    @ColumnInfo(name = "created_at", defaultValue = IpListSchema.NOW) var createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = IpListSchema.NOW) var updatedAt: Long = 0L,
)

/** The desktop's `ip_list_entries`; `loss_pct` NULL is unknown (-1 in IpListEntry). */
@Entity(
    tableName = IpListSchema.ENTRIES,
    primaryKeys = ["ip_list_id", "generation", "entry_order"],
    foreignKeys = [
        ForeignKey(
            entity = IpListEntity::class,
            parentColumns = ["id"],
            childColumns = ["ip_list_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["ip_list_id", "generation", "cidr", "port"], name = IpListSchema.TARGET_INDEX, unique = true)],
)
data class IpListEntryEntity(
    @ColumnInfo(name = "ip_list_id") var ipListId: Long = 0L,
    @ColumnInfo(name = "generation", defaultValue = "0") var generation: Long = 0L,
    @ColumnInfo(name = "entry_order") var entryOrder: Long = 0L,
    @ColumnInfo(name = "cidr") var cidr: String = "",
    @ColumnInfo(name = "port", defaultValue = "0") var port: Int = 0,
    @ColumnInfo(name = "latency_ms", defaultValue = "0") var latencyMs: Int = 0,
    @ColumnInfo(name = "loss_pct") var lossPct: Int? = null,
)

/** The desktop's `ip_scans` (IpScansRepo.cpp createTables); [configJson] is ScanConfig's JSON. */
@Entity(tableName = IpListSchema.SCANS)
data class IpScanEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") var id: Long = 0L,
    @ColumnInfo(name = "name", defaultValue = "") var name: String = "",
    @ColumnInfo(name = "kind", defaultValue = "0") var kind: Int = 0,
    @ColumnInfo(name = "sort_order", defaultValue = "0") var sortOrder: Int = 0,
    @ColumnInfo(name = "base_list_id", defaultValue = "-1") var baseListId: Long = -1L,
    @ColumnInfo(name = "result_list_id", defaultValue = "-1") var resultListId: Long = -1L,
    @ColumnInfo(name = "config_json", defaultValue = "{}") var configJson: String = "{}",
    @ColumnInfo(name = "status", defaultValue = "0") var status: Int = 0,
    @ColumnInfo(name = "mode", defaultValue = "0") var mode: Int = 0,
    @ColumnInfo(name = "snapshot_list_id", defaultValue = "-1") var snapshotListId: Long = -1L,
    @ColumnInfo(name = "seed", defaultValue = "0") var seed: Long = 0L,
    @ColumnInfo(name = "cursor", defaultValue = "0") var cursor: Long = 0L,
    @ColumnInfo(name = "total", defaultValue = "0") var total: Long = 0L,
    @ColumnInfo(name = "spec_hash", defaultValue = "") var specHash: String = "",
    @ColumnInfo(name = "rescan_snapshot_list_id", defaultValue = "-1") var rescanSnapshotListId: Long = -1L,
    @ColumnInfo(name = "rescan_cursor", defaultValue = "0") var rescanCursor: Long = 0L,
    @ColumnInfo(name = "rescan_total", defaultValue = "0") var rescanTotal: Long = 0L,
    @ColumnInfo(name = "found", defaultValue = "0") var found: Int = 0,
    @ColumnInfo(name = "removed", defaultValue = "0") var removed: Int = 0,
    @ColumnInfo(name = "last_error", defaultValue = "") var lastError: String = "",
    @ColumnInfo(name = "started_at", defaultValue = "0") var startedAt: Long = 0L,
    @ColumnInfo(name = "finished_at", defaultValue = "0") var finishedAt: Long = 0L,
    @ColumnInfo(name = "created_at", defaultValue = IpListSchema.NOW) var createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = IpListSchema.NOW) var updatedAt: Long = 0L,
)

/** An IP list header with the number of entries in its current generation. */
data class IpListHeaderRow(
    @Embedded val list: IpListEntity,
    @ColumnInfo(name = "entry_count") val entryCount: Int,
)

/** Reads and the simple writes of the IP list tables; the generation-changing writes are in scan.IpListsRepo. */
@Dao
interface IpListDao {

    @Query("SELECT * FROM `ip_lists` WHERE `id` = :id")
    fun get(id: Long): IpListEntity?

    @Query(
        "SELECT *, (SELECT COUNT(*) FROM `ip_list_entries` WHERE `ip_list_id` = `ip_lists`.`id` " +
            "AND `generation` = `ip_lists`.`entries_generation`) AS `entry_count` FROM `ip_lists` ORDER BY `sort_order`, `id`"
    )
    fun allWithCounts(): List<IpListHeaderRow>

    /** Everything but the scan snapshots (role 2). */
    @Query(
        "SELECT *, (SELECT COUNT(*) FROM `ip_list_entries` WHERE `ip_list_id` = `ip_lists`.`id` " +
            "AND `generation` = `ip_lists`.`entries_generation`) AS `entry_count` FROM `ip_lists` WHERE `role` != 2 " +
            "ORDER BY `sort_order`, `id`"
    )
    fun visibleWithCounts(): List<IpListHeaderRow>

    @Query("SELECT `entries_generation` FROM `ip_lists` WHERE `id` = :id")
    fun generation(id: Long): Long?

    @Query(
        "SELECT COUNT(*) FROM `ip_list_entries` WHERE `ip_list_id` = :id " +
            "AND `generation` = (SELECT `entries_generation` FROM `ip_lists` WHERE `id` = :id)"
    )
    fun entryCount(id: Long): Int

    @Query(
        "SELECT * FROM `ip_list_entries` WHERE `ip_list_id` = :id AND `generation` = :generation " +
            "ORDER BY `entry_order` LIMIT :limit OFFSET :offset"
    )
    fun entriesPage(id: Long, generation: Long, limit: Int, offset: Int): List<IpListEntryEntity>

    @Query(
        "SELECT * FROM `ip_list_entries` WHERE `ip_list_id` = :id AND `generation` = :generation " +
            "AND `entry_order` > :after ORDER BY `entry_order` LIMIT :limit"
    )
    fun entriesAfter(id: Long, generation: Long, after: Long, limit: Int): List<IpListEntryEntity>

    /** A scan result list's best entry: measured before unmeasured, then lower loss, lower latency, list order. */
    @Query(
        "SELECT * FROM `ip_list_entries` WHERE `ip_list_id` = :id AND `generation` = :generation " +
            "ORDER BY `latency_ms` <= 0, MAX(COALESCE(`loss_pct`, 0), 0), MAX(`latency_ms`, 0), `entry_order` LIMIT 1"
    )
    fun bestFirstEntry(id: Long, generation: Long): IpListEntryEntity?

    @Query(
        "SELECT * FROM `ip_list_entries` WHERE `ip_list_id` = :id AND `generation` = :generation " +
            "ORDER BY `entry_order` LIMIT 1"
    )
    fun firstEntry(id: Long, generation: Long): IpListEntryEntity?

    /** An UPDATE, never an upsert: a refresh finishing after the list was deleted must not bring it back. */
    @Query(
        "UPDATE `ip_lists` SET `name` = :name, `related_test_id` = :relatedTestId, `role` = :role, " +
            "`source_kind` = :sourceKind, `source` = :source, `auto_update` = :autoUpdate, " +
            "`update_interval` = :updateInterval, `last_update` = :lastUpdate, `last_error` = :lastError, " +
            "`updated_at` = strftime('%s', 'now') WHERE `id` = :id"
    )
    fun updateHeader(
        id: Long, name: String, relatedTestId: Long, role: Int, sourceKind: Int, source: String, autoUpdate: Boolean,
        updateInterval: Int, lastUpdate: Long, lastError: String,
    ): Int

    /** A refresh's outcome only, so an edit saved while the list downloaded stays. */
    @Query(
        "UPDATE `ip_lists` SET `last_update` = :lastUpdate, `last_error` = :lastError, " +
            "`updated_at` = strftime('%s', 'now') WHERE `id` = :id"
    )
    fun saveUpdateResult(id: Long, lastUpdate: Long, lastError: String): Int

    /** Hides the list and unlinks it from its scan in one write. */
    @Query("UPDATE `ip_lists` SET `role` = 2, `related_test_id` = -1, `updated_at` = strftime('%s', 'now') WHERE `id` = :id")
    fun detach(id: Long)

    @Query("UPDATE `ip_lists` SET `sort_order` = :order WHERE `id` = :id")
    fun setSortOrder(id: Long, order: Int)

    /** Snapshot lists whose scan is gone. */
    @Query("SELECT `id` FROM `ip_lists` AS l WHERE `role` = 2 AND NOT EXISTS (SELECT 1 FROM `ip_scans` WHERE `id` = l.`related_test_id`)")
    fun orphanedSnapshots(): List<Long>
}

@Dao
interface IpScanDao {

    @Query("SELECT * FROM `ip_scans` WHERE `id` = :id")
    fun get(id: Long): IpScanEntity?

    @Query("SELECT * FROM `ip_scans` ORDER BY `sort_order`, `id`")
    fun all(): List<IpScanEntity>

    @Query(
        "UPDATE `ip_scans` SET `name` = :name, `kind` = :kind, `base_list_id` = :baseListId, " +
            "`result_list_id` = :resultListId, `config_json` = :configJson, `status` = :status, `mode` = :mode, " +
            "`snapshot_list_id` = :snapshotListId, `seed` = :seed, `cursor` = :cursor, `total` = :total, " +
            "`spec_hash` = :specHash, `rescan_snapshot_list_id` = :rescanSnapshotListId, `rescan_cursor` = :rescanCursor, " +
            "`rescan_total` = :rescanTotal, `found` = :found, `removed` = :removed, `last_error` = :lastError, " +
            "`started_at` = :startedAt, `finished_at` = :finishedAt, `updated_at` = strftime('%s', 'now') WHERE `id` = :id"
    )
    fun update(
        id: Long, name: String, kind: Int, baseListId: Long, resultListId: Long, configJson: String, status: Int, mode: Int,
        snapshotListId: Long, seed: Long, cursor: Long, total: Long, specHash: String, rescanSnapshotListId: Long,
        rescanCursor: Long, rescanTotal: Long, found: Int, removed: Int, lastError: String, startedAt: Long, finishedAt: Long,
    ): Int

    @Query(
        "UPDATE `ip_scans` SET `mode` = :mode, `cursor` = :cursor, `total` = :total, `found` = :found, " +
            "`removed` = :removed, `updated_at` = strftime('%s', 'now') WHERE `id` = :id"
    )
    fun saveInitialProgress(id: Long, mode: Int, cursor: Long, total: Long, found: Int, removed: Int)

    @Query(
        "UPDATE `ip_scans` SET `mode` = :mode, `rescan_cursor` = :cursor, `rescan_total` = :total, `found` = :found, " +
            "`removed` = :removed, `updated_at` = strftime('%s', 'now') WHERE `id` = :id"
    )
    fun saveRescanProgress(id: Long, mode: Int, cursor: Long, total: Long, found: Int, removed: Int)

    @Query("UPDATE `ip_scans` SET `status` = :status, `last_error` = :lastError, `updated_at` = strftime('%s', 'now') WHERE `id` = :id")
    fun saveStatus(id: Long, status: Int, lastError: String)

    /** Name only, so it never overwrites the progress a running scan writes from the other process. */
    @Query("UPDATE `ip_scans` SET `name` = :name, `updated_at` = strftime('%s', 'now') WHERE `id` = :id")
    fun rename(id: Long, name: String): Int

    @Query("UPDATE `ip_scans` SET `result_list_id` = :resultListId, `updated_at` = strftime('%s', 'now') WHERE `id` = :id")
    fun setResultList(id: Long, resultListId: Long): Int

    @Query("DELETE FROM `ip_scans` WHERE `id` = :id")
    fun delete(id: Long)

    @Query("UPDATE `ip_scans` SET `sort_order` = :order WHERE `id` = :id")
    fun setSortOrder(id: Long, order: Int)

    /** Rows still Running (1) after the process that ran them died become Paused (2). */
    @Query("UPDATE `ip_scans` SET `status` = 2 WHERE `status` = 1")
    fun normalizeInterrupted()
}

/**
 * The scanner tables' names and the DDL Room derives from the entities above (its createAllTables text), which the
 * 21 -> 22 migration runs, so a migrated database validates exactly like a new one.
 */
object IpListSchema {
    const val LISTS = "ip_lists"
    const val ENTRIES = "ip_list_entries"
    const val SCANS = "ip_scans"
    const val TARGET_INDEX = "idx_ip_list_entries_target"

    /** The desktop's timestamp default, spelled as its DDL spells it. */
    const val NOW = "(strftime('%s', 'now'))"

    @JvmField
    val CREATE_SQL: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS `ip_lists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL DEFAULT '', `related_test_id` INTEGER NOT NULL DEFAULT -1, `role` INTEGER NOT NULL DEFAULT 0, " +
            "`source_kind` INTEGER NOT NULL DEFAULT 0, `source` TEXT NOT NULL DEFAULT '', " +
            "`auto_update` INTEGER NOT NULL DEFAULT 0, `update_interval` INTEGER NOT NULL DEFAULT 1440, " +
            "`last_update` INTEGER NOT NULL DEFAULT 0, `last_error` TEXT NOT NULL DEFAULT '', " +
            "`sort_order` INTEGER NOT NULL DEFAULT 0, `entries_generation` INTEGER NOT NULL DEFAULT 0, " +
            "`created_at` INTEGER NOT NULL DEFAULT $NOW, `updated_at` INTEGER NOT NULL DEFAULT $NOW)",
        "CREATE TABLE IF NOT EXISTS `ip_list_entries` (`ip_list_id` INTEGER NOT NULL, " +
            "`generation` INTEGER NOT NULL DEFAULT 0, `entry_order` INTEGER NOT NULL, `cidr` TEXT NOT NULL, " +
            "`port` INTEGER NOT NULL DEFAULT 0, `latency_ms` INTEGER NOT NULL DEFAULT 0, `loss_pct` INTEGER, " +
            "PRIMARY KEY(`ip_list_id`, `generation`, `entry_order`), " +
            "FOREIGN KEY(`ip_list_id`) REFERENCES `ip_lists`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE UNIQUE INDEX IF NOT EXISTS `idx_ip_list_entries_target` ON `ip_list_entries` " +
            "(`ip_list_id`, `generation`, `cidr`, `port`)",
        "CREATE TABLE IF NOT EXISTS `ip_scans` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
            "`name` TEXT NOT NULL DEFAULT '', `kind` INTEGER NOT NULL DEFAULT 0, `sort_order` INTEGER NOT NULL DEFAULT 0, " +
            "`base_list_id` INTEGER NOT NULL DEFAULT -1, `result_list_id` INTEGER NOT NULL DEFAULT -1, " +
            "`config_json` TEXT NOT NULL DEFAULT '{}', `status` INTEGER NOT NULL DEFAULT 0, `mode` INTEGER NOT NULL DEFAULT 0, " +
            "`snapshot_list_id` INTEGER NOT NULL DEFAULT -1, `seed` INTEGER NOT NULL DEFAULT 0, " +
            "`cursor` INTEGER NOT NULL DEFAULT 0, `total` INTEGER NOT NULL DEFAULT 0, `spec_hash` TEXT NOT NULL DEFAULT '', " +
            "`rescan_snapshot_list_id` INTEGER NOT NULL DEFAULT -1, `rescan_cursor` INTEGER NOT NULL DEFAULT 0, " +
            "`rescan_total` INTEGER NOT NULL DEFAULT 0, `found` INTEGER NOT NULL DEFAULT 0, " +
            "`removed` INTEGER NOT NULL DEFAULT 0, `last_error` TEXT NOT NULL DEFAULT '', " +
            "`started_at` INTEGER NOT NULL DEFAULT 0, `finished_at` INTEGER NOT NULL DEFAULT 0, " +
            "`created_at` INTEGER NOT NULL DEFAULT $NOW, `updated_at` INTEGER NOT NULL DEFAULT $NOW)",
    )

    @JvmStatic
    fun create(db: SupportSQLiteDatabase) {
        for (sql in CREATE_SQL) db.execSQL(sql)
    }
}
