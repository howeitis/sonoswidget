package com.sycamorecreek.sonoswidget.sonos.local

/**
 * Typed response models for Sonos UPnP SOAP actions.
 *
 * These are intermediate representations of the raw SOAP XML responses.
 * The repository layer (Task 1.6) maps these to the widget's domain model
 * (SonosWidgetState, Track, Zone, etc.).
 */

// ──────────────────────────────────────────────
// AVTransport responses
// ──────────────────────────────────────────────

/**
 * Response from AVTransport:GetTransportInfo.
 *
 * @param state PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING, NO_MEDIA_PRESENT
 * @param status OK, ERROR_OCCURRED
 * @param speed Playback speed (typically "1")
 */
data class TransportInfo(
    val state: String,
    val status: String,
    val speed: String
)

/**
 * Response from AVTransport:GetPositionInfo.
 *
 * @param trackNum Current track number in queue (1-based, 0 if no track)
 * @param trackDuration Duration in H:MM:SS format ("0:00:00" if unknown)
 * @param trackUri URI of the current track
 * @param relTime Current position in H:MM:SS format
 * @param metadata Parsed DIDL-Lite track metadata (null if unavailable)
 */
data class PositionInfo(
    val trackNum: Int,
    val trackDuration: String,
    val trackUri: String,
    val relTime: String,
    val metadata: TrackMetadata?
)

/**
 * Parsed DIDL-Lite metadata embedded in GetPositionInfo/GetMediaInfo responses.
 *
 * Sonos encodes track metadata as entity-escaped DIDL-Lite XML within the
 * SOAP response. This class represents the decoded fields.
 */
data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: String?,
    val durationText: String?
)

/**
 * Response from AVTransport:GetMediaInfo.
 *
 * @param nrTracks Number of tracks in the current queue
 * @param currentUri URI of the current media
 * @param metadata Parsed metadata of the current media (null if unavailable)
 */
data class MediaInfo(
    val nrTracks: Int,
    val currentUri: String,
    val metadata: TrackMetadata?
)

/**
 * Response from AVTransport:GetTransportSettings.
 *
 * Sonos play modes combine shuffle and repeat into a single string:
 *   - NORMAL            = no shuffle, no repeat
 *   - REPEAT_ALL        = no shuffle, repeat all
 *   - REPEAT_ONE        = no shuffle, repeat one
 *   - SHUFFLE_NOREPEAT  = shuffle, no repeat
 *   - SHUFFLE           = shuffle, repeat all
 *   - SHUFFLE_REPEAT_ONE = shuffle, repeat one
 *
 * @param playMode Raw play mode string from Sonos
 */
data class TransportSettings(
    val playMode: String
)

// ──────────────────────────────────────────────
// RenderingControl responses
// ──────────────────────────────────────────────

/**
 * Response from RenderingControl:GetVolume.
 *
 * @param volume Current volume level (0–100)
 */
data class VolumeInfo(
    val volume: Int
)

/**
 * Response from RenderingControl:GetMute.
 *
 * @param muted true if the speaker is muted
 */
data class MuteInfo(
    val muted: Boolean
)

// ──────────────────────────────────────────────
// ZoneGroupTopology responses
// ──────────────────────────────────────────────

/**
 * A Sonos zone group (a coordinator + its grouped members).
 *
 * @param coordinatorId UUID of the group coordinator (e.g., "RINCON_xxx")
 * @param groupId Opaque group identifier
 * @param members All members of this group, including the coordinator
 */
data class ZoneGroup(
    val coordinatorId: String,
    val groupId: String,
    val members: List<ZoneGroupMember>
)

/**
 * A single speaker within a zone group.
 *
 * @param uuid Speaker UUID (e.g., "RINCON_xxx")
 * @param zoneName Human-readable room name (e.g., "Living Room")
 * @param ip Speaker IP address (extracted from Location URL)
 * @param port Speaker port (typically 1400)
 * @param isCoordinator true if this member is the group coordinator
 */
data class ZoneGroupMember(
    val uuid: String,
    val zoneName: String,
    val ip: String,
    val port: Int = 1400,
    val isCoordinator: Boolean = false
)

// ──────────────────────────────────────────────
// ContentDirectory responses
// ──────────────────────────────────────────────

/**
 * A single item from the Sonos queue, parsed from ContentDirectory:Browse.
 *
 * @param position 1-based position in the queue
 * @param title Track name
 * @param artist Artist name
 * @param album Album name
 * @param albumArtUri Album art URI (may be relative to the speaker)
 */
data class QueueItemInfo(
    val position: Int,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtUri: String?
)

// ──────────────────────────────────────────────
// ContentDirectory — Favorites
// ──────────────────────────────────────────────

/**
 * A single Sonos favorite, parsed from ContentDirectory:Browse on FV:2.
 *
 * @param id Favorite item ID (e.g., "FV:2/3")
 * @param title Display name of the favorite
 * @param albumArtUri Album art URI (may be relative to the speaker)
 * @param description Description or type hint
 * @param uri The content URI to load this favorite
 * @param metadata The DIDL-Lite metadata for loading
 */
data class FavoriteInfo(
    val id: String,
    val title: String,
    val albumArtUri: String?,
    val description: String,
    val uri: String,
    val metadata: String
)

// ──────────────────────────────────────────────
// Utility: time parsing
// ──────────────────────────────────────────────

/**
 * Converts a UPnP duration string (H:MM:SS or HH:MM:SS) to milliseconds.
 * Returns 0 if the format is unrecognized or the input is "NOT_IMPLEMENTED".
 */
fun parseDurationToMs(duration: String): Long {
    if (duration.isBlank() || duration == "NOT_IMPLEMENTED" || duration == "0:00:00") return 0L
    return try {
        val parts = duration.split(":")
        when (parts.size) {
            3 -> {
                val h = parts[0].toLong()
                val m = parts[1].toLong()
                val s = parts[2].toLong()
                (h * 3600 + m * 60 + s) * 1000
            }
            2 -> {
                val m = parts[0].toLong()
                val s = parts[1].toLong()
                (m * 60 + s) * 1000
            }
            else -> 0L
        }
    } catch (_: NumberFormatException) {
        0L
    }
}

/**
 * Converts milliseconds to UPnP duration format (H:MM:SS).
 */
fun formatMsToDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
}
