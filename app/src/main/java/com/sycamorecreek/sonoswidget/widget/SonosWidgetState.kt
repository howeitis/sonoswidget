package com.sycamorecreek.sonoswidget.widget

/**
 * Canonical widget state model per PRD Section 11.1.
 * Persisted in Glance's DataStore. The widget reads this; the service writes it.
 */

enum class PlaybackState {
    PLAYING, PAUSED, STOPPED, TRANSITIONING
}

data class Track(
    val name: String = "",
    val artist: String = "",
    val album: String = "",
    val artUrl: String? = null,
    val durationMs: Long = 0L,
    val elapsedMs: Long = 0L
)

data class Zone(
    val id: String = "",
    val displayName: String = "",
    val groupId: String? = null,
    val isGroupCoordinator: Boolean = false
)

data class QueueItem(
    val trackName: String = "",
    val artist: String = "",
    val thumbnailUrl: String? = null,
    val position: Int = 0
)

data class Favorite(
    val id: String = "",
    val title: String = "",
    val artUrl: String? = null
)

enum class ConnectionMode {
    LOCAL_SSDP, LOCAL_MDNS, LOCAL_MANUAL_IP, CLOUD, DISCONNECTED
}

enum class RepeatMode {
    NONE, ALL, ONE
}

data class WidgetColorPalette(
    val background: String = "#1E1E2E",
    val textPrimary: String = "#FFFFFF",
    val textSecondary: String = "#B0B0C0",
    val accent: String = "#6C63FF",
    val chipBackground: String = "#3E3E5E"
)

/**
 * Badge type displayed as a semi-transparent pill overlay on album art.
 * Only one badge is shown at a time, prioritized by severity.
 */
enum class StatusBadgeType {
    NONE, OFFLINE, RECONNECTING, RATE_LIMITED, UPDATING
}

/** A user-initiated operation tracked independently from device playback state. */
enum class WidgetOperationType {
    LOADING_FAVORITE, SWITCHING_ROOM, APPLYING_GROUPING, RECONNECTING, COMMAND
}

/**
 * An operation may be acknowledged before a following refresh completes, or have
 * an unknown outcome when a speaker connection times out after dispatch.
 */
enum class WidgetOperationPhase {
    REQUESTED, ACKNOWLEDGED, UNKNOWN, FAILED
}

data class PendingWidgetOperation(
    val id: String = "",
    val type: WidgetOperationType = WidgetOperationType.COMMAND,
    val targetId: String = "",
    val affectedField: String = "",
    val startedAtMs: Long = 0L,
    val phase: WidgetOperationPhase = WidgetOperationPhase.REQUESTED
)

/** Operations that are meaningful for the currently selected source and connection. */
data class WidgetCapabilities(
    val canPlayPause: Boolean = false,
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    val canSeek: Boolean = false,
    val canMute: Boolean = false,
    val canChangeVolume: Boolean = false,
    val canShuffle: Boolean = false,
    val canRepeat: Boolean = false,
    val canViewQueue: Boolean = false,
    val canPlayFavorites: Boolean = false,
    val canGroup: Boolean = false
)

data class SonosWidgetState(
    val playbackState: PlaybackState = PlaybackState.STOPPED,
    val currentTrack: Track = Track(),
    val activeZone: Zone = Zone(),
    val volume: Int = 50,
    val zones: List<Zone> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val currentSource: String = "",
    val connectionMode: ConnectionMode = ConnectionMode.DISCONNECTED,
    val volumeMuted: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val colorPalette: WidgetColorPalette = WidgetColorPalette(),
    /** Identity of the artwork file currently safe to pair with [currentTrack]. */
    val artworkVersion: String? = null,
    val isReconnecting: Boolean = false,
    val isRateLimited: Boolean = false,
    val isOffline: Boolean = false,
    val isUpdating: Boolean = false,
    /** Pending intent is deliberately separate from playback and firmware state. */
    val pendingOperations: List<PendingWidgetOperation> = emptyList(),
    val capabilities: WidgetCapabilities = WidgetCapabilities(),
    /** Time of the last successful essential speaker refresh, never an optimistic UI update. */
    val lastEssentialRefreshMs: Long = 0L,
    val isContentStale: Boolean = false,
    val errorMessage: String? = null,
    val showPermissionHint: Boolean = false,
    val offlineSpeakerIds: Set<String> = emptySet(),
    val lastUpdatedMs: Long = 0L
) {
    /** Resolves the highest-priority badge to display on album art. */
    val activeBadge: StatusBadgeType get() = when {
        isOffline -> StatusBadgeType.OFFLINE
        isUpdating -> StatusBadgeType.UPDATING
        isRateLimited -> StatusBadgeType.RATE_LIMITED
        isReconnecting -> StatusBadgeType.RECONNECTING
        else -> StatusBadgeType.NONE
    }
}
