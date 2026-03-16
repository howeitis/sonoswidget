package com.sycamorecreek.sonoswidget.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.sycamorecreek.sonoswidget.data.ActionDebouncer
import com.sycamorecreek.sonoswidget.data.SonosRepository
import com.sycamorecreek.sonoswidget.service.PlaybackService

/**
 * Glance ActionCallback handlers for widget transport controls.
 *
 * Each callback resolves the [SonosRepository] singleton and executes
 * the corresponding command. The repository handles the SOAP call,
 * immediate re-poll, and widget state push — so the widget updates
 * automatically after each action.
 *
 * **Debounce behavior (Task 3.4):** When the widget is in reconnecting
 * state, taps are queued via [ActionDebouncer] instead of being executed
 * immediately. The repository drains the queue and executes collapsed
 * net actions once the connection is restored. Taps older than 3 seconds
 * at drain time are silently discarded.
 *
 * All callbacks trigger haptic feedback via [HapticHelper] for tactile
 * confirmation of the user's tap.
 *
 * These callbacks are wired to clickable modifiers in CompactLayout.
 *
 * Usage in Glance composables:
 * ```
 * Box(modifier = GlanceModifier.clickable(actionRunCallback<PlayPauseAction>())) { ... }
 * ```
 */

private const val TAG = "WidgetActions"

/**
 * Ensures the foreground service is running before executing a speaker command.
 *
 * After idle teardown (PRD §12.1), the service may not be running when the
 * user taps a widget control. This re-launches it so polling resumes at
 * active-mode frequency. The [PlaybackService.start] call is idempotent
 * and also cancels the WorkManager periodic fallback.
 */
private fun ensureServiceRunning(context: Context) {
    PlaybackService.start(context)
}

/** Parameter key for passing a zone ID to [SwitchZoneAction]. */
val ZONE_ID_KEY = ActionParameters.Key<String>("zone_id")

// ──────────────────────────────────────────────
// Transport controls
// ──────────────────────────────────────────────

/**
 * Toggles play/pause based on current playback state.
 *
 * During reconnection, queued and debounced: odd taps = toggle, even = no-op.
 */
class PlayPauseAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "PlayPauseAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playConfirm(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.PLAY_PAUSE)
        } else {
            repo.togglePlayPause()
        }
    }
}

/**
 * Skips to the next track.
 *
 * During reconnection, queued and debounced: 3 taps → single skip-3.
 */
class NextTrackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "NextTrackAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playClick(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.NEXT)
        } else {
            repo.next()
        }
    }
}

/**
 * Skips to the previous track.
 *
 * During reconnection, queued and debounced: accumulates with Next.
 */
class PreviousTrackAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "PreviousTrackAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playClick(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.PREVIOUS)
        } else {
            repo.previous()
        }
    }
}

// ──────────────────────────────────────────────
// Shuffle & Repeat (Task 2.4)
// ──────────────────────────────────────────────

/**
 * Toggles shuffle mode on/off.
 *
 * During reconnection, debounced: odd taps = toggle, even = no-op.
 */
class ToggleShuffleAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "ToggleShuffleAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playClick(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.TOGGLE_SHUFFLE)
        } else {
            repo.toggleShuffle()
        }
    }
}

/**
 * Cycles repeat mode: NONE → ALL → ONE → NONE.
 *
 * During reconnection, debounced: cycles mod 3.
 */
class CycleRepeatAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "CycleRepeatAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playClick(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.CYCLE_REPEAT)
        } else {
            repo.cycleRepeatMode()
        }
    }
}

// ──────────────────────────────────────────────
// Volume controls
// ──────────────────────────────────────────────

/**
 * Volume up (+5).
 *
 * During reconnection, debounced: accumulates with VolumeDown.
 */
class VolumeUpAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "VolumeUpAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playRamp(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.VOLUME_UP)
        } else {
            val currentVol = repo.widgetState.value.volume
            repo.setVolume((currentVol + 5).coerceAtMost(100))
        }
    }
}

/**
 * Volume down (-5).
 *
 * During reconnection, debounced: accumulates with VolumeUp.
 */
class VolumeDownAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "VolumeDownAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playRamp(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.VOLUME_DOWN)
        } else {
            val currentVol = repo.widgetState.value.volume
            repo.setVolume((currentVol - 5).coerceAtLeast(0))
        }
    }
}

// ──────────────────────────────────────────────
// Zone selection
// ──────────────────────────────────────────────

/**
 * Switches the active zone to the speaker/group identified by the [ZONE_ID_KEY]
 * parameter.
 *
 * During reconnection, debounced: last zone wins.
 *
 * Usage in Glance composables:
 * ```
 * actionRunCallback<SwitchZoneAction>(
 *     actionParametersOf(ZONE_ID_KEY to zone.id)
 * )
 * ```
 */
class SwitchZoneAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val zoneId = parameters[ZONE_ID_KEY]
        if (zoneId == null) {
            Log.w(TAG, "SwitchZoneAction: missing zone_id parameter")
            return
        }
        Log.d(TAG, "SwitchZoneAction triggered for zone: $zoneId")
        ensureServiceRunning(context)
        HapticHelper.playConfirm(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.SWITCH_ZONE, zoneId)
        } else {
            repo.switchZone(zoneId)
        }
    }
}

// ──────────────────────────────────────────────
// Speaker grouping (Task 2.2)
// ──────────────────────────────────────────────

/** Parameter key for passing a speaker UUID to group/ungroup actions. */
val SPEAKER_UUID_KEY = ActionParameters.Key<String>("speaker_uuid")

/**
 * Toggles a speaker's membership in the active zone's group.
 *
 * During reconnection, debounced per speaker: odd taps = toggle, even = no-op.
 *
 * If the speaker is already grouped with the active zone, it is ungrouped.
 * Otherwise, it is added to the active zone's group.
 */
class ToggleGroupAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val speakerUuid = parameters[SPEAKER_UUID_KEY]
        if (speakerUuid == null) {
            Log.w(TAG, "ToggleGroupAction: missing speaker_uuid parameter")
            return
        }
        Log.d(TAG, "ToggleGroupAction triggered for speaker: $speakerUuid")
        ensureServiceRunning(context)
        HapticHelper.playConfirm(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.TOGGLE_GROUP, speakerUuid)
        } else {
            repo.toggleSpeakerGroup(speakerUuid)
        }
    }
}

/**
 * Groups all discovered speakers into the active zone's group.
 *
 * During reconnection, debounced: odd taps = execute, even = no-op.
 */
class GroupAllAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "GroupAllAction triggered")
        ensureServiceRunning(context)
        HapticHelper.playConfirm(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.GROUP_ALL)
        } else {
            repo.groupAll()
        }
    }
}

// ──────────────────────────────────────────────
// Queue navigation (Task 2.3)
// ──────────────────────────────────────────────

/** Parameter key for passing a 1-based track number to [JumpToQueueItemAction]. */
val QUEUE_TRACK_NR_KEY = ActionParameters.Key<Int>("queue_track_nr")

/**
 * Jumps playback to a specific track in the queue.
 *
 * During reconnection, debounced: last jump target wins (overrides skip delta).
 *
 * Usage in Glance composables:
 * ```
 * actionRunCallback<JumpToQueueItemAction>(
 *     actionParametersOf(QUEUE_TRACK_NR_KEY to item.position)
 * )
 * ```
 */
class JumpToQueueItemAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val trackNr = parameters[QUEUE_TRACK_NR_KEY]
        if (trackNr == null) {
            Log.w(TAG, "JumpToQueueItemAction: missing queue_track_nr parameter")
            return
        }
        Log.d(TAG, "JumpToQueueItemAction triggered for track #$trackNr")
        ensureServiceRunning(context)
        HapticHelper.playClick(context)
        val repo = SonosRepository.getInstance(context)
        if (repo.shouldDebounce()) {
            repo.enqueueAction(ActionDebouncer.ActionType.JUMP_TO_TRACK, trackNr.toString())
        } else {
            repo.playQueueItem(trackNr)
        }
    }
}

// ──────────────────────────────────────────────
// Deep link
// ──────────────────────────────────────────────

/**
 * Opens the Sonos app when the user taps album art.
 *
 * Attempts to launch the Sonos S2 app (com.sonos.acr2) first,
 * then falls back to the S1 app (com.sonos.acr), then to the
 * Play Store listing if neither is installed.
 *
 * Not debounced — this is a navigation action, not a speaker command.
 */
class OpenSonosAppAction : ActionCallback {

    companion object {
        private const val SONOS_S2_PACKAGE = "com.sonos.acr2"
        private const val SONOS_S1_PACKAGE = "com.sonos.acr"
        private const val PLAY_STORE_URI = "market://details?id=$SONOS_S2_PACKAGE"
        private const val PLAY_STORE_WEB =
            "https://play.google.com/store/apps/details?id=$SONOS_S2_PACKAGE"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d(TAG, "OpenSonosAppAction triggered")
        HapticHelper.playConfirm(context)

        val launched = tryLaunchPackage(context, SONOS_S2_PACKAGE)
            || tryLaunchPackage(context, SONOS_S1_PACKAGE)

        if (!launched) {
            Log.d(TAG, "Sonos app not installed — opening Play Store")
            openPlayStore(context)
        }
    }

    private fun tryLaunchPackage(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                Log.d(TAG, "Launched $packageName")
                true
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch $packageName", e)
                false
            }
        }
        return false
    }

    private fun openPlayStore(context: Context) {
        try {
            // Try market:// URI first (opens Play Store app)
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_URI)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(marketIntent)
        } catch (e: Exception) {
            // Fallback to web Play Store
            try {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(PLAY_STORE_WEB)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to open Play Store", e2)
            }
        }
    }
}
