package com.sycamorecreek.sonoswidget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.ThemeExtractor
import com.sycamorecreek.sonoswidget.service.WidgetBackgroundRenderer
import com.sycamorecreek.sonoswidget.service.WidgetStateMapper
import com.sycamorecreek.sonoswidget.service.WidgetStateStore
import com.sycamorecreek.sonoswidget.sonos.cloud.CloudSonosController
import com.sycamorecreek.sonoswidget.sonos.cloud.SonosOAuthManager
import com.sycamorecreek.sonoswidget.sonos.cloud.TokenStore
import com.sycamorecreek.sonoswidget.sonos.local.DiscoveredSpeaker
import com.sycamorecreek.sonoswidget.sonos.local.CommandTransportOutcome
import com.sycamorecreek.sonoswidget.sonos.local.FavoriteInfo
import com.sycamorecreek.sonoswidget.sonos.local.LocalSonosController
import com.sycamorecreek.sonoswidget.sonos.local.QueueItemInfo
import com.sycamorecreek.sonoswidget.sonos.local.TransportSettings
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroupMember
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.Favorite
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.PendingWidgetOperation
import com.sycamorecreek.sonoswidget.widget.QueueItem
import com.sycamorecreek.sonoswidget.widget.RepeatMode
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.WidgetOperationType
import com.sycamorecreek.sonoswidget.widget.WidgetOperationPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
        // Play mode and current-source URI change rarely; no need to refetch every
        // 2-second poll. Throttle these auxiliary queries to limit SOAP traffic/battery.
        private const val METADATA_REFRESH_INTERVAL_MS = 15_000L
        // Favorites change rarely; refresh them only every few minutes.
        private const val FAVORITES_REFRESH_INTERVAL_MS = 300_000L
        private const val CONNECTION_CACHE_MS = 30_000L
        private const val COMMAND_TIMEOUT_MS = 3_000L
        // How long an optimistic transport/volume value shadows incoming poll
        // results, so a poll already in flight when the user tapped can't flash the
        // pre-command value back into the UI before the speaker catches up.
        private const val OPTIMISTIC_HOLD_MS = 2_000L
        private const val RATE_LIMIT_BACKOFF_MS = 30_000L
        private const val ERROR_BANNER_DISMISS_MS = 5_000L
        private const val OFFLINE_CHECK_INTERVAL_MS = 60_000L
        private const val STOPPED_RESCAN_THRESHOLD_MS = 30_000L

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
    /** Increments for every explicit room intent, invalidating older callbacks. */
    private var roomTargetGeneration: Long = 0L

    private var cachedZoneGroups: List<ZoneGroup>? = null
    private var lastZoneRefreshMs: Long = 0L
    private var forceZoneRefreshUntilMs: Long = 0L

    private var cachedQueue: List<QueueItemInfo>? = null
    private var lastQueueTrackNum: Int = -1
    private var cachedTransportSettings: TransportSettings? = null
    private var lastTransportSettingsMs: Long = 0L
    private var lastMediaInfoMs: Long = 0L
    private var cachedSource: String = ""
    // Mute changes rarely, so it's polled on the slow metadata cadence rather
    // than every cycle. [muteDirty] forces a refetch on the poll that follows a
    // SetMute command so the widget reflects the toggle immediately.
    private var cachedMuted: Boolean = false
    private var lastMuteMs: Long = 0L
    private var muteDirty: Boolean = false
    private var cachedFavorites: List<FavoriteInfo>? = null
    private var lastFavoritesMs: Long = 0L

    // Guards against re-running local discovery more than once within a single
    // poll cycle (see [handleLocalFailure]). Reset at the top of each poll.
    private var localRecoveryAttempted = false

    // Optimistic UI overrides for transport/volume. When the user taps a control
    // we flip the widget immediately, then hold that value over poll results for a
    // short window (see [OPTIMISTIC_HOLD_MS]) so the UI feels instant and doesn't
    // flicker. Cleared once the speaker's real state agrees or the window expires.
    private var optimisticPlayback: PlaybackState? = null
    private var optimisticPlaybackUntilMs: Long = 0L
    private var optimisticVolume: Int? = null
    private var optimisticVolumeUntilMs: Long = 0L
    /** Serializes relative volume intent so rapid widget callbacks cannot race. */
    private val volumeIntentMutex = Mutex()
    /** One shared refresh gate for service, workers, companion, and widget taps. */
    private val refreshMutex = Mutex()
    /** A request received mid-refresh earns one immediate follow-up cycle. */
    private val refreshRequested = AtomicBoolean(false)

    // Palette extraction is expensive; only recompute when the art URL changes.
    private var lastPaletteArtUrl: String? = null
    private var cachedPalette: com.sycamorecreek.sonoswidget.widget.WidgetColorPalette? = null
    /** The media identity currently represented by the shared artwork files. */
    private var publishedArtworkVersion: String? = null

    // Error state tracking
    private var rateLimitedUntilMs: Long = 0L
    private var permissionHintShown: Boolean = false
    private var errorBannerExpiresMs: Long = 0L

    // Offline speaker detection throttling (Task 3.7)
    // Pinging every zone member on every poll is expensive for battery.
    // Throttle to once every 60 seconds (same as zone group refresh).
    private var cachedOfflineSpeakers: Set<String> = emptySet()
    private var lastOfflineCheckMs: Long = 0L

    // Smart speaker re-evaluation: tracks how long the current speaker has been STOPPED
    private var stoppedSinceMs: Long = 0L

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
     *   0. Saved speaker IP (single unicast probe — fast and immune to
     *      multicast flakiness)
     *   1. SSDP/mDNS (concurrent, 2s timeout)
     *   2. Manual speaker IPs
     *   3. Cloud API
     *
     * Returns true if any connection mode succeeded.
     */
    suspend fun discoverAndConnect(): Boolean {
        // Must run before discovery or any new preference write so legacy installs
        // retain their historical follow behaviour. Read failures intentionally
        // propagate to the caller for a later retry rather than being treated as
        // a fresh install.
        preferences.migrateExperiencePreferences()

        // Clear stale error states on fresh discovery
        clearErrorStates()

        Log.d(TAG, "discoverAndConnect: onWifi=${isOnWifi()}, hasPermission=${hasLocalNetworkPermission()}")

        if (trySavedSpeaker()) return true
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

    /**
     * Step 0: probe the last-known speaker IP directly before any multicast
     * discovery. SSDP/mDNS can fail transiently (mesh networks, multicast
     * filtering, radio wake-up), but a unicast HTTP request to a known-good
     * IP is cheap (~50ms) and far more reliable. This is what makes the
     * widget reconnect near-instantly when the phone rejoins home Wi-Fi.
     */
    private suspend fun trySavedSpeaker(): Boolean {
        if (!isOnWifi()) return false
        val saved = preferences.activeSpeaker.first() ?: return false

        Log.d(TAG, "Step 0: Probing saved speaker ${saved.zoneName} @ ${saved.ip}:${saved.port}")
        val info = controller.getTransportInfo(saved.ip, saved.port)
        if (info == null) {
            Log.d(TAG, "Step 0: Saved speaker not reachable")
            return false
        }

        activeSpeakerIp = saved.ip
        activeSpeakerPort = saved.port
        activeZoneId = saved.zoneId

        // Refresh topology and re-point at the group coordinator in case
        // grouping changed while we were away.
        val zoneGroups = controller.getZoneGroupState(saved.ip, saved.port)
        if (zoneGroups != null && zoneGroups.isNotEmpty()) {
            cachedZoneGroups = zoneGroups
            lastZoneRefreshMs = System.currentTimeMillis()

            val coordinator = zoneGroups.firstOrNull { g ->
                g.members.any { it.uuid == saved.zoneId }
            }?.members?.find { it.isCoordinator }
            if (coordinator != null && coordinator.ip != saved.ip) {
                Log.d(TAG, "Step 0: Redirecting to group coordinator ${coordinator.zoneName} @ ${coordinator.ip}")
                activeSpeakerIp = coordinator.ip
                activeSpeakerPort = coordinator.port
                activeZoneId = coordinator.uuid
                preferences.saveActiveSpeaker(
                    coordinator.uuid, coordinator.zoneName, coordinator.ip, coordinator.port
                )
            }
        }

        stoppedSinceMs = 0L
        cacheConnection(ConnectionMode.LOCAL_SSDP)
        Log.d(TAG, "Step 0: Reconnected to saved speaker")
        return true
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

        // Use any discovered speaker to fetch the full zone group topology.
        // Every speaker on the network returns the same topology.
        val anySpeaker = speakers.first()
        Log.d(TAG, "Step 1: Discovered ${speakers.size} speaker(s), querying topology via ${anySpeaker.displayName} @ ${anySpeaker.ip}:${anySpeaker.port}")

        val zoneGroups = controller.getZoneGroupState(anySpeaker.ip, anySpeaker.port)
        if (zoneGroups != null && zoneGroups.isNotEmpty()) {
            cachedZoneGroups = zoneGroups
            lastZoneRefreshMs = System.currentTimeMillis()

            // 1. Check if we have a saved active speaker in preferences
            val savedActive = preferences.activeSpeaker.first()
            val savedCoordinator = if (savedActive != null) {
                // Find the coordinator for the group the saved speaker belongs to
                zoneGroups.firstOrNull { g ->
                    g.members.any { m -> m.uuid == savedActive.zoneId }
                }?.members?.find { it.isCoordinator }
            } else null

            val roomFollow = preferences.getRoomFollowPreferences()

            // A saved target remains authoritative in stay mode. Follow mode only
            // retains it when it is actively playing; otherwise another playing
            // coordinator is eligible to become the shared target.
            val verifiedSavedCoordinator = if (savedCoordinator != null) {
                val info = controller.getTransportInfo(savedCoordinator.ip, savedCoordinator.port)
                if (info != null && (
                        roomFollow.mode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM ||
                            info.state == "PLAYING"
                    )) savedCoordinator else null
            } else null

            // Stay mode with an explicit target must never silently redirect to
            // another room. Follow mode chooses the best playing coordinator.
            val bestCoordinator = when {
                roomFollow.mode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM &&
                    roomFollow.target != null -> {
                    resolvePreferredCoordinator(zoneGroups, roomFollow.target)
                }
                roomFollow.mode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM -> {
                    verifiedSavedCoordinator
                }
                else -> verifiedSavedCoordinator ?: findBestCoordinator(zoneGroups)
            }
            if (bestCoordinator != null) {
                activeSpeakerIp = bestCoordinator.ip
                activeSpeakerPort = bestCoordinator.port
                activeZoneId = bestCoordinator.uuid
                Log.d(TAG, "Step 1: Selected coordinator ${bestCoordinator.zoneName} @ ${bestCoordinator.ip}")
            } else if (
                roomFollow.mode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM &&
                    roomFollow.target != null
            ) {
                Log.w(TAG, "Selected room '${roomFollow.target.name}' is unavailable; not redirecting")
                return false
            } else {
                // Fallback: redirect discovered speaker to its group coordinator
                val coordinator = findCoordinator(zoneGroups, anySpeaker)
                if (coordinator != null) {
                    activeSpeakerIp = coordinator.ip
                    activeSpeakerPort = coordinator.port
                    activeZoneId = coordinator.uuid
                } else {
                    activeSpeakerIp = anySpeaker.ip
                    activeSpeakerPort = anySpeaker.port
                    activeZoneId = anySpeaker.id
                }
            }
        } else {
            // No zone groups — use the discovered speaker directly
            activeSpeakerIp = anySpeaker.ip
            activeSpeakerPort = anySpeaker.port
            activeZoneId = anySpeaker.id
        }

        val ip = activeSpeakerIp ?: return false
        val zoneId = activeZoneId ?: anySpeaker.id
        val zoneName = cachedZoneGroups?.flatMap { it.members }
            ?.find { it.uuid == zoneId }?.zoneName ?: anySpeaker.displayName
        preferences.saveActiveSpeaker(zoneId, zoneName, ip, activeSpeakerPort)

        stoppedSinceMs = 0L
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
        if (!refreshMutex.tryLock()) {
            refreshRequested.set(true)
            Log.d(TAG, "Refresh request coalesced; one follow-up reconciliation is queued")
            return _widgetState.value
        }
        var result: SonosWidgetState?
        try {
            do {
                refreshRequested.set(false)
                result = pollAndUpdateLocked()
            } while (refreshRequested.getAndSet(false))
            return result
        } finally {
            refreshMutex.unlock()
        }
    }

    private suspend fun pollAndUpdateLocked(): SonosWidgetState? {
        val wasReconnecting = _widgetState.value.isReconnecting
        localRecoveryAttempted = false

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
        // Never replay a command issued while disconnected. Older versions could
        // leave process-local debounced actions behind; discard them on recovery.
        if (wasReconnecting && !_widgetState.value.isReconnecting
            && actionDebouncer.hasPendingActions
        ) {
            actionDebouncer.clear()
        }

        return result
    }

    private suspend fun pollLocal(): SonosWidgetState? {
        val ip = activeSpeakerIp ?: return null
        val port = activeSpeakerPort
        val targetGeneration = roomTargetGeneration
        val targetZoneId = activeZoneId

        val (transportInfo, positionInfo, volumeInfo) =
            controller.pollPlaybackState(ip, port)

        if (!isPollTargetCurrent(targetGeneration, targetZoneId, ip)) return null

        if (transportInfo == null && positionInfo == null && volumeInfo == null) {
            Log.w(TAG, "All local polls failed — speaker at $ip may be unreachable")
            return handleLocalFailure()
        }

        // ── Smart speaker re-evaluation ──
        // If the current coordinator is STOPPED for 30+ seconds, scan all
        // coordinators for one that is PLAYING and switch to it automatically.
        val transportState = transportInfo?.state
        if (transportState == "STOPPED" || transportState == "NO_MEDIA_PRESENT") {
            val now2 = System.currentTimeMillis()
            if (stoppedSinceMs == 0L) {
                stoppedSinceMs = now2
            } else if (
                preferences.getRoomFollowPreferences().mode == SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC &&
                    now2 - stoppedSinceMs >= STOPPED_RESCAN_THRESHOLD_MS
            ) {
                Log.d(TAG, "Speaker STOPPED for ${(now2 - stoppedSinceMs) / 1000}s — scanning for playing coordinator")
                stoppedSinceMs = now2 // Reset to wait another 30s before next scan

                val freshGroups = controller.getZoneGroupState(ip, port)
                if (freshGroups != null && freshGroups.isNotEmpty()) {
                    cachedZoneGroups = freshGroups
                    lastZoneRefreshMs = now2

                    val better = findBestCoordinator(freshGroups)
                    if (better != null && better.uuid != activeZoneId) {
                        Log.d(TAG, "Switching to playing coordinator: ${better.zoneName} @ ${better.ip}")
                        activeSpeakerIp = better.ip
                        activeSpeakerPort = better.port
                        activeZoneId = better.uuid
                        preferences.saveActiveSpeaker(better.uuid, better.zoneName, better.ip, better.port)
                        return pollLocal() // Re-poll with the new speaker
                    }
                }
            }
        } else {
            // Speaker is PLAYING or PAUSED — reset the stopped timer
            stoppedSinceMs = 0L
        }

        val now = System.currentTimeMillis()
        if (now - lastZoneRefreshMs > ZONE_REFRESH_INTERVAL_MS || cachedZoneGroups == null || now < forceZoneRefreshUntilMs) {
            val freshGroups = controller.getZoneGroupState(ip, port)
            if (freshGroups != null) {
                cachedZoneGroups = freshGroups
                if (now >= forceZoneRefreshUntilMs) {
                    lastZoneRefreshMs = now
                }
            }
        }

        val currentTrackNum = positionInfo?.trackNum ?: 0
        val trackChanged = currentTrackNum != lastQueueTrackNum

        // Play mode (shuffle/repeat) changes rarely — refetch on a slow cadence
        // rather than on every 2s poll cycle.
        if (now - lastTransportSettingsMs > METADATA_REFRESH_INTERVAL_MS || cachedTransportSettings == null) {
            cachedTransportSettings = controller.getTransportSettings(ip, port)
                ?: cachedTransportSettings
            lastTransportSettingsMs = now
        }

        // Current media URI for source detection — also throttled. Always refresh
        // on a track change so the source label tracks the now-playing item.
        // (lastMediaInfoMs starts at 0, so the first poll always fetches.)
        if (trackChanged || now - lastMediaInfoMs > METADATA_REFRESH_INTERVAL_MS) {
            val mediaInfo = controller.getMediaInfo(ip, port)
            cachedSource = WidgetStateMapper.mapCurrentSource(mediaInfo?.currentUri)
            lastMediaInfoMs = now
        }
        val currentSource = cachedSource

        // When the active room is grouped with others, volume and mute act on
        // the whole group (GroupRenderingControl on the coordinator) rather than
        // just the coordinator speaker.
        val grouped = isActiveGroupGrouped()

        // Mute state — slow cadence, but refetched right after a toggle.
        if (muteDirty || now - lastMuteMs > METADATA_REFRESH_INTERVAL_MS) {
            val muteInfo = if (grouped) controller.getGroupMute(ip, port)
                           else controller.getMute(ip, port)
            cachedMuted = muteInfo?.muted ?: cachedMuted
            lastMuteMs = now
            muteDirty = false
        }

        // Group volume is fetched per poll (only when grouped) so the displayed
        // level reflects the whole group; ungrouped uses the coordinator volume
        // already returned by pollPlaybackState.
        val groupVolume = if (grouped) controller.getGroupVolume(ip, port)?.volume else null

        // Queue and favorites use the previous successful cache for the fast
        // state publication below. Their SOAP browses happen after controls and
        // playback have been made visible to the widget.
        val favorites = cachedFavorites?.map {
            Favorite(id = it.id, title = it.title, artUrl = it.albumArtUri)
        } ?: emptyList()

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
            favorites = favorites,
            currentSource = currentSource,
            volumeMuted = cachedMuted,
            isUpdating = isFirmwareUpdating,
            isOffline = false,
            isReconnecting = false,
            isRateLimited = System.currentTimeMillis() < rateLimitedUntilMs,
            showPermissionHint = false,
            errorMessage = expiredErrorMessage(),
            offlineSpeakerIds = detectOfflineSpeakers(),
            lastEssentialRefreshMs = now,
            isContentStale = false
        )

        state = state.copy(
            capabilities = WidgetStateMapper.capabilitiesFor(
                connectionMode = activeConnectionMode,
                track = state.currentTrack,
                currentSource = currentSource,
                hasQueue = queueItems.isNotEmpty(),
                hasFavorites = favorites.isNotEmpty(),
                hasLocalGrouping = cachedZoneGroups != null
            )
        )

        if (groupVolume != null) {
            state = state.copy(volume = groupVolume)
        }

        // Publish transport, room and controls before artwork I/O. A track change
        // deliberately hides the prior cover until its own enrichment is ready.
        val artworkVersion = artworkVersionFor(state)
        state = state.copy(
            artworkVersion = artworkVersion.takeIf { it == publishedArtworkVersion }
        )
        state = applyOptimisticOverrides(state)
        if (!isPollTargetCurrent(targetGeneration, targetZoneId, ip)) return null
        _widgetState.value = state
        WidgetStateStore.pushState(context, state)

        if (trackChanged) {
            lastQueueTrackNum = currentTrackNum
            cachedQueue = controller.browseQueue(ip, port, startIndex = currentTrackNum, count = 20)
            Log.d(TAG, "Queue refreshed: ${cachedQueue?.size ?: 0} item(s), startIndex=$currentTrackNum")
        }
        if (cachedFavorites == null || now - lastFavoritesMs > FAVORITES_REFRESH_INTERVAL_MS) {
            controller.browseFavorites(ip, port)?.let {
                cachedFavorites = it
                lastFavoritesMs = now
            }
        }
        val enrichedQueue = mapQueueItems(cachedQueue, currentTrackNum)
        val enrichedFavorites = cachedFavorites?.map {
            Favorite(id = it.id, title = it.title, artUrl = it.albumArtUri)
        } ?: emptyList()
        // Queue/favorites are enrichment. They may complete after a user action,
        // so merge them into the newest state only for the same media identity.
        val currentForEnrichment = _widgetState.value
        if (artworkVersionFor(currentForEnrichment) != artworkVersion) return currentForEnrichment
        state = currentForEnrichment.copy(
            queue = enrichedQueue,
            favorites = enrichedFavorites,
            capabilities = WidgetStateMapper.capabilitiesFor(
                connectionMode = activeConnectionMode,
                track = currentForEnrichment.currentTrack,
                currentSource = currentSource,
                hasQueue = enrichedQueue.isNotEmpty(),
                hasFavorites = enrichedFavorites.isNotEmpty(),
                hasLocalGrouping = cachedZoneGroups != null
            )
        )

        if (!isPollTargetCurrent(targetGeneration, targetZoneId, ip)) return null

        Log.d(TAG, "Album art URL: ${state.currentTrack.artUrl ?: "(null)"}")
        val artBitmap = AlbumArtLoader.loadAndCache(
            context = context,
            artUrl = state.currentTrack.artUrl,
            speakerIp = ip,
            artworkVersion = artworkVersion
        )
        Log.d(TAG, "Album art loaded: ${artBitmap != null}")
        if (artBitmap != null) {
            // Only re-extract the palette when the art actually changed — Palette
            // generation is CPU-heavy and the bitmap is unchanged on most polls.
            val artUrl = state.currentTrack.artUrl
            val palette = if (artUrl == lastPaletteArtUrl && cachedPalette != null) {
                cachedPalette!!
            } else {
                ThemeExtractor.extractFromBitmap(artBitmap).also {
                    cachedPalette = it
                    lastPaletteArtUrl = artUrl
                }
            }
            publishedArtworkVersion = artworkVersion
            val currentForArtwork = _widgetState.value
            if (artworkVersionFor(currentForArtwork) != artworkVersion) return currentForArtwork
            state = currentForArtwork.copy(colorPalette = palette, artworkVersion = artworkVersion)
            WidgetBackgroundRenderer.renderAndCache(context, artBitmap, artUrl)
        } else {
            val currentForArtwork = _widgetState.value
            if (artworkVersionFor(currentForArtwork) != artworkVersion) return currentForArtwork
            publishedArtworkVersion = null
            cachedPalette = null
            lastPaletteArtUrl = null
            WidgetBackgroundRenderer.clear(context)
            state = currentForArtwork.copy(artworkVersion = null)
        }

        cacheConnection(activeConnectionMode)

        if (!isPollTargetCurrent(targetGeneration, targetZoneId, ip)) return null
        val finalState = applyOptimisticOverrides(state)
        _widgetState.value = finalState
        WidgetStateStore.pushState(context, finalState)
        return finalState
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

        // Probe all non-active members concurrently rather than serially — a
        // serial sweep cost up to (connect+read timeout) × member count, which
        // could stall a poll cycle for several seconds on larger households.
        val membersToProbe = groups.flatMap { it.members }
            .filter { it.ip != activeIp && it.ip != "0.0.0.0" }

        val offline = coroutineScope {
            membersToProbe.map { member ->
                async(Dispatchers.IO) {
                    val info = controller.getTransportInfo(member.ip, member.port)
                    if (info == null) member.uuid else null
                }
            }.awaitAll().filterNotNull().toSet()
        }

        cachedOfflineSpeakers = offline
        lastOfflineCheckMs = now
        return offline
    }

    private suspend fun pollCloud(): SonosWidgetState? {
        val targetGeneration = roomTargetGeneration
        // Check if we're in a rate-limit backoff period
        if (System.currentTimeMillis() < rateLimitedUntilMs) {
            Log.d(TAG, "Cloud poll skipped — rate limited until ${rateLimitedUntilMs}")
            return _widgetState.value
        }

        val cloudState = cloudController.getPlaybackStatus()
        if (targetGeneration != roomTargetGeneration) return null
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

        val displayState = applyOptimisticOverrides(successState)
        if (targetGeneration != roomTargetGeneration) return null
        _widgetState.value = displayState
        WidgetStateStore.pushState(context, displayState)
        return displayState
    }

    /**
     * Handles a local connection failure by attempting the fallback chain.
     * Tries cloud before fully disconnecting.
     */
    private suspend fun handleLocalFailure(): SonosWidgetState? {
        activeSpeakerIp = null
        activeConnectionMode = ConnectionMode.DISCONNECTED

        // The most common cause of a local poll failing is the speaker's DHCP
        // lease changing — the saved IP is dead but the speaker is still on the
        // LAN at a new address. Re-run local discovery immediately to find it,
        // so we recover in this same cycle (~2s) instead of clearing the saved
        // speaker and waiting out the 30s+ disconnect backoff. Guarded so a
        // genuinely-gone speaker can't loop discovery within one poll.
        if (!localRecoveryAttempted) {
            localRecoveryAttempted = true
            if (tryLocalDiscovery()) {
                Log.d(TAG, "Recovered local connection via re-discovery after IP failure")
                return pollLocal()
            }
        }

        if (tryCloudFallback()) {
            Log.d(TAG, "Switched to cloud after local failure")
            return _widgetState.value
        }

        // Deliberately keep the saved speaker and cached album art: the most
        // likely cause is the phone leaving Wi-Fi, and both are exactly what
        // makes reconnection instant (Step 0 probe) and keeps the widget
        // showing the last track instead of a blank "searching" card.
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

        // Arm a one-shot reconnect that fires the moment Wi-Fi comes back,
        // even if the polling service has been torn down by then.
        com.sycamorecreek.sonoswidget.service.WifiReconnectWorker.scheduleOnWifiAvailable(context)
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
     * Deferred command replay is deliberately retired. Commands dispatched from
     * an old rendered widget are either validated against current capabilities or
     * rejected; they are never stored for a later connection.
     */
    fun shouldDebounce(): Boolean = false

    /**
     * Enqueues a widget action for deferred execution after reconnection.
     * Called by [WidgetActions] callbacks when [shouldDebounce] returns true.
     */
    fun enqueueAction(type: ActionDebouncer.ActionType, param: String? = null) {
        Log.d(TAG, "Ignoring deferred $type action while reconnecting")
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
        // Under shuffle, queue track numbers don't follow play order, so a direct
        // TRACK_NR seek would jump to the wrong song. Fall back to sequential skips.
        if (currentTrackNum > 0 && !_widgetState.value.shuffleEnabled) {
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

    suspend fun play(): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canPlayPause, "play")) return false
        return routeCommand(
            local = { ip, port -> controller.playOutcome(ip, port) },
            cloud = { cloudController.play() }
        ).isAcknowledged
    }

    /** A stable media identity prevents a shared widget file being paired with old metadata. */
    private fun artworkVersionFor(state: SonosWidgetState): String {
        val track = state.currentTrack
        return listOf(
            state.activeZone.id,
            track.artUrl.orEmpty(),
            track.name,
            track.artist,
            track.album
        ).joinToString("|")
    }

    suspend fun pause(): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canPlayPause, "pause")) return false
        return routeCommand(
            local = { ip, port -> controller.pauseOutcome(ip, port) },
            cloud = { cloudController.pause() }
        ).isAcknowledged
    }

    suspend fun togglePlayPause(): Boolean {
        val base = _widgetState.value
        val target = if (base.playbackState == PlaybackState.PLAYING) {
            PlaybackState.PAUSED
        } else {
            PlaybackState.PLAYING
        }
        applyOptimisticPlayback(target)
        val outcome = if (target == PlaybackState.PAUSED) {
            routeCommand({ ip, port -> controller.pauseOutcome(ip, port) }, { cloudController.pause() })
        } else {
            routeCommand({ ip, port -> controller.playOutcome(ip, port) }, { cloudController.play() })
        }
        if (outcome == CommandOutcome.DEFINITE_FAILURE) revertPlaybackOptimism(base.playbackState)
        return outcome.isAcknowledged
    }

    suspend fun next(): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canNext, "next")) return false
        val base = _widgetState.value.playbackState
        // After a skip the speaker keeps playing — show the playing icon instantly.
        applyOptimisticPlayback(PlaybackState.PLAYING)
        val outcome = routeCommand(
            local = { ip, port -> controller.nextOutcome(ip, port) },
            cloud = { cloudController.next() }
        )
        if (outcome == CommandOutcome.DEFINITE_FAILURE) revertPlaybackOptimism(base)
        return outcome.isAcknowledged
    }

    suspend fun previous(): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canPrevious, "previous")) return false
        val base = _widgetState.value.playbackState
        applyOptimisticPlayback(PlaybackState.PLAYING)
        val outcome = routeCommand(
            local = { ip, port -> controller.previousOutcome(ip, port) },
            cloud = { cloudController.previous() }
        )
        if (outcome == CommandOutcome.DEFINITE_FAILURE) revertPlaybackOptimism(base)
        return outcome.isAcknowledged
    }

    /** Drops the playback override and restores [base] while keeping any error banner. */
    private suspend fun revertPlaybackOptimism(base: PlaybackState) {
        optimisticPlayback = null
        pushState(_widgetState.value.copy(playbackState = base))
    }

    suspend fun seek(positionMs: Long): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canSeek, "seek")) return false
        return routeCommand(
            local = { ip, port -> controller.seekOutcome(ip, port, positionMs) },
            cloud = { false }
        ).isAcknowledged
    }

    suspend fun setVolume(volume: Int): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canChangeVolume, "change volume")) return false
        val base = _widgetState.value.volume
        applyOptimisticVolume(volume.coerceIn(0, 100))
        val outcome = routeCommand(
            local = { ip, port ->
                if (isActiveGroupGrouped()) controller.setGroupVolumeOutcome(ip, port, volume)
                else controller.setVolumeOutcome(ip, port, volume)
            },
            cloud = { cloudController.setVolume(volume) }
        )
        if (outcome == CommandOutcome.DEFINITE_FAILURE) {
            optimisticVolume = null
            pushState(_widgetState.value.copy(volume = base))
        }
        return outcome.isAcknowledged
    }

    /**
     * Applies a relative user intent against the latest optimistic value. Unlike
     * callbacks that each read the same stale volume, serialized deltas preserve
     * every tap (for example, five +5 taps from 50 converge to 75).
     */
    suspend fun adjustVolume(delta: Int): Boolean = volumeIntentMutex.withLock {
        setVolume((_widgetState.value.volume + delta).coerceIn(0, 100))
    }

    suspend fun setMute(muted: Boolean): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canMute, "mute")) return false
        val base = _widgetState.value.volumeMuted
        // Optimistic + force a mute refetch on the re-poll that routeCommand runs.
        cachedMuted = muted
        muteDirty = true
        pushState(_widgetState.value.copy(volumeMuted = muted))
        val outcome = routeCommand(
            local = { ip, port ->
                if (isActiveGroupGrouped()) controller.setGroupMuteOutcome(ip, port, muted)
                else controller.setMuteOutcome(ip, port, muted)
            },
            cloud = { false }
        )
        if (outcome == CommandOutcome.DEFINITE_FAILURE) {
            cachedMuted = base
            pushState(_widgetState.value.copy(volumeMuted = base))
        }
        return outcome.isAcknowledged
    }

    /**
     * True when the active coordinator's group contains more than one member,
     * i.e. volume/mute should target the whole group rather than one speaker.
     */
    private fun isActiveGroupGrouped(): Boolean {
        val groups = cachedZoneGroups ?: return false
        val zoneId = activeZoneId ?: return false
        val group = groups.find { it.coordinatorId == zoneId } ?: return false
        return group.members.size > 1
    }

    suspend fun switchZone(zoneId: String): Boolean {
        if (activeConnectionMode == ConnectionMode.CLOUD) {
            val generation = ++roomTargetGeneration
            publishSwitchingRoom(zoneId, _widgetState.value.zones.find { it.id == zoneId }?.displayName.orEmpty())
            cloudController.setActiveGroup(zoneId)
            return generation == roomTargetGeneration && pollCloud() != null
        }

        val groups = cachedZoneGroups ?: return false
        for (group in groups) {
            val member = group.members.find { it.uuid == zoneId }
            if (member != null) {
                val coordinator = group.members.find { it.isCoordinator } ?: member
                val generation = ++roomTargetGeneration
                activeSpeakerIp = coordinator.ip
                activeSpeakerPort = coordinator.port
                activeZoneId = coordinator.uuid
                publishSwitchingRoom(coordinator.uuid, coordinator.zoneName)
                preferences.saveActiveSpeaker(
                    coordinator.uuid,
                    coordinator.zoneName,
                    coordinator.ip,
                    coordinator.port
                )
                Log.d(TAG, "Switched zone to: ${coordinator.zoneName} @ ${coordinator.ip}")
                stoppedSinceMs = 0L
                pollAndUpdate()
                return generation == roomTargetGeneration
            }
        }

        Log.w(TAG, "Zone $zoneId not found in cached topology")
        return false
    }

    /**
     * A new target must never inherit the old target's track/artwork while its
     * essential poll is pending. This state is shared to every widget instance.
     */
    private suspend fun publishSwitchingRoom(zoneId: String, zoneName: String) {
        // Secondary data is destination-scoped. Do not reuse room A's queue or
        // favorites while room B is being confirmed.
        cachedQueue = null
        cachedFavorites = null
        cachedTransportSettings = null
        cachedSource = ""
        lastQueueTrackNum = -1
        val current = _widgetState.value
        val operation = PendingWidgetOperation(
            id = "room-${System.currentTimeMillis()}",
            type = WidgetOperationType.SWITCHING_ROOM,
            targetId = zoneId,
            affectedField = "room",
            startedAtMs = System.currentTimeMillis()
        )
        pushState(current.copy(
            activeZone = current.zones.find { it.id == zoneId }
                ?: current.activeZone.copy(id = zoneId, displayName = zoneName),
            currentTrack = current.currentTrack.copy(name = "", artist = "", album = "", artUrl = null,
                durationMs = 0L, elapsedMs = 0L),
            currentSource = "",
            queue = emptyList(),
            favorites = emptyList(),
            artworkVersion = null,
            pendingOperations = listOf(operation),
            isContentStale = true,
            errorMessage = null
        ))
    }

    private fun isPollTargetCurrent(generation: Long, zoneId: String?, ip: String): Boolean {
        val current = generation == roomTargetGeneration && zoneId == activeZoneId && ip == activeSpeakerIp
        if (!current) Log.d(TAG, "Discarding poll result for obsolete room target")
        return current
    }

    // ──────────────────────────────────────────────
    // Shuffle & Repeat (Task 2.4)
    // ──────────────────────────────────────────────

    suspend fun toggleShuffle(): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canShuffle, "shuffle")) return false
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
        if (!canExecute(_widgetState.value.capabilities.canRepeat, "repeat")) return false
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

    suspend fun playQueueItem(trackNr: Int): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canViewQueue, "play queue item")) return false
        return executeLocalAndPoll { ip, port -> controller.seekToTrack(ip, port, trackNr) }
    }

    /**
     * Starts playback of a cached Sonos Favorite by its DIDL id. Routed through
     * the active coordinator. Favorites are local-only (no cloud equivalent).
     */
    suspend fun playFavorite(favoriteId: String): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canPlayFavorites, "play favorite")) return false
        val fav = cachedFavorites?.find { it.id == favoriteId }
        if (fav == null) {
            Log.w(TAG, "Favorite '$favoriteId' not found in cache")
            return false
        }
        // Container favorites (playlists) play through the queue, which needs the
        // coordinator UUID. Prefer the active group's coordinator; fall back to the
        // active zone id (set to the coordinator on connect).
        val coordinatorUuid = cachedZoneGroups
            ?.find { group -> group.members.any { it.uuid == activeZoneId } }
            ?.coordinatorId
            ?: activeZoneId
        // Loading a playlist is content preparation, not a firmware update. Keep
        // that intent separate so the widget can describe it truthfully.
        val operationId = "favorite-${System.currentTimeMillis()}"
        pushState(_widgetState.value.copy(
            pendingOperations = _widgetState.value.pendingOperations + PendingWidgetOperation(
                id = operationId,
                type = WidgetOperationType.LOADING_FAVORITE,
                targetId = coordinatorUuid.orEmpty(),
                affectedField = "favorite",
                startedAtMs = System.currentTimeMillis()
            )
        ))
        val ok = executeLocalAndPoll { ip, port ->
            controller.playFavorite(ip, port, fav.uri, fav.metadata, coordinatorUuid)
        }
        if (!ok) {
            pushState(_widgetState.value.copy(
                pendingOperations = _widgetState.value.pendingOperations.filterNot { it.id == operationId }
            ))
        }
        return ok
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
            forceZoneRefreshUntilMs = System.currentTimeMillis() + 8000L
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
        forceZoneRefreshUntilMs = System.currentTimeMillis() + 8000L
        cachedZoneGroups = controller.getZoneGroupState(ip, activeSpeakerPort)
        lastZoneRefreshMs = System.currentTimeMillis()
        pollAndUpdate()

        return allSucceeded
    }

    /** Applies an explicit grouping draft; no membership changes occur before this call. */
    suspend fun applyGroupingDraft(
        selectedSpeakerIds: Set<String>,
        expectedTargetId: String
    ): Boolean {
        if (!canExecute(_widgetState.value.capabilities.canGroup, "apply grouping")) return false
        if (activeConnectionMode == ConnectionMode.CLOUD) return false
        val targetGeneration = roomTargetGeneration
        if (!isGroupingTargetCurrent(expectedTargetId, targetGeneration)) {
            Log.d(TAG, "Ignoring grouping draft for an obsolete room target")
            return false
        }
        if (_widgetState.value.pendingOperations.any {
                it.type == WidgetOperationType.APPLYING_GROUPING
            }) {
            Log.d(TAG, "Ignoring duplicate grouping apply while one is in flight")
            return false
        }

        // Membership can change outside the widget. Validate the topology at the
        // last responsible moment so an explicit draft is never applied to a
        // stale group map.
        // Capture the destination before the topology request suspends. Every
        // later command is guarded against a room switch, not just a topology
        // refresh that happens to return successfully.
        val ip = activeSpeakerIp ?: return false
        val port = activeSpeakerPort
        val groups = controller.getZoneGroupState(ip, port) ?: return false
        if (!isGroupingTargetCurrent(expectedTargetId, targetGeneration) || ip != activeSpeakerIp) {
            Log.d(TAG, "Discarding grouping topology for an obsolete room target")
            return false
        }
        cachedZoneGroups = groups
        lastZoneRefreshMs = System.currentTimeMillis()
        val activeId = activeZoneId ?: return false
        val activeGroup = groups.firstOrNull { group -> group.members.any { it.uuid == activeId } }
            ?: return false
        val coordinatorId = activeGroup.coordinatorId
        val desired = selectedSpeakerIds + coordinatorId
        val members = groups.flatMap { it.members }.distinctBy { it.uuid }
        val operationId = "group-${System.currentTimeMillis()}"
        pushState(_widgetState.value.copy(
            pendingOperations = listOf(PendingWidgetOperation(
                id = operationId,
                type = WidgetOperationType.APPLYING_GROUPING,
                targetId = coordinatorId,
                affectedField = "grouping",
                startedAtMs = System.currentTimeMillis()
            ))
        ))

        var succeeded = true
        for (member in members) {
            if (!isGroupingTargetCurrent(expectedTargetId, targetGeneration) || ip != activeSpeakerIp) {
                Log.d(TAG, "Stopping grouping apply after room target changed")
                return false
            }
            if (member.uuid == coordinatorId) continue
            val groupedHere = activeGroup.members.any { it.uuid == member.uuid }
            when {
                member.uuid in desired && !groupedHere -> {
                    if (!controller.addToGroup(member.ip, member.port, coordinatorId)) succeeded = false
                }
                member.uuid !in desired && groupedHere -> {
                    if (!controller.removeFromGroup(member.ip, member.port)) succeeded = false
                }
            }
        }

        kotlinx.coroutines.delay(500)
        forceZoneRefreshUntilMs = System.currentTimeMillis() + 8_000L
        if (!isGroupingTargetCurrent(expectedTargetId, targetGeneration) || ip != activeSpeakerIp) return false
        cachedZoneGroups = controller.getZoneGroupState(ip, port)
        lastZoneRefreshMs = System.currentTimeMillis()
        pollAndUpdate()
        if (!succeeded) {
            // The topology above is the source of truth after a partial apply.
            // Keep the editor open and give the user a concrete recovery path
            // instead of pretending the staged selection was all-or-nothing.
            pushState(_widgetState.value.copy(
                errorMessage = "Some speakers could not be regrouped. Review the selection and try again."
            ))
        }
        return succeeded
    }

    private fun isGroupingTargetCurrent(expectedTargetId: String, expectedGeneration: Long): Boolean =
        isGroupingTargetCurrent(
            expectedTargetId,
            activeZoneId,
            expectedGeneration,
            roomTargetGeneration
        )

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
    // Optimistic UI helpers
    // ──────────────────────────────────────────────

    /** Pushes a state to the StateFlow and the Glance widget immediately. */
    private suspend fun pushState(newState: SonosWidgetState) {
        _widgetState.value = newState
        WidgetStateStore.pushState(context, newState)
    }

    /** Optimistically flips play/pause in the UI and holds it over lagging polls. */
    private suspend fun applyOptimisticPlayback(target: PlaybackState) {
        optimisticPlayback = target
        optimisticPlaybackUntilMs = System.currentTimeMillis() + OPTIMISTIC_HOLD_MS
        pushState(_widgetState.value.copy(playbackState = target))
    }

    /** Optimistically sets the volume in the UI and holds it over lagging polls. */
    private suspend fun applyOptimisticVolume(target: Int) {
        optimisticVolume = target
        optimisticVolumeUntilMs = System.currentTimeMillis() + OPTIMISTIC_HOLD_MS
        pushState(_widgetState.value.copy(volume = target))
    }

    /**
     * Re-applies any still-valid optimistic overrides on top of a freshly polled
     * state. Each override is cleared once the speaker's real value agrees with it
     * or its hold window lapses. Called at every poll write point.
     */
    private fun applyOptimisticOverrides(state: SonosWidgetState): SonosWidgetState {
        val now = System.currentTimeMillis()
        var result = state

        optimisticPlayback?.let { target ->
            if (now >= optimisticPlaybackUntilMs || state.playbackState == target) {
                optimisticPlayback = null
            } else {
                result = result.copy(playbackState = target)
            }
        }
        optimisticVolume?.let { target ->
            if (now >= optimisticVolumeUntilMs || state.volume == target) {
                optimisticVolume = null
            } else {
                result = result.copy(volume = target)
            }
        }
        return result
    }

    // ──────────────────────────────────────────────
    // Command routing internals
    // ──────────────────────────────────────────────

    /**
     * Routes a command through either local or cloud with a command-only deadline.
     * Reconciliation happens afterward, so slow secondary work cannot invalidate
     * a speaker acknowledgement.
     */
    private enum class CommandOutcome {
        ACKNOWLEDGED,
        DEFINITE_FAILURE,
        UNKNOWN;

        val isAcknowledged: Boolean get() = this == ACKNOWLEDGED
    }

    private suspend fun routeCommand(
        local: suspend (ip: String, port: Int) -> CommandTransportOutcome,
        cloud: suspend () -> Boolean
    ): CommandOutcome {
        val commandStartedAtMs = SystemClock.elapsedRealtime()

        val result = try {
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) {
                if (activeConnectionMode == ConnectionMode.CLOUD) {
                    if (cloud()) CommandTransportOutcome.ACKNOWLEDGED
                    else CommandTransportOutcome.REJECTED
                } else {
                    executeLocalCommand(local)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Command failed with exception", e)
            null
        }

        if (result == null || result == CommandTransportOutcome.UNKNOWN) {
            // A timeout can occur after a speaker acts. Preserve the newest
            // intent and make the next action an explicit status check.
            recordUnknownCommand()
            pushErrorMessage("Couldn't confirm command — check status")
            return CommandOutcome.UNKNOWN
        }

        if (result == CommandTransportOutcome.REJECTED) {
            pushErrorMessage("Command failed — check status")
            return CommandOutcome.DEFINITE_FAILURE
        }

        Log.d(
            TAG,
            "Command acknowledged in ${SystemClock.elapsedRealtime() - commandStartedAtMs}ms; " +
                "starting reconciliation"
        )

        // The command has already been acknowledged. Reconciliation is useful
        // but belongs outside the command deadline: a slow queue/image refresh
        // must never report this successful control action as failed.
        try {
            pollAndUpdate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Command acknowledged; reconciliation will retry on the next poll", e)
        }
        return CommandOutcome.ACKNOWLEDGED
    }

    private suspend fun recordUnknownCommand() {
        val now = System.currentTimeMillis()
        pushState(_widgetState.value.copy(
            pendingOperations = _widgetState.value.pendingOperations + PendingWidgetOperation(
                id = "unknown-$now",
                type = WidgetOperationType.COMMAND,
                targetId = activeZoneId.orEmpty(),
                affectedField = "command",
                startedAtMs = now,
                phase = WidgetOperationPhase.UNKNOWN
            )
        ))
    }

    private fun canExecute(supported: Boolean, action: String): Boolean {
        if (!supported) Log.w(TAG, "Ignoring unsupported $action action for current state")
        return supported
    }

    private suspend fun executeLocalCommand(
        command: suspend (ip: String, port: Int) -> CommandTransportOutcome
    ): CommandTransportOutcome {
        val ip = activeSpeakerIp
        if (ip == null) {
            Log.w(TAG, "No active speaker — command ignored")
            return CommandTransportOutcome.REJECTED
        }
        return command(ip, activeSpeakerPort)
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
            // Optimistic overrides hold the UI steady, so reconcile right away
            // rather than padding every command with a fixed delay.
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

    /**
     * Finds the best coordinator across all zone groups by checking transport state.
     *
     * Queries each group's coordinator for its current playback state and picks
     * the best one in priority order:
     *   1. Currently PLAYING
     *   2. Currently PAUSED_PLAYBACK
     *   3. Any reachable coordinator
     *   4. First coordinator (fallback)
     *
     * Probes are run in parallel to minimize latency (~200ms per speaker).
     */
    private data class CoordinatorProbe(
        val member: ZoneGroupMember,
        val transportState: String?
    )

    /**
     * Resolves the room explicitly selected for stay mode to a reachable group
     * coordinator. There is deliberately no name fallback: a new installation
     * chooses its target during setup, rather than silently controlling a room.
     *
     * Returns null if the preferred room isn't present in the topology or its
     * coordinator doesn't respond, in which case the caller falls back.
     */
    private suspend fun resolvePreferredCoordinator(
        zoneGroups: List<ZoneGroup>,
        target: SonosPreferences.DefaultZone?
    ): ZoneGroupMember? {
        val preferredId = target?.id ?: return null
        val preferredName = target.name.trim()

        val group = zoneGroups.firstOrNull { g ->
            g.members.any { m ->
                m.uuid == preferredId
            }
        } ?: return null

        val coordinator = group.members.find { it.isCoordinator } ?: return null
        // Only commit to it if it's actually reachable right now.
        val info = controller.getTransportInfo(coordinator.ip, coordinator.port)
        if (info == null) {
            Log.d(TAG, "Preferred zone '$preferredName' coordinator unreachable, falling back")
            return null
        }
        Log.d(TAG, "Preferred zone '$preferredName' → coordinator ${coordinator.zoneName} @ ${coordinator.ip}")
        return coordinator
    }

    private suspend fun findBestCoordinator(
        zoneGroups: List<ZoneGroup>
    ): ZoneGroupMember? {
        val coordinators = zoneGroups.mapNotNull { group ->
            group.members.find { it.isCoordinator }
        }

        if (coordinators.isEmpty()) return null
        if (coordinators.size == 1) return coordinators.first()

        Log.d(TAG, "Probing ${coordinators.size} coordinator(s) for playing state...")

        // Probe all coordinators in parallel
        val probes: List<CoordinatorProbe> = coroutineScope {
            coordinators.map { coordinator ->
                async(Dispatchers.IO) {
                    val info = controller.getTransportInfo(coordinator.ip, coordinator.port)
                    Log.d(TAG, "  ${coordinator.zoneName} @ ${coordinator.ip}: ${info?.state ?: "unreachable"}")
                    CoordinatorProbe(coordinator, info?.state)
                }
            }.map { it.await() }
        }

        // Priority: PLAYING > PAUSED > any reachable > first
        val best = probes.find { it.transportState == "PLAYING" }?.member
            ?: probes.find { it.transportState == "PAUSED_PLAYBACK" }?.member
            ?: probes.find { it.transportState != null }?.member
            ?: coordinators.first()

        Log.d(TAG, "Best coordinator: ${best.zoneName} @ ${best.ip}")
        return best
    }
}

/** Pure guard used before and after every suspending grouping step. */
internal fun isGroupingTargetCurrent(
    expectedTargetId: String,
    currentTargetId: String?,
    expectedGeneration: Long,
    currentGeneration: Long
): Boolean = expectedTargetId == currentTargetId && expectedGeneration == currentGeneration
