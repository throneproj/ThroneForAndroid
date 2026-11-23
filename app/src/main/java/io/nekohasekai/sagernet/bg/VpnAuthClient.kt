package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The UI's copy of the service's [VpnAuthState], fed by every [SagerConnection]; null until a service reported. */
object VpnAuthClient {

    private val mutableState = MutableStateFlow<VpnAuthState?>(null)

    val state: StateFlow<VpnAuthState?> = mutableState

    /** Challenges a prompt has shown, so a screen of the app opens each one by itself only once. */
    private val prompted = HashSet<String>()

    fun update(json: String?) {
        mutableState.value = VpnAuthState.parse(json)
    }

    /** The service process went away. */
    fun reset() {
        mutableState.value = VpnAuthState.EMPTY
    }

    @Synchronized
    fun markPrompted(challenge: VpnAuthState.Challenge) {
        prompted.add(challenge.key)
    }

    /** The first pending challenge no prompt has shown yet. */
    @Synchronized
    fun unprompted(): VpnAuthState.Challenge? =
        mutableState.value?.challenges?.firstOrNull { it.key !in prompted && !it.expired() }
}
