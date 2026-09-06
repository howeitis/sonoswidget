package com.sycamorecreek.sonoswidget.widget

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
        assertTrue(WidgetStateFixtures.longContent.zones.size > 6)
        assertTrue(WidgetStateFixtures.longContent.favorites.size > 6)
    }
}
