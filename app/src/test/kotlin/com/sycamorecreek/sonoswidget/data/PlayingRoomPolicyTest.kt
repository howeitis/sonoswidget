package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.data.PlayingRoomPolicy.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayingRoomPolicyTest {

    @Test fun `moves to the room that is playing`() {
        val probes = listOf(Probe("KITCHEN", "STOPPED"), Probe("OFFICE", "PLAYING"))

        assertEquals("OFFICE", PlayingRoomPolicy.pick(probes, activeCoordinatorId = "KITCHEN"))
    }

    @Test fun `a room still loading what was just started counts`() {
        val probes = listOf(Probe("KITCHEN", "STOPPED"), Probe("OFFICE", "TRANSITIONING"))

        assertEquals("OFFICE", PlayingRoomPolicy.pick(probes, activeCoordinatorId = "KITCHEN"))
    }

    @Test fun `playing beats loading`() {
        val probes = listOf(Probe("DEN", "TRANSITIONING"), Probe("OFFICE", "PLAYING"))

        assertEquals("OFFICE", PlayingRoomPolicy.pick(probes, activeCoordinatorId = "KITCHEN"))
    }

    @Test fun `never trades one silent room for another`() {
        // Discovery's ranking would accept a paused or merely reachable room;
        // an explicit scan must not lose the user's place for one.
        val probes = listOf(
            Probe("KITCHEN", "STOPPED"),
            Probe("OFFICE", "PAUSED_PLAYBACK"),
            Probe("DEN", null)
        )

        assertNull(PlayingRoomPolicy.pick(probes, activeCoordinatorId = "KITCHEN"))
    }

    @Test fun `the active room is never a switch target`() {
        val probes = listOf(Probe("KITCHEN", "PLAYING"))

        assertNull(PlayingRoomPolicy.pick(probes, activeCoordinatorId = "KITCHEN"))
    }
}
