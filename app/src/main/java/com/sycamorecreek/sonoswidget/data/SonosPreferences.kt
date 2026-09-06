package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
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
        private val KEY_MANUAL_SPEAKER_IPS = stringSetPreferencesKey("manual_speaker_ips")
        private val KEY_DEFAULT_ZONE_ID = stringPreferencesKey("default_zone_id")
        private val KEY_DEFAULT_ZONE_NAME = stringPreferencesKey("default_zone_name")
        private val KEY_PREFERRED_SERVICE = stringPreferencesKey("preferred_service")
        private val KEY_EXPERIENCE_PREFERENCES_VERSION = intPreferencesKey("experience_preferences_version")
        private val KEY_ROOM_FOLLOW_MODE = stringPreferencesKey("room_follow_mode")
        private val KEY_ROOM_TARGET_ID = stringPreferencesKey("room_target_id")
        private val KEY_ROOM_TARGET_NAME = stringPreferencesKey("room_target_name")

        private const val EXPERIENCE_PREFERENCES_VERSION = RoomFollowMigrationPolicy.CURRENT_VERSION
        private val LEGACY_KEY_NAMES = setOf(
            "active_zone_id",
            "active_zone_name",
            "active_speaker_ip",
            "active_speaker_port",
            "manual_speaker_ips",
            "default_zone_id",
            "default_zone_name",
            "preferred_service"
        )
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

    // ──────────────────────────────────────────────
    // Manual speaker IPs (fallback when discovery fails)
    // ──────────────────────────────────────────────

    /**
     * Returns the set of manually configured speaker IPs.
     */
    suspend fun getManualIps(): Set<String> {
        return context.sonosPrefsDataStore.data.first()[KEY_MANUAL_SPEAKER_IPS] ?: emptySet()
    }

    /**
     * Saves a set of manually configured speaker IPs.
     */
    suspend fun saveManualIps(ips: Set<String>) {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs[KEY_MANUAL_SPEAKER_IPS] = ips
        }
        Log.d(TAG, "Saved ${ips.size} manual IP(s)")
    }

    /**
     * Adds a single manual speaker IP.
     */
    suspend fun addManualIp(ip: String) {
        val current = getManualIps().toMutableSet()
        current.add(ip)
        saveManualIps(current)
    }

    /**
     * Removes a single manual speaker IP.
     */
    suspend fun removeManualIp(ip: String) {
        val current = getManualIps().toMutableSet()
        current.remove(ip)
        saveManualIps(current)
    }

    // ──────────────────────────────────────────────
    // Default zone (user's preferred speaker/room)
    // ──────────────────────────────────────────────

    data class DefaultZone(val id: String, val name: String)

    enum class RoomFollowMode {
        STAY_WITH_ROOM,
        FOLLOW_PLAYING_MUSIC
    }

    /** Versioned room-targeting preferences used by widget discovery and selection. */
    data class RoomFollowPreferences(
        val version: Int,
        val mode: RoomFollowMode,
        val target: DefaultZone?
    )

    /**
     * Migrates the legacy default-zone behaviour exactly once. This deliberately
     * reads before any new preference write: an empty legacy store is a new
     * install, while an existing legacy store without a selected default follows
     * playing music. DataStore read failures propagate so callers can retry; a
     * failed read must never be mistaken for an empty store.
     */
    suspend fun migrateExperiencePreferences(
        hasPersistedWidgetState: Boolean = false
    ): RoomFollowPreferences {
        val snapshot = context.sonosPrefsDataStore.data.first()
        val explicitMode = parseRoomFollowMode(snapshot[KEY_ROOM_FOLLOW_MODE])
        val version = snapshot[KEY_EXPERIENCE_PREFERENCES_VERSION] ?: 0
        val legacyDefault = readLegacyDefaultZone(snapshot)
        val explicitTarget = readRoomFollowPreferences(
            snapshot,
            explicitMode ?: RoomFollowMode.STAY_WITH_ROOM,
            version
        ).target
        val hasLegacyEvidence = hasPersistedWidgetState || snapshot.asMap().keys.any {
            it.name in LEGACY_KEY_NAMES
        }
        val decision = RoomFollowMigrationPolicy.resolve(
            RoomFollowMigrationPolicy.Snapshot(
                version = version,
                explicitMode = explicitMode,
                explicitTarget = explicitTarget,
                legacyDefault = legacyDefault,
                hasLegacyEvidence = hasLegacyEvidence
            )
        )
        if (decision.alreadyMigrated) {
            return readRoomFollowPreferences(
                snapshot,
                explicitMode ?: error("Migrated room preferences are missing their mode"),
                version
            )
        }

        context.sonosPrefsDataStore.edit { prefs ->
            // Preserve an explicit choice if another writer made one between the
            // snapshot and this atomic migration transaction.
            val resolvedMode = parseRoomFollowMode(prefs[KEY_ROOM_FOLLOW_MODE]) ?: decision.mode
            prefs[KEY_ROOM_FOLLOW_MODE] = resolvedMode.name
            prefs[KEY_EXPERIENCE_PREFERENCES_VERSION] = EXPERIENCE_PREFERENCES_VERSION

            if (prefs[KEY_ROOM_TARGET_ID].isNullOrBlank() && decision.target != null) {
                prefs[KEY_ROOM_TARGET_ID] = decision.target.id
                prefs[KEY_ROOM_TARGET_NAME] = decision.target.name
            }
        }

        val migrated = context.sonosPrefsDataStore.data.first()
        val migratedMode = parseRoomFollowMode(migrated[KEY_ROOM_FOLLOW_MODE])
            ?: error("Room-follow preference migration did not persist a mode")
        return readRoomFollowPreferences(
            migrated,
            migratedMode,
            migrated[KEY_EXPERIENCE_PREFERENCES_VERSION] ?: EXPERIENCE_PREFERENCES_VERSION
        )
    }

    suspend fun getRoomFollowPreferences(): RoomFollowPreferences =
        migrateExperiencePreferences()

    /** Updates room-follow mode and its applicable room atomically. */
    suspend fun saveRoomFollowPreferences(mode: RoomFollowMode, target: DefaultZone? = null) {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs[KEY_EXPERIENCE_PREFERENCES_VERSION] = EXPERIENCE_PREFERENCES_VERSION
            prefs[KEY_ROOM_FOLLOW_MODE] = mode.name
            if (target == null) {
                prefs.remove(KEY_ROOM_TARGET_ID)
                prefs.remove(KEY_ROOM_TARGET_NAME)
            } else {
                prefs[KEY_ROOM_TARGET_ID] = target.id
                prefs[KEY_ROOM_TARGET_NAME] = target.name
            }
        }
    }

    suspend fun getDefaultZone(): DefaultZone? {
        val prefs = context.sonosPrefsDataStore.data.first()
        val id = prefs[KEY_DEFAULT_ZONE_ID] ?: return null
        return DefaultZone(
            id = id,
            name = prefs[KEY_DEFAULT_ZONE_NAME] ?: ""
        )
    }

    suspend fun saveDefaultZone(id: String, name: String) {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs[KEY_DEFAULT_ZONE_ID] = id
            prefs[KEY_DEFAULT_ZONE_NAME] = name
            prefs[KEY_EXPERIENCE_PREFERENCES_VERSION] = EXPERIENCE_PREFERENCES_VERSION
            prefs[KEY_ROOM_FOLLOW_MODE] = RoomFollowMode.STAY_WITH_ROOM.name
            prefs[KEY_ROOM_TARGET_ID] = id
            prefs[KEY_ROOM_TARGET_NAME] = name
        }
        Log.d(TAG, "Saved default zone: $name ($id)")
    }

    suspend fun clearDefaultZone() {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs.remove(KEY_DEFAULT_ZONE_ID)
            prefs.remove(KEY_DEFAULT_ZONE_NAME)
            prefs[KEY_EXPERIENCE_PREFERENCES_VERSION] = EXPERIENCE_PREFERENCES_VERSION
            prefs[KEY_ROOM_FOLLOW_MODE] = RoomFollowMode.FOLLOW_PLAYING_MUSIC.name
            prefs.remove(KEY_ROOM_TARGET_ID)
            prefs.remove(KEY_ROOM_TARGET_NAME)
        }
        Log.d(TAG, "Cleared default zone")
    }

    // ──────────────────────────────────────────────
    // Preferred music service
    // ──────────────────────────────────────────────

    suspend fun getPreferredService(): String? {
        return context.sonosPrefsDataStore.data.first()[KEY_PREFERRED_SERVICE]
    }

    suspend fun savePreferredService(serviceId: String) {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs[KEY_PREFERRED_SERVICE] = serviceId
        }
        Log.d(TAG, "Saved preferred service: $serviceId")
    }

    suspend fun clearPreferredService() {
        context.sonosPrefsDataStore.edit { prefs ->
            prefs.remove(KEY_PREFERRED_SERVICE)
        }
        Log.d(TAG, "Cleared preferred service")
    }

    private fun parseRoomFollowMode(value: String?): RoomFollowMode? =
        value?.let { encoded -> RoomFollowMode.entries.firstOrNull { it.name == encoded } }

    private fun readLegacyDefaultZone(
        prefs: androidx.datastore.preferences.core.Preferences
    ): DefaultZone? {
        val id = prefs[KEY_DEFAULT_ZONE_ID]?.takeIf { it.isNotBlank() } ?: return null
        return DefaultZone(id, prefs[KEY_DEFAULT_ZONE_NAME].orEmpty())
    }

    private fun readRoomFollowPreferences(
        prefs: androidx.datastore.preferences.core.Preferences,
        mode: RoomFollowMode,
        version: Int
    ): RoomFollowPreferences {
        val targetId = prefs[KEY_ROOM_TARGET_ID]?.takeIf { it.isNotBlank() }
        val target = targetId?.let { DefaultZone(it, prefs[KEY_ROOM_TARGET_NAME].orEmpty()) }
        return RoomFollowPreferences(version = version, mode = mode, target = target)
    }
}
