package com.example.networkcellanalyzer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.*
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.example.networkcellanalyzer.databinding.ActivityMainBinding
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    private val TAG = "NetworkCellAnalyzer"
    private val LOCATION_PERMISSION_REQUEST_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        // Set FAB click listener to fetch cell data when pressed.
        binding.fab.setOnClickListener { view ->
            Snackbar.make(view, "Fetching cell data...", Snackbar.LENGTH_LONG)
                .setAction("Action", null).show()
            checkPermissionsAndFetchData()
        }

        // Optionally, fetch cell data on startup if permissions have been granted.
        checkPermissionsAndFetchData()
    }

    /**
     * Check for ACCESS_FINE_LOCATION permission and trigger the cell data fetch.
     */
    private fun checkPermissionsAndFetchData() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST_CODE
            )
        } else {
            fetchAndPrintCellData()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                fetchAndPrintCellData()
            } else {
                Log.e(TAG, "Location permission is required to access cell information")
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    /**
     * Use the TelephonyManager API to fetch and print cell information.
     */
    private fun fetchAndPrintCellData() {
        val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        // Get the operator name.
        val operatorName = telephonyManager.networkOperatorName
        Log.d(TAG, "Operator: $operatorName")

        // Determine network type.
        val networkType = when (telephonyManager.networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "3G"
            TelephonyManager.NETWORK_TYPE_LTE -> "4G"
            else -> "Unknown"
        }
        Log.d(TAG, "Network Type: $networkType")

        // Print the current timestamp.
        val timeStamp = SimpleDateFormat("dd MMM yyyy hh:mm a", Locale.getDefault()).format(Date())
        Log.d(TAG, "Time Stamp: $timeStamp")

        // Check permission again before fetching the cell info.
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Permission not granted to access cell info")
            return
        }

        val cellInfoList = telephonyManager.allCellInfo
        if (cellInfoList.isNullOrEmpty()) {
            Log.d(TAG, "No cell info available")
            return
        }

        // Loop through all available cell info records and log details.
        for (cellInfo in cellInfoList) {
            when (cellInfo) {
                is CellInfoGsm -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    Log.d(TAG, "GSM Cell Info: CID=${identity.cid}, LAC=${identity.lac}, Signal=${signal.dbm}dBm")
                }
                is CellInfoCdma -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    Log.d(TAG, "CDMA Cell Info: NetworkId=${identity.networkId}, BaseStationId=${identity.basestationId}, Signal=${signal.dbm}dBm")
                }
                is CellInfoWcdma -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    Log.d(TAG, "WCDMA (3G) Cell Info: CID=${identity.cid}, LAC=${identity.lac}, PSC=${identity.psc}, Signal=${signal.dbm}dBm")
                }
                is CellInfoLte -> {
                    val identity = cellInfo.cellIdentity
                    val signal = cellInfo.cellSignalStrength
                    Log.d(TAG, "LTE (4G) Cell Info: CI=${identity.ci}, TAC=${identity.tac}, PCI=${identity.pci}, Signal=${signal.dbm}dBm")
                    // LTE additional values might be available on API level 24 and above.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        Log.d(TAG, "LTE Metrics: RSRP=${signal.rsrp} dBm, RSRQ=${signal.rsrq} dB, RSSNR=${signal.rssnr} dB")
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown cell info type encountered")
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }
}
