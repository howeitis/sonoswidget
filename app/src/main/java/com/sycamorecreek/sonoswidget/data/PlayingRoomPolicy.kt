package com.sycamorecreek.sonoswidget.data

/**
 * Chooses the room a user-requested "find what's playing" scan should move to.
 *
 * Stricter than discovery's best-coordinator ranking, which falls back to a
 * paused or merely reachable room so that a cold start always has a target.
 * An explicit scan happens while the widget already has a room; moving it to
 * another silent room would only lose the user's place. So it moves only to a
 * room that is actually producing sound — or about to: `TRANSITIONING` is what
 * a speaker reports while loading something just started from another app,
 * which is precisely the moment this scan exists for.
 */
internal object PlayingRoomPolicy {

    /** One coordinator's probe result; [transportState] is null when unreachable. */
    data class Probe(val coordinatorId: String, val transportState: String?)

    /**
     * The coordinator to switch to, or null to stay where the widget is.
     * A PLAYING room beats a TRANSITIONING one, and the active room is never
     * returned: it is already shown, so switching to it would be a no-op.
     */
    fun pick(probes: List<Probe>, activeCoordinatorId: String?): String? {
        val candidates = probes.filter { it.coordinatorId != activeCoordinatorId }
        return candidates.firstOrNull { it.transportState == "PLAYING" }?.coordinatorId
            ?: candidates.firstOrNull { it.transportState == "TRANSITIONING" }?.coordinatorId
    }
}
