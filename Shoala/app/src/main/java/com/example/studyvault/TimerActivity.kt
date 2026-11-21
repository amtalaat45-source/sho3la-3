package com.example.studyvault

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

private enum class CamState { NONE, PLACING, COUNTDOWN, SAMPLING, MONITORING }

/**
 * Focus page.
 *  1. Setup: count-up or countdown, optional camera spot lock.
 *  2. Placement (only with camera lock): live preview, tap "lock spot", 3-2-1, the app memorises the view.
 *  3. Focus: big timer, pause / end, "phone removed" alert, camera match status.
 *  4. Ended: the final time stays on screen (it does NOT reset) until "new session".
 */
class TimerActivity : BaseActivity() {

    companion object {
        private const val REQ_CAMERA = 202
        private const val DEFAULT_STATUS = "اختر نوع المؤقت ثم ابدأ التركيز"
    }

    // ---- timer options ----
    private var isCountdown = false
    private var minutes = 25
    private var cameraLockOn = false
    private var faceUp = true // screen up -> front camera sees the room/ceiling

    // ---- session / UI state ----
    private var sessionEnded = false
    private var wasStudying = false
    private var pendingStart = false
    private var pendingStartAt = 0L
    private var pendingTarget = 0L

    // ---- camera state ----
    private var camState = CamState.NONE
    private var cameraSession = false
    private var pendingPlacement = false
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var lastAnalyzed = 0L
    private val samples = mutableListOf<FloatArray>()
    private var badCount = 0
    private var goodCount = 0
    private var lastPct = 100

    // ---- views ----
    private lateinit var setupGroup: LinearLayout
    private lateinit var placementGroup: LinearLayout
    private lateinit var focusGroup: LinearLayout
    private lateinit var countdownPanel: LinearLayout
    private lateinit var facingRow: LinearLayout
    private lateinit var controlsRow: LinearLayout
    private lateinit var btnModeUp: Button
    private lateinit var btnModeDown: Button
    private lateinit var modeHintText: TextView
    private lateinit var minutesText: TextView
    private lateinit var btnMinus: Button
    private lateinit var btnPlus: Button
    private lateinit var chips: List<Pair<Button, Int>>
    private lateinit var btnCameraLock: Button
    private lateinit var btnFaceUp: Button
    private lateinit var btnFaceDown: Button
    private lateinit var btnStart: Button
    private lateinit var previewView: PreviewView
    private lateinit var placementHintText: TextView
    private lateinit var placementCountdownText: TextView
    private lateinit var btnLockSpot: Button
    private lateinit var btnSkipCamera: Button
    private lateinit var btnCancelPlacement: Button
    private lateinit var modeLabelText: TextView
    private lateinit var timerText: TextView
    private lateinit var countdownProgress: ProgressBar
    private lateinit var alertCard: TextView
    private lateinit var cameraStatusText: TextView
    private lateinit var btnPauseResume: Button
    private lateinit var btnEnd: Button
    private lateinit var btnNewSession: Button
    private lateinit var statusText: TextView

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_timer)

        val p = appPrefs()
        isCountdown = p.getBoolean("TIMER_MODE_DOWN", false)
        minutes = p.getInt("TIMER_MINUTES", 25).coerceIn(1, 300)
        cameraLockOn = p.getBoolean("CAMERA_LOCK_ON", false) && hasCameraPermission()
        faceUp = p.getBoolean("CAM_FACE_UP", true)

        // If the session finished while this page was closed, show its final time.
        sessionEnded = StudySessionService.sessionCompleted && !StudySessionService.isStudying
        // If the page was recreated mid-session, keep using the saved camera spot.
        cameraSession = SpotLock.reference != null && StudySessionService.isStudying

        bindViews()
        setupClicks()
        applyModeUI()
        applyCameraUI()
        refreshUI()
    }

    private fun bindViews() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        setupGroup = findViewById(R.id.setupGroup)
        placementGroup = findViewById(R.id.placementGroup)
        focusGroup = findViewById(R.id.focusGroup)
        countdownPanel = findViewById(R.id.countdownPanel)
        facingRow = findViewById(R.id.facingRow)
        controlsRow = findViewById(R.id.controlsRow)
        btnModeUp = findViewById(R.id.btnModeUp)
        btnModeDown = findViewById(R.id.btnModeDown)
        modeHintText = findViewById(R.id.modeHintText)
        minutesText = findViewById(R.id.minutesText)
        btnMinus = findViewById(R.id.btnMinus)
        btnPlus = findViewById(R.id.btnPlus)
        chips = listOf(
            findViewById<Button>(R.id.chip15) to 15,
            findViewById<Button>(R.id.chip25) to 25,
            findViewById<Button>(R.id.chip45) to 45,
            findViewById<Button>(R.id.chip60) to 60,
            findViewById<Button>(R.id.chip90) to 90
        )
        btnCameraLock = findViewById(R.id.btnCameraLock)
        btnFaceUp = findViewById(R.id.btnFaceUp)
        btnFaceDown = findViewById(R.id.btnFaceDown)
        btnStart = findViewById(R.id.btnStart)
        previewView = findViewById(R.id.previewView)
        placementHintText = findViewById(R.id.placementHintText)
        placementCountdownText = findViewById(R.id.placementCountdownText)
        btnLockSpot = findViewById(R.id.btnLockSpot)
        btnSkipCamera = findViewById(R.id.btnSkipCamera)
        btnCancelPlacement = findViewById(R.id.btnCancelPlacement)
        modeLabelText = findViewById(R.id.modeLabelText)
        timerText = findViewById(R.id.timerText)
        countdownProgress = findViewById(R.id.countdownProgress)
        alertCard = findViewById(R.id.alertCard)
        cameraStatusText = findViewById(R.id.cameraStatusText)
        btnPauseResume = findViewById(R.id.btnPauseResume)
        btnEnd = findViewById(R.id.btnEnd)
        btnNewSession = findViewById(R.id.btnNewSession)
        statusText = findViewById(R.id.statusText)
    }

    private fun setupClicks() {
        btnModeUp.setOnClickListener { setMode(false) }
        btnModeDown.setOnClickListener { setMode(true) }
        btnMinus.setOnClickListener { setMinutes(max(1, minutes - 5)) }
        btnPlus.setOnClickListener { setMinutes(minOf(300, minutes + 5)) }
        chips.forEach { (btn, value) -> btn.setOnClickListener { setMinutes(value) } }

        btnCameraLock.setOnClickListener { toggleCameraLock() }
        btnFaceUp.setOnClickListener { setFacing(true) }
        btnFaceDown.setOnClickListener { setFacing(false) }

        btnStart.setOnClickListener { startSession() }
        btnLockSpot.setOnClickListener { startPlacementCountdown() }
        btnSkipCamera.setOnClickListener {
            stopCamera()
            camState = CamState.NONE
            launchSession(withCamera = false)
        }
        btnCancelPlacement.setOnClickListener { cancelPlacement() }

        btnPauseResume.setOnClickListener { togglePause() }
        btnEnd.setOnClickListener { endSession() }
        btnNewSession.setOnClickListener { newSession() }
    }

    override fun onStart() {
        super.onStart()
        if (cameraSession) {
            if (StudySessionService.isStudying && SpotLock.reference != null) {
                startMonitoring()
            } else {
                cameraSession = false
                SpotLock.clear()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // The camera must never keep running in the background.
        if (camState == CamState.PLACING || camState == CamState.COUNTDOWN || camState == CamState.SAMPLING) {
            cancelPlacement()
        }
        if (cameraSession) suspendMonitoring()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    // =====================================================================
    // Setup options
    // =====================================================================

    private fun setMode(down: Boolean) {
        isCountdown = down
        appPrefs().edit().putBoolean("TIMER_MODE_DOWN", down).apply()
        applyModeUI()
    }

    private fun setMinutes(value: Int) {
        minutes = value
        appPrefs().edit().putInt("TIMER_MINUTES", value).apply()
        applyModeUI()
    }

    private fun tint(b: Button, selected: Boolean) {
        b.backgroundTintList = ColorStateList.valueOf(Color.parseColor(if (selected) "#FF3D00" else "#221918"))
        b.setTextColor(Color.parseColor(if (selected) "#FFFFFF" else "#CCCCCC"))
    }

    private fun applyModeUI() {
        tint(btnModeUp, !isCountdown)
        tint(btnModeDown, isCountdown)
        countdownPanel.visibility = if (isCountdown) View.VISIBLE else View.GONE
        modeHintText.text = if (isCountdown) {
            "التنازلي: تحدد المدة والمؤقت يعد لحد الصفر وتنتهي الجلسة تلقائياً."
        } else {
            "التصاعدي: يعد من 00:00 لحد ما توقفه بنفسك."
        }
        minutesText.text = "$minutes د"
        chips.forEach { (btn, value) -> tint(btn, value == minutes) }
    }

    private fun applyCameraUI() {
        btnCameraLock.text = if (cameraLockOn) "📷 قفل الكاميرا: شغال ✅" else "📷 قفل الكاميرا: متوقف"
        btnCameraLock.backgroundTintList =
            ColorStateList.valueOf(Color.parseColor(if (cameraLockOn) "#2E7D32" else "#221918"))
        btnCameraLock.setTextColor(Color.parseColor(if (cameraLockOn) "#FFFFFF" else "#CCCCCC"))
        facingRow.visibility = if (cameraLockOn) View.VISIBLE else View.GONE
        tint(btnFaceUp, faceUp)
        tint(btnFaceDown, !faceUp)
    }

    private fun toggleCameraLock() {
        if (cameraLockOn) {
            cameraLockOn = false
            appPrefs().edit().putBoolean("CAMERA_LOCK_ON", false).apply()
        } else {
            cameraLockOn = true
            appPrefs().edit().putBoolean("CAMERA_LOCK_ON", true).apply()
            if (!hasCameraPermission()) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
            }
        }
        applyCameraUI()
    }

    private fun setFacing(up: Boolean) {
        faceUp = up
        appPrefs().edit().putBoolean("CAM_FACE_UP", up).apply()
        applyCameraUI()
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_CAMERA) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (pendingPlacement) {
                pendingPlacement = false
                beginPlacement()
            }
        } else {
            pendingPlacement = false
            cameraLockOn = false
            appPrefs().edit().putBoolean("CAMERA_LOCK_ON", false).apply()
            applyCameraUI()
            Toast.makeText(this, "محتاج إذن الكاميرا عشان قفل المكان", Toast.LENGTH_LONG).show()
        }
    }

    // =====================================================================
    // Start / pause / end
    // =====================================================================

    private fun startSession() {
        if (StudySessionService.isStudying) return
        sessionEnded = false
        if (cameraLockOn) {
            if (!hasCameraPermission()) {
                pendingPlacement = true
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
                return
            }
            beginPlacement()
        } else {
            launchSession(withCamera = false)
        }
    }

    private fun launchSession(withCamera: Boolean) {
        if (StudySessionService.isStudying) return
        val target = if (isCountdown) minutes * 60L else 0L
        val serviceIntent = Intent(this, StudySessionService::class.java)
            .putExtra(StudySessionService.EXTRA_TARGET_SECONDS, target)
        ContextCompat.startForegroundService(this, serviceIntent)

        sessionEnded = false
        pendingStart = true
        pendingStartAt = SystemClock.elapsedRealtime()
        pendingTarget = target

        if (withCamera) {
            cameraSession = true
            startMonitoring()
            Toast.makeText(this, "🔥 بدأ العد! المكان اتثبّت — متشيلش الهاتف", Toast.LENGTH_SHORT).show()
        } else {
            cameraSession = false
            SpotLock.clear()
            Toast.makeText(this, "🔥 بدأ العد! ضع الموبايل ثابتاً على المكتب", Toast.LENGTH_SHORT).show()
        }
        refreshUI()
    }

    private fun togglePause() {
        if (!StudySessionService.isStudying) return
        StudySessionService.isPaused = !StudySessionService.isPaused
        if (!StudySessionService.isPaused) {
            StudySessionService.isPhoneLifted = true
            StudySessionService.graceSecondsLeft = 120
            StudySessionService.resetSensorAnchor = true
            StudySessionService.alarmArmed = false
            StudySessionService.statusMessage = "تم الاستئناف! ضع الهاتف في مكانه ثابتاً"
            badCount = 0
            goodCount = 0
        }
        refreshUI()
    }

    private fun endSession() {
        if (!StudySessionService.isStudying) return
        val mins = StudySessionService.studySeconds / 60L
        stopService(Intent(this, StudySessionService::class.java))
        StudySessionService.statusMessage = "تم إنهاء الجلسة وحفظ $mins دقيقة ✅"
        Toast.makeText(this, "تم حفظ الدقائق والشعلات بنجاح", Toast.LENGTH_SHORT).show()
        // Keep the final time on screen instead of resetting it to zero.
        sessionEnded = true
        wasStudying = false
        endCameraSession()
        refreshUI()
    }

    private fun newSession() {
        sessionEnded = false
        wasStudying = false
        pendingStart = false
        StudySessionService.sessionCompleted = false
        if (!StudySessionService.isStudying) StudySessionService.statusMessage = DEFAULT_STATUS
        refreshUI()
    }

    // =====================================================================
    // Camera spot lock: placement
    // =====================================================================

    private fun beginPlacement() {
        camState = CamState.PLACING
        placementHintText.text = "ضع الهاتف في مكانه النهائي على المكتب، ثم اضغط تثبيت وابعد إيدك."
        placementCountdownText.text = ""
        btnLockSpot.isEnabled = true
        bindCamera(withPreview = true) {
            Toast.makeText(this, "الكاميرا مش متاحة. هتبدأ الجلسة بدون قفل الكاميرا.", Toast.LENGTH_LONG).show()
            camState = CamState.NONE
            launchSession(withCamera = false)
        }
        refreshUI()
    }

    private fun startPlacementCountdown() {
        if (camState != CamState.PLACING) return
        camState = CamState.COUNTDOWN
        btnLockSpot.isEnabled = false
        placementHintText.text = "ابعد إيدك عن الهاتف..."
        var n = 3
        val ticker = object : Runnable {
            override fun run() {
                if (camState != CamState.COUNTDOWN) return
                if (n > 0) {
                    placementCountdownText.text = n.toString()
                    n--
                    uiHandler.postDelayed(this, 1000L)
                } else {
                    placementCountdownText.text = "📷"
                    placementHintText.text = "جاري تسجيل المكان... ثبّت الهاتف"
                    samples.clear()
                    lastAnalyzed = 0L
                    camState = CamState.SAMPLING
                }
            }
        }
        uiHandler.post(ticker)
    }

    private fun finishSampling() {
        val avg = SpotLock.average(samples)
        val stability = samples.minOf { SpotLock.correlation(it, avg) }
        placementCountdownText.text = ""
        when {
            SpotLock.contrast(avg) < SpotLock.MIN_REF_CONTRAST ->
                placementFailed("الكاميرا مش شايفة تفاصيل كفاية (سقف أبيض أو إضاءة ضعيفة). جرّب وضع الهاتف التاني (الشاشة لفوق/لتحت) أو ابدأ بدون كاميرا.")
            stability < SpotLock.MIN_SAMPLE_STABILITY ->
                placementFailed("الهاتف اتحرك أثناء التسجيل. ثبّته وجرّب تاني.")
            else -> {
                SpotLock.reference = avg
                launchSession(withCamera = true)
            }
        }
    }

    private fun placementFailed(message: String) {
        camState = CamState.PLACING
        btnLockSpot.isEnabled = true
        placementHintText.text = message
        Toast.makeText(this, "❌ التثبيت فشل — شوف الرسالة", Toast.LENGTH_SHORT).show()
    }

    private fun cancelPlacement() {
        stopCamera()
        camState = CamState.NONE
        placementCountdownText.text = ""
        refreshUI()
    }

    // =====================================================================
    // Camera spot lock: monitoring during the session
    // =====================================================================

    private fun startMonitoring() {
        camState = CamState.MONITORING
        badCount = 0
        goodCount = 0
        lastPct = 100
        lastAnalyzed = 0L
        StudySessionService.cameraLockActive = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bindCamera(withPreview = false) {
            Toast.makeText(this, "الكاميرا مش متاحة، هكمل بالحساس فقط", Toast.LENGTH_LONG).show()
            endCameraSession()
        }
    }

    /** Page went to background: stop the camera but keep the saved spot for when we return. */
    private fun suspendMonitoring() {
        stopCamera()
        camState = CamState.NONE
        badCount = 0
        goodCount = 0
        StudySessionService.cameraLockActive = false
        StudySessionService.visualMismatch = false
    }

    private fun endCameraSession() {
        cameraSession = false
        SpotLock.clear()
        stopCamera()
        camState = CamState.NONE
        StudySessionService.cameraLockActive = false
        StudySessionService.visualMismatch = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun onSignature(sig: FloatArray) {
        when (camState) {
            CamState.SAMPLING -> {
                samples.add(sig)
                if (samples.size >= SpotLock.SAMPLES_NEEDED) finishSampling()
            }
            CamState.MONITORING -> evaluate(sig)
            else -> {}
        }
    }

    private fun evaluate(sig: FloatArray) {
        val ref = SpotLock.reference ?: return
        if (StudySessionService.isPaused || !StudySessionService.isStudying) {
            badCount = 0
            goodCount = 0
            if (StudySessionService.visualMismatch) StudySessionService.visualMismatch = false
            return
        }
        // Lights off / lens covered: unknown, not proof that the phone moved.
        if (SpotLock.contrast(sig) < SpotLock.MIN_LIVE_CONTRAST) return

        val c = SpotLock.correlation(sig, ref)
        lastPct = (c * 100f).toInt().coerceIn(0, 100)

        if (c < SpotLock.MISMATCH_BELOW) {
            badCount++
            goodCount = 0
        } else if (c > SpotLock.MATCH_ABOVE) {
            goodCount++
            badCount = 0
        }
        if (badCount >= SpotLock.BAD_CHECKS_TO_TRIGGER && !StudySessionService.visualMismatch) {
            StudySessionService.visualMismatch = true
        }
        if (goodCount >= SpotLock.GOOD_CHECKS_TO_CLEAR && StudySessionService.visualMismatch) {
            StudySessionService.visualMismatch = false
        }
    }

    private val analyzer = ImageAnalysis.Analyzer { image ->
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzed >= SpotLock.CHECK_INTERVAL_MS) {
                lastAnalyzed = now
                val sig = SpotLock.signature(image)
                uiHandler.post { onSignature(sig) }
            }
        } finally {
            image.close()
        }
    }

    private fun bindCamera(withPreview: Boolean, onFail: () -> Unit) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                provider.unbindAll()

                var selector = if (faceUp) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                if (!provider.hasCamera(selector)) {
                    selector = if (faceUp) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(320, 240))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, analyzer) }

                val useCases = mutableListOf<UseCase>(analysis)
                if (withPreview) {
                    val preview = Preview.Builder().build()
                        .also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    useCases.add(preview)
                }
                provider.bindToLifecycle(this@TimerActivity, selector, *useCases.toTypedArray())
            } catch (e: Exception) {
                onFail()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        try { cameraProvider?.unbindAll() } catch (e: Exception) { }
    }

    // =====================================================================
    // UI refresh (every second)
    // =====================================================================

    private fun fmt(totalSeconds: Long) =
        String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)

    override fun refreshUI() {
        val studying = StudySessionService.isStudying
        if (studying) {
            wasStudying = true
            pendingStart = false
        }
        if (wasStudying && !studying) {
            // Session ended by itself (countdown finished, NFC unlock, penalty...)
            wasStudying = false
            sessionEnded = true
            endCameraSession()
        }
        if (pendingStart && SystemClock.elapsedRealtime() - pendingStartAt > 3000L) pendingStart = false

        val placing = camState == CamState.PLACING || camState == CamState.COUNTDOWN || camState == CamState.SAMPLING
        val showFocus = !placing && (sessionEnded || studying || pendingStart)
        val showSetup = !placing && !showFocus

        setupGroup.visibility = if (showSetup) View.VISIBLE else View.GONE
        placementGroup.visibility = if (placing) View.VISIBLE else View.GONE
        focusGroup.visibility = if (showFocus) View.VISIBLE else View.GONE
        statusText.visibility = if (placing) View.GONE else View.VISIBLE
        statusText.text = StudySessionService.statusMessage

        if (showFocus) updateFocus(studying)
    }

    private fun updateFocus(studying: Boolean) {
        if (sessionEnded) {
            // Final time stays visible.
            modeLabelText.text = "✅ انتهت الجلسة — مدة المذاكرة"
            timerText.text = fmt(StudySessionService.studySeconds)
            countdownProgress.visibility = View.GONE
            alertCard.visibility = View.GONE
            cameraStatusText.visibility = View.GONE
            controlsRow.visibility = View.GONE
            btnNewSession.visibility = View.VISIBLE
            return
        }

        controlsRow.visibility = View.VISIBLE
        btnNewSession.visibility = View.GONE

        val target = if (studying) StudySessionService.targetSeconds else pendingTarget
        val elapsed = if (studying) StudySessionService.studySeconds else 0L
        if (target > 0L) {
            timerText.text = fmt(max(0L, target - elapsed))
            modeLabelText.text = "⏳ تنازلي — الوقت المتبقي"
            countdownProgress.visibility = View.VISIBLE
            countdownProgress.progress = ((elapsed * 100L) / target).toInt().coerceIn(0, 100)
        } else {
            timerText.text = fmt(elapsed)
            modeLabelText.text = "⏱️ تصاعدي"
            countdownProgress.visibility = View.GONE
        }
        btnPauseResume.text = if (StudySessionService.isPaused) "استئناف" else "إيقاف مؤقت"

        // "Phone removed" alert (same idea as the vault app's red screen)
        val lifted = StudySessionService.isPhoneLifted || StudySessionService.visualMismatch
        val showAlert = studying && StudySessionService.alarmArmed && lifted &&
                !StudySessionService.isPaused && !StudySessionService.isInCall
        if (showAlert) {
            val cd = StudySessionService.alarmCountdown
            val grace = StudySessionService.graceSecondsLeft
            alertCard.text = when {
                cd > 0 -> "⚠️ الهاتف اتشال من مكانه!\nالإنذار بعد $cd ث"
                StudySessionService.isAlarmRinging -> "🚨 رجّع الهاتف مكانه عشان يقف الإنذار\n(الجلسة تنتهي بعد $grace ث)"
                else -> "⚠️ الهاتف خارج مكانه — رجّعه خلال $grace ث"
            }
            alertCard.visibility = View.VISIBLE
        } else {
            alertCard.visibility = View.GONE
        }

        // Camera lock status line
        if (cameraSession) {
            cameraStatusText.visibility = View.VISIBLE
            cameraStatusText.text = when {
                !StudySessionService.cameraLockActive -> "📷 قفل الكاميرا متوقف مؤقتاً"
                StudySessionService.visualMismatch -> "📍 المكان اتغير (تطابق $lastPct%)"
                else -> "📷 قفل المكان شغال — تطابق $lastPct%"
            }
        } else {
            cameraStatusText.visibility = View.GONE
        }
    }
}
