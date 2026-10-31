package io.nekohasekai.sagernet.group

import android.os.SystemClock
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.`import`.ImportReport
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.validation.vplural
import io.nekohasekai.sagernet.validation.vtr
import kotlinx.coroutines.delay

/** GroupUpdater::refresh, importDocuments and afterUrlTest (GroupUpdater.cpp:465-683) over the Room data layer. */
internal object SubscriptionRefresh {

    const val AUTOSELECTOR = "autoselector"

    /** kInsertChunk (GroupUpdater.cpp:23). */
    private const val INSERT_CHUNK = 500

    /** How long a replaced running profile waits for the service to finish stopping. */
    private const val RECONNECT_WAIT_MS = 15_000L

    private val BOM = Char(0xFEFF)

    /** [reported]: the import box went out, so the caller's own count line is skipped (MwArg::Quiet). */
    class ImportResult(@JvmField val gid: Long, @JvmField val count: Int, @JvmField val reported: Boolean = false)

    /**
     * GroupUpdater::Surface: where an update's problems go: the log only (automatic), the diff popup (manual
     * refresh), or a report box (a new subscription's first update).
     */
    enum class Surface { Log, Diff, Report }

    private fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    /** notice (GroupUpdater.cpp:166-176): a list of 1000 names or more collapses to one count line. */
    fun notice(names: List<String>, prefix: String, action: String): String {
        if (names.size >= 1000) return "$prefix $action ${names.size}\n"
        val sb = StringBuilder()
        for (name in names) sb.append(prefix).append(' ').append(name).append('\n')
        return sb.toString()
    }

    /** reportTitle (GroupUpdater.cpp). */
    private fun reportTitle(surface: Surface, groupName: String): String =
        if (surface == Surface.Report) vtr(R.string.validation_import_title_new, groupName) else vtr(R.string.validation_import_title_update, groupName)

    /** logImportReport (GroupUpdater.cpp). */
    private fun logImportReport(report: ImportReport) = Logs.w(report.text())

    /**
     * refresh and applyRefresh (GroupUpdater.cpp); [notifyErrors]: fetch failures also go to the callbacks. The parse
     * report goes to the log, and with [Surface.Diff] / [Surface.Report] to a popup.
     */
    suspend fun refresh(gid: Long, surface: Surface, notifyErrors: Boolean) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions
        val url = group.url.trim()

        val (info, result, routing) = fetchAndParse(group, url, surface, notifyErrors) ?: return
        val parsed = result.outbounds
        // Android-only (#53): filtered servers never enter the sink, so the diff deletes the ones already stored.
        val servers = SubscriptionNameFilter.apply(parsed, options, group.name)
        if (servers.isEmpty()) {
            if (info.valid) saveMetadata(group, info)
            val message = str(R.string.subs_filtered_all_aborted, parsed.size)
            Logs.w("${group.name}: $message")
            if (notifyErrors) SubscriptionQueue.error(gid, message)
            return
        }

        val metadata = saveMetadata(group, info)

        // Deleted for good, for the route rules that named them.
        val removed = ArrayList<Long>()
        // Ids a running auto selector can no longer trust: deleted, or same id with new settings.
        val disturbed = ArrayList<Long>()
        val snapshot = loadSnapshot(gid, options, disturbed, removed) ?: return
        val sticky = snapshot.sticky
        val stickyIds = snapshot.stickyIds
        val old = snapshot.old
        val working = snapshot.working
        val cleared = snapshot.cleared
        var stoppedRunning = snapshot.stopped
        var dbFailed = false
        val index = ContentIndex(old)
        val sink = ImportSink(gid, if (cleared) null else index)

        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (outbound in servers) sink.add(outbound)
        sink.finish(old)
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))
        val report = result.report()
        report.setImported(sink.stored())

        var changeText: String
        if (cleared) {
            changeText = if (sink.entries.size >= 1000) {
                "[+] ${sink.entries.size} profiles\n"
            } else {
                sink.entries.joinToString("") { "[+] ${it.display}\n" }
            }
        } else {
            val plan = SubscriptionReconcile.reconcile(old, sink.entries, index)
            disturbed.addAll(plan.updates)

            val stale = ArrayList<Long>()
            val keptWorking = ArrayList<Long>()
            for (id in plan.stale) (if (id in working) keptWorking else stale).add(id)

            val previousOrder = ProfileManager.memberIds(gid)
            val order = ArrayList(plan.order)
            for ((position, id) in sticky) order.add(minOf(position, order.size), id)

            val outcome = ProfileManager.batchDeleteProfiles(stale)
            if (!outcome.ok) {
                dbError(gid)
                dbFailed = true
            }
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)
            outcome.stopped?.let { stoppedRunning = it }

            // Survivors of the deletion (the running profile, working ones) return to their previous position.
            val ordered = order.toHashSet()
            val previousPosition = HashMap<Long, Int>(previousOrder.size * 2)
            previousOrder.forEachIndexed { position, id -> previousPosition.putIfAbsent(id, position) }
            fun restore(id: Long): Boolean {
                if (!ordered.add(id)) return false
                order.add(minOf(previousPosition[id] ?: order.size, order.size), id)
                return true
            }
            val keptIds = outcome.kept.filter(::restore)
            val workingIds = keptWorking.filter(::restore)
            val survivors = ProfileManager.getProfiles(keptIds + workingIds).associateBy { it.id }
            val noticeKept = StringBuilder()
            for (id in keptIds) {
                survivors[id]?.let { noticeKept.append("[=] ").append(it.outbound.displayTypeAndName()).append('\n') }
            }
            val workingNames = workingIds.mapNotNull { survivors[it]?.outbound?.displayTypeAndName() }
            ProfileManager.setOrder(gid, order)

            var deletedNames: List<String> = plan.deleted
            if (keptWorking.isNotEmpty()) {
                val doomed = stale.toHashSet()
                deletedNames = old.filter { it.id in doomed }.map { it.display }
            }

            changeText = "\n" + str(
                R.string.subs_change_summary,
                plan.added.size, notice(plan.added, "[+]", "added"),
                plan.updates.size, notice(plan.updated, "[~]", "updated"),
                deletedNames.size, notice(deletedNames, "[-]", "deleted"),
            )
            if (noticeKept.isNotEmpty()) changeText += "\n" + str(R.string.subs_still_in_use, noticeKept.toString())
            if (workingNames.isNotEmpty()) {
                changeText += "\n" + str(R.string.subs_working_kept, notice(workingNames, "[=]", "kept"))
            }
            if (plan.added.isEmpty() && plan.updates.isEmpty() && deletedNames.isEmpty() && keptWorking.isEmpty()) {
                changeText = str(R.string.subs_nothing)
            }
        }

        val filteredOut = parsed.size - servers.size
        if (filteredOut > 0) changeText += "\n" + str(R.string.subs_filtered_out, filteredOut) + "\n"
        val filtered = SubscriptionFilters.removeFlagged(gid, options) {
            ProfileManager.members(gid).filter { it.id !in stickyIds }
        }
        changeText += filtered.report
        disturbed.addAll(filtered.deleted)
        removed.addAll(filtered.deleted)
        filtered.stopped?.let { stoppedRunning = it }
        if (!filtered.ok) dbFailed = true

        SubscriptionRouteRebind.rebind(gid, removed, snapshot.routeNames)
        // The subscription's own order replaced the group's above.
        if (disturbed.isNotEmpty() || ProfileManager.memberIds(gid) != snapshot.membersBefore) GroupSort.resortLater(gid)
        // Stamped only once the update went through, so an interrupted one is retried instead of counting as fresh.
        if (!urlChanged(gid, url)) {
            SagerDatabase.groupDao.setSubscriptionInfo(gid, System.currentTimeMillis() / 1000, metadata)
            GroupRepo.postUpdate(gid)
        }

        Logs.i("<<<<<<<< " + str(R.string.subs_change_of_log, group.name) + "\n" + changeText)
        if (report.hasProblems()) logImportReport(report)
        val diffPopup = surface == Surface.Diff && DataStore.subShowChangePopup
        var diffBody = changeText.trim().ifEmpty { str(R.string.subs_nothing) }
        if (diffPopup && report.hasProblems()) diffBody += "\n\n" + vtr(R.string.validation_import_problems) + "\n" + report.text()
        SubscriptionQueue.report(gid, str(R.string.subs_change_of, group.name), diffBody, diffPopup)
        if (!diffPopup && surface != Surface.Log && report.hasProblems()) {
            SubscriptionQueue.report(gid, reportTitle(surface, group.name), report.text(), true)
        }
        stoppedRunning?.let { reconnectSuccessor(gid, it) }
        SubscriptionQueue.groupChanged(gid, disturbed)
        // GroupUpdater.cpp:996: only after an update that went through.
        if (routing.isNotEmpty() && !dbFailed) SubscriptionRoute.importRoute(gid, routing)

        if (options.urlTest) SubscriptionQueue.requestUrlTest(gid, ProfileManager.memberIds(gid).filter { it !in stickyIds })
    }

    /**
     * The answer's subscription info, parse result and routing profile value ([SubscriptionRoute.valueOf], "" unless
     * the group imports it), null when the update stops here (already reported). The body dies with this frame
     * instead of living through the whole refresh.
     */
    private suspend fun fetchAndParse(
        group: ProxyGroup,
        url: String,
        surface: Surface,
        notifyErrors: Boolean,
    ): Triple<SubUserInfo, ProfileImport.Result, String>? {
        val gid = group.id
        val fetched = SubscriptionFetch.fetch(url, group.name, RequestIdentity.resolve(group))
        if (fetched.error != null) {
            if (surface == Surface.Report) {
                // The group is stored before the download: a failed one is fixed through its own TLS, HTTP and User Agent settings.
                val report = ImportReport()
                report.addFatal(vtr(R.string.validation_import_fetch_failed, fetched.error))
                report.addFatal(vtr(R.string.validation_import_first_download_failed, group.name))
                SubscriptionQueue.report(gid, reportTitle(surface, group.name), report.summary(), true)
            } else if (notifyErrors) {
                SubscriptionQueue.error(gid, fetched.error)
            }
            return null
        }
        val current = GroupRepo.get(gid) ?: return null
        if (current.url.trim() != url) {
            Logs.w(str(R.string.subs_url_changed, group.name))
            return null
        }
        // A body without a single profile is far likelier a broken or blocked response than an emptied subscription:
        // abort before the first write (no deletion, no update time). Parsing writes nothing, so it runs first.
        val info = fetched.subInfo
        val result = parse(fetched.body)
        if (result.outbounds.isEmpty()) {
            val probe = result.report()
            if (looksLikeWebPage(fetched.body)) probe.addFatal(str(R.string.subs_web_page))
            // Panels answer a device or quota limit with an empty list and an announcement saying why.
            if (info.valid) {
                if (info.announce.isNotEmpty()) probe.addFatal(str(R.string.subs_announcement_from, group.name, info.announce))
                saveMetadata(group, info)
            }
            val message = str(R.string.subs_empty_aborted)
            probe.addFatal(message)
            Logs.w("${group.name}: " + probe.text())
            if (surface != Surface.Log) SubscriptionQueue.report(gid, reportTitle(surface, group.name), probe.text(), true)
            else if (notifyErrors) SubscriptionQueue.error(gid, message)
            return null
        }
        val routing = if (current.importSubRoute) SubscriptionRoute.valueOf(fetched.routing, fetched.body) else ""
        return Triple(info, result, routing)
    }

    /**
     * reconnectSuccessor (GroupUpdater.cpp, #2051): the refresh deleted the running profile, so only an unambiguous
     * successor (same type and name) takes over; with none or several, the profile stays stopped.
     */
    private fun reconnectSuccessor(gid: Long, stopped: ProxyEntity) {
        val name = stopped.name.orEmpty()
        val matches = if (name.isEmpty()) emptyList() else {
            SagerDatabase.proxyDao.getNamesByGroup(gid).filter { it.type == stopped.type && it.name == name }
        }
        val display = stopped.outbound.displayTypeAndName()
        if (matches.size != 1) {
            Logs.w(str(R.string.subs_running_removed, display))
            return
        }
        Logs.i(str(R.string.subs_running_replaced, display))
        val successor = matches.first().id
        runOnDefaultDispatcher { startWhenStopped(stopped.id, successor) }
    }

    /**
     * EngineHost's subscriptionReplacedRunning: the delete only asked the service to stop, and a start while it is
     * stopping is ignored, so this waits for Stopped. Whatever the user started meanwhile wins.
     */
    private suspend fun startWhenStopped(stoppedId: Long, successor: Long) {
        val deadline = SystemClock.elapsedRealtime() + RECONNECT_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = DataStore.serviceState
            if (state == BaseService.State.Stopped) {
                DataStore.selectedProxy = successor
                try {
                    SagerNet.startService()
                } catch (e: Exception) {
                    Logs.w(e)
                }
                return
            }
            if (state.started && DataStore.currentProfile != stoppedId) return
            delay(200)
        }
    }

    /** The group as the diff starts from it; [routeNames] is [SubscriptionRouteRebind.referencedNames]' result. */
    private class Snapshot(
        @JvmField val sticky: List<Pair<Int, Long>>,
        @JvmField val stickyIds: HashSet<Long>,
        @JvmField val membersBefore: List<Long>,
        @JvmField val routeNames: Map<Long, String>,
        @JvmField val old: ArrayList<OldEntry>,
        @JvmField val working: HashSet<Long>,
        @JvmField val cleared: Boolean,
        @JvmField val stopped: ProxyEntity?,
    )

    /**
     * Loads the group once and runs "clear servers before updating"; null after a database error (already reported).
     * The loaded rows, with their parsed outbounds, die with this frame instead of living through the whole refresh.
     */
    private suspend fun loadSnapshot(
        gid: Long,
        options: SubscriptionOptions,
        disturbed: MutableList<Long>,
        removed: MutableList<Long>,
    ): Snapshot? {
        // Auto selectors are local state, not servers the remote sent: keep them out of the diff.
        var members = ProfileManager.members(gid)
        val sticky = members.withIndex()
            .filter { it.value.type == AUTOSELECTOR }
            .map { it.index to it.value.id }
        val stickyIds = sticky.mapTo(HashSet()) { it.second }
        val membersBefore = members.map { it.id }
        members = members.filter { it.id !in stickyIds }

        val routeNames = SubscriptionRouteRebind.referencedNames(members)
        var cleared = false
        var stopped: ProxyEntity? = null
        if (DataStore.subClear) {
            Logs.i(str(R.string.subs_clearing))
            val doomed = if (options.keepWorking) members.filter { !it.isWorking() } else members
            val outcome = ProfileManager.batchDeleteProfiles(doomed.map { it.id })
            if (!outcome.ok) {
                val message = str(R.string.subs_db_error_retry)
                Logs.e(message)
                SubscriptionQueue.error(gid, message)
                return null
            }
            disturbed.addAll(outcome.deleted)
            removed.addAll(outcome.deleted)
            stopped = outcome.stopped
            // A survivor still belongs to the subscription: fall through to the diff. A row gone meanwhile is none.
            val alive = ProfileManager.memberIds(gid).toHashSet()
            members = members.filter { it.id in alive }
            cleared = members.isEmpty()
        }

        val old = ArrayList<OldEntry>()
        val working = HashSet<Long>()
        if (!cleared) {
            for (profile in members) {
                old.add(OldEntry(profile.id, contentKey(profile), identityKey(profile), profile.outbound.displayTypeAndName()))
                if (options.keepWorking && profile.isWorking()) working.add(profile.id)
            }
        }
        return Snapshot(sticky, stickyIds, membersBefore, routeNames, old, working, cleared, stopped)
    }

    /**
     * importDocuments (GroupUpdater.cpp): a plain import into [gid] (<= 0: the current group). With problems the
     * import box goes out as a popup report; [emptyReason] replaces the generic "nothing found" when nothing is stored.
     */
    suspend fun importDocuments(gid: Long, documents: List<String>, emptyReason: String = ""): ImportResult {
        val target = if (gid > 0) gid else GroupRepo.currentId()
        val sink = ImportSink(target, null)
        val report = ImportReport()
        Logs.i(">>>>>>>> " + str(R.string.subs_processing))
        for (document in documents) {
            val result = parse(document)
            report.addAll(result.reports)
            for (outbound in result.outbounds) sink.add(outbound)
        }
        sink.flush()
        Logs.i(">>>>>>>> " + str(R.string.subs_process_complete))
        val count = sink.stored()
        report.setImported(count)
        val lost = sink.entries.size - count
        if (lost > 0) report.addFatal(vplural(R.plurals.validation_import_not_saved, lost))
        if (count == 0) {
            if (emptyReason.isNotEmpty()) report.addFatal(emptyReason)
            else if (!report.hasProblems()) report.addFatal(vtr(R.string.validation_import_none))
        }
        if (!report.hasProblems()) {
            Logs.i(str(R.string.subs_imported, count))
            return ImportResult(target, count)
        }
        // The logged summary already carries the count.
        logImportReport(report)
        SubscriptionQueue.report(target, vtr(R.string.validation_import_title), report.text(), true)
        return ImportResult(target, count, reported = true)
    }

    /** afterUrlTest (GroupUpdater.cpp:669-683). */
    suspend fun afterUrlTest(gid: Long) {
        val group = GroupRepo.get(gid) ?: return
        if (group.archive) return
        val options = group.subOptions
        if (!options.urlTest || (!options.removeUnavailable && !options.sortByLatency)) return

        val result = SubscriptionFilters.removeUnavailableAndSort(group, options)
        if (result.report.isNotEmpty()) {
            val heading = str(R.string.subs_after_url_test, group.name)
            Logs.i("<<<<<<<< $heading${result.report}")
            SubscriptionQueue.report(gid, heading.removeSuffix(":"), result.report.trim(), false)
        }
        SubscriptionQueue.groupChanged(gid, result.deleted)
    }

    /** GroupUpdater.cpp:850-853: an edit of the group's URL meanwhile (or its deletion) makes the answer stale. */
    private fun urlChanged(gid: Long, url: String): Boolean = GroupRepo.get(gid)?.url?.trim() != url

    /** looksLikeWebPage (GroupUpdater.cpp:84-87): a CAPTCHA, bot check or block page instead of a subscription. */
    internal fun looksLikeWebPage(body: String): Boolean {
        val start = body.indexOfFirst { !it.isWhitespace() && it != BOM }
        if (start < 0) return false
        return body.startsWith("<!doctype html", start, ignoreCase = true) || body.startsWith("<html", start, ignoreCase = true)
    }

    /**
     * Stores what the answer said about the subscription (no update time) and returns the column text; a changed
     * profile-update-interval moves a group that follows it to another schedule.
     */
    private suspend fun saveMetadata(group: ProxyGroup, info: SubUserInfo): String {
        val metadata = info.toJsonString()
        SagerDatabase.groupDao.setSubscriptionMetadata(group.id, metadata)
        GroupRepo.postUpdate(group.id)
        if (info.serverInterval != group.subInfo.serverInterval && SubscriptionScheduler.followsServerInterval(group)) {
            SubscriptionScheduler.schedule()
        }
        return metadata
    }

    internal fun dbError(gid: Long) {
        val message = str(R.string.subs_db_error_corrupted)
        Logs.e(message)
        SubscriptionQueue.error(gid, message)
    }

    private fun parse(body: String): ProfileImport.Result =
        ProfileImport.parse(body, SettingsMapper.xrayVlessPreference(), SettingsMapper.buildContext())

    /** The stored JSON of a type this build cannot parse is kept as is (re-exporting would drop its fields). */
    private fun contentKey(profile: ProxyEntity): String = SubscriptionReconcile.contentKey(
        if (profile.outbound.invalid) profile.outboundJson else profile.outbound.exportToJson().toCompact()
    )

    private fun identityKey(profile: ProxyEntity): String =
        SubscriptionReconcile.identityKey(profile.type, profile.outbound.exportIdentity().toCompact())

    /**
     * ImportSink (GroupUpdater.cpp:112-154): inserts in chunks, claiming old ids by identical content first. Against a
     * [ContentIndex] the rest waits for [finish], where the identity pairs update their old rows in place.
     */
    private class ImportSink(private val gid: Long, private val index: ContentIndex?) {
        val entries = ArrayList<NewEntry>()
        private val chunk = ArrayList<Outbound>()

        /** stored (GroupUpdater.cpp): rows that really reached the database, added or reused from the group. */
        fun stored(): Int = entries.count { it.id > 0 }
        private val entryIndex = ArrayList<Int>()

        /** The entries (by position) waiting for [finish], with their profiles. */
        private val pendingIndex = ArrayList<Int>()
        private val pending = ArrayList<Outbound>()

        suspend fun add(outbound: Outbound) {
            val entry = NewEntry(outbound.displayTypeAndName())
            if (index != null) {
                entry.content = SubscriptionReconcile.contentKey(outbound.exportToJson().toCompact())
                index.noteArrival(entry.content)
                entry.contentKnown = index.knows(entry.content)
                val oldId = index.claim(entry.content)
                if (oldId != null) {
                    entry.id = oldId
                    entry.reused = true
                    entries.add(entry)
                    return
                }
                if (!entry.contentKnown) {
                    entry.identity = SubscriptionReconcile.identityKey(outbound.type, outbound.exportIdentity().toCompact())
                }
                pendingIndex.add(entries.size)
                entries.add(entry)
                pending.add(outbound)
                return
            }
            entryIndex.add(entries.size)
            entries.add(entry)
            chunk.add(outbound)
            if (chunk.size >= INSERT_CHUNK) flush()
        }

        /** After the last [add]: the identity pairs of [old] take their arrival's data, the other arrivals are inserted. */
        suspend fun finish(old: List<OldEntry>) {
            if (index != null) {
                SubscriptionReconcile.pairByIdentity(old, entries, index)
                val updates = ArrayList<Int>()
                for (k in pending.indices) {
                    val position = pendingIndex[k]
                    if (entries[position].supersedes > 0L) {
                        updates.add(k)
                        continue
                    }
                    entryIndex.add(position)
                    chunk.add(pending[k])
                    if (chunk.size >= INSERT_CHUNK) flush()
                }
                flush()
                for (part in updates.chunked(INSERT_CHUNK)) {
                    val json = part.map { pending[it].exportToJson().toCompact() }
                    // The old row keeps its id, test results and traffic.
                    SagerDatabase.instance.runInTransaction {
                        part.forEachIndexed { i, k ->
                            val entry = entries[pendingIndex[k]]
                            val outbound = pending[k]
                            SagerDatabase.proxyDao.updateOutbound(entry.supersedes, outbound.type, outbound.name, json[i])
                            entry.id = entry.supersedes
                        }
                    }
                }
                pendingIndex.clear()
                pending.clear()
            }
            flush()
        }

        suspend fun flush() {
            if (chunk.isEmpty()) return
            try {
                val rows = ProfileManager.addProfileBatch(chunk, gid)
                rows.forEachIndexed { i, row -> entries[entryIndex[i]].id = row.id }
            } catch (e: Exception) {
                Logs.w(e)
            }
            chunk.clear()
            entryIndex.clear()
        }
    }
}
