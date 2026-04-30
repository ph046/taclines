package com.tac.lines

data class AiPlayPoint(
    val x: Float,
    val y: Float
)

data class AiPlayGesture(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMs: Long
)

data class AiPlayStep(
    val ok: Boolean,
    val action: String,
    val confidence: Float,
    val gesture: AiPlayGesture?,
    val done: Boolean,
    val shouldShoot: Boolean,
    val message: String = ""
) {
    fun isUsable(): Boolean {
        return ok &&
                confidence >= 0.45f &&
                action.isNotBlank()
    }

    fun isDragAction(): Boolean {
        return action.lowercase() == "drag" && gesture != null
    }

    fun isShootAction(): Boolean {
        return action.lowercase() == "shoot" &&
                shouldShoot &&
                gesture != null
    }

    fun isStopAction(): Boolean {
        val a = action.lowercase()
        return a == "stop" || a == "fail" || a == "none"
    }
}
