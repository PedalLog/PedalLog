package io.github.pedallog.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.maplibre.android.MapLibre
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import io.github.pedallog.R
import io.github.pedallog.databinding.ActivityTrackingBinding
import io.github.pedallog.db.Journey
import io.github.pedallog.other.Constants.ACTION_PAUSE_SERVICE
import io.github.pedallog.other.Constants.ACTION_START_OR_RESUME_SERVICE
import io.github.pedallog.other.Constants.ACTION_STOP_SERVICE
import io.github.pedallog.other.MbtilesHttpServer
import io.github.pedallog.other.TrackingUtility
import io.github.pedallog.other.RouteSerialization
import io.github.pedallog.ui.MenuItemModel
import io.github.pedallog.services.TrackingService
import io.github.pedallog.ui.viewmodels.PedalLogViewModel
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import java.io.FileOutputStream
import java.util.*
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import io.github.pedallog.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

@AndroidEntryPoint
class TrackingActivity : AppCompatActivity() {

    lateinit var binding: ActivityTrackingBinding

    private var map: MapLibreMap? = null
    private var isTracking = false
    private var pathPoints = mutableListOf<io.github.pedallog.services.Polyline>()
    private var curTimeInSeconds = 0L
    private var distance = 0f
    private var avgSpeed = 0
    private var currentSpeed = 0f
    
    // Track user interaction with map
    private var userHasInteractedWithMap = false
    private var autoRecenterJob: Job? = null
    private val autoRecenterDelayMs = 5000L

    private val ROUTE_SOURCE_ID = "route-source-id"
    private val ROUTE_LAYER_ID = "route-layer-id"
    private val LOCATION_SOURCE_ID = "location-source-id"
    private val LOCATION_LAYER_ID = "location-layer-id"

    private var httpServer: MbtilesHttpServer? = null
    private val localPort = 8080

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private val continousLocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            super.onLocationResult(result)
            result.lastLocation?.let { location ->
                val latLng = org.maplibre.android.geometry.LatLng(location.latitude, location.longitude)
                updateLocationMarker(latLng)
                
                // Update current speed even when not tracking - show actual GPS speed
                if (!isTracking) {
                    currentSpeed = if (location.hasSpeed()) location.speed else 0f
                    val speedKmh = (currentSpeed * 3.6f).toInt()
                    binding.tvCurrentSpeed.text = "$speedKmh ${getString(R.string.unit_kmh)}"
                }
            }
        }
    }

    val viewModel: PedalLogViewModel by viewModels()

    private val locationSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            sendCommandToService(ACTION_START_OR_RESUME_SERVICE)
        } else {
            Toast.makeText(this, getString(R.string.location_services_required), Toast.LENGTH_SHORT).show()
        }
    }

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
        binding = ActivityTrackingBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)
        
        // Apply font and text size preferences
        applyFontAndTextSizePreferences()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val assetMbtiles = sharedPreferences.getString("asset_mbtiles", "none")
        val mapFile = sharedPreferences.getString("map_file", "default")

        if (assetMbtiles != null && assetMbtiles != "none") {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val mbtilesFile = copyAssetToCache(assetMbtiles, "map.mbtiles")
                    withContext(Dispatchers.Main) {
                        showMbTilesMapViaHttp(mbtilesFile)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@TrackingActivity, getString(R.string.failed_load_mbtiles), Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else if (mapFile == "satellite") {
            binding.mapView.getMapAsync {
                map = it
                it.setStyle(Style.getPredefinedStyle("satellite-streets")) { style ->
                    binding.progressBar.visibility = View.GONE
                    addRouteLayerToStyle(style)
                    addAllPolylines()
                    setupMapTouchListener()
                }
            }
        } else {
            val uriString = sharedPreferences.getString("mbtiles_file", null)
            if (uriString != null) {
                val uri = Uri.parse(uriString)
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val mbtilesFile = copyUriToCache(uri, "map.mbtiles")
                        withContext(Dispatchers.Main) {
                            showMbTilesMapViaHttp(mbtilesFile)
                            setupMapTouchListener()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@TrackingActivity, getString(R.string.failed_load_mbtiles), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            } else {
                val dialog = MaterialAlertDialogBuilder(this)
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
                
                // Keep system UI hidden when dialog is shown
                dialog.window?.let { dialogWindow ->
                    val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
                    val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
                    val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
                    
                    val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                    windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    
                    if (!actionBarVisible) {
                        windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
                    }
                    if (!navBarVisible) {
                        windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
                    }
                }
            }
        }

        subscribeToObservers()

        // Map init
        binding.mapView.onCreate(savedInstanceState)

        // Android 13 Back Gesture 대응
        onBackPressedDispatcher.addCallback(this) {
            showCancelTrackingDialog()
        }

        binding.btnStartService.setOnClickListener { toggleJourney() }

        binding.btnSaveJourney.setOnClickListener {
            // Stop tracking first if still running
            if (isTracking) {
                sendCommandToService(ACTION_PAUSE_SERVICE)
            }
            zoomToSeeWholeTrack()
            endJourneyAndSaveToDb()
        }

        binding.fabMyLocation.setOnClickListener {
            userHasInteractedWithMap = false
            cancelAutoRecenter()
            moveCameraToUserManual()
        }
        
        // Setup zoom controls
        binding.fabZoomIn.setOnClickListener {
            userHasInteractedWithMap = true
            startAutoRecenterTimer()
            map?.animateCamera(CameraUpdateFactory.zoomIn())
        }
        
        binding.fabZoomOut.setOnClickListener {
            userHasInteractedWithMap = true
            startAutoRecenterTimer()
            map?.animateCamera(CameraUpdateFactory.zoomOut())
        }
        
        // Setup sidebar button (always visible)
        binding.fabRidingSidebar.visibility = View.VISIBLE
        binding.fabRidingSidebar.setOnClickListener {
            showRidingSidebarMenu()
        }
        
        // Hide save button initially
        binding.btnSaveJourney.visibility = View.GONE
        
        // Long press on stats area to customize UI order
        binding.linearLayout2.setOnLongClickListener {
            showUICustomizationDialog()
            true
        }
        
        // Load and apply UI order preferences
        applyUIOrderPreferences()
    }

    private fun addRouteLayerToStyle(style: Style) {
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }
        if (style.getLayer(ROUTE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                    PropertyFactory.lineColor(ContextCompat.getColor(this, R.color.mainColor2)),
                    PropertyFactory.lineWidth(8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
                )
            )
        }
        
        // Add location marker source and layer
        if (style.getSource(LOCATION_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(LOCATION_SOURCE_ID))
        }
        if (style.getLayer(LOCATION_LAYER_ID) == null) {
            // Add marker icon to style
            val bitmap = ContextCompat.getDrawable(this, R.drawable.ic_location_on)?.let { drawable ->
                val bitmap = android.graphics.Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.setTint(ContextCompat.getColor(this, R.color.mainColor1))
                drawable.draw(canvas)
                bitmap
            }
            bitmap?.let {
                style.addImage("location-icon", it)
            }
            
            style.addLayer(
                SymbolLayer(LOCATION_LAYER_ID, LOCATION_SOURCE_ID).withProperties(
                    PropertyFactory.iconImage("location-icon"),
                    PropertyFactory.iconSize(1.5f),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            )
        }
    }

    private fun updateRouteSource() {
        val style = map?.style
        if (style != null && style.isFullyLoaded) {
            val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID)
            if (source != null) {
                try {
                    val lineStrings = pathPoints.filter { it.size > 1 }.map { polyline ->
                        LineString.fromLngLats(polyline.map { Point.fromLngLat(it.longitude, it.latitude) })
                    }
                    val features = lineStrings.map { Feature.fromGeometry(it) }
                    source.setGeoJson(FeatureCollection.fromFeatures(features))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun addLatestPolyline() {
        updateRouteSource()
    }

    private fun addAllPolylines() {
        updateRouteSource()
    }

    private fun moveCameraToUser() {
        // Only auto-move camera if user hasn't manually interacted with the map
        if (!userHasInteractedWithMap && pathPoints.isNotEmpty() && pathPoints.last().isNotEmpty()) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    pathPoints.last().last(),
                    15.0
                )
            )
        }
    }
    
    private fun setupMapTouchListener() {
        map?.addOnMapClickListener {
            userHasInteractedWithMap = true
            startAutoRecenterTimer()
            false
        }
        
        map?.addOnMoveListener(object : MapLibreMap.OnMoveListener {
            override fun onMoveBegin(detector: org.maplibre.android.gestures.MoveGestureDetector) {
                userHasInteractedWithMap = true
                startAutoRecenterTimer()
            }
            override fun onMove(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
            override fun onMoveEnd(detector: org.maplibre.android.gestures.MoveGestureDetector) {}
        })
        
        map?.addOnScaleListener(object : MapLibreMap.OnScaleListener {
            override fun onScaleBegin(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {
                userHasInteractedWithMap = true
                startAutoRecenterTimer()
            }
            override fun onScale(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
            override fun onScaleEnd(detector: org.maplibre.android.gestures.StandardScaleGestureDetector) {}
        })
    }
    
    private fun startAutoRecenterTimer() {
        cancelAutoRecenter()
        autoRecenterJob = lifecycleScope.launch {
            delay(autoRecenterDelayMs)
            // Reset zoom and recenter to current location
            userHasInteractedWithMap = false
            moveCameraToUserManual()
        }
    }
    
    private fun cancelAutoRecenter() {
        autoRecenterJob?.cancel()
        autoRecenterJob = null
    }

    private fun zoomToSeeWholeTrack() {
        // Check if there are any path points before zooming
        if (pathPoints.isEmpty() || pathPoints.all { it.isEmpty() }) {
            return
        }
        
        val bounds = LatLngBounds.Builder()
        var hasPoints = false
        for (polyline in pathPoints) {
            for (pos in polyline) {
                bounds.include(pos)
                hasPoints = true
            }
        }
        if (hasPoints) {
            try {
                map?.moveCamera(
                    CameraUpdateFactory.newLatLngBounds(bounds.build(), 100)
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun moveCameraToUserManual() {
        if (TrackingUtility.hasLocationPermissions(this)) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                location?.let {
                    val latLng = org.maplibre.android.geometry.LatLng(it.latitude, it.longitude)
                    map?.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15.0))
                    updateLocationMarker(latLng)
                }
            }
        }
    }
    
    private fun updateLocationMarker(location: org.maplibre.android.geometry.LatLng) {
        val style = map?.style
        if (style != null && style.isFullyLoaded) {
            val source = style.getSourceAs<GeoJsonSource>(LOCATION_SOURCE_ID)
            source?.setGeoJson(Point.fromLngLat(location.longitude, location.latitude))
        }
    }

    private fun updateUIPreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
        val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (!navBarVisible) {
            windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            windowInsetsController.show(WindowInsetsCompat.Type.navigationBars())
        }

        if (!actionBarVisible) {
            supportActionBar?.hide()
            windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            supportActionBar?.show()
            windowInsetsController.show(WindowInsetsCompat.Type.statusBars())
        }
    }

    private fun showMbTilesMapViaHttp(mbtilesFile: File) {
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
                            map?.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50))
                            binding.progressBar.visibility = View.GONE
                            addRouteLayerToStyle(style)
                            addAllPolylines()
                            setupMapTouchListener()
                            Toast.makeText(this@TrackingActivity, getString(R.string.mbtiles_loaded), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@TrackingActivity, getString(R.string.mbtiles_server_error), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyUriToCache(uri: Uri, outName: String): File {
        val outFile = File(cacheDir, outName)
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lastUri = sharedPrefs.getString("last_cached_mbtiles_uri", "")

        // Optimize: skip copying if the URI hasn't changed and the file exists
        if (outFile.exists() && outFile.length() > 0 && lastUri == uri.toString()) {
            return outFile
        }

        if (outFile.exists()) outFile.delete()
        contentResolver.openInputStream(uri)?.use { input -> 
            FileOutputStream(outFile).use { output -> 
                input.copyTo(output) 
            } 
        }
        
        sharedPrefs.edit().putString("last_cached_mbtiles_uri", uri.toString()).apply()
        
        if (!outFile.exists() || outFile.length() == 0L) throw Exception("Failed to copy MBTiles file to cache")
        return outFile
    }

    private fun copyAssetToCache(assetPath: String, outName: String): File {
        val outFile = File(cacheDir, outName)
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this)
        val lastAsset = sharedPrefs.getString("last_cached_asset_mbtiles", "")

        if (outFile.exists() && outFile.length() > 0 && lastAsset == assetPath) {
            return outFile
        }

        if (outFile.exists()) outFile.delete()
        assets.open(assetPath).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }

        sharedPrefs.edit().putString("last_cached_asset_mbtiles", assetPath).apply()
        
        if (!outFile.exists() || outFile.length() == 0L) throw Exception("Failed to copy Asset MBTiles file to cache")
        return outFile
    }

    private fun endJourneyAndSaveToDb() {
        // Stop tracking first if still running
        if (isTracking) {
            sendCommandToService(ACTION_PAUSE_SERVICE)
        }
        
        // Check if there's any tracking data
        if (pathPoints.isEmpty() || pathPoints.all { it.isEmpty() }) {
            // No data at all - just exit
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.no_data_title))
                .setMessage(getString(R.string.no_data_to_save))
                .setPositiveButton(getString(R.string.ok)) { _, _ ->
                    stopJourney()
                }
                .setCancelable(false)
                .show()
            return
        }
        
        // Show save/discard dialog
        showSaveJourneyDialog()
    }
    
    private fun showSaveJourneyDialog() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val minDistanceConfirm = sharedPreferences.getString("min_distance_confirm", "10")?.toFloatOrNull() ?: 10f
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.save_journey_title))
            .setMessage(getString(R.string.save_journey_message))
            .setPositiveButton(getString(R.string.save_button)) { _, _ ->
                saveJourneyToDb()
            }
            .setNegativeButton(getString(R.string.discard)) { _, _ ->
                // Check if distance exceeds minimum threshold
                if (distance >= minDistanceConfirm) {
                    showDiscardConfirmationDialog()
                } else {
                    stopJourney()
                }
            }
            .setNeutralButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(true)
            .create()
        
        dialog.show()
        
        // Keep system UI hidden when dialog is shown
        dialog.window?.let { dialogWindow ->
            val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
            val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
            
            val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (!actionBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (!navBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    
    private fun showDiscardConfirmationDialog() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.discard_confirm_title))
            .setMessage(getString(R.string.discard_confirm_message))
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(getString(R.string.discard)) { _, _ ->
                stopJourney()
            }
            .setNegativeButton(getString(R.string.cancel)) { d, _ ->
                d.dismiss()
            }
            .setCancelable(true)
            .create()
        
        dialog.show()
        
        // Keep system UI hidden when dialog is shown
        dialog.window?.let { dialogWindow ->
            val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
            val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
            
            val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (!actionBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (!navBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    
    private fun saveJourneyToDb() {
        map?.snapshot { bmp ->
            val curDate = Calendar.getInstance().timeInMillis

            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val quality = sharedPreferences.getString("journey_thumbnail_quality", "medium") ?: "medium"
            val preparedBmp = prepareJourneyThumbnail(bmp, quality)
            val routeJson = RouteSerialization.toJson(pathPoints)

            val journey = Journey(
                dateCreated = curDate,
                speed = avgSpeed.toLong(),
                distance = distance / 1000f,
                duration = curTimeInSeconds * 1000,
                img = preparedBmp,
                routeJson = routeJson
            )

            viewModel.insertJourney(journey)
            stopJourney()
            Toast.makeText(applicationContext, getString(R.string.journey_saved_successfully), Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun prepareJourneyThumbnail(bmp: android.graphics.Bitmap?, quality: String): android.graphics.Bitmap? {
        if (bmp == null) return null

        val maxSizePx = when (quality) {
            "high" -> 1536
            "low" -> 512
            else -> 1024
        }

        val safeBitmap = if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
            bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null
        } else {
            bmp
        }

        val width = safeBitmap.width
        val height = safeBitmap.height
        if (width <= 0 || height <= 0) return safeBitmap

        val maxDim = maxOf(width, height)
        if (maxDim <= maxSizePx) return safeBitmap

        val scale = maxSizePx.toFloat() / maxDim.toFloat()
        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return android.graphics.Bitmap.createScaledBitmap(safeBitmap, targetW, targetH, true)
    }

    private fun updateTracking(isTracking: Boolean) {
        this.isTracking = isTracking
        val label = if (isTracking) getString(R.string.stop_button) else getString(R.string.start_button)
        binding.btnStartService.text = ""
        binding.btnStartService.contentDescription = label
        binding.btnStartService.tooltipText = label
        binding.btnStartService.setIconResource(if (isTracking) R.drawable.ic_stop else R.drawable.ic_play_arrow)
    }

    private fun toggleJourney() {
        if (isTracking) {
            sendCommandToService(ACTION_PAUSE_SERVICE)
        } else {
            checkLocationSettingsAndStart()
        }
    }

    private fun checkLocationSettingsAndStart() {
        // Reduced interval for faster initial check
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)
            .setAlwaysShow(true)

        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {
            sendCommandToService(ACTION_START_OR_RESUME_SERVICE)
        }

        task.addOnFailureListener { exception ->
            if (exception is ResolvableApiException) {
                try {
                    val intentSenderRequest = IntentSenderRequest.Builder(exception.resolution.intentSender).build()
                    locationSettingsLauncher.launch(intentSenderRequest)
                } catch (sendEx: Exception) {
                    sendCommandToService(ACTION_START_OR_RESUME_SERVICE) // Try to start anyway if resolution fails
                }
            } else {
                sendCommandToService(ACTION_START_OR_RESUME_SERVICE) // Try to start anyway
            }
        }
    }

    private fun subscribeToObservers() {

        TrackingService.isTracking.observe(this, Observer {
            updateTracking(it)
        })

        TrackingService.pathPoints.observe(this, Observer {
            pathPoints = it
            addLatestPolyline()
            moveCameraToUser()
            
            distance = TrackingUtility.calculateLengthofPolylines(it)
            avgSpeed = if (curTimeInSeconds > 0) {
                ((distance / 1000f) / (curTimeInSeconds / 3600f)).toInt()
            } else 0
            
            // Format distance: show meters if less than 1km, otherwise km
            if (distance < 1000f) {
                binding.tvDistance.text = "${distance.toInt()} ${getString(R.string.unit_m)}"
            } else {
                binding.tvDistance.text = "${String.format("%.2f", distance / 1000f)} ${getString(R.string.unit_km)}"
            }
            binding.tvSpeed.text = "$avgSpeed ${getString(R.string.unit_kmh)}"
            
            // Update location marker
            if (it.isNotEmpty() && it.last().isNotEmpty()) {
                updateLocationMarker(it.last().last())
            }
        })

        TrackingService.currentSpeed.observe(this, Observer {
            currentSpeed = it
            // Display current speed in km/h - show actual GPS speed
            val speedKmh = (it * 3.6f).toInt()
            binding.tvCurrentSpeed.text = "$speedKmh ${getString(R.string.unit_kmh)}"
        })

        TrackingService.timeRunInMillis.observe(this, Observer {
            curTimeInSeconds = it / 1000
            binding.tvTime.text = TrackingUtility.getFormattedStopwatchTime(it)

            // Show save button only when there's tracking data
            binding.btnSaveJourney.visibility = if (it > 0) View.VISIBLE else View.GONE
        })
    }

    private fun showRidingSidebarMenu() {
        // Show different menu based on whether the journey has started.
        // (GPS/location updates can populate points even before the user starts tracking.)
        val hasStartedJourney = isTracking || curTimeInSeconds > 0L

        if (hasStartedJourney) {
            val items = listOf(
                MenuItemModel(R.drawable.ic_stop, getString(R.string.end_journey)),
                MenuItemModel(R.drawable.ic_save, getString(R.string.save_button)),
                MenuItemModel(R.drawable.ic_delete, getString(R.string.cancel_ride))
            )

            val adapter = object : ArrayAdapter<MenuItemModel>(this, R.layout.dialog_menu_item, items) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.dialog_menu_item, parent, false)
                    val item = getItem(position)!!

                    view.findViewById<ImageView>(R.id.ivIcon).setImageResource(item.iconRes)
                    view.findViewById<TextView>(R.id.tvTitle).text = item.title
                    return view
                }
            }
            
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.menu))
                .setAdapter(adapter) { _, which ->
                    when (which) {
                        0 -> { // End Journey
                            if (isTracking) {
                                sendCommandToService(ACTION_PAUSE_SERVICE)
                            }
                            zoomToSeeWholeTrack()
                            endJourneyAndSaveToDb()
                        }
                        1 -> { // Save
                            if (isTracking) {
                                sendCommandToService(ACTION_PAUSE_SERVICE)
                            }
                            zoomToSeeWholeTrack()
                            endJourneyAndSaveToDb()
                        }
                        2 -> { // Cancel
                            showCancelTrackingDialog()
                        }
                    }
                }
                .create()
            
            showDialogWithHiddenSystemUI(dialog)
        } else {
            val items = listOf(
                MenuItemModel(R.drawable.ic_arrow_back, getString(R.string.go_home))
            )

            val adapter = object : ArrayAdapter<MenuItemModel>(this, R.layout.dialog_menu_item, items) {
                override fun getView(position: Int, convertView: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                    val view = convertView ?: layoutInflater.inflate(R.layout.dialog_menu_item, parent, false)
                    val item = getItem(position)!!

                    view.findViewById<ImageView>(R.id.ivIcon).setImageResource(item.iconRes)
                    view.findViewById<TextView>(R.id.tvTitle).text = item.title
                    return view
                }
            }
            
            val dialog = MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.menu))
                .setAdapter(adapter) { _, which ->
                    when (which) {
                        0 -> { // Exit to home
                            finish()
                        }
                    }
                }
                .create()
            
            showDialogWithHiddenSystemUI(dialog)
        }
    }
    
    private fun showDialogWithHiddenSystemUI(dialog: androidx.appcompat.app.AlertDialog) {
        
        dialog.show()
        
        // Keep system UI hidden when dialog is shown
        dialog.window?.let { dialogWindow ->
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
            val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
            
            val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (!actionBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (!navBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    
    private fun showUICustomizationDialog() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val currentOrder = sharedPreferences.getString("ui_field_order", "0,1,2,3") ?: "0,1,2,3"
        val orderList = currentOrder.split(",").map { it.toInt() }.toMutableList()
        
        val fieldNames = arrayOf(
            getString(R.string.distance),
            getString(R.string.avg_speed),
            getString(R.string.current_speed),
            getString(R.string.time)
        )
        
        val displayItems = orderList.map { fieldNames[it] }.toTypedArray()
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.customize_ui))
            .setItems(displayItems) { _, which ->
                // Simple reordering: move selected item to front
                if (which > 0) {
                    val item = orderList.removeAt(which)
                    orderList.add(0, item)
                    
                    // Save preference
                    sharedPreferences.edit()
                        .putString("ui_field_order", orderList.joinToString(","))
                        .apply()
                    
                    applyUIOrderPreferences()
                    Toast.makeText(this, "UI order updated. Tap field to move it to front.", Toast.LENGTH_SHORT).show()
                    
                    // Show dialog again for further customization
                    showUICustomizationDialog()
                }
            }
            .setNegativeButton(getString(R.string.cancel)) { d, _ -> d.dismiss() }
            .create()
        
        dialog.show()
        
        // Keep system UI hidden when dialog is shown
        dialog.window?.let { dialogWindow ->
            val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
            val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
            
            val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (!actionBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (!navBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }
    
    private fun applyUIOrderPreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        val orderString = sharedPreferences.getString("ui_field_order", "0,1,2,3") ?: "0,1,2,3"
        val order = orderString.split(",").map { it.toInt() }
        
        // Reorder the views in the LinearLayout
        val parent = binding.linearLayout2
        val views = listOf(
            binding.layoutDistance,
            binding.layoutAvgSpeed,
            binding.layoutCurrentSpeed,
            binding.layoutTime
        )
        
        // Remove all views
        parent.removeAllViews()
        
        // Add views back in the specified order
        order.forEach { index ->
            if (index < views.size) {
                parent.addView(views[index])
            }
        }
    }
    
    private fun applyFontAndTextSizePreferences() {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        
        // Apply font family
        val fontFamily = sharedPreferences.getString("font_family", "system")
        val typeface = when (fontFamily) {
            "roboto" -> android.graphics.Typeface.DEFAULT
            "sans_serif" -> android.graphics.Typeface.SANS_SERIF
            "serif" -> android.graphics.Typeface.SERIF
            "monospace" -> android.graphics.Typeface.MONOSPACE
            else -> null
        }
        
        // Apply text size scale
        val textSizeScale = sharedPreferences.getString("text_size_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        
        // Apply UI scale
        val uiScale = sharedPreferences.getString("ui_scale", "1.0")?.toFloatOrNull() ?: 1.0f
        
        // Apply to root view
        applyStyleToViewGroup(binding.root as android.view.ViewGroup, typeface, textSizeScale, uiScale)
    }
    
    private fun applyStyleToViewGroup(viewGroup: android.view.ViewGroup, typeface: android.graphics.Typeface?, textSizeScale: Float, uiScale: Float) {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            
            if (child is android.widget.TextView) {
                typeface?.let { child.typeface = it }
                val currentSize = child.textSize
                child.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, currentSize * textSizeScale)
            }
            
            // Apply UI scale (but not to map view)
            if (child !is org.maplibre.android.maps.MapView) {
                child.scaleX = uiScale
                child.scaleY = uiScale
            }
            
            if (child is android.view.ViewGroup) {
                applyStyleToViewGroup(child, typeface, textSizeScale, uiScale)
            }
        }
    }

    private fun showCancelTrackingDialog() {
        // If tracking has started, show save/discard dialog
        if (pathPoints.isNotEmpty() && pathPoints.any { it.isNotEmpty() }) {
            showSaveJourneyDialog()
            return
        }
        
        // No data - just confirm exit
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.cancel_journey_title))
            .setMessage(getString(R.string.cancel_journey_message))
            .setIcon(R.drawable.ic_delete)
            .setPositiveButton(getString(R.string.yes)) { _, _ -> stopJourney() }
            .setNegativeButton(getString(R.string.no)) { d, _ -> d.cancel() }
            .create()
        
        dialog.show()
        
        // Keep system UI hidden when dialog is shown
        dialog.window?.let { dialogWindow ->
            val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
            val actionBarVisible = sharedPreferences.getBoolean("action_bar", true)
            val navBarVisible = sharedPreferences.getBoolean("nav_bar", true)
            
            val windowInsetsController = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
            windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            
            if (!actionBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.statusBars())
            }
            if (!navBarVisible) {
                windowInsetsController.hide(WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    private fun stopJourney() {
        sendCommandToService(ACTION_STOP_SERVICE)
        finish()
    }

    private fun sendCommandToService(action: String) =
        Intent(this, TrackingService::class.java).also {
            it.action = action
            startService(it)
        }

    override fun onResume() { 
        super.onResume()
        updateUIPreferences()
        binding.mapView.onResume()
        startContinuousLocationUpdates()
    }
    
    @SuppressLint("MissingPermission")
    private fun startContinuousLocationUpdates() {
        if (TrackingUtility.hasLocationPermissions(this)) {
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(2000L)
                .build()
            fusedLocationClient.requestLocationUpdates(
                request,
                continousLocationCallback,
                Looper.getMainLooper()
            )
        }
    }
    
    private fun stopContinuousLocationUpdates() {
        fusedLocationClient.removeLocationUpdates(continousLocationCallback)
    }
    
    override fun onStart() { super.onStart(); binding.mapView.onStart() }
    override fun onStop() { super.onStop(); binding.mapView.onStop() }
    override fun onPause() { 
        super.onPause()
        binding.mapView.onPause()
        stopContinuousLocationUpdates()
    }
    override fun onLowMemory() { super.onLowMemory(); binding.mapView.onLowMemory() }
    override fun onDestroy() {
        super.onDestroy()
        cancelAutoRecenter()
        httpServer?.stopServer()
        binding.mapView.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}
