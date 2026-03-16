package com.sycamorecreek.sonoswidget.data

import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Queues and debounces widget actions during cold-start reconnection.
 *
 * Per PRD Section 7.3: During reconnection (when the "Reconnecting…" badge is
 * visible), user taps are queued but debounced — only the final intended state
 * is executed once the service reconnects. For example, tapping Skip three times
 * while disconnected results in a single skip-to-third-track command.
 *
 * A 3-second stale intent timeout applies to all debounced taps. If the service
 * takes longer than 3 seconds to reconnect, all queued actions are silently
 * discarded.
 *
 * Thread safety: uses [CopyOnWriteArrayList] since enqueue (from Glance
 * ActionCallbacks on various dispatchers) and drain (from the poll coroutine)
 * may interleave.
 */
class ActionDebouncer {

    companion object {
        private const val TAG = "ActionDebouncer"

        /**
         * PRD: "A 3-second stale intent timeout applies to all debounced taps."
         * Intents older than this threshold at drain time are silently discarded.
         */
        const val STALE_INTENT_TIMEOUT_MS = 3_000L
    }

    /**
     * Widget action types that can be debounced during reconnection.
     */
    enum class ActionType {
        PLAY_PAUSE,
        NEXT,
        PREVIOUS,
        VOLUME_UP,
        VOLUME_DOWN,
        TOGGLE_SHUFFLE,
        CYCLE_REPEAT,
        JUMP_TO_TRACK,
        SWITCH_ZONE,
        TOGGLE_GROUP,
        GROUP_ALL
    }

    /**
     * A timestamped intent queued during reconnection.
     *
     * @property type The widget action that was requested.
     * @property timestampMs When the user tapped (wall-clock millis).
     * @property param Optional parameter (zone ID, speaker UUID, track number, etc.).
     */
    data class QueuedIntent(
        val type: ActionType,
        val timestampMs: Long = System.currentTimeMillis(),
        val param: String? = null
    )

    /**
     * Net actions produced by collapsing multiple queued intents.
     * Each variant represents the minimal set of work needed after debouncing.
     */
    sealed class CollapsedAction {
        /** Toggle play/pause once. */
        data object PlayPauseToggle : CollapsedAction()

        /** Skip by [delta] tracks: positive = forward, negative = backward. */
        data class Skip(val delta: Int) : CollapsedAction()

        /** Adjust volume by [delta] units (positive = louder, negative = quieter). */
        data class VolumeAdjust(val delta: Int) : CollapsedAction()

        /** Toggle shuffle once. */
        data object ShuffleToggle : CollapsedAction()

        /** Cycle repeat mode [count] times (1 or 2, since 3 is a full cycle = no-op). */
        data class RepeatCycle(val count: Int) : CollapsedAction()

        /** Jump playback to queue position [trackNr]. */
        data class JumpToTrack(val trackNr: Int) : CollapsedAction()

        /** Switch to zone [zoneId]. */
        data class SwitchZone(val zoneId: String) : CollapsedAction()

        /** Toggle group membership for speaker [speakerUuid]. */
        data class ToggleGroup(val speakerUuid: String) : CollapsedAction()

        /** Group all speakers into the active zone. */
        data object GroupAll : CollapsedAction()
    }

    // ──────────────────────────────────────────────
    // Queue
    // ──────────────────────────────────────────────

    private val queue = CopyOnWriteArrayList<QueuedIntent>()

    /** Whether there are any queued intents waiting to be drained. */
    val hasPendingActions: Boolean get() = queue.isNotEmpty()

    /**
     * Enqueues a widget action to be executed when the connection is restored.
     * Called from Glance ActionCallbacks when the widget is in reconnecting state.
     */
    fun enqueue(type: ActionType, param: String? = null) {
        val intent = QueuedIntent(type = type, param = param)
        queue.add(intent)
        Log.d(TAG, "Enqueued $type (param=$param) — queue size: ${queue.size}")
    }

    /**
     * Drains the queue and returns collapsed net actions to execute.
     *
     * Applies the 3-second stale intent timeout: any intent whose age exceeds
     * [STALE_INTENT_TIMEOUT_MS] at drain time is silently discarded.
     *
     * @return A list of [CollapsedAction] representing the minimal work to
     *         replicate the user's intent, or empty if all intents were stale.
     */
    fun drain(): List<CollapsedAction> {
        val snapshot = ArrayList(queue)
        queue.clear()

        if (snapshot.isEmpty()) return emptyList()

        val now = System.currentTimeMillis()
        val fresh = snapshot.filter { now - it.timestampMs <= STALE_INTENT_TIMEOUT_MS }

        if (fresh.isEmpty()) {
            Log.d(
                TAG,
                "All ${snapshot.size} queued intent(s) expired " +
                    "(>${STALE_INTENT_TIMEOUT_MS}ms) — silently discarding"
            )
            return emptyList()
        }

        val staleCount = snapshot.size - fresh.size
        if (staleCount > 0) {
            Log.d(TAG, "Draining ${fresh.size} fresh intent(s) ($staleCount stale discarded)")
        } else {
            Log.d(TAG, "Draining ${fresh.size} intent(s)")
        }

        return collapse(fresh)
    }

    /**
     * Discards all queued intents without executing them.
     * Called when entering a terminal error state (e.g., fully offline).
     */
    fun clear() {
        val count = queue.size
        queue.clear()
        if (count > 0) {
            Log.d(TAG, "Cleared $count queued intent(s)")
        }
    }

    // ──────────────────────────────────────────────
    // Collapse logic
    // ──────────────────────────────────────────────

    /**
     * Collapses a list of fresh intents into net actions.
     *
     * Debounce rules:
     * - Next/Previous: accumulate into a skip delta (+N forward, -N backward)
     * - Play/Pause: odd count → toggle once, even count → no-op
     * - Volume Up/Down: accumulate delta (+5 per up, -5 per down)
     * - Shuffle Toggle: odd count → toggle once, even count → no-op
     * - Repeat Cycle: count mod 3 (since 3 modes cycle back to original)
     * - Jump to Track: last one wins (explicit position overrides skip delta)
     * - Switch Zone: last one wins
     * - Toggle Group: per-speaker odd count → toggle, even → no-op
     * - Group All: odd count → execute, even → no-op
     */
    private fun collapse(intents: List<QueuedIntent>): List<CollapsedAction> {
        val actions = mutableListOf<CollapsedAction>()

        // Accumulators
        var skipDelta = 0
        var volumeDelta = 0
        var playPauseCount = 0
        var shuffleToggleCount = 0
        var repeatCycleCount = 0

        // Last-wins actions
        var lastJumpTrack: Int? = null
        var lastSwitchZone: String? = null

        // Per-speaker group toggle counts
        val groupToggles = mutableMapOf<String, Int>()
        var groupAllCount = 0

        for (intent in intents) {
            when (intent.type) {
                ActionType.NEXT -> skipDelta++
                ActionType.PREVIOUS -> skipDelta--
                ActionType.PLAY_PAUSE -> playPauseCount++
                ActionType.VOLUME_UP -> volumeDelta += 5
                ActionType.VOLUME_DOWN -> volumeDelta -= 5
                ActionType.TOGGLE_SHUFFLE -> shuffleToggleCount++
                ActionType.CYCLE_REPEAT -> repeatCycleCount++
                ActionType.JUMP_TO_TRACK -> lastJumpTrack = intent.param?.toIntOrNull()
                ActionType.SWITCH_ZONE -> lastSwitchZone = intent.param
                ActionType.TOGGLE_GROUP -> {
                    val uuid = intent.param ?: continue
                    groupToggles[uuid] = (groupToggles[uuid] ?: 0) + 1
                }
                ActionType.GROUP_ALL -> groupAllCount++
            }
        }

        // Emit collapsed actions in logical execution order:
        // 1. Zone switch first (changes context for all subsequent commands)
        if (lastSwitchZone != null) {
            actions.add(CollapsedAction.SwitchZone(lastSwitchZone))
        }

        // 2. Play/pause toggle (only if odd number of taps = net toggle)
        if (playPauseCount % 2 == 1) {
            actions.add(CollapsedAction.PlayPauseToggle)
        }

        // 3. Skip delta (explicit queue jump overrides this — see below)
        if (skipDelta != 0) {
            actions.add(CollapsedAction.Skip(skipDelta))
        }

        // 4. Volume adjustment
        if (volumeDelta != 0) {
            actions.add(CollapsedAction.VolumeAdjust(volumeDelta))
        }

        // 5. Shuffle toggle
        if (shuffleToggleCount % 2 == 1) {
            actions.add(CollapsedAction.ShuffleToggle)
        }

        // 6. Repeat cycle (mod 3: 0 = no-op, 1 or 2 cycles needed)
        val netRepeatCycles = repeatCycleCount % 3
        if (netRepeatCycles > 0) {
            actions.add(CollapsedAction.RepeatCycle(netRepeatCycles))
        }

        // 7. Jump to track — overrides skip delta since explicit position wins
        if (lastJumpTrack != null) {
            actions.removeAll { it is CollapsedAction.Skip }
            actions.add(CollapsedAction.JumpToTrack(lastJumpTrack))
        }

        // 8. Per-speaker group toggles (only emit for speakers with odd toggle count)
        for ((uuid, count) in groupToggles) {
            if (count % 2 == 1) {
                actions.add(CollapsedAction.ToggleGroup(uuid))
            }
        }

        // 9. Group all (odd count = effective, even = no-op)
        if (groupAllCount % 2 == 1) {
            actions.add(CollapsedAction.GroupAll)
        }

        Log.d(TAG, "Collapsed ${intents.size} intent(s) into ${actions.size} action(s): $actions")
        return actions
    }
}
