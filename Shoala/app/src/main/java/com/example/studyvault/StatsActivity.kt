package com.example.studyvault

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView

/** Charts, totals, records and badges. */
class StatsActivity : BaseActivity() {

    private lateinit var statsManager: StatsManager
    private lateinit var gamificationManager: GamificationManager

    private lateinit var chartTitleText: TextView
    private lateinit var btnWeeklyChart: Button
    private lateinit var btnMonthlyChart: Button
    private lateinit var weeklyChart: WeeklyBarChartView
    private lateinit var todayStatsText: TextView
    private lateinit var weekStatsText: TextView
    private lateinit var monthStatsText: TextView
    private lateinit var yearStatsText: TextView
    private lateinit var recordsText: TextView
    private lateinit var badgesText: TextView

    private var isShowingMonthlyChart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)

        statsManager = StatsManager(this)
        gamificationManager = GamificationManager(this)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }

        chartTitleText = findViewById(R.id.chartTitleText)
        btnWeeklyChart = findViewById(R.id.btnWeeklyChart)
        btnMonthlyChart = findViewById(R.id.btnMonthlyChart)
        weeklyChart = findViewById(R.id.weeklyChart)
        todayStatsText = findViewById(R.id.todayStatsText)
        weekStatsText = findViewById(R.id.weekStatsText)
        monthStatsText = findViewById(R.id.monthStatsText)
        yearStatsText = findViewById(R.id.yearStatsText)
        recordsText = findViewById(R.id.recordsText)
        badgesText = findViewById(R.id.badgesText)

        btnWeeklyChart.setOnClickListener {
            isShowingMonthlyChart = false
            btnWeeklyChart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF3D00"))
            btnMonthlyChart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#221918"))
            chartTitleText.text = "الرسم البياني (أسبوعي)"
            refreshChart()
        }
        btnMonthlyChart.setOnClickListener {
            isShowingMonthlyChart = true
            btnMonthlyChart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF3D00"))
            btnWeeklyChart.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#221918"))
            chartTitleText.text = "الرسم البياني (هذا الشهر)"
            refreshChart()
        }

        refreshUI()
    }

    override fun refreshUI() {
        todayStatsText.text = formatMinutes(statsManager.getTodayMinutes())
        weekStatsText.text = formatMinutes(statsManager.getWeekMinutes())
        monthStatsText.text = formatMinutes(statsManager.getMonthMinutes())
        yearStatsText.text = formatMinutes(statsManager.getYearMinutes())

        recordsText.text = "أطول جلسة: ${gamificationManager.getBestSessionMins()} د | " +
                "أعلى يوم: ${gamificationManager.getBestDayMins()} د | " +
                "أطول ستريك: ${gamificationManager.getBestStreakDays()} يوم"

        val badges = gamificationManager.getUnlockedBadges()
        badgesText.text = if (badges.isEmpty()) {
            "الجوائز المفتوحة: ابدأ المذاكرة لفتح أول جائزة!"
        } else {
            "الجوائز المفتوحة:\n" + badges.joinToString("\n• ", prefix = "• ")
        }

        refreshChart()
    }

    private fun refreshChart() {
        if (isShowingMonthlyChart) {
            weeklyChart.setData(statsManager.getCurrentMonthChartData(), isMonthly = true)
        } else {
            weeklyChart.setData(statsManager.getLast7DaysChartData(), isMonthly = false)
        }
    }

    private fun formatMinutes(totalMins: Long): String {
        val hours = totalMins / 60L
        val mins = totalMins % 60L
        return if (hours > 0) "${hours}س ${mins}د" else "${mins} د"
    }
}
