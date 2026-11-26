package io.nekohasekai.sagernet.scan

import androidx.sqlite.db.SupportSQLiteStatement
import io.nekohasekai.sagernet.database.IpScanEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.outbound.json.JsonInput
import java.util.concurrent.Callable

/**
 * Configs::IpScansRepo (IpScansRepo.h/.cpp), with the data side of Scanner::ScanManager and ScanWorker's list helpers.
 * Getters return fresh objects; only resolver scans load, rows of the retired kinds stay unseen. Blocking and safe from
 * any thread of either process.
 *
 * Whether a scan runs is the :bg engine's to know: the helpers that the desktop refuses while a scan runs
 * ([deleteScan], [ensureResultList]) leave that check to the caller.
 */
object IpScansRepo {

    private val dao get() = SagerDatabase.ipScanDao

    private const val INSERT =
        "INSERT INTO `ip_scans` (`name`, `kind`, `base_list_id`, `result_list_id`, `config_json`, `status`, `mode`, " +
            "`snapshot_list_id`, `seed`, `cursor`, `total`, `spec_hash`, `rescan_snapshot_list_id`, `rescan_cursor`, " +
            "`rescan_total`, `found`, `removed`, `last_error`, `started_at`, `finished_at`, `sort_order`) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
            "(SELECT COALESCE(MAX(`sort_order`), 0) + 1 FROM `ip_scans`))"

    private inline fun <T> guard(op: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: Exception) {
        Logs.e("IpScansRepo.$op", e)
        fallback
    }

    private fun IpScanEntity.toModel(): IpScan? {
        if (kind != IpScan.Kind.Resolver.code) return null
        val config = IpScan.defaultConfig(IpScan.Kind.Resolver)
        JsonInput.parseObjectOrNull(configJson)?.let { config.fromJson(it) }
        return IpScan(
            id = id, name = name, kind = IpScan.Kind.Resolver, baseListId = baseListId, resultListId = resultListId,
            config = config, status = IpScan.Status.of(status), mode = IpScan.Mode.of(mode), snapshotListId = snapshotListId,
            seed = seed, cursor = cursor, total = total, specHash = specHash, rescanSnapshotListId = rescanSnapshotListId,
            rescanCursor = rescanCursor, rescanTotal = rescanTotal, found = found, removed = removed, lastError = lastError,
            startedAt = startedAt, finishedAt = finishedAt,
        )
    }

    private fun SupportSQLiteStatement.bindScan(scan: IpScan) {
        bindString(1, scan.name)
        bindLong(2, scan.kind.code.toLong())
        bindLong(3, scan.baseListId)
        bindLong(4, scan.resultListId)
        bindString(5, scan.config.toJson().toCompact())
        bindLong(6, scan.status.code.toLong())
        bindLong(7, scan.mode.code.toLong())
        bindLong(8, scan.snapshotListId)
        bindLong(9, scan.seed)
        bindLong(10, scan.cursor)
        bindLong(11, scan.total)
        bindString(12, scan.specHash)
        bindLong(13, scan.rescanSnapshotListId)
        bindLong(14, scan.rescanCursor)
        bindLong(15, scan.rescanTotal)
        bindLong(16, scan.found.toLong())
        bindLong(17, scan.removed.toLong())
        bindString(18, scan.lastError)
        bindLong(19, scan.startedAt)
        bindLong(20, scan.finishedAt)
    }

    @JvmStatic
    fun newIpScan(kind: IpScan.Kind): IpScan = IpScan(kind = kind, config = IpScan.defaultConfig(kind))

    /** AddIpScan: [scan] must be new (id < 0); it goes last in the scan order and gets its id. */
    fun addIpScan(scan: IpScan): Boolean {
        if (scan.id >= 0) return false
        return guard("addIpScan", false) {
            val database = SagerDatabase.instance
            val id = database.runInTransaction(Callable {
                database.openHelper.writableDatabase.compileStatement(INSERT).use { insert ->
                    insert.bindScan(scan)
                    insert.executeInsert()
                }
            })
            if (id <= 0) return@guard false
            scan.id = id
            true
        }
    }

    fun getIpScan(id: Long): IpScan? = guard("getIpScan", null) { dao.get(id)?.toModel() }

    /** GetAllIpScans: in scan order. */
    fun getAllIpScans(): List<IpScan> = guard("getAllIpScans", emptyList()) { dao.all().mapNotNull { it.toModel() } }

    /** Save: the whole row. Not for a scan the other process runs, whose progress it would overwrite. */
    fun saveScan(scan: IpScan): Boolean {
        if (scan.id < 0) return false
        return guard("saveScan", false) {
            dao.update(
                scan.id, scan.name, scan.kind.code, scan.baseListId, scan.resultListId, scan.config.toJson().toCompact(),
                scan.status.code, scan.mode.code, scan.snapshotListId, scan.seed, scan.cursor, scan.total, scan.specHash,
                scan.rescanSnapshotListId, scan.rescanCursor, scan.rescanTotal, scan.found, scan.removed, scan.lastError,
                scan.startedAt, scan.finishedAt,
            ) > 0
        }
    }

    /** SaveProgress: the cursor and total of [mode]'s pass. */
    fun saveProgress(id: Long, mode: IpScan.Mode, cursor: Long, total: Long, found: Int, removed: Int) {
        if (id < 0) return
        guard("saveProgress", Unit) {
            if (mode == IpScan.Mode.RescanResult) dao.saveRescanProgress(id, mode.code, cursor, total, found, removed)
            else dao.saveInitialProgress(id, mode.code, cursor, total, found, removed)
        }
    }

    @JvmOverloads
    fun saveStatus(id: Long, status: IpScan.Status, lastError: String = "") {
        if (id < 0) return
        guard("saveStatus", Unit) { dao.saveStatus(id, status.code, lastError) }
    }

    /** DeleteIpScan: the row only; [deleteScan] also removes the lists the scan owns. */
    fun deleteIpScan(id: Long) {
        guard("deleteIpScan", Unit) { dao.delete(id) }
    }

    /** UpdateIpScansOrder: sort_order 1, 2, ... in the given order. */
    fun updateIpScansOrder(idsInOrder: List<Long>) {
        if (idsInOrder.isEmpty()) return
        guard("updateIpScansOrder", Unit) {
            SagerDatabase.instance.runInTransaction { idsInOrder.forEachIndexed { i, id -> dao.setSortOrder(id, i + 1) } }
        }
    }

    /** NormalizeInterrupted: rows still Running become Paused. Only where no scan can run yet: the :bg engine's start. */
    fun normalizeInterrupted() {
        guard("normalizeInterrupted", Unit) { dao.normalizeInterrupted() }
    }

    /**
     * DeleteRetiredScans: once per migration, drops the rows of [kinds]; their result lists become user lists, their
     * snapshots orphans for [sweepOrphans].
     */
    fun deleteRetiredScans(kinds: List<Int>): Boolean {
        if (kinds.isEmpty()) return true
        val set = kinds.joinToString(", ", "(", ")")
        val retired = "(SELECT `id` FROM `ip_scans` WHERE `kind` IN $set)"
        return guard("deleteRetiredScans", false) {
            val database = SagerDatabase.instance
            database.runInTransaction(Callable {
                val d = database.openHelper.writableDatabase
                d.execSQL(
                    "UPDATE `ip_lists` SET `role` = ?, `related_test_id` = -1, `updated_at` = strftime('%s', 'now') " +
                        "WHERE `role` = ? AND `related_test_id` IN $retired",
                    arrayOf<Any?>(IpList.Role.User.code, IpList.Role.ScanResult.code),
                )
                d.execSQL(
                    "UPDATE `ip_lists` SET `related_test_id` = -1, `updated_at` = strftime('%s', 'now') WHERE `role` = ? AND " +
                        "(`related_test_id` IN $retired OR `id` IN (SELECT `snapshot_list_id` FROM `ip_scans` WHERE `kind` IN $set) " +
                        "OR `id` IN (SELECT `rescan_snapshot_list_id` FROM `ip_scans` WHERE `kind` IN $set))",
                    arrayOf<Any?>(IpList.Role.ScanSnapshot.code),
                )
                d.execSQL("DELETE FROM `ip_scans` WHERE `kind` IN $set")
                true
            })
        }
    }

    // ------------------------------------------------------------------------------------------------ ScanManager

    /** ScanManager::ResultListName. */
    @JvmStatic
    fun resultListName(scanName: String): String = scanName.trim()

    /** EnsureScanResultList: the scan's result list, made when it has none; -1 on failure. The caller persists [IpScan.resultListId]. */
    fun ensureScanResultList(scan: IpScan): Long {
        if (scan.resultListId >= 0) {
            val header = IpListsRepo.getIpListHeader(scan.resultListId)
            if (header != null && header.role == IpList.Role.ScanResult && header.relatedTestId == scan.id) return header.id
        }
        val list = IpList(name = resultListName(scan.name), role = IpList.Role.ScanResult, relatedTestId = scan.id)
        if (!IpListsRepo.addIpList(list)) return -1L
        scan.resultListId = list.id
        return list.id
    }

    /**
     * ScanManager::EnsureResultList for a scan that is not running (a running one's engine makes the list itself): its
     * result list, made when missing; -1 when the scan is gone or no list could be made.
     */
    fun ensureResultList(scanId: Long): Long {
        val scan = getIpScan(scanId) ?: return -1L
        val previous = scan.resultListId
        val listId = ensureScanResultList(scan)
        if (listId >= 0 && listId != previous) guard("ensureResultList", Unit) { dao.setResultList(scanId, listId) }
        return listId
    }

    /**
     * ScanManager::DeleteScan for a scan that is not running (the desktop refuses with "Pause the scan first"): the row,
     * its snapshots, and its result list too with [deleteResultList], else that becomes a user list. Blocking: the lists'
     * rows go chunk by chunk after the row.
     */
    fun deleteScan(scanId: Long, deleteResultList: Boolean) {
        if (scanId < 0) return
        val scan = getIpScan(scanId)
        val snapshotId = scan?.snapshotListId ?: -1L
        val rescanSnapshotId = scan?.rescanSnapshotListId ?: -1L
        val doomed = ArrayList<Long>()
        for (header in IpListsRepo.getAllIpLists(includeHidden = true)) {
            if (header.role == IpList.Role.ScanSnapshot &&
                (header.relatedTestId == scanId || header.id == snapshotId || header.id == rescanSnapshotId)
            ) {
                doomed.add(header.id)
            } else if (header.role == IpList.Role.ScanResult && header.relatedTestId == scanId) {
                if (deleteResultList) {
                    doomed.add(header.id)
                } else {
                    header.role = IpList.Role.User
                    header.relatedTestId = -1L
                    IpListsRepo.saveHeader(header)
                }
            }
        }
        for (id in doomed) IpListsRepo.detachIpList(id)
        deleteIpScan(scanId)
        for (id in doomed) IpListsRepo.deleteIpList(id, onlyIfDetached = true)
    }

    /** ScanManager::RenameScan: the scan and its result list; a blank name changes nothing. Safe while the scan runs. */
    fun renameScan(scanId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val scan = getIpScan(scanId) ?: return
        if (scan.name != trimmed) guard("renameScan", Unit) { dao.rename(scanId, trimmed) }
        val result = if (scan.resultListId >= 0) IpListsRepo.getIpListHeader(scan.resultListId) else null
        if (result != null && result.role == IpList.Role.ScanResult && result.relatedTestId == scanId) {
            val resultName = resultListName(trimmed)
            if (result.name != resultName) {
                result.name = resultName
                IpListsRepo.saveHeader(result)
            }
        }
    }

    /**
     * ReplacePending: whether the old results still wait for the initial pass's first hit to replace them. The flag is
     * the snapshot's last_update, which has no other use there.
     */
    fun replacePending(scan: IpScan): Boolean {
        if (scan.snapshotListId < 0) return false
        val header = IpListsRepo.getIpListHeader(scan.snapshotListId)
        return header != null && header.lastUpdate > 0
    }

    fun clearReplacePending(scan: IpScan) {
        if (scan.snapshotListId < 0) return
        val header = IpListsRepo.getIpListHeader(scan.snapshotListId) ?: return
        if (header.lastUpdate == 0L) return
        header.lastUpdate = 0L
        IpListsRepo.saveHeader(header)
    }

    /** ScannerUi::FindResolvers: the resolver scan of profile [profileId] with the highest id, the one its dialog opens. */
    fun newestResolverScan(profileId: Long): IpScan? =
        getAllIpScans().filter { it.kind == IpScan.Kind.Resolver && it.config.resolverProfileId == profileId }.maxByOrNull { it.id }

    /** Scans whose DNS tunnel profile is gone: nothing can open them any more. Empty when the profiles cannot be read. */
    fun profilelessScans(): List<Long> = guard("profilelessScans", emptyList()) {
        val scans = getAllIpScans()
        if (scans.isEmpty()) return@guard emptyList()
        val profileIds = scans.map { it.config.resolverProfileId }.filter { it >= 0 }.distinct()
        val existing = HashSet<Long>()
        for (chunk in profileIds.chunked(900)) existing.addAll(SagerDatabase.proxyDao.existingIds(chunk))
        scans.filter { it.config.resolverProfileId !in existing }.map { it.id }
    }

    /**
     * ScanManager's startup sweep, for the :bg engine before it starts any scan: scan snapshots whose scan is gone (and
     * the leftovers of interrupted deletes) are deleted, then the scans whose profile is gone. Blocking.
     */
    fun sweepOrphans() {
        for (id in IpListsRepo.orphanedIpLists()) {
            IpListsRepo.detachIpList(id)
            IpListsRepo.deleteIpList(id, onlyIfDetached = true)
        }
        for (scanId in profilelessScans()) deleteScan(scanId, deleteResultList = false)
    }
}
