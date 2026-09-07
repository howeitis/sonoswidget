package com.sycamorecreek.sonoswidget.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeIntentPolicyTest {

    @Test
    fun `five rapid plus five intents reach seventy five before network dispatch`() {
        var desired = 50
        repeat(5) { desired = nextVolumeIntent(desired, 5) }
        assertEquals(75, desired)
    }

    @Test
    fun `volume intent clamps at both endpoints`() {
        assertEquals(0, nextVolumeIntent(2, -10))
        assertEquals(100, nextVolumeIntent(98, 10))
    }

    @Test
    fun `queued volume cannot cross a room change`() {
        assertTrue(isVolumeIntentCurrent("kitchen", "kitchen"))
        assertFalse(isVolumeIntentCurrent("kitchen", "office"))
    }
}
