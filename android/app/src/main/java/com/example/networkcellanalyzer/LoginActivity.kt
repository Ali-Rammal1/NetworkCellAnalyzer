package com.example.networkcellanalyzer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.threeten.bp.Instant

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

class LoginActivity : AppCompatActivity() {

    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var registerButton: MaterialButton
    private lateinit var guestButton: MaterialButton
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val PREFS_NAME = "NetworkCellPrefs"
        private const val AUTH_PREFS_NAME = "NetworkCellAuthPrefs" // Separate prefs for auth data
        private const val SERVER_URL = "${BuildConfig.API_BASE_URL}" // Replace with your actual API endpoint
        private const val TAG = "LoginActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_login)
        val registered = intent.getBooleanExtra("REGISTER_SUCCESS", false)
        if (registered) {
            Toast.makeText(this, "✅ Registered successfully! You can now log in.", Toast.LENGTH_LONG).show()
        }
        // Initialize UI elements
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        loginButton = findViewById(R.id.loginButton)
        registerButton = findViewById(R.id.registerButton)
        guestButton = findViewById(R.id.guestButton)

        // Check if user is already logged in and token is valid
        checkExistingAuth()

        // Set up click listeners
        loginButton.setOnClickListener {
            loginUser()
        }

        registerButton.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
        }

        guestButton.setOnClickListener {
            // Save guest status
            val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sharedPreferences.edit().apply {
                putBoolean("isGuest", true)
                putBoolean("isLoggedIn", false) // Ensure logged in flag is off for guests
                apply()
            }

            // Clear any existing auth tokens
            clearAuthToken()

            // Start main activity
            startMainActivity("Guest")
        }
    }

    private fun checkExistingAuth() {
        // Get SharedPreferences
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val authPrefs = getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)

        // Check if user is already logged in
        if (sharedPreferences.getBoolean("isLoggedIn", false)) {
            val username = sharedPreferences.getString("username", "") ?: ""

            // Check token validity
            val token = authPrefs.getString("auth_token", null)
            val expiryString = authPrefs.getString("token_expiry", null)

            if (token != null && expiryString != null && !isTokenExpired(expiryString)) {
                // Valid token exists
                Log.d(TAG, "Valid auth token found, proceeding to main activity")
                startMainActivity(username)
            } else if (token != null) {
                // Token exists but might be expired - validate with server
                validateTokenWithServer(token, username)
            } else {
                // No token, clear login state
                Log.d(TAG, "No auth token found, clearing login state")
                sharedPreferences.edit().putBoolean("isLoggedIn", false).apply()
            }
        }
    }

    private fun validateTokenWithServer(token: String, username: String) {
        coroutineScope.launch {
            try {
                val isValid = withContext(Dispatchers.IO) {
                    validateToken(token)
                }

                if (isValid) {
                    Log.d(TAG, "Token validated with server, proceeding to main activity")
                    startMainActivity(username)
                } else {
                    // Token invalid, clear login state
                    Log.d(TAG, "Token invalid, clearing login state")
                    clearAuthToken()
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean("isLoggedIn", false).apply()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error validating token", e)
                // If we can't reach the server, assume token is valid for now
                // This prevents logout on network issues
                startMainActivity(username)
            }
        }
    }

    private fun loginUser() {
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()

        // Basic validation
        if (email.isEmpty()) {
            emailEditText.error = "Email is required"
            return
        }

        if (password.isEmpty()) {
            passwordEditText.error = "Password is required"
            return
        }

        // Show loading indicator
        loginButton.isEnabled = false
        loginButton.text = "Logging in..."

        // Make API request using coroutines
        coroutineScope.launch {
            try {
                val result = loginWithServer(email, password)
                if (result.success) {
                    // Successful login - store user info and token
                    val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    sharedPreferences.edit().apply {
                        putBoolean("isLoggedIn", true)
                        putBoolean("isGuest", false)
                        putString("username", result.name)
                        putString("user_email", email)
                        putString("userId", result.userId)
                        apply()
                    }

                    // Store the auth token separately
                    storeAuthToken(result.token, result.tokenExpiry)

                    startMainActivity(result.name)
                } else {
                    // Failed login
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LoginActivity, "Invalid credentials", Toast.LENGTH_SHORT).show()
                        loginButton.isEnabled = true
                        loginButton.text = "LOGIN"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@LoginActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    loginButton.isEnabled = true
                    loginButton.text = "LOGIN"
                    Log.e(TAG, "Login error", e)
                }
            }
        }
    }

    private suspend fun loginWithServer(email: String, password: String): LoginResult = withContext(Dispatchers.IO) {
        val url = URL("$SERVER_URL/login")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.doInput = true

            // Create JSON request body
            val jsonBody = JSONObject().apply {
                put("email", email)
                put("password", password)
            }

            // Send request
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)

                if (jsonResponse.getBoolean("success")) {
                    val name = jsonResponse.getString("name")
                    val userId = jsonResponse.getString("id")
                    val token = jsonResponse.getString("token")
                    val tokenExpiry = jsonResponse.getString("expires_at")

                    return@withContext LoginResult(
                        success = true,
                        name = name,
                        userId = userId,
                        token = token,
                        tokenExpiry = tokenExpiry
                    )
                }
            }

            return@withContext LoginResult(success = false)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        val url = URL("$SERVER_URL/validate-token")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")

            // Read response
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val jsonResponse = JSONObject(response)
                return@withContext jsonResponse.getBoolean("success")
            }

            return@withContext false
        } finally {
            connection.disconnect()
        }
    }

    private fun storeAuthToken(token: String, expiresAt: String) {
        val authPrefs = getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
        authPrefs.edit().apply {
            putString("auth_token", token)
            putString("token_expiry", expiresAt)
            apply()
        }
        Log.d(TAG, "Auth token stored successfully, expires: $expiresAt")
    }

    private fun clearAuthToken() {
        val authPrefs = getSharedPreferences(AUTH_PREFS_NAME, Context.MODE_PRIVATE)
        authPrefs.edit().clear().apply()
        Log.d(TAG, "Auth token cleared")
    }

    private fun isTokenExpired(expiryString: String): Boolean {
        return try {
            val expiryInstant = Instant.parse(expiryString)
            val now = Instant.now()
            now.isAfter(expiryInstant)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing token expiry date", e)
            true // If we can't parse the date, assume token is expired
        }
    }

    private fun startMainActivity(username: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra("username", username)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
    }

    // Data class to hold login result
    data class LoginResult(
        val success: Boolean,
        val name: String = "",
        val userId: String = "",
        val token: String = "",
        val tokenExpiry: String = ""
    )
}