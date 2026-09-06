package com.sycamorecreek.sonoswidget.service

import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.PendingWidgetOperation
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.RepeatMode
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.Track
import com.sycamorecreek.sonoswidget.widget.WidgetCapabilities
import com.sycamorecreek.sonoswidget.widget.WidgetOperationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateStoreTest {

    @Test
    fun `legacy JSON with missing experience fields receives safe defaults`() {
        val state = WidgetStateStore.deserialize("""{
            "playbackState":"PAUSED",
            "currentTrack":{"name":"Legacy track"},
            "volume":21
        }""")

        assertEquals(PlaybackState.PAUSED, state.playbackState)
        assertEquals("Legacy track", state.currentTrack.name)
        assertEquals(21, state.volume)
        assertEquals(ConnectionMode.DISCONNECTED, state.connectionMode)
        assertFalse(state.capabilities.canPlayPause)
        assertTrue(state.pendingOperations.isEmpty())
    }

    @Test
    fun `unknown persisted enums do not discard otherwise usable state`() {
        val state = WidgetStateStore.deserialize("""{
            "playbackState":"FUTURE_STATE",
            "connectionMode":"FUTURE_CONNECTION",
            "repeatMode":"FUTURE_REPEAT",
            "currentTrack":{"name":"Still readable"},
            "volume":33
        }""")

        assertEquals(PlaybackState.STOPPED, state.playbackState)
        assertEquals(ConnectionMode.DISCONNECTED, state.connectionMode)
        assertEquals(RepeatMode.NONE, state.repeatMode)
        assertEquals("Still readable", state.currentTrack.name)
        assertEquals(33, state.volume)
    }

    @Test
    fun `process restart clears transient operations but preserves confirmed state`() {
        val original = SonosWidgetState(
            playbackState = PlaybackState.PLAYING,
            currentTrack = Track(name = "Confirmed track", durationMs = 123_000L),
            connectionMode = ConnectionMode.LOCAL_SSDP,
            capabilities = WidgetCapabilities(canPlayPause = true, canGroup = true),
            pendingOperations = listOf(
                PendingWidgetOperation(
                    id = "group-1",
                    type = WidgetOperationType.APPLYING_GROUPING,
                    affectedField = "grouping"
                )
            ),
            lastEssentialRefreshMs = 42L,
            isContentStale = true
        )

        val restored = WidgetStateStore.deserialize(WidgetStateStore.serialize(original))

        assertEquals(PlaybackState.PLAYING, restored.playbackState)
        assertEquals("Confirmed track", restored.currentTrack.name)
        assertTrue(restored.capabilities.canPlayPause)
        assertTrue(restored.capabilities.canGroup)
        assertEquals(42L, restored.lastEssentialRefreshMs)
        assertTrue(restored.isContentStale)
        assertTrue(restored.pendingOperations.isEmpty())
    }
}
