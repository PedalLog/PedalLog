package io.github.pedallog.other

import org.json.JSONArray
import org.maplibre.android.geometry.LatLng

object RouteSerialization {
    fun toJson(polylines: List<List<LatLng>>): String {
        val outer = JSONArray()
        for (polyline in polylines) {
            val segment = JSONArray()
            for (point in polyline) {
                val pair = JSONArray()
                pair.put(point.latitude)
                pair.put(point.longitude)
                segment.put(pair)
            }
            outer.put(segment)
        }
        return outer.toString()
    }

    fun fromJson(json: String?): List<List<LatLng>> {
        if (json.isNullOrBlank()) return emptyList()
        val outer = JSONArray(json)
        val result = ArrayList<List<LatLng>>(outer.length())
        for (i in 0 until outer.length()) {
            val segmentJson = outer.optJSONArray(i) ?: continue
            val segment = ArrayList<LatLng>(segmentJson.length())
            for (j in 0 until segmentJson.length()) {
                val pair = segmentJson.optJSONArray(j) ?: continue
                val lat = pair.optDouble(0)
                val lon = pair.optDouble(1)
                segment.add(LatLng(lat, lon))
            }
            result.add(segment)
        }
        return result
    }
}
