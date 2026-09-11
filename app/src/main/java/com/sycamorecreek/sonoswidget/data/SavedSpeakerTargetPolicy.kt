package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroupMember

/**
 * Confirms that the speaker answering at a saved address is still the room the
 * user chose, and resolves which speaker to actually talk to.
 *
 * A reachable IP proves nothing about identity. DHCP hands a departed speaker's
 * address to a different one, and every Sonos speaker answers `GetTransportInfo`
 * — so the fast reconnect path can look successful while pointing at a room the
 * user never selected. Phase 5 is explicit that an unavailable room must show
 * recovery rather than silently control another.
 *
 * Zone group state is household-wide, so asking any reachable speaker returns
 * every group. That makes it possible to find the saved room wherever it now
 * lives, and to tell "moved to a new address" apart from "no longer here".
 */
internal object SavedSpeakerTargetPolicy {

    /**
     * The speaker to send commands to for [savedZoneId], or null when the saved
     * room cannot be confirmed and the caller should fall through to discovery.
     *
     * Commands go to the group coordinator: a grouped member answers transport
     * commands with HTTP 500.
     */
    fun resolveTarget(savedZoneId: String, zoneGroups: List<ZoneGroup>?): ZoneGroupMember? {
        if (savedZoneId.isBlank()) return null
        // Without topology nothing identifies who holds the address, and
        // guessing means risking another room.
        if (zoneGroups.isNullOrEmpty()) return null

        val savedGroup = zoneGroups.firstOrNull { group ->
            group.members.any { it.uuid == savedZoneId }
        } ?: return null

        // A group always has a coordinator; a topology that says otherwise is
        // not one to act on.
        return savedGroup.members.firstOrNull { it.isCoordinator }
            ?: savedGroup.members.firstOrNull { it.uuid == savedZoneId }
    }

    /**
     * Whether the saved address is still held by the saved room.
     *
     * False means the address was reassigned. The room may still exist
     * elsewhere — [resolveTarget] says where — but the saved address must not
     * be used for it.
     */
    fun addressStillBelongsToRoom(
        savedZoneId: String,
        savedIp: String,
        zoneGroups: List<ZoneGroup>?
    ): Boolean {
        val occupant = zoneGroups
            ?.flatMap { it.members }
            ?.firstOrNull { it.ip == savedIp }
            ?: return false
        return occupant.uuid == savedZoneId
    }
}
