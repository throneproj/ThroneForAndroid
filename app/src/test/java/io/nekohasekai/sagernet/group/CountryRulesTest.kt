package io.nekohasekai.sagernet.group

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CountryRulesTest {

    @Test
    fun parsesKeepsAndDrops() {
        val rules = CountryRules.parse(" hk, JP ,!ru, ! cn ")
        assertEquals(setOf("HK", "JP"), rules.keep)
        assertEquals(setOf("RU", "CN"), rules.drop)
        assertTrue(rules.active)
    }

    @Test
    fun readsUkAsGb() {
        val rules = CountryRules.parse("UK,!uk")
        assertEquals(setOf("GB"), rules.keep)
        assertEquals(setOf("GB"), rules.drop)
    }

    @Test
    fun skipsAndReportsBadItems() {
        val problems = ArrayList<String>()
        val rules = CountryRules.parse("HK,USA,1A,!,,  ,J,!DEU", problems)
        assertEquals(setOf("HK"), rules.keep)
        assertTrue(rules.drop.isEmpty())
        assertEquals(listOf("USA", "1A", "!", "J", "!DEU"), problems)
        assertEquals(problems, CountryRules.problems("HK,USA,1A,!,,  ,J,!DEU"))
        assertTrue(CountryRules.problems("hk, !ru").isEmpty())
    }

    @Test
    fun emptyIsInactive() {
        assertFalse(CountryRules.parse("").active)
        assertFalse(CountryRules.parse(" , ,").active)
        assertFalse(CountryRules.parse("XYZ").active)
    }

    @Test
    fun acceptsByKeepAndDrop() {
        val keep = CountryRules.parse("HK,JP")
        assertTrue(keep.accepts("hk"))
        assertTrue(keep.accepts("JP"))
        assertFalse(keep.accepts("US"))

        val drop = CountryRules.parse("!RU")
        assertFalse(drop.accepts("ru"))
        assertTrue(drop.accepts("DE"))

        val both = CountryRules.parse("HK,!HK,JP")
        assertFalse(both.accepts("HK"))
        assertTrue(both.accepts("JP"))
        assertFalse(both.accepts("US"))
    }

    @Test
    fun unknownCountryHasNoVerdict() {
        val rules = CountryRules.parse("HK,!RU")
        assertTrue(rules.accepts(""))
        assertTrue(rules.accepts(null))
        assertTrue(rules.accepts("  "))
    }

    @Test
    fun normalizes() {
        assertEquals("HK,JP,!RU", CountryRules.normalize("!ru, hk ,jp,HK,!RU"))
        assertEquals("GB", CountryRules.normalize("uk,gb"))
        assertEquals("HK", CountryRules.normalize("HK,USA,!"))
        assertEquals("", CountryRules.normalize(" , "))
    }
}
