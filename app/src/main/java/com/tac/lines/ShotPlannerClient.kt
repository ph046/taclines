package com.tac.lines

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object ShotPlannerClient {

    private const val PLAN_SHOT_URL = "https://taclines-backend.onrender.com/plan-shot"

    private data class UploadImage(
        val bitmap: Bitmap,
        val scaleBackX: Float,
        val scaleBackY: Float
    )

    fun planShot(bitmap: Bitmap): ShotPlan {
        return try {
            val upload = makeUploadBitmap(bitmap, maxSide = 1280)
            val jpegBase64 = bitmapToBase64Jpeg(upload.bitmap)

            val payload = JSONObject().apply {
                put("image_base64", jpegBase64)
                put("game", "8ball_pool")
                put("task", "plan_best_shot")
                put("original_width", bitmap.width)
                put("original_height", bitmap.height)
                put("upload_width", upload.bitmap.width)
                put("upload_height", upload.bitmap.height)
            }

            val conn = URL(PLAN_SHOT_URL).openConnection() as HttpURLConnection

            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 45000
                conn.readTimeout = 90000
                conn.doOutput = true
                conn.useCaches = false

                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("Accept", "application/json")

                conn.outputStream.use { os ->
                    os.write(payload.toString().toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                val code = conn.responseCode

                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }

                if (upload.bitmap !== bitmap && !upload.bitmap.isRecycled) {
                    upload.bitmap.recycle()
                }

                if (code !in 200..299) {
                    return ShotPlan(
                        ok = false,
                        confidence = 0f,
                        cueBall = null,
                        targetBall = null,
                        pocket = null,
                        ghostBall = null,
                        pull = null,
                        power = 0f,
                        message = extractErrorMessage(body).ifBlank { "Erro backend HTTP $code" }
                    )
                }

                val parsed = parseShotPlan(body)

                scaleShotPlanBack(
                    plan = parsed,
                    scaleX = upload.scaleBackX,
                    scaleY = upload.scaleBackY
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            ShotPlan(
                ok = false,
                confidence = 0f,
                cueBall = null,
                targetBall = null,
                pocket = null,
                ghostBall = null,
                pull = null,
                power = 0f,
                message = "IA demorou demais. Tente novamente."
            )
        } catch (e: Exception) {
            ShotPlan(
                ok = false,
                confidence = 0f,
                cueBall = null,
                targetBall = null,
                pocket = null,
                ghostBall = null,
                pull = null,
                power = 0f,
                message = e.message ?: "Erro ao conectar na IA"
            )
        }
    }

    private fun makeUploadBitmap(bitmap: Bitmap, maxSide: Int): UploadImage {
        val originalW = bitmap.width
        val originalH = bitmap.height
        val biggest = maxOf(originalW, originalH)

        if (biggest <= maxSide) {
            return UploadImage(
                bitmap = bitmap,
                scaleBackX = 1f,
                scaleBackY = 1f
            )
        }

        val scale = maxSide.toFloat() / biggest.toFloat()
        val uploadW = (originalW * scale).toInt().coerceAtLeast(1)
        val uploadH = (originalH * scale).toInt().coerceAtLeast(1)

        val resized = Bitmap.createScaledBitmap(bitmap, uploadW, uploadH, true)

        return UploadImage(
            bitmap = resized,
            scaleBackX = originalW.toFloat() / uploadW.toFloat(),
            scaleBackY = originalH.toFloat() / uploadH.toFloat()
        )
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 78, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun parseShotPlan(json: String): ShotPlan {
        val root = JSONObject(json)

        val ok = root.optBoolean("ok", false)
        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val message = root.optString("message", "")
        val power = root.optDouble("power", 0.0).toFloat()

        val cueBall = parseBall(root.optJSONObject("cueBall"))
        val targetBall = parseBall(root.optJSONObject("targetBall"))
        val pocket = parsePoint(root.optJSONObject("pocket"))
        val ghostBall = parsePoint(root.optJSONObject("ghostBall"))
        val pull = parsePull(root.optJSONObject("pull"))

        return ShotPlan(
            ok = ok,
            confidence = confidence,
            cueBall = cueBall,
            targetBall = targetBall,
            pocket = pocket,
            ghostBall = ghostBall,
            pull = pull,
            power = power,
            message = message
        )
    }

    private fun parseBall(obj: JSONObject?): ShotBall? {
        if (obj == null) return null

        val x = obj.optDouble("x", -1.0).toFloat()
        val y = obj.optDouble("y", -1.0).toFloat()
        val r = obj.optDouble("r", 12.0).toFloat()

        if (x < 0f || y < 0f || r <= 0f) return null

        return ShotBall(
            x = x,
            y = y,
            r = r,
            color = obj.optString("color", "")
        )
    }

    private fun parsePoint(obj: JSONObject?): ShotPoint? {
        if (obj == null) return null

        val x = obj.optDouble("x", -1.0).toFloat()
        val y = obj.optDouble("y", -1.0).toFloat()

        if (x < 0f || y < 0f) return null

        return ShotPoint(x, y)
    }

    private fun parsePull(obj: JSONObject?): ShotPull? {
        if (obj == null) return null

        val fromX = obj.optDouble("fromX", -1.0).toFloat()
        val fromY = obj.optDouble("fromY", -1.0).toFloat()
        val toX = obj.optDouble("toX", -1.0).toFloat()
        val toY = obj.optDouble("toY", -1.0).toFloat()
        val durationMs = obj.optLong("durationMs", 260L)

        if (fromX < 0f || fromY < 0f || toX < 0f || toY < 0f) return null

        return ShotPull(
            fromX = fromX,
            fromY = fromY,
            toX = toX,
            toY = toY,
            durationMs = durationMs.coerceIn(80L, 900L)
        )
    }

    private fun scaleShotPlanBack(
        plan: ShotPlan,
        scaleX: Float,
        scaleY: Float
    ): ShotPlan {
        val radiusScale = (scaleX + scaleY) / 2f

        val cue = plan.cueBall?.let {
            ShotBall(
                x = it.x * scaleX,
                y = it.y * scaleY,
                r = it.r * radiusScale,
                color = it.color
            )
        }

        val target = plan.targetBall?.let {
            ShotBall(
                x = it.x * scaleX,
                y = it.y * scaleY,
                r = it.r * radiusScale,
                color = it.color
            )
        }

        val pocket = plan.pocket?.let {
            ShotPoint(
                x = it.x * scaleX,
                y = it.y * scaleY
            )
        }

        val ghost = plan.ghostBall?.let {
            ShotPoint(
                x = it.x * scaleX,
                y = it.y * scaleY
            )
        }

        val pull = plan.pull?.let {
            ShotPull(
                fromX = it.fromX * scaleX,
                fromY = it.fromY * scaleY,
                toX = it.toX * scaleX,
                toY = it.toY * scaleY,
                durationMs = it.durationMs
            )
        }

        return plan.copy(
            cueBall = cue,
            targetBall = target,
            pocket = pocket,
            ghostBall = ghost,
            pull = pull
        )
    }

    private fun extractErrorMessage(body: String): String {
        if (body.isBlank()) return ""

        return try {
            val root = JSONObject(body)
            root.optString("message", "").ifBlank {
                root.optString("error", "")
            }
        } catch (_: Exception) {
            body.take(180)
        }
    }
}
