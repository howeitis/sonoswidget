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
import org.json.JSONObject
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
    fun `display serialization round trip preserves pending operations`() {
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
        assertEquals(original.pendingOperations, restored.pendingOperations)
    }

    @Test
    fun `display decoding keeps operations from the publishing session`() {
        val json = WidgetStateStore.serialize(loadingFavoriteState)

        val decoded = WidgetStateStore.deserializeForDisplay(json)

        assertEquals(PlaybackState.PLAYING, decoded.playbackState)
        assertEquals("favorite-1", decoded.pendingOperations.single().id)
    }

    @Test
    fun `display decoding drops operations left by an earlier process`() {
        val json = JSONObject(WidgetStateStore.serialize(loadingFavoriteState))
            .put("sessionId", "a-process-that-has-since-died")
            .toString()

        val decoded = WidgetStateStore.deserializeForDisplay(json)

        // The rest of the last known state still renders; only the request that
        // can no longer resolve is dropped, for the next poll to reconcile.
        assertEquals(PlaybackState.PLAYING, decoded.playbackState)
        assertTrue(decoded.pendingOperations.isEmpty())
    }

    @Test
    fun `display decoding drops operations stored before session identity`() {
        val json = JSONObject(WidgetStateStore.serialize(loadingFavoriteState))
            .apply { remove("sessionId") }
            .toString()

        assertTrue(WidgetStateStore.deserializeForDisplay(json).pendingOperations.isEmpty())
    }

    private val loadingFavoriteState = SonosWidgetState(
        playbackState = PlaybackState.PLAYING,
        pendingOperations = listOf(
            PendingWidgetOperation(
                id = "favorite-1",
                type = WidgetOperationType.LOADING_FAVORITE,
                targetId = "kitchen"
            )
        )
    )
}
