package com.tac.lines

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class AutoAimAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: AutoAimAccessibilityService? = null

        const val SHOT_MODE_FORWARD = 0
        const val SHOT_MODE_BACKWARD = 1
        const val SHOT_MODE_CUE_PULL = 2

        fun isRunning(): Boolean {
            return instance != null
        }

        fun isBusy(): Boolean {
            return instance?.busy == true
        }

        fun drag(
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            durationMs: Long = 260L
        ): Boolean {
            val service = instance ?: return false
            return service.performSmoothDrag(fromX, fromY, toX, toY, durationMs)
        }

        fun tap(
            x: Float,
            y: Float,
            durationMs: Long = 60L
        ): Boolean {
            val service = instance ?: return false
            return service.performTap(x, y, durationMs)
        }

        fun smartShot(
            cueX: Float,
            cueY: Float,
            ghostX: Float,
            ghostY: Float,
            powerDistance: Float,
            durationMs: Long = 320L,
            mode: Int = SHOT_MODE_CUE_PULL
        ): Boolean {
            val service = instance ?: return false
            return service.performSmartShot(
                cueX = cueX,
                cueY = cueY,
                ghostX = ghostX,
                ghostY = ghostY,
                powerDistance = powerDistance,
                durationMs = durationMs,
                mode = mode
            )
        }

        fun arcAim(
            cueX: Float,
            cueY: Float,
            startAngle: Float,
            endAngle: Float,
            radius: Float,
            durationMs: Long = 260L
        ): Boolean {
            val service = instance ?: return false
            return service.performArcAim(cueX, cueY, startAngle, endAngle, radius, durationMs)
        }
    }

    private data class TouchStep(
        val fromX: Float,
        val fromY: Float,
        val toX: Float,
        val toY: Float,
        val durationMs: Long,
        val delayAfterMs: Long = 80L,
        val curved: Boolean = true
    )

    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        busy = false
        super.onDestroy()
    }

    override fun onInterrupt() {
        busy = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Serviço só executa gestos. Quem decide é o LineService.
    }

    private fun performTap(
        x: Float,
        y: Float,
        durationMs: Long
    ): Boolean {
        if (busy) return false

        val path = Path().apply {
            moveTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    durationMs.coerceIn(30L, 250L)
                )
            )
            .build()

        busy = true

        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    busy = false
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false
                }
            },
            main
        )

        if (!started) busy = false
        return started
    }

    private fun performSmoothDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        if (busy) return false

        val dist = hypot(toX - fromX, toY - fromY)
        if (dist < 5f) return false

        val step = TouchStep(
            fromX = fromX,
            fromY = fromY,
            toX = toX,
            toY = toY,
            durationMs = durationMs.coerceIn(100L, 900L),
            delayAfterMs = 0L,
            curved = true
        )

        return performSequence(listOf(step))
    }

    private fun performSmartShot(
        cueX: Float,
        cueY: Float,
        ghostX: Float,
        ghostY: Float,
        powerDistance: Float,
        durationMs: Long,
        mode: Int
    ): Boolean {
        if (busy) return false

        val dx = ghostX - cueX
        val dy = ghostY - cueY
        val len = hypot(dx, dy)

        if (len < 8f) return false

        val nx = dx / len
        val ny = dy / len

        val power = powerDistance.coerceIn(120f, 360f)
        val dur = durationMs.coerceIn(180L, 650L)

        val steps = when (mode) {
            SHOT_MODE_FORWARD -> {
                listOf(
                    TouchStep(
                        fromX = cueX,
                        fromY = cueY,
                        toX = cueX + nx * power,
                        toY = cueY + ny * power,
                        durationMs = dur,
                        delayAfterMs = 120L
                    )
                )
            }

            SHOT_MODE_BACKWARD -> {
                listOf(
                    TouchStep(
                        fromX = cueX,
                        fromY = cueY,
                        toX = cueX - nx * power,
                        toY = cueY - ny * power,
                        durationMs = dur,
                        delayAfterMs = 120L
                    )
                )
            }

            else -> {
                /*
                 * Modo mais forte:
                 * 1. toca na bola branca
                 * 2. faz um arraste curto na direção da mira para “acordar” o controle
                 * 3. puxa no sentido oposto para força
                 */
                listOf(
                    TouchStep(
                        fromX = cueX,
                        fromY = cueY,
                        toX = cueX,
                        toY = cueY,
                        durationMs = 70L,
                        delayAfterMs = 120L,
                        curved = false
                    ),
                    TouchStep(
                        fromX = cueX - nx * 35f,
                        fromY = cueY - ny * 35f,
                        toX = cueX + nx * 155f,
                        toY = cueY + ny * 155f,
                        durationMs = 260L,
                        delayAfterMs = 160L,
                        curved = true
                    ),
                    TouchStep(
                        fromX = cueX + nx * 45f,
                        fromY = cueY + ny * 45f,
                        toX = cueX - nx * power,
                        toY = cueY - ny * power,
                        durationMs = dur,
                        delayAfterMs = 100L,
                        curved = true
                    )
                )
            }
        }

        return performSequence(steps)
    }

    private fun performArcAim(
        cueX: Float,
        cueY: Float,
        startAngle: Float,
        endAngle: Float,
        radius: Float,
        durationMs: Long
    ): Boolean {
        if (busy) return false

        val r = radius.coerceIn(80f, 360f)

        val fromX = cueX + cos(startAngle) * r
        val fromY = cueY + sin(startAngle) * r
        val toX = cueX + cos(endAngle) * r
        val toY = cueY + sin(endAngle) * r

        val step = TouchStep(
            fromX = fromX,
            fromY = fromY,
            toX = toX,
            toY = toY,
            durationMs = durationMs.coerceIn(120L, 800L),
            delayAfterMs = 80L,
            curved = true
        )

        return performSequence(listOf(step))
    }

    private fun performSequence(steps: List<TouchStep>): Boolean {
        if (busy) return false
        if (steps.isEmpty()) return false

        busy = true
        runStep(steps, 0)
        return true
    }

    private fun runStep(steps: List<TouchStep>, index: Int) {
        if (index >= steps.size) {
            busy = false
            return
        }

        val step = steps[index]

        val dist = hypot(step.toX - step.fromX, step.toY - step.fromY)

        val path = Path().apply {
            moveTo(step.fromX, step.fromY)

            if (dist < 3f || !step.curved) {
                lineTo(step.toX, step.toY)
            } else {
                val midX = (step.fromX + step.toX) / 2f
                val midY = (step.fromY + step.toY) / 2f

                val bendX = midX + (step.toY - step.fromY) * 0.035f
                val bendY = midY - (step.toX - step.fromX) * 0.035f

                quadTo(bendX, bendY, step.toX, step.toY)
            }
        }

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    step.durationMs.coerceIn(40L, 1000L)
                )
            )
            .build()

        val started = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    main.postDelayed({
                        runStep(steps, index + 1)
                    }, step.delayAfterMs.coerceIn(0L, 600L))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    busy = false
                }
            },
            main
        )

        if (!started) {
            busy = false
        }
    }
}
