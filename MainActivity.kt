package com.example.networkcellanalyzer

import android.Manifest
import android.annotation.SuppressLint // Needed for ANDROID_ID
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager // Needed for IP/MAC
import android.os.*
import android.provider.Settings // Needed for ANDROID_ID
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
import java.net.InetAddress // Needed for IP
import java.net.NetworkInterface // Needed for IP/MAC
import java.net.URL
import java.text.SimpleDateFormat // Keep for display
import java.util.* // Keep for Date

class MainActivity : ComponentActivity() {

    // ... (keep existing TextViews, Button, telephonyManager, permissions, etc.)
    private lateinit var fetchButton: MaterialButton

    private lateinit var operatorText: TextView
    private lateinit var signalPowerText: TextView
    private lateinit var sinrText: TextView
    private lateinit var networkTypeText: TextView
    private lateinit var frequencyBandText: TextView
    private lateinit var cellIdText: TextView
    private lateinit var timestampText: TextView // This is the display timestamp

    private lateinit var telephonyManager: TelephonyManager

    // --- Add INTERNET and ACCESS_WIFI_STATE for IP/MAC ---
    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE // Added
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_WIFI_STATE // Added
        )
    }

    // ... (keep requestPermissionLauncher, fetchHandler, fetchRunnable)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            fetchCellInfo()
        } else {
            Toast.makeText(
                this,
                "Permissions required to access cell information and network state", // Updated message
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val fetchHandler = Handler(Looper.getMainLooper())
    private val fetchRunnable = object : Runnable {
        override fun run() {
            checkPermissionsAndFetchCellInfo()
            fetchHandler.postDelayed(this, 10000) // 10 seconds
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // --- Initialize Views ---
        fetchButton = findViewById(R.id.fetchButton)
        operatorText = findViewById(R.id.operatorText)
        signalPowerText = findViewById(R.id.signalPowerText)
        sinrText = findViewById(R.id.sinrText)
        networkTypeText = findViewById(R.id.networkTypeText)
        frequencyBandText = findViewById(R.id.frequencyBandText)
        cellIdText = findViewById(R.id.cellIdText)
        timestampText = findViewById(R.id.timestampText) // For display

        fetchButton.setOnClickListener {
            checkPermissionsAndFetchCellInfo()
        }
    }

    override fun onResume() {
        super.onResume()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        checkPermissionsAndFetchCellInfo() // Ensure first fetch triggers permission check if needed
        fetchHandler.post(fetchRunnable) // Start periodic fetching
    }

    override fun onPause() {
        super.onPause()
        fetchHandler.removeCallbacks(fetchRunnable) // Stop periodic fetching
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

    // --- Get Unique User ID ---
    @SuppressLint("HardwareIds") // Suppress warning for ANDROID_ID
    private fun getUserId(): String {
        // ANDROID_ID is unique per app signing key & user on device. Resets on factory reset.
        // Good enough for basic identification in this context.
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
    }

    // --- Get Current IP Address (Best Effort) ---
    private fun getIpAddress(): String? {
        try {
            // Iterate through network interfaces
            for (networkInterface in NetworkInterface.getNetworkInterfaces()) {
                for (inetAddress in networkInterface.inetAddresses) {
                    if (!inetAddress.isLoopbackAddress && inetAddress is java.net.Inet4Address) { // Get IPv4
                        return inetAddress.hostAddress
                    }
                    // Could add IPv6 handling here if needed
                }
            }
        } catch (e: Exception) {
            Log.e("NetworkInfo", "Error getting IP Address: ${e.message}")
        }

        // Fallback using WifiManager (requires ACCESS_WIFI_STATE)
        // Note: This only gets the WiFi IP, not mobile data IP.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED) {
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        Locale.getDefault(), "%d.%d.%d.%d",
                        (ipInt and 0xff), (ipInt shr 8 and 0xff), (ipInt shr 16 and 0xff), (ipInt shr 24 and 0xff)
                    )
                }
            } catch (e: Exception) {
                Log.e("NetworkInfo", "Error getting WiFi IP Address: ${e.message}")
            }
        }
        return null // Return null if not found
    }


    // --- Get MAC Address (Highly Restricted on modern Android) ---
    @SuppressLint("HardwareIds") // Suppress warning for MAC address access
    private fun getMacAddress(): String? {
        // **WARNING:** Accessing MAC address is heavily restricted for privacy since Android 6.
        // This will likely return "02:00:00:00:00:00" on Android 6+ unless it's a system app.
        // Needs ACCESS_FINE_LOCATION and WiFi must be enabled.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w("NetworkInfo", "Permissions missing for MAC address")
            return null
        }
        try {
            // Try NetworkInterface first (often fails on newer Android)
            val all = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (!nif.name.equals("wlan0", ignoreCase = true)) continue // Focus on WiFi interface

                val macBytes = nif.hardwareAddress ?: return null // No hardware address available

                val res1 = StringBuilder()
                for (b in macBytes) {
                    res1.append(String.format("%02X:", b))
                }

                if (res1.isNotEmpty()) {
                    res1.deleteCharAt(res1.length - 1) // Remove last ":"
                    val mac = res1.toString()
                    // Avoid returning the dummy MAC address if possible
                    if (mac != "02:00:00:00:00:00") {
                        return mac
                    }
                }
            }

            // Fallback to WifiManager (also often fails or returns dummy)
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val macAddress = wifiInfo.macAddress // Deprecated and restricted
            if (macAddress != null && macAddress != "02:00:00:00:00:00") {
                return macAddress
            }

        } catch (ex: Exception) {
            Log.e("NetworkInfo", "Error getting MAC address: ${ex.message}")
        }
        // Return null if we couldn't get a meaningful MAC
        return null
    }

    private fun fetchCellInfo() {
        if (!hasRequiredPermissions()) {
            Log.w("Permissions", "Required permissions not granted. Cannot fetch info.")
            return
        }

        if (!isLocationEnabled()) {
            Toast.makeText(this, "Please enable location services for full cell info", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            } catch (e: Exception) {
                Log.e("Location", "Could not open location settings: ${e.message}")
            }
        }

        // --- Get Timestamp for Display ---
        val displayDateFormat = SimpleDateFormat("dd MMM yyyy hh:mm:ss a", Locale.getDefault())
        val displayTimestamp = displayDateFormat.format(Date())
        timestampText.text = displayTimestamp

        // --- Get ISO Timestamp for Server ---
        val iso8601DateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        iso8601DateFormat.timeZone = TimeZone.getTimeZone("UTC")
        val clientTimestampISO = iso8601DateFormat.format(Date())

        // --- Get Operator Name ---
        val operatorName = try {
            telephonyManager.networkOperatorName.ifEmpty { "Unknown" }
        } catch (e: SecurityException) {
            Log.e("Telephony", "Permission denied for Operator Name")
            "Permission denied"
        } catch (e: Exception) {
            Log.e("Telephony", "Error getting Operator Name: ${e.message}")
            "Error"
        }
        operatorText.text = operatorName

        // --- Get Network Type ---
        val networkType = try {
            getNetworkTypeString(telephonyManager.dataNetworkType)
        } catch (e: SecurityException) {
            Log.e("Telephony", "Permission denied for Network Type")
            "Permission denied"
        } catch (e: Exception) {
            Log.e("Telephony", "Error getting Network Type: ${e.message}")
            "Error"
        }
        networkTypeText.text = networkType

        // --- Reset Fields ---
        signalPowerText.text = "Fetching..."
        sinrText.text = "Fetching..."
        frequencyBandText.text = "Fetching..."
        cellIdText.text = "Fetching..."

        try {
            val cellInfoList = telephonyManager.allCellInfo
            if (!cellInfoList.isNullOrEmpty()) {
                processCellInfo(cellInfoList)
            } else {
                Log.w("CellInfo", "allCellInfo list is null or empty.")
                displayUnavailable()
            }
        } catch (e: SecurityException) {
            Log.e("Telephony", "Permission denied for Cell Info")
            displayPermissionDenied()
        } catch (e: Exception) {
            Log.e("CellInfo", "Error fetching allCellInfo: ${e.message}")
            Toast.makeText(this, "Error getting cell details: ${e.message}", Toast.LENGTH_SHORT).show()
            displayUnavailable()
        }

        // --- Prepare Data Payload for Server ---
        val dataToSend = mutableMapOf<String, String?>()

        dataToSend["userId"] = getUserId()
        dataToSend["clientTimestamp"] = clientTimestampISO
        dataToSend["ipAddress"] = getIpAddress()
        dataToSend["macAddress"] = getMacAddress()
        dataToSend["deviceBrand"] = Build.BRAND

        dataToSend["operator"] = operatorText.text.toString()
            .takeIf { it != "Permission denied" && it != "Error" }

        dataToSend["networkType"] = networkTypeText.text.toString()
            .takeIf { it != "Permission denied" && it != "Error" }

        dataToSend["signalPower"] = signalPowerText.text.toString()
            .takeIf { it != "Not available" && it != "Fetching..." && it != "Permission denied" }

        // Replacing SINR with SNR
        dataToSend["snr"] = sinrText.text.toString()
            .takeIf { it != "Not available" && it != "Fetching..." && it != "Permission denied" && !it.contains("N/A") }

        dataToSend["frequencyBand"] = frequencyBandText.text.toString()
            .takeIf { it != "Not available" && it != "Fetching..." && it != "Permission denied" }

        dataToSend["cellId"] = cellIdText.text.toString()
            .takeIf { it != "Not available" && it != "Fetching..." && it != "Permission denied" }

        val finalDataToSend = dataToSend.filterValues { it != null } as Map<String, String>

        sendDataToServer(finalDataToSend)
    }



    private fun sendDataToServer(data: Map<String, String>) {
        if (data.isEmpty()) {
            Log.w("SendData", "No data to send.")
            return
        }
        // Ensure critical fields are present before attempting send
        if (!data.containsKey("userId") || !data.containsKey("clientTimestamp")) {
            Log.e("SendData", "Cannot send data: Missing userId or clientTimestamp.")
            return
        }

        Thread {
            var conn: HttpURLConnection? = null
            try {
                val json = JSONObject(data)
                // IMPORTANT: Use your server's actual IP or hostname accessible from the phone
                // If testing on an emulator talking to localhost: Use 10.0.2.2
                // If testing on a real device on the same WiFi: Use your computer's WiFi IP (e.g., 192.168.1.XX)
                // If using Supabase/cloud: Use the actual cloud URL
                //val url = URL("http://192.168.1.36:5000/upload") // <-- !!! CHECK THIS IP !!!
                val url = URL("http://10.0.2.2:5000/upload") // <-- For emulator testing localhost

                Log.d("SendData", "Sending data to $url: ${json.toString(2)}") // Log formatted JSON

                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; utf-8")
                conn.setRequestProperty("Accept", "application/json") // Expect JSON response
                conn.doOutput = true
                conn.connectTimeout = 10000 // 10 seconds
                conn.readTimeout = 10000 // 10 seconds

                // Write JSON data
                conn.outputStream.use { os ->
                    val input = json.toString().toByteArray(Charsets.UTF_8)
                    os.write(input, 0, input.size)
                    os.flush() // Ensure data is sent
                }

                val responseCode = conn.responseCode
                Log.i("SendData", "POST Response Code: $responseCode")

                // Read response (optional but good for debugging)
                val responseStream = if (responseCode < HttpURLConnection.HTTP_BAD_REQUEST) {
                    conn.inputStream
                } else {
                    conn.errorStream
                }
                val responseText = responseStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                Log.d("SendData", "POST Response Body: $responseText")

                // Optionally show success/error based on response code on UI thread
                // Handler(Looper.getMainLooper()).post {
                //     if (responseCode == HttpURLConnection.HTTP_CREATED) {
                //         Toast.makeText(this, "Data sent successfully", Toast.LENGTH_SHORT).show()
                //     } else {
                //         Toast.makeText(this, "Server error: $responseCode", Toast.LENGTH_LONG).show()
                //     }
                // }

            } catch (e: java.net.ConnectException) {
                Log.e("SendData", "Connection failed: ${e.message}. Check server IP/port and network.")
                // Maybe show a specific error on UI thread
                // Handler(Looper.getMainLooper()).post { Toast.makeText(this, "Cannot connect to server", Toast.LENGTH_LONG).show() }
            } catch (e: java.net.SocketTimeoutException) {
                Log.e("SendData", "Connection timed out: ${e.message}.")
            }
            catch (e: Exception) {
                Log.e("SendData", "Error sending data: ${e.javaClass.simpleName} - ${e.message}", e)
            } finally {
                conn?.disconnect()
            }
        }.start()
    }

    // ... (keep processCellInfo, processLte/5G/Wcdma/Gsm methods) ...
    private fun processCellInfo(cellInfoList: List<CellInfo>) {
        var infoProcessed = false
        for (cellInfo in cellInfoList) {
            when {
                cellInfo is CellInfoLte && cellInfo.isRegistered -> {
                    processLteCellInfo(cellInfo)
                    infoProcessed = true
                    break // Process only the first registered cell
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        cellInfo is CellInfoNr && cellInfo.isRegistered -> {
                    process5GCellInfo(cellInfo)
                    infoProcessed = true
                    break // Process only the first registered cell
                }
                cellInfo is CellInfoWcdma && cellInfo.isRegistered -> {
                    processWcdmaCellInfo(cellInfo)
                    infoProcessed = true
                    break // Process only the first registered cell
                }
                cellInfo is CellInfoGsm && cellInfo.isRegistered -> {
                    processGsmCellInfo(cellInfo)
                    infoProcessed = true
                    break // Process only the first registered cell
                }
            }
        }
        if (!infoProcessed) {
            Log.w("CellInfo", "No registered cell found in the list.")
            displayUnavailable()
        }
    }

    // --- (Make sure process methods update the TextViews correctly) ---
    private fun processLteCellInfo(cellInfo: CellInfoLte) {
        val identity = cellInfo.cellIdentity as? CellIdentityLte
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthLte

        val dbm = signal?.dbm?.takeIf { it != CellInfo.UNAVAILABLE } ?: -999

        val rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // Changed to O for Rssnr
            signal?.rssnr?.takeIf { it != CellInfo.UNAVAILABLE }
        } else null

        val earfcn = identity?.earfcn?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val tac = identity?.tac?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val ci = identity?.ci?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = rssnr?.let { "$it dB" } ?: "Not available" // RSRQ might be available: signal?.rsrq
        frequencyBandText.text = if (earfcn >= 0) getLteBandFromEarfcn(earfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && ci >= 0 && ci != Int.MAX_VALUE) String.format("%05d-%09d", tac, ci) else "Not available" // Adjust format if CI is long

        Log.d("CellInfoLTE", "DBM=$dbm, RSSNR=$rssnr, EARFCN=$earfcn, TAC=$tac, CI=$ci")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun process5GCellInfo(cellInfo: CellInfoNr) {
        // Ensure it's CellIdentityNr and CellSignalStrengthNr
        val identity = cellInfo.cellIdentity as? CellIdentityNr ?: run { Log.w("CellInfo5G","No CellIdentityNr"); return }
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthNr ?: run { Log.w("CellInfo5G","No CellSignalStrengthNr"); return }

        val ssRsrp = signal.ssRsrp.takeIf { it != CellInfo.UNAVAILABLE } ?: -999 // RSRP is more common than DBM for 5G/LTE signal strength display
        val ssSinr = signal.ssSinr.takeIf { it != CellInfo.UNAVAILABLE } ?: -999 // Use SS-SINR
        val csiSinr = signal.csiSinr.takeIf { it != CellInfo.UNAVAILABLE } ?: -999 // CSI-SINR might also be useful

        val nrarfcn = identity.nrarfcn.takeIf { it > 0 && it != CellInfo.UNAVAILABLE } ?: -1
        val tac = identity.tac?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1 // TAC is nullable
        val nci = identity.nci.takeIf { it != Long.MAX_VALUE && it != CellInfo.UNAVAILABLE.toLong() } ?: -1L // NCI is Long

        // Display RSRP in the main signal power field
        signalPowerText.text = if (ssRsrp > -999) "$ssRsrp dBm (RSRP)" else "Not available"
        // Display SS-SINR if available, otherwise CSI-SINR, else N/A
        sinrText.text = when {
            ssSinr != -999 -> "$ssSinr dB (SS-SINR)"
            csiSinr != -999 -> "$csiSinr dB (CSI-SINR)"
            else -> "Not available"
        }
        frequencyBandText.text = if (nrarfcn > 0) getNrBandFromNrarfcn(nrarfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && nci >= 0) String.format("%05d-%010d", tac, nci) else "Not available" // Use %d for long NCI

        Log.d("CellInfo5G", "RSRP=$ssRsrp, SS-SINR=$ssSinr, CSI-SINR=$csiSinr, NRARFCN=$nrarfcn, TAC=$tac, NCI=$nci")
    }

    private fun processWcdmaCellInfo(cellInfo: CellInfoWcdma) {
        val identity = cellInfo.cellIdentity as? CellIdentityWcdma
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthWcdma

        val dbm = signal?.dbm?.takeIf { it != CellInfo.UNAVAILABLE } ?: -999
        // WCDMA might report ASU level: signal?.asuLevel
        val lac = identity?.lac?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val cid = identity?.cid?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val psc = identity?.psc?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1 // Primary Scrambling Code

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = "N/A (3G)" // SINR not typically reported directly for WCDMA via API
        frequencyBandText.text = identity?.uarfcn?.takeIf { it != CellInfo.UNAVAILABLE }?.let { "UARFCN: $it" } ?: "Band Unknown"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%08d (PSC:%d)", lac, cid, psc) else "Not available"

        Log.d("CellInfoWCDMA", "DBM=$dbm, LAC=$lac, CID=$cid, PSC=$psc, UARFCN=${identity?.uarfcn}")
    }

    private fun processGsmCellInfo(cellInfo: CellInfoGsm) {
        val identity = cellInfo.cellIdentity as? CellIdentityGsm
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthGsm

        val dbm = signal?.dbm?.takeIf { it != CellInfo.UNAVAILABLE } ?: -999
        // GSM might report ASU level: signal?.asuLevel
        // GSM timing advance: signal?.timingAdvance
        val lac = identity?.lac?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val cid = identity?.cid?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1
        val bsic = identity?.bsic?.takeIf { it != CellInfo.UNAVAILABLE } ?: -1 // Base Station Identity Code

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = "N/A (2G)" // SINR not applicable to GSM in the same way
        frequencyBandText.text = identity?.arfcn?.takeIf { it != CellInfo.UNAVAILABLE }?.let { "ARFCN: $it" } ?: "Band Unknown"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%05d (BSIC:%d)", lac, cid, bsic) else "Not available"

        Log.d("CellInfoGSM", "DBM=$dbm, LAC=$lac, CID=$cid, BSIC=$bsic, ARFCN=${identity?.arfcn}")
    }

    private fun displayUnavailable() {
        signalPowerText.text = "Not available"
        sinrText.text = "Not available"
        frequencyBandText.text = "Not available"
        cellIdText.text = "Not available"
    }

    private fun displayPermissionDenied() {
        operatorText.text = "Permission denied"
        networkTypeText.text = "Permission denied"
        signalPowerText.text = "Permission denied"
        sinrText.text = "Permission denied"
        frequencyBandText.text = "Permission denied"
        cellIdText.text = "Permission denied"
    }

    // ... (keep getNetworkTypeString, getLteBandFromEarfcn, getNrBandFromNrarfcn methods) ...
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
            TelephonyManager.NETWORK_TYPE_HSPAP -> "3G" // TD_SCDMA is also 3G

            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            TelephonyManager.NETWORK_TYPE_NR -> "5G" // This indicates 5G NSA or SA connection state reporting NR
            TelephonyManager.NETWORK_TYPE_IWLAN -> "WiFi Calling" // Often uses TelephonyManager
            TelephonyManager.NETWORK_TYPE_GSM -> "2G (GSM)" // Explicit GSM
            // Add more specific types if needed
            else -> "Unknown ($networkType)"
        }
    }
    // --- Band Info (keep as is, maybe add more bands) ---
    private fun getLteBandFromEarfcn(earfcn: Int): String {
        // (Your existing Earfcn mapping logic here)
        return when {
            earfcn in 0..599 -> "B1 (2100MHz)"
            earfcn in 600..1199 -> "B2 (1900MHz)"
            earfcn in 1200..1949 -> "B3 (1800MHz)"
            earfcn in 1950..2399 -> "B4 (AWS-1 1700/2100MHz)"
            earfcn in 2400..2649 -> "B5 (850MHz)"
            // earfcn in 2650..2749 -> "B6" // Not commonly used
            earfcn in 2750..3449 -> "B7 (2600MHz)"
            earfcn in 3450..3799 -> "B8 (900MHz)"
            // earfcn in 3800..4149 -> "B9" // Not commonly used
            // ... add more bands as needed from standards documents (e.g., 3GPP TS 36.101)
            earfcn in 5010..5179 -> "B12 (700MHz a)"
            earfcn in 5180..5279 -> "B13 (700MHz c)"
            earfcn in 5280..5379 -> "B14 (700MHz Public Safety)"
            earfcn in 5730..5849 -> "B17 (700MHz b)"
            earfcn in 5850..5999 -> "B18 (850MHz Lower)"
            earfcn in 6000..6149 -> "B19 (850MHz Upper)"
            earfcn in 6150..6449 -> "B20 (800MHz DD)"
            earfcn in 6600..7399 -> "B25 (1900MHz + G)"
            earfcn in 8040..8689 -> "B26 (850MHz + Ext)"
            earfcn in 8690..9039 -> "B27 (800MHz SMR)"
            earfcn in 9040..9209 -> "B28 (700MHz APT)"
            earfcn in 9210..9659 -> "B29 (700MHz SDL)"
            earfcn in 9660..9769 -> "B30 (2300MHz WCS)"
            earfcn in 36000..36199 -> "B38 (TDD 2600MHz)"
            earfcn in 36200..37749 -> "B39 (TDD 1900MHz)"
            earfcn in 37750..41589 -> "B40 (TDD 2300MHz)"
            earfcn in 41590..43589 -> "B41 (TDD 2500MHz)" // Often used for Sprint/T-Mobile
            earfcn in 43590..45589 -> "B42 (TDD 3500MHz)"
            earfcn in 45590..46589 -> "B43 (TDD 3700MHz)"
            earfcn in 46790..54539 -> "B46 (LAA 5GHz)"
            earfcn in 55240..56739 -> "B48 (CBRS 3.5GHz)"
            earfcn in 65536..66435 -> "B66 (AWS-3 1700/2100MHz)"
            earfcn in 66436..67335 -> "B71 (600MHz)"
            else -> "Unknown EARFCN ($earfcn)"
        }
    }

    private fun getNrBandFromNrarfcn(nrarfcn: Int): String {
        // (Your existing Nrarfcn mapping logic here - maybe add more)
        return when {
            nrarfcn in 422000..434000 -> "n1 (2100MHz)"
            nrarfcn in 386000..398000 -> "n2 (1900MHz)"
            nrarfcn in 361000..376000 -> "n3 (1800MHz)"
            nrarfcn in 173800..178800 -> "n5 (850MHz)"
            nrarfcn in 524000..538000 -> "n7 (2600MHz)"
            nrarfcn in 185000..192000 -> "n8 (900MHz)"
            nrarfcn in 151600..160600 -> "n20 (800MHz)"
            nrarfcn in 160600..167800 -> "n25 (1900MHz)"
            nrarfcn in 140800..146600 -> "n28 (700MHz)"
            nrarfcn in 620000..680000 -> "n41 (2500MHz TDD)" // Common T-Mobile/Sprint band
            nrarfcn in 434000..444000 -> "n66 (AWS-3 1700/2100MHz)"
            nrarfcn in 122400..132400 -> "n71 (600MHz)" // Common T-Mobile band
            nrarfcn in 2054166..2104165 -> "n77 (3.7GHz C-Band TDD)" // Common C-Band
            nrarfcn in 2104166..2154165 -> "n78 (3.5GHz C-Band TDD)" // Common C-Band
            nrarfcn in 2016667..2050000 -> "n257 (28GHz mmWave)"
            nrarfcn in 2070834..2083333 -> "n260 (39GHz mmWave)"
            nrarfcn in 2008333..2016667 -> "n261 (28GHz mmWave)"
            // Add more bands from 3GPP TS 38.101-1 (FR1) and 38.101-2 (FR2)
            else -> "Unknown NRARFCN ($nrarfcn)"
        }
    }
}