package com.tac.lines

import android.content.Context
import android.graphics.*
import android.view.View

class OverlayView(context: Context) : View(context) {

    private var rayLine: RayLine? = null
    private var autoShot: AutoAimShot? = null

    private val rayGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 220, 255)
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

    private val autoCueLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 90)
        strokeWidth = 6f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val autoCueGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 0, 255, 90)
        strokeWidth = 18f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val autoPocketLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 220, 0)
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(22f, 10f), 0f)
    }

    private val ghostFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 255, 90)
        style = Paint.Style.FILL
    }

    private val ghostStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 90)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 255, 80, 80)
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val pocketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 220, 0)
        style = Paint.Style.FILL
    }

    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 255, 255)
        style = Paint.Style.FILL
    }

    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(255, 0, 255, 255)
        style = Paint.Style.FILL
    }

    init {
        setWillNotDraw(false)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?) {
        update(lines, pockets, cue, null, null)
    }

    fun update(lines: List<AimLine>, pockets: List<Pocket>, cue: Ball?, rayLine: RayLine?) {
        update(lines, pockets, cue, rayLine, null)
    }

    fun update(
        lines: List<AimLine>,
        pockets: List<Pocket>,
        cue: Ball?,
        rayLine: RayLine?,
        autoShot: AutoAimShot?
    ) {
        this.rayLine = rayLine
        this.autoShot = autoShot
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawRayLine(canvas)
        drawAutoShot(canvas)
    }

    private fun drawRayLine(canvas: Canvas) {
        rayLine?.let { ray ->
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayGlowPaint)
            canvas.drawLine(ray.x1, ray.y1, ray.x2, ray.y2, rayPaint)
            canvas.drawCircle(ray.x1, ray.y1, 5f, startPaint)
            canvas.drawCircle(ray.x2, ray.y2, 8f, endPaint)
        }
    }

    private fun drawAutoShot(canvas: Canvas) {
        val shot = autoShot ?: return

        // Linha da bola branca até o ponto fantasma
        canvas.drawLine(
            shot.cueX,
            shot.cueY,
            shot.ghostX,
            shot.ghostY,
            autoCueGlowPaint
        )

        canvas.drawLine(
            shot.cueX,
            shot.cueY,
            shot.ghostX,
            shot.ghostY,
            autoCueLinePaint
        )

        // Linha da bola alvo até a caçapa
        canvas.drawLine(
            shot.targetBallX,
            shot.targetBallY,
            shot.pocketX,
            shot.pocketY,
            autoPocketLinePaint
        )

        // Ponto fantasma
        canvas.drawCircle(shot.ghostX, shot.ghostY, 16f, ghostFillPaint)
        canvas.drawCircle(shot.ghostX, shot.ghostY, 16f, ghostStrokePaint)

        // Bola alvo escolhida
        canvas.drawCircle(shot.targetBallX, shot.targetBallY, 18f, targetPaint)

        // Caçapa escolhida
        canvas.drawCircle(shot.pocketX, shot.pocketY, 12f, pocketPaint)
    }
}
