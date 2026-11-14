package io.nekohasekai.sagernet.database

/**
 * DnsServerHistory (RouteDnsEditor.cpp): the servers typed into the remote / direct DNS fields (remote_dns_history,
 * direct_dns_history), newest first, offered together with fixed presets.
 */
object DnsServerHistory {

    private const val MAX_ENTRIES = 8

    val REMOTE_PRESETS: List<String> = listOf(
        "https://8.8.8.8/dns-query", "tls://8.8.8.8", "tls://1.1.1.1", "8.8.8.8", "1.1.1.1",
        "https://1.1.1.1/dns-query, https://8.8.8.8/dns-query",
    )
    val DIRECT_PRESETS: List<String> = listOf("localhost", "223.5.5.5", "119.29.29.29", "178.22.122.100", "77.88.8.8")

    fun remoteSuggestions(): List<String> = suggestions(DataStore.remoteDnsHistory, REMOTE_PRESETS)

    fun directSuggestions(): List<String> = suggestions(DataStore.directDnsHistory, DIRECT_PRESETS)

    fun rememberRemote(server: String) {
        remember(DataStore.remoteDnsHistory, REMOTE_PRESETS, server)?.let { DataStore.remoteDnsHistory = it }
    }

    fun rememberDirect(server: String) {
        remember(DataStore.directDnsHistory, DIRECT_PRESETS, server)?.let { DataStore.directDnsHistory = it }
    }

    /** Fill: the history, then the presets it does not repeat. */
    fun suggestions(history: List<String>, presets: List<String>): List<String> {
        val out = ArrayList(history)
        for (preset in presets) if (preset !in out) out.add(preset)
        return out
    }

    /**
     * Remember: [history] with the trimmed [server] moved to the front, at most [MAX_ENTRIES] kept; null when it would
     * not change (an empty server, a preset, or the one already first).
     */
    fun remember(history: List<String>, presets: List<String>, server: String): List<String>? {
        val value = server.trim()
        if (value.isEmpty() || value in presets) return null
        if (history.firstOrNull() == value) return null
        val out = ArrayList<String>(history.size + 1)
        out.add(value)
        history.filterTo(out) { it != value }
        while (out.size > MAX_ENTRIES) out.removeAt(out.size - 1)
        return out
    }
}
