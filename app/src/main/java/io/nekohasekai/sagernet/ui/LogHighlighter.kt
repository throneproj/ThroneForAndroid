package io.nekohasekai.sagernet.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.Spannable
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import com.google.android.material.color.MaterialColors
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** The desktop's log levels (LogHighlighter.hpp): Trace reads as Debug, Fatal and Panic as Error. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Colours core.log lines like the desktop's log browser (LogHighlighter.cpp): time, counter and connection bracket
 * muted, the level word in its level colour, each connection id in the colour of that id on every line, the cause of a
 * warning or error in the level colour, DNS failures and rejections in the body. Search hits are the caller's, painted
 * after these.
 *
 * Android's lines are CoreLog's "yyyy/MM/dd HH:mm:ss " and the "[Level] " of the core's sink or of Logs, followed by a
 * sing-box line ("[test] " first from a probe box), "xray: " and an Xray message, or the app's "[Tag] " and text. That
 * bracket is the level word; the sing-box or Xray level after it repeats it, so it is muted. Lines without it (stack
 * trace continuations) go through the desktop's parsers.
 *
 * Reuses its matchers from line to line: one instance per view, on the main thread.
 */
class LogHighlighter(private val palette: LogPalette) {

    private companion object {
        // sing-box: optional "-0700 date time", LEVEL, optional "[secs]" counter, "[id duration]" and "tag: ".
        val SING_BOX: Pattern = Pattern.compile(
            """^(?:([+-]\d{4} \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?) )?(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|PANIC)(?=[\[ ]|$)(\[\d+\])? ?(?:(\[(\d+) \d[\d.m]*s\]) )?(?:[a-z][\w-]*(?:/[\w-]+)?(?:\[[^\]]*\])?: )?"""
        )

        // Xray and Go's standard logger: "yyyy/mm/dd hh:mm:ss[.us]", optional "[Level]", "[id]" and "component/path: ".
        val XRAY: Pattern = Pattern.compile(
            """^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?) (?:\[(Debug|Info|Warning|Error)\] )?(?:(\[(\d+)\]) )?(?:(?:[\w-]+/)+[\w-]+: )?"""
        )

        val THRONE: Pattern = Pattern.compile("""^\[([A-Za-z][A-Za-z ]{0,20})\] """)

        // CoreLog's time, whole seconds unlike Xray's own, and the level the sink or Logs wrote the line at.
        val ANDROID: Pattern = Pattern.compile(
            """^(\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}) (\[(Trace|Debug|Info|Warning|Error|Fatal|Panic)\]) """
        )

        // The sink's Xray message: optional "[Level]", "[id]" and "component/path: ".
        val XRAY_MESSAGE: Pattern = Pattern.compile(
            """^(?:(\[(?:Debug|Info|Warning|Error)\]) )?(?:(\[(\d+)\]) )?(?:(?:[\w-]+/)+[\w-]+: )?"""
        )

        // The class Logs tags the app's own lines with.
        val APP_TAG: Pattern = Pattern.compile("""^\[[^\]\s]+\] """)

        val BODY_TOKENS: Pattern = Pattern.compile(
            """(\b(?:NXDOMAIN|SERVFAIL|REFUSED|FORMERR|NOTIMP)\b)|(=> (?:reject|block)\b|\brejected\b)"""
        )

        const val PROBE = "[test] "
        const val XRAY_SOURCE = "xray: "
    }

    private val singBox = SING_BOX.matcher("")
    private val xray = XRAY.matcher("")
    private val throne = THRONE.matcher("")
    private val envelope = ANDROID.matcher("")
    private val xrayMessage = XRAY_MESSAGE.matcher("")
    private val appTag = APP_TAG.matcher("")
    private val tokens = BODY_TOKENS.matcher("")

    // The last parsed prefix; -1 starts for the parts the line lacks. The meta run is one muted span over the counter
    // and connection bracket, from the repeated level word on in Android's lines.
    private var level = LogLevel.INFO
    private var timeStart = -1
    private var timeEnd = -1
    private var wordStart = -1
    private var wordEnd = -1
    private var metaStart = -1
    private var metaEnd = -1
    private var idStart = -1
    private var idEnd = -1
    private var bodyStart = 0

    /** ClassifyLogLine: the level of [line] from its prefix alone, Info when it has none. */
    fun classify(line: String): LogLevel {
        parse(line)
        return level
    }

    /** Colours [line], which [out] holds from [offset]. */
    fun paint(out: Spannable, offset: Int, line: String) {
        parse(line)
        if (timeStart >= 0) color(out, offset + timeStart, offset + timeEnd, palette.muted)
        val alarming = level == LogLevel.WARN || level == LogLevel.ERROR
        if (wordStart >= 0) {
            color(out, offset + wordStart, offset + wordEnd, palette.levelColor(level))
            if (alarming) {
                out.setSpan(StyleSpan(Typeface.BOLD), offset + wordStart, offset + wordEnd, SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (metaStart >= 0) color(out, offset + metaStart, offset + metaEnd, palette.muted)
        if (idStart >= 0) color(out, offset + idStart, offset + idEnd, palette.connections[connectionIndex(line)])

        if (alarming) {
            // The cause trails the last ": "; a message without one is the cause itself.
            val colon = line.lastIndexOf(": ")
            val from = if (colon >= bodyStart) colon + 2 else bodyStart
            if (from < line.length) color(out, offset + from, offset + line.length, palette.levelColor(level))
        }

        if (bodyStart < line.length && tokens.reset(line).find(bodyStart)) {
            do {
                val rcode = tokens.start(1)
                val tokenColor = if (rcode >= 0 && line.startsWith("NXDOMAIN", rcode)) palette.warn else palette.error
                color(out, offset + tokens.start(), offset + tokens.end(), tokenColor)
            } while (tokens.find())
        }
    }

    private fun color(out: Spannable, start: Int, end: Int, color: Int) {
        if (end > start) out.setSpan(ForegroundColorSpan(color), start, end, SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    // The id modulo the palette size, digit by digit: the same as the desktop's id % size without parsing a number.
    private fun connectionIndex(line: String): Int {
        val size = palette.connections.size
        var index = 0
        for (i in idStart until idEnd) index = (index * 10 + Character.digit(line[i], 10)) % size
        return index
    }

    private fun parse(line: String) {
        level = LogLevel.INFO
        timeStart = -1
        timeEnd = -1
        wordStart = -1
        wordEnd = -1
        metaStart = -1
        metaEnd = -1
        idStart = -1
        idEnd = -1
        bodyStart = 0
        if (line.isEmpty()) return
        val first = line[0]
        when {
            first == '[' -> if (throne.reset(line).lookingAt()) {
                level = levelOf(line, throne.start(1), throne.end(1))
                wordStart = 0
                wordEnd = throne.end() - 1
                bodyStart = throne.end()
            }

            first.isDigit() -> if (envelope.reset(line).lookingAt()) parseAndroid(line) else parseXray(line)

            isSingBoxStart(first) -> if (singBox.reset(line).lookingAt()) {
                time(singBox, 1)
                level = levelOf(line, singBox.start(2), singBox.end(2))
                wordStart = singBox.start(2)
                wordEnd = singBox.end(2)
                meta(singBox, 3)
                meta(singBox, 4)
                id(singBox, 5)
                bodyStart = singBox.end()
            }
        }
    }

    private fun parseXray(line: String) {
        if (!xray.reset(line).lookingAt()) return
        time(xray, 1)
        if (xray.start(2) >= 0) {
            level = levelOf(line, xray.start(2), xray.end(2))
            wordStart = xray.start(2)
            wordEnd = xray.end(2)
        }
        meta(xray, 3)
        id(xray, 4)
        bodyStart = xray.end()
    }

    private fun parseAndroid(line: String) {
        time(envelope, 1)
        level = levelOf(line, envelope.start(3), envelope.end(3))
        wordStart = envelope.start(2)
        wordEnd = envelope.end(2)
        var at = envelope.end()
        if (line.startsWith(PROBE, at)) at += PROBE.length
        bodyStart = at
        if (line.startsWith(XRAY_SOURCE, at)) {
            bodyStart = at + XRAY_SOURCE.length
            val message = xrayMessage.reset(line).region(bodyStart, line.length)
            if (!message.lookingAt()) return
            meta(message, 1)
            meta(message, 2)
            id(message, 3)
            bodyStart = message.end()
        } else if (at < line.length && isSingBoxStart(line[at]) &&
            singBox.reset(line).region(at, line.length).lookingAt()
        ) {
            for (group in 1..4) meta(singBox, group)
            id(singBox, 5)
            bodyStart = singBox.end()
        } else if (at < line.length && line[at] == '[' && appTag.reset(line).region(at, line.length).lookingAt()) {
            bodyStart = appTag.end()
        }
    }

    private fun isSingBoxStart(c: Char) = c.isUpperCase() || c == '+' || c == '-'

    private fun time(m: Matcher, group: Int) {
        if (m.start(group) < 0) return
        timeStart = m.start(group)
        timeEnd = m.end(group)
    }

    private fun meta(m: Matcher, group: Int) {
        val start = m.start(group)
        if (start < 0) return
        if (metaStart < 0 || start < metaStart) metaStart = start
        if (m.end(group) > metaEnd) metaEnd = m.end(group)
    }

    private fun id(m: Matcher, group: Int) {
        if (m.start(group) < 0) return
        idStart = m.start(group)
        idEnd = m.end(group)
    }

    private fun levelOf(text: String, start: Int, end: Int): LogLevel = when {
        isWord(text, start, end, "DEBUG") || isWord(text, start, end, "TRACE") -> LogLevel.DEBUG
        end - start >= 4 && text.regionMatches(start, "WARN", 0, 4, ignoreCase = true) -> LogLevel.WARN
        end - start >= 5 && text.regionMatches(end - 5, "ERROR", 0, 5, ignoreCase = true) ||
            isWord(text, start, end, "FATAL") || isWord(text, start, end, "PANIC") -> LogLevel.ERROR

        else -> LogLevel.INFO
    }

    private fun isWord(text: String, start: Int, end: Int, word: String) =
        end - start == word.length && text.regionMatches(start, word, 0, word.length, ignoreCase = true)
}

/**
 * The desktop's log colours (mainwindow_log.cpp logLevelColors and connectionColors) from its theme token maths
 * (ThemeManager.cpp resolveTokens), measured against the surface the log is drawn on and the log's text colour.
 */
class LogPalette(surface: Int, text: Int) {

    val muted: Int
    val info: Int
    val warn: Int
    val error: Int

    /** Picked by connection id modulo the size, so every line of a connection shares one. */
    val connections: IntArray

    init {
        val s = surface or OPAQUE
        val onSurface = blend(text or OPAQUE, s, (text ushr 24) / 255.0)
        muted = separate(blend(onSurface, s, 0.62), s, 4.0)
        info = calm(separate(INFO, s, 4.0), onSurface, 0.38)
        // 4.5 would darken the warning to brown on a light surface; 3.0 keeps it reading as yellow.
        warn = calm(separate(WARNING, s, 3.0), onSurface, 0.55)
        error = calm(separate(DANGER, s, 4.5), onSurface, 0.55)
        // Evenly spaced hues at one lightness, each walked to the same contrast so no connection reads louder.
        val tone = if (lightness(s) < 0.5) 0.66 else 0.42
        connections = IntArray(CONNECTION_COLORS) {
            separate(fromHsl(it * 360.0 / CONNECTION_COLORS, 0.5, tone), s, 4.0)
        }
    }

    fun levelColor(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> muted
        LogLevel.INFO -> info
        LogLevel.WARN -> warn
        LogLevel.ERROR -> error
    }

    companion object {
        private const val OPAQUE = 0xFF000000.toInt()
        private const val DANGER = 0xFFC62828.toInt()
        private const val WARNING = 0xFFE09B1A.toInt()
        private const val INFO = 0xFF3299FF.toInt()
        private const val CONNECTION_COLORS = 16

        /** For [view]: the first opaque colour behind it, normally the window background, and its text colour. */
        fun of(view: TextView) = LogPalette(surfaceBehind(view), view.currentTextColor)

        private fun surfaceBehind(view: View): Int {
            var at: View? = view
            while (at != null) {
                val background = at.background
                if (background is ColorDrawable && background.color ushr 24 == 0xFF) return background.color
                at = at.parent as? View
            }
            return MaterialColors.getColor(view.context, android.R.attr.colorBackground, Color.WHITE)
        }

        private fun red(c: Int) = (c shr 16) and 0xFF
        private fun green(c: Int) = (c shr 8) and 0xFF
        private fun blue(c: Int) = c and 0xFF

        private fun channel(value: Double) = value.roundToInt().coerceIn(0, 255)

        /** From channels in 0..255. */
        private fun rgb(r: Double, g: Double, b: Double) =
            OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)

        /** blendToward: [keep] of [from], the rest of [to]. */
        private fun blend(from: Int, to: Int, keep: Double) = rgb(
            red(from) * keep + red(to) * (1 - keep),
            green(from) * keep + green(to) * (1 - keep),
            blue(from) * keep + blue(to) * (1 - keep),
        )

        private fun linear(channel: Int): Double {
            val v = channel / 255.0
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }

        private fun luminance(c: Int) = 0.2126 * linear(red(c)) + 0.7152 * linear(green(c)) + 0.0722 * linear(blue(c))

        private fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        private fun lightness(c: Int) = (maxOf(red(c), green(c), blue(c)) + minOf(red(c), green(c), blue(c))) / 510.0

        /** QColor's HSL: hue in degrees (-1 for a grey), saturation and lightness in 0..1. */
        private fun toHsl(c: Int): DoubleArray {
            val r = red(c) / 255.0
            val g = green(c) / 255.0
            val b = blue(c) / 255.0
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val delta = max - min
            val sum = max + min
            val l = sum / 2
            if (delta == 0.0) return doubleArrayOf(-1.0, 0.0, l)
            val s = if (l < 0.5) delta / sum else delta / (2 - sum)
            var h = 60 * when (max) {
                r -> (g - b) / delta
                g -> 2 + (b - r) / delta
                else -> 4 + (r - g) / delta
            }
            if (h < 0) h += 360
            return doubleArrayOf(h, s, l)
        }

        private fun fromHsl(hue: Double, s: Double, l: Double): Int {
            if (hue < 0 || s == 0.0) return rgb(l * 255, l * 255, l * 255)
            val t2 = if (l < 0.5) l * (1 + s) else l + s - l * s
            val t1 = 2 * l - t2
            val h = hue / 360
            return rgb(
                hslChannel(t1, t2, h + 1.0 / 3) * 255,
                hslChannel(t1, t2, h) * 255,
                hslChannel(t1, t2, h - 1.0 / 3) * 255,
            )
        }

        private fun hslChannel(t1: Double, t2: Double, at: Double): Double {
            val t = if (at < 0) at + 1 else if (at > 1) at - 1 else at
            return when {
                t * 6 < 1 -> t1 + (t2 - t1) * 6 * t
                t * 2 < 1 -> t2
                t * 3 < 2 -> t1 + (t2 - t1) * (2.0 / 3 - t) * 6
                else -> t1
            }
        }

        /**
         * separate(): steps the lightness of [color] by 10/255 away from [surface], at most 24 times, until their
         * contrast ratio reaches [target]. Hue and saturation stay as QColor::getHsl reports them, in whole degrees and
         * 255ths.
         */
        private fun separate(color: Int, surface: Int, target: Double): Int {
            if (contrast(color, surface) >= target) return color
            val hsl = toHsl(color)
            val hue = if (hsl[0] < 0) -1.0 else ((hsl[0] * 100).roundToInt() / 100 % 360).toDouble()
            val saturation = (hsl[1] * 255).roundToInt() / 255.0
            var l = (hsl[2] * 255).roundToInt()
            val step = if (luminance(surface) > 0.5) -10 else 10
            var c = color
            repeat(24) {
                val next = (l + step).coerceIn(0, 255)
                if (next == l) return c
                l = next
                c = fromHsl(hue, saturation, l / 255.0)
                if (contrast(c, surface) >= target) return c
            }
            return c
        }

        /** calmLogColor: saturation capped at [maxSaturation], then drawn 20% toward [text]. */
        private fun calm(color: Int, text: Int, maxSaturation: Double): Int {
            val hsl = toHsl(color)
            return blend(fromHsl(hsl[0], min(hsl[1], maxSaturation), hsl[2]), text, 0.8)
        }
    }
}
