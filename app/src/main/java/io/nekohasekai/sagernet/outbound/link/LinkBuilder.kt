package io.nekohasekai.sagernet.outbound.link

/**
 * Builds `QUrl::toString(QUrl::FullyEncoded)` output for the setScheme/setHost/setPort/setUserName/setPassword/
 * setFragment/setQuery(QUrlQuery) sequence every desktop ExportToLink uses. An invalid host or port yields ""
 * exactly like toString() on an invalid QUrl.
 */
class LinkBuilder(@JvmField val scheme: String) {
    @JvmField var host: String = ""
    @JvmField var port: Int = -1
    @JvmField var fragment: String? = null

    /** setQuery(query.toString(QUrl::FullyEncoded).replace('+', "%2B"), QUrl::TolerantMode) (xrayVless.cpp:82). */
    @JvmField var plusAsEscape: Boolean = false
    private var userName: String? = null
    private var password: String? = null
    private val query = ArrayList<Item>()

    private class Item(val key: String, val value: String, val encoded: Boolean)

    /** QUrl::setUserName: the user-info section becomes present even for an empty value. */
    fun setUserName(value: String): LinkBuilder {
        userName = value
        return this
    }

    /** QUrl::setPassword: emits ":password" even when empty. */
    fun setPassword(value: String): LinkBuilder {
        password = value
        return this
    }

    /** QUrlQuery::addQueryItem with a plain (decoded) value. */
    fun addQueryItem(key: String, value: String): LinkBuilder {
        query.add(Item(key, value, false))
        return this
    }

    /**
     * QUrlQuery::addQueryItem with a value that already went through QUrl::toPercentEncoding(): QUrlQuery keeps
     * such escapes and the FullyEncoded output reproduces the string verbatim (shadowsocks.cpp:135).
     */
    fun addQueryItemPercentEncoded(key: String, encodedValue: String): LinkBuilder {
        query.add(Item(key, encodedValue, true))
        return this
    }

    /** mergeUrlQuery (utils.cpp:7-14). */
    fun addQueryItems(items: List<Pair<String, String>>): LinkBuilder {
        for ((k, v) in items) query.add(Item(k, v, false))
        return this
    }

    fun build(): String {
        if (port > 65535 || port < -1) return ""
        val hostEncoded = Hosts.normalizeHost(host, percentDecode = false) ?: return ""
        val sb = StringBuilder()
        if (scheme.isNotEmpty()) sb.append(scheme).append(':')
        sb.append("//")
        if (userName != null || password != null) {
            sb.append(LinkCodec.encodeUserName(userName ?: ""))
            password?.let { sb.append(':').append(LinkCodec.encodePassword(it)) }
            sb.append('@')
        }
        sb.append(Hosts.formatForUrl(hostEncoded))
        if (port >= 0) sb.append(':').append(port)
        if (query.isNotEmpty()) {
            val q = StringBuilder()
            for (item in query) {
                if (q.isNotEmpty()) q.append('&')
                q.append(LinkCodec.encodeQueryPart(item.key)).append('=')
                q.append(if (item.encoded) item.value else LinkCodec.encodeQueryPart(item.value))
            }
            val text = q.toString()
            sb.append('?').append(if (plusAsEscape) text.replace("+", "%2B") else text)
        }
        fragment?.let { sb.append('#').append(LinkCodec.encodeFragment(it)) }
        return sb.toString()
    }

    companion object {
        /** QUrlQuery::toString() of a stand-alone item list, for the callers that only need a query string. */
        @JvmStatic
        fun formatQuery(items: List<Pair<String, String>>): String =
            items.joinToString("&") { (k, v) -> LinkCodec.encodeQueryPart(k) + "=" + LinkCodec.encodeQueryPart(v) }
    }
}
