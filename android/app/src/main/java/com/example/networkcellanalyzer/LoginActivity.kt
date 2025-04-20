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

class LoginActivity : AppCompatActivity() {

    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var registerButton: MaterialButton
    private lateinit var guestButton: MaterialButton
    private val coroutineScope = CoroutineScope(Dispatchers.Main + Job())

    companion object {
        private const val PREFS_NAME = "NetworkCellPrefs"
        private const val SERVER_URL = "${BuildConfig.API_BASE_URL}" // Replace with your actual API endpoint
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

        // Get SharedPreferences
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if user is already logged in
        if (sharedPreferences.getBoolean("isLoggedIn", false)) {
            startMainActivity(sharedPreferences.getString("username", "") ?: "")
        }

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
            sharedPreferences.edit().apply {
                putBoolean("isGuest", true)
                apply()
            }

            // Start main activity
            startMainActivity("Guest")
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

        // Show loading indicator (you might want to add a progress bar to your layout)
        loginButton.isEnabled = false
        loginButton.text = "Logging in..."

        // Make API request using coroutines
        coroutineScope.launch {
            try {
                val result = loginWithServer(email, password)
                if (result.first) {
                    // Successful login
                    val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    sharedPreferences.edit().apply {
                        putBoolean("isLoggedIn", true)
                        putBoolean("isGuest", false)
                        putString("username", result.second)
                        putString("user_email", email)
                        putString("userId", result.third)
                        apply()
                    }

                    startMainActivity(result.second)
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
                    Log.e("LoginActivity", "Login error", e)
                }
            }
        }
    }

    private suspend fun loginWithServer(email: String, password: String): Triple<Boolean, String, String> = withContext(Dispatchers.IO) {
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
                    return@withContext Triple(true, name, userId)
                }
            }

            return@withContext Triple(false, "", "")
        } finally {
            connection.disconnect()
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
}