package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.ProfileValidator
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortAction
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

/** The post-update filters (GroupUpdater.cpp:183-296) and the manual group actions that need the core. */
internal object SubscriptionFilters {

    private const val RESOLVE_PARALLELISM = 8

    /** [deleted] ids and the report sections to append to the change text. */
    class Outcome(@JvmField val deleted: List<Long>, @JvmField val report: String)

    private val NONE = Outcome(emptyList(), "")

    /** removeFlagged (GroupUpdater.cpp:208-264): each profile is listed under the first enabled action that claims it. */
    suspend fun removeFlagged(gid: Long, profiles: List<ProxyEntity>, options: SubscriptionOptions): Outcome {
        if (!options.removeDuplicates && !options.removeInsecure && !options.removeInvalid) return NONE

        val flagged = HashSet<Long>()
        val doomed = ArrayList<Long>()
        val sections = StringBuilder()
        fun claim(heading: Int, matches: (ProxyEntity) -> Boolean) {
            val names = ArrayList<String>()
            for (profile in profiles) {
                if (profile.id in flagged || !matches(profile)) continue
                flagged.add(profile.id)
                doomed.add(profile.id)
                names.add(profile.outbound.displayTypeAndName())
            }
            if (names.isNotEmpty()) {
                sections.append('\n').append(app.getString(heading, names.size)).append('\n')
                    .append(SubscriptionRefresh.notice(names, "[-]", "removed"))
            }
        }

        if (options.removeDuplicates) {
            // ProfileFilter::Uniq with keep_last=false: the first profile of each key survives.
            val keys = HashSet<String>()
            val keep = HashSet<Long>()
            for (profile in profiles) if (keys.add(profile.dedupKey())) keep.add(profile.id)
            claim(R.string.subs_removed_duplicates) { it.id !in keep }
        }
        if (options.removeInsecure) {
            val skipCert = DataStore.skipCert
            claim(R.string.subs_removed_insecure) { it.outbound.effectiveSecurity(skipCert).isInsecure }
        }
        if (options.removeInvalid) {
            val scan = ProfileValidator.findInvalid(profiles.filter { it.id !in flagged })
            // Every check fails while the core is down: deleting on that verdict would empty the group.
            if (scan.coreUnreachable) {
                Logs.w(app.getString(R.string.subs_invalid_core_unreachable))
            } else {
                claim(R.string.subs_removed_invalid) { it.id in scan.invalid }
            }
        }

        if (doomed.isEmpty()) return NONE
        val outcome = ProfileManager.batchDeleteProfiles(doomed)
        if (!outcome.ok) SubscriptionRefresh.dbError(gid)
        if (outcome.kept.isNotEmpty()) sections.append('\n').append(app.getString(R.string.subs_running_kept))
        return Outcome(outcome.deleted, sections.toString())
    }

    /** removeUnavailableAndSort (GroupUpdater.cpp:266-296): the follow-ups of the subscription's URL test. */
    suspend fun removeUnavailableAndSort(group: ProxyGroup, options: SubscriptionOptions): Outcome {
        var deleted: List<Long> = emptyList()
        val report = StringBuilder()
        if (options.removeUnavailable) {
            val doomed = ArrayList<Long>()
            val names = ArrayList<String>()
            for (profile in ProfileManager.members(group.id)) {
                if (profile.type == SubscriptionRefresh.AUTOSELECTOR || !profile.isUnavailable()) continue
                doomed.add(profile.id)
                names.add(profile.outbound.displayTypeAndName())
            }
            if (doomed.isNotEmpty()) {
                val outcome = ProfileManager.batchDeleteProfiles(doomed)
                if (!outcome.ok) SubscriptionRefresh.dbError(group.id)
                deleted = outcome.deleted
                report.append('\n').append(app.getString(R.string.subs_removed_unavailable, names.size)).append('\n')
                    .append(SubscriptionRefresh.notice(names, "[-]", "removed"))
                if (outcome.kept.isNotEmpty()) report.append('\n').append(app.getString(R.string.subs_running_kept))
            }
        }
        if (options.sortByLatency && !GroupSort.sortProfiles(group.id, GroupSortAction(GroupSortMethod.BY_LATENCY))) {
            Logs.i(app.getString(R.string.subs_sort_skipped, group.name))
        }
        return Outcome(deleted, report.toString())
    }

    /**
     * Remove Invalid (mainwindow_profiles.cpp:366-419) on group [gid] with the running-profile rule; unlike the
     * desktop's manual action it keeps everything while the core is unreachable. Returns the report text.
     */
    suspend fun removeInvalid(gid: Long): String {
        val profiles = ProfileManager.members(gid)
        val scan = ProfileValidator.findInvalid(profiles)
        if (scan.coreUnreachable) {
            return app.getString(R.string.subs_invalid_core_unreachable).also { Logs.w(it) }
        }
        val invalid = profiles.filter { it.id in scan.invalid }
        if (invalid.isEmpty()) return app.getString(R.string.subs_no_invalid)
        val outcome = ProfileManager.batchDeleteProfiles(invalid.map { it.id })
        if (!outcome.ok) SubscriptionRefresh.dbError(gid)
        val names = invalid.map { it.outbound.displayTypeAndName() }
        var report = app.getString(R.string.subs_removed_invalid, names.size) + "\n" +
            SubscriptionRefresh.notice(names, "[-]", "removed")
        if (outcome.kept.isNotEmpty()) report += "\n" + app.getString(R.string.subs_running_kept)
        Logs.i(report)
        return report.trim()
    }

    /**
     * Resolve Domain for group (mainwindow_profiles.cpp:470-495, outbound::ResolveDomainToIP): every member whose
     * server is a domain takes the first address the system resolver returns. Returns the report text.
     */
    suspend fun resolveDomains(gid: Long): String {
        val targets = ProfileManager.members(gid).filter { profile ->
            val address = profile.outbound.getAddress()
            !profile.outbound.invalid && address.isNotEmpty() && !address.isIpAddress()
        }
        val resolved = AtomicInteger()
        val permits = Semaphore(RESOLVE_PARALLELISM)
        coroutineScope {
            for (profile in targets) launch(Dispatchers.IO) {
                permits.withPermit {
                    val outbound = profile.outbound
                    val address = runCatching {
                        InetAddress.getAllByName(outbound.getAddress()).firstOrNull()?.hostAddress
                    }.getOrNull()
                    if (address.isNullOrEmpty()) return@withPermit
                    val before = profile.outboundJson
                    outbound.setAddress(address)
                    profile.putOutbound(outbound)
                    if (profile.outboundJson == before) return@withPermit
                    SagerDatabase.proxyDao.updateOutbound(profile.id, profile.type, profile.name, profile.outboundJson)
                    resolved.incrementAndGet()
                }
            }
        }
        GroupRepo.postReload(gid)
        val report = app.getString(R.string.subs_resolved, resolved.get(), targets.size)
        Logs.i(report)
        return report
    }
}
