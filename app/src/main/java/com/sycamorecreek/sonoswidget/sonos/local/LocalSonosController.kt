package com.sycamorecreek.sonoswidget.sonos.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Result of local speaker discovery.
 * Used by both SSDP and mDNS discovery paths.
 * Mapped to the widget's Zone model by SonosRepository (Task 1.6).
 */
data class DiscoveredSpeaker(
    val ip: String,
    val port: Int = 1400,
    val id: String,
    val displayName: String,
    val modelName: String? = null,
    val modelNumber: String? = null
)

/**
 * Coordinates local Sonos speaker discovery and control.
 *
 * **Discovery** (Task 1.3):
 * Fires SSDP and mDNS concurrently with a 2-second timeout.
 * The first protocol to return a non-empty result wins; the other is cancelled.
 * Checks NEARBY_WIFI_DEVICES permission before any LAN access (Android 16 requirement).
 *
 * **Control** (Task 1.4):
 * Delegates SOAP actions to [SonosControlActions], which sends UPnP commands
 * (play, pause, volume, etc.) to the active speaker's IP.
 * All control methods target the group coordinator for proper zone behavior.
 */
class LocalSonosController(
    private val ssdpDiscovery: SsdpDiscovery = SsdpDiscovery(),
    private val mdnsDiscovery: MdnsDiscovery = MdnsDiscovery(),
    private val controlActions: SonosControlActions = SonosControlActions()
) {

    companion object {
        private const val TAG = "LocalSonosController"
        private const val DISCOVERY_TIMEOUT_MS = 2000L
    }

    fun hasLocalNetworkPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Discovers Sonos speakers on the local network.
     *
     * Races SSDP and mDNS concurrently (2-second cap).
     * First non-empty result wins; the losing protocol is cancelled.
     * Returns empty if permission is denied or no speakers are found.
     */
    suspend fun discoverSpeakers(context: Context): List<DiscoveredSpeaker> {
        if (!hasLocalNetworkPermission(context)) {
            Log.w(TAG, "NEARBY_WIFI_DEVICES not granted — skipping local discovery")
            return emptyList()
        }

        val result = withTimeoutOrNull(DISCOVERY_TIMEOUT_MS) {
            coroutineScope {
                val ssdpDeferred = async(Dispatchers.IO) {
                    try {
                        ssdpDiscovery.discover(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "SSDP discovery threw", e)
                        emptyList()
                    }
                }

                val mdnsDeferred = async(Dispatchers.IO) {
                    try {
                        mdnsDiscovery.discover(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "mDNS discovery threw", e)
                        emptyList()
                    }
                }

                select {
                    ssdpDeferred.onAwait { speakers ->
                        if (speakers.isNotEmpty()) {
                            mdnsDeferred.cancel()
                            Log.d(TAG, "SSDP won the race: ${speakers.size} speaker(s)")
                            speakers
                        } else {
                            Log.d(TAG, "SSDP returned empty, waiting for mDNS")
                            mdnsDeferred.await()
                        }
                    }
                    mdnsDeferred.onAwait { speakers ->
                        if (speakers.isNotEmpty()) {
                            ssdpDeferred.cancel()
                            Log.d(TAG, "mDNS won the race: ${speakers.size} speaker(s)")
                            speakers
                        } else {
                            Log.d(TAG, "mDNS returned empty, waiting for SSDP")
                            ssdpDeferred.await()
                        }
                    }
                }
            }
        }

        if (result.isNullOrEmpty()) {
            Log.w(TAG, "No speakers found within ${DISCOVERY_TIMEOUT_MS}ms")
        }

        return result ?: emptyList()
    }

    // ──────────────────────────────────────────────
    // Control actions (Task 1.4)
    //
    // These delegate to SonosControlActions, targeting a speaker by IP/port.
    // The caller (repository/service layer) is responsible for resolving
    // the active zone's coordinator IP before calling these methods.
    // ──────────────────────────────────────────────

    /** Start or resume playback on the target speaker. */
    suspend fun play(ip: String, port: Int = 1400): Boolean =
        controlActions.play(ip, port)

    /** Pause playback on the target speaker. */
    suspend fun pause(ip: String, port: Int = 1400): Boolean =
        controlActions.pause(ip, port)

    /** Stop playback on the target speaker. */
    suspend fun stop(ip: String, port: Int = 1400): Boolean =
        controlActions.stop(ip, port)

    /** Skip to the next track on the target speaker. */
    suspend fun next(ip: String, port: Int = 1400): Boolean =
        controlActions.next(ip, port)

    /** Skip to the previous track on the target speaker. */
    suspend fun previous(ip: String, port: Int = 1400): Boolean =
        controlActions.previous(ip, port)

    /** Seek to a position within the current track. */
    suspend fun seek(ip: String, port: Int = 1400, positionMs: Long): Boolean =
        controlActions.seek(ip, port, positionMs)

    /** Set the play mode (shuffle/repeat combination). */
    suspend fun setPlayMode(ip: String, port: Int = 1400, playMode: String): Boolean =
        controlActions.setPlayMode(ip, port, playMode)

    /** Get the current transport settings (play mode). */
    suspend fun getTransportSettings(ip: String, port: Int = 1400): TransportSettings? =
        controlActions.getTransportSettings(ip, port)

    /** Get the current transport state (playing, paused, stopped). */
    suspend fun getTransportInfo(ip: String, port: Int = 1400): TransportInfo? =
        controlActions.getTransportInfo(ip, port)

    /** Get the current track position, duration, and metadata. */
    suspend fun getPositionInfo(ip: String, port: Int = 1400): PositionInfo? =
        controlActions.getPositionInfo(ip, port)

    /** Get media info including number of tracks in queue. */
    suspend fun getMediaInfo(ip: String, port: Int = 1400): MediaInfo? =
        controlActions.getMediaInfo(ip, port)

    /** Get the current volume level (0–100). */
    suspend fun getVolume(ip: String, port: Int = 1400): VolumeInfo? =
        controlActions.getVolume(ip, port)

    /** Set the volume level (0–100). */
    suspend fun setVolume(ip: String, port: Int = 1400, volume: Int): Boolean =
        controlActions.setVolume(ip, port, volume)

    /** Get the current mute state. */
    suspend fun getMute(ip: String, port: Int = 1400): MuteInfo? =
        controlActions.getMute(ip, port)

    /** Set the mute state. */
    suspend fun setMute(ip: String, port: Int = 1400, muted: Boolean): Boolean =
        controlActions.setMute(ip, port, muted)

    /** Get the full zone group topology (all speakers and groupings). */
    suspend fun getZoneGroupState(ip: String, port: Int = 1400): List<ZoneGroup>? =
        controlActions.getZoneGroupState(ip, port)

    /** Seek to a specific track in the queue by 1-based position. */
    suspend fun seekToTrack(ip: String, port: Int = 1400, trackNr: Int): Boolean =
        controlActions.seekToTrack(ip, port, trackNr)

    /** Browse the Sonos queue. Returns up to [count] items from [startIndex]. */
    suspend fun browseQueue(
        ip: String, port: Int = 1400, startIndex: Int = 0, count: Int = 20
    ): List<QueueItemInfo>? =
        controlActions.browseQueue(ip, port, startIndex, count)

    /** Add a speaker to a group by targeting the coordinator's UUID. */
    suspend fun addToGroup(ip: String, port: Int = 1400, coordinatorUuid: String): Boolean =
        controlActions.addToGroup(ip, port, coordinatorUuid)

    /** Remove a speaker from its group, making it standalone. */
    suspend fun removeFromGroup(ip: String, port: Int = 1400): Boolean =
        controlActions.removeFromGroup(ip, port)

    /**
     * Polls the full playback state in a single batch.
     *
     * Fetches transport info, position info, and volume concurrently
     * for efficient widget updates. Returns null components individually
     * if any single request fails.
     *
     * @return Triple of (TransportInfo?, PositionInfo?, VolumeInfo?)
     */
    suspend fun pollPlaybackState(
        ip: String,
        port: Int = 1400
    ): Triple<TransportInfo?, PositionInfo?, VolumeInfo?> = coroutineScope {
        val transport = async(Dispatchers.IO) { controlActions.getTransportInfo(ip, port) }
        val position = async(Dispatchers.IO) { controlActions.getPositionInfo(ip, port) }
        val volume = async(Dispatchers.IO) { controlActions.getVolume(ip, port) }
        Triple(transport.await(), position.await(), volume.await())
    }
}
