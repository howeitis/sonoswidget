package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.widget.PendingWidgetOperation
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.WidgetOperationType
import com.sycamorecreek.sonoswidget.widget.Zone
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * R3 acceptance: newer intent survives slow enrichment, old rollbacks, room
 * changes and concurrent publication.
 *
 * These drive the real publication path rather than the revision ledger alone,
 * so a regression in field merging or lock scope fails here.
 */
class WidgetStatePublisherTest {

    private fun publisher(
        initial: SonosWidgetState = SonosWidgetState(),
        sink: suspend (SonosWidgetState) -> Unit = {}
    ) = WidgetStatePublisher(initial, sink)

    // ── Slow enrichment vs. newer intent ─────────────────────────────

    @Test fun `pause during delayed artwork stays paused`() = runBlocking {
        val publisher = publisher(SonosWidgetState(playbackState = PlaybackState.PLAYING))

        // A poll starts while the speaker is playing.
        val pollSnapshot = publisher.snapshot()

        // The user pauses before that poll's artwork comes back.
        val tap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))
        publisher.publish(
            publisher.current.copy(playbackState = PlaybackState.PAUSED),
            setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
            tap
        )

        // Artwork resolves late, still carrying the pre-tap PLAYING snapshot.
        publisher.publish(
            SonosWidgetState(playbackState = PlaybackState.PLAYING, artworkVersion = "art-2"),
            setOf(WidgetStateRevisionPolicy.Field.ARTWORK)
        )

        assertEquals(PlaybackState.PAUSED, publisher.current.playbackState)
        assertEquals("art-2", publisher.current.artworkVersion)
        assertFalse(publisher.isFieldUnchanged(pollSnapshot, WidgetStateRevisionPolicy.Field.PLAYBACK))
    }

    @Test fun `a poll that outlived a tap keeps the tap and applies its other fields`() = runBlocking {
        val publisher = publisher(SonosWidgetState(playbackState = PlaybackState.PLAYING, volume = 50))
        val pollSnapshot = publisher.snapshot()

        val tap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))
        publisher.publish(
            publisher.current.copy(playbackState = PlaybackState.PAUSED),
            setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
            tap
        )

        // The poll finally lands with the speaker's pre-tap playback and a
        // volume nobody touched.
        assertTrue(
            publisher.publishPoll(
                SonosWidgetState(playbackState = PlaybackState.PLAYING, volume = 30),
                pollSnapshot
            )
        )

        assertEquals(PlaybackState.PAUSED, publisher.current.playbackState)
        assertEquals(30, publisher.current.volume)
    }

    @Test fun `a poll with nothing left to apply publishes nothing`() = runBlocking {
        val publisher = publisher(SonosWidgetState(volume = 50))
        val pollSnapshot = publisher.snapshot()

        // A full write moves every field on after the poll read them.
        publisher.publish(SonosWidgetState(volume = 70))

        assertFalse(publisher.publishPoll(SonosWidgetState(volume = 10), pollSnapshot))
        assertEquals(70, publisher.current.volume)
    }

    // ── Old rollbacks vs. newer success ──────────────────────────────

    @Test fun `an older failure cannot undo a newer volume success`() = runBlocking {
        val publisher = publisher(SonosWidgetState(volume = 50))

        val firstTap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.VOLUME))
        publisher.publish(
            publisher.current.copy(volume = 60),
            setOf(WidgetStateRevisionPolicy.Field.VOLUME),
            firstTap
        )

        val secondTap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.VOLUME))
        publisher.publish(
            publisher.current.copy(volume = 70),
            setOf(WidgetStateRevisionPolicy.Field.VOLUME),
            secondTap
        )

        // The first command's failure arrives last and wants its own base back.
        assertFalse(
            publisher.publish(
                publisher.current.copy(volume = 50),
                setOf(WidgetStateRevisionPolicy.Field.VOLUME),
                firstTap
            )
        )
        assertEquals(70, publisher.current.volume)

        // The newest command may still roll itself back.
        assertTrue(
            publisher.publish(
                publisher.current.copy(volume = 60),
                setOf(WidgetStateRevisionPolicy.Field.VOLUME),
                secondTap
            )
        )
        assertEquals(60, publisher.current.volume)
    }

    @Test fun `a mute failure cannot undo a confirmed poll`() = runBlocking {
        val publisher = publisher(SonosWidgetState(volumeMuted = false))

        val tap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))
        publisher.publish(
            publisher.current.copy(volumeMuted = true),
            setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
            tap
        )

        // A poll started after the tap, so it can confirm the mute the tap asked
        // for. That confirmation is newer than the tap and retires its claim.
        val pollSnapshot = publisher.snapshot()
        assertTrue(publisher.publishPoll(SonosWidgetState(volumeMuted = true), pollSnapshot))

        // The tap's own response times out and reports failure afterwards.
        assertFalse(
            publisher.publish(
                publisher.current.copy(volumeMuted = false),
                setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
                tap
            )
        )
        assertTrue(publisher.current.volumeMuted)
    }

    @Test fun `a tap that still owns its field may roll itself back`() = runBlocking {
        val publisher = publisher(SonosWidgetState(volumeMuted = false))

        // A poll already in flight when the tap happens cannot confirm playback,
        // so it leaves the tap owning the field.
        val pollSnapshot = publisher.snapshot()
        val tap = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))
        publisher.publish(
            publisher.current.copy(volumeMuted = true),
            setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
            tap
        )
        publisher.publishPoll(SonosWidgetState(volumeMuted = false), pollSnapshot)
        assertTrue("a stale poll overwrote newer intent", publisher.current.volumeMuted)

        // Nothing newer touched playback, so the failed tap undoes its own guess.
        assertTrue(
            publisher.publish(
                publisher.current.copy(volumeMuted = false),
                setOf(WidgetStateRevisionPolicy.Field.PLAYBACK),
                tap
            )
        )
        assertFalse(publisher.current.volumeMuted)
    }

    // ── In-flight requests vs. polling ───────────────────────────────

    @Test fun `a poll cannot clear a request that is still running`() = runBlocking {
        val loadingFavorite = PendingWidgetOperation(
            id = "favorite-1",
            type = WidgetOperationType.LOADING_FAVORITE,
            targetId = "kitchen"
        )
        val publisher = publisher(SonosWidgetState(volume = 50))
        publisher.publish(publisher.current.copy(pendingOperations = listOf(loadingFavorite)))

        // A poll runs while the playlist is still loading. It reports the
        // speaker, which knows nothing about this app's request.
        val pollSnapshot = publisher.snapshot()
        assertTrue(publisher.publishPoll(SonosWidgetState(volume = 30), pollSnapshot))

        assertEquals(listOf(loadingFavorite), publisher.current.pendingOperations)
        assertEquals(30, publisher.current.volume)
    }

    @Test fun `finishing a request still clears it`() = runBlocking {
        val publisher = publisher()
        publisher.publish(
            publisher.current.copy(
                pendingOperations = listOf(
                    PendingWidgetOperation(id = "group-1", type = WidgetOperationType.APPLYING_GROUPING)
                )
            )
        )

        // Only the request's own completion retires it.
        publisher.publish(publisher.current.copy(pendingOperations = emptyList()))

        assertTrue(publisher.current.pendingOperations.isEmpty())
    }

    // ── Room scoping ─────────────────────────────────────────────────

    @Test fun `room A optimism cannot leak into room B`() = runBlocking {
        val publisher = publisher(
            SonosWidgetState(activeZone = Zone(id = "A", displayName = "Kitchen"), volume = 40)
        )

        val tapInA = publisher.claim(setOf(WidgetStateRevisionPolicy.Field.VOLUME))
        publisher.publish(
            publisher.current.copy(volume = 55),
            setOf(WidgetStateRevisionPolicy.Field.VOLUME),
            tapInA
        )

        // Switching rooms republishes the whole state for the new destination.
        publisher.publish(
            SonosWidgetState(activeZone = Zone(id = "B", displayName = "Office"), volume = 20)
        )

        // Room A's command fails afterwards and tries to restore room A's volume.
        assertFalse(
            publisher.publish(
                publisher.current.copy(volume = 40),
                setOf(WidgetStateRevisionPolicy.Field.VOLUME),
                tapInA
            )
        )
        assertEquals(20, publisher.current.volume)
        assertEquals("B", publisher.current.activeZone.id)
    }

    // ── Publication ordering across widget instances ─────────────────

    @Test fun `a slow write cannot deliver older state after a newer one`() = runBlocking {
        val delivered = CopyOnWriteArrayList<Int>()
        val insideFirstWrite = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        var firstWrite = true

        // The sink stands in for the Glance write that reaches every widget
        // instance; blocking it holds the publication lock.
        val publisher = publisher(SonosWidgetState(volume = 0)) { state ->
            if (firstWrite) {
                firstWrite = false
                insideFirstWrite.complete(Unit)
                releaseFirstWrite.await()
            }
            delivered += state.volume
        }

        val slow = launch(Dispatchers.Default) {
            publisher.publish(
                publisher.current.copy(volume = 10),
                setOf(WidgetStateRevisionPolicy.Field.VOLUME)
            )
        }
        insideFirstWrite.await()

        val fast = launch(Dispatchers.Default) {
            publisher.publish(
                publisher.current.copy(volume = 20),
                setOf(WidgetStateRevisionPolicy.Field.VOLUME)
            )
        }

        // The newer write must wait behind the stalled one rather than
        // overtaking it into the launcher.
        delay(100)
        assertTrue("a stalled publication still reached the widgets", delivered.isEmpty())
        assertTrue("the newer write did not wait for the lock", fast.isActive)
        assertEquals("the newer write advanced state past a stalled publication", 10, publisher.current.volume)

        releaseFirstWrite.complete(Unit)
        slow.join()
        fast.join()

        assertEquals(listOf(10, 20), delivered)
        assertEquals(20, publisher.current.volume)
        // What the widgets last received is what the app believes it published.
        assertEquals(publisher.current.volume, delivered.last())
    }
}
