package com.example.studyvault

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast

/** Alarm toggle, accessibility (social blocker) activation, and NFC card reset. */
class SettingsActivity : BaseActivity() {

    private lateinit var btnAlarmToggle: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnResetNfc: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        btnAlarmToggle = findViewById(R.id.btnAlarmToggle)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnResetNfc = findViewById(R.id.btnResetNfc)

        btnAlarmToggle.setOnClickListener {
            val p = appPrefs()
            val on = !p.getBoolean("ALARM_ENABLED", true)
            p.edit().putBoolean("ALARM_ENABLED", on).apply()
            Toast.makeText(this, if (on) "🔔 إنذار رفع الموبايل شغال" else "🔕 تم إيقاف إنذار رفع الموبايل", Toast.LENGTH_SHORT).show()
            refreshUI()
        }

        btnAccessibility.setOnClickListener {
            if (isAccessibilityEnabled()) {
                Toast.makeText(this, "الخدمة مفعّلة بالفعل ✅ (لو غيّرت التطبيق: أطفئها وشغّلها مرة أخرى)", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "فعّل تطبيق 'شعلة' من قائمة التطبيقات المثبتة", Toast.LENGTH_LONG).show()
            }
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnResetNfc.setOnClickListener {
            val prefs = appPrefs()
            if (prefs.getBoolean("IS_APP_ACTIVE", false) || StudySessionService.isStudying) {
                Toast.makeText(this, "المس الكارت القديم لقفل البرنامج أولاً قبل تغييره!", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().remove("SAVED_NFC_UID").apply()
                Toast.makeText(this, "تم مسح الكارت القديم. المس الكارت الجديد لتسجيله", Toast.LENGTH_SHORT).show()
                refreshUI()
            }
        }

        refreshUI()
    }

    override fun refreshUI() {
        btnAlarmToggle.text = if (appPrefs().getBoolean("ALARM_ENABLED", true)) {
            "🔔 إنذار رفع الموبايل: شغال"
        } else {
            "🔕 إنذار رفع الموبايل: مقفول"
        }

        val a11yOn = isAccessibilityEnabled()
        btnAccessibility.text = if (a11yOn) "✅ قفل السوشيال مفعّل" else "تفعيل قفل السوشيال"
        btnAccessibility.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (a11yOn) "#2E7D32" else "#B71C1C"))
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val full = "$packageName/${SocialBlockerService::class.java.name}"
        val short = "$packageName/.SocialBlockerService"
        return enabled.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }
}
