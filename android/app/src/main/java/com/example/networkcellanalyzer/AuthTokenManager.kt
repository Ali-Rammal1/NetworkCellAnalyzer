package com.example.networkcellanalyzer

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.threeten.bp.Instant


/**
 * Utility class for managing authentication tokens
 */
class AuthTokenManager(private val context: Context) {

    companion object {
        private const val TAG = "AuthTokenManager"
        private const val AUTH_PREFS_NAME = "NetworkCellAuthPrefs"
        private const val PREFS_NAME = "NetworkCellPrefs"
        private const val SERVER_URL = "${BuildConfig.API_BASE_URL}"
    }

    /**
     * Get the stored authentication token
     */
    fun getAuthToken(): String? {
        val authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
        return authPrefs.getString("auth_token", null)
    }

    /**
     * Check if the current token is expired
     */
    fun isTokenExpired(): Boolean {
        val authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
        val expiryString = authPrefs.getString("token_expiry", null) ?: return true

        return try {
            val expiryInstant = Instant.parse(expiryString)
            val now = Instant.now()
            // Return true if token expires in less than 30 minutes
            now.isAfter(expiryInstant.minusSeconds(30 * 60))
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing token expiry date", e)
            true // If we can't parse the date, assume token is expired
        }
    }

    /**
     * Add authentication header to a HttpURLConnection
     */
    fun addAuthHeader(connection: HttpURLConnection) {
        val token = getAuthToken()
        if (token != null) {
            connection.setRequestProperty("Authorization", "Bearer $token")
        }
    }

    /**
     * Refresh the authentication token
     * Returns true if successful, false otherwise
     */
    suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        val currentToken = getAuthToken() ?: return@withContext false

        val url = URL("$SERVER_URL/refresh-token")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $currentToken")
            connection.doOutput = true
            connection.doInput = true

            // Send empty body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write("{}")
                writer.flush()
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                if (jsonResponse.getBoolean("success")) {
                    val newToken = jsonResponse.getString("token")
                    val expiresAt = jsonResponse.getString("expires_at")

                    // Store the new token
                    val authPrefs = context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
                    authPrefs.edit().apply {
                        putString("auth_token", newToken)
                        putString("token_expiry", expiresAt)
                        apply()
                    }

                    Log.d(TAG, "Token refreshed successfully, new expiry: $expiresAt")
                    return@withContext true
                }
            }

            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing token", e)
            return@withContext false
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Clear authentication data and login state
     */
    fun clearAuthAndLoginState() {
        // Clear auth token
        context.getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()

        // Clear login state
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("isLoggedIn", false).apply()

        Log.d(TAG, "Auth token and login state cleared")
    }

    /**
     * Check if user is logged in (not a guest)
     */
    fun isUserLoggedIn(): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("isLoggedIn", false) && !prefs.getBoolean("isGuest", true)
    }
}