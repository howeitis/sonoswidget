package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Mini lock screen layout (3×1, ~240×80dp).
 *
 * Purpose-built hyper-compact layout per PRD Section 6.4:
 *
 * Normal (≥200dp width):
 * ┌────────────────────────────────────────┐
 * │ ┌────┐  Track Name     ⏮  ▶/⏸  ⏭  │
 * │ │Art │  Artist                         │
 * │ └────┘                                 │
 * └────────────────────────────────────────┘
 *
 * Extreme compression (<200dp width):
 * ┌──────────────────┐
 * │ ┌────┐   ▶/⏸   │
 * │ │Art │           │
 * │ └────┘           │
 * └──────────────────┘
 *
 * No volume slider, speaker grouping, queue, or source switcher.
 * Tapping album art opens the Sonos app.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun MiniLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val hasTrack = state.currentTrack.name.isNotBlank()
    val palette = state.colorPalette

    val bgColor = parseHexColor(palette.background, Color(0xFF1E1E2E))
    val textPrimary = parseHexColor(palette.textPrimary, Color.White)
    val textSecondary = parseHexColor(palette.textSecondary, Color(0xFFB0B0C0))
    val disabledColor = Color(0xFF555570)
    val chipBg = parseHexColor(palette.chipBackground, Color(0xFF3E3E5E))

    val size = LocalSize.current
    val extremeCompression = size.width < 200.dp

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Left zone: Album art (48dp) ──
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .cornerRadius(8.dp)
                    .background(chipBg)
                    .clickable(actionRunCallback<OpenSonosAppAction>()),
                contentAlignment = Alignment.Center
            ) {
                if (albumArt != null) {
                    Image(
                        provider = ImageProvider(albumArt),
                        contentDescription = state.currentTrack.album.ifBlank {
                            "Album art \u2014 tap to open Sonos"
                        },
                        modifier = GlanceModifier
                            .size(48.dp)
                            .cornerRadius(8.dp),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = if (hasTrack) "\uD83C\uDFB5" else "\uD83D\uDD0A",
                        style = TextStyle(
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }

            if (extremeCompression) {
                // ── Extreme compression: just play/pause ──
                Spacer(modifier = GlanceModifier.defaultWeight())

                MiniPlayPauseButton(
                    state = state,
                    isDisconnected = isDisconnected,
                    textPrimary = textPrimary,
                    disabledColor = disabledColor,
                    size = 40.dp
                )

                Spacer(modifier = GlanceModifier.width(4.dp))
            } else {
                Spacer(modifier = GlanceModifier.width(8.dp))

                // ── Center zone: Track + artist ──
                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    Text(
                        text = when {
                            isDisconnected -> "Searching\u2026"
                            hasTrack -> state.currentTrack.name
                            else -> "No music playing"
                        },
                        style = TextStyle(
                            color = ColorProvider(textPrimary),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )

                    if (state.currentTrack.artist.isNotBlank() && !isDisconnected) {
                        Spacer(modifier = GlanceModifier.height(1.dp))
                        Text(
                            text = state.currentTrack.artist,
                            style = TextStyle(
                                color = ColorProvider(textSecondary),
                                fontSize = 11.sp
                            ),
                            maxLines = 1
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(4.dp))

                // ── Right zone: Transport controls ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Skip back
                    Box(
                        modifier = if (isDisconnected) GlanceModifier
                        else GlanceModifier.clickable(
                            actionRunCallback<PreviousTrackAction>()
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "\u23EE",
                            style = TextStyle(
                                color = ColorProvider(
                                    if (isDisconnected) disabledColor else textPrimary
                                ),
                                fontSize = 14.sp
                            )
                        )
                    }

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Play/Pause (primary, 48dp target)
                    MiniPlayPauseButton(
                        state = state,
                        isDisconnected = isDisconnected,
                        textPrimary = textPrimary,
                        disabledColor = disabledColor,
                        size = 48.dp
                    )

                    Spacer(modifier = GlanceModifier.width(8.dp))

                    // Skip forward
                    Box(
                        modifier = if (isDisconnected) GlanceModifier
                        else GlanceModifier.clickable(
                            actionRunCallback<NextTrackAction>()
                        ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "\u23ED",
                            style = TextStyle(
                                color = ColorProvider(
                                    if (isDisconnected) disabledColor else textPrimary
                                ),
                                fontSize = 14.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Play/pause button used in the mini layout.
 * Sized as the primary touch target per Material Design (48dp minimum).
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun MiniPlayPauseButton(
    state: SonosWidgetState,
    isDisconnected: Boolean,
    textPrimary: Color,
    disabledColor: Color,
    size: androidx.compose.ui.unit.Dp
) {
    Box(
        modifier = GlanceModifier
            .size(size)
            .let {
                if (isDisconnected) it
                else it.clickable(actionRunCallback<PlayPauseAction>())
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = when (state.playbackState) {
                PlaybackState.PLAYING -> "\u23F8"
                else -> "\u25B6"
            },
            style = TextStyle(
                color = ColorProvider(
                    if (isDisconnected) disabledColor else textPrimary
                ),
                fontSize = 20.sp
            )
        )
    }
}
