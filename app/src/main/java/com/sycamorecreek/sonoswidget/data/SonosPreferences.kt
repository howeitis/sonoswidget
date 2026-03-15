package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed user preferences persisted across app restarts.
 *
 * Stores:
 *   - Active zone ID (user's last-selected speaker/room)
 *   - Active zone IP + port (cached coordinator address for fast reconnect)
 *
 * Uses Jetpack DataStore Preferences (already in dependencies from Glance).
 */

private val Context.sonosPrefsDataStore by preferencesDataStore(name = "sonos_preferences")

class SonosPreferences(private val context: Context) {

    companion object {
        private const val TAG = "SonosPreferences"

        private val KEY_ACTIVE_ZONE_ID = stringPreferencesKey("active_zone_id")
        private val KEY_ACTIVE_ZONE_NAME = stringPreferencesKey("active_zone_name")
        private val KEY_ACTIVE_SPEAKER_IP = stringPreferencesKey("active_speaker_ip")
        private val KEY_ACTIVE_SPEAKER_PORT = stringPreferencesKey("active_speaker_port")
    }

    /**
     * Persisted active speaker target.
     */
    data class ActiveSpeaker(
        val zoneId: String,
        val zoneName: String,
        val ip: String,
        val port: Int
    )

    /**
     * Flow of the currently saved active speaker, or null if none saved.
     */
    val activeSpeaker: Flow<ActiveSpeaker?> = context.sonosPrefsDataStore.data
        .catch { e ->
            Log.e(TAG, "Error reading preferences", e)
            emit(androidx.datastore.preferences.core.emptyPreferences())
        }
        .map { prefs ->
            val zoneId = prefs[KEY_ACTIVE_ZONE_ID] ?: return@map null
            val ip = prefs[KEY_ACTIVE_SPEAKER_IP] ?: return@map null
            ActiveSpeaker(
                zoneId = zoneId,
                zoneName = prefs[KEY_ACTIVE_ZONE_NAME] ?: "",
                ip = ip,
                port = prefs[KEY_ACTIVE_SPEAKER_PORT]?.toIntOrNull() ?: 1400
            )
        }

    /**
     * Saves the active speaker target for fast reconnect on next start.
     */
    suspend fun saveActiveSpeaker(zoneId: String, zoneName: String, ip: String, port: Int) {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_ZONE_ID] = zoneId
            prefs[KEY_ACTIVE_ZONE_NAME] = zoneName
            prefs[KEY_ACTIVE_SPEAKER_IP] = ip
            prefs[KEY_ACTIVE_SPEAKER_PORT] = port.toString()
        }
        Log.d(TAG, "Saved active speaker: $zoneName ($zoneId) @ $ip:$port")
    }

    /**
     * Clears the saved active speaker (e.g., when it becomes unreachable).
     */
    suspend fun clearActiveSpeaker() {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs.remove(KEY_ACTIVE_ZONE_ID)
            prefs.remove(KEY_ACTIVE_ZONE_NAME)
            prefs.remove(KEY_ACTIVE_SPEAKER_IP)
            prefs.remove(KEY_ACTIVE_SPEAKER_PORT)
        }
        Log.d(TAG, "Cleared active speaker")
    }
}
