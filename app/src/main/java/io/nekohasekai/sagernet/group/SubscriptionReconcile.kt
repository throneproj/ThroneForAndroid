package io.nekohasekai.sagernet.group

import java.security.MessageDigest

/** SubscriptionReconcile.hpp/.cpp: the diff of a subscription refresh over per-profile digests. */
object SubscriptionReconcile {

    /** contentKeyOf (GroupUpdater.cpp:32-34): the digest of the compact ExportToJson. */
    @JvmStatic
    fun contentKey(exportJson: String): String = digest(exportJson)

    /** identityKeyOf (GroupUpdater.cpp:36-38): the digest of `type|compact ExportIdentity`. */
    @JvmStatic
    fun identityKey(type: String, identityJson: String): String = digest("$type|$identityJson")

    private fun digest(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]).append(HEX[v and 0xF])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /**
     * The identity and name passes of Reconcile (SubscriptionReconcile.cpp), once every arrival went through [index]
     * and before any is stored: an arrival that pairs with a leftover old row gets it as [NewEntry.supersedes], so it
     * updates that row in place instead of being inserted and deleted again.
     */
    @JvmStatic
    fun pair(old: List<OldEntry>, incoming: List<NewEntry>, index: ContentIndex) {
        // Identity pairing only sees what the content pass never saw: leftovers whose key never arrived, arrivals whose key was never known.
        val leftoverByIdentity = HashMap<String, ArrayDeque<Long>>()
        for (entry in old) {
            if (index.isClaimed(entry.id) || index.arrived(entry.content)) continue
            leftoverByIdentity.getOrPut(entry.identity) { ArrayDeque() }.addLast(entry.id)
        }
        val matchedOld = HashSet<Long>()
        for (entry in incoming) {
            if (entry.reused || entry.contentKnown) continue
            val leftovers = leftoverByIdentity[entry.identity]
            if (leftovers.isNullOrEmpty()) continue
            entry.supersedes = leftovers.removeFirst()
            matchedOld.add(entry.supersedes)
        }

        // Best effort on what both passes left, excess duplicates and known content included.
        val leftoverByName = HashMap<String, ArrayDeque<Long>>()
        for (entry in old) {
            if (entry.name.isEmpty() || index.isClaimed(entry.id) || entry.id in matchedOld) continue
            leftoverByName.getOrPut(entry.name) { ArrayDeque() }.addLast(entry.id)
        }
        for (entry in incoming) {
            if (entry.reused || entry.name.isEmpty() || entry.supersedes > 0L) continue
            val leftovers = leftoverByName[entry.name]
            if (leftovers.isNullOrEmpty()) continue
            entry.supersedes = leftovers.removeFirst()
            entry.byName = true
        }
    }

    /** Reconcile (SubscriptionReconcile.cpp) over the stored arrivals, paired by [pair]. */
    @JvmStatic
    fun reconcile(old: List<OldEntry>, incoming: List<NewEntry>, index: ContentIndex): ReconcilePlan {
        val plan = ReconcilePlan()

        val matchedOld = HashSet<Long>()
        for (entry in incoming) {
            if (entry.supersedes <= 0L) continue
            plan.updates.add(entry.supersedes)
            plan.updated.add(entry.display)
            matchedOld.add(entry.supersedes)
        }

        for (entry in old) {
            if (index.isClaimed(entry.id) || entry.id in matchedOld) continue
            plan.stale.add(entry.id)
            plan.deleted.add(entry.display)
        }

        for (entry in incoming) {
            if (entry.supersedes > 0L) {
                plan.order.add(entry.supersedes)
                continue
            }
            if (entry.id < 0) continue
            plan.order.add(entry.id)
            if (!entry.reused) plan.added.add(entry.display)
        }
        return plan
    }
}

/** An old member of the group, in group order; [name] is the outbound's own name. */
class OldEntry(
    @JvmField val id: Long,
    @JvmField val content: String,
    @JvmField val identity: String,
    @JvmField val display: String,
    @JvmField val name: String,
)

/**
 * A parsed profile in arrival order; [id] is the claimed old id when [reused], the updated old id when it
 * [supersedes] one, the inserted id otherwise (-1 = not stored).
 */
class NewEntry(@JvmField val display: String) {
    @JvmField var id: Long = -1L
    @JvmField var reused: Boolean = false
    @JvmField var contentKnown: Boolean = false
    @JvmField var content: String = ""

    /** Only computed when the content was never known. */
    @JvmField var identity: String = ""

    /** The outbound's own name; left empty where no name match is possible (reused arrivals, imports without an index). */
    @JvmField var name: String = ""

    /** The old row of the same identity (or, failing that, the same name) that takes this profile's data in place, 0 = none. */
    @JvmField var supersedes: Long = 0L

    /** [supersedes] was paired by name alone, so possibly another server or protocol. */
    @JvmField var byName: Boolean = false
}

/** ContentIndex: each old profile is claimed at most once, in group order, by identical arrivals in arrival order (#1775). */
class ContentIndex(old: List<OldEntry>) {
    private val unclaimed = HashMap<String, ArrayDeque<Long>>()
    private val known = HashSet<String>()
    private val arrivals = HashSet<String>()
    private val claimedIds = HashSet<Long>()

    init {
        for (entry in old) {
            unclaimed.getOrPut(entry.content) { ArrayDeque() }.addLast(entry.id)
            known.add(entry.content)
        }
    }

    fun knows(content: String): Boolean = content in known

    fun claim(content: String): Long? {
        val ids = unclaimed[content]
        if (ids.isNullOrEmpty()) return null
        val id = ids.removeFirst()
        claimedIds.add(id)
        return id
    }

    fun noteArrival(content: String) {
        arrivals.add(content)
    }

    fun arrived(content: String): Boolean = content in arrivals

    fun isClaimed(id: Long): Boolean = id in claimedIds
}

class ReconcilePlan {
    /** The group's new list: the document order over old ids (kept or updated in place) and new ids. */
    @JvmField val order = ArrayList<Long>()

    /** The old ids that took an arrival's data in place. */
    @JvmField val updates = ArrayList<Long>()
    @JvmField val stale = ArrayList<Long>()
    @JvmField val added = ArrayList<String>()
    @JvmField val updated = ArrayList<String>()
    @JvmField val deleted = ArrayList<String>()
}
