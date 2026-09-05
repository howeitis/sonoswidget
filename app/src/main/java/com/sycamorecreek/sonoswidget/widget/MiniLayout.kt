package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sycamorecreek.sonoswidget.R

/**
 * Mini (~240x80dp) immersive widget layout for the lock screen and
 * hyper-compact grid placements.
 *
 * Single row: album art thumbnail, track title + room, play/pause and
 * next controls. Shares the "glass on art" design system with the larger
 * layouts via [ImmersiveSurface] and [WidgetTheme].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun MiniLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null,
    background: Bitmap? = null
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val isSwitchingRoom = state.pendingOperations.any { it.type == WidgetOperationType.SWITCHING_ROOM }
    val controlsDisabled = isDisconnected || state.isOffline || state.isRateLimited || state.isUpdating || isSwitchingRoom
    val hasTrack = state.currentTrack.name.isNotBlank()

    ImmersiveSurface(background = background, palette = state.colorPalette) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AlbumArtWithBadge(
                albumArt = albumArt,
                state = state,
                size = 56.dp,
                hasTrack = hasTrack
            )

            Spacer(modifier = GlanceModifier.width(10.dp))

            Column(modifier = GlanceModifier.defaultWeight()) {
                Text(
                    // While reconnecting, keep showing the last-known track —
                    // the art badge carries the status signal.
                    text = when {
                        state.isOffline && !hasTrack -> "Offline"
                        isDisconnected && !hasTrack -> "Searching…"
                        state.currentSource == "TV" -> "TV Audio"
                        hasTrack -> state.currentTrack.name
                        else -> "Nothing playing"
                    },
                    style = TextStyle(
                        color = ColorProvider(WidgetTheme.TextPrimary),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 1
                )
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = when {
                        isDisconnected && state.isReconnecting -> "Reconnecting…"
                        isSwitchingRoom -> "Switching room…"
                        isDisconnected -> "Check Wi-Fi connection"
                        state.activeZone.displayName.isNotBlank() -> state.activeZone.displayName
                        state.currentTrack.artist.isNotBlank() -> state.currentTrack.artist
                        else -> "Sonos"
                    },
                    style = TextStyle(
                        color = ColorProvider(WidgetTheme.TextSecondary),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }

            Spacer(modifier = GlanceModifier.width(8.dp))

            PlayPauseButton(
                isPlaying = state.playbackState == PlaybackState.PLAYING,
                enabled = !controlsDisabled && state.capabilities.canPlayPause,
                action = actionRunCallback<PlayPauseAction>(),
                size = 40.dp,
                iconSize = 20.dp
            )

            Spacer(modifier = GlanceModifier.width(4.dp))

            GlassIconButton(
                resId = R.drawable.ic_skip_next,
                contentDescription = "Next track",
                action = actionRunCallback<NextTrackAction>(),
                enabled = !controlsDisabled && state.capabilities.canNext,
                boxSize = 40.dp,
                iconSize = 22.dp
            )
        }
    }
}
