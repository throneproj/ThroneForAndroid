package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceDataStore
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.profiles.GroupMenu
import io.nekohasekai.sagernet.ui.profiles.ProfileImports
import io.nekohasekai.sagernet.ui.profiles.ProfileItemMenu
import io.nekohasekai.sagernet.ui.profiles.ProfileListFragment
import io.nekohasekai.sagernet.ui.profiles.ProfilesDbWatcher
import io.nekohasekai.sagernet.ui.profiles.ProfilesHeader
import io.nekohasekai.sagernet.ui.profiles.ProfilesPagerAdapter
import io.nekohasekai.sagernet.ui.profiles.SelectionMode
import io.nekohasekai.sagernet.ui.test.TestPanelController
import io.nekohasekai.sagernet.ui.test.TestSessionClient
import io.nekohasekai.sagernet.ui.test.TestUiState
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.applyInsetMargin
import io.nekohasekai.sagernet.widget.applyInsetPadding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The profiles screen: one tab per group in `display_order` (the desktop main window's group tabs), the current tab is
 * `current_group`. Also the profile picker of [ProfileSelectActivity] / [SwitchActivity] ([forSelection]).
 * The pieces live in `ui/profiles`: pages and rows, the group menu, the multi-selection, the row menu, imports.
 */
class ConfigurationFragment : ToolbarFragment(R.layout.layout_group_list),
    Toolbar.OnMenuItemClickListener,
    SearchView.OnQueryTextListener,
    GroupRepo.Listener,
    ProfileManager.Listener {

    interface SelectCallback {
        fun returnProfile(profileId: Long)
    }

    companion object {
        private const val ARG_SELECT = "select"
        private const val ARG_PICKED_ID = "picked_id"
        private const val ARG_PICKED_GROUP = "picked_group"
        private const val ARG_TITLE = "title"
        private const val ARG_HIDE_AUTO_SELECTORS = "hide_auto_selectors"
        private const val STATE_SORT_DESCENDING = "sort_descending"

        /** Below this height (a phone in landscape) the header hides while the list scrolls down. */
        private const val COMPACT_HEIGHT_DP = 480

        /** From this width in landscape the test panel stands beside the list: 40 % of the width, 320 to 420 dp. */
        private const val SIDE_PANEL_MIN_WINDOW_DP = 600
        private const val SIDE_PANEL_FRACTION = 0.4f
        private const val SIDE_PANEL_MIN_DP = 320
        private const val SIDE_PANEL_MAX_DP = 420

        /**
         * The picker: a tap returns the profile to the activity, a [SelectCallback]; [selected] is highlighted.
         * [hideAutoSelectors] for slots that need a fixed server (front / landing proxy, chain hops).
         */
        fun forSelection(selected: ProxyEntity?, @StringRes titleRes: Int, hideAutoSelectors: Boolean = false) =
            ConfigurationFragment().apply {
                arguments = bundleOf(
                    ARG_SELECT to true,
                    ARG_PICKED_ID to (selected?.id ?: 0L),
                    ARG_PICKED_GROUP to (selected?.groupId ?: 0L),
                    ARG_TITLE to titleRes,
                    ARG_HIDE_AUTO_SELECTORS to hideAutoSelectors,
                )
            }
    }

    val select: Boolean get() = arguments?.getBoolean(ARG_SELECT) == true
    val hideAutoSelectors: Boolean get() = arguments?.getBoolean(ARG_HIDE_AUTO_SELECTORS) == true
    private val pickedId: Long get() = arguments?.getLong(ARG_PICKED_ID) ?: 0L
    private val pickedGroup: Long get() = arguments?.getLong(ARG_PICKED_GROUP) ?: 0L

    val alwaysShowAddress by lazy { DataStore.alwaysShowAddress }

    /** Double column (groupLayoutMode 1): the compact card, two columns at least (the width may give more). */
    var doubleColumn = false
        private set

    val groupMenu = GroupMenu(this)
    val selection = SelectionMode(this)
    val itemMenu = ProfileItemMenu(this)
    private val imports = ProfileImports(this)

    private lateinit var tabLayout: TabLayout
    private lateinit var pager: ViewPager2
    private lateinit var pagerAdapter: ProfilesPagerAdapter
    private var mediator: TabLayoutMediator? = null
    private lateinit var groupProgress: View
    private lateinit var runtimeStatus: TextView
    private lateinit var panelContainer: ViewGroup
    private var searchView: SearchView? = null
    private var testPanel: TestPanelController? = null
    private var header: ProfilesHeader? = null

    /** Wide landscape: the test panel stands at the end side, beside the list (read once per view). */
    private var sidePanel = false
    private var sidePanelShown = false

    private val lists = LinkedHashSet<ProfileListFragment>()
    private var shownGroupId = 0L
    private var query = ""

    /** A toolbar long press waiting for its tab's first load: group id to profile id. */
    private var pendingReveal: Pair<Long, Long>? = null

    var testState = TestUiState()
        private set
    private var subscriptionStates: Map<Long, Int> = emptyMap()
    private val busyGroups = HashSet<Long>()

    /** Height of the test panel above the navigation bar (the lists pad their bottom by at least it). */
    var panelHeight = 0
        private set

    private var selectedProxy = 0L
    private var currentProfile = 0L
    private var serviceStarted = false
    private val stateRequests = Channel<Unit>(Channel.CONFLATED)

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (selection.active) selection.finish() else clearSearch()
        }
    }

    /** The profile editor's "Move" names the target group in `editingGroup`: the screen follows the profile there. */
    private val editingGroupListener = object : OnPreferenceDataStoreChangeListener {
        override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
            if (key != Key.PROFILE_GROUP) return
            runOnMainDispatcher {
                val target = DataStore.editingGroup
                if (view == null || select || target <= 0L || target == currentGroupId) return@runOnMainDispatcher
                val index = pagerAdapter.indexOf(target)
                if (index >= 0) pager.setCurrentItem(index, false) else reloadGroups(switchTo = target)
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupMenu.sortDescending = savedInstanceState?.getBoolean(STATE_SORT_DESCENDING) == true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SORT_DESCENDING, groupMenu.sortDescending)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        doubleColumn = DataStore.groupLayoutMode == 1
        readProfileState()

        setUpToolbar()
        tabLayout = view.findViewById(R.id.group_tab)
        pager = view.findViewById(R.id.group_pager)
        groupProgress = view.findViewById(R.id.group_progress)
        runtimeStatus = view.findViewById(R.id.runtime_status)
        panelContainer = view.findViewById(R.id.test_panel_container)
        tabLayout.applyInsetPadding(horizontal = true)
        runtimeStatus.applyInsetPadding(horizontal = true)
        // the panel keeps its content above the navigation bar, the stats bar and the FAB itself
        panelContainer.applyInsetMargin(horizontal = true)
        val config = resources.configuration
        header = ProfilesHeader(
            view as ViewGroup, view.findViewById(R.id.toolbar), tabLayout, runtimeStatus,
            autoHide = config.screenHeightDp < COMPACT_HEIGHT_DP && !SagerNet.isTv,
            pinned = ::headerPinned,
            frozen = { lists.any { it.adapter.dragging } },
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { root, insets ->
            if (insets.isVisible(WindowInsetsCompat.Type.ime())) root.post { header?.update() }
            insets
        }
        sidePanel = !select && config.screenWidthDp >= SIDE_PANEL_MIN_WINDOW_DP &&
            config.screenWidthDp > config.screenHeightDp
        if (sidePanel) setUpSidePanel(view)

        pagerAdapter = ProfilesPagerAdapter(this)
        pager.adapter = pagerAdapter
        pager.offscreenPageLimit = 2
        applyGroups(GroupRepo.all(), initialGroupId())
        mediator = TabLayoutMediator(tabLayout, pager) { tab, position ->
            pagerAdapter.groups.getOrNull(position)?.let { tab.text = tabLabel(it) }
            tab.view.setOnLongClickListener {
                if (!select) pagerAdapter.groups.getOrNull(tab.position)?.let(::editGroup)
                true
            }
        }.also { it.attach() }
        pager.registerOnPageChangeCallback(pageCallback)
        onPageShown()

        GroupRepo.addListener(this)
        ProfileManager.addListener(this)
        DataStore.profileCacheStore.registerChangeListener(editingGroupListener)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val scope = viewLifecycleOwner.lifecycleScope
        ProfilesDbWatcher(
            onProfiles = { lists.forEach { it.adapter.reload() } },
            onGroups = { reloadGroups() },
        ).start(scope)
        scope.launch {
            for (request in stateRequests) {
                val (selected, current, started) = withContext(Dispatchers.IO) { profileStateSnapshot() }
                applyProfileState(selected, current, started)
            }
        }
        scope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { TestSessionClient.state.collect { onTestState(it) } }
                launch { AutoSelectorStatusLine.follow(this@ConfigurationFragment) }
                launch {
                    SubscriptionClient.states.collect {
                        subscriptionStates = it
                        updateGroupProgress()
                    }
                }
            }
        }

        if (!select) {
            testPanel = TestPanelController(this, panelContainer, side = sidePanel).apply {
                onSelectProfile = { id -> selectProfile(id, toggleOnTv = false) }
                onHeightChanged = { height ->
                    if (height != panelHeight) {
                        panelHeight = height
                        lists.forEach { it.updateBottomPadding() }
                    }
                }
                onVisibilityChanged = { visible ->
                    if (sidePanel) {
                        sidePanelShown = visible
                        updatePagerMargin()
                    }
                }
                floatingViews = (activity as? MainActivity)?.binding?.let { listOf(it.fab, it.stats) }.orEmpty()
                setProfileState(selectedProxy, if (serviceStarted) currentProfile else 0L)
            }
        }
    }

    override fun onDestroyView() {
        GroupRepo.removeListener(this)
        ProfileManager.removeListener(this)
        DataStore.profileCacheStore.unregisterChangeListener(editingGroupListener)
        testPanel = null
        panelHeight = 0
        header = null
        sidePanelShown = false
        pager.unregisterOnPageChangeCallback(pageCallback)
        mediator?.detach()
        mediator = null
        selection.finish()
        lists.clear()
        pendingReveal = null
        searchView = null
        super.onDestroyView()
    }

    override fun onKeyDown(ketCode: Int, event: KeyEvent): Boolean {
        // only pulls focus in when nothing has it: the toolbar, tabs and panel stay reachable by D-pad
        if (activity?.currentFocus == null) currentList()?.focusList()
        // a hidden header comes back on the way up, so the toolbar and the tabs stay reachable
        if (ketCode == KeyEvent.KEYCODE_DPAD_UP) header?.show()
        return super.onKeyDown(ketCode, event)
    }

    // ------------------------------------------------------------------------------------------------ toolbar

    private fun setUpToolbar() {
        val toolbar = toolbar ?: return
        toolbar.inflateMenu(R.menu.add_profile_menu)
        if (select) {
            toolbar.menu.findItem(R.id.action_add)?.isVisible = false
            toolbar.menu.findItem(R.id.action_misc)?.isVisible = false
            arguments?.getInt(ARG_TITLE)?.takeIf { it != 0 }?.let(toolbar::setTitle)
            setNavigationIcon(R.drawable.ic_navigation_close, R.string.navigation_close)
            toolbar.setNavigationOnClickListener { requireActivity().finish() }
        } else {
            toolbar.inflateMenu(R.menu.profile_selection_menu)
            TvControls.addServerSwitch(toolbar.menu, R.id.group_profiles_toolbar)
            groupMenu.setUp(toolbar.menu)
            imports.prepare(toolbar.menu)
            toolbar.setNavigationOnClickListener {
                if (selection.active) {
                    selection.finish()
                } else {
                    (activity as? MainActivity)?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
                }
            }
        }
        toolbar.setOnMenuItemClickListener(this)
        searchView = (toolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView)?.apply {
            setOnQueryTextListener(this@ConfigurationFragment)
            maxWidth = Int.MAX_VALUE
            setOnQueryTextFocusChangeListener { _, hasFocus ->
                if (!hasFocus && query.isEmpty()) clearSearch()
            }
        }
        // a tap on the toolbar brings the selected profile into view (or goes back to the top)
        toolbar.setOnClickListener { currentList()?.scrollToProfile(pickedOrSelectedId()) }
        toolbar.setOnLongClickListener {
            showActiveProfile()
            true
        }
    }

    /**
     * The toolbar's long press: the selected (picked) profile in whichever tab holds it. Without one, while searching
     * or selecting, or when its group has no tab, it does what the tap does.
     */
    private fun showActiveProfile() {
        val id = pickedOrSelectedId()
        val owner = viewLifecycleOwnerLiveData.value ?: return
        if (id <= 0L || query.isNotEmpty() || selection.active) {
            currentList()?.scrollToProfile(id)
            return
        }
        owner.lifecycleScope.launch {
            val groupId = withContext(Dispatchers.IO) { ProfileManager.getProfile(id)?.groupId } ?: 0L
            val index = pagerAdapter.indexOf(groupId)
            if (index < 0 || query.isNotEmpty() || selection.active) {
                currentList()?.scrollToProfile(id)
                return@launch
            }
            if (pager.currentItem != index) pager.setCurrentItem(index, false)
            val list = listFor(groupId)
            if (list != null && list.adapter.loaded) list.revealProfile(id) else pendingReveal = groupId to id
        }
    }

    /** The navigation icon in the toolbar's colours (the white theme draws it dark, as ToolbarFragment does). */
    private fun setNavigationIcon(@DrawableRes icon: Int, @StringRes description: Int) {
        val toolbar = toolbar ?: return
        toolbar.setNavigationIcon(icon)
        toolbar.setNavigationContentDescription(description)
        if (Theme.isWhiteTheme()) {
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_misc) {
            toolbar?.menu?.let(groupMenu::prepare)
            return false
        }
        return TvControls.onMenuItemClick(requireContext(), item) || selection.onMenuItemClick(item) ||
            imports.onMenuItemClick(item) || groupMenu.onMenuItemClick(item)
    }

    override fun onQueryTextChange(newText: String): Boolean {
        query = newText
        applyQuery()
        updateBackCallback()
        header?.update()
        return false
    }

    override fun onQueryTextSubmit(query: String): Boolean = false

    private fun clearSearch() {
        searchView?.apply {
            setQuery("", false)
            onActionViewCollapsed()
            clearFocus()
        }
    }

    private fun applyQuery() {
        val current = currentGroupId
        lists.forEach { it.adapter.setQuery(if (it.groupId == current) query else "") }
    }

    private fun updateBackCallback() {
        backCallback.isEnabled = selection.active || query.isNotEmpty()
    }

    // ------------------------------------------------------------------------------------------------ tabs

    private fun tabLabel(group: ProxyGroup): String =
        if (group.archive) getString(R.string.profiles_tab_archived, group.displayName()) else group.displayName()

    /** A tab's long press: the group editor, as the groups screen opens it. */
    private fun editGroup(group: ProxyGroup) {
        startActivity(Intent(requireContext(), GroupSettingsActivity::class.java).apply {
            putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
        })
    }

    private fun initialGroupId(): Long = if (select && pickedGroup > 0) pickedGroup else GroupRepo.currentId()

    /** The current tab's group (`current_group`). */
    val currentGroupId: Long
        get() = if (::pagerAdapter.isInitialized) pagerAdapter.groups.getOrNull(pager.currentItem)?.id ?: 0L else 0L

    fun currentGroup(): ProxyGroup? =
        if (::pagerAdapter.isInitialized) pagerAdapter.groups.getOrNull(pager.currentItem) else null

    /** Every group in tab order. */
    fun groups(): List<ProxyGroup> = if (::pagerAdapter.isInitialized) pagerAdapter.groups else emptyList()

    private fun reloadGroups(switchTo: Long = 0L) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val (groups, current) = withContext(Dispatchers.IO) { GroupRepo.all() to GroupRepo.currentId() }
            val target = switchTo.takeIf { id -> groups.any { it.id == id } }
                ?: currentGroupId.takeIf { id -> groups.any { it.id == id } }
                ?: current
            applyGroups(groups, target)
        }
    }

    private fun applyGroups(groups: List<ProxyGroup>, targetGroupId: Long) {
        if (!pagerAdapter.submit(groups)) {
            groups.forEachIndexed { index, group -> tabLayout.getTabAt(index)?.text = tabLabel(group) }
        }
        val single = groups.size < 2
        header?.tabsWanted = !single
        toolbar?.elevation = if (single) 0f else dp2px(4).toFloat()
        val index = pagerAdapter.indexOf(targetGroupId).takeIf { it >= 0 } ?: 0
        if (pager.currentItem != index) pager.setCurrentItem(index, false)
        onPageShown()
    }

    private val pageCallback = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = onPageShown()
    }

    /** show_group: current_group follows the tab (the leaving tab stores its scroll row when it pauses). */
    private fun onPageShown() {
        val group = currentGroup() ?: return
        if (group.id == shownGroupId) return
        shownGroupId = group.id
        if (!select) GroupRepo.setCurrent(group.id)
        selection.finish()
        applyQuery()
        updateGroupProgress()
        header?.show()
    }

    fun attachList(list: ProfileListFragment) {
        lists.add(list)
        list.adapter.setQuery(if (list.groupId == currentGroupId) query else "")
    }

    fun detachList(list: ProfileListFragment) {
        lists.remove(list)
    }

    fun listFor(groupId: Long): ProfileListFragment? = lists.firstOrNull { it.groupId == groupId }

    fun currentList(): ProfileListFragment? = listFor(currentGroupId)

    /** The profile a toolbar long press left for [groupId]'s list to show on its first load, 0 for none. */
    fun takePendingReveal(groupId: Long): Long {
        val (group, profile) = pendingReveal ?: return 0L
        if (group != groupId) return 0L
        pendingReveal = null
        return profile
    }

    /** A list scrolled by [dy] (0 after a layout): the current tab's hides and shows the header in a compact height. */
    fun onListScrolled(list: ProfileListFragment, dy: Int) {
        if (list.groupId == currentGroupId) header?.onListScrolled(list.list, dy)
    }

    /** The header is sliding: list scrolls now come from the lists moving on screen, not from the user. */
    val headerMoving: Boolean get() = header?.moving == true

    /** The header stays as it is during a row drag; a selection the drag left behind brings it back. */
    fun onDragEnded() {
        header?.update()
    }

    /** What keeps the header shown: the selection's actions, the search, the keyboard. */
    private fun headerPinned(): Boolean =
        selection.active || query.isNotEmpty() || searchView?.isIconified == false ||
            view?.let { ViewCompat.getRootWindowInsets(it) }?.isVisible(WindowInsetsCompat.Type.ime()) == true

    override suspend fun groupAdd(group: ProxyGroup) {
        onMainDispatcher { reloadGroups(switchTo = group.id) }
    }

    override suspend fun groupUpdated(group: ProxyGroup) {
        onMainDispatcher {
            if (!::pagerAdapter.isInitialized || view == null) return@onMainDispatcher
            val index = pagerAdapter.update(group)
            if (index >= 0) tabLayout.getTabAt(index)?.text = tabLabel(group)
        }
    }

    override suspend fun groupRemoved(groupId: Long) {
        onMainDispatcher { reloadGroups() }
    }

    override suspend fun groupUpdated(groupId: Long) = Unit

    override suspend fun groupsReordered() {
        onMainDispatcher { reloadGroups() }
    }

    // ------------------------------------------------------------------------------------------------ rows

    /** The profile the service uses (or would use); in the picker the passed-in one. */
    fun pickedOrSelectedId(): Long = if (select && pickedId > 0) pickedId else selectedProxy

    fun isSelectedProfile(id: Long) = id == pickedOrSelectedId()

    /** The profile the running service carries. */
    fun isStartedProfile(id: Long) = serviceStarted && id == selectedProxy && id == currentProfile

    fun onRowClick(profile: ProxyEntity) {
        when {
            select -> (activity as? SelectCallback)?.returnProfile(profile.id)
            selection.active -> selection.toggle(profile.id)
            else -> selectProfile(profile.id, toggleOnTv = true)
        }
    }

    /**
     * A long press starts the multi-selection; by touch it also picks the row up, so the same press can turn into a
     * reorder (the drag start selects the row). D-pad long presses only select.
     */
    fun onRowLongClick(holder: RecyclerView.ViewHolder, profile: ProxyEntity, touch: Boolean): Boolean {
        if (select) return false
        val list = listFor(profile.groupId)
        if (list?.adapter?.dragging == true) return true
        if (selection.active) {
            selection.toggle(profile.id)
            return true
        }
        if (touch && list?.startDrag(holder) == true) return true
        selection.start(profile)
        return true
    }

    /** A new selection reloads a running service; re-selecting on a TV toggles it (no FAB within reach). */
    private fun selectProfile(id: Long, toggleOnTv: Boolean) {
        val previous = selectedProxy
        applyProfileState(id, currentProfile, serviceStarted)
        runOnDefaultDispatcher {
            val last = DataStore.selectedProxy
            if (last != id) {
                DataStore.selectedProxy = id
                ProfileManager.postUpdate(last, noTraffic = true)
                if (DataStore.serviceState.canStop) SagerNet.reloadService()
            } else if (toggleOnTv && SagerNet.isTv && previous == id) {
                if (DataStore.serviceState.started) SagerNet.stopService() else SagerNet.startService()
            }
        }
    }

    fun onSelectionModeChanged(active: Boolean) {
        val toolbar = toolbar
        if (toolbar != null) {
            toolbar.menu.setGroupVisible(R.id.group_profiles_toolbar, !active)
            toolbar.menu.setGroupVisible(R.id.group_selection_toolbar, active)
            if (active) {
                searchView?.clearFocus()
                setNavigationIcon(R.drawable.ic_navigation_close, R.string.navigation_cancel_selection)
                toolbar.title = resources.getQuantityString(R.plurals.profiles_selected, selection.count, selection.count)
            } else {
                setNavigationIcon(R.drawable.ic_navigation_menu, R.string.navigation_open_drawer)
                toolbar.setTitle(R.string.app_name)
            }
        }
        if (::pager.isInitialized) pager.isUserInputEnabled = !active
        updateBackCallback()
        header?.update()
        lists.forEach { it.adapter.notifyState(null) }
    }

    fun onSelectionChanged(ids: Collection<Long>?) {
        toolbar?.title = resources.getQuantityString(R.plurals.profiles_selected, selection.count, selection.count)
        listFor(selection.groupId)?.adapter?.notifyState(ids)
    }

    fun setDoubleColumn(value: Boolean) {
        if (value == doubleColumn) return
        doubleColumn = value
        runOnDefaultDispatcher { DataStore.groupLayoutMode = if (value) 1 else 0 }
        lists.forEach { it.switchLayout() }
    }

    // ------------------------------------------------------------------------------------------------ state

    /** The selected / running profile changed (MainActivity, the service, the notification, the widgets). */
    fun refreshProfileState() {
        stateRequests.trySend(Unit)
    }

    private fun profileStateSnapshot() =
        Triple(DataStore.selectedProxy, DataStore.currentProfile, DataStore.serviceState.started)

    private fun readProfileState() {
        val (selected, current, started) = profileStateSnapshot()
        selectedProxy = selected
        currentProfile = current
        serviceStarted = started
    }

    private fun applyProfileState(selected: Long, current: Long, started: Boolean) {
        val changed = HashSet<Long>()
        if (selected != selectedProxy) changed += listOf(selectedProxy, selected)
        if (current != currentProfile) changed += listOf(currentProfile, current)
        if (started != serviceStarted) changed += listOf(selectedProxy, currentProfile, selected, current)
        selectedProxy = selected
        currentProfile = current
        serviceStarted = started
        testPanel?.setProfileState(selected, if (started) current else 0L)
        changed.removeAll { it <= 0L }
        if (changed.isNotEmpty()) lists.forEach { it.adapter.notifyState(changed) }
    }

    override suspend fun onAdd(profile: ProxyEntity) = Unit

    override suspend fun onUpdated(data: List<TrafficData>) = Unit

    override suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean) {
        if (noTraffic) refreshProfileState()
    }

    override suspend fun onRemoved(groupId: Long, profileId: Long) = Unit

    private fun onTestState(state: TestUiState) {
        testState = state
        lists.forEach { it.adapter.onTestState(state) }
    }

    /** The group's subscription update is queued / running, or a group action runs. */
    fun setGroupBusy(groupId: Long, busy: Boolean) {
        if (busy) busyGroups.add(groupId) else busyGroups.remove(groupId)
        updateGroupProgress()
    }

    private fun updateGroupProgress() {
        if (!::groupProgress.isInitialized) return
        val id = currentGroupId
        groupProgress.isVisible = subscriptionStates[id] != null || id in busyGroups
    }

    /**
     * The status line under the tabs, hidden while [text] is null. Wave C: the running auto-selector's summary
     * ("Auto selector on X (3 of 300 working), switched 2m ago"), [onClick] opening its details.
     */
    fun setRuntimeStatus(text: CharSequence?, onClick: (() -> Unit)? = null) {
        if (!::runtimeStatus.isInitialized) return
        runtimeStatus.text = text
        header?.statusWanted = !text.isNullOrEmpty()
        if (onClick != null) {
            runtimeStatus.setOnClickListener { onClick() }
        } else {
            runtimeStatus.setOnClickListener(null)
            runtimeStatus.isClickable = false
        }
    }

    // ------------------------------------------------------------------------------------------------ side panel

    /**
     * Wide landscape: the test panel stands at the end side, from under the header down to the bottom edge, 40 % of
     * the fragment's width within 320 to 420 dp; the pager gives it that width while it is shown.
     */
    private fun setUpSidePanel(root: View) {
        val density = resources.displayMetrics.density
        fun widthFor(fragmentWidth: Float) = (fragmentWidth * SIDE_PANEL_FRACTION)
            .coerceIn(SIDE_PANEL_MIN_DP * density, SIDE_PANEL_MAX_DP * density).roundToInt()
        panelContainer.updateLayoutParams<CoordinatorLayout.LayoutParams> {
            width = widthFor(resources.configuration.screenWidthDp * density)
            height = ViewGroup.LayoutParams.MATCH_PARENT
            gravity = Gravity.END
        }
        // the configuration's width only estimates the fragment's
        root.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val width = widthFor((right - left).toFloat())
            if (width != panelContainer.layoutParams.width) root.post {
                if (view == null) return@post
                panelContainer.updateLayoutParams<ViewGroup.LayoutParams> { this.width = width }
                updatePagerMargin()
            }
        }
    }

    /**
     * The pager ends where the side panel starts while it is shown. The lists keep their end inset padding, which then
     * lies under the panel's end inset margin: the rows end 4 dp before the panel, no double gap.
     */
    private fun updatePagerMargin() {
        val end = if (sidePanelShown) panelContainer.layoutParams.width else 0
        val params = pager.layoutParams as ViewGroup.MarginLayoutParams
        if (params.marginEnd == end) return
        params.marginEnd = end
        pager.layoutParams = params
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** Background work that must finish even when the screen goes away. */
    fun launchIo(block: suspend CoroutineScope.() -> Unit) = runOnDefaultDispatcher(block)

    /** Back on the main thread, only while the view exists. */
    suspend fun onUi(block: ConfigurationFragment.() -> Unit) {
        val fragment = this
        onMainDispatcher { if (fragment.isAdded && fragment.view != null) fragment.block() }
    }
}
