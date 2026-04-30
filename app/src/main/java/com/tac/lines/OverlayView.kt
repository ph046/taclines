package com.tac.lines

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var rayLine: RayLine? = null

    private val rayGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 0, 220, 255)
        strokeWidth = 16f
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
        color = Color.argb(255, 0, 255, 255)
        style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?) {
        update(lines, pockets, cue, null)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?, rayLine: RayLine?) {
        this.rayLine = rayLine
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        rayLine?.let { ray ->
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayGlowPaint)
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayPaint)
            canvas.drawCircle(ray.x2, ray.y2, 8f, rayEndPaint)
        }
    }
}
