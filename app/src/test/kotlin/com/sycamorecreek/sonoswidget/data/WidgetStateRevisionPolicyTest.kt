package com.sycamorecreek.sonoswidget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetStateRevisionPolicyTest {
    @Test fun `delayed poll cannot replace newer playback intent`() {
        val policy = WidgetStateRevisionPolicy()
        val poll = policy.snapshot()
        policy.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))

        assertFalse(policy.unchangedSince(poll, WidgetStateRevisionPolicy.Field.PLAYBACK))
    }

    @Test fun `old failed operation cannot roll back newer success`() {
        val policy = WidgetStateRevisionPolicy()
        val oldPlay = policy.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))
        policy.claim(setOf(WidgetStateRevisionPolicy.Field.PLAYBACK))

        assertFalse(policy.stillOwns(oldPlay, WidgetStateRevisionPolicy.Field.PLAYBACK))
    }

    @Test fun `media enrichment remains valid when only volume changes`() {
        val policy = WidgetStateRevisionPolicy()
        val enrichment = policy.snapshot()
        policy.claim(setOf(WidgetStateRevisionPolicy.Field.VOLUME))

        assertTrue(policy.unchangedSince(enrichment, WidgetStateRevisionPolicy.Field.MEDIA))
        assertFalse(policy.unchangedSince(enrichment, WidgetStateRevisionPolicy.Field.VOLUME))
    }

    @Test fun `room transition invalidates room scoped snapshot`() {
        val policy = WidgetStateRevisionPolicy()
        val roomA = policy.snapshot()
        policy.record(setOf(WidgetStateRevisionPolicy.Field.ROOM))

        assertFalse(policy.unchangedSince(roomA, WidgetStateRevisionPolicy.Field.ROOM))
    }
}
