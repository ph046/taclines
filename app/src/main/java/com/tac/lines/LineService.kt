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
    private var lastStatusTime = 0L

    private val scanDelayMs = 85L

    private var statusView: TextView? = null
    private var scanButton: Button? = null

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
            setBackgroundColor(Color.argb(210, 8, 8, 8))
            setPadding(14, 14, 14, 14)
        }

        val btnScan = Button(this).apply {
            text = "▶ AUTO"
            setTextColor(Color.BLACK)
            textSize = 13f
            backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))
        }

        val tvStatus = TextView(this).apply {
            text = "AUTO desligado"
            setTextColor(Color.GRAY)
            textSize = 10f
            gravity = Gravity.CENTER
        }

        scanButton = btnScan
        statusView = tvStatus

        root.addView(
            btnScan,
            LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = 6 }
        )

        root.addView(
            tvStatus,
            LinearLayout.LayoutParams(-1, -2)
        )

        val lp = WindowManager.LayoutParams(
            220,
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
            scanButton?.text = "■ AUTO"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 255, 210, 0))

            statusView?.text = "AUTO ligado"
            statusView?.setTextColor(Color.YELLOW)

            main.removeCallbacks(scanLoop)
            main.post(scanLoop)
        } else {
            scanButton?.text = "▶ AUTO"
            scanButton?.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.argb(255, 0, 210, 80))

            statusView?.text = "AUTO desligado"
            statusView?.setTextColor(Color.GRAY)

            main.removeCallbacks(scanLoop)
            overlay?.update(emptyList(), emptyList(), null, null)
        }
    }

    private fun scanFrame(forceStatus: Boolean = false) {
        if (processing) return

        val tv = statusView

        val img = reader?.acquireLatestImage()
        if (img == null) {
            if (forceStatus) {
                main.post {
                    tv?.text = "Sem imagem"
                    tv?.setTextColor(Color.RED)
                }
            }
            return
        }

        processing = true

        try {
            val plane = img.planes[0]
            val bw = plane.rowStride / plane.pixelStride

            val bmp = Bitmap.createBitmap(bw, img.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)

            val finalBmp = if (bw != img.width) {
                Bitmap.createBitmap(bmp, 0, 0, img.width, img.height).also {
                    bmp.recycle()
                }
            } else {
                bmp
            }

            bg.post {
                try {
                    val result = Detector.analyzeFull(finalBmp)

                    if (!finalBmp.isRecycled) {
                        finalBmp.recycle()
                    }

                    val balls = result.balls
                    val rayLine = result.aimLine

                    overlay?.update(
                        lines = emptyList(),
                        pockets = emptyList(),
                        cue = null,
                        rayLine = rayLine
                    )

                    val rayStatus = if (rayLine != null) "mira=OK" else "mira=OFF"

                    postStatusLimited(
                        text = "$rayStatus | bolas=${balls.size}",
                        color = if (rayLine != null) Color.GREEN else Color.YELLOW,
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

                    postStatusLimited(
                        text = "Erro scan",
                        color = Color.RED,
                        force = true
                    )

                    processing = false
                }
            }
        } finally {
            try {
                img.close()
            } catch (_: Exception) {
            }
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
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
