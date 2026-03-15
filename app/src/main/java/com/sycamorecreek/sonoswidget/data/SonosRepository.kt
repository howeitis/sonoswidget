package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.util.Log
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.ThemeExtractor
import com.sycamorecreek.sonoswidget.service.WidgetStateMapper
import com.sycamorecreek.sonoswidget.service.WidgetStateStore
import com.sycamorecreek.sonoswidget.sonos.local.DiscoveredSpeaker
import com.sycamorecreek.sonoswidget.sonos.local.LocalSonosController
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroupMember
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for Sonos state across the app.
 *
 * Coordinates between:
 *   - [LocalSonosController] — discovery + SOAP control (Tasks 1.3, 1.4)
 *   - [SonosPreferences] — persisted user settings (zone selection)
 *   - [WidgetStateStore] — pushes state to Glance widget
 *   - [PlaybackService] — calls poll/command methods from its loop
 *
 * The repository is the only class that directly talks to the controller.
 * All other layers (service, widget actions) go through this.
 *
 * Thread-safe: all mutable state is protected by StateFlow or synchronized.
 */
class SonosRepository private constructor(
    private val context: Context,
    private val controller: LocalSonosController = LocalSonosController(),
    private val preferences: SonosPreferences = SonosPreferences(context)
) {

    companion object {
        private const val TAG = "SonosRepository"
        private const val ZONE_REFRESH_INTERVAL_MS = 60_000L

        @Volatile
        private var instance: SonosRepository? = null

        /**
         * Returns the singleton SonosRepository instance.
         * Uses application context to prevent Activity leaks.
         */
        fun getInstance(context: Context): SonosRepository {
            return instance ?: synchronized(this) {
                instance ?: SonosRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Observable state
    // ──────────────────────────────────────────────

    private val _widgetState = MutableStateFlow(SonosWidgetState())

    /** Current widget state, observable by the service and other components. */
    val widgetState: StateFlow<SonosWidgetState> = _widgetState.asStateFlow()

    // ──────────────────────────────────────────────
    // Speaker target tracking
    // ──────────────────────────────────────────────

    private var activeSpeakerIp: String? = null
    private var activeSpeakerPort: Int = 1400
    private var activeZoneId: String? = null

    private var cachedZoneGroups: List<ZoneGroup>? = null
    private var lastZoneRefreshMs: Long = 0L

    /** Whether we have a speaker target to poll. */
    val isConnected: Boolean get() = activeSpeakerIp != null

    /** Current playback state for adaptive polling by the service. */
    val currentPlaybackState: PlaybackState get() = _widgetState.value.playbackState

    // ──────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────

    /**
     * Restores the active speaker from persisted preferences.
     * Called by PlaybackService on startup for fast reconnect.
     * Returns true if a saved speaker was found, false if fresh discovery is needed.
     */
    suspend fun restoreFromPreferences(): Boolean {
        val saved = preferences.activeSpeaker.first()
        if (saved != null) {
            activeSpeakerIp = saved.ip
            activeSpeakerPort = saved.port
            activeZoneId = saved.zoneId
            Log.d(TAG, "Restored active speaker from preferences: ${saved.zoneName} @ ${saved.ip}")
            return true
        }
        return false
    }

    // ──────────────────────────────────────────────
    // Discovery
    // ──────────────────────────────────────────────

    /** Whether the app has the required NEARBY_WIFI_DEVICES permission. */
    fun hasLocalNetworkPermission(): Boolean =
        controller.hasLocalNetworkPermission(context)

    /**
     * Discovers Sonos speakers and caches the group coordinator.
     * Persists the result for fast reconnect on next app start.
     */
    suspend fun discoverAndConnect(): Boolean {
        if (!hasLocalNetworkPermission()) {
            Log.w(TAG, "No network permission — cannot discover speakers")
            return false
        }

        Log.d(TAG, "Running speaker discovery...")
        val speakers = controller.discoverSpeakers(context)

        if (speakers.isEmpty()) {
            Log.w(TAG, "No speakers discovered")
            return false
        }

        // Use the first discovered speaker as our initial target
        val speaker = speakers.first()
        activeSpeakerIp = speaker.ip
        activeSpeakerPort = speaker.port
        activeZoneId = speaker.id
        Log.d(TAG, "Discovered: ${speaker.displayName} @ ${speaker.ip}:${speaker.port}")

        // Fetch zone topology and switch to coordinator if needed
        val zoneGroups = controller.getZoneGroupState(speaker.ip, speaker.port)
        if (zoneGroups != null) {
            cachedZoneGroups = zoneGroups
            lastZoneRefreshMs = System.currentTimeMillis()

            val coordinator = findCoordinator(zoneGroups, speaker)
            if (coordinator != null) {
                activeSpeakerIp = coordinator.ip
                activeSpeakerPort = coordinator.port
                activeZoneId = coordinator.uuid
                Log.d(TAG, "Targeting coordinator: ${coordinator.zoneName} @ ${coordinator.ip}")
            }
        }

        // Persist for fast reconnect
        val ip = activeSpeakerIp ?: return false
        val port = activeSpeakerPort
        val zoneId = activeZoneId ?: speaker.id
        val zoneName = cachedZoneGroups?.flatMap { it.members }
            ?.find { it.uuid == zoneId }?.zoneName ?: speaker.displayName
        preferences.saveActiveSpeaker(zoneId, zoneName, ip, port)

        return true
    }

    // ──────────────────────────────────────────────
    // Polling (called by PlaybackService)
    // ──────────────────────────────────────────────

    /**
     * Polls the active speaker and updates widget state.
     * Returns the new state, or null if no speaker is connected.
     */
    suspend fun pollAndUpdate(): SonosWidgetState? {
        val ip = activeSpeakerIp ?: return null
        val port = activeSpeakerPort

        // Poll playback state (3 concurrent SOAP calls)
        val (transportInfo, positionInfo, volumeInfo) =
            controller.pollPlaybackState(ip, port)

        // If all three fail, mark speaker as unreachable
        if (transportInfo == null && positionInfo == null && volumeInfo == null) {
            Log.w(TAG, "All polls failed — speaker at $ip may be unreachable")
            handleDisconnect()
            return _widgetState.value
        }

        // Refresh zone topology periodically
        val now = System.currentTimeMillis()
        if (now - lastZoneRefreshMs > ZONE_REFRESH_INTERVAL_MS || cachedZoneGroups == null) {
            cachedZoneGroups = controller.getZoneGroupState(ip, port)
            lastZoneRefreshMs = now
        }

        // Build base state (without palette — palette depends on art fetch)
        var state = WidgetStateMapper.buildState(
            transportInfo = transportInfo,
            positionInfo = positionInfo,
            volumeInfo = volumeInfo,
            zoneGroups = cachedZoneGroups,
            activeZoneId = activeZoneId,
            connectionMode = ConnectionMode.LOCAL_SSDP
        )

        // Load album art and extract dynamic color palette
        val artBitmap = AlbumArtLoader.loadAndCache(
            context = context,
            artUrl = state.currentTrack.artUrl,
            speakerIp = ip
        )
        if (artBitmap != null) {
            val palette = ThemeExtractor.extractFromBitmap(artBitmap)
            state = state.copy(colorPalette = palette)
        }

        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
        return state
    }

    /**
     * Pushes the current disconnected state to the widget.
     */
    suspend fun pushDisconnectedState() {
        val state = SonosWidgetState(
            connectionMode = ConnectionMode.DISCONNECTED,
            isReconnecting = true,
            lastUpdatedMs = System.currentTimeMillis()
        )
        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
    }

    private suspend fun handleDisconnect() {
        activeSpeakerIp = null
        preferences.clearActiveSpeaker()
        AlbumArtLoader.clearCache(context)
        pushDisconnectedState()
    }

    // ──────────────────────────────────────────────
    // Control commands (called by widget ActionCallbacks)
    // ──────────────────────────────────────────────

    /**
     * Executes a transport command on the active speaker.
     * After execution, triggers an immediate poll to reflect the change.
     *
     * Returns true if the command was sent successfully.
     */
    suspend fun play(): Boolean = executeAndPoll { ip, port ->
        controller.play(ip, port)
    }

    suspend fun pause(): Boolean = executeAndPoll { ip, port ->
        controller.pause(ip, port)
    }

    suspend fun togglePlayPause(): Boolean {
        return if (_widgetState.value.playbackState == PlaybackState.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    suspend fun next(): Boolean = executeAndPoll { ip, port ->
        controller.next(ip, port)
    }

    suspend fun previous(): Boolean = executeAndPoll { ip, port ->
        controller.previous(ip, port)
    }

    suspend fun seek(positionMs: Long): Boolean = executeAndPoll { ip, port ->
        controller.seek(ip, port, positionMs)
    }

    suspend fun setVolume(volume: Int): Boolean = executeAndPoll { ip, port ->
        controller.setVolume(ip, port, volume)
    }

    suspend fun setMute(muted: Boolean): Boolean = executeAndPoll { ip, port ->
        controller.setMute(ip, port, muted)
    }

    /**
     * Switches the active zone to a different speaker/group.
     * Looks up the coordinator IP from the cached zone topology.
     */
    suspend fun switchZone(zoneId: String): Boolean {
        val groups = cachedZoneGroups ?: return false

        // Find the group containing this zone member
        for (group in groups) {
            val member = group.members.find { it.uuid == zoneId }
            if (member != null) {
                val coordinator = group.members.find { it.isCoordinator } ?: member
                activeSpeakerIp = coordinator.ip
                activeSpeakerPort = coordinator.port
                activeZoneId = coordinator.uuid
                preferences.saveActiveSpeaker(
                    coordinator.uuid,
                    coordinator.zoneName,
                    coordinator.ip,
                    coordinator.port
                )
                Log.d(TAG, "Switched zone to: ${coordinator.zoneName} @ ${coordinator.ip}")

                // Immediately poll the new zone
                pollAndUpdate()
                return true
            }
        }

        Log.w(TAG, "Zone $zoneId not found in cached topology")
        return false
    }

    // ──────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────

    /**
     * Executes a command on the active speaker, then immediately polls
     * to reflect the new state in the widget.
     */
    private suspend fun executeAndPoll(
        command: suspend (ip: String, port: Int) -> Boolean
    ): Boolean {
        val ip = activeSpeakerIp
        val port = activeSpeakerPort

        if (ip == null) {
            Log.w(TAG, "No active speaker — command ignored")
            return false
        }

        val success = command(ip, port)
        if (success) {
            // Small delay for the speaker to process the command
            kotlinx.coroutines.delay(200)
            pollAndUpdate()
        }
        return success
    }

    private fun findCoordinator(
        zoneGroups: List<ZoneGroup>,
        discoveredSpeaker: DiscoveredSpeaker
    ): ZoneGroupMember? {
        for (group in zoneGroups) {
            val isMember = group.members.any { it.ip == discoveredSpeaker.ip }
            if (isMember) {
                return group.members.find { it.isCoordinator }
            }
        }
        return null
    }
}
