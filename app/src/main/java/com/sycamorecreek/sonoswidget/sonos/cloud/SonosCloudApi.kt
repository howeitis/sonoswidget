package com.sycamorecreek.sonoswidget.sonos.cloud

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Low-level HTTP client for the Sonos Cloud Control API (api.ws.sonos.com).
 *
 * All methods require a valid OAuth access token, obtained via [SonosOAuthManager].
 * Responses are parsed from JSON into typed data classes defined below.
 *
 * API reference: https://developer.sonos.com/reference/control-api/
 */
class SonosCloudApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "SonosCloudApi"
        private const val BASE_URL = "https://api.ws.sonos.com/control/api/v1"
        private val JSON_TYPE = "application/json".toMediaType()
    }

    // ── Response models ─────────────────────────────

    data class Household(val id: String)

    data class CloudGroup(
        val id: String,
        val name: String,
        val coordinatorId: String,
        val playerIds: List<String>
    )

    data class CloudPlayer(
        val id: String,
        val name: String
    )

    data class CloudPlaybackStatus(
        val playbackState: String, // PLAYBACK_STATE_PLAYING, _PAUSED, _IDLE, _BUFFERING
        val positionMillis: Long,
        val shuffleEnabled: Boolean,
        val repeatEnabled: Boolean, // Sonos Cloud uses repeat: true/false + repeatOne: true/false
        val repeatOneEnabled: Boolean
    )

    data class CloudTrackInfo(
        val name: String,
        val artist: String,
        val album: String,
        val imageUrl: String?,
        val durationMillis: Long
    )

    data class CloudVolume(
        val volume: Int, // 0–100
        val muted: Boolean
    )

    // ── API methods ─────────────────────────────────

    /**
     * Lists all households associated with the authenticated user.
     * GET /v1/households
     */
    suspend fun getHouseholds(token: String): List<Household>? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/households", token) ?: return@withContext null
        try {
            val items = json.getJSONArray("households")
            (0 until items.length()).map { i ->
                val obj = items.getJSONObject(i)
                Household(id = obj.getString("id"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse households", e)
            null
        }
    }

    /**
     * Lists all groups and players in a household.
     * GET /v1/households/{householdId}/groups
     */
    suspend fun getGroups(
        token: String,
        householdId: String
    ): Pair<List<CloudGroup>, List<CloudPlayer>>? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/households/$householdId/groups", token)
            ?: return@withContext null
        try {
            val groups = json.getJSONArray("groups")
            val players = json.getJSONArray("players")

            val groupList = (0 until groups.length()).map { i ->
                val g = groups.getJSONObject(i)
                val playerIds = g.getJSONArray("playerIds")
                CloudGroup(
                    id = g.getString("id"),
                    name = g.getString("name"),
                    coordinatorId = g.getString("coordinatorId"),
                    playerIds = (0 until playerIds.length()).map { j -> playerIds.getString(j) }
                )
            }

            val playerList = (0 until players.length()).map { i ->
                val p = players.getJSONObject(i)
                CloudPlayer(
                    id = p.getString("id"),
                    name = p.getString("name")
                )
            }

            Pair(groupList, playerList)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse groups", e)
            null
        }
    }

    /**
     * Gets the playback status of a group.
     * GET /v1/groups/{groupId}/playback
     */
    suspend fun getPlaybackStatus(
        token: String,
        groupId: String
    ): CloudPlaybackStatus? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/groups/$groupId/playback", token)
            ?: return@withContext null
        try {
            CloudPlaybackStatus(
                playbackState = json.getString("playbackState"),
                positionMillis = json.optLong("positionMillis", 0L),
                shuffleEnabled = json.optJSONObject("playModes")
                    ?.optBoolean("shuffle", false) ?: false,
                repeatEnabled = json.optJSONObject("playModes")
                    ?.optBoolean("repeat", false) ?: false,
                repeatOneEnabled = json.optJSONObject("playModes")
                    ?.optBoolean("repeatOne", false) ?: false
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse playback status", e)
            null
        }
    }

    /**
     * Gets the current track metadata for a group.
     * GET /v1/groups/{groupId}/playback/metadata
     * (Sonos may nest this under the playback endpoint or a separate metadata endpoint)
     */
    suspend fun getMetadata(
        token: String,
        groupId: String
    ): CloudTrackInfo? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/groups/$groupId/playbackMetadata", token)
            ?: return@withContext null
        try {
            val container = json.optJSONObject("currentItem")
                ?.optJSONObject("track") ?: return@withContext null
            val imageUrl = container.optJSONObject("imageUrl")?.optString("url")
                ?: container.optJSONArray("images")?.let { images ->
                    if (images.length() > 0) images.getJSONObject(0).optString("url") else null
                }
            CloudTrackInfo(
                name = container.optString("name", ""),
                artist = container.optJSONObject("artist")?.optString("name", "") ?: "",
                album = container.optJSONObject("album")?.optString("name", "") ?: "",
                imageUrl = imageUrl,
                durationMillis = container.optLong("durationMillis", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse metadata", e)
            null
        }
    }

    /**
     * Gets the volume of a group.
     * GET /v1/groups/{groupId}/groupVolume
     */
    suspend fun getVolume(
        token: String,
        groupId: String
    ): CloudVolume? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/groups/$groupId/groupVolume", token)
            ?: return@withContext null
        try {
            CloudVolume(
                volume = json.getInt("volume"),
                muted = json.optBoolean("muted", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse volume", e)
            null
        }
    }

    // ── Favorites & Music Services ──────────────────

    data class CloudFavorite(
        val id: String,
        val name: String,
        val description: String,
        val imageUrl: String?,
        val service: CloudService?
    )

    data class CloudService(
        val id: String,
        val name: String
    )

    /**
     * Lists all Sonos favorites in a household.
     * GET /v1/households/{householdId}/favorites
     */
    suspend fun getFavorites(
        token: String,
        householdId: String
    ): List<CloudFavorite>? = withContext(Dispatchers.IO) {
        val json = get("$BASE_URL/households/$householdId/favorites", token)
            ?: return@withContext null
        try {
            val items = json.optJSONArray("items") ?: return@withContext emptyList()
            (0 until items.length()).map { i ->
                val obj = items.getJSONObject(i)
                val serviceObj = obj.optJSONObject("service")
                CloudFavorite(
                    id = obj.getString("id"),
                    name = obj.optString("name", ""),
                    description = obj.optString("description", ""),
                    imageUrl = obj.optJSONObject("imageUrl")?.optString("url")
                        ?: obj.optString("imageUrl", null),
                    service = serviceObj?.let {
                        CloudService(
                            id = it.optString("id", ""),
                            name = it.optString("name", "")
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse favorites", e)
            null
        }
    }

    /**
     * Loads (plays) a favorite on a group.
     * POST /v1/groups/{groupId}/favorites
     */
    suspend fun loadFavorite(
        token: String,
        groupId: String,
        favoriteId: String,
        playOnCompletion: Boolean = true
    ): Boolean {
        val body = JSONObject().apply {
            put("favoriteId", favoriteId)
            put("playOnCompletion", playOnCompletion)
        }.toString()
        return postWithBody("$BASE_URL/groups/$groupId/favorites", token, body)
    }

    // ── Control commands (POST) ─────────────────────

    suspend fun play(token: String, groupId: String): Boolean =
        post("$BASE_URL/groups/$groupId/playback/play", token)

    suspend fun pause(token: String, groupId: String): Boolean =
        post("$BASE_URL/groups/$groupId/playback/pause", token)

    suspend fun skipToNext(token: String, groupId: String): Boolean =
        post("$BASE_URL/groups/$groupId/playback/skipToNextTrack", token)

    suspend fun skipToPrevious(token: String, groupId: String): Boolean =
        post("$BASE_URL/groups/$groupId/playback/skipToPreviousTrack", token)

    suspend fun setVolume(token: String, groupId: String, volume: Int): Boolean {
        val body = JSONObject().put("volume", volume).toString()
        return postWithBody("$BASE_URL/groups/$groupId/groupVolume", token, body)
    }

    suspend fun setPlayModes(
        token: String,
        groupId: String,
        shuffle: Boolean? = null,
        repeat: Boolean? = null,
        repeatOne: Boolean? = null
    ): Boolean {
        val modes = JSONObject()
        if (shuffle != null) modes.put("shuffle", shuffle)
        if (repeat != null) modes.put("repeat", repeat)
        if (repeatOne != null) modes.put("repeatOne", repeatOne)
        val body = JSONObject().put("playModes", modes).toString()
        return postWithBody("$BASE_URL/groups/$groupId/playback/playMode", token, body)
    }

    // ── HTTP helpers ────────────────────────────────

    private suspend fun get(url: String, token: String): JSONObject? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "GET $url → HTTP ${response.code}")
                        if (response.code == 429) {
                            Log.w(TAG, "Rate limited by Sonos Cloud API")
                        }
                        return@withContext null
                    }
                    val body = response.body?.string() ?: return@withContext null
                    JSONObject(body)
                }
            } catch (e: IOException) {
                Log.e(TAG, "GET $url network error: ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "GET $url parse error", e)
                null
            }
        }

    private suspend fun post(url: String, token: String): Boolean =
        postWithBody(url, token, "{}")

    private suspend fun postWithBody(
        url: String,
        token: String,
        jsonBody: String
    ): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toRequestBody(JSON_TYPE))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "POST $url → HTTP ${response.code}: ${response.body?.string()}")
                    return@withContext false
                }
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "POST $url network error: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "POST $url error", e)
            false
        }
    }
}
