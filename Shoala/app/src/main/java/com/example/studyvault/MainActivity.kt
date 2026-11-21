package com.example.studyvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** Home page: rank, XP, flames, social-time status, and the entry point to focus mode. */
class MainActivity : BaseActivity() {

    private lateinit var gamificationManager: GamificationManager

    private lateinit var profileRankText: TextView
    private lateinit var flamesCountText: TextView
    private lateinit var levelProgressBar: ProgressBar
    private lateinit var nextLevelText: TextView
    private lateinit var streakInfoText: TextView
    private lateinit var nfcStateText: TextView
    private lateinit var statusText: TextView
    private lateinit var rewardText: TextView
    private lateinit var bingeStatsText: TextView
    private lateinit var btnStartFocus: Button
    private lateinit var btnSpendFlames: Button
    private lateinit var btnStats: ImageButton
    private lateinit var btnSettings: ImageButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        gamificationManager = GamificationManager(this)

        bindViews()
        requestRequiredPermissions()
        setupButtons()
        refreshUI()

        if (savedInstanceState == null) handleNfcIntent(intent)
    }

    private fun bindViews() {
        profileRankText = findViewById(R.id.profileRankText)
        flamesCountText = findViewById(R.id.flamesCountText)
        levelProgressBar = findViewById(R.id.levelProgressBar)
        nextLevelText = findViewById(R.id.nextLevelText)
        streakInfoText = findViewById(R.id.streakInfoText)
        nfcStateText = findViewById(R.id.nfcStateText)
        statusText = findViewById(R.id.statusText)
        rewardText = findViewById(R.id.rewardText)
        bingeStatsText = findViewById(R.id.bingeStatsText)
        btnStartFocus = findViewById(R.id.btnStartFocus)
        btnSpendFlames = findViewById(R.id.btnSpendFlames)
        btnStats = findViewById(R.id.btnStats)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_PHONE_STATE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 101)
        }
    }

    private fun setupButtons() {
        btnStats.setOnClickListener { startActivity(Intent(this, StatsActivity::class.java)) }
        btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        btnStartFocus.setOnClickListener { startActivity(Intent(this, TimerActivity::class.java)) }

        btnSpendFlames.setOnClickListener {
            val prefs = appPrefs()
            val isAppActive = prefs.getBoolean("IS_APP_ACTIVE", false)

            if (!isAppActive) {
                Toast.makeText(this, "البرنامج مقفول بالكارت أصلاً والسوشيال مفتوح", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (StudySessionService.isStudying && !StudySessionService.isPaused) {
                Toast.makeText(this, "أوقف أو أنهِ جلسة المذاكرة أولاً لصرف الشعلات", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val currentlySpending = prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)
            if (!currentlySpending) {
                if (gamificationManager.getSpendableFlames() <= 0L) {
                    Toast.makeText(this, "رصيدك 0 شعلة! ذاكر لجمع شعلات أولاً", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", true).apply()
                Toast.makeText(this, "🔥 تم فتح السوشيال بالشعلات (الدقيقة = 6 🔥)", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().putBoolean("IS_SPENDING_FLAMES_MODE", false).apply()
                Toast.makeText(this, "🔒 تم إعادة قفل السوشيال والحفاظ على شعلاتك", Toast.LENGTH_SHORT).show()
            }
            refreshUI()
        }
    }

    override fun refreshUI() {
        val prefs = appPrefs()
        val isAppActive = prefs.getBoolean("IS_APP_ACTIVE", false)
        val isSpendingFlames = prefs.getBoolean("IS_SPENDING_FLAMES_MODE", false)
        val now = System.currentTimeMillis()
        val penaltyEnd = prefs.getLong("PENALTY_END_TIME", 0L)
        val bingeLockEnd = prefs.getLong("BINGE_LOCK_END_TIME", 0L)

        nfcStateText.text = when {
            now < bingeLockEnd -> "⛔ استراحة إجبارية بعد 3 ساعات سوشيال (متبقي ${(bingeLockEnd - now) / 60000L + 1} د)"
            now < penaltyEnd -> "⛔ عقوبة رفع الموبايل نشطة (متبقي ${(penaltyEnd - now) / 60000L + 1} د)"
            isAppActive && isSpendingFlames -> "🔥 وضع صرف الشعلات مفتوح (يخصم 6 🔥 كل دقيقة)"
            isAppActive -> "🔒 البرنامج شغال بالكارت (فيسبوك وإنستا ويوتيوب مقفولين)"
            else -> "🔓 البرنامج مقفول بالكارت (السوشيال مفتوح بحد أقصى 3 ساعات)"
        }

        statusText.text = StudySessionService.statusMessage

        btnStartFocus.text = if (StudySessionService.isStudying) "🔥 الرجوع لصفحة التركيز" else "🔥 ابدأ التركيز"
        btnSpendFlames.text = if (isSpendingFlames) {
            "🔒 إيقاف صرف الشعلات وقفل السوشيال"
        } else {
            "🔥 فتح السوشيال بالشعلات (6 🔥 / دقيقة)"
        }

        val availableMins = gamificationManager.getAvailableSocialMinutes()
        rewardText.text = "متاح: $availableMins د سوشيال (الواتساب مجاني)"

        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().time)
        val bingeSecs = if (prefs.getString("BINGE_DATE", "") == today) prefs.getLong("BINGE_SOCIAL_SECONDS", 0L) else 0L
        bingeStatsText.text = "سوشيال اليوم: ${bingeSecs / 60L} / 180 دقيقة (يقفل ساعة عند 3 ساعات)"

        val profile = gamificationManager.getProfileStatus()
        val streak = gamificationManager.getValidCurrentStreak()
        val rate = gamificationManager.getFlamesPerFiveMinutes()

        profileRankText.text = "${profile.rankEmoji} المستوى ${profile.level} • ${profile.rankTitle}"
        flamesCountText.text = "🔥 ${profile.spendableFlames}"
        levelProgressBar.progress = profile.progressPercent
        nextLevelText.text = "XP: ${profile.totalXp} • متبقي ${profile.xpToNextLevel} للمستوى ${profile.level + 1}"
        streakInfoText.text = "ستريك: $streak يوم ($rate🔥/5د)"
    }
}
