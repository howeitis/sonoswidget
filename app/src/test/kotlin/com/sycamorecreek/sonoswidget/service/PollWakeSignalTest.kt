package com.sycamorecreek.sonoswidget.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6 acceptance: "a refresh request must wake/reset a sleeping loop rather
 * than wait out the previous backoff interval".
 *
 * The intervals here stand in for the real ones, which reach a minute on Wi-Fi
 * and five minutes off it — long enough that riding one out is the difference
 * between a widget that recovers and one that looks frozen.
 */
class PollWakeSignalTest {

    /** Long enough that finishing early cannot be mistaken for the timeout. */
    private val longWait = 30_000L

    @Test(timeout = 5_000) fun `a refresh during the wait cuts it short`() = runBlocking {
        val signal = PollWakeSignal()

        val startedAtMs = System.currentTimeMillis()
        launch(Dispatchers.Default) { signal.requestNow() }
        val wokenEarly = signal.awaitNextPoll(longWait)

        assertTrue("the loop slept through a refresh", wokenEarly)
        assertTrue(
            "the wait was not actually cut short",
            System.currentTimeMillis() - startedAtMs < longWait
        )
    }

    @Test(timeout = 5_000) fun `a refresh arriving mid-poll still cuts short the next wait`() = runBlocking {
        val signal = PollWakeSignal()

        // The request lands while a poll is running, so nobody is waiting yet.
        signal.requestNow()

        assertTrue("a refresh was dropped because no one was waiting", signal.awaitNextPoll(longWait))
    }

    @Test(timeout = 5_000) fun `an undisturbed wait runs to its interval`() = runBlocking {
        val signal = PollWakeSignal()

        val startedAtMs = System.currentTimeMillis()
        val wokenEarly = signal.awaitNextPoll(150)

        assertFalse("the loop woke without being asked to", wokenEarly)
        assertTrue(
            "the interval was not respected",
            System.currentTimeMillis() - startedAtMs >= 150
        )
    }

    @Test(timeout = 5_000) fun `two rapid refreshes do not skip two waits`() = runBlocking {
        val signal = PollWakeSignal()

        signal.requestNow()
        signal.requestNow()

        assertTrue(signal.awaitNextPoll(longWait))
        // Coalesced: the second request does not buy a second free poll.
        assertFalse("a coalesced refresh skipped an extra interval", signal.awaitNextPoll(150))
    }

    @Test(timeout = 5_000) fun `the loop keeps waiting normally after being woken`() = runBlocking {
        val signal = PollWakeSignal()

        signal.requestNow()
        assertTrue(signal.awaitNextPoll(longWait))
        assertFalse(signal.awaitNextPoll(150))
        assertFalse(signal.awaitNextPoll(150))
    }
}
