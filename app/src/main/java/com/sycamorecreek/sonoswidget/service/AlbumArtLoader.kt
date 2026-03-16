package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import coil.ImageLoader
import coil.disk.DiskCache
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Loads album art from Sonos speakers using Coil's raw ImageLoader API.
 *
 * Handles:
 *   - Resolving relative Sonos URLs (e.g., "/getaa?s=1&u=...") against the speaker IP
 *   - Fetching via Coil with memory + disk caching
 *   - Saving the latest art to internal storage for the Glance widget to read
 *   - Skipping re-fetch when the art URL hasn't changed
 *
 * The Glance widget cannot use Coil Compose integration — it reads bitmaps
 * from internal storage via [loadFromDisk].
 *
 * PRD Note: Uses Coil raw ImageLoader API only, NOT Compose integration.
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object AlbumArtLoader {

    private const val TAG = "AlbumArtLoader"
    private const val ART_FILENAME = "album_art_current.webp"
    private const val ART_SIZE_PX = 240 // Target size for widget art (80dp × 3x density)
    private const val DISK_CACHE_DIR = "image_cache"
    private const val DISK_CACHE_MAX_SIZE = 50L * 1024 * 1024 // 50 MB per PRD

    // Track the last URL to avoid redundant fetches
    private var lastArtUrl: String? = null
    private var cachedBitmap: Bitmap? = null

    private var imageLoader: ImageLoader? = null

    private fun getImageLoader(context: Context): ImageLoader {
        return imageLoader ?: ImageLoader.Builder(context.applicationContext)
            .crossfade(false)
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, DISK_CACHE_DIR))
                    .maxSizeBytes(DISK_CACHE_MAX_SIZE)
                    .build()
            }
            .build()
            .also { imageLoader = it }
    }

    /**
     * Loads album art from a URL, caches it, and saves to disk.
     *
     * Returns the bitmap if successful, or null if the URL is blank/fetch failed.
     * Skips the network call if the URL matches the previously loaded art.
     *
     * @param context Application context
     * @param artUrl Album art URL from DIDL-Lite metadata (may be relative)
     * @param speakerIp Speaker IP for resolving relative URLs
     * @return The loaded bitmap, or null
     */
    suspend fun loadAndCache(
        context: Context,
        artUrl: String?,
        speakerIp: String?
    ): Bitmap? {
        if (artUrl.isNullOrBlank()) {
            clearCache(context)
            return null
        }

        // Skip if same URL as last time
        if (artUrl == lastArtUrl && cachedBitmap != null) {
            return cachedBitmap
        }

        val resolvedUrl = resolveUrl(artUrl, speakerIp)
        Log.d(TAG, "Loading album art: $resolvedUrl")

        val bitmap = fetchBitmap(context, resolvedUrl)
        if (bitmap != null) {
            cachedBitmap = bitmap
            lastArtUrl = artUrl
            saveToDisk(context, bitmap)
            Log.d(TAG, "Album art cached: ${bitmap.width}x${bitmap.height}")
        } else {
            Log.w(TAG, "Failed to load album art from $resolvedUrl")
        }

        return bitmap
    }

    /**
     * Loads the most recently cached album art from disk.
     *
     * Called by SonosWidget.provideGlance() to display art without
     * making a network call in the widget rendering path.
     *
     * @return The cached bitmap, or null if no art is cached
     */
    fun loadFromDisk(context: Context): Bitmap? {
        val file = artFile(context)
        if (!file.exists()) return null

        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cached album art", e)
            null
        }
    }

    /**
     * Clears the cached album art (e.g., when switching tracks to one without art).
     */
    fun clearCache(context: Context) {
        lastArtUrl = null
        cachedBitmap = null
        val file = artFile(context)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Returns the total size of the disk image cache in bytes.
     * Includes both Coil's disk cache directory and the current art file.
     */
    fun getDiskCacheSize(context: Context): Long {
        var total = 0L
        val cacheDir = File(context.cacheDir, DISK_CACHE_DIR)
        if (cacheDir.exists()) {
            total += cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        }
        val file = artFile(context)
        if (file.exists()) {
            total += file.length()
        }
        return total
    }

    /**
     * Clears all disk caches: Coil's image cache directory and the current art file.
     * Also resets in-memory cached bitmap and URL tracking.
     */
    fun clearAllDiskCache(context: Context) {
        // Clear in-memory state
        lastArtUrl = null
        cachedBitmap = null

        // Clear Coil disk cache
        imageLoader?.diskCache?.clear()
        val cacheDir = File(context.cacheDir, DISK_CACHE_DIR)
        if (cacheDir.exists()) {
            cacheDir.deleteRecursively()
        }

        // Clear current art file
        val file = artFile(context)
        if (file.exists()) {
            file.delete()
        }

        Log.d(TAG, "All disk caches cleared")
    }

    /**
     * Resolves a potentially relative Sonos album art URL.
     *
     * Sonos speakers may return art URLs in several forms:
     *   - Absolute HTTP:  "http://192.168.1.10:1400/getaa?s=1&u=..."
     *   - Absolute HTTPS: "https://i.scdn.co/image/..."  (Spotify, etc.)
     *   - Relative path:  "/getaa?s=1&u=..."
     *
     * Relative paths are resolved against http://{speakerIp}:1400.
     */
    private fun resolveUrl(artUrl: String, speakerIp: String?): String {
        // Already absolute
        if (artUrl.startsWith("http://") || artUrl.startsWith("https://")) {
            return artUrl
        }

        // Relative — prepend speaker base URL
        if (speakerIp != null && artUrl.startsWith("/")) {
            return "http://$speakerIp:1400$artUrl"
        }

        // Fallback: return as-is and let Coil handle it
        return artUrl
    }

    /**
     * Fetches a bitmap from a URL using Coil's ImageLoader.
     * Scales to widget-appropriate dimensions.
     */
    private suspend fun fetchBitmap(context: Context, url: String): Bitmap? {
        return try {
            val loader = getImageLoader(context)
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(Size(ART_SIZE_PX, ART_SIZE_PX))
                .allowHardware(false) // Need software bitmap for Palette + file save
                .build()

            val result = loader.execute(request)
            if (result is SuccessResult) {
                (result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Coil fetch failed for $url", e)
            null
        }
    }

    /**
     * Saves the bitmap to internal storage as WebP for the Glance widget to read.
     */
    private suspend fun saveToDisk(context: Context, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        try {
            val file = artFile(context)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save album art to disk", e)
        }
    }

    private fun artFile(context: Context): File =
        File(context.filesDir, ART_FILENAME)
}
