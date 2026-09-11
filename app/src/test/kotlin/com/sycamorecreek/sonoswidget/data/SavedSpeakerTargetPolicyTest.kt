package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroup
import com.sycamorecreek.sonoswidget.sonos.local.ZoneGroupMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 5 acceptance: "if unavailable, show recovery; do not silently control
 * another room".
 *
 * The saved-address fast path is where that goes wrong quietly. Every Sonos
 * speaker answers a transport query, so a reachable address looks like a
 * successful reconnect even when DHCP has handed it to a different speaker.
 */
class SavedSpeakerTargetPolicyTest {

    private val kitchen = ZoneGroupMember(
        uuid = "RINCON_KITCHEN", zoneName = "Kitchen", ip = "192.168.1.50", isCoordinator = true
    )
    private val office = ZoneGroupMember(
        uuid = "RINCON_OFFICE", zoneName = "Office", ip = "192.168.1.51", isCoordinator = true
    )

    private fun soloGroups(vararg members: ZoneGroupMember) = members.map {
        ZoneGroup(coordinatorId = it.uuid, groupId = "${it.uuid}:1", members = listOf(it))
    }

    // ── The room is still where we left it ───────────────────────────

    @Test fun `an ungrouped saved room resolves to itself`() {
        val target = SavedSpeakerTargetPolicy.resolveTarget(kitchen.uuid, soloGroups(kitchen, office))

        assertEquals(kitchen, target)
    }

    @Test fun `a grouped saved room resolves to its coordinator`() {
        // A grouped member answers transport commands with HTTP 500, so the
        // command has to go to the coordinator.
        val member = kitchen.copy(isCoordinator = false)
        val grouped = listOf(
            ZoneGroup(
                coordinatorId = office.uuid,
                groupId = "${office.uuid}:7",
                members = listOf(office, member)
            )
        )

        assertEquals(office, SavedSpeakerTargetPolicy.resolveTarget(member.uuid, grouped))
    }

    // ── The room is not where we left it ─────────────────────────────

    @Test fun `a saved room missing from the household is refused`() {
        // The address answered, but the speaker holding it is a different room:
        // the saved one has been removed, renamed or replaced.
        assertNull(SavedSpeakerTargetPolicy.resolveTarget(kitchen.uuid, soloGroups(office)))
    }

    @Test fun `a saved room that moved address resolves to its new one`() {
        // DHCP shuffled the household; the room is still here, just elsewhere.
        val movedKitchen = kitchen.copy(ip = "192.168.1.77")

        val target = SavedSpeakerTargetPolicy.resolveTarget(kitchen.uuid, soloGroups(movedKitchen, office))

        assertEquals("192.168.1.77", target?.ip)
    }

    // ── Nothing to verify against ────────────────────────────────────

    @Test fun `an unavailable topology is refused rather than assumed`() {
        assertNull(SavedSpeakerTargetPolicy.resolveTarget(kitchen.uuid, null))
        assertNull(SavedSpeakerTargetPolicy.resolveTarget(kitchen.uuid, emptyList()))
    }

    @Test fun `a blank saved room is refused`() {
        assertNull(SavedSpeakerTargetPolicy.resolveTarget("", soloGroups(kitchen)))
    }

    // ── Telling a regrouping apart from a reassigned address ─────────

    @Test fun `the saved address still belongs to the saved room`() {
        assertTrue(
            SavedSpeakerTargetPolicy.addressStillBelongsToRoom(
                kitchen.uuid, kitchen.ip, soloGroups(kitchen, office)
            )
        )
    }

    @Test fun `another speaker holding the saved address is detected`() {
        // The Office now answers on what used to be the Kitchen's address.
        val officeOnKitchensAddress = office.copy(ip = kitchen.ip)

        assertFalse(
            SavedSpeakerTargetPolicy.addressStillBelongsToRoom(
                kitchen.uuid,
                kitchen.ip,
                soloGroups(officeOnKitchensAddress)
            )
        )
    }

    @Test fun `an address nobody holds does not belong to the saved room`() {
        assertFalse(
            SavedSpeakerTargetPolicy.addressStillBelongsToRoom(
                kitchen.uuid, "192.168.1.99", soloGroups(kitchen)
            )
        )
        assertFalse(
            SavedSpeakerTargetPolicy.addressStillBelongsToRoom(kitchen.uuid, kitchen.ip, null)
        )
    }
}
