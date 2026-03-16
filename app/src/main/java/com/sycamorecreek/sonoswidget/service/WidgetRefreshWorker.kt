package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sycamorecreek.sonoswidget.data.SonosRepository
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager worker that refreshes the widget state every 15 minutes.
 *
 * This is the idle-mode fallback per PRD §12.1: when the foreground service
 * tears down after 5 minutes of silence, the widget falls back to this
 * periodic worker to keep the UI from going completely stale.
 *
 * The worker does a single poll cycle (discovery + state poll) and pushes
 * the result to the Glance widget. It does NOT start the foreground service;
 * that only happens when the user taps a widget control.
 *
 * Lifecycle:
 *   - Scheduled by [PlaybackService] before idle teardown
 *   - Also scheduled by [SonosWidgetReceiver.onEnabled] as a safety net
 *   - Cancelled by [PlaybackService] when it starts (foreground service
 *     handles polling at much higher frequency)
 *
 * Battery impact: minimal — runs once every 15 minutes (Android minimum),
 * executes a single HTTP request, and exits immediately.
 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WidgetRefreshWorker"
        private const val UNIQUE_WORK_NAME = "sonos_widget_refresh"

        /**
         * Schedules the periodic refresh worker.
         *
         * Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid replacing an
         * already-scheduled instance (idempotent — safe to call multiple times).
         */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(
                15, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Periodic refresh scheduled (every 15 min)")
        }

        /**
         * Cancels the periodic refresh worker.
         *
         * Called when the foreground service starts, since it handles
         * polling at a much higher frequency.
         */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            Log.d(TAG, "Periodic refresh cancelled (foreground service active)")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Periodic refresh starting")

        return try {
            val repo = SonosRepository.getInstance(applicationContext)

            // Attempt discovery if not connected
            if (!repo.isConnected) {
                val discovered = repo.discoverAndConnect()
                if (!discovered) {
                    Log.d(TAG, "Periodic refresh: no speaker found")
                    return Result.success()
                }
            }

            // Single poll cycle
            repo.pollAndUpdate()
            Log.d(TAG, "Periodic refresh complete")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Periodic refresh failed", e)
            Result.success() // Don't retry — next periodic run will try again
        }
    }
}
