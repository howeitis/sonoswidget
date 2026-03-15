package com.sycamorecreek.sonoswidget.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.sycamorecreek.sonoswidget.R
import com.sycamorecreek.sonoswidget.data.SonosRepository
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that polls a Sonos speaker for playback state
 * and pushes updates to the Glance widget.
 *
 * Delegates all Sonos interaction to [SonosRepository], which is the
 * single source of truth for speaker state and control commands.
 *
 * Lifecycle:
 *   - Started by [SonosWidgetReceiver] when the first widget is placed
 *   - Runs a coroutine-based polling loop with adaptive intervals
 *   - Stopped when the last widget is removed or via explicit intent
 *
 * Polling strategy:
 *   - PLAYING:      Poll every 2 seconds (for progress bar updates)
 *   - PAUSED:       Poll every 10 seconds (for external state changes)
 *   - STOPPED:      Poll every 15 seconds (idle monitoring)
 *   - DISCONNECTED: Retry discovery every 30 seconds
 *
 * The service uses FOREGROUND_SERVICE_MEDIA_PLAYBACK type, which requires
 * the FOREGROUND_SERVICE_MEDIA_PLAYBACK permission in the manifest.
 * On Android 14+ (API 34), the service type must also be specified at runtime.
 */
class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "sonos_playback"

        // Polling intervals (milliseconds)
        private const val POLL_PLAYING_MS = 2_000L
        private const val POLL_PAUSED_MS = 10_000L
        private const val POLL_STOPPED_MS = 15_000L
        private const val POLL_DISCONNECTED_MS = 30_000L

        // Intent actions
        const val ACTION_START = "com.sycamorecreek.sonoswidget.action.START_PLAYBACK_SERVICE"
        const val ACTION_STOP = "com.sycamorecreek.sonoswidget.action.STOP_PLAYBACK_SERVICE"

        /**
         * Starts the playback service as a foreground service.
         * Safe to call multiple times — the service handles re-entry.
         */
        fun start(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        /**
         * Stops the playback service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pollJob: Job? = null

    private lateinit var repository: SonosRepository

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        repository = SonosRepository.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.d(TAG, "Stop requested")
                stopPolling()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                Log.d(TAG, "Start requested")
                startForegroundWithNotification()
                startPolling()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        stopPolling()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────
    // Foreground notification
    // ──────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(trackName: String? = null): Notification {
        val contentText = if (!trackName.isNullOrBlank()) {
            trackName
        } else {
            getString(R.string.no_music_playing)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    /**
     * Updates the ongoing notification with the current track name.
     */
    private fun updateNotification(trackName: String?) {
        val notification = buildNotification(trackName)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    // ──────────────────────────────────────────────
    // Polling loop
    // ──────────────────────────────────────────────

    private fun startPolling() {
        if (pollJob?.isActive == true) {
            Log.d(TAG, "Polling already active")
            return
        }

        pollJob = serviceScope.launch {
            Log.d(TAG, "Polling loop started")

            // Try to restore saved speaker for fast reconnect
            repository.restoreFromPreferences()

            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll cycle error", e)
                }

                val interval = when (repository.currentPlaybackState) {
                    PlaybackState.PLAYING -> POLL_PLAYING_MS
                    PlaybackState.PAUSED -> POLL_PAUSED_MS
                    PlaybackState.TRANSITIONING -> POLL_PLAYING_MS
                    PlaybackState.STOPPED -> {
                        if (!repository.isConnected) POLL_DISCONNECTED_MS
                        else POLL_STOPPED_MS
                    }
                }

                delay(interval)
            }

            Log.d(TAG, "Polling loop ended")
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
        Log.d(TAG, "Polling stopped")
    }

    /**
     * Executes a single poll cycle via the repository.
     *
     * If no speaker is connected, attempts discovery first.
     * Otherwise, polls playback state and updates the widget.
     */
    private suspend fun pollOnce() {
        // Ensure we have a speaker to talk to
        if (!repository.isConnected) {
            val discovered = repository.discoverAndConnect()
            if (!discovered) {
                repository.pushDisconnectedState()
                updateNotification(null)
                return
            }
        }

        // Poll and update widget state
        val state = repository.pollAndUpdate()

        // Update notification with current track
        val trackName = state?.currentTrack?.name?.ifBlank { null }
        updateNotification(trackName)
    }
}
