package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.util.Log
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.ThemeExtractor
import com.sycamorecreek.sonoswidget.service.WidgetStateMapper
import com.sycamorecreek.sonoswidget.service.WidgetStateStore
import com.sycamorecreek.sonoswidget.sonos.cloud.CloudSonosController
import com.sycamorecreek.sonoswidget.sonos.cloud.SonosOAuthManager
import com.sycamorecreek.sonoswidget.sonos.cloud.TokenStore
import com.sycamorecreek.sonoswidget.sonos.local.DiscoveredSpeaker
import com.sycamorecreek.sonoswidget.sonos.local.LocalSonosController
import com.sycamorecreek.sonoswidget.sonos.local.QueueItemInfo
import com.sycamorecreek.sonoswidget.sonos.local.TransportSettings
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroupMember
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.QueueItem
import com.sycamorecreek.sonoswidget.widget.RepeatMode
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Single source of truth for Sonos state across the app.
 *
 * Implements the tiered fallback chain per PRD Section 10.3:
 *   1. Local SSDP/mDNS discovery (concurrent, 2-second timeout)
 *   2. Manual IP fallback (user-configured speaker IPs)
 *   3. Cloud API fallback (Sonos Cloud REST API via OAuth)
 *
 * The active connection mode is cached for 30 seconds to avoid repeated
 * discovery attempts. A mode icon on the widget indicates which path is active.
 *
 * On every poll/command cycle, if the cached mode has expired or failed,
 * the repository walks the fallback chain again.
 */
class SonosRepository private constructor(
    private val context: Context,
    private val controller: LocalSonosController = LocalSonosController(),
    private val preferences: SonosPreferences = SonosPreferences(context)
) {

    companion object {
        private const val TAG = "SonosRepository"
        private const val ZONE_REFRESH_INTERVAL_MS = 60_000L
        private const val CONNECTION_CACHE_MS = 30_000L

        @Volatile
        private var instance: SonosRepository? = null

        fun getInstance(context: Context): SonosRepository {
            return instance ?: synchronized(this) {
                instance ?: SonosRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Cloud controller (lazy-initialized)
    // ──────────────────────────────────────────────

    private val tokenStore = TokenStore(context)
    private val oAuthManager = SonosOAuthManager(tokenStore)
    private val cloudController = CloudSonosController(oAuthManager)

    /** Whether the user has completed OAuth sign-in. */
    val isCloudAvailable: Boolean get() = cloudController.isLoggedIn

    // ──────────────────────────────────────────────
    // Observable state
    // ──────────────────────────────────────────────

    private val _widgetState = MutableStateFlow(SonosWidgetState())
    val widgetState: StateFlow<SonosWidgetState> = _widgetState.asStateFlow()

    // ──────────────────────────────────────────────
    // Connection mode tracking
    // ──────────────────────────────────────────────

    private var activeConnectionMode: ConnectionMode = ConnectionMode.DISCONNECTED
    private var lastConnectionValidMs: Long = 0L

    private fun isConnectionCached(): Boolean {
        return activeConnectionMode != ConnectionMode.DISCONNECTED &&
            System.currentTimeMillis() - lastConnectionValidMs < CONNECTION_CACHE_MS
    }

    private fun cacheConnection(mode: ConnectionMode) {
        activeConnectionMode = mode
        lastConnectionValidMs = System.currentTimeMillis()
    }

    // ──────────────────────────────────────────────
    // Speaker target tracking (local modes only)
    // ──────────────────────────────────────────────

    private var activeSpeakerIp: String? = null
    private var activeSpeakerPort: Int = 1400
    private var activeZoneId: String? = null

    private var cachedZoneGroups: List<ZoneGroup>? = null
    private var lastZoneRefreshMs: Long = 0L

    private var cachedQueue: List<QueueItemInfo>? = null
    private var lastQueueTrackNum: Int = -1
    private var cachedTransportSettings: TransportSettings? = null

    /** Whether we have a speaker target (local) or valid cloud session. */
    val isConnected: Boolean get() =
        activeSpeakerIp != null || activeConnectionMode == ConnectionMode.CLOUD

    val currentPlaybackState: PlaybackState get() = _widgetState.value.playbackState

    // ──────────────────────────────────────────────
    // Initialization
    // ──────────────────────────────────────────────

    suspend fun restoreFromPreferences(): Boolean {
        val saved = preferences.activeSpeaker.first()
        if (saved != null) {
            activeSpeakerIp = saved.ip
            activeSpeakerPort = saved.port
            activeZoneId = saved.zoneId
            cacheConnection(ConnectionMode.LOCAL_SSDP)
            Log.d(TAG, "Restored active speaker from preferences: ${saved.zoneName} @ ${saved.ip}")
            return true
        }
        return false
    }

    // ──────────────────────────────────────────────
    // Discovery — tiered fallback chain (PRD 10.3)
    // ──────────────────────────────────────────────

    fun hasLocalNetworkPermission(): Boolean =
        controller.hasLocalNetworkPermission(context)

    /**
     * Walks the tiered fallback chain:
     *   1. SSDP/mDNS (concurrent, 2s timeout)
     *   2. Manual speaker IPs
     *   3. Cloud API
     *
     * Returns true if any connection mode succeeded.
     */
    suspend fun discoverAndConnect(): Boolean {
        if (tryLocalDiscovery()) return true
        if (tryManualIps()) return true
        if (tryCloudFallback()) return true
        Log.w(TAG, "All connection methods failed")
        return false
    }

    private suspend fun tryLocalDiscovery(): Boolean {
        if (!hasLocalNetworkPermission()) {
            Log.w(TAG, "Step 1: No network permission — skipping local discovery")
            return false
        }

        Log.d(TAG, "Step 1: Running SSDP/mDNS discovery...")
        val speakers = controller.discoverSpeakers(context)

        if (speakers.isEmpty()) {
            Log.d(TAG, "Step 1: No speakers found via SSDP/mDNS")
            return false
        }

        val speaker = speakers.first()
        activeSpeakerIp = speaker.ip
        activeSpeakerPort = speaker.port
        activeZoneId = speaker.id
        Log.d(TAG, "Step 1: Discovered ${speaker.displayName} @ ${speaker.ip}:${speaker.port}")

        val zoneGroups = controller.getZoneGroupState(speaker.ip, speaker.port)
        if (zoneGroups != null) {
            cachedZoneGroups = zoneGroups
            lastZoneRefreshMs = System.currentTimeMillis()

            val coordinator = findCoordinator(zoneGroups, speaker)
            if (coordinator != null) {
                activeSpeakerIp = coordinator.ip
                activeSpeakerPort = coordinator.port
                activeZoneId = coordinator.uuid
            }
        }

        val ip = activeSpeakerIp ?: return false
        val zoneId = activeZoneId ?: speaker.id
        val zoneName = cachedZoneGroups?.flatMap { it.members }
            ?.find { it.uuid == zoneId }?.zoneName ?: speaker.displayName
        preferences.saveActiveSpeaker(zoneId, zoneName, ip, activeSpeakerPort)

        cacheConnection(ConnectionMode.LOCAL_SSDP)
        return true
    }

    private suspend fun tryManualIps(): Boolean {
        val manualIps = preferences.getManualIps()
        if (manualIps.isEmpty()) {
            Log.d(TAG, "Step 2: No manual IPs configured")
            return false
        }

        Log.d(TAG, "Step 2: Trying ${manualIps.size} manual IP(s)...")
        for (ip in manualIps) {
            val info = controller.getTransportInfo(ip)
            if (info != null) {
                activeSpeakerIp = ip
                activeSpeakerPort = 1400
                Log.d(TAG, "Step 2: Manual IP $ip responded")

                val zoneGroups = controller.getZoneGroupState(ip, 1400)
                if (zoneGroups != null) {
                    cachedZoneGroups = zoneGroups
                    lastZoneRefreshMs = System.currentTimeMillis()
                    val coordinator = zoneGroups.flatMap { it.members }
                        .find { it.isCoordinator && it.ip == ip }
                    activeZoneId = coordinator?.uuid
                }

                preferences.saveActiveSpeaker(
                    activeZoneId ?: ip, "Manual", ip, 1400
                )
                cacheConnection(ConnectionMode.LOCAL_MANUAL_IP)
                return true
            }
        }

        Log.d(TAG, "Step 2: No manual IPs responded")
        return false
    }

    private suspend fun tryCloudFallback(): Boolean {
        if (!cloudController.isLoggedIn) {
            Log.d(TAG, "Step 3: Cloud not available (not logged in)")
            return false
        }

        Log.d(TAG, "Step 3: Trying Sonos Cloud API...")
        val cloudState = cloudController.getPlaybackStatus()
        if (cloudState != null) {
            Log.d(TAG, "Step 3: Cloud API connected")
            activeSpeakerIp = null
            _widgetState.value = cloudState
            WidgetStateStore.pushState(context, cloudState)
            cacheConnection(ConnectionMode.CLOUD)
            return true
        }

        Log.d(TAG, "Step 3: Cloud API failed")
        return false
    }

    // ──────────────────────────────────────────────
    // Polling (called by PlaybackService)
    // ──────────────────────────────────────────────

    suspend fun pollAndUpdate(): SonosWidgetState? {
        // If connection cache expired and no local speaker, re-walk fallback chain
        if (!isConnectionCached() && activeSpeakerIp == null) {
            if (!discoverAndConnect()) {
                pushDisconnectedState()
                return _widgetState.value
            }
        }

        return when (activeConnectionMode) {
            ConnectionMode.CLOUD -> pollCloud()
            else -> pollLocal()
        }
    }

    private suspend fun pollLocal(): SonosWidgetState? {
        val ip = activeSpeakerIp ?: return null
        val port = activeSpeakerPort

        val (transportInfo, positionInfo, volumeInfo) =
            controller.pollPlaybackState(ip, port)

        if (transportInfo == null && positionInfo == null && volumeInfo == null) {
            Log.w(TAG, "All local polls failed — speaker at $ip may be unreachable")
            return handleLocalFailure()
        }

        val now = System.currentTimeMillis()
        if (now - lastZoneRefreshMs > ZONE_REFRESH_INTERVAL_MS || cachedZoneGroups == null) {
            cachedZoneGroups = controller.getZoneGroupState(ip, port)
            lastZoneRefreshMs = now
        }

        val currentTrackNum = positionInfo?.trackNum ?: 0
        if (currentTrackNum != lastQueueTrackNum) {
            lastQueueTrackNum = currentTrackNum
            cachedQueue = controller.browseQueue(ip, port)
            Log.d(TAG, "Queue refreshed: ${cachedQueue?.size ?: 0} item(s)")
        }

        cachedTransportSettings = controller.getTransportSettings(ip, port)
            ?: cachedTransportSettings

        val queueItems = mapQueueItems(cachedQueue, currentTrackNum)

        var state = WidgetStateMapper.buildState(
            transportInfo = transportInfo,
            positionInfo = positionInfo,
            volumeInfo = volumeInfo,
            zoneGroups = cachedZoneGroups,
            activeZoneId = activeZoneId,
            transportSettings = cachedTransportSettings,
            connectionMode = activeConnectionMode
        ).copy(queue = queueItems)

        val artBitmap = AlbumArtLoader.loadAndCache(
            context = context,
            artUrl = state.currentTrack.artUrl,
            speakerIp = ip
        )
        if (artBitmap != null) {
            val palette = ThemeExtractor.extractFromBitmap(artBitmap)
            state = state.copy(colorPalette = palette)
        }

        cacheConnection(activeConnectionMode)

        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
        return state
    }

    private suspend fun pollCloud(): SonosWidgetState? {
        val cloudState = cloudController.getPlaybackStatus()
        if (cloudState == null) {
            Log.w(TAG, "Cloud poll failed")
            return _widgetState.value
        }

        cacheConnection(ConnectionMode.CLOUD)

        _widgetState.value = cloudState
        WidgetStateStore.pushState(context, cloudState)
        return cloudState
    }

    /**
     * Handles a local connection failure by attempting the fallback chain.
     * Tries cloud before fully disconnecting.
     */
    private suspend fun handleLocalFailure(): SonosWidgetState? {
        activeSpeakerIp = null
        activeConnectionMode = ConnectionMode.DISCONNECTED

        if (tryCloudFallback()) {
            Log.d(TAG, "Switched to cloud after local failure")
            return _widgetState.value
        }

        preferences.clearActiveSpeaker()
        AlbumArtLoader.clearCache(context)
        pushDisconnectedState()
        return _widgetState.value
    }

    suspend fun pushDisconnectedState() {
        val state = SonosWidgetState(
            connectionMode = ConnectionMode.DISCONNECTED,
            isReconnecting = true,
            lastUpdatedMs = System.currentTimeMillis()
        )
        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
    }

    // ──────────────────────────────────────────────
    // Control commands (routed by connection mode)
    // ──────────────────────────────────────────────

    suspend fun play(): Boolean = routeCommand(
        local = { ip, port -> controller.play(ip, port) },
        cloud = { cloudController.play() }
    )

    suspend fun pause(): Boolean = routeCommand(
        local = { ip, port -> controller.pause(ip, port) },
        cloud = { cloudController.pause() }
    )

    suspend fun togglePlayPause(): Boolean {
        return if (_widgetState.value.playbackState == PlaybackState.PLAYING) {
            pause()
        } else {
            play()
        }
    }

    suspend fun next(): Boolean = routeCommand(
        local = { ip, port -> controller.next(ip, port) },
        cloud = { cloudController.next() }
    )

    suspend fun previous(): Boolean = routeCommand(
        local = { ip, port -> controller.previous(ip, port) },
        cloud = { cloudController.previous() }
    )

    suspend fun seek(positionMs: Long): Boolean = routeCommand(
        local = { ip, port -> controller.seek(ip, port, positionMs) },
        cloud = { false }
    )

    suspend fun setVolume(volume: Int): Boolean = routeCommand(
        local = { ip, port -> controller.setVolume(ip, port, volume) },
        cloud = { cloudController.setVolume(volume) }
    )

    suspend fun setMute(muted: Boolean): Boolean = routeCommand(
        local = { ip, port -> controller.setMute(ip, port, muted) },
        cloud = { false }
    )

    suspend fun switchZone(zoneId: String): Boolean {
        if (activeConnectionMode == ConnectionMode.CLOUD) {
            cloudController.setActiveGroup(zoneId)
            return pollCloud() != null
        }

        val groups = cachedZoneGroups ?: return false
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
                pollAndUpdate()
                return true
            }
        }

        Log.w(TAG, "Zone $zoneId not found in cached topology")
        return false
    }

    // ──────────────────────────────────────────────
    // Shuffle & Repeat (Task 2.4)
    // ──────────────────────────────────────────────

    suspend fun toggleShuffle(): Boolean {
        val current = _widgetState.value
        val newShuffle = !current.shuffleEnabled

        if (activeConnectionMode == ConnectionMode.CLOUD) {
            val ok = cloudController.setShuffle(newShuffle)
            if (ok) pollCloud()
            return ok
        }

        val playMode = WidgetStateMapper.buildPlayMode(newShuffle, current.repeatMode)
        Log.d(TAG, "Setting play mode: $playMode (shuffle=$newShuffle, repeat=${current.repeatMode})")
        return executeLocalAndPoll { ip, port ->
            controller.setPlayMode(ip, port, playMode)
        }
    }

    suspend fun cycleRepeatMode(): Boolean {
        val current = _widgetState.value
        val newRepeat = when (current.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }

        if (activeConnectionMode == ConnectionMode.CLOUD) {
            val ok = cloudController.setRepeat(newRepeat)
            if (ok) pollCloud()
            return ok
        }

        val playMode = WidgetStateMapper.buildPlayMode(current.shuffleEnabled, newRepeat)
        Log.d(TAG, "Setting play mode: $playMode (shuffle=${current.shuffleEnabled}, repeat=$newRepeat)")
        return executeLocalAndPoll { ip, port ->
            controller.setPlayMode(ip, port, playMode)
        }
    }

    // ──────────────────────────────────────────────
    // Queue control (Task 2.3 — local only)
    // ──────────────────────────────────────────────

    suspend fun playQueueItem(trackNr: Int): Boolean = executeLocalAndPoll { ip, port ->
        controller.seekToTrack(ip, port, trackNr)
    }

    // ──────────────────────────────────────────────
    // Speaker grouping (Task 2.2 — local only)
    // ──────────────────────────────────────────────

    suspend fun toggleSpeakerGroup(speakerUuid: String): Boolean {
        if (activeConnectionMode == ConnectionMode.CLOUD) {
            Log.w(TAG, "Speaker grouping not supported in cloud mode")
            return false
        }

        val groups = cachedZoneGroups ?: return false
        val activeId = activeZoneId ?: return false

        val activeGroup = groups.find { group ->
            group.members.any { it.uuid == activeId }
        } ?: return false
        val coordinatorUuid = activeGroup.coordinatorId

        val targetMember = groups.flatMap { it.members }.find { it.uuid == speakerUuid }
            ?: return false

        if (speakerUuid == coordinatorUuid) {
            Log.w(TAG, "Cannot ungroup the coordinator — switch zones instead")
            return false
        }

        val isCurrentlyGrouped = activeGroup.members.any { it.uuid == speakerUuid }

        val previousState = _widgetState.value
        _widgetState.value = buildOptimisticGroupState(
            previousState, speakerUuid, !isCurrentlyGrouped, activeGroup.groupId
        )
        WidgetStateStore.pushState(context, _widgetState.value)

        val success = if (isCurrentlyGrouped) {
            Log.d(TAG, "Ungrouping ${targetMember.zoneName} from ${activeGroup.coordinatorId}")
            controller.removeFromGroup(targetMember.ip, targetMember.port)
        } else {
            Log.d(TAG, "Grouping ${targetMember.zoneName} into ${activeGroup.coordinatorId}")
            controller.addToGroup(targetMember.ip, targetMember.port, coordinatorUuid)
        }

        if (success) {
            kotlinx.coroutines.delay(500)
            val ip = activeSpeakerIp ?: return true
            cachedZoneGroups = controller.getZoneGroupState(ip, activeSpeakerPort)
            lastZoneRefreshMs = System.currentTimeMillis()
            pollAndUpdate()
        } else {
            Log.w(TAG, "Group toggle failed — reverting optimistic state")
            _widgetState.value = previousState
            WidgetStateStore.pushState(context, previousState)
        }

        return success
    }

    suspend fun groupAll(): Boolean {
        if (activeConnectionMode == ConnectionMode.CLOUD) {
            Log.w(TAG, "Group all not supported in cloud mode")
            return false
        }

        val groups = cachedZoneGroups ?: return false
        val activeId = activeZoneId ?: return false

        val activeGroup = groups.find { group ->
            group.members.any { it.uuid == activeId }
        } ?: return false
        val coordinatorUuid = activeGroup.coordinatorId

        val ungroupedMembers = groups.flatMap { group ->
            if (group.coordinatorId == coordinatorUuid) emptyList()
            else group.members
        }

        if (ungroupedMembers.isEmpty()) {
            Log.d(TAG, "All speakers already grouped")
            return true
        }

        val previousState = _widgetState.value
        val allGroupedZones = previousState.zones.map { zone ->
            zone.copy(groupId = activeGroup.groupId, isGroupCoordinator = zone.id == coordinatorUuid)
        }
        _widgetState.value = previousState.copy(zones = allGroupedZones)
        WidgetStateStore.pushState(context, _widgetState.value)

        var allSucceeded = true
        for (member in ungroupedMembers) {
            Log.d(TAG, "Grouping ${member.zoneName} into $coordinatorUuid")
            val ok = controller.addToGroup(member.ip, member.port, coordinatorUuid)
            if (!ok) {
                Log.w(TAG, "Failed to group ${member.zoneName}")
                allSucceeded = false
            }
        }

        kotlinx.coroutines.delay(500)
        val ip = activeSpeakerIp ?: return allSucceeded
        cachedZoneGroups = controller.getZoneGroupState(ip, activeSpeakerPort)
        lastZoneRefreshMs = System.currentTimeMillis()
        pollAndUpdate()

        return allSucceeded
    }

    private fun buildOptimisticGroupState(
        currentState: SonosWidgetState,
        speakerUuid: String,
        addToGroup: Boolean,
        activeGroupId: String
    ): SonosWidgetState {
        val updatedZones = currentState.zones.map { zone ->
            if (zone.id == speakerUuid) {
                if (addToGroup) {
                    zone.copy(groupId = activeGroupId, isGroupCoordinator = false)
                } else {
                    zone.copy(groupId = "${speakerUuid}:standalone", isGroupCoordinator = true)
                }
            } else zone
        }
        return currentState.copy(zones = updatedZones)
    }

    // ──────────────────────────────────────────────
    // Command routing internals
    // ──────────────────────────────────────────────

    private suspend fun routeCommand(
        local: suspend (ip: String, port: Int) -> Boolean,
        cloud: suspend () -> Boolean
    ): Boolean {
        return if (activeConnectionMode == ConnectionMode.CLOUD) {
            val ok = cloud()
            if (ok) {
                kotlinx.coroutines.delay(200)
                pollCloud()
            }
            ok
        } else {
            executeLocalAndPoll(local)
        }
    }

    private suspend fun executeLocalAndPoll(
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
            kotlinx.coroutines.delay(200)
            pollAndUpdate()
        }
        return success
    }

    private fun mapQueueItems(
        soapItems: List<QueueItemInfo>?,
        currentTrackNum: Int
    ): List<QueueItem> {
        if (soapItems.isNullOrEmpty()) return emptyList()
        return soapItems
            .filter { it.position > currentTrackNum }
            .take(20)
            .map { item ->
                QueueItem(
                    trackName = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.albumArtUri,
                    position = item.position
                )
            }
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
