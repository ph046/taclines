package com.tac.lines

data class ShotPoint(
    val x: Float,
    val y: Float
)

data class ShotBall(
    val x: Float,
    val y: Float,
    val r: Float,
    val color: String = ""
)

data class ShotPull(
    val fromX: Float,
    val fromY: Float,
    val toX: Float,
    val toY: Float,
    val durationMs: Long
)

data class ShotPlan(
    val ok: Boolean,
    val confidence: Float,

    val cueBall: ShotBall?,
    val targetBall: ShotBall?,
    val pocket: ShotPoint?,
    val ghostBall: ShotPoint?,
    val pull: ShotPull?,

    val power: Float,
    val message: String = ""
) {
    fun isUsable(): Boolean {
        return ok &&
                confidence >= 0.55f &&
                cueBall != null &&
                targetBall != null &&
                pocket != null &&
                ghostBall != null &&
                pull != null
    }

    fun toAutoAimShot(): AutoAimShot? {
        val cue = cueBall ?: return null
        val target = targetBall ?: return null
        val p = pocket ?: return null
        val ghost = ghostBall ?: return null
        val shotPull = pull ?: return null

        return AutoAimShot(
            cueX = cue.x,
            cueY = cue.y,

            targetBallX = target.x,
            targetBallY = target.y,

            pocketX = p.x,
            pocketY = p.y,

            ghostX = ghost.x,
            ghostY = ghost.y,

            aimX = ghost.x,
            aimY = ghost.y,

            pullFromX = shotPull.fromX,
            pullFromY = shotPull.fromY,
            pullToX = shotPull.toX,
            pullToY = shotPull.toY,

            powerDistance = distance(
                shotPull.fromX,
                shotPull.fromY,
                shotPull.toX,
                shotPull.toY
            ),

            durationMs = shotPull.durationMs.coerceIn(80L, 900L),
            confidence = confidence.coerceIn(0f, 1f)
        )
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
