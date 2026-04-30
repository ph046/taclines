package com.tac.lines

import kotlin.math.*

data class AimLine(
    val cueX: Float, val cueY: Float,
    val ghostX: Float, val ghostY: Float,
    val ballX: Float, val ballY: Float,
    val pocketX: Float, val pocketY: Float,
    val willScore: Boolean
)

object LineCalc {

    fun calculate(cue: Ball, balls: List<Ball>, pockets: List<Pocket>): List<AimLine> {
        val results = mutableListOf<Pair<Float, AimLine>>()

        for (target in balls) {
            for (pocket in pockets) {
                val line = eval(cue, target, pocket, balls) ?: continue
                val dist = hypot(cue.x - target.x, cue.y - target.y)
                val score = if (line.willScore) 1_000_000f - dist else -dist
                results.add(score to line)
            }
        }

        // Return top 3 scoring lines
        return results.sortedByDescending { it.first }.take(3).map { it.second }
    }

    private fun eval(cue: Ball, target: Ball, pocket: Pocket, all: List<Ball>): AimLine? {
        val tpx = pocket.x - target.x
        val tpy = pocket.y - target.y
        val tpd = hypot(tpx, tpy); if (tpd < 5f) return null
        val nx = tpx / tpd; val ny = tpy / tpd
        val gx = target.x - nx * (cue.r + target.r)
        val gy = target.y - ny * (cue.r + target.r)

        val cueBlocked = all.filter { it != target }.any { o ->
            segDist(o.x,o.y,cue.x,cue.y,gx,gy) < (cue.r+o.r)*0.82f
        }
        val targetBlocked = all.filter { it != target }.any { o ->
            segDist(o.x,o.y,target.x,target.y,pocket.x,pocket.y) < (target.r+o.r)*0.82f
        }

        return AimLine(cue.x,cue.y,gx,gy,target.x,target.y,pocket.x,pocket.y,
            !cueBlocked && !targetBlocked)
    }

    private fun segDist(px:Float,py:Float,x1:Float,y1:Float,x2:Float,y2:Float):Float {
        val dx=x2-x1;val dy=y2-y1;val l2=dx*dx+dy*dy
        if(l2<0.001f) return hypot(px-x1,py-y1)
        val t=((px-x1)*dx+(py-y1)*dy).div(l2).coerceIn(0f,1f)
        return hypot(px-x1-t*dx,py-y1-t*dy)
    }
}
