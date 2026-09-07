package com.sycamorecreek.sonoswidget.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.glance.GlanceId
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.currentState
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.WidgetBackgroundRenderer
import com.sycamorecreek.sonoswidget.service.WidgetStateStore

/**
 * Main Glance widget for Sonos control.
 *
 * Uses SizeMode.Responsive with three size buckets:
 * - Mini    (~240x80dp)   — lock screen / hyper-compact
 * - Compact (~320x180dp)  — 4x2 home screen
 * - Full    (~400x460dp)  — enlarged home screen (expanded layout)
 *
 * Reads playback state from Glance Preferences (written by PlaybackService
 * via WidgetStateStore) and delegates to the appropriate layout composable.
 *
 * Album art is loaded from internal storage (saved by PlaybackService via
 * AlbumArtLoader). Glance cannot use Coil Compose, so we read the bitmap
 * directly from disk during provideGlance().
 */
class SonosWidget : GlanceAppWidget() {

    companion object {
        val MINI_SIZE = DpSize(
            WidgetLayoutPolicy.MINI_WIDTH_DP.dp,
            WidgetLayoutPolicy.MINI_HEIGHT_DP.dp
        )
        val HALF_SIZE = DpSize(
            WidgetLayoutPolicy.COMPACT_WIDTH_DP.dp,
            WidgetLayoutPolicy.COMPACT_HEIGHT_DP.dp
        )
        val FULL_SIZE = DpSize(
            WidgetLayoutPolicy.EXPANDED_WIDTH_DP.dp,
            WidgetLayoutPolicy.EXPANDED_HEIGHT_DP.dp
        )
    }

    override val sizeMode = SizeMode.Responsive(
        setOf(MINI_SIZE, HALF_SIZE, FULL_SIZE)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read serialized state from Glance Preferences
            val prefs = currentState<Preferences>()
            val stateJson = prefs[WidgetStateStore.STATE_KEY]
            val state = if (stateJson != null) {
                WidgetStateStore.deserialize(stateJson)
            } else {
                SonosWidgetState() // Default empty state on first render
            }

            // Artwork is a separately refreshed enrichment. Do not expose the
            // previous track's file while the new track's art is still loading.
            val albumArt: Bitmap? = if (state.artworkVersion != null) {
                AlbumArtLoader.loadFromDisk(context)
            } else null
            val background: Bitmap? = if (state.artworkVersion != null) {
                WidgetBackgroundRenderer.loadFromDisk(context)
            } else null

            val size = LocalSize.current
            when (WidgetLayoutPolicy.bucketFor(size.width.value.toInt(), size.height.value.toInt())) {
                WidgetLayoutPolicy.Bucket.EXPANDED -> ExpandedLayout(state, albumArt, background)
                WidgetLayoutPolicy.Bucket.COMPACT -> CompactLayout(state, albumArt, background)
                WidgetLayoutPolicy.Bucket.MINI -> MiniLayout(state, albumArt, background)
            }
        }
    }
}
