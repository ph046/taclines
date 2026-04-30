package com.tac.lines

import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

data class AutoAimShot(
    val cueX: Float,
    val cueY: Float,

    val targetBallX: Float,
    val targetBallY: Float,

    val pocketX: Float,
    val pocketY: Float,

    val ghostX: Float,
    val ghostY: Float,

    val aimX: Float,
    val aimY: Float,

    // Gesto: começa na bola branca e puxa para trás
    val pullFromX: Float,
    val pullFromY: Float,
    val pullToX: Float,
    val pullToY: Float,

    val powerDistance: Float,
    val durationMs: Long,
    val confidence: Float
)

object AutoAimEngine {

    fun findBestShot(
        cue: Ball?,
        balls: List<Ball>,
        pockets: List<Pocket>
    ): AutoAimShot? {
        if (cue == null) return null
        if (balls.isEmpty()) return null
        if (pockets.isEmpty()) return null

        val ballRadius = estimateBallRadius(cue, balls)
        val allBalls = listOf(cue) + balls

        var bestShot: AutoAimShot? = null
        var bestScore = -1f

        for (target in balls) {
            for (pocket in pockets) {
                val shot = evaluateShot(
                    cue = cue,
                    target = target,
                    pocket = pocket,
                    allBalls = allBalls,
                    ballRadius = ballRadius
                ) ?: continue

                if (shot.confidence > bestScore) {
                    bestScore = shot.confidence
                    bestShot = shot
                }
            }
        }

        return bestShot
    }

    private fun evaluateShot(
        cue: Ball,
        target: Ball,
        pocket: Pocket,
        allBalls: List<Ball>,
        ballRadius: Float
    ): AutoAimShot? {
        val targetToPocketX = pocket.x - target.x
        val targetToPocketY = pocket.y - target.y
        val targetToPocketLen = hypot(targetToPocketX, targetToPocketY)

        if (targetToPocketLen < ballRadius * 3f) return null

        val pocketDirX = targetToPocketX / targetToPocketLen
        val pocketDirY = targetToPocketY / targetToPocketLen

        // Ponto fantasma: onde o centro da bola branca precisa chegar
        val ghostX = target.x - pocketDirX * ballRadius * 2f
        val ghostY = target.y - pocketDirY * ballRadius * 2f

        val cueToGhostX = ghostX - cue.x
        val cueToGhostY = ghostY - cue.y
        val cueToGhostLen = hypot(cueToGhostX, cueToGhostY)

        if (cueToGhostLen < ballRadius * 3f) return null

        val cueDirX = cueToGhostX / cueToGhostLen
        val cueDirY = cueToGhostY / cueToGhostLen

        // Quanto mais alinhado, mais fácil a tacada.
        val cutDot = (cueDirX * pocketDirX + cueDirY * pocketDirY).coerceIn(-1f, 1f)
        if (cutDot < 0.18f) return null

        val cutAngle = Math.toDegrees(acos(cutDot).toDouble()).toFloat()
        if (cutAngle > 78f) return null

        // Caminho da branca até o ponto fantasma.
        val cuePathClear = isSegmentClear(
            x1 = cue.x,
            y1 = cue.y,
            x2 = ghostX,
            y2 = ghostY,
            balls = allBalls,
            ignoreA = cue,
            ignoreB = target,
            clearance = ballRadius * 1.70f
        )

        if (!cuePathClear) return null

        // Caminho da bola alvo até a caçapa.
        val targetPathClear = isSegmentClear(
            x1 = target.x,
            y1 = target.y,
            x2 = pocket.x,
            y2 = pocket.y,
            balls = allBalls,
            ignoreA = cue,
            ignoreB = target,
            clearance = ballRadius * 1.55f
        )

        if (!targetPathClear) return null

        val totalDistance = cueToGhostLen + targetToPocketLen

        val distanceScore = when {
            totalDistance < 350f -> 1.00f
            totalDistance < 520f -> 0.82f
            totalDistance < 720f -> 0.66f
            else -> 0.48f
        }

        val cutScore = when {
            cutAngle < 12f -> 1.00f
            cutAngle < 25f -> 0.90f
            cutAngle < 40f -> 0.75f
            cutAngle < 58f -> 0.55f
            else -> 0.35f
        }

        val pocketDistanceScore = when {
            targetToPocketLen < 220f -> 1.00f
            targetToPocketLen < 420f -> 0.82f
            targetToPocketLen < 620f -> 0.65f
            else -> 0.46f
        }

        val confidence = (
                distanceScore * 0.30f +
                        cutScore * 0.45f +
                        pocketDistanceScore * 0.25f
                ).coerceIn(0f, 1f)

        if (confidence < 0.38f) return null

        val powerDistance = estimatePowerDistance(
            cueToGhostLen = cueToGhostLen,
            targetToPocketLen = targetToPocketLen,
            cutAngle = cutAngle
        )

        // Para tacar, puxa no sentido contrário da direção da tacada.
        val pullFromX = cue.x
        val pullFromY = cue.y
        val pullToX = cue.x - cueDirX * powerDistance
        val pullToY = cue.y - cueDirY * powerDistance

        val durationMs = estimateDuration(powerDistance)

        return AutoAimShot(
            cueX = cue.x,
            cueY = cue.y,

            targetBallX = target.x,
            targetBallY = target.y,

            pocketX = pocket.x,
            pocketY = pocket.y,

            ghostX = ghostX,
            ghostY = ghostY,

            aimX = ghostX,
            aimY = ghostY,

            pullFromX = pullFromX,
            pullFromY = pullFromY,
            pullToX = pullToX,
            pullToY = pullToY,

            powerDistance = powerDistance,
            durationMs = durationMs,
            confidence = confidence
        )
    }

    private fun estimateBallRadius(cue: Ball, balls: List<Ball>): Float {
        val radii = (listOf(cue.r) + balls.map { it.r })
            .filter { it in 4f..36f }
            .sorted()

        if (radii.isEmpty()) return 14f

        return radii[radii.size / 2].coerceIn(8f, 24f)
    }

    private fun estimatePowerDistance(
        cueToGhostLen: Float,
        targetToPocketLen: Float,
        cutAngle: Float
    ): Float {
        val base = cueToGhostLen * 0.18f + targetToPocketLen * 0.12f
        val cutBonus = cutAngle * 1.15f

        return (base + cutBonus + 80f).coerceIn(95f, 340f)
    }

    private fun estimateDuration(powerDistance: Float): Long {
        return when {
            powerDistance < 130f -> 170L
            powerDistance < 190f -> 230L
            powerDistance < 260f -> 310L
            else -> 390L
        }
    }

    private fun isSegmentClear(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        balls: List<Ball>,
        ignoreA: Ball?,
        ignoreB: Ball?,
        clearance: Float
    ): Boolean {
        for (b in balls) {
            if (sameBall(b, ignoreA) || sameBall(b, ignoreB)) continue

            val hit = distancePointToSegment(
                px = b.x,
                py = b.y,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2
            )

            if (hit.distance < clearance && hit.t > 0.06f && hit.t < 0.96f) {
                return false
            }
        }

        return true
    }

    private data class SegmentHit(
        val distance: Float,
        val t: Float
    )

    private fun distancePointToSegment(
        px: Float,
        py: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float
    ): SegmentHit {
        val dx = x2 - x1
        val dy = y2 - y1
        val len2 = dx * dx + dy * dy

        if (len2 <= 0.0001f) {
            return SegmentHit(
                distance = hypot(px - x1, py - y1),
                t = 0f
            )
        }

        val rawT = ((px - x1) * dx + (py - y1) * dy) / len2
        val t = min(1f, max(0f, rawT))

        val projX = x1 + t * dx
        val projY = y1 + t * dy

        return SegmentHit(
            distance = hypot(px - projX, py - projY),
            t = t
        )
    }

    private fun sameBall(a: Ball?, b: Ball?): Boolean {
        if (a == null || b == null) return false

        return hypot(a.x - b.x, a.y - b.y) < max(a.r, b.r) * 0.50f
    }
}
