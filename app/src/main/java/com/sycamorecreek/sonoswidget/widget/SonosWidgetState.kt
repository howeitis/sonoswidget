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
    val iconResId: Int = 0,
    val playlists: List<SourcePlaylist> = emptyList()
)

data class SourcePlaylist(
    val id: String = "",
    val name: String = "",
    val imageUrl: String? = null,
    val sourceId: String = ""
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
    val lastUpdatedMs: Long = 0L,
    val sourcesPanelExpanded: Boolean = false,
    val selectedSourceId: String? = null
)
