package io.nekohasekai.sagernet.ui.scan

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.activity.result.ActivityResultLauncher
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.scan.StartMode
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.scan.IpListsRepo
import io.nekohasekai.sagernet.scan.IpScan
import io.nekohasekai.sagernet.scan.IpScansRepo
import io.nekohasekai.sagernet.scan.ScanProfiles
import io.nekohasekai.sagernet.ui.iplist.EndpointSourceSection
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/** ScannerUi.cpp: the status line, the start questions and "Find resolvers", shared by the scan screens. */
object ScannerUi {

    /** Scanner::ScanLiveState as the status line reads it: a running scan's live state, or its row's ([rowState]). */
    class Live(
        @JvmField val running: Boolean = false,
        @JvmField val stopping: Boolean = false,
        @JvmField val stage: String = "",
        @JvmField val tested: Long = 0L,
        @JvmField val total: Long = 0L,
        @JvmField val found: Int = 0,
        @JvmField val removed: Int = 0,
        @JvmField val error: String = "",
        @JvmField val log: List<String> = emptyList(),
    )

    class StatusLine(@JvmField val text: String, @JvmField val error: Boolean)

    fun isResumable(scan: IpScan): Boolean {
        if (scan.mode == IpScan.Mode.RescanResult && scan.rescanSnapshotListId >= 0 && scan.rescanTotal != 0L &&
            java.lang.Long.compareUnsigned(scan.rescanCursor, scan.rescanTotal) < 0
        ) return true
        return scan.canResumeInitial()
    }

    /** Blocking. */
    fun resultCount(scan: IpScan): Int = if (scan.resultListId < 0) 0 else IpListsRepo.entryCount(scan.resultListId)

    fun rowState(scan: IpScan) = Live(
        tested = scan.activeCursor,
        total = scan.activeTotal,
        found = scan.found,
        removed = scan.removed,
        error = scan.lastError,
    )

    /** A saved scan's state: the live one while [run] runs, its row's otherwise (a stopped run's state may lag the row). */
    fun live(scan: IpScan, run: ScanClient.RunState?): Live = if (run?.running == true) {
        Live(true, run.stopping, run.stage, run.tested, run.total, run.found, run.removed, run.error, run.log)
    } else {
        rowState(scan)
    }

    fun permille(tested: Long, total: Long): Int =
        if (total <= 0L) 0 else (tested.toDouble() * 1000 / total.toDouble()).toInt().coerceIn(0, 1000)

    fun statusLine(context: Context, scan: IpScan, live: Live, withStage: Boolean): StatusLine {
        val parts = ArrayList<String>()
        var showCounts = true
        if (live.running) {
            parts.add(context.getString(if (live.stopping) R.string.scan_status_pausing else R.string.scan_status_running))
            if (withStage && !live.stopping && live.stage.isNotEmpty()) parts.add(live.stage)
        } else {
            when (scan.status) {
                IpScan.Status.Idle -> {
                    parts.add(context.getString(R.string.scan_status_idle))
                    showCounts = false
                }

                IpScan.Status.Running, IpScan.Status.Paused -> parts.add(context.getString(R.string.scan_status_paused))
                IpScan.Status.Completed -> parts.add(context.getString(R.string.scan_status_completed))
                IpScan.Status.Failed -> parts.add(context.getString(R.string.scan_status_failed))
            }
        }
        if (showCounts) {
            val finished = !live.running && scan.status == IpScan.Status.Completed
            if (live.total > 0 && !finished) {
                parts.add(
                    context.getString(
                        R.string.scan_progress_count, live.tested.toString(), live.total.toString(),
                        permille(live.tested, live.total) / 10,
                    )
                )
            }
            if (live.found > 0 || finished) parts.add(context.resources.getQuantityString(R.plurals.scan_found, live.found, live.found))
            if (live.removed > 0) parts.add(context.resources.getQuantityString(R.plurals.scan_removed, live.removed, live.removed))
        }
        val error = live.error.isNotEmpty()
        if (error) parts.add(live.error)
        return StatusLine(parts.joinToString(" · "), error)
    }

    /** A Yes/No question; false when dismissed. The dialog goes with the caller's coroutine. */
    suspend fun ask(context: Context, title: CharSequence, message: CharSequence): Boolean =
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(R.string.yes) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton(R.string.no) { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                .show()
            continuation.invokeOnCancellation { runOnMainDispatcher { dialog.dismiss() } }
        }

    fun notice(context: Context, title: CharSequence, message: CharSequence) {
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    suspend fun confirmStartOver(context: Context, scan: IpScan): Boolean {
        val text = if (isResumable(scan)) {
            context.getString(R.string.scan_confirm_discard_progress)
        } else {
            val results = withContext(Dispatchers.IO) { resultCount(scan) }
            if (results <= 0) return true
            context.resources.getQuantityString(R.plurals.scan_confirm_keep_results, results, results)
        }
        return ask(context, context.getString(R.string.scan_resolver_title), text)
    }

    /** ScannerUi::StartScan: a resume the changed targets refuse is offered as a start over. */
    suspend fun startScan(context: Context, scanId: Long, mode: StartMode): Boolean {
        val title = context.getString(R.string.scan_resolver_title)
        var result = ScanClient.start(scanId, mode)
        if (result.targetsChanged) {
            if (!ask(context, title, context.getString(R.string.scan_confirm_targets_changed))) return false
            result = ScanClient.start(scanId, StartMode.FromInitial)
        }
        if (!result.ok) {
            notice(context, title, result.error.ifEmpty { context.getString(R.string.scan_start_failed) })
            return false
        }
        return true
    }

    private class Prepared(val scanId: Long, val error: String)

    private const val LAUNCH_GUARD_MS = 2000L

    /** When a scan screen was asked for and is not created yet (elapsedRealtime), 0 when none; main thread. */
    private var launchingAt = 0L

    /** False while another scan screen is on its way: a second tap meanwhile would open a second screen. */
    private fun beginLaunch(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (launchingAt != 0L && now - launchingAt < LAUNCH_GUARD_MS) return false
        launchingAt = now
        return true
    }

    /** The scan screen exists now, or the launch was given up. */
    internal fun launchDone() {
        launchingAt = 0L
    }

    /**
     * ScannerUi::FindResolvers: opens the newest resolver scan of saved DNS tunnel profile [profileId], or a new one.
     * Called from that profile's editor, the screen hands "Use as resolvers" to the editor ([takeResolverList]).
     */
    @JvmStatic
    fun findResolvers(context: Context, profileId: Long) {
        if (!beginLaunch()) return
        val fromEditor = context is ProfileSettingsActivity<*> && DataStore.editingId == profileId
        val block: suspend CoroutineScope.() -> Unit = {
            var launched = false
            try {
                val prepared = withContext(Dispatchers.IO) { prepareResolverScan(context, profileId) }
                if (prepared.error.isNotEmpty()) {
                    launchDone()
                    notice(context, context.getString(R.string.scan_resolver_title), prepared.error)
                } else {
                    val intent = ResolverScanActivity.intent(context, prepared.scanId, fromEditor)
                    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    launched = true
                }
            } finally {
                if (!launched) launchDone()
            }
        }
        val owner = context as? LifecycleOwner
        if (owner != null) owner.lifecycleScope.launch(block = block) else runOnMainDispatcher(block)
    }

    private fun prepareResolverScan(context: Context, profileId: Long): Prepared {
        val profile = if (profileId > 0) ProfileManager.getProfile(profileId) else null
        if (profile == null || !ScanProfiles.isDnsTunnelBase(profile.outbound)) {
            return Prepared(-1L, context.getString(R.string.scan_resolver_needs_saved))
        }
        val name = context.getString(R.string.scan_resolver_name, profile.outbound.name).trim()
        val newest = IpScansRepo.newestResolverScan(profileId)
        if (newest == null) {
            val scan = IpScansRepo.newIpScan(IpScan.Kind.Resolver)
            scan.name = name
            scan.config.resolverProfileId = profileId
            if (!IpScansRepo.addIpScan(scan)) return Prepared(-1L, context.getString(R.string.scan_store_failed))
            return Prepared(scan.id, "")
        }
        val result = if (newest.resultListId >= 0) IpListsRepo.getIpListHeader(newest.resultListId) else null
        val stale = newest.name != name || (result != null && result.name != IpScansRepo.resultListName(name))
        // Unlike the desktop's whole-row save, the rename touches only the names, so a running scan is no obstacle.
        if (stale) IpScansRepo.renameScan(newest.id, name)
        return Prepared(newest.id, "")
    }

    private val offeredLists = HashMap<Long, Long>()

    /** "Use as resolvers" while an editor of [profileId] is open: the editor saves its own list choice. Main thread. */
    internal fun offerResolverList(profileId: Long, listId: Long) {
        offeredLists[profileId] = listId
    }

    /**
     * The IP list "Use as resolvers" chose for the open editor of [profileId], handed over once; -1 when none. The
     * DNS tunnel editors call it when they resume and put the list into their resolver IP list row. Main thread.
     */
    @JvmStatic
    fun takeResolverList(profileId: Long): Long = offeredLists.remove(profileId) ?: -1L

    /** The endpoint_json an editor's draft dials with: the endpoint section's pending choice, else the stored one. */
    private fun draftEndpointJson(stored: String?): String = try {
        EndpointSourceSection.pendingJson()
    } catch (e: Exception) {
        Logs.w(e)
        null
    } ?: stored ?: "{}"

    /**
     * EditWireguard / EditMasque findWarpEndpoint: the WARP finder on get_draft_profile, the profile under edit with
     * [outbound]'s pending edits and the pending endpoint source.
     */
    fun findWarpEndpoint(editor: ProfileSettingsActivity<*>, finder: ActivityResultLauncher<WarpFinderActivity.Request>, outbound: () -> Outbound) {
        if (!beginLaunch()) return
        try {
            val draft = ProxyEntity(id = DataStore.editingId, groupId = DataStore.editingGroup).putOutbound(outbound()).apply {
                endpointJson = draftEndpointJson(if (DataStore.editingId == 0L) null else editor.proxyEntity?.endpointJson)
            }
            finder.launch(WarpFinderActivity.Request(DataStore.editingId, draft))
        } catch (e: Exception) {
            Logs.w(e)
            launchDone()
        }
    }

    /** The "Find resolvers" row of the DNS tunnel editors, under their resolver IP list row. */
    const val KEY_FIND_RESOLVERS = "findResolvers"

    /** ResolverSourceWidget's "Find resolvers" button: the scan tests the saved profile, so a new one has none yet. */
    fun bindFindResolvers(editor: ProfileSettingsActivity<*>, screen: PreferenceFragmentCompat) {
        val row = screen.findPreference<Preference>(KEY_FIND_RESOLVERS) ?: return
        val saved = DataStore.editingId > 0L
        row.isEnabled = saved
        row.setSummary(if (saved) R.string.scan_resolver_find_sum else R.string.scan_resolver_find_unsaved)
        row.setOnPreferenceClickListener {
            findResolvers(editor, DataStore.editingId)
            true
        }
    }
}
