package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.outbound.Outbound
import io.nekohasekai.sagernet.outbound.types.AutoSelector
import io.nekohasekai.sagernet.outbound.types.Psiphon
import io.nekohasekai.sagernet.validation.vtr
import java.util.Locale

/**
 * The exit wrappers of generate_profiles.cpp (exitProfile, exitHopOf, exitError) for the exits Android runs, WARP and
 * Psiphon; Tor builds as [ExitThrough.SERVER]. The exit stands in for [WarpHop.PROFILE_ID] in a hop list and takes the
 * `proxy` tag, dialing through the wrapped hop, which becomes `warp-bypass`.
 */
internal object ExitHop {

    /** exitWrapperOn: the main chain gets the exit in front of it. */
    @JvmStatic
    fun wraps(settings: GeneratorSettings): Boolean =
        settings.exitThrough == ExitThrough.WARP || settings.exitThrough == ExitThrough.PSIPHON

    /** exitProfile: the outbound of the active exit, null while none wraps. */
    @JvmStatic
    fun outbound(settings: GeneratorSettings): Outbound? = when (settings.exitThrough) {
        ExitThrough.WARP -> WarpHop.outbound(settings)
        ExitThrough.PSIPHON -> psiphon(settings)
        else -> null
    }

    /** psiphonExitProfile (generate_profiles.cpp). */
    @JvmStatic
    fun psiphon(settings: GeneratorSettings): Psiphon = Psiphon().apply {
        name = "Psiphon"
        egress_region = settings.psiphonEgressRegion.trim().uppercase(Locale.ROOT)
    }

    /** ExitName (ExitThrough.h): what route decisions name the exit's `proxy` tag. */
    @JvmStatic
    fun name(settings: GeneratorSettings): String = when (settings.exitThrough) {
        ExitThrough.WARP -> "WARP"
        ExitThrough.PSIPHON -> ExitThrough.PSIPHON.displayName
        else -> ""
    }

    /**
     * exitError (generate_profiles.cpp): why the active exit cannot wrap [profile], "" when it can. [landingProxyId]
     * is the landing proxy the build puts under the exit, <= 0 for none.
     */
    @JvmStatic
    fun error(settings: GeneratorSettings, reads: ProfileProvider, profile: Outbound, landingProxyId: Long): String {
        when (settings.exitThrough) {
            ExitThrough.WARP -> return if (WarpHop.missing(settings)) WarpHop.MISSING_ERROR else ""
            ExitThrough.PSIPHON -> Unit
            else -> return ""
        }
        val hop = exitHopOf(reads, profile, landingProxyId) ?: return ""
        if (hop.type != ExitThrough.PSIPHON.value) return ""
        return vtr(R.string.exit_runs_over_itself, ExitThrough.PSIPHON.displayName)
    }

    /**
     * exitHopOf: the hop [profile]'s traffic leaves through before the exit, its landing proxy, a chain's last hop or
     * the profile; null for an auto selector, whose members leave through their own groups' landing proxies.
     */
    private fun exitHopOf(reads: ProfileProvider, profile: Outbound, landingProxyId: Long): Outbound? {
        if (profile is AutoSelector) return null
        val hop = when {
            landingProxyId > 0 -> reads.get(landingProxyId)
            profile.type == "chain" -> TypeAccess.chainHops(profile).lastOrNull()?.let(reads::get)
            else -> profile
        }
        return hop?.takeIf { it !is AutoSelector }
    }
}
