package com.sycamorecreek.sonoswidget.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetLayoutPolicyTest {

    @Test
    fun `offered widget sizes select their intended buckets`() {
        assertEquals(
            WidgetLayoutPolicy.Bucket.MINI,
            WidgetLayoutPolicy.bucketFor(WidgetLayoutPolicy.MINI_WIDTH_DP, WidgetLayoutPolicy.MINI_HEIGHT_DP)
        )
        assertEquals(
            WidgetLayoutPolicy.Bucket.COMPACT,
            WidgetLayoutPolicy.bucketFor(WidgetLayoutPolicy.COMPACT_WIDTH_DP, WidgetLayoutPolicy.COMPACT_HEIGHT_DP)
        )
        assertEquals(
            WidgetLayoutPolicy.Bucket.EXPANDED,
            WidgetLayoutPolicy.bucketFor(WidgetLayoutPolicy.EXPANDED_WIDTH_DP, WidgetLayoutPolicy.EXPANDED_HEIGHT_DP)
        )
    }

    @Test
    fun `narrow or short hosts fall back to a fitting layout`() {
        assertEquals(WidgetLayoutPolicy.Bucket.MINI, WidgetLayoutPolicy.bucketFor(240, 340))
        assertEquals(WidgetLayoutPolicy.Bucket.MINI, WidgetLayoutPolicy.bucketFor(319, 180))
        assertEquals(WidgetLayoutPolicy.Bucket.COMPACT, WidgetLayoutPolicy.bucketFor(400, 459))
        assertEquals(WidgetLayoutPolicy.Bucket.MINI, WidgetLayoutPolicy.bucketFor(180, 48))
    }
}
