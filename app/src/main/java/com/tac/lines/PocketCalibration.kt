package com.tac.lines

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CalibratedPocket(
    val id: String,
    val x: Float,
    val y: Float
)

object PocketCalibration {

    private const val PREFS = "pocket_calibration"
    private const val KEY_POCKETS = "pockets_json"

    const val TOP_LEFT = "top_left"
    const val TOP_MIDDLE = "top_middle"
    const val TOP_RIGHT = "top_right"
    const val BOTTOM_LEFT = "bottom_left"
    const val BOTTOM_MIDDLE = "bottom_middle"
    const val BOTTOM_RIGHT = "bottom_right"

    val REQUIRED_ORDER = listOf(
        TOP_LEFT,
        TOP_MIDDLE,
        TOP_RIGHT,
        BOTTOM_LEFT,
        BOTTOM_MIDDLE,
        BOTTOM_RIGHT
    )

    fun labelFor(id: String): String {
        return when (id) {
            TOP_LEFT -> "superior esquerdo"
            TOP_MIDDLE -> "superior meio"
            TOP_RIGHT -> "superior direito"
            BOTTOM_LEFT -> "inferior esquerdo"
            BOTTOM_MIDDLE -> "inferior meio"
            BOTTOM_RIGHT -> "inferior direito"
            else -> id
        }
    }

    fun save(context: Context, pockets: List<CalibratedPocket>) {
        val arr = JSONArray()

        pockets.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                }
            )
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_POCKETS, arr.toString())
            .apply()
    }

    fun load(context: Context): List<CalibratedPocket> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POCKETS, null)
            ?: return emptyList()

        return try {
            val arr = JSONArray(json)
            val out = mutableListOf<CalibratedPocket>()

            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue

                val id = o.optString("id", "")
                val x = o.optDouble("x", -1.0).toFloat()
                val y = o.optDouble("y", -1.0).toFloat()

                if (id.isNotBlank() && x >= 0f && y >= 0f) {
                    out.add(
                        CalibratedPocket(
                            id = id,
                            x = x,
                            y = y
                        )
                    )
                }
            }

            REQUIRED_ORDER.mapNotNull { id ->
                out.firstOrNull { it.id == id }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_POCKETS)
            .apply()
    }

    fun isComplete(context: Context): Boolean {
        val pockets = load(context)
        return pockets.size == 6 &&
                REQUIRED_ORDER.all { id -> pockets.any { it.id == id } }
    }

    fun toPocketList(context: Context): List<Pocket> {
        return load(context).map {
            Pocket(it.x, it.y)
        }
    }

    fun toJsonArray(context: Context): JSONArray {
        val arr = JSONArray()

        load(context).forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("x", p.x.toDouble())
                    put("y", p.y.toDouble())
                }
            )
        }

        return arr
    }

    fun toPayloadJson(context: Context): String {
        return toJsonArray(context).toString()
    }
}
