package com.sycamorecreek.sonoswidget.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sycamorecreek.sonoswidget.R
import com.sycamorecreek.sonoswidget.data.SonosPreferences
import com.sycamorecreek.sonoswidget.data.SonosRepository
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.PlaybackService
import com.sycamorecreek.sonoswidget.sonos.cloud.CloudSonosController
import com.sycamorecreek.sonoswidget.sonos.cloud.SonosOAuthManager
import com.sycamorecreek.sonoswidget.sonos.cloud.TokenStore
import com.sycamorecreek.sonoswidget.widget.ConnectionMode
import com.sycamorecreek.sonoswidget.widget.PlaybackState
import com.sycamorecreek.sonoswidget.widget.SonosWidgetState
import com.sycamorecreek.sonoswidget.widget.Zone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Companion app for the Sonos widget.
 *
 * Redesigned around a live view of the same state the widget renders:
 *   - Now Playing hero card (live via [SonosRepository.widgetState])
 *   - Connection health with prerequisites checklist and one-tap scan
 *   - Speaker list with default-room selection
 *   - Sonos OAuth account (cloud fallback)
 *   - Network tools (manual IPs), preferences, and maintenance
 *
 * Not a remote control by design — playback control lives on the widget and
 * in the Sonos app; this screen is for setup, diagnostics, and glanceable
 * status.
 *
 * Uses standard Jetpack Compose (NOT Glance) with Material 3 dynamic color.
 */
class SonosCompanionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SonosCompanionActivity"
        private const val SONOS_S2_PACKAGE = "com.sonos.acr2"
        private const val SONOS_S1_PACKAGE = "com.sonos.acr"
        const val EXTRA_SONOS_APP_UNAVAILABLE = "sonos_app_unavailable"
        const val EXTRA_ROOM_CHOOSER = "room_chooser"
    }

    private lateinit var tokenStore: TokenStore
    private lateinit var oAuthManager: SonosOAuthManager
    private lateinit var cloudController: CloudSonosController
    private lateinit var preferences: SonosPreferences

    // Account state
    private var accountState by mutableStateOf(AccountUiState.IDLE)
    private var accountMessage by mutableStateOf("")
    private var isLoggedIn by mutableStateOf(false)

    // Settings state
    private val manualIps = mutableStateListOf<String>()
    private val ipTestResults = mutableStateMapOf<String, Boolean?>()
    private var defaultZoneName by mutableStateOf<String?>(null)
    private var defaultZoneId by mutableStateOf<String?>(null)
    private var roomFollowMode by mutableStateOf(SonosPreferences.RoomFollowMode.STAY_WITH_ROOM)
    private var preferredService by mutableStateOf<String?>(null)
    private var cacheSizeBytes by mutableLongStateOf(0L)

    // Connection prerequisites
    private var connectedSpeakerIp by mutableStateOf<String?>(null)
    private var isOnWifi by mutableStateOf(false)
    private var hasNearbyPermission by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)
    private var scanMessage by mutableStateOf<String?>(null)
    private var showRoomChooser by mutableStateOf(false)
    private var showSonosUnavailableExplanation by mutableStateOf(false)

    private val scope = CoroutineScope(Dispatchers.Main)

    // Runtime permission launcher for NEARBY_WIFI_DEVICES (Android 13+)
    private val nearbyWifiPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d(TAG, "NEARBY_WIFI_DEVICES granted — reloading settings")
            loadSettings()
        } else {
            Log.w(TAG, "NEARBY_WIFI_DEVICES denied — local discovery will be unavailable")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tokenStore = TokenStore(applicationContext)
        oAuthManager = SonosOAuthManager(tokenStore)
        cloudController = CloudSonosController(oAuthManager)
        preferences = SonosPreferences(applicationContext)

        isLoggedIn = tokenStore.isLoggedIn

        handleIntent(intent)
        // The artwork fallback is a focused, low-friction explanation. Do not
        // obscure its explicit Back/Get Sonos actions with an unrelated local
        // discovery permission request.
        if (!showSonosUnavailableExplanation) {
            requestNearbyWifiPermissionIfNeeded()
        }
        loadSettings()

        setContent {
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
                    dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    dynamicLightColorScheme(this)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }

            MaterialTheme(colorScheme = colorScheme) {
                val repo = remember { SonosRepository.getInstance(applicationContext) }
                val widgetState by repo.widgetState.collectAsState()

                Scaffold { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        CompanionScreen(
                            state = widgetState,
                            accountState = accountState,
                            accountMessage = accountMessage,
                            isLoggedIn = isLoggedIn,
                            onSignIn = ::startOAuthLogin,
                            onSignOut = ::signOut,
                            connectedSpeakerIp = connectedSpeakerIp,
                            isOnWifi = isOnWifi,
                            hasNearbyPermission = hasNearbyPermission,
                            isScanning = isScanning,
                            scanMessage = scanMessage,
                            onScan = ::scanForSpeakers,
                            onOpenSonos = ::openSonosApp,
                            onGetSonos = ::openSonosStore,
                            manualIps = manualIps,
                            ipTestResults = ipTestResults,
                            onAddIp = ::addManualIp,
                            onRemoveIp = ::removeManualIp,
                            onTestIp = ::testManualIp,
                            defaultZoneId = defaultZoneId,
                            defaultZoneName = defaultZoneName,
                            roomFollowMode = roomFollowMode,
                            onRoomFollowModeChange = ::updateRoomFollowMode,
                            onSelectDefaultZone = ::selectDefaultZone,
                            onClearDefaultZone = ::clearDefaultZone,
                            preferredService = preferredService,
                            onSelectService = ::selectPreferredService,
                            cacheSizeBytes = cacheSizeBytes,
                            onClearCache = ::clearImageCache,
                            onRefreshWidget = ::refreshWidget,
                            showRoomChooser = showRoomChooser,
                            onSwitchRoom = ::switchRoom,
                            showSonosUnavailableExplanation = showSonosUnavailableExplanation,
                            onDismissSonosUnavailableExplanation = ::dismissSonosUnavailableExplanation
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Wake the polling service while the user is looking at the app so
        // the Now Playing card and connection status stay live.
        PlaybackService.start(this)
        refreshPrerequisites()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
        // This is a singleTask activity. A normal launcher or OAuth intent can
        // arrive after the focused artwork fallback, so treat that fallback as
        // scoped to the current intent rather than leaving its card stranded.
        if (!showSonosUnavailableExplanation) {
            requestNearbyWifiPermissionIfNeeded()
        }
    }

    // ──────────────────────────────────────────────
    // OAuth handling
    // ──────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        showRoomChooser = intent?.getBooleanExtra(EXTRA_ROOM_CHOOSER, false) == true
        showSonosUnavailableExplanation =
            intent?.getBooleanExtra(EXTRA_SONOS_APP_UNAVAILABLE, false) == true
        val data = intent?.data ?: return

        val isHttpsCallback = data.scheme == "https" && data.host == "sycamorecreekconsulting.com" && data.path == "/callback"
        val isCustomCallback = data.scheme == "sonoswidget" && data.host == "callback"
        if (isHttpsCallback || isCustomCallback) {
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")

            if (error != null) {
                Log.e(TAG, "OAuth error: $error")
                accountState = AccountUiState.ERROR
                accountMessage = "Authorization denied: $error"
                return
            }

            if (code == null) {
                Log.e(TAG, "No authorization code in callback")
                accountState = AccountUiState.ERROR
                accountMessage = "No authorization code received"
                return
            }

            // Verify state parameter for CSRF protection. Fails closed: an
            // unsolicited callback with no persisted pending state is rejected.
            if (!oAuthManager.verifyAndConsumeState(state)) {
                Log.e(TAG, "OAuth state verification failed (got=$state)")
                accountState = AccountUiState.ERROR
                accountMessage = "Security check failed (state mismatch)"
                return
            }

            exchangeCode(code)
        }
    }

    private fun startOAuthLogin() {
        accountState = AccountUiState.LOADING
        accountMessage = "Opening Sonos login..."
        oAuthManager.launchLogin(this)
    }

    private fun exchangeCode(code: String) {
        accountState = AccountUiState.LOADING
        accountMessage = "Signing in..."

        scope.launch {
            val success = oAuthManager.handleCallback(code)
            if (success) {
                isLoggedIn = true
                accountState = AccountUiState.SUCCESS
                accountMessage = "Signed in to Sonos"
                Log.d(TAG, "OAuth flow completed successfully — triggering discovery")
                PlaybackService.pollNow(applicationContext)
            } else {
                accountState = AccountUiState.ERROR
                accountMessage = "Failed to complete sign in. Please try again."
                Log.e(TAG, "Token exchange failed")
            }
        }
    }

    private fun signOut() {
        oAuthManager.logout()
        cloudController.clearCache()
        isLoggedIn = false
        accountState = AccountUiState.IDLE
        accountMessage = ""
        Log.d(TAG, "User signed out")
    }

    // ──────────────────────────────────────────────
    // Permission handling
    // ──────────────────────────────────────────────

    private fun requestNearbyWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

        val status = ContextCompat.checkSelfPermission(
            this, Manifest.permission.NEARBY_WIFI_DEVICES
        )
        if (status != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Requesting NEARBY_WIFI_DEVICES permission")
            nearbyWifiPermissionLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    // ──────────────────────────────────────────────
    // Settings loading
    // ──────────────────────────────────────────────

    private fun loadSettings() {
        scope.launch {
            val ips = withContext(Dispatchers.IO) { preferences.getManualIps() }
            manualIps.clear()
            manualIps.addAll(ips)

            val roomPreferences = withContext(Dispatchers.IO) { preferences.getRoomFollowPreferences() }
            val zone = roomPreferences.target
            defaultZoneId = zone?.id
            defaultZoneName = zone?.name
            roomFollowMode = roomPreferences.mode

            preferredService = withContext(Dispatchers.IO) { preferences.getPreferredService() }

            cacheSizeBytes = withContext(Dispatchers.IO) {
                AlbumArtLoader.getDiskCacheSize(applicationContext)
            }

            refreshPrerequisites()
        }
    }

    private fun refreshPrerequisites() {
        val repo = SonosRepository.getInstance(applicationContext)

        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        isOnWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        hasNearbyPermission = repo.hasLocalNetworkPermission()

        scope.launch {
            connectedSpeakerIp = withContext(Dispatchers.IO) {
                preferences.activeSpeaker.first()
            }?.ip
        }
    }

    private fun scanForSpeakers() {
        if (isScanning) return
        isScanning = true
        scanMessage = null
        scope.launch {
            try {
                val repo = SonosRepository.getInstance(applicationContext)
                val found = withContext(Dispatchers.IO) {
                    repo.discoverAndConnect()
                }
                if (found) {
                    withContext(Dispatchers.IO) { repo.pollAndUpdate() }
                    val zoneCount = repo.widgetState.value.zones.size
                    scanMessage = if (zoneCount > 0) "Found $zoneCount speaker(s)" else "Connected"
                    PlaybackService.pollNow(applicationContext)
                } else {
                    scanMessage = "No speakers found"
                }
                refreshPrerequisites()
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                scanMessage = "Scan failed: ${e.message}"
            } finally {
                isScanning = false
            }
        }
    }

    // ──────────────────────────────────────────────
    // Open Sonos app
    // ──────────────────────────────────────────────

    private fun openSonosApp() {
        val intent = packageManager.getLaunchIntentForPackage(SONOS_S2_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(SONOS_S1_PACKAGE)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch Sonos app", e)
            }
        }
        // The widget opens this companion explanation when unavailable. The
        // in-app button below deliberately distinguishes "Get Sonos" from a
        // normal app launch.
        Log.w(TAG, "Sonos app is unavailable")
    }

    private fun openSonosStore() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SONOS_S2_PACKAGE"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$SONOS_S2_PACKAGE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (webError: Exception) {
                Log.e(TAG, "Failed to open Sonos store listing", webError)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Manual IP management
    // ──────────────────────────────────────────────

    private fun addManualIp(ip: String) {
        val trimmed = ip.trim()
        if (trimmed.isBlank() || manualIps.contains(trimmed)) return
        if (!isValidIpAddress(trimmed)) return

        manualIps.add(trimmed)
        scope.launch(Dispatchers.IO) {
            preferences.addManualIp(trimmed)
        }
        testManualIp(trimmed)
    }

    private fun removeManualIp(ip: String) {
        manualIps.remove(ip)
        ipTestResults.remove(ip)
        scope.launch(Dispatchers.IO) {
            preferences.removeManualIp(ip)
        }
    }

    private fun testManualIp(ip: String) {
        ipTestResults[ip] = null // null = testing in progress
        scope.launch {
            val reachable = withContext(Dispatchers.IO) {
                testSpeakerConnection(ip, 1400)
            }
            ipTestResults[ip] = reachable
            if (reachable) {
                Log.d(TAG, "Manual IP $ip reachable — triggering discovery")
                PlaybackService.pollNow(applicationContext)
            }
        }
    }

    // ──────────────────────────────────────────────
    // Default zone
    // ──────────────────────────────────────────────

    private fun selectDefaultZone(zone: Zone) {
        defaultZoneId = zone.id
        defaultZoneName = zone.displayName
        roomFollowMode = SonosPreferences.RoomFollowMode.STAY_WITH_ROOM
        scope.launch(Dispatchers.IO) {
            preferences.saveDefaultZone(zone.id, zone.displayName)
        }
    }

    private fun clearDefaultZone() {
        defaultZoneId = null
        defaultZoneName = null
        roomFollowMode = SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC
        scope.launch(Dispatchers.IO) {
            preferences.clearDefaultZone()
        }
    }

    private fun switchRoom(zone: Zone) {
        scope.launch {
            SonosRepository.getInstance(applicationContext).switchZone(zone.id)
            showRoomChooser = false
        }
    }

    private fun dismissSonosUnavailableExplanation() {
        showSonosUnavailableExplanation = false
        finish()
    }

    private fun updateRoomFollowMode(mode: SonosPreferences.RoomFollowMode) {
        roomFollowMode = mode
        if (mode == SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC) {
            defaultZoneId = null
            defaultZoneName = null
        }
        scope.launch(Dispatchers.IO) {
            val target = if (mode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM) {
                defaultZoneId?.let { id ->
                    SonosPreferences.DefaultZone(id, defaultZoneName.orEmpty())
                }
            } else null
            preferences.saveRoomFollowPreferences(mode, target)
        }
    }

    // ──────────────────────────────────────────────
    // Preferred service
    // ──────────────────────────────────────────────

    private fun selectPreferredService(serviceId: String) {
        preferredService = serviceId
        scope.launch(Dispatchers.IO) {
            preferences.savePreferredService(serviceId)
        }
    }

    // ──────────────────────────────────────────────
    // Cache management
    // ──────────────────────────────────────────────

    private fun clearImageCache() {
        scope.launch {
            withContext(Dispatchers.IO) {
                AlbumArtLoader.clearAllDiskCache(applicationContext)
            }
            cacheSizeBytes = 0L
            Log.d(TAG, "Image cache cleared")
        }
    }

    // ──────────────────────────────────────────────
    // Widget refresh
    // ──────────────────────────────────────────────

    private fun refreshWidget() {
        scope.launch {
            try {
                val repo = SonosRepository.getInstance(applicationContext)
                withContext(Dispatchers.IO) {
                    if (!repo.isConnected) {
                        repo.discoverAndConnect()
                    }
                    repo.pollAndUpdate()
                }
                cacheSizeBytes = withContext(Dispatchers.IO) {
                    AlbumArtLoader.getDiskCacheSize(applicationContext)
                }
                Log.d(TAG, "Manual widget refresh triggered")
            } catch (e: Exception) {
                Log.e(TAG, "Widget refresh failed", e)
            }
        }
    }
}

// ──────────────────────────────────────────────
// Helper functions
// ──────────────────────────────────────────────

private fun isValidIpAddress(ip: String): Boolean {
    val parts = ip.split(".")
    if (parts.size != 4) return false
    return parts.all { part ->
        val num = part.toIntOrNull() ?: return false
        num in 0..255
    }
}

/**
 * Tests connectivity to a Sonos speaker by requesting its device description.
 * Returns true if the speaker responds on port 1400.
 */
private fun testSpeakerConnection(ip: String, port: Int): Boolean {
    return try {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("http://$ip:$port/xml/device_description.xml")
            .head()
            .build()
        val response = client.newCall(request).execute()
        val success = response.use { it.isSuccessful }
        Log.d("SonosCompanion", "Manual IP test $ip:$port -> HTTP ${response.code} (success=$success)")
        success
    } catch (e: Exception) {
        Log.e("SonosCompanion", "Manual IP test $ip:$port failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
}

private fun formatCacheSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

private enum class AccountUiState {
    IDLE, LOADING, SUCCESS, ERROR
}

// ──────────────────────────────────────────────
// Compose UI
// ──────────────────────────────────────────────

@Composable
private fun CompanionScreen(
    state: SonosWidgetState,
    accountState: AccountUiState,
    accountMessage: String,
    isLoggedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    connectedSpeakerIp: String?,
    isOnWifi: Boolean,
    hasNearbyPermission: Boolean,
    isScanning: Boolean,
    scanMessage: String?,
    onScan: () -> Unit,
    onOpenSonos: () -> Unit,
    onGetSonos: () -> Unit,
    manualIps: List<String>,
    ipTestResults: Map<String, Boolean?>,
    onAddIp: (String) -> Unit,
    onRemoveIp: (String) -> Unit,
    onTestIp: (String) -> Unit,
    defaultZoneId: String?,
    defaultZoneName: String?,
    roomFollowMode: SonosPreferences.RoomFollowMode,
    onRoomFollowModeChange: (SonosPreferences.RoomFollowMode) -> Unit,
    onSelectDefaultZone: (Zone) -> Unit,
    onClearDefaultZone: () -> Unit,
    preferredService: String?,
    onSelectService: (String) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    onRefreshWidget: () -> Unit,
    showRoomChooser: Boolean,
    onSwitchRoom: (Zone) -> Unit,
    showSonosUnavailableExplanation: Boolean,
    onDismissSonosUnavailableExplanation: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        // ── Header ──
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sonos Widget",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Companion",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ConnectionPill(state)
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Now Playing hero ──
        NowPlayingCard(state = state, onOpenSonos = onOpenSonos, onGetSonos = onGetSonos)

        Spacer(modifier = Modifier.height(14.dp))

        if (showSonosUnavailableExplanation) {
            SonosUnavailableCard(
                onGetSonos = onGetSonos,
                onBack = onDismissSonosUnavailableExplanation
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        if (showRoomChooser) {
            RoomChooserCard(state = state, onSwitchRoom = onSwitchRoom)
            Spacer(modifier = Modifier.height(14.dp))
        }

        // ── Connection ──
        SectionCard(title = "Connection") {
            val method = when (state.connectionMode) {
                ConnectionMode.LOCAL_SSDP -> "Auto-discovery (SSDP)"
                ConnectionMode.LOCAL_MDNS -> "Auto-discovery (mDNS)"
                ConnectionMode.LOCAL_MANUAL_IP -> "Manual IP"
                ConnectionMode.CLOUD -> "Cloud API"
                ConnectionMode.DISCONNECTED -> null
            }

            if (method != null) {
                DetailRow("Room", state.activeZone.displayName.ifBlank { "—" })
                DetailRow("Speaker IP", connectedSpeakerIp ?: "—")
                DetailRow("Method", method)
            } else {
                Text(
                    text = if (state.isOffline) "Offline — no local network or internet"
                        else "Not connected to a speaker",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(10.dp))

            ChecklistRow("Wi-Fi connected", isOnWifi)
            ChecklistRow("Nearby devices permission", hasNearbyPermission)

            if (!isOnWifi) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Auto-discovery requires Wi-Fi on the same network as your speakers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!hasNearbyPermission) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Grant \"Nearby devices\" permission in app settings for auto-discovery",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(if (isScanning) "Scanning…" else "Scan for speakers")
            }

            if (scanMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = scanMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scanMessage.startsWith("Found") || scanMessage == "Connected")
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Speakers ──
        SectionCard(title = "Speakers") {
            Text(
                text = "Widget room behavior",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Stay with a selected room, or automatically follow the room that is playing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onRoomFollowModeChange(SonosPreferences.RoomFollowMode.STAY_WITH_ROOM) },
                    label = { Text("Stay with room") },
                    leadingIcon = if (roomFollowMode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
                AssistChip(
                    onClick = { onRoomFollowModeChange(SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC) },
                    label = { Text("Follow playing") },
                    leadingIcon = if (roomFollowMode == SonosPreferences.RoomFollowMode.FOLLOW_PLAYING_MUSIC) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (state.zones.isEmpty()) {
                Text(
                    text = "No speakers discovered yet. Tap \"Scan for speakers\" or add the widget to your home screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = if (roomFollowMode == SonosPreferences.RoomFollowMode.STAY_WITH_ROOM)
                        "Tap the star to choose the room where the widget stays"
                    else "Following the room currently playing music",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                state.zones.forEachIndexed { index, zone ->
                    if (index > 0) HorizontalDivider()
                    SpeakerRow(
                        zone = zone,
                        isActive = zone.id == state.activeZone.id,
                        isDefault = zone.id == defaultZoneId,
                        onToggleDefault = {
                            if (zone.id == defaultZoneId) onClearDefaultZone()
                            else onSelectDefaultZone(zone)
                        }
                    )
                }
                if (defaultZoneName != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Default room: $defaultZoneName",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Sonos Account ──
        SectionCard(title = "Sonos Account") {
            if (isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Signed in",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Cloud control available when away from home",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign out")
                    }
                }
            } else {
                Text(
                    text = "Sign in to enable cloud control as a fallback when local discovery fails",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSignIn,
                    enabled = accountState != AccountUiState.LOADING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign in with Sonos")
                }
            }

            if (accountMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                when (accountState) {
                    AccountUiState.LOADING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = accountMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AccountUiState.SUCCESS -> Text(
                        text = accountMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    AccountUiState.ERROR -> Text(
                        text = accountMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    AccountUiState.IDLE -> {}
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Manual IPs ──
        SectionCard(title = "Manual Speaker IPs") {
            Text(
                text = "Add speaker IPs if automatic discovery fails on your network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            manualIps.forEach { ip ->
                ManualIpRow(
                    ip = ip,
                    testResult = ipTestResults[ip],
                    onTest = { onTestIp(ip) },
                    onRemove = { onRemoveIp(ip) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            var newIp by remember { mutableStateOf("") }
            var showIpError by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newIp,
                    onValueChange = {
                        newIp = it
                        showIpError = false
                    },
                    label = { Text("Speaker IP") },
                    placeholder = { Text("192.168.1.100") },
                    isError = showIpError,
                    supportingText = if (showIpError) {
                        { Text("Invalid IP address") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (isValidIpAddress(newIp.trim())) {
                            onAddIp(newIp)
                            newIp = ""
                        } else {
                            showIpError = true
                        }
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add IP")
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Preferences ──
        SectionCard(title = "Preferences") {
            Text(
                text = "Preferred music service",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            ServicePicker(
                selectedService = preferredService,
                onSelect = onSelectService
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Maintenance ──
        SectionCard(title = "Maintenance") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Image cache",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = formatCacheSize(cacheSizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(onClick = onClearCache) {
                    Text("Clear")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRefreshWidget,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Refresh widget now")
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

// ──────────────────────────────────────────────
// Now Playing hero
// ──────────────────────────────────────────────

@Composable
private fun NowPlayingCard(
    state: SonosWidgetState,
    onOpenSonos: () -> Unit,
    onGetSonos: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val sonosInstalled = remember {
        context.packageManager.getLaunchIntentForPackage("com.sonos.acr2") != null ||
            context.packageManager.getLaunchIntentForPackage("com.sonos.acr") != null
    }
    val hasTrack = state.currentTrack.name.isNotBlank()
    // Reload art from disk whenever the track's art URL changes
    val albumArt = remember(state.currentTrack.artUrl, state.lastUpdatedMs) {
        AlbumArtLoader.loadFromDisk(context)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Art / placeholder
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (albumArt != null && hasTrack) {
                        Image(
                            bitmap = albumArt.asImageBitmap(),
                            contentDescription = "Album art",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_music_note),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            state.currentSource == "TV" -> "TV Audio"
                            hasTrack -> state.currentTrack.name
                            else -> "Nothing playing"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2
                    )
                    if (hasTrack && state.currentTrack.artist.isNotBlank()) {
                        Text(
                            text = state.currentTrack.artist,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    if (state.activeZone.displayName.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speaker_device),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "${state.activeZone.displayName} · ${state.volume}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlaybackStateChip(state.playbackState, state.connectionMode)
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = if (sonosInstalled) onOpenSonos else onGetSonos) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (sonosInstalled) "Open Sonos" else "Get Sonos")
                }
            }
        }
    }
}

@Composable
private fun PlaybackStateChip(playback: PlaybackState, mode: ConnectionMode) {
    val (label, color) = when {
        mode == ConnectionMode.DISCONNECTED -> "Disconnected" to MaterialTheme.colorScheme.error
        playback == PlaybackState.PLAYING -> "Playing" to MaterialTheme.colorScheme.primary
        playback == PlaybackState.PAUSED -> "Paused" to MaterialTheme.colorScheme.tertiary
        playback == PlaybackState.TRANSITIONING -> "Loading…" to MaterialTheme.colorScheme.tertiary
        else -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    AssistChip(
        onClick = {},
        label = { Text(label) },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    )
}

/** Small colored status pill shown in the header. */
@Composable
private fun ConnectionPill(state: SonosWidgetState) {
    val connected = state.connectionMode != ConnectionMode.DISCONNECTED
    val color = if (connected) Color(0xFF34C759) else MaterialTheme.colorScheme.error
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (connected) "Connected" else "Offline",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

// ──────────────────────────────────────────────
// Shared building blocks
// ──────────────────────────────────────────────

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun ChecklistRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (ok) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SonosUnavailableCard(
    onGetSonos: () -> Unit,
    onBack: () -> Unit
) {
    SectionCard(title = "Open Sonos") {
        Text(
            text = "Sonos isn't installed or couldn't be opened. Install it to browse and manage your system.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f)
            ) {
                Text("Back")
            }
            Button(
                onClick = onGetSonos,
                modifier = Modifier.weight(1f)
            ) {
                Text("Get Sonos")
            }
        }
    }
}

@Composable
private fun RoomChooserCard(
    state: SonosWidgetState,
    onSwitchRoom: (Zone) -> Unit
) {
    SectionCard(title = "Switch room") {
        if (state.zones.isEmpty()) {
            Text(
                text = "No rooms are available yet. Scan for speakers and try again.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Choose a room for this widget. The full list remains available here when the widget is too small to show it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            state.zones.forEachIndexed { index, zone ->
                if (index > 0) HorizontalDivider()
                TextButton(
                    onClick = { onSwitchRoom(zone) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_speaker_device),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = zone.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (zone.id == state.activeZone.id) FontWeight.Bold else FontWeight.Normal
                        )
                        if (zone.id == state.activeZone.id) {
                            Text(
                                text = "Current widget room",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerRow(
    zone: Zone,
    isActive: Boolean,
    isDefault: Boolean,
    onToggleDefault: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_speaker_device),
            contentDescription = null,
            tint = if (isActive) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = zone.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
            )
            if (isActive) {
                Text(
                    text = "Active on widget",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(onClick = onToggleDefault) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = if (isDefault) "Clear default room" else "Set as default room",
                tint = if (isDefault) Color(0xFFF5B942)
                    else MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

@Composable
private fun ManualIpRow(
    ip: String,
    testResult: Boolean?,
    onTest: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = ip,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )

        when (testResult) {
            null -> CircularProgressIndicator(modifier = Modifier.size(16.dp))
            true -> Icon(
                Icons.Default.Check,
                contentDescription = "Reachable",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            false -> Icon(
                Icons.Default.Close,
                contentDescription = "Unreachable",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        TextButton(onClick = onTest) {
            Text("Test", style = MaterialTheme.typography.labelSmall)
        }

        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove IP",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServicePicker(
    selectedService: String?,
    onSelect: (String) -> Unit
) {
    // Common Sonos-compatible music services
    val services = listOf(
        "spotify" to "Spotify",
        "apple_music" to "Apple Music",
        "amazon_music" to "Amazon Music",
        "youtube_music" to "YouTube Music",
        "tidal" to "Tidal",
        "deezer" to "Deezer",
        "pandora" to "Pandora",
        "sonos_radio" to "Sonos Radio",
        "library" to "Local Library"
    )

    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = services.find { it.first == selectedService }?.second ?: "None"

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            services.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(id)
                        expanded = false
                    }
                )
            }
        }
    }
}
