package com.sycamorecreek.sonoswidget.service

import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.RepeatMode
import com.sycamorecreek.sonoswidget.widget.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateMapperTest {

    @Test
    fun `cloud radio exposes only supported controls`() {
        val capabilities = WidgetStateMapper.capabilitiesFor(
            connectionMode = ConnectionMode.CLOUD,
            track = Track(name = "Morning Radio"),
            currentSource = "Radio",
            hasQueue = true,
            hasFavorites = true,
            hasLocalGrouping = true
        )

        assertTrue(capabilities.canPlayPause)
        assertTrue(capabilities.canPrevious)
        assertTrue(capabilities.canNext)
        assertTrue(capabilities.canChangeVolume)
        assertFalse(capabilities.canSeek)
        assertFalse(capabilities.canMute)
        assertFalse(capabilities.canViewQueue)
        assertFalse(capabilities.canPlayFavorites)
        assertFalse(capabilities.canGroup)
    }

    @Test
    fun `tv source removes transport controls that Sonos does not support`() {
        val capabilities = WidgetStateMapper.capabilitiesFor(
            connectionMode = ConnectionMode.LOCAL_SSDP,
            track = Track(name = "Living Room TV", durationMs = 3_600_000L),
            currentSource = "TV",
            hasQueue = true,
            hasFavorites = true,
            hasLocalGrouping = true
        )

        assertTrue(capabilities.canPlayPause)
        assertTrue(capabilities.canMute)
        assertTrue(capabilities.canChangeVolume)
        assertTrue(capabilities.canGroup)
        assertFalse(capabilities.canPrevious)
        assertFalse(capabilities.canNext)
        assertFalse(capabilities.canSeek)
        assertFalse(capabilities.canShuffle)
        assertFalse(capabilities.canRepeat)
    }

    @Test
    fun `play modes round trip through their widget representation`() {
        RepeatMode.entries.forEach { repeat ->
            listOf(false, true).forEach { shuffle ->
                val mode = WidgetStateMapper.buildPlayMode(shuffle, repeat)

                assertEquals(shuffle, WidgetStateMapper.mapShuffleEnabled(
                    com.sycamorecreek.sonoswidget.sonos.local.TransportSettings(mode)
                ))
                assertEquals(repeat, WidgetStateMapper.mapRepeatMode(
                    com.sycamorecreek.sonoswidget.sonos.local.TransportSettings(mode)
                ))
            }
        }
    }

    @Test
    fun `source mapping keeps TV distinct from unknown duration audio`() {
        assertEquals("TV", WidgetStateMapper.mapCurrentSource("x-sonos-htastream:RINCON_123"))
        assertEquals("Internet Radio", WidgetStateMapper.mapCurrentSource("http://example.test/live"))
        assertEquals("", WidgetStateMapper.mapCurrentSource("x-unknown:source"))
    }
}
