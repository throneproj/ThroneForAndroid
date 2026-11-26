package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.Toolbar
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutLogcatBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.applyInsetMargin
import io.nekohasekai.sagernet.widget.applyInsetPadding
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.matsuri.nb4a.utils.CoreLog
import moe.matsuri.nb4a.utils.LogExport
import moe.matsuri.nb4a.utils.SendLog
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.util.regex.PatternSyntaxException

/**
 * The desktop's log browser (mainwindow_log.cpp): a live tail of core.log, coloured by [LogHighlighter], with a filter
 * behind a toolbar button (case and regex toggles, match count, highlighted hits) that follows new lines only while
 * scrolled to the bottom; scrolled up, new lines wait and merge on the way back down.
 */
class LogcatFragment : ToolbarFragment(R.layout.layout_logcat),
    Toolbar.OnMenuItemClickListener {

    companion object {
        // "Hide destinations" lasts for the process; "Hide sensitive data" is logExportRedact.
        private var hideDestinations = false

        /** The tail of core.log the screen opens with. */
        private const val INITIAL_BYTES = 50 * 1024

        /** What the view and the hold queue each keep; the desktop counts lines (max_log_line), Android has no such setting. */
        private const val MAX_CHARS = 50 * 1024
        private const val MAX_LINE_CHARS = 20000

        /** Highlighted hits per line, so a broad filter ('.') over a long line does not add tens of thousands of spans. */
        private const val MAX_LINE_HITS = 256

        private const val POLL_MS = 500L
        private const val SEARCH_DEBOUNCE_MS = 150L

        /** Slack so a fractional layout height still counts as scrolled to the bottom. */
        private const val BOTTOM_SLACK_DP = 4
        private const val ERROR_TEXT_COLOR = 0xFFE05252.toInt()
        private const val STATE_FILTER = "logFilterVisible"

        /** In sp; 0 keeps the text view's own size. */
        private val FONT_SIZES = intArrayOf(0, 10, 11, 12, 13, 14, 16, 18, 20)
    }

    private class LogLine(val text: String, var visible: Boolean)

    lateinit var binding: LayoutLogcatBinding
    private lateinit var highlighter: LogHighlighter

    private val lines = ArrayDeque<LogLine>()
    private var linesChars = 0
    private val held = ArrayDeque<String>()
    private var heldChars = 0
    private var follow = true

    private var search: Regex? = null
    private var filterVisible = false
    private val applySearchLater = Runnable { applySearch() }
    private var searchTextColors: ColorStateList? = null
    private var hitBackground = 0
    private var hitForeground = 0

    private var tail = LogTail(CoreLog.file, INITIAL_BYTES)

    // Bumped by every reset: a poll that started before it belongs to the old view.
    private var tailGeneration = 0
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var defaultTextSize = 0f
    private var bottomSlack = 0

    private val closeFilter = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setFilterVisible(false)
    }

    private val saveLogs = registerForActivityResult(SaveDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        val context = requireContext().applicationContext
        val redact = DataStore.logExportRedact
        val destinations = hideDestinations
        runOnDefaultDispatcher {
            val error = try {
                val file = LogExport.build(context, redact, destinations)
                try {
                    context.contentResolver.openOutputStream(uri)!!.use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                } finally {
                    file.delete()
                }
                null
            } catch (e: Exception) {
                Logs.w(e)
                e.readableMessage
            }
            onMainDispatcher {
                safeSnackbar(
                    if (error == null) context.getString(R.string.log_saved)
                    else context.getString(R.string.log_export_failed, error)
                )
            }
        }
    }

    @SuppressLint("RestrictedApi", "WrongConstant")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar?.setTitle(R.string.menu_log)

        toolbar?.inflateMenu(R.menu.logcat_menu)
        toolbar?.setOnMenuItemClickListener(this)
        updateExportOptions()

        binding = LayoutLogcatBinding.bind(view)

        if (Build.VERSION.SDK_INT >= 23) {
            binding.textview.breakStrategy = 0 // simple
        }
        defaultTextSize = binding.textview.textSize
        applyFontSize()
        bottomSlack = dp2px(BOTTOM_SLACK_DP)
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        hitBackground = if (dark) Color.rgb(150, 110, 0) else Color.rgb(255, 225, 90)
        hitForeground = if (dark) Color.WHITE else Color.BLACK
        searchTextColors = binding.logSearch.textColors
        // The desktop's log body is the window's text colour, which the muted and level colours are measured against.
        binding.textview.setTextColor(
            MaterialColors.getColor(view.context, android.R.attr.textColorPrimary, binding.textview.currentTextColor)
        )
        highlighter = LogHighlighter(LogPalette.of(binding.textview))

        binding.scroolview.applyListInsets(ime = true)
        binding.logFilterBar.applyInsetPadding(horizontal = true)
        binding.logJumpLatest.applyInsetMargin(bottom = true, horizontal = true, ime = true)

        // Follow mode tracks where the user leaves the scroll position; content changes only snap it back down.
        binding.scroolview.setOnScrollChangeListener { _, _, scrollY, _, _ -> onScrolled(scrollY) }
        val stick = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (follow) scrollToBottom() }
        binding.textview.addOnLayoutChangeListener(stick)
        binding.scroolview.addOnLayoutChangeListener(stick)
        binding.logJumpLatest.setOnClickListener { releaseHeld() }

        binding.logSearch.doAfterTextChanged {
            binding.logSearch.removeCallbacks(applySearchLater)
            binding.logSearch.postDelayed(applySearchLater, SEARCH_DEBOUNCE_MS)
        }
        binding.logSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return@setOnEditorActionListener false
            applySearch()
            hideKeyboard()
            true
        }
        // Esc closes the filter, as on the desktop (32138a44).
        binding.logSearch.setOnKeyListener { _, keyCode, event ->
            if (keyCode != KeyEvent.KEYCODE_ESCAPE) return@setOnKeyListener false
            if (event.action == KeyEvent.ACTION_UP) setFilterVisible(false)
            true
        }
        binding.logSearchOptions.addOnButtonCheckedListener { _, _, _ -> applySearch() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, closeFilter)

        resetView()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { followLog() }
        }
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        if (savedInstanceState?.getBoolean(STATE_FILTER) == true) setFilterVisible(true, focus = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_FILTER, filterVisible)
    }

    override fun onDestroyView() {
        binding.logSearch.removeCallbacks(applySearchLater)
        super.onDestroyView()
    }

    private fun updateExportOptions() {
        val menu = toolbar?.menu ?: return
        val redact = DataStore.logExportRedact
        menu.findItem(R.id.action_hide_sensitive)?.isChecked = redact
        menu.findItem(R.id.action_hide_destinations)?.apply {
            isEnabled = redact
            isChecked = redact && hideDestinations
        }
    }

    private suspend fun followLog() {
        while (true) {
            val generation = tailGeneration
            val reader = tail
            // The read position moves only together with the append: a cancellation while the poll runs on IO drops
            // the batch and leaves the position as it was, so the next poll reads those lines again.
            val batch = withContext(Dispatchers.IO) { reader.poll() }
            if (batch != null && generation == tailGeneration) {
                reader.commit(batch)
                if (batch.lines.isNotEmpty()) appendLines(batch.lines)
            }
            withTimeoutOrNull(POLL_MS) { wake.receive() }
        }
    }

    /** Starts over from the last [INITIAL_BYTES] of the file (Refresh, Clear). */
    private fun resetView() {
        tailGeneration++
        tail = LogTail(CoreLog.file, INITIAL_BYTES)
        lines.clear()
        linesChars = 0
        held.clear()
        heldChars = 0
        follow = true
        binding.textview.setText("", TextView.BufferType.EDITABLE)
        binding.textview.clearFocus()
        updateStatus()
        wake.trySend(Unit)
    }

    /** flush_log_batch: everything passes the hold queue, which only fills while the view is scrolled up. */
    private fun appendLines(batch: List<String>) {
        for (raw in batch) {
            if (raw.isBlank()) continue
            val line = if (raw.length > MAX_LINE_CHARS) "TRUNCATED LONG LOG: ${raw.take(1000)}..." else raw
            held.addLast(line)
            heldChars += line.length + 1
        }
        trimHeld()
        if (follow) releaseHeld() else updateStatus()
    }

    private fun releaseHeld() {
        // Set first so the layout pass of the append already snaps to the bottom.
        follow = true
        if (held.isNotEmpty()) {
            val shown = SpannableStringBuilder()
            for (text in held) {
                val visible = matches(text)
                if (visible) appendStyled(shown, text)
                lines.addLast(LogLine(text, visible))
                linesChars += text.length + 1
            }
            held.clear()
            heldChars = 0
            val document = document()
            if (shown.isNotEmpty()) {
                if (document.isNotEmpty()) document.append('\n')
                document.append(shown)
            }
            removeLeadingLines(document, trimLines())
        }
        scrollToBottom()
        updateStatus()
    }

    /** Drops the oldest lines past [MAX_CHARS]; returns how many of them the view shows. */
    private fun trimLines(): Int {
        var droppedVisible = 0
        while (linesChars > MAX_CHARS && lines.size > 1) {
            val line = lines.removeFirst()
            linesChars -= line.text.length + 1
            if (line.visible) droppedVisible++
        }
        trimHeld()
        return droppedVisible
    }

    private fun trimHeld() {
        while (heldChars > MAX_CHARS && held.size > 1) heldChars -= held.removeFirst().length + 1
    }

    private fun rebuildView() {
        val shown = SpannableStringBuilder()
        for (line in lines) {
            line.visible = matches(line.text)
            if (line.visible) appendStyled(shown, line.text)
        }
        follow = true
        binding.textview.setText(shown, TextView.BufferType.EDITABLE)
        scrollToBottom()
        updateStatus()
    }

    private fun document(): Editable = binding.textview.editableText ?: run {
        binding.textview.setText("", TextView.BufferType.EDITABLE)
        binding.textview.editableText!!
    }

    // Every log line stays one text line: the view maps visible lines to text lines by count.
    private fun removeLeadingLines(document: Editable, count: Int) {
        if (count <= 0) return
        var end = 0
        repeat(count) {
            val next = TextUtils.indexOf(document, '\n', end)
            if (next < 0) {
                document.clear()
                return
            }
            end = next + 1
        }
        document.delete(0, end)
    }

    private fun matches(text: String) = search?.containsMatchIn(text) ?: true

    private fun appendStyled(out: SpannableStringBuilder, text: String) {
        if (out.isNotEmpty()) out.append('\n')
        val start = out.length
        out.append(text)
        highlighter.paint(out, start, text)
        val pattern = search ?: return
        // Last, so search hits paint over the syntax colours. One Matcher per line: findAll makes one per hit, and each
        // copies the whole line.
        val matcher = pattern.toPattern().matcher(text)
        var hits = 0
        while (hits < MAX_LINE_HITS && matcher.find()) {
            if (matcher.start() == matcher.end()) continue
            val from = start + matcher.start()
            val to = start + matcher.end()
            out.setSpan(BackgroundColorSpan(hitBackground), from, to, SPAN_EXCLUSIVE_EXCLUSIVE)
            out.setSpan(ForegroundColorSpan(hitForeground), from, to, SPAN_EXCLUSIVE_EXCLUSIVE)
            hits++
        }
    }

    private fun scrollRange(): Int {
        val scroll = binding.scroolview
        val child = scroll.getChildAt(0) ?: return 0
        return maxOf(0, child.height - (scroll.height - scroll.paddingTop - scroll.paddingBottom))
    }

    private fun scrollToBottom() = binding.scroolview.scrollTo(0, scrollRange())

    private fun onScrolled(scrollY: Int) {
        val atBottom = scrollY >= scrollRange() - bottomSlack
        if (atBottom == follow) return
        follow = atBottom
        if (follow && held.isNotEmpty()) {
            // Deferred: merging relayouts the text while the scroll is still being applied.
            binding.scroolview.post { if (follow) releaseHeld() }
        }
        updateStatus()
    }

    private fun applySearch() {
        binding.logSearch.removeCallbacks(applySearchLater)
        // A hidden bar keeps its text and toggles but stops filtering.
        val text = if (filterVisible) binding.logSearch.text?.toString().orEmpty() else ""
        var pattern: Regex? = null
        if (text.isNotEmpty()) {
            val options = if (binding.logSearchCase.isChecked) emptySet() else setOf(RegexOption.IGNORE_CASE)
            try {
                pattern = Regex(if (binding.logSearchRegex.isChecked) text else Regex.escape(text), options)
            } catch (e: PatternSyntaxException) {
                // Keep the last valid filter rather than blanking the view while a pattern is half-typed.
                binding.logSearch.setTextColor(ERROR_TEXT_COLOR)
                TooltipCompat.setTooltipText(binding.logSearch, e.description)
                return
            }
        }
        searchTextColors?.let { binding.logSearch.setTextColor(it) }
        TooltipCompat.setTooltipText(binding.logSearch, null)
        if (pattern?.pattern == search?.pattern && pattern?.options == search?.options) return

        search = pattern
        // A new filter jumps to the newest results, so fold in whatever was held.
        for (line in held) {
            lines.addLast(LogLine(line, true))
            linesChars += line.length + 1
        }
        held.clear()
        heldChars = 0
        trimLines()
        rebuildView()
    }

    private fun setFilterVisible(visible: Boolean, focus: Boolean = true) {
        filterVisible = visible
        binding.logFilterBar.isVisible = visible
        closeFilter.isEnabled = visible
        toolbar?.menu?.findItem(R.id.action_log_filter)
            ?.setTitle(if (visible) R.string.log_filter_hide else R.string.log_filter)
        if (visible && focus) {
            binding.logSearch.requestFocus()
            binding.logSearch.post {
                context?.getSystemService<InputMethodManager>()
                    ?.showSoftInput(binding.logSearch, InputMethodManager.SHOW_IMPLICIT)
            }
        } else if (!visible) {
            hideKeyboard()
            binding.logSearch.clearFocus()
        }
        applySearch()
    }

    private fun hideKeyboard() {
        context?.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(binding.logSearch.windowToken, 0)
    }

    private fun updateStatus() {
        binding.logMatchCount.text = if (search == null) "" else "${lines.count { it.visible }} / ${lines.size}"
        binding.logJumpLatest.isVisible = !follow
        binding.logJumpLatest.text = if (held.isEmpty()) {
            getString(R.string.log_jump_latest)
        } else {
            resources.getQuantityString(R.plurals.log_jump_latest_new, held.size, held.size)
        }
    }

    private fun applyFontSize() {
        val size = DataStore.configurationStore.getInt(Key.LOG_FONT_SIZE, 0)
        if (size > 0) {
            binding.textview.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        } else {
            binding.textview.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaultTextSize)
        }
    }

    /** The desktop's "Log Browser Font Size"; Android has a single monospace family, so there is no family picker. */
    private fun pickFontSize() {
        val current = DataStore.configurationStore.getInt(Key.LOG_FONT_SIZE, 0)
        val labels = FONT_SIZES.map { if (it == 0) getString(R.string.setting_default) else it.toString() }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.log_font_size)
            .setSingleChoiceItems(labels.toTypedArray(), FONT_SIZES.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                val size = FONT_SIZES[which]
                if (size == 0) {
                    DataStore.configurationStore.remove(Key.LOG_FONT_SIZE)
                } else {
                    DataStore.configurationStore.putInt(Key.LOG_FONT_SIZE, size)
                }
                applyFontSize()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_log_filter -> setFilterVisible(!filterVisible)

            R.id.action_clear_logcat -> {
                runOnDefaultDispatcher {
                    try {
                        CoreLog.clear()
                        Runtime.getRuntime().exec("/system/bin/logcat -c")
                    } catch (e: Exception) {
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        if (view != null) resetView()
                    }
                }

            }

            R.id.action_share_logs -> {
                val context = requireContext()
                val redact = DataStore.logExportRedact
                val destinations = hideDestinations
                runOnDefaultDispatcher {
                    try {
                        val file = LogExport.build(context, redact, destinations)
                        onMainDispatcher {
                            SendLog.share(context, file, file.nameWithoutExtension)
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            safeSnackbar(context.getString(R.string.log_export_failed, e.readableMessage))
                        }
                    }
                }
            }

            R.id.action_save_logs -> startFilesForResult(saveLogs, LogExport.fileName())

            R.id.action_log_font_size -> pickFontSize()

            R.id.action_hide_sensitive -> {
                DataStore.logExportRedact = !DataStore.logExportRedact
                updateExportOptions()
            }

            R.id.action_hide_destinations -> {
                hideDestinations = !hideDestinations
                updateExportOptions()
            }

            R.id.action_refresh -> resetView()
        }
        return true
    }

}

/**
 * Follows core.log, which both processes append to and CoreLog trims in place to its newest half at the size cap (a
 * clear or a new service session empties it). A poll returns the complete lines written since the last one. When the
 * bytes last read are no longer just before the read offset, the file was cut: reading goes on after those bytes in
 * the cut file, or takes the whole file as new when they are gone. Blocking; one poll at a time. A poll only reads: the
 * caller [commit]s its batch once the lines are shown.
 */
private class LogTail(private val file: File, private val initialBytes: Int) {

    private companion object {
        const val MARK_BYTES = 1024
        const val NEWLINE = '\n'.code.toByte()
    }

    class Batch(val lines: List<String>, val offset: Long, val mark: ByteArray)

    /** Bytes of the file consumed, always at a line start; -1 before the first read. */
    private var offset = -1L

    /** The last bytes before [offset], at most [MARK_BYTES]. */
    private var mark = ByteArray(0)

    private var lastError: String? = null

    /** Null when nothing could be read; the next poll starts again from the committed position. */
    fun poll(): Batch? = try {
        RandomAccessFile(file, "r").use { read(it) }.also { lastError = null }
    } catch (e: FileNotFoundException) {
        null
    } catch (e: EOFException) {
        // The file was cut between its length and the read: the next poll sees the cut.
        null
    } catch (e: IOException) {
        // Logcat only, once per distinct error: a line in core.log would show up in this very view.
        val error = e.toString()
        if (error != lastError) {
            lastError = error
            Log.w("LogTail", "core.log read failed", e)
        }
        null
    }

    fun commit(batch: Batch) {
        offset = batch.offset
        mark = batch.mark
    }

    private fun read(raf: RandomAccessFile): Batch {
        val length = raf.length()
        if (offset < 0) {
            val start = maxOf(0L, length - initialBytes)
            val bytes = bytesAt(raf, start, length)
            // The cut lands mid-line: start at the next full line.
            val from = if (start > 0) bytes.indexOf(NEWLINE) + 1 else 0
            return take(bytes, from, start, ByteArray(0))
        }
        if (length < offset || !markHolds(raf)) {
            val bytes = bytesAt(raf, 0, length)
            val at = if (mark.isEmpty()) -1 else indexOf(bytes, mark)
            return take(bytes, if (at < 0) 0 else at + mark.size, 0, ByteArray(0))
        }
        if (length == offset) return Batch(emptyList(), offset, mark)
        return take(bytesAt(raf, offset, length), 0, offset, mark)
    }

    /** [bytes] are the file from [base]; the lines from [from] up to the last newline are new, [prior] precedes them. */
    private fun take(bytes: ByteArray, from: Int, base: Long, prior: ByteArray): Batch {
        val end = maxOf(from, bytes.lastIndexOf(NEWLINE) + 1)
        val nextMark = if (end >= MARK_BYTES) {
            bytes.copyOfRange(end - MARK_BYTES, end)
        } else {
            val keep = minOf(prior.size, MARK_BYTES - end)
            prior.copyOfRange(prior.size - keep, prior.size) + bytes.copyOfRange(0, end)
        }
        val lines = if (end == from) {
            emptyList()
        } else {
            String(bytes, from, end - from, Charsets.UTF_8).split('\n').dropLast(1).map { it.removeSuffix("\r") }
        }
        return Batch(lines, base + end, nextMark)
    }

    private fun markHolds(raf: RandomAccessFile): Boolean {
        if (mark.isEmpty()) return true
        val at = offset - mark.size
        return at >= 0 && bytesAt(raf, at, offset).contentEquals(mark)
    }

    private fun bytesAt(raf: RandomAccessFile, from: Long, to: Long): ByteArray {
        val out = ByteArray((to - from).toInt())
        raf.seek(from)
        raf.readFully(out)
        return out
    }

    private fun indexOf(bytes: ByteArray, target: ByteArray): Int {
        outer@ for (i in 0..bytes.size - target.size) {
            for (j in target.indices) if (bytes[i + j] != target[j]) continue@outer
            return i
        }
        return -1
    }
}
