package com.sycamorecreek.sonoswidget.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accumulates volume taps and hands the newest absolute target to one drain.
 *
 * The point of this type is the *scope* of its lock. Every method here does
 * bookkeeping only and returns immediately; the SOAP command and the refresh
 * that follows it run outside, on the drain coroutine. A tap arriving while a
 * reconcile is still in flight therefore never waits for it — which is the
 * whole of R5, and is not observable from the repository, whose taps and
 * network calls are entangled with Android.
 *
 * Three values are deliberately distinct:
 *  - *desired* is what the user has asked for and the app has not yet sent.
 *  - *acknowledged* is the last value a speaker confirmed, and is what a
 *    definite failure restores.
 *  - the displayed value is the repository's optimistic override, published
 *    separately so the widget moves on the tap rather than on the response.
 */
internal class VolumeIntentCoordinator {

    data class Intent(val volume: Int, val targetId: String)

    private val mutex = Mutex()
    private var desired: Int? = null
    private var desiredTargetId: String? = null
    private var acknowledged: Int? = null
    private var draining = false

    /**
     * Folds one relative tap into the newest intent and returns the value to
     * display now.
     *
     * Deltas accumulate against the previous *intent*, not against the last
     * value the speaker reported, so five +5 taps from 50 reach 75 even though
     * no response has arrived yet.
     */
    suspend fun accumulate(
        delta: Int,
        targetId: String,
        displayedVolume: Int,
        acknowledgedVolume: Int
    ): Int = mutex.withLock {
        val target = nextVolumeIntent(desired ?: displayedVolume, delta)
        if (acknowledged == null) acknowledged = acknowledgedVolume
        desired = target
        desiredTargetId = targetId
        target
    }

    /** Whether this caller became the drain owner; false when one already runs. */
    suspend fun claimDrain(): Boolean = mutex.withLock {
        if (draining) false else { draining = true; true }
    }

    /**
     * Takes the newest unsent target, coalescing away everything the user has
     * since superseded. Returns null once nothing is queued, which also
     * releases drain ownership so the next tap starts a fresh drain.
     */
    suspend fun takeNext(): Intent? = mutex.withLock {
        val volume = desired
        val targetId = desiredTargetId
        if (volume == null || targetId == null) {
            draining = false
            null
        } else {
            desired = null
            Intent(volume, targetId)
        }
    }

    suspend fun onAcknowledged(volume: Int) = mutex.withLock { acknowledged = volume }

    /**
     * The value a definite failure should restore, or null when it must not.
     *
     * A newer tap is already queued, or the room has moved on: either way the
     * failed command no longer describes what the user wants.
     */
    suspend fun restoreAfterFailure(
        intentTargetId: String,
        currentTargetId: String?
    ): Int? = mutex.withLock {
        if (desired == null && intentTargetId == currentTargetId) acknowledged else null
    }

    /** A room switch is a new destination; unsent intent must not follow it. */
    suspend fun discardForRoomChange() = mutex.withLock {
        desired = null
        desiredTargetId = null
        acknowledged = null
    }
}
