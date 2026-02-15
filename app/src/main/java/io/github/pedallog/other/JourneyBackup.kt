package io.github.pedallog.other

import android.util.Base64
import io.github.pedallog.db.Converters
import io.github.pedallog.db.Journey
import org.json.JSONArray
import org.json.JSONObject

object JourneyBackup {
    private const val KEY_VERSION = "version"
    private const val KEY_JOURNEYS = "journeys"
    private const val VERSION = 1

    private const val KEY_ID = "id"
    private const val KEY_DATE_CREATED = "dateCreated"
    private const val KEY_SPEED = "speed"
    private const val KEY_DISTANCE = "distance"
    private const val KEY_DURATION = "duration"
    private const val KEY_ROUTE_JSON = "routeJson"
    private const val KEY_IMG_BASE64 = "imgBase64"

    fun toJsonString(journeys: List<Journey>): String {
        val converters = Converters()
        val root = JSONObject()
        root.put(KEY_VERSION, VERSION)

        val arr = JSONArray()
        journeys.forEach { j ->
            val obj = JSONObject()
            j.id?.let { obj.put(KEY_ID, it) }
            obj.put(KEY_DATE_CREATED, j.dateCreated)
            obj.put(KEY_SPEED, j.speed)
            obj.put(KEY_DISTANCE, j.distance.toDouble())
            obj.put(KEY_DURATION, j.duration)
            if (!j.routeJson.isNullOrBlank()) obj.put(KEY_ROUTE_JSON, j.routeJson)

            val bytes = converters.fromBitmap(j.img)
            if (bytes != null && bytes.isNotEmpty()) {
                obj.put(KEY_IMG_BASE64, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
            arr.put(obj)
        }

        root.put(KEY_JOURNEYS, arr)
        return root.toString()
    }

    fun fromJsonString(json: String): List<Journey> {
        val converters = Converters()
        val root = JSONObject(json)
        val version = root.optInt(KEY_VERSION, VERSION)
        if (version != VERSION) {
            // Best-effort: attempt to parse anyway.
        }

        val arr = root.optJSONArray(KEY_JOURNEYS) ?: JSONArray()
        val result = ArrayList<Journey>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue

            val dateCreated = obj.optLong(KEY_DATE_CREATED, 0L)
            val speed = obj.optLong(KEY_SPEED, 0L)
            val distance = obj.optDouble(KEY_DISTANCE, 0.0).toFloat()
            val duration = obj.optLong(KEY_DURATION, 0L)
            val routeJson = obj.optString(KEY_ROUTE_JSON).takeIf { it.isNotBlank() }

            val imgBase64 = obj.optString(KEY_IMG_BASE64).takeIf { it.isNotBlank() }
            val bmp = if (!imgBase64.isNullOrBlank()) {
                try {
                    val bytes = Base64.decode(imgBase64, Base64.DEFAULT)
                    converters.toBitmap(bytes)
                } catch (_: Throwable) {
                    null
                }
            } else null

            val journey = Journey(
                dateCreated = dateCreated,
                speed = speed,
                distance = distance,
                duration = duration,
                img = bmp,
                routeJson = routeJson
            )
            if (obj.has(KEY_ID)) {
                val id = obj.optInt(KEY_ID, -1)
                if (id >= 0) journey.id = id
            }
            result.add(journey)
        }
        return result
    }
}
