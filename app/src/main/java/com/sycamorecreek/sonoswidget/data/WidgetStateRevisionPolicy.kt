package com.sycamorecreek.sonoswidget.data

/**
 * Small, platform-independent ownership ledger for asynchronous widget writes.
 *
 * A poll can take longer than a tap.  Callers snapshot the fields they read,
 * and may write a field only when nobody has changed it since that snapshot.
 * An optimistic command claims the field; its failure is allowed to undo the
 * value only while it still owns that claim.
 */
internal class WidgetStateRevisionPolicy {
    enum class Field { PLAYBACK, VOLUME, MEDIA, ENRICHMENT, ARTWORK, ROOM, STATUS }

    data class Snapshot internal constructor(internal val revisions: Map<Field, Long>)
    data class Ownership internal constructor(
        internal val operationId: Long,
        internal val revisions: Map<Field, Long>
    )

    private var nextRevision = 0L
    private var nextOperation = 0L
    private val revisions = Field.entries.associateWith { 0L }.toMutableMap()

    fun snapshot(): Snapshot = Snapshot(revisions.toMap())

    /** Records an ordinary state write and invalidates older snapshots. */
    fun record(fields: Set<Field>) {
        val revision = ++nextRevision
        fields.forEach { revisions[it] = revision }
    }

    /** Claims fields for one optimistic command. */
    fun claim(fields: Set<Field>): Ownership {
        val revision = ++nextRevision
        fields.forEach { revisions[it] = revision }
        return Ownership(++nextOperation, fields.associateWith { revision })
    }

    fun unchangedSince(snapshot: Snapshot, field: Field): Boolean =
        revisions[field] == snapshot.revisions[field]

    fun stillOwns(ownership: Ownership, field: Field): Boolean =
        revisions[field] == ownership.revisions[field]
}
