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
    private var aiPlaying = false
    private var lastStatusTime = 0L

    private val scanDelayMs = 350L
    private val maxAiSteps = 5

    private var statusView: TextView? = null
    private var scanButton: Button? = null
    private var aiButton: Button? = null
    private var stopButton: Button? = null

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
            text = "🤖 IA JOGAR"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 180, 120, 255))
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
            text = "Pronto | aperte IA JOGAR"
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.CENTER
        }

        scanButton = btnScan
        aiButton = btnAi
        stopButton = btnStop
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
            btnStop,
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
            startAiPlay()
        }

        btnStop.setOnClickListener {
            stopAiPlay("IA parada")
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

            statusView?.text = "SCAN local ligado"
            statusView?.setTextColor(Color.YELLOW)

            main.removeCallbacks(scanLoop)
            main.post(scanLoop)
        } else {
            scanButton?.text = "▶ SCAN"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

            statusView?.text = "SCAN desligado | aperte IA JOGAR"
            statusView?.setTextColor(Color.GRAY)

            main.removeCallbacks(scanLoop)
            overlay?.update(emptyList(), emptyList(), null, null, null)
        }
    }

    private fun startAiPlay() {
        if (aiPlaying) return

        if (!AutoAimAccessibilityService.isRunning()) {
            statusView?.text = "Acessibilidade OFF"
            statusView?.setTextColor(Color.RED)
            return
        }

        aiPlaying = true
        autoScan = false
        main.removeCallbacks(scanLoop)

        scanButton?.text = "▶ SCAN"
        scanButton?.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

        updateAiButton(false)
        updateStopButton(true)

        overlay?.update(emptyList(), emptyList(), null, null, null)

        statusView?.text = "IA jogando..."
        statusView?.setTextColor(Color.YELLOW)

        main.postDelayed({
            runAiStep(0)
        }, 350L)
    }

    private fun stopAiPlay(message: String) {
        aiPlaying = false
        updateAiButton(true)
        updateStopButton(false)

        statusView?.text = message
        statusView?.setTextColor(Color.GRAY)
    }

    private fun runAiStep(stepIndex: Int) {
        if (!aiPlaying) return

        if (stepIndex >= maxAiSteps) {
            stopAiPlay("IA parou: limite de passos")
            return
        }

        if (AutoAimAccessibilityService.isBusy()) {
            main.postDelayed({
                runAiStep(stepIndex)
            }, 300L)
            return
        }

        val img = reader?.acquireLatestImage()
        if (img == null) {
            stopAiPlay("IA: sem imagem")
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
            stopAiPlay("IA: erro imagem")
            statusView?.setTextColor(Color.RED)
            return
        }

        statusView?.text = "IA vendo tela ${stepIndex + 1}/$maxAiSteps..."
        statusView?.setTextColor(Color.YELLOW)

        bg.post {
            try {
                val step = AiPlayerClient.playStep(
                    bitmap = finalBmp,
                    stepIndex = stepIndex,
                    maxSteps = maxAiSteps
                )

                if (!finalBmp.isRecycled) {
                    finalBmp.recycle()
                }

                main.post {
                    handleAiStep(step, stepIndex)
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
                    stopAiPlay("IA erro: ${e.message ?: "backend"}")
                    statusView?.setTextColor(Color.RED)
                }
            }
        }
    }

    private fun handleAiStep(step: AiPlayStep, stepIndex: Int) {
        if (!aiPlaying) return

        val msg = step.message.ifBlank { step.action }

        if (step.isStopAction()) {
            stopAiPlay("IA parou: ${msg.take(55)}")
            statusView?.setTextColor(Color.RED)
            return
        }

        if (!step.ok || !step.isUsable()) {
            stopAiPlay("IA falhou: ${msg.take(55)}")
            statusView?.setTextColor(Color.RED)
            return
        }

        val gesture = step.gesture

        if (gesture == null) {
            stopAiPlay("IA sem gesto")
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
            stopAiPlay("Falha ao executar gesto")
            statusView?.setTextColor(Color.RED)
            return
        }

        when {
            step.isShootAction() -> {
                statusView?.text =
                    "IA bateu ${(step.confidence * 100f).toInt()}% | ${msg.take(45)}"
                statusView?.setTextColor(Color.GREEN)

                main.postDelayed({
                    stopAiPlay("IA finalizou")
                }, gesture.durationMs + 1200L)
            }

            step.isDragAction() -> {
                statusView?.text =
                    "IA ajustando ${(step.confidence * 100f).toInt()}% | ${msg.take(45)}"
                statusView?.setTextColor(Color.YELLOW)

                main.postDelayed({
                    runAiStep(stepIndex + 1)
                }, gesture.durationMs + 900L)
            }

            step.done -> {
                stopAiPlay("IA finalizou: ${msg.take(45)}")
                statusView?.setTextColor(Color.GREEN)
            }

            else -> {
                main.postDelayed({
                    runAiStep(stepIndex + 1)
                }, gesture.durationMs + 900L)
            }
        }
    }

    private fun scanFrame(forceStatus: Boolean = false) {
        if (processing || aiPlaying) return

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

                overlay?.update(
                    lines = emptyList(),
                    pockets = emptyList(),
                    cue = cue,
                    rayLine = null,
                    autoShot = null
                )

                postStatusLimited(
                    text = "SCAN | branca=${if (cue != null) "OK" else "OFF"} | bolas=${balls.size}",
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

    private fun updateAiButton(enabled: Boolean) {
        main.post {
            aiButton?.isEnabled = enabled
            aiButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    if (enabled) {
                        Color.argb(255, 180, 120, 255)
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
        aiPlaying = false

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
        aiPlaying = false

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
            .setContentText("IA jogando por passos")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
