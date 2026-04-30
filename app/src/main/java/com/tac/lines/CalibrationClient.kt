package com.tac.lines

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

object CalibrationClient {

    /*
     * Troca essa URL pela URL do teu backend.
     *
     * NÃO coloque chave da OpenAI aqui no APK.
     * O Android chama teu backend.
     * Teu backend chama a OpenAI.
     */
    private const val CALIBRATION_URL = "https://SEU-BACKEND.com/calibrate"

    fun calibrate(bitmap: Bitmap): AiCalibration {
        val jpegBase64 = bitmapToBase64Jpeg(bitmap)

        val payload = JSONObject().apply {
            put("image_base64", jpegBase64)
            put("game", "8ball_pool")
            put("task", "calibrate_pool_table")
        }

        val conn = URL(CALIBRATION_URL).openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 30000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

        conn.outputStream.use { os ->
            os.write(payload.toString().toByteArray(Charsets.UTF_8))
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
                message = "Erro backend HTTP $code"
            )
        }

        return parseCalibration(body)
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
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
                r = cueObj.optDouble("r", 0.0).toFloat(),
                color = "white"
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

            out.add(
                AiBall(
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat(),
                    r = o.optDouble("r", 0.0).toFloat(),
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

            out.add(
                AiPoint(
                    x = o.optDouble("x", 0.0).toFloat(),
                    y = o.optDouble("y", 0.0).toFloat()
                )
            )
        }

        return out
    }
}
