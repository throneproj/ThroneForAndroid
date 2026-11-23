package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.Outbound

/**
 * The exit wrappers of generate_profiles.cpp (exitProfile, exitError) for the WARP exit. The exit stands in for
 * [WarpHop.PROFILE_ID] in a hop list and takes the `proxy` tag, dialing through the wrapped hop, which becomes
 * `warp-bypass`.
 */
internal object ExitHop {

    /** exitWrapperOn: the main chain gets the exit in front of it. */
    @JvmStatic
    fun wraps(settings: GeneratorSettings): Boolean = settings.exitThrough == ExitThrough.WARP

    /** exitProfile: the outbound of the active exit, null while none wraps. */
    @JvmStatic
    fun outbound(settings: GeneratorSettings): Outbound? = if (wraps(settings)) WarpHop.outbound(settings) else null

    /** ExitName (ExitThrough.h): what route decisions name the exit's `proxy` tag. */
    @JvmStatic
    fun name(settings: GeneratorSettings): String = if (wraps(settings)) "WARP" else ""

    /** exitError (generate_profiles.cpp): why the active exit cannot run, "" when it can. */
    @JvmStatic
    fun error(settings: GeneratorSettings): String =
        if (wraps(settings) && WarpHop.missing(settings)) WarpHop.MISSING_ERROR else ""
}
