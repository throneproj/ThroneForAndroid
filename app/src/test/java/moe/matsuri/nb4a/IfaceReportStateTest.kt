package moe.matsuri.nb4a

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IfaceReportStateTest {

    private class IfaceReportState {
        var name: String? = null
        var index: Int = Int.MIN_VALUE
        var networkId: Int? = null
        var isExpensive: Boolean = false
        var hasReportedAtLeastOnce: Boolean = false
    }

    private data class UpdateResult(
        val reportedName: String,
        val reportedIndex: Int,
        val isExpensive: Boolean,
        val changed: Boolean,
    )

    private fun simulateUpdate(
        state: IfaceReportState,
        ifaceName: String?,
        interfaceIndex: Int,
        networkId: Int?,
        isExpensive: Boolean,
    ): UpdateResult? {
        if (networkId == null || ifaceName == null) {
            // clearInterface
            state.name = null
            state.index = Int.MIN_VALUE
            state.networkId = null
            state.isExpensive = false
            return UpdateResult("", -1, false, false)
        }

        val sameInterface = state.name == ifaceName && state.index == interfaceIndex &&
            state.networkId == networkId

        if (sameInterface && state.isExpensive == isExpensive) {
            return null // short-circuited duplicate
        }

        val changed = !sameInterface && (state.name != null || state.hasReportedAtLeastOnce)
        state.name = ifaceName
        state.index = interfaceIndex
        state.networkId = networkId
        state.isExpensive = isExpensive
        state.hasReportedAtLeastOnce = true

        return UpdateResult(ifaceName, interfaceIndex, isExpensive, changed)
    }

    @Test
    fun initialNetworkDetectionDoesNotTriggerReset() {
        val state = IfaceReportState()
        val result = simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)

        // Initial connection must not be considered a handover/switch
        assertTrue(result != null)
        assertFalse("First interface report must not trigger connection reset", result!!.changed)
        assertTrue(state.hasReportedAtLeastOnce)
    }

    @Test
    fun handoverWithDisconnectTriggerResetOnNewInterface() {
        val state = IfaceReportState()
        // 1. Initially on Wi-Fi
        simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)

        // 2. Wi-Fi drops (Break-Before-Make)
        val lostResult = simulateUpdate(state, null, -1, null, isExpensive = false)
        assertTrue(lostResult != null)
        assertFalse(lostResult!!.changed)

        // 3. Mobile data connects
        val mobileResult = simulateUpdate(state, "rmnet0", 15, 101, isExpensive = true)
        assertTrue(mobileResult != null)
        assertTrue(
            "Switching from disconnected state to mobile data must trigger connection reset",
            mobileResult!!.changed,
        )
    }

    @Test
    fun handoverDirectSwitchTriggersReset() {
        val state = IfaceReportState()
        // 1. Wi-Fi
        simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)

        // 2. Direct handover to LTE (make-before-break)
        val lteResult = simulateUpdate(state, "rmnet0", 15, 101, isExpensive = true)
        assertTrue(lteResult != null)
        assertTrue("Direct handover must trigger connection reset", lteResult!!.changed)
    }

    @Test
    fun duplicateCapabilityEventsDoNotTriggerReset() {
        val state = IfaceReportState()
        simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)

        // Same interface, same network, same capabilities
        val duplicateResult = simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)
        assertTrue("Duplicate capability event should be skipped", duplicateResult == null)
    }

    @Test
    fun reconnectingToSameInterfaceAfterDisconnectTriggersReset() {
        val state = IfaceReportState()
        // 1. Wi-Fi
        simulateUpdate(state, "wlan0", 12, 100, isExpensive = false)

        // 2. Temporary disconnect
        simulateUpdate(state, null, -1, null, false)

        // 3. Reconnects to Wi-Fi with new Network instance
        val reconnectResult = simulateUpdate(state, "wlan0", 12, 102, isExpensive = false)
        assertTrue(reconnectResult != null)
        assertTrue("Reconnecting after drop should reset stale connections", reconnectResult!!.changed)
    }
}
