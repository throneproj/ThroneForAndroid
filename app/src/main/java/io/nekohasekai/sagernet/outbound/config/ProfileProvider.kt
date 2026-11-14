package io.nekohasekai.sagernet.outbound.config

import io.nekohasekai.sagernet.outbound.Outbound

/**
 * The storage layer's lookup of a stored profile by id (ProfilesRepo::GetProfile): the parsed outbound of the row,
 * or null when no such profile exists. The generator never mutates what it gets back, except for the transient
 * bridge fields of a `Custom` xrayfullconfig hop (bridgePort / bridgeAuth, custom.h:19-21) and the Normalize() clamps
 * of a started auto-selector (autoselector.h:140-167, which the desktop applies before every build too).
 */
fun interface ProfileProvider {
    fun get(id: Long): Outbound?

    /** The group of profile [id] (GroupsRepo::GetGroup(ent->gid)); null when the profile or its group does not exist. */
    fun groupOf(id: Long): ProfileGroup? = null
}

/** A group's landing / front proxy (any id <= 0 is none) and its name, for errors. */
class ProfileGroup(@JvmField val landingProxyId: Long, @JvmField val frontProxyId: Long, @JvmField val name: String)
