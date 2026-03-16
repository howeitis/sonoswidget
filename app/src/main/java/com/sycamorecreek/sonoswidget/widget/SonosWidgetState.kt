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

data class MusicSource(
    val id: String = "",
    val name: String = "",
    val iconResId: Int = 0
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

data class SonosWidgetState(
    val playbackState: PlaybackState = PlaybackState.STOPPED,
    val currentTrack: Track = Track(),
    val activeZone: Zone = Zone(),
    val volume: Int = 50,
    val zones: List<Zone> = emptyList(),
    val queue: List<QueueItem> = emptyList(),
    val availableSources: List<MusicSource> = emptyList(),
    val connectionMode: ConnectionMode = ConnectionMode.DISCONNECTED,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val colorPalette: WidgetColorPalette = WidgetColorPalette(),
    val isReconnecting: Boolean = false,
    val isRateLimited: Boolean = false,
    val isOffline: Boolean = false,
    val isUpdating: Boolean = false,
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
