package com.sycamorecreek.sonoswidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomFollowMigrationPolicyTest {

    @Test
    fun `saved legacy default migrates to stay with that room`() {
        val room = SonosPreferences.DefaultZone("RINCON_1", "Kitchen")
        val decision = resolve(legacyDefault = room, hasLegacyEvidence = true)

        assertEquals(SonosPreferences.RoomFollowMode.STAY_WITH_ROOM, decision.mode)
        assertEquals(room, decision.target)
    }

    @Test
    fun `legacy install with cleared default follows playing music`() {
        val decision = resolve(hasLegacyEvidence = true)

        assertEquals(SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC, decision.mode)
        assertNull(decision.target)
    }

    @Test
    fun `empty storage defaults to stay with room for new installs`() {
        val decision = resolve()

        assertEquals(SonosPreferences.RoomFollowMode.STAY_WITH_ROOM, decision.mode)
        assertNull(decision.target)
    }

    @Test
    fun `explicit migrated choice remains unchanged`() {
        val room = SonosPreferences.DefaultZone("RINCON_2", "Office")
        val decision = RoomFollowMigrationPolicy.resolve(
            RoomFollowMigrationPolicy.Snapshot(
                version = RoomFollowMigrationPolicy.CURRENT_VERSION,
                explicitMode = SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC,
                explicitTarget = room,
                legacyDefault = SonosPreferences.DefaultZone("old", "Old room"),
                hasLegacyEvidence = true
            )
        )

        assertTrue(decision.alreadyMigrated)
        assertEquals(SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC, decision.mode)
        assertEquals(room, decision.target)
    }

    private fun resolve(
        legacyDefault: SonosPreferences.DefaultZone? = null,
        hasLegacyEvidence: Boolean = false
    ): RoomFollowMigrationPolicy.Decision {
        val decision = RoomFollowMigrationPolicy.resolve(
            RoomFollowMigrationPolicy.Snapshot(
                version = 0,
                explicitMode = null,
                explicitTarget = null,
                legacyDefault = legacyDefault,
                hasLegacyEvidence = hasLegacyEvidence
            )
        )
        assertFalse(decision.alreadyMigrated)
        return decision
    }
}
