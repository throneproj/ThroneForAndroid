package io.nekohasekai.sagernet.outbound.link

/**
 * Percent-encoding/decoding with the exact per-component behaviour of Qt's QUrl (TolerantMode parsing,
 * `toString(QUrl::FullyEncoded)` output) and QUrlQuery (PrettyDecoded / FullyDecoded getters), as verified
 * against Qt 6.11. The character sets below are what Qt leaves unencoded in each component.
 */
object LinkCodec {
    private const val HEX = "0123456789ABCDEF"
    private const val USER_RAW = "!$&'()*+,-.;=_~"
    private const val PASSWORD_RAW = "!$&'()*+,-.:;=_~"
    private const val QUERY_RAW = "!$'()*+,-./:;?@[]_~"
    private const val FRAGMENT_RAW = "!$&'()*+,-./:;=?@[]_~"
    private const val UNRESERVED_RAW = "-._~"

    // Escapes QUrlQuery decodes in its PrettyDecoded form: unreserved, space, the "unsafe" set and the query
    // delimiters & = #; every other delimiter (! $ % ' ( ) * + , / : ; ? @ [ ]) and control characters stay encoded.
    private const val PRETTY_DECODE = " \"#&-.<=>\\^_`{|}~"

    @JvmStatic fun encodeUserName(s: String): String = encode(s, USER_RAW)
    @JvmStatic fun encodePassword(s: String): String = encode(s, PASSWORD_RAW)
    @JvmStatic fun encodeQueryPart(s: String): String = encode(s, QUERY_RAW)
    @JvmStatic fun encodeFragment(s: String): String = encode(s, FRAGMENT_RAW)

    /** QUrl::toPercentEncoding(): everything but unreserved characters is encoded. */
    @JvmStatic fun percentEncodeAll(s: String): String = encode(s, UNRESERVED_RAW)

    private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun encode(s: String, raw: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code < 0x80) {
                if (isAlnum(c) || raw.indexOf(c) >= 0) sb.append(c) else appendEscape(sb, c.code)
                i++
                continue
            }
            val cp = s.codePointAt(i)
            val len = Character.charCount(cp)
            for (b in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) appendEscape(sb, b.toInt() and 0xFF)
            i += len
        }
        return sb.toString()
    }

    private fun appendEscape(sb: StringBuilder, byte: Int) {
        sb.append('%').append(HEX[(byte shr 4) and 0xF]).append(HEX[byte and 0xF])
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun escapeAt(s: String, i: Int): Int {
        if (i >= s.length || s[i] != '%' || i + 2 >= s.length) return -1
        val h = hexValue(s[i + 1])
        val l = hexValue(s[i + 2])
        return if (h < 0 || l < 0) -1 else (h shl 4) or l
    }

    @JvmStatic
    fun hasInvalidPercent(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%' && escapeAt(s, i) < 0) return true
            i++
        }
        return false
    }

    /**
     * QUrlQuery::queryItemValue(key, QUrl::FullyEncoded) recoded from the stored PrettyDecoded [pretty] value: kept
     * escapes stay (unreserved ones decode), every character outside [QUERY_RAW] is encoded, delimiters are left as
     * they are; one malformed escape makes every '%' literal and encoded.
     */
    @JvmStatic
    fun recodeQueryFullyEncoded(pretty: String): String {
        val keepEscapes = !hasInvalidPercent(pretty)
        val sb = StringBuilder(pretty.length + 16)
        var i = 0
        while (i < pretty.length) {
            val b = if (keepEscapes) escapeAt(pretty, i) else -1
            if (b >= 0) {
                val ch = b.toChar()
                if (b < 0x80 && (isAlnum(ch) || UNRESERVED_RAW.indexOf(ch) >= 0)) sb.append(ch) else appendEscape(sb, b)
                i += 3
                continue
            }
            val c = pretty[i]
            if (c.code < 0x80) {
                if (isAlnum(c) || QUERY_RAW.indexOf(c) >= 0) sb.append(c) else appendEscape(sb, c.code)
                i++
                continue
            }
            val cp = pretty.codePointAt(i)
            for (byte in String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)) appendEscape(sb, byte.toInt() and 0xFF)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    /** QUrl TolerantMode: one malformed escape makes every '%' of that component a literal percent sign. */
    @JvmStatic
    fun tolerant(s: String): String = if (hasInvalidPercent(s)) s.replace("%", "%25") else s

    /** FullyDecoded: every escape decoded, invalid UTF-8 bytes become U+FFFD one by one. */
    @JvmStatic
    fun decodeFully(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val b = escapeAt(raw, i)
            if (b < 0) {
                sb.append(raw[i])
                i++
                continue
            }
            if (b < 0x80) {
                sb.append(b.toChar())
                i += 3
                continue
            }
            val consumed = decodeUtf8Escape(raw, i, sb)
            if (consumed > 0) {
                i += consumed
            } else {
                sb.append('�')
                i += 3
            }
        }
        return sb.toString()
    }

    /**
     * PrettyDecoded as QUrlQuery stores and returns query keys/values: see [PRETTY_DECODE]; kept escapes are
     * upper-cased, raw control characters are encoded, valid UTF-8 escape sequences are decoded.
     */
    @JvmStatic
    fun decodePretty(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val b = escapeAt(raw, i)
            if (b < 0) {
                if (c.code < 0x20 || c.code == 0x7F) appendEscape(sb, c.code) else sb.append(c)
                i++
                continue
            }
            if (b < 0x80) {
                val ch = b.toChar()
                if (isAlnum(ch) || PRETTY_DECODE.indexOf(ch) >= 0) sb.append(ch) else appendEscape(sb, b)
                i += 3
                continue
            }
            val consumed = decodeUtf8Escape(raw, i, sb)
            if (consumed > 0) {
                i += consumed
            } else {
                appendEscape(sb, b)
                i += 3
            }
        }
        return sb.toString()
    }

    // Decodes one strictly valid UTF-8 sequence written as consecutive escapes; returns the characters consumed or 0.
    private fun decodeUtf8Escape(s: String, start: Int, sb: StringBuilder): Int {
        val b0 = escapeAt(s, start)
        val need = when (b0) {
            in 0xC2..0xDF -> 2
            in 0xE0..0xEF -> 3
            in 0xF0..0xF4 -> 4
            else -> return 0
        }
        var cp = when (need) {
            2 -> b0 and 0x1F
            3 -> b0 and 0x0F
            else -> b0 and 0x07
        }
        for (k in 1 until need) {
            val b = escapeAt(s, start + 3 * k)
            if (b < 0) return 0
            val lo = when {
                k == 1 && b0 == 0xE0 -> 0xA0
                k == 1 && b0 == 0xF0 -> 0x90
                else -> 0x80
            }
            val hi = when {
                k == 1 && b0 == 0xED -> 0x9F
                k == 1 && b0 == 0xF4 -> 0x8F
                else -> 0xBF
            }
            if (b < lo || b > hi) return 0
            cp = (cp shl 6) or (b and 0x3F)
        }
        sb.appendCodePoint(cp)
        return 3 * need
    }
}
