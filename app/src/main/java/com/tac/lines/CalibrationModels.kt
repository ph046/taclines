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
        return Ball(c.x, c.y, c.r)
    }

    fun ballsAsList(): List<Ball> {
        return balls.map { Ball(it.x, it.y, it.r) }
    }

    fun pocketsAsList(): List<Pocket> {
        return pockets.map { Pocket(it.x, it.y) }
    }

    fun isUsable(): Boolean {
        return ok &&
                confidence >= 0.70f &&
                cueBall != null &&
                balls.isNotEmpty() &&
                pockets.size >= 4
    }
}
