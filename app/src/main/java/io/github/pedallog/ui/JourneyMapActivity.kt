package io.github.pedallog.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import io.github.pedallog.R
import io.github.pedallog.SettingsActivity
import io.github.pedallog.databinding.ActivityJourneyMapBinding
import io.github.pedallog.other.MbtilesHttpServer
import io.github.pedallog.other.RouteSerialization
import io.github.pedallog.other.TrackingUtility
import io.github.pedallog.ui.viewmodels.JourneyMapViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.android.WellKnownTileServer
import java.io.File
import java.io.FileOutputStream

@AndroidEntryPoint
class JourneyMapActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJourneyMapBinding
    private val viewModel: JourneyMapViewModel by viewModels()

    private var map: MapLibreMap? = null
    private var httpServer: MbtilesHttpServer? = null
    private val localPort = 8088

    private val ROUTE_SOURCE_ID = "journey-route-source"
    private val ROUTE_LAYER_ID = "journey-route-layer"

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val theme = sharedPreferences.getString("app_theme", "Indigo")
        when (theme) {
            "Teal" -> setTheme(R.style.Theme_PedalLog_Teal)
            "Sunset" -> setTheme(R.style.Theme_PedalLog_Sunset)
            "Purple" -> setTheme(R.style.Theme_PedalLog_Purple)
            "Blue" -> setTheme(R.style.Theme_PedalLog_Blue)
            "Green" -> setTheme(R.style.Theme_PedalLog_Green)
            "Red" -> setTheme(R.style.Theme_PedalLog_Red)
            else -> setTheme(R.style.Theme_PedalLog)
        }

        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
        WindowCompat.setDecorFitsSystemWindows(window, actionBarVisible)

        MapLibre.getInstance(this, "", WellKnownTileServer.MapLibre)

        binding = ActivityJourneyMapBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.journey_map_title)

        val journeyId = intent.getIntExtra(EXTRA_JOURNEY_ID, -1)
        if (journeyId < 0) {
            Toast.makeText(this, getString(R.string.no_data_to_save), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        viewModel.journey.observe(this, Observer { journey ->
            if (journey == null) {
                Toast.makeText(this, getString(R.string.no_data_title), Toast.LENGTH_SHORT).show()
                finish()
                return@Observer
            }

            val route = RouteSerialization.fromJson(journey.routeJson)
            if (route.isEmpty() || route.all { it.isEmpty() }) {
                Toast.makeText(this, getString(R.string.journey_map_unavailable), Toast.LENGTH_SHORT).show()
                finish()
                return@Observer
            }

            initMap(route)
        })

        viewModel.loadJourney(journeyId)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        httpServer?.stopServer()
        httpServer = null
    }

    private fun initMap(route: List<List<org.maplibre.android.geometry.LatLng>>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val assetMbtiles = sharedPreferences.getString("asset_mbtiles", "none")
        val mapFile = sharedPreferences.getString("map_file", "default")

        if (assetMbtiles != null && assetMbtiles != "none") {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val mbtilesFile = copyAssetToCache(assetMbtiles, "map.mbtiles")
                    withContext(Dispatchers.Main) {
                        showMbTilesMapViaHttp(mbtilesFile, route)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@JourneyMapActivity, getString(R.string.failed_load_mbtiles), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            return
        }

        if (mapFile == "satellite") {
            binding.mapView.getMapAsync {
                map = it
                it.setStyle(Style.getPredefinedStyle("satellite-streets")) { style ->
                    binding.progressBar.visibility = android.view.View.GONE
                    addRouteLayerToStyle(style)
                    updateRouteSource(route)
                    zoomToRoute(route)
                }
            }
            return
        }

        val uriString = sharedPreferences.getString("mbtiles_file", null)
        if (uriString != null) {
            val uri = Uri.parse(uriString)
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val mbtilesFile = copyUriToCache(uri, "map.mbtiles")
                    withContext(Dispatchers.Main) {
                        showMbTilesMapViaHttp(mbtilesFile, route)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@JourneyMapActivity, getString(R.string.failed_load_mbtiles), Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
            }
            return
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.map_file_required_title))
            .setMessage(getString(R.string.map_file_required_message))
            .setPositiveButton(getString(R.string.settings)) { _, _ ->
                startActivity(Intent(this, SettingsActivity::class.java))
                finish()
            }
            .setNegativeButton(getString(R.string.cancel)) { _, _ -> finish() }
            .setCancelable(false)
            .create()

        dialog.show()
    }

    private fun showMbTilesMapViaHttp(mbtilesFile: File, route: List<List<org.maplibre.android.geometry.LatLng>>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                httpServer?.stopServer()
                httpServer = MbtilesHttpServer(localPort, mbtilesFile).apply { startServer() }

                val styleJsonInputStream = assets.open("basic/basic.json")
                val dir = File(filesDir.absolutePath, "basic")
                if (!dir.exists()) dir.mkdirs()
                val styleFile = File(dir, "basic.json")
                styleJsonInputStream.use { input ->
                    FileOutputStream(styleFile).use { output -> input.copyTo(output) }
                }

                val bounds = TrackingUtility.getLatLngBounds(mbtilesFile)
                val minZoomLevel = TrackingUtility.getMinZoom(mbtilesFile).toDouble()
                val maxZoomLevel = TrackingUtility.getMaxZoom(mbtilesFile).toDouble()

                val newFileStr = styleFile.inputStream().bufferedReader().use { it.readText() }
                    .replace(
                        "\"url\": \"___FILE_URI___\"",
                        "\"tiles\": [\"http://127.0.0.1:$localPort/{z}/{x}/{y}.pbf\"], \"minzoom\": $minZoomLevel, \"maxzoom\": $maxZoomLevel"
                    )
                styleFile.writeText(newFileStr)

                withContext(Dispatchers.Main) {
                    binding.mapView.getMapAsync {
                        map = it
                        it.setStyle(Style.Builder().fromUri(Uri.fromFile(styleFile).toString())) { style ->
                            it.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50))
                            binding.progressBar.visibility = android.view.View.GONE
                            addRouteLayerToStyle(style)
                            updateRouteSource(route)
                            zoomToRoute(route)
                            Toast.makeText(this@JourneyMapActivity, getString(R.string.mbtiles_loaded), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@JourneyMapActivity, getString(R.string.mbtiles_server_error), Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun addRouteLayerToStyle(style: Style) {
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }
        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                PropertyFactory.lineColor(android.graphics.Color.RED),
                PropertyFactory.lineWidth(5f),
                PropertyFactory.lineOpacity(0.9f)
            )
            style.addLayer(layer)
        }
    }

    private fun updateRouteSource(route: List<List<org.maplibre.android.geometry.LatLng>>) {
        val style = map?.style ?: return
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return

        val lineStrings = route.filter { it.size > 1 }.map { polyline ->
            LineString.fromLngLats(polyline.map { Point.fromLngLat(it.longitude, it.latitude) })
        }

        val features = lineStrings.map { Feature.fromGeometry(it) }
        source.setGeoJson(FeatureCollection.fromFeatures(features))
    }

    private fun zoomToRoute(route: List<List<org.maplibre.android.geometry.LatLng>>) {
        val allPoints = route.flatten()
        if (allPoints.isEmpty()) return

        val builder = LatLngBounds.Builder()
        for (p in allPoints) builder.include(p)
        val bounds = builder.build()
        map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100))
    }

    private fun copyUriToCache(uri: Uri, outName: String): File {
        val outFile = File(cacheDir, outName)
        if (outFile.exists()) outFile.delete()
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        if (!outFile.exists() || outFile.length() == 0L) throw Exception("Failed to copy MBTiles file to cache")
        return outFile
    }

    private fun copyAssetToCache(assetPath: String, outName: String): File {
        val outFile = File(cacheDir, outName)
        if (outFile.exists()) outFile.delete()
        assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        if (!outFile.exists() || outFile.length() == 0L) throw Exception("Failed to copy Asset MBTiles file to cache")
        return outFile
    }

    companion object {
        const val EXTRA_JOURNEY_ID = "extra_journey_id"
    }
}
