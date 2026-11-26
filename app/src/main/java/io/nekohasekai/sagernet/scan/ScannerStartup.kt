package io.nekohasekai.sagernet.scan

import io.nekohasekai.sagernet.database.MarkerEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher

/**
 * The scanner's part of app start in the main process: IpListsRepo's leftover purge, DatabaseManager's retired-scan
 * migrations, initDB's default lists and their migrations (Configs.cpp), then the display cache and the list updater.
 * The :bg engine does its own part (normalizeInterrupted, sweepOrphans) before it starts a scan.
 */
object ScannerStartup {

    // MarkersRepo.h: "migration.*" marks a data migration as applied.
    const val MARKER_DEFAULT_IP_LISTS = "migration.default_ip_lists"
    const val MARKER_DEFAULT_RESOLVER_LISTS = "migration.default_resolver_lists"
    const val MARKER_WARP_MASQUE_RANGES = "migration.warp_masque_ranges"
    const val MARKER_RETIRED_SCAN_KINDS = "migration.retired_scan_kinds"
    const val MARKER_RETIRED_WARP_SCANS = "migration.retired_warp_scans"
    const val MARKER_LIST_SEEDS_NOV_2026 = "migration.list_seeds_2026_11"

    @JvmStatic
    fun start() {
        runOnIoDispatcher {
            try {
                ensureDefaults()
            } catch (e: Exception) {
                Logs.w("ScannerStartup", e)
            }
            try {
                EndpointSources.watch()
            } catch (e: Exception) {
                Logs.w("ScannerStartup", e)
            }
            IpListUpdater.schedule()
        }
    }

    private fun marked(key: String): Boolean = SagerDatabase.markerDao.get(key) != null

    private fun mark(key: String) {
        if (!marked(key)) SagerDatabase.markerDao.put(MarkerEntity(key, System.currentTimeMillis() / 1000))
    }

    /** Idempotent and blocking. */
    @JvmStatic
    fun ensureDefaults() {
        IpListsRepo.purgeLeftovers()
        if (!marked(MARKER_RETIRED_SCAN_KINDS) && IpScansRepo.deleteRetiredScans(listOf(0, 3))) mark(MARKER_RETIRED_SCAN_KINDS)
        // Saved WARP scans gave way to the per-profile endpoint finder.
        if (!marked(MARKER_RETIRED_WARP_SCANS) && IpScansRepo.deleteRetiredScans(listOf(IpScan.Kind.Warp.code))) {
            mark(MARKER_RETIRED_WARP_SCANS)
        }
        // Once only, so lists the user deleted stay deleted.
        if (!marked(MARKER_DEFAULT_IP_LISTS)) {
            DefaultIpLists.ensureAll()
            mark(MARKER_DEFAULT_IP_LISTS)
            mark(MARKER_DEFAULT_RESOLVER_LISTS)
            mark(MARKER_WARP_MASQUE_RANGES)
            mark(MARKER_LIST_SEEDS_NOV_2026)
            return
        }
        if (!marked(MARKER_DEFAULT_RESOLVER_LISTS)) {
            DefaultIpLists.ensure(DefaultIpLists.IRAN_RESOLVERS_NAME)
            DefaultIpLists.ensure(DefaultIpLists.RUSSIA_RESOLVERS_NAME)
            mark(MARKER_DEFAULT_RESOLVER_LISTS)
        }
        if (!marked(MARKER_WARP_MASQUE_RANGES) && DefaultIpLists.migrateWarpMasqueRanges()) mark(MARKER_WARP_MASQUE_RANGES)
        if (!marked(MARKER_LIST_SEEDS_NOV_2026) && DefaultIpLists.migrateListSeedsNov2026()) mark(MARKER_LIST_SEEDS_NOV_2026)
    }
}
