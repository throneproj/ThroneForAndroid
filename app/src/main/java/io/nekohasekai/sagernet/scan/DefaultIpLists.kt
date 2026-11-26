package io.nekohasekai.sagernet.scan

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.security.MessageDigest

/** Scanner::DefaultIpLists (DefaultIpLists.h/.cpp): the seeded user lists. Blocking. */
object DefaultIpLists {

    // Untranslated on purpose: views find the default lists by name, whatever the UI language.
    const val WARP_WIREGUARD_NAME = "Cloudflare WARP (WireGuard)"
    const val WARP_MASQUE_NAME = "Cloudflare WARP (MASQUE)"
    const val IRAN_RESOLVERS_NAME = "Iran DNS resolvers"
    const val RUSSIA_RESOLVERS_NAME = "Russia DNS resolvers"

    private class Spec(val name: String, val asset: String = "", val cidrs: List<String>? = null)

    private val specs = listOf(
        Spec(WARP_WIREGUARD_NAME, cidrs = WarpPresets.WIREGUARD_RANGES),
        Spec(WARP_MASQUE_NAME, cidrs = WarpPresets.MASQUE_ENDPOINTS),
        Spec(IRAN_RESOLVERS_NAME, asset = "ip_lists/iran-resolvers.txt"),
        Spec(RUSSIA_RESOLVERS_NAME, asset = "ip_lists/russia-resolvers.txt"),
    )

    /** The user list named [name], -1 when there is none. */
    @JvmStatic
    fun find(name: String): Long =
        IpListsRepo.getAllIpLists().firstOrNull { it.role == IpList.Role.User && it.name == name }?.id ?: -1L

    private fun build(spec: Spec): IpList? {
        val entries = if (spec.cidrs != null) {
            IpListParse.entriesFromCidrs(spec.cidrs, 0)
        } else {
            val bytes = try {
                app.assets.open(spec.asset).use { it.readBytes() }
            } catch (e: Exception) {
                Logs.w("DefaultIpLists: ${spec.asset}", e)
                return null
            }
            IpListParse.parseIpListText(bytes).entries
        }
        if (entries.isEmpty()) return null
        return IpList(name = spec.name, entries = entries, entryCount = entries.size, entriesLoaded = true)
    }

    // SHA-256 of the sorted "cidr|port" lines, so list order does not matter.
    private fun digest(entries: List<IpListEntry>): String {
        val text = entries.map { it.cidr + "|" + it.port }.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Ensure: the default list [name], put back with its seed when it is gone; -1 when it cannot be. */
    @JvmStatic
    fun ensure(name: String): Long {
        find(name).let { if (it >= 0) return it }
        val spec = specs.firstOrNull { it.name == name } ?: return -1L
        val list = build(spec) ?: return -1L
        return if (IpListsRepo.addIpList(list)) list.id else -1L
    }

    /** EnsureAll: puts back every missing default list; returns how many it made. */
    @JvmStatic
    fun ensureAll(): Int {
        var created = 0
        for (spec in specs) {
            if (find(spec.name) >= 0) continue
            if (ensure(spec.name) >= 0) created++
        }
        return created
    }

    /**
     * Puts the 162.159.198.0/24 and 199.0/24 seeds in the MASQUE list in place of the .1/.2 hosts they cover. True when
     * done or there is nothing to do (the list is gone); false when the rewrite failed.
     */
    @JvmStatic
    fun migrateWarpMasqueRanges(): Boolean {
        val id = find(WARP_MASQUE_NAME)
        if (id < 0) return true
        val covered = setOf("162.159.198.1", "162.159.198.2", "162.159.199.1", "162.159.199.2")
        val entries = IpListsRepo.getEntries(id)
        val present = entries.mapTo(HashSet()) { it.cidr + " " + it.port }
        val ranges = IpListParse.entriesFromCidrs(listOf("162.159.198.0/24", "162.159.199.0/24"), 0)
            .filter { (it.cidr + " " + it.port) !in present }
        val migrated = ArrayList<IpListEntry>(entries.size + ranges.size)
        var placed = false
        var changed = ranges.isNotEmpty()
        for (entry in entries) {
            // Only the seeded portless hosts: an entry with its own port is not covered by a portless range.
            if (entry.port == 0 && entry.cidr in covered) {
                // The ranges take the first seed's place, so a migrated list reads like a fresh seed.
                if (!placed) migrated.addAll(ranges)
                placed = true
                changed = true
                continue
            }
            migrated.add(entry)
        }
        if (!changed) return true
        if (!placed) migrated.addAll(ranges)
        return IpListsRepo.replaceEntries(id, migrated)
    }

    /**
     * Gives the WARP WireGuard, Iran and Russia lists their 2026-11 seeds where they still hold the old seed exactly.
     * True when done or there is nothing to do; false when a rewrite failed.
     */
    @JvmStatic
    fun migrateListSeedsNov2026(): Boolean {
        class OldSeed(val name: String, val count: Int, val digest: String)
        // Frozen: the seeds shipped before 2026-11-24, the resolver lists as the size and digest of their files then.
        val oldWireGuard = IpListParse.entriesFromCidrs(
            listOf("162.159.192.0/24", "162.159.195.0/24", "188.114.96.0/22", "2606:4700:d0::/64", "2606:4700:d1::/64"), 0,
        )
        val oldSeeds = listOf(
            OldSeed(WARP_WIREGUARD_NAME, oldWireGuard.size, digest(oldWireGuard)),
            OldSeed(IRAN_RESOLVERS_NAME, 7864, "0ae3da1934296b6e606414afe5414905872e1ec5ff297aa32d464fcc4568ffcb"),
            OldSeed(RUSSIA_RESOLVERS_NAME, 958, "95373b977b4129332184af6666c877e263c7aeae923e80f9e74a5a165885f78c"),
        )
        var ok = true
        for (seed in oldSeeds) {
            val id = find(seed.name)
            if (id < 0) continue
            val entries = IpListsRepo.getEntries(id)
            if (entries.size != seed.count || digest(entries) != seed.digest) continue
            val spec = specs.firstOrNull { it.name == seed.name } ?: continue
            val fresh = build(spec)
            if (fresh == null || !IpListsRepo.replaceEntries(id, fresh.entries)) ok = false
        }
        return ok
    }

    /** The seeds of a WARP search: the carrier's default list, put back if it was deleted; the presets when it cannot be or holds nothing. */
    @JvmStatic
    fun warpSeeds(masque: Boolean): List<IpListEntry> {
        val listId = ensure(if (masque) WARP_MASQUE_NAME else WARP_WIREGUARD_NAME)
        val entries = if (listId >= 0) IpListsRepo.getEntries(listId) else emptyList()
        return entries.ifEmpty {
            IpListParse.entriesFromCidrs(if (masque) WarpPresets.MASQUE_ENDPOINTS else WarpPresets.WIREGUARD_RANGES, 0)
        }
    }
}
