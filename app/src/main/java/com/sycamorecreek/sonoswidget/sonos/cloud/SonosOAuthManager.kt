package com.sycamorecreek.sonoswidget.sonos.cloud

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import com.sycamorecreek.sonoswidget.BuildConfig
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Manages the Sonos OAuth 2.0 Authorization Code flow.
 *
 * Flow:
 * 1. [launchLogin] opens a Custom Tab to the Sonos authorization endpoint
 * 2. User signs in and grants access
 * 3. Sonos redirects to https://sycamorecreekconsulting.com/callback?code=...&state=...
 * 4. [handleCallback] exchanges the authorization code for tokens
 * 5. Tokens are stored securely in [TokenStore]
 *
 * Token refresh is handled automatically by [ensureValidToken].
 */
class SonosOAuthManager(
    private val tokenStore: TokenStore,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "SonosOAuthManager"

        // Sonos OAuth endpoints
        private const val AUTH_BASE = "https://api.sonos.com/login/v3/oauth"
        private const val TOKEN_URL = "https://api.sonos.com/login/v3/oauth/access"

        // App credentials — loaded from local.properties via BuildConfig (gitignored)
        val CLIENT_ID = BuildConfig.SONOS_CLIENT_ID
        val CLIENT_SECRET = BuildConfig.SONOS_CLIENT_SECRET

        const val REDIRECT_URI = "https://sycamorecreekconsulting.com/callback"
        private const val SCOPE = "playback-control-all"
    }

    /**
     * Opens a Custom Tab to the Sonos login page.
     * Generates a random state parameter for CSRF protection.
     *
     * @return The state parameter used, so the callback can verify it.
     */
    fun launchLogin(context: Context): String {
        val state = java.util.UUID.randomUUID().toString()

        val authUrl = Uri.parse(AUTH_BASE).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .build()

        Log.d(TAG, "Launching OAuth: $authUrl")

        val customTabsIntent = CustomTabsIntent.Builder().build()
        customTabsIntent.launchUrl(context, authUrl)

        return state
    }

    /**
     * Exchanges an authorization code for access and refresh tokens.
     *
     * Called when the app receives the https://sycamorecreekconsulting.com/callback redirect.
     *
     * @param code  The authorization code from the callback URI
     * @return true if tokens were obtained and stored successfully
     */
    suspend fun handleCallback(code: String): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Exchanging authorization code for tokens")

        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", REDIRECT_URI)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .addHeader("Authorization", okhttp3.Credentials.basic(CLIENT_ID, CLIENT_SECRET))
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token exchange failed: HTTP ${response.code} — ${response.body?.string()}")
                    return@withContext false
                }

                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)

                val accessToken = json.getString("access_token")
                val refreshToken = json.getString("refresh_token")
                val expiresIn = json.getLong("expires_in")

                tokenStore.saveTokens(accessToken, refreshToken, expiresIn)
                Log.d(TAG, "OAuth tokens obtained successfully")
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Token exchange network error", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange parse error", e)
            false
        }
    }

    /**
     * Refreshes the access token using the stored refresh token.
     *
     * @return true if the token was refreshed successfully
     */
    suspend fun refreshAccessToken(): Boolean = withContext(Dispatchers.IO) {
        val refreshToken = tokenStore.refreshToken
        if (refreshToken == null) {
            Log.w(TAG, "No refresh token available")
            return@withContext false
        }

        Log.d(TAG, "Refreshing access token")

        val formBody = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .addHeader("Authorization", okhttp3.Credentials.basic(CLIENT_ID, CLIENT_SECRET))
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody)
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Token refresh failed: HTTP ${response.code} — ${response.body?.string()}")
                    if (response.code == 401) {
                        Log.w(TAG, "Refresh token revoked, clearing tokens")
                        tokenStore.clearTokens()
                    }
                    return@withContext false
                }

                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)

                val newAccessToken = json.getString("access_token")
                val expiresIn = json.getLong("expires_in")
                val newRefreshToken: String? = if (json.has("refresh_token")) json.getString("refresh_token") else null

                tokenStore.updateAccessToken(newAccessToken, expiresIn, newRefreshToken)
                Log.d(TAG, "Access token refreshed successfully")
                true
            }
        } catch (e: IOException) {
            Log.e(TAG, "Token refresh network error", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Token refresh parse error", e)
            false
        }
    }

    /**
     * Ensures a valid access token is available, refreshing if needed.
     *
     * @return A valid access token, or null if login is required
     */
    suspend fun ensureValidToken(): String? {
        if (!tokenStore.isLoggedIn) return null

        if (tokenStore.isAccessTokenExpired) {
            if (!refreshAccessToken()) return null
        }

        return tokenStore.accessToken
    }

    val isLoggedIn: Boolean
        get() = tokenStore.isLoggedIn

    fun logout() {
        tokenStore.clearTokens()
        Log.d(TAG, "User logged out")
    }
}
