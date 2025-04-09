package com.example.networkcellanalyzer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.*
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import com.google.android.material.button.MaterialButton
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

    private val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_PHONE_STATE
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

        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

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
    }

    private fun displayUnavailable() {
        signalPowerText.text = "Not available"
        sinrText.text = "Not available"
        frequencyBandText.text = "Not available"
        cellIdText.text = "Not available"
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
            }
        }
        displayUnavailable()
    }

    private fun processLteCellInfo(cellInfo: CellInfoLte) {
        val identity = cellInfo.cellIdentity as? CellIdentityLte
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthLte

        val signalStrength = signal?.dbm ?: -999
        val sinr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) signal?.rssnr ?: -999 else -999
        val earfcn = identity?.earfcn ?: -1
        val tac = identity?.tac ?: -1
        val ci = identity?.ci ?: -1

        signalPowerText.text = if (signalStrength > -999) "$signalStrength dBm" else "Not available"
        sinrText.text = if (sinr > -999) "$sinr dB" else "Not available"
        frequencyBandText.text = if (earfcn >= 0) getLteBandFromEarfcn(earfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && ci >= 0) "$tac-$ci" else "Not available"

        Log.d("CellInfo", "EARFCN=$earfcn, TAC=$tac, CI=$ci, RSSNR=$sinr, DBM=$signalStrength")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun process5GCellInfo(cellInfo: CellInfoNr) {
        val identity = cellInfo.cellIdentity as? CellIdentityNr
        val signal = cellInfo.cellSignalStrength as? CellSignalStrengthNr

        val signalStrength = signal?.dbm ?: -999
        val sinr = signal?.csiSinr ?: -999
        val nrarfcn = identity?.nrarfcn ?: -1
        val tac = identity?.tac ?: -1
        val nci = identity?.nci ?: -1

        signalPowerText.text = if (signalStrength > -999) "$signalStrength dBm" else "Not available"
        sinrText.text = if (sinr > -999) "$sinr dB" else "Not available"
        frequencyBandText.text = if (nrarfcn >= 0) getNrBandFromNrarfcn(nrarfcn) else "Not available"
        cellIdText.text = if (tac >= 0 && nci >= 0) "$tac-$nci" else "Not available"

        Log.d("CellInfo5G", "NRARFCN=$nrarfcn, TAC=$tac, NCI=$nci, SINR=$sinr, DBM=$signalStrength")
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
            else -> "Unknown ($earfcn)"
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
            else -> "Unknown ($nrarfcn)"
        }
    }
}
