package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.util.Log
import com.sycamorecreek.sonoswidget.widget.WidgetColorPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the immersive widget background: the current album art blurred,
 * saturation-boosted, and dimmed under a vertical scrim so white text and
 * glass surfaces stay legible on top of any artwork.
 *
 * Rendering happens in the service layer alongside palette extraction and is
 * keyed by the art URL, so it runs once per track change — not per poll.
 * The result is cached to internal storage; the Glance widget reads it back
 * via [loadFromDisk] and crops it to each size bucket with ContentScale.Crop.
 *
 * When no artwork is available, [fallback] produces a dark gradient built
 * from the extracted palette (memory-cached, cheap enough for the widget
 * render path).
 */
object WidgetBackgroundRenderer {

    private const val TAG = "WidgetBgRenderer"
    private const val BG_FILENAME = "widget_bg_current.webp"

    // Single output canvas; every widget size crops it via ContentScale.Crop.
    private const val BG_WIDTH = 600
    private const val BG_HEIGHT = 500

    // Blur is approximated by heavy downscale + box blur + bilinear upscale —
    // no RenderScript needed and it runs in ~1ms at this resolution.
    private const val SMALL_WIDTH = 40
    private const val SMALL_HEIGHT = 34
    private const val BLUR_RADIUS = 3
    private const val BLUR_PASSES = 2
    private const val SATURATION_BOOST = 1.6f

    // Vertical scrim: lighter at the top (art shows through), darker at the
    // bottom where chips and the queue live.
    private const val SCRIM_TOP = 0x66000000
    private const val SCRIM_BOTTOM = 0xB8000000.toInt()

    private var lastRenderedUrl: String? = null

    private var diskCacheStamp: Long = 0
    private var diskCacheBitmap: Bitmap? = null

    private var fallbackKey: String? = null
    private var fallbackBitmap: Bitmap? = null

    /**
     * Renders and persists the background for [art]. Skips work when the art
     * URL matches the previously rendered one and the file still exists.
     */
    suspend fun renderAndCache(context: Context, art: Bitmap, artUrl: String?) =
        withContext(Dispatchers.Default) {
            val file = bgFile(context)
            if (artUrl != null && artUrl == lastRenderedUrl && file.exists()) return@withContext

            try {
                val bg = render(art)
                FileOutputStream(file).use { out ->
                    bg.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, out)
                }
                lastRenderedUrl = artUrl
                diskCacheBitmap = bg
                diskCacheStamp = file.lastModified()
                Log.d(TAG, "Immersive background rendered for $artUrl")
            } catch (e: Exception) {
                Log.e(TAG, "Background render failed", e)
            }
        }

    /**
     * Loads the cached background from disk. Memory-cached against the file's
     * modification stamp so repeated widget recompositions don't re-decode.
     */
    fun loadFromDisk(context: Context): Bitmap? {
        val file = bgFile(context)
        if (!file.exists()) return null

        val stamp = file.lastModified()
        diskCacheBitmap?.let { if (stamp == diskCacheStamp) return it }

        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.also {
                diskCacheBitmap = it
                diskCacheStamp = stamp
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached background", e)
            null
        }
    }

    /** Clears the cached background (e.g., when the track has no artwork). */
    fun clear(context: Context) {
        lastRenderedUrl = null
        diskCacheBitmap = null
        diskCacheStamp = 0
        val file = bgFile(context)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Dark gradient background derived from the palette, used when no album
     * art is available (disconnected, TV source, radio without art).
     */
    fun fallback(palette: WidgetColorPalette): Bitmap {
        val key = palette.background + palette.accent
        fallbackBitmap?.let { if (key == fallbackKey) return it }

        val base = parseColorOr(palette.background, 0xFF1E1E2E.toInt())
        val accent = parseColorOr(palette.accent, 0xFF6C63FF.toInt())

        val bitmap = Bitmap.createBitmap(BG_WIDTH, BG_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Vertical base gradient: palette background fading to near-black.
        paint.shader = LinearGradient(
            0f, 0f, 0f, BG_HEIGHT.toFloat(),
            blend(base, Color.BLACK, 0.25f),
            blend(base, Color.BLACK, 0.65f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, BG_WIDTH.toFloat(), BG_HEIGHT.toFloat(), paint)

        // Soft accent glow in the upper-left for depth.
        paint.shader = RadialGradient(
            BG_WIDTH * 0.18f, BG_HEIGHT * 0.05f, BG_WIDTH * 0.85f,
            (accent and 0x00FFFFFF) or 0x40000000,
            0x00000000,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, BG_WIDTH.toFloat(), BG_HEIGHT.toFloat(), paint)

        fallbackKey = key
        fallbackBitmap = bitmap
        return bitmap
    }

    // ──────────────────────────────────────────────
    // Rendering pipeline
    // ──────────────────────────────────────────────

    private fun render(art: Bitmap): Bitmap {
        // 1. Downscale to a tiny canvas, center-cropped to the output aspect
        //    ratio, with a saturation boost for richness.
        val small = Bitmap.createBitmap(SMALL_WIDTH, SMALL_HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(small)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(SATURATION_BOOST) }
            )
        }
        canvas.drawBitmap(art, centerCropSource(art, SMALL_WIDTH, SMALL_HEIGHT),
            Rect(0, 0, SMALL_WIDTH, SMALL_HEIGHT), paint)

        // 2. Box blur at the tiny resolution to remove bilinear artifacts.
        repeat(BLUR_PASSES) { boxBlur(small, BLUR_RADIUS) }

        // 3. Bilinear upscale to the output size = smooth, heavy blur.
        val bg = Bitmap.createScaledBitmap(small, BG_WIDTH, BG_HEIGHT, true)
        val result = if (bg.isMutable) bg else bg.copy(Bitmap.Config.ARGB_8888, true)

        // 4. Vertical scrim for text legibility.
        val scrimPaint = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, BG_HEIGHT.toFloat(),
                SCRIM_TOP, SCRIM_BOTTOM,
                Shader.TileMode.CLAMP
            )
        }
        Canvas(result).drawRect(0f, 0f, BG_WIDTH.toFloat(), BG_HEIGHT.toFloat(), scrimPaint)

        return result
    }

    /** Source rect that center-crops [src] to the aspect ratio of dstW:dstH. */
    private fun centerCropSource(src: Bitmap, dstW: Int, dstH: Int): Rect {
        val dstRatio = dstW.toFloat() / dstH
        val srcRatio = src.width.toFloat() / src.height
        return if (srcRatio > dstRatio) {
            val cropW = (src.height * dstRatio).toInt()
            val x = (src.width - cropW) / 2
            Rect(x, 0, x + cropW, src.height)
        } else {
            val cropH = (src.width / dstRatio).toInt()
            val y = (src.height - cropH) / 2
            Rect(0, y, src.width, y + cropH)
        }
    }

    /** In-place separable box blur (horizontal + vertical pass). */
    private fun boxBlur(bitmap: Bitmap, radius: Int) {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val temp = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dx in -radius..radius) {
                    val nx = (x + dx).coerceIn(0, w - 1)
                    val c = pixels[row + nx]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                temp[row + x] = (0xFF shl 24) or ((r / count) shl 16) or
                    ((g / count) shl 8) or (b / count)
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            for (y in 0 until h) {
                var r = 0; var g = 0; var b = 0; var count = 0
                for (dy in -radius..radius) {
                    val ny = (y + dy).coerceIn(0, h - 1)
                    val c = temp[ny * w + x]
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                    count++
                }
                pixels[y * w + x] = (0xFF shl 24) or ((r / count) shl 16) or
                    ((g / count) shl 8) or (b / count)
            }
        }

        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    private fun blend(color1: Int, color2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(color1) * inv + Color.red(color2) * ratio).toInt()
        val g = (Color.green(color1) * inv + Color.green(color2) * ratio).toInt()
        val b = (Color.blue(color1) * inv + Color.blue(color2) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun parseColorOr(hex: String, fallback: Int): Int = try {
        Color.parseColor(hex)
    } catch (_: Exception) {
        fallback
    }

    private fun bgFile(context: Context): File = File(context.filesDir, BG_FILENAME)
}
