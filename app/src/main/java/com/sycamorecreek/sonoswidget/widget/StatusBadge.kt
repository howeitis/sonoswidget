package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

/**
 * Album art with an optional semi-transparent pill badge overlay.
 *
 * The badge displays status text ("Reconnecting…", "Rate Limited", "Updating…",
 * "Offline") as a small pill at the bottom of the album art. The pill uses
 * a semi-transparent dark background for readability against any album art.
 *
 * Used by all widget layouts. The badge type is resolved from
 * [SonosWidgetState.activeBadge].
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun AlbumArtWithBadge(
    albumArt: Bitmap?,
    state: SonosWidgetState,
    size: Dp,
    hasTrack: Boolean
) {
    val cornerRadius = if (size >= 100.dp) 18.dp else 14.dp
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(cornerRadius)
            .background(WidgetTheme.Glass)
            .clickable(actionRunCallback<OpenSonosAppAction>()),
        contentAlignment = Alignment.Center
    ) {
        // Album art or placeholder
        if (state.currentSource == "TV") {
            ThemedIcon(
                resId = com.sycamorecreek.sonoswidget.R.drawable.ic_tv,
                size = if (size >= 100.dp) 56.dp else 36.dp,
                tint = WidgetTheme.TextSecondary,
                contentDescription = "TV — tap to open Sonos"
            )
        } else if (albumArt != null) {
            Image(
                provider = ImageProvider(albumArt),
                contentDescription = state.currentTrack.album.ifBlank {
                    "Album art — tap to open Sonos"
                },
                modifier = GlanceModifier
                    .size(size)
                    .cornerRadius(cornerRadius),
                contentScale = ContentScale.Crop
            )
        } else {
            val placeholderLabel = if (hasTrack) "Music note — tap to open Sonos"
                else "Speaker — tap to open Sonos"
            ThemedIcon(
                resId = if (hasTrack) com.sycamorecreek.sonoswidget.R.drawable.ic_music_note
                    else com.sycamorecreek.sonoswidget.R.drawable.ic_speaker_device,
                size = if (size >= 100.dp) 44.dp else 30.dp,
                tint = WidgetTheme.TextTertiary,
                contentDescription = placeholderLabel
            )
        }

        // Pill badge overlay (bottom-center of album art)
        val badge = state.activeBadge
        if (badge != StatusBadgeType.NONE) {
            Box(
                modifier = GlanceModifier.size(size).padding(6.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                StatusPillBadge(badge)
            }
        }
    }
}

/**
 * Semi-transparent pill badge showing a status label.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun StatusPillBadge(badge: StatusBadgeType) {
    val text = when (badge) {
        StatusBadgeType.RECONNECTING -> "Reconnecting…"
        StatusBadgeType.RATE_LIMITED -> "Rate Limited"
        StatusBadgeType.UPDATING -> "Updating…"
        StatusBadgeType.OFFLINE -> "Offline"
        StatusBadgeType.NONE -> return
    }

    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(10.dp)
            .background(Color(0xCC000000.toInt()))
            .padding(horizontal = 6.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextPrimary),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

/**
 * Inline error banner displayed at the top of the widget.
 * Auto-dismissed after 5 seconds by the service (via errorMessage = null).
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun InlineErrorBanner(
    message: String,
    action: androidx.glance.action.Action? = null
) {
    val baseModifier = GlanceModifier
        .fillMaxWidth()
        .cornerRadius(12.dp)
        .background(WidgetTheme.ErrorSurface)
        .padding(horizontal = 12.dp, vertical = 6.dp)
    Box(
        modifier = if (action == null) baseModifier else baseModifier.clickable(action),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (action == null) message else "$message. Tap to check status.",
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextPrimary),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            ),
            maxLines = 2
        )
    }
}

/**
 * One-time inline hint for local network permission denied.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun PermissionHintBanner() {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .cornerRadius(12.dp)
            .background(WidgetTheme.Glass)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = "Local control unavailable — grant network permission in Settings for faster response",
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextSecondary),
                fontSize = 10.sp
            ),
            maxLines = 2
        )
    }
}

// ──────────────────────────────────────────────
// Static Progress Bar
// ──────────────────────────────────────────────

/**
 * Non-animated progress bar that shows the current playback position.
 *
 * Per PRD Engineering Decision E2: the progress bar updates only on user
 * interaction and track changes — no continuous animation. It renders as
 * a static snapshot of the current elapsed/duration ratio.
 *
 * Rendered as a pre-drawn [Bitmap] displayed via Glance [Image], because
 * Glance does not support fractional width modifiers or weighted Row children.
 * The bitmap is created at the device's native density for crisp rendering
 * and stretched to fill the available width via [ContentScale.FillBounds].
 *
 * @param elapsedMs Current playback position in milliseconds.
 * @param durationMs Total track duration in milliseconds.
 * @param barHeight Height of the progress bar (default 4dp).
 * @param showTimeLabels Whether to show elapsed/remaining time labels.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun StaticProgressBar(
    elapsedMs: Long,
    durationMs: Long,
    fillColor: Color = WidgetTheme.TextPrimary,
    trackColor: Color = WidgetTheme.ProgressTrack,
    barHeight: Dp = 4.dp,
    showTimeLabels: Boolean = false
) {
    // Guard: don't render if no valid duration
    if (durationMs <= 0L) return

    val context = LocalContext.current
    val density = context.resources.displayMetrics.density
    val fraction = (elapsedMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    // Pre-render the progress bar as a Bitmap for precise fractional fill
    val heightPx = (barHeight.value * density).toInt().coerceAtLeast(1)
    val barBitmap = renderProgressBarBitmap(
        widthPx = (600 * density).toInt(), // wide enough for crisp rendering
        heightPx = heightPx,
        fraction = fraction,
        fillColorArgb = fillColor.toArgb(),
        trackColorArgb = trackColor.toArgb()
    )

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        Image(
            provider = ImageProvider(barBitmap),
            contentDescription = "Track progress: ${(fraction * 100).toInt()}%",
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(barHeight),
            contentScale = ContentScale.FillBounds
        )

        // Time labels (elapsed / remaining)
        if (showTimeLabels) {
            Spacer(modifier = GlanceModifier.height(3.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = formatDuration(elapsedMs),
                    style = TextStyle(
                        color = ColorProvider(WidgetTheme.TextTertiary),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "-${formatDuration(durationMs - elapsedMs)}",
                    style = TextStyle(
                        color = ColorProvider(WidgetTheme.TextTertiary),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }
    }
}

/**
 * Pre-renders a progress bar as a [Bitmap] using Canvas drawing.
 *
 * The bar consists of a rounded-rect track background with a
 * proportionally-filled rounded-rect foreground overlay.
 */
private fun renderProgressBarBitmap(
    widthPx: Int,
    heightPx: Int,
    fraction: Float,
    fillColorArgb: Int,
    trackColorArgb: Int
): Bitmap {
    val bitmap = Bitmap.createBitmap(
        widthPx.coerceAtLeast(1),
        heightPx.coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    val radius = heightPx / 2f

    // Draw track background
    paint.color = trackColorArgb
    canvas.drawRoundRect(
        RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat()),
        radius, radius, paint
    )

    // Draw filled portion
    val fillWidth = widthPx * fraction
    if (fillWidth > 0f) {
        paint.color = fillColorArgb
        canvas.drawRoundRect(
            RectF(0f, 0f, fillWidth, heightPx.toFloat()),
            radius, radius, paint
        )
    }

    return bitmap
}

/**
 * Formats a duration in milliseconds to a human-readable string.
 *
 * - Under 1 hour: "m:ss" (e.g., "3:42")
 * - 1 hour or more: "h:mm:ss" (e.g., "1:05:30")
 */
fun formatDuration(ms: Long): String {
    if (ms < 0) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}
