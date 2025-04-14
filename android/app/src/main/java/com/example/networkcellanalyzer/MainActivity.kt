package com.example.networkcellanalyzer

import android.Manifest
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

        val dataToSend = mapOf(
            "operator" to operatorText.text.toString(),
            "signalPower" to signalPowerText.text.toString(),
            "sinr" to sinrText.text.toString(),
            "networkType" to networkTypeText.text.toString(),
            "frequencyBand" to frequencyBandText.text.toString(),
            "cellId" to cellIdText.text.toString(),
            "timestamp" to timestampText.text.toString()
        )
        sendDataToServer(dataToSend)
    }

    private fun sendDataToServer(data: Map<String, String>) {
        Thread {
            try {
                val json = JSONObject(data)
                val url = URL("http://192.168.1.36:5000/upload") // Change to your server IP if using a real device
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

        val rssnr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val value = signal?.rssnr ?: -999
            if (value == Integer.MAX_VALUE || value == -999) null else value
        } else null

        val earfcn = identity?.earfcn ?: -1
        val tac = identity?.tac ?: -1
        val ci = identity?.ci ?: -1

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = rssnr?.let { "$it dB" } ?: "Not available"
        frequencyBandText.text = if (earfcn >= 0) getLteBandFromEarfcn(earfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && ci >= 0) String.format("%05d-%08d", tac, ci) else "Not available"

        Log.d("CellInfo", "EARFCN=$earfcn, RSSNR=$rssnr")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun process5GCellInfo(cellInfo: CellInfoNr) {
        val identity = cellInfo.cellIdentity as? CellIdentityNr
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthNr

        val dbm = signal?.dbm ?: -999
        val sinr = signal?.csiSinr?.takeIf { it != Integer.MAX_VALUE } ?: -999
        val nrarfcn = identity?.nrarfcn?.takeIf { it > 0 } ?: -1
        val tac = identity?.tac ?: -1
        val nci = identity?.nci ?: -1

        signalPowerText.text = if (dbm > -999) "$dbm dBm" else "Not available"
        sinrText.text = if (sinr != -999) "$sinr dB" else "Not available"
        frequencyBandText.text = if (nrarfcn > 0) getNrBandFromNrarfcn(nrarfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && nci >= 0) String.format("%05d-%08d", tac, nci) else "Not available"

        Log.d("CellInfo5G", "NRARFCN=$nrarfcn, SINR=$sinr")
    }

    private fun processWcdmaCellInfo(cellInfo: CellInfoWcdma) {
        val identity = cellInfo.cellIdentity as? CellIdentityWcdma
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthWcdma

        val dbm = signal?.dbm ?: -999
        val lac = identity?.lac ?: -1
        val cid = identity?.cid ?: -1

        signalPowerText.text = "$dbm dBm"
        sinrText.text = "Not available"
        frequencyBandText.text = "Unknown"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%08d", lac, cid) else "Not available"
    }

    private fun processGsmCellInfo(cellInfo: CellInfoGsm) {
        val identity = cellInfo.cellIdentity as? CellIdentityGsm
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthGsm

        val dbm = signal?.dbm ?: -999
        val lac = identity?.lac ?: -1
        val cid = identity?.cid ?: -1

        signalPowerText.text = "$dbm dBm"
        sinrText.text = "Not available"
        frequencyBandText.text = "Unknown"
        cellIdText.text = if (lac >= 0 && cid >= 0) String.format("%05d-%08d", lac, cid) else "Not available"
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
}


