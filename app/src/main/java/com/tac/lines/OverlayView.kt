package com.tac.lines

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var lines: List<AimLine> = emptyList()
    private var pockets: List<Pocket> = emptyList()
    private var cue: Ball? = null
    private var rayLine: RayLine? = null

    private val greenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 0, 255, 80)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(28f, 12f), 0f)
    }

    private val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 50, 50)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }

    private val yellowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 220, 0)
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 10f), 0f)
    }

    private val ghostPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 80)
        style = Paint.Style.FILL
    }

    private val ghostStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 255, 80)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val cuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 255, 165, 0)
        style = Paint.Style.FILL
    }

    private val rayGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(85, 0, 220, 255)
        strokeWidth = 14f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 255)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rayEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 255)
        style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?) {
        update(lines, pockets, cue, null)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?, rayLine: RayLine?) {
        this.lines = lines
        this.pockets = pockets
        this.cue = cue
        this.rayLine = rayLine
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (p in pockets) {
            canvas.drawCircle(p.x, p.y, 16f, pocketPaint)
        }

        cue?.let {
            canvas.drawCircle(it.x, it.y, it.r + 6f, cuePaint)
        }

        for (l in lines) {
            if (!l.willScore) {
                canvas.drawLine(l.cueX, l.cueY, l.ghostX, l.ghostY, redLine)
            }
        }

        for (l in lines) {
            if (l.willScore) {
                canvas.drawLine(l.cueX, l.cueY, l.ghostX, l.ghostY, greenLine)

                val r = cue?.r ?: 14f
                canvas.drawCircle(l.ghostX, l.ghostY, r, ghostPaint)
                canvas.drawCircle(l.ghostX, l.ghostY, r, ghostStroke)

                canvas.drawLine(l.ballX, l.ballY, l.pocketX, l.pocketY, yellowLine)
            }
        }

        rayLine?.let { ray ->
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayGlowPaint)
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayPaint)
            canvas.drawCircle(ray.x2, ray.y2, 7f, rayEndPaint)
        }
    }
}
