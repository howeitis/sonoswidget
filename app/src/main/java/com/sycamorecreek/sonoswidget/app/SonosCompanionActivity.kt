package com.sycamorecreek.sonoswidget.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sycamorecreek.sonoswidget.data.SonosPreferences
import com.sycamorecreek.sonoswidget.data.SonosRepository
import com.sycamorecreek.sonoswidget.service.AlbumArtLoader
import com.sycamorecreek.sonoswidget.service.PlaybackService
import com.sycamorecreek.sonoswidget.service.WidgetStateStore
import com.sycamorecreek.sonoswidget.sonos.cloud.CloudSonosController
import com.sycamorecreek.sonoswidget.sonos.cloud.SonosCloudApi
import com.sycamorecreek.sonoswidget.sonos.cloud.SonosOAuthManager
import com.sycamorecreek.sonoswidget.sonos.cloud.TokenStore
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
 * Companion app activity for Sonos OAuth login and settings.
 *
 * Provides:
 *   - Sonos OAuth 2.0 login/logout
 *   - Manual speaker IP entry with connection test
 *   - Default zone selection from discovered speakers
 *   - Preferred music service picker
 *   - Cache management (size display + clear)
 *   - Widget refresh button
 *
 * Uses standard Jetpack Compose (NOT Glance) for the UI.
 */
class SonosCompanionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SonosCompanionActivity"
    }

    private lateinit var tokenStore: TokenStore
    private lateinit var oAuthManager: SonosOAuthManager
    private lateinit var cloudController: CloudSonosController
    private lateinit var preferences: SonosPreferences

    private var pendingState: String? = null

    // UI state
    private var uiState by mutableStateOf(CompanionUiState.IDLE)
    private var statusMessage by mutableStateOf("")
    private var isLoggedIn by mutableStateOf(false)

    // Settings state
    private val manualIps = mutableStateListOf<String>()
    private val ipTestResults = mutableStateMapOf<String, Boolean?>()
    private var discoveredZones by mutableStateOf<List<Zone>>(emptyList())
    private var defaultZoneName by mutableStateOf<String?>(null)
    private var defaultZoneId by mutableStateOf<String?>(null)
    private var preferredService by mutableStateOf<String?>(null)
    private var cacheSizeBytes by mutableLongStateOf(0L)

    // Connection status state
    private var connectionStatus by mutableStateOf("Unknown")
    private var connectedSpeakerName by mutableStateOf<String?>(null)
    private var connectedSpeakerIp by mutableStateOf<String?>(null)
    private var connectionMethod by mutableStateOf<String?>(null)
    private var isOnWifi by mutableStateOf(false)
    private var hasNearbyPermission by mutableStateOf(false)
    private var isScanning by mutableStateOf(false)

    private val scope = CoroutineScope(Dispatchers.Main)

    // Runtime permission launcher for NEARBY_WIFI_DEVICES (Android 13+ / targetSdk 33+)
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

        // Check if launched from an OAuth callback redirect
        handleIntent(intent)

        // Request NEARBY_WIFI_DEVICES permission if needed (Android 13+)
        requestNearbyWifiPermissionIfNeeded()

        // Load persisted settings
        loadSettings()

        setContent {
            MaterialTheme {
                Scaffold { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        CompanionScreen(
                            uiState = uiState,
                            statusMessage = statusMessage,
                            isLoggedIn = isLoggedIn,
                            onSignIn = ::startOAuthLogin,
                            onSignOut = ::signOut,
                            connectionStatus = connectionStatus,
                            connectedSpeakerName = connectedSpeakerName,
                            connectedSpeakerIp = connectedSpeakerIp,
                            connectionMethod = connectionMethod,
                            isOnWifi = isOnWifi,
                            hasNearbyPermission = hasNearbyPermission,
                            isScanning = isScanning,
                            onScan = ::scanForSpeakers,
                            manualIps = manualIps,
                            ipTestResults = ipTestResults,
                            onAddIp = ::addManualIp,
                            onRemoveIp = ::removeManualIp,
                            onTestIp = ::testManualIp,
                            discoveredZones = discoveredZones,
                            defaultZoneName = defaultZoneName,
                            onSelectDefaultZone = ::selectDefaultZone,
                            onClearDefaultZone = ::clearDefaultZone,
                            preferredService = preferredService,
                            onSelectService = ::selectPreferredService,
                            cacheSizeBytes = cacheSizeBytes,
                            onClearCache = ::clearImageCache,
                            onRefreshWidget = ::refreshWidget
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // ──────────────────────────────────────────────
    // OAuth handling
    // ──────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return

        val isHttpsCallback = data.scheme == "https" && data.host == "sycamorecreekconsulting.com" && data.path == "/callback"
        val isCustomCallback = data.scheme == "sonoswidget" && data.host == "callback"
        if (isHttpsCallback || isCustomCallback) {
            val code = data.getQueryParameter("code")
            val state = data.getQueryParameter("state")
            val error = data.getQueryParameter("error")

            if (error != null) {
                Log.e(TAG, "OAuth error: $error")
                uiState = CompanionUiState.ERROR
                statusMessage = "Authorization denied: $error"
                return
            }

            if (code == null) {
                Log.e(TAG, "No authorization code in callback")
                uiState = CompanionUiState.ERROR
                statusMessage = "No authorization code received"
                return
            }

            // Verify state parameter for CSRF protection
            if (pendingState != null && state != pendingState) {
                Log.e(TAG, "State mismatch: expected=$pendingState, got=$state")
                uiState = CompanionUiState.ERROR
                statusMessage = "Security check failed (state mismatch)"
                return
            }

            exchangeCode(code)
        }
    }

    private fun startOAuthLogin() {
        uiState = CompanionUiState.LOADING
        statusMessage = "Opening Sonos login..."
        pendingState = oAuthManager.launchLogin(this)
    }

    private fun exchangeCode(code: String) {
        uiState = CompanionUiState.LOADING
        statusMessage = "Signing in..."

        scope.launch {
            val success = oAuthManager.handleCallback(code)
            if (success) {
                isLoggedIn = true
                uiState = CompanionUiState.SUCCESS
                statusMessage = "Successfully signed in to Sonos!"
                Log.d(TAG, "OAuth flow completed successfully — triggering discovery")
                // Trigger immediate discovery now that cloud credentials are available
                PlaybackService.pollNow(applicationContext)
            } else {
                uiState = CompanionUiState.ERROR
                statusMessage = "Failed to complete sign in. Please try again."
                Log.e(TAG, "Token exchange failed")
            }
        }
    }

    private fun signOut() {
        oAuthManager.logout()
        cloudController.clearCache()
        isLoggedIn = false
        uiState = CompanionUiState.IDLE
        statusMessage = ""
        Log.d(TAG, "User signed out")
    }

    // ──────────────────────────────────────────────
    // Permission handling
    // ──────────────────────────────────────────────

    private fun requestNearbyWifiPermissionIfNeeded() {
        // NEARBY_WIFI_DEVICES is only required on Android 13 (API 33) and above
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
            // Load manual IPs
            val ips = withContext(Dispatchers.IO) { preferences.getManualIps() }
            manualIps.clear()
            manualIps.addAll(ips)

            // Load default zone
            val zone = withContext(Dispatchers.IO) { preferences.getDefaultZone() }
            defaultZoneId = zone?.id
            defaultZoneName = zone?.name

            // Load preferred service
            preferredService = withContext(Dispatchers.IO) { preferences.getPreferredService() }

            // Load discovered zones from widget state
            val repo = SonosRepository.getInstance(applicationContext)
            discoveredZones = repo.widgetState.value.zones

            // Load cache size
            cacheSizeBytes = withContext(Dispatchers.IO) {
                AlbumArtLoader.getDiskCacheSize(applicationContext)
            }

            // Refresh connection status
            refreshConnectionStatus()
        }
    }

    private fun refreshConnectionStatus() {
        val repo = SonosRepository.getInstance(applicationContext)
        val state = repo.widgetState.value

        // Check prerequisites
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        isOnWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        hasNearbyPermission = repo.hasLocalNetworkPermission()

        // Determine connection info from widget state
        val mode = state.connectionMode
        connectionMethod = when (mode) {
            com.sycamorecreek.sonoswidget.widget.ConnectionMode.LOCAL_SSDP -> "Auto-discovery (SSDP)"
            com.sycamorecreek.sonoswidget.widget.ConnectionMode.LOCAL_MDNS -> "Auto-discovery (mDNS)"
            com.sycamorecreek.sonoswidget.widget.ConnectionMode.LOCAL_MANUAL_IP -> "Manual IP"
            com.sycamorecreek.sonoswidget.widget.ConnectionMode.CLOUD -> "Cloud API"
            com.sycamorecreek.sonoswidget.widget.ConnectionMode.DISCONNECTED -> null
        }

        if (mode != com.sycamorecreek.sonoswidget.widget.ConnectionMode.DISCONNECTED) {
            connectionStatus = "Connected"
            connectedSpeakerName = state.activeZone.displayName.ifBlank { null }
            // IP comes from saved prefs
            scope.launch {
                val saved = withContext(Dispatchers.IO) {
                    preferences.activeSpeaker.first()
                }
                connectedSpeakerIp = saved?.ip
            }
        } else {
            connectionStatus = if (state.isOffline) "Offline" else "Disconnected"
            connectedSpeakerName = null
            connectedSpeakerIp = null
        }
    }

    private fun scanForSpeakers() {
        if (isScanning) return
        isScanning = true
        connectionStatus = "Scanning..."
        scope.launch {
            try {
                val repo = SonosRepository.getInstance(applicationContext)
                val found = withContext(Dispatchers.IO) {
                    repo.discoverAndConnect()
                }
                if (found) {
                    // Trigger an immediate poll to populate full state
                    withContext(Dispatchers.IO) {
                        repo.pollAndUpdate()
                    }
                    refreshConnectionStatus()
                    // Reload zones
                    discoveredZones = repo.widgetState.value.zones
                    PlaybackService.pollNow(applicationContext)
                } else {
                    connectionStatus = "No speakers found"
                    connectionMethod = null
                    connectedSpeakerName = null
                    connectedSpeakerIp = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scan failed", e)
                connectionStatus = "Scan failed: ${e.message}"
            } finally {
                isScanning = false
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
        scope.launch(Dispatchers.IO) {
            preferences.saveDefaultZone(zone.id, zone.displayName)
        }
    }

    private fun clearDefaultZone() {
        defaultZoneId = null
        defaultZoneName = null
        scope.launch(Dispatchers.IO) {
            preferences.clearDefaultZone()
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
                uiState = CompanionUiState.SUCCESS
                statusMessage = "Widget refreshed"
                Log.d(TAG, "Manual widget refresh triggered")
            } catch (e: Exception) {
                Log.e(TAG, "Widget refresh failed", e)
                uiState = CompanionUiState.ERROR
                statusMessage = "Refresh failed: ${e.message}"
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

private enum class CompanionUiState {
    IDLE, LOADING, SUCCESS, ERROR
}

// ──────────────────────────────────────────────
// Compose UI
// ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompanionScreen(
    uiState: CompanionUiState,
    statusMessage: String,
    isLoggedIn: Boolean,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    connectionStatus: String,
    connectedSpeakerName: String?,
    connectedSpeakerIp: String?,
    connectionMethod: String?,
    isOnWifi: Boolean,
    hasNearbyPermission: Boolean,
    isScanning: Boolean,
    onScan: () -> Unit,
    manualIps: List<String>,
    ipTestResults: Map<String, Boolean?>,
    onAddIp: (String) -> Unit,
    onRemoveIp: (String) -> Unit,
    onTestIp: (String) -> Unit,
    discoveredZones: List<Zone>,
    defaultZoneName: String?,
    onSelectDefaultZone: (Zone) -> Unit,
    onClearDefaultZone: () -> Unit,
    preferredService: String?,
    onSelectService: (String) -> Unit,
    cacheSizeBytes: Long,
    onClearCache: () -> Unit,
    onRefreshWidget: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Header ──
        Text(
            text = "Sonos Widget",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Settings",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ── Connection Status Section ──
        SettingsSection(title = "Connection Status") {
            // Status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = when (connectionStatus) {
                    "Connected" -> MaterialTheme.colorScheme.primary
                    "Scanning..." -> MaterialTheme.colorScheme.tertiary
                    "Offline", "Disconnected", "No speakers found" -> MaterialTheme.colorScheme.error
                    else -> if (connectionStatus.startsWith("Scan failed")) MaterialTheme.colorScheme.error
                           else MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    imageVector = when (connectionStatus) {
                        "Connected" -> Icons.Default.Check
                        else -> Icons.Default.Close
                    },
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = connectionStatus,
                    style = MaterialTheme.typography.bodyLarge,
                    color = statusColor
                )
            }

            // Connection details when connected
            if (connectedSpeakerName != null) {
                Spacer(modifier = Modifier.height(8.dp))
                StatusRow("Speaker", connectedSpeakerName!!)
            }
            if (connectedSpeakerIp != null) {
                StatusRow("IP", connectedSpeakerIp!!)
            }
            if (connectionMethod != null) {
                StatusRow("Method", connectionMethod!!)
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Prerequisites
            StatusRow("Wi-Fi", if (isOnWifi) "Connected" else "Not connected")
            StatusRow("Nearby Devices Permission", if (hasNearbyPermission) "Granted" else "Not granted")

            if (!isOnWifi) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Auto-discovery requires Wi-Fi connection to the same network as your Sonos speakers",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (!hasNearbyPermission) {
                Spacer(modifier = Modifier.height(4.dp))
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
                Text(if (isScanning) "Scanning..." else "Scan for Speakers")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Cloud Account Section ──
        SettingsSection(title = "Sonos Account") {
            if (isLoggedIn) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Connected to Sonos",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    OutlinedButton(onClick = onSignOut) {
                        Text("Sign Out")
                    }
                }
            } else {
                Text(
                    text = "Sign in to enable cloud control when away from home",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onSignIn,
                    enabled = uiState != CompanionUiState.LOADING,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign in with Sonos")
                }
            }

            // Status feedback
            if (statusMessage.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                when (uiState) {
                    CompanionUiState.LOADING -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    CompanionUiState.SUCCESS -> {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    CompanionUiState.ERROR -> {
                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    CompanionUiState.IDLE -> {}
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Manual Speaker IPs Section ──
        SettingsSection(title = "Manual Speaker IPs") {
            Text(
                text = "Add speaker IPs if automatic discovery fails on your network",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Existing IPs
            manualIps.forEach { ip ->
                ManualIpRow(
                    ip = ip,
                    testResult = ipTestResults[ip],
                    onTest = { onTestIp(ip) },
                    onRemove = { onRemoveIp(ip) }
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Add new IP input
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

        Spacer(modifier = Modifier.height(16.dp))

        // ── Default Zone Section ──
        SettingsSection(title = "Default Zone") {
            Text(
                text = "Select the speaker or room to control by default",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (discoveredZones.isEmpty()) {
                Text(
                    text = "No speakers discovered yet. Add the widget to your home screen to start discovery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                ZoneDropdown(
                    zones = discoveredZones,
                    selectedName = defaultZoneName,
                    onSelect = onSelectDefaultZone,
                    onClear = onClearDefaultZone
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Preferred Music Service Section ──
        SettingsSection(title = "Preferred Music Service") {
            Text(
                text = "Default source shown in the widget's source switcher",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            ServicePicker(
                selectedService = preferredService,
                onSelect = onSelectService
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Cache Management Section ──
        SettingsSection(title = "Cache Management") {
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
                    Text("Clear Cache")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Widget Refresh Section ──
        SettingsSection(title = "Widget") {
            Text(
                text = "Force an immediate widget state refresh",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onRefreshWidget,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Refresh Now")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            content()
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

        // Test result indicator
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
private fun ZoneDropdown(
    zones: List<Zone>,
    selectedName: String?,
    onSelect: (Zone) -> Unit,
    onClear: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = selectedName ?: "Auto (last used)",
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
                zones.forEach { zone ->
                    DropdownMenuItem(
                        text = { Text(zone.displayName) },
                        onClick = {
                            onSelect(zone)
                            expanded = false
                        }
                    )
                }
            }
        }

        if (selectedName != null) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Clear default zone",
                    modifier = Modifier.size(20.dp)
                )
            }
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
