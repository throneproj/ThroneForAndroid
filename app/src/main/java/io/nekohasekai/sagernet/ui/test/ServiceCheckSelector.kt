package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.preference.Preference
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.CoreServiceClient
import io.nekohasekai.sagernet.bg.test.ServiceCatalog
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.tryToShow
import io.nekohasekai.sagernet.outbound.json.JsonInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns Service Check's services on and off (ServiceCheckSelector.cpp), one section per category, into
 * service_check_disabled. The main process has no core: the services are the ones :bg last described, asked for
 * (describeServiceChecks) until it has described any.
 */
internal object ServiceCheckSelector {

    const val PREFERENCE_KEY = "service_check_services"

    private const val ICON_DP = 20

    /** Settings › Testing: the summary counts the services turned on, a click opens the selector. */
    fun bind(preference: Preference) {
        fun update(catalog: ServiceCatalog?) {
            preference.summary = summary(preference.context, catalog)
        }
        val saved = ServiceCatalog.saved()
        if (saved != null) {
            update(saved)
        } else {
            preference.summary = null
            runOnMainDispatcher { update(described()) }
        }
        preference.setOnPreferenceClickListener {
            show(it.context, ::update)
            true
        }
    }

    private fun summary(context: Context, catalog: ServiceCatalog?): String {
        if (catalog == null) return context.getString(R.string.service_check_services_unknown)
        val disabled = ServiceResults.disabled
        val on = catalog.checks.count { it.id !in disabled }
        return when (on) {
            catalog.checks.size -> context.getString(R.string.service_check_services_all)
            0 -> context.getString(R.string.service_check_none_selected)
            else -> context.getString(R.string.service_check_services_count, on, catalog.checks.size)
        }
    }

    fun show(context: Context, onSaved: ((ServiceCatalog) -> Unit)? = null) {
        val saved = ServiceCatalog.saved()
        if (saved != null) return show(context, saved, onSaved)
        runOnMainDispatcher {
            val catalog = described()
            if (catalog != null) {
                show(context, catalog, onSaved)
            } else {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.service_check_services)
                    .setMessage(R.string.service_check_services_unknown)
                    .setPositiveButton(android.R.string.ok, null)
                    .create()
                    .tryToShow()
            }
        }
    }

    /** What :bg describes, which also writes the list [ServiceCatalog.saved] reads; null when it cannot. */
    private suspend fun described(): ServiceCatalog? {
        val list = try {
            withContext(Dispatchers.IO) { CoreServiceClient.call { it.describeServiceChecks() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logs.w(e)
            return null
        }
        return JsonInput.parseObjectOrNull(list.orEmpty())?.let { ServiceCatalog.parse(it) }
            ?.takeIf { it.checks.isNotEmpty() }
    }

    private fun show(context: Context, catalog: ServiceCatalog, onSaved: ((ServiceCatalog) -> Unit)?) {
        val stored = SettingsRegistry.SERVICE_CHECK_DISABLED.read(DataStore.configurationStore)
        val boxes = LinkedHashMap<String, CheckBox>()
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(24), dp2px(8), dp2px(24), dp2px(8))
        }
        val listed = catalog.categories.mapTo(HashSet()) { it.id }
        // A check whose category the definitions do not list still gets a section, the last one.
        val sections = catalog.categories.map { it.id to ServiceResults.categoryName(context, it.id, it.name) } +
            ("" to ServiceResults.categoryName(context, "", ""))
        val size = dp2px(ICON_DP)
        for ((id, title) in sections) {
            val members = catalog.checks.filter { (if (it.category in listed) it.category else "") == id }
            if (members.isEmpty()) continue
            layout.addView(TextView(context).apply {
                TextViewCompat.setTextAppearance(this, HEADING)
                text = title
                setPadding(0, dp2px(12), 0, dp2px(2))
            })
            for (check in members) {
                if (check.id in boxes) continue
                // A compound drawable would overlap the box: the logo goes into the text.
                val label = SpannableStringBuilder("￼").apply {
                    val icon = ServiceIcons.drawable(context, check.id, check.name, ServiceIcons.State.PLAIN, size)
                    setSpan(ServiceIcons.IconSpan(icon, dp2px(8)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append(check.name)
                }
                val box = MaterialCheckBox(context).apply {
                    text = label
                    contentDescription = check.name
                    isChecked = check.id !in stored
                }
                boxes[check.id] = box
                layout.addView(box)
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.service_check_services)
            .setView(NestedScrollView(context).apply { addView(layout) })
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val disabled = disabledIds(stored, boxes.keys, boxes.filterValues { !it.isChecked }.keys)
                if (disabled != stored) {
                    SettingsRegistry.SERVICE_CHECK_DISABLED.write(DataStore.configurationStore, disabled)
                    ServiceResults.invalidateDisabled()
                }
                onSaved?.invoke(catalog)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .tryToShow()
    }

    /**
     * The unchecked ids plus the stored ones the definitions do not list; earlier choices keep their order, so an
     * untouched list saves unchanged.
     */
    private fun disabledIds(stored: List<String>, listed: Set<String>, unchecked: Set<String>): List<String> {
        val ids = stored.filterTo(ArrayList()) { it !in listed || it in unchecked }
        for (id in unchecked) if (id !in ids) ids.add(id)
        return ids
    }

    private val HEADING = com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle2
}
