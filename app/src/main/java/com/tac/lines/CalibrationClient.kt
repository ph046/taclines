package com.tac.lines

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

object CalibrationClient {

    private const val CALIBRATION_URL = "https://taclines-backend.onrender.com/calibrate"

    fun calibrate(bitmap: Bitmap): AiCalibration {
        return try {
            val jpegBase64 = bitmapToBase64Jpeg(bitmap)

            val payload = JSONObject().apply {
                put("image_base64", jpegBase64)
                put("game", "8ball_pool")
                put("task", "calibrate_pool_table")
            }

            val conn = URL(CALIBRATION_URL).openConnection() as HttpURLConnection

            try {
                conn.requestMethod = "POST"

                // Render Free + IA pode demorar bastante na primeira chamada
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

                if (code !in 200..299) {
                    return AiCalibration(
                        ok = false,
                        confidence = 0f,
                        table = null,
                        cueBall = null,
                        balls = emptyList(),
                        pockets = emptyList(),
                        message = extractErrorMessage(body).ifBlank { "Erro backend HTTP $code" }
                    )
                }

                parseCalibration(body)
            } finally {
                conn.disconnect()
            }
        } catch (e: SocketTimeoutException) {
            AiCalibration(
                ok = false,
                confidence = 0f,
                table = null,
                cueBall = null,
                balls = emptyList(),
                pockets = emptyList(),
                message = "IA demorou demais. Tente novamente."
            )
        } catch (e: Exception) {
            AiCalibration(
                ok = false,
                confidence = 0f,
                table = null,
                cueBall = null,
                balls = emptyList(),
                pockets = emptyList(),
                message = e.message ?: "Erro ao conectar na IA"
            )
        }
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val uploadBitmap = resizeForUpload(bitmap, maxSide = 1280)

        val out = ByteArrayOutputStream()
        uploadBitmap.compress(Bitmap.CompressFormat.JPEG, 76, out)

        if (uploadBitmap !== bitmap && !uploadBitmap.isRecycled) {
            uploadBitmap.recycle()
        }

        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun resizeForUpload(bitmap: Bitmap, maxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val biggest = maxOf(w, h)

        if (biggest <= maxSide) return bitmap

        val scale = maxSide.toFloat() / biggest.toFloat()
        val newW = (w * scale).toInt().coerceAtLeast(1)
        val newH = (h * scale).toInt().coerceAtLeast(1)

        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
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

    private fun parseCalibration(json: String): AiCalibration {
        val root = JSONObject(json)

        val ok = root.optBoolean("ok", false)
        val confidence = root.optDouble("confidence", 0.0).toFloat()
        val message = root.optString("message", "")

        val tableObj = root.optJSONObject("table")
        val table = if (tableObj != null) {
            AiTable(
                x = tableObj.optDouble("x", 0.0).toFloat(),
                y = tableObj.optDouble("y", 0.0).toFloat(),
                w = tableObj.optDouble("w", 0.0).toFloat(),
                h = tableObj.optDouble("h", 0.0).toFloat()
            )
        } else {
            null
        }

        val cueObj = root.optJSONObject("cueBall")
        val cueBall = if (cueObj != null) {
            AiBall(
                x = cueObj.optDouble("x", 0.0).toFloat(),
                y = cueObj.optDouble("y", 0.0).toFloat(),
                r = cueObj.optDouble("r", 12.0).toFloat(),
                color = cueObj.optString("color", "white")
            )
        } else {
            null
        }

        val balls = parseBalls(root.optJSONArray("balls"))
        val pockets = parsePockets(root.optJSONArray("pockets"))

        return AiCalibration(
            ok = ok,
            confidence = confidence,
            table = table,
            cueBall = cueBall,
            balls = balls,
            pockets = pockets,
            message = message
        )
    }

    private fun parseBalls(arr: JSONArray?): List<AiBall> {
        if (arr == null) return emptyList()

        val out = mutableListOf<AiBall>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val x = o.optDouble("x", -1.0).toFloat()
            val y = o.optDouble("y", -1.0).toFloat()
            val r = o.optDouble("r", 12.0).toFloat()

            if (x < 0f || y < 0f || r <= 0f) continue

            out.add(
                AiBall(
                    x = x,
                    y = y,
                    r = r,
                    color = o.optString("color", "")
                )
            )
        }

        return out
    }

    private fun parsePockets(arr: JSONArray?): List<AiPoint> {
        if (arr == null) return emptyList()

        val out = mutableListOf<AiPoint>()

        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue

            val x = o.optDouble("x", -1.0).toFloat()
            val y = o.optDouble("y", -1.0).toFloat()

            if (x < 0f || y < 0f) continue

            out.add(
                AiPoint(
                    x = x,
                    y = y
                )
            )
        }

        return out
    }
}
