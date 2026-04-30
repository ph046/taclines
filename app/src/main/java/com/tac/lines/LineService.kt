package com.tac.lines

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat

class LineService : Service() {

    companion object {
        const val CH = "tac_lines"
        const val NID = 66
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null

    private lateinit var wm: WindowManager
    private var overlay: OverlayView? = null
    private var panel: View? = null

    private var sw = 0
    private var sh = 0
    private var dpi = 0

    private val main = Handler(Looper.getMainLooper())

    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler

    private var autoScan = false
    private var processing = false
    private var calibrating = false
    private var lastStatusTime = 0L

    private val scanDelayMs = 140L

    private var statusView: TextView? = null
    private var scanButton: Button? = null
    private var aiButton: Button? = null
    private var aimButton: Button? = null

    private var latestShot: AutoAimShot? = null
    private var latestCue: Ball? = null
    private var latestBallCount = 0

    private var aiCalibration: AiCalibration? = null

    private val projCb = object : MediaProjection.Callback() {
        override fun onStop() {
            cleanup()
            stopSelf()
        }
    }

    private val scanLoop = object : Runnable {
        override fun run() {
            if (autoScan) {
                scanFrame()
                main.postDelayed(this, scanDelayMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        bgThread = HandlerThread("LinesThread")
        bgThread.start()
        bg = Handler(bgThread.looper)

        refreshMetrics()
        createChannel()
    }

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", Activity.RESULT_CANCELED)
            ?: return START_NOT_STICKY

        val data: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("data", Intent::class.java)
                ?: return START_NOT_STICKY
        } else {
            intent.getParcelableExtra("data")
                ?: return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NID,
                buildNotif(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NID, buildNotif())
        }

        cleanup()
        refreshMetrics()

        val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = pm.getMediaProjection(code, data)
        projection?.registerCallback(projCb, main)

        reader = ImageReader.newInstance(sw, sh, PixelFormat.RGBA_8888, 3)

        vDisplay = projection?.createVirtualDisplay(
            "TacLines",
            sw,
            sh,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader?.surface,
            null,
            main
        )

        addOverlay()
        addPanel()

        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun refreshMetrics() {
        val m = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(m)

        sw = m.widthPixels
        sh = m.heightPixels
        dpi = m.densityDpi
    }

    private fun addOverlay() {
        val v = OverlayView(this)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(v, lp)
            overlay = v
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun addPanel() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(225, 8, 8, 8))
            setPadding(14, 14, 14, 14)
        }

        val btnScan = Button(this).apply {
            text = "▶ SCAN"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))
        }

        val btnAi = Button(this).apply {
            text = "🧠 CALIBRAR IA"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 180, 120, 255))
        }

        val btnAim = Button(this).apply {
            text = "🎯 AUTO AIM"
            setTextColor(Color.BLACK)
            textSize = 13f
            isEnabled = false
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 100, 100, 100))
        }

        val tvStatus = TextView(this).apply {
            text = "IA=OFF | SCAN desligado"
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.CENTER
        }

        scanButton = btnScan
        aiButton = btnAi
        aimButton = btnAim
        statusView = tvStatus

        root.addView(
            btnScan,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            btnAi,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            btnAim,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            tvStatus,
            LinearLayout.LayoutParams(-1, -2)
        )

        val lp = WindowManager.LayoutParams(
            270,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 8
            y = 150
        }

        var ox = 0f
        var oy = 0f
        var sx = 0f
        var sy = 0f

        root.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX
                    sy = e.rawY
                    ox = lp.x.toFloat()
                    oy = lp.y.toFloat()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    lp.x = (ox - (e.rawX - sx)).toInt()
                    lp.y = (oy + (e.rawY - sy)).toInt()

                    try {
                        wm.updateViewLayout(v, lp)
                    } catch (_: Exception) {
                    }

                    true
                }

                else -> false
            }
        }

        btnScan.setOnClickListener {
            toggleAutoScan()
        }

        btnScan.setOnLongClickListener {
            scanFrame(forceStatus = true)
            true
        }

        btnAi.setOnClickListener {
            calibrateWithAi()
        }

        btnAim.setOnClickListener {
            performAutoAim()
        }

        try {
            wm.addView(root, lp)
            panel = root
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleAutoScan() {
        autoScan = !autoScan

        if (autoScan) {
            scanButton?.text = "■ SCAN"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 255, 210, 0))

            statusView?.text = "Escaneando..."
            statusView?.setTextColor(Color.YELLOW)

            main.removeCallbacks(scanLoop)
            main.post(scanLoop)
        } else {
            scanButton?.text = "▶ SCAN"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

            statusView?.text = if (aiCalibration?.isUsable() == true) {
                "IA=OK | SCAN desligado"
            } else {
                "IA=OFF | SCAN desligado"
            }

            statusView?.setTextColor(Color.GRAY)

            main.removeCallbacks(scanLoop)

            latestShot = null
            latestCue = null
            latestBallCount = 0

            updateAimButton(false)

            overlay?.update(emptyList(), emptyList(), null, null, null)
        }
    }

    private fun calibrateWithAi() {
        if (calibrating) return

        val img = reader?.acquireLatestImage()
        if (img == null) {
            statusView?.text = "IA: sem imagem"
            statusView?.setTextColor(Color.RED)
            return
        }

        calibrating = true

        statusView?.text = "IA calibrando..."
        statusView?.setTextColor(Color.YELLOW)

        val finalBmp = try {
            imageToBitmap(img)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                img.close()
            } catch (_: Exception) {
            }
        }

        if (finalBmp == null) {
            calibrating = false
            statusView?.text = "IA: erro imagem"
            statusView?.setTextColor(Color.RED)
            return
        }

        bg.post {
            try {
                val calibration = CalibrationClient.calibrate(finalBmp)

                if (!finalBmp.isRecycled) {
                    finalBmp.recycle()
                }

                aiCalibration = calibration

                val usable = calibration.isUsable()

                main.post {
                    calibrating = false

                    if (usable) {
                        statusView?.text =
                            "IA=OK ${(calibration.confidence * 100f).toInt()}% | bolas=${calibration.balls.size}"
                        statusView?.setTextColor(Color.GREEN)

                        updateAimButton(latestShot != null)
                    } else {
                        statusView?.text =
                            "IA=OFF | ${calibration.message.ifBlank { "calibração fraca" }}"
                        statusView?.setTextColor(Color.RED)

                        updateAimButton(false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()

                try {
                    if (!finalBmp.isRecycled) {
                        finalBmp.recycle()
                    }
                } catch (_: Exception) {
                }

                main.post {
                    calibrating = false
                    aiCalibration = null
                    updateAimButton(false)

                    statusView?.text = "IA erro: backend"
                    statusView?.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun scanFrame(forceStatus: Boolean = false) {
        if (processing) return

        val img = reader?.acquireLatestImage()
        if (img == null) {
            if (forceStatus) {
                main.post {
                    statusView?.text = "Sem imagem"
                    statusView?.setTextColor(Color.RED)
                }
            }
            return
        }

        processing = true

        val finalBmp = try {
            imageToBitmap(img)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            try {
                img.close()
            } catch (_: Exception) {
            }
        }

        if (finalBmp == null) {
            processing = false
            postStatusLimited(
                text = "Erro imagem",
                color = Color.RED,
                force = true
            )
            return
        }

        bg.post {
            try {
                val detectorResult = Detector.analyzeFull(finalBmp)

                if (!finalBmp.isRecycled) {
                    finalBmp.recycle()
                }

                val ai = aiCalibration

                val usingAi = ai?.isUsable() == true

                val cue: Ball?
                val balls: List<Ball>
                val pockets: List<Pocket>

                if (usingAi && ai != null) {
                    cue = ai.cueAsBall()
                    balls = ai.ballsAsList()
                    pockets = ai.pocketsAsList()
                } else {
                    cue = detectorResult.cue
                    balls = detectorResult.balls
                    pockets = detectorResult.pockets
                }

                val rayLine = detectorResult.aimLine

                val shot = if (usingAi) {
                    AutoAimEngine.findBestShot(
                        cue = cue,
                        balls = balls,
                        pockets = pockets
                    )
                } else {
                    null
                }

                latestShot = shot
                latestCue = cue
                latestBallCount = balls.size

                overlay?.update(
                    lines = emptyList(),
                    pockets = emptyList(),
                    cue = cue,
                    rayLine = rayLine,
                    autoShot = shot
                )

                val aiStatus = if (usingAi) "IA=OK" else "IA=OFF"
                val rayStatus = if (rayLine != null) "mira=OK" else "mira=OFF"
                val cueStatus = if (cue != null) "branca=OK" else "branca=OFF"
                val shotStatus = if (shot != null) {
                    "shot=${(shot.confidence * 100f).toInt()}%"
                } else {
                    "shot=OFF"
                }

                updateAimButton(usingAi && shot != null)

                postStatusLimited(
                    text = "$aiStatus | $rayStatus | $cueStatus | bolas=${balls.size} | $shotStatus",
                    color = when {
                        usingAi && shot != null -> Color.GREEN
                        usingAi -> Color.YELLOW
                        cue != null -> Color.YELLOW
                        else -> Color.RED
                    },
                    force = forceStatus
                )

                processing = false
            } catch (e: Exception) {
                e.printStackTrace()

                try {
                    if (!finalBmp.isRecycled) {
                        finalBmp.recycle()
                    }
                } catch (_: Exception) {
                }

                latestShot = null
                latestCue = null
                latestBallCount = 0

                updateAimButton(false)

                overlay?.update(emptyList(), emptyList(), null, null, null)

                postStatusLimited(
                    text = "Erro scan",
                    color = Color.RED,
                    force = true
                )

                processing = false
            }
        }
    }

    private fun performAutoAim() {
        val aiOk = aiCalibration?.isUsable() == true

        if (!aiOk) {
            statusView?.text = "AUTO bloqueado: calibre IA"
            statusView?.setTextColor(Color.RED)
            return
        }

        val shot = latestShot

        if (shot == null) {
            statusView?.text = "Sem jogada IA"
            statusView?.setTextColor(Color.RED)
            return
        }

        if (!AutoAimAccessibilityService.isRunning()) {
            statusView?.text = "Acessibilidade OFF"
            statusView?.setTextColor(Color.RED)
            return
        }

        val ok = AutoAimAccessibilityService.drag(
            fromX = shot.pullFromX,
            fromY = shot.pullFromY,
            toX = shot.pullToX,
            toY = shot.pullToY,
            durationMs = shot.durationMs
        )

        if (ok) {
            statusView?.text = "AUTO AIM enviado ${(shot.confidence * 100f).toInt()}%"
            statusView?.setTextColor(Color.GREEN)
        } else {
            statusView?.text = "Falha no gesto"
            statusView?.setTextColor(Color.RED)
        }
    }

    private fun imageToBitmap(img: android.media.Image): Bitmap {
        val plane = img.planes[0]
        val bw = plane.rowStride / plane.pixelStride

        val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)

        return if (bw != img.width) {
            Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also {
                bmp.recycle()
            }
        } else {
            bmp
        }
    }

    private fun updateAimButton(enabled: Boolean) {
        main.post {
            aimButton?.isEnabled = enabled
            aimButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (enabled) {
                        Color.argb(255, 0, 190, 255)
                    } else {
                        Color.argb(255, 100, 100, 100)
                    }
                )
        }
    }

    private fun postStatusLimited(text: String, color: Int, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()

        if (!force && now - lastStatusTime < 250L) {
            return
        }

        lastStatusTime = now

        main.post {
            statusView?.text = text
            statusView?.setTextColor(color)
        }
    }

    private fun cleanup() {
        autoScan = false
        processing = false
        calibrating = false
        main.removeCallbacks(scanLoop)

        try {
            vDisplay?.release()
        } catch (_: Exception) {
        }
        vDisplay = null

        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    override fun onDestroy() {
        autoScan = false
        processing = false
        calibrating = false

        main.removeCallbacksAndMessages(null)

        try {
            overlay?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        overlay = null

        try {
            panel?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }
        panel = null

        cleanup()

        try {
            projection?.unregisterCallback(projCb)
        } catch (_: Exception) {
        }

        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null

        try {
            bgThread.quitSafely()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

            nm.createNotificationChannel(
                NotificationChannel(
                    CH,
                    "TacLines",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun buildNotif(): Notification {
        return NotificationCompat.Builder(this, CH)
            .setContentTitle("TacLines ativo")
            .setContentText("Assistente de linha ativo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
