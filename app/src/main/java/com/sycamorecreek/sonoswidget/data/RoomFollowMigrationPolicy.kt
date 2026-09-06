package com.sycamorecreek.sonoswidget.data

/**
 * Pure decision logic for the one-time room-follow preference migration.
 * Keeping it independent of DataStore lets us lock down legacy behaviour with
 * JVM tests while [SonosPreferences] retains the atomic persistence step.
 */
internal object RoomFollowMigrationPolicy {
    const val CURRENT_VERSION = 1

    data class Snapshot(
        val version: Int,
        val explicitMode: SonosPreferences.RoomFollowMode?,
        val explicitTarget: SonosPreferences.DefaultZone?,
        val legacyDefault: SonosPreferences.DefaultZone?,
        val hasLegacyEvidence: Boolean
    )

    data class Decision(
        val alreadyMigrated: Boolean,
        val mode: SonosPreferences.RoomFollowMode,
        val target: SonosPreferences.DefaultZone?
    )

    fun resolve(snapshot: Snapshot): Decision {
        val alreadyMigrated = snapshot.explicitMode != null && snapshot.version >= CURRENT_VERSION
        if (alreadyMigrated) {
            return Decision(
                alreadyMigrated = true,
                mode = snapshot.explicitMode,
                target = snapshot.explicitTarget
            )
        }

        val mode = snapshot.explicitMode ?: when {
            snapshot.legacyDefault != null -> SonosPreferences.RoomFollowMode.STAY_WITH_ROOM
            snapshot.hasLegacyEvidence -> SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC
            else -> SonosPreferences.RoomFollowMode.STAY_WITH_ROOM
        }
        return Decision(
            alreadyMigrated = false,
            mode = mode,
            target = snapshot.explicitTarget ?: snapshot.legacyDefault
        )
    }
}
