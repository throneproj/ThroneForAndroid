package io.nekohasekai.sagernet.ui.test

import android.content.Context
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.LruCache
import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SettingsRegistry
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.outbound.json.JsonInput
import io.nekohasekai.sagernet.outbound.json.JsonObject

/**
 * A profile's stored service check (`profiles.service_checks`, ServiceChecks::ResultJson) for display
 * (ServiceCheckHtml.cpp). Parsed documents are cached; the services turned off in the settings are left out.
 */
internal object ServiceResults : OnPreferenceDataStoreChangeListener {

    const val STATUS_AVAILABLE = "available"
    const val STATUS_PARTIAL = "partial"
    const val STATUS_RESTRICTED = "restricted"
    const val STATUS_BLOCKED = "blocked"
    const val STATUS_UNKNOWN = "unknown"
    const val STATUS_FAILED = "failed"

    private const val ROW_ICON_DP = 16
    private const val ROW_ICON_GAP_DP = 4

    class Check(
        val id: String,
        val name: String,
        val category: String,
        val status: String,
        val region: String,
        val note: String,
    )

    class Category(val id: String, val name: String)

    /** One document: [error] set when the whole check failed, then nothing else is filled. */
    class Document(
        val checkedAt: Long,
        val error: String,
        val categories: List<Category>,
        val checks: List<Check>,
        val ip: List<JsonObject>,
    )

    private val parsed = LruCache<String, Document>(200)

    @Volatile
    private var disabledCache: Set<String>? = null

    init {
        DataStore.configurationStore.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == SettingsRegistry.SERVICE_CHECK_DISABLED.key) disabledCache = null
    }

    /** service_check_disabled, read once until it is written. */
    val disabled: Set<String>
        get() = disabledCache ?: SettingsRegistry.SERVICE_CHECK_DISABLED.read(DataStore.configurationStore).toHashSet()
            .also { disabledCache = it }

    fun invalidateDisabled() {
        disabledCache = null
    }

    /** Null for an empty or unreadable document; a sweep over a whole group passes [remember] false. */
    fun parse(document: String, remember: Boolean = true): Document? {
        if (document.isEmpty()) return null
        parsed[document]?.let { return it }
        val root = JsonInput.parseObjectOrNull(document)?.takeIf { it.isNotEmpty() } ?: return null
        val result = Document(
            checkedAt = root.integer("at"),
            error = root.string("error"),
            categories = root.array("cats").mapNotNull { value ->
                (value as? JsonObject)?.let { Category(it.string("id"), it.string("name")) }
            },
            checks = root.array("checks").mapNotNull { value ->
                (value as? JsonObject)?.let {
                    Check(
                        it.string("id"), it.string("name"), it.string("cat"), it.string("status"), it.string("region"),
                        it.string("note"),
                    )
                }
            },
            ip = root.array("ip").mapNotNull { it as? JsonObject },
        )
        if (remember) parsed.put(document, result)
        return result
    }

    /** ServiceCheckHtml::items: the shown checks of a check that ran, in their stored order. */
    fun shown(document: String): List<Check> {
        val result = parse(document)?.takeIf { it.error.isEmpty() } ?: return emptyList()
        val disabled = disabled
        return if (disabled.isEmpty()) result.checks else result.checks.filter { it.id !in disabled }
    }

    fun statusText(context: Context, status: String): String = when (status) {
        STATUS_AVAILABLE -> context.getString(R.string.service_status_available)
        STATUS_PARTIAL -> context.getString(R.string.service_status_partial)
        STATUS_RESTRICTED -> context.getString(R.string.service_status_restricted)
        STATUS_BLOCKED -> context.getString(R.string.service_status_blocked)
        STATUS_UNKNOWN -> context.getString(R.string.service_status_unknown)
        STATUS_FAILED -> context.getString(R.string.service_status_failed)
        else -> status
    }

    /** The definitions name categories in English; the known ones get a translation. */
    fun categoryName(context: Context, id: String, name: String): String = when (id) {
        "ai" -> context.getString(R.string.service_category_ai)
        "streaming" -> context.getString(R.string.service_category_streaming)
        "social" -> context.getString(R.string.service_category_social)
        "reputation" -> context.getString(R.string.service_category_reputation)
        "" -> context.getString(R.string.service_category_other)
        else -> name.ifEmpty { id }
    }

    /** The desktop's Test column strip: one icon per check with a break opportunity after each. */
    fun iconStrip(context: Context, checks: List<Check>): CharSequence {
        val size = dp2px(ROW_ICON_DP)
        val gap = dp2px(ROW_ICON_GAP_DP)
        val text = SpannableStringBuilder()
        for (check in checks) {
            val start = text.length
            text.append(OBJECT)
            text.setSpan(
                ServiceIcons.IconSpan(
                    ServiceIcons.drawable(context, check.id, check.name, ServiceIcons.stateOf(check.status), size), gap,
                ),
                start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            text.append(ZWSP)
        }
        return text
    }

    /** The strip's tooltips, read out: "ChatGPT: Available, Netflix: Blocked". */
    fun spoken(context: Context, checks: List<Check>): String =
        checks.joinToString(", ") { "${it.name}: ${statusText(context, it.status)}" }

    private const val OBJECT = "￼"
    private const val ZWSP = "​"
}
