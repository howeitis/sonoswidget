package com.sycamorecreek.sonoswidget.widget

/**
 * When the widget offers its idle shortcuts — "Find playing" and the
 * quick-play favorites — and which of them. Pure so every layout agrees and
 * the rules are unit-testable without Glance.
 */
internal object IdleActionsPolicy {

    /**
     * Nothing is playing and nothing the app started is still settling. A
     * favorite that is loading, or a room or grouping change in flight, is
     * about to produce playback; offering "start something else" over it
     * invites a second, competing request.
     */
    fun isIdle(state: SonosWidgetState): Boolean {
        if (state.playbackState == PlaybackState.PLAYING ||
            state.playbackState == PlaybackState.TRANSITIONING
        ) {
            return false
        }
        if (state.isUpdating) return false
        return state.pendingOperations.none {
            it.type == WidgetOperationType.LOADING_FAVORITE ||
                it.type == WidgetOperationType.SWITCHING_ROOM ||
                it.type == WidgetOperationType.APPLYING_GROUPING
        }
    }

    /**
     * "Find playing" is also the natural retry while disconnected — discovery
     * already prefers a playing room — so only [isIdle] gates it.
     */
    fun showFindPlaying(state: SonosWidgetState): Boolean = isIdle(state)

    /**
     * Quick-play favorites to offer. Shown while disconnected too: the tap
     * reconnects before playing, and idle is when a reclaimed process leaves
     * the widget disconnected. Hidden when the connection cannot play favorites
     * (cloud mode), when offline, and while rate-limited.
     */
    fun quickPlay(state: SonosWidgetState): List<Favorite> {
        if (!isIdle(state) || state.isOffline || state.isRateLimited) return emptyList()
        val reachable = state.capabilities.canPlayFavorites ||
            state.connectionMode == ConnectionMode.DISCONNECTED
        return if (reachable) state.quickPlay else emptyList()
    }
}
