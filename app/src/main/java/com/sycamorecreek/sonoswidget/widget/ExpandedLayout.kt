package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.action.actionParametersOf
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
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sycamorecreek.sonoswidget.R

/**
 * Full-size (5x5+) immersive widget layout.
 *
 * Design system: "glass on art" — the blurred album-art background renders
 * behind white-alpha glass surfaces. Visual hierarchy, top to bottom:
 *
 * 1. Header: room pill (tap to switch rooms) + connection indicator
 * 2. Hero: large album art + track title / artist / album
 * 3. Progress bar with seek nudges and time labels
 * 4. Transport: shuffle · previous · play (white circle) · next · repeat
 * 5. Volume: slim glass pill + mute
 * 6. Quiet strips: speakers, favorites (collapsible)
 * 7. Up Next queue — flexes to fill whatever height remains
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun ExpandedLayout(
    state: SonosWidgetState = SonosWidgetState(),
    albumArt: Bitmap? = null,
    background: Bitmap? = null
) {
    val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
    val showRoomSelector = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("show_room_selector")] ?: false

    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED
    val isSwitchingRoom = state.pendingOperations.any { it.type == WidgetOperationType.SWITCHING_ROOM }
    val controlsDisabled = isDisconnected || state.isOffline || state.isRateLimited || state.isUpdating || isSwitchingRoom
    val hasTrack = state.currentTrack.name.isNotBlank()
    val palette = state.colorPalette
    val accent = WidgetTheme.accent(palette)
    // Keep one bounded secondary surface. Queue is most useful while music is
    // active; favorites are the useful idle affordance when playback stops.
    val showFavoritesAsSecondary = state.playbackState != PlaybackState.PLAYING &&
        state.favorites.isNotEmpty() && state.capabilities.canPlayFavorites

    ImmersiveSurface(background = background, palette = palette) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // ── Group 1: banner + header + room selector ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (state.errorMessage != null) {
                    InlineErrorBanner(state.errorMessage, actionRunCallback<RefreshStatusAction>())
                    Spacer(modifier = GlanceModifier.height(8.dp))
                }
                HeaderRow(state, controlsDisabled)
                if (showRoomSelector) {
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    RoomSelectorPanel(state)
                }
            }

            Spacer(modifier = GlanceModifier.height(12.dp))

            // ── Group 2: hero (art + track info) + progress ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                NowPlayingHero(state, albumArt, controlsDisabled, hasTrack)

                if (hasTrack && !controlsDisabled && state.capabilities.canSeek && state.currentTrack.durationMs > 0L) {
                    Spacer(modifier = GlanceModifier.height(12.dp))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeekNudgeButton(
                            label = "-15",
                            desc = "Rewind 15 seconds",
                            callback = actionRunCallback<SeekBackAction>()
                        )
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        Box(modifier = GlanceModifier.defaultWeight()) {
                            StaticProgressBar(
                                elapsedMs = state.currentTrack.elapsedMs,
                                durationMs = state.currentTrack.durationMs,
                                barHeight = 5.dp,
                                showTimeLabels = true
                            )
                        }
                        Spacer(modifier = GlanceModifier.width(8.dp))
                        SeekNudgeButton(
                            label = "+15",
                            desc = "Forward 15 seconds",
                            callback = actionRunCallback<SeekForwardAction>()
                        )
                    }
                }
            }

            Spacer(modifier = GlanceModifier.height(10.dp))

            // ── Group 3: transport + volume ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                TransportControlsBar(state, controlsDisabled, accent)
                Spacer(modifier = GlanceModifier.height(10.dp))
                VolumeRow(state, controlsDisabled)
            }

            Spacer(modifier = GlanceModifier.height(12.dp))

            // ── Group 4: one bounded secondary section ──
            Column(modifier = GlanceModifier.fillMaxWidth()) {
                if (showFavoritesAsSecondary) {
                    FavoritesSection(state, controlsDisabled)
                } else {
                    SectionHeader(
                        title = if (state.queue.isEmpty()) "Up Next" else "Up Next · ${state.queue.size}"
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    if (state.queue.isEmpty()) {
                        Text(
                            text = "Queue is empty",
                            style = TextStyle(
                                color = ColorProvider(WidgetTheme.TextTertiary),
                                fontSize = 12.sp
                            )
                        )
                    } else {
                        // A bounded list prevents secondary content from pushing
                        // transport or volume outside the offered widget size.
                        state.queue.take(3).forEach { item -> QueueItemRow(item) }
                    }
                }
            }

            // ── Permission hint (one-time) ──
            if (state.showPermissionHint) {
                Spacer(modifier = GlanceModifier.height(6.dp))
                PermissionHintBanner()
            }
        }
    }
}

// ──────────────────────────────────────────────
// Header: room pill + connection indicator
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun HeaderRow(
    state: SonosWidgetState,
    controlsDisabled: Boolean
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!controlsDisabled && state.activeZone.displayName.isNotBlank()) {
            GlassChip(
                text = state.activeZone.displayName.uppercase(),
                contentDescription = "Room: ${state.activeZone.displayName}. Tap to switch rooms",
                action = actionRunCallback<ToggleRoomSelectorAction>(),
                leadingIcon = R.drawable.ic_speaker_device,
                textColor = WidgetTheme.TextSecondary,
                fontSize = 10,
                horizontalPadding = 10.dp,
                verticalPadding = 6.dp
            )
        } else {
            Text(
                text = when {
                    state.isOffline -> "OFFLINE"
                    else -> "SONOS"
                },
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextTertiary),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.defaultWeight())

        val modeIcon = connectionModeIconRes(state.connectionMode)
        if (!controlsDisabled && modeIcon != null) {
            ThemedIcon(
                resId = modeIcon,
                size = 14.dp,
                tint = WidgetTheme.TextTertiary,
                contentDescription = if (state.connectionMode == ConnectionMode.CLOUD)
                    "Connected via cloud" else "Connected via manual IP"
            )
        }
    }
}

// ──────────────────────────────────────────────
// Now Playing hero
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun NowPlayingHero(
    state: SonosWidgetState,
    albumArt: Bitmap?,
    controlsDisabled: Boolean,
    hasTrack: Boolean
) {
    val isDisconnected = state.connectionMode == ConnectionMode.DISCONNECTED

    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumArtWithBadge(
            albumArt = albumArt,
            state = state,
            size = 128.dp,
            hasTrack = hasTrack
        )

        Spacer(modifier = GlanceModifier.width(16.dp))

        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                // While reconnecting, keep showing the last-known track — the
                // subtitle and art badge carry the "Reconnecting…" signal.
                text = when {
                    state.isOffline && !hasTrack -> "Offline"
                    isDisconnected && !hasTrack -> "Searching for speakers…"
                    state.currentSource == "TV" -> "TV Audio"
                    hasTrack -> state.currentTrack.name
                    else -> "Nothing playing"
                },
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextPrimary),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 2
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = when {
                    state.isOffline -> "No internet and no local network"
                    state.isUpdating -> "Speaker is updating firmware…"
                    state.isRateLimited -> "Cloud API rate limited — retrying…"
                    isDisconnected && state.isReconnecting -> "Reconnecting…"
                    isDisconnected -> "Check Wi-Fi connection"
                    state.currentTrack.artist.isNotBlank() -> state.currentTrack.artist
                    else -> "Tap play to start"
                },
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextSecondary),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                ),
                maxLines = 1
            )

            if (hasTrack && state.currentTrack.album.isNotBlank() && !controlsDisabled) {
                Spacer(modifier = GlanceModifier.height(3.dp))
                Text(
                    text = state.currentTrack.album,
                    style = TextStyle(
                        color = ColorProvider(WidgetTheme.TextTertiary),
                        fontSize = 12.sp
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

// ──────────────────────────────────────────────
// Transport controls
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun TransportControlsBar(
    state: SonosWidgetState,
    controlsDisabled: Boolean,
    accent: androidx.compose.ui.graphics.Color
) {
    val shuffleLabel = if (state.shuffleEnabled) "Shuffle on, double tap to turn off"
        else "Shuffle off, double tap to turn on"
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
        GlassIconButton(
            resId = R.drawable.ic_shuffle,
            contentDescription = shuffleLabel,
            action = actionRunCallback<ToggleShuffleAction>(),
            enabled = !controlsDisabled && state.capabilities.canShuffle,
            boxSize = 44.dp,
            iconSize = 20.dp,
            tint = if (state.shuffleEnabled) accent else WidgetTheme.TextTertiary
        )

        Spacer(modifier = GlanceModifier.defaultWeight())

        GlassIconButton(
            resId = R.drawable.ic_skip_previous,
            contentDescription = "Previous track",
            action = actionRunCallback<PreviousTrackAction>(),
            enabled = !controlsDisabled && state.capabilities.canPrevious,
            boxSize = 48.dp,
            iconSize = 28.dp
        )

        Spacer(modifier = GlanceModifier.width(18.dp))

        PlayPauseButton(
            isPlaying = state.playbackState == PlaybackState.PLAYING,
            enabled = !controlsDisabled && state.capabilities.canPlayPause,
            action = actionRunCallback<PlayPauseAction>(),
            size = 60.dp,
            iconSize = 30.dp
        )

        Spacer(modifier = GlanceModifier.width(18.dp))

        GlassIconButton(
            resId = R.drawable.ic_skip_next,
            contentDescription = "Next track",
            action = actionRunCallback<NextTrackAction>(),
            enabled = !controlsDisabled && state.capabilities.canNext,
            boxSize = 48.dp,
            iconSize = 28.dp
        )

        Spacer(modifier = GlanceModifier.defaultWeight())

        GlassIconButton(
            resId = if (state.repeatMode == RepeatMode.ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat,
            contentDescription = repeatLabel,
            action = actionRunCallback<CycleRepeatAction>(),
            enabled = !controlsDisabled && state.capabilities.canRepeat,
            boxSize = 44.dp,
            iconSize = 20.dp,
            tint = if (state.repeatMode != RepeatMode.NONE) accent else WidgetTheme.TextTertiary
        )
    }
}

// ──────────────────────────────────────────────
// Volume pill
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun VolumeRow(
    state: SonosWidgetState,
    controlsDisabled: Boolean
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Slim glass pill: − 45% +
        Row(
            modifier = GlanceModifier
                .cornerRadius(20.dp)
                .background(WidgetTheme.Glass)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GlassIconButton(
                resId = R.drawable.ic_volume_down,
                contentDescription = "Decrease volume",
                action = actionRunCallback<VolumeDownAction>(),
                enabled = !controlsDisabled && state.capabilities.canChangeVolume,
                boxSize = 40.dp,
                iconSize = 18.dp,
                tint = WidgetTheme.TextSecondary
            )
            Text(
                text = "${state.volume}%",
                style = TextStyle(
                    color = ColorProvider(
                        if (controlsDisabled) WidgetTheme.Disabled else WidgetTheme.TextPrimary
                    ),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                ),
                modifier = GlanceModifier
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = "Volume: ${state.volume} percent" }
            )
            GlassIconButton(
                resId = R.drawable.ic_volume_up,
                contentDescription = "Increase volume",
                action = actionRunCallback<VolumeUpAction>(),
                enabled = !controlsDisabled && state.capabilities.canChangeVolume,
                boxSize = 40.dp,
                iconSize = 18.dp,
                tint = WidgetTheme.TextSecondary
            )
        }

        Spacer(modifier = GlanceModifier.width(8.dp))

        GlassIconButton(
            resId = if (state.volumeMuted) R.drawable.ic_volume_off else R.drawable.ic_volume_up,
            contentDescription = if (state.volumeMuted) "Unmute" else "Mute",
            action = actionRunCallback<ToggleMuteAction>(),
            enabled = !controlsDisabled && state.capabilities.canMute,
            boxSize = 40.dp,
            iconSize = 18.dp,
            tint = if (state.volumeMuted) WidgetTheme.TextPrimary else WidgetTheme.TextTertiary,
            glass = true
        )

        Spacer(modifier = GlanceModifier.defaultWeight())
    }
}

// ──────────────────────────────────────────────
// Seek nudge (-15 / +15)
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun SeekNudgeButton(
    label: String,
    desc: String,
    callback: androidx.glance.action.Action
) {
    Box(
        modifier = GlanceModifier
            .width(40.dp)
            .height(32.dp)
            .semantics { contentDescription = desc }
            .clickable(callback),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextTertiary),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

// ──────────────────────────────────────────────
// Speaker grouping strip
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun SpeakerGroupingSection(
    state: SonosWidgetState,
    controlsDisabled: Boolean,
    palette: WidgetColorPalette
) {
    Column(modifier = GlanceModifier.fillMaxWidth()) {
        val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
        val showSpeakers = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("show_speakers")] ?: true

        SectionHeader(
            title = "Speakers",
            expanded = showSpeakers,
            action = actionRunCallback<ToggleSpeakersAction>()
        )

        if (showSpeakers) {
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
                        color = ColorProvider(WidgetTheme.TextTertiary),
                        fontSize = 12.sp
                    )
                )
            } else {
                val activeGroupId = state.activeZone.groupId
                val activeGroupMembers = allZones.filter { it.groupId == activeGroupId }
                val otherCoordinators = allZones.filter {
                    it.groupId != activeGroupId && it.isGroupCoordinator
                }
                val allDisplayZones = activeGroupMembers + otherCoordinators
                val visibleZones = allDisplayZones.take(8)

                val items = mutableListOf<@androidx.compose.runtime.Composable () -> Unit>()

                visibleZones.forEach { zone ->
                    val isGrouped = zone.groupId == activeGroupId
                    val isSpeakerOffline = zone.id in state.offlineSpeakerIds
                    items.add {
                        SpeakerChip(zone, isGrouped, isSpeakerOffline, palette)
                    }
                }

                if (otherCoordinators.isNotEmpty()) {
                    items.add {
                        GlassChip(
                            text = "Group all",
                            contentDescription = "Group all speakers",
                            action = actionRunCallback<GroupAllAction>(),
                            textColor = WidgetTheme.TextTertiary
                        )
                    }
                }

                val rowSize = 3
                val chunks = items.chunked(rowSize)

                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    chunks.forEachIndexed { rowIndex, chunk ->
                        if (rowIndex > 0) {
                            Spacer(modifier = GlanceModifier.height(6.dp))
                        }
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            chunk.forEachIndexed { index, itemComposable ->
                                if (index > 0) {
                                    Spacer(modifier = GlanceModifier.width(6.dp))
                                }
                                itemComposable()
                            }

                            if (rowIndex == chunks.lastIndex && allDisplayZones.size > 8) {
                                Spacer(modifier = GlanceModifier.width(4.dp))
                                Text(
                                    text = "+${allDisplayZones.size - 8}",
                                    style = TextStyle(
                                        color = ColorProvider(WidgetTheme.TextTertiary),
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * A speaker chip that toggles group membership when tapped.
 * Grouped speakers show an accent-tinted glass fill with a check mark;
 * ungrouped are plain glass. Offline speakers are dimmed and inert.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun SpeakerChip(
    zone: Zone,
    isGrouped: Boolean,
    isSpeakerOffline: Boolean,
    palette: WidgetColorPalette
) {
    val chipLabel = when {
        isSpeakerOffline -> "${zone.displayName}, offline"
        isGrouped -> "${zone.displayName}, grouped"
        else -> "${zone.displayName}, ungrouped"
    }
    GlassChip(
        text = zone.displayName.take(14),
        contentDescription = chipLabel,
        action = if (isSpeakerOffline) null else actionRunCallback<ToggleGroupAction>(
            actionParametersOf(SPEAKER_UUID_KEY to zone.id)
        ),
        background = if (isGrouped && !isSpeakerOffline) WidgetTheme.accentGlass(palette)
            else WidgetTheme.Glass,
        textColor = when {
            isSpeakerOffline -> WidgetTheme.Disabled
            isGrouped -> WidgetTheme.TextPrimary
            else -> WidgetTheme.TextSecondary
        },
        leadingIcon = if (isGrouped && !isSpeakerOffline) R.drawable.ic_check else null,
        iconTint = WidgetTheme.TextPrimary,
        bold = isGrouped && !isSpeakerOffline
    )
}

// ──────────────────────────────────────────────
// Favorites strip
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun FavoritesSection(
    state: SonosWidgetState,
    controlsDisabled: Boolean
) {
    if (state.favorites.isEmpty()) return

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        val prefs = androidx.glance.currentState<androidx.datastore.preferences.core.Preferences>()
        val showFavorites = prefs[androidx.datastore.preferences.core.booleanPreferencesKey("show_favorites")] ?: true

        SectionHeader(
            title = "Favorites",
            expanded = showFavorites,
            action = actionRunCallback<ToggleFavoritesAction>()
        )

        if (showFavorites) {
            Spacer(modifier = GlanceModifier.height(6.dp))

            val rowSize = 3
            val visibleFavorites = state.favorites.take(6)
            val chunks = visibleFavorites.chunked(rowSize)

            chunks.forEachIndexed { rowIndex, chunk ->
                if (rowIndex > 0) {
                    Spacer(modifier = GlanceModifier.height(6.dp))
                }
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    chunk.forEachIndexed { index, fav ->
                        if (index > 0) Spacer(modifier = GlanceModifier.width(6.dp))
                        Box(
                            modifier = GlanceModifier
                                .defaultWeight()
                                .cornerRadius(12.dp)
                                .background(WidgetTheme.Glass)
                                .padding(horizontal = 10.dp, vertical = 9.dp)
                                .semantics { contentDescription = "Play ${fav.title}" }
                                .let { mod ->
                                    if (controlsDisabled) mod
                                    else mod.clickable(
                                        actionRunCallback<PlayFavoriteAction>(
                                            actionParametersOf(FAVORITE_ID_KEY to fav.id)
                                        )
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = fav.title,
                                style = TextStyle(
                                    color = ColorProvider(
                                        if (controlsDisabled) WidgetTheme.Disabled
                                        else WidgetTheme.TextSecondary
                                    ),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                ),
                                maxLines = 1
                            )
                        }
                    }

                    // Add empty spacers to align columns if the last row is incomplete
                    if (chunk.size < rowSize) {
                        val remaining = rowSize - chunk.size
                        for (i in 0 until remaining) {
                            Spacer(modifier = GlanceModifier.width(6.dp))
                            Box(modifier = GlanceModifier.defaultWeight()) {}
                        }
                    }
                }
            }
        }
    }
}

// ──────────────────────────────────────────────
// Queue
// ──────────────────────────────────────────────

/**
 * A single tappable queue item row.
 * Tapping jumps playback to that track via [JumpToQueueItemAction].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
private fun QueueItemRow(item: QueueItem) {
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
                color = ColorProvider(WidgetTheme.TextSecondary),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight()
        )

        if (item.artist.isNotBlank()) {
            Spacer(modifier = GlanceModifier.width(8.dp))
            Text(
                text = item.artist,
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextTertiary),
                    fontSize = 11.sp
                ),
                maxLines = 1
            )
        }
    }
}

// ──────────────────────────────────────────────
// Room selector
// ──────────────────────────────────────────────

@GlanceComposable
@androidx.compose.runtime.Composable
private fun RoomSelectorPanel(state: SonosWidgetState) {
    val allZones = state.zones
    val otherZones = allZones.filter { it.id != state.activeZone.id }

    if (otherZones.isEmpty()) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .cornerRadius(12.dp)
                .background(WidgetTheme.Glass)
                .padding(10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No other rooms found",
                style = TextStyle(
                    color = ColorProvider(WidgetTheme.TextTertiary),
                    fontSize = 12.sp
                )
            )
        }
        return
    }

    Column(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(14.dp)
            .background(WidgetTheme.Glass)
            .padding(8.dp)
    ) {
        Text(
            text = "SWITCH ROOM",
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextTertiary),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = GlanceModifier.height(6.dp))

        val rowSize = 3
        val chunks = otherZones.chunked(rowSize)
        chunks.forEachIndexed { rowIndex, chunk ->
            if (rowIndex > 0) {
                Spacer(modifier = GlanceModifier.height(6.dp))
            }
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                chunk.forEachIndexed { index, zone ->
                    if (index > 0) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                    }
                    Box(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .cornerRadius(10.dp)
                            .background(WidgetTheme.GlassStrong)
                            .padding(horizontal = 8.dp, vertical = 8.dp)
                            .semantics { contentDescription = "Switch to ${zone.displayName}" }
                            .clickable(
                                actionRunCallback<SwitchZoneAction>(
                                    actionParametersOf(ZONE_ID_KEY to zone.id)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = zone.displayName.take(12),
                            style = TextStyle(
                                color = ColorProvider(WidgetTheme.TextPrimary),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            ),
                            maxLines = 1
                        )
                    }
                }

                if (chunk.size < rowSize) {
                    val remaining = rowSize - chunk.size
                    for (i in 0 until remaining) {
                        Spacer(modifier = GlanceModifier.width(6.dp))
                        Box(modifier = GlanceModifier.defaultWeight()) {}
                    }
                }
            }
        }
    }
}
