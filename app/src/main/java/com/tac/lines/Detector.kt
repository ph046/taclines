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

    private const val SCALE = 0.50f
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
            .filter { it.r in 4f..30f }
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

        val aimLine = detectAimLine(
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

        val padX = (w * 0.015f).toInt()
        val padY = (h * 0.025f).toInt()

        return Box(
            minX = (minX + padX).coerceIn(0, sw - 1),
            minY = (minY + padY).coerceIn(0, sh - 1),
            maxX = (maxX - padX).coerceIn(0, sw - 1),
            maxY = (maxY - padY).coerceIn(0, sh - 1)
        )
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

        var localMinX = Int.MAX_VALUE
        var localMaxX = Int.MIN_VALUE
        var localMinY = Int.MAX_VALUE
        var localMaxY = Int.MIN_VALUE
        var localSumX = 0f
        var localSumY = 0f
        var localCount = 0

        for (idx in cluster) {
            val x = idx % sw
            val y = idx / sw

            val dx = x - bx
            val dy = y - by

            if (dx * dx + dy * dy <= searchR2) {
                if (x < localMinX) localMinX = x
                if (x > localMaxX) localMaxX = x
                if (y < localMinY) localMinY = y
                if (y > localMaxY) localMaxY = y

                localSumX += x
                localSumY += y
                localCount++
            }
        }

        if (localCount < 10) return null

        val lw = localMaxX - localMinX + 1
        val lh = localMaxY - localMinY + 1

        if (lw < 2 || lh < 2 || lw > 30 || lh > 30) return null

        val localRatio = lw.toFloat() / lh.toFloat().coerceAtLeast(1f)
        if (localRatio < 0.45f || localRatio > 2.40f) return null

        val cx = (localSumX / localCount) * inv
        val cy = (localSumY / localCount) * inv
        val rad = sqrt(localCount / Math.PI).toFloat() * inv

        return Ball(cx, cy, rad.coerceIn(5f, 28f))
    }

    private fun detectAimLine(
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

        val minDist = (cr + 4f).coerceAtLeast(6f)
        val maxDist = (230f * SCALE).coerceAtLeast(80f)

        val bins = 180
        val scores = IntArray(bins)
        val twoPi = (Math.PI * 2.0).toFloat()

        val startX = (cx - maxDist).roundToInt().coerceAtLeast(table.minX)
        val endX = (cx + maxDist).roundToInt().coerceAtMost(table.maxX)
        val startY = (cy - maxDist).roundToInt().coerceAtLeast(table.minY)
        val endY = (cy + maxDist).roundToInt().coerceAtMost(table.maxY)

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

                if (!isAimPixel(r, g, b)) continue

                var ang = atan2(dy.toDouble(), dx.toDouble()).toFloat()
                if (ang < 0f) ang += twoPi

                val bin = ((ang / twoPi) * bins).toInt().coerceIn(0, bins - 1)
                val distBonus = sqrt(d2.toDouble()).toInt() / 8

                scores[bin] += 1 + distBonus
            }
        }

        val bestBin = scores.indices.maxByOrNull { scores[it] } ?: return null
        if (scores[bestBin] < 6) return null

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

                val idx = py * sw + px
                if (idx !in pixels.indices) continue

                val p = pixels[idx]
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)

                if (isAimPixel(r, g, b)) {
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

        val left = table.minX * inv
        val top = table.minY * inv
        val right = table.maxX * inv
        val bottom = table.maxY * inv

        val endDistance = rayToBoxDistance(
            x = cue.x,
            y = cue.y,
            dx = dirX,
            dy = dirY,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )

        if (endDistance <= 0f) return null

        return RayLine(
            x1 = cue.x,
            y1 = cue.y,
            x2 = cue.x + dirX * endDistance,
            y2 = cue.y + dirY * endDistance
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
            lum > 125f &&
                    mn > 85 &&
                    sat < 0.42f

        val coloredAim =
            mx > 90 &&
                    lum > 45f &&
                    sat > 0.22f

        val normalTableGreen =
            g > r + 18 &&
                    g > b + 18 &&
                    g in 60..190 &&
                    r < 110 &&
                    b < 110 &&
                    lum < 150f

        return (whiteAim || coloredAim) && !normalTableGreen
    }
}
