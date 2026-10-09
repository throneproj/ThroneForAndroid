package io.nekohasekai.sagernet.outbound.link

/** Query items as QUrlQuery(url.query()) holds them: keys and values in PrettyDecoded form, order preserved. */
class LinkQuery(@JvmField val items: List<Pair<String, String>>) {

    fun has(key: String): Boolean = items.any { it.first == key }

    /** QUrlQuery::queryItemValue(key): PrettyDecoded, first match, "" when absent. */
    fun value(key: String): String = items.firstOrNull { it.first == key }?.second ?: ""

    /** QUrlQuery::queryItemValue(key, QUrl::FullyDecoded). */
    fun valueFully(key: String): String = LinkCodec.decodeFully(value(key))

    /**
     * formDecodedQueryValue (utils.cpp:20-26): the FullyEncoded value with '+' read as a space, as urlencode()-style
     * panels send it, then percent-decoded; an encoded %2B stays a '+'.
     */
    fun valueFormDecoded(key: String): String =
        LinkCodec.decodeFully(LinkCodec.recodeQueryFullyEncoded(value(key)).replace("+", "%20"))

    /** GetQueryValue (Utils.cpp:69-75): [default] when the item is absent OR empty. */
    fun valueOr(key: String, default: String): String = value(key).ifEmpty { default }

    fun isEmpty(): Boolean = items.isEmpty()

    companion object {
        @JvmField
        val EMPTY = LinkQuery(emptyList())
    }
}

/**
 * The components QUrl(link) exposes: [userName], [password], [path] and [fragment] are FullyDecoded, [host] is
 * the FullyEncoded form (ACE, lower case, IPv6 without brackets), [port] is -1 when absent.
 */
class ParsedLink(
    @JvmField val scheme: String,
    @JvmField val userName: String,
    @JvmField val password: String,
    @JvmField val host: String,
    @JvmField val port: Int,
    @JvmField val invalidPort: Boolean,
    @JvmField val invalidHost: Boolean,
    @JvmField val path: String,
    @JvmField val query: LinkQuery,
    @JvmField val fragment: String?,
) {
    /** QUrl::isValid(). */
    val isValid: Boolean get() = !invalidPort && !invalidHost

    /** The only error is "Invalid port": the desktop's TLS/QUIC/uTLS/ECH/Reality parsers still read such links. */
    val invalidPortOnly: Boolean get() = invalidPort && !invalidHost

    /** QUrl::port(defaultPort). */
    fun port(default: Int): Int = if (port >= 0) port else default
}

/** QUrl(link) in TolerantMode, reduced to what the profile parsers read. */
object LinkParser {

    @JvmStatic
    fun parse(link: String): ParsedLink {
        // QUrlPrivate::parse: '#' ends everything, '?' ends the hierarchical part, the first ':' before either may end a scheme
        var hash = -1
        var question = -1
        var colon = -1
        for (i in link.indices) {
            val c = link[i]
            if (c == '#') {
                hash = i
                break
            }
            if (question == -1) {
                if (c == ':' && colon == -1) colon = i else if (c == '?') question = i
            }
        }
        val len = link.length
        var scheme = ""
        var hierStart = 0
        if (colon != -1 && isValidScheme(link, colon)) {
            scheme = link.substring(0, colon).lowercase()
            hierStart = colon + 1
        }
        val hierEnd = minOf(if (question == -1) len else question, if (hash == -1) len else hash)

        var userName = ""
        var password = ""
        var host = ""
        var port = -1
        var invalidPort = false
        var invalidHost = false
        val pathStart: Int
        if (hierEnd - hierStart >= 2 && link[hierStart] == '/' && link[hierStart + 1] == '/') {
            var authorityEnd = hierEnd
            for (i in hierStart + 2 until hierEnd) {
                if (link[i] == '/') {
                    authorityEnd = i
                    break
                }
            }
            val auth = link.substring(hierStart + 2, authorityEnd)
            var from = 0
            val at = auth.indexOf('@')
            if (at >= 0) {
                val userInfo = auth.substring(0, at)
                val delim = userInfo.indexOf(':')
                userName = LinkCodec.decodeFully(LinkCodec.tolerant(if (delim >= 0) userInfo.substring(0, delim) else userInfo))
                if (delim >= 0) password = LinkCodec.decodeFully(LinkCodec.tolerant(userInfo.substring(delim + 1)))
                from = at + 1
            }
            val rest = auth.substring(from)
            var colonIndex = rest.lastIndexOf(':')
            if (colonIndex >= 0 && rest.startsWith("[")) {
                val closing = rest.indexOf(']')
                if (closing < 0 || closing > colonIndex) colonIndex = -1
            }
            if (colonIndex >= 0 && colonIndex != rest.length - 1) {
                val digits = rest.substring(colonIndex + 1)
                val value = if (digits.length <= 9 && digits.all { it in '0'..'9' }) digits.toInt() else -1
                if (value in 0..65535) port = value else invalidPort = true
            }
            val hostRaw = if (colonIndex >= 0) rest.substring(0, colonIndex) else rest
            val normalized = Hosts.normalizeHost(hostRaw, percentDecode = true)
            if (normalized == null) invalidHost = true else host = normalized
            pathStart = authorityEnd
        } else {
            pathStart = hierStart
        }
        val path = LinkCodec.decodeFully(LinkCodec.tolerant(link.substring(pathStart, hierEnd)))
        val query = if (question != -1) parseQuery(link.substring(question + 1, if (hash == -1) len else hash)) else LinkQuery.EMPTY
        val fragment = if (hash != -1) LinkCodec.decodeFully(LinkCodec.tolerant(link.substring(hash + 1))) else null
        return ParsedLink(scheme, userName, password, host, port, invalidPort, invalidHost, path, query, fragment)
    }

    private fun isValidScheme(link: String, colon: Int): Boolean {
        if (colon <= 0) return false
        val first = link[0]
        if (!(first in 'a'..'z' || first in 'A'..'Z')) return false
        for (i in 1 until colon) {
            val c = link[i]
            if (!(c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '+' || c == '-' || c == '.')) return false
        }
        return true
    }

    /** QUrlQuery::setQuery on url.query(): items split on '&', key/value at the first '='. */
    @JvmStatic
    fun parseQuery(rawQuery: String): LinkQuery {
        if (rawQuery.isEmpty()) return LinkQuery.EMPTY
        val text = LinkCodec.tolerant(rawQuery)
        val items = ArrayList<Pair<String, String>>()
        for (segment in text.split('&')) {
            val eq = segment.indexOf('=')
            val key = LinkCodec.decodePretty(if (eq >= 0) segment.substring(0, eq) else segment)
            val value = if (eq >= 0) LinkCodec.decodePretty(segment.substring(eq + 1)) else ""
            items.add(key to value)
        }
        return LinkQuery(items)
    }
}
