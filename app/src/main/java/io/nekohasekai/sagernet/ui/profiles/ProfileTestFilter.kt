package io.nekohasekai.sagernet.ui.profiles

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ui.test.ServiceResults

/**
 * The test words of the profile search (ProfilesFilterProxyModel's test filter): a word is a country or a working
 * service; "!word" a refused service or another country; "service+XX" / "service+!XX" also asks for the region the
 * service reported. The phone has one search box, so a row matches when its text holds the whole query, or when every
 * word either is in its text or matches as a test word.
 */
internal class ProfileTestFilter(query: String) {

    private class Term(val text: String, val region: String, val refused: Boolean, val regionNegated: Boolean) {
        val plain: Boolean get() = !refused && region.isEmpty()
    }

    /** One stored check; names, words and region are lowercase. */
    class Service(
        val id: String,
        /** Check id and name, category id and name. */
        val names: List<String>,
        val words: List<String>,
        /** The country code the service reported, empty when unknown. */
        val region: String,
        /** 1 available or partial, -1 restricted or blocked, 0 inconclusive. */
        val state: Int,
    )

    class Key(val country: String, val services: List<Service>)

    private val terms: List<Term> = query.lowercase().split(SEPARATORS).mapNotNull { raw ->
        var word = raw
        val refused = word.startsWith('!')
        if (refused) word = word.substring(1)
        var region = ""
        var regionNegated = false
        val plus = word.indexOf('+')
        if (plus >= 0) {
            region = word.substring(plus + 1)
            word = word.substring(0, plus)
            regionNegated = region.startsWith('!')
            if (regionNegated) region = region.substring(1)
        }
        word.takeIf { it.isNotEmpty() }?.let { Term(it, region, refused, regionNegated) }
    }

    /**
     * [text] is the row's search text; [disabled] services take no part; [anyService] tells whether a word picks a
     * service of any row of the group.
     */
    fun matches(text: String, key: Key?, disabled: Set<String>, anyService: (String) -> Boolean): Boolean {
        if (terms.isEmpty()) return false
        return terms.all { term ->
            term.plain && text.contains(term.text) || key != null && termMatches(key, term, disabled, anyService)
        }
    }

    /** Every service the term picks must work; "!term" needs one of them refused. Unpicked terms fall back to the country. */
    private fun termMatches(key: Key, term: Term, disabled: Set<String>, anyService: (String) -> Boolean): Boolean {
        var picked = 0
        var working = 0
        var refusing = 0
        for (service in key.services) {
            if (service.id in disabled || !picks(service.names, service.words, term.text)) continue
            picked++
            if (!regionMatches(service, term)) continue
            if (service.state > 0) working++ else if (service.state < 0) refusing++
        }
        val countryTerm = term.region.isEmpty()
        if (term.refused) {
            if (refusing > 0) return true
            // Checked group-wide: "!ai" on a profile without results must not turn into "not Anguilla".
            return countryTerm && term.text.length == 2 && key.country.isNotEmpty() &&
                !key.country.contains(term.text, ignoreCase = true) && !anyService(term.text)
        }
        if (picked > 0 && working == picked) return true
        return countryTerm && key.country.contains(term.text, ignoreCase = true)
    }

    /** An unknown region never matches, so a failed region read doesn't pass as "not RU". */
    private fun regionMatches(service: Service, term: Term): Boolean {
        if (term.region.isEmpty()) return true
        return service.region.isNotEmpty() && (service.region == term.region) != term.regionNegated
    }

    companion object {
        private val SEPARATORS = Regex("[\\s,]+")
        private val WORD_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        /** Two letters could be a country code, so they only pick a service by its exact id or category ("ai"). */
        fun picks(names: Collection<String>, words: Collection<String>, term: String): Boolean {
            if (term in names) return true
            if (term.length < 3) return false
            return words.any { it.startsWith(term) }
        }

        /** What a row's test words are matched against: its test country and stored service checks. */
        fun keyOf(profile: ProxyEntity): Key {
            val document = ServiceResults.parse(profile.serviceChecks, remember = false)
            val services: List<Service> = if (document == null || document.error.isNotEmpty()) emptyList() else {
                val categoryNames = document.categories.associate { it.id to it.name }
                document.checks.map { check ->
                    val names = listOf(check.id, check.name, check.category, categoryNames[check.category].orEmpty())
                        .filter { it.isNotEmpty() }.map { it.lowercase() }
                    Service(
                        id = check.id,
                        names = names,
                        words = names.flatMap { name -> name.split(WORD_SEPARATORS).filter { it.isNotEmpty() } },
                        region = check.region.lowercase(),
                        state = when (check.status) {
                            ServiceResults.STATUS_AVAILABLE, ServiceResults.STATUS_PARTIAL -> 1
                            ServiceResults.STATUS_RESTRICTED, ServiceResults.STATUS_BLOCKED -> -1
                            else -> 0
                        },
                    )
                }
            }
            return Key(profile.testCountry.orEmpty(), services)
        }
    }
}
