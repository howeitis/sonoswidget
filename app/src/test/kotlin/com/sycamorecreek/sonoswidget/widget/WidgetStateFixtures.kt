package com.sycamorecreek.sonoswidget.widget

/**
 * Deterministic states for JVM and future Glance/instrumentation coverage.
 * These contain no household, credential, or real-media data.
 */
object WidgetStateFixtures {
    private val rooms = listOf(
        Zone(id = "room-kitchen", displayName = "Kitchen", groupId = "group-main", isGroupCoordinator = true),
        Zone(id = "room-office", displayName = "Office", groupId = "group-main"),
        Zone(id = "room-patio", displayName = "Patio", groupId = "group-patio", isGroupCoordinator = true)
    )

    val playing = SonosWidgetState(
        playbackState = PlaybackState.PLAYING,
        currentTrack = Track(
            name = "Sample Song",
            artist = "Sample Artist",
            album = "Sample Album",
            artUrl = "http://192.0.2.10:1400/getaa?sample=playing",
            durationMs = 242_000L,
            elapsedMs = 61_000L
        ),
        activeZone = rooms.first(),
        zones = rooms,
        queue = listOf(QueueItem("Next Song", "Sample Artist", position = 2)),
        connectionMode = ConnectionMode.LOCAL_SSDP,
        capabilities = WidgetCapabilities(
            canPlayPause = true, canPrevious = true, canNext = true, canSeek = true,
            canMute = true, canChangeVolume = true, canShuffle = true, canRepeat = true,
            canViewQueue = true, canPlayFavorites = true, canGroup = true
        )
    )

    val paused = playing.copy(playbackState = PlaybackState.PAUSED)
    val television = playing.copy(
        currentTrack = Track(name = "Living Room TV"),
        currentSource = "TV",
        capabilities = WidgetCapabilities(canPlayPause = true, canMute = true, canChangeVolume = true, canGroup = true)
    )
    val radio = playing.copy(
        currentTrack = Track(name = "Sample Radio", artist = "Live"),
        currentSource = "Radio",
        capabilities = playing.capabilities.copy(canSeek = false)
    )
    val idle = playing.copy(
        playbackState = PlaybackState.STOPPED,
        currentTrack = Track(),
        queue = emptyList(),
        favorites = listOf(Favorite("favorite-1", "Morning Mix"))
    )
    val disconnectedWithCachedMetadata = playing.copy(
        connectionMode = ConnectionMode.DISCONNECTED,
        isReconnecting = true,
        isContentStale = true,
        capabilities = WidgetCapabilities()
    )
    val permissionDenied = disconnectedWithCachedMetadata.copy(showPermissionHint = true)
    val cloud = radio.copy(
        connectionMode = ConnectionMode.CLOUD,
        capabilities = WidgetCapabilities(canPlayPause = true, canPrevious = true, canNext = true, canChangeVolume = true)
    )
    val loadingFavorite = idle.copy(
        pendingOperations = listOf(PendingWidgetOperation("favorite-1", WidgetOperationType.LOADING_FAVORITE))
    )
    val failedCommand = playing.copy(errorMessage = "Couldn't confirm command — check status")
    val missingArtwork = playing.copy(
        currentTrack = playing.currentTrack.copy(artUrl = null),
        artworkVersion = null
    )
    val brightArtwork = playing.copy(
        currentTrack = playing.currentTrack.copy(
            artUrl = "http://192.0.2.10:1400/getaa?sample=bright"
        ),
        artworkVersion = "bright-sample-v1",
        colorPalette = WidgetColorPalette(
            background = "#F6D365",
            textPrimary = "#171717",
            textSecondary = "#4A3A12",
            accent = "#5C2E00",
            chipBackground = "#FFF3C4"
        )
    )
    val longContent = playing.copy(
        currentTrack = Track(
            name = "An Intentionally Long Track Title That Exercises Widget Truncation",
            artist = "An Intentionally Long Artist Name for Accessibility Coverage",
            album = "An Intentionally Long Album Name",
            durationMs = 320_000L
        ),
        zones = rooms + (1..8).map { index -> Zone("extra-$index", "Very Long Room Name $index") },
        favorites = (1..8).map { index -> Favorite("favorite-$index", "Long Favorite Name $index") }
    )
}
