package io.nekohasekai.sagernet.outbound

import java.util.Locale

/**
 * QString helpers with the exact semantics the desktop profile code relies on
 * (`include/global/Utils.hpp`, QString::toInt, QString::split, QString::section).
 */
object QtStrings {
    private val INTEGER = Regex("[+-]?[0-9]+")

    /** QString::toInt(): surrounding whitespace tolerated, optional sign, ASCII digits only; 0 on any failure or overflow. */
    @JvmStatic
    fun toInt(s: String): Int {
        val t = s.trim()
        if (!INTEGER.matches(t)) return 0
        return t.toIntOrNull() ?: 0
    }

    /** QString::toLongLong() with the same rules as [toInt]. */
    @JvmStatic
    fun toLong(s: String): Long {
        val t = s.trim()
        if (!INTEGER.matches(t)) return 0L
        return t.toLongOrNull() ?: 0L
    }

    /** QString::toInt(&ok): the value, or null when Qt would report ok == false. */
    @JvmStatic
    fun toIntOrNull(s: String): Int? {
        val t = s.trim()
        if (!INTEGER.matches(t)) return null
        return t.toIntOrNull()
    }

    /** Utils.hpp:110-113 — the whole string when [sub] is absent. */
    @JvmStatic
    fun substrBefore(str: String, sub: String): String {
        val pos = str.indexOf(sub)
        return if (pos == -1) str else str.substring(0, pos)
    }

    /** Utils.hpp:115-118 — the whole string when [sub] is absent. */
    @JvmStatic
    fun substrAfter(str: String, sub: String): String {
        val pos = str.indexOf(sub)
        return if (pos == -1) str else str.substring(pos + sub.length)
    }

    /** QString::split with KeepEmptyParts: "" gives one empty element, "a," gives two. */
    @JvmStatic
    fun split(s: String, separator: String): MutableList<String> = s.split(separator).toMutableList()

    /** QString::split with Qt::SkipEmptyParts. */
    @JvmStatic
    fun splitSkipEmpty(s: String, separator: String): MutableList<String> =
        s.split(separator).filterTo(ArrayList()) { it.isNotEmpty() }

    /** xrayStreamSetting.cpp:17-23 splitList: comma separated, trimmed, blanks dropped. */
    @JvmStatic
    fun splitList(s: String): MutableList<String> =
        s.split(',').map { it.trim() }.filterTo(ArrayList()) { it.isNotEmpty() }

    /** QString::section(sep, 0, 0, SectionSkipEmpty): the first non-empty field. */
    @JvmStatic
    fun sectionFirstSkipEmpty(s: String, separator: Char): String =
        s.split(separator).firstOrNull { it.isNotEmpty() } ?: ""

    /** QString::number(d, 'g', precision): %g with the trailing zeros dropped; QString::number(double) is precision 6. */
    @JvmStatic
    @JvmOverloads
    fun numberG(d: Double, precision: Int = 6): String {
        val s = String.format(Locale.ROOT, "%.${precision}g", d)
        val e = s.indexOfFirst { it == 'e' || it == 'E' }
        var mantissa = if (e >= 0) s.substring(0, e) else s
        if (mantissa.contains('.')) mantissa = mantissa.trimEnd('0').trimEnd('.')
        return if (e >= 0) mantissa + s.substring(e) else mantissa
    }
}
