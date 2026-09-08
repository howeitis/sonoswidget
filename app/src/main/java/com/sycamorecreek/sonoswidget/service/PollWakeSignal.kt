package com.sycamorecreek.sonoswidget.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets a refresh cut short the poll loop's wait.
 *
 * The loop sleeps for an interval that grows to a minute on Wi-Fi and five
 * minutes off it. A user tapping refresh, or Wi-Fi coming back, already runs
 * one immediate poll — but without this the loop stays asleep on its old
 * deadline, so a reconnected speaker went unpolled for the rest of that
 * backoff: no progress bar, no external changes, for up to five minutes.
 *
 * The channel is conflated, so a request that arrives while a poll is already
 * running is remembered and cuts short the wait that follows it, rather than
 * being dropped. At most one is remembered: two rapid requests do not skip two
 * waits.
 */
internal class PollWakeSignal {

    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Asks the loop to stop waiting and poll now. Safe to call from anywhere. */
    fun requestNow() {
        wake.trySend(Unit)
    }

    /**
     * Waits out [intervalMs] unless a refresh is requested first.
     *
     * Returns true when the wait was cut short, which the caller may log; the
     * loop's behaviour is the same either way — poll again.
     */
    suspend fun awaitNextPoll(intervalMs: Long): Boolean =
        withTimeoutOrNull(intervalMs) { wake.receive() } != null
}
