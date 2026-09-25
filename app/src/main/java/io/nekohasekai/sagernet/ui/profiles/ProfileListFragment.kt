package io.nekohasekai.sagernet.ui.profiles

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewTreeObserver
import android.widget.ImageView
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.FixedGridLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ConfigurationFragment
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.widget.applyListInsets
import io.nekohasekai.sagernet.widget.updateBasePadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** One tab of the profiles screen: the profiles of one group. */
class ProfileListFragment : Fragment(R.layout.layout_profile_list) {

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        /** The list clears the FAB and the stats bar (plus the navigation bar, see applyListInsets). */
        private const val BOTTOM_PADDING_DP = 80

        fun newInstance(groupId: Long) = ProfileListFragment().apply {
            arguments = bundleOf(ARG_GROUP_ID to groupId)
        }
    }

    val groupId: Long get() = requireArguments().getLong(ARG_GROUP_ID)

    val host: ConfigurationFragment? get() = parentFragment as? ConfigurationFragment

    lateinit var list: RecyclerView
        private set
    private lateinit var emptyHint: View
    internal lateinit var adapter: ProfileListAdapter
        private set

    /** The view's scope while the view exists. */
    internal var scope: CoroutineScope? = null
        private set
    internal var undoManager: UndoSnackbarManager<ProxyEntity>? = null
        private set
    private var dragHelper: ItemTouchHelper? = null
    private var savedScroll = Int.MIN_VALUE

    internal val isScrolling: Boolean
        get() = ::list.isInitialized && list.scrollState != RecyclerView.SCROLL_STATE_IDLE

    /** Double column mode: the compact card (no edit / share buttons, a one-line error). */
    internal val isCompact: Boolean get() = host?.doubleColumn == true

    /** Cards side by side (the list's width decides): a grid row pairs their heights, drags go sideways too. */
    internal val isMultiColumn: Boolean
        get() = ::list.isInitialized && ((list.layoutManager as? GridLayoutManager)?.spanCount ?: 1) > 1

    private var columnsPending = false
    private var bottomBar: MainActivity? = null

    internal fun onMain(block: () -> Unit) {
        scope?.launch(Dispatchers.Main.immediate) { block() }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        list = view.findViewById(R.id.configuration_list)
        list.applyListInsets(ime = true)
        emptyHint = view.findViewById(R.id.profiles_empty)
        emptyHint.applyListInsets()
        if (host?.select == true) {
            view.findViewById<View>(R.id.profiles_empty_icon).isVisible = false
            view.findViewById<View>(R.id.profiles_empty_hint).isVisible = false
        }
        scope = viewLifecycleOwner.lifecycleScope
        adapter = ProfileListAdapter(this)
        list.layoutManager = FixedGridLayoutManager(list, if (isCompact) 2 else 1)
        list.adapter = adapter
        list.setItemViewCacheSize(20)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) adapter.flushPendingTraffic()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val host = host
                if (host?.headerMoving == true) return
                host?.onListScrolled(this@ProfileListFragment, dy)
                if (dy != 0) bottomBar?.driveBottomBar(dy)
            }
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onListLaidOut() }
        ProfileManager.addListener(adapter)
        GroupRepo.addListener(adapter)

        val host = host
        if (host != null && !host.select) {
            (activity as? ThemedActivity)?.let { undoManager = UndoSnackbarManager(it, adapter) }
            attachDragHelper()
            driveBottomBar()
        }
        updateBottomPadding()
        host?.attachList(this)
        host?.let { adapter.onTestState(it.testState) }
        adapter.reload()
    }

    override fun onPause() {
        super.onPause()
        saveScrollPosition()
    }

    override fun onDestroyView() {
        ProfileManager.removeListener(adapter)
        GroupRepo.removeListener(adapter)
        host?.detachList(this)
        undoManager?.flush()
        undoManager = null
        dragHelper?.attachToRecyclerView(null)
        dragHelper = null
        bottomBar = null
        scope = null
        super.onDestroyView()
    }

    /** Single / double column changed: the card look and the column count. */
    @SuppressLint("NotifyDataSetChanged")
    fun switchLayout() {
        (list.layoutManager as? GridLayoutManager)?.spanCount = columns()
        adapter.notifyDataSetChanged()
    }

    /** Single keeps cards 320 dp wide or more, Double 180 dp and two columns at least, over the list's width. */
    private fun columns(): Int {
        val width = (list.width - list.paddingLeft - list.paddingRight) / list.resources.displayMetrics.density
        return if (isCompact) max(2, (width / 180).toInt()) else max(1, (width / 320).toInt())
    }

    /**
     * A new width may change the column count. It is applied before the next frame is drawn, outside the layout pass
     * that noticed it, and that frame is skipped: it would show the old columns at the new width.
     */
    private fun onListLaidOut() {
        val layoutManager = list.layoutManager as? GridLayoutManager ?: return
        if (columnsPending || columns() == layoutManager.spanCount) return
        columnsPending = true
        val observer = list.viewTreeObserver
        observer.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                columnsPending = false
                val columns = columns()
                if (view == null || columns == layoutManager.spanCount) return true
                // LinearLayoutManager anchors on the first row on screen (moved to its first column): the place stays
                layoutManager.spanCount = columns
                // grid rows pair their cards' heights when they bind
                adapter.notifyAllContent()
                return false
            }
        })
    }

    fun updateBottomPadding() {
        if (!::list.isInitialized) return
        // The test panel reaches the bottom edge and keeps its own content clear of the FAB and the stats bar; the
        // picker has neither.
        val host = host
        val clearance = if (host?.select == true) list.paddingTop else dp2px(BOTTOM_PADDING_DP)
        list.updateBasePadding(bottom = max(clearance, host?.panelHeight ?: 0))
    }

    /** An empty group says how to add profiles instead of a blank page; an empty subscription offers its update. */
    internal fun showEmpty(empty: Boolean) {
        emptyHint.isVisible = empty
        if (!empty) return
        val subscription = adapter.group?.isSubscription == true
        emptyHint.findViewById<TextView>(R.id.profiles_empty_title)
            .setText(if (subscription) R.string.profiles_empty_subscription else R.string.profiles_empty)
        emptyHint.findViewById<TextView>(R.id.profiles_empty_hint)
            .setText(if (subscription) R.string.profiles_empty_subscription_hint else R.string.profiles_empty_hint)
        emptyHint.findViewById<ImageView>(R.id.profiles_empty_icon)
            .setImageResource(if (subscription) R.drawable.ic_baseline_update_24 else R.drawable.ic_action_note_add)
        emptyHint.findViewById<View>(R.id.profiles_empty_update).apply {
            isVisible = subscription && host?.select != true
            setOnClickListener { adapter.group?.let(GroupActions::updateSubscription) }
        }
    }

    /**
     * First data of the view: the profile a toolbar long press is looking for, the desktop's scroll_last_profile, the
     * picked or selected profile otherwise.
     */
    internal fun onFirstLoad() {
        val host = host ?: return
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val stored = adapter.group?.scrollLastProfile ?: -1
        savedScroll = stored
        val revealed = host.takePendingReveal(groupId)
        val target = when {
            revealed > 0 -> adapter.displayedIds.indexOf(revealed)
            host.select -> adapter.displayedIds.indexOf(host.pickedOrSelectedId())
            stored >= 0 -> stored.coerceAtMost(adapter.itemCount - 1)
            else -> adapter.displayedIds.indexOf(host.pickedOrSelectedId())
        }
        // the long press's row even when it is the first: a page restored from its saved state starts where it was
        if (target > 0 || (target == 0 && revealed > 0)) layoutManager.scrollToPositionWithOffset(target, 0)
    }

    /** show_group's scroll_last_profile = firstVisibleRow(), stored when the tab is left or the screen pauses. */
    fun saveScrollPosition() {
        val host = host ?: return
        if (host.select || !::adapter.isInitialized || !adapter.loaded || adapter.isFiltered) return
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val first = layoutManager.findFirstVisibleItemPosition().let { if (it == RecyclerView.NO_POSITION) -1 else it }
        if (first == savedScroll) return
        savedScroll = first
        val gid = groupId
        runOnDefaultDispatcher { GroupFields.update(gid) { it.scrollLastProfile = first } }
    }

    fun focusList() {
        if (::list.isInitialized && !list.hasFocus()) list.requestFocus()
    }

    /** Scrolls to [profileId], or to the top when it is not in the list or already visible. */
    fun scrollToProfile(profileId: Long) {
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val index = adapter.displayedIds.indexOf(profileId)
        if (index >= 0 && index !in layoutManager.findFirstVisibleItemPosition()..layoutManager.findLastVisibleItemPosition()) {
            list.smoothScrollToPosition(index)
        } else {
            list.smoothScrollToPosition(0)
        }
    }

    /** Brings [profileId] into view: on screen just far enough to show it whole, from further away to the top. */
    fun revealProfile(profileId: Long) {
        val layoutManager = list.layoutManager as? LinearLayoutManager ?: return
        val index = adapter.displayedIds.indexOf(profileId)
        if (index < 0) return
        if (index in layoutManager.findFirstVisibleItemPosition()..layoutManager.findLastVisibleItemPosition()) {
            list.smoothScrollToPosition(index)
        } else {
            layoutManager.scrollToPositionWithOffset(index, 0)
        }
    }

    /** Picks [holder] up for a drag from its long press; false when the list cannot be reordered now. */
    fun startDrag(holder: RecyclerView.ViewHolder): Boolean {
        val helper = dragHelper ?: return false
        if (!adapter.canDrag()) return false
        helper.startDrag(holder)
        return adapter.dragging
    }

    /**
     * Drags reorder (the desktop's EmplaceProfile). They start from the row's long press, which also starts the
     * multi-selection; that ends again when the row really moved: a long press selects, a long press and drag reorders.
     */
    private fun attachDragHelper() {
        dragHelper?.attachToRecyclerView(null)
        dragHelper = ItemTouchHelper(object : ItemTouchHelper.Callback() {
            override fun isLongPressDragEnabled() = false

            override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder): Int {
                if (!adapter.canDrag()) return 0
                val directions = if (isMultiColumn) {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
                } else {
                    ItemTouchHelper.UP or ItemTouchHelper.DOWN
                }
                return makeMovementFlags(directions, 0)
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.move(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder is ProfileRowHolder) {
                    adapter.startDrag(viewHolder.profile)
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                adapter.endDrag()
            }
        }).also { it.attachToRecyclerView(list) }
    }

    /** The stats bar hides and shows with this list's scrolling (also when the list is too short to scroll). */
    @SuppressLint("ClickableViewAccessibility")
    private fun driveBottomBar() {
        val mainActivity = activity as? MainActivity ?: return
        // the scroll listener feeds it the list's scrolls (none while the header slides)
        bottomBar = mainActivity
        val touchSlop = ViewConfiguration.get(requireContext()).scaledTouchSlop
        var lastRawY = 0f
        list.setOnTouchListener { recyclerView, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> lastRawY = event.rawY
                MotionEvent.ACTION_MOVE -> {
                    val cannotScroll = !recyclerView.canScrollVertically(-1) && !recyclerView.canScrollVertically(1)
                    if (cannotScroll) {
                        val fingerDy = event.rawY - lastRawY
                        if (abs(fingerDy) >= touchSlop) {
                            mainActivity.driveBottomBar(-fingerDy.toInt())
                            lastRawY = event.rawY
                        }
                    }
                }
            }
            false
        }
    }
}
