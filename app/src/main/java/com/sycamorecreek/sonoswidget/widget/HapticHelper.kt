package com.sycamorecreek.sonoswidget.widget

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * Provides haptic feedback for widget control interactions.
 *
 * Uses Android 12+ (API 31) VibrationEffect.Composition with haptic primitives
 * for distinct tactile responses per control type. Falls back to simple
 * one-shot vibrations on older devices.
 *
 * Haptic profiles:
 *   - **Confirm** (play/pause, zone select): Soft pulse — PRIMITIVE_CLICK
 *   - **Click** (skip forward/back):        Sharp tick — PRIMITIVE_TICK
 *   - **Ramp** (volume up/down):            Gentle bump — PRIMITIVE_LOW_TICK
 *
 * Called from Glance [ActionCallback.onAction] which provides a Context.
 * The Vibrator service works from any context (doesn't need a View).
 */
object HapticHelper {

    private const val TAG = "HapticHelper"

    // Fallback durations for pre-API 31 devices (milliseconds)
    private const val DURATION_CONFIRM_MS = 30L
    private const val DURATION_CLICK_MS = 15L
    private const val DURATION_RAMP_MS = 20L

    // Fallback amplitudes (0–255, or DEFAULT_AMPLITUDE)
    private const val AMPLITUDE_CONFIRM = 80
    private const val AMPLITUDE_CLICK = 180
    private const val AMPLITUDE_RAMP = 60

    /**
     * Soft pulse for play/pause and zone selection.
     */
    fun playConfirm(context: Context) {
        vibrate(context, HapticType.CONFIRM)
    }

    /**
     * Sharp tick for skip forward/back.
     */
    fun playClick(context: Context) {
        vibrate(context, HapticType.CLICK)
    }

    /**
     * Gentle bump for volume adjustments.
     */
    fun playRamp(context: Context) {
        vibrate(context, HapticType.RAMP)
    }

    private enum class HapticType {
        CONFIRM, CLICK, RAMP
    }

    private fun vibrate(context: Context, type: HapticType) {
        try {
            val vibrator = getVibrator(context) ?: return
            val effect = createEffect(type, vibrator)
            if (effect != null) {
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            // Haptic failure is non-critical — swallow and log
            Log.w(TAG, "Haptic feedback failed", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE)
                as? VibratorManager
            manager?.defaultVibrator
        } else {
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun createEffect(type: HapticType, vibrator: Vibrator): VibrationEffect? {
        // API 31+ (Android 12): use composition primitives for rich haptics
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return createCompositionEffect(type, vibrator)
        }

        // Fallback: simple one-shot vibration
        return createFallbackEffect(type)
    }

    /**
     * Creates a rich haptic effect using VibrationEffect.Composition.
     * Available on API 31+ (Android 12).
     *
     * Each control type maps to a distinct primitive for tactile differentiation.
     */
    private fun createCompositionEffect(type: HapticType, vibrator: Vibrator): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null

        val primitiveId = when (type) {
            HapticType.CONFIRM -> VibrationEffect.Composition.PRIMITIVE_CLICK
            HapticType.CLICK -> VibrationEffect.Composition.PRIMITIVE_TICK
            HapticType.RAMP -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
        }

        // Check if the device supports this primitive
        if (!vibrator.areAllPrimitivesSupported(primitiveId)) {
            return createFallbackEffect(type)
        }

        val scale = when (type) {
            HapticType.CONFIRM -> 0.6f
            HapticType.CLICK -> 1.0f
            HapticType.RAMP -> 0.4f
        }

        return VibrationEffect.startComposition()
            .addPrimitive(primitiveId, scale)
            .compose()
    }

    /**
     * Creates a simple one-shot vibration for pre-API 31 devices.
     */
    private fun createFallbackEffect(type: HapticType): VibrationEffect {
        val (duration, amplitude) = when (type) {
            HapticType.CONFIRM -> DURATION_CONFIRM_MS to AMPLITUDE_CONFIRM
            HapticType.CLICK -> DURATION_CLICK_MS to AMPLITUDE_CLICK
            HapticType.RAMP -> DURATION_RAMP_MS to AMPLITUDE_RAMP
        }
        return VibrationEffect.createOneShot(duration, amplitude)
    }
}
