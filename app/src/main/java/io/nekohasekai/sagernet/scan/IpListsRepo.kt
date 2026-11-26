package io.nekohasekai.sagernet.scan

import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement
import io.nekohasekai.sagernet.database.IpListEntity
import io.nekohasekai.sagernet.database.IpListEntryEntity
import io.nekohasekai.sagernet.database.IpListSchema
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Callable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Configs::IpListsRepo (IpListsRepo.h/.cpp) on the shared Room database. Getters return fresh objects. Everything is
 * safe from any thread of either process but blocks, so callers stay off the main thread.
 *
 * Readers only see a list's `entries_generation`. A bulk write fills another generation in chunks, each its own short
 * transaction, and flips to it with its last chunk. Writes that change the generation run one at a time per list
 * within a process (the desktop's ListClaim); across processes every chunk re-checks that the generation the write
 * started from is still live, so a write from the other process, a backup restore or a delete in between makes it
 * give up and remove what it staged.
 */
object IpListsRepo {

    /** Keeps every write transaction far below SQLite's busy timeout. */
    private const val CHUNK_ROWS = 20000

    private const val INSERT_ENTRY =
        "INSERT OR IGNORE INTO `ip_list_entries` (`ip_list_id`, `generation`, `entry_order`, `cidr`, `port`, " +
            "`latency_ms`, `loss_pct`) VALUES (?, ?, ?, ?, ?, ?, ?)"

    /** The tables [addObserver] watches. */
    @JvmField
    val TABLES: Array<String> = arrayOf(IpListSchema.LISTS, IpListSchema.ENTRIES, IpListSchema.SCANS)

    private val db get() = SagerDatabase.instance
    private val raw: SupportSQLiteDatabase get() = SagerDatabase.instance.openHelper.writableDatabase
    private val dao get() = SagerDatabase.ipListDao

    // Must match IpListDao.bestFirstEntry: measured before unmeasured, then lower loss (unknown as 0), lower latency.
    private val BEST_FIRST: Comparator<IpListEntry> =
        compareBy({ it.latencyMs <= 0 }, { maxOf(it.lossPct, 0) }, { maxOf(it.latencyMs, 0) })

    // ------------------------------------------------------------------------------------------------ plumbing

    private class Rollback : RuntimeException(null, null, false, false)

    private class Read<T>(@JvmField val value: T)

    private class Staged(@JvmField val generation: Long, @JvmField val written: Int)

    private class Pace {
        @JvmField var lastEnd = 0L
        @JvmField var lastHeld = 0L
    }

    private val pace = object : ThreadLocal<Pace>() {
        override fun initialValue() = Pace()
    }

    private val claimLock = ReentrantLock()
    private val claimReleased = claimLock.newCondition()
    private val claimed = HashSet<Long>()

    private inline fun <T> guard(op: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        Logs.e("IpListsRepo.$op", e)
        fallback
    }

    /** One write transaction; a null result rolls it back. */
    private fun <T : Any> tx(body: (SupportSQLiteDatabase) -> T?): T? {
        val database = db
        return try {
            database.runInTransaction(Callable { body(database.openHelper.writableDatabase) ?: throw Rollback() })
        } catch (_: Rollback) {
            null
        }
    }

    // Neither the connection pool nor SQLite's busy wait is fair: back-to-back chunks first wait half the last hold (1..50 ms).
    private fun <T : Any> chunk(body: (SupportSQLiteDatabase) -> T?): T? {
        val p = pace.get()!!
        val gap = (p.lastHeld / 2).coerceIn(1_000_000L, 50_000_000L)
        val idle = System.nanoTime() - p.lastEnd
        if (idle in 0 until gap) {
            val wait = gap - idle
            Thread.sleep(wait / 1_000_000L, (wait % 1_000_000L).toInt())
        }
        val start = System.nanoTime()
        try {
            return tx(body)
        } finally {
            p.lastEnd = System.nanoTime()
            p.lastHeld = p.lastEnd - start
        }
    }

    /** ListClaim: [body] is this process's only generation-changing write to list [id] while it runs. */
    private fun <T> claim(id: Long, body: () -> T): T {
        claimLock.withLock {
            while (id in claimed) claimReleased.awaitUninterruptibly()
            claimed.add(id)
        }
        try {
            return body()
        } finally {
            claimLock.withLock {
                claimed.remove(id)
                claimReleased.signalAll()
            }
        }
    }

    private fun SupportSQLiteDatabase.longOrNull(sql: String, vararg args: Any?): Long? =
        query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }

    private fun SupportSQLiteDatabase.liveGeneration(id: Long): Long =
        longOrNull("SELECT `entries_generation` FROM `ip_lists` WHERE `id` = ?", id) ?: -1L

    private fun SupportSQLiteDatabase.highestGeneration(id: Long): Long = longOrNull(
        "SELECT `generation` FROM `ip_list_entries` WHERE `ip_list_id` = ? ORDER BY `generation` DESC LIMIT 1", id,
    ) ?: -1L

    private fun SupportSQLiteDatabase.setGeneration(id: Long, generation: Long) = execSQL(
        "UPDATE `ip_lists` SET `entries_generation` = ?, `updated_at` = strftime('%s', 'now') WHERE `id` = ?",
        arrayOf<Any?>(generation, id),
    )

    private fun SupportSQLiteStatement.bindEntry(id: Long, generation: Long, order: Long, entry: IpListEntry) {
        bindLong(1, id)
        bindLong(2, generation)
        bindLong(3, order)
        bindString(4, entry.cidr)
        bindLong(5, entry.port.toLong())
        bindLong(6, entry.latencyMs.toLong())
        if (entry.lossPct < 0) bindNull(7) else bindLong(7, entry.lossPct.toLong())
    }

    /** Entries [begin, end) of [entries] as rows of [generation], each at its index; returns how many were new. */
    private fun SupportSQLiteDatabase.insertEntries(
        id: Long, generation: Long, entries: List<IpListEntry>, begin: Int, end: Int,
    ): Int {
        if (begin >= end) return 0
        var written = 0
        compileStatement(INSERT_ENTRY).use { insert ->
            for (i in begin until end) {
                insert.bindEntry(id, generation, i.toLong(), entries[i])
                written += insert.executeUpdateDelete()
            }
        }
        return written
    }

    private fun SupportSQLiteDatabase.insertHeader(list: IpList): Long = compileStatement(
        "INSERT INTO `ip_lists` (`name`, `related_test_id`, `role`, `source_kind`, `source`, `auto_update`, " +
            "`update_interval`, `last_update`, `last_error`, `sort_order`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, " +
            "(SELECT COALESCE(MAX(`sort_order`), 0) + 1 FROM `ip_lists`))"
    ).use { insert ->
        insert.bindString(1, list.name)
        insert.bindLong(2, list.relatedTestId)
        insert.bindLong(3, list.role.code.toLong())
        insert.bindLong(4, list.sourceKind.code.toLong())
        insert.bindString(5, list.source)
        insert.bindLong(6, if (list.autoUpdate) 1L else 0L)
        insert.bindLong(7, list.updateInterval.toLong())
        insert.bindLong(8, list.lastUpdate)
        insert.bindString(9, list.lastError)
        insert.executeInsert()
    }

    private fun unique(entries: List<IpListEntry>): List<IpListEntry> {
        val seen = HashSet<String>(entries.size * 2)
        val out = ArrayList<IpListEntry>(entries.size)
        for (entry in entries) if (entry.cidr.isNotEmpty() && seen.add(entry.cidr + " " + entry.port)) out.add(entry)
        return out
    }

    private fun IpListEntity.toModel(count: Int = 0) = IpList(
        id = id, name = name, relatedTestId = relatedTestId, role = IpList.Role.of(role),
        sourceKind = IpList.SourceKind.of(sourceKind), source = source, autoUpdate = autoUpdate,
        updateInterval = updateInterval, lastUpdate = lastUpdate, lastError = lastError, entryCount = count,
    )

    private fun IpListEntryEntity.toModel() = IpListEntry(cidr, port, latencyMs, lossPct ?: -1)

    // ------------------------------------------------------------------------------------------------ generations

    /**
     * Writes [entries] (already unique) into a new generation of list [id] and makes it live with the last chunk; null,
     * with the staged rows removed, once [current] is no longer the live generation.
     */
    private fun stage(id: Long, current: Long, entries: List<IpListEntry>): Staged? {
        var target = -1L
        var written = 0
        var begin = 0
        try {
            do {
                val end = minOf(begin + CHUNK_ROWS, entries.size)
                val done = chunk { d ->
                    if (d.liveGeneration(id) != current) return@chunk null
                    // Above every generation, so it never meets the leftovers of a write cut short.
                    val generation = if (target >= 0) target else maxOf(current, d.highestGeneration(id)) + 1
                    val rows = d.insertEntries(id, generation, entries, begin, end)
                    if (end == entries.size) d.setGeneration(id, generation)
                    longArrayOf(generation, rows.toLong())
                }
                if (done == null) {
                    if (target >= 0) purgeGeneration(id, target)
                    return null
                }
                target = done[0]
                written += done[1].toInt()
                begin = end
            } while (begin < entries.size)
        } catch (e: Exception) {
            if (target >= 0) runCatching { purgeGeneration(id, target) }
            throw e
        }
        return Staged(target, written)
    }

    /** Deletes [generation] of list [id] chunk by chunk; false once it is the live one (a restore can make it so). */
    private fun purgeGeneration(id: Long, generation: Long): Boolean {
        while (true) {
            val more = chunk { d ->
                if (d.liveGeneration(id) == generation) return@chunk null
                val bound = d.longOrNull(
                    "SELECT `entry_order` FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? " +
                        "ORDER BY `entry_order` LIMIT 1 OFFSET ?",
                    id, generation, CHUNK_ROWS - 1,
                )
                if (bound != null) {
                    d.execSQL(
                        "DELETE FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? AND `entry_order` <= ?",
                        arrayOf<Any?>(id, generation, bound),
                    )
                } else {
                    d.execSQL(
                        "DELETE FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ?",
                        arrayOf<Any?>(id, generation),
                    )
                }
                bound != null
            } ?: return false
            if (!more) return true
        }
    }

    /**
     * Purges the generations of list [id] below [keep], and above it too with [above]. Only a write's own staging lies
     * above the live generation, so nothing else purges there while the other process may be staging.
     */
    private fun purgeOther(id: Long, keep: Long, above: Boolean): Boolean {
        while (true) {
            val stale = raw.longOrNull(
                "SELECT `generation` FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` < ? LIMIT 1", id, keep,
            ) ?: (if (above) {
                raw.longOrNull(
                    "SELECT `generation` FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` > ? LIMIT 1", id, keep,
                )
            } else null) ?: return true
            if (!purgeGeneration(id, stale)) return false
        }
    }

    /** Moves list [id] to a new, empty generation above every other; -1 when it is gone, or not detached with [requireDetached]. */
    private fun bump(id: Long, requireDetached: Boolean): Long = tx { d ->
        if (requireDetached && d.longOrNull(
                "SELECT 1 FROM `ip_lists` WHERE `id` = ? AND `role` = ? AND `related_test_id` = -1",
                id, IpList.Role.ScanSnapshot.code,
            ) == null
        ) return@tx null
        val current = d.liveGeneration(id)
        if (current < 0) return@tx null
        val fresh = maxOf(current, d.highestGeneration(id)) + 1
        d.setGeneration(id, fresh)
        fresh
    } ?: -1L

    /**
     * Removes list [id], its rows chunk by chunk and the header last. With [expected] >= 0 the header goes only while
     * that is still its generation: a restore, or a replace from the other process, brought the list back meanwhile.
     */
    private fun discard(id: Long, expected: Long = -1L) {
        val purged = purgeOther(id, expected, above = expected < 0)
        tx { d ->
            if (expected < 0) {
                d.execSQL("DELETE FROM `ip_lists` WHERE `id` = ?", arrayOf<Any?>(id))
            } else if (purged) {
                d.execSQL("DELETE FROM `ip_lists` WHERE `id` = ? AND `entries_generation` = ?", arrayOf<Any?>(id, expected))
            }
            true
        }
    }

    /** The rows of [generation] from [offset] on, at most [limit] (< 0: all); null once a replace made another generation live. */
    private fun readGeneration(id: Long, generation: Long, offset: Int, limit: Int): MutableList<IpListEntry>? {
        val out = ArrayList<IpListEntry>(if (limit > 0) minOf(limit, CHUNK_ROWS) else 16)
        var remaining = if (limit < 0) Long.MAX_VALUE else limit.toLong()
        var after = -1L
        while (remaining > 0) {
            val page = minOf(remaining, CHUNK_ROWS.toLong()).toInt()
            val rows = if (after < 0) dao.entriesPage(id, generation, page, maxOf(offset, 0))
            else dao.entriesAfter(id, generation, after, page)
            if (dao.generation(id) != generation) return null
            for (row in rows) out.add(row.toModel())
            if (rows.isNotEmpty()) after = rows.last().entryOrder
            if (rows.size < page) break
            remaining -= rows.size
        }
        return out
    }

    /** [read] returns null when a replace published between two of its pages; the retry runs in one transaction, where none can. */
    private fun <T> readConsistent(read: () -> Read<T>?): Read<T>? = read() ?: tx { read() }

    private fun <T> slice(list: List<T>, offset: Int, limit: Int): List<T> {
        val from = maxOf(offset, 0)
        if (from >= list.size || limit == 0) return emptyList()
        val to = if (limit < 0) list.size else minOf(list.size.toLong(), from.toLong() + limit).toInt()
        return ArrayList(list.subList(from, to))
    }

    // ------------------------------------------------------------------------------------------------ lists

    /** AddIpList: [list] must be new (id < 0); its unique entries go in with it. Sets id, entryCount and entriesLoaded. */
    fun addIpList(list: IpList): Boolean {
        if (list.id >= 0) return false
        val entries = unique(list.entries)
        val staged = entries.size > CHUNK_ROWS
        return guard("addIpList", false) {
            var written = 0
            val id = tx { d ->
                val newId = d.insertHeader(list)
                if (newId <= 0) return@tx null
                if (!staged) written = d.insertEntries(newId, 0L, entries, 0, entries.size)
                newId
            } ?: return@guard false
            if (staged) {
                val done = try {
                    claim(id) { stage(id, 0L, entries) }
                } catch (e: Exception) {
                    runCatching { discard(id) }
                    throw e
                }
                if (done == null) {
                    discard(id)
                    return@guard false
                }
                written = done.written
            }
            list.id = id
            if (entries.size != list.entries.size) list.entries = entries
            list.entryCount = written
            list.entriesLoaded = true
            true
        }
    }

    /** GetIpList: the header with every entry; a scan result list reads best first. */
    fun getIpList(id: Long): IpList? = guard("getIpList", null) {
        readConsistent {
            val header = dao.get(id) ?: return@readConsistent Read<IpList?>(null)
            val entries = readGeneration(id, header.entriesGeneration, 0, -1) ?: return@readConsistent null
            val list = header.toModel()
            if (list.role == IpList.Role.ScanResult) entries.sortWith(BEST_FIRST)
            list.entries = entries
            list.entryCount = entries.size
            list.entriesLoaded = true
            Read<IpList?>(list)
        }?.value
    }

    /** GetIpListHeader: no entries and no count (entryCount stays 0); hidden lists too. */
    fun getIpListHeader(id: Long): IpList? = guard("getIpListHeader", null) { dao.get(id)?.toModel() }

    /** GetAllIpLists: headers with their entry counts in list order; scan snapshots only with [includeHidden]. */
    @JvmOverloads
    fun getAllIpLists(includeHidden: Boolean = false): List<IpList> = guard("getAllIpLists", emptyList()) {
        (if (includeHidden) dao.allWithCounts() else dao.visibleWithCounts()).map { it.list.toModel(it.entryCount) }
    }

    /**
     * GetEntries: [limit] < 0 reads to the end. Scan result lists read best first: measured before unmeasured, then lower
     * loss (unknown as 0), then lower latency, then list order; a page of one is cut from the whole list, sorted.
     */
    @JvmOverloads
    fun getEntries(id: Long, offset: Int = 0, limit: Int = -1): List<IpListEntry> = guard("getEntries", emptyList()) {
        readConsistent {
            val header = dao.get(id) ?: return@readConsistent Read<List<IpListEntry>>(emptyList())
            val whole = header.role == IpList.Role.ScanResult.code
            val entries = readGeneration(id, header.entriesGeneration, if (whole) 0 else offset, if (whole) -1 else limit)
                ?: return@readConsistent null
            if (!whole) return@readConsistent Read<List<IpListEntry>>(entries)
            entries.sortWith(BEST_FIRST)
            Read<List<IpListEntry>>(slice(entries, offset, limit))
        }?.value ?: emptyList()
    }

    /** GetBestEntry: the first entry [getEntries] returns. */
    fun getBestEntry(id: Long): IpListEntry? = guard("getBestEntry", null) {
        readConsistent {
            val header = dao.get(id) ?: return@readConsistent Read<IpListEntry?>(null)
            val generation = header.entriesGeneration
            val row = if (header.role == IpList.Role.ScanResult.code) dao.bestFirstEntry(id, generation)
            else dao.firstEntry(id, generation)
            if (row == null && dao.generation(id) != generation) return@readConsistent null
            Read<IpListEntry?>(row?.toModel())
        }?.value
    }

    fun entryCount(id: Long): Int = guard("entryCount", 0) { dao.entryCount(id) }

    /** SaveHeader: an UPDATE, never an upsert, so a refresh finishing after the list was deleted cannot bring it back. */
    fun saveHeader(list: IpList): Boolean {
        if (list.id < 0) return false
        return guard("saveHeader", false) {
            dao.updateHeader(
                list.id, list.name, list.relatedTestId, list.role.code, list.sourceKind.code, list.source, list.autoUpdate,
                list.updateInterval, list.lastUpdate, list.lastError,
            ) > 0
        }
    }

    /** A refresh's outcome (last_update, last_error) without touching the rest of the header; false when the list is gone. */
    fun saveUpdateResult(id: Long, lastUpdate: Long, lastError: String): Boolean {
        if (id < 0) return false
        return guard("saveUpdateResult", false) { dao.saveUpdateResult(id, lastUpdate, lastError) > 0 }
    }

    /** ReplaceEntries: atomic for readers; the first of duplicate (cidr, port) pairs wins. False when the list is gone. */
    fun replaceEntries(id: Long, entries: List<IpListEntry>): Boolean {
        if (id < 0) return false
        val fresh = unique(entries)
        return guard("replaceEntries", false) {
            claim(id) {
                val current = dao.generation(id) ?: return@claim false
                val staged = stage(id, current, fresh) ?: return@claim false
                purgeOther(id, staged.generation, above = false)
                true
            }
        }
    }

    /** UpsertEntries: existing (cidr, port) pairs take the new latency and loss; returns how many were appended. */
    fun upsertEntries(id: Long, entries: List<IpListEntry>): Int {
        if (id < 0 || entries.isEmpty()) return 0
        return guard("upsertEntries", 0) {
            var appended = 0
            var pinned = -1L
            var begin = 0
            while (begin < entries.size) {
                val end = minOf(begin + CHUNK_ROWS, entries.size)
                val added = chunk { d ->
                    val generation = d.liveGeneration(id)
                    // Replaced between two chunks: the rest would land in a list this call never saw.
                    if (generation < 0 || (pinned >= 0 && generation != pinned)) return@chunk null
                    pinned = generation
                    var order = (d.longOrNull(
                        "SELECT `entry_order` FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? " +
                            "ORDER BY `entry_order` DESC LIMIT 1",
                        id, generation,
                    ) ?: -1L) + 1
                    var count = 0
                    d.compileStatement(INSERT_ENTRY).use { insert ->
                        d.compileStatement(
                            "UPDATE `ip_list_entries` SET `latency_ms` = ?, `loss_pct` = ? " +
                                "WHERE `ip_list_id` = ? AND `generation` = ? AND `cidr` = ? AND `port` = ?"
                        ).use { update ->
                            for (i in begin until end) {
                                val entry = entries[i]
                                if (entry.cidr.isEmpty()) continue
                                insert.bindEntry(id, generation, order, entry)
                                if (insert.executeUpdateDelete() > 0) {
                                    order++
                                    count++
                                    continue
                                }
                                update.bindLong(1, entry.latencyMs.toLong())
                                if (entry.lossPct < 0) update.bindNull(2) else update.bindLong(2, entry.lossPct.toLong())
                                update.bindLong(3, id)
                                update.bindLong(4, generation)
                                update.bindString(5, entry.cidr)
                                update.bindLong(6, entry.port.toLong())
                                update.executeUpdateDelete()
                            }
                        }
                    }
                    count
                } ?: break
                appended += added
                begin = end
            }
            appended
        }
    }

    /** RemoveEntries: by (cidr, port); returns how many were removed. */
    fun removeEntries(id: Long, entries: List<IpListEntry>): Int {
        if (id < 0 || entries.isEmpty()) return 0
        return guard("removeEntries", 0) {
            var removed = 0
            var pinned = -1L
            var begin = 0
            while (begin < entries.size) {
                val end = minOf(begin + CHUNK_ROWS, entries.size)
                val gone = chunk { d ->
                    val generation = d.liveGeneration(id)
                    if (generation < 0 || (pinned >= 0 && generation != pinned)) return@chunk null
                    pinned = generation
                    var count = 0
                    d.compileStatement(
                        "DELETE FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? AND `cidr` = ? AND `port` = ?"
                    ).use { delete ->
                        for (i in begin until end) {
                            val entry = entries[i]
                            delete.bindLong(1, id)
                            delete.bindLong(2, generation)
                            delete.bindString(3, entry.cidr)
                            delete.bindLong(4, entry.port.toLong())
                            count += delete.executeUpdateDelete()
                        }
                    }
                    count
                } ?: break
                removed += gone
                begin = end
            }
            removed
        }
    }

    /** ClearEntries: readers see the list empty at once; the rows go chunk by chunk. */
    fun clearEntries(id: Long) {
        if (id < 0) return
        guard("clearEntries", Unit) {
            claim(id) {
                val fresh = bump(id, requireDetached = false)
                if (fresh >= 0) purgeOther(id, fresh, above = false)
            }
        }
    }

    /** DetachIpList: hides the list and unlinks it from its scan in one write, so nothing adopts it during a slow delete. */
    fun detachIpList(id: Long) {
        if (id < 0) return
        guard("detachIpList", Unit) { dao.detach(id) }
    }

    /**
     * DeleteIpList: readers see the list empty while its rows go chunk by chunk; the header goes last. [onlyIfDetached]
     * skips a list that is no longer detached (a backup restore brought it back).
     */
    @JvmOverloads
    fun deleteIpList(id: Long, onlyIfDetached: Boolean = false) {
        if (id < 0) return
        guard("deleteIpList", Unit) {
            claim(id) {
                val fresh = bump(id, onlyIfDetached)
                if (fresh >= 0) discard(id, fresh)
            }
        }
    }

    /** OrphanedIpLists: scan snapshots whose scan is gone. */
    fun orphanedIpLists(): List<Long> = guard("orphanedIpLists", emptyList()) { dao.orphanedSnapshots() }

    /** UpdateIpListsOrder: sort_order 1, 2, ... in the given order. */
    fun updateIpListsOrder(idsInOrder: List<Long>) {
        if (idsInOrder.isEmpty()) return
        guard("updateIpListsOrder", Unit) {
            db.runInTransaction { idsInOrder.forEachIndexed { i, id -> dao.setSortOrder(id, i + 1) } }
        }
    }

    /**
     * CopyAsUserList: a new user list named [name] holding [sourceId]'s entries, or null. Scan results are copied best
     * first, since a user list reads in list order. Fails when the other process replaces the source midway.
     */
    fun copyAsUserList(sourceId: Long, name: String): IpList? {
        if (sourceId < 0) return null
        return guard("copyAsUserList", null) {
            if (dao.get(sourceId)?.role == IpList.Role.ScanResult.code) {
                val copy = IpList(name = name, entries = getEntries(sourceId))
                return@guard if (addIpList(copy)) copy else null
            }
            val id = claim(sourceId) { copyRows(sourceId, name) } ?: return@guard null
            getIpList(id)
        }
    }

    /** The rows of [sourceId]'s live generation into a new list, keeping their order, chunk by chunk. */
    private fun copyRows(sourceId: Long, name: String): Long? {
        var sourceGeneration = -1L
        val id = tx { d ->
            sourceGeneration = d.liveGeneration(sourceId)
            if (sourceGeneration < 0) return@tx null
            d.insertHeader(IpList(name = name)).takeIf { it > 0 }
        } ?: return null
        return claim(id) {
            var after = -1L
            var last = false
            try {
                while (!last) {
                    val copied = chunk { d ->
                        if (d.liveGeneration(id) != 0L || d.liveGeneration(sourceId) != sourceGeneration) return@chunk null
                        val bound = d.longOrNull(
                            "SELECT `entry_order` FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? " +
                                "AND `entry_order` > ? ORDER BY `entry_order` LIMIT 1 OFFSET ?",
                            sourceId, sourceGeneration, after, CHUNK_ROWS - 1,
                        )
                        d.execSQL(
                            "INSERT INTO `ip_list_entries` (`ip_list_id`, `generation`, `entry_order`, `cidr`, `port`, " +
                                "`latency_ms`, `loss_pct`) SELECT ?, 1, `entry_order`, `cidr`, `port`, `latency_ms`, `loss_pct` " +
                                "FROM `ip_list_entries` WHERE `ip_list_id` = ? AND `generation` = ? AND `entry_order` > ? " +
                                "AND `entry_order` <= ?",
                            arrayOf<Any?>(id, sourceId, sourceGeneration, after, bound ?: Long.MAX_VALUE),
                        )
                        if (bound == null) d.setGeneration(id, 1L)
                        longArrayOf(bound ?: -1L)
                    }
                    if (copied == null) {
                        discard(id)
                        return@claim null
                    }
                    last = copied[0] < 0
                    if (!last) after = copied[0]
                }
            } catch (e: Exception) {
                runCatching { discard(id) }
                throw e
            }
            id
        }
    }

    /** Rows below a list's live generation, the leftovers of a write cut short; safe while the other process writes. */
    fun purgeLeftovers() {
        guard("purgeLeftovers", Unit) {
            val stale = ArrayList<LongArray>()
            raw.query(
                "SELECT `id`, `entries_generation` FROM `ip_lists` AS l WHERE EXISTS (SELECT 1 FROM `ip_list_entries` " +
                    "WHERE `ip_list_id` = l.`id` AND `generation` < l.`entries_generation`)"
            ).use { c -> while (c.moveToNext()) stale.add(longArrayOf(c.getLong(0), c.getLong(1))) }
            for (row in stale) purgeOther(row[0], row[1], above = false)
        }
    }

    // ------------------------------------------------------------------------------------------------ observing

    /**
     * Calls [onChange] on Room's thread after writes to the IP list and scan tables from either process. Blocking:
     * call it off the main thread, and keep the result for [removeObserver].
     */
    fun addObserver(onChange: (Set<String>) -> Unit): InvalidationTracker.Observer {
        val observer = object : InvalidationTracker.Observer(TABLES) {
            override fun onInvalidated(tables: Set<String>) = onChange(tables)
        }
        db.invalidationTracker.addObserver(observer)
        return observer
    }

    fun removeObserver(observer: InvalidationTracker.Observer) {
        db.invalidationTracker.removeObserver(observer)
    }
}
