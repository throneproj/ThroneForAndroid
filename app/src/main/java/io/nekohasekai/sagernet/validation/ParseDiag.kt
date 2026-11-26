package io.nekohasekai.sagernet.validation

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.json.JsonArray
import io.nekohasekai.sagernet.outbound.json.JsonNull
import io.nekohasekai.sagernet.outbound.link.LinkQuery

/**
 * Configs::ParseDiag (include/configs/ParseDiagnostics.h): opt-in import diagnostics. The innermost live [Scope] on the
 * current thread collects every report; without one (database loads, clones) every call is a no-op, so the parsers
 * can report unconditionally and only an import pays for it. Parsing never suspends, so a thread-local is enough.
 */
object ParseDiag {

    private val current = ThreadLocal<Scope?>()

    class Scope : AutoCloseable {
        private val collected = ArrayList<Issue>()
        private val previous: Scope? = current.get()

        init {
            current.set(this)
        }

        fun hasError(): Boolean = Validation.hasError(collected)

        fun issues(): List<Issue> = collected

        fun take(): List<Issue> = ArrayList(collected).also { collected.clear() }

        internal fun add(issue: Issue) {
            collected.add(issue)
        }

        override fun close() {
            current.set(previous)
        }
    }

    @JvmStatic
    fun active(): Boolean = current.get() != null

    @JvmStatic
    fun report(issue: Issue) {
        current.get()?.add(issue)
    }

    @JvmStatic
    @JvmOverloads
    fun warn(field: String, message: String, value: String = "") {
        current.get()?.add(Issue(Severity.Warning, field, message, value))
    }

    @JvmStatic
    @JvmOverloads
    fun error(field: String, message: String, value: String = "") {
        val scope = current.get() ?: return
        scope.add(Issue(Severity.Error, field, message.ifBlank { vtr(R.string.validation_value_invalid) }, value))
    }

    /** Reports a non-empty validation [message] and returns whether the value is acceptable. */
    @JvmStatic
    @JvmOverloads
    fun accept(field: String, message: String, value: String = "", severity: Severity = Severity.Warning): Boolean {
        if (message.isEmpty()) return true
        current.get()?.add(Issue(severity, field, message, value))
        return false
    }

    /** Parser::scoped (SubscriptionParser.cpp): the scope covers [parse] only, its issues are appended to [issues]. */
    inline fun <T> scoped(issues: MutableList<Issue>, parse: () -> T): T {
        val scope = Scope()
        try {
            return parse()
        } finally {
            issues.addAll(scope.take())
            scope.close()
        }
    }
}

/** AcceptOptional (utils.cpp): import gate for an optional value; a non-empty rule [message] is reported as ignored. */
fun acceptOptional(field: String, message: String, value: String = ""): Boolean {
    if (message.isEmpty()) return true
    ParseDiag.warn(field, vtr(R.string.validation_value_ignored, message), value)
    return false
}

private val LEGACY_COMBO_TEXT = mapOf(
    "нативный" to "native",
    "bbr v2" to "bbr2",
    "вариант bbr" to "bbr_variant",
    "bbr变体" to "bbr_variant",
    "标准bbr" to "bbr_standard",
)

/** LegacyComboValue (utils.cpp): older builds stored translated menu text; maps it back to one of [identifiers], else the trimmed value. */
fun legacyComboValue(value: String, identifiers: List<String>): String {
    val folded = value.trim().lowercase()
    for (candidate in listOf(LEGACY_COMBO_TEXT[folded] ?: folded, folded.replace(' ', '_'), folded.replace(" ", ""))) {
        if (candidate in identifiers) return candidate
    }
    return value.trim()
}

/** ReadQueryInt (utils.cpp): the item as a whole number in [min, max], else [current] (reported when present but unusable). */
fun readQueryInt(query: LinkQuery, key: String, current: Int, min: Int = 0, max: Int = Int.MAX_VALUE): Int {
    if (!query.has(key)) return current
    val text = query.value(key).trim()
    if (!acceptOptional(key, Validation.int(text, min.toLong(), max.toLong()), text)) return current
    return Validation.toInt(text)?.toInt() ?: current
}

/** JsonStringList (utils.cpp): sing-box Listable values, one string or an array of strings; other JSON types are reported and skipped. */
fun jsonStringList(value: Any?, field: String): MutableList<String> {
    if (value is String) return mutableListOf(value)
    if (value == null || value === JsonNull) return ArrayList()
    if (value !is JsonArray) {
        ParseDiag.warn(field, vtr(R.string.validation_json_list_type))
        return ArrayList()
    }
    val items = ArrayList<String>()
    var skipped = false
    for (item in value) {
        if (item is String) items.add(item) else skipped = true
    }
    if (skipped) ParseDiag.warn(field, vtr(R.string.validation_json_list_items))
    return items
}
