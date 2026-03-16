package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.action.actionParametersOf

/**
 * Half-screen (4x2) compact widget layout.
 *
 * Displays current track info, album art, transport controls, zone selector,
 * and volume indicator. Reads from [SonosWidgetState] pushed by PlaybackService
 * via WidgetStateStore. Album art bitmap loaded from disk by SonosWidget.provideGlance().
 *
 * Layout (single zone):
 * ┌──────────────────────────────────┐
 * │ ┌──────┐  Track Name             │
 * │ │ Art  │  Artist                  │
 * │ │      │  ⏮  ▶/⏸  ⏭             │
 * │ └──────┘  Zone · Vol%            │
 * └──────────────────────────────────┘
 *
 * Layout (multiple zones — grouping mode):
 * ┌──────────────────────────────────────┐
 * │ ┌──────┐  Track Name                 │
 * │ │ Art  │  Artist                      │
 * │ │      │  ⏮  ▶/⏸  ⏭   Vol%         │
 * │ └──────┘  [Room1] [Room2] [Room3] All│
 * └──────────────────────────────────────┘
 *
 * Colors are driven by [WidgetColorPalette] extracted from album art
 * via ThemeExtractor. Falls back to static defaults when no art is available.
 *
 * Tapping album art opens the Sonos app via [OpenSonosAppAction].
 * All transport controls provide haptic feedback via [HapticHelper].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun CompactLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val hasTrack = state.currentTrack.name.isNotBlank()
    val palette = state.colorPalette

    // Parse palette hex colors to Compose Color values
    val bgColor = parseHexColor(palette.background, Color(0xFF1E1E2E))
    val textPrimary = parseHexColor(palette.textPrimary, Color.White)
    val textSecondary = parseHexColor(palette.textSecondary, Color(0xFFB0B0C0))
    val accentColor = parseHexColor(palette.accent, Color(0xFF6C63FF))
    val chipBg = parseHexColor(palette.chipBackground, Color(0xFF3E3E5E))
    val disabledColor = Color(0xFF555570)

    // Show zone chips when connected with 2+ zones
    // Include active group members + other coordinators for grouping
    val activeGroupId = state.activeZone.groupId
    val activeGroupMembers = state.zones.filter { it.groupId == activeGroupId }
    val otherCoordinators = state.zones.filter {
        it.groupId != activeGroupId && it.isGroupCoordinator
    }
    val allDisplayZones = activeGroupMembers + otherCoordinators
    val showZoneChips = !isDisconnected && allDisplayZones.size > 1

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            // ── Main content row: art + track info + controls ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Album art — tappable to open Sonos app
                Box(
                    modifier = GlanceModifier
                        .size(80.dp)
                        .cornerRadius(12.dp)
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
                                .size(80.dp)
                                .cornerRadius(12.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Text(
                            text = if (hasTrack) "\uD83C\uDFB5" else "\uD83D\uDD0A",
                            style = TextStyle(
                                fontSize = 28.sp,
                                textAlign = TextAlign.Center
                            )
                        )
                    }
                }

                Spacer(modifier = GlanceModifier.width(12.dp))

                // Track info and controls
                Column(
                    modifier = GlanceModifier.defaultWeight()
                ) {
                    // Track name
                    Text(
                        text = when {
                            isDisconnected -> "Searching for speakers\u2026"
                            hasTrack -> state.currentTrack.name
                            else -> "No music playing"
                        },
                        style = TextStyle(
                            color = ColorProvider(textPrimary),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.height(2.dp))

                    // Artist / subtitle
                    Text(
                        text = when {
                            isDisconnected && state.isReconnecting -> "Reconnecting\u2026"
                            isDisconnected -> "Check Wi-Fi connection"
                            state.currentTrack.artist.isNotBlank() -> state.currentTrack.artist
                            else -> "Tap play to start"
                        },
                        style = TextStyle(
                            color = ColorProvider(textSecondary),
                            fontSize = 13.sp
                        ),
                        maxLines = 1
                    )

                    Spacer(modifier = GlanceModifier.height(8.dp))

                    // Transport controls row
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Previous
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
                                    fontSize = 18.sp
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(16.dp))

                        // Play/Pause toggle
                        Box(
                            modifier = if (isDisconnected) GlanceModifier
                            else GlanceModifier.clickable(
                                actionRunCallback<PlayPauseAction>()
                            ),
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
                                    fontSize = 22.sp
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.width(16.dp))

                        // Next
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
                                    fontSize = 18.sp
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.defaultWeight())

                        // Volume indicator (always shown when connected)
                        if (!isDisconnected) {
                            val modeIcon = connectionModeIcon(state.connectionMode)
                            Text(
                                text = if (showZoneChips) {
                                    if (modeIcon.isNotEmpty()) "${state.volume}% $modeIcon"
                                    else "${state.volume}%"
                                } else {
                                    buildZoneLabel(state)
                                },
                                style = TextStyle(
                                    color = ColorProvider(textSecondary),
                                    fontSize = 11.sp
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            // ── Speaker grouping chip row (only with 2+ zones) ──
            if (showZoneChips) {
                Spacer(modifier = GlanceModifier.height(6.dp))

                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Show up to 4 grouping chips to fit the compact width
                    val visibleZones = allDisplayZones.take(4)
                    visibleZones.forEachIndexed { index, zone ->
                        if (index > 0) {
                            Spacer(modifier = GlanceModifier.width(6.dp))
                        }

                        val isGrouped = zone.groupId == activeGroupId
                        CompactGroupingChip(
                            zone = zone,
                            isGrouped = isGrouped,
                            groupedBg = accentColor,
                            ungroupedBg = chipBg,
                            groupedText = textPrimary,
                            ungroupedText = textSecondary
                        )
                    }

                    // "All" chip when there are ungrouped speakers
                    if (otherCoordinators.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Box(
                            modifier = GlanceModifier
                                .cornerRadius(8.dp)
                                .background(chipBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .clickable(actionRunCallback<GroupAllAction>()),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "All",
                                style = TextStyle(
                                    color = ColorProvider(textSecondary),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    // Overflow indicator if more zones than can be displayed
                    if (allDisplayZones.size > 4) {
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        Text(
                            text = "+${allDisplayZones.size - 4}",
                            style = TextStyle(
                                color = ColorProvider(textSecondary),
                                fontSize = 10.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * A speaker grouping chip for the compact layout.
 *
 * Grouped speakers show accent background; ungrouped show chip background.
 * Tapping toggles group membership via [ToggleGroupAction].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun CompactGroupingChip(
    zone: Zone,
    isGrouped: Boolean,
    groupedBg: Color,
    ungroupedBg: Color,
    groupedText: Color,
    ungroupedText: Color
) {
    Box(
        modifier = GlanceModifier
            .cornerRadius(8.dp)
            .background(if (isGrouped) groupedBg else ungroupedBg)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable(
                actionRunCallback<ToggleGroupAction>(
                    actionParametersOf(SPEAKER_UUID_KEY to zone.id)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = zone.displayName.take(12),
            style = TextStyle(
                color = ColorProvider(if (isGrouped) groupedText else ungroupedText),
                fontSize = 11.sp,
                fontWeight = if (isGrouped) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

/**
 * Builds the zone + volume label with connection mode icon: "Living Room · 45% ☁"
 */
private fun buildZoneLabel(state: SonosWidgetState): String {
    val zoneName = state.activeZone.displayName.ifBlank { "Sonos" }
    val modeIcon = connectionModeIcon(state.connectionMode)
    return if (modeIcon.isNotEmpty()) {
        "$zoneName \u00B7 ${state.volume}% $modeIcon"
    } else {
        "$zoneName \u00B7 ${state.volume}%"
    }
}

/**
 * Returns a short icon string indicating the active connection mode.
 */
internal fun connectionModeIcon(mode: ConnectionMode): String {
    return when (mode) {
        ConnectionMode.LOCAL_SSDP, ConnectionMode.LOCAL_MDNS -> ""
        ConnectionMode.LOCAL_MANUAL_IP -> "\uD83D\uDD17" // link
        ConnectionMode.CLOUD -> "\u2601" // cloud
        ConnectionMode.DISCONNECTED -> ""
    }
}

/**
 * Parses a hex color string (#RRGGBB or #AARRGGBB) to a Compose Color.
 * Returns [fallback] on any parse error.
 */
internal fun parseHexColor(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}
