package com.example.networkcellanalyzer

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.networkcellanalyzer.databinding.ActivityStatisticsBinding
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.utils.ColorTemplate
import com.github.mikephil.charting.components.Legend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import android.graphics.Color

class StatisticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStatisticsBinding
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private var startDate: Date = Date()
    private var endDate: Date = Date()
    private lateinit var userId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStatisticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userId = intent.getStringExtra("USER_ID") ?: getUserIdFromPrefs()

        if (userId.isBlank()) {
            Toast.makeText(this, "User ID not found. Please log in again.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupToolbar()
        setupQuickTimeButtons()
        setupDateRangePickers()
        setupCharts()
        setTimeRange(TimeUnit.HOURS.toMillis(1))
    }

    private fun getUserIdFromPrefs(): String {
        val prefs = getSharedPreferences("UserPrefs", MODE_PRIVATE)
        return prefs.getString("userId", "") ?: ""
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Your Connection Statistics"

        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupQuickTimeButtons() {
        binding.btnLastFiveMinutes.setOnClickListener { setTimeRange(TimeUnit.MINUTES.toMillis(15)) }
        binding.btnLastThirtyMinutes.setOnClickListener { setTimeRange(TimeUnit.MINUTES.toMillis(30)) }
        binding.btnLastHour.setOnClickListener { setTimeRange(TimeUnit.HOURS.toMillis(1)) }
        binding.btnLastSixHours.setOnClickListener { setTimeRange(TimeUnit.HOURS.toMillis(6)) }
        binding.btnLastDay.setOnClickListener { setTimeRange(TimeUnit.DAYS.toMillis(1)) }
        binding.btnLastWeek.setOnClickListener { setTimeRange(TimeUnit.DAYS.toMillis(7)) }
    }

    private fun setupDateRangePickers() {
        binding.btnCustomRange.setOnClickListener {
            binding.dateRangeLayout.visibility = View.VISIBLE
        }

        binding.btnApplyDateRange.setOnClickListener {
            val startCalendar = Calendar.getInstance()
            startCalendar.set(
                binding.startDatePicker.year,
                binding.startDatePicker.month,
                binding.startDatePicker.dayOfMonth,
                binding.startTimePicker.hour,
                binding.startTimePicker.minute
            )

            val endCalendar = Calendar.getInstance()
            endCalendar.set(
                binding.endDatePicker.year,
                binding.endDatePicker.month,
                binding.endDatePicker.dayOfMonth,
                binding.endTimePicker.hour,
                binding.endTimePicker.minute
            )

            startDate = startCalendar.time
            endDate = endCalendar.time

            binding.dateRangeLayout.visibility = View.GONE

            fetchUserStatistics(startDate, endDate)
            updateDateRangeDisplay()
        }
    }

    private fun setupCharts() {
        binding.signalStrengthChart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            setDrawGridBackground(false)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)

            xAxis.position = XAxis.XAxisPosition.BOTTOM
            xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(value.toLong()))
                }
            }

            axisRight.isEnabled = false
            legend.isEnabled = true
        }

        binding.networkPieChart.apply {
            setUsePercentValues(true)
            description.isEnabled = false
            isDrawHoleEnabled = true
            holeRadius = 40f
            transparentCircleRadius = 45f
            setDrawEntryLabels(false)
            legend.isEnabled = true
            legend.verticalAlignment = Legend.LegendVerticalAlignment.CENTER
            legend.horizontalAlignment = Legend.LegendHorizontalAlignment.RIGHT
            legend.orientation = Legend.LegendOrientation.VERTICAL
        }

        binding.tvDataSpeedTitle.visibility = View.GONE
        binding.dataSpeedChart.visibility = View.GONE
    }

    private fun setTimeRange(milliseconds: Long) {
        endDate = Date()
        startDate = Date(endDate.time - milliseconds)

        fetchUserStatistics(startDate, endDate)
        updateDateRangeDisplay()
        binding.dateRangeLayout.visibility = View.GONE
    }

    private fun updateDateRangeDisplay() {
        val formattedStart = dateFormat.format(startDate)
        val formattedEnd = dateFormat.format(endDate)
        binding.tvSelectedRange.text = "Stats from $formattedStart to $formattedEnd"
    }

    private fun fetchUserStatistics(startDate: Date, endDate: Date) {
        binding.progressBar.visibility = View.VISIBLE
        binding.noDataMessage.visibility = View.GONE

        val formattedStart = dateFormat.format(startDate)
        val formattedEnd = dateFormat.format(endDate)

        lifecycleScope.launch {
            try {
                val statsData = withContext(Dispatchers.IO) {
                    fetchUserStatsFromServer(userId, formattedStart, formattedEnd)
                }

                if (statsData.signalData.isEmpty() && statsData.networkData.isEmpty()) {
                    showNoDataMessage()
                } else {
                    updateChartsWithUserData(statsData)
                }
            } catch (e: Exception) {
                showError("Failed to fetch statistics: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun fetchUserStatsFromServer(userId: String, startDate: String, endDate: String): UserStatsData {
        val encodedUserId = java.net.URLEncoder.encode(userId, "UTF-8")
        val encodedStartDate = java.net.URLEncoder.encode(startDate, "UTF-8")
        val encodedEndDate = java.net.URLEncoder.encode(endDate, "UTF-8")

        val url = URL("${BuildConfig.API_BASE_URL}/api/user-stats?userId=$encodedUserId&start_date=$encodedStartDate&end_date=$encodedEndDate")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"

        try {
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                return parseUserStatsResponse(response)
            } else {
                throw Exception("Server returned code: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseUserStatsResponse(jsonResponse: String): UserStatsData {
        val jsonObject = JSONObject(jsonResponse)
        if (jsonObject.getString("status") != "success") {
            throw Exception(jsonObject.optString("message", "Unknown error"))
        }

        val data = jsonObject.getJSONObject("data")

        val signalDataArray = data.getJSONArray("signalData")
        val signalData = mutableListOf<SignalDataPoint>()
        for (i in 0 until signalDataArray.length()) {
            val signalObj = signalDataArray.getJSONObject(i)
            signalData.add(
                SignalDataPoint(
                    timestamp = signalObj.getLong("timestamp"),
                    signalStrength = signalObj.getDouble("signalStrength").toFloat()
                )
            )
        }

        val networkDataArray = data.getJSONArray("networkData")
        val networkData = mutableListOf<NetworkDataPoint>()
        for (i in 0 until networkDataArray.length()) {
            val networkObj = networkDataArray.getJSONObject(i)
            networkData.add(
                NetworkDataPoint(
                    timestamp = networkObj.getLong("timestamp"),
                    networkType = networkObj.getString("networkType"),
                    networkTypeValue = networkObj.getDouble("networkTypeValue").toFloat()
                )
            )
        }

        val summaryObj = data.getJSONObject("summary")

        // Filter LTE SNR values only (numeric and meaningful)
        val avgSnrValue = if (summaryObj.has("avgSnr") && !summaryObj.isNull("avgSnr")) {
            val snr = summaryObj.getDouble("avgSnr")
            if (snr in -30.0..30.0) snr.toFloat() else null  // Filter out placeholder text like "Excellent"
        } else null

        val summary = StatsSummary(
            avgSignalStrength = if (summaryObj.has("avgSignalStrength") && !summaryObj.isNull("avgSignalStrength"))
                summaryObj.getDouble("avgSignalStrength").toFloat() else null,
            avgSnr = avgSnrValue,
            dataPoints = summaryObj.optInt("dataPoints", 0)
        )

        if (summaryObj.has("networkDistribution")) {
            val distributionObj = summaryObj.getJSONObject("networkDistribution")
            val keys = distributionObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                summary.networkDistribution[key] = distributionObj.getDouble(key).toFloat()
            }
        }

        return UserStatsData(signalData, networkData, summary)
    }

    private fun showNoDataMessage() {
        binding.noDataMessage.visibility = View.VISIBLE
        binding.signalStrengthChart.clear()
        binding.networkPieChart.clear()
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun updateChartsWithUserData(statsData: UserStatsData) {
        // --- Signal Strength Line Chart ---
        if (statsData.signalData.isNotEmpty()) {
            val signalEntries = statsData.signalData.map {
                Entry(it.timestamp.toFloat(), it.signalStrength)
            }
            val signalDataSet = LineDataSet(signalEntries, "Signal Strength (dBm)").apply {
                color = ContextCompat.getColor(this@StatisticsActivity, R.color.signal_strength_color)
                setDrawCircles(false)
                lineWidth = 2f
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
            }
            binding.signalStrengthChart.data = LineData(signalDataSet)
            binding.signalStrengthChart.invalidate()
        } else {
            binding.signalStrengthChart.clear()
            binding.signalStrengthChart.setNoDataText("No signal data available")
        }

        // --- Network Type Pie Chart ---
        val distribution = statsData.summary.networkDistribution
        if (distribution.isNotEmpty()) {
            val pieEntries = distribution.map { PieEntry(it.value, it.key) }
            val pieDataSet = PieDataSet(pieEntries, "Network Type Distribution").apply {
                setDrawValues(true)
                valueTextSize = 12f
                sliceSpace = 2f
                colors = ColorTemplate.MATERIAL_COLORS.toList()
            }
            val pieData = PieData(pieDataSet)
            binding.networkPieChart.data = pieData
            binding.networkPieChart.invalidate()
        } else {
            binding.networkPieChart.clear()
            binding.networkPieChart.setNoDataText("No network type data available")
        }

        // --- Summary Text ---
        updateSummaryStatistics(statsData.summary)
    }

    private fun updateSummaryStatistics(summary: StatsSummary) {
        val summaryText = StringBuilder()

        summary.avgSignalStrength?.let {
            summaryText.append("Average Signal Strength: ${String.format("%.1f", it)} dBm\n")
        }
        summary.avgSnr?.let {
            summaryText.append("Average SNR: ${String.format("%.1f", it)} dB\n")
        }
        summaryText.append("Total Data Points: ${summary.dataPoints}\n\n")

        if (summary.networkDistribution.isNotEmpty()) {
            summaryText.append("Network Distribution:\n")
            summary.networkDistribution.entries.forEach { entry ->
                summaryText.append("${entry.key}: ${String.format("%.1f", entry.value)}%\n")
            }
        }

        binding.tvSummaryStats.text = summaryText.toString()
    }

    data class SignalDataPoint(val timestamp: Long, val signalStrength: Float)
    data class NetworkDataPoint(val timestamp: Long, val networkType: String, val networkTypeValue: Float)
    data class StatsSummary(
        val avgSignalStrength: Float?,
        val avgSnr: Float?,
        val dataPoints: Int,
        val networkDistribution: MutableMap<String, Float> = mutableMapOf()
    )
    data class UserStatsData(
        val signalData: List<SignalDataPoint>,
        val networkData: List<NetworkDataPoint>,
        val summary: StatsSummary
    )
}
