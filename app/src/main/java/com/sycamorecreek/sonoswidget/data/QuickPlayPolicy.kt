package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.widget.Favorite

/**
 * Decides which Sonos Favorites the widget offers as one-tap quick-play
 * buttons while nothing is playing.
 *
 * Quick play deliberately rides on Sonos Favorites rather than on per-service
 * URIs. A favorite already carries the service account, item id and DIDL
 * metadata the speaker needs, so a YouTube Music playlist or a Pocket Casts
 * filter plays exactly as it does from the Sonos app — and anything the user
 * can add to My Sonos becomes a candidate without service-specific code here.
 */
internal object QuickPlayPolicy {

    const val SLOT_COUNT = 2

    /**
     * A user's choice for one slot. [title] is kept alongside [id] because a
     * favorite's `FV:2/N` id is only positional: removing or re-adding
     * favorites in the Sonos app can renumber them, while the title survives.
     */
    data class Pick(val id: String, val title: String)

    /**
     * Resolves [picks] (one entry per slot, null for "not chosen") against the
     * household's current [favorites].
     *
     * - A chosen favorite matches by id first, then by title. A choice that
     *   matches neither is dropped rather than replaced: silently playing a
     *   different favorite than the one the user chose is worse than showing
     *   one button fewer.
     * - An unchosen slot takes the first favorite no other slot uses, so a
     *   fresh install still offers useful buttons in Sonos-app order.
     */
    fun resolve(favorites: List<Favorite>, picks: List<Pick?>): List<Favorite> {
        if (favorites.isEmpty()) return emptyList()
        val slots = (0 until SLOT_COUNT).map { picks.getOrNull(it) }
        val chosen = slots.map { pick -> pick?.let { match(favorites, it) } }
        val used = chosen.filterNotNullTo(mutableSetOf())
        val result = mutableListOf<Favorite>()
        slots.forEachIndexed { index, pick ->
            val favorite = if (pick != null) {
                chosen[index]
            } else {
                favorites.firstOrNull { it !in used }?.also { used += it }
            }
            if (favorite != null && favorite !in result) result += favorite
        }
        return result
    }

    /**
     * Finds the favorite a stored choice refers to, or null when it is gone.
     * An id alone is never enough: after a renumbering it may name a different
     * favorite, which is exactly the substitution [resolve] refuses to make.
     */
    fun match(favorites: List<Favorite>, pick: Pick): Favorite? =
        favorites.firstOrNull { it.id == pick.id && it.title == pick.title }
            ?: favorites.firstOrNull { it.title.equals(pick.title, ignoreCase = true) }
}
