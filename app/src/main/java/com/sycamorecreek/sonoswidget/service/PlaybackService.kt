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
 * Lifecycle per PRD §12.1:
 *   - Started by [SonosWidgetReceiver] when the first widget is placed,
 *     or re-launched by any [WidgetActions] callback when the user taps a control
 *   - Runs a coroutine-based polling loop with adaptive intervals
 *   - **Self-stops after 5 minutes of idle** (STOPPED state with no user interaction)
 *   - On idle teardown, schedules [WidgetRefreshWorker] for 15-minute periodic fallback
 *   - Stopped when the last widget is removed or via explicit intent
 *
 * Polling strategy (active mode):
 *   - PLAYING:      Poll every 2 seconds (for progress bar updates)
 *   - PAUSED:       Poll every 10 seconds (for external state changes)
 *   - STOPPED:      Poll every 15 seconds (idle monitoring)
 *   - DISCONNECTED: Exponential backoff 30s → 60s → 120s → 240s → 300s (capped)
 *
 * Battery budget per PRD §12.2:
 *   - Active playback (4 hrs/day): < 1.5% battery via foreground service + push events
 *   - Idle (20 hrs/day): < 0.5% battery via WorkManager periodic only
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

        // Disconnected exponential backoff
        private const val POLL_DISCONNECTED_BASE_MS = 30_000L
        private const val POLL_DISCONNECTED_MAX_MS = 300_000L // 5 min cap

        // Idle teardown: service self-stops after this duration of STOPPED state
        // per PRD §12.1: "foreground service is torn down after 5 minutes of silence"
        private const val IDLE_TEARDOWN_MS = 5 * 60 * 1000L // 5 minutes

        // Intent actions
        const val ACTION_START = "com.sycamorecreek.sonoswidget.action.START_PLAYBACK_SERVICE"
        const val ACTION_STOP = "com.sycamorecreek.sonoswidget.action.STOP_PLAYBACK_SERVICE"

        /**
         * Starts the playback service as a foreground service.
         * Safe to call multiple times — the service handles re-entry.
         *
         * Also cancels the periodic [WidgetRefreshWorker] since the
         * foreground service handles polling at much higher frequency.
         */
        fun start(context: Context) {
            WidgetRefreshWorker.cancel(context)
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
    private lateinit var networkReceiver: NetworkChangeReceiver

    // Idle teardown tracking (PRD §12.1)
    private var idleStartMs: Long = 0L

    // Exponential backoff for disconnected state
    private var consecutiveDisconnects: Int = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        repository = SonosRepository.getInstance(this)
        createNotificationChannel()

        // Register network callback for auto-retry on connectivity changes
        networkReceiver = NetworkChangeReceiver(this) {
            // Trigger immediate re-discovery when network becomes available
            serviceScope.launch {
                if (!repository.isConnected) {
                    Log.d(TAG, "Network restored — attempting re-discovery")
                    consecutiveDisconnects = 0 // Reset backoff on network change
                    pollOnce()
                }
            }
        }
        networkReceiver.register()
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
                // Reset idle tracking — service is being (re)started, likely by user action
                idleStartMs = 0L
                consecutiveDisconnects = 0
                startForegroundWithNotification()
                startPolling()
                return START_STICKY
            }
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "Service destroyed")
        networkReceiver.unregister()
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

                // Check for idle teardown (PRD §12.1)
                if (shouldTearDownForIdle()) {
                    Log.d(TAG, "Idle for ${IDLE_TEARDOWN_MS / 1000}s — tearing down service, " +
                        "scheduling WorkManager fallback")
                    WidgetRefreshWorker.schedule(this@PlaybackService)
                    stopSelf()
                    return@launch
                }

                val interval = computeNextInterval()
                delay(interval)
            }

            Log.d(TAG, "Polling loop ended")
        }
    }

    /**
     * Computes the next poll interval based on current state.
     *
     * Uses exponential backoff for disconnected state:
     * 30s → 60s → 120s → 240s → 300s (capped at 5 min).
     * Resets on successful connection or network change.
     */
    private fun computeNextInterval(): Long {
        val playbackState = repository.currentPlaybackState
        val isConnected = repository.isConnected

        return when {
            playbackState == PlaybackState.PLAYING -> POLL_PLAYING_MS
            playbackState == PlaybackState.TRANSITIONING -> POLL_PLAYING_MS
            playbackState == PlaybackState.PAUSED -> POLL_PAUSED_MS
            !isConnected -> {
                // Exponential backoff: base * 2^n, capped
                val backoff = POLL_DISCONNECTED_BASE_MS *
                    (1L shl consecutiveDisconnects.coerceAtMost(4))
                backoff.coerceAtMost(POLL_DISCONNECTED_MAX_MS)
            }
            else -> POLL_STOPPED_MS // STOPPED but connected
        }
    }

    /**
     * Tracks idle state and determines whether to self-stop.
     *
     * Returns true if the speaker has been in STOPPED (or disconnected)
     * state for longer than [IDLE_TEARDOWN_MS].
     */
    private fun shouldTearDownForIdle(): Boolean {
        val isIdle = repository.currentPlaybackState == PlaybackState.STOPPED ||
            !repository.isConnected

        if (!isIdle) {
            // Reset idle timer when music is playing or paused
            idleStartMs = 0L
            return false
        }

        val now = System.currentTimeMillis()
        if (idleStartMs == 0L) {
            // Start tracking idle time
            idleStartMs = now
            return false
        }

        return (now - idleStartMs) >= IDLE_TEARDOWN_MS
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
     *
     * Tracks consecutive disconnect failures for exponential backoff.
     */
    private suspend fun pollOnce() {
        // Ensure we have a speaker to talk to
        if (!repository.isConnected) {
            val discovered = repository.discoverAndConnect()
            if (!discovered) {
                consecutiveDisconnects++
                repository.pushDisconnectedState()
                updateNotification(null)
                return
            }
            // Successfully connected — reset backoff
            consecutiveDisconnects = 0
        }

        // Poll and update widget state
        val state = repository.pollAndUpdate()

        // Reset backoff on successful poll
        consecutiveDisconnects = 0

        // Update notification with current track
        val trackName = state?.currentTrack?.name?.ifBlank { null }
        updateNotification(trackName)
    }
}
