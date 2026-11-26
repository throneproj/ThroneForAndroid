package io.nekohasekai.sagernet.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.Formatter
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.LiveSeries
import java.text.BreakIterator
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The desktop's LiveChart (src/ui/widget/LiveChart.cpp) over one [LiveSeries]: a line per row × column across a time
 * window, each bucket at the peak of its points when the window holds more points than the plot has room for,
 * monotone smoothing, gaps and stops as breaks, and a crosshair with a value card that follows a touch. Compact is the
 * desktop sparkline: no time axis or pills, the ceiling and zero inside a tinted panel. The legend and the window
 * choice are the screen's own views.
 */
class LiveChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** A measured thing; [fields] holds its [LiveSeries] field per column. */
    class Row(val key: String, val label: String, val color: Int, val wash: Boolean, val fields: IntArray)

    /** A measure: [dashed] lines; [wash] fills under the lines of washed rows. */
    class Column(val key: String, val arrow: String, val dashed: Boolean, val wash: Boolean)

    class Scale(val top: Double, val step: Double, val label: (Double) -> String)

    interface Axis {
        fun format(value: Double): String
        fun scale(peak: Double): Scale
    }

    /** [text] and [width] are for the tick at [wall] in [seconds] form. */
    private class AxisLabel {
        var wall = Long.MIN_VALUE
        var seconds = false
        var x = 0f
        var text = ""
        var width = 0f
    }

    private class CardLine(val kind: Int, val row: Int, val focus: Boolean, val values: Int)

    companion object {
        /** A newer point than this is the current one: the window ends at it rather than now. */
        private const val FRESH_MS = 2500L
        private const val MAX_GRID_LINES = 24
        private const val CARD_ROW = 0
        private const val CARD_TOTAL = 1

        /** The desktop palette: ThemeTokens chartSeries[0] and chartContext, RuntimeStatsWidget's core colour. */
        private const val SERIES_BLUE = 0xFF2A78D6.toInt()
        private const val CONTEXT_GREY = 0xFF7D8796.toInt()
        private const val CORE_GREEN = 0xFF34C98A.toInt()

        private fun niceStep(x: Double): Double {
            if (!(x > 0)) return 1.0
            val m = 10.0.pow(floor(log10(x)))
            val n = x / m
            return (if (n <= 1) 1.0 else if (n <= 2) 2.0 else if (n <= 2.5) 2.5 else if (n <= 5) 5.0 else 10.0) * m
        }

        private var trimmedLocale: Locale? = null
        private val trimmedFormats = arrayOfNulls<NumberFormat>(2)

        /** [decimals] is 0 or 1. Main thread only: the formats are shared. */
        private fun trimmed(value: Double, decimals: Int): String {
            val locale = Locale.getDefault(Locale.Category.FORMAT)
            if (locale != trimmedLocale) {
                trimmedLocale = locale
                trimmedFormats.fill(null)
            }
            val format = trimmedFormats[decimals] ?: NumberFormat.getInstance(locale).apply {
                maximumFractionDigits = decimals
                minimumFractionDigits = 0
                isGroupingUsed = false
            }.also { trimmedFormats[decimals] = it }
            return format.format(value)
        }

        /** Steps in the unit [Formatter] shows (1000-based since Android 8, 1024 before), as liveChartUnitAxis picks them. */
        private fun unitAxis(floorValue: Double, formatter: (Double) -> String, labeler: (Double) -> String): Axis {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 1000.0 else 1024.0
            val largest = base.pow(3)
            return object : Axis {
                override fun format(value: Double) = formatter(value)

                override fun scale(peak: Double): Scale {
                    var v = max(peak, floorValue)
                    var unit = 1.0
                    while (v >= base && unit < largest) {
                        v /= base
                        unit *= base
                    }
                    while (true) {
                        val step = niceStep(v / 4)
                        val top = ceil(v / step - 1e-9) * step
                        if (top >= 1000 && unit < largest) {
                            v /= base
                            unit *= base
                            continue
                        }
                        return Scale(top * unit, step * unit, labeler)
                    }
                }
            }
        }

        /** Bytes per second, as the stats bar shows them. */
        fun rateAxis(context: Context): Axis {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 1000.0 else 1024.0
            return unitAxis(
                base,
                { context.getString(R.string.speed, Formatter.formatFileSize(context, it.roundToLong())) },
                { context.getString(R.string.speed, Formatter.formatShortFileSize(context, it.roundToLong())) },
            )
        }

        fun bytesAxis(context: Context): Axis {
            val base = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 1000.0 else 1024.0
            return unitAxis(
                base * base,
                { Formatter.formatFileSize(context, it.roundToLong()) },
                { Formatter.formatShortFileSize(context, it.roundToLong()) },
            )
        }

        fun percentAxis(): Axis = object : Axis {
            override fun format(value: Double) = trimmed(value, if (value < 10) 1 else 0) + "%"

            override fun scale(peak: Double): Scale {
                val v = max(peak, 5.0)
                val step = niceStep(v / 4)
                return Scale(min(100.0, ceil(v / step - 1e-9) * step), step) { trimmed(it, 1) + "%" }
            }
        }

        fun countAxis(): Axis = object : Axis {
            override fun format(value: Double) = trimmed(value, 0)

            override fun scale(peak: Double): Scale {
                val v = max(peak, 4.0)
                val step = max(1.0, niceStep(v / 4).let { if (it == 2.5) 2.0 else it })
                return Scale(ceil(v / step - 1e-9) * step, step) { trimmed(it, 0) }
            }
        }

        private fun surfaceOf(context: Context): Int =
            ColorUtils.setAlphaComponent(MaterialColors.getColor(context, R.attr.colorSurface, Color.WHITE), 255)

        /** The theme's primary when it has a hue (the white and black themes do not), else the desktop blue. */
        fun proxyColor(context: Context): Int {
            val primary = MaterialColors.getColor(context, R.attr.colorPrimary, SERIES_BLUE)
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(primary, hsl)
            val chromatic = hsl[1] >= 0.3f && hsl[2] in 0.2f..0.8f
            return separate(if (chromatic) primary else SERIES_BLUE, surfaceOf(context), 3.0)
        }

        fun directColor(context: Context): Int = separate(CONTEXT_GREY, surfaceOf(context), 3.0)

        fun coreColor(context: Context): Int = separate(CORE_GREEN, surfaceOf(context), 3.0)

        /** ThemeManager's separate(): walks the lightness away from [surface] until the contrast reaches [target]. */
        private fun separate(color: Int, surface: Int, target: Double): Int {
            val darker = ColorUtils.calculateLuminance(surface) > 0.5
            val hsl = FloatArray(3)
            ColorUtils.colorToHSL(color, hsl)
            var c = ColorUtils.setAlphaComponent(color, 255)
            repeat(24) {
                if (ColorUtils.calculateContrast(c, surface) >= target) return c
                val next = (hsl[2] + if (darker) -10f / 255 else 10f / 255).coerceIn(0f, 1f)
                if (next == hsl[2]) return c
                hsl[2] = next
                c = ColorUtils.HSLToColor(hsl)
            }
            return c
        }

        /** ShortDisplayName: at most [max] graphemes, so a leading flag emoji is never cut in half. */
        fun shortName(name: String, max: Int = 15): String {
            if (name.length <= max) return name
            val breaks = BreakIterator.getCharacterInstance()
            breaks.setText(name)
            var cut = 0
            for (i in 0 until max - 1) {
                val next = breaks.next()
                if (next == BreakIterator.DONE) return name
                cut = next
            }
            val end = breaks.next()
            if (end == BreakIterator.DONE || end == name.length) return name
            return name.substring(0, cut).trim() + "…"
        }
    }

    // ------------------------------------------------------------------------------------------------ style

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private fun sp(value: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)
    private fun withAlpha(color: Int, alpha: Int) = ColorUtils.setAlphaComponent(color, alpha)

    private val surface = surfaceOf(context)
    private val ink = MaterialColors.getColor(
        context, R.attr.colorOnSurface, if (ColorUtils.calculateLuminance(surface) > 0.5) Color.BLACK else Color.WHITE
    )
    private val cInk2 = withAlpha(ink, 205)
    private val cMuted = withAlpha(ink, 150)
    private val cGrid = withAlpha(ink, 24)
    private val cAxis = withAlpha(ink, 70)
    private val cBorder = withAlpha(ink, 60)
    private val cHairline = withAlpha(ink, 92)
    private val cPanel = withAlpha(ink, 12)
    private val cFocus = withAlpha(ink, 18)
    private val cLabel = withAlpha(ink, 150)

    private fun textPaint(size: Float, face: Typeface = Typeface.DEFAULT) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(size)
        typeface = face
    }

    private val pBase = textPaint(12f)
    private val pBold = textPaint(12f, Typeface.DEFAULT_BOLD)
    private val pSmall = textPaint(11f)
    private val pSmallBold = textPaint(11f, Typeface.DEFAULT_BOLD)
    private val pLabel = textPaint(10f)
    private val pTitle = textPaint(14f, Typeface.DEFAULT_BOLD)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeJoin = Paint.Join.ROUND
        color = surface
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val flat = Paint().apply { style = Paint.Style.FILL }
    private val dashUp = DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    private val metrics = Paint.FontMetrics()
    private val hairline = dp(1f)

    private fun textHeight(paint: Paint): Float {
        paint.getFontMetrics(metrics)
        return metrics.descent - metrics.ascent
    }

    private val pillH = textHeight(pSmallBold) + dp(4f)
    private val axisH = max(dp(22f), pillH + dp(6f))
    private val cardRowH = max(dp(19f), textHeight(pBase) + dp(3f))
    private val compactRowH = max(dp(16f), textHeight(pSmall) + dp(2f))
    private val gridLabelGap = max(dp(9f), ceil(textHeight(pSmall) / 2) + dp(2f))
    private val messageLineH = max(dp(17f), textHeight(pBase) + dp(1f))
    private val messageTitleH = max(dp(20f), textHeight(pTitle) + dp(3f))

    private val totalText = context.getString(R.string.live_graph_total)
    private val emptyTitle = context.getString(R.string.live_graph_waiting)
    private val emptyBody = context.getString(R.string.live_graph_waiting_body)
    private val hiddenTitle = context.getString(R.string.live_graph_all_hidden)
    private val hiddenBody = context.getString(R.string.live_graph_all_hidden_body)

    private val is24 = DateFormat.is24HourFormat(context)
    private val clockSeconds = SimpleDateFormat(if (is24) "HH:mm:ss" else "h:mm:ss", Locale.getDefault())
    private val clockMinutes = SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault())
    private val date = Date()

    private fun clock(wallMs: Long, seconds: Boolean): String {
        date.time = wallMs
        return (if (seconds) clockSeconds else clockMinutes).format(date)
    }

    // ------------------------------------------------------------------------------------------------ configuration

    private var rows: List<Row> = emptyList()
    private var rowNames: Array<String> = emptyArray()
    private var columns: List<Column> = emptyList()
    private var axis: Axis = percentAxis()
    private var compact = false

    var data: LiveSeries? = null
    var windowMs = 120_000L
    var tickMs = 30_000L
    var pointIntervalMs = 1000L
    var smooth = true
    var hiddenRows: Set<String> = emptySet()
    var hiddenColumns: Set<String> = emptySet()

    // Series are rows × columns, row-major.
    private var ns = 0
    private var seriesRow = IntArray(0)
    private var seriesColumn = IntArray(0)
    private var seriesField = IntArray(0)
    private var seriesOn = BooleanArray(0)
    private var drawOrder = IntArray(0)

    fun setup(rows: List<Row>, columns: List<Column>, axis: Axis, compact: Boolean = false) {
        this.rows = rows
        rowNames = Array(rows.size) { shortName(rows[it].label) }
        this.columns = columns
        this.axis = axis
        this.compact = compact
        val nc = columns.size
        ns = rows.size * nc
        seriesRow = IntArray(ns) { it / nc }
        seriesColumn = IntArray(ns) { it % nc }
        seriesField = IntArray(ns) { rows[it / nc].fields.getOrElse(it % nc) { -1 } }
        seriesOn = BooleanArray(ns)
        // Later rows under earlier ones, dashed under solid.
        drawOrder = (0 until ns).sortedWith(
            compareByDescending<Int> { seriesRow[it] }.thenByDescending { columns[seriesColumn[it]].dashed }
        ).toIntArray()
        bucketSeries = -1
        resetView()
    }

    /** Drops the crosshair and the eased scale, e.g. after the history was cleared. */
    fun resetView() {
        anchorSet = false
        ap = -1
        apSeries = -1
        drawnTop = Double.NaN
        invalidate()
    }

    // ------------------------------------------------------------------------------------------------ state

    private val plot = RectF()
    private val panel = RectF()
    private val rect = RectF()
    private var viewEnd = 0L
    private var viewStart = 0L
    private var viewSpan = 1L
    private var kms = 1000L
    private var wallOffset = 0L
    private var scaleTop = 1.0
    private var drawnTop = Double.NaN
    private var lastFrame = 0L
    private var allHidden = false
    private var valid = false

    private var bucketCap = 0
    private var bucketSeries = -1
    private var nb = 0
    private var bucketKey = LongArray(0)
    private var bucketFrom = LongArray(0)
    private var bucketTo = LongArray(0)
    private var bucketTx = LongArray(0)
    private var bucketX = FloatArray(0)
    private var bucketN = IntArray(0)
    private var bucketBrk = BooleanArray(0)
    private var bucketMax = FloatArray(0)
    private var bucketTotal = FloatArray(0)
    private var sums = DoubleArray(0)
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var slopes = FloatArray(0)
    private var tangents = FloatArray(0)
    private val linePath = Path()
    private val fillPath = Path()
    private val axisLabels = ArrayList<AxisLabel>()
    private var axisCount = 0
    private var lastScale: Scale? = null
    private var scaleAxis: Axis? = null
    private var scalePeak = Double.NaN
    private val gridLabels = arrayOfNulls<String>(MAX_GRID_LINES)
    private var topLabel: String? = null
    private val cardLines = ArrayList<CardLine>()
    private val cardValues = ArrayList<String>()
    private val cardColumns = ArrayList<Int>()

    private var anchorSet = false
    private var anchorT = 0L
    private var anchorRow = -1
    private var anchorColumn = -1
    private var ap = -1
    private var apSeries = -1

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var scrubbing = false
    private var hadAnchor = false

    private fun ensureBuckets(capacity: Int) {
        if (capacity <= bucketCap && bucketSeries == ns) return
        bucketCap = max(capacity, bucketCap)
        bucketSeries = ns
        val nc = max(1, columns.size)
        bucketKey = LongArray(bucketCap)
        bucketFrom = LongArray(bucketCap)
        bucketTo = LongArray(bucketCap)
        bucketTx = LongArray(bucketCap)
        bucketX = FloatArray(bucketCap)
        bucketN = IntArray(bucketCap)
        bucketBrk = BooleanArray(bucketCap)
        bucketMax = FloatArray(bucketCap * max(1, ns))
        bucketTotal = FloatArray(bucketCap * nc)
        sums = DoubleArray(nc)
        xs = FloatArray(bucketCap)
        ys = FloatArray(bucketCap)
        slopes = FloatArray(bucketCap)
        tangents = FloatArray(bucketCap)
    }

    private fun xOf(t: Long): Float = plot.right - (viewEnd - t).toFloat() / viewSpan * plot.width()

    private fun yOf(v: Double): Float {
        var f = v / scaleTop
        if (compact) f = f.coerceIn(0.0, 1.0)
        return plot.bottom - (f * plot.height()).toFloat()
    }

    private fun valueAt(bucket: Int, series: Int): Float = bucketMax[bucket * ns + series]

    // ------------------------------------------------------------------------------------------------ data

    private fun prepare(series: LiveSeries?): Boolean {
        nb = 0
        if (series == null || series.size == 0 || ns == 0) return false
        val interval = max(1L, pointIntervalMs)
        // The desktop puts a bucket on every other pixel at 96 dpi.
        val slots = max(1f, plot.width() / density / 2)
        val perBucket = max(1L, ceil(viewSpan.toDouble() / interval / slots).toLong())
        kms = perBucket * interval
        ensureBuckets(series.capacity)
        buildBuckets(series, perBucket == 1L)
        // Only the lead-in point before the window, nothing inside it.
        if (nb > 0 && bucketX[nb - 1] < plot.left) nb = 0
        return nb > 0
    }

    private fun buildBuckets(series: LiveSeries, perPoint: Boolean) {
        val nc = columns.size
        val gapMs = (pointIntervalMs * 2.5).toLong()
        var prevT = 0L
        var i = max(0, series.lowerBound(viewStart - kms) - 1)
        while (i < series.size) {
            val t = series.time(i)
            if (t > viewEnd) break
            // Keys are absolute time, not buffer positions, so a bucket keeps its points as new ones arrive.
            val key = if (perPoint) t else t.floorDiv(kms)
            val gap = nb > 0 && t - prevT > gapMs
            if (nb == 0 || gap || bucketKey[nb - 1] != key) {
                if (nb == bucketCap) break
                bucketKey[nb] = key
                bucketFrom[nb] = t
                bucketN[nb] = 0
                bucketBrk[nb] = gap
                bucketMax.fill(Float.NaN, nb * ns, nb * ns + ns)
                bucketTotal.fill(Float.NaN, nb * nc, nb * nc + nc)
                nb++
            }
            val b = nb - 1
            bucketTo[b] = t
            bucketN[b]++
            sums.fill(Double.NaN)
            for (j in 0 until ns) {
                val field = seriesField[j]
                if (field < 0) continue
                val v = series.value(field, i)
                if (v.isNaN()) continue
                val k = b * ns + j
                if (!(bucketMax[k] >= v)) bucketMax[k] = v
                val c = seriesColumn[j]
                sums[c] = if (sums[c].isNaN()) v.toDouble() else sums[c] + v
            }
            for (c in 0 until nc) {
                val k = b * nc + c
                if (!sums[c].isNaN() && !(bucketTotal[k] >= sums[c])) bucketTotal[k] = sums[c].toFloat()
            }
            prevT = t
            i++
        }
        for (b in 0 until nb) {
            bucketTx[b] = bucketFrom[b] + (bucketTo[b] - bucketFrom[b]) / 2
            bucketX[b] = xOf(bucketTx[b])
        }
    }

    private fun peak(): Double {
        var peak = 0f
        for (j in 0 until ns) {
            if (!seriesOn[j]) continue
            for (b in 0 until nb) {
                val v = valueAt(b, j)
                if (v > peak) peak = v
            }
        }
        return peak.toDouble()
    }

    // ------------------------------------------------------------------------------------------------ drawing

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        wallOffset = System.currentTimeMillis() - now
        val series = data
        val latest = if (series != null && series.size > 0) series.latestTime else 0L
        viewEnd = if (latest > 0L && now - latest <= FRESH_MS) latest else now
        viewSpan = max(1L, windowMs)
        viewStart = viewEnd - viewSpan
        for (j in 0 until ns) {
            seriesOn[j] = rows[seriesRow[j]].key !in hiddenRows && columns[seriesColumn[j]].key !in hiddenColumns
        }
        allHidden = seriesOn.none { it }
        if (compact) drawCompact(canvas, series) else drawFull(canvas, series)
    }

    private fun drawFull(canvas: Canvas, series: LiveSeries?) {
        valid = false
        val w = width.toFloat()
        val h = height.toFloat()
        plot.set(dp(4f), dp(10f), w - dp(8f), h - axisH)
        if (plot.width() < dp(40f) || plot.height() < dp(24f)) return
        if (!prepare(series)) {
            drawMessage(canvas, emptyTitle, emptyBody)
            return
        }
        val scale = scaleFor(peak())
        val target = if (scale.top > 0) scale.top else 1.0
        val frame = SystemClock.uptimeMillis()
        val dt = if (lastFrame > 0L) min(100L, frame - lastFrame) else 16L
        lastFrame = frame
        var animating = false
        if (drawnTop.isNaN()) {
            drawnTop = target
        } else {
            val diff = target - drawnTop
            if (abs(diff) > target * 0.003) {
                drawnTop += diff * (1 - exp(-dt / 50.0))
                animating = true
            } else {
                drawnTop = target
            }
        }
        scaleTop = drawnTop

        drawGrid(canvas, scale)
        drawSeries(canvas)
        valid = true
        resolveAnchor()
        val crossX = if (ap >= 0 && !allHidden) bucketX[ap].roundToInt().toFloat() else Float.NaN
        drawTimeAxis(canvas, crossX)
        if (allHidden) drawMessage(canvas, hiddenTitle, hiddenBody) else drawOverlay(canvas)
        if (animating) postInvalidateOnAnimation()
    }

    private fun drawCompact(canvas: Canvas, series: LiveSeries?) {
        valid = false
        panel.set(dp(1.5f), dp(1.5f), width - dp(1.5f), height - dp(1.5f))
        if (panel.width() <= dp(2f) || panel.height() <= dp(2f)) return
        fill.color = cPanel
        canvas.drawRoundRect(panel, dp(4f), dp(4f), fill)
        val strip = min(textHeight(pLabel), panel.height() / 3)
        val padX = dp(5f)
        plot.set(panel.left + padX, panel.top + strip, panel.right - padX, panel.bottom - strip)
        if (plot.width() <= 1f || plot.height() <= 1f) return
        val hasData = prepare(series)
        val scale = scaleFor(peak())
        scaleTop = if (scale.top > 0) scale.top else 1.0
        drawnTop = scaleTop
        if (hasData && !allHidden) drawSeries(canvas)
        val top = topLabel ?: scale.label(scale.top).also { topLabel = it }
        drawText(canvas, pLabel, top, plot.left, panel.top + strip / 2, Paint.Align.LEFT, cLabel)
        drawText(canvas, pLabel, "0", plot.left, panel.bottom - strip / 2, Paint.Align.LEFT, cLabel)
        valid = hasData
        if (!valid) return
        resolveAnchor()
        if (!allHidden) drawOverlay(canvas)
    }

    private fun baseline(paint: Paint, yMid: Float): Float {
        paint.getFontMetrics(metrics)
        return yMid - (metrics.ascent + metrics.descent) / 2
    }

    private fun drawText(canvas: Canvas, paint: Paint, text: String, x: Float, yMid: Float, align: Paint.Align, color: Int) {
        paint.color = color
        paint.textAlign = align
        canvas.drawText(text, x, baseline(paint, yMid), paint)
    }

    private fun drawHaloText(canvas: Canvas, paint: Paint, text: String, x: Float, yMid: Float, align: Paint.Align, color: Int) {
        halo.textSize = paint.textSize
        halo.typeface = paint.typeface
        halo.textAlign = align
        canvas.drawText(text, x, baseline(paint, yMid), halo)
        drawText(canvas, paint, text, x, yMid, align, color)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.color = surface
        canvas.drawCircle(x, y, r + dp(2f), fill)
        fill.color = color
        canvas.drawCircle(x, y, r, fill)
    }

    private fun drawGrid(canvas: Canvas, scale: Scale) {
        for (i in 0 until MAX_GRID_LINES) {
            if (i > 0 && !(scale.step > 0)) break
            val g = i * scale.step
            val yy = yOf(g)
            if (yy < plot.top - 0.5f) break
            val y = yy.roundToInt().toFloat()
            flat.color = if (i == 0) cAxis else cGrid
            canvas.drawRect(plot.left, y, plot.right, y + hairline, flat)
            if (i == 0) continue
            val mid = y + hairline / 2
            val labelMid = if (mid - gridLabelGap < plot.top) mid + gridLabelGap else mid - gridLabelGap
            val label = gridLabels[i] ?: scale.label(g).also { gridLabels[i] = it }
            drawHaloText(canvas, pSmall, label, plot.left + dp(3f), labelMid, Paint.Align.LEFT, cMuted)
        }
    }

    /** The axis' scale for [peak], kept with its labels while the peak holds. */
    private fun scaleFor(peak: Double): Scale {
        lastScale?.let { if (scaleAxis === axis && scalePeak == peak) return it }
        val scale = axis.scale(peak)
        lastScale = scale
        scaleAxis = axis
        scalePeak = peak
        gridLabels.fill(null)
        topLabel = null
        return scale
    }

    private fun drawSeries(canvas: Canvas) {
        if (nb == 0) return
        canvas.save()
        if (compact) {
            canvas.clipRect(panel)
        } else {
            canvas.clipRect(plot.left, plot.top - dp(8f), plot.right + dp(8f), plot.bottom + dp(1f))
        }
        for (j in drawOrder) {
            if (!seriesOn[j]) continue
            val row = rows[seriesRow[j]]
            val column = columns[seriesColumn[j]]
            val dashed = !compact && column.dashed
            line.strokeWidth = dp(if (compact) 1.6f else if (dashed) 1.5f else 2f)
            line.color = row.color
            line.pathEffect = if (dashed) dashUp else null
            val wash = row.wash && column.wash
            val washColor = withAlpha(row.color, if (compact) 36 else 26)
            var start = -1
            for (i in 0..nb) {
                val ok = i < nb && !valueAt(i, j).isNaN()
                if (start >= 0 && (!ok || bucketBrk[i])) {
                    drawRun(canvas, j, start, i - 1, wash, washColor, row.color)
                    start = -1
                }
                if (ok && start < 0) start = i
            }
            val last = valueAt(nb - 1, j)
            if (last.isNaN()) continue
            val ex = bucketX[nb - 1]
            val ey = yOf(last.toDouble())
            if (compact) {
                fill.color = row.color
                canvas.drawCircle(ex, ey, dp(2.2f), fill)
            } else {
                drawDot(canvas, ex, ey, dp(3.5f), row.color)
            }
        }
        line.pathEffect = null
        canvas.restore()
    }

    private fun drawRun(canvas: Canvas, j: Int, a: Int, b: Int, wash: Boolean, washColor: Int, color: Int) {
        if (a == b) {
            fill.color = color
            canvas.drawCircle(bucketX[a], yOf(valueAt(a, j).toDouble()), dp(1.5f), fill)
            return
        }
        val n = b - a + 1
        for (k in 0 until n) {
            xs[k] = bucketX[a + k]
            ys[k] = yOf(valueAt(a + k, j).toDouble())
        }
        trace(linePath, n)
        if (wash) {
            fillPath.set(linePath)
            fillPath.lineTo(xs[n - 1], plot.bottom)
            fillPath.lineTo(xs[0], plot.bottom)
            fillPath.close()
            fill.color = washColor
            canvas.drawPath(fillPath, fill)
        }
        canvas.drawPath(linePath, line)
    }

    // Fritsch–Carlson monotone cubic: clamped tangents never overshoot a point, so the curve stays within each pair's range.
    private fun trace(path: Path, n: Int) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        if (n < 3 || !smooth) {
            for (i in 1 until n) path.lineTo(xs[i], ys[i])
            return
        }
        for (i in 0 until n - 1) {
            val dx = xs[i + 1] - xs[i]
            slopes[i] = (ys[i + 1] - ys[i]) / (if (dx != 0f) dx else 1f)
        }
        tangents[0] = slopes[0]
        tangents[n - 1] = slopes[n - 2]
        for (i in 1 until n - 1) {
            tangents[i] = if (slopes[i - 1] * slopes[i] <= 0f) 0f else (slopes[i - 1] + slopes[i]) / 2
        }
        for (i in 0 until n - 1) {
            if (slopes[i] == 0f) {
                tangents[i] = 0f
                tangents[i + 1] = 0f
                continue
            }
            val a = tangents[i] / slopes[i]
            val b = tangents[i + 1] / slopes[i]
            val s = a * a + b * b
            if (s > 9f) {
                val t = 3f / sqrt(s)
                tangents[i] = t * a * slopes[i]
                tangents[i + 1] = t * b * slopes[i]
            }
        }
        for (i in 0 until n - 1) {
            val h = (xs[i + 1] - xs[i]) / 3
            path.cubicTo(xs[i] + h, ys[i] + tangents[i] * h, xs[i + 1] - h, ys[i + 1] - tangents[i + 1] * h, xs[i + 1], ys[i + 1])
        }
    }

    /** Ticks on round local clock times; labels that would collide with each other or the time pill are left out. */
    private fun drawTimeAxis(canvas: Canvas, crossX: Float) {
        axisCount = 0
        val tick = max(1000L, tickMs)
        val wallStart = viewStart + wallOffset
        val wallEnd = viewEnd + wallOffset
        val tz = TimeZone.getDefault().getOffset(wallEnd).toLong()
        val seconds = tick < 60_000L
        var tw = -(-(wallStart + tz)).floorDiv(tick) * tick - tz
        while (tw <= wallEnd) {
            val x = xOf(tw - wallOffset)
            if (x >= plot.left - 0.5f) {
                if (axisCount == axisLabels.size) axisLabels.add(AxisLabel())
                val label = axisLabels[axisCount++]
                if (label.wall != tw || label.seconds != seconds) {
                    label.wall = tw
                    label.seconds = seconds
                    label.text = clock(tw, seconds)
                    label.width = pSmall.measureText(label.text)
                }
                label.x = x
            }
            tw += tick
        }
        val y = plot.bottom.roundToInt().toFloat()
        flat.color = cAxis
        for (k in 0 until axisCount) {
            val x = axisLabels[k].x.roundToInt().toFloat()
            canvas.drawRect(x, y, x + hairline, y + dp(4f), flat)
        }
        val labelY = plot.bottom + dp(4f) + pillH / 2
        var lastRight = Float.NEGATIVE_INFINITY
        for (k in 0 until axisCount) {
            val label = axisLabels[k]
            val half = label.width / 2
            if (label.x - half < dp(2f) || label.x + half > plot.right + dp(6f) || label.x - half < lastRight + dp(14f)) continue
            if (!crossX.isNaN() && abs(label.x - crossX) < half + dp(40f)) continue
            drawText(canvas, pSmall, label.text, label.x, labelY, Paint.Align.CENTER, cMuted)
            lastRight = label.x + half
        }
    }

    private fun drawMessage(canvas: Canvas, title: String, body: String) {
        val lines = wrap(body, pBase, max(dp(120f), plot.width() - dp(32f)))
        val cx = plot.centerX()
        var y = plot.centerY() - (messageTitleH + lines.size * messageLineH) / 2 + messageTitleH * 0.4f
        drawHaloText(canvas, pTitle, title, cx, y, Paint.Align.CENTER, ink)
        for (text in lines) {
            y += messageLineH
            drawHaloText(canvas, pBase, text, cx, y + dp(3f), Paint.Align.CENTER, cInk2)
        }
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            var end = start + max(1, paint.breakText(text, start, text.length, true, maxWidth, null))
            if (end < text.length) {
                val space = text.lastIndexOf(' ', end)
                if (space > start) end = space
            }
            out.add(text.substring(start, end).trim())
            start = end
            while (start < text.length && text[start] == ' ') start++
        }
        return out
    }

    // ------------------------------------------------------------------------------------------------ crosshair

    private fun drawOverlay(canvas: Canvas) {
        if (!valid || ap < 0 || apSeries < 0) return
        val focus = apSeries
        val focusValue = valueAt(ap, focus).toDouble()
        val bx = bucketX[ap]
        val cx = bx.roundToInt().toFloat()
        val cy = yOf(focusValue)
        val hy = cy.coerceIn(plot.top, plot.bottom).roundToInt().toFloat()
        flat.color = cHairline
        canvas.drawRect(cx, plot.top, cx + hairline, plot.bottom, flat)
        canvas.drawRect(plot.left, hy, plot.right, hy + hairline, flat)
        val other = dp(if (compact) 2.5f else 3f)
        for (j in 0 until ns) {
            if (!seriesOn[j] || j == focus) continue
            val v = valueAt(ap, j)
            if (!v.isNaN()) drawDot(canvas, bx, yOf(v.toDouble()), other, rows[seriesRow[j]].color)
        }
        drawDot(canvas, bx, cy, dp(if (compact) 3.5f else 5f), rows[seriesRow[focus]].color)
        if (!compact) {
            drawPill(canvas, clock(bucketTo[ap] + wallOffset, true), cx + 0.5f, plot.bottom + dp(3f), true)
            drawPill(canvas, axis.format(focusValue), plot.left, hy, false)
        }
        drawCard(canvas, cx + 0.5f, cy, focus)
    }

    private fun drawPill(canvas: Canvas, text: String, x: Float, y: Float, centered: Boolean) {
        val w = ceil(pSmallBold.measureText(text)) + dp(12f)
        val left = (if (centered) x - w / 2 else x).coerceIn(dp(2f), max(dp(2f), width - w - dp(2f)))
        val pillTop = if (centered) y else y - pillH / 2
        rect.set(left, pillTop, left + w, pillTop + pillH)
        fill.color = ink
        canvas.drawRoundRect(rect, dp(4f), dp(4f), fill)
        drawText(canvas, pSmallBold, text, rect.centerX(), rect.centerY(), Paint.Align.CENTER, surface)
    }

    private fun drawCard(canvas: Canvas, cx: Float, anchorY: Float, focus: Int) {
        val nc = columns.size
        val multi = nc > 1
        val font = if (compact) pSmall else pBase
        val valueFont = if (compact) pSmallBold else pBold
        val subFont = if (compact) pLabel else pSmall
        val rowH = if (compact) compactRowH else cardRowH
        val padX = dp(if (compact) 8f else 10f)
        val headH = rowH + dp(2f)

        cardColumns.clear()
        for (c in 0 until nc) if (columns[c].key !in hiddenColumns) cardColumns.add(c)
        val n = bucketN[ap]
        val to = clock(bucketTo[ap] + wallOffset, true)
        val head = if (n > 1) clock(bucketFrom[ap] + wallOffset, true) + " – " + to else to
        val sub = if (n > 1) context.getString(R.string.live_graph_peak, (n * pointIntervalMs / 1000.0).roundToInt()) else ""

        cardLines.clear()
        cardValues.clear()
        var rowCount = 0
        for (r in rows.indices) {
            if (rows[r].key in hiddenRows) continue
            if (cardColumns.none { !valueAt(ap, r * nc + it).isNaN() }) continue
            val offset = cardValues.size
            for (c in cardColumns) {
                val v = valueAt(ap, r * nc + c)
                cardValues.add(if (v.isNaN()) "—" else axis.format(v.toDouble()))
            }
            cardLines.add(CardLine(CARD_ROW, r, seriesRow[focus] == r, offset))
            rowCount++
        }
        if (!compact && rowCount > 1) {
            val offset = cardValues.size
            for (c in cardColumns) {
                val v = bucketTotal[ap * nc + c]
                cardValues.add(axis.format(if (v.isNaN()) 0.0 else v.toDouble()))
            }
            cardLines.add(CardLine(CARD_TOTAL, -1, false, offset))
        }

        var nameW = font.measureText(totalText)
        for (cardLine in cardLines) {
            if (cardLine.kind == CARD_ROW) nameW = max(nameW, font.measureText(rowNames[cardLine.row]))
        }
        val vc = cardColumns.size
        val rights = FloatArray(vc)
        var colsTotal = 0f
        for (k in 0 until vc) {
            var w = if (multi) valueFont.measureText(columns[cardColumns[k]].arrow) else 0f
            for (cardLine in cardLines) {
                if (cardLine.values >= 0) w = max(w, valueFont.measureText(cardValues[cardLine.values + k]))
            }
            rights[k] = w
            colsTotal += dp(14f) + w
        }
        val headW = valueFont.measureText(head)
        val subW = if (sub.isEmpty()) 0f else dp(8f) + subFont.measureText(sub)
        val w = ceil(max(dp(14f) + nameW + colsTotal, headW + subW + dp(16f) + if (multi) colsTotal else 0f)) + padX * 2
        val h = dp(8f) + headH + dp(6f) + cardLines.size * rowH + dp(6f)
        var x = cx + dp(16f)
        if (x + w > width - dp(4f)) x = cx - dp(16f) - w
        x = max(dp(if (compact) 2f else 4f), x)
        val lo = if (compact) dp(2f) else max(dp(4f), plot.top)
        val hi = max(lo, if (compact) height - h - dp(2f) else min(height - h - dp(4f), plot.bottom - h))
        val y = (anchorY - h / 2).coerceIn(lo, hi)
        var acc = x + padX + dp(14f) + nameW
        for (k in 0 until vc) {
            acc += dp(14f) + rights[k]
            rights[k] = acc
        }

        rect.set(x, y, x + w, y + h)
        fill.color = surface
        canvas.drawRoundRect(rect, dp(7f), dp(7f), fill)
        border.color = cBorder
        canvas.drawRoundRect(rect, dp(7f), dp(7f), border)
        var ly = y + dp(8f) + headH / 2
        drawText(canvas, valueFont, head, x + padX, ly, Paint.Align.LEFT, ink)
        if (sub.isNotEmpty()) drawText(canvas, subFont, sub, x + padX + headW + dp(8f), ly, Paint.Align.LEFT, cMuted)
        if (multi) {
            for (k in 0 until vc) drawText(canvas, valueFont, columns[cardColumns[k]].arrow, rights[k], ly, Paint.Align.RIGHT, cMuted)
        }
        val separator = (y + dp(8f) + headH + dp(2f)).roundToInt().toFloat()
        flat.color = cGrid
        canvas.drawRect(x + 1, separator, x + w - 1, separator + hairline, flat)
        ly = separator + dp(4f) + rowH / 2
        for (cardLine in cardLines) {
            val lx = x + padX
            when (cardLine.kind) {
                CARD_ROW -> {
                    val row = rows[cardLine.row]
                    if (cardLine.focus) {
                        flat.color = cFocus
                        canvas.drawRect(x + dp(3f), ly - rowH / 2, x + w - dp(3f), ly + rowH / 2, flat)
                    }
                    fill.color = row.color
                    canvas.drawCircle(lx + dp(4f), ly, dp(3.5f), fill)
                    drawText(canvas, font, rowNames[cardLine.row], lx + dp(14f), ly, Paint.Align.LEFT, ink)
                }

                else -> drawText(canvas, font, totalText, lx + dp(14f), ly, Paint.Align.LEFT, cInk2)
            }
            if (cardLine.values >= 0) {
                for (k in 0 until vc) {
                    drawText(canvas, valueFont, cardValues[cardLine.values + k], rights[k], ly, Paint.Align.RIGHT, ink)
                }
            }
            ly += rowH
        }
    }

    private fun nearestByX(x: Float): Int {
        var lo = 0
        var hi = nb - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bucketX[mid] < x) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(bucketX[lo - 1] - x) <= abs(bucketX[lo] - x)) lo--
        return lo
    }

    private fun nearestByTime(t: Long): Int {
        var lo = 0
        var hi = nb - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bucketTx[mid] < t) lo = mid + 1 else hi = mid
        }
        if (lo > 0 && abs(bucketTx[lo - 1] - t) <= abs(bucketTx[lo] - t)) lo--
        return lo
    }

    private fun pickSeries(bucket: Int, y: Float): Int {
        var best = -1
        var bestDistance = Float.POSITIVE_INFINITY
        for (j in 0 until ns) {
            if (!seriesOn[j]) continue
            val v = valueAt(bucket, j)
            if (v.isNaN()) continue
            val distance = abs(yOf(v.toDouble()) - y)
            if (distance < bestDistance) {
                bestDistance = distance
                best = j
            }
        }
        return best
    }

    /** The crosshair stays on its moment as the window moves, and on its line while that has a value there. */
    private fun resolveAnchor() {
        ap = -1
        apSeries = -1
        if (!anchorSet || nb == 0) return
        val index = nearestByTime(anchorT)
        if (abs(bucketTx[index] - anchorT) > kms || bucketX[index] < plot.left) {
            anchorSet = false
            return
        }
        val nc = columns.size
        var j = if (anchorRow in rows.indices && anchorColumn in 0 until nc) anchorRow * nc + anchorColumn else -1
        if (j < 0 || !seriesOn[j] || valueAt(index, j).isNaN()) {
            j = pickSeries(index, plot.centerY())
            if (j < 0) return
            anchorRow = seriesRow[j]
            anchorColumn = seriesColumn[j]
        }
        apSeries = j
        ap = index
    }

    private fun pointAt(x: Float, y: Float) {
        if (!valid || nb == 0 || allHidden) return
        val bucket = nearestByX(x.coerceIn(plot.left, plot.right))
        if (bucketX[bucket] < plot.left) return
        val j = pickSeries(bucket, y)
        if (j < 0) return
        anchorSet = true
        anchorT = bucketTx[bucket]
        anchorRow = seriesRow[j]
        anchorColumn = seriesColumn[j]
        invalidate()
    }

    /** A tap shows the crosshair at that moment and the next tap hides it; a horizontal drag scrubs. */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!valid || nb == 0 || allHidden) return false
                downX = event.x
                downY = event.y
                scrubbing = false
                hadAnchor = anchorSet
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = abs(event.x - downX)
                if (!scrubbing && dx > touchSlop && dx > abs(event.y - downY)) {
                    scrubbing = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                if (scrubbing) pointAt(event.x, event.y)
            }

            MotionEvent.ACTION_UP -> {
                if (!scrubbing) {
                    if (hadAnchor) {
                        anchorSet = false
                        invalidate()
                    } else {
                        pointAt(event.x, event.y)
                    }
                    performClick()
                }
                scrubbing = false
            }

            MotionEvent.ACTION_CANCEL -> scrubbing = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
