package com.sycamorecreek.sonoswidget.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTargetGuardTest {

    @Test
    fun `draft may apply only to its original room and generation`() {
        assertTrue(isGroupingTargetCurrent("kitchen", "kitchen", 12L, 12L))
        assertFalse(isGroupingTargetCurrent("kitchen", "office", 12L, 13L))
    }

    @Test
    fun `same room with a newer switch generation rejects an old draft`() {
        assertFalse(isGroupingTargetCurrent("kitchen", "kitchen", 12L, 13L))
    }
}
