package com.sycamorecreek.sonoswidget.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R5 acceptance: taps stay responsive while a reconcile is still in flight.
 *
 * The arithmetic was already covered; what was not is the lock *scope* — that
 * accumulating a tap never waits on the network. Each test that matters here
 * blocks the drain deliberately and then taps: if the critical section ever
 * widened to cover the command again, these would deadlock rather than fail
 * quietly, so they carry timeouts.
 */
class VolumeIntentCoordinatorTest {

    private val kitchen = "kitchen"
    private val office = "office"

    @Test(timeout = 5_000) fun `five rapid taps reach seventy five while a reconcile is blocked`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        val sent = CopyOnWriteArrayList<Int>()
        val reconcileStarted = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()

        var displayed = intents.accumulate(
            delta = 5, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50
        )
        assertEquals("the first tap must show immediately", 55, displayed)
        assertTrue(intents.claimDrain())

        val drain = launch(Dispatchers.Default) {
            while (true) {
                val next = intents.takeNext() ?: return@launch
                reconcileStarted.complete(Unit)
                releaseReconcile.await()
                sent += next.volume
            }
        }
        reconcileStarted.await()

        // Four more taps arrive while the speaker has not answered the first.
        repeat(4) {
            displayed = intents.accumulate(
                delta = 5, targetId = kitchen, displayedVolume = displayed, acknowledgedVolume = 50
            )
        }

        assertEquals("taps must accumulate against intent, not the last response", 75, displayed)
        assertTrue("a tap was sent before the blocked reconcile finished", sent.isEmpty())

        releaseReconcile.complete(Unit)
        drain.join()

        // Coalesced: the in-flight target, then the newest — not five commands.
        assertEquals(listOf(55, 75), sent)
    }

    @Test(timeout = 5_000) fun `a tap taken mid-drain is not lost`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 5, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)
        assertTrue(intents.claimDrain())

        assertEquals(VolumeIntentCoordinator.Intent(55, kitchen), intents.takeNext())
        // Nothing queued, so the drain ends and releases its claim.
        assertNull(intents.takeNext())
        assertTrue("a finished drain must release ownership", intents.claimDrain())
    }

    @Test fun `a second drain does not start while one is running`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 5, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)

        assertTrue(intents.claimDrain())
        assertFalse("a second drain would send the same target twice", intents.claimDrain())
    }

    // ── Clamping ─────────────────────────────────────────────────────

    @Test fun `taps clamp at both endpoints and keep accumulating from there`() = runBlocking {
        val intents = VolumeIntentCoordinator()

        var displayed = 98
        repeat(3) {
            displayed = intents.accumulate(10, kitchen, displayed, acknowledgedVolume = 98)
        }
        assertEquals(100, displayed)

        repeat(3) {
            displayed = intents.accumulate(-50, kitchen, displayed, acknowledgedVolume = 98)
        }
        assertEquals(0, displayed)

        displayed = intents.accumulate(7, kitchen, displayed, acknowledgedVolume = 98)
        assertEquals("clamping must not strand the value at the endpoint", 7, displayed)
    }

    // ── Destination ──────────────────────────────────────────────────

    @Test fun `a room change discards intent that was never sent`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 20, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)

        intents.discardForRoomChange()

        assertNull("the Kitchen's volume followed the user into another room", intents.takeNext())
    }

    @Test fun `a failure cannot restore a volume once the room has moved on`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 20, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)
        intents.takeNext()

        assertNull(intents.restoreAfterFailure(intentTargetId = kitchen, currentTargetId = office))
    }

    // ── Failure rollback ─────────────────────────────────────────────

    @Test fun `a failure restores the last acknowledged volume`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 20, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)
        intents.takeNext()

        assertEquals(50, intents.restoreAfterFailure(kitchen, kitchen))
    }

    @Test fun `an older failure cannot undo a newer desired volume`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 20, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)
        val inFlight = intents.takeNext()!!

        // The user taps again while that command is still out.
        intents.accumulate(delta = 5, targetId = kitchen, displayedVolume = inFlight.volume, acknowledgedVolume = 50)

        assertNull(
            "a failed command dragged the volume back under newer intent",
            intents.restoreAfterFailure(inFlight.targetId, kitchen)
        )
    }

    @Test fun `an acknowledged volume becomes the value a later failure restores`() = runBlocking {
        val intents = VolumeIntentCoordinator()
        intents.accumulate(delta = 20, targetId = kitchen, displayedVolume = 50, acknowledgedVolume = 50)
        val first = intents.takeNext()!!
        intents.onAcknowledged(first.volume)

        intents.accumulate(delta = 10, targetId = kitchen, displayedVolume = 70, acknowledgedVolume = 50)
        intents.takeNext()

        assertEquals(
            "the restore point must move forward with each confirmed command",
            70,
            intents.restoreAfterFailure(kitchen, kitchen)
        )
    }
}
