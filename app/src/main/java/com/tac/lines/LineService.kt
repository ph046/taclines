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
    private var pocketCaptureView: View? = null

    private var sw = 0
    private var sh = 0
    private var dpi = 0

    private val main = Handler(Looper.getMainLooper())

    private lateinit var bgThread: HandlerThread
    private lateinit var bg: Handler

    private var autoScan = false
    private var processing = false
    private var fineTuning = false
    private var calibratingPockets = false
    private var lastStatusTime = 0L

    private val scanDelayMs = 350L
    private val maxFineTuneSteps = 4

    private var statusView: TextView? = null
    private var scanButton: Button? = null
    private var pocketButton: Button? = null
    private var tuneButton: Button? = null
    private var stopButton: Button? = null

    private val pocketPoints = mutableListOf<CalibratedPocket>()

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

        val btnPockets = Button(this).apply {
            text = "🎱 BURACOS"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 255, 180, 40))
        }

        val btnTune = Button(this).apply {
            text = "🎯 AJUSTAR IA"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 190, 255))
        }

        val btnStop = Button(this).apply {
            text = "■ PARAR IA"
            setTextColor(Color.BLACK)
            textSize = 13f
            isEnabled = false
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 100, 100, 100))
        }

        val tvStatus = TextView(this).apply {
            text = if (PocketCalibration.isComplete(this@LineService)) {
                "Buracos OK | mire e aperte AJUSTAR IA"
            } else {
                "Calibre os buracos primeiro"
            }
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.CENTER
        }

        scanButton = btnScan
        pocketButton = btnPockets
        tuneButton = btnTune
        stopButton = btnStop
        statusView = tvStatus

        root.addView(
            btnScan,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            btnPockets,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            btnTune,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            btnStop,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            tvStatus,
            LinearLayout.LayoutParams(-1, -2)
        )

        val lp = WindowManager.LayoutParams(
            290,
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

        btnPockets.setOnClickListener {
            startPocketCalibration()
        }

        btnPockets.setOnLongClickListener {
            PocketCalibration.clear(this@LineService)
            pocketPoints.clear()
            overlay?.update(emptyList(), emptyList(), null, null, null)
            statusView?.text = "Buracos apagados"
            statusView?.setTextColor(Color.RED)
            true
        }

        btnTune.setOnClickListener {
            startFineTune()
        }

        btnStop.setOnClickListener {
            if (calibratingPockets) {
                stopPocketCalibration("Calibração cancelada")
            } else {
                stopFineTune("IA parada")
            }
        }

        try {
            wm.addView(root, lp)
            panel = root
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleAutoScan() {
        if (calibratingPockets || fineTuning) return

        autoScan = !autoScan

        if (autoScan) {
            scanButton?.text = "■ SCAN"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 255, 210, 0))

            statusView?.text = "SCAN local ligado"
            statusView?.setTextColor(Color.YELLOW)

            main.removeCallbacks(scanLoop)
            main.post(scanLoop)
        } else {
            scanButton?.text = "▶ SCAN"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

            statusView?.text = "SCAN desligado"
            statusView?.setTextColor(Color.GRAY)

            main.removeCallbacks(scanLoop)
            overlay?.update(emptyList(), PocketCalibration.toPocketList(this), null, null, null)
        }
    }

    private fun startPocketCalibration() {
        if (fineTuning || calibratingPockets) return

        autoScan = false
        main.removeCallbacks(scanLoop)

        scanButton?.text = "▶ SCAN"
        scanButton?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

        calibratingPockets = true
        pocketPoints.clear()

        updateTuneButton(false)
        updatePocketButton(false)
        updateStopButton(true)

        overlay?.update(emptyList(), emptyList(), null, null, null)

        statusView?.text = "Toque no buraco ${PocketCalibration.labelFor(PocketCalibration.REQUIRED_ORDER[0])}"
        statusView?.setTextColor(Color.YELLOW)

        addPocketCaptureLayer()
    }

    private fun addPocketCaptureLayer() {
        removePocketCaptureLayer()

        val v = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)

            setOnTouchListener { _, e ->
                if (!calibratingPockets) return@setOnTouchListener false

                when (e.action) {
                    MotionEvent.ACTION_UP -> {
                        recordPocketTap(e.rawX, e.rawY)
                        true
                    }

                    else -> true
                }
            }
        }

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(v, lp)
            pocketCaptureView = v
        } catch (e: Exception) {
            e.printStackTrace()
            stopPocketCalibration("Erro ao abrir captura")
            statusView?.setTextColor(Color.RED)
        }
    }

    private fun recordPocketTap(x: Float, y: Float) {
        if (!calibratingPockets) return

        val index = pocketPoints.size

        if (index >= PocketCalibration.REQUIRED_ORDER.size) return

        val id = PocketCalibration.REQUIRED_ORDER[index]

        pocketPoints.add(
            CalibratedPocket(
                id = id,
                x = x,
                y = y
            )
        )

        val preview = pocketPoints.map { Pocket(it.x, it.y) }
        overlay?.update(emptyList(), preview, null, null, null)

        if (pocketPoints.size >= PocketCalibration.REQUIRED_ORDER.size) {
            PocketCalibration.save(this, pocketPoints.toList())

            removePocketCaptureLayer()

            calibratingPockets = false
            updateTuneButton(true)
            updatePocketButton(true)
            updateStopButton(false)

            statusView?.text = "Buracos salvos ✅"
            statusView?.setTextColor(Color.GREEN)

            overlay?.update(emptyList(), PocketCalibration.toPocketList(this), null, null, null)
            return
        }

        val nextId = PocketCalibration.REQUIRED_ORDER[pocketPoints.size]

        statusView?.text =
            "Salvo ${pocketPoints.size}/6 | toque ${PocketCalibration.labelFor(nextId)}"
        statusView?.setTextColor(Color.YELLOW)
    }

    private fun stopPocketCalibration(message: String) {
        calibratingPockets = false
        pocketPoints.clear()

        removePocketCaptureLayer()

        updateTuneButton(true)
        updatePocketButton(true)
        updateStopButton(false)

        statusView?.text = message
        statusView?.setTextColor(Color.GRAY)

        overlay?.update(emptyList(), PocketCalibration.toPocketList(this), null, null, null)
    }

    private fun removePocketCaptureLayer() {
        try {
            pocketCaptureView?.let { wm.removeView(it) }
        } catch (_: Exception) {
        }

        pocketCaptureView = null
    }

    private fun startFineTune() {
        if (fineTuning || calibratingPockets) return

        if (!PocketCalibration.isComplete(this)) {
            statusView?.text = "Calibre os 6 buracos primeiro"
            statusView?.setTextColor(Color.RED)
            return
        }

        if (!AutoAimAccessibilityService.isRunning()) {
            statusView?.text = "Acessibilidade OFF"
            statusView?.setTextColor(Color.RED)
            return
        }

        fineTuning = true
        autoScan = false

        main.removeCallbacks(scanLoop)

        scanButton?.text = "▶ SCAN"
        scanButton?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

        updateTuneButton(false)
        updatePocketButton(false)
        updateStopButton(true)

        overlay?.update(emptyList(), PocketCalibration.toPocketList(this), null, null, null)

        statusView?.text = "IA ajustando mira..."
        statusView?.setTextColor(Color.YELLOW)

        main.postDelayed({
            runFineTuneStep(0)
        }, 350L)
    }

    private fun stopFineTune(message: String) {
        fineTuning = false

        updateTuneButton(true)
        updatePocketButton(true)
        updateStopButton(false)

        statusView?.text = message
        statusView?.setTextColor(Color.GRAY)
    }

    private fun runFineTuneStep(stepIndex: Int) {
        if (!fineTuning) return

        if (stepIndex >= maxFineTuneSteps) {
            stopFineTune("IA parou: limite de ajustes")
            return
        }

        if (AutoAimAccessibilityService.isBusy()) {
            main.postDelayed({
                runFineTuneStep(stepIndex)
            }, 250L)
            return
        }

        val img = reader?.acquireLatestImage()
        if (img == null) {
            stopFineTune("IA: sem imagem")
            statusView?.setTextColor(Color.RED)
            return
        }

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
            stopFineTune("IA: erro imagem")
            statusView?.setTextColor(Color.RED)
            return
        }

        statusView?.text = "IA vendo mira ${stepIndex + 1}/$maxFineTuneSteps..."
        statusView?.setTextColor(Color.YELLOW)

        bg.post {
            try {
                val result = FineTuneClient.fineTune(
                    bitmap = finalBmp,
                    stepIndex = stepIndex,
                    maxSteps = maxFineTuneSteps,
                    calibratedPocketsJson = PocketCalibration.toPayloadJson(this@LineService)
                )

                if (!finalBmp.isRecycled) {
                    finalBmp.recycle()
                }

                main.post {
                    handleFineTuneResult(result, stepIndex)
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
                    stopFineTune("IA erro: ${e.message ?: "backend"}")
                    statusView?.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun handleFineTuneResult(result: FineTuneResult, stepIndex: Int) {
        if (!fineTuning) return

        val msg = result.message.ifBlank { result.action }

        if (result.isStop()) {
            stopFineTune("IA parou: ${msg.take(55)}")
            statusView?.setTextColor(Color.RED)
            return
        }

        if (!result.ok || !result.isUsable()) {
            stopFineTune("IA falhou: ${msg.take(55)}")
            statusView?.setTextColor(Color.RED)
            return
        }

        val gesture = result.gesture

        if (gesture == null) {
            stopFineTune("IA sem gesto")
            statusView?.setTextColor(Color.RED)
            return
        }

        val ok = AutoAimAccessibilityService.drag(
            fromX = gesture.fromX,
            fromY = gesture.fromY,
            toX = gesture.toX,
            toY = gesture.toY,
            durationMs = gesture.durationMs
        )

        if (!ok) {
            stopFineTune("Falha ao executar gesto")
            statusView?.setTextColor(Color.RED)
            return
        }

        when {
            result.isShoot() -> {
                statusView?.text =
                    "IA bateu ${(result.confidence * 100f).toInt()}% | força ${(result.power * 100f).toInt()}%"
                statusView?.setTextColor(Color.GREEN)

                main.postDelayed({
                    stopFineTune("IA finalizou")
                }, gesture.durationMs + 1000L)
            }

            result.isAdjust() -> {
                statusView?.text =
                    "IA ajuste ${result.direction} ${result.pixels.toInt()}px | ${(result.confidence * 100f).toInt()}%"
                statusView?.setTextColor(Color.YELLOW)

                main.postDelayed({
                    runFineTuneStep(stepIndex + 1)
                }, gesture.durationMs + 750L)
            }

            else -> {
                stopFineTune("IA finalizou: ${msg.take(45)}")
                statusView?.setTextColor(Color.GREEN)
            }
        }
    }

    private fun scanFrame(forceStatus: Boolean = false) {
        if (processing || fineTuning || calibratingPockets) return

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

                val cue = detectorResult.cue
                val balls = detectorResult.balls
                val pockets = PocketCalibration.toPocketList(this@LineService)

                overlay?.update(
                    lines = emptyList(),
                    pockets = pockets,
                    cue = cue,
                    rayLine = null,
                    autoShot = null
                )

                postStatusLimited(
                    text = "SCAN | branca=${if (cue != null) "OK" else "OFF"} | bolas=${balls.size} | buracos=${pockets.size}/6",
                    color = if (cue != null) Color.GREEN else Color.YELLOW,
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

                processing = false

                postStatusLimited(
                    text = "Erro scan",
                    color = Color.RED,
                    force = true
                )
            }
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

    private fun updateTuneButton(enabled: Boolean) {
        main.post {
            tuneButton?.isEnabled = enabled
            tuneButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (enabled) {
                        Color.argb(255, 0, 190, 255)
                    } else {
                        Color.argb(255, 100, 100, 100)
                    }
                )
        }
    }

    private fun updatePocketButton(enabled: Boolean) {
        main.post {
            pocketButton?.isEnabled = enabled
            pocketButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (enabled) {
                        Color.argb(255, 255, 180, 40)
                    } else {
                        Color.argb(255, 100, 100, 100)
                    }
                )
        }
    }

    private fun updateStopButton(enabled: Boolean) {
        main.post {
            stopButton?.isEnabled = enabled
            stopButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (enabled) {
                        Color.argb(255, 255, 90, 90)
                    } else {
                        Color.argb(255, 100, 100, 100)
                    }
                )
        }
    }

    private fun postStatusLimited(text: String, color: Int, force: Boolean = false) {
        val now = SystemClock.uptimeMillis()

        if (!force && now - lastStatusTime < 350L) {
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
        fineTuning = false
        calibratingPockets = false

        main.removeCallbacks(scanLoop)
        removePocketCaptureLayer()

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
        fineTuning = false
        calibratingPockets = false

        main.removeCallbacksAndMessages(null)

        removePocketCaptureLayer()

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
            .setContentText("Ajuste fino IA ativo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
