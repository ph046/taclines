package com.tac.lines

data class FineTuneGesture(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMs: Long
)

data class FineTuneResult(
    val ok: Boolean,
    val action: String,
    val confidence: Float,
    val direction: String,
    val pixels: Float,
    val gesture: FineTuneGesture?,
    val power: Float,
    val readyToShoot: Boolean,
    val message: String = ""
) {
    fun isUsable(): Boolean {
        return ok &&
                confidence >= 0.45f &&
                action.isNotBlank()
    }

    fun isAdjust(): Boolean {
        return action.lowercase() == "adjust" && gesture != null
    }

    fun isShoot(): Boolean {
        return action.lowercase() == "shoot" &&
                readyToShoot &&
                gesture != null
    }

    fun isStop(): Boolean {
        val a = action.lowercase()
        return a == "stop" || a == "fail" || a == "none"
    }
}
