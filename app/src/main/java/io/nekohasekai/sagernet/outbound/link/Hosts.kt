package io.nekohasekai.sagernet.outbound.link

import java.text.Normalizer
import java.util.Locale

/** RFC 3492 Punycode encoder (the ACE form of one IDN label, without the "xn--" prefix). */
object Punycode {
    private const val BASE = 36
    private const val T_MIN = 1
    private const val T_MAX = 26
    private const val SKEW = 38
    private const val DAMP = 700
    private const val INITIAL_BIAS = 72
    private const val INITIAL_N = 128

    @JvmStatic
    fun encode(input: List<Int>): String {
        val out = StringBuilder()
        for (cp in input) if (cp < 0x80) out.append(cp.toChar())
        val basicCount = out.length
        var handled = basicCount
        if (basicCount > 0) out.append('-')
        var n = INITIAL_N
        var delta = 0
        var bias = INITIAL_BIAS
        while (handled < input.size) {
            var m = Int.MAX_VALUE
            for (cp in input) if (cp >= n && cp < m) m = cp
            delta += (m - n) * (handled + 1)
            n = m
            for (cp in input) {
                if (cp < n) delta++
                if (cp == n) {
                    var q = delta
                    var k = BASE
                    while (true) {
                        val t = if (k <= bias) T_MIN else if (k >= bias + T_MAX) T_MAX else k - bias
                        if (q < t) break
                        out.append(digit(t + (q - t) % (BASE - t)))
                        q = (q - t) / (BASE - t)
                        k += BASE
                    }
                    out.append(digit(q))
                    bias = adapt(delta, handled + 1, handled == basicCount)
                    delta = 0
                    handled++
                }
            }
            delta++
            n++
        }
        return out.toString()
    }

    private fun adapt(delta0: Int, numPoints: Int, firstTime: Boolean): Int {
        var delta = if (firstTime) delta0 / DAMP else delta0 / 2
        delta += delta / numPoints
        var k = 0
        while (delta > ((BASE - T_MIN) * T_MAX) / 2) {
            delta /= BASE - T_MIN
            k += BASE
        }
        return k + (BASE - T_MIN + 1) * delta / (delta + SKEW)
    }

    private fun digit(d: Int): Char = if (d < 26) ('a' + d) else ('0' + (d - 26))
}

/**
 * Host handling with QUrl's rules: hostname validation, IPv4/IPv6 canonical forms, IDN to ACE.
 * All results are the "FullyEncoded" host form QUrl::host(QUrl::FullyEncoded) reports (ACE, lower case,
 * IPv6 without brackets).
 */
object Hosts {

    /** toAceHost (utils.cpp:74-93): per element of a comma list; ASCII hosts untouched; unconvertible names untouched. */
    @JvmStatic
    fun toAceHost(host: String): String {
        if (host.contains(',')) return host.split(',').joinToString(",") { toAceHost(it.trim()) }
        if (isAscii(host)) return host
        val ace = toAce(host)
        return if (ace.isEmpty()) host else ace
    }

    /** QUrl::toAce(): the lower-cased ACE form, or "" for IP literals and names failing the hostname rules. */
    @JvmStatic
    fun toAce(host: String): String = normalizeName(host) ?: ""

    /**
     * The host QUrl stores for [raw]: null when QUrl rejects it. [percentDecode] is true for a parsed authority
     * (TolerantMode decodes escapes first) and false for QUrl::setHost(), where '%' is literal and thus invalid.
     */
    @JvmStatic
    fun normalizeHost(raw: String, percentDecode: Boolean): String? {
        if (raw.isEmpty()) return ""
        val host = if (percentDecode) LinkCodec.decodeFully(LinkCodec.tolerant(raw)) else raw
        if (host.startsWith("[")) return normalizeBracketed(host)
        if (!host.contains('%')) {
            parseIpv4(host)?.let { return formatIpv4(it) }
            normalizeName(host)?.let { return it }
        }
        // QUrl::setHost retries an unbracketed value as an IPv6 literal
        return if (!percentDecode) normalizeBracketed("[$host]") else null
    }

    private fun normalizeBracketed(host: String): String? {
        if (!host.endsWith("]") || host.length < 3) return null
        val inner = host.substring(1, host.length - 1)
        val pct = inner.indexOf('%')
        val addr = if (pct >= 0) inner.substring(0, pct) else inner
        val zone = if (pct >= 0) inner.substring(pct + 1) else ""
        if (pct >= 0 && zone.isEmpty()) return null
        val bytes = parseIpv6(addr) ?: return null
        val formatted = formatIpv6(bytes)
        return if (pct >= 0) "$formatted%25$zone" else formatted
    }

    /** Adds the brackets an IPv6 host needs inside a URL. */
    @JvmStatic
    fun formatForUrl(hostFullyEncoded: String): String =
        if (hostFullyEncoded.contains(':')) "[$hostFullyEncoded]" else hostFullyEncoded

    @JvmStatic
    fun isIpv6Address(text: String): Boolean {
        val t = text.removePrefix("[").removeSuffix("]")
        val pct = t.indexOf('%')
        return parseIpv6(if (pct >= 0) t.substring(0, pct) else t) != null
    }

    @JvmStatic
    fun isIpv4Address(text: String): Boolean = parseIpv4(text) != null

    @JvmStatic
    fun isIpAddress(text: String): Boolean = isIpv4Address(text) || isIpv6Address(text)

    /** DisplayAddress (Utils.hpp:187-190). */
    @JvmStatic
    fun displayAddress(server: String, port: Int): String {
        if (server.isEmpty() && port == 0) return ""
        val host = if (isIpv6Address(server)) "[" + server.replace("[", "").replace("]", "") + "]" else server
        return "$host:$port"
    }

    /**
     * IsPrivateHost (utils.cpp:109-141): Xray's own private list (common/geodata/consts.go): loopback, LAN, CGNAT
     * and reserved ranges, local-only names. Only IP literals are matched against the ranges; names never resolve.
     */
    @JvmStatic
    fun isPrivateHost(host: String): Boolean {
        var bare = host.trim().lowercase().replace("[", "").replace("]", "")
        if (bare.endsWith(".")) bare = bare.dropLast(1)
        if (bare.isEmpty()) return false

        hostAddressBytes(bare)?.let { ip -> return PRIVATE_RANGES.any { it.contains(ip) } }

        for (suffix in PRIVATE_SUFFIXES) {
            if (bare == suffix || bare.endsWith(".$suffix")) return true
        }
        // A dotless name only resolves through the local search domain.
        return DOTLESS_NAME.matches(bare)
    }

    private class Subnet(val prefix: ByteArray, val bits: Int) {
        fun contains(ip: ByteArray): Boolean {
            if (ip.size != prefix.size) return false
            for (i in 0 until bits / 8) if (ip[i] != prefix[i]) return false
            val rest = bits % 8
            if (rest == 0) return true
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (ip[bits / 8].toInt() and mask) == (prefix[bits / 8].toInt() and mask)
        }
    }

    private val PRIVATE_RANGES: List<Subnet> by lazy {
        listOf(
            "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16",
            "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24", "192.88.99.0/24", "192.168.0.0/16",
            "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3",
            "::/127", "fc00::/7", "fe80::/10", "ff00::/8",
        ).map { cidr ->
            val slash = cidr.indexOf('/')
            Subnet(hostAddressBytes(cidr.substring(0, slash))!!, cidr.substring(slash + 1).toInt())
        }
    }

    private val PRIVATE_SUFFIXES = listOf(
        "lan", "localdomain", "example", "invalid", "localhost", "test", "local", "home.arpa", "internal",
    )

    private val DOTLESS_NAME = Regex("[a-z]([a-z0-9-]{0,61}[a-z0-9])?")

    // QHostAddress::setAddress then the v4-mapped fold of toIPv4Address(): 4 bytes for IPv4 and ::ffff:a.b.c.d,
    // 16 for other IPv6 (the scope id after the last '%' dropped), null when the text is no IP literal.
    private fun hostAddressBytes(text: String): ByteArray? {
        if (text.contains(':')) {
            val pct = text.lastIndexOf('%')
            val v6 = parseIpv6(if (pct >= 0) text.substring(0, pct) else text)
            if (v6 != null) {
                val mapped = (0 until 10).all { v6[it].toInt() == 0 } && v6[10] == 0xFF.toByte() && v6[11] == 0xFF.toByte()
                return if (mapped) v6.copyOfRange(12, 16) else v6
            }
        }
        val v4 = parseIpv4(text) ?: return null
        return byteArrayOf((v4 shr 24).toByte(), (v4 shr 16).toByte(), (v4 shr 8).toByte(), v4.toByte())
    }

    private fun isAscii(s: String): Boolean = s.all { it.code <= 0x7F }

    // qt_ACE_do with ForbidLeadingDot: one trailing dot allowed, labels of 1-63 LDH/underscore characters
    // without leading or trailing hyphen, non-ASCII labels converted to ACE and everything lower-cased.
    private fun normalizeName(host: String): String? {
        if (host.isEmpty() || host.startsWith(".")) return null
        val trailingDot = host.endsWith(".")
        val body = if (trailingDot) host.substring(0, host.length - 1) else host
        val labels = body.split('.')
        val out = ArrayList<String>(labels.size)
        for (label in labels) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) return null
            val converted = if (isAscii(label)) label.lowercase() else (toAceLabel(label) ?: return null)
            if (!isValidAsciiLabel(converted)) return null
            out.add(converted)
        }
        return out.joinToString(".") + (if (trailingDot) "." else "")
    }

    // UTS #46 style mapping (NFKC + lower case, deviation characters such as U+00DF kept) followed by RFC 3492
    // Punycode, which is what QUrl::toAce does; java.net.IDN would apply IDNA2003 (fass.de, no emoji labels).
    private fun toAceLabel(label: String): String? {
        val mapped = Normalizer.normalize(label, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        if (isAscii(mapped)) return mapped
        val codePoints = ArrayList<Int>()
        var i = 0
        while (i < mapped.length) {
            val cp = mapped.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x80) {
                val c = cp.toChar()
                if (!(c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_')) return null
            } else if (Character.isWhitespace(cp) || Character.isISOControl(cp) || Character.isSpaceChar(cp)) {
                return null
            }
            codePoints.add(cp)
        }
        return "xn--" + Punycode.encode(codePoints)
    }

    private fun isValidAsciiLabel(label: String): Boolean {
        if (label.isEmpty() || label.length > 63) return false
        if (label.startsWith("-") || label.endsWith("-")) return false
        return label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }
    }

    /** QIPAddressUtils::parseIp4: one to four dot-separated C-style numbers (decimal, 0x hex, leading-zero octal). */
    @JvmStatic
    fun parseIpv4(text: String): Long? {
        if (text.isEmpty()) return null
        val parts = text.split('.')
        if (parts.size > 4) return null
        var address = 0L
        for ((index, part) in parts.withIndex()) {
            val x = parseCNumber(part) ?: return null
            val last = index == parts.size - 1
            if (!last || parts.size == 4) {
                if (x > 0xFF) return null
                address = (address shl 8) or x
            } else {
                when (parts.size) {
                    3 -> {
                        if (x > 0xFFFF) return null
                        address = (address shl 16) or x
                    }
                    2 -> {
                        if (x > 0xFFFFFF) return null
                        address = (address shl 24) or x
                    }
                    else -> address = x
                }
            }
        }
        return address
    }

    private fun parseCNumber(part: String): Long? {
        if (part.isEmpty()) return null
        val digits: String
        val radix: Int
        when {
            part.length > 2 && part[0] == '0' && (part[1] == 'x' || part[1] == 'X') -> {
                digits = part.substring(2)
                radix = 16
            }
            part.length > 1 && part[0] == '0' -> {
                digits = part.substring(1)
                radix = 8
            }
            else -> {
                digits = part
                radix = 10
            }
        }
        if (digits.isEmpty()) return null
        var value = 0L
        for (c in digits) {
            if (c.code > 0x7F) return null
            val d = Character.digit(c, radix)
            if (d < 0) return null
            value = value * radix + d
            if (value > 0xFFFFFFFFL) return null
        }
        return value
    }

    @JvmStatic
    fun formatIpv4(address: Long): String =
        "${(address shr 24) and 0xFF}.${(address shr 16) and 0xFF}.${(address shr 8) and 0xFF}.${address and 0xFF}"

    /** RFC 4291 text form (with `::`, and an optional trailing dotted quad) to 16 bytes; null when malformed. */
    @JvmStatic
    fun parseIpv6(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val dbl = text.indexOf("::")
        if (dbl >= 0 && text.indexOf("::", dbl + 1) >= 0) return null
        val head = if (dbl >= 0) text.substring(0, dbl) else text
        val tail = if (dbl >= 0) text.substring(dbl + 2) else ""
        val headGroups = parseGroups(head) ?: return null
        val tailGroups = parseGroups(tail) ?: return null
        val groups: List<Int> = if (dbl >= 0) {
            if (headGroups.size + tailGroups.size > 7) return null
            headGroups + List(8 - headGroups.size - tailGroups.size) { 0 } + tailGroups
        } else {
            if (headGroups.size != 8) return null
            headGroups
        }
        val out = ByteArray(16)
        for (i in 0 until 8) {
            out[2 * i] = (groups[i] shr 8).toByte()
            out[2 * i + 1] = groups[i].toByte()
        }
        return out
    }

    private fun parseGroups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val items = part.split(':')
        val out = ArrayList<Int>(items.size + 1)
        for ((i, item) in items.withIndex()) {
            if (item.isEmpty()) return null
            if (i == items.size - 1 && item.contains('.')) {
                val v4 = parseIpv4(item) ?: return null
                out.add(((v4 shr 16) and 0xFFFF).toInt())
                out.add((v4 and 0xFFFF).toInt())
                continue
            }
            if (item.length > 4) return null
            var v = 0
            for (c in item) {
                if (c.code > 0x7F) return null
                val d = Character.digit(c, 16)
                if (d < 0) return null
                v = v * 16 + d
            }
            out.add(v)
        }
        return out
    }

    // QIPAddressUtils::toString for IPv6: "::" for the first longest run of at least two zero groups,
    // lower-case hex without leading zeros, and a dotted quad for ::ffff:a.b.c.d / ::a.b.c.d forms.
    @JvmStatic
    fun formatIpv6(a: ByteArray): String {
        var embeddedIp4 = false
        if ((0 until 10).all { a[it].toInt() == 0 }) {
            if (a[10] == 0xFF.toByte() && a[11] == 0xFF.toByte()) {
                embeddedIp4 = true
            } else if (a[10].toInt() == 0 && a[11].toInt() == 0) {
                if (a[12].toInt() != 0 || a[13].toInt() != 0 || a[14].toInt() != 0) {
                    embeddedIp4 = true
                } else if (a[15].toInt() == 0) {
                    return "::"
                }
            }
        }
        var zeroRunLength = 0
        var zeroRunOffset = 0
        var i = 0
        while (i < 16) {
            if (a[i].toInt() == 0 && a[i + 1].toInt() == 0) {
                var j = i
                while (j < 16 && a[j].toInt() == 0 && a[j + 1].toInt() == 0) j += 2
                if (j - i > zeroRunLength) {
                    zeroRunLength = j - i
                    zeroRunOffset = i
                    i = j
                }
            }
            i += 2
        }
        val sb = StringBuilder()
        if (zeroRunLength < 4) zeroRunOffset = -1 else if (zeroRunOffset == 0) sb.append(':')
        i = 0
        while (i < 16) {
            if (i == zeroRunOffset) {
                sb.append(':')
                i += zeroRunLength
                continue
            }
            if (i == 12 && embeddedIp4) {
                val v4 = ((a[12].toLong() and 0xFF) shl 24) or ((a[13].toLong() and 0xFF) shl 16) or
                    ((a[14].toLong() and 0xFF) shl 8) or (a[15].toLong() and 0xFF)
                sb.append(formatIpv4(v4))
                break
            }
            val group = ((a[i].toInt() and 0xFF) shl 8) or (a[i + 1].toInt() and 0xFF)
            sb.append(Integer.toHexString(group))
            if (i != 14) sb.append(':')
            i += 2
        }
        return sb.toString()
    }
}
