package com.tac.lines

data class AiPoint(
    val x: Float,
    val y: Float
)

data class AiBall(
    val x: Float,
    val y: Float,
    val r: Float,
    val color: String = ""
)

data class AiTable(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float
)

data class AiCalibration(
    val ok: Boolean,
    val confidence: Float,
    val table: AiTable?,
    val cueBall: AiBall?,
    val balls: List<AiBall>,
    val pockets: List<AiPoint>,
    val message: String = ""
) {
    fun cueAsBall(): Ball? {
        val c = cueBall ?: return null
        return Ball(c.x, c.y, normalizeRadius(c.r))
    }

    fun ballsAsList(): List<Ball> {
        return balls
            .filter { it.r > 0f }
            .map { Ball(it.x, it.y, normalizeRadius(it.r)) }
    }

    fun pocketsAsList(): List<Pocket> {
        return pockets.map { Pocket(it.x, it.y) }
    }

    fun hasBasicData(): Boolean {
        return cueBall != null &&
                balls.isNotEmpty() &&
                pockets.size >= 4
    }

    fun isUsable(): Boolean {
        return ok &&
                confidence >= 0.45f &&
                hasBasicData()
    }

    fun statusText(): String {
        return when {
            isUsable() -> "IA=OK ${(confidence * 100f).toInt()}%"
            !ok -> "IA=OFF"
            !hasBasicData() -> "IA fraca: dados incompletos"
            confidence < 0.45f -> "IA fraca ${(confidence * 100f).toInt()}%"
            else -> "IA=OFF"
        }
    }

    private fun normalizeRadius(r: Float): Float {
        return r.coerceIn(6f, 28f)
    }
}
