package io.nekohasekai.sagernet.ui.profile

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.widget.AlertDialogFragment
import io.nekohasekai.sagernet.widget.Empty
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupRepo
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SettingsMapper
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.group.SubscriptionClient
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.ui.json.JsonEditorActivity
import io.nekohasekai.sagernet.ui.json.ProfileJson
import io.nekohasekai.sagernet.ui.profiles.SelectionMode
import io.nekohasekai.sagernet.validation.Issue
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.parcelize.Parcelize
import kotlin.properties.Delegates

/**
 * The profile editor: the entity's [Outbound] is loaded into the profile cache store by `T.init()`, edited through
 * the preference screen, read back by `T.serialize()` and stored with `ProxyEntity.putOutbound`.
 */
@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : Outbound>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ThemedActivity(resId), OnPreferenceDataStoreChangeListener {

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.save) { _, _ ->
                (requireActivity() as ProfileSettingsActivity<*>).requestSave()
            }
            setNegativeButton(R.string.discard) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long, val name: String, val warning: String = "") : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(resources.getQuantityString(R.plurals.confirm_remove_profiles, 1, 1))
            setMessage(arg.name + arg.warning)
            setPositiveButton(R.string.delete) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteProfile(arg.groupId, arg.profileId)
                }
                requireActivity().finish()
            }
            setNegativeButton(android.R.string.cancel, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"

        /** Result extra: the saved profile is one the running config uses, so the service needs a restart. */
        const val EXTRA_RESTART_NEEDED = "restartNeeded"

        /** Profile cache key holding the whole ExportToJson while the "Edit as JSON" editor is open. */
        const val KEY_RAW_JSON = "serverRawJson"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    /** Whether the "Edit as JSON" action (the whole ExportToJson in the JSON editor) is offered. */
    protected open val supportsRawJson: Boolean = true

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(DataStore.editingId) }
    protected var isSubscription by Delegates.notNull<Boolean>()

    /** The outbound under edit: a fresh one for a new profile, the entity's parsed one otherwise. */
    lateinit var editingOutbound: T

    /** Set when the preference screen is rebuilt from edited JSON, so the rebuilt screen starts dirty. */
    private var dirtyAfterRebuild = false
    private var rawJsonSynced: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        onBackPressedDispatcher.addCallback(this, unsavedChangesCallback)

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    DataStore.editingGroup = DataStore.currentGroupId()
                    editingOutbound = createEntity()
                } else {
                    val entity = proxyEntity
                    val loaded = entity?.outbound
                    if (entity == null || loaded == null || !createEntity().javaClass.isInstance(loaded)) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    DataStore.editingGroup = entity.groupId
                    editingOutbound = loaded as T
                }
                editingOutbound.init()

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()
                }
            }
        } else {
            isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
        }

    }

    protected fun ensureEditingOutbound(): T {
        if (!::editingOutbound.isInitialized) {
            editingOutbound = if (DataStore.editingId == 0L) createEntity()
            else (proxyEntity?.outbound as? T) ?: createEntity()
        }
        return editingOutbound
    }

    /** The screen's validation form; null until the preferences are created. */
    protected var form: ValidationForm? = null

    private val validationContext by lazy { SettingsMapper.buildContext() }

    /** Issue.field (the desktop key) → this screen's preference key, where [ValidationForm.keyFor] cannot tell. */
    protected open fun issueKey(field: String): String? = null

    /**
     * The editor's own checks (the desktop's bindValidation): text the model no longer sees once parsed, and the
     * editor-only rules. [validationOutbound]'s Validate already covers everything the model checks.
     */
    protected open fun ValidationForm.bindValidation() {}

    /** A copy of the outbound with the screen's pending edits, for Validate; never saved. */
    protected open fun validationOutbound(): T {
        val copy = createEntity()
        copy.parseFromJson(ensureEditingOutbound().exportToJson())
        copy.serialize()
        return copy
    }

    /**
     * Everything on the screen that [validationOutbound]'s Validate reads, for a screen whose check is costly: its
     * issues are kept while this stays equal. Null (the default) validates on every check.
     */
    protected open fun validationInputs(): Any? = null

    private var modelCache: Pair<Any, List<Issue>>? = null

    private fun modelIssues(): List<Issue> {
        val inputs = validationInputs()
        modelCache?.let { (cachedInputs, cached) -> if (inputs != null && cachedInputs == inputs) return cached }
        val issues = ArrayList<Issue>()
        try {
            validationOutbound().validate(issues, validationContext)
        } catch (e: Exception) {
            Logs.w(e)
        }
        modelCache = inputs?.let { it to issues }
        return issues
    }

    private fun attachValidation(fragment: PreferenceFragmentCompat) {
        modelCache = null
        val validation = ValidationForm(fragment)
        // As on the desktop, an error on a field without a row still blocks saving; the JSON editor reaches it.
        if (supportsRawJson) validation.unplacedFix = getString(R.string.edit_as_json)
        validation.addModelIssues(::modelIssues, ::issueKey)
        validation.bindProfileBlocks(ensureEditingOutbound())
        validation.bindValidation()
        validation.start()
        // dialog_edit_profile.cpp: a loaded profile shows its problems at once, a new one only as fields are edited.
        if (DataStore.editingId != 0L || dirtyAfterRebuild) validation.markAll()
        form = validation
    }

    /** The save gate (Validation::Form::validateAll); errors keep the editor open, warnings never do. */
    fun requestSave() {
        if (form?.validateAll() == false) return
        runOnDefaultDispatcher { saveAndExit() }
    }

    open suspend fun saveAndExit() {
        val outbound = ensureEditingOutbound()
        outbound.serialize()

        val editingId = DataStore.editingId
        if (editingId == 0L) {
            ProfileManager.createProfile(DataStore.editingGroup, outbound)
        } else {
            val entity = proxyEntity
            if (entity == null) {
                finish()
                return
            }
            // DialogEditProfile::accept: the stored test results describe the old server. Parsed afresh, as the
            // edited outbound is the entity's cached one.
            val stored = ProxyEntity.parseOutbound(entity.type, entity.outboundJson)
            if (stored.getAddress() != outbound.getAddress() || stored.getPort() != outbound.getPort()) {
                ProfileManager.clearTestResults(listOf(entity.id))
            }
            // dialog_edit_profile.cpp:947-951: saving a profile the running config uses asks for a restart.
            if (ProfileManager.updateOutbound(entity.putOutbound(outbound)) && ProfileManager.runningUses(entity.id)) {
                setResult(RESULT_OK, Intent().putExtra(EXTRA_RESTART_NEEDED, true))
            }
        }
        finish()

    }

    private val rawJsonEditor = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        applyRawJsonFromCache()
    }

    /** Opens the JSON editor on the ExportToJson of the outbound with the screen's pending edits applied. */
    fun openRawJsonEditor() {
        val outbound = ensureEditingOutbound()
        outbound.serialize()
        val text = ProfileJson.text(outbound)
        DataStore.profileCacheStore.putString(KEY_RAW_JSON, text)
        rawJsonSynced = text
        rawJsonEditor.launch(
            JsonEditorActivity.intent(
                this,
                KEY_RAW_JSON,
                schemaRoots = ProfileJson.schemaRoots(outbound),
                relaxations = ProfileJson.relaxations(outbound),
                title = getString(R.string.edit_as_json),
            )
        )
    }

    /** A changed JSON text replaces the outbound and the preference screen is rebuilt from it. */
    private fun applyRawJsonFromCache() {
        val text = DataStore.profileCacheStore.getString(KEY_RAW_JSON) ?: return
        if (text == rawJsonSynced) return
        val parsed = createEntity()
        val obj = JsonInput.parseObject(text)
        if (obj.isEmpty() || !parsed.parseFromJson(obj)) {
            Toast.makeText(this, R.string.raw_json_invalid, Toast.LENGTH_SHORT).show()
            return
        }
        rawJsonSynced = text
        runOnDefaultDispatcher {
            editingOutbound = parsed
            editingOutbound.init()
            onMainDispatcher {
                dirtyAfterRebuild = true
                supportFragmentManager.beginTransaction()
                    .replace(R.id.settings, MyPreferenceFragmentCompat())
                    .commit()
            }
        }
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        // Like the list: the started profile can be edited, not deleted.
        menu.findItem(R.id.action_delete)?.isVisible =
            DataStore.editingId == 0L || ProfileManager.runningProfileId() != DataStore.editingId
        menu.findItem(R.id.action_move)?.apply {
            // The picker always offers "New group".
            if (DataStore.editingId != 0L // not new profile
                && SagerDatabase.groupDao.getById(DataStore.editingGroup)?.isSubscription == false
            ) isVisible = true
        }
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && DataStore.editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        menu.findItem(R.id.action_edit_raw_json)?.isVisible = supportsRawJson
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    private val unsavedChangesCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (DataStore.dirty) {
                UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            } else {
                finish()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                    attachValidation(this@MyPreferenceFragmentCompat)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                DataStore.dirty = dirtyAfterRebuild
                dirtyAfterRebuild = false
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        @SuppressLint("CheckResult")
        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    val name = activity?.proxyEntity?.displayName().orEmpty()
                    val warning = GroupRepo.hopWarning(listOf(DataStore.editingId))
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId, DataStore.editingGroup, name, warning))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                activity?.requestSave()
                true
            }

            R.id.action_edit_raw_json -> {
                activity?.openRawJsonEditor()
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            activity, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        context, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val groups = SagerDatabase.groupDao.allGroups()
                SelectionMode.pickMoveTarget(activity, activity.lifecycleScope, groups, ent.groupId) { target, created ->
                    val refusal = ProfileManager.moveToGroup(listOf(ent.id), target.id, SubscriptionClient.states.value.keys).refusal
                    if (refusal == null) DataStore.editingGroup = target.id // post switch animation
                    onMainDispatcher {
                        if (refusal == null) {
                            if (created) SelectionMode.editGroup(activity, target)
                            activity.finish()
                        } else {
                            Toast.makeText(activity, SubscriptionClient.refusalText(refusal, move = true), Toast.LENGTH_LONG).show()
                        }
                    }
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "•".repeat(text.length)
            }
        }

    }

}
