package io.github.pedallog.other

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.pedallog.services.Polyline
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import java.io.File
import java.util.concurrent.TimeUnit

object TrackingUtility {
    fun hasLocationPermissions(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun getFormattedStopwatchTime(ms: Long, includeMillis: Boolean = false): String {
        var milliseconds = ms
        val hours = TimeUnit.MILLISECONDS.toHours(milliseconds)
        milliseconds -= TimeUnit.HOURS.toMillis(hours)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
        milliseconds -= TimeUnit.MINUTES.toMillis(minutes)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds)
        if (!includeMillis) {
            return "${if (hours < 10) "0" else ""}$hours:" +
                    "${if (minutes < 10) "0" else ""}$minutes:" +
                    "${if (seconds < 10) "0" else ""}$seconds"
        } else {
            milliseconds -= TimeUnit.SECONDS.toMillis(seconds)
            milliseconds /= 10
            return "${if (hours < 10) "0" else ""}$hours:" +
                    "${if (minutes < 10) "0" else ""}$minutes:" +
                    "${if (seconds < 10) "0" else ""}$seconds:" +
                    "${if (milliseconds < 10) "0" else ""}$milliseconds"
        }
    }

    fun calculatePolylineLength(polyline: Polyline): Float {
        var distance = 0f
        for (i in 0..polyline.size - 2) {
            val pos1 = polyline[i]
            val pos2 = polyline[i + 1]

            val result = FloatArray(1)
            Location.distanceBetween(
                pos1.latitude, pos1.longitude, pos2.latitude, pos2.longitude,
                result
            )
            distance += result[0]
        }
        return distance
    }

    fun calculateLengthofPolylines(polylines: MutableList<Polyline>): Float {
        var distance = 0f
        for (line in polylines) {
            distance += calculatePolylineLength(line)
        }
        return distance
    }

    fun getLatLngBounds(file: File): LatLngBounds {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.query("metadata", arrayOf("name", "value"), "name=?", arrayOf("bounds"), null, null, null)
        val hasRow = cursor.moveToFirst()
        val boundsStr = if (hasRow) cursor.getString(1) else null
        cursor.close(); db.close()
        if (boundsStr.isNullOrBlank()) {
            return LatLngBounds.Builder().include(LatLng(-85.0511, -180.0)).include(LatLng(85.0511, 180.0)).build()
        }
        val parts = boundsStr.split(","); if (parts.size != 4) {
            return LatLngBounds.Builder().include(LatLng(-85.0511, -180.0)).include(LatLng(85.0511, 180.0)).build()
        }
        val minLon = parts[0].toDoubleOrNull(); val minLat = parts[1].toDoubleOrNull()
        val maxLon = parts[2].toDoubleOrNull(); val maxLat = parts[3].toDoubleOrNull()
        return if (minLon == null || minLat == null || maxLon == null || maxLat == null) {
            LatLngBounds.Builder().include(LatLng(-85.0511, -180.0)).include(LatLng(85.0511, 180.0)).build()
        } else {
            LatLngBounds.Builder().include(LatLng(minLat, minLon)).include(LatLng(maxLat, maxLon)).build()
        }
    }

    fun getMinZoom(file: File): Int {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = db.query("metadata", arrayOf("name", "value"), "name=?", arrayOf("minzoom"), null, null, null)
        val value = if (cursor.moveToFirst()) cursor.getString(1) else null
        cursor.close(); db.close(); return value?.toIntOrNull() ?: 0
    }

    fun getMaxZoom(file: File): Int {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.query("metadata", arrayOf("name", "value"), "name=?", arrayOf("maxzoom"), null, null, null).use { cursor ->
                if (cursor.moveToFirst()) { cursor.getString(1)?.toIntOrNull()?.let { return it } }
            }
            val tables = mutableSetOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c -> while (c.moveToNext()) tables.add(c.getString(0)) }
            if ("tiles" in tables) {
                db.rawQuery("SELECT MAX(zoom_level) FROM tiles", null).use { c -> if (c.moveToFirst()) { val v = c.getInt(0); if (v > 0) return v } }
                db.rawQuery("SELECT MAX(zoom) FROM tiles", null).use { c -> if (c.moveToFirst()) { val v = c.getInt(0); if (v > 0) return v } }
            }
            if ("tiles_shallow" in tables) {
                db.rawQuery("SELECT MAX(zoom_level) FROM tiles_shallow", null).use { c -> if (c.moveToFirst()) { val v = c.getInt(0); if (v > 0) return v } }
            }
            return 14
        } catch (e: Exception) { Log.w("MBTiles", "getMaxZoom fallback: ${e.message}"); return 14 }
        finally { db.close() }
    }
}
