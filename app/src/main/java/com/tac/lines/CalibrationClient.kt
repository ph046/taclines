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

    private data class UploadImage(
        val bitmap: Bitmap,
        val scaleBackX: Float,
        val scaleBackY: Float
    )

    fun calibrate(bitmap: Bitmap): AiCalibration {
        return try {
            val upload = makeUploadBitmap(bitmap, maxSide = 1280)
            val jpegBase64 = bitmapToBase64Jpeg(upload.bitmap)

            val payload = JSONObject().apply {
                put("image_base64", jpegBase64)
                put("game", "8ball_pool")
                put("task", "calibrate_pool_table")
                put("original_width", bitmap.width)
                put("original_height", bitmap.height)
                put("upload_width", upload.bitmap.width)
                put("upload_height", upload.bitmap.height)
            }

            val conn = URL(CALIBRATION_URL).openConnection() as HttpURLConnection

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

                val parsed = parseCalibration(body)

                // Aqui está a correção principal:
                // IA devolve coordenadas da imagem reduzida.
                // O app precisa desenhar na tela original.
                scaleCalibrationBack(
                    calibration = parsed,
                    scaleX = upload.scaleBackX,
                    scaleY = upload.scaleBackY
                )
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

    private fun scaleCalibrationBack(
        calibration: AiCalibration,
        scaleX: Float,
        scaleY: Float
    ): AiCalibration {
        val radiusScale = (scaleX + scaleY) / 2f

        val scaledTable = calibration.table?.let {
            AiTable(
                x = it.x * scaleX,
                y = it.y * scaleY,
                w = it.w * scaleX,
                h = it.h * scaleY
            )
        }

        val scaledCue = calibration.cueBall?.let {
            AiBall(
                x = it.x * scaleX,
                y = it.y * scaleY,
                r = it.r * radiusScale,
                color = it.color
            )
        }

        val scaledBalls = calibration.balls.map {
            AiBall(
                x = it.x * scaleX,
                y = it.y * scaleY,
                r = it.r * radiusScale,
                color = it.color
            )
        }

        val scaledPockets = calibration.pockets.map {
            AiPoint(
                x = it.x * scaleX,
                y = it.y * scaleY
            )
        }

        return calibration.copy(
            table = scaledTable,
            cueBall = scaledCue,
            balls = scaledBalls,
            pockets = scaledPockets
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
