package com.example.networkcellanalyzer
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.*
import android.provider.Settings
import android.telephony.*
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import android.view.Menu
import android.view.MenuItem
import com.example.networkcellanalyzer.databinding.ActivityMainBinding

class MainActivity : ComponentActivity() {

    private lateinit var fetchButton: MaterialButton

    private lateinit var operatorText: TextView
    private lateinit var signalPowerText: TextView
    private lateinit var sinrText: TextView
    private lateinit var networkTypeText: TextView
    private lateinit var frequencyBandText: TextView
    private lateinit var cellIdText: TextView
    private lateinit var timestampText: TextView

    private lateinit var telephonyManager: TelephonyManager
    private val PREFS_NAME = "NetworkCellPrefs"
    private lateinit var username: String
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var welcomeText: TextView //not implemented yet
    private lateinit var guestBackButton: MaterialButton


    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            fetchCellInfo()
        } else {
            Toast.makeText(
                this,
                "Permissions required to access cell information",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val fetchHandler = Handler(Looper.getMainLooper())
    private val fetchRunnable = object : Runnable {
        override fun run() {
            checkPermissionsAndFetchCellInfo()
            fetchHandler.postDelayed(this, 10000)
        }
    }
    private lateinit var binding: ActivityMainBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)  // Use binding.root instead of R.layout.activity_main

        // Now you can use binding
        binding.btnStatistics.setOnClickListener {
            startActivity(Intent(this, StatisticsActivity::class.java))
        }

        // Initialize shared preferences once
        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check login status
        if (!sharedPreferences.getBoolean("isLoggedIn", false) &&
            !sharedPreferences.getBoolean("isGuest", false)) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // Disable stats button if guest
        if (sharedPreferences.getBoolean("isGuest", false)) {
            binding.btnStatistics.isEnabled = false
            binding.btnStatistics.alpha = 0.5f  // Optional: make it look disabled
        }
        // Get username and email
        username = intent.getStringExtra("username")
            ?: sharedPreferences.getString("username", "User") ?: "User"


        // Setup logout/back button
        val guestBackButton = findViewById<MaterialButton>(R.id.logoutButton)
        if (sharedPreferences.getBoolean("isGuest", false)) {
            guestBackButton.text = "Back to Login"
        } else {
            guestBackButton.text = "Log Out"
        }
        guestBackButton.setOnClickListener {
            logout()
        }

        // Initialize UI components
        fetchButton = findViewById(R.id.fetchButton)
        operatorText = findViewById(R.id.operatorText)
        signalPowerText = findViewById(R.id.signalPowerText)
        sinrText = findViewById(R.id.sinrText)
        networkTypeText = findViewById(R.id.networkTypeText)
        frequencyBandText = findViewById(R.id.frequencyBandText)
        cellIdText = findViewById(R.id.cellIdText)
        timestampText = findViewById(R.id.timestampText)

        fetchButton.setOnClickListener {
            checkPermissionsAndFetchCellInfo()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_logout -> {
                logout()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    private fun logout() {
        sharedPreferences.edit().apply {
            putBoolean("isLoggedIn", false)
            putBoolean("isGuest", false)
            apply()
        }

        val intent = Intent(this, LoginActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
    }
    override fun onResume() {
        super.onResume()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        checkPermissionsAndFetchCellInfo() // ensure first fetch triggers permission check
        fetchHandler.post(fetchRunnable)
    }

    override fun onPause() {
        super.onPause()
        fetchHandler.removeCallbacks(fetchRunnable)
    }

    private fun checkPermissionsAndFetchCellInfo() {
        if (hasRequiredPermissions()) {
            fetchCellInfo()
        } else {
            requestPermissionLauncher.launch(requiredPermissions)
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return requiredPermissions.all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun fetchCellInfo() {
        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please enable location services for full cell info", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }

        val dateFormat = SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault())
        val timestamp = dateFormat.format(Date())
        timestampText.text = timestamp

        val operatorName = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            telephonyManager.networkOperatorName.ifEmpty { "Unknown" }
        } else {
            "Permission denied"
        }
        operatorText.text = operatorName

        val networkType = if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            getNetworkTypeString(telephonyManager.dataNetworkType)
        } else {
            "Permission denied"
        }
        networkTypeText.text = networkType

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                val cellInfoList = telephonyManager.allCellInfo
                if (!cellInfoList.isNullOrEmpty()) {
                    processCellInfo(cellInfoList)
                } else {
                    displayUnavailable()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                displayUnavailable()
            }
        } else {
            displayUnavailable()
        }

        val iso8601DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        iso8601DateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val clientTimestampISO = iso8601DateFormat.format(Date())
        val userEmail = sharedPreferences.getString("user_email", "") ?: ""
        val dataToSend = mapOf(
            "userId" to getUserId(),
            "email" to userEmail,
            "clientTimestamp" to clientTimestampISO,
            "operator" to operatorText.text.toString(),
            "signalPower" to signalPowerText.text.toString(),
            "snr" to sinrText.text.toString(), // ✅ Replaces sinr with snr
            "networkType" to networkTypeText.text.toString(),
            "frequencyBand" to frequencyBandText.text.toString(),
            "cellId" to cellIdText.text.toString(),
            "ipAddress" to getLocalIpAddress(),
            "macAddress" to getMacAddress(),
            "deviceBrand" to Build.BRAND

        )


        sendDataToServer(dataToSend)
    }
    @SuppressLint("HardwareIds")
    private fun getUserId(): String {
        val prefs = getSharedPreferences("UserPrefs", Context.MODE_PRIVATE)
        val stored = prefs.getString("userId", null)
        if (!stored.isNullOrBlank()) return stored

        // First time? Get ANDROID_ID and store it
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        prefs.edit().putString("userId", androidId).apply()
        return androidId
    }


    private fun sendDataToServer(data: Map<String, String>) {
        Thread {
            try {
                val json = JSONObject(data)
                val url = URL("${BuildConfig.API_BASE_URL}/upload")

                //val url = URL("https://key-pigeon-creative.ngrok-free.app/upload")

                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.doOutput = true

                val output = conn.outputStream
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.close()

                val responseCode = conn.responseCode
                Log.d("HTTP", "POST Response Code: $responseCode")
                conn.disconnect()
            } catch (e: Exception) {
                Log.e("HTTP", "Error sending data: ${e.message}")
            }
        }.start()
    }


    private fun processCellInfo(cellInfoList: List<CellInfo>) {
        for (cellInfo in cellInfoList) {
            when {
                cellInfo is CellInfoLte && cellInfo.isRegistered -> {
                    processLteCellInfo(cellInfo)
                    return
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        cellInfo is CellInfoNr && cellInfo.isRegistered -> {
                    process5GCellInfo(cellInfo)
                    return
                }
                cellInfo is CellInfoWcdma && cellInfo.isRegistered -> {
                    processWcdmaCellInfo(cellInfo)
                    return
                }
                cellInfo is CellInfoGsm && cellInfo.isRegistered -> {
                    processGsmCellInfo(cellInfo)
                    return
                }
            }
        }
        displayUnavailable()
    }

    private fun processLteCellInfo(cellInfo: CellInfoLte) {
        val identity = cellInfo.cellIdentity as? CellIdentityLte
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthLte

        val dbm = signal?.dbm ?: -999

        // Modified RSSNR handling
        val rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            signal?.rssnr
        } else {
            try {
                // For older Android versions, try to get RSSNR via reflection
                val rssnrMethod = CellSignalStrengthLte::class.java.getDeclaredMethod("getRssnr")
                rssnrMethod.isAccessible = true
                rssnrMethod.invoke(signal) as Int
            } catch (e: Exception) {
                Log.e("CellInfo", "Error getting RSSNR: ${e.message}")
                null
            }
        }

        val earfcn = identity?.earfcn ?: -1
        val tac = identity?.tac ?: -1
        val ci = identity?.ci ?: -1

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"

        // Better handling of RSSNR values
        sinrText.text = when {
            rssnr == null -> "Not available"
            rssnr == Integer.MAX_VALUE -> "Not available"
            rssnr == -999 -> "Not available"
            else -> "$rssnr dB"
        }

        frequencyBandText.text = if (earfcn >= 0) getLteBandFromEarfcn(earfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && ci >= 0) String.format("%05d-%08d", tac, ci) else "Not available"

        Log.d("CellInfo", "EARFCN=$earfcn, RSSNR=$rssnr")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun process5GCellInfo(cellInfo: CellInfoNr) {
        val identity = cellInfo.cellIdentity as? CellIdentityNr
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthNr

        val dbm = signal?.dbm ?: -999

        val ssSinr = signal?.ssSinr?.takeIf { it != CellInfo.UNAVAILABLE } ?: -999
        val csiSinr = signal?.csiSinr?.takeIf { it != CellInfo.UNAVAILABLE } ?: -999

        val sinrToDisplay = when {
            ssSinr != -999 -> ssSinr
            csiSinr != -999 -> csiSinr
            else -> -999
        }

        val nrarfcn = identity?.nrarfcn?.takeIf { it > 0 } ?: -1
        val tac = identity?.tac ?: -1
        val nci = identity?.nci ?: -1

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = if (sinrToDisplay != -999) "$sinrToDisplay dB" else "Not available"
        frequencyBandText.text = if (nrarfcn > 0) getNrBandFromNrarfcn(nrarfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && nci >= 0) String.format("%05d-%08d", tac, nci) else "Not available"

        Log.d("CellInfo5G", "SS-SINR=$ssSinr, CSI-SINR=$csiSinr")
    }
    private fun getGsmBandFromArfcn(arfcn: Int): String {
        return when (arfcn) {
            in 1..124       -> "GSM 900 MHz"
            in 512..885     -> "DCS 1800 MHz"
            in 128..251     -> "GSM 850 MHz"
            in 512..810     -> "PCS 1900 MHz"
            else -> "Unknown ARFCN ($arfcn)"
        }
    }
    private fun getQualityFromDbm(dbm: Int): String {
        return when {
            dbm >= -70 -> "Excellent"
            dbm >= -85 -> "Good"
            dbm >= -100 -> "Fair"
            dbm >= -110 -> "Poor"
            dbm != -999 -> "Very Poor"
            else -> "Not available"
        }
    }

    private fun processWcdmaCellInfo(cellInfo: CellInfoWcdma) {
        val identity = cellInfo.cellIdentity as? CellIdentityWcdma
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthWcdma

        val dbm = signal?.dbm ?: -999
        val lac = identity?.lac ?: -1
        val cid = identity?.cid ?: -1

        val ecio = try {
            val fieldNames = arrayOf("mEcNo", "mEcio", "mEcIo")
            var value: Int? = null
            for (name in fieldNames) {
                try {
                    val field = signal?.javaClass?.getDeclaredField(name)
                    field?.isAccessible = true
                    val temp = field?.getInt(signal)
                    if (temp != null && temp != Integer.MAX_VALUE && temp > -999) {
                        value = temp
                        break
                    }
                } catch (_: Exception) {}
            }
            value?.div(10.0)
        } catch (e: Exception) {
            null
        }

        signalPowerText.text = "$dbm dBm"

        // Display quality based on signal strength when Ec/Io isn't available
        sinrText.text = if (ecio != null) {
            "%.1f dB".format(ecio)
        } else {
            getQualityFromDbm(dbm)
        }

        val uarfcn = identity?.uarfcn ?: -1
        frequencyBandText.text = if (uarfcn >= 0) getWcdmaBandFromUarfcn(uarfcn) else "Not available"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%08d", lac, cid) else "Not available"
    }



    private fun getWcdmaBandFromUarfcn(uarfcn: Int): String {
        return when (uarfcn) {
            in 10562..10838 -> "Band 1 (2100 MHz)"
            in 9662..9938 -> "Band 8 (900 MHz)"
            in 2937..3088 -> "Band 5 (850 MHz)"
            in 4387..4413 -> "Band 2 (1900 MHz)"
            else -> "Unknown UARFCN ($uarfcn)"
        }
    }


    private fun processGsmCellInfo(cellInfo: CellInfoGsm) {
        val identity = cellInfo.cellIdentity as? CellIdentityGsm
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthGsm

        val dbm = signal?.dbm ?: -999
        val lac = identity?.lac ?: -1
        val cid = identity?.cid ?: -1

        // Get BER (Bit Error Rate) if available
        val ber = try {
            val field = signal?.javaClass?.getDeclaredField("mBitErrorRate")
            field?.isAccessible = true
            val value = field?.getInt(signal)
            if (value != null && value in 0..7) value else null  // Only use valid BER values (0-7)
        } catch (e: Exception) {
            Log.d("CellInfo", "Error accessing BER: ${e.message}")
            null
        }

        Log.d("CellInfo", "GSM BER value: $ber, dbm: $dbm")  // Add logging to track what's happening

        signalPowerText.text = "$dbm dBm"

        // Convert BER to a descriptive quality rating with percentage
        sinrText.text = when (ber) {
            0 -> "Excellent (BER < 0.2%)"
            1 -> "Very Good (BER 0.2–0.4%)"
            2 -> "Good (BER 0.4–0.8%)"
            3 -> "OK (BER 0.8–1.6%)"
            4 -> "Fair (BER 1.6–3.2%)"
            5 -> "Poor (BER 3.2–6.4%)"
            6 -> "Very Poor (BER 6.4–12.8%)"
            7 -> "Extremely Poor (BER >12.8%)"
            else -> "${getQualityDescription(dbm)}"
        }

        val arfcn = identity?.arfcn ?: -1
        frequencyBandText.text = if (arfcn >= 0) getGsmBandFromArfcn(arfcn) else "Not available"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%08d", lac, cid) else "Not available"
    }
    private fun getQualityDescription(dbm: Int): String {
        return when {
            dbm >= -70 -> "Excellent"
            dbm >= -85 -> "Good"
            dbm >= -100 -> "Fair"
            dbm >= -110 -> "Poor"
            dbm != -999 -> "Very Poor"
            else -> "Not available"
        }
    }
    private fun displayUnavailable() {
        signalPowerText.text = "Not available"
        sinrText.text = "Not available"
        frequencyBandText.text = "Not available"
        cellIdText.text = "Not available"
    }

    private fun getNetworkTypeString(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> "2G"

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G"

            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G"
            else -> "Unknown"
        }
    }

    private fun getLteBandFromEarfcn(earfcn: Int): String {
        return when {
            earfcn in 0..599 -> "1 (2100MHz)"
            earfcn in 600..1199 -> "2 (1900MHz)"
            earfcn in 1200..1949 -> "3 (1800MHz)"
            earfcn in 1950..2399 -> "4 (1700/2100MHz)"
            earfcn in 2400..2649 -> "5 (850MHz)"
            earfcn in 2750..3449 -> "7 (2600MHz)"
            earfcn in 3450..3799 -> "8 (900MHz)"
            earfcn in 6150..6449 -> "20 (800MHz)"
            earfcn in 65536..66435 -> "66 (AWS-3)"
            else -> "Unknown EARFCN ($earfcn)"
        }
    }

    private fun getNrBandFromNrarfcn(nrarfcn: Int): String {
        return when {
            nrarfcn in 422000..434000 -> "n1 (2100MHz)"
            nrarfcn in 386000..398000 -> "n2 (1900MHz)"
            nrarfcn in 361000..376000 -> "n3 (1800MHz)"
            nrarfcn in 173800..178800 -> "n5 (850MHz)"
            nrarfcn in 524000..538000 -> "n7 (2600MHz)"
            nrarfcn in 185000..192000 -> "n8 (900MHz)"
            nrarfcn in 151600..160600 -> "n20 (800MHz)"
            nrarfcn in 620000..680000 -> "n41 (2500MHz)"
            nrarfcn in 2054166..2104165 -> "n77 (3.7GHz)"
            nrarfcn in 2104166..2154165 -> "n78 (3.5GHz)"
            else -> "Unknown NRARFCN ($nrarfcn)"
        }
    }


    private fun getLocalIpAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in interfaces) {
                val addrs = intf.inetAddresses
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IP", "Error getting IP: ${e.message}")
        }
        return "Unknown"
    }
    private fun getMacAddress(): String {
        return try {
            val all = java.net.NetworkInterface.getNetworkInterfaces()
            for (intf in all) {
                if (intf.name.equals("wlan0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: return "Unavailable"
                    return mac.joinToString(":") { "%02X".format(it) }
                }
            }
            "Unavailable"
        } catch (e: Exception) {
            "Unavailable"
        }
    }



}


