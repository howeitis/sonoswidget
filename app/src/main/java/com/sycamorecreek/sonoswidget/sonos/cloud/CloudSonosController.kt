package com.sycamorecreek.sonoswidget.sonos.cloud

import android.util.Log
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.MusicSource
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.RepeatMode
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.SourcePlaylist
import com.sycamorecreek.sonoswidget.widget.Track
import com.sycamorecreek.sonoswidget.widget.Zone

/**
 * High-level Sonos controller that uses the Sonos Cloud REST API.
 *
 * Mirrors the interface pattern of [LocalSonosController] so that
 * [SonosRepository] can route commands to either controller transparently.
 *
 * Requires a valid OAuth session managed by [SonosOAuthManager].
 * All methods automatically ensure a valid access token before making API calls.
 */
class CloudSonosController(
    private val oAuthManager: SonosOAuthManager,
    private val api: SonosCloudApi = SonosCloudApi()
) {

    companion object {
        private const val TAG = "CloudSonosController"
    }

    // Cached household and group IDs to avoid repeated lookups
    private var cachedHouseholdId: String? = null
    private var cachedGroupId: String? = null

    /**
     * Polls the full playback state from the Sonos Cloud API and
     * returns a complete [SonosWidgetState].
     *
     * This is the cloud equivalent of the local controller's poll flow.
     */
    suspend fun getPlaybackStatus(): SonosWidgetState? {
        val token = oAuthManager.ensureValidToken() ?: run {
            Log.w(TAG, "No valid token — login required")
            return null
        }

        val householdId = resolveHousehold(token) ?: return null
        val (groups, players) = api.getGroups(token, householdId) ?: return null

        if (groups.isEmpty()) {
            Log.w(TAG, "No groups found in household")
            return null
        }

        // Use the first group (or the cached one if still valid)
        val group = if (cachedGroupId != null) {
            groups.find { it.id == cachedGroupId } ?: groups.first()
        } else {
            groups.first()
        }
        cachedGroupId = group.id

        // Fetch playback, metadata, and volume in sequence (could be parallelized)
        val playbackStatus = api.getPlaybackStatus(token, group.id)
        val trackInfo = api.getMetadata(token, group.id)
        val volumeInfo = api.getVolume(token, group.id)

        // Map cloud groups to widget zones
        val zones = groups.flatMap { g ->
            g.playerIds.mapIndexed { index, playerId ->
                val player = players.find { it.id == playerId }
                Zone(
                    id = playerId,
                    displayName = player?.name ?: "Player $playerId",
                    groupId = g.id,
                    isGroupCoordinator = playerId == g.coordinatorId
                )
            }
        }

        val activeZone = zones.find { it.id == group.coordinatorId }
            ?: zones.firstOrNull()
            ?: Zone()

        return SonosWidgetState(
            playbackState = mapCloudPlaybackState(playbackStatus?.playbackState),
            currentTrack = Track(
                name = trackInfo?.name ?: "",
                artist = trackInfo?.artist ?: "",
                album = trackInfo?.album ?: "",
                artUrl = trackInfo?.imageUrl,
                durationMs = trackInfo?.durationMillis ?: 0L,
                elapsedMs = playbackStatus?.positionMillis ?: 0L
            ),
            activeZone = activeZone,
            volume = volumeInfo?.volume ?: 50,
            zones = zones,
            shuffleEnabled = playbackStatus?.shuffleEnabled ?: false,
            repeatMode = mapCloudRepeatMode(
                playbackStatus?.repeatEnabled ?: false,
                playbackStatus?.repeatOneEnabled ?: false
            ),
            connectionMode = ConnectionMode.CLOUD,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    // ── Control commands ────────────────────────────

    suspend fun play(): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.play(token, groupId)
    }

    suspend fun pause(): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.pause(token, groupId)
    }

    suspend fun next(): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.skipToNext(token, groupId)
    }

    suspend fun previous(): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.skipToPrevious(token, groupId)
    }

    suspend fun setVolume(volume: Int): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.setVolume(token, groupId, volume.coerceIn(0, 100))
    }

    suspend fun setShuffle(enabled: Boolean): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.setPlayModes(token, groupId, shuffle = enabled)
    }

    suspend fun setRepeat(repeatMode: RepeatMode): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return when (repeatMode) {
            RepeatMode.NONE -> api.setPlayModes(token, groupId, repeat = false, repeatOne = false)
            RepeatMode.ALL -> api.setPlayModes(token, groupId, repeat = true, repeatOne = false)
            RepeatMode.ONE -> api.setPlayModes(token, groupId, repeat = false, repeatOne = true)
        }
    }

    /**
     * Fetches available music sources from Sonos favorites.
     *
     * Retrieves all favorites, groups them by service name, and returns
     * a list of [MusicSource] objects with their associated playlists.
     */
    suspend fun getMusicSources(): List<MusicSource> {
        val token = oAuthManager.ensureValidToken() ?: return emptyList()
        val householdId = resolveHousehold(token) ?: return emptyList()

        val favorites = api.getFavorites(token, householdId) ?: return emptyList()
        if (favorites.isEmpty()) return emptyList()

        // Group favorites by service name
        val grouped = favorites.groupBy { it.service?.name ?: "Sonos" }

        return grouped.map { (serviceName, favs) ->
            MusicSource(
                id = favs.first().service?.id ?: serviceName.lowercase().replace(" ", "_"),
                name = serviceName,
                playlists = favs.map { fav ->
                    SourcePlaylist(
                        id = fav.id,
                        name = fav.name,
                        imageUrl = fav.imageUrl,
                        sourceId = fav.service?.id ?: serviceName.lowercase().replace(" ", "_")
                    )
                }
            )
        }
    }

    /**
     * Loads a favorite for playback on the active group.
     */
    suspend fun playFavorite(favoriteId: String): Boolean {
        val (token, groupId) = resolveTokenAndGroup() ?: return false
        return api.loadFavorite(token, groupId, favoriteId)
    }

    /**
     * Switches the active group for cloud commands.
     */
    fun setActiveGroup(groupId: String) {
        cachedGroupId = groupId
        Log.d(TAG, "Active group set to: $groupId")
    }

    fun clearCache() {
        cachedHouseholdId = null
        cachedGroupId = null
    }

    val isLoggedIn: Boolean
        get() = oAuthManager.isLoggedIn

    // ── Private helpers ─────────────────────────────

    private suspend fun resolveHousehold(token: String): String? {
        cachedHouseholdId?.let { return it }

        val households = api.getHouseholds(token)
        if (households.isNullOrEmpty()) {
            Log.w(TAG, "No households found")
            return null
        }

        cachedHouseholdId = households.first().id
        return cachedHouseholdId
    }

    private suspend fun resolveTokenAndGroup(): Pair<String, String>? {
        val token = oAuthManager.ensureValidToken() ?: run {
            Log.w(TAG, "No valid token")
            return null
        }

        val groupId = cachedGroupId ?: run {
            val householdId = resolveHousehold(token) ?: return null
            val (groups, _) = api.getGroups(token, householdId) ?: return null
            if (groups.isEmpty()) return null
            cachedGroupId = groups.first().id
            cachedGroupId
        }

        return Pair(token, groupId!!)
    }

    private fun mapCloudPlaybackState(state: String?): PlaybackState {
        return when (state) {
            "PLAYBACK_STATE_PLAYING" -> PlaybackState.PLAYING
            "PLAYBACK_STATE_PAUSED" -> PlaybackState.PAUSED
            "PLAYBACK_STATE_BUFFERING" -> PlaybackState.TRANSITIONING
            else -> PlaybackState.STOPPED
        }
    }

    private fun mapCloudRepeatMode(repeat: Boolean, repeatOne: Boolean): RepeatMode {
        return when {
            repeatOne -> RepeatMode.ONE
            repeat -> RepeatMode.ALL
            else -> RepeatMode.NONE
        }
    }
}
