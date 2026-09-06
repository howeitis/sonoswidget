package com.sycamorecreek.sonoswidget.widget

import com.sycamorecreek.sonoswidget.service.WidgetStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateFixturesTest {

    @Test
    fun `fixture catalog spans playback, connection, and content edge states`() {
        assertEquals(PlaybackState.PLAYING, WidgetStateFixtures.playing.playbackState)
        assertEquals(PlaybackState.PAUSED, WidgetStateFixtures.paused.playbackState)
        assertEquals("TV", WidgetStateFixtures.television.currentSource)
        assertFalse(WidgetStateFixtures.radio.capabilities.canSeek)
        assertTrue(WidgetStateFixtures.idle.favorites.isNotEmpty())
        assertTrue(WidgetStateFixtures.disconnectedWithCachedMetadata.isContentStale)
        assertTrue(WidgetStateFixtures.permissionDenied.showPermissionHint)
        assertEquals(ConnectionMode.CLOUD, WidgetStateFixtures.cloud.connectionMode)
        assertTrue(WidgetStateFixtures.loadingFavorite.pendingOperations.isNotEmpty())
        assertTrue(WidgetStateFixtures.failedCommand.errorMessage != null)
        assertEquals(null, WidgetStateFixtures.missingArtwork.currentTrack.artUrl)
        assertEquals("bright-sample-v1", WidgetStateFixtures.brightArtwork.artworkVersion)
        assertTrue(WidgetStateFixtures.longContent.zones.size > 6)
        assertTrue(WidgetStateFixtures.longContent.favorites.size > 6)
    }

    @Test
    fun `loading fixture retains feedback through the widget display decoder`() {
        val decoded = WidgetStateStore.deserialize(
            WidgetStateStore.serialize(WidgetStateFixtures.loadingFavorite)
        )

        assertEquals(WidgetOperationType.LOADING_FAVORITE, decoded.pendingOperations.single().type)
    }
}
