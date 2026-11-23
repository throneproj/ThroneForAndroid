package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.style.ReplacementSpan
import android.util.LruCache
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import io.nekohasekai.sagernet.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Service Check logos with a badge for the check's outcome (ServiceIcons.cpp), drawn on a 24-unit square. A check id
 * without a logo gets a letter tile from its name. Rendered icons are cached like the desktop's QPixmapCache.
 */
internal object ServiceIcons {

    enum class State {
        /** The logo alone, as in the selector. */
        PLAIN,
        ACCESS,
        PARTIAL,
        NO_ACCESS,

        /** Inconclusive or failed: a greyed logo without a badge. */
        UNKNOWN,
    }

    /** A stored check status (available, partial, restricted, blocked, unknown, failed) as an icon state. */
    fun stateOf(status: String): State = when (status) {
        ServiceResults.STATUS_AVAILABLE -> State.ACCESS
        ServiceResults.STATUS_PARTIAL -> State.PARTIAL
        ServiceResults.STATUS_RESTRICTED, ServiceResults.STATUS_BLOCKED -> State.NO_ACCESS
        else -> State.UNKNOWN
    }

    @DrawableRes
    private fun glyph(id: String): Int = when (id) {
        "chatgpt" -> R.drawable.ic_service_chatgpt
        "claude" -> R.drawable.ic_service_claude
        "gemini" -> R.drawable.ic_service_gemini
        "copilot" -> R.drawable.ic_service_copilot
        "netflix" -> R.drawable.ic_service_netflix
        "youtube_premium" -> R.drawable.ic_service_youtube_premium
        "spotify" -> R.drawable.ic_service_spotify
        "prime_video" -> R.drawable.ic_service_prime_video
        "tiktok" -> R.drawable.ic_service_tiktok
        "google_search" -> R.drawable.ic_service_google_search
        "wikipedia" -> R.drawable.ic_service_wikipedia
        "steam" -> R.drawable.ic_service_steam
        else -> 0
    }

    private val TILE_COLORS = intArrayOf(
        0xff5b7fd6.toInt(), 0xff2e9c8f.toInt(), 0xff4c9a4f.toInt(), 0xffc9892b.toInt(),
        0xffd9674e.toInt(), 0xffc9578a.toInt(), 0xff8a63c9.toInt(), 0xff3794b8.toInt(),
    )

    private val cache = LruCache<String, Bitmap>(256)

    /** [sizePx] square, bounds set. */
    fun drawable(context: Context, id: String, name: String, state: State, sizePx: Int): Drawable =
        BitmapDrawable(context.resources, bitmap(context, id, name, state, sizePx)).apply { setBounds(0, 0, sizePx, sizePx) }

    /** Main thread only, like the desktop's Pixmap. */
    fun bitmap(context: Context, id: String, name: String, state: State, sizePx: Int): Bitmap {
        val size = max(1, sizePx)
        @ColorInt val foreground = TestFormat.themeColor(context, android.R.attr.textColorPrimary, Color.BLACK)
        @ColorInt val muted = TestFormat.themeColor(context, android.R.attr.textColorSecondary, Color.GRAY)
        val glyph = glyph(id)
        val key = "$state/$size/$foreground/$muted/${id.length}/$id" + if (glyph == 0) name else ""
        cache[key]?.let { return it }

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val unit = size / 24f
        val greyed = state == State.NO_ACCESS || state == State.UNKNOWN
        val area = (if (state == State.PLAIN) 24f else 20.2f) * unit
        val logo = if (glyph != 0) AppCompatResources.getDrawable(context, glyph)?.mutate() else null
        if (logo != null) {
            // The drawables are square with the logo centred, as serviceIconFit places it.
            if (greyed) logo.setTint(muted)
            logo.setBounds(0, 0, area.roundToInt(), area.roundToInt())
            logo.draw(canvas)
        } else {
            val label = name.trim().ifEmpty { id }
            tile(canvas, area, label, if (greyed) muted else tileColor(label))
        }
        when (state) {
            State.ACCESS -> badge(canvas, unit, state, TestFormat.latencyColor(1))
            State.PARTIAL -> badge(canvas, unit, state, TestFormat.latencyColor(101))
            State.NO_ACCESS -> badge(canvas, unit, state, TestFormat.latencyColor(301))
            else -> Unit
        }
        cache.put(key, bitmap)
        return bitmap
    }

    /** First letters of the first two words, or the first two of a single word. */
    private fun initials(label: String): String {
        val words = label.trim().split(Regex("\\s+")).mapNotNull { word ->
            val letters = ArrayList<String>()
            var i = 0
            while (i < word.length) {
                val codePoint = word.codePointAt(i)
                val length = Character.charCount(codePoint)
                if (Character.isLetterOrDigit(codePoint)) letters.add(word.substring(i, i + length))
                i += length
            }
            letters.takeIf { it.isNotEmpty() }
        }
        if (words.isEmpty()) return ""
        val initials = if (words.size > 1) words[0][0] + words[1][0] else words[0].take(2).joinToString("")
        return initials.uppercase(Locale.ROOT)
    }

    /** FNV-1a over the UTF-16 units, as serviceIconTileColor. */
    @ColorInt
    private fun tileColor(label: String): Int {
        var hash = 2166136261u
        for (c in label) hash = (hash xor c.code.toUInt()) * 16777619u
        return TILE_COLORS[(hash % TILE_COLORS.size.toUInt()).toInt()]
    }

    private fun tile(canvas: Canvas, side: Float, label: String, @ColorInt color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val radius = side * 5 / 24
        canvas.drawRoundRect(0f, 0f, side, side, radius, radius, paint)
        val initials = initials(label)
        if (initials.isEmpty()) return
        paint.color = Color.WHITE
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 48f
        val text = Path()
        paint.getTextPath(initials, 0, initials.length, 0f, 0f, text)
        val bounds = RectF()
        text.computeBounds(bounds, true)
        if (bounds.isEmpty) return
        val scale = min(side * 0.72f / bounds.width(), side * 0.42f / bounds.height())
        text.transform(Matrix().apply {
            setTranslate(-bounds.centerX(), -bounds.centerY())
            postScale(scale, scale)
            postTranslate(side / 2, side / 2)
        })
        canvas.drawPath(text, paint)
    }

    /** A ring cut out of the logo, then a coloured dot with a white tick, dash or cross. */
    private fun badge(canvas: Canvas, unit: Float, state: State, @ColorInt color: Int) {
        canvas.save()
        canvas.scale(unit, unit)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        canvas.drawCircle(19f, 19f, 5.7f, paint)
        paint.xfermode = null
        paint.color = color
        canvas.drawCircle(19f, 19f, 4.6f, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.6f
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        when (state) {
            State.ACCESS -> canvas.drawPath(Path().apply {
                moveTo(16.6f, 19.2f)
                lineTo(18.2f, 20.8f)
                lineTo(21.2f, 17.6f)
            }, paint)

            State.PARTIAL -> canvas.drawLine(16.6f, 19f, 21.4f, 19f, paint)
            else -> {
                canvas.drawLine(17.2f, 17.2f, 20.8f, 20.8f, paint)
                canvas.drawLine(20.8f, 17.2f, 17.2f, 20.8f, paint)
            }
        }
        canvas.restore()
    }

    /** An icon in running text, centred on the line, with [gapPx] after it. */
    class IconSpan(private val drawable: Drawable, private val gapPx: Int) : ReplacementSpan() {

        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            val height = drawable.bounds.height()
            if (fm != null) {
                val metrics = paint.fontMetricsInt
                val extra = max(0, height - (metrics.descent - metrics.ascent))
                fm.ascent = metrics.ascent - extra / 2
                fm.descent = metrics.descent + (extra - extra / 2)
                fm.top = min(metrics.top, fm.ascent)
                fm.bottom = max(metrics.bottom, fm.descent)
            }
            return drawable.bounds.width() + gapPx
        }

        override fun draw(
            canvas: Canvas, text: CharSequence?, start: Int, end: Int,
            x: Float, top: Int, y: Int, bottom: Int, paint: Paint,
        ) {
            val metrics = paint.fontMetricsInt
            val center = y + (metrics.ascent + metrics.descent) / 2f
            canvas.save()
            canvas.translate(x, center - drawable.bounds.height() / 2f)
            drawable.draw(canvas)
            canvas.restore()
        }
    }
}
