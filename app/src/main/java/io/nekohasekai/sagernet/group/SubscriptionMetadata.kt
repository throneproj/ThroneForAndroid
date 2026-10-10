package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.SubUserInfo
import io.nekohasekai.sagernet.outbound.`import`.ProfileImport
import io.nekohasekai.sagernet.outbound.link.Base64Strict

/**
 * What a subscription says about itself (GroupUpdater::fetch, GroupUpdater.cpp:615-697): the response headers
 * Subscription-UserInfo, profile-update-interval, Profile-Title, Profile-Web-Page-Url, Support-Url and Announce, then
 * the body's leading `#key: value` (or `//key: value`) comment lines for whatever the headers left out.
 */
internal object SubscriptionMetadata {

    private const val HEAD_CHARS = 16384
    private const val MAX_INTERVAL_HOURS = 24L * 366

    /** ProfileImport.Scan.trim's character sets: a BOM is trimmed only at the front. */
    private const val TRAILING_TRIM = " \t\n\r\u000B\u000C\u00A0"
    private const val LEADING_TRIM = "$TRAILING_TRIM\uFEFF"

    /** [header] looks a response header up case-insensitively, "" when absent (none for `content://`). */
    fun read(header: (String) -> String, body: String): SubUserInfo {
        val info = SubUserInfo()
        val userInfo = header("Subscription-UserInfo")
        if (userInfo.isNotEmpty()) SubUserInfo.parseHeader(userInfo).takeIf { it.valid }?.copyQuotaTo(info)
        var userInfoSeen = info.valid

        val interval = header("profile-update-interval").ifEmpty { header("x-profile-update-interval") }
        parseUpdateInterval(interval).takeIf { it > 0 }?.let {
            info.serverInterval = it
            info.valid = true
        }
        decodeHeaderValue(header("Profile-Title")).takeIf { it.isNotEmpty() }?.let {
            info.title = it
            info.valid = true
        }
        header("Profile-Web-Page-Url").takeIf { it.isNotEmpty() }?.let {
            info.webUrl = it
            info.valid = true
        }
        header("Support-Url").takeIf { it.isNotEmpty() }?.let {
            info.supportUrl = it
            info.valid = true
        }
        decodeHeaderValue(header("Announce")).takeIf { it.isNotEmpty() }?.let {
            info.announce = it
            info.valid = true
        }

        ProfileImport.Scan.forEachLine(metadataHead(body)) { rawLine ->
            val line = ProfileImport.Scan.trim(rawLine)
            if (line.isEmpty()) return@forEachLine true
            if (!line.startsWith('#') && !line.startsWith("//")) return@forEachLine false
            val comment = ProfileImport.Scan.trim(if (line.startsWith("//")) line.substring(2) else line.substring(1))
            val colon = comment.indexOf(':')
            if (colon < 0) return@forEachLine true
            val key = ProfileImport.Scan.trim(comment.substring(0, colon))
            val value = ProfileImport.Scan.trim(comment.substring(colon + 1))
            fun has(prefix: String) = key.startsWith(prefix, ignoreCase = true)
            when {
                !userInfoSeen && has("subscription-userinfo") -> {
                    val parsed = SubUserInfo.parseHeader(value)
                    if (parsed.valid) {
                        parsed.copyQuotaTo(info)
                        userInfoSeen = true
                    }
                }

                info.serverInterval == 0 && has("profile-update-interval") -> {
                    val hours = parseUpdateInterval(value)
                    if (hours > 0) {
                        info.serverInterval = hours
                        info.valid = true
                    }
                }

                info.title.isEmpty() && has("profile-title") -> {
                    info.title = decodeHeaderValue(value)
                    if (info.title.isNotEmpty()) info.valid = true
                }

                info.webUrl.isEmpty() && has("profile-web-page-url") -> {
                    info.webUrl = value
                    if (value.isNotEmpty()) info.valid = true
                }

                info.supportUrl.isEmpty() && has("support-url") -> {
                    info.supportUrl = value
                    if (value.isNotEmpty()) info.valid = true
                }

                info.announce.isEmpty() && (has("announce") || has("notice")) -> {
                    info.announce = decodeHeaderValue(value)
                    if (info.announce.isNotEmpty()) info.valid = true
                }
            }
            true
        }
        return info
    }

    private fun SubUserInfo.copyQuotaTo(target: SubUserInfo) {
        target.upload = upload
        target.download = download
        target.total = total
        target.expire = expire
        target.hasQuota = hasQuota
        target.valid = true
    }

    /** decodeHeaderValue (GroupUpdater.cpp:208-222): a `base64:` value, url-safe first, then lenient standard. */
    fun decodeHeaderValue(raw: String): String {
        val str = raw.trim()
        if (!str.startsWith("base64:", ignoreCase = true)) return str
        val payload = str.substring(7).trim()
        if (payload.isEmpty()) return ""
        val decoded = Base64Strict.decode(payload, urlSafe = true)?.takeIf { it.isNotEmpty() }
            ?: Base64Strict.decodeLenient(payload)
        return if (decoded.isNotEmpty()) String(decoded, Charsets.UTF_8).trim() else ""
    }

    /** parseUpdateInterval (GroupUpdater.cpp:224-241): hours; h/d/s suffixes are tolerated. */
    fun parseUpdateInterval(raw: String): Int {
        var text = raw.trim().replace("\"", "").replace("'", "").lowercase()
        var unit = 3600L
        when {
            text.endsWith('h') -> text = text.dropLast(1)
            text.endsWith('d') -> {
                text = text.dropLast(1)
                unit = 86400L
            }

            text.endsWith('s') -> {
                text = text.dropLast(1)
                unit = 1L
            }
        }
        val value = text.trim().toLongOrNull() ?: return 0
        if (value <= 0) return 0
        return (minOf(value, MAX_INTERVAL_HOURS * 3600) * unit / 3600).coerceIn(1L, MAX_INTERVAL_HOURS).toInt()
    }

    /** metadataHead (GroupUpdater.cpp:244-258): the body's head, decoded first when the body is one base64 blob. */
    private fun metadataHead(body: String): String {
        // scan::trim(body).substr(0, kHeadChars) by index: the body can be tens of MiB and only its head is read.
        var start = 0
        while (start < body.length && body[start] in LEADING_TRIM) start++
        var end = body.length
        while (end > start && body[end - 1] in TRAILING_TRIM) end--
        val head = body.substring(start, minOf(end, start + HEAD_CHARS))
        val compact = head.filterNot { it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000B' || it == '\u000C' }
        if (compact.isEmpty() || !ProfileImport.Scan.looksLikeBase64(compact)) return head
        return String(Base64Strict.decodeLenient(compact.substring(0, compact.length and 3.inv())), Charsets.UTF_8)
    }
}
