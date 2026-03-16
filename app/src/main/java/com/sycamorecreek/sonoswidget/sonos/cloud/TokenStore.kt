package com.sycamorecreek.sonoswidget.sonos.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Sonos OAuth 2.0 tokens.
 *
 * Uses Android Keystore-backed EncryptedSharedPreferences to store:
 *   - Access token (short-lived, typically 24 hours)
 *   - Refresh token (long-lived, used to obtain new access tokens)
 *   - Expiry timestamp (epoch millis when the access token expires)
 *
 * All values are encrypted at rest using AES-256 GCM.
 */
class TokenStore(context: Context) {

    companion object {
        private const val TAG = "TokenStore"
        private const val PREFS_FILE = "sonos_oauth_tokens"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val EXPIRY_BUFFER_MS = 60_000L // refresh 1 minute before actual expiry
    }

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to create EncryptedSharedPreferences, falling back to clear", e)
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    val accessToken: String?
        get() = prefs.getString(KEY_ACCESS_TOKEN, null)

    val refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)

    val isLoggedIn: Boolean
        get() = refreshToken != null

    val isAccessTokenExpired: Boolean
        get() {
            val expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
            return System.currentTimeMillis() >= (expiresAt - EXPIRY_BUFFER_MS)
        }

    /**
     * Stores a new token set from an OAuth token response.
     *
     * @param accessToken  The access token string
     * @param refreshToken The refresh token string
     * @param expiresInSeconds Token lifetime in seconds (from the OAuth response)
     */
    fun saveTokens(accessToken: String, refreshToken: String, expiresInSeconds: Long) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putLong(KEY_EXPIRES_AT, expiresAt)
            .apply()
        Log.d(TAG, "Tokens saved, expires in ${expiresInSeconds}s")
    }

    /**
     * Updates only the access token and expiry after a refresh.
     * The refresh token stays the same unless the server rotates it.
     */
    fun updateAccessToken(accessToken: String, expiresInSeconds: Long, newRefreshToken: String? = null) {
        val expiresAt = System.currentTimeMillis() + (expiresInSeconds * 1000)
        prefs.edit().apply {
            putString(KEY_ACCESS_TOKEN, accessToken)
            putLong(KEY_EXPIRES_AT, expiresAt)
            if (newRefreshToken != null) {
                putString(KEY_REFRESH_TOKEN, newRefreshToken)
            }
            apply()
        }
        Log.d(TAG, "Access token updated, expires in ${expiresInSeconds}s")
    }

    fun clearTokens() {
        prefs.edit().clear().apply()
        Log.d(TAG, "All tokens cleared")
    }
}
