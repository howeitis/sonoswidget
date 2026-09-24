package com.sycamorecreek.sonoswidget.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleActionsPolicyTest {

    private val quick = listOf(Favorite("FV:2/1", "Your Likes"), Favorite("FV:2/2", "New Releases"))
    private val idle = SonosWidgetState(
        playbackState = PlaybackState.STOPPED,
        connectionMode = ConnectionMode.LOCAL_SSDP,
        capabilities = WidgetCapabilities(canPlayFavorites = true),
        quickPlay = quick
    )

    @Test fun `stopped and paused are idle`() {
        assertTrue(IdleActionsPolicy.isIdle(idle))
        assertTrue(IdleActionsPolicy.isIdle(idle.copy(playbackState = PlaybackState.PAUSED)))
        assertEquals(quick, IdleActionsPolicy.quickPlay(idle))
    }

    @Test fun `playing or loading is not idle`() {
        assertFalse(IdleActionsPolicy.isIdle(idle.copy(playbackState = PlaybackState.PLAYING)))
        assertFalse(IdleActionsPolicy.isIdle(idle.copy(playbackState = PlaybackState.TRANSITIONING)))
    }

    @Test fun `an in-flight favorite or room change suppresses the shortcuts`() {
        for (type in listOf(
            WidgetOperationType.LOADING_FAVORITE,
            WidgetOperationType.SWITCHING_ROOM,
            WidgetOperationType.APPLYING_GROUPING
        )) {
            val busy = idle.copy(pendingOperations = listOf(PendingWidgetOperation(id = "op", type = type)))
            assertFalse(type.name, IdleActionsPolicy.showFindPlaying(busy))
            assertTrue(type.name, IdleActionsPolicy.quickPlay(busy).isEmpty())
        }
    }

    @Test fun `disconnected still offers quick play because the tap reconnects first`() {
        val disconnected = idle.copy(
            connectionMode = ConnectionMode.DISCONNECTED,
            capabilities = WidgetCapabilities()
        )

        assertTrue(IdleActionsPolicy.showFindPlaying(disconnected))
        assertEquals(quick, IdleActionsPolicy.quickPlay(disconnected))
    }

    @Test fun `cloud mode cannot play favorites`() {
        val cloud = idle.copy(connectionMode = ConnectionMode.CLOUD, capabilities = WidgetCapabilities())

        assertTrue(IdleActionsPolicy.quickPlay(cloud).isEmpty())
        assertTrue(IdleActionsPolicy.showFindPlaying(cloud))
    }

    @Test fun `offline or rate limited hides quick play`() {
        assertTrue(IdleActionsPolicy.quickPlay(idle.copy(isOffline = true)).isEmpty())
        assertTrue(IdleActionsPolicy.quickPlay(idle.copy(isRateLimited = true)).isEmpty())
    }
}
