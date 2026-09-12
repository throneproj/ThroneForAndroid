package io.nekohasekai.sagernet.fmt.hysteria

import org.junit.Assert.assertEquals
import org.junit.Test

class HysteriaFmtTest {

    @Test
    fun firstPortFromSinglePort() {
        assertEquals(443, getFirstPort("443"))
    }

    @Test
    fun firstPortFromCommaList() {
        assertEquals(443, getFirstPort("443,8000-9000"))
    }

    @Test
    fun firstPortFromDashRange() {
        assertEquals(1000, getFirstPort("1000-2000"))
    }

    @Test
    fun firstPortFromMixedList() {
        assertEquals(2000, getFirstPort("2000-3000,4000"))
    }

    @Test
    fun firstPortInvalidFallsBackTo443() {
        assertEquals(443, getFirstPort("bad"))
        assertEquals(443, getFirstPort(""))
    }
}