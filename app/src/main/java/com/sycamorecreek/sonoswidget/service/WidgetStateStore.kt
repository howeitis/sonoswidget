package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.QueueItem
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.SonosWidget
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.Track
import com.sycamorecreek.sonoswidget.widget.WidgetColorPalette
import com.sycamorecreek.sonoswidget.widget.Zone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridge between the PlaybackService and the Glance widget.
 *
 * Serializes [SonosWidgetState] to JSON, stores it in Glance's
 * Preferences DataStore, and triggers widget refresh on all instances.
 *
 * Uses a single JSON string preference key to avoid the complexity
 * of flattening nested objects into individual preference keys.
 */
object WidgetStateStore {

    private const val TAG = "WidgetStateStore"

    /** Glance Preferences key holding the serialized widget state JSON. */
    val STATE_KEY = stringPreferencesKey("sonos_widget_state")

    /**
     * Pushes a new state to all widget instances and triggers refresh.
     *
     * 1. Serializes the state to JSON
     * 2. Writes to each widget's Glance Preferences
     * 3. Calls update() to trigger provideGlance()
     */
    suspend fun pushState(context: Context, state: SonosWidgetState) {
        val json = serialize(state)
        val manager = GlanceAppWidgetManager(context)
        val glanceIds = manager.getGlanceIds(SonosWidget::class.java)

        if (glanceIds.isEmpty()) {
            Log.d(TAG, "No widget instances to update")
            return
        }

        val widget = SonosWidget()
        for (glanceId in glanceIds) {
            try {
                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[STATE_KEY] = json
                }
                widget.update(context, glanceId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update widget $glanceId", e)
            }
        }

        Log.d(TAG, "Pushed state to ${glanceIds.size} widget(s): ${state.playbackState}")
    }

    // ──────────────────────────────────────────────
    // Serialization: SonosWidgetState ↔ JSON
    //
    // Uses Android's built-in JSONObject to avoid
    // adding kotlinx.serialization as a dependency.
    // ──────────────────────────────────────────────

    fun serialize(state: SonosWidgetState): String {
        return JSONObject().apply {
            put("playbackState", state.playbackState.name)
            put("currentTrack", serializeTrack(state.currentTrack))
            put("activeZone", serializeZone(state.activeZone))
            put("volume", state.volume)
            put("zones", JSONArray().apply {
                state.zones.forEach { put(serializeZone(it)) }
            })
            put("queue", JSONArray().apply {
                state.queue.forEach { put(serializeQueueItem(it)) }
            })
            put("currentSource", state.currentSource)
            put("connectionMode", state.connectionMode.name)
            put("shuffleEnabled", state.shuffleEnabled)
            put("repeatMode", state.repeatMode.name)
            put("colorPalette", serializeColorPalette(state.colorPalette))
            put("isReconnecting", state.isReconnecting)
            put("isRateLimited", state.isRateLimited)
            put("lastUpdatedMs", state.lastUpdatedMs)
        }.toString()
    }

    fun deserialize(json: String): SonosWidgetState {
        return try {
            val obj = JSONObject(json)
            SonosWidgetState(
                playbackState = PlaybackState.valueOf(
                    obj.optString("playbackState", "STOPPED")
                ),
                currentTrack = deserializeTrack(obj.optJSONObject("currentTrack")),
                activeZone = deserializeZone(obj.optJSONObject("activeZone")),
                volume = obj.optInt("volume", 50),
                zones = deserializeZoneList(obj.optJSONArray("zones")),
                queue = deserializeQueueList(obj.optJSONArray("queue")),
                currentSource = obj.optString("currentSource", ""),
                connectionMode = try {
                    ConnectionMode.valueOf(obj.optString("connectionMode", "DISCONNECTED"))
                } catch (_: Exception) {
                    ConnectionMode.DISCONNECTED
                },
                shuffleEnabled = obj.optBoolean("shuffleEnabled", false),
                colorPalette = deserializeColorPalette(obj.optJSONObject("colorPalette")),
                isReconnecting = obj.optBoolean("isReconnecting", false),
                isRateLimited = obj.optBoolean("isRateLimited", false),
                lastUpdatedMs = obj.optLong("lastUpdatedMs", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize widget state", e)
            SonosWidgetState()
        }
    }

    private fun serializeTrack(track: Track): JSONObject = JSONObject().apply {
        put("name", track.name)
        put("artist", track.artist)
        put("album", track.album)
        put("artUrl", track.artUrl ?: JSONObject.NULL)
        put("durationMs", track.durationMs)
        put("elapsedMs", track.elapsedMs)
    }

    private fun deserializeTrack(obj: JSONObject?): Track {
        if (obj == null) return Track()
        return Track(
            name = obj.optString("name", ""),
            artist = obj.optString("artist", ""),
            album = obj.optString("album", ""),
            artUrl = if (obj.isNull("artUrl")) null else obj.optString("artUrl"),
            durationMs = obj.optLong("durationMs", 0L),
            elapsedMs = obj.optLong("elapsedMs", 0L)
        )
    }

    private fun serializeZone(zone: Zone): JSONObject = JSONObject().apply {
        put("id", zone.id)
        put("displayName", zone.displayName)
        put("groupId", zone.groupId ?: JSONObject.NULL)
        put("isGroupCoordinator", zone.isGroupCoordinator)
    }

    private fun deserializeZone(obj: JSONObject?): Zone {
        if (obj == null) return Zone()
        return Zone(
            id = obj.optString("id", ""),
            displayName = obj.optString("displayName", ""),
            groupId = if (obj.isNull("groupId")) null else obj.optString("groupId"),
            isGroupCoordinator = obj.optBoolean("isGroupCoordinator", false)
        )
    }

    private fun deserializeZoneList(arr: JSONArray?): List<Zone> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            deserializeZone(arr.optJSONObject(i))
        }
    }

    private fun serializeQueueItem(item: QueueItem): JSONObject = JSONObject().apply {
        put("trackName", item.trackName)
        put("artist", item.artist)
        put("thumbnailUrl", item.thumbnailUrl ?: JSONObject.NULL)
        put("position", item.position)
    }

    private fun deserializeQueueItem(obj: JSONObject?): QueueItem {
        if (obj == null) return QueueItem()
        return QueueItem(
            trackName = obj.optString("trackName", ""),
            artist = obj.optString("artist", ""),
            thumbnailUrl = if (obj.isNull("thumbnailUrl")) null else obj.optString("thumbnailUrl"),
            position = obj.optInt("position", 0)
        )
    }

    private fun deserializeQueueList(arr: JSONArray?): List<QueueItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            deserializeQueueItem(arr.optJSONObject(i))
        }
    }

    private fun serializeColorPalette(palette: WidgetColorPalette): JSONObject =
        JSONObject().apply {
            put("background", palette.background)
            put("textPrimary", palette.textPrimary)
            put("textSecondary", palette.textSecondary)
            put("accent", palette.accent)
            put("chipBackground", palette.chipBackground)
        }

    private fun deserializeColorPalette(obj: JSONObject?): WidgetColorPalette {
        if (obj == null) return WidgetColorPalette()
        return WidgetColorPalette(
            background = obj.optString("background", "#1E1E2E"),
            textPrimary = obj.optString("textPrimary", "#FFFFFF"),
            textSecondary = obj.optString("textSecondary", "#B0B0C0"),
            accent = obj.optString("accent", "#6C63FF"),
            chipBackground = obj.optString("chipBackground", "#3E3E5E")
        )
    }
}
