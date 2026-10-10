package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SubscriptionOptions
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.outbound.Outbound
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

/**
 * Android-only (#53, #15): the include/exclude name filters of a subscription, v1.6.4's filterMode/filterRegex as
 * two patterns usable together. Matched like the auto selector's name filter (AutoSelectorPlan.buildFilters).
 */
internal object SubscriptionNameFilter {

    private const val FLAGS = Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE

    /** Why [pattern] does not compile; null when it is empty or valid. */
    fun error(pattern: String): String? {
        if (pattern.isEmpty()) return null
        return try {
            Pattern.compile(pattern, FLAGS)
            null
        } catch (e: PatternSyntaxException) {
            e.description ?: e.message.orEmpty()
        }
    }

    /** The servers both filters keep; an invalid pattern filters nothing (the editor refuses to save one). */
    fun apply(servers: List<Outbound>, options: SubscriptionOptions, group: String): List<Outbound> {
        val include = compile(options.nameInclude, group)
        val exclude = compile(options.nameExclude, group)
        if (include == null && exclude == null) return servers
        return servers.filter {
            val name = it.displayName()
            (include == null || include.matcher(name).find()) && (exclude == null || !exclude.matcher(name).find())
        }
    }

    private fun compile(pattern: String, group: String): Pattern? {
        if (pattern.isEmpty()) return null
        return try {
            Pattern.compile(pattern, FLAGS)
        } catch (e: PatternSyntaxException) {
            Logs.w("$group: " + app.getString(R.string.subs_filter_ignored, pattern, e.description ?: e.message.orEmpty()))
            null
        }
    }
}
