package com.sycamorecreek.sonoswidget.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.sycamorecreek.sonoswidget.data.SonosRepository
import com.sycamorecreek.sonoswidget.widget.PlaybackState

/**
 * One-shot reconnect worker that fires as soon as an unmetered (Wi-Fi)
 * network becomes available.
 *
 * This closes the biggest connectivity gap: when the phone leaves home,
 * the polling service eventually tears down after its idle window, and the
 * only thing left is the 15-minute [WidgetRefreshWorker] — meaning the
 * widget could show "disconnected" for up to 15 minutes after walking back
 * in the door. WorkManager's network constraint turns Wi-Fi reattachment
 * itself into the wake-up signal, so the widget reconnects within seconds
 * of the phone rejoining the home network.
 *
 * Armed by [SonosRepository.pushDisconnectedState] every time the widget
 * enters a disconnected state (idempotent — unique work with KEEP policy).
 * On fire: reconnect (the saved-IP fast path makes this a single unicast
 * probe), push fresh state to the widget, and if music is playing, try to
 * resume the foreground polling service so the widget stays live.
 */
class WifiReconnectWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WifiReconnectWorker"
        private const val UNIQUE_WORK_NAME = "sonos_wifi_reconnect"

        /**
         * Schedules the reconnect to run once Wi-Fi is available.
         * Safe to call repeatedly — KEEP policy preserves the pending request.
         */
        fun scheduleOnWifiAvailable(context: Context) {
            val request = OneTimeWorkRequestBuilder<WifiReconnectWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Wi-Fi reconnect armed")
        }
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Wi-Fi available — attempting reconnect")

        return try {
            val repo = SonosRepository.getInstance(applicationContext)

            if (!repo.isConnected && !repo.discoverAndConnect()) {
                Log.d(TAG, "Reconnect failed — no speaker reachable yet")
                // Re-arm: the constraint may have been satisfied by a hotspot
                // or a Wi-Fi network that isn't home. Retry keeps us listening.
                return Result.retry()
            }

            val state = repo.pollAndUpdate()
            Log.d(TAG, "Reconnected — widget state refreshed")

            // If music is playing, bring the foreground service back so the
            // widget updates at full cadence. Starting an FGS from a worker
            // can be disallowed depending on app standby state — the widget
            // state is already fresh either way, so failure is non-fatal.
            if (state?.playbackState == PlaybackState.PLAYING) {
                try {
                    PlaybackService.start(applicationContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not restart polling service from background: ${e.message}")
                }
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi reconnect failed", e)
            Result.retry()
        }
    }
}
