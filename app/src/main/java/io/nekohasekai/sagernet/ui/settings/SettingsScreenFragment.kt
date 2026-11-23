package io.nekohasekai.sagernet.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.annotation.XmlRes
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.ui.ValidationForm
import io.nekohasekai.sagernet.validation.Rule
import moe.matsuri.nb4a.ui.EditConfigPreference

/**
 * One settings sub-screen bound to the configuration store. Sub-classes wire their preferences in [bind]; values
 * that shape the generated config prompt a service reload after a change.
 */
abstract class SettingsScreenFragment(@XmlRes private val preferences: Int) : PreferenceFragmentCompat() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private var form: ValidationForm? = null

    /**
     * The screen's checks (the desktop dialogs' Validation::Form). The screen saves on every change, so errors refuse
     * the value at commit ([gateText], [gateInt], [ValidationForm.Field.gate]) and warnings are shown on the row. It
     * starts after [bind] with everything marked, as the desktop dialogs mark everything when they open.
     */
    protected val validation: ValidationForm
        get() = form ?: ValidationForm(this).also { form = it }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        beforeInflate()
        setPreferencesFromResource(preferences, rootKey)
        bind()
        form?.let {
            it.start()
            it.markAll()
        }
    }

    protected open fun beforeInflate() {}

    protected abstract fun bind()

    override fun onResume() {
        super.onResume()
        // The JSON editors write from their own activity.
        preferenceScreen?.let { screen ->
            for (i in 0 until screen.preferenceCount) refreshEditors(screen.getPreference(i))
        }
    }

    private fun refreshEditors(preference: Preference) {
        if (preference is EditConfigPreference) preference.notifyChanged()
        if (preference is PreferenceGroup) {
            for (i in 0 until preference.preferenceCount) refreshEditors(preference.getPreference(i))
        }
    }

    protected fun <T : Preference> pref(key: String): T = findPreference(key)!!

    protected fun toast(@StringRes message: Int, vararg args: Any) {
        Toast.makeText(requireContext(), getString(message, *args), Toast.LENGTH_SHORT).show()
    }

    /** A change of any of [keys] prompts a service reload. */
    protected fun reloadOn(vararg keys: String) {
        for (key in keys) {
            findPreference<Preference>(key)?.setOnPreferenceChangeListener { _, _ ->
                needReload()
                true
            }
        }
    }

    /**
     * Text input of [key] is trimmed and checked with [valid]: a rejected value shows [invalid] (formatted with the
     * value) and is not saved; an accepted one prompts a service reload when [reload].
     */
    protected fun checkText(
        key: String,
        @StringRes invalid: Int,
        reload: Boolean = true,
        valid: (String) -> Boolean,
    ) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty()
            val value = raw.trim()
            if (!valid(value)) {
                toast(invalid, value)
                return@setOnPreferenceChangeListener false
            }
            if (reload) needReload()
            if (value != raw) {
                // setText persists without calling the listener again.
                preference.text = value
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    /** A setting stored as an integer: blank input stores [blankAs], anything else must pass [valid]. */
    protected fun checkInt(
        key: String,
        @StringRes invalid: Int,
        reload: Boolean = true,
        blankAs: Int? = null,
        valid: (Int) -> Boolean,
    ) {
        val preference = findPreference<EditTextPreference>(key) ?: return
        preference.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        preference.setOnPreferenceChangeListener { _, newValue ->
            val raw = newValue?.toString().orEmpty().trim()
            val value = if (raw.isEmpty() && blankAs != null) blankAs else raw.toIntOrNull()
            if (value == null || !valid(value)) {
                toast(invalid, raw)
                return@setOnPreferenceChangeListener false
            }
            if (reload) needReload()
            if (value.toString() != newValue?.toString()) {
                preference.text = value.toString()
                return@setOnPreferenceChangeListener false
            }
            true
        }
    }

    /**
     * [checkText] refusing with [rule]'s own message; [valid] (with the [invalid] toast) only catches what the stored
     * setting cannot hold although the rule accepts it.
     */
    protected fun gateText(
        key: String,
        rule: Rule,
        reload: Boolean = true,
        @StringRes invalid: Int = R.string.invalid_value,
        valid: (String) -> Boolean = { true },
    ): ValidationForm.Field {
        checkText(key, invalid, reload, valid)
        return validation.bind(key, rule).gate()
    }

    /** [checkInt] refusing with [rule]'s own message; blank stores [blankAs] when the rule lets it through. */
    protected fun gateInt(key: String, rule: Rule, reload: Boolean = true, blankAs: Int? = null): ValidationForm.Field {
        checkInt(key, R.string.invalid_number, reload, blankAs) { true }
        return validation.bind(key, rule).gate()
    }

    /**
     * SettingsService::Update's port check on a listener switch: refuses (with the dialog) when [change] would give two
     * local listeners one port.
     */
    protected fun refusedByPorts(preference: Preference, change: Pair<String, String>): Boolean {
        val message = SettingChecks.portConflict(mapOf(change))
        if (message.isEmpty()) return false
        refuse(preference, message)
        return true
    }

    /** The refusal dialog of a gate, for checks a [ValidationForm.Field.gate] cannot express. */
    protected fun refuse(preference: Preference, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(preference.title?.toString()?.ifEmpty { null } ?: getString(R.string.validation_form_title))
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        const val ARG_TITLE = "title"
    }
}
