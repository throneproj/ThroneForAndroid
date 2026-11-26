package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.CoreRuntime
import io.nekohasekai.sagernet.bg.ProfileValidator
import io.nekohasekai.sagernet.bg.proto.CoreConfigs
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.GroupSort
import io.nekohasekai.sagernet.database.GroupSortAction
import io.nekohasekai.sagernet.database.GroupSortMethod
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SortInput
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.readableMessage
import io.throneproj.mobile.Mobile
import io.throneproj.mobile.ResolveRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** The post-update filters (GroupUpdater.cpp:183-296) and the manual group actions that need the core. */
internal object SubscriptionFilters {

    /** [deleted] ids and the report sections to append to the change text; [ok] is false after a database error. */
    class Outcome(
        @JvmField val deleted: List<Long>,
        @JvmField val report: String,
        @JvmField val stopped: ProxyEntity? = null,
        @JvmField val ok: Boolean = true,
    )

    private val NONE = Outcome(emptyList(), "")

    /** Rows written per transaction. */
    private const val WRITE_CHUNK = 500

    /**
     * removeFlagged (GroupUpdater.cpp:208-264): each profile is listed under the first enabled action that claims it.
     * The [members] are loaded only when an action is enabled.
     */
    suspend fun removeFlagged(gid: Long, options: SubscriptionOptions, members: () -> List<ProxyEntity>): Outcome {
        if (!options.removeDuplicates && !options.removeInsecure && !options.removeInvalid) return NONE

        val profiles = members()
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
        sections.append(GroupRepo.hopNotice(outcome.clearedHops))
        return Outcome(outcome.deleted, sections.toString(), outcome.stopped, outcome.ok)
    }

    /** [untested]: members with no known exit country, never removed, as a failed test is no verdict. */
    class CountryOutcome(
        @JvmField val untested: List<Long>,
        @JvmField val deleted: List<Long>,
        @JvmField val report: String,
        @JvmField val stopped: ProxyEntity?,
        @JvmField val ok: Boolean,
    )

    /**
     * applyCountryRules (GroupUpdater.cpp): removes the members [rules] refuse, unless some still await their test and
     * this is not the [final] pass. A refused verdict is remembered only once its profile goes, so a refresh drops that
     * content before it is imported. [verdicts] is updated and stored.
     */
    suspend fun applyCountryRules(
        gid: Long,
        rules: CountryRules,
        verdicts: MutableMap<String, String>,
        final: Boolean,
    ): CountryOutcome {
        val untested = ArrayList<Long>()
        val refused = ArrayList<Long>()
        val refusedNames = ArrayList<String>()
        val refusedVerdicts = ArrayList<Pair<String, String>>()
        var accepted = 0
        for (profile in ProfileManager.members(gid)) {
            if (profile.type == SubscriptionRefresh.AUTOSELECTOR) continue
            val country = profile.testCountry.orEmpty().trim().uppercase(Locale.ROOT)
            if (country.isEmpty()) {
                untested.add(profile.id)
                continue
            }
            val content = SubscriptionRefresh.contentKey(profile)
            if (rules.accepts(country)) {
                accepted++
                verdicts[content] = country
                continue
            }
            refused.add(profile.id)
            refusedNames.add(profile.outbound.displayTypeAndName())
            refusedVerdicts.add(content to country)
        }

        val report = StringBuilder()
        var outcome: ProfileManager.DeleteOutcome? = null
        val decide = refused.isNotEmpty() && (final || untested.isEmpty())
        // Like a fetch the filters would empty: rules that accept none of the tested profiles are likelier wrong than meant.
        if (decide && accepted == 0) {
            report.append('\n').append(app.getString(R.string.subs_country_none_accepted, refused.size))
        } else if (decide) {
            val deleted = ProfileManager.batchDeleteProfiles(refused)
            if (!deleted.ok) SubscriptionRefresh.dbError(gid)
            for ((content, country) in refusedVerdicts) verdicts[content] = country
            report.append('\n').append(app.getString(R.string.subs_removed_countries, refusedNames.size)).append('\n')
                .append(SubscriptionRefresh.notice(refusedNames, "[-]", "removed"))
            if (deleted.kept.isNotEmpty()) report.append('\n').append(app.getString(R.string.subs_running_kept))
            report.append(GroupRepo.hopNotice(deleted.clearedHops))
            outcome = deleted
        }
        SubscriptionCountryVerdicts.save(gid, verdicts)
        return CountryOutcome(untested, outcome?.deleted.orEmpty(), report.toString(), outcome?.stopped, outcome?.ok ?: true)
    }

    /**
     * removeUnavailableAndSort (GroupUpdater.cpp:518-545): the follow-ups of the subscription's URL test. Only the
     * profiles that failed in the run started at [testedSince] (seconds) are removed, so an inconclusive or aborted
     * test keeps the earlier result; null removes nothing.
     */
    suspend fun removeUnavailableAndSort(group: ProxyGroup, options: SubscriptionOptions, testedSince: Long?): Outcome {
        var deleted: List<Long> = emptyList()
        val report = StringBuilder()
        if (options.removeUnavailable && testedSince != null) {
            val doomed = ArrayList<Long>()
            val names = ArrayList<String>()
            for (profile in ProfileManager.members(group.id)) {
                if (profile.type == SubscriptionRefresh.AUTOSELECTOR || !profile.isUnavailable()) continue
                if (profile.latencyAt < testedSince) continue
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
                report.append(GroupRepo.hopNotice(outcome.clearedHops))
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
        report += GroupRepo.hopNotice(outcome.clearedHops)
        Logs.i(report)
        return report.trim()
    }

    /** Resolve Domain for group (on_menu_resolve_domain_triggered): [resolveProfiles] over the members of [gid]. */
    suspend fun resolveDomains(gid: Long): String = resolve(ProfileManager.members(gid))

    /**
     * Resolve Domain for the profiles [ids] (on_menu_resolve_selected_triggered), from any groups; unknown ids are
     * skipped. Returns the report text.
     */
    suspend fun resolveProfiles(ids: List<Long>): String = resolve(ProfileManager.getProfiles(ids))

    /**
     * resolve_profile_domains (mainwindow_profiles.cpp): every profile whose server is a domain takes the first
     * address the core's dns-direct returns, never the system resolver, which the tun answers from FakeIP or
     * dns-remote while the VPN runs. Only answered domains are written back.
     */
    private suspend fun resolve(profiles: List<ProxyEntity>): String = withContext(Dispatchers.IO) {
        val targets = profiles.filter { profile ->
            val address = profile.outbound.getAddress()
            !profile.outbound.invalid && address.isNotEmpty() && !address.isIpAddress()
        }
        val resolved = if (targets.isEmpty()) emptyMap<String, String>() else try {
            lookup(targets.map { it.outbound.getAddress() }.distinct())
        } catch (e: Exception) {
            val message = app.getString(R.string.resolve_domains_failed, e.readableMessage)
            Logs.w(message)
            return@withContext message
        }
        val changed = ArrayList<ProxyEntity>()
        for (profile in targets) {
            val outbound = profile.outbound
            val address = resolved[outbound.getAddress()] ?: continue
            val before = profile.outboundJson
            outbound.setAddress(address)
            profile.putOutbound(outbound)
            if (profile.outboundJson == before) continue
            changed.add(profile)
        }
        for (part in changed.chunked(WRITE_CHUNK)) {
            SagerDatabase.instance.runInTransaction {
                for (profile in part) {
                    SagerDatabase.proxyDao.updateOutbound(profile.id, profile.type, profile.name, profile.outboundJson)
                }
            }
        }
        val byGroup = changed.groupBy({ it.groupId }, { it.id })
        GroupSort.repositionLater(byGroup, SortInput.CONFIG)
        for (groupId in byGroup.keys) GroupRepo.postReload(groupId)
        val report = app.getString(R.string.subs_resolved, changed.size, targets.size)
        Logs.i(report)
        report
    }

    /** Domain -> first address, through the running instance's dns-direct or a probe box of the test config. */
    private fun lookup(domains: List<String>): Map<String, String> {
        val probe = CoreConfigs.buildTest(emptyList())
        if (!probe.ok) error(probe.error.orEmpty())
        val request = ResolveRequest().apply {
            coreConfig = probe.coreConfig
            domains.forEach(::addDomain)
        }
        val results = Mobile.resolveDomains(CoreRuntime.running?.instance, CoreRuntime.platform, request)
        val resolved = HashMap<String, String>()
        while (results.hasNext()) {
            val result = results.next()
            val addresses = result.addresses()
            if (addresses.hasNext()) {
                resolved[result.domain] = addresses.next()
            } else {
                Logs.w(app.getString(R.string.resolve_domain_failed, result.domain, result.error))
            }
        }
        return resolved
    }
}
