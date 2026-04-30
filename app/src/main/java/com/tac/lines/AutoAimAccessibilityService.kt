package com.tac.lines

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import kotlin.math.hypot

class AutoAimAccessibilityService : AccessibilityService() {

    companion object {
        private var instance: AutoAimAccessibilityService? = null

        fun isRunning(): Boolean {
            return instance != null
        }

        fun drag(
            fromX: Float,
            fromY: Float,
            toX: Float,
            toY: Float,
            durationMs: Long = 260L
        ): Boolean {
            val service = instance ?: return false
            return service.performDrag(fromX, fromY, toX, toY, durationMs)
        }

        fun tap(
            x: Float,
            y: Float,
            durationMs: Long = 60L
        ): Boolean {
            val service = instance ?: return false
            return service.performTap(x, y, durationMs)
        }
    }

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
        super.onDestroy()
    }

    override fun onInterrupt() {
        busy = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Não precisa ler eventos da tela.
        // Esse serviço só existe para executar gesto quando o app mandar.
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
                    durationMs.coerceIn(20L, 300L)
                )
            )
            .build()

        busy = true

        return dispatchGesture(
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
    }

    private fun performDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long
    ): Boolean {
        if (busy) return false

        val dist = hypot(toX - fromX, toY - fromY)
        if (dist < 5f) return false

        val path = Path().apply {
            moveTo(fromX, fromY)
            lineTo(toX, toY)
        }

        val safeDuration = durationMs.coerceIn(80L, 900L)

        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    safeDuration
                )
            )
            .build()

        busy = true

        return dispatchGesture(
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
    }
}
