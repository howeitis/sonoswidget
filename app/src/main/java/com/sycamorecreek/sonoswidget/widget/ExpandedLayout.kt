package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.action.actionParametersOf

/**
 * Full-screen (4x5) expanded widget layout.
 *
 * Shows the complete now-playing area, transport controls, volume section,
 * speaker grouping panel, queue list, and source bar.
 *
 * Layout:
 * ┌──────────────────────────────┐
 * │  ┌──────┐ Track Name         │
 * │  │ Art  │ Artist              │
 * │  │120dp │                     │
 * │  └──────┘                     │
 * ├──────────────────────────────┤
 * │  🔀  ⏮  ▶/⏸  ⏭  🔁        │  Controls bar
 * ├──────────────────────────────┤
 * │  [ – ]  45%  [ + ]   Zone    │  Volume + zone
 * ├──────────────────────────────┤
 * │  Speakers                    │
 * │  [Room1] [Room2] [Group All] │  Grouping chips
 * ├──────────────────────────────┤
 * │  Up Next                     │
 * │  1. Track A - Artist A       │
 * │  2. Track B - Artist B       │  Queue list
 * ├──────────────────────────────┤
 * │  🎵 Source Name              │  Source bar
 * └──────────────────────────────┘
 *
 * Phase 2 placeholder: source switching (Task 3.1).
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun ExpandedLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val controlsDisabled = isDisconnected || state.isOffline || state.isRateLimited || state.isUpdating
    val hasTrack = state.currentTrack.name.isNotBlank()
    val palette = state.colorPalette

    val bgColor = parseHexColor(palette.background, Color(0xFF1E1E2E))
    val textPrimary = parseHexColor(palette.textPrimary, Color.White)
    val textSecondary = parseHexColor(palette.textSecondary, Color(0xFFB0B0C0))
    val accentColor = parseHexColor(palette.accent, Color(0xFF6C63FF))
    val chipBg = parseHexColor(palette.chipBackground, Color(0xFF3E3E5E))
    val disabledColor = Color(0xFF555570)
    val sectionLabelColor = Color(0xFF888898)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(bgColor)
            .padding(12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Glance Column has a 10-element limit. Group sections into logical
        // containers to stay well under the cap.
        Column(modifier = GlanceModifier.fillMaxWidth()) {

            // ── Inline error banner (auto-dismissed after 5s by service) ──
            if (state.errorMessage != null) {
                InlineErrorBanner(state.errorMessage)
                Spacer(modifier = GlanceModifier.height(6.dp))
            }

            // ── Group 1: Now Playing + progress bar ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                NowPlayingSection(
                    state = state,
                    albumArt = albumArt,
                    controlsDisabled = controlsDisabled,
                    hasTrack = hasTrack,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    chipBg = chipBg
                )

                if (hasTrack && !controlsDisabled && state.currentTrack.durationMs > 0L) {
                    Spacer(modifier = GlanceModifier.height(10.dp))
                    StaticProgressBar(
                        elapsedMs = state.currentTrack.elapsedMs,
                        durationMs = state.currentTrack.durationMs,
                        accentColor = accentColor,
                        trackColor = chipBg,
                        barHeight = 4.dp,
                        showTimeLabels = true,
                        timeLabelColor = textSecondary
                    )
                }
            }

            Spacer(modifier = GlanceModifier.height(8.dp))

            // ── Group 2: Transport controls + volume ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                TransportControlsBar(
                    state = state,
                    controlsDisabled = controlsDisabled,
                    textPrimary = textPrimary,
                    disabledColor = disabledColor,
                    accentColor = accentColor
                )

                Spacer(modifier = GlanceModifier.height(8.dp))

                VolumeSection(
                    state = state,
                    controlsDisabled = controlsDisabled,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    chipBg = chipBg,
                    accentColor = accentColor
                )
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            // ── Group 3: Speakers + queue + source ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                SpeakerGroupingSection(
                    state = state,
                    controlsDisabled = controlsDisabled,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    accentColor = accentColor,
                    chipBg = chipBg,
                    disabledColor = disabledColor,
                    sectionLabelColor = sectionLabelColor
                )

                Spacer(modifier = GlanceModifier.height(10.dp))

                QueueSection(
                    state = state,
                    textPrimary = textPrimary,
                    textSecondary = textSecondary,
                    sectionLabelColor = sectionLabelColor,
                    chipBg = chipBg
                )

                Spacer(modifier = GlanceModifier.height(8.dp))

                SourceBarSection(
                    state = state,
                    textSecondary = textSecondary,
                    chipBg = chipBg,
                    sectionLabelColor = sectionLabelColor
                )
            }

            // ── Permission hint (one-time) ──
            if (state.showPermissionHint) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                PermissionHintBanner(textSecondary, chipBg)
            }
        }
    }
}

// ──────────────────────────────────────────────
// Now Playing section
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun NowPlayingSection(
    state: SonosWidgetState,
    albumArt: Bitmap?,
    controlsDisabled: Boolean,
    hasTrack: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    chipBg: Color
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Album art with pill badge overlay (120dp in expanded layout)
        AlbumArtWithBadge(
            albumArt = albumArt,
            state = state,
            size = 120.dp,
            chipBg = chipBg,
            hasTrack = hasTrack
        )

        Spacer(modifier = GlanceModifier.width(12.dp))

        // Track info
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = when {
                    state.isOffline -> "Offline"
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

            Text(
                text = when {
                    state.isOffline -> "No internet and no local network"
                    state.isUpdating -> "Speaker is updating firmware\u2026"
                    state.isRateLimited -> "Cloud API rate limited \u2014 retrying\u2026"
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

            // Show album name in expanded layout
            if (hasTrack && state.currentTrack.album.isNotBlank() && !controlsDisabled) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = state.currentTrack.album,
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

// ──────────────────────────────────────────────
// Transport controls bar
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun TransportControlsBar(
    state: SonosWidgetState,
    controlsDisabled: Boolean,
    textPrimary: Color,
    disabledColor: Color,
    accentColor: Color
) {
    // Build accessible labels for stateful controls
    val shuffleLabel = if (state.shuffleEnabled) "Shuffle on, double tap to turn off"
        else "Shuffle off, double tap to turn on"
    val playPauseLabel = if (state.playbackState == PlaybackState.PLAYING)
        "Pause playback" else "Play"
    val repeatLabel = when (state.repeatMode) {
        RepeatMode.NONE -> "Repeat off, double tap to repeat all"
        RepeatMode.ALL -> "Repeat all, double tap to repeat one"
        RepeatMode.ONE -> "Repeat one, double tap to turn off"
    }

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Shuffle toggle (48dp touch target per PRD §7.1)
        Box(
            modifier = GlanceModifier.size(48.dp)
                .semantics { contentDescription = shuffleLabel }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<ToggleShuffleAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\uD83D\uDD00", // 🔀
                style = TextStyle(
                    color = ColorProvider(
                        when {
                            controlsDisabled -> disabledColor
                            state.shuffleEnabled -> accentColor
                            else -> textPrimary
                        }
                    ),
                    fontSize = 16.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Previous (48dp touch target)
        Box(
            modifier = GlanceModifier.size(48.dp)
                .semantics { contentDescription = "Previous track" }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<PreviousTrackAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u23EE",
                style = TextStyle(
                    color = ColorProvider(
                        if (controlsDisabled) disabledColor else textPrimary
                    ),
                    fontSize = 20.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.width(20.dp))

        // Play/Pause — primary control, larger (56dp touch target)
        Box(
            modifier = GlanceModifier.size(56.dp)
                .semantics { contentDescription = playPauseLabel }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<PlayPauseAction>())
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
                        if (controlsDisabled) disabledColor else textPrimary
                    ),
                    fontSize = 28.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.width(20.dp))

        // Next (48dp touch target)
        Box(
            modifier = GlanceModifier.size(48.dp)
                .semantics { contentDescription = "Next track" }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<NextTrackAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u23ED",
                style = TextStyle(
                    color = ColorProvider(
                        if (controlsDisabled) disabledColor else textPrimary
                    ),
                    fontSize = 20.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Repeat cycle (48dp touch target per PRD §7.1, upgraded from 40dp)
        Box(
            modifier = GlanceModifier.size(48.dp)
                .semantics { contentDescription = repeatLabel }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<CycleRepeatAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when (state.repeatMode) {
                    RepeatMode.ONE -> "\uD83D\uDD02" // 🔂
                    else -> "\uD83D\uDD01" // 🔁
                },
                style = TextStyle(
                    color = ColorProvider(
                        when {
                            controlsDisabled -> disabledColor
                            state.repeatMode != RepeatMode.NONE -> accentColor
                            else -> textPrimary
                        }
                    ),
                    fontSize = 16.sp
                )
            )
        }
    }
}

// ──────────────────────────────────────────────
// Volume + zone selector
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun VolumeSection(
    state: SonosWidgetState,
    controlsDisabled: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    chipBg: Color,
    accentColor: Color
) {
    val disabledVol = Color(0xFF555570)

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Volume down (48dp touch target)
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .cornerRadius(8.dp)
                .background(chipBg)
                .semantics { contentDescription = "Decrease volume" }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<VolumeDownAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u2013",
                style = TextStyle(
                    color = ColorProvider(
                        if (controlsDisabled) disabledVol else textPrimary
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Volume percentage — TalkBack reads "Volume: N%"
        Text(
            text = "${state.volume}%",
            style = TextStyle(
                color = ColorProvider(textPrimary),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            ),
            modifier = GlanceModifier.semantics {
                contentDescription = "Volume: ${state.volume} percent"
            }
        )

        Spacer(modifier = GlanceModifier.width(8.dp))

        // Volume up (48dp touch target)
        Box(
            modifier = GlanceModifier
                .size(48.dp)
                .cornerRadius(8.dp)
                .background(chipBg)
                .semantics { contentDescription = "Increase volume" }
                .let { mod ->
                    if (controlsDisabled) mod
                    else mod.clickable(actionRunCallback<VolumeUpAction>())
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "+",
                style = TextStyle(
                    color = ColorProvider(
                        if (controlsDisabled) disabledVol else textPrimary
                    ),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        // Active zone name + connection mode icon
        if (!controlsDisabled && state.activeZone.displayName.isNotBlank()) {
            val modeIcon = connectionModeIcon(state.connectionMode)
            Box(
                modifier = GlanceModifier
                    .cornerRadius(8.dp)
                    .background(chipBg)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (modeIcon.isNotEmpty()) {
                        "${state.activeZone.displayName} $modeIcon"
                    } else {
                        state.activeZone.displayName
                    },
                    style = TextStyle(
                        color = ColorProvider(textSecondary),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Speaker grouping panel (Task 2.2)
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun SpeakerGroupingSection(
    state: SonosWidgetState,
    controlsDisabled: Boolean,
    textPrimary: Color,
    textSecondary: Color,
    accentColor: Color,
    chipBg: Color,
    disabledColor: Color,
    sectionLabelColor: Color
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // Section label
        Text(
            text = "Speakers",
            style = TextStyle(
                color = ColorProvider(sectionLabelColor),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        val allZones = state.zones
        if (allZones.isEmpty() || controlsDisabled) {
            Text(
                text = when {
                    state.isOffline -> "Offline"
                    controlsDisabled -> "Unavailable"
                    else -> "No speakers found"
                },
                style = TextStyle(
                    color = ColorProvider(textSecondary),
                    fontSize = 12.sp
                )
            )
        } else {
            // Find which zones are grouped with the active zone
            val activeGroupId = state.activeZone.groupId

            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val activeGroupMembers = allZones.filter { it.groupId == activeGroupId }
                val otherCoordinators = allZones.filter {
                    it.groupId != activeGroupId && it.isGroupCoordinator
                }
                val displayZones = (activeGroupMembers + otherCoordinators).take(5)

                displayZones.forEachIndexed { index, zone ->
                    if (index > 0) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                    }
                    val isGrouped = zone.groupId == activeGroupId
                    val isSpeakerOffline = zone.id in state.offlineSpeakerIds
                    GroupingChip(
                        zone = zone,
                        isGrouped = isGrouped,
                        isSpeakerOffline = isSpeakerOffline,
                        groupedBg = accentColor,
                        ungroupedBg = chipBg,
                        groupedText = textPrimary,
                        ungroupedText = textSecondary,
                        offlineColor = disabledColor
                    )
                }

                // "Group All" chip
                if (otherCoordinators.isNotEmpty()) {
                    Spacer(modifier = GlanceModifier.width(6.dp))
                    GroupAllChip(
                        chipBg = chipBg,
                        textColor = textSecondary
                    )
                }

                // Overflow indicator
                val totalAvailable = activeGroupMembers.size + otherCoordinators.size
                if (totalAvailable > 5) {
                    Spacer(modifier = GlanceModifier.width(4.dp))
                    Text(
                        text = "+${totalAvailable - 5}",
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

/**
 * A speaker chip that toggles group membership when tapped.
 * Grouped speakers show accent background; ungrouped show dim background.
 * Offline speakers are grayed out and not tappable.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun GroupingChip(
    zone: Zone,
    isGrouped: Boolean,
    isSpeakerOffline: Boolean,
    groupedBg: Color,
    ungroupedBg: Color,
    groupedText: Color,
    ungroupedText: Color,
    offlineColor: Color
) {
    val bg = when {
        isSpeakerOffline -> ungroupedBg
        isGrouped -> groupedBg
        else -> ungroupedBg
    }
    val textColor = when {
        isSpeakerOffline -> offlineColor
        isGrouped -> groupedText
        else -> ungroupedText
    }
    // TalkBack: announce group state per PRD §14
    val chipLabel = when {
        isSpeakerOffline -> "${zone.displayName}, offline"
        isGrouped -> "${zone.displayName}, grouped"
        else -> "${zone.displayName}, ungrouped"
    }
    Box(
        modifier = GlanceModifier
            .cornerRadius(20.dp)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = chipLabel }
            .let { mod ->
                if (isSpeakerOffline) mod
                else mod.clickable(
                    actionRunCallback<ToggleGroupAction>(
                        actionParametersOf(SPEAKER_UUID_KEY to zone.id)
                    )
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = zone.displayName.take(14),
            style = TextStyle(
                color = ColorProvider(textColor),
                fontSize = 11.sp,
                fontWeight = if (isGrouped && !isSpeakerOffline) FontWeight.Bold else FontWeight.Normal
            ),
            maxLines = 1
        )
    }
}

/**
 * "Group All" chip that groups every discovered speaker.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun GroupAllChip(
    chipBg: Color,
    textColor: Color
) {
    Box(
        modifier = GlanceModifier
            .cornerRadius(20.dp)
            .background(chipBg)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .semantics { contentDescription = "Group all speakers" }
            .clickable(actionRunCallback<GroupAllAction>()),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Group All",
            style = TextStyle(
                color = ColorProvider(textColor),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1
        )
    }
}

// ──────────────────────────────────────────────
// Scrollable queue list (Task 2.3)
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun QueueSection(
    state: SonosWidgetState,
    textPrimary: Color,
    textSecondary: Color,
    sectionLabelColor: Color,
    chipBg: Color
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // Section label
        Text(
            text = "Up Next",
            style = TextStyle(
                color = ColorProvider(sectionLabelColor),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(modifier = GlanceModifier.height(6.dp))

        if (state.queue.isEmpty()) {
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(8.dp)
                    .background(chipBg)
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Queue is empty",
                    style = TextStyle(
                        color = ColorProvider(textSecondary),
                        fontSize = 12.sp
                    )
                )
            }
        } else {
            // Scrollable queue list via Glance LazyColumn
            LazyColumn(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .cornerRadius(8.dp)
                    .background(chipBg)
                    .padding(8.dp)
            ) {
                items(state.queue.take(20)) { item ->
                    QueueItemRow(
                        item = item,
                        textPrimary = textPrimary,
                        textSecondary = textSecondary
                    )
                }
            }
        }
    }
}

/**
 * A single tappable queue item row.
 * Tapping jumps playback to that track via [JumpToQueueItemAction].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun QueueItemRow(
    item: QueueItem,
    textPrimary: Color,
    textSecondary: Color
) {
    val queueItemLabel = if (item.artist.isNotBlank()) {
        "Play ${item.trackName} by ${item.artist}"
    } else {
        "Play ${item.trackName}"
    }
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = queueItemLabel }
            .clickable(
                actionRunCallback<JumpToQueueItemAction>(
                    actionParametersOf(QUEUE_TRACK_NR_KEY to item.position)
                )
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.trackName,
            style = TextStyle(
                color = ColorProvider(textPrimary),
                fontSize = 12.sp
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )

        if (item.artist.isNotBlank()) {
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = item.artist,
                style = TextStyle(
                    color = ColorProvider(textSecondary),
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
        }
    }
}

// ──────────────────────────────────────────────
// Source bar (placeholder — Task 3.1)
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun SourceBarSection(
    state: SonosWidgetState,
    textSecondary: Color,
    chipBg: Color,
    sectionLabelColor: Color
) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(8.dp)
            .background(chipBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "\uD83C\uDFB5",
                style = TextStyle(fontSize = 14.sp)
            )
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = if (state.currentSource.isNotBlank()) {
                    state.currentSource
                } else {
                    "Source"
                },
                style = TextStyle(
                    color = ColorProvider(textSecondary),
                    fontSize = 12.sp
                ),
                maxLines = 1
            )
        }
    }
}
