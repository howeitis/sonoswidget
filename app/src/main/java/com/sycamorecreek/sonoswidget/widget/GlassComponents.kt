package com.sycamorecreek.sonoswidget.widget

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceComposable
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.sycamorecreek.sonoswidget.R
import com.sycamorecreek.sonoswidget.service.WidgetBackgroundRenderer

/**
 * Shared building blocks for the immersive widget design system.
 *
 * Everything renders as "glass on art": white-alpha surfaces layered over
 * the blurred album-art background provided by [ImmersiveSurface].
 */

/**
 * Root surface for every widget size: rounded container with the blurred
 * album-art background (or the palette gradient fallback) cropped to fill.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun ImmersiveSurface(
    background: Bitmap?,
    palette: WidgetColorPalette,
    content: @GlanceComposable @androidx.compose.runtime.Composable () -> Unit
) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(24.dp)
            .background(WidgetTheme.FallbackBg)
    ) {
        Image(
            provider = ImageProvider(background ?: WidgetBackgroundRenderer.fallback(palette)),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )
        content()
    }
}

/** Tinted vector icon. */
@GlanceComposable
@androidx.compose.runtime.Composable
fun ThemedIcon(
    resId: Int,
    size: Dp,
    tint: Color,
    contentDescription: String? = null
) {
    Image(
        provider = ImageProvider(resId),
        contentDescription = contentDescription,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(ColorProvider(tint))
    )
}

/**
 * Icon button with a proper touch target. When [enabled] is false the icon
 * dims and the click action is not attached.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun GlassIconButton(
    resId: Int,
    contentDescription: String,
    action: Action,
    enabled: Boolean = true,
    boxSize: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    tint: Color = WidgetTheme.TextPrimary,
    glass: Boolean = false
) {
    Box(
        modifier = GlanceModifier
            .size(boxSize)
            .let { if (glass) it.cornerRadius(boxSize / 2).background(WidgetTheme.Glass) else it }
            .semantics { this.contentDescription = contentDescription }
            .let { if (enabled) it.clickable(action) else it },
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            resId = resId,
            size = iconSize,
            tint = if (enabled) tint else WidgetTheme.Disabled
        )
    }
}

/** The hero play/pause control: a solid white circle with a dark glyph. */
@GlanceComposable
@androidx.compose.runtime.Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    enabled: Boolean,
    action: Action,
    size: Dp = 60.dp,
    iconSize: Dp = 28.dp
) {
    val label = if (isPlaying) "Pause playback" else "Play"
    Box(
        modifier = GlanceModifier
            .size(size)
            .cornerRadius(size / 2)
            .background(if (enabled) WidgetTheme.PlayButtonBg else WidgetTheme.Glass)
            .semantics { contentDescription = label }
            .let { if (enabled) it.clickable(action) else it },
        contentAlignment = Alignment.Center
    ) {
        ThemedIcon(
            resId = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow,
            size = iconSize,
            tint = if (enabled) WidgetTheme.PlayButtonFg else WidgetTheme.Disabled
        )
    }
}

/**
 * Rounded glass chip with optional leading icon. Used for speakers,
 * favorites, rooms, and the zone pill.
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun GlassChip(
    text: String,
    contentDescription: String,
    action: Action?,
    modifier: GlanceModifier = GlanceModifier,
    background: Color = WidgetTheme.Glass,
    textColor: Color = WidgetTheme.TextSecondary,
    leadingIcon: Int? = null,
    iconTint: Color = textColor,
    bold: Boolean = false,
    fontSize: Int = 11,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .cornerRadius(20.dp)
            .background(background)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding)
            .semantics { this.contentDescription = contentDescription }
            .let { if (action != null) it.clickable(action) else it },
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leadingIcon != null) {
                ThemedIcon(resId = leadingIcon, size = 12.dp, tint = iconTint)
                Spacer(modifier = GlanceModifier.width(5.dp))
            }
            Text(
                text = text,
                style = TextStyle(
                    color = ColorProvider(textColor),
                    fontSize = fontSize.sp,
                    fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium
                ),
                maxLines = 1
            )
        }
    }
}

/**
 * Small-caps section header, optionally collapsible (shows a chevron and
 * toggles via [action]).
 */
@GlanceComposable
@androidx.compose.runtime.Composable
fun SectionHeader(
    title: String,
    expanded: Boolean? = null,
    action: Action? = null
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .let { if (action != null) it.clickable(action) else it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            style = TextStyle(
                color = ColorProvider(WidgetTheme.TextTertiary),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(modifier = GlanceModifier.defaultWeight())
        if (expanded != null) {
            ThemedIcon(
                resId = if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more,
                size = 14.dp,
                tint = WidgetTheme.TextTertiary
            )
        }
    }
}
