package io.nekohasekai.sagernet.scan

/** Scanner::ParseResult (IpListParse.h). */
class IpListParseResult(
    @JvmField val entries: List<IpListEntry>,
    @JvmField val rejected: Int,
    @JvmField val duplicates: Int,
    /** At most five rejected tokens, each cut to 64 characters. */
    @JvmField val samples: List<String>,
)

/**
 * src/scanner/IpListParse.cpp: the IP list text format and the canonical CIDR form entries are stored in. Lines, CSV
 * or whitespace separated: ip, cidr, ip:port, [v6]:port, "cidr port", a-b ranges; '#', "//" and ';' comments.
 */
object IpListParse {

    private const val MAX_SAMPLES = 5
    private const val SAMPLE_CHARS = 64

    /** A token that is no address; ports are -1 when a token names none. */
    private const val NOT_ADDRESS = Int.MIN_VALUE

    private val COLUMN_NAMES = listOf(
        "address", "host", "hostname", "cidr", "subnet", "network", "prefix", "range", "endpoint", "server", "target",
    )

    /** IPv4 lives in the low 32 bits of [lo]; [hi] and [lo] are read unsigned. */
    private class Addr(@JvmField var v6: Boolean = false, @JvmField var hi: Long = 0L, @JvmField var lo: Long = 0L) {
        fun copy() = Addr(v6, hi, lo)

        fun toV4() {
            v6 = false
            hi = 0L
            lo = lo and 0xffffffffL
        }
    }

    private enum class Kind { Address, Port, Other }

    private class Token(@JvmField val text: String, @JvmField val field: Int) {
        var kind = Kind.Other
        var cidrs: List<String> = emptyList()
        var port = -1
        var consumed = false
    }

    /** QChar::isSpace(). */
    private fun isSpace(c: Char): Boolean {
        if (c == ' ' || c in '\t'..'\r' || c == '\u0085' || c == ' ') return true
        if (c.code < 0x80) return false
        val type = Character.getType(c)
        return type == Character.SPACE_SEPARATOR.toInt() || type == Character.LINE_SEPARATOR.toInt() ||
            type == Character.PARAGRAPH_SEPARATOR.toInt()
    }

    /** QString::trimmed(). */
    private fun trimmed(s: String): String {
        var start = 0
        var end = s.length
        while (start < end && isSpace(s[start])) start++
        while (end > start && isSpace(s[end - 1])) end--
        return s.substring(start, end)
    }

    private fun bits(addr: Addr): Int = if (addr.v6) 128 else 32

    /** The value of at most [maxDigits] ASCII digits, -1 otherwise. */
    private fun decimal(text: String, maxDigits: Int): Int {
        if (text.isEmpty() || text.length > maxDigits) return -1
        var value = 0
        for (c in text) {
            if (c !in '0'..'9') return -1
            value = value * 10 + (c - '0')
        }
        return value
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** 0..65535, or -1. */
    private fun parsePort(text: String): Int = decimal(text, 5).takeIf { it in 0..65535 } ?: -1

    // inet_aton's short ("1.2.3"), integer and octal ("010.0.0.1") forms are refused, as Go's netip does.
    private fun v4(text: String): Long {
        var value = 0L
        var parts = 0
        var start = 0
        while (true) {
            val dot = text.indexOf('.', start)
            val part = if (dot < 0) text.substring(start) else text.substring(start, dot)
            val octet = decimal(part, 3)
            if (octet !in 0..255) return -1L
            if (part.length > 1 && part[0] == '0') return -1L
            if (++parts > 4) return -1L
            value = (value shl 8) or octet.toLong()
            if (dot < 0) break
            start = dot + 1
        }
        return if (parts == 4) value else -1L
    }

    /** Fills [groups] from 0; the number of groups, or -1 when [text] is no group list. */
    private fun v6Part(text: String, allowV4Tail: Boolean, groups: IntArray): Int {
        if (text.isEmpty()) return 0
        var count = 0
        var start = 0
        while (true) {
            val colon = text.indexOf(':', start)
            val group = if (colon < 0) text.substring(start) else text.substring(start, colon)
            if (colon < 0 && allowV4Tail && group.contains('.')) {
                if (count > 6) return -1
                val v4 = v4(group)
                if (v4 < 0) return -1
                groups[count++] = (v4 ushr 16).toInt()
                groups[count++] = (v4 and 0xffffL).toInt()
                return count
            }
            if (group.isEmpty() || group.length > 4 || count >= 8) return -1
            var value = 0
            for (c in group) {
                val digit = hexDigit(c)
                if (digit < 0) return -1
                value = (value shl 4) or digit
            }
            groups[count++] = value
            if (colon < 0) return count
            start = colon + 1
        }
    }

    private fun v6(text: String, out: Addr): Boolean {
        val head = IntArray(8)
        val tail = IntArray(8)
        val headCount: Int
        var tailCount = 0
        val gap = text.indexOf("::")
        if (gap < 0) {
            headCount = v6Part(text, true, head)
            if (headCount != 8) return false
        } else {
            if (text.indexOf("::", gap + 1) >= 0) return false
            headCount = v6Part(text.substring(0, gap), false, head)
            if (headCount < 0) return false
            tailCount = v6Part(text.substring(gap + 2), true, tail)
            if (tailCount < 0 || headCount + tailCount > 7) return false
        }
        val groups = IntArray(8)
        for (i in 0 until headCount) groups[i] = head[i]
        for (i in 0 until tailCount) groups[8 - tailCount + i] = tail[i]
        var hi = 0L
        var lo = 0L
        for (i in 0 until 4) hi = (hi shl 16) or groups[i].toLong()
        for (i in 4 until 8) lo = (lo shl 16) or groups[i].toLong()
        out.hi = hi
        out.lo = lo
        return true
    }

    private fun address(raw: String, out: Addr): Boolean {
        if (raw.contains(':')) {
            val zone = raw.indexOf('%')
            out.v6 = true
            return v6(if (zone >= 0) raw.substring(0, zone) else raw, out)
        }
        val v4 = v4(raw)
        if (v4 < 0) return false
        out.v6 = false
        out.hi = 0L
        out.lo = v4
        return true
    }

    private fun isMapped(addr: Addr): Boolean = addr.v6 && addr.hi == 0L && (addr.lo ushr 32) == 0xffffL

    private fun mask(addr: Addr, prefix: Int) {
        when {
            !addr.v6 -> addr.lo = addr.lo and ((0xffffffffL shl (32 - prefix)) and 0xffffffffL)
            prefix < 64 -> {
                addr.hi = addr.hi and (if (prefix == 0) 0L else -1L shl (64 - prefix))
                addr.lo = 0L
            }

            prefix == 64 -> addr.lo = 0L
            prefix < 128 -> addr.lo = addr.lo and (-1L shl (128 - prefix))
        }
    }

    private fun format(addr: Addr): String {
        if (!addr.v6) {
            val v = addr.lo
            return "${(v ushr 24) and 0xff}.${(v ushr 16) and 0xff}.${(v ushr 8) and 0xff}.${v and 0xff}"
        }
        val groups = IntArray(8)
        for (i in 0 until 4) groups[i] = ((addr.hi ushr (48 - 16 * i)) and 0xffffL).toInt()
        for (i in 0 until 4) groups[4 + i] = ((addr.lo ushr (48 - 16 * i)) and 0xffffL).toInt()
        // RFC 5952, as Go's netip prints it: the first longest run of two or more zero groups becomes "::".
        var bestStart = -1
        var bestLength = 1
        var i = 0
        while (i < 8) {
            if (groups[i] != 0) {
                i++
                continue
            }
            var j = i
            while (j < 8 && groups[j] == 0) j++
            if (j - i > bestLength) {
                bestStart = i
                bestLength = j - i
            }
            i = j
        }
        val out = StringBuilder()
        i = 0
        while (i < 8) {
            if (i == bestStart) {
                out.append("::")
                i += bestLength
                continue
            }
            if (out.isNotEmpty() && out[out.length - 1] != ':') out.append(':')
            out.append(Integer.toHexString(groups[i]))
            i++
        }
        return out.toString()
    }

    private fun cidr(addr: Addr, prefix: Int): String = if (prefix >= bits(addr)) format(addr) else format(addr) + "/" + prefix

    /** The prefix of [raw] parsed into [out] (masked), or -1. */
    private fun parseNetwork(raw: String, out: Addr): Int {
        val text = trimmed(raw)
        var body = text
        var suffix = ""
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close < 0) return -1
            body = text.substring(1, close)
            suffix = text.substring(close + 1)
            if (suffix.isNotEmpty() && !suffix.startsWith('/')) return -1
        }
        var prefixText = ""
        var hasPrefix = false
        val slash = body.indexOf('/')
        if (slash >= 0) {
            if (suffix.isNotEmpty()) return -1
            prefixText = body.substring(slash + 1)
            body = body.substring(0, slash)
            hasPrefix = true
        } else if (suffix.isNotEmpty()) {
            prefixText = suffix.substring(1)
            hasPrefix = true
        }
        if (!address(body, out)) return -1
        val bits = bits(out)
        var prefix = bits
        if (hasPrefix) {
            val value = decimal(prefixText, 3)
            if (value < 0 || value > bits) return -1
            prefix = value
        }
        if (isMapped(out) && prefix >= 96) {
            out.toV4()
            prefix -= 96
        }
        if (prefix == 0) return -1
        mask(out, prefix)
        return prefix
    }

    private fun less(a: Addr, b: Addr): Boolean =
        if (a.hi != b.hi) java.lang.Long.compareUnsigned(a.hi, b.hi) < 0 else java.lang.Long.compareUnsigned(a.lo, b.lo) < 0

    private fun bitWidth(x: Long): Int = 64 - java.lang.Long.numberOfLeadingZeros(x)

    // 128-bit arithmetic on (hi, lo); false when the cover would be the whole address space (/0).
    private fun coverRange(start: Addr, last: Addr, out: MutableList<String>): Boolean {
        val first = start.copy()
        val bits = bits(first)
        val topHi = if (first.v6) -1L else 0L
        val topLo = if (first.v6) -1L else 0xffffffffL
        if (first.hi == 0L && first.lo == 0L && last.hi == topHi && last.lo == topLo) return false
        while (true) {
            val align = minOf(
                when {
                    first.lo != 0L -> java.lang.Long.numberOfTrailingZeros(first.lo)
                    first.hi != 0L -> 64 + java.lang.Long.numberOfTrailingZeros(first.hi)
                    else -> 128
                },
                bits,
            )
            val diffLo = last.lo - first.lo
            val diffHi = last.hi - first.hi - (if (java.lang.Long.compareUnsigned(last.lo, first.lo) < 0) 1L else 0L)
            val sizeLo = diffLo + 1
            val sizeHi = diffHi + (if (sizeLo == 0L) 1L else 0L)
            val fit = if (sizeHi != 0L) 64 + bitWidth(sizeHi) - 1 else bitWidth(sizeLo) - 1
            val k = minOf(align, fit)
            out.add(cidr(first, bits - k))
            // first is aligned to 2^k, so OR-ing 2^k - 1 yields the block's last address without a carry.
            val blockEnd = first.copy()
            if (k >= 64) {
                blockEnd.lo = -1L
                if (k > 64) blockEnd.hi = blockEnd.hi or ((1L shl (k - 64)) - 1)
            } else if (k > 0) {
                blockEnd.lo = blockEnd.lo or ((1L shl k) - 1)
            }
            if (blockEnd.hi == last.hi && blockEnd.lo == last.lo) return true
            first.lo = blockEnd.lo + 1
            first.hi = blockEnd.hi + (if (first.lo == 0L) 1L else 0L)
        }
    }

    private fun rangeEnd(raw: String, out: Addr): Boolean {
        val text = if (raw.startsWith('[') && raw.endsWith(']')) raw.substring(1, raw.length - 1) else raw
        if (text.contains('/') || !address(text, out)) return false
        if (isMapped(out)) out.toV4()
        return true
    }

    private fun rangeToken(token: String, cidrs: MutableList<String>): Int {
        val dash = token.indexOf('-')
        val left = token.substring(0, dash)
        var right = token.substring(dash + 1)
        var port = -1
        if (right.startsWith('[')) {
            val close = right.indexOf(']')
            if (close < 0) return NOT_ADDRESS
            val rest = right.substring(close + 1)
            if (rest.isNotEmpty()) {
                if (!rest.startsWith(':')) return NOT_ADDRESS
                port = parsePort(rest.substring(1))
                if (port < 0) return NOT_ADDRESS
            }
            right = right.substring(0, close + 1)
        } else if (right.count { it == ':' } == 1) {
            val colon = right.indexOf(':')
            port = parsePort(right.substring(colon + 1))
            if (port < 0) return NOT_ADDRESS
            right = right.substring(0, colon)
        }
        var first = Addr()
        var last = Addr()
        if (!rangeEnd(left, first)) return NOT_ADDRESS
        val octet = if (first.v6) -1 else decimal(right, 3)
        if (octet >= 0) {
            // The last-octet shorthand: 1.2.3.4-20.
            if (octet > 255 || (right.length > 1 && right[0] == '0')) return NOT_ADDRESS
            last = first.copy()
            last.lo = (first.lo and 0xffL.inv()) or octet.toLong()
        } else if (!rangeEnd(right, last) || last.v6 != first.v6) {
            return NOT_ADDRESS
        }
        if (less(last, first)) first = last.also { last = first }
        return if (coverRange(first, last, cidrs)) port else NOT_ADDRESS
    }

    /** The token's port (-1 when it names none) with its networks added to [cidrs], or [NOT_ADDRESS]. */
    private fun addressToken(token: String, cidrs: MutableList<String>): Int {
        if (token.isEmpty()) return NOT_ADDRESS
        if (token.contains('-')) return rangeToken(token, cidrs)
        var network = token
        var port = -1
        if (token.startsWith('[')) {
            val close = token.indexOf(']')
            if (close < 0) return NOT_ADDRESS
            val rest = token.substring(close + 1)
            if (rest.startsWith(':')) {
                port = parsePort(rest.substring(1))
                if (port < 0) return NOT_ADDRESS
                network = token.substring(0, close + 1)
            }
        } else if (token.count { it == ':' } == 1) {
            val colon = token.indexOf(':')
            port = parsePort(token.substring(colon + 1))
            if (port < 0) return NOT_ADDRESS
            network = token.substring(0, colon)
        }
        val addr = Addr()
        val prefix = parseNetwork(network, addr)
        if (prefix < 0) return NOT_ADDRESS
        cidrs.add(cidr(addr, prefix))
        return port
    }

    // Tabular rows ignore unrelated columns, but a field shaped like an address that fails to parse is reported.
    private fun looksLikeAddress(token: String): Boolean {
        if (token.isEmpty()) return false
        if (token.startsWith('[') || token.contains("::") || token.count { it == ':' } >= 7) return true
        if (token.count { it == '.' } < 2 || token[0] !in '0'..'9') return false
        return token.all { it in '0'..'9' || it == '.' || it == '/' || it == ':' || it == '-' }
    }

    // A header must name a column: a bare label line would otherwise switch the file to tabular parsing.
    private fun isColumnName(token: String): Boolean {
        if (token.startsWith("ip", ignoreCase = true) || token.startsWith("addr", ignoreCase = true) ||
            token.contains("port", ignoreCase = true)
        ) return true
        return COLUMN_NAMES.any { token.equals(it, ignoreCase = true) }
    }

    private fun isSeparator(c: Char): Boolean = c == ',' || c == ';' || c == '\t' || c == '|'

    private fun rangeChar(c: Char): Boolean = hexDigit(c) >= 0 || c == '.' || c == ':' || c == '[' || c == ']'

    /** "1.2.3.4 - 1.2.3.9" and en dashes become one "a-b" token. */
    private fun joinRanges(line: String): String {
        val out = StringBuilder(line.length)
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '-' || c == '–') {
                var end = out.length
                while (end > 0 && isSpace(out[end - 1])) end--
                var next = i + 1
                while (next < line.length && isSpace(line[next])) next++
                if (end > 0 && next < line.length && rangeChar(out[end - 1]) && rangeChar(line[next])) {
                    out.setLength(end)
                    out.append('-')
                    i = next
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun unquote(raw: String): String {
        val text = trimmed(raw)
        var start = 0
        var end = text.length
        while (start < end && (text[start] == '"' || text[start] == '\'')) start++
        while (end > start && (text[end - 1] == '"' || text[end - 1] == '\'')) end--
        return trimmed(text.substring(start, end))
    }

    private fun split(text: String, bySeparator: Boolean): List<String> {
        val out = ArrayList<String>()
        var start = 0
        for (i in 0..text.length) {
            val boundary = i == text.length || (if (bySeparator) isSeparator(text[i]) else isSpace(text[i]))
            if (!boundary) continue
            val part = text.substring(start, i)
            // Separated fields keep their empty cells so column indexes line up with the header.
            if (bySeparator || part.isNotEmpty()) out.add(part)
            start = i + 1
        }
        return out
    }

    /** NormalizeCidr: the canonical form, "" when invalid; IPv4 needs all four octets, IPv6 a ':'. */
    @JvmStatic
    fun normalizeCidr(text: String): String {
        val addr = Addr()
        val prefix = parseNetwork(text, addr)
        return if (prefix < 0) "" else cidr(addr, prefix)
    }

    /** ParseIpListText on UTF-8 bytes (a file or a download). */
    @JvmStatic
    @JvmOverloads
    fun parseIpListText(bytes: ByteArray, defaultPort: Int = 0): IpListParseResult =
        parseIpListText(String(bytes, Charsets.UTF_8), defaultPort)

    /** ParseIpListText: entries keep the first of duplicate (cidr, port) pairs; a portless entry takes [defaultPort]. */
    @JvmStatic
    @JvmOverloads
    fun parseIpListText(text: String, defaultPort: Int = 0): IpListParseResult {
        val fallbackPort = if (defaultPort in 0..65535) defaultPort else 0
        val content = if (text.startsWith('﻿')) text.substring(1) else text
        val entries = ArrayList<IpListEntry>()
        val samples = ArrayList<String>()
        var rejected = 0
        var duplicates = 0
        val seen = HashSet<String>()
        var firstContentLine = true
        var tabularFile = false
        var portColumn = -1

        fun reject(token: String) {
            rejected++
            if (samples.size < MAX_SAMPLES) samples.add(token.take(SAMPLE_CHARS))
        }

        fun add(cidrs: List<String>, port: Int) {
            val p = if (port <= 0) fallbackPort else port
            for (cidr in cidrs) {
                if (!seen.add("$cidr $p")) {
                    duplicates++
                    continue
                }
                entries.add(IpListEntry(cidr, p, 0))
            }
        }

        var lineStart = 0
        while (lineStart < content.length) {
            var lineEnd = content.indexOf('\n', lineStart)
            if (lineEnd < 0) lineEnd = content.length
            var line = content.substring(lineStart, lineEnd)
            lineStart = lineEnd + 1

            line.indexOf('#').let { if (it >= 0) line = line.substring(0, it) }
            line.indexOf("//").let { if (it >= 0) line = line.substring(0, it) }
            line = trimmed(line)
            if (line.isEmpty() || line.startsWith(';')) continue
            if (line.contains('-') || line.contains('–')) line = joinRanges(line)

            val separated = line.any { isSeparator(it) }
            val fields = split(line, separated).map { unquote(it) }
            val tokens = ArrayList<Token>()
            for ((f, field) in fields.withIndex()) {
                for (word in split(field, false)) {
                    val token = Token(unquote(word), f)
                    if (token.text.isEmpty()) continue
                    val cidrs = ArrayList<String>()
                    val port = addressToken(token.text, cidrs)
                    if (port != NOT_ADDRESS) {
                        token.kind = Kind.Address
                        token.cidrs = cidrs
                        token.port = port
                    } else {
                        token.port = parsePort(token.text)
                        token.kind = if (token.port >= 0) Kind.Port else Kind.Other
                    }
                    tokens.add(token)
                }
            }
            if (tokens.isEmpty()) continue

            val tabular = separated || tabularFile
            var addresses = 0
            var addressShaped = false
            var namesColumn = false
            for (token in tokens) {
                if (token.kind == Kind.Address) addresses++ else if (looksLikeAddress(token.text)) addressShaped = true
                if (isColumnName(token.text)) namesColumn = true
            }

            if (addresses == 0) {
                if (firstContentLine && !addressShaped && namesColumn) {
                    firstContentLine = false
                    tabularFile = true
                    portColumn = fields.indexOfFirst { it.contains("port", ignoreCase = true) }
                    continue
                }
                firstContentLine = false
                when {
                    !tabular -> for (token in tokens) reject(token.text)
                    addressShaped -> for (token in tokens) if (looksLikeAddress(token.text)) reject(token.text)
                    else -> reject(line)
                }
                continue
            }
            firstContentLine = false

            for ((i, token) in tokens.withIndex()) {
                if (token.consumed) continue
                when (token.kind) {
                    Kind.Address -> {
                        var port = token.port
                        if (port < 0 && tabularFile) {
                            if (portColumn >= 0 && portColumn < fields.size && portColumn != token.field) {
                                val columnPort = parsePort(fields[portColumn])
                                if (columnPort >= 0) port = columnPort
                            }
                        } else if (port < 0 && i + 1 < tokens.size && tokens[i + 1].kind == Kind.Port) {
                            port = tokens[i + 1].port
                            tokens[i + 1].consumed = true
                        }
                        add(token.cidrs, port)
                    }

                    Kind.Port -> if (!tabular) reject(token.text)
                    Kind.Other -> if (!tabular || looksLikeAddress(token.text)) reject(token.text)
                }
            }
        }
        return IpListParseResult(entries, rejected, duplicates, samples)
    }

    /** FormatTarget: "address:port", IPv6 bracketed; the address alone without a port. */
    @JvmStatic
    fun formatTarget(address: String, port: Int): String {
        if (port <= 0) return address
        if (address.contains(':')) return "[$address]:$port"
        return "$address:$port"
    }

    /** FormatEntry: one line [parseIpListText] reads back as [entry]. */
    @JvmStatic
    fun formatEntry(entry: IpListEntry): String {
        if (isHostCidr(entry.cidr)) {
            val slash = entry.cidr.indexOf('/')
            return formatTarget(if (slash < 0) entry.cidr else entry.cidr.substring(0, slash), entry.port)
        }
        if (entry.port <= 0) return entry.cidr
        return entry.cidr + " " + entry.port
    }

    /** IsHostCidr: one address (no suffix, /32 or /128). */
    @JvmStatic
    fun isHostCidr(cidr: String): Boolean {
        if (cidr.isEmpty()) return false
        val slash = cidr.indexOf('/')
        if (slash < 0) return true
        return cidr.substring(slash + 1) == (if (cidr.substring(0, slash).contains(':')) "128" else "32")
    }

    /** AddressCount: saturates at 2^63; 0 for an empty or malformed CIDR. */
    @JvmStatic
    fun addressCount(cidr: String): ULong {
        if (cidr.isEmpty()) return 0uL
        val slash = cidr.indexOf('/')
        if (slash < 0) return 1uL
        val bits = if (cidr.substring(0, slash).contains(':')) 128 else 32
        val prefix = decimal(cidr.substring(slash + 1), 3)
        if (prefix < 0 || prefix > bits) return 0uL
        val hostBits = bits - prefix
        return if (hostBits >= 63) 1uL shl 63 else 1uL shl hostBits
    }

    /** EntriesFromCidrs: the valid ones, canonical and deduplicated, each with [defaultPort]. */
    @JvmStatic
    fun entriesFromCidrs(cidrs: List<String>, defaultPort: Int): List<IpListEntry> {
        val port = if (defaultPort in 0..65535) defaultPort else 0
        val entries = ArrayList<IpListEntry>(cidrs.size)
        val seen = HashSet<String>()
        for (text in cidrs) {
            val cidr = normalizeCidr(text)
            if (cidr.isEmpty() || !seen.add(cidr)) continue
            entries.add(IpListEntry(cidr, port, 0))
        }
        return entries
    }
}
