package io.nekohasekai.sagernet.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.RemoteException
import android.os.SystemClock
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.LiveStatsBatch
import io.nekohasekai.sagernet.bg.LiveSeries
import io.nekohasekai.sagernet.bg.LiveStatsRecorder
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.databinding.LayoutLiveGraphsBinding
import io.nekohasekai.sagernet.databinding.LayoutLiveLegendRowBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.widget.LiveChartView
import io.nekohasekai.sagernet.widget.applyListInsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The desktop's Traffic Graph tab (c3c2778b): proxy and direct speed over 1, 2, 5 or 10 minutes from the service's
 * history (bg.LiveStatsRecorder), lines and directions toggled in the legend; below it the runtime panel's sparklines
 * for the service process that runs the core. The graph state is the desktop's traffic_graph_* settings.
 */
class LiveGraphsFragment : ToolbarFragment(R.layout.layout_live_graphs), Toolbar.OnMenuItemClickListener {

    private class Range(val seconds: Int, val tickSeconds: Int, @IdRes val button: Int)

    private class LegendRow(val binding: LayoutLiveLegendRowBinding, val row: LiveChartView.Row)

    companion object {
        private const val POLL_MS = 500L
        private const val SPARKLINE_MS = 60_000L

        /** A point older than this is not the current value any more. */
        private const val STALE_MS = 3000L

        // The desktop's line and direction keys (Stats::TrafficLine, LiveChartOptions::trafficGraph).
        private const val ROW_PROXY = "proxy"
        private const val ROW_DIRECT = "direct"
        private const val COLUMN_DOWN = "d"
        private const val COLUMN_UP = "u"

        private val RANGES = arrayOf(
            Range(60, 15, R.id.live_range_1m),
            Range(120, 30, R.id.live_range_2m),
            Range(300, 60, R.id.live_range_5m),
            Range(600, 120, R.id.live_range_10m),
        )
    }

    private var savedHidden by SettingsRegistry.TRAFFIC_GRAPH_HIDDEN
    private var savedHiddenDirs by SettingsRegistry.TRAFFIC_GRAPH_HIDDEN_DIRS
    private var savedSmooth by SettingsRegistry.TRAFFIC_GRAPH_SMOOTH

    private lateinit var binding: LayoutLiveGraphsBinding
    private lateinit var rateAxis: LiveChartView.Axis
    private val percentAxis = LiveChartView.percentAxis()
    private lateinit var bytesAxis: LiveChartView.Axis
    private val countAxis = LiveChartView.countAxis()
    private val legend = ArrayList<LegendRow>()
    private val hiddenRows = LinkedHashSet<String>()
    private val hiddenColumns = LinkedHashSet<String>()
    private var range = 1
    private var smooth = true

    private val charts get() = listOf(binding.liveTrafficChart, binding.liveCpuChart, binding.liveRamChart, binding.liveConnectionsChart)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutLiveGraphsBinding.bind(view)
        toolbar?.setTitle(R.string.live_graphs)
        toolbar?.inflateMenu(R.menu.live_graphs_menu)
        toolbar?.setOnMenuItemClickListener(this)
        binding.liveScroll.applyListInsets()

        val context = requireContext()
        legend.clear()
        hiddenRows.clear()
        hiddenColumns.clear()
        hiddenRows.addAll(savedHidden)
        val dirs = savedHiddenDirs
        if (dirs and 1 != 0) hiddenColumns.add(COLUMN_DOWN)
        if (dirs and 2 != 0) hiddenColumns.add(COLUMN_UP)
        // Another window length shows two minutes, as on the desktop.
        range = RANGES.indexOfFirst { it.seconds == DataStore.trafficGraphSeconds }.takeIf { it >= 0 } ?: 1
        smooth = savedSmooth
        toolbar?.menu?.findItem(R.id.action_live_smooth)?.isChecked = smooth

        rateAxis = LiveChartView.rateAxis(context)
        bytesAxis = LiveChartView.bytesAxis(context)
        val rows = listOf(
            LiveChartView.Row(
                ROW_PROXY, getString(R.string.live_graph_proxy), LiveChartView.proxyColor(context), true,
                intArrayOf(LiveStatsBatch.PROXY_DOWN, LiveStatsBatch.PROXY_UP),
            ),
            LiveChartView.Row(
                ROW_DIRECT, getString(R.string.live_graph_direct), LiveChartView.directColor(context), false,
                intArrayOf(LiveStatsBatch.DIRECT_DOWN, LiveStatsBatch.DIRECT_UP),
            ),
        )
        binding.liveTrafficChart.setup(
            rows,
            listOf(
                LiveChartView.Column(COLUMN_DOWN, "↓", dashed = false, wash = true),
                LiveChartView.Column(COLUMN_UP, "↑", dashed = true, wash = false),
            ),
            rateAxis,
        )
        val core = getString(R.string.live_graph_core)
        val coreColor = LiveChartView.coreColor(context)
        val value = listOf(LiveChartView.Column("v", "", dashed = false, wash = true))
        binding.liveCpuChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.CPU))), value, percentAxis,
            compact = true,
        )
        binding.liveRamChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.MEMORY))), value, bytesAxis,
            compact = true,
        )
        binding.liveConnectionsChart.setup(
            listOf(LiveChartView.Row("core", core, coreColor, true, intArrayOf(LiveStatsBatch.CONNECTIONS))), value,
            countAxis, compact = true,
        )
        for (chart in charts) chart.data = LiveHistory.series
        for (chart in listOf(binding.liveCpuChart, binding.liveRamChart, binding.liveConnectionsChart)) {
            chart.windowMs = SPARKLINE_MS
        }

        for (row in rows) {
            val item = LayoutLiveLegendRowBinding.inflate(layoutInflater, binding.liveLegend, true)
            item.liveLegendName.text = row.label
            item.root.setOnClickListener { toggleRow(row.key) }
            item.root.setOnLongClickListener {
                soloRow(row.key)
                true
            }
            legend.add(LegendRow(item, row))
        }
        val secondary = MaterialColors.getColor(context, android.R.attr.textColorSecondary, 0xff808080.toInt())
        binding.liveColumnDown.text = "↓ " + getString(R.string.live_graph_down)
        binding.liveColumnUp.text = "↑ " + getString(R.string.live_graph_up)
        binding.liveColumnDown.setCompoundDrawablesRelativeWithIntrinsicBounds(lineKey(secondary, false), null, null, null)
        binding.liveColumnUp.setCompoundDrawablesRelativeWithIntrinsicBounds(lineKey(secondary, true), null, null, null)
        binding.liveColumnDown.setOnClickListener { toggleColumn(COLUMN_DOWN) }
        binding.liveColumnUp.setOnClickListener { toggleColumn(COLUMN_UP) }

        binding.liveRange.check(RANGES[range].button)
        binding.liveRange.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            val index = RANGES.indexOfFirst { it.button == id }
            if (index < 0 || index == range) return@addOnButtonCheckedListener
            range = index
            DataStore.trafficGraphSeconds = RANGES[index].seconds
            applyState()
        }

        applyState()
        render()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    pull()
                    render()
                    delay(POLL_MS)
                }
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_live_smooth -> {
                smooth = !smooth
                item.isChecked = smooth
                savedSmooth = smooth
            }

            R.id.action_live_show_all -> {
                if (hiddenRows.isEmpty() && hiddenColumns.isEmpty()) return true
                hiddenRows.clear()
                hiddenColumns.clear()
                saveHidden()
            }

            R.id.action_live_clear -> {
                LiveHistory.clear()
                for (chart in charts) chart.resetView()
                render()
            }

            else -> return false
        }
        applyState()
        return true
    }

    // ------------------------------------------------------------------------------------------------ state

    private fun toggleRow(key: String) {
        if (!hiddenRows.remove(key)) hiddenRows.add(key)
        saveHidden()
        applyState()
    }

    /** The desktop's Alt-click: only this line, or all of them again when it already is the only one. */
    private fun soloRow(key: String) {
        val keys = legend.map { it.row.key }
        val already = keys.all { (it in hiddenRows) == (it != key) }
        // Only legend lines change: hidden keys of lines not shown here (a desktop route chain) stay saved.
        for (k in keys) {
            hiddenRows.remove(k)
            if (!already && k != key) hiddenRows.add(k)
        }
        saveHidden()
        applyState()
    }

    private fun toggleColumn(key: String) {
        if (!hiddenColumns.remove(key)) hiddenColumns.add(key)
        saveHidden()
        applyState()
    }

    private fun saveHidden() {
        savedHidden = hiddenRows.toList()
        savedHiddenDirs = (if (COLUMN_DOWN in hiddenColumns) 1 else 0) or (if (COLUMN_UP in hiddenColumns) 2 else 0)
    }

    private fun applyState() {
        val selected = RANGES[range]
        binding.liveTrafficChart.apply {
            windowMs = selected.seconds * 1000L
            tickMs = selected.tickSeconds * 1000L
            smooth = this@LiveGraphsFragment.smooth
            hiddenRows = this@LiveGraphsFragment.hiddenRows.toSet()
            hiddenColumns = this@LiveGraphsFragment.hiddenColumns.toSet()
            invalidate()
        }
        val downOn = COLUMN_DOWN !in hiddenColumns
        val upOn = COLUMN_UP !in hiddenColumns
        for (item in legend) {
            val on = item.row.key !in hiddenRows
            item.binding.liveLegendDot.background = dot(item.row.color, on)
            item.binding.liveLegendName.alpha = if (on) 1f else 0.5f
            item.binding.liveLegendDown.alpha = if (on && downOn) 1f else 0.5f
            item.binding.liveLegendUp.alpha = if (on && upOn) 1f else 0.5f
        }
        binding.liveColumnDown.alpha = if (downOn) 1f else 0.5f
        binding.liveColumnUp.alpha = if (upOn) 1f else 0.5f
    }

    private fun dot(color: Int, filled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        if (filled) setColor(color) else setStroke(resources.displayMetrics.density.times(1.5f).toInt().coerceAtLeast(1), color)
    }

    /** The legend key of a direction: solid for download, dashed for upload, as the graph draws them. */
    private fun lineKey(color: Int, dashed: Boolean) = GradientDrawable().apply {
        val density = resources.displayMetrics.density
        shape = GradientDrawable.LINE
        if (dashed) {
            setStroke((1.5f * density).toInt().coerceAtLeast(1), color, 4f * density, 3f * density)
        } else {
            setStroke((2f * density).toInt().coerceAtLeast(1), color)
        }
        setSize((14 * density).toInt(), (8 * density).toInt())
    }

    // ------------------------------------------------------------------------------------------------ data

    private suspend fun pull() {
        val service = (activity as? MainActivity)?.connection?.service ?: return
        val session = LiveHistory.session
        val serial = LiveHistory.serial
        val batch = withContext(Dispatchers.IO) {
            try {
                service.liveStats(session, serial)
            } catch (_: RemoteException) {
                null
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
        } ?: return
        LiveHistory.merge(batch)
    }

    private fun render() {
        for (chart in charts) chart.invalidate()
        val series = LiveHistory.series
        val last = if (series.size > 0 && SystemClock.elapsedRealtime() - series.latestTime <= STALE_MS) series.size - 1 else -1
        fun show(view: TextView, field: Int, axis: LiveChartView.Axis) {
            val v = if (last < 0) Float.NaN else series.value(field, last)
            view.text = if (v.isNaN()) "—" else axis.format(v.toDouble())
        }
        for (item in legend) {
            show(item.binding.liveLegendDown, item.row.fields[0], rateAxis)
            show(item.binding.liveLegendUp, item.row.fields[1], rateAxis)
        }
        show(binding.liveCpuValue, LiveStatsBatch.CPU, percentAxis)
        show(binding.liveRamValue, LiveStatsBatch.MEMORY, bytesAxis)
        show(binding.liveConnectionsValue, LiveStatsBatch.CONNECTIONS, countAxis)
    }
}

/** This process's copy of the service's live history, so a later visit only pulls what is new. Main thread. */
private object LiveHistory {

    val series = LiveSeries(LiveStatsRecorder.CAPACITY, LiveStatsBatch.FIELDS)

    var session = 0L
        private set
    var serial = 0L
        private set

    fun merge(batch: LiveStatsBatch) {
        if (batch.session != session) {
            series.clear()
            session = batch.session
        }
        serial = batch.serial
        for (i in batch.times.indices) series.push(batch.times[i], batch.values, i * LiveStatsBatch.FIELDS)
    }

    /** Drops what came before; the serial stays, so the service's copy is not pulled again. */
    fun clear() {
        series.clear()
    }
}
