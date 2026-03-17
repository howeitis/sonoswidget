package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

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
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val RATE_LIMIT_BACKOFF_MS = 30_000L
        private const val ERROR_BANNER_DISMISS_MS = 5_000L
        private const val OFFLINE_CHECK_INTERVAL_MS = 60_000L

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

    // Error state tracking
    private var rateLimitedUntilMs: Long = 0L
    private var permissionHintShown: Boolean = false
    private var errorBannerExpiresMs: Long = 0L

    // Offline speaker detection throttling (Task 3.7)
    // Pinging every zone member on every poll is expensive for battery.
    // Throttle to once every 60 seconds (same as zone group refresh).
    private var cachedOfflineSpeakers: Set<String> = emptySet()
    private var lastOfflineCheckMs: Long = 0L

    // ──────────────────────────────────────────────
    // Action debouncer (Task 3.4)
    // ──────────────────────────────────────────────

    /**
     * Queues and collapses widget taps during cold-start reconnection.
     * Drained automatically on successful reconnection via [drainDebouncedActions].
     */
    val actionDebouncer = ActionDebouncer()

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

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * Walks the tiered fallback chain:
     *   1. SSDP/mDNS (concurrent, 2s timeout)
     *   2. Manual speaker IPs
     *   3. Cloud API
     *
     * Returns true if any connection mode succeeded.
     */
    suspend fun discoverAndConnect(): Boolean {
        // Clear stale error states on fresh discovery
        clearErrorStates()

        Log.d(TAG, "discoverAndConnect: onWifi=${isOnWifi()}, hasPermission=${hasLocalNetworkPermission()}")

        if (tryLocalDiscovery()) return true
        if (tryManualIps()) return true

        // Show permission hint (one-time) if local discovery was skipped due to permission
        if (!hasLocalNetworkPermission() && !permissionHintShown) {
            permissionHintShown = true
        }

        if (tryCloudFallback()) return true

        Log.w(TAG, "All connection methods failed — entering offline state")
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

        Log.d(TAG, "Step 2: Trying ${manualIps.size} manual IP(s): $manualIps")
        for (ip in manualIps) {
            Log.d(TAG, "Step 2: Testing SOAP GetTransportInfo on $ip:1400...")
            val info = controller.getTransportInfo(ip)
            Log.d(TAG, "Step 2: $ip result: ${info?.state ?: "null (unreachable)"}")
            if (info != null) {
                activeSpeakerIp = ip
                activeSpeakerPort = 1400
                Log.d(TAG, "Step 2: Manual IP $ip responded")

                val zoneGroups = controller.getZoneGroupState(ip, 1400)
                if (zoneGroups != null) {
                    cachedZoneGroups = zoneGroups
                    lastZoneRefreshMs = System.currentTimeMillis()

                    // Find the coordinator for the group this IP belongs to.
                    // The manual IP may be a satellite/surround speaker — AVTransport
                    // commands (GetPositionInfo, play/pause) only work on the coordinator.
                    val coordinator = findCoordinatorByIp(zoneGroups, ip)
                    if (coordinator != null && coordinator.ip != ip) {
                        Log.d(TAG, "Step 2: Manual IP $ip is not coordinator, redirecting to ${coordinator.zoneName} @ ${coordinator.ip}")
                        activeSpeakerIp = coordinator.ip
                        activeSpeakerPort = coordinator.port
                        activeZoneId = coordinator.uuid
                    } else {
                        activeZoneId = coordinator?.uuid
                    }
                }

                preferences.saveActiveSpeaker(
                    activeZoneId ?: ip,
                    "Manual",
                    activeSpeakerIp ?: ip,
                    activeSpeakerPort
                )
                cacheConnection(ConnectionMode.LOCAL_MANUAL_IP)
                return true
            }
        }

        Log.d(TAG, "Step 2: No manual IPs responded")
        return false
    }

    private suspend fun tryCloudFallback(): Boolean {
        Log.d(TAG, "Step 3: Cloud isLoggedIn=${cloudController.isLoggedIn}")
        if (!cloudController.isLoggedIn) {
            Log.d(TAG, "Step 3: Cloud not available (not logged in)")
            return false
        }

        Log.d(TAG, "Step 3: Trying Sonos Cloud API...")
        val cloudState = cloudController.getPlaybackStatus()
        if (cloudState != null) {
            Log.d(TAG, "Step 3: Cloud API connected — ${cloudState.zones.size} zone(s), state=${cloudState.playbackState}")
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
        val wasReconnecting = _widgetState.value.isReconnecting

        // If connection cache expired and no local speaker, re-walk fallback chain
        if (!isConnectionCached() && activeSpeakerIp == null) {
            if (!discoverAndConnect()) {
                pushDisconnectedState()
                return _widgetState.value
            }
        }

        val result = when (activeConnectionMode) {
            ConnectionMode.CLOUD -> pollCloud()
            else -> pollLocal()
        }

        // Drain debounced actions on reconnection (Task 3.4)
        // Triggered when state transitions from isReconnecting → connected
        if (wasReconnecting && !_widgetState.value.isReconnecting
            && actionDebouncer.hasPendingActions
        ) {
            drainDebouncedActions()
        }

        return result
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
            // Start browsing from current track position to get "up next" items
            cachedQueue = controller.browseQueue(ip, port, startIndex = currentTrackNum, count = 20)
            Log.d(TAG, "Queue refreshed: ${cachedQueue?.size ?: 0} item(s), startIndex=$currentTrackNum")
        }

        cachedTransportSettings = controller.getTransportSettings(ip, port)
            ?: cachedTransportSettings

        // Fetch current media URI for source detection
        val mediaInfo = controller.getMediaInfo(ip, port)
        val currentSource = WidgetStateMapper.mapCurrentSource(mediaInfo?.currentUri)

        val queueItems = mapQueueItems(cachedQueue, currentTrackNum)

        // Detect firmware update: Sonos returns "TRANSITIONING" for extended periods
        // during firmware updates and no track metadata is available
        val isFirmwareUpdating = transportInfo?.state == "TRANSITIONING" &&
            positionInfo?.metadata == null &&
            positionInfo?.trackUri.isNullOrBlank()

        var state = WidgetStateMapper.buildState(
            transportInfo = transportInfo,
            positionInfo = positionInfo,
            volumeInfo = volumeInfo,
            zoneGroups = cachedZoneGroups,
            activeZoneId = activeZoneId,
            transportSettings = cachedTransportSettings,
            connectionMode = activeConnectionMode
        ).copy(
            queue = queueItems,
            currentSource = currentSource,
            isUpdating = isFirmwareUpdating,
            isOffline = false,
            isReconnecting = false,
            isRateLimited = System.currentTimeMillis() < rateLimitedUntilMs,
            showPermissionHint = false,
            errorMessage = expiredErrorMessage(),
            offlineSpeakerIds = detectOfflineSpeakers()
        )

        Log.d(TAG, "Album art URL: ${state.currentTrack.artUrl ?: "(null)"}")
        val artBitmap = AlbumArtLoader.loadAndCache(
            context = context,
            artUrl = state.currentTrack.artUrl,
            speakerIp = ip
        )
        Log.d(TAG, "Album art loaded: ${artBitmap != null}")
        if (artBitmap != null) {
            val palette = ThemeExtractor.extractFromBitmap(artBitmap)
            state = state.copy(colorPalette = palette)
        }

        cacheConnection(activeConnectionMode)

        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
        return state
    }

    /**
     * Detects speakers that are powered off / unreachable.
     *
     * Throttled to once every 60 seconds (Task 3.7 — battery optimization).
     * Pinging every zone member on every 2-second poll cycle was the largest
     * battery drain. Returns cached results between checks.
     *
     * Attempts a quick transport info check on each known zone member
     * that is not the currently active speaker.
     */
    private suspend fun detectOfflineSpeakers(): Set<String> {
        val now = System.currentTimeMillis()
        if (now - lastOfflineCheckMs < OFFLINE_CHECK_INTERVAL_MS) {
            return cachedOfflineSpeakers
        }

        val groups = cachedZoneGroups ?: return emptySet()
        val activeIp = activeSpeakerIp ?: return emptySet()
        val offline = mutableSetOf<String>()

        for (member in groups.flatMap { it.members }) {
            // Skip the active speaker — we already know it's online
            if (member.ip == activeIp) continue

            val info = controller.getTransportInfo(member.ip, member.port)
            if (info == null) {
                offline.add(member.uuid)
            }
        }

        cachedOfflineSpeakers = offline
        lastOfflineCheckMs = now
        return offline
    }

    private suspend fun pollCloud(): SonosWidgetState? {
        // Check if we're in a rate-limit backoff period
        if (System.currentTimeMillis() < rateLimitedUntilMs) {
            Log.d(TAG, "Cloud poll skipped — rate limited until ${rateLimitedUntilMs}")
            return _widgetState.value
        }

        val cloudState = cloudController.getPlaybackStatus()
        if (cloudState == null) {
            // Check if cloud controller reported a rate limit
            if (cloudController.isRateLimited) {
                Log.w(TAG, "Cloud API rate limited — backing off for ${RATE_LIMIT_BACKOFF_MS}ms")
                rateLimitedUntilMs = System.currentTimeMillis() + RATE_LIMIT_BACKOFF_MS
                val rateLimitState = _widgetState.value.copy(
                    isRateLimited = true,
                    errorMessage = null
                )
                _widgetState.value = rateLimitState
                WidgetStateStore.pushState(context, rateLimitState)
                return rateLimitState
            }

            Log.w(TAG, "Cloud poll failed")
            return _widgetState.value
        }

        // Success — clear rate limit and error states
        rateLimitedUntilMs = 0L
        val successState = cloudState.copy(
            isRateLimited = false,
            isOffline = false,
            isReconnecting = false,
            isUpdating = false,
            errorMessage = expiredErrorMessage()
        )

        cacheConnection(ConnectionMode.CLOUD)

        _widgetState.value = successState
        WidgetStateStore.pushState(context, successState)
        return successState
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

    /**
     * Pushes a disconnected state. If the cloud fallback also failed, this
     * becomes a full "Offline" state (no internet + no LAN). Otherwise,
     * it's a "Reconnecting…" state.
     *
     * When entering a fully offline state, any debounced actions are discarded
     * since there's no prospect of reconnection to drain them into.
     */
    suspend fun pushDisconnectedState() {
        val isFullyOffline = !cloudController.isLoggedIn || activeConnectionMode == ConnectionMode.DISCONNECTED

        // Discard debounced actions when entering terminal offline state
        if (isFullyOffline && actionDebouncer.hasPendingActions) {
            Log.d(TAG, "Fully offline — discarding debounced actions")
            actionDebouncer.clear()
        }

        val lastKnown = _widgetState.value
        val state = lastKnown.copy(
            connectionMode = ConnectionMode.DISCONNECTED,
            isReconnecting = !isFullyOffline,
            isOffline = isFullyOffline,
            isRateLimited = false,
            isUpdating = false,
            showPermissionHint = permissionHintShown && !hasLocalNetworkPermission(),
            errorMessage = expiredErrorMessage(),
            lastUpdatedMs = System.currentTimeMillis()
        )
        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
    }

    /** Clears transient error flags (called on successful connection/poll). */
    private fun clearErrorStates() {
        rateLimitedUntilMs = 0L
    }

    /**
     * Returns the current error message if it hasn't expired, or null.
     * Auto-dismisses after [ERROR_BANNER_DISMISS_MS].
     */
    private fun expiredErrorMessage(): String? {
        if (errorBannerExpiresMs == 0L) return null
        return if (System.currentTimeMillis() < errorBannerExpiresMs) {
            _widgetState.value.errorMessage
        } else {
            errorBannerExpiresMs = 0L
            null
        }
    }

    /** Sets a transient error message that auto-dismisses after 5 seconds. */
    private suspend fun pushErrorMessage(message: String) {
        errorBannerExpiresMs = System.currentTimeMillis() + ERROR_BANNER_DISMISS_MS
        val state = _widgetState.value.copy(errorMessage = message)
        _widgetState.value = state
        WidgetStateStore.pushState(context, state)
    }

    // ──────────────────────────────────────────────
    // Action debouncing API (Task 3.4)
    // ──────────────────────────────────────────────

    /**
     * Whether widget actions should be queued instead of executed immediately.
     * True when the widget is in reconnecting state and there is hope of
     * re-establishing a connection (cloud credentials exist).
     *
     * Called by [WidgetActions] callbacks to decide between immediate
     * execution and debounce queuing.
     */
    fun shouldDebounce(): Boolean = _widgetState.value.isReconnecting

    /**
     * Enqueues a widget action for deferred execution after reconnection.
     * Called by [WidgetActions] callbacks when [shouldDebounce] returns true.
     */
    fun enqueueAction(type: ActionDebouncer.ActionType, param: String? = null) {
        actionDebouncer.enqueue(type, param)
    }

    /**
     * Drains the debounce queue and executes collapsed net actions.
     *
     * Called after a successful reconnection (transition from isReconnecting
     * to connected). The 3-second stale intent timeout is applied at drain
     * time — any queued tap older than 3 seconds is silently discarded.
     *
     * Per PRD Section 7.3: "Tapping Skip 3x during 'Reconnecting…' results
     * in a single skip-3 command when connected."
     */
    private suspend fun drainDebouncedActions() {
        val actions = actionDebouncer.drain()
        if (actions.isEmpty()) return

        Log.d(TAG, "Executing ${actions.size} debounced action(s) after reconnection")

        for (action in actions) {
            try {
                when (action) {
                    is ActionDebouncer.CollapsedAction.PlayPauseToggle -> togglePlayPause()
                    is ActionDebouncer.CollapsedAction.Skip -> executeSkipDelta(action.delta)
                    is ActionDebouncer.CollapsedAction.VolumeAdjust -> {
                        val current = _widgetState.value.volume
                        setVolume((current + action.delta).coerceIn(0, 100))
                    }
                    is ActionDebouncer.CollapsedAction.ShuffleToggle -> toggleShuffle()
                    is ActionDebouncer.CollapsedAction.RepeatCycle -> {
                        repeat(action.count) { cycleRepeatMode() }
                    }
                    is ActionDebouncer.CollapsedAction.JumpToTrack -> playQueueItem(action.trackNr)
                    is ActionDebouncer.CollapsedAction.SwitchZone -> switchZone(action.zoneId)
                    is ActionDebouncer.CollapsedAction.ToggleGroup -> toggleSpeakerGroup(action.speakerUuid)
                    is ActionDebouncer.CollapsedAction.GroupAll -> groupAll()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute debounced action: $action", e)
            }
        }
    }

    /**
     * Executes a net skip delta by jumping directly to the target track
     * position in the queue (more efficient than calling next()/previous()
     * in a loop).
     *
     * Falls back to sequential next()/previous() calls if the current
     * track position is unknown.
     */
    private suspend fun executeSkipDelta(delta: Int) {
        if (delta == 0) return

        val currentTrackNum = lastQueueTrackNum
        if (currentTrackNum > 0) {
            // Use direct queue seek for efficiency: "single skip-N command"
            val target = (currentTrackNum + delta).coerceAtLeast(1)
            Log.d(TAG, "Skip delta $delta: seeking from track $currentTrackNum to $target")
            playQueueItem(target)
        } else {
            // Fallback: sequential skip calls
            Log.d(TAG, "Skip delta $delta: sequential (track position unknown)")
            if (delta > 0) {
                repeat(delta) { next() }
            } else {
                repeat(-delta) { previous() }
            }
        }
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

    /**
     * Routes a command through either local or cloud, with a 3-second timeout.
     * On timeout, reverts the optimistic UI update and shows an inline error.
     * On success, auto-retries once via poll.
     */
    private suspend fun routeCommand(
        local: suspend (ip: String, port: Int) -> Boolean,
        cloud: suspend () -> Boolean
    ): Boolean {
        val previousState = _widgetState.value

        val result = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                if (activeConnectionMode == ConnectionMode.CLOUD) {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command failed with exception", e)
            null
        }

        if (result == null) {
            // Timeout or exception — revert optimistic update
            Log.w(TAG, "Command timed out (>${COMMAND_TIMEOUT_MS}ms) — reverting UI")
            _widgetState.value = previousState
            WidgetStateStore.pushState(context, previousState)
            pushErrorMessage("Command timed out \u2014 tap to retry")
            return false
        }

        return result
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
        } else {
            pushErrorMessage("Command failed \u2014 tap to retry")
        }
        return success
    }

    private fun mapQueueItems(
        soapItems: List<QueueItemInfo>?,
        currentTrackNum: Int
    ): List<QueueItem> {
        if (soapItems.isNullOrEmpty()) return emptyList()
        // Items are already fetched starting from currentTrackNum, so no position filter needed
        val items = soapItems
            .take(20)
            .map { item ->
                QueueItem(
                    trackName = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.albumArtUri,
                    position = item.position
                )
            }
        Log.d(TAG, "Queue: ${items.size} upcoming items, first=${items.firstOrNull()?.trackName}")
        return items
    }

    private fun findCoordinator(
        zoneGroups: List<ZoneGroup>,
        discoveredSpeaker: DiscoveredSpeaker
    ): ZoneGroupMember? {
        return findCoordinatorByIp(zoneGroups, discoveredSpeaker.ip)
    }

    private fun findCoordinatorByIp(
        zoneGroups: List<ZoneGroup>,
        ip: String
    ): ZoneGroupMember? {
        for (group in zoneGroups) {
            val isMember = group.members.any { it.ip == ip }
            if (isMember) {
                return group.members.find { it.isCoordinator }
            }
        }
        return null
    }
}
