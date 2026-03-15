package com.sycamorecreek.sonoswidget.sonos.local

import android.util.Log

/**
 * High-level typed wrappers for Sonos UPnP SOAP actions.
 *
 * Each method invokes [SonosSoapClient] with the correct service, action name,
 * and parameters, then parses the XML response into a typed model from [SoapModels].
 *
 * Covers the three UPnP services needed for widget playback control:
 *   - AVTransport:      play/pause/stop/skip, transport & position state
 *   - RenderingControl: volume & mute
 *   - ZoneGroupTopology: zone/group membership
 *
 * All methods are suspend functions safe to call from any coroutine scope.
 * Network errors return null; callers should handle gracefully.
 */
class SonosControlActions(
    private val soapClient: SonosSoapClient = SonosSoapClient()
) {

    companion object {
        private const val TAG = "SonosControlActions"
        private const val INSTANCE_ID = "0"
    }

    // ──────────────────────────────────────────────
    // AVTransport — Transport commands
    // ──────────────────────────────────────────────

    /** Start or resume playback. */
    suspend fun play(ip: String, port: Int = 1400): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Play",
            listOf("InstanceID" to INSTANCE_ID, "Speed" to "1"))

    /** Pause playback. */
    suspend fun pause(ip: String, port: Int = 1400): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Pause",
            listOf("InstanceID" to INSTANCE_ID))

    /** Stop playback entirely. */
    suspend fun stop(ip: String, port: Int = 1400): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Stop",
            listOf("InstanceID" to INSTANCE_ID))

    /** Skip to the next track. */
    suspend fun next(ip: String, port: Int = 1400): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Next",
            listOf("InstanceID" to INSTANCE_ID))

    /** Skip to the previous track. */
    suspend fun previous(ip: String, port: Int = 1400): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Previous",
            listOf("InstanceID" to INSTANCE_ID))

    /**
     * Seek to a position within the current track.
     * @param positionMs Target position in milliseconds
     */
    suspend fun seek(ip: String, port: Int = 1400, positionMs: Long): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.AV_TRANSPORT, "Seek",
            listOf(
                "InstanceID" to INSTANCE_ID,
                "Unit" to "REL_TIME",
                "Target" to formatMsToDuration(positionMs)
            ))

    // ──────────────────────────────────────────────
    // AVTransport — State queries
    // ──────────────────────────────────────────────

    /**
     * Gets the current transport state (playing, paused, stopped, etc.).
     * Returns null if the request fails.
     */
    suspend fun getTransportInfo(ip: String, port: Int = 1400): TransportInfo? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.AV_TRANSPORT,
            "GetTransportInfo",
            listOf("InstanceID" to INSTANCE_ID)
        ) ?: return null

        return try {
            TransportInfo(
                state = extractXmlValue(xml, "CurrentTransportState") ?: "STOPPED",
                status = extractXmlValue(xml, "CurrentTransportStatus") ?: "OK",
                speed = extractXmlValue(xml, "CurrentSpeed") ?: "1"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetTransportInfo", e)
            null
        }
    }

    /**
     * Gets the current track position, duration, and metadata.
     * Returns null if the request fails.
     */
    suspend fun getPositionInfo(ip: String, port: Int = 1400): PositionInfo? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.AV_TRANSPORT,
            "GetPositionInfo",
            listOf("InstanceID" to INSTANCE_ID)
        ) ?: return null

        return try {
            val trackMetaDataRaw = extractXmlValueGreedy(xml, "TrackMetaData")
            val metadata = if (!trackMetaDataRaw.isNullOrBlank() &&
                trackMetaDataRaw != "NOT_IMPLEMENTED"
            ) {
                parseDidlLite(decodeXmlEntities(trackMetaDataRaw))
            } else null

            PositionInfo(
                trackNum = extractXmlValue(xml, "Track")?.toIntOrNull() ?: 0,
                trackDuration = extractXmlValue(xml, "TrackDuration") ?: "0:00:00",
                trackUri = extractXmlValue(xml, "TrackURI") ?: "",
                relTime = extractXmlValue(xml, "RelTime") ?: "0:00:00",
                metadata = metadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetPositionInfo", e)
            null
        }
    }

    /**
     * Gets media info including number of tracks in the queue.
     * Returns null if the request fails.
     */
    suspend fun getMediaInfo(ip: String, port: Int = 1400): MediaInfo? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.AV_TRANSPORT,
            "GetMediaInfo",
            listOf("InstanceID" to INSTANCE_ID)
        ) ?: return null

        return try {
            val metaDataRaw = extractXmlValueGreedy(xml, "CurrentURIMetaData")
            val metadata = if (!metaDataRaw.isNullOrBlank() &&
                metaDataRaw != "NOT_IMPLEMENTED"
            ) {
                parseDidlLite(decodeXmlEntities(metaDataRaw))
            } else null

            MediaInfo(
                nrTracks = extractXmlValue(xml, "NrTracks")?.toIntOrNull() ?: 0,
                currentUri = extractXmlValue(xml, "CurrentURI") ?: "",
                metadata = metadata
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetMediaInfo", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // RenderingControl — Volume & Mute
    // ──────────────────────────────────────────────

    /**
     * Gets the current volume level (0–100).
     * Returns null if the request fails.
     */
    suspend fun getVolume(ip: String, port: Int = 1400): VolumeInfo? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.RENDERING_CONTROL,
            "GetVolume",
            listOf("InstanceID" to INSTANCE_ID, "Channel" to "Master")
        ) ?: return null

        return try {
            val vol = extractXmlValue(xml, "CurrentVolume")?.toIntOrNull() ?: 0
            VolumeInfo(volume = vol.coerceIn(0, 100))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetVolume", e)
            null
        }
    }

    /**
     * Sets the volume level (0–100).
     * @param volume Desired volume level, clamped to 0–100
     */
    suspend fun setVolume(ip: String, port: Int = 1400, volume: Int): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.RENDERING_CONTROL, "SetVolume",
            listOf(
                "InstanceID" to INSTANCE_ID,
                "Channel" to "Master",
                "DesiredVolume" to volume.coerceIn(0, 100).toString()
            ))

    /**
     * Gets the current mute state.
     * Returns null if the request fails.
     */
    suspend fun getMute(ip: String, port: Int = 1400): MuteInfo? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.RENDERING_CONTROL,
            "GetMute",
            listOf("InstanceID" to INSTANCE_ID, "Channel" to "Master")
        ) ?: return null

        return try {
            val muted = extractXmlValue(xml, "CurrentMute") == "1"
            MuteInfo(muted = muted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetMute", e)
            null
        }
    }

    /**
     * Sets the mute state.
     * @param muted true to mute, false to unmute
     */
    suspend fun setMute(ip: String, port: Int = 1400, muted: Boolean): Boolean =
        invokeSimple(ip, port, SonosSoapClient.Service.RENDERING_CONTROL, "SetMute",
            listOf(
                "InstanceID" to INSTANCE_ID,
                "Channel" to "Master",
                "DesiredMute" to if (muted) "1" else "0"
            ))

    // ──────────────────────────────────────────────
    // ZoneGroupTopology — Zone & Group management
    // ──────────────────────────────────────────────

    /**
     * Gets the current zone group topology — all speakers and their groupings.
     *
     * The response contains an entity-encoded XML blob in <ZoneGroupState>
     * that must be decoded and then parsed for <ZoneGroup> / <ZoneGroupMember> elements.
     *
     * Returns null if the request fails.
     */
    suspend fun getZoneGroupState(ip: String, port: Int = 1400): List<ZoneGroup>? {
        val xml = soapClient.invoke(
            ip, port,
            SonosSoapClient.Service.ZONE_GROUP_TOPOLOGY,
            "GetZoneGroupState",
            emptyList()
        ) ?: return null

        return try {
            val stateRaw = extractXmlValueGreedy(xml, "ZoneGroupState")
            if (stateRaw.isNullOrBlank()) {
                Log.w(TAG, "GetZoneGroupState returned empty ZoneGroupState")
                return emptyList()
            }

            val decoded = decodeXmlEntities(stateRaw)
            parseZoneGroupState(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GetZoneGroupState", e)
            null
        }
    }

    // ──────────────────────────────────────────────
    // Internal helpers
    // ──────────────────────────────────────────────

    /**
     * Invokes a SOAP action that has no meaningful response body (commands like Play, Pause, etc.).
     * Returns true if the request succeeded (non-null response), false otherwise.
     */
    private suspend fun invokeSimple(
        ip: String,
        port: Int,
        service: SonosSoapClient.Service,
        action: String,
        params: List<Pair<String, String>>
    ): Boolean {
        val result = soapClient.invoke(ip, port, service, action, params)
        if (result == null) {
            Log.w(TAG, "$action failed for $ip:$port")
        }
        return result != null
    }

    /**
     * Parses DIDL-Lite XML to extract track metadata.
     *
     * DIDL-Lite is the standard UPnP metadata format. Sonos uses these elements:
     *   - dc:title       → track name
     *   - dc:creator      → artist name
     *   - upnp:album      → album name
     *   - upnp:albumArtURI → album art URL (may be relative to speaker)
     *   - res@duration     → track duration
     */
    private fun parseDidlLite(didl: String): TrackMetadata? {
        if (didl.isBlank() || !didl.contains("DIDL-Lite", ignoreCase = true)) {
            return null
        }

        val title = extractDidlValue(didl, "dc:title") ?: ""
        val artist = extractDidlValue(didl, "dc:creator")
            ?: extractDidlValue(didl, "upnp:artist")
            ?: ""
        val album = extractDidlValue(didl, "upnp:album") ?: ""
        val albumArtUri = extractDidlValue(didl, "upnp:albumArtURI")

        // Duration can appear as an attribute on the <res> element
        val durationText = Regex("""duration="([^"]+)"""")
            .find(didl)?.groupValues?.get(1)

        return TrackMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtUri = albumArtUri,
            durationText = durationText
        )
    }

    /**
     * Extracts a value from a namespaced DIDL-Lite element.
     * Handles both <dc:title>value</dc:title> and self-closing tags.
     */
    private fun extractDidlValue(didl: String, tag: String): String? {
        val pattern = "<$tag[^>]*>([^<]*)</$tag>"
        return Regex(pattern).find(didl)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    /**
     * Parses the decoded ZoneGroupState XML into a list of [ZoneGroup] objects.
     *
     * The XML structure is:
     * ```xml
     * <ZoneGroups>
     *   <ZoneGroup Coordinator="RINCON_xxx" ID="RINCON_xxx:123">
     *     <ZoneGroupMember UUID="RINCON_xxx"
     *         Location="http://192.168.1.10:1400/xml/device_description.xml"
     *         ZoneName="Living Room" ... />
     *   </ZoneGroup>
     * </ZoneGroups>
     * ```
     */
    private fun parseZoneGroupState(xml: String): List<ZoneGroup> {
        val groups = mutableListOf<ZoneGroup>()

        // Match each <ZoneGroup ...>...</ZoneGroup> block
        val groupPattern = Regex(
            """<ZoneGroup\s+([^>]*)>(.*?)</ZoneGroup>""",
            RegexOption.DOT_MATCHES_ALL
        )

        for (groupMatch in groupPattern.findAll(xml)) {
            val groupAttrs = groupMatch.groupValues[1]
            val groupBody = groupMatch.groupValues[2]

            val coordinatorId = extractXmlAttribute(groupAttrs, "Coordinator") ?: continue
            val groupId = extractXmlAttribute(groupAttrs, "ID") ?: coordinatorId

            // Match each <ZoneGroupMember .../> within this group
            val memberPattern = Regex("""<ZoneGroupMember\s+([^/]*?)/>""", RegexOption.DOT_MATCHES_ALL)
            val members = mutableListOf<ZoneGroupMember>()

            for (memberMatch in memberPattern.findAll(groupBody)) {
                val memberAttrs = memberMatch.groupValues[1]
                val uuid = extractXmlAttribute(memberAttrs, "UUID") ?: continue
                val zoneName = extractXmlAttribute(memberAttrs, "ZoneName") ?: "Unknown"
                val location = extractXmlAttribute(memberAttrs, "Location") ?: ""

                // Extract IP and port from Location URL
                // e.g., "http://192.168.1.10:1400/xml/device_description.xml"
                val (ip, port) = parseLocationUrl(location)

                members.add(
                    ZoneGroupMember(
                        uuid = uuid,
                        zoneName = zoneName,
                        ip = ip,
                        port = port,
                        isCoordinator = uuid == coordinatorId
                    )
                )
            }

            if (members.isNotEmpty()) {
                groups.add(
                    ZoneGroup(
                        coordinatorId = coordinatorId,
                        groupId = groupId,
                        members = members
                    )
                )
            }
        }

        Log.d(TAG, "Parsed ${groups.size} zone group(s) with ${groups.sumOf { it.members.size }} total member(s)")
        return groups
    }

    /**
     * Extracts IP and port from a Sonos device Location URL.
     * Falls back to "0.0.0.0":1400 if parsing fails.
     */
    private fun parseLocationUrl(location: String): Pair<String, Int> {
        return try {
            val url = java.net.URL(location)
            val ip = url.host ?: "0.0.0.0"
            val port = if (url.port > 0) url.port else 1400
            ip to port
        } catch (_: Exception) {
            "0.0.0.0" to 1400
        }
    }
}
