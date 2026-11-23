package io.nekohasekai.sagernet.group

import java.util.Locale

/**
 * CountryRules (SubscriptionFilter.cpp): a subscription's exit-country filter, judged by the country of an IP test.
 * "HK,JP" keeps only those countries, "!RU" drops one, "UK" reads as GB.
 */
class CountryRules(val keep: Set<String>, val drop: Set<String>) {

    val active: Boolean get() = keep.isNotEmpty() || drop.isNotEmpty()

    /** An empty country is accepted: a profile that was never tested, or failed its test, has no verdict. */
    fun accepts(country: String?): Boolean {
        val code = country.orEmpty().trim().uppercase(Locale.ROOT)
        if (code.isEmpty()) return true
        if (code in drop) return false
        return keep.isEmpty() || code in keep
    }

    companion object {

        /** ParseCountryRules: items that are not two letters are skipped and added to [problems]. */
        @JvmStatic
        fun parse(text: String, problems: MutableList<String>? = null): CountryRules {
            val keep = LinkedHashSet<String>()
            val drop = LinkedHashSet<String>()
            for ((code, dropped) in items(text, problems)) (if (dropped) drop else keep).add(code)
            return CountryRules(keep, drop)
        }

        /** The items of [text] that are not two-letter codes (CountryRulesProblem), in input order. */
        @JvmStatic
        fun problems(text: String): List<String> = ArrayList<String>().also { items(text, it) }

        /** NormalizeCountryRules: "HK,JP,!RU", upper-case, de-duplicated, keeps before drops; bad items are dropped. */
        @JvmStatic
        fun normalize(text: String): String {
            val keep = LinkedHashSet<String>()
            val drop = LinkedHashSet<String>()
            for ((code, dropped) in items(text, null)) (if (dropped) drop else keep).add(code)
            return (keep + drop.map { "!$it" }).joinToString(",")
        }

        /** countryCode: two ASCII letters, upper-cased; null otherwise. */
        private fun code(item: String): String? {
            val code = item.trim().uppercase(Locale.ROOT)
            if (code.length != 2 || !code.all { it in 'A'..'Z' }) return null
            return if (code == "UK") "GB" else code
        }

        /** countryItems: (code, drop) in input order. */
        private fun items(text: String, problems: MutableList<String>?): List<Pair<String, Boolean>> {
            val items = ArrayList<Pair<String, Boolean>>()
            for (raw in text.split(',')) {
                val item = raw.trim()
                if (item.isEmpty()) continue
                val dropped = item.startsWith('!')
                val code = code(if (dropped) item.substring(1) else item)
                if (code == null) {
                    problems?.add(item)
                    continue
                }
                items.add(code to dropped)
            }
            return items
        }
    }
}
