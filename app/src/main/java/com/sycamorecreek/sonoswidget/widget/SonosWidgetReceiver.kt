package com.sycamorecreek.sonoswidget.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.sycamorecreek.sonoswidget.service.PlaybackService
import com.sycamorecreek.sonoswidget.service.WidgetRefreshWorker

/**
 * BroadcastReceiver that handles widget lifecycle events.
 * Registered in AndroidManifest.xml with the APPWIDGET_UPDATE action.
 *
 * Manages the PlaybackService lifecycle:
 *   - onEnabled():  First widget placed → start foreground service
 *   - onDisabled(): Last widget removed → stop foreground service
 *   - onReceive():  Ensures service is running on any widget event
 */
class SonosWidgetReceiver : GlanceAppWidgetReceiver() {

    companion object {
        private const val TAG = "SonosWidgetReceiver"
    }

    override val glanceAppWidget: GlanceAppWidget = SonosWidget()

    /**
     * Called when the first widget instance is placed on the home screen.
     * Starts the foreground service to begin polling.
     */
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "First widget placed — starting PlaybackService")
        PlaybackService.start(context)
    }

    /**
     * Called when the last widget instance is removed from the home screen.
     * Stops the foreground service and cancels the periodic worker since
     * there are no widgets to update.
     */
    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Last widget removed — stopping PlaybackService and WorkManager")
        PlaybackService.stop(context)
        WidgetRefreshWorker.cancel(context)
    }

    /**
     * Called on every widget broadcast (update, enabled, disabled, etc.).
     * Ensures the service is running whenever widgets exist.
     *
     * This handles edge cases like the service being killed by the system
     * while widgets still exist on the home screen.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        // Re-ensure the service is running on any widget event
        // (onEnabled only fires for the *first* widget; onUpdate fires for all)
        if (intent.action == "android.appwidget.action.APPWIDGET_UPDATE") {
            Log.d(TAG, "Widget update broadcast — ensuring service is running")
            PlaybackService.start(context)
        }
    }
}
