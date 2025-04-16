package com.example.networkcellanalyzer
import android.widget.TextView
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class LoginActivity : AppCompatActivity() {

    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var loginButton: MaterialButton
    private lateinit var registerButton: MaterialButton
    private lateinit var guestButton: MaterialButton

    companion object {
        private const val PREFS_NAME = "NetworkCellPrefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

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

        findViewById<TextView>(R.id.forgotPasswordText).setOnClickListener {
            Toast.makeText(this, "Forgot password functionality coming soon", Toast.LENGTH_SHORT).show()
            // Implement password reset functionality
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

        // In a real app, you would check credentials against a database or an API
        // For this example, we'll use SharedPreferences for simplicity
        val sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedEmail = sharedPreferences.getString("email", "")
        val savedPassword = sharedPreferences.getString("password", "")
        val savedUsername = sharedPreferences.getString("username", "")

        if (email == savedEmail && password == savedPassword) {
            // Successful login
            sharedPreferences.edit().apply {
                putBoolean("isLoggedIn", true)
                putBoolean("isGuest", false)
                apply()
            }

            startMainActivity(savedUsername ?: "")
        } else {
            Toast.makeText(this, "Invalid credentials", Toast.LENGTH_SHORT).show()
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
}