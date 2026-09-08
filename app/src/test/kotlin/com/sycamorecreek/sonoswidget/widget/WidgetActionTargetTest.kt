package com.sycamorecreek.sonoswidget.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2 acceptance: an action carries the room its render was showing, and is
 * refused if it would land anywhere else.
 *
 * A launcher dispatches from the RemoteViews the user actually touched, which
 * can be seconds old. Checking the repository's *current* room is not enough —
 * by then it may already be the wrong one.
 */
class WidgetActionTargetTest {

    private val kitchen = Zone(id = "kitchen", displayName = "Kitchen")
    private val office = Zone(id = "office", displayName = "Office")

    private fun connected(
        activeZone: Zone = kitchen,
        pendingOperations: List<PendingWidgetOperation> = emptyList()
    ) = SonosWidgetState(
        activeZone = activeZone,
        zones = listOf(kitchen, office),
        connectionMode = ConnectionMode.LOCAL_SSDP,
        pendingOperations = pendingOperations
    )

    // ── Target binding ───────────────────────────────────────────────

    @Test fun `a tap aimed at room A is refused once room B is active`() {
        // The user tapped pause on a render showing the Kitchen; by the time the
        // launcher delivered it, the shared target had moved to the Office.
        assertFalse(
            canDispatchAction(
                state = connected(activeZone = office),
                renderedTargetId = kitchen.id,
                supported = true
            )
        )
    }

    @Test fun `a tap aimed at the active room is dispatched`() {
        assertTrue(
            canDispatchAction(
                state = connected(activeZone = kitchen),
                renderedTargetId = kitchen.id,
                supported = true
            )
        )
    }

    @Test fun `an action with no rendered room is still dispatched`() {
        // Picking a room from the selector chooses its own destination, and a
        // RemoteViews built before this key existed reports nothing.
        val state = connected(activeZone = office)
        assertTrue(canDispatchAction(state, renderedTargetId = null, supported = true))
        assertTrue(canDispatchAction(state, renderedTargetId = "", supported = true))
    }

    // ── Operations in flight ─────────────────────────────────────────

    @Test fun `a tap is refused while the room is still switching`() {
        // Topology is still loading, so the destination is not settled yet.
        val switching = connected(
            pendingOperations = listOf(
                PendingWidgetOperation(
                    id = "switch-1",
                    type = WidgetOperationType.SWITCHING_ROOM,
                    targetId = office.id
                )
            )
        )

        assertFalse(canDispatchAction(switching, renderedTargetId = kitchen.id, supported = true))
    }

    @Test fun `a tap is refused while a grouping change is being applied`() {
        val grouping = connected(
            pendingOperations = listOf(
                PendingWidgetOperation(
                    id = "group-1",
                    type = WidgetOperationType.APPLYING_GROUPING,
                    targetId = kitchen.id
                )
            )
        )

        assertFalse(canDispatchAction(grouping, renderedTargetId = kitchen.id, supported = true))
    }

    @Test fun `an unrelated request in flight does not block a tap`() {
        val loading = connected(
            pendingOperations = listOf(
                PendingWidgetOperation(
                    id = "favorite-1",
                    type = WidgetOperationType.LOADING_FAVORITE,
                    targetId = kitchen.id
                )
            )
        )

        assertTrue(canDispatchAction(loading, renderedTargetId = kitchen.id, supported = true))
    }

    // ── Availability ─────────────────────────────────────────────────

    @Test fun `the right room is not enough when the control is unsupported`() {
        assertFalse(
            canDispatchAction(connected(), renderedTargetId = kitchen.id, supported = false)
        )
    }

    @Test fun `an unreachable speaker refuses a tap for its own room`() {
        val disconnected = connected().copy(connectionMode = ConnectionMode.DISCONNECTED)
        assertFalse(canDispatchAction(disconnected, renderedTargetId = kitchen.id, supported = true))

        val offline = connected().copy(isOffline = true)
        assertFalse(canDispatchAction(offline, renderedTargetId = kitchen.id, supported = true))

        val rateLimited = connected().copy(isRateLimited = true)
        assertFalse(canDispatchAction(rateLimited, renderedTargetId = kitchen.id, supported = true))

        val updating = connected().copy(isUpdating = true)
        assertFalse(canDispatchAction(updating, renderedTargetId = kitchen.id, supported = true))
    }
}
