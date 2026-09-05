package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sycamorecreek.sonoswidget.R

/**
 * Half-size (4x2) immersive widget layout.
 *
 * Same "glass on art" design system as [ExpandedLayout], condensed:
 * album art left, track info + progress + transport right. Group editing is
 * intentionally kept out of this bounded layout; it belongs to the expanded
 * "Play in…" surface where Apply/Cancel can be represented safely.
 *
 * Colors come from [WidgetTheme] — white-alpha glass over the blurred
 * album-art background so the widget stays legible on any artwork.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun CompactLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null,
    background: Bitmap? = null
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val isSwitchingRoom = state.pendingOperations.any { it.type == WidgetOperationType.SWITCHING_ROOM }
    val controlsDisabled = isDisconnected || state.isOffline || state.isRateLimited || state.isUpdating || isSwitchingRoom
    val hasTrack = state.currentTrack.name.isNotBlank()
    val palette = state.colorPalette

    ImmersiveSurface(background = background, palette = palette) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            // ── Inline error banner (auto-dismissed after 5s by service) ──
            if (state.errorMessage != null) {
                InlineErrorBanner(state.errorMessage, actionRunCallback<RefreshStatusAction>())
                Spacer(modifier = GlanceModifier.height(6.dp))
            }

            // ── Main content row: art + track info + controls ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtWithBadge(
                    albumArt = albumArt,
                    state = state,
                    size = 86.dp,
                    hasTrack = hasTrack
                )

                Spacer(modifier = GlanceModifier.width(12.dp))

                Column(modifier = GlanceModifier.defaultWeight()) {
                    // Track name
                    Text(
                        // While reconnecting, keep showing the last-known track —
                        // the subtitle and art badge carry the status signal.
                        text = when {
                            state.isOffline && !hasTrack -> "Offline"
                            isDisconnected && !hasTrack -> "Searching for speakers…"
                            state.currentSource == "TV" -> "TV Audio"
                            hasTrack -> state.currentTrack.name
                            else -> "Nothing playing"
                        },
                        style = TextStyle(
                            color = ColorProvider(WidgetTheme.TextPrimary),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.height(2.dp))

                    // Artist / subtitle
                    Text(
                        text = when {
                            state.isOffline -> "No internet and no local network"
                            state.isUpdating -> "Speaker is updating firmware…"
                            isSwitchingRoom -> "Switching room…"
                            state.pendingOperations.any { it.type == WidgetOperationType.LOADING_FAVORITE } -> "Preparing favorite…"
                            state.isRateLimited -> "Cloud API rate limited — retrying…"
                            isDisconnected && state.isReconnecting -> "Reconnecting…"
                            isDisconnected -> "Check Wi-Fi connection"
                            state.currentTrack.artist.isNotBlank() -> state.currentTrack.artist
                            else -> "Tap play to start"
                        },
                        style = TextStyle(
                            color = ColorProvider(WidgetTheme.TextSecondary),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        maxLines = 1
                    )

                    // ── Static progress bar ──
                    if (hasTrack && !controlsDisabled && state.capabilities.canSeek && state.currentTrack.durationMs > 0L) {
                        Spacer(modifier = GlanceModifier.height(6.dp))
                        StaticProgressBar(
                            elapsedMs = state.currentTrack.elapsedMs,
                            durationMs = state.currentTrack.durationMs,
                            barHeight = 3.dp,
                            showTimeLabels = false
                        )
                    }

                    Spacer(modifier = GlanceModifier.height(4.dp))

                    // Transport controls row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GlassIconButton(
                            resId = R.drawable.ic_skip_previous,
                            contentDescription = "Previous track",
                            action = actionRunCallback<PreviousTrackAction>(),
                            enabled = !controlsDisabled && state.capabilities.canPrevious,
                            boxSize = 40.dp,
                            iconSize = 22.dp
                        )

                        Spacer(modifier = GlanceModifier.width(6.dp))

                        PlayPauseButton(
                            isPlaying = state.playbackState == PlaybackState.PLAYING,
                            enabled = !controlsDisabled && state.capabilities.canPlayPause,
                            action = actionRunCallback<PlayPauseAction>(),
                            size = 40.dp,
                            iconSize = 20.dp
                        )

                        Spacer(modifier = GlanceModifier.width(6.dp))

                        GlassIconButton(
                            resId = R.drawable.ic_skip_next,
                            contentDescription = "Next track",
                            action = actionRunCallback<NextTrackAction>(),
                            enabled = !controlsDisabled && state.capabilities.canNext,
                            boxSize = 40.dp,
                            iconSize = 22.dp
                        )

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        // Zone / volume indicator
                        if (!controlsDisabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val modeIcon = connectionModeIconRes(state.connectionMode)
                                if (modeIcon != null) {
                                    ThemedIcon(
                                        resId = modeIcon,
                                        size = 12.dp,
                                        tint = WidgetTheme.TextTertiary
                                    )
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                }
                                Text(
                                    text = buildZoneLabel(state),
                                    style = TextStyle(
                                        color = ColorProvider(WidgetTheme.TextTertiary),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // ── Permission hint (one-time, shown when local network denied) ──
            if (state.showPermissionHint) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                PermissionHintBanner()
            }
        }
    }
}

/**
 * Builds the zone + volume label: "Living Room · 45%"
 */
private fun buildZoneLabel(state: SonosWidgetState): String {
    val zoneName = state.activeZone.displayName.ifBlank { "Sonos" }
    return "$zoneName · ${state.volume}%"
}
