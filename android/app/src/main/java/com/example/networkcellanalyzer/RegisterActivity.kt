package com.example.networkcellanalyzer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class RegisterActivity : AppCompatActivity() {

    private lateinit var nameEditText: TextInputEditText
    private lateinit var emailEditText: TextInputEditText
    private lateinit var passwordEditText: TextInputEditText
    private lateinit var confirmPasswordEditText: TextInputEditText
    private lateinit var registerButton: MaterialButton
    private lateinit var backToLoginButton: MaterialButton

    companion object {
        private const val API_URL = "${BuildConfig.API_BASE_URL}/register"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Initialize UI elements
        nameEditText = findViewById(R.id.nameEditText)
        emailEditText = findViewById(R.id.emailEditText)
        passwordEditText = findViewById(R.id.passwordEditText)
        confirmPasswordEditText = findViewById(R.id.confirmPasswordEditText)
        registerButton = findViewById(R.id.registerButton)
        backToLoginButton = findViewById(R.id.backToLoginButton)

        // Click listeners
        registerButton.setOnClickListener {
            registerUser()
        }

        backToLoginButton.setOnClickListener {
            finish()
        }
    }

    private fun registerUser() {
        val name = nameEditText.text.toString().trim()
        val email = emailEditText.text.toString().trim()
        val password = passwordEditText.text.toString().trim()
        val confirmPassword = confirmPasswordEditText.text.toString().trim()

        if (name.isEmpty()) {
            nameEditText.error = "Name is required"
            return
        }

        if (email.isEmpty()) {
            emailEditText.error = "Email is required"
            return
        }

        if (!isValidEmail(email)) {
            emailEditText.error = "Enter a valid email address"
            return
        }

        if (password.isEmpty()) {
            passwordEditText.error = "Password is required"
            return
        }

        if (password.length < 6) {
            passwordEditText.error = "Password must be at least 6 characters"
            return
        }

        if (confirmPassword.isEmpty()) {
            confirmPasswordEditText.error = "Confirm your password"
            return
        }

        if (password != confirmPassword) {
            confirmPasswordEditText.error = "Passwords do not match"
            return
        }

        sendRegistrationToApi(name, email, password)
    }

    private fun isValidEmail(email: String): Boolean {
        return android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()
    }

    private fun sendRegistrationToApi(name: String, email: String, password: String) {
        registerButton.isEnabled = false

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL(API_URL)
                val httpURLConnection = url.openConnection() as HttpURLConnection
                httpURLConnection.requestMethod = "POST"
                httpURLConnection.setRequestProperty("Content-Type", "application/json")
                httpURLConnection.doOutput = true
                httpURLConnection.connectTimeout = 10000
                httpURLConnection.readTimeout = 10000

                val jsonObject = JSONObject().apply {
                    put("name", name)
                    put("email", email)
                    put("password", password)
                }

                val outputStream = BufferedOutputStream(httpURLConnection.outputStream)
                val writer = BufferedWriter(OutputStreamWriter(outputStream, "UTF-8"))
                writer.write(jsonObject.toString())
                writer.flush()
                writer.close()
                outputStream.close()

                val responseCode = httpURLConnection.responseCode

                withContext(Dispatchers.Main) {
                    if (responseCode in 200..299) {
                        // Navigate to LoginActivity with a success flag
                        val intent = Intent(this@RegisterActivity, LoginActivity::class.java)
                        intent.putExtra("REGISTER_SUCCESS", true)
                        startActivity(intent)
                        finish()
                    } else {
                        val errorMessage = httpURLConnection.errorStream?.bufferedReader()?.use { it.readText() }
                            ?: "Registration failed"
                        Toast.makeText(this@RegisterActivity, "Error: $errorMessage", Toast.LENGTH_LONG).show()
                    }
                    registerButton.isEnabled = true
                }

                httpURLConnection.disconnect()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@RegisterActivity, "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                    registerButton.isEnabled = true
                }
            }
        }
    }

    private fun saveUserToken(token: String) {
        val sharedPreferences = getSharedPreferences("NetworkCellPrefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().apply {
            putString("auth_token", token)
            apply()
        }
    }
}
