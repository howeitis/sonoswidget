package com.sycamorecreek.sonoswidget.service

import com.sycamorecreek.sonoswidget.sonos.local.PositionInfo
import com.sycamorecreek.sonoswidget.sonos.local.TransportInfo
import com.sycamorecreek.sonoswidget.sonos.local.VolumeInfo
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.parseDurationToMs
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.Track
import com.sycamorecreek.sonoswidget.widget.Zone

/**
 * Pure mapping functions that convert SOAP response models (Task 1.4)
 * into the widget's domain model (Task 1.2).
 *
 * This is the bridge between the Sonos UPnP layer and the widget UI.
 * All functions are stateless and side-effect free.
 */
object WidgetStateMapper {

    /**
     * Builds a full [SonosWidgetState] from individual SOAP poll results.
     *
     * Any null parameter is treated as "unavailable" and the corresponding
     * field falls back to its default value.
     */
    fun buildState(
        transportInfo: TransportInfo?,
        positionInfo: PositionInfo?,
        volumeInfo: VolumeInfo?,
        zoneGroups: List<ZoneGroup>?,
        activeZoneId: String?,
        connectionMode: ConnectionMode = ConnectionMode.LOCAL_SSDP
    ): SonosWidgetState {
        val playbackState = mapPlaybackState(transportInfo)
        val currentTrack = mapTrack(positionInfo)
        val zones = mapZones(zoneGroups)
        val activeZone = resolveActiveZone(zones, activeZoneId)
        val volume = volumeInfo?.volume ?: 50

        return SonosWidgetState(
            playbackState = playbackState,
            currentTrack = currentTrack,
            activeZone = activeZone,
            volume = volume,
            zones = zones,
            connectionMode = connectionMode,
            lastUpdatedMs = System.currentTimeMillis()
        )
    }

    /**
     * Maps AVTransport's CurrentTransportState to the widget's PlaybackState enum.
     *
     * Sonos transport states:
     *   PLAYING           → PLAYING
     *   PAUSED_PLAYBACK   → PAUSED
     *   STOPPED           → STOPPED
     *   TRANSITIONING     → TRANSITIONING
     *   NO_MEDIA_PRESENT  → STOPPED
     */
    fun mapPlaybackState(transportInfo: TransportInfo?): PlaybackState {
        if (transportInfo == null) return PlaybackState.STOPPED
        return when (transportInfo.state) {
            "PLAYING" -> PlaybackState.PLAYING
            "PAUSED_PLAYBACK" -> PlaybackState.PAUSED
            "TRANSITIONING" -> PlaybackState.TRANSITIONING
            else -> PlaybackState.STOPPED
        }
    }

    /**
     * Maps GetPositionInfo response + DIDL-Lite metadata to a [Track].
     *
     * Duration and elapsed time are converted from H:MM:SS to milliseconds.
     * Album art URL from DIDL-Lite metadata is passed through directly —
     * it may be a relative URL that needs resolving against the speaker IP
     * (handled by the image loading layer in Task 1.7).
     */
    fun mapTrack(positionInfo: PositionInfo?): Track {
        if (positionInfo == null) return Track()

        val metadata = positionInfo.metadata
        return Track(
            name = metadata?.title ?: "",
            artist = metadata?.artist ?: "",
            album = metadata?.album ?: "",
            artUrl = metadata?.albumArtUri,
            durationMs = parseDurationToMs(positionInfo.trackDuration),
            elapsedMs = parseDurationToMs(positionInfo.relTime)
        )
    }

    /**
     * Flattens zone group topology into a flat list of [Zone] objects.
     *
     * Each ZoneGroupMember becomes a Zone, with:
     *   - id = UUID
     *   - displayName = ZoneName
     *   - groupId = the parent ZoneGroup's ID
     *   - isGroupCoordinator = true for the coordinator member
     */
    fun mapZones(zoneGroups: List<ZoneGroup>?): List<Zone> {
        if (zoneGroups.isNullOrEmpty()) return emptyList()

        return zoneGroups.flatMap { group ->
            group.members.map { member ->
                Zone(
                    id = member.uuid,
                    displayName = member.zoneName,
                    groupId = group.groupId,
                    isGroupCoordinator = member.isCoordinator
                )
            }
        }
    }

    /**
     * Resolves the active zone from the zone list.
     *
     * Priority:
     * 1. Match by activeZoneId if provided
     * 2. First group coordinator (most useful default — controls the group)
     * 3. First zone in the list
     * 4. Empty Zone (no speakers found)
     */
    fun resolveActiveZone(zones: List<Zone>, activeZoneId: String?): Zone {
        if (zones.isEmpty()) return Zone()

        // Try exact match first
        if (activeZoneId != null) {
            zones.find { it.id == activeZoneId }?.let { return it }
        }

        // Default to the first coordinator
        return zones.find { it.isGroupCoordinator } ?: zones.first()
    }
}
