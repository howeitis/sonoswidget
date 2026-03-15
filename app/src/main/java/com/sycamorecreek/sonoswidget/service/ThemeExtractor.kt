package com.sycamorecreek.sonoswidget.service

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.palette.graphics.Palette
import com.sycamorecreek.sonoswidget.widget.WidgetColorPalette

/**
 * Extracts a dynamic color palette from album art using AndroidX Palette.
 *
 * Maps Palette swatches to [WidgetColorPalette] fields:
 *   - background:     Dark Muted → Muted → dark default
 *   - textPrimary:    Light Vibrant body → white
 *   - textSecondary:  Muted body → light gray default
 *   - accent:         Vibrant → Light Vibrant → purple default
 *   - chipBackground: blended mid-tone from background + accent
 *
 * All colors are output as hex strings (#RRGGBB or #AARRGGBB) matching
 * the [WidgetColorPalette] field format.
 *
 * Returns [WidgetColorPalette] defaults when no meaningful palette can be
 * extracted (e.g., monochrome artwork or very small bitmaps).
 *
 * PRD Note: Uses AndroidX Palette API for color extraction.
 */
object ThemeExtractor {

    private const val TAG = "ThemeExtractor"

    /** Default palette — matches [WidgetColorPalette] constructor defaults. */
    private val DEFAULT_PALETTE = WidgetColorPalette()

    // Default color ints for fallback
    private const val DEFAULT_BG = 0xFF1E1E2E.toInt()
    private const val DEFAULT_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    private const val DEFAULT_TEXT_SECONDARY = 0xFFB0B0C0.toInt()
    private const val DEFAULT_ACCENT = 0xFF6C63FF.toInt()
    private const val DEFAULT_CHIP_BG = 0xFF3E3E5E.toInt()

    /**
     * Extracts a color palette from the given album art bitmap.
     *
     * Uses synchronous palette generation (called from a coroutine context
     * in the service layer, so blocking is acceptable).
     *
     * @param bitmap Album art bitmap (should be software, not hardware)
     * @return Extracted palette, or defaults if extraction fails
     */
    fun extractFromBitmap(bitmap: Bitmap): WidgetColorPalette {
        return try {
            val palette = Palette.from(bitmap)
                .maximumColorCount(16)
                .generate()

            mapPaletteToColors(palette)
        } catch (e: Exception) {
            Log.e(TAG, "Palette extraction failed", e)
            DEFAULT_PALETTE
        }
    }

    /**
     * Maps AndroidX Palette swatches to our widget color scheme.
     *
     * Priority order for each slot ensures visually appealing results
     * even when some swatch types are absent.
     */
    private fun mapPaletteToColors(palette: Palette): WidgetColorPalette {
        // Background: prefer dark tones for the widget's dark theme
        val bgSwatch = palette.darkMutedSwatch
            ?: palette.mutedSwatch
            ?: palette.darkVibrantSwatch
        val bgColor = if (bgSwatch != null) {
            darkenColor(bgSwatch.rgb, 0.7f)
        } else {
            DEFAULT_BG
        }

        // Accent: prefer vibrant colors for controls and highlights
        val accentSwatch = palette.vibrantSwatch
            ?: palette.lightVibrantSwatch
            ?: palette.mutedSwatch
        val accentColor = accentSwatch?.rgb ?: DEFAULT_ACCENT

        // Text primary: ensure high contrast against the dark background
        val textPrimary = if (bgSwatch != null) {
            ensureContrast(bgSwatch.bodyTextColor, bgColor, DEFAULT_TEXT_PRIMARY)
        } else {
            DEFAULT_TEXT_PRIMARY
        }

        // Text secondary: lighter, less prominent text
        val textSecondary = if (bgSwatch != null) {
            val candidate = bgSwatch.titleTextColor
            ensureContrast(candidate, bgColor, DEFAULT_TEXT_SECONDARY)
        } else {
            DEFAULT_TEXT_SECONDARY
        }

        // Chip background: blend between bg and accent for a subtle tint
        val chipBg = blendColors(bgColor, accentColor, 0.25f)

        return WidgetColorPalette(
            background = colorToHex(bgColor),
            textPrimary = colorToHex(textPrimary),
            textSecondary = colorToHex(textSecondary),
            accent = colorToHex(accentColor),
            chipBackground = colorToHex(chipBg)
        )
    }

    /**
     * Darkens a color by the given factor (0.0 = black, 1.0 = unchanged).
     * Keeps the hue/saturation, reduces brightness.
     */
    private fun darkenColor(color: Int, factor: Float): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[2] = (hsv[2] * factor).coerceIn(0f, 1f)
        // Clamp saturation up slightly to keep color identity in dark tones
        hsv[1] = (hsv[1] * 1.1f).coerceIn(0f, 1f)
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    /**
     * Ensures the foreground color has sufficient contrast against the background.
     * Returns [fallback] if the contrast ratio is too low (< 3.0:1).
     *
     * Uses WCAG relative luminance formula.
     */
    private fun ensureContrast(fg: Int, bg: Int, fallback: Int): Int {
        val fgLum = relativeLuminance(fg)
        val bgLum = relativeLuminance(bg)
        val lighter = maxOf(fgLum, bgLum)
        val darker = minOf(fgLum, bgLum)
        val ratio = (lighter + 0.05) / (darker + 0.05)

        return if (ratio >= 3.0) fg else fallback
    }

    /**
     * Calculates relative luminance per WCAG 2.0.
     */
    private fun relativeLuminance(color: Int): Double {
        fun linearize(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        val r = linearize(Color.red(color))
        val g = linearize(Color.green(color))
        val b = linearize(Color.blue(color))
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /**
     * Blends two colors. [ratio] 0.0 = 100% color1, 1.0 = 100% color2.
     */
    private fun blendColors(color1: Int, color2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(color1) * inv + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inv + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inv + Color.blue(color2) * ratio).toInt()
        val a = (Color.alpha(color1) * inv + Color.alpha(color2) * ratio).toInt()
        return Color.argb(a, r, g, b)
    }

    /**
     * Converts an ARGB int to a hex string (#RRGGBB or #AARRGGBB).
     */
    private fun colorToHex(color: Int): String {
        val a = Color.alpha(color)
        return if (a == 255) {
            String.format("#%06X", color and 0x00FFFFFF)
        } else {
            String.format("#%08X", color)
        }
    }
}
