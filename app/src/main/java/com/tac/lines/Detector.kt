package com.tac.lines

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

data class Ball(val x: Float, val y: Float, val r: Float)
data class Pocket(val x: Float, val y: Float)

object Detector {

    private const val SCALE = 0.18f

    fun analyze(bmp: Bitmap): Triple<Ball?, List<Ball>, List<Pocket>> {
        val sw = (bmp.width * SCALE).toInt().coerceAtLeast(1)
        val sh = (bmp.height * SCALE).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bmp, sw, sh, false)
        val inv = 1f / SCALE
        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            val p = pixels[y * sw + x]
            if (isGreen(Color.red(p), Color.green(p), Color.blue(p))) {
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (minX >= maxX || minY >= maxY) {
            minX = (sw*0.05f).toInt(); maxX = (sw*0.95f).toInt()
            minY = (sh*0.08f).toInt(); maxY = (sh*0.92f).toInt()
        }

        val visited = BooleanArray(sw * sh)
        val whites = mutableListOf<Ball>()
        val colors = mutableListOf<Ball>()

        for (y in minY..maxY) for (x in minX..maxX) {
            val idx = y * sw + x
            if (visited[idx]) continue
            val p = pixels[idx]
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            val isW = isWhite(r, g, b)
            val isC = !isW && isColored(r, g, b)
            if (!isW && !isC) continue

            val cluster = mutableListOf<Int>()
            val q = ArrayDeque<Int>(); q.add(idx)
            while (q.isNotEmpty() && cluster.size < 700) {
                val cur = q.removeFirst()
                if (cur < 0 || cur >= pixels.size || visited[cur]) continue
                val cp = pixels[cur]
                val cr = Color.red(cp); val cg = Color.green(cp); val cb = Color.blue(cp)
                if (!(if (isW) isWhite(cr,cg,cb) else isColored(cr,cg,cb))) continue
                visited[cur] = true; cluster.add(cur)
                val cx = cur % sw; val cy = cur / sw
                if (cx+1<sw) q.add(cur+1); if (cx-1>=0) q.add(cur-1)
                if (cy+1<sh) q.add(cur+sw); if (cy-1>=0) q.add(cur-sw)
            }
            if (cluster.size < 4) continue

            val xs = cluster.map { it % sw }
            val ys = cluster.map { it / sw }
            val bx1=xs.min(); val bx2=xs.max()
            val by1=ys.min(); val by2=ys.max()
            val bw=bx2-bx1+1; val bh=by2-by1+1
            if (bw<2||bh<2||bw>80||bh>80) continue
            val ratio=bw.toFloat()/bh.toFloat().coerceAtLeast(1f)
            if (ratio<0.35f||ratio>2.8f) continue

            val cx=((bx1+bx2)/2f)*inv
            val cy=((by1+by2)/2f)*inv
            val rad=((bw+bh)/4f*inv).coerceIn(6f,36f)

            if (isW) whites.add(Ball(cx,cy,rad))
            else colors.add(Ball(cx,cy,rad))
        }

        val cue = whites.maxByOrNull { it.r }
        val deduped = mutableListOf<Ball>()
        for (b in colors.sortedByDescending { it.r }) {
            if (deduped.none { hypot(b.x-it.x,b.y-it.y) < max(b.r,it.r)*1.2f }) deduped.add(b)
        }

        val sx1=minX*inv; val sx2=maxX*inv
        val sy1=minY*inv; val sy2=maxY*inv; val mx=(sx1+sx2)/2f
        val pockets = listOf(
            Pocket(sx1+22f,sy1+22f), Pocket(mx,sy1+10f), Pocket(sx2-22f,sy1+22f),
            Pocket(sx1+22f,sy2-22f), Pocket(mx,sy2-10f), Pocket(sx2-22f,sy2-22f)
        )
        return Triple(cue, deduped.take(15), pockets)
    }

    private fun isGreen(r: Int, g: Int, b: Int) = g > 55 && g > r+12 && g > b+12
    private fun isWhite(r: Int, g: Int, b: Int): Boolean {
        val mx=maxOf(r,g,b); val mn=minOf(r,g,b)
        return mx>155 && mn>120 && abs(r-g)<50 && abs(g-b)<50 && abs(r-b)<55
    }
    private fun isColored(r: Int, g: Int, b: Int): Boolean {
        if (isGreen(r,g,b)||isWhite(r,g,b)) return false
        val mx=maxOf(r,g,b); val mn=minOf(r,g,b)
        val sat=if(mx>0)(mx-mn).toFloat()/mx else 0f
        return (sat>0.18f&&mx>50)||(mx<80&&mn<55)
    }
}
