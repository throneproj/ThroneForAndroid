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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.widget.AlertDialogFragment
import io.nekohasekai.sagernet.widget.Empty
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.QuickToggleShortcut
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.json.JsonEditorActivity
import io.nekohasekai.sagernet.ui.json.ProfileJson
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
                runOnDefaultDispatcher {
                    (requireActivity() as ProfileSettingsActivity<*>).saveAndExit()
                }
            }
            setNegativeButton(R.string.discard) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long, val name: String) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(resources.getQuantityString(R.plurals.confirm_remove_profiles, 1, 1))
            setMessage(arg.name)
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
            if (DataStore.editingId != 0L // not new profile
                && SagerDatabase.groupDao.getById(DataStore.editingGroup)?.isSubscription == false
                && SagerDatabase.groupDao.allGroups().count { !it.isSubscription } > 1 // have other basic group
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
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId, DataStore.editingGroup, name))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
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
                val view = LinearLayout(context).apply {
                    val ent = activity.proxyEntity!!
                    orientation = LinearLayout.VERTICAL

                    SagerDatabase.groupDao.allGroups()
                        .filter { !it.isSubscription && it.id != ent.groupId }
                        .forEach { group ->
                            LayoutGroupItemBinding.inflate(layoutInflater, this, true).apply {
                                edit.isVisible = false
                                options.isVisible = false
                                groupName.text = group.displayName()
                                groupUpdate.text = getString(R.string.move)
                                groupUpdate.setOnClickListener {
                                    runOnDefaultDispatcher {
                                        val newGroupId = group.id
                                        ProfileManager.moveToGroup(listOf(ent.id), newGroupId)
                                        DataStore.editingGroup = newGroupId // post switch animation
                                        runOnMainDispatcher {
                                            activity.finish()
                                        }
                                    }
                                }
                            }
                        }
                }
                val scrollView = ScrollView(context).apply {
                    addView(view)
                }
                MaterialAlertDialogBuilder(activity).setView(scrollView).show()
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
