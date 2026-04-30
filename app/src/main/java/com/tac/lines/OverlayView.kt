package com.tac.lines

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var lines: List<AimLine> = emptyList()
    private var pockets: List<Pocket> = emptyList()
    private var cue: Ball? = null

    private val greenLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 0, 255, 80)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(28f, 12f), 0f)
    }
    private val redLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 50, 50)
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(18f, 14f), 0f)
    }
    private val yellowLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 220, 0)
        strokeWidth = 4f
        style = Paint.Style.STROKE
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

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?) {
        this.lines = lines
        this.pockets = pockets
        this.cue = cue
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Draw pocket markers
        for (p in pockets) canvas.drawCircle(p.x, p.y, 16f, pocketPaint)

        // Draw cue ball ring
        cue?.let { canvas.drawCircle(it.x, it.y, it.r + 6f, cuePaint) }

        val scoring = lines.filter { it.willScore }
        val blocked = lines.filter { !it.willScore }

        // Blocked lines (red, behind scoring)
        for (l in blocked) {
            canvas.drawLine(l.cueX, l.cueY, l.ghostX, l.ghostY, redLine)
        }

        // Scoring lines (green on top)
        for (l in scoring) {
            // Cue → ghost ball
            canvas.drawLine(l.cueX, l.cueY, l.ghostX, l.ghostY, greenLine)
            // Ghost ball circle
            val r = cue?.r ?: 14f
            canvas.drawCircle(l.ghostX, l.ghostY, r, ghostPaint)
            canvas.drawCircle(l.ghostX, l.ghostY, r, ghostStroke)
            // Target ball → pocket
            canvas.drawLine(l.ballX, l.ballY, l.pocketX, l.pocketY, yellowLine)
        }
    }
}
