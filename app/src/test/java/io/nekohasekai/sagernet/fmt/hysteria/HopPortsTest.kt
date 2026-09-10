package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Test

class HopPortsTest {

    @Test
    fun normalizesSeparatorsAndTrims() {
        assertEquals(
            listOf("1000:2000", "3000:4000"),
            hopPortsToSingboxList("1000-2000, 3000:4000")
        )
    }

    @Test
    fun trimsWhitespaceInsideRange() {
        assertEquals(
            listOf("1000:2000"),
            hopPortsToSingboxList(" 1000 : 2000 ")
        )
    }

    @Test
    fun dropsInvalidFragments() {
        assertEquals(
            listOf("1000:2000"),
            hopPortsToSingboxList("1000-2000,bad,5000x,1:2:3,")
        )
    }

    @Test
    fun allInvalidReturnsEmptyList() {
        assertEquals(
            emptyList<String>(),
            hopPortsToSingboxList("bad,5000x,")
        )
    }
}