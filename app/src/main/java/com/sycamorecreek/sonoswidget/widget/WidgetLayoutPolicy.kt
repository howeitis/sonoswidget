package com.sycamorecreek.sonoswidget.widget

/**
 * Chooses the largest layout that can fit both host dimensions. Kept pure so
 * offered-size boundaries and defensive fallback behavior are regression-testable.
 */
object WidgetLayoutPolicy {
    enum class Bucket { MINI, COMPACT, EXPANDED }

    const val MINI_WIDTH_DP = 240
    const val MINI_HEIGHT_DP = 80
    const val COMPACT_WIDTH_DP = 320
    const val COMPACT_HEIGHT_DP = 180
    const val EXPANDED_WIDTH_DP = 400
    const val EXPANDED_HEIGHT_DP = 340

    fun bucketFor(widthDp: Int, heightDp: Int): Bucket = when {
        widthDp >= EXPANDED_WIDTH_DP && heightDp >= EXPANDED_HEIGHT_DP -> Bucket.EXPANDED
        widthDp >= COMPACT_WIDTH_DP && heightDp >= COMPACT_HEIGHT_DP -> Bucket.COMPACT
        else -> Bucket.MINI
    }
}
