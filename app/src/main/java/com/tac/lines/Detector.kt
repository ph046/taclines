package com.tac.lines

import android.graphics.Bitmap
import android.graphics.Color
import java.util.ArrayDeque
import kotlin.math.abs
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

    private const val SCALE = 0.70f
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
        val safeMinX = (sw * 0.16f).toInt()
        val safeMaxX = (sw * 0.84f).toInt()
        val safeMinY = (sh * 0.15f).toInt()
        val safeMaxY = (sh * 0.88f).toInt()

        val rowCounts = IntArray(sh)

        for (y in safeMinY..safeMaxY) {
            var c = 0

            for (x in safeMinX..safeMaxX) {
                val idx = y * sw + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]

                if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) {
                    c++
                }
            }

            rowCounts[y] = c
        }

        val rowThreshold = ((safeMaxX - safeMinX) * 0.26f).toInt().coerceAtLeast(30)
        val rowRun = bestRun(rowCounts, safeMinY, safeMaxY, rowThreshold) ?: return fallbackTable(sw, sh)

        val rawMinY = rowRun.first
        val rawMaxY = rowRun.second

        val colCounts = IntArray(sw)

        for (x in safeMinX..safeMaxX) {
            var c = 0

            for (y in rawMinY..rawMaxY) {
                val idx = y * sw + x
                if (idx !in pixels.indices) continue

                val p = pixels[idx]

                if (isTableGreen(Color.red(p), Color.green(p), Color.blue(p))) {
                    c++
                }
            }

            colCounts[x] = c
        }

        val colThreshold = ((rawMaxY - rawMinY) * 0.24f).toInt().coerceAtLeast(20)
        val colRun = bestRun(colCounts, safeMinX, safeMaxX, colThreshold) ?: return fallbackTable(sw, sh)

        var minX = colRun.first
        var maxX = colRun.second
        var minY = rawMinY
        var maxY = rawMaxY

        val w = maxX - minX
        val h = maxY - minY

        if (w < sw * 0.30f || h < sh * 0.20f || w < h) {
            return fallbackTable(sw, sh)
        }

        val padX = (w * 0.012f).toInt()
        val padY = (h * 0.025f).toInt()

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

        return if (bestS >= 0 && bestE > bestS) {
            Pair(bestS, bestE)
        } else {
            null
        }
    }

    private fun fallbackTable(sw: Int, sh: Int): Box {
        return Box(
            minX = (sw * 0.17f).toInt(),
            minY = (sh * 0.20f).toInt(),
            maxX = (sw * 0.82f).toInt(),
            maxY = (sh * 0.86f).toInt()
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

        val sh = pixels.size / sw

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

                while (!q.isEmpty() && cluster.size < 1600) {
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
                        isWhiteBallPixel(cr, cg, cb)
                    } else {
                        isColoredBallPixel(cr, cg, cb)
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

                val ball = makeBall(cluster, sw, inv, isWhite = isW) ?: continue

                if (ball.x < table.minX * inv || ball.x > table.maxX * inv) continue
                if (ball.y < table.minY * inv || ball.y > table.maxY * inv) continue

                if (isW) {
                    whites.add(ball)
                } else {
                    colors.add(ball)
                }
            }
        }

        val cue = whites
            .filter { it.r in 4f..34f }
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

        if (bw < 2 || bh < 2 || bw > 52 || bh > 52) return null

        val ratio = bw.toFloat() / bh.toFloat().coerceAtLeast(1f)
        if (ratio < 0.40f || ratio > 2.50f) return null

        val fill = cluster.size.toFloat() / (bw * bh).toFloat().coerceAtLeast(1f)
        if (fill < 0.10f) return null

        val cx = (sumX / cluster.size) * inv
        val cy = (sumY / cluster.size) * inv

        val minR = if (isWhite) 5f else 4f
        val maxR = if (isWhite) 32f else 28f
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

        if (cx < table.minX || cx > table.maxX || cy < table.minY || cy > table.maxY) {
            return null
        }

        val minDist = (cr + 6f).coerceAtLeast(9f)
        val maxDist = (320f * SCALE).coerceAtLeast(150f)

        var bestDeg = -1
        var bestScore = 0f
        var bestHits = 0
        var bestNearHits = 0
        var bestThickPenalty = 0

        for (deg in 0 until 360) {
            val angle = Math.toRadians(deg.toDouble())
            val dirX = cos(angle).toFloat()
            val dirY = sin(angle).toFloat()

            var t = minDist
            var hits = 0
            var nearHits = 0
            var firstBandHits = 0
            var farthest = 0f
            var score = 0f
            var thickPenalty = 0
            var consecutiveMiss = 0

            while (t <= maxDist) {
                var hitCount = 0
                var centerBonus = 0

                for (off in -6..6) {
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

                    if (isAimLinePixel(r, g, b)) {
                        hitCount++

                        if (off in -1..1) {
                            centerBonus += 2
                        } else if (off in -3..3) {
                            centerBonus += 1
                        }
                    }
                }

                val thinLineHit = hitCount in 1..6
                val tooThick = hitCount >= 9

                if (thinLineHit) {
                    hits++
                    farthest = t
                    consecutiveMiss = 0

                    if (t < minDist + 40f) {
                        firstBandHits++
                        score += 26f + centerBonus
                    } else if (t < minDist + 90f) {
                        nearHits++
                        score += 18f + centerBonus
                    } else {
                        score += 7f + centerBonus
                    }

                    score += t * 0.030f
                } else {
                    consecutiveMiss++

                    if (tooThick) {
                        thickPenalty++
                        score -= 10f
                    }

                    if (t < minDist + 55f && consecutiveMiss >= 9) {
                        break
                    }
                }

                t += 2f
            }

            if (firstBandHits < 1 && nearHits < 2) continue
            if (hits < 5) continue
            if (thickPenalty > hits / 2 + 2) continue

            score += farthest * 0.22f
            score += nearHits * 12f
            score += firstBandHits * 22f
            score -= thickPenalty * 18f

            if (score > bestScore) {
                bestScore = score
                bestDeg = deg
                bestHits = hits
                bestNearHits = nearHits + firstBandHits
                bestThickPenalty = thickPenalty
            }
        }

        if (bestDeg < 0 || bestScore < 75f || bestHits < 5 || bestNearHits < 2) {
            return null
        }

        if (bestThickPenalty > bestHits / 2 + 2) {
            return null
        }

        val angle = Math.toRadians(bestDeg.toDouble())
        val dirX = cos(angle).toFloat()
        val dirY = sin(angle).toFloat()

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

        return RayLine(
            x1 = startXOriginal,
            y1 = startYOriginal,
            x2 = startXOriginal + dirX * endDistance,
            y2 = startYOriginal + dirY * endDistance
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

        return g > 45 &&
                g > r + 8 &&
                g > b + 8 &&
                lum > 38f &&
                mx - mn > 14
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
                    sat < 0.48f &&
                    diff < 105

        val coloredAim =
            mx > 88 &&
                    lum > 42f &&
                    sat > 0.20f

        val woodCue =
            r > 85 &&
                    g > 40 &&
                    b < 75 &&
                    r > g + 14 &&
                    g > b + 8 &&
                    lum < 150f &&
                    sat > 0.25f

        val railGreyOrDark =
            lum < 36f ||
                    (sat < 0.13f && lum in 38f..125f)

        return (whiteAim || coloredAim) && !woodCue && !railGreyOrDark
    }
}
