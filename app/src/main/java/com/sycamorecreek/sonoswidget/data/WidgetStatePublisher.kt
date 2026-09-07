package com.sycamorecreek.sonoswidget.data

import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The only path that publishes visible widget state.
 *
 * Serializing the StateFlow update and the Glance write under one lock keeps
 * every widget instance on the same publication order: a slow writer cannot
 * deliver its state to the launcher after a newer writer already did.
 *
 * Field revisions let a slow caller prove it is still writing the state it
 * originally observed. Enrichment that outlived a user's tap drops the fields
 * that tap changed; an optimistic command's rollback applies only while it
 * still owns the field it claimed.
 *
 * The sink is the durable write (`WidgetStateStore.pushState`). It is invoked
 * while the lock is held, so it must not call back into this publisher.
 */
internal class WidgetStatePublisher(
    initialState: SonosWidgetState = SonosWidgetState(),
    private val sink: suspend (SonosWidgetState) -> Unit
) {
    private val mutex = Mutex()
    private val revisions = WidgetStateRevisionPolicy()
    private val _state = MutableStateFlow(initialState)

    val state: StateFlow<SonosWidgetState> = _state.asStateFlow()
    val current: SonosWidgetState get() = _state.value

    /** Claims fields for one optimistic command so its own rollback stays identifiable. */
    suspend fun claim(
        fields: Set<WidgetStateRevisionPolicy.Field>
    ): WidgetStateRevisionPolicy.Ownership = mutex.withLock { revisions.claim(fields) }

    suspend fun snapshot(): WidgetStateRevisionPolicy.Snapshot = mutex.withLock { revisions.snapshot() }

    suspend fun isFieldUnchanged(
        snapshot: WidgetStateRevisionPolicy.Snapshot,
        field: WidgetStateRevisionPolicy.Field
    ): Boolean = mutex.withLock { revisions.unchangedSince(snapshot, field) }

    /**
     * Publishes [fields] of [newState]. With an [ownership], the write happens
     * only while that operation still owns every field it is writing, so an
     * older failure cannot undo a newer success.
     *
     * Returns whether the state was published.
     */
    suspend fun publish(
        newState: SonosWidgetState,
        fields: Set<WidgetStateRevisionPolicy.Field> = WidgetStateRevisionPolicy.Field.entries.toSet(),
        ownership: WidgetStateRevisionPolicy.Ownership? = null
    ): Boolean = mutex.withLock {
        if (ownership != null && fields.any { !revisions.stillOwns(ownership, it) }) {
            return@withLock false
        }
        // An owned write keeps its claim so the operation can still roll itself
        // back later; only unowned writes retire older snapshots.
        if (ownership == null) revisions.record(fields)
        write(mergeStateFields(_state.value, newState, fields))
        true
    }

    /**
     * Publishes only the poll fields that no command changed while the poll was
     * in flight, so a slow refresh cannot overwrite newer user intent.
     *
     * Pending operations are exempt from a poll entirely. They are this app's
     * own in-flight requests, and a speaker response neither reports nor
     * cancels them — a poll that carried its own empty list would clear
     * "Preparing favorite" while the favorite is still loading.
     *
     * Returns whether anything was published.
     */
    suspend fun publishPoll(
        incoming: SonosWidgetState,
        snapshot: WidgetStateRevisionPolicy.Snapshot
    ): Boolean = mutex.withLock {
        val fields = WidgetStateRevisionPolicy.Field.entries.filterTo(mutableSetOf()) {
            revisions.unchangedSince(snapshot, it)
        }
        if (fields.isEmpty()) return@withLock false
        val inFlight = _state.value.pendingOperations
        revisions.record(fields)
        write(mergeStateFields(_state.value, incoming, fields).copy(pendingOperations = inFlight))
        true
    }

    private suspend fun write(published: SonosWidgetState) {
        _state.value = published
        sink(published)
    }

    /** Applies a narrow asynchronous patch without restoring unrelated old data. */
    private fun mergeStateFields(
        current: SonosWidgetState,
        incoming: SonosWidgetState,
        fields: Set<WidgetStateRevisionPolicy.Field>
    ): SonosWidgetState {
        if (fields.size == WidgetStateRevisionPolicy.Field.entries.size) return incoming
        var merged = current
        if (WidgetStateRevisionPolicy.Field.PLAYBACK in fields) {
            merged = merged.copy(
                playbackState = incoming.playbackState,
                volumeMuted = incoming.volumeMuted,
                shuffleEnabled = incoming.shuffleEnabled,
                repeatMode = incoming.repeatMode
            )
        }
        if (WidgetStateRevisionPolicy.Field.VOLUME in fields) merged = merged.copy(volume = incoming.volume)
        if (WidgetStateRevisionPolicy.Field.ENRICHMENT in fields) {
            merged = merged.copy(
                queue = incoming.queue,
                favorites = incoming.favorites,
                capabilities = incoming.capabilities
            )
        }
        if (WidgetStateRevisionPolicy.Field.ARTWORK in fields) {
            merged = merged.copy(colorPalette = incoming.colorPalette, artworkVersion = incoming.artworkVersion)
        }
        if (WidgetStateRevisionPolicy.Field.MEDIA in fields) {
            merged = merged.copy(currentTrack = incoming.currentTrack, currentSource = incoming.currentSource)
        }
        if (WidgetStateRevisionPolicy.Field.ROOM in fields) {
            merged = merged.copy(
                activeZone = incoming.activeZone,
                zones = incoming.zones,
                connectionMode = incoming.connectionMode
            )
        }
        if (WidgetStateRevisionPolicy.Field.STATUS in fields) {
            merged = merged.copy(
                isReconnecting = incoming.isReconnecting,
                isRateLimited = incoming.isRateLimited,
                isOffline = incoming.isOffline,
                isUpdating = incoming.isUpdating,
                pendingOperations = incoming.pendingOperations,
                lastEssentialRefreshMs = incoming.lastEssentialRefreshMs,
                isContentStale = incoming.isContentStale,
                errorMessage = incoming.errorMessage,
                showPermissionHint = incoming.showPermissionHint,
                offlineSpeakerIds = incoming.offlineSpeakerIds,
                lastUpdatedMs = incoming.lastUpdatedMs
            )
        }
        return merged
    }
}
