package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
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
    val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
    val showRoomSelector = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("show_room_selector")] ?: false
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val isSwitchingRoom = state.pendingOperations.any { it.type == WidgetOperationType.SWITCHING_ROOM }
    val isApplyingGrouping = state.pendingOperations.any { it.type == WidgetOperationType.APPLYING_GROUPING }
    val controlsDisabled = isDisconnected || state.isOffline || state.isRateLimited || state.isUpdating ||
        isSwitchingRoom || isApplyingGrouping
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

            if (showRoomSelector) {
                CompactRoomSelector(state)
            } else {
            // ── Main content row: art + track info + controls ──
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AlbumArtWithBadge(
                    albumArt = albumArt,
                    state = state,
                    size = 86.dp,
                    hasTrack = hasTrack,
                    // Compact has a dedicated title, subtitle, and recovery
                    // area; a second badge at this size hides the art/icon.
                    showStatusBadge = false
                )

                Spacer(modifier = GlanceModifier.width(12.dp))

                Column(modifier = GlanceModifier.defaultWeight()) {
                    // Track name
                    Text(
                        // While reconnecting, keep showing the last-known track —
                        // the title/subtitle area carries the status signal.
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
                            isApplyingGrouping -> "Applying speaker grouping…"
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

                        // Room selector / volume indicator
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
                                GlassChip(
                                    text = buildZoneLabel(state),
                                    contentDescription = "Room: ${state.activeZone.displayName}. Tap to switch rooms",
                                    action = actionRunCallback<ToggleRoomSelectorAction>(),
                                    textColor = WidgetTheme.TextTertiary,
                                    fontSize = 10,
                                    horizontalPadding = 7.dp,
                                    verticalPadding = 4.dp
                                )
                            }
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

/** Compact room navigation replaces player content, keeping this size bounded. */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun CompactRoomSelector(state: SonosWidgetState) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassChip(
                text = "Back",
                contentDescription = "Close room selector",
                action = actionRunCallback<ToggleRoomSelectorAction>(),
                textColor = WidgetTheme.TextSecondary,
                fontSize = 10,
                horizontalPadding = 9.dp,
                verticalPadding = 5.dp
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = "SWITCH ROOM",
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextTertiary),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(8.dp))
        val allOtherRooms = state.zones.filter { it.id != state.activeZone.id }
        val rooms = allOtherRooms.take(4)
        if (rooms.isEmpty()) {
            Text(
                text = "No other rooms found",
                style = TextStyle(color = ColorProvider(WidgetTheme.TextTertiary), fontSize = 12.sp)
            )
        } else {
            rooms.chunked(2).forEachIndexed { rowIndex, row ->
                if (rowIndex > 0) Spacer(modifier = GlanceModifier.height(6.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    row.forEachIndexed { index, room ->
                        if (index > 0) Spacer(modifier = GlanceModifier.width(6.dp))
                        GlassChip(
                            text = room.displayName.take(16),
                            contentDescription = "Switch to ${room.displayName}",
                            action = actionRunCallback<SwitchZoneAction>(
                                actionParametersOf(ZONE_ID_KEY to room.id)
                            ),
                            modifier = GlanceModifier.defaultWeight(),
                            background = WidgetTheme.GlassStrong,
                            textColor = WidgetTheme.TextPrimary,
                            fontSize = 11,
                            horizontalPadding = 8.dp,
                            verticalPadding = 9.dp
                        )
                    }
                    if (row.size == 1) Box(modifier = GlanceModifier.defaultWeight()) {}
                }
            }
            if (allOtherRooms.size > rooms.size) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                GlassChip(
                    text = "More rooms…",
                    contentDescription = "Open all rooms in the Sonos Widget companion",
                    action = actionRunCallback<OpenRoomChooserAction>(),
                    textColor = WidgetTheme.TextSecondary,
                    fontSize = 10,
                    horizontalPadding = 9.dp,
                    verticalPadding = 6.dp
                )
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
