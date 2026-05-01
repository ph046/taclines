package com.tac.lines

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object FineTuneClient {

    private const val FINE_TUNE_URL = "https://taclines-backend.onrender.com/fine-tune-shot"

    private data class UploadImage(
        val bitmap: Bitmap,
        val scaleBackX: Float,
        val scaleBackY: Float
    )

    fun fineTune(
        bitmap: Bitmap,
        stepIndex: Int,
        maxSteps: Int = 4
    ): FineTuneResult {
        return try {
            val upload = makeUploadBitmap(bitmap, maxSide = 1280)
            val jpegBase64 = bitmapToBase64Jpeg(upload.bitmap)

            val payload = JSONObject().apply {
                put("image_base64", jpegBase64)
                put("game", "8ball_pool")
                put("task", "fine_tune_shot")
                put("step_index", stepIndex)
                put("max_steps", maxSteps)
                put("original_width", bitmap.width)
                put("original_height", bitmap.height)
                put("upload_width", upload.bitmap.width)
                put("upload_height", upload.bitmap.height)
            }

            val conn = URL(FINE_TUNE_URL).openConnection() as HttpURLConnection

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
                    return FineTuneResult(
                        ok = false,
                        action = "fail",
                        confidence = 0f,
                        direction = "none",
                        pixels = 0f,
                        gesture = null,
                        power = 0f,
                        readyToShoot = false,
                        message = extractErrorMessage(body).ifBlank { "Erro backend HTTP $code" }
                    )
                }

                val parsed = parseFineTune(body)

                scaleFineTuneBack(
                    result = parsed,
                    scaleX = upload.scaleBackX,
                    scaleY = upload.scaleBackY
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            FineTuneResult(
                ok = false,
                action = "fail",
                confidence = 0f,
                direction = "none",
                pixels = 0f,
                gesture = null,
                power = 0f,
                readyToShoot = false,
                message = "IA demorou demais. Tente novamente."
            )
        } catch (e: Exception) {
            FineTuneResult(
                ok = false,
                action = "fail",
                confidence = 0f,
                direction = "none",
                pixels = 0f,
                gesture = null,
                power = 0f,
                readyToShoot = false,
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

    private fun parseFineTune(json: String): FineTuneResult {
        val root = JSONObject(json)

        val ok = root.optBoolean("ok", false)
        val action = root.optString("action", "fail")
        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val direction = root.optString("direction", "none")
        val pixels = root.optDouble("pixels", 0.0).toFloat()
        val power = root.optDouble("power", 0.0).toFloat()
        val readyToShoot = root.optBoolean("readyToShoot", false)
        val message = root.optString("message", "")

        val gesture = parseGesture(root.optJSONObject("gesture"))

        return FineTuneResult(
            ok = ok,
            action = action,
            confidence = confidence,
            direction = direction,
            pixels = pixels,
            gesture = gesture,
            power = power,
            readyToShoot = readyToShoot,
            message = message
        )
    }

    private fun parseGesture(obj: JSONObject?): FineTuneGesture? {
        if (obj == null) return null

        val fromX = obj.optDouble("fromX", -1.0).toFloat()
        val fromY = obj.optDouble("fromY", -1.0).toFloat()
        val toX = obj.optDouble("toX", -1.0).toFloat()
        val toY = obj.optDouble("toY", -1.0).toFloat()
        val durationMs = obj.optLong("durationMs", 180L)

        if (fromX < 0f || fromY < 0f || toX < 0f || toY < 0f) return null

        return FineTuneGesture(
            fromX = fromX,
            fromY = fromY,
            toX = toX,
            toY = toY,
            durationMs = durationMs.coerceIn(60L, 900L)
        )
    }

    private fun scaleFineTuneBack(
        result: FineTuneResult,
        scaleX: Float,
        scaleY: Float
    ): FineTuneResult {
        val g = result.gesture ?: return result

        val scaledGesture = FineTuneGesture(
            fromX = g.fromX * scaleX,
            fromY = g.fromY * scaleY,
            toX = g.toX * scaleX,
            toY = g.toY * scaleY,
            durationMs = g.durationMs
        )

        val avgScale = (scaleX + scaleY) / 2f

        return result.copy(
            gesture = scaledGesture,
            pixels = result.pixels * avgScale
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
