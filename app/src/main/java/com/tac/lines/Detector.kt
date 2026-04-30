package com.tac.lines

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.atan2
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

    private const val SCALE = 0.55f
    private const val MAX_BALLS = 15

    private data class Box(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
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

        val table = detectMainTableBox(pixels, sw, sh)

        val detected = detectBalls(pixels, sw, table, inv)
        val cue = detected.first
        val balls = detected.second

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

        val aimLine = detectAimFromCue(
            pixels = pixels,
            sw = sw,
            sh = sh,
            table = table,
            cue = cue,
            inv = inv
        )

        return DetectionResult(
            cue = cue,
            balls = balls.take(MAX_BALLS),
            pockets = pockets,
            aimLine = aimLine
        )
    }

    private fun detectMainTableBox(pixels: IntArray, sw: Int, sh: Int): Box {
        val safeMinX = (sw * 0.24f).toInt()
        val safeMaxX = (sw * 0.82f).toInt()
        val safeMinY = (sh * 0.18f).toInt()
        val safeMaxY = (sh * 0.84f).toInt()

        val rowCounts = IntArray(sh)
        for (y in safeMinY..safeMaxY) {
            var c = 0
            for (x in safeMinX..safeMaxX) {
                val p = pixels[y * sw + x]
                if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) c++
            }
            rowCounts[y] = c
        }

        val rowThreshold = ((safeMaxX - safeMinX) * 0.32f).toInt().coerceAtLeast(30)
        val rowRun = bestRun(rowCounts, safeMinY, safeMaxY, rowThreshold)

        if (rowRun == null) {
            return fallbackTable(sw, sh)
        }

        val rawMinY = rowRun.first
        val rawMaxY = rowRun.second

        val colCounts = IntArray(sw)
        for (x in safeMinX..safeMaxX) {
            var c = 0
            for (y in rawMinY..rawMaxY) {
                val p = pixels[y * sw + x]
                if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) c++
            }
            colCounts[x] = c
        }

        val colThreshold = ((rawMaxY - rawMinY) * 0.30f).toInt().coerceAtLeast(20)
        val colRun = bestRun(colCounts, safeMinX, safeMaxX, colThreshold)

        if (colRun == null) {
            return fallbackTable(sw, sh)
        }

        var minX = colRun.first
        var maxX = colRun.second
        var minY = rawMinY
        var maxY = rawMaxY

        val w = maxX - minX
        val h = maxY - minY

        if (w < sw * 0.30f || h < sh * 0.20f || w < h) {
            return fallbackTable(sw, sh)
        }

        val padX = (w * 0.018f).toInt()
        val padY = (h * 0.035f).toInt()

        minX = (minX + padX).coerceIn(0, sw - 1)
        maxX = (maxX - padX).coerceIn(0, sw - 1)
        minY = (minY + padY).coerceIn(0, sh - 1)
        maxY = (maxY - padY).coerceIn(0, sh - 1)

        return Box(minX, minY, maxX, maxY)
    }

    private fun bestRun(values: IntArray, start: Int, end: Int, threshold: Int): Pair<Int, Int>? {
        var bestS = -1
        var bestE = -1
        var curS = -1

        for (i in start..end) {
            if (values[i] >= threshold) {
                if (curS < 0) curS = i
            } else if (curS >= 0) {
                val curE = i - 1
                if (curE - curS > bestE - bestS) {
                    bestS = curS
                    bestE = curE
                }
                curS = -1
            }
        }

        if (curS >= 0) {
            val curE = end
            if (curE - curS > bestE - bestS) {
                bestS = curS
                bestE = curE
            }
        }

        return if (bestS >= 0 && bestE > bestS) Pair(bestS, bestE) else null
    }

    private fun fallbackTable(sw: Int, sh: Int): Box {
        return Box(
            minX = (sw * 0.27f).toInt(),
            minY = (sh * 0.24f).toInt(),
            maxX = (sw * 0.77f).toInt(),
            maxY = (sh * 0.80f).toInt()
        )
    }

    private fun detectBalls(
        pixels: IntArray,
        sw: Int,
        table: Box,
        inv: Float
    ): Pair<Ball?, List<Ball>> {
        val visited = BooleanArray(pixels.size)
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

                val isW = isWhiteBallPixel(r, g, b)
                val isC = !isW && isColoredBallPixel(r, g, b)

                if (!isW && !isC) continue

                val cluster = mutableListOf<Int>()
                val q = ArrayDeque<Int>()
                q.add(idx)

                while (!q.isEmpty() && cluster.size < 1200) {
                    val cur = q.removeFirst()
                    if (cur !in pixels.indices || visited[cur]) continue

                    val cx = cur % sw
                    val cy = cur / sw

                    if (cx < table.minX || cx > table.maxX || cy < table.minY || cy > table.maxY) continue

                    val cp = pixels[cur]
                    val cr = Color.red(cp)
                    val cg = Color.green(cp)
                    val cb = Color.blue(cp)

                    val ok = if (isW) isWhiteBallPixel(cr, cg, cb) else isColoredBallPixel(cr, cg, cb)
                    if (!ok) continue

                    visited[cur] = true
                    cluster.add(cur)

                    if (cx + 1 < sw) q.add(cur + 1)
                    if (cx - 1 >= 0) q.add(cur - 1)
                    if (cy + 1 < pixels.size / sw) q.add(cur + sw)
                    if (cy - 1 >= 0) q.add(cur - sw)
                }

                if (cluster.size < 4) continue

                val ball = makeBall(cluster, sw, inv, isWhite = isW) ?: continue
                if (ball.x < table.minX * inv || ball.x > table.maxX * inv) continue
                if (ball.y < table.minY * inv || ball.y > table.maxY * inv) continue

                if (isW) whites.add(ball) else colors.add(ball)
            }
        }

        val cue = whites
            .filter { it.r in 4f..32f }
            .maxByOrNull { it.r }

        val deduped = mutableListOf<Ball>()
        for (b in colors.sortedByDescending { it.r }) {
            if (cue != null && hypot(b.x - cue.x, b.y - cue.y) < max(b.r, cue.r) * 1.3f) continue

            val duplicated = deduped.any {
                hypot(b.x - it.x, b.y - it.y) < max(b.r, it.r) * 1.25f
            }

            if (!duplicated) deduped.add(b)
        }

        return Pair(cue, deduped)
    }

    private fun makeBall(cluster: List<Int>, sw: Int, inv: Float, isWhite: Boolean): Ball? {
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
        if (bw < 2 || bh < 2 || bw > 42 || bh > 42) return null

        val ratio = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
        if (ratio < 0.42f || ratio > 2.35f) return null

        val fill = cluster.size.toFloat() / (bw * bh).toFloat().coerceAtLeast(1f)
        if (fill < 0.12f) return null

        val cx = (sumX / cluster.size) * inv
        val cy = (sumY / cluster.size) * inv

        val minR = if (isWhite) 5f else 4f
        val maxR = if (isWhite) 30f else 26f
        val rad = ((bw + bh) / 4f * inv).coerceIn(minR, maxR)

        return Ball(cx, cy, rad)
    }

    private fun detectAimFromCue(
        pixels: IntArray,
        sw: Int,
        sh: Int,
        table: Box,
        cue: Ball?,
        inv: Float
    ): RayLine? {
        if (cue == null) return null

        val cx = cue.x * SCALE
        val cy = cue.y * SCALE
        val cr = cue.r * SCALE

        if (cx < table.minX || cx > table.maxX || cy < table.minY || cy > table.maxY) return null

        val minDist = (cr + 5f).coerceAtLeast(7f)
        val maxDist = (240f * SCALE).coerceAtLeast(90f)

        val bins = 180
        val scores = IntArray(bins)
        val twoPi = (Math.PI * 2.0).toFloat()

        val startX = (cx - maxDist).roundToInt().coerceAtLeast(table.minX + 2)
        val endX = (cx + maxDist).roundToInt().coerceAtMost(table.maxX - 2)
        val startY = (cy - maxDist).roundToInt().coerceAtLeast(table.minY + 2)
        val endY = (cy + maxDist).roundToInt().coerceAtMost(table.maxY - 2)

        for (y in startY..endY) {
            for (x in startX..endX) {
                val dx = x - cx
                val dy = y - cy
                val d2 = dx * dx + dy * dy

                if (d2 < minDist * minDist || d2 > maxDist * maxDist) continue

                val idx = y * sw + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (!isAimLinePixel(r, g, b)) continue

                var ang = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                if (ang < 0f) ang += twoPi

                val bin = ((ang / twoPi) * bins).toInt().coerceIn(0, bins - 1)
                val distBonus = sqrt(d2.toDouble()).toInt() / 10

                scores[bin] += 1 + distBonus
            }
        }

        var bestBin = -1
        var bestScore = 0

        for (i in scores.indices) {
            val score = scores[i] + scores[(i + 1) % bins] / 2 + scores[(i + bins - 1) % bins] / 2
            if (score > bestScore) {
                bestScore = score
                bestBin = i
            }
        }

        if (bestBin < 0 || bestScore < 8) return null

        val angle = ((bestBin + 0.5f) / bins.toFloat()) * twoPi
        val dirX = cos(angle.toDouble()).toFloat()
        val dirY = sin(angle.toDouble()).toFloat()

        var hits = 0
        var farthest = 0f
        var t = minDist

        while (t <= maxDist) {
            var found = false

            for (off in -3..3) {
                val px = (cx + dirX * t - dirY * off).roundToInt()
                val py = (cy + dirY * t + dirX * off).roundToInt()

                if (px !in 0 until sw || py !in 0 until sh) continue
                if (px < table.minX || px > table.maxX || py < table.minY || py > table.maxY) continue

                val p = pixels[py * sw + px]
                if (isAimLinePixel(Color.red(p), Color.green(p), Color.blue(p))) {
                    found = true
                    break
                }
            }

            if (found) {
                hits++
                farthest = t
            }

            t += 1.5f
        }

        if (hits < 4 || farthest < minDist + 8f) return null

        val startXOriginal = cue.x + dirX * (cue.r + 4f)
        val startYOriginal = cue.y + dirY * (cue.r + 4f)

        val left = table.minX * inv
        val top = table.minY * inv
        val right = table.maxX * inv
        val bottom = table.maxY * inv

        val endDistance = rayToBoxDistance(
            x = startXOriginal,
            y = startYOriginal,
            dx = dirX,
            dy = dirY,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )

        if (endDistance <= 20f) return null

        val x2 = startXOriginal + dirX * endDistance
        val y2 = startYOriginal + dirY * endDistance

        if (x2 < left - 2f || x2 > right + 2f || y2 < top - 2f || y2 > bottom + 2f) {
            return null
        }

        return RayLine(
            x1 = startXOriginal,
            y1 = startYOriginal,
            x2 = x2,
            y2 = y2
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

    private fun isTableGreen(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b

        return g > 55 &&
                g > r + 12 &&
                g > b + 12 &&
                lum > 45f &&
                mx - mn > 18
    }

    private fun isWhiteBallPixel(r: Int, g: Int, b: Int): Boolean {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        return lum > 125f &&
                mn > 80 &&
                sat < 0.36f &&
                diff < 90 &&
                !isTableGreen(r, g, b)
    }

    private fun isColoredBallPixel(r: Int, g: Int, b: Int): Boolean {
        if (isTableGreen(r, g, b) || isWhiteBallPixel(r, g, b)) return false

        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        return mx > 35 &&
                lum > 18f &&
                (
                        sat > 0.14f ||
                                mx < 95 && mn < 72
                        )
    }

    private fun isAimLinePixel(r: Int, g: Int, b: Int): Boolean {
        if (isTableGreen(r, g, b)) return false

        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val diff = mx - mn

        val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
        val sat = if (mx == 0) 0f else diff / mx.toFloat()

        val whiteAim =
            lum > 120f &&
                    mn > 78 &&
                    sat < 0.46f

        val coloredAim =
            mx > 85 &&
                    lum > 38f &&
                    sat > 0.20f

        val tooDark = lum < 35f
        val railGrey = sat < 0.12f && lum in 35f..115f

        return (whiteAim || coloredAim) && !tooDark && !railGrey
    }
}
