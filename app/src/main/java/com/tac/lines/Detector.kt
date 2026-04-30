package com.tac.lines

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class Ball(val x: Float, val y: Float, val r: Float)
data class Pocket(val x: Float, val y: Float)

data class RayLine(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float
)

data class DetectionResult(
    val cue: Ball?,
    val balls: List<Ball>,
    val pockets: List<Pocket>,
    val aimLine: RayLine?
)

object Detector {

    private const val SCALE = 0.50f
    private const val MAX_BALLS = 15

    private data class Box(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    )

    private data class AimCandidate(
        val x: Int,
        val y: Int,
        val weight: Int
    )

    private data class FittedLine(
        val nx: Float,
        val ny: Float,
        val dx: Float,
        val dy: Float,
        val rho: Float,
        val minT: Float,
        val maxT: Float,
        val score: Int
    )

    private data class SegmentSmall(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    )

    fun analyze(bmp: Bitmap): Triple<Ball?, List<Ball>, List<Pocket>> {
        val result = analyzeFull(bmp)
        return Triple(result.cue, result.balls, result.pockets)
    }

    fun analyzeFull(bmp: Bitmap): DetectionResult {
        val sw = (bmp.width * SCALE).toInt().coerceAtLeast(1)
        val sh = (bmp.height * SCALE).toInt().coerceAtLeast(1)

        val small = Bitmap.createScaledBitmap(bmp, sw, sh, false)
        val inv = 1f / SCALE

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        val table = detectTableBox(pixels, sw, sh)

        val visited = BooleanArray(sw * sh)
        val whites = mutableListOf<Ball>()
        val colors = mutableListOf<Ball>()

        for (y in table.minY..table.maxY) {
            for (x in table.minX..table.maxX) {
                val idx = y * sw + x
                if (idx !in pixels.indices || visited[idx]) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                val isW = isWhite(r, g, b)
                val isC = !isW && isColored(r, g, b)

                if (!isW && !isC) continue

                val cluster = mutableListOf<Int>()
                val q = ArrayDeque<Int>()
                q.add(idx)

                while (!q.isEmpty() && cluster.size < 1200) {
                    val cur = q.removeFirst()
                    if (cur !in pixels.indices || visited[cur]) continue

                    val cx = cur % sw
                    val cy = cur / sw

                    if (cx < table.minX || cx > table.maxX || cy < table.minY || cy > table.maxY) {
                        continue
                    }

                    val cp = pixels[cur]
                    val cr = Color.red(cp)
                    val cg = Color.green(cp)
                    val cb = Color.blue(cp)

                    val ok = if (isW) {
                        isWhite(cr, cg, cb)
                    } else {
                        isColored(cr, cg, cb)
                    }

                    if (!ok) continue

                    visited[cur] = true
                    cluster.add(cur)

                    if (cx + 1 < sw) q.add(cur + 1)
                    if (cx - 1 >= 0) q.add(cur - 1)
                    if (cy + 1 < sh) q.add(cur + sw)
                    if (cy - 1 >= 0) q.add(cur - sw)
                }

                if (cluster.size < 4) continue

                if (isW) {
                    val ball = makeWhiteBall(cluster, sw, inv)
                    if (ball != null && insideOriginalTable(ball, table, inv)) {
                        whites.add(ball)
                    }
                } else {
                    val ball = makeColoredBall(cluster, sw, inv)
                    if (ball != null && insideOriginalTable(ball, table, inv)) {
                        colors.add(ball)
                    }
                }
            }
        }

        val cue = whites
            .filter { it.r in 4f..32f }
            .maxByOrNull { it.r }

        val deduped = mutableListOf<Ball>()

        for (b in colors.sortedByDescending { it.r }) {
            if (cue != null && hypot(b.x - cue.x, b.y - cue.y) < max(b.r, cue.r) * 1.3f) {
                continue
            }

            val duplicated = deduped.any {
                hypot(b.x - it.x, b.y - it.y) < max(b.r, it.r) * 1.25f
            }

            if (!duplicated) {
                deduped.add(b)
            }
        }

        val sx1 = table.minX * inv
        val sx2 = table.maxX * inv
        val sy1 = table.minY * inv
        val sy2 = table.maxY * inv
        val mx = (sx1 + sx2) / 2f

        val pockets = listOf(
            Pocket(sx1 + 22f, sy1 + 22f),
            Pocket(mx, sy1 + 10f),
            Pocket(sx2 - 22f, sy1 + 22f),
            Pocket(sx1 + 22f, sy2 - 22f),
            Pocket(mx, sy2 - 10f),
            Pocket(sx2 - 22f, sy2 - 22f)
        )

        val aimLine = detectAimLineByTableLine(
            pixels = pixels,
            sw = sw,
            sh = sh,
            table = table,
            cue = cue,
            inv = inv
        )

        return DetectionResult(
            cue = cue,
            balls = deduped.take(MAX_BALLS),
            pockets = pockets,
            aimLine = aimLine
        )
    }

    private fun detectTableBox(pixels: IntArray, sw: Int, sh: Int): Box {
        var minX = sw
        var minY = sh
        var maxX = 0
        var maxY = 0
        var count = 0

        val scanMinX = (sw * 0.24f).toInt()
        val scanMaxX = (sw * 0.82f).toInt()
        val scanMinY = (sh * 0.18f).toInt()
        val scanMaxY = (sh * 0.90f).toInt()

        for (y in scanMinY..scanMaxY) {
            for (x in scanMinX..scanMaxX) {
                val idx = y * sw + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (isGreen(r, g, b)) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    count++
                }
            }
        }

        if (count < 500 || minX >= maxX || minY >= maxY) {
            return Box(
                minX = (sw * 0.28f).toInt(),
                minY = (sh * 0.27f).toInt(),
                maxX = (sw * 0.76f).toInt(),
                maxY = (sh * 0.81f).toInt()
            )
        }

        val w = maxX - minX
        val h = maxY - minY

        val padX = (w * 0.020f).toInt()
        val padY = (h * 0.030f).toInt()

        return Box(
            minX = (minX + padX).coerceIn(0, sw - 1),
            minY = (minY + padY).coerceIn(0, sh - 1),
            maxX = (maxX - padX).coerceIn(0, sw - 1),
            maxY = (maxY - padY).coerceIn(0, sh - 1)
        )
    }

    private fun detectAimLineByTableLine(
        pixels: IntArray,
        sw: Int,
        sh: Int,
        table: Box,
        cue: Ball?,
        inv: Float
    ): RayLine? {
        val candidates = collectAimCandidates(pixels, sw, table)

        if (candidates.size < 8) return null

        val fitted = fitBestLine(candidates, sw, sh) ?: return null

        val fullSegment = lineToBoxSegmentSmall(
            px = fitted.nx * fitted.rho,
            py = fitted.ny * fitted.rho,
            dx = fitted.dx,
            dy = fitted.dy,
            table = table
        ) ?: return null

        val cueSmallX = cue?.x?.times(SCALE)
        val cueSmallY = cue?.y?.times(SCALE)

        if (cue != null && cueSmallX != null && cueSmallY != null) {
            val distToLine = abs(cueSmallX * fitted.nx + cueSmallY * fitted.ny - fitted.rho)

            if (distToLine < 18f) {
                val p0x = fitted.nx * fitted.rho
                val p0y = fitted.ny * fitted.rho

                val tCue = (cueSmallX - p0x) * fitted.dx + (cueSmallY - p0y) * fitted.dy
                val tAimCenter = (fitted.minT + fitted.maxT) / 2f

                val sign = if (tAimCenter >= tCue) 1f else -1f

                val left = table.minX * inv
                val top = table.minY * inv
                val right = table.maxX * inv
                val bottom = table.maxY * inv

                val endDistance = rayToBoxDistance(
                    x = cue.x,
                    y = cue.y,
                    dx = fitted.dx * sign,
                    dy = fitted.dy * sign,
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom
                )

                if (endDistance > 20f) {
                    return RayLine(
                        x1 = cue.x,
                        y1 = cue.y,
                        x2 = cue.x + fitted.dx * sign * endDistance,
                        y2 = cue.y + fitted.dy * sign * endDistance
                    )
                }
            }
        }

        return RayLine(
            x1 = fullSegment.x1 * inv,
            y1 = fullSegment.y1 * inv,
            x2 = fullSegment.x2 * inv,
            y2 = fullSegment.y2 * inv
        )
    }

    private fun collectAimCandidates(
        pixels: IntArray,
        sw: Int,
        table: Box
    ): List<AimCandidate> {
        val out = ArrayList<AimCandidate>()

        val minX = table.minX + 4
        val maxX = table.maxX - 4
        val minY = table.minY + 4
        val maxY = table.maxY - 4

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val idx = y * sw + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (!isAimPixel(r, g, b)) continue

                val mx = maxOf(r, g, b)
                val mn = minOf(r, g, b)
                val diff = mx - mn
                val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
                val sat = if (mx == 0) 0f else diff / mx.toFloat()

                val weight = when {
                    lum > 155f && mn > 100 && sat < 0.45f -> 4
                    mx > 120 && sat > 0.25f -> 3
                    else -> 2
                }

                out.add(AimCandidate(x, y, weight))
            }
        }

        if (out.size <= 7000) return out

        val sampled = ArrayList<AimCandidate>()
        val step = (out.size / 7000).coerceAtLeast(2)

        for (i in out.indices step step) {
            sampled.add(out[i])
        }

        return sampled
    }

    private fun fitBestLine(
        candidates: List<AimCandidate>,
        sw: Int,
        sh: Int
    ): FittedLine? {
        val angleStepDeg = 2
        val angleBins = 180 / angleStepDeg

        val rhoStep = 3f
        val rhoMax = ceil(hypot(sw.toFloat(), sh.toFloat())).toInt()
        val rhoBins = ((rhoMax * 2) / rhoStep).toInt() + 3

        val cosTable = FloatArray(angleBins)
        val sinTable = FloatArray(angleBins)

        for (i in 0 until angleBins) {
            val angle = Math.toRadians((i * angleStepDeg).toDouble())
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }

        val acc = IntArray(angleBins * rhoBins)

        for (c in candidates) {
            for (a in 0 until angleBins) {
                val rho = c.x * cosTable[a] + c.y * sinTable[a]
                val ri = ((rho + rhoMax) / rhoStep).roundToInt()

                if (ri in 0 until rhoBins) {
                    acc[a * rhoBins + ri] += c.weight
                }
            }
        }

        val top = ArrayList<Int>()

        for (i in acc.indices) {
            if (acc[i] >= 12) {
                top.add(i)
            }
        }

        top.sortByDescending { acc[it] }

        var best: FittedLine? = null

        for (k in 0 until minOf(35, top.size)) {
            val idx = top[k]
            val a = idx / rhoBins
            val ri = idx % rhoBins

            val nx = cosTable[a]
            val ny = sinTable[a]
            val dx = -ny
            val dy = nx
            val rho = ri * rhoStep - rhoMax

            val fitted = evaluateLine(
                candidates = candidates,
                nx = nx,
                ny = ny,
                dx = dx,
                dy = dy,
                rho = rho,
                rhoMax = rhoMax
            ) ?: continue

            if (best == null || fitted.score > best!!.score) {
                best = fitted
            }
        }

        return best
    }

    private fun evaluateLine(
        candidates: List<AimCandidate>,
        nx: Float,
        ny: Float,
        dx: Float,
        dy: Float,
        rho: Float,
        rhoMax: Int
    ): FittedLine? {
        val distanceTol = 2.8f
        val tStep = 2f
        val tBins = ((rhoMax * 2) / tStep).toInt() + 6
        val occupied = BooleanArray(tBins)

        var count = 0
        var minT = Float.MAX_VALUE
        var maxT = -Float.MAX_VALUE

        val p0x = nx * rho
        val p0y = ny * rho

        for (c in candidates) {
            val dist = abs(c.x * nx + c.y * ny - rho)

            if (dist <= distanceTol) {
                val t = (c.x - p0x) * dx + (c.y - p0y) * dy

                if (t < minT) minT = t
                if (t > maxT) maxT = t

                val bi = ((t + rhoMax) / tStep).roundToInt()

                if (bi in 0 until tBins) {
                    occupied[bi] = true
                }

                count += c.weight
            }
        }

        if (minT == Float.MAX_VALUE || maxT == -Float.MAX_VALUE) return null

        val totalLen = maxT - minT

        var longest = 0
        var current = 0
        var occupiedCount = 0

        for (v in occupied) {
            if (v) {
                occupiedCount++
                current++
                if (current > longest) longest = current
            } else {
                current = 0
            }
        }

        val longestLen = longest * tStep

        if (count < 14) return null
        if (totalLen < 24f) return null
        if (longestLen < 10f && occupiedCount < 12) return null

        val score = count * 3 + occupiedCount * 6 + longest * 10 + totalLen.toInt()

        return FittedLine(
            nx = nx,
            ny = ny,
            dx = dx,
            dy = dy,
            rho = rho,
            minT = minT,
            maxT = maxT,
            score = score
        )
    }

    private fun lineToBoxSegmentSmall(
        px: Float,
        py: Float,
        dx: Float,
        dy: Float,
        table: Box
    ): SegmentSmall? {
        val ts = ArrayList<Float>()

        val left = table.minX.toFloat()
        val right = table.maxX.toFloat()
        val top = table.minY.toFloat()
        val bottom = table.maxY.toFloat()

        fun addIfValid(t: Float) {
            val x = px + dx * t
            val y = py + dy * t

            if (x >= left && x <= right && y >= top && y <= bottom) {
                ts.add(t)
            }
        }

        if (abs(dx) > 0.0001f) {
            addIfValid((left - px) / dx)
            addIfValid((right - px) / dx)
        }

        if (abs(dy) > 0.0001f) {
            addIfValid((top - py) / dy)
            addIfValid((bottom - py) / dy)
        }

        if (ts.size < 2) return null

        ts.sort()

        val t1 = ts.first()
        val t2 = ts.last()

        return SegmentSmall(
            x1 = px + dx * t1,
            y1 = py + dy * t1,
            x2 = px + dx * t2,
            y2 = py + dy * t2
        )
    }

    private fun rayToBoxDistance(
        x: Float,
        y: Float,
        dx: Float,
        dy: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): Float {
        var best = Float.MAX_VALUE

        fun check(t: Float) {
            if (t <= 0f) return

            val hx = x + dx * t
            val hy = y + dy * t

            if (hx >= left && hx <= right && hy >= top && hy <= bottom) {
                if (t < best) best = t
            }
        }

        if (abs(dx) > 0.0001f) {
            check((left - x) / dx)
            check((right - x) / dx)
        }

        if (abs(dy) > 0.0001f) {
            check((top - y) / dy)
            check((bottom - y) / dy)
        }

        return if (best == Float.MAX_VALUE) -1f else best
    }

    private fun makeColoredBall(cluster: List<Int>, sw: Int, inv: Float): Ball? {
        if (cluster.size < 5) return null

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0f
        var sumY = 0f

        for (idx in cluster) {
            val x = idx % sw
            val y = idx / sw

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            sumX += x
            sumY += y
        }

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1

        if (bw < 2 || bh < 2 || bw > 40 || bh > 40) return null

        val ratio = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
        if (ratio < 0.45f || ratio > 2.2f) return null

        val fill = cluster.size.toFloat() / (bw * bh).toFloat().coerceAtLeast(1f)
        if (fill < 0.16f) return null

        val cx = (sumX / cluster.size) * inv
        val cy = (sumY / cluster.size) * inv
        val rad = ((bw + bh) / 4f * inv).coerceIn(4f, 26f)

        return Ball(cx, cy, rad)
    }

    private fun makeWhiteBall(cluster: List<Int>, sw: Int, inv: Float): Ball? {
        if (cluster.size < 5) return null

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        var sumX = 0f
        var sumY = 0f

        for (idx in cluster) {
            val x = idx % sw
            val y = idx / sw

            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y

            sumX += x
            sumY += y
        }

        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val ratio = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
        val fill = cluster.size.toFloat() / (bw * bh).toFloat().coerceAtLeast(1f)

        if (bw in 2..35 && bh in 2..35 && ratio in 0.50f..1.90f && fill > 0.20f) {
            val cx = (sumX / cluster.size) * inv
            val cy = (sumY / cluster.size) * inv
            val rad = ((bw + bh) / 4f * inv).coerceIn(5f, 28f)
            return Ball(cx, cy, rad)
        }

        val searchR = 8
        val searchR2 = searchR * searchR

        var bestIdx = -1
        var bestCount = 0

        for (i in cluster.indices) {
            val a = cluster[i]
            val ax = a % sw
            val ay = a / sw

            var count = 0

            for (j in cluster.indices) {
                val c = cluster[j]
                val cx = c % sw
                val cy = c / sw

                val dx = cx - ax
                val dy = cy - ay

                if (dx * dx + dy * dy <= searchR2) {
                    count++
                }
            }

            if (count > bestCount) {
                bestCount = count
                bestIdx = a
            }
        }

        if (bestIdx < 0 || bestCount < 10) return null

        val bx = bestIdx % sw
        val by = bestIdx / sw

        var localSumX = 0f
        var localSumY = 0f
        var localCount = 0

        for (idx in cluster) {
            val x = idx % sw
            val y = idx / sw

            val dx = x - bx
            val dy = y - by

            if (dx * dx + dy * dy <= searchR2) {
                localSumX += x
                localSumY += y
                localCount++
            }
        }

        if (localCount < 10) return null

        val cx = (localSumX / localCount) * inv
        val cy = (localSumY / localCount) * inv
        val rad = sqrt(localCount / Math.PI).toFloat() * inv

        return Ball(cx, cy, rad.coerceIn(5f, 28f))
    }

    private fun insideOriginalTable(ball: Ball, table: Box, inv: Float): Boolean {
        val left = table.minX * inv
        val top = table.minY * inv
        val right = table.maxX * inv
        val bottom = table.maxY * inv

        return ball.x in left..right && ball.y in top..bottom
    }

    private fun isGreen(r: Int, g: Int, b: Int): Boolean {
        return g > 45 &&
                g > r + 8 &&
                g > b + 8
    }

    private fun isWhite(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        return lum > 130f &&
                mn > 85 &&
                sat < 0.34f &&
                diff < 85 &&
                !isGreen(r, g, b)
    }

    private fun isColored(r: Int, g: Int, b: Int): Boolean {
        if (isGreen(r, g, b) || isWhite(r, g, b)) return false

        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        return mx > 35 &&
                lum > 20f &&
                (
                        sat > 0.14f ||
                                mx < 95 && mn < 70
                        )
    }

    private fun isAimPixel(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        val whiteAim =
            lum > 120f &&
                    mn > 80 &&
                    sat < 0.45f

        val coloredAim =
            mx > 85 &&
                    lum > 40f &&
                    sat > 0.20f

        val normalTableGreen =
            g > r + 18 &&
                    g > b + 18 &&
                    g in 55..195 &&
                    r < 125 &&
                    b < 125 &&
                    lum < 160f

        return (whiteAim || coloredAim) && !normalTableGreen
    }
}
