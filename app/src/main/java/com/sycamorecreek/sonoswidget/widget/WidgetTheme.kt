package com.sycamorecreek.sonoswidget.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Design tokens for the immersive "on-art" widget theme.
 *
 * The widget background is always a dark surface — either a blurred, dimmed
 * rendition of the current album art or a dark gradient fallback — so every
 * foreground token is defined as white-with-alpha ("glass") to guarantee
 * contrast on any artwork. The extracted palette accent is reserved for
 * active states (shuffle on, repeat on, grouped speakers).
 */
object WidgetTheme {
    val TextPrimary = Color(0xFFFFFFFF)
    val TextSecondary = Color(0xB8FFFFFF)   // 72% white
    val TextTertiary = Color(0x80FFFFFF)    // 50% white
    val Disabled = Color(0x4DFFFFFF)        // 30% white

    val Glass = Color(0x26FFFFFF)           // 15% white — chips, pills, cards
    val GlassStrong = Color(0x40FFFFFF)     // 25% white — emphasized surfaces
    val ProgressTrack = Color(0x40FFFFFF)   // 25% white

    val PlayButtonBg = Color(0xFFFFFFFF)
    val PlayButtonFg = Color(0xFF0A0A0F)

    val ErrorSurface = Color(0xD9B3261E)
    val FallbackBg = Color(0xFF14141B)

    /**
     * Resolves the accent from the extracted palette, brightening dark or
     * oversaturated accents so they stay legible on the dimmed art background.
     */
    fun accent(palette: WidgetColorPalette): Color {
        val base = parseHexColor(palette.accent, Color(0xFF9B8CFF))
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(base.toArgb(), hsv)
        if (hsv[2] < 0.78f) hsv[2] = 0.78f
        if (hsv[1] > 0.72f) hsv[1] = 0.72f
        return Color(android.graphics.Color.HSVToColor(hsv))
    }

    /** Translucent accent used as the fill for active/selected glass chips. */
    fun accentGlass(palette: WidgetColorPalette): Color =
        accent(palette).copy(alpha = 0.38f)
}

/**
 * Parses a hex color string (#RRGGBB or #AARRGGBB) to a Compose Color.
 * Returns [fallback] on any parse error.
 */
internal fun parseHexColor(hex: String, fallback: Color): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        fallback
    }
}

/**
 * Returns the drawable resource for the active connection mode indicator,
 * or null when no indicator should be shown (local discovery is the norm).
 */
internal fun connectionModeIconRes(mode: ConnectionMode): Int? = when (mode) {
    ConnectionMode.LOCAL_MANUAL_IP -> com.sycamorecreek.sonoswidget.R.drawable.ic_link
    ConnectionMode.CLOUD -> com.sycamorecreek.sonoswidget.R.drawable.ic_cloud
    else -> null
}
